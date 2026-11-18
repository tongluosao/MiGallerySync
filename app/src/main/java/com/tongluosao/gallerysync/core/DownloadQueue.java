package com.tongluosao.gallerysync.core;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;
import androidx.exifinterface.media.ExifInterface;
import android.os.StatFs;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

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
 * <p>计划线程只响应相册自己的批量下载检查事件。每个事件最多连续枚举
 * {@link #MAX_BATCH_ROUNDS} 轮，每轮最多认下 {@link #MAX_DOWNLOADS_PER_ROUND} 个文件；
 * 没有相册事件时不会自行扫描远端。
 *
 * <p>运行在相册进程，全部 IO 在自有后台线程，绝不阻塞相册的同步线程。
 */
public final class DownloadQueue {

    private static final String STATE_DIR = "webdav_sync";
    private static final String STATE_FILE = "state.json";
    /** 瘦身副本所在的隐藏目录：媒体扫描器跳过点目录，副本因此不会变成新的照片行 */
    private static final String SLIM_DIRECTORY = ".gallerysync-slim";
    /** Public storage root used when the remote path can be mapped back to an album. */
    private static final String PRIMARY_STORAGE_ROOT = "/storage/emulated/0";

    /**
     * Fallback for date/flat layouts and for album paths already occupied by different bytes.
     * Everything stays below one reserved directory so the fallback remains easy to inspect.
     */
    private static final String MIRROR_ROOT = PRIMARY_STORAGE_ROOT + "/DCIM/MiGallerySync";

    /**
     * 相册自己的云端索引表。模块判断"这张照片宿主还要不要"看的就是它：表里还有指向该本地
     * 路径的行 = 用户没删；查不到行 = 用户已删，绝不能自动下回来（见 {@link #shouldPlanDownload}）。
     * 与 core/TrashQueue.java:474-475 用的是同一个 provider。
     *
     * <p>只存字符串、用到时才 {@code Uri.parse}：纯 JVM 单测没有 android.net.Uri，放在静态
     * 初始化里会让整个类加载失败。
     */
    private static final String CLOUD_URI = "content://com.miui.gallery.cloud.provider/cloud";

    /** 一轮最多走几个目录（含子目录），防止一次枚举把整棵树拉下来 */
    private static final int MAX_DIRS_PER_ROUND = 200;
    /** 一轮最多认下几个文件 */
    private static final int MAX_DOWNLOADS_PER_ROUND = 200;
    /** 目录下钻深度上限 */
    private static final int MAX_DEPTH = 4;
    /** 同一个远端路径连续失败几次就不再重试（下一轮还会遇到，只是不再计入本轮） */
    private static final int MAX_FAIL_PER_PATH = 3;
    private static final long FAILURE_COOLDOWN_MS = 60_000L;
    /** 本地剩余空间的下限：低于它就整轮不下载，免得把手机写满 */
    private static final long MIN_FREE_BYTES = 512L * 1024 * 1024;
    /** 记住"哪些远端路径已经下过了"的上限，与上传的 done 表同一量级 */
    private static final int MAX_DONE_RECORDS = 20000;

    /** One native event may continue through a bounded number of productive listing rounds. */
    private static final int MAX_BATCH_ROUNDS = 8;

    /** 下载 worker 的路数：下载的瓶颈几乎总在远端带宽，两路足够叠起等待 */
    private static final int THREADS = 2;
    /** Maximum long edge for locally compressed previews. */
    private static final int PREVIEW_MAX_EDGE = 2560;
    private static final int PREVIEW_JPEG_QUALITY = 84;

    private static volatile DownloadQueue sInstance;

    private final Context mContext;
    private final OriginalStore mOriginalStore;
    /** 用户主动删掉的垃圾副本的远端路径账本，见 {@link RemoteSuppress} */
    private final RemoteSuppress mRemoteSuppress;
    private final Object mLock = new Object();

    /** 认出但还没下完的项。计划线程添、worker 线程取 */
    private final List<Pending> mPending = new ArrayList<>();
    /** 已经下过的远端路径 → 下完的时刻 */
    private final LinkedHashMap<String, Long> mDone = new LinkedHashMap<>();
    /** 失败次数，用来把反复失败的项排除掉 */
    private final LinkedHashMap<String, Integer> mFailures = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> mFailureTimes = new LinkedHashMap<>();
    /** 正在下载的远端路径，受 mLock 保护 */
    private final Set<String> mInflight = new HashSet<>();

    private volatile Config mConfig;
    /** Native Gallery auto-download gate. No host wiring means downloads stay disabled. */
    private volatile BooleanSupplier mDownloadPolicy = () -> false;
    /** Download completion paths only apply to this WebDAV target. */
    private String mDownloadTarget;
    private volatile boolean mRunning = true;
    private boolean mNativeRequestPending;
    private boolean mPlannerBusy;
    /** At most one ordinary trailing scan per busy scan chain. */
    private boolean mTrailingRequestUsed;
    private boolean mPolicySuspended = true;
    private int mWorkGeneration;
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
        /** 把相册目录里的真原图交给宿主扫描器，让宿主自己算出缩略图并建行。 */
        void ingest(String absolutePath);

        /**
         * 把 {@code <相册目录>/.gallerysync-slim/} 下的预览挂到**既有云行**上：把该行改成
         * "本地表示 = 这张预览"（thumbnailFile = previewPath、localFile 置空），与
         * {@code SlimHook} 走的是同一条 {@code SlimController.saveToDB} 路径。
         *
         * <p>**绝不**调用宿主扫描器：扫描器会把隐藏目录里的预览当成一张新照片，
         * 在相册的原图行之外多出一行"副本"（设备实证：cloud 表多出 _id=15989/15991）。
         *
         * <p>默认实现什么都不做（并留一条日志）：隐藏目录里的预览宁可暂时不上屏，
         * 也绝不能被扫描器当成新照片入库。
         */
        default void adoptPreview(String albumPath, String previewPath) {
            Logger.d("event=ingest_adopt_preview_unsupported album=" + albumPath
                    + " preview=" + previewPath);
        }

        /**
         * 把**既有云行**精确改成“本地文件 = 原路径、缩略图字段 = thumbnailFileOrEmpty”，
         * 返回是否真的改到了行。
         *
         * <p>原地覆盖用这条通道而不用 {@link #ingest}：原路径上那份字节已经是预览，但行本来就
         * 指向这个路径，所以既不需要扫描器建行，也不允许它建行（那会多出一行“副本”）。
         *
         * <p>默认实现返回 false（并留一条日志）：没有宿主接线时，调用方拿不到“行已切好”的确认，
         * 必须把那一次原地覆盖当成失败，回落到整份下载原图。
         */
        default boolean updateExistingRow(File dest, long rowId, String sha1,
                String thumbnailFileOrEmpty) {
            Logger.d("event=download_row_update_unsupported row=" + rowId);
            return false;
        }
    }

    /**
     * 宿主扫描器的唯一闸门：只有相册目录里的真原图才允许交给 {@link Ingest#ingest}。
     *
     * <p>判据是路径分段里有没有 {@code .gallerysync-slim}（忽略大小写、同时认 {@code /} 与
     * {@code \}），因此相册目录本身、以及名字里带这段字符的普通文件都仍然是 true。
     * 纯字符串逻辑，不碰 Android，可以直接单测。
     */
    public static boolean hostScanAllowed(String destPath) {
        if (destPath == null || destPath.isEmpty()) {
            return false;
        }
        for (String segment : destPath.replace('\\', '/').split("/", -1)) {
            if (SLIM_DIRECTORY.equalsIgnoreCase(segment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 发布成功之后通知宿主：相册目录里的文件照旧交给扫描器；隐藏目录里的预览只能挂到既有云行上，
     * 两者由 {@link #hostScanAllowed} 分流，调用方不再各自判断。
     */
    private void notifyHostAfterPublish(File published, File albumOriginal) {
        Ingest ingest = mIngest;
        if (ingest == null || published == null) {
            return;
        }
        String path = published.getAbsolutePath();
        if (hostScanAllowed(path)) {
            ingest.ingest(path);
            return;
        }
        File album = albumOriginal == null ? published : albumOriginal;
        Logger.i("event=download_preview_adopt_requested album=" + album.getAbsolutePath()
                + " preview=" + path);
        ingest.adoptPreview(album.getAbsolutePath(), path);
    }

    /** 待下载的一项 */
    private static final class Pending {
        final String remotePath;
        final String relativePath;
        final long size;
        final String target;
        final String localPath;

        Pending(String remotePath, String relativePath, long size, String target,
                String localPath) {
            this.remotePath = remotePath;
            this.relativePath = relativePath;
            this.size = size;
            this.target = target;
            this.localPath = localPath;
        }
    }

    private static final class DownloadCancelledException extends IOException {
        DownloadCancelledException() {
            super("native Gallery download gate is closed");
        }
    }

    private DownloadQueue(Context context, Config config) {
        mContext = context.getApplicationContext();
        mConfig = config;
        mOriginalStore = OriginalStore.get(mContext);
        mRemoteSuppress = RemoteSuppress.get(mContext);
        mDownloadTarget = OriginalStore.targetKey(config);
        load();
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
        String nextTarget = OriginalStore.targetKey(config);
        boolean targetChanged;
        synchronized (mLock) {
            targetChanged = !nextTarget.equals(mDownloadTarget);
            if (targetChanged) {
                mDownloadTarget = nextTarget;
                mPending.clear();
                mDone.clear();
                mFailures.clear();
                mFailureTimes.clear();
                mWorkGeneration++;
                mPendingVersion++;
                saveLocked();
            }
            mConfig = config;
            mLock.notifyAll();
        }
        if (targetChanged) {
            closeClients();
        }
    }

    private volatile BooleanSupplier mHighQualityPolicy = () -> false;
    /** Last observed mode; the prior supplier reads the same live native preference. */
    private boolean mHighQualityMode;

    /** Gallery's native thumbnail/original selection, supplied by the host hook. */
    public void setHighQualityPolicy(BooleanSupplier policy) {
        BooleanSupplier next = policy == null ? () -> false : policy;
        boolean changed;
        synchronized (mLock) {
            mHighQualityPolicy = next;
            boolean nextMode = readHighQuality(next);
            changed = mHighQualityMode != nextMode;
            mHighQualityMode = nextMode;
            if (changed) {
                if (mRunning) {
                    // Invalidate active planner/download work chosen under the old mode.
                    mWorkGeneration++;
                    // The mode change itself must be scanned even if an ordinary trailing
                    // request was already consumed while the planner was busy.
                    mNativeRequestPending = true;
                    mTrailingRequestUsed = true;
                }
                mFailures.clear();
                mFailureTimes.clear();
                mLock.notifyAll();
            }
        }
        if (changed) {
            closeClients();
        }
    }

    private boolean isHighQualityRequested() {
        return readHighQuality(mHighQualityPolicy);
    }

    private static boolean readHighQuality(BooleanSupplier policy) {
        try { return policy != null && policy.getAsBoolean(); }
        catch (Throwable failure) { return false; }
    }

    /** Install Gallery's live predicate; missing or failing policies keep downloads stopped. */
    public void setDownloadPolicy(BooleanSupplier policy) {
        mDownloadPolicy = policy == null ? () -> false : policy;
        refreshPolicyState();
        synchronized (mLock) {
            mLock.notifyAll();
        }
    }

    /**
     * Request a scan in response to Gallery's own batch-download check.
     * Busy callbacks keep one trailing scan; further condition checks collapse until it ends.
     */
    public void requestFromNative() {
        if (!refreshPolicyState()) {
            return;
        }
        synchronized (mLock) {
            if (!mPlannerBusy) {
                if (!mNativeRequestPending) {
                    mTrailingRequestUsed = false;
                }
                mNativeRequestPending = true;
            } else if (!mTrailingRequestUsed) {
                // Preserve one request that arrived too late for the in-progress scan. Further
                // condition checks coalesce until that trailing scan completes.
                mNativeRequestPending = true;
                mTrailingRequestUsed = true;
            }
            mLock.notifyAll();
        }
    }

    public void setIngest(Ingest ingest) {
        mIngest = ingest;
    }

    public void setPublisher(UploadProgressPublisher publisher) {
        mPublisher = publisher;
    }

    /** Refresh the native gate and invalidate unpublished work when Gallery disables downloads. */
    private boolean refreshPolicyState() {
        BooleanSupplier policy = mDownloadPolicy;
        boolean enabled;
        try {
            enabled = policy != null && policy.getAsBoolean();
        } catch (Throwable t) {
            Logger.w("event=download_policy_failed detail=" + t);
            enabled = false;
        }
        if (enabled) {
            synchronized (mLock) {
                if (mPolicySuspended) {
                    mPolicySuspended = false;
                    mLock.notifyAll();
                }
            }
            return true;
        }

        boolean close = false;
        synchronized (mLock) {
            if (!mPolicySuspended) {
                mWorkGeneration++;
                close = true;
            }
            mPolicySuspended = true;
            mNativeRequestPending = false;
            if (!mPending.isEmpty()) {
                mPending.clear();
                mPendingVersion++;
            }
            mFailures.clear();
            mFailureTimes.clear();
            mLock.notifyAll();
        }
        if (close) {
            closeClients();
        }
        return false;
    }

    private boolean isWorkCurrent(Config config, int generation) {
        if (!isPolicyCurrent(generation)) {
            return false;
        }
        return config != null && config.isReady()
                && NetGuard.canUpload(mContext, config.isWifiOnly());
    }

    private boolean isPolicyCurrent(int generation) {
        return isGenerationCurrent(generation) && refreshPolicyState()
                && isGenerationCurrent(generation);
    }

    private void ensureWorkCurrent(Config config, int generation)
            throws DownloadCancelledException {
        if (!isWorkCurrent(config, generation)) {
            throw new DownloadCancelledException();
        }
    }

    private boolean isGenerationCurrent(int generation) {
        synchronized (mLock) {
            return mRunning && generation == mWorkGeneration;
        }
    }

    private boolean isScheduledLocked(String remotePath) {
        if (mInflight.contains(remotePath)) {
            return true;
        }
        for (Pending pending : mPending) {
            if (remotePath.equals(pending.remotePath)) {
                return true;
            }
        }
        Integer failures = mFailures.get(remotePath);
        if (failures == null || failures < MAX_FAIL_PER_PATH) return false;
        Long failedAt = mFailureTimes.get(remotePath);
        if (failedAt != null && SystemClock.elapsedRealtime() - failedAt < FAILURE_COOLDOWN_MS) {
            return true;
        }
        // A later native event can retry; no independent scan timer is introduced.
        mFailures.remove(remotePath);
        mFailureTimes.remove(remotePath);
        return false;
    }

    private void removePendingLocked(String remotePath) {
        Iterator<Pending> it = mPending.iterator();
        while (it.hasNext()) {
            if (remotePath.equals(it.next().remotePath)) {
                it.remove();
            }
        }
    }

    private void closeClients() {
        for (WebDavClient client : mClients) {
            if (client != null) {
                client.close();
            }
        }
    }

    private void waitForSignal() {
        synchronized (mLock) {
            if (!mRunning) {
                return;
            }
            try {
                mLock.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mRunning = false;
            }
        }
    }

    /**
     * 用扩展名判断下载项是不是视频。
     *
     * <p>下载队列的 {@link Pending} 不带类型标记（远端只给了路径），进度页要把它分成
     * "图片/视频"两档，只能靠名字后缀。判错最多让统计偏一项，不影响调度。
     */
    private static boolean isVideoFileName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".m4v")
                || lower.endsWith(".mkv") || lower.endsWith(".avi") || lower.endsWith(".3gp")
                || lower.endsWith(".webm") || lower.endsWith(".ts") || lower.endsWith(".flv");
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
            public int pendingPhotos() {
                synchronized (mLock) {
                    int count = 0;
                    for (Pending pending : mPending) {
                        if (mInflight.contains(pending.remotePath)
                                || isVideoFileName(pending.relativePath)) {
                            continue;
                        }
                        count++;
                    }
                    return count;
                }
            }

            @Override
            public int pendingVideos() {
                synchronized (mLock) {
                    int count = 0;
                    for (Pending pending : mPending) {
                        if (mInflight.contains(pending.remotePath)
                                || !isVideoFileName(pending.relativePath)) {
                            continue;
                        }
                        count++;
                    }
                    return count;
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

    /** Compatibility hook for older host wiring; upload activity never schedules a scan. */
    public void onUploadIdle() {
        synchronized (mLock) {
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
            mNativeRequestPending = false;
            mWorkGeneration++;
            mLock.notifyAll();
        }
        closeClients();
    }

    // ------------------------------------------------------------------ 计划侧

    /**
     * 计划线程：响应相册事件遍历远端树，把"本地还没有的文件"放进 {@link #mPending}。
     *
     * <p>只有它做 PROPFIND。worker 线程只下载；新扫描只由 native Gallery 事件触发。
     */
    private void plannerLoop() {
        while (mRunning) {
            Config config;
            int generation;
            synchronized (mLock) {
                while (mRunning && !mNativeRequestPending) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        mRunning = false;
                        return;
                    }
                }
                if (!mRunning) {
                    return;
                }
                mNativeRequestPending = false;
                mPlannerBusy = true;
                config = mConfig;
                generation = mWorkGeneration;
            }
            try {
                if (!refreshPolicyState() || config == null || !config.isReady()) {
                    continue;
                }
                // A native start can discover a large remote tree. Continue only while each
                // bounded listing round finds new work, and cap work for one native event.
                for (int round = 0; round < MAX_BATCH_ROUNDS && mRunning; round++) {
                    if (!isWorkCurrent(config, generation)) {
                        break;
                    }
                    if (!NetGuard.canUpload(mContext, config.isWifiOnly())) {
                        Logger.d("event=download_skip reason=wifi_only");
                        break;
                    }
                    long free = freeBytes();
                    if (free < MIN_FREE_BYTES) {
                        Logger.w("event=download_skip reason=low_space free=" + free);
                        break;
                    }
                    int added;
                    try {
                        added = plan(config, generation);
                    } catch (Throwable t) {
                        Logger.w("event=download_round_failed detail=" + t);
                        break;
                    }
                    if (added <= 0) {
                        break;
                    }
                    if (round == MAX_BATCH_ROUNDS - 1) {
                        Logger.i("event=download_batch_limit rounds=" + MAX_BATCH_ROUNDS);
                    }
                }
            } finally {
                synchronized (mLock) {
                    mPlannerBusy = false;
                    if (!mNativeRequestPending) {
                        mTrailingRequestUsed = false;
                    }
                    mLock.notifyAll();
                }
            }
        }
    }

    /** @return 这一轮认出多少个新文件 */
    private int plan(Config config, int generation) {
        List<Pending> found = new ArrayList<>();
        // The directory planner must not share worker 0's connection: listing and downloading
        // concurrently on one WebDAV client can interleave response bodies.
        WebDavClient client = new WebDavClient(config.getBaseUrl(), config.getUsername(),
                config.getPassword());
        try {
            walk(client, config.getRemoteRoot(), "", 0, found,
                    config, OriginalStore.targetKey(config), isHighQualityRequested(),
                    generation, new HostRows(mContext));
        } finally {
            client.close();
        }
        if (found.isEmpty()) {
            Logger.d("event=download_round_empty");
            return 0;
        }
        int added = 0;
        synchronized (mLock) {
            if (generation != mWorkGeneration || !mRunning) {
                return 0;
            }
            for (Pending pending : found) {
                if (isScheduledLocked(pending.remotePath)) {
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

    /** Walk one bounded round and collect remote files that have no local match. */
    private void walk(WebDavClient client, String remoteDir, String relativeDir, int depth,
            List<Pending> out, Config config, String target, boolean highQuality,
            int generation, HostRows hostRows) {
        if (depth > MAX_DEPTH || out.size() >= MAX_DOWNLOADS_PER_ROUND
                || !isPolicyCurrent(generation)) {
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
            if (out.size() >= MAX_DOWNLOADS_PER_ROUND || dirs >= MAX_DIRS_PER_ROUND
                    || !isPolicyCurrent(generation)) {
                return;
            }
            String name = entry.name;
            if (!isSafeSegment(name)) {
                Logger.d("event=download_bad_name remote=" + remoteDir + "/" + name);
                continue;
            }
            String relative = relativeDir.isEmpty() ? name : relativeDir + "/" + name;
            if (entry.dir) {
                if (name.startsWith(".")) {
                    // 点开头目录一律不进：远端哈希旁车（{@link RemoteHashes#DIR}）就放在
                    // <root>/.mgs-hashes/ 里，递归进去只会白列一遍目录、白花一次往返，
                    // 而里面除了 .sha256 小文件没有任何相册媒体。
                    Logger.d("event=download_skip_hidden_dir name=" + name);
                    continue;
                }
                dirs++;
                walk(client, WebDavClient.joinPath(remoteDir, name), relative, depth + 1, out,
                        config, target, highQuality, generation, hostRows);
                continue;
            }
            if (skipByExtension(name) || !isGalleryMedia(name)) {
                continue;
            }
            String remotePath = WebDavClient.joinPath(remoteDir, name);
            if (!mayDownload(mRemoteSuppress, config, remotePath)) {
                // 用户主动删掉的垃圾副本：远端还在，但绝不能再下回来（否则删一次下回来一次）
                Logger.i("event=download_skip_suppressed remote=" + remotePath);
                continue;
            }
            File normalLocal = downloadDestinationFor(config, remotePath, relative, entry.size);
            OriginalStore.Record occupied = mOriginalStore.findByLocalPath(normalLocal);
            if (occupied != null && !remotePath.equals(occupied.remotePath)) {
                // Another format's JPEG preview must never count as this remote original.
                normalLocal = new File(MIRROR_ROOT, relative);
            }
            if (hasValidRegisteredSlimPreview(config, target, remotePath, normalLocal)) {
                Logger.d("event=download_skip_registered_slim_preview remote=" + remotePath);
                continue;
            }
            migrateLegacyDownload(config, remotePath, relative, normalLocal, generation);
            boolean done;
            boolean scheduled;
            synchronized (mLock) {
                done = mDone.containsKey(remotePath);
                scheduled = isScheduledLocked(remotePath);
            }
            boolean restoreMissingOriginal = !highQuality
                    && isRegisteredPreviewNeedingOriginalRestore(remotePath, config, normalLocal);
            if (!shouldPlanDownload(scheduled, done, restoreMissingOriginal, normalLocal.exists(),
                    hostRows.knows(normalLocal))) {
                continue;
            }
            out.add(new Pending(remotePath, relative, entry.size, target,
                    normalLocal.getAbsolutePath()));
        }
    }

    /**
     * 缺失的远端文件是否可以自动补齐。
     *
     * <p>判据（本次修复）：远端有、本地没有<b>不足以</b>说明这张照片该回来。相册自己的
     * {@code cloud} 表里还有一行指向这个本地路径，才说明是"相册还要它、只是本地文件被清了"
     * （换机/空间优化后的正当补齐）；没有行说明用户已经把它删了，此时把它下回来只会变成
     * 用户反复看到的"副本"。
     *
     * <p>2026-10-19 19:49 那次 200 项误下就是这个漏洞：这些 remote 名下的 cloud 行在计划时
     * 已经不存在（是本模块落盘后扫描器新建的，行号从 3xxx 变成 16xxx），而 {@code dl_done}
     * 账本里也没有它们（{@code done=false}）、本地文件又已被删（{@code exists()=false}），
     * 于是 :672/:675 两道 continue 都不成立，直接入队下载。
     *
     * <p>注册表里登记着"这一 remote 的原图被瘦身预览替代"的记录仍然放行（ORIGIN 恢复），
     * 它是模块自己的账本，与 DB 行是否存在无关；按需"下载原图"走的是原生请求链路，不经过这里。
     */
    static boolean shouldPlanDownload(boolean scheduled, boolean done,
            boolean restoreMissingOriginal, boolean localExists, boolean hostKnowsLocalPath) {
        if (scheduled) {
            return false;
        }
        if (done && !restoreMissingOriginal) {
            return false;
        }
        if (localExists && !restoreMissingOriginal) {
            return false;
        }
        return restoreMissingOriginal || hostKnowsLocalPath;
    }

    /**
     * 这条远端路径还允许自动下载吗。
     *
     * <p>闸在 {@link #shouldPlanDownload} 之上、也更强：那个判据问的是"要不要补"，
     * 这个问的是"还准不准碰"。用户主动删掉的垃圾副本（{@link RemoteSuppress} 记过账）
     * 一律不准碰 —— 远端对象还在，但它已经不再是"缺失的照片"，而是用户明确不要的东西。
     * 本轮计划与 worker 发 GET 之前各问一次：计划期间账本可能又多了新条目。
     */
    static boolean mayDownload(RemoteSuppress ledger, Config config, String remotePath) {
        return ledger == null || !ledger.isSuppressed(config, remotePath);
    }

    /** Whether the host still lists a local path (exact path, or its resolved canonical form). */
    static boolean hostKnowsLocalPath(Set<String> hostLocalPaths, File localPath) {
        if (hostLocalPaths == null || localPath == null) {
            return false;
        }
        if (hostLocalPaths.contains(localPath.getAbsolutePath())) {
            return true;
        }
        try {
            return hostLocalPaths.contains(localPath.getCanonicalPath());
        } catch (IOException ignored) {
            return false;
        }
    }

    /**
     * 一轮下载计划内的宿主 cloud 表本地路径快照，懒加载且最多查一次。
     *
     * <p>查询失败（provider 未就绪、被限制等）时 {@link #knows(File)} 返回 false，即
     * <b>失败即保守</b>：宁可这一轮不下，也不把用户删掉的副本重新写回相册。合法补齐
     * 允许漏一轮，下轮重试；误下却会立刻污染用户的相册。
     */
    private static final class HostRows {
        private final Context context;
        private boolean loaded;
        private Set<String> paths;

        HostRows(Context context) {
            this.context = context;
        }

        boolean knows(File localPath) {
            if (!loaded) {
                loaded = true;
                paths = read(context);
            }
            return hostKnowsLocalPath(paths, localPath);
        }

        private static Set<String> read(Context context) {
            Set<String> found = new LinkedHashSet<>();
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(Uri.parse(CLOUD_URI),
                        new String[]{"localFile"}, null, null, null);
                if (cursor == null) {
                    Logger.w("event=download_host_rows_unavailable reason=null_cursor");
                    return null;
                }
                while (cursor.moveToNext()) {
                    String local = cursor.getString(0);
                    if (local == null) {
                        continue;
                    }
                    String path = local.trim();
                    if (path.isEmpty()) {
                        continue;
                    }
                    found.add(path.startsWith("/") ? path : PRIMARY_STORAGE_ROOT + "/" + path);
                }
            } catch (Throwable t) {
                Logger.w("event=download_host_rows_unavailable reason=" + t);
                return null;
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            Logger.d("event=download_host_rows_loaded rows=" + found.size());
            return found;
        }
    }

    /** Let ORIGIN mode restore a registered preview after its path or bytes replaced the original. */
    private boolean isRegisteredPreviewNeedingOriginalRestore(String remotePath, Config config,
            File originalPath) {
        OriginalStore.Record record = mOriginalStore.findByRemotePath(config, remotePath,
                originalPath);
        if (record == null || record.previewLocalPath == null
                || record.originalLocalPath == null) {
            return false;
        }
        try {
            if (!originalPath.getCanonicalPath().equals(record.originalLocalPath)) {
                return false;
            }
            // A JPEG preview can share the original pathname. A sibling JPEG preview can also
            // remain registered after ORIGIN restores a PNG/WebP original, so recognize an
            // already restored original by content and avoid fetching it on every scan.
            return !originalPath.isFile() || originalPath.length() != record.originalLength
                    || !record.sha256.equals(sha256(originalPath));
        } catch (IOException ignored) {
            return false;
        }
    }

    /**
     * 已登记的瘦身副本就是本地匹配：判断依据是注册表里的派生路径 + 指纹，
     * 而不是目录名。旧版本遗留的 /.gallerysync-slim/ 路径仍然承认，因为它们本来也登记过。
     */
    private boolean hasValidRegisteredSlimPreview(Config config, String target, String remotePath,
            File originalPath) {
        OriginalStore.Record record = findRecordForOriginal(config, target, remotePath,
                originalPath);
        if (record == null) return false;
        Set<String> candidates = new LinkedHashSet<>(record.derivedPaths);
        if (record.previewLocalPath != null) {
            candidates.add(record.previewLocalPath);
        }
        for (String path : candidates) {
            if (path == null) continue;
            // 注册表里的派生路径优先；旧版本只记了隐藏目录里的文件名时用路径形状兜底
            if (!record.derivedPaths.contains(path) && !isLegacySlimPath(path)) {
                continue;
            }
            File preview = new File(path);
            if (!preview.isFile() || preview.length() <= 0L) continue;
            try {
                Long expectedLength = record.derivedHashes.get(sha256(preview));
                if (expectedLength != null && expectedLength.longValue() == preview.length()) {
                    return true;
                }
            } catch (IOException ignored) {
                // An unreadable copy is not a verified local match.
            }
        }
        return false;
    }

    private OriginalStore.Record findRecordForOriginal(Config config, String target,
            String remotePath, File originalPath) {
        OriginalStore.Record record = mOriginalStore.findByRemotePath(config, remotePath,
                originalPath);
        if (record == null || !target.equals(record.target)
                || !remotePath.equals(record.remotePath) || record.originalLocalPath == null) {
            return null;
        }
        try {
            return originalPath.getCanonicalPath().equals(record.originalLocalPath) ? record : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private long restoreRegisteredOriginalMtime(Pending pending, Config config,
            File normalLocal, File downloadedOriginal) throws IOException {
        OriginalStore.Record record = findRecordForOriginal(config, pending.target,
                pending.remotePath, normalLocal);
        if (record == null || record.originalLocalMtime <= 0L) return 0L;
        if (downloadedOriginal.lastModified() != record.originalLocalMtime
                && !downloadedOriginal.setLastModified(record.originalLocalMtime)) {
            throw new IOException("cannot restore registered original modification time");
        }
        return record.originalLocalMtime;
    }

    private static void setModificationTime(File file, long modified) throws IOException {
        if (modified > 0L && file.lastModified() != modified && !file.setLastModified(modified)) {
            throw new IOException("cannot preserve downloaded file modification time");
        }
    }

    // ------------------------------------------------------------------ 下载侧

    private void workerLoop() {
        while (mRunning) {
            Config config = mConfig;
            if (!refreshPolicyState() || config == null || !config.isReady()
                    || !NetGuard.canUpload(mContext, config.isWifiOnly())) {
                waitForSignal();
                continue;
            }
            Pending next = null;
            int generation;
            synchronized (mLock) {
                while (mRunning && next == null) {
                    next = takeLocked();
                    if (next == null) {
                        try {
                            mLock.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                if (!mRunning) {
                    return;
                }
                generation = mWorkGeneration;
            }
            download(next, config, generation);
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

    private void download(Pending pending, Config config, int generation) {
        int index = workerIndex();
        UploadProgressPublisher publisher = mPublisher;
        long size = Math.max(pending.size, 0L);
        File normalDest = new File(pending.localPath);
        File dest = normalDest;
        String key = key(pending);
        boolean ok = false;
        /** 这一项是否已经按原地模式提交（预览就在原路径上，DB 行也已经精确改过） */
        boolean inplaceCommitted = false;
        if (publisher != null) {
            publisher.taskStart(key, baseName(pending.relativePath), normalDest.getAbsolutePath(),
                    size, false, ProgressProtocol.DIR_DOWN);
        }
        File raw = null;
        File staged = null;
        File compressed = null;
        long registeredOriginalMtime = 0L;
        try {
            ensureWorkCurrent(config, generation);
            if (hasValidRegisteredSlimPreview(config, pending.target, pending.remotePath,
                    normalDest)) {
                Logger.d("event=download_skip_registered_slim_preview remote="
                        + pending.remotePath);
                markDone(pending.remotePath, pending.target);
                ok = true;
                return;
            }
            boolean restoreRegisteredOriginal = !isHighQualityRequested()
                    && isRegisteredPreviewNeedingOriginalRestore(pending.remotePath, config,
                            normalDest);
            if (restoreRegisteredOriginal) {
                OriginalStore.Record record = mOriginalStore.findByRemotePath(config,
                        pending.remotePath, normalDest);
                if (record == null) {
                    throw new IOException("registered preview disappeared before restore");
                }
                ensureWorkCurrent(config, generation);
                dest = mOriginalStore.restoreOriginal(config, record, normalDest);
                markDone(pending.remotePath, pending.target);
                notifyHostAfterPublish(dest, normalDest);
                Logger.i("event=download_original_restored remote=" + pending.remotePath
                        + " local=" + dest.getAbsolutePath());
                ok = true;
                return;
            }
            boolean qualityRequested = isHighQualityRequested()
                    && isCompressibleExtension(normalDest.getName()) && pending.size > 0L;
            if (normalDest.exists()) {
                // 本地已经有同名文件：一律不覆盖。用户可能自己改过它，也可能同名但是别的东西
                Logger.i("event=download_skip_local_exists remote=" + pending.remotePath
                        + " local=" + normalDest);
                markDone(pending.remotePath, pending.target);
                ok = true;
                return;
            }
            if (!mayDownload(mRemoteSuppress, config, pending.remotePath)) {
                // 计划之后账本又多了这条（删除线程与下载线程并行）：到 GET 之前再拦一次
                Logger.i("event=download_skip_suppressed remote=" + pending.remotePath);
                ok = true;
                return;
            }
            WebDavClient client = clientFor(config, index);
            client.setProgressListener((done, total) -> {
                // 每 64KB 一次，这里只把数字塞给进度发布器（它自己按 2Hz 节流后发帧）
                if (!isWorkCurrent(config, generation)) {
                    client.close();
                    return;
                }
                UploadProgressPublisher target = mPublisher;
                if (target != null) {
                    target.taskBytes(key, done);
                }
            });
            if (qualityRequested) {
                File rawDir = new File(mContext.getFilesDir(), STATE_DIR + "/original_staging");
                if (!rawDir.isDirectory() && !rawDir.mkdirs() && !rawDir.isDirectory()) {
                    throw new IOException("cannot create original staging directory");
                }
                raw = File.createTempFile("original-", ".tmp", rawDir);
                try {
                    client.get(pending.remotePath, raw);
                } finally {
                    client.setProgressListener(null);
                }
                ensureWorkCurrent(config, generation);
                verifyDownloadedOriginal(pending, raw);
                registeredOriginalMtime = restoreRegisteredOriginalMtime(pending, config,
                        normalDest, raw);
                String digest = sha256(raw);
                File previewDest = previewPathFor(normalDest, config.isPreserveHeicFormat());
                OriginalStore.Record previewRecord = mOriginalStore.findByRemotePath(config,
                        pending.remotePath, normalDest);
                if (previewRecord == null) {
                    // 原图已被隐藏目录里的副本取代时，记录只按副本路径查得到
                    previewRecord = mOriginalStore.findByRemotePath(config, pending.remotePath,
                            previewDest);
                }
                if (samePath(previewDest, normalDest)) {
                    // ---- 原地分支：预览按原路径、原文件名覆盖，不再产生 .gallerysync-slim 副本 ----
                    InplaceAttempt attempt = publishPreviewInPlace(config, pending, raw, digest,
                            normalDest, registeredOriginalMtime, generation);
                    if (attempt.committed) {
                        dest = normalDest;
                        inplaceCommitted = true;
                    } else {
                        // 失败一律回落到“整份下载原图”：原路径上要么还没有字节，要么是这次写下的
                        // 预览 —— 后者由 publishDownloadedOriginal 按摘要确认后才允许覆盖。
                        Logger.i("event=download_preview_inplace_fallback remote="
                                + pending.remotePath + " local=" + normalDest.getAbsolutePath());
                        dest = publishDownloadedOriginal(config, pending, raw, digest, normalDest,
                                registeredOriginalMtime, generation, attempt);
                        if (attempt.publishAttempted) {
                            restoreRegistryAfterReplacement(config, pending, normalDest);
                        }
                    }
                } else {
                    // 目标位置上的文件只有是这张照片自己登记过的副本才可以复用或覆盖，别的文件一律不碰。
                    boolean registeredPreview = previewDest.exists()
                            && mOriginalStore.isRegisteredDerivedCopy(previewRecord, previewDest);
                    boolean previewCollision = !samePath(previewDest, normalDest)
                            && previewPathOccupiedByAnotherFile(previewRecord, previewDest);
                    File parent = previewDest.getAbsoluteFile().getParentFile();
                    if (parent == null
                            || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
                        throw new IOException("cannot create download directory");
                    }
                    boolean compressedOk = false;
                    boolean previewReusable = registeredPreview
                            && mOriginalStore.isVerifiedDerivedBytes(previewRecord, previewDest);
                    if (!previewCollision && !previewReusable) {
                        compressed = new File(parent, "." + previewDest.getName() + ".mgs-"
                                + UUID.randomUUID() + ".tmp");
                        compressedOk = createCompressedPreview(raw, compressed, normalDest.getName(),
                                config)
                                && compressed.length() > 0L && compressed.length() < raw.length();
                    }
                    if (compressedOk) {
                        // Persist the protected final path before it becomes visible to a media scanner.
                        ensureWorkCurrent(config, generation);
                        mOriginalStore.registerDownloadedPreview(config, pending.remotePath,
                                raw.length(), digest, normalDest, previewDest, compressed);
                        dest = previewDest;
                        ensureWorkCurrent(config, generation);
                        if (!isHighQualityRequested()) {
                            throw new DownloadCancelledException();
                        }
                        publishStagedFile(compressed, dest, registeredPreview);
                        compressed = null;
                        Logger.i("event=download_preview_ok remote=" + pending.remotePath
                                + " local=" + dest.getAbsolutePath() + " original=" + raw.length()
                                + " preview=" + dest.length());
                    } else if (previewReusable) {
                        // 已经是这张照片的副本：不再重新编码，只刷新登记与修改时间。
                        ensureWorkCurrent(config, generation);
                        mOriginalStore.registerDownloadedPreview(config, pending.remotePath,
                                raw.length(), digest, normalDest, previewDest);
                        dest = previewDest;
                        setModificationTime(dest, registeredOriginalMtime);
                        Logger.i("event=download_preview_reused remote=" + pending.remotePath
                                + " local=" + dest.getAbsolutePath() + " preview="
                                + dest.length());
                    } else {
                        Logger.i("event=download_preview_skipped reason="
                                + (previewCollision ? "destination_collision"
                                        : "unsupported_or_no_savings")
                                + " remote=" + pending.remotePath);
                        dest = publishDownloadedOriginal(config, pending, raw, digest, normalDest,
                                registeredOriginalMtime, generation, null);
                    }
                }
            } else {
                // Keep raw bytes private until durable mirror protection is in place.
                File rawDir = new File(mContext.getFilesDir(), STATE_DIR + "/original_staging");
                if (!rawDir.isDirectory() && !rawDir.mkdirs() && !rawDir.isDirectory()) {
                    throw new IOException("cannot create original staging directory");
                }
                raw = File.createTempFile("original-", ".tmp", rawDir);
                try {
                    client.get(pending.remotePath, raw);
                } finally {
                    client.setProgressListener(null);
                }
                ensureWorkCurrent(config, generation);
                verifyDownloadedOriginal(pending, raw);
                registeredOriginalMtime = restoreRegisteredOriginalMtime(pending, config,
                        normalDest, raw);
                String digest = sha256(raw);
                // 原路径上可能还留着第 3 波瘦身写下的原地预览：整份原图正是要把它顶掉。
                // 只认“这条记录自己登记过的副本”，用户文件与计划之后出现的文件仍按失败处理。
                boolean replacingInplacePreview = false;
                String existingRowId = "";
                if (normalDest.exists()) {
                    OriginalStore.Record existing = mOriginalStore.findByRemotePath(config,
                            pending.remotePath, normalDest);
                    if (!mOriginalStore.isRegisteredDerivedCopy(existing, normalDest)) {
                        throw new IOException("download destination appeared before publish: "
                                + normalDest);
                    }
                    replacingInplacePreview = true;
                    existingRowId = existing == null ? "" : existing.rowId;
                }
                staged = stageBesideDestination(raw, normalDest);
                setModificationTime(staged, registeredOriginalMtime);
                ensureWorkCurrent(config, generation);
                mOriginalStore.registerDownloadedOriginal(config, pending.remotePath, staged,
                        normalDest, raw.length(), digest);
                dest = normalDest;
                ensureWorkCurrent(config, generation);
                publishStagedFile(staged, normalDest, replacingInplacePreview);
                staged = null;
                if (replacingInplacePreview) {
                    // 原路径上现在是完整原图：把账本从“这里放的是预览”复位成 ORIGINAL，
                    // 否则上传分类会永远把这份文件当成派生副本拦住。
                    restoreRegistryAfterReplacement(config, pending, normalDest);
                    Logger.i("event=download_original_inplace row=" + existingRowId
                            + " bytes=" + dest.length()
                            + " remote=" + pending.remotePath
                            + " local=" + normalDest.getAbsolutePath());
                }
            }
            raw.delete();
            raw = null;
            markDone(pending.remotePath, pending.target);
            // 原地发布已经把既有行精确改成“本地文件 = 原路径、缩略图为空”：这时再交给宿主
            // 扫描器只会让它在同一路径上多建一行，所以原地成功分支刻意跳过通知。
            if (!inplaceCommitted) {
                // 质量分支的 dest 在 .gallerysync-slim 里时只能挂到既有云行，不能交给宿主扫描器
                notifyHostAfterPublish(dest, normalDest);
            }
            Logger.i("event=download_ok remote=" + pending.remotePath
                    + " local=" + dest.getAbsolutePath() + " size=" + dest.length());
            ok = true;
        } catch (DownloadCancelledException e) {
            cleanupUnpublished(raw, staged, compressed);
            Logger.d("event=download_cancelled remote=" + pending.remotePath);
        } catch (WebDavClient.TransportFailure e) {
            cleanupUnpublished(raw, staged, compressed);
            if (isWorkCurrent(config, generation)) {
                fail(pending, e.getMessage());
            } else {
                Logger.d("event=download_cancelled remote=" + pending.remotePath);
            }
        } catch (IOException e) {
            cleanupUnpublished(raw, staged, compressed);
            if (isWorkCurrent(config, generation)) {
                fail(pending, e.getMessage());
            } else {
                Logger.d("event=download_cancelled remote=" + pending.remotePath);
            }
        } catch (Throwable t) {
            cleanupUnpublished(raw, staged, compressed);
            if (isWorkCurrent(config, generation)) {
                fail(pending, String.valueOf(t));
                Logger.w("event=download_error remote=" + pending.remotePath, t);
            } else {
                Logger.d("event=download_cancelled remote=" + pending.remotePath);
            }
        } finally {
            if (publisher != null) {
                publisher.taskEnd(key, ok);
            }
            synchronized (mLock) {
                mInflight.remove(pending.remotePath);
                if (ok) {
                    removePendingLocked(pending.remotePath);
                    mFailures.remove(pending.remotePath);
                    mFailureTimes.remove(pending.remotePath);
                    mPendingVersion++;
                }
                mLock.notifyAll();
            }
        }
    }

    private static void cleanupUnpublished(File raw, File staged, File compressed) {
        if (staged != null && staged.exists()) {
            staged.delete();
        }
        if (compressed != null && compressed.exists()) {
            compressed.delete();
        }
        if (raw != null && raw.exists()) {
            raw.delete();
        }
    }

    private static void verifyDownloadedOriginal(Pending pending, File file) throws IOException {
        if (!file.isFile() || file.length() <= 0L) {
            throw new IOException("download produced an empty original");
        }
        if (pending.size > 0L && file.length() != pending.size) {
            throw new IOException("download length mismatch expected=" + pending.size
                    + " actual=" + file.length());
        }
    }

    /**
     * 在目标文件的同一目录里创建一个隐藏的临时文件名，写入完成后再原子改名成目标。
     * 空间优化与下载共用这个命名，临时文件永远落在同一个目录里。
     */
    public static File stageBesideDestination(File destination) throws IOException {
        File parent = destination.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
            throw new IOException("cannot create download directory");
        }
        return new File(parent, "." + destination.getName() + ".mgs-"
                + UUID.randomUUID() + ".tmp");
    }

    private static File stageBesideDestination(File source, File dest) throws IOException {
        File stage = stageBesideDestination(dest);
        try (FileInputStream in = new FileInputStream(source);
                FileOutputStream out = new FileOutputStream(stage)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            out.getFD().sync();
        } catch (Throwable t) {
            stage.delete();
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            throw new IOException("cannot stage original", t);
        }
        if (stage.length() != source.length()) {
            stage.delete();
            throw new IOException("staged original length mismatch");
        }
        return stage;
    }

    private static void publishStagedFile(File staged, File dest) throws IOException {
        publishStagedFile(staged, dest, false);
    }

    /**
     * replaceRegisteredPreview 只允许在目标位置是这条记录自己登记过的副本时置为 true：
     * 那时覆盖的是我们以前写下的同名副本，不是用户的文件。
     */
    private static void publishStagedFile(File staged, File dest, boolean replaceRegisteredPreview)
            throws IOException {
        // 载荷闸门：任何东西在改名进入相册之前，都必须真的是有效介质。压缩预览与原图两条
        // 发布路径都从这里出去，所以闸门放在这一层；不合规就抛 IOException，
        // 由 download() 的 catch 分支清理临时文件并按一次失败记账（不发布半成品）。
        if (!MediaPayload.isUsableMedia(staged)) {
            Logger.w("event=download_rejected reason=invalid_payload path="
                    + (staged == null ? "null" : staged.getAbsolutePath())
                    + " dest=" + (dest == null ? "null" : dest.getAbsolutePath())
                    + " size=" + (staged == null ? -1L : staged.length()));
            throw new IOException("download produced an invalid payload");
        }
        if (!replaceRegisteredPreview && dest.exists()) {
            throw new IOException("download destination appeared before publish: " + dest);
        }
        // Same-directory moves publish the complete file at once and fail if another file wins
        // the race; never replace a file that appeared after planning.
        if (replaceRegisteredPreview) {
            Files.move(staged.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(staged.toPath(), dest.toPath());
        }
    }

    /**
     * 一次“原地预览”尝试留下的事实：写到哪一步、以及原路径上可能会留下哪份字节。
     *
     * <p>{@code committed} 为 false 时调用方必须回落到“整份下载原图”；
     * {@code previewLength/previewSha256} 让回落分支能按摘要认出“原路径上这份字节是我刚写的预览”，
     * 从而只允许覆盖自己写下的东西。
     */
    private static final class InplaceAttempt {
        boolean committed;
        /** 是否真的调用过写入引擎（true 时注册表状态可能已被改动，回落需要收尾） */
        boolean publishAttempted;
        long previewLength;
        String previewSha256;
    }

    /**
     * 下载高清图 / 下载原图的原地分支：预览编码到相册目录里的临时件，再交给
     * {@link InplacePreviewWriter} 按固定顺序覆盖原路径。
     *
     * <p>相对侧车分支少了两件事：预览不再搬进隐藏目录（目标就是原文件本身），也<b>不再交给
     * 宿主扫描器</b>；DB 反过来写 {@code localFile=原路径、thumbnailFile=""}，所以相册里仍然只有
     * 原来那一行、原路径上也仍然只有一份文件。
     *
     * <p>只有“注册表里有这一行、这行已经有 rowId、宿主 Ingest 可用”时才敢走：原地发布把 DB
     * 精确改到既有行上，缺任何一项都只能整份下载原图 —— 宁可多占空间，也绝不让扫描器多建一行。
     *
     * <p>失败一律返回 {@code committed=false}：临时件在这里删干净，原路径的收尾交给回落的
     * {@link #publishDownloadedOriginal}（它按摘要确认后才覆盖）。
     */
    private InplaceAttempt publishPreviewInPlace(Config config, Pending pending, File raw,
            String digest, File dest, long originalMtime, int generation) throws IOException {
        InplaceAttempt attempt = new InplaceAttempt();
        OriginalStore.Record record = mOriginalStore.findByRemotePath(config, pending.remotePath,
                dest);
        Ingest ingest = mIngest;
        if (record == null || record.originalLocalPath == null
                || !samePath(record.originalLocalPath, dest.getPath())) {
            Logger.i("event=download_preview_inplace_skipped reason=no_registered_original"
                    + " remote=" + pending.remotePath + " local=" + dest.getAbsolutePath());
            return attempt;
        }
        Long rowId = parseRowId(record.rowId);
        if (rowId == null || ingest == null) {
            Logger.i("event=download_preview_inplace_skipped reason="
                    + (rowId == null ? "row_unbound" : "no_host_ingest")
                    + " remote=" + pending.remotePath + " row=" + record.rowId);
            return attempt;
        }
        if (dest.exists()) {
            // 原路径上已经有字节：只有“我们自己以前写下的同名预览/副本”才允许顶掉。那正是重复下载
            // 高清图的常态（先瘦身成原地预览，再要求下载高清图），账本按登记路径或字节指纹认得出
            // 这份字节；不是登记过的副本（用户自己放的文件、或计划之后别人写的东西）一律不动。
            if (!mOriginalStore.isRegisteredDerivedCopy(record, dest)) {
                Logger.w("event=download_preview_inplace_skipped reason=destination_not_empty"
                        + " remote=" + pending.remotePath + " local=" + dest.getAbsolutePath());
                return attempt;
            }
            Logger.i("event=download_preview_inplace_replace_existing remote="
                    + pending.remotePath + " local=" + dest.getAbsolutePath());
        }
        File parent = dest.getAbsoluteFile().getParentFile();
        if (parent == null
                || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
            Logger.w("event=download_preview_inplace_skipped reason=target_directory_unavailable"
                    + " remote=" + pending.remotePath);
            return attempt;
        }
        // 临时件必须与目标同目录：跨目录的改名会退化成“复制 + 删除”，不再是原子发布。
        File staged = new File(parent, "." + dest.getName() + ".mgs-tmp-" + UUID.randomUUID());
        boolean encoded;
        try {
            encoded = createCompressedPreview(raw, staged, dest.getName(), config)
                    && staged.length() > 0L && staged.length() < raw.length();
        } catch (Throwable t) {
            encoded = false;
            Logger.w("event=download_preview_inplace_skipped reason=encode_failed remote="
                    + pending.remotePath, t);
        }
        if (!encoded) {
            staged.delete();
            Logger.i("event=download_preview_inplace_skipped"
                    + " reason=unsupported_or_no_savings remote=" + pending.remotePath);
            return attempt;
        }
        String previewSha256 = InplacePreviewWriter.sha256Of(staged);
        if (previewSha256 == null) {
            staged.delete();
            Logger.w("event=download_preview_inplace_failed reason=preview_hash_failed remote="
                    + pending.remotePath);
            return attempt;
        }
        attempt.previewLength = staged.length();
        attempt.previewSha256 = previewSha256;
        InplacePreviewWriter.Request request = new InplacePreviewWriter.Request();
        request.original = dest;
        // 左上角的长度/摘要就是远端完整原图的那一份：本地原图还没写下来，只能拿远端事实当基准
        request.originalLength = raw.length();
        request.originalSha256 = digest;
        request.staged = staged;
        request.previewLength = attempt.previewLength;
        request.previewSha256 = previewSha256;
        request.previewFormat = InplaceSlimPolicy.targetFormat(dest.getName());
        request.rowId = record.rowId;
        DownloadInplaceCallbacks callbacks = new DownloadInplaceCallbacks(config, pending, record,
                dest, rowId, ingest, originalMtime);
        InplacePreviewWriter.Outcome outcome;
        try {
            ensureWorkCurrent(config, generation);
            attempt.publishAttempted = true;
            outcome = InplacePreviewWriter.publish(request, callbacks);
        } catch (IOException cancelled) {
            // 模式/目标在编码之后变了：这一项整体作废，临时件由 release 收掉
            staged.delete();
            throw cancelled;
        } catch (Throwable t) {
            staged.delete();
            Logger.w("event=download_preview_inplace_failed reason=exception remote="
                    + pending.remotePath, t);
            return attempt;
        } finally {
            callbacks.close();
        }
        if (!outcome.success) {
            Logger.w("event=download_preview_inplace_failed reason=" + outcome.reason
                    + " restored=" + outcome.restored + " remote=" + pending.remotePath);
            return attempt;
        }
        attempt.committed = true;
        Logger.i("event=download_preview_inplace row=" + record.rowId
                + " bytes=" + attempt.previewLength
                + " saved=" + Math.max(0L, raw.length() - attempt.previewLength)
                + " remote=" + pending.remotePath + " local=" + dest.getAbsolutePath());
        return attempt;
    }

    /**
     * 把刚下完的原图整份发布到相册原路径。
     *
     * <p>两条路共用它：不可压缩/没有收益的老分支，以及原地预览失败后的回落。{@code attempt}
     * 非空表示刚试过原地覆盖 —— 那时原路径上可能已经是本次写下的预览（甚至是被复核失败分支
     * 回填好的原图），两种已知字节才允许覆盖，别的文件一律不动。
     */
    private File publishDownloadedOriginal(Config config, Pending pending, File raw, String digest,
            File normalDest, long originalMtime, int generation, InplaceAttempt attempt)
            throws IOException {
        boolean replaceExisting = false;
        if (normalDest.isFile()) {
            String current = sha256(normalDest);
            if (digest.equals(current)) {
                // 复核失败分支已经把原图回填到原路径：只补登记与时间戳，不再搬第二遍
                mOriginalStore.registerDownloadedOriginal(config, pending.remotePath, normalDest,
                        raw.length(), digest);
                setModificationTime(normalDest, originalMtime);
                return normalDest;
            }
            if (attempt == null || attempt.previewSha256 == null
                    || !attempt.previewSha256.equals(current)) {
                throw new IOException("download destination appeared before publish: "
                        + normalDest);
            }
            replaceExisting = true;
        }
        File staged = stageBesideDestination(raw, normalDest);
        try {
            setModificationTime(staged, originalMtime);
            ensureWorkCurrent(config, generation);
            mOriginalStore.registerDownloadedOriginal(config, pending.remotePath, staged,
                    normalDest, raw.length(), digest);
            ensureWorkCurrent(config, generation);
            publishStagedFile(staged, normalDest, replaceExisting);
        } catch (Throwable t) {
            staged.delete();
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            throw new IOException("cannot publish downloaded original", t);
        }
        return normalDest;
    }

    /**
     * 原路径上的字节被整份原图顶掉之后的注册表收尾（两个调用点：原地预览失败的回落、
     * “下载原图”顶掉旧预览）。
     *
     * <p>写入引擎可能已经把状态推到 {@code PENDING_INPLACE} / {@code DAMAGED}，并把原路径登记成
     * 派生副本；而调用方随即又在那里写下了**完整原图**。用 {@code restoreOriginal} 把这行
     * 复位成 {@link OriginalStore.LocalState#ORIGINAL}：它按长度 + 摘要认出原路径上就是原图，
     * 只改账本、不再联网重下。失败只记日志 —— 字节已经正确，不让收尾把整次下载判失败。
     */
    private void restoreRegistryAfterReplacement(Config config, Pending pending, File dest) {
        try {
            OriginalStore.Record record = mOriginalStore.findByRemotePath(config,
                    pending.remotePath, dest);
            if (record == null) {
                return;
            }
            mOriginalStore.restoreOriginal(config, record, dest);
            Logger.i("event=download_inplace_registry_restored remote="
                    + pending.remotePath + " local=" + dest.getAbsolutePath());
        } catch (Throwable t) {
            Logger.w("event=download_inplace_registry_restore_failed remote="
                    + pending.remotePath, t);
        }
    }

    /** 相册行的 rowId 只接受十进制整数；空串/null/别的东西都当作“这一行还没绑定”。 */
    private static Long parseRowId(String rowId) {
        String value = rowId == null ? "" : rowId.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException unreadable) {
            return null;
        }
    }

    /**
     * 原地覆盖需要的六个副作用：租约、意图、DB 精确更新、状态提交、损坏标记、原图回填。
     *
     * <p>全部经由 {@link OriginalStore} 与宿主 {@link Ingest}；每个回调自己吞异常并返回
     * false/void，让引擎只关心 reason，不必区分“抛了异常”和“返回了失败”。
     */
    private final class DownloadInplaceCallbacks implements InplacePreviewWriter.Callbacks {
        private final Config mConfig;
        private final Pending mPending;
        private final OriginalStore.Record mRecord;
        private final File mDest;
        private final long mRowId;
        private final Ingest mIngest;
        private final long mOriginalMtime;
        private OriginalStore.PathLease mLease;

        DownloadInplaceCallbacks(Config config, Pending pending, OriginalStore.Record record,
                File dest, long rowId, Ingest ingest, long originalMtime) {
            mConfig = config;
            mPending = pending;
            mRecord = record;
            mDest = dest;
            mRowId = rowId;
            mIngest = ingest;
            mOriginalMtime = originalMtime;
        }

        @Override public boolean beginLease() {
            try {
                // 先挡住上传线程，再把原路径登记成“即将被接管的派生位置”
                mLease = mOriginalStore.beginDerivedMutation(mConfig, mRecord.rowId, mRecord.sha1,
                        mDest);
                return mLease != null;
            } catch (Throwable t) {
                Logger.w("event=download_preview_inplace_lease_failed remote="
                        + mPending.remotePath, t);
                return false;
            }
        }

        @Override public boolean markPending(long len, String sha256, String format) {
            try {
                return mOriginalStore.markInplacePending(mConfig, mRecord, len, sha256, format)
                        != null;
            } catch (Throwable t) {
                Logger.w("event=download_preview_inplace_pending_failed remote="
                        + mPending.remotePath, t);
                return false;
            }
        }

        @Override public boolean commitDatabase(File original) {
            try {
                // thumbnailFile 置空、localFile=原路径：相册认为原图仍在本机，且不多出一行
                boolean updated = mIngest.updateExistingRow(original, mRowId, mRecord.sha1, "");
                if (!updated) {
                    Logger.w("event=download_row_update_failed row=" + mRowId
                            + " remote=" + mPending.remotePath);
                }
                return updated;
            } catch (Throwable t) {
                Logger.e("event=download_row_update_failed row=" + mRowId
                        + " remote=" + mPending.remotePath, t);
                return false;
            }
        }

        @Override public void markInPlace(long len, String sha256, String format) {
            try {
                mOriginalStore.markDownloadedInPlace(mConfig, mRecord, len, sha256, format);
            } catch (Throwable t) {
                Logger.w("event=registry_download_inplace_failed remote=" + mPending.remotePath,
                        t);
            }
        }

        @Override public void markDamaged(String reason) {
            try {
                mOriginalStore.markDamaged(mConfig, mRecord, reason);
            } catch (Throwable t) {
                Logger.w("event=download_preview_inplace_damaged_failed remote="
                        + mPending.remotePath, t);
            }
        }

        @Override public void restoreOriginal() {
            try {
                // 复核失败时原路径上已经是这次写下的预览：先登记成派生副本，恢复流程才肯覆盖它
                mOriginalStore.registerDerivedPath(mConfig, mRecord.rowId, mRecord.sha1, mDest);
            } catch (Throwable t) {
                Logger.w("event=download_preview_inplace_restore_mark_failed remote="
                        + mPending.remotePath, t);
            }
            try {
                mOriginalStore.restoreOriginal(mConfig, mRecord, mDest);
                setModificationTime(mDest, mOriginalMtime);
            } catch (Throwable t) {
                Logger.e("event=download_preview_inplace_restore_failed remote="
                        + mPending.remotePath, t);
            }
        }

        /** 释放租约并清掉没被引擎消费掉的临时件（成功路径上它已经被改名走了）。 */
        void close() {
            if (mLease != null) {
                mLease.close();
                mLease = null;
            }
        }
    }

    private static boolean isCompressibleExtension(String name) {        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp")
                || lower.endsWith(".bmp") || isHeicName(name);
    }

    public static boolean isHeicName(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".heic") || lower.endsWith(".heif");
    }

    static File previewPathFor(File original) {
        return previewPathFor(original, true);
    }

    /**
     * 本地高清副本的位置与命名规则。<b>原地模式的格式直接返回原文件本身</b>
     * （{@code .jpg/.jpeg}、{@code .png}，以及 {@code preserveHeic} 打开时的 {@code .heic/.heif}）：
     * 预览就覆盖在原路径、原文件名上，不再另起一份。
     *
     * <p>PNG 与 HEIC 的开关无关：它的编码器只会写 PNG（无损、保 alpha），同扩展名覆盖永远成立；
     * 只有 HEIC 需要看 {@code preserveHeic}，因为关掉时编码器产出 JPEG，写进 {@code .heic}
     * 就是容器不匹配。其余容器（webp/bmp/gif/视频）与 {@code preserveHeic=false} 的 HEIC 仍走
     * 老侧车规则（{@code <相册目录>/.gallerysync-slim/<stem>.<ext>}，见 {@link #legacyPreviewPathFor}）。
     *
     * <p>判定全部交给 {@link InplaceSlimPolicy} 与 {@link PreviewEncodePolicy}，调用方不要再自己
     * 拼目录或文件名。规则幂等：同一张图片永远得到同一个路径。返回原图路径表示“这个类型就地覆盖”。
     */
    public static File previewPathFor(File original, boolean preserveHeic) {
        String format = InplaceSlimPolicy.targetFormat(original.getName());
        if ("png".equals(format) || "jpg".equals(format)) {
            return InplaceSlimPolicy.targetPath(original);
        }
        if ("heic".equals(format) && preserveHeic) {
            return InplaceSlimPolicy.targetPath(original);
        }
        return legacyPreviewPathFor(original, preserveHeic);
    }

    /**
     * 老侧车规则：隐藏目录里一个与原图同名的文件（HEIC 关掉“保留 HEIC”时扩展名改 {@code .jpg}）。
     *
     * <p><b>只服务历史数据</b>：{@link #migrateLegacyDownload} 用它在老副本的位置上找到那份字节；
     * 新的下载/瘦身逻辑一律走 {@link #previewPathFor}，不得再调用本方法产出新副本。
     */
    static File legacyPreviewPathFor(File original, boolean preserveHeic) {
        String name = original.getName();
        if (!isCompressibleExtension(name)) {
            return original;
        }
        int extension = name.lastIndexOf('.');
        String stem = extension > 0 ? name.substring(0, extension) : name;
        String suffix = isHeicName(name) && preserveHeic ? ".heic" : ".jpg";
        return new File(previewDirectoryFor(original), stem + suffix);
    }

    /**
     * 视频瘦身产出的封面图沿用同一规则：隐藏目录里一个与视频同名的 JPEG。
     * 视频本身不可压缩，所以不走 {@link #previewPathFor}。
     */
    public static File videoPosterPathFor(File original) {
        String name = original.getName();
        int extension = name.lastIndexOf('.');
        String stem = extension > 0 ? name.substring(0, extension) : name;
        return new File(previewDirectoryFor(original), stem + ".jpg");
    }

    /** 相册目录下的隐藏副本目录；副本路径只从这里派生，不再有第二个目录。 */
    static File previewDirectoryFor(File original) {
        File parent = original.getParentFile();
        if (parent == null) {
            parent = original.getAbsoluteFile().getParentFile();
        }
        return new File(parent == null ? new File(".") : parent, SLIM_DIRECTORY);
    }

    /** 旧版本把副本放在这个隐藏目录里；新副本目录名相同，判断"是否副本"只能靠注册表。 */
    static boolean isLegacySlimPath(String path) {
        return path != null && path.replace('\\', '/').contains("/" + SLIM_DIRECTORY + "/");
    }

    private static boolean samePath(File first, File second) {
        try {
            return first.getCanonicalPath().equals(second.getCanonicalPath());
        } catch (IOException ignored) {
            return first.getAbsoluteFile().equals(second.getAbsoluteFile());
        }
    }

    /** 同一个判据的字符串版本：注册表里存的是规范路径，候选落点是 File。 */
    private static boolean samePath(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        try {
            return new File(first).getCanonicalPath().equals(new File(second).getCanonicalPath())
                    || first.equals(second);
        } catch (IOException ignored) {
            return first.equals(second);
        }
    }

    /**
     * 计算出来的副本位置是否已被别的文件占用。已经存在的文件只有在它是这张照片自己
     * 登记过的副本时才不算冲突：那种情况可以直接复用或覆盖，不会留下第二份副本。
     */
    private boolean previewPathOccupiedByAnotherFile(OriginalStore.Record record, File previewDest) {
        return previewDest.exists() && !mOriginalStore.isRegisteredDerivedCopy(record, previewDest);
    }

    /**
     * Album backups carry their original storage-relative path, so a missing local file belongs
     * at that album location. 分流判据本身在 {@link DownloadDestinationPolicy} 里（纯函数、可单测）。
     */
    static File destinationFor(Config config, String relativePath, long remoteSize) {
        return destinationFor(config == null ? null : config.getRemoteLayout(), relativePath,
                remoteSize);
    }

    static File destinationFor(String layout, String relativePath, long remoteSize) {
        return destinationFor(layout, relativePath, remoteSize,
                new File(PRIMARY_STORAGE_ROOT), new File(MIRROR_ROOT));
    }

    static File destinationFor(String layout, String relativePath, long remoteSize,
            File primaryRoot, File mirrorRoot) {
        return destinationFor(layout, relativePath, remoteSize, primaryRoot, mirrorRoot, null);
    }

    /**
     * 计划阶段的纯判据版本：{@code registeredOriginalPath} 是注册表里这一行登记的原路径（可空）。
     *
     * <p>未登记时保持老行为（同名同长才算同一份，否则分流镜像）；一旦登记过，相册路径优先，
     * 长度差异不再把下载赶到镜像目录 —— 那正是历史上产生第二份文件/第二行的根因。
     */
    static File destinationFor(String layout, String relativePath, long remoteSize,
            File primaryRoot, File mirrorRoot, String registeredOriginalPath) {
        File mirror = new File(mirrorRoot, relativePath);
        if (!Config.LAYOUT_ALBUM.equals(layout) || !isSafeRelativePath(relativePath)) {
            return mirror;
        }
        File album = new File(primaryRoot, relativePath);
        boolean sameNameSameLength = album.isFile() && remoteSize >= 0L
                && album.length() == remoteSize;
        return DownloadDestinationPolicy.resolve(album, mirror, sameNameSameLength,
                registeredOriginalPath);
    }

    /**
     * 计划阶段的真实落点：在纯判据之上带上注册表里这一 remote 已登记的本地原路径。
     *
     * <p>已登记的路径一律回流到它。空间优化把原图换成同名预览之后，相册路径上的长度必然短于
     * 远端对象，老判据会把下载改写到 {@code DCIM/MiGallerySync}，于是同一张照片出现第二份文件、
     * 相册多出第二行。现在只要账本认得出这条路径，落点就是它。
     */
    private File downloadDestinationFor(Config config, String remotePath, String relativePath,
            long remoteSize) {
        String layout = config == null ? null : config.getRemoteLayout();
        File mirror = new File(MIRROR_ROOT, relativePath);
        if (!Config.LAYOUT_ALBUM.equals(layout) || !isSafeRelativePath(relativePath)) {
            return mirror;
        }
        File album = new File(PRIMARY_STORAGE_ROOT, relativePath);
        boolean sameNameSameLength = album.isFile() && remoteSize >= 0L
                && album.length() == remoteSize;
        return DownloadDestinationPolicy.resolve(album, mirror, sameNameSameLength,
                registeredOriginalPathFor(config, remotePath, album));
    }

    /** 注册表里这一 remote 的既有原路径；查不到（或压根没登记）返回 null，调用方按“未登记”处理。 */
    private String registeredOriginalPathFor(Config config, String remotePath,
            File albumCandidate) {
        OriginalStore.Record record = mOriginalStore.findByRemotePath(config, remotePath,
                albumCandidate);
        return record == null ? null : record.originalLocalPath;
    }

    /**
     * 发布一份**历史侧车文件**到相册原路径（老版本把预览放在镜像目录/隐藏目录，下载补回时顺手迁移）。
     *
     * <p><b>只服务历史侧车文件</b>：入口只允许计划线程（{@code walk}）在“当前版本的落点为空”时调用。
     * 新的下载落盘逻辑（原地覆盖分支与 {@link #publishDownloadedOriginal}）一律不得调用本方法 ——
     * 它产出的目标姿势是“隐藏目录副本 + 迁移”，而第 4 波起新链路统一到原路径。
     */
    private void migrateLegacyDownload(Config config, String remotePath, String relative,
            File original, int generation) {
        File legacy = new File(MIRROR_ROOT, relative);
        File newPreview = previewPathFor(original, config.isPreserveHeicFormat());
        if (original.exists() || (!samePath(newPreview, original) && newPreview.exists())) return;
        OriginalStore.Record record = mOriginalStore.findByRemotePath(config, remotePath, legacy);
        if (record == null) return;
        File preview = record.previewLocalPath == null ? null : new File(record.previewLocalPath);
        if (preview != null && isHeicName(original.getName())
                && preview.getName().toLowerCase(Locale.US).endsWith(".jpg")) {
            // Legacy JPEG bytes remain JPEG even after the HEIC preference changes.
            newPreview = legacyPreviewPathFor(original, false);
        }
        if (!samePath(newPreview, original) && newPreview.exists()) return;
        File source = preview != null && preview.isFile() ? preview : legacy;
        if (!source.isFile()) return;
        File staged = null;
        try {
            ensureWorkCurrent(config, generation);
            String digest = sha256(source);
            boolean derived = preview != null && source.equals(preview);
            if (derived && !isCompressibleExtension(original.getName())) return;
            Long expected = record.derivedHashes.get(digest);
            if (derived ? expected == null || expected.longValue() != source.length()
                    : source.length() != record.originalLength || !record.sha256.equals(digest)) return;
            File destination = derived ? newPreview : original;
            staged = stageBesideDestination(source, destination);
            if (!digest.equals(sha256(staged))) throw new IOException("legacy copy digest mismatch");
            ensureWorkCurrent(config, generation);
            if (derived) {
                mOriginalStore.registerDownloadedPreview(config, remotePath, record.originalLength,
                        record.sha256, original, destination, staged);
            } else {
                mOriginalStore.registerDownloadedOriginal(config, remotePath, staged,
                        original, record.originalLength, record.sha256);
            }
            ensureWorkCurrent(config, generation);
            publishStagedFile(staged, destination);
            staged = null;
            notifyHostAfterPublish(destination, original);
            Logger.i("event=download_legacy_album_migrated local=" + destination);
        } catch (Throwable t) {
            Logger.w("event=download_legacy_album_migration_failed", t);
        } finally {
            if (staged != null) staged.delete();
        }
    }

    static boolean isSafeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()
                || relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            return false;
        }
        for (String segment : relativePath.replace('\\', '/').split("/", -1)) {
            if (!isSafeSegment(segment)) {
                return false;
            }
        }
        return true;
    }

    /** Detect animated formats by signature/chunk, since the private staging file has no extension. */
    private static boolean isAnimatedImage(File file) throws IOException {
        byte[] header = new byte[12];
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            int count = input.read(header);
            if (count >= 6 && header[0] == 'G' && header[1] == 'I'
                    && header[2] == 'F' && header[3] == '8'
                    && (header[4] == '7' || header[4] == '9') && header[5] == 'a') {
                // GIF may be static, but keeping it raw is the safe choice without frame decoding.
                return true;
            }
            byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
            if (count >= png.length) {
                boolean matchesPng = true;
                for (int i = 0; i < png.length; i++) {
                    matchesPng &= header[i] == png[i];
                }
                if (matchesPng) {
                    return containsAscii(file, "acTL");
                }
            }
            if (count >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F'
                    && header[3] == 'F' && header[8] == 'W' && header[9] == 'E'
                    && header[10] == 'B' && header[11] == 'P') {
                return containsAscii(file, "ANIM") || containsAscii(file, "ANMF");
            }
        }
        return false;
    }

    private static boolean containsAscii(File file, String needle) throws IOException {
        byte[] wanted = needle.getBytes(StandardCharsets.US_ASCII);
        byte[] window = new byte[wanted.length];
        int filled = 0;
        byte[] buffer = new byte[32 * 1024];
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            int count;
            while ((count = in.read(buffer)) != -1) {
                for (int b = 0; b < count; b++) {
                    if (filled < window.length) {
                        window[filled++] = buffer[b];
                        if (filled < window.length) {
                            continue;
                        }
                    } else {
                        System.arraycopy(window, 1, window, 0, window.length - 1);
                        window[window.length - 1] = buffer[b];
                    }
                    boolean match = true;
                    for (int i = 0; i < wanted.length; i++) {
                        if (window[i] != wanted[i]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * JPEG 预览：铺白底把透明压平，再按长边 {@link #PREVIEW_MAX_EDGE} 缩放。
     *
     * <p>解码、降采样与等比缩放都在 {@link #decodeBounded(File)} 里；那份数学与 PNG 分支共用，
     * 不再各写一套（见 {@link PreviewEncodePolicy}）。
     */
    private static boolean makePreview(File original, File output, boolean preserveMetadata) {
        Bitmap oriented = null;
        Bitmap rgb = null;
        try {
            oriented = decodeBounded(original);
            if (oriented == null) {
                return false;
            }
            rgb = Bitmap.createBitmap(oriented.getWidth(), oriented.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(rgb);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(oriented, 0f, 0f, new Paint(Paint.FILTER_BITMAP_FLAG));
            try (FileOutputStream out = new FileOutputStream(output)) {
                if (!writeBitmap(rgb, "jpg", out)) {
                    return false;
                }
                out.flush();
                out.getFD().sync();
            }
            copyCaptureTime(original, output, preserveMetadata);
            return dimensionsWithinLimit(output);
        } catch (Throwable t) {
            Logger.d("event=download_preview_failed reason=" + t);
            output.delete();
            return false;
        } finally {
            recycle(rgb);
            recycle(oriented);
        }
    }

    /**
     * PNG 预览：<b>不铺白底</b>，ARGB_8888 直接无损写回，透明通道原样保留。
     *
     * <p>PNG 里没有 EXIF 可写（也不该为它塞一份伪 EXIF），能做的只有把修改时间搬过来 ——
     * 否则相册会把这批老图排到扫描当天。iCCP 属于可选增强，见
     * {@link #createCompressedPreview(File, File, String, Config)} 里的说明：明确跳过。
     */
    private static boolean makePngPreview(File original, File output) {
        Bitmap oriented = null;
        try {
            oriented = decodeBounded(original);
            if (oriented == null) {
                return false;
            }
            try (FileOutputStream out = new FileOutputStream(output)) {
                if (!writeBitmap(oriented, "png", out)) {
                    return false;
                }
                out.flush();
                out.getFD().sync();
            }
            preservePreviewTime(original, output);
            if (!dimensionsWithinLimit(output)) {
                return false;
            }
            // 落盘后复核：真的是 PNG，且原来带透明通道时 alpha 没被压平
            int colorType = pngColorType(output);
            if (colorType < 0) {
                Logger.w("event=download_preview_png_invalid output=" + output.getName());
                return false;
            }
            if (oriented.hasAlpha() && !PreviewEncodePolicy.pngColorTypeKeepsAlpha(colorType)) {
                Logger.w("event=download_preview_png_alpha_lost colorType=" + colorType);
                return false;
            }
            return true;
        } catch (Throwable t) {
            Logger.d("event=download_preview_failed reason=" + t);
            output.delete();
            return false;
        } finally {
            recycle(oriented);
        }
    }

    /**
     * 把原图解码成“方向已摆正、长边不超过 {@link #PREVIEW_MAX_EDGE}”的 ARGB_8888 位图。
     *
     * <p>降采样倍率与缩放边长都来自 {@link PreviewEncodePolicy}（纯函数、可单测），JPEG 与 PNG
     * 两条编码路径共用同一份数学。返回值由调用方负责 {@link #recycle(Bitmap)}；失败返回 {@code null}。
     */
    private static Bitmap decodeBounded(File original) {
        Bitmap current = null;
        boolean handedOff = false;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(original.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = PreviewEncodePolicy.sampleSize(bounds.outWidth,
                    bounds.outHeight, PREVIEW_MAX_EDGE);
            // 显式要 ARGB_8888：RGBA PNG 的透明通道从解码这一步就得留下
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            current = BitmapFactory.decodeFile(original.getAbsolutePath(), options);
            if (current == null) {
                return null;
            }
            Matrix orientation = orientationMatrix(original);
            if (orientation != null && !orientation.isIdentity()) {
                Bitmap rotated = Bitmap.createBitmap(current, 0, 0, current.getWidth(),
                        current.getHeight(), orientation, true);
                if (rotated == null) {
                    return null;
                }
                if (rotated != current) {
                    current.recycle();
                    current = rotated;
                }
            }
            int width = PreviewEncodePolicy.scaledWidth(current.getWidth(), current.getHeight(),
                    PREVIEW_MAX_EDGE);
            int height = PreviewEncodePolicy.scaledHeight(current.getWidth(), current.getHeight(),
                    PREVIEW_MAX_EDGE);
            if (width != current.getWidth() || height != current.getHeight()) {
                Bitmap resized = Bitmap.createScaledBitmap(current, width, height, true);
                if (resized != null && resized != current) {
                    current.recycle();
                    current = resized;
                }
            }
            Bitmap result = current;
            handedOff = true;
            return result;
        } catch (Throwable t) {
            Logger.d("event=download_preview_decode_failed reason=" + t);
            return null;
        } finally {
            if (!handedOff) {
                recycle(current);
            }
        }
    }

    /**
     * 按策略给出的格式名落盘：{@code png} 走无损（quality 被忽略，固定 100），其余按
     * {@link #PREVIEW_JPEG_QUALITY} 写 JPEG。HEIC 不在这里 —— 它由 HeifWriter 负责。
     */
    private static boolean writeBitmap(Bitmap bitmap, String encodeFormat, OutputStream out) {
        boolean png = PreviewEncodePolicy.keepsAlpha(encodeFormat);
        Bitmap.CompressFormat compressFormat = png
                ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
        return bitmap.compress(compressFormat, png ? 100 : PREVIEW_JPEG_QUALITY, out);
    }

    /**
     * 读 PNG IHDR 里的 color type（签名后第 25 字节）；不是合法 PNG 时返回 {@code -1}。
     *
     * <p>原地覆盖 .png 时容器不能变，透明通道也不能悄悄丢，所以编码完再读一次头部复核。
     */
    private static int pngColorType(File file) {
        byte[] header = new byte[26];
        try (InputStream in = new FileInputStream(file)) {
            int total = 0;
            while (total < header.length) {
                int count = in.read(header, total, header.length - total);
                if (count < 0) break;
                total += count;
            }
            if (total < header.length) return -1;
            // 8 字节签名，随后 4 字节长度 + "IHDR" + 宽(4) + 高(4) + 位深(1) + color type(1)
            if ((header[0] & 0xff) != 0x89 || header[1] != 'P' || header[2] != 'N'
                    || header[3] != 'G' || header[4] != 0x0D || header[5] != 0x0A
                    || header[6] != 0x1A || header[7] != 0x0A) {
                return -1;
            }
            if (header[12] != 'I' || header[13] != 'H' || header[14] != 'D' || header[15] != 'R') {
                return -1;
            }
            return header[25] & 0xff;
        } catch (IOException e) {
            return -1;
        }
    }

    /** 落盘后的宽高必须读得出来，且长边不超过 {@link #PREVIEW_MAX_EDGE}。 */
    private static boolean dimensionsWithinLimit(File file) {
        BitmapFactory.Options verify = new BitmapFactory.Options();
        verify.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), verify);
        return verify.outWidth > 0 && verify.outHeight > 0
                && Math.max(verify.outWidth, verify.outHeight) <= PREVIEW_MAX_EDGE;
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    /** Re-encoding must not move old photos to today's date when the gallery scans the preview. */
    private static void copyCaptureTime(File original, File preview, boolean preserveMetadata)
            throws IOException {
        ExifInterface target = new ExifInterface(preview.getAbsolutePath());
        if (preserveMetadata) {
            ExifInterface source = new ExifInterface(original.getAbsolutePath());
            // Resolve tag names at runtime so a platform that lacks a newer public constant can
            // still preserve the rest of the allowlist. Deliberately omit dimensions, offsets,
            // compression, embedded thumbnails, serial numbers, and the source orientation.
            String[] tagFields = {
                "TAG_GPS_VERSION_ID", "TAG_GPS_LATITUDE", "TAG_GPS_LATITUDE_REF",
                "TAG_GPS_LONGITUDE", "TAG_GPS_LONGITUDE_REF", "TAG_GPS_ALTITUDE",
                "TAG_GPS_ALTITUDE_REF", "TAG_GPS_TIMESTAMP", "TAG_GPS_DATESTAMP",
                "TAG_GPS_PROCESSING_METHOD", "TAG_GPS_SPEED", "TAG_GPS_SPEED_REF",
                "TAG_GPS_TRACK", "TAG_GPS_TRACK_REF", "TAG_GPS_IMG_DIRECTION",
                "TAG_GPS_IMG_DIRECTION_REF", "TAG_GPS_DEST_LATITUDE",
                "TAG_GPS_DEST_LATITUDE_REF", "TAG_GPS_DEST_LONGITUDE",
                "TAG_GPS_DEST_LONGITUDE_REF", "TAG_GPS_DEST_BEARING",
                "TAG_GPS_DEST_BEARING_REF", "TAG_GPS_DEST_DISTANCE",
                "TAG_GPS_DEST_DISTANCE_REF", "TAG_GPS_AREA_INFORMATION",
                "TAG_GPS_DOP", "TAG_GPS_MAP_DATUM", "TAG_GPS_SATELLITES",
                "TAG_GPS_STATUS", "TAG_GPS_MEASURE_MODE", "TAG_GPS_H_POSITIONING_ERROR",
                "TAG_GPS_DIFFERENTIAL", "TAG_MAKE", "TAG_MODEL", "TAG_EXPOSURE_TIME",
                "TAG_F_NUMBER", "TAG_ISO_SPEED_RATINGS", "TAG_PHOTOGRAPHIC_SENSITIVITY",
                "TAG_EXPOSURE_PROGRAM", "TAG_EXPOSURE_BIAS_VALUE", "TAG_METERING_MODE",
                "TAG_APERTURE_VALUE", "TAG_MAX_APERTURE_VALUE", "TAG_FOCAL_LENGTH",
                "TAG_FLASH", "TAG_WHITE_BALANCE", "TAG_LIGHT_SOURCE", "TAG_ARTIST",
                "TAG_COPYRIGHT", "TAG_DATETIME", "TAG_DATETIME_ORIGINAL",
                "TAG_DATETIME_DIGITIZED", "TAG_SUBSEC_TIME", "TAG_SUBSEC_TIME_ORIGINAL",
                "TAG_SUBSEC_TIME_DIGITIZED", "TAG_OFFSET_TIME", "TAG_OFFSET_TIME_ORIGINAL",
                "TAG_OFFSET_TIME_DIGITIZED", "TAG_LENS_MAKE", "TAG_LENS_MODEL",
                "TAG_LENS_SPECIFICATION", "TAG_FOCAL_LENGTH_IN_35MM_FILM", "TAG_USER_COMMENT",
                "TAG_IMAGE_DESCRIPTION", "TAG_SOFTWARE"
            };
            for (String fieldName : tagFields) {
                String tag;
                try {
                    Object value = ExifInterface.class.getField(fieldName).get(null);
                    if (!(value instanceof String)) continue;
                    tag = (String) value;
                } catch (NoSuchFieldException | IllegalAccessException ignored) {
                    continue;
                }
                String value = source.getAttribute(tag);
                String rawRational = HeicPreviewEncoder.rawUnsignedRational(source, tag);
                if (rawRational != null) value = rawRational;
                if (value != null) target.setAttribute(tag, value);
            }
        }
        // The JPEG pixels have already been rotated/flipped; never copy the old orientation.
        target.setAttribute(ExifInterface.TAG_ORIENTATION,
                String.valueOf(ExifInterface.ORIENTATION_NORMAL));
        // A failed EXIF write aborts preview creation, keeping the original in place.
        target.saveAttributes();
        preservePreviewTime(original, preview);
    }

    /**
     * Re-encoding must not move old photos to today's date when the gallery scans the preview.
     *
     * <p>JPEG/HEIC 预览把它写在 EXIF 之后，PNG 预览没有 EXIF 可写，只剩这一步 —— 所以单独抽出来，
     * 两条路的行为保持一致（失败就中止本次预览，原图不动）。
     */
    private static void preservePreviewTime(File original, File preview) throws IOException {
        if (original.lastModified() > 0
                && !preview.setLastModified(original.lastModified())) {
            throw new IOException("cannot preserve preview file modification time");
        }
    }

    /**
     * Create a JPEG preview in a caller-selected directory without publishing it to MediaStore.
     * Returns false for animated or unsupported image data; callers should retain the original.
     */
    public static boolean createCompressedPreview(File original, File output) {
        return createCompressedPreview(original, output, true);
    }

    /**
     * Apply the same format and metadata policy to downloads and storage optimization.
     *
     * <p>格式名由 {@link PreviewEncodePolicy} 决定（纯函数）：{@code png} 容器必须写回 PNG
     * （无损、保留 alpha），{@code heic} 容器按“保留 HEIC”开关写 HEIC 或 JPEG，其余落 JPEG。
     */
    public static boolean createCompressedPreview(File original, File output,
            String originalName, Config config) {
        boolean metadata = config == null || config.isPreserveMetadata();
        boolean preserveHeic = config == null || config.isPreserveHeicFormat();
        String format = PreviewEncodePolicy.encodeFormat(
                InplaceSlimPolicy.targetFormat(originalName), preserveHeic);
        boolean encoded;
        if ("png".equals(format)) {
            // PNG 没有 EXIF 可写；iCCP 要自己拼 chunk（不引新依赖就做不完整），所以这里明确跳过：
            // 预览像素已按解码结果输出，缺一个 sRGB profile 只影响极端色彩管理场景，不影响相册显示。
            encoded = createPngPreview(original, output);
            if (encoded) {
                Logger.i("event=preview_icc_skipped reason=png_iccp_unsupported format=png");
            }
        } else if ("heic".equals(format)) {
            encoded = HeicPreviewEncoder.encode(original, output, PREVIEW_MAX_EDGE,
                    PREVIEW_JPEG_QUALITY, metadata);
        } else {
            encoded = createCompressedPreview(original, output, metadata);
        }
        if (encoded && metadata && !"png".equals(format)) {
            embedPreviewColorProfile(original, output, "heic".equals(format));
        }
        return encoded;
    }

    /**
     * Carry the source ICC into the preview. The preview pixels are already converted to sRGB, so
     * only an sRGB-shaped profile may be attached; anything else would tint the preview. Embedding
     * is best effort and never changes the result of preview creation.
     */
    private static void embedPreviewColorProfile(File original, File output, boolean heif) {
        String format = heif ? "heif" : "jpeg";
        try {
            byte[] icc = IccProfile.extract(original);
            if (icc == null) {
                return;
            }
            if (!IccProfile.isSrgbCompatible(icc)) {
                Logger.i("event=preview_icc_skipped reason=non_srgb");
                return;
            }
            boolean injected = heif
                    ? IccInjector.injectHeif(output, icc)
                    : IccInjector.injectJpeg(output, icc);
            if (injected) {
                Logger.i("event=preview_icc_embedded format=" + format + " bytes=" + icc.length);
            } else {
                Logger.w("event=preview_icc_inject_failed format=" + format);
            }
        } catch (Throwable t) {
            Logger.w("event=preview_icc_inject_failed format=" + format, t);
        }
    }

    /** Create a JPEG preview with optional allowlisted EXIF copying. */
    public static boolean createCompressedPreview(File original, File output,
            boolean preserveMetadata) {
        if (original == null || output == null || !original.isFile()) {
            return false;
        }
        try {
            if (isAnimatedImage(original)) {
                return false;
            }
            File parent = output.getAbsoluteFile().getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
                return false;
            }
            return makePreview(original, output, preserveMetadata);
        } catch (Throwable t) {
            Logger.d("event=download_preview_failed reason=" + t);
            output.delete();
            return false;
        }
    }

    /**
     * Create a lossless PNG preview in a caller-selected path (原地覆盖 PNG 时用).
     *
     * <p>与 JPEG 入口共用同一批前置检查（存在、非动图、父目录可写），只换编码器：
     * 输出必须还是 PNG，否则“原地覆盖 .png”的容器约定就破了。
     */
    private static boolean createPngPreview(File original, File output) {
        if (original == null || output == null || !original.isFile()) {
            return false;
        }
        try {
            if (isAnimatedImage(original)) {
                return false;
            }
            File parent = output.getAbsoluteFile().getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs()
                    && !parent.isDirectory())) {
                return false;
            }
            return makePngPreview(original, output);
        } catch (Throwable t) {
            Logger.d("event=download_preview_failed reason=" + t);
            output.delete();
            return false;
        }
    }

    private static Matrix orientationMatrix(File file) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                    matrix.setScale(-1f, 1f);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    matrix.setRotate(180f);
                    break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    matrix.setScale(1f, -1f);
                    break;
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    matrix.setRotate(90f);
                    matrix.postScale(-1f, 1f);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_90:
                    matrix.setRotate(90f);
                    break;
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    matrix.setRotate(270f);
                    matrix.postScale(-1f, 1f);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    matrix.setRotate(270f);
                    break;
                default:
                    return null;
            }
            return matrix;
        } catch (Throwable ignored) {
            // A missing or malformed EXIF block means the encoded pixels already define orientation.
            return null;
        }
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest.digest()) {
                out.append(String.format(Locale.US, "%02x", value & 0xff));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by Android", e);
        }
    }

    private void fail(Pending pending, String reason) {
        int count;
        synchronized (mLock) {
            Integer previous = mFailures.get(pending.remotePath);
            count = (previous == null ? 0 : previous) + 1;
            mFailures.put(pending.remotePath, count);
            mFailureTimes.put(pending.remotePath, SystemClock.elapsedRealtime());
        }
        Logger.w("event=download_retry remote=" + pending.remotePath + " attempts=" + count
                + " reason=" + reason);
    }

    private void markDone(String remotePath, String target) {
        synchronized (mLock) {
            if (!target.equals(mDownloadTarget)) {
                return;
            }
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

    static boolean isGalleryMedia(String name) {
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        switch (name.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "jpg": case "jpeg": case "png": case "gif": case "webp":
            case "bmp": case "heic": case "heif": case "avif": case "dng":
            case "mp4": case "m4v": case "mov": case "3gp": case "3gpp":
            case "mkv": case "webm": case "avi": case "mpeg": case "mpg":
                return true;
            default: return false;
        }
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
        return pending.localPath;
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
            StatFs stat = new StatFs(PRIMARY_STORAGE_ROOT);
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
            root.put("dl_target", mDownloadTarget);
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
            if (done == null || !mDownloadTarget.equals(root.optString("dl_target", ""))) {
                Logger.i("event=download_state_target_changed old="
                        + root.optString("dl_target", "<legacy>") + " new=" + mDownloadTarget);
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
