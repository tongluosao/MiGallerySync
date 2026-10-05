package com.tongluosao.gallerysync.core;

import android.content.Context;
import android.os.StatFs;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * WebDAV **下载**队列：把远端有、本地没有的文件取回本地。
 *
 * <p>与 {@link UploadQueue} 对称但**独立**：自己的线程、自己的连接、自己的落盘键，
 * 复用同一个 {@code state.json} 与同一张进度页（两个队列各挂一个
 * {@link UploadProgressPublisher.QueueView}，计数与列表由发布器合起来）。
 * 不复用上传队列的线程是有意的：下载与上传抢同一条通道只会让两边都变慢。
 *
 * <p><b>只做"补齐"，不做"对齐"</b>：本地有同名文件就跳过，不比对内容、不覆盖、不删除。
 * 这是刻意的边界 —— 双向一致性（远端删了本地也删）需要一个持久化的同步基线，
 * 而相册里的文件是用户的东西，模块替它删错的代价无法挽回。所以这里只有"多了就取回来"。
 *
 * <p><b>省电省性能的取舍</b>：一条"计划线程"按间隔走一遍远端目录树（最多
 * {@link #MAX_DIRS_PER_ROUND} 个目录、每轮最多认下 {@link #MAX_DOWNLOADS_PER_ROUND} 个文件），
 * 两条 worker 线程只负责把认出队列里的文件下载下来。一轮什么也没找到就把间隔翻倍
 * （{@link #ROUND_BASE} 起、{@link #ROUND_MAX} 封顶），上传一有活动就重置回最短 ——
 * 用户在传照片时才是"在用这个 App"，那时补下载最不容易打扰；半夜没人用的时候退到几小时一轮，
 * 几乎不耗电。
 *
 * <p>运行在相册进程，全部 IO 在自有后台线程，绝不阻塞相册的同步线程。
 */
public final class DownloadQueue {

    private static final String STATE_DIR = "webdav_sync";
    private static final String STATE_FILE = "state.json";
    /**
     * 下载落到哪里。
     *
     * <p>必须是 DCIM 下的一个子目录：相册只收 DCIM / Pictures 这类公开媒体目录里的文件，
     * 落在 {@code Android/data} 或模块自己的目录下，扫描引擎会直接跳过 —— 那就成了
     * "下回来了但相册里看不见"。
     *
     * <p>单独一个 {@code MiGallerySync} 子目录而不是直接铺进 DCIM：远端树在这里原样重建，
     * 不致于把用户的 DCIM 根目录搅乱，用户要清理也有一个明确的边界。
     */
    private static final String DOWNLOAD_ROOT = "/storage/emulated/0/DCIM/MiGallerySync";

    /** 一轮最多走几个目录（含子目录），防止一次枚举把整棵树拉下来 */
    private static final int MAX_DIRS_PER_ROUND = 200;
    /** 一轮最多认下几个文件 */
    private static final int MAX_DOWNLOADS_PER_ROUND = 200;
    /** 目录下钻深度上限 */
    private static final int MAX_DEPTH = 4;
    /** 同一个远端路径连续失败几次就不再重试（下一轮还会遇到，只是不再计入本轮） */
    private static final int MAX_FAIL_PER_PATH = 3;
    /** 本地剩余空间的下限：低于它就整轮不下载，免得把手机写满 */
    private static final long MIN_FREE_BYTES = 512L * 1024 * 1024;
    /** 记住"哪些远端路径已经下过了"的上限，与上传的 done 表同一量级 */
    private static final int MAX_DONE_RECORDS = 20000;

    /** 轮次间隔：空手而归时从 30 分钟起每次翻倍，到 6 小时封顶 */
    private static final long ROUND_BASE = 30 * 60_000L;
    private static final long ROUND_MAX = 6 * 60 * 60_000L;
    /** 上一轮有收获时的下一轮间隔：还有得下就接着下，不必等半小时 */
    private static final long ROUND_ACTIVE = 2 * 60_000L;
    /** 刚启动时的第一轮延迟 —— 开机那几分钟先把带宽和电留给上传 */
    private static final long START_DELAY = 3 * 60_000L;
    /** 被唤醒（上传有活动）之后至少隔这么久才允许提前开始新一轮 */
    private static final long MIN_ROUND_GAP = 60_000L;

    /** 下载 worker 的路数：下载的瓶颈几乎总在远端带宽，两路足够叠起等待 */
    private static final int THREADS = 2;

    private static volatile DownloadQueue sInstance;

    private final Context mContext;
    private final Object mLock = new Object();

    /** 认出但还没下完的项。计划线程添、worker 线程取 */
    private final List<Pending> mPending = new ArrayList<>();
    /** 已经下过的远端路径 → 下完的时刻 */
    private final LinkedHashMap<String, Long> mDone = new LinkedHashMap<>();
    /** 失败次数，用来把反复失败的项排除掉 */
    private final LinkedHashMap<String, Integer> mFailures = new LinkedHashMap<>();
    /** 正在下载的远端路径，受 mLock 保护 */
    private final Set<String> mInflight = new HashSet<>();

    private volatile Config mConfig;
    private volatile boolean mRunning = true;
    private long mNextRoundAt;
    private long mRoundDelay = ROUND_BASE;
    private int mPendingVersion;

    private final Thread[] mWorkers = new Thread[THREADS];
    private Thread mPlanner;
    private final WebDavClient[] mClients = new WebDavClient[THREADS];
    private final String[] mClientTargets = new String[THREADS];

    /** 进度发布器（与上传共用一个），由 Hook 侧接线 */
    private volatile UploadProgressPublisher mPublisher;
    /** 下载完成之后把文件交给相册扫描；为 null 时只落盘不通知 */
    private volatile Ingest mIngest;

    /** 让相册把刚落地的文件收进媒体库 */
    public interface Ingest {
        void ingest(String absolutePath);
    }

    /** 待下载的一项 */
    private static final class Pending {
        final String remotePath;
        final String relativePath;
        final long size;

        Pending(String remotePath, String relativePath, long size) {
            this.remotePath = remotePath;
            this.relativePath = relativePath;
            this.size = size;
        }
    }

    private DownloadQueue(Context context, Config config) {
        mContext = context.getApplicationContext();
        mConfig = config;
        load();
        mNextRoundAt = System.currentTimeMillis() + START_DELAY;
        mPlanner = new Thread(this::plannerLoop, "MiGallerySync-download-plan");
        mPlanner.setDaemon(true);
        mPlanner.start();
        for (int i = 0; i < THREADS; i++) {
            Thread thread = new Thread(this::workerLoop, "MiGallerySync-download-" + i);
            thread.setDaemon(true);
            mWorkers[i] = thread;
            thread.start();
        }
    }

    public static DownloadQueue get(Context context, Config config) {
        if (sInstance == null) {
            synchronized (DownloadQueue.class) {
                if (sInstance == null) {
                    sInstance = new DownloadQueue(context, config);
                }
            }
        }
        return sInstance;
    }

    public void setConfig(Config config) {
        mConfig = config;
    }

    public void setIngest(Ingest ingest) {
        mIngest = ingest;
    }

    public void setPublisher(UploadProgressPublisher publisher) {
        mPublisher = publisher;
    }

    public void addQueueView(UploadProgressPublisher publisher) {
        if (publisher == null) {
            return;
        }
        mPublisher = publisher;
        publisher.addQueueView(new UploadProgressPublisher.QueueView() {
            @Override
            public int pendingCount() {
                synchronized (mLock) {
                    return Math.max(mPending.size() - mInflight.size(), 0);
                }
            }

            @Override
            public long pendingBytes() {
                synchronized (mLock) {
                    long total = 0;
                    for (Pending pending : mPending) {
                        if (mInflight.contains(pending.remotePath)) {
                            continue;
                        }
                        total += Math.max(pending.size, 0L);
                    }
                    return total;
                }
            }

            @Override
            public List<UploadProgressPublisher.PendingItem> pendingItems(int limit) {
                synchronized (mLock) {
                    List<UploadProgressPublisher.PendingItem> out = new ArrayList<>();
                    for (Pending pending : mPending) {
                        if (out.size() >= limit) {
                            break;
                        }
                        out.add(new UploadProgressPublisher.PendingItem(
                                key(pending), baseName(pending.relativePath),
                                fullPathOf(pending), Math.max(pending.size, 0L), false,
                                ProgressProtocol.DIR_DOWN));
                    }
                    return out;
                }
            }

            @Override
            public int pendingVersion() {
                return mPendingVersion;
            }

            @Override
            public boolean healthy() {
                // 下载不参与"服务器不可用"的告警：那个告警说的是"照片传不上去"，
                // 是用户真正在意的事；下载偶发失败不该把进度页染红
                return true;
            }
        });
    }

    /**
     * 上传队列见底时叫醒下载计划线程。
     *
     * <p>这是整条下载通路的"节拍器"：上传在动说明用户正在用，把间隔拉回最短；
     * 上传长时间不动，下载自己也按翻倍后的间隔慢慢来。
     */
    public void onUploadIdle() {
        synchronized (mLock) {
            if (mRoundDelay > ROUND_BASE) {
                mRoundDelay = ROUND_BASE;
            }
            long earliest = System.currentTimeMillis() + MIN_ROUND_GAP;
            if (mNextRoundAt > earliest) {
                mNextRoundAt = earliest;
            }
            mLock.notifyAll();
        }
    }

    /**
     * 停线程。热重载交接时旧 generation 必须调：否则新旧两套下载队列同时往
     * state.json 写各自的视图。
     */
    public void shutdown() {
        synchronized (DownloadQueue.class) {
            sInstance = null;
        }
        synchronized (mLock) {
            mRunning = false;
            mLock.notifyAll();
        }
        for (WebDavClient client : mClients) {
            if (client != null) {
                client.close();
            }
        }
    }

    // ------------------------------------------------------------------ 计划侧

    /**
     * 计划线程：按间隔走一遍远端树，把"本地还没有的文件"放进 {@link #mPending}。
     *
     * <p>只有它做 PROPFIND。worker 线程只下载 —— 两边共用一棵目录树的结果就是
     * 反复走同一条路径，那正是"省电省性能"要避免的。
     */
    private void plannerLoop() {
        while (mRunning) {
            Config config = mConfig;
            if (config == null || !config.isDownloadEnabled() || !config.isReady()) {
                waitLocked(60_000L);
                continue;
            }
            long wait;
            synchronized (mLock) {
                wait = mNextRoundAt - System.currentTimeMillis();
            }
            if (wait > 0L) {
                waitLocked(wait);
                continue;
            }
            if (!NetGuard.canUpload(mContext, config.isWifiOnly())) {
                Logger.d("event=download_skip reason=wifi_only");
                backOff(false);
                continue;
            }
            long free = freeBytes();
            if (free < MIN_FREE_BYTES) {
                Logger.w("event=download_skip reason=low_space free=" + free);
                backOff(false);
                continue;
            }
            int found;
            try {
                found = plan(config);
            } catch (Throwable t) {
                Logger.w("event=download_round_failed detail=" + t);
                backOff(false);
                continue;
            }
            backOff(found > 0);
        }
    }

    /**
     * 下一轮什么时候跑。
     *
     * @param productive 这一轮有没有认出新的文件。有就说明"还有得下"，隔 {@link #ROUND_ACTIVE}
     *                   再来一趟；没有就把间隔翻倍 —— 远端没有新东西时最不该做的就是
     *                   每半小时去走一遍整棵树
     */
    private void backOff(boolean productive) {
        synchronized (mLock) {
            if (productive) {
                if (mRoundDelay > ROUND_BASE) {
                    mRoundDelay = ROUND_BASE;
                }
                mNextRoundAt = System.currentTimeMillis() + ROUND_ACTIVE;
            } else {
                mRoundDelay = Math.min(mRoundDelay * 2, ROUND_MAX);
                mNextRoundAt = System.currentTimeMillis() + mRoundDelay;
            }
            mLock.notifyAll();
        }
    }

    /** @return 这一轮认出多少个新文件 */
    private int plan(Config config) {
        List<Pending> found = new ArrayList<>();
        WebDavClient client = clientFor(config, 0);
        walk(client, config.getRemoteRoot(), "", 0, found);
        if (found.isEmpty()) {
            Logger.d("event=download_round_empty next_in=" + mRoundDelay);
            return 0;
        }
        int added = 0;
        synchronized (mLock) {
            // 已经在队列里（上一轮还没下完）的不重复加入
            Set<String> queued = new HashSet<>();
            for (Pending pending : mPending) {
                queued.add(pending.remotePath);
            }
            for (Pending pending : found) {
                if (queued.contains(pending.remotePath)) {
                    continue;
                }
                mPending.add(pending);
                added++;
            }
            if (added > 0) {
                mPendingVersion++;
            }
            mLock.notifyAll();
        }
        Logger.i("event=download_round_planned found=" + found.size() + " added=" + added
                + " queued=" + mPending.size());
        return added;
    }

    /**
     * 走一遍远端目录树，挑出"本地还没有"的文件。
     *
     * <p>判断"本地有没有"用的是**文件名**（落到同一个目录里），与上传侧"远端有没有同名文件"
     * 是同一套判定 —— 两边都只看名字，都不比对内容。
     */
    private void walk(WebDavClient client, String remoteDir, String relativeDir, int depth,
            List<Pending> out) {
        if (depth > MAX_DEPTH || out.size() >= MAX_DOWNLOADS_PER_ROUND) {
            return;
        }
        List<WebDavClient.RemoteEntry> entries;
        try {
            entries = client.list(remoteDir);
        } catch (Throwable t) {
            Logger.d("event=download_list_failed dir=" + remoteDir + " reason=" + t);
            return;
        }
        if (entries == null) {
            return;
        }
        int dirs = 0;
        for (WebDavClient.RemoteEntry entry : entries) {
            if (out.size() >= MAX_DOWNLOADS_PER_ROUND || dirs >= MAX_DIRS_PER_ROUND) {
                return;
            }
            String name = entry.name;
            if (!isSafeSegment(name)) {
                Logger.d("event=download_bad_name remote=" + remoteDir + "/" + name);
                continue;
            }
            String relative = relativeDir.isEmpty() ? name : relativeDir + "/" + name;
            if (entry.dir) {
                dirs++;
                walk(client, WebDavClient.joinPath(remoteDir, name), relative, depth + 1, out);
                continue;
            }
            if (skipByExtension(name)) {
                continue;
            }
            String remotePath = WebDavClient.joinPath(remoteDir, name);
            synchronized (mLock) {
                if (mDone.containsKey(remotePath)) {
                    continue;
                }
            }
            if (new File(new File(DOWNLOAD_ROOT, relativeDir), name).exists()) {
                continue;
            }
            out.add(new Pending(remotePath, relative, entry.size));
        }
    }

    // ------------------------------------------------------------------ 下载侧

    private void workerLoop() {
        while (mRunning) {
            Config config = mConfig;
            Pending next = null;
            if (config != null && config.isDownloadEnabled() && config.isReady()) {
                synchronized (mLock) {
                    next = takeLocked();
                    if (next == null) {
                        try {
                            mLock.wait(5_000L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        continue;
                    }
                }
            } else {
                waitLocked(30_000L);
                continue;
            }
            download(next, config);
        }
    }

    /** 领取一项：从队首拿第一个没被别的 worker 领走、且没超过失败上限的 */
    private Pending takeLocked() {
        Iterator<Pending> it = mPending.iterator();
        while (it.hasNext()) {
            Pending pending = it.next();
            if (mInflight.contains(pending.remotePath)) {
                continue;
            }
            Integer failures = mFailures.get(pending.remotePath);
            if (failures != null && failures >= MAX_FAIL_PER_PATH) {
                it.remove();
                mPendingVersion++;
                Logger.w("event=download_give_up remote=" + pending.remotePath
                        + " attempts=" + failures);
                continue;
            }
            mInflight.add(pending.remotePath);
            return pending;
        }
        return null;
    }

    private void download(Pending pending, Config config) {
        int index = workerIndex();
        UploadProgressPublisher publisher = mPublisher;
        long size = Math.max(pending.size, 0L);
        File dest = new File(fullPathOf(pending));
        String key = key(pending);
        boolean ok = false;
        if (publisher != null) {
            publisher.taskStart(key, baseName(pending.relativePath), dest.getAbsolutePath(),
                    size, false, ProgressProtocol.DIR_DOWN);
        }
        try {
            if (dest.exists()) {
                // 本地已经有同名文件：一律不覆盖。用户可能自己改过它，也可能同名但是别的东西
                Logger.i("event=download_skip_local_exists remote=" + pending.remotePath
                        + " local=" + dest.getAbsolutePath());
                markDone(pending.remotePath);
                ok = true;
                return;
            }
            WebDavClient client = clientFor(config, index);
            client.setProgressListener((done, total) -> {
                // 每 64KB 一次，这里只把数字塞给进度发布器（它自己按 2Hz 节流后发帧）
                UploadProgressPublisher target = mPublisher;
                if (target != null) {
                    target.taskBytes(key, done);
                }
            });
            try {
                client.get(pending.remotePath, dest);
            } finally {
                client.setProgressListener(null);
            }
            Logger.i("event=download_ok remote=" + pending.remotePath
                    + " local=" + dest.getAbsolutePath() + " size=" + dest.length());
            markDone(pending.remotePath);
            Ingest ingest = mIngest;
            if (ingest != null) {
                ingest.ingest(dest.getAbsolutePath());
            }
            ok = true;
        } catch (WebDavClient.TransportFailure e) {
            fail(pending, e.getMessage());
        } catch (IOException e) {
            fail(pending, e.getMessage());
        } catch (Throwable t) {
            fail(pending, String.valueOf(t));
            Logger.w("event=download_error remote=" + pending.remotePath, t);
        } finally {
            if (publisher != null) {
                publisher.taskEnd(key, ok);
            }
            synchronized (mLock) {
                mInflight.remove(pending.remotePath);
                mLock.notifyAll();
            }
        }
    }

    private void fail(Pending pending, String reason) {
        int count;
        synchronized (mLock) {
            Integer previous = mFailures.get(pending.remotePath);
            count = (previous == null ? 0 : previous) + 1;
            mFailures.put(pending.remotePath, count);
        }
        Logger.w("event=download_retry remote=" + pending.remotePath + " attempts=" + count
                + " reason=" + reason);
    }

    private void markDone(String remotePath) {
        synchronized (mLock) {
            mDone.put(remotePath, System.currentTimeMillis());
            trimDoneLocked();
            saveLocked();
        }
    }

    private void trimDoneLocked() {
        if (mDone.size() <= MAX_DONE_RECORDS) {
            return;
        }
        Iterator<String> it = mDone.keySet().iterator();
        while (it.hasNext() && mDone.size() > MAX_DONE_RECORDS) {
            it.next();
            it.remove();
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 只在本地留"相册会认"的东西。
     *
     * <p>不按扩展名过滤的话，远端的 {@code .DS_Store}、{@code .nomedia}、缩略图缓存
     * 也会被拉回来。{@code .nomedia} 尤其危险：它一旦落在 DCIM 下，相册会把这个目录
     * 整个忽略掉，用户会以为备份丢了一半。
     */
    private static boolean skipByExtension(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.startsWith(".")) {
            return true;
        }
        return lower.endsWith(".tmp") || lower.endsWith(".part") || lower.endsWith(".log")
                || lower.endsWith(".nomedia") || lower.endsWith(".db")
                || lower.endsWith(".json") || lower.endsWith(".txt");
    }

    /**
     * 段名安全性：必须是单一段、不能是 {@code .} / {@code ..}、不能带路径分隔符。
     *
     * <p>远端目录里的内容是别处（甚至别的客户端）给的，一个叫 {@code ../..} 的目录名
     * 会让下载写到 DCIM 之外 —— 下载是唯一一条"远端内容决定本地路径"的通路，
     * 这里必须挡住。
     */
    private static boolean isSafeSegment(String name) {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return false;
        }
        return name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf('\0') < 0;
    }

    private static String fullPathOf(Pending pending) {
        return DOWNLOAD_ROOT + "/" + pending.relativePath;
    }

    private static String key(Pending pending) {
        return "down|" + pending.remotePath;
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 && slash + 1 < path.length() ? path.substring(slash + 1) : path;
    }

    private long freeBytes() {
        try {
            StatFs stat = new StatFs(DOWNLOAD_ROOT);
            return stat.getAvailableBytes();
        } catch (Throwable t) {
            // 判不出来就当作有空间：这里失败的方向最多是"写满了才发现"，
            // 而保守拦住会让下载永远不启动
            return Long.MAX_VALUE;
        }
    }

    private WebDavClient clientFor(Config config, int index) {
        String signature = Config.safeHost(config.getBaseUrl()) + "|" + config.getUsername()
                + "|" + config.getPassword();
        WebDavClient client = mClients[index];
        if (client == null || !signature.equals(mClientTargets[index])) {
            if (client != null) {
                client.close();
            }
            client = new WebDavClient(config.getBaseUrl(), config.getUsername(),
                    config.getPassword());
            mClients[index] = client;
            mClientTargets[index] = signature;
        }
        return client;
    }

    /** 每个 worker 一路连接，所以只需要区分"我是第几路" */
    private int workerIndex() {
        Thread current = Thread.currentThread();
        for (int i = 0; i < mWorkers.length; i++) {
            if (mWorkers[i] == current) {
                return i;
            }
        }
        return 0;
    }

    private void waitLocked(long millis) {
        long wait = Math.max(millis, 1_000L);
        synchronized (mLock) {
            try {
                mLock.wait(Math.min(wait, 60_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mRunning = false;
            }
        }
    }

    private File stateFile() {
        File dir = new File(mContext.getFilesDir(), STATE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Logger.w("event=state_dir_failed dir=" + dir.getAbsolutePath());
        }
        return new File(dir, STATE_FILE);
    }

    /**
     * 把"已经下过哪些远端路径"写进与上传共用的 state.json。
     *
     * <p>两个队列各写各的键，读的时候各自读自己那一份，互不覆盖 —— 用两个文件反而
     * 会多一处"一半写成了、一半没写成"的不一致。写入前先把现有内容读出来合并，
     * 否则这里会把上传的 pending/done 整个抹掉。
     */
    private void saveLocked() {
        if (!mRunning) {
            // 热重载交接后新 generation 会自己重建；旧 worker 这时再落一次盘，
            // 会把新队列刚写入的视图整个盖掉
            return;
        }
        File file = stateFile();
        File tmp = new File(file.getParentFile(), STATE_FILE + ".download.tmp");
        try {
            JSONObject root = new JSONObject();
            if (file.exists()) {
                root = new JSONObject(new String(
                        Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            }
            JSONObject done = new JSONObject();
            for (Map.Entry<String, Long> entry : mDone.entrySet()) {
                done.put(entry.getKey(), entry.getValue());
            }
            root.put("dl_done", done);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) {
                Files.deleteIfExists(tmp.toPath());
            }
        } catch (Throwable t) {
            Logger.w("event=download_state_save_failed", t);
            try {
                Files.deleteIfExists(tmp.toPath());
            } catch (Throwable ignored) {
                // 清不掉就算了，下次覆盖写
            }
        }
    }

    private void load() {
        File file = stateFile();
        if (!file.exists()) {
            return;
        }
        try {
            JSONObject root = new JSONObject(new String(
                    Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            JSONObject done = root.optJSONObject("dl_done");
            if (done == null) {
                return;
            }
            Iterator<String> keys = done.keys();
            List<String> list = new ArrayList<>();
            while (keys.hasNext()) {
                list.add(keys.next());
            }
            synchronized (mLock) {
                for (String key : list) {
                    mDone.put(key, done.optLong(key, 0L));
                }
            }
            Logger.i("event=download_state_loaded done=" + mDone.size());
        } catch (Throwable t) {
            Logger.w("event=download_state_load_failed", t);
        }
    }
}
