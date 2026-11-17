package com.tongluosao.gallerysync.core;

import com.tongluosao.gallerysync.core.Logger;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A short-lived loopback HTTP relay for a verified remote original video.
 *
 * <p>The gallery's native player accepts a URL and follows ordinary HTTP Range requests. This
 * relay keeps WebDAV credentials inside the app, translates each player request into one isolated
 * {@link WebDavClient#streamRange(String, long, long)} call, and only listens on 127.0.0.1. It is
 * a playback transport; it never downloads, stores, or verifies a full original.
 */
public final class WebDavVideoSource implements AutoCloseable {
    private static final int BACKLOG = 8;
    private static final int WORKERS = 3;
    private static final int QUEUE_CAPACITY = 6;
    private static final int MAX_REQUEST_BYTES = 16 * 1024;
    private static final int MAX_LINE_BYTES = 4 * 1024;
    private static final int LOCAL_READ_TIMEOUT_MS = 15_000;
    private static final long IDLE_EXPIRY_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long WARMUP_BUDGET_MS = 8_000L;    private static final long MAX_LIFETIME_MS = TimeUnit.HOURS.toMillis(4);
    /** 渐进缓存读不到新字节时的复查间隔与「无增长」判死时限。 */
    private static final long PROGRESSIVE_POLL_MS = 50L;
    private static final long PROGRESSIVE_STALL_MS = 20_000L;
    /** 起点还没下到时等下载追上：累计 60 秒无增长才判死。 */
    private static final long PROGRESSIVE_WAIT_MS = 60_000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile WebDavClient sSharedClient;
    private static volatile String sSharedClientKey;

    private final Set<Socket> mLocalSockets = ConcurrentHashMap.newKeySet();
    private final Set<WebDavClient.RangeCall> mActiveCalls = ConcurrentHashMap.newKeySet();
    private final Set<WebDavClient.RangeResponse> mActiveRanges = ConcurrentHashMap.newKeySet();
    private volatile boolean mClosed = true;
    private boolean mStarted;
    private volatile long mOpenedAt;
    private volatile long mLastActivityAt;
    private volatile ServerSocket mServer;
    private volatile ThreadPoolExecutor mWorkers;
    private volatile Thread mAcceptThread;
    private volatile WebDavClient mClient;
    private volatile String mRemotePath;
    private volatile String mToken;
    private volatile String mMimeType;
    private volatile long mOriginalLength;
    private volatile String mPlaybackUrl;
    /** 下载中的 {@code .part}；为空时行为与纯中继完全一致。 */
    private volatile File mProgressiveFile;
    /** MP4 的 moov 索引常在尾部：会话建立时就并行拉尾部 4MB 到内存，尾部请求不必等下载。 */
    private static final long TAIL_BYTES = 4L * 1024L * 1024L;
    private static final long TAIL_WAIT_MS = 5_000L;
    private volatile byte[] mTail;
    private volatile boolean mTailLoading;
    /** 中继同时处理的连接数上限：播放器一次会开十几条探测连接。 */
    private static final long BUSY_WAIT_MS = 2_000L;
    private final java.util.concurrent.Semaphore mHandlers =
            new java.util.concurrent.Semaphore(4);
    /** 上游请求计数：证明探测/重连不会额外增加上游负担。 */
    private final java.util.concurrent.atomic.AtomicInteger mUpstreamCalls =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger mUpstreamWarmup =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger mUpstreamTail =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger mUpstreamRelay =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 播放器主动断开的连接数（探测/切档），只做汇总，不记失败。 */
    private final java.util.concurrent.atomic.AtomicInteger mClientClosedCount =
            new java.util.concurrent.atomic.AtomicInteger();

    private void noteUpstreamCall(java.util.concurrent.atomic.AtomicInteger slot) {
        slot.incrementAndGet();
        mUpstreamCalls.incrementAndGet();
    }
    /** 播放器已经请求到的最大字节偏移（单调递增），用来限制下载领先量。 */
    private final java.util.concurrent.atomic.AtomicLong mPlayheadBytes =
            new java.util.concurrent.atomic.AtomicLong();

    public WebDavVideoSource() { }

    /** 播放位置（播放器请求过的最大字节偏移）。 */
    public long playheadBytes() {
        return mPlayheadBytes.get();
    }

    /** 该视频的期望总长度，未初始化时返回 0。 */
    public long expectedLength() {
        return mOriginalLength;
    }

    /**
     * 让中继优先读本地渐进缓存：命中已下到的区间就直接从 {@code partFile} 读，读不到再转发上游。
     * 不调用（或传 null）时行为与原来的纯中继一致。
     */
    public void setProgressiveCache(File partFile, long expectedLength) {
        mProgressiveFile = partFile;
        if (partFile != null && expectedLength > 0L) {
            mOriginalLength = expectedLength;
        }
    }

    /**
     * Reuse one client per target: a fresh client per playback means a fresh TLS handshake, and
     * that delay is charged to the player's very first byte.
     */
    private static WebDavClient sharedClient(Config config) {
        String key = config.getBaseUrl() + "\n" + config.getUsername();
        WebDavClient client = sSharedClient;
        if (client != null && key.equals(sSharedClientKey)) {
            return client;
        }
        synchronized (WebDavVideoSource.class) {
            if (sSharedClient != null && key.equals(sSharedClientKey)) {
                return sSharedClient;
            }
            WebDavClient created = new WebDavClient(config.getBaseUrl(), config.getUsername(),
                    config.getPassword());
            sSharedClientKey = key;
            sSharedClient = created;
            return created;
        }
    }

    WebDavVideoSource(WebDavClient client, String remotePath, long originalLength) {
        mClient = client;
        mRemotePath = remotePath;
        mOriginalLength = originalLength;
        mMimeType = mimeTypeFor(remotePath == null ? "" : remotePath);
    }

    /**
     * Start the relay for a registered original and return its local-only playback URL.
     * Call {@link #close()} when Gallery replaces or leaves the video.
     */
    public synchronized String open(Config config, OriginalStore.Record record)
            throws IOException {
        if (mStarted) {
            throw new IOException("video source instances are single-use");
        }
        close();
        if (config == null || !config.isReady() || record == null
                || record.remotePath == null || record.remotePath.isEmpty()
                || record.originalLength <= 0L) {
            throw new IOException("verified video record is unavailable");
        }
        if (!OriginalStore.targetKey(config).equals(record.target)) {
            throw new IOException("video record belongs to a different WebDAV target");
        }

        WebDavClient client = sharedClient(config);
        mClient = client;
        mRemotePath = record.remotePath;
        mOriginalLength = record.originalLength;
        mMimeType = mimeTypeFor(record.remotePath);
        mTail = null;
        mTailLoading = false;
        if (mProgressiveFile != null) prefetchTail();
        String url = start();
        warmUp();
        return url;
    }

    /**
     * 并行拉尾部 4MB（moov 索引）进内存：播放器一上来就请求尾部区间时不必等顺序下载走到片尾。
     * 失败只记日志，回退到「等 .part 下载追上」。
     */
    private void prefetchTail() {
        final WebDavClient client = mClient;
        final String remotePath = mRemotePath;
        final long length = mOriginalLength;
        if (client == null || remotePath == null || remotePath.isEmpty() || length <= 0L) return;
        if (mTailLoading || mTail != null) return;
        final long start = Math.max(0L, length - TAIL_BYTES);
        final int size = (int) (length - start);
        if (size <= 0) return;
        mTailLoading = true;
        Thread thread = new Thread(() -> {
            try {
                noteUpstreamCall(mUpstreamTail);
                WebDavClient.RangeResponse response = client.streamRange(remotePath, start,
                        length - 1L);
                try {
                    byte[] buffer = new byte[size];
                    int filled = 0;
                    InputStream body = response.body();
                    while (filled < size) {
                        int read = body.read(buffer, filled, size - filled);
                        if (read < 0) break;
                        filled += read;
                    }
                    if (filled < size) {
                        Logger.w("event=video_relay_tail_short bytes=" + filled + "/" + size);
                        return;
                    }
                    mTail = buffer;
                    Logger.i("event=video_relay_tail_ready bytes=" + filled + " start=" + start);
                } finally {
                    response.close();
                }
            } catch (Throwable t) {
                Logger.w("event=video_relay_tail_failed", t);
            } finally {
                mTailLoading = false;
            }
        }, "gallery-webdav-video-tail");
        thread.setDaemon(true);
        try {
            thread.start();
        } catch (Throwable t) {
            mTailLoading = false;
            Logger.w("event=video_relay_tail_failed", t);
        }
    }

    /**
     * Fetch one byte before handing the URL to the player. A cold handshake plus the server's 302
     * redirect can outlast the player's first-frame timeout, so pay it here and fail fast instead.
     */
    private void warmUp() throws IOException {
        WebDavClient client = mClient;
        String remotePath = mRemotePath;
        if (client == null || remotePath == null || remotePath.isEmpty()) {
            return;
        }
        try {
            File part = mProgressiveFile;
            if (part != null) {
                // 渐进缓存接管时首字节由本地文件提供，不必再等一轮上游往返。
                Logger.i("event=video_relay_warmup_skipped reason=progressive_cache");
                return;
            }
        } catch (Throwable ignored) {
            // 读不到本地状态就当没有渐进缓存
        }
        long startedAt = System.currentTimeMillis();
        noteUpstreamCall(mUpstreamWarmup);
        WebDavClient.RangeCall call = client.newRangeCall(remotePath, 0L, 0L);
        try (WebDavClient.RangeResponse response = call.open()) {
            if (response.body().read() < 0) {
                throw new IOException("remote video is empty");
            }
        } finally {
            call.close();
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        Logger.i("event=video_relay_warmed elapsed=" + elapsed + " path=" + remotePath);
        if (elapsed > WARMUP_BUDGET_MS) {
            // The player gives up quickly when the first byte stalls; a slow upstream is better
            // served by the full-restore path, which shows the gallery's own download state.
            throw new IOException("video upstream is too slow: " + elapsed + "ms");
        }
    }

    synchronized String start() throws IOException {
        if (mStarted) {
            throw new IOException("video source instances are single-use");
        }
        if (mClient == null || mRemotePath == null || mRemotePath.isEmpty()
                || mOriginalLength <= 0L) {
            throw new IOException("video source is not configured");
        }
        ServerSocket server = new ServerSocket();
        try {
            server.setReuseAddress(false);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), BACKLOG);
            server.setSoTimeout(5_000);

            mToken = randomToken();
            mOpenedAt = System.currentTimeMillis();
            mLastActivityAt = mOpenedAt;
            mClosed = false;
            mServer = server;
            mWorkers = new ThreadPoolExecutor(WORKERS, WORKERS, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(QUEUE_CAPACITY), relayThreadFactory(),
                    (task, executor) -> {
                        if (task instanceof RelayTask) {
                            ((RelayTask) task).reject();
                        }
                    });
            mPlaybackUrl = "http://127.0.0.1:" + server.getLocalPort() + "/"
                    + mToken + "/" + safeVideoName(mRemotePath);
            mStarted = true;
            Thread accept = new Thread(this::acceptLoop, "gallery-webdav-video-accept");
            accept.setDaemon(true);
            mAcceptThread = accept;
            accept.start();
            return mPlaybackUrl;
        } catch (IOException | RuntimeException e) {
            try {
                server.close();
            } catch (IOException ignored) { }
            WebDavClient client = mClient;
            if (client != null) client.close();
            mClosed = true;
            throw e;
        }
    }

    public String getPlaybackUrl() {
        return mPlaybackUrl;
    }

    private void acceptLoop() {
        while (!mClosed) {
            if (expired()) {
                close();
                return;
            }
            ServerSocket server = mServer;
            if (server == null || server.isClosed()) {
                return;
            }
            try {
                Socket socket = server.accept();
                if (!socket.getInetAddress().isLoopbackAddress() || mClosed) {
                    closeQuietly(socket);
                    continue;
                }
                socket.setSoTimeout(LOCAL_READ_TIMEOUT_MS);
                mLocalSockets.add(socket);
                touch();
                ThreadPoolExecutor workers = mWorkers;
                if (workers == null) {
                    closeQuietly(socket);
                    mLocalSockets.remove(socket);
                } else {
                    workers.execute(new RelayTask(socket));
                }
            } catch (java.net.SocketTimeoutException ignored) {
                // The timeout lets this thread reap idle relay instances.
            } catch (IOException e) {
                if (!mClosed) {
                    close();
                }
                return;
            }
        }
    }

    private boolean expired() {
        long now = System.currentTimeMillis();
        return now - mLastActivityAt >= IDLE_EXPIRY_MS
                || now - mOpenedAt >= MAX_LIFETIME_MS;
    }

    private void touch() {
        mLastActivityAt = System.currentTimeMillis();
    }

    private final class RelayTask implements Runnable {
        private final Socket socket;

        RelayTask(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            boolean acquired = false;
            try {
                acquired = acquireHandler(socket);
                if (!acquired) return;
                relay(socket);
            } finally {
                if (acquired) mHandlers.release();
                mLocalSockets.remove(socket);
                closeQuietly(socket);
            }
        }

        void reject() {
            mLocalSockets.remove(socket);
            closeQuietly(socket);
        }
    }

    /**
     * 单会话最多 4 条中继连接，拿不到信号量等 2 秒：超时直接 503。播放器探测/重连只会撞上
     * 这个闸门，不会把上游连接数顶上去。
     */
    private boolean acquireHandler(Socket socket) {
        boolean acquired;
        try {
            acquired = mHandlers.tryAcquire(BUSY_WAIT_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (acquired) return true;
        Logger.w("event=video_relay_busy");
        try {
            OutputStream output = new BufferedOutputStream(socket.getOutputStream(), 8 * 1024);
            writeError(output, 503, "Service Unavailable", null);
        } catch (Throwable ignored) {
            // 客户端可能已经走了
        }
        return false;
    }

    private void relay(Socket socket) {
        boolean responseStarted = false;
        long relayRangeStart = -1L;
        long relayRangeBytes = 0L;
        try {
            InputStream input = new BufferedInputStream(socket.getInputStream(), 8 * 1024);
            OutputStream output = new BufferedOutputStream(socket.getOutputStream(), 32 * 1024);
            Request request = readRequest(input);
            if (request == null) {
                writeError(output, 400, "Bad Request", null);
                return;
            }
            touch();
            Logger.d("event=video_relay_request method=" + request.method
                    + " range=" + request.range + " length=" + mOriginalLength);
            if (!"GET".equals(request.method) && !"HEAD".equals(request.method)) {
                writeError(output, 405, "Method Not Allowed", "Allow: GET, HEAD\r\n");
                return;
            }
            if (!authorizedPath(request.target)) {
                writeError(output, 404, "Not Found", null);
                return;
            }

            RangeSpec range = parseRange(request.range, mOriginalLength);
            if (range == null) {
                writeError(output, 416, "Range Not Satisfiable",
                        "Content-Range: bytes */" + mOriginalLength + "\r\n");
                return;
            }
            relayRangeStart = range.start;
            relayRangeBytes = range.end - range.start + 1L;
            boolean partial = request.range != null;
            long tailStart = VideoStreamPolicy.tailStart(mOriginalLength, (int) TAIL_BYTES);
            if (tailStart <= 0L || range.start < tailStart) {
                // 尾部索引请求不算播放进度，否则下载端会误以为已经播到片尾
                mPlayheadBytes.accumulateAndGet(range.start, Math::max);
            }
            long expectedLength = range.end - range.start + 1L;
            String responseType = mMimeType;
            if ("HEAD".equals(request.method)) {
                writeHeaders(output, partial ? 206 : 200, partial ? "Partial Content" : "OK",
                        expectedLength, responseType,
                        partial ? "Content-Range: bytes " + range.start + "-" + range.end
                                + "/" + mOriginalLength + "\r\n" : null);
                responseStarted = true;
                return;
            }

            // 渐进缓存已启用就只用本地文件服务：起点还没下到就让读线程等下载追上来，
            // 绝不再转发上游 Range（上游在这里会被 ConnectionReset，播放器只会在重试里打转）。
            File progressive = progressiveFileFor();
            if (progressive != null) {
                if (VideoStreamPolicy.coveredByTail(range.start, mOriginalLength,
                        (int) TAIL_BYTES)) {
                    // 请求起点已经在尾部索引区：直接内存切片，必要时最多等 5 秒。
                    byte[] tail = awaitTail(TAIL_WAIT_MS);
                    if (tail != null) {
                        long tailOffset = mOriginalLength - tail.length;
                        int from = (int) Math.max(0L, range.start - tailOffset);
                        int to = (int) Math.min(tail.length - 1L, range.end - tailOffset);
                        if (from >= 0 && to >= from && range.start >= tailOffset) {
                            writeHeaders(output, partial ? 206 : 200,
                                    partial ? "Partial Content" : "OK", expectedLength,
                                    responseType,
                                    partial ? "Content-Range: bytes " + range.start + "-"
                                            + range.end + "/" + mOriginalLength + "\r\n" : null);
                            responseStarted = true;
                            output.write(tail, from, to - from + 1);
                            output.flush();
                            touch();
                            Logger.d("event=video_relay_tail_served start=" + range.start
                                    + " bytes=" + (to - from + 1));
                            return;
                        }
                    }
                }
                writeHeaders(output, partial ? 206 : 200,
                        partial ? "Partial Content" : "OK", expectedLength, responseType,
                        partial ? "Content-Range: bytes " + range.start + "-" + range.end
                                + "/" + mOriginalLength + "\r\n" : null);
                responseStarted = true;
                streamProgressiveFile(progressive, output, range);
                return;
            }

            noteUpstreamCall(mUpstreamRelay);
            WebDavClient.RangeCall rangeCall = mClient.newRangeCall(
                    mRemotePath, range.start, range.end);
            mActiveCalls.add(rangeCall);
            if (mClosed) {
                rangeCall.close();
                mActiveCalls.remove(rangeCall);
                return;
            }
            WebDavClient.RangeResponse upstream;
            try {
                upstream = rangeCall.open();
            } finally {
                mActiveCalls.remove(rangeCall);
            }
            mActiveRanges.add(upstream);
            if (mClosed) {
                upstream.close();
                mActiveRanges.remove(upstream);
                return;
            }
            try (WebDavClient.RangeResponse rangeResponse = upstream) {
                if (rangeResponse.contentType != null && !rangeResponse.contentType.isEmpty()) {
                    responseType = safeContentType(rangeResponse.contentType, mMimeType);
                }
                boolean shouldBePartial = partial;
                long bodyLength = upstream.contentLength >= 0L
                        ? upstream.contentLength : expectedLength;
                if (upstream.statusCode != 206 || upstream.rangeStart != range.start
                        || upstream.rangeEnd != range.end
                        || upstream.resourceLength != mOriginalLength
                        || bodyLength != expectedLength
                        || (!partial && bodyLength != mOriginalLength)) {
                    Logger.w("event=video_relay_range_mismatch status=" + upstream.statusCode
                            + " want=" + range.start + "-" + range.end
                            + " got=" + upstream.rangeStart + "-" + upstream.rangeEnd
                            + " remoteLength=" + upstream.resourceLength
                            + " expectedLength=" + mOriginalLength);
                    throw new IOException("remote server did not return the requested range");
                }
                String contentRange = upstream.contentRange;
                if (partial && contentRange == null) {
                    contentRange = "bytes " + range.start + "-" + range.end
                            + "/" + mOriginalLength;
                }
                writeHeaders(output, shouldBePartial ? 206 : 200,
                        shouldBePartial ? "Partial Content" : "OK", bodyLength, responseType,
                        shouldBePartial && contentRange != null
                                ? "Content-Range: " + contentRange + "\r\n" : null);
                responseStarted = true;

                byte[] buffer = new byte[64 * 1024];
                long remaining = bodyLength;
                while (remaining > 0L) {
                    int read = upstream.body().read(buffer, 0,
                            (int) Math.min(buffer.length, remaining));
                    if (read < 0) {
                        throw new EOFException("remote video range ended early");
                    }
                    output.write(buffer, 0, read);
                    remaining -= read;
                    touch();
                }
                output.flush();
            } finally {
                mActiveRanges.remove(upstream);
            }
        } catch (IOException | RuntimeException e) {
            if (responseStarted && isClientClosed(e)) {
                // 播放器开一条探测连接、读几 KB 就自己断开，属于正常行为，不记失败也不写 502。
                int closed = mClientClosedCount.incrementAndGet();
                Logger.d("event=video_relay_closed_by_client range=" + relayRangeStart
                        + " bytes=" + relayRangeBytes);
                if (closed % 20 == 0) {
                    Logger.i("event=video_relay_client_closed_summary count=" + closed);
                }
                return;
            }
            Logger.w("event=video_relay_failed responseStarted=" + responseStarted
                    + " kind=" + e.getClass().getSimpleName() + " message=" + e.getMessage());
            // Request URLs, credentials, and signed redirect locations must never enter logs.
            try {
                if (!responseStarted && !socket.isClosed() && !socket.isOutputShutdown()) {
                    OutputStream output = new BufferedOutputStream(socket.getOutputStream());
                    writeError(output, 502, "Bad Gateway", null);
                }
            } catch (Throwable ignored) { }
        }
    }

    /** 客户端主动关连接（探测/切档）的判定：不谈“失败”，只做汇总。 */
    private static boolean isClientClosed(Throwable t) {
        if (t == null) return false;
        if (t instanceof java.net.SocketException) return true;
        String name = t.getClass().getSimpleName();
        if (name.contains("ConnectionReset") || name.contains("BrokenPipe")) return true;
        String message = t.getMessage();
        if (message == null) return false;
        return message.contains("Connection reset") || message.contains("Broken pipe")
                || message.contains("Socket closed") || message.contains("EPIPE");
    }

    /**
     * 渐进会话里要服务的文件：{@code .part} 已改名成正式缓存就整份直读，否则就是 {@code .part}
     * （可能还没下到请求起点，由 {@link #streamProgressiveFile} 等下载追上来）。未启用时返回 null。
     */
    private File progressiveFileFor() {
        File part = mProgressiveFile;
        if (part == null) return null;
        try {
            File completed = completedFileFor(part);
            if (completed != null && completed.isFile()
                    && (mOriginalLength <= 0L || completed.length() == mOriginalLength)) {
                return completed;
            }
        } catch (Throwable ignored) {
            // 读不到就继续用 .part
        }
        return part;
    }

    /** 等尾部缓存就绪（最多 timeoutMs）；加载已结束仍为空就立刻返回 null。 */
    private byte[] awaitTail(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            byte[] tail = mTail;
            if (tail != null) return tail;
            if (mClosed || !mTailLoading) return null;
            if (System.currentTimeMillis() >= deadline) {
                Logger.w("event=video_relay_tail_wait_timeout");
                return null;
            }
            sleepForGrowth();
        }
    }

    /**
     * 打开渐进文件再边下边播。文件还没出现（下载线程正在重建目录）或还没下到请求起点时，
     * 每 50ms 复查一次长度，累计 60 秒没有任何增长才抛 {@code progressive wait timeout}。
     */
    private void streamProgressiveFile(File file, OutputStream output, RangeSpec range)
            throws IOException {
        RandomAccessFile local = null;
        long lastLength = -1L;
        long lastGrowthAt = System.currentTimeMillis();
        try {
            while (true) {
                if (mClosed) throw new IOException("progressive relay closed");
                if (local == null) {
                    File target = file;
                    if (!target.isFile()) {
                        File completed = completedFileFor(file);
                        if (completed != null && completed.isFile()
                                && (mOriginalLength <= 0L
                                        || completed.length() == mOriginalLength)) {
                            target = completed;
                        }
                    }
                    try {
                        local = new RandomAccessFile(target, "r");
                    } catch (Throwable t) {
                        local = null;
                    }
                }
                long length = -1L;
                if (local != null) {
                    try {
                        length = local.length();
                    } catch (Throwable t) {
                        length = -1L;
                    }
                }
                if (VideoStreamPolicy.canServeFromLocal(length, range.start)) break;
                long now = System.currentTimeMillis();
                if (length != lastLength) {
                    lastLength = length;
                    lastGrowthAt = now;
                } else if (now - lastGrowthAt > PROGRESSIVE_WAIT_MS) {
                    throw new IOException("progressive wait timeout");
                }
                sleepForGrowth();
            }
            streamProgressive(local, output, range);
        } finally {
            if (local != null) closeQuietly(local);
        }
    }

    private static File completedFileFor(File part) {
        String path = part.getPath();
        if (!path.endsWith(VideoPlayCache.PART_SUFFIX)) return null;
        return new File(path.substring(0, path.length() - VideoPlayCache.PART_SUFFIX.length()));
    }

    /**
     * 边下边播：从 {@code range.start} 顺序读本地文件写进 socket，读到当前文件末尾就每 50ms 复查一次
     * 长度；连续 20 秒没有任何增长（下载线程已死/上游断了）才放弃，让播放器走错误回退。
     */
    private void streamProgressive(RandomAccessFile file, OutputStream output, RangeSpec range)
            throws IOException {
        file.seek(range.start);
        byte[] buffer = new byte[64 * 1024];
        long position = range.start;
        long end = range.end;
        long knownLength = -1L;
        long lastGrowthAt = System.currentTimeMillis();
        while (position <= end) {
            if (mClosed) throw new IOException("progressive relay closed");
            long length = file.length();
            if (length <= position) {
                long now = System.currentTimeMillis();
                if (knownLength < 0L || length > knownLength) {
                    knownLength = length;
                    lastGrowthAt = now;
                } else if (now - lastGrowthAt > PROGRESSIVE_STALL_MS) {
                    throw new IOException("progressive cache stalled");
                }
                sleepForGrowth();
                continue;
            }
            int want = (int) Math.min(buffer.length, end - position + 1L);
            want = (int) Math.min(want, length - position);
            if (want <= 0) {
                sleepForGrowth();
                continue;
            }
            // 每次读前显式定位：下载线程截断重写 .part 时也不会把读指针带偏。
            file.seek(position);
            int read = file.read(buffer, 0, want);
            if (read <= 0) {
                sleepForGrowth();
                continue;
            }
            output.write(buffer, 0, read);
            position += read;
            // 真正喂给播放器的位置也要记：播放器只发一次开放式 Range 时靠这个推进播放位置。
            mPlayheadBytes.accumulateAndGet(position, Math::max);
            knownLength = file.length();
            lastGrowthAt = System.currentTimeMillis();
            touch();
        }
        output.flush();
        Logger.d("event=video_relay_progressive_done start=" + range.start + " end=" + end);
    }

    private static void sleepForGrowth() throws IOException {
        try {
            Thread.sleep(PROGRESSIVE_POLL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("progressive relay interrupted");
        }
    }

    private static void closeQuietly(RandomAccessFile file) {
        try {
            file.close();
        } catch (IOException ignored) { }
    }

    private boolean authorizedPath(String requestTarget) {
        String prefix = "/" + mToken + "/";
        if (requestTarget == null || !requestTarget.startsWith(prefix)) {
            return false;
        }
        String leaf = requestTarget.substring(prefix.length());
        return !leaf.isEmpty() && leaf.indexOf('/') < 0 && leaf.indexOf('?') < 0
                && leaf.indexOf('#') < 0 && leaf.equals(safeVideoName(mRemotePath));
    }

    private static Request readRequest(InputStream input) throws IOException {
        String requestLine = readLine(input, MAX_LINE_BYTES);
        if (requestLine == null || requestLine.isEmpty()) {
            return null;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
            return null;
        }
        String range = null;
        int total = requestLine.length() + 2;
        while (true) {
            String line = readLine(input, MAX_LINE_BYTES);
            if (line == null) {
                return null;
            }
            total += line.length() + 2;
            if (total > MAX_REQUEST_BYTES) {
                return null;
            }
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            if ("Range".equalsIgnoreCase(name)) {
                if (range != null) {
                    range = "invalid-duplicate-range";
                } else {
                    range = line.substring(colon + 1).trim();
                }
            }
        }
        return new Request(parts[0], parts[1], range);
    }

    private static String readLine(InputStream input, int maximumBytes) throws IOException {
        byte[] bytes = new byte[maximumBytes];
        int count = 0;
        while (count < maximumBytes) {
            int value = input.read();
            if (value < 0) {
                return count == 0 ? null : new String(bytes, 0, count, StandardCharsets.ISO_8859_1);
            }
            if (value == '\n') {
                int length = count > 0 && bytes[count - 1] == '\r' ? count - 1 : count;
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            bytes[count++] = (byte) value;
        }
        throw new IOException("local request line too long");
    }

    private static RangeSpec parseRange(String value, long totalLength) {
        if (value == null) {
            return new RangeSpec(0L, totalLength - 1L);
        }
        if (totalLength <= 0L || !value.regionMatches(true, 0, "bytes=", 0, 6)) {
            return null;
        }
        String spec = value.substring(6).trim();
        if (spec.indexOf(',') >= 0) {
            return null;
        }
        int dash = spec.indexOf('-');
        if (dash < 0 || dash != spec.lastIndexOf('-')) {
            return null;
        }
        String first = spec.substring(0, dash).trim();
        String last = spec.substring(dash + 1).trim();
        try {
            long start;
            long end;
            if (first.isEmpty()) {
                long suffix = Long.parseLong(last);
                if (suffix <= 0L) {
                    return null;
                }
                start = Math.max(0L, totalLength - suffix);
                end = totalLength - 1L;
            } else {
                start = Long.parseLong(first);
                end = last.isEmpty() ? totalLength - 1L : Long.parseLong(last);
                if (start < 0L || end < start || start >= totalLength) {
                    return null;
                }
                end = Math.min(end, totalLength - 1L);
            }
            return new RangeSpec(start, end);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void writeError(OutputStream output, int code, String reason, String extra)
            throws IOException {
        writeHeaders(output, code, reason, 0L, "text/plain; charset=utf-8", extra);
    }

    private static void writeHeaders(OutputStream output, int code, String reason,
            long contentLength, String contentType, String extra) throws IOException {
        StringBuilder headers = new StringBuilder(256)
                .append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
                .append("Connection: close\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Accept-Ranges: bytes\r\n")
                .append("Content-Length: ").append(Math.max(0L, contentLength)).append("\r\n")
                .append("Content-Type: ").append(safeContentType(contentType,
                        "application/octet-stream")).append("\r\n");
        if (extra != null) {
            headers.append(extra);
        }
        headers.append("\r\n");
        output.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
    }

    private static String safeContentType(String value, String fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x20 || ch > 0x7e || ch == '\r' || ch == '\n') {
                return fallback;
            }
        }
        return value;
    }

    private static String mimeTypeFor(String path) {
        String lower = path.toLowerCase(Locale.US);
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".m4v")) return "video/x-m4v";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".3gp")) return "video/3gpp";
        return "application/octet-stream";
    }

    private static String safeVideoName(String path) {
        int slash = path.lastIndexOf('/');
        String leaf = slash >= 0 ? path.substring(slash + 1) : path;
        StringBuilder safe = new StringBuilder(Math.min(leaf.length(), 80));
        for (int i = 0; i < leaf.length() && safe.length() < 80; i++) {
            char ch = leaf.charAt(i);
            safe.append((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-'
                    ? ch : '_');
        }
        return safe.length() == 0 ? "video.mp4" : safe.toString();
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ThreadFactory relayThreadFactory() {
        AtomicInteger number = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable,
                    "gallery-webdav-video-relay-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public synchronized void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        Logger.i("event=video_relay_upstream_summary calls=" + mUpstreamCalls.get()
                + " tail=" + mUpstreamTail.get() + " warmup=" + mUpstreamWarmup.get()
                + " relay=" + mUpstreamRelay.get());
        ServerSocket server = mServer;
        mServer = null;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) { }
        }
        ThreadPoolExecutor workers = mWorkers;
        mWorkers = null;
        if (workers != null) {
            workers.shutdownNow();
        }
        for (Socket socket : mLocalSockets) {
            closeQuietly(socket);
        }
        mLocalSockets.clear();
        for (WebDavClient.RangeCall call : mActiveCalls) {
            call.close();
        }
        mActiveCalls.clear();
        for (WebDavClient.RangeResponse range : mActiveRanges) {
            range.close();
        }
        mActiveRanges.clear();
        WebDavClient client = mClient;
        mClient = null;
        if (client != null) {
            client.close();
        }
        mPlaybackUrl = null;
        mToken = null;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) { }
    }

    private static final class Request {
        final String method;
        final String target;
        final String range;

        Request(String method, String target, String range) {
            this.method = method;
            this.target = target;
            this.range = range;
        }
    }

    private static final class RangeSpec {
        final long start;
        final long end;

        RangeSpec(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }
}
