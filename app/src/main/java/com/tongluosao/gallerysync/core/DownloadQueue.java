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
import java.util.HashMap;
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
    /**
     * 每个远端路径最近一次失败的**种类**，与 {@link #mFailures} 同步维护。
     *
     * <p>决定冷却长短：可重试从 60s 起步，终态从 30min 起步，两者都只随次数增长、
     * <b>不再清零</b>（见 {@link DownloadFailurePolicy}）。旧实现冷却一过就把次数删掉，
     * 于是"每次返回坏字节"这类永不痊愈的失败会每分钟重下一整份。
     */
    private final LinkedHashMap<String, DownloadFailurePolicy.Kind> mFailureKinds =
            new LinkedHashMap<>();
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
    /**
     * 自动下载专用 worker，路数由 {@link Config#KEY_DOWNLOAD_CONCURRENCY} 决定。
     *
     * <p>为什么不直接加在 {@link #THREADS} 上：那两路是**补齐**下载（远端枚举出来的项），
     * 它们的数量是"远端枚举多快"的配平；自动下载是"注册表里几千条 REMOTE_ONLY 一条条
     * 握手取回"的另一条链路，瓶颈在每对象的握手与排队，两者共用一个数字只会让一边
     * 把另一边饿死。这里各走各的通道，唯一共享的是 {@link #mLock} 保护的队列与账本。
     */
    private final Thread[] mAutoWorkers = new Thread[Config.MAX_DOWNLOAD_CONCURRENCY];
    /** 当前生效的自动下载路数；调小时旧线程按“下标越界”自己退出 */
    private volatile int mAutoConcurrency;
    /** 路数改动的互斥：setConfig 与构造器都可能调用，不能同时开同一个下标 */
    private final Object mAutoGate = new Object();
    /**
     * 每路一条连接：{@code [0, THREADS)} 给通用 worker，其后是自动下载的每一路。
     *
     * <p>绝不能共用：两个线程同时读同一个响应体必然串包（这是"列表与下载不能共用
     * 一条连接"那条注释的同一个道理，只是换成了 worker 之间）。
     */
    private final WebDavClient[] mClients =
            new WebDavClient[THREADS + Config.MAX_DOWNLOAD_CONCURRENCY];
    private final String[] mClientTargets =
            new String[THREADS + Config.MAX_DOWNLOAD_CONCURRENCY];
    /**
     * 本轮已知的"远端对象在不在"：目录枚举名单 + 5 分钟内的逐条结论。
     * 用它替掉自动下载里逐条的 {@code propfindSize}，见 {@link DownloadProbeCache}。
     */
    private final DownloadProbeCache mProbeCache = new DownloadProbeCache();

    /** 停滞看门狗的巡检间隔：每 5s 看一次"最后收到字节到现在多久了" */
    private static final long WATCHDOG_INTERVAL_MS = 5_000L;
    /**
     * 正在传输的远端路径 → 最后一次收到字节的时刻（{@code elapsedRealtime}）。
     *
     * <p>为什么要单独记账：连接卡死时读循环一个字节都不会来，进度回调永远不会触发，
     * worker 只能等 socket 自己超时（{@code READ_TIMEOUT_MAX} 是分钟级）。有了这张表，
     * 看门狗可以在 {@link DownloadProgressPolicy#STALL_MS} 之后主动关掉那条连接。
     */
    private final Map<String, Long> mLastByteAt = new HashMap<>();
    /** 正在传输的远端路径 → 承载它的连接，停滞时由看门狗关闭 */
    private final Map<String, WebDavClient> mActiveClients = new HashMap<>();
    /**
     * 被看门狗判定停滞、主动关掉连接的远端路径。
     *
     * <p>连接一关，读循环抛出来的只是 {@code SocketException: Socket closed}，从异常本身分不出
     * "服务器卡住"和"网线掉了"。这条标记让失败分类能落到 {@code stalled}，从而按
     * {@link DownloadFailurePolicy#attemptCost} 记满额、下一次尝试直接进冷却，而不是立刻重连、
     * 再白等 60 秒。
     */
    private final java.util.Set<String> mStalledPaths = new java.util.LinkedHashSet<>();
    private Thread mWatchdog;

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
        /**
         * 由注册表 {@code REMOTE_ONLY} 行排进来的下载：落地后要把该行升级成 ORIGINAL，
         * 并把相册 cloud 表那一行的 localFile 指回原路径（而不是让扫描器再建一行）。
         */
        final boolean autoDownload;
        /** 接管登记的相册行 id / sha1，仅 autoDownload 时非空（可能仍为 null） */
        final String rowId;
        final String sha1;

        Pending(String remotePath, String relativePath, long size, String target,
                String localPath) {
            this(remotePath, relativePath, size, target, localPath, false, null, null);
        }

        Pending(String remotePath, String relativePath, long size, String target,
                String localPath, boolean autoDownload, String rowId, String sha1) {
            this.remotePath = remotePath;
            this.relativePath = relativePath;
            this.size = size;
            this.target = target;
            this.localPath = localPath;
            this.autoDownload = autoDownload;
            this.rowId = rowId;
            this.sha1 = sha1;
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
        mWatchdog = new Thread(this::watchdogLoop, "MiGallerySync-download-watchdog");
        mWatchdog.setDaemon(true);
        mWatchdog.start();
        for (int i = 0; i < THREADS; i++) {
            Thread thread = new Thread(this::workerLoop, "MiGallerySync-download-" + i);
            thread.setDaemon(true);
            mWorkers[i] = thread;
            thread.start();
        }
        applyAutoConcurrency(config == null
                ? Config.DEFAULT_DOWNLOAD_CONCURRENCY : config.getDownloadConcurrency());
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
            // 换目标之后旧的目录名单全部作废：那是另一个远端根的结论
            mProbeCache.clearListings();
        }
        if (config != null) {
            applyAutoConcurrency(config.getDownloadConcurrency());
        }
    }

    /**
     * 按配置开/收自动下载的路数。
     *
     * <p>只补开、不掐断：调小时不动正在下载的那几路，让下标越界的线程下次醒来自己退出
     * （{@link #autoWorkerLoop} 的循环条件就是 {@code index < mAutoConcurrency}）——
     * 中途把一条正在下文件的连接关掉，会把那一项白白记成一次失败。
     *
     * <p>线程对象只在**死了**的时候才换新的：{@link Thread#isAlive()} 为 false 说明这一路
     * 已经按收缩退出了，再调大时必须补开，否则那条通道就永久消失了。
     */
    private void applyAutoConcurrency(int requested) {
        int target = Config.clampDownloadConcurrency(requested);
        int previous;
        synchronized (mAutoGate) {
            previous = mAutoConcurrency;
            if (target == previous) {
                return;
            }
            mAutoConcurrency = target;
            for (int index : lanesToStart(target, mAutoWorkers)) {
                Thread worker = new Thread(() -> autoWorkerLoop(index),
                        "MiGallerySync-autodl-" + index);
                // daemon：下载线程绝不能拖住相册进程不让它退出
                worker.setDaemon(true);
                mAutoWorkers[index] = worker;
                worker.start();
            }
        }
        Logger.i("event=auto_download_concurrency n=" + target + " previous=" + previous);
        synchronized (mLock) {
            // 调大之后新开的那几路要立刻能领到已经在排队的项
            mLock.notifyAll();
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
        if (failures == null || failures <= 0) return false;
        DownloadFailurePolicy.Kind kind = mFailureKinds.get(remotePath);
        Long failedAt = mFailureTimes.get(remotePath);
        return DownloadFailurePolicy.blocked(kind, failures,
                failedAt == null ? 0L : failedAt, SystemClock.elapsedRealtime(),
                MAX_FAIL_PER_PATH);
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
                //
                // 注册表驱动的"补原图"先单独排一轮：它只读本地注册表，不依赖远端枚举，
                // 所以远端树再大、单次 PROPFIND 再慢，也不会把这条链路一起拖住。
                try {
                    List<Pending> auto = new ArrayList<>();
                    // 目录名单只活在"这一轮"里：跨轮复用等于拿几分钟前（甚至几小时前）
                    // 的目录快照判断远端有没有，删掉的照片会被反复判成还在
                    mProbeCache.clearListings();
                    planAutoDownloads(config, generation, auto, "registry");
                    if (!auto.isEmpty()) {
                        enqueue(auto, generation);
                    }
                } catch (Throwable t) {
                    Logger.w("event=auto_download_plan_failed phase=registry detail=" + t);
                }
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
                // 第二遍注册表计划：这时目录名单已经被上面的枚举填满，注册表行的
                // "远端在不在"可以直接命中探测缓存。第一遍跑在枚举之前（那时一个名单都
                // 没有，hit 恒为 0，真机日志里就是 probe_cache hit=0 miss=30），
                // 所以两遍都要跑：第一遍抢延迟，第二遍拿命中率。
                try {
                    if (isWorkCurrent(config, generation)) {
                        List<Pending> afterWalk = new ArrayList<>();
                        planAutoDownloads(config, generation, afterWalk, "after_walk");
                        if (!afterWalk.isEmpty()) {
                            enqueue(afterWalk, generation);
                        }
                    }
                } catch (Throwable t) {
                    Logger.w("event=auto_download_plan_failed phase=after_walk detail=" + t);
                }
                // 这一轮自动下载的探测缓存收尾：hit = 因为"本轮已经知道答案"而省掉的握手次数，
                // miss = 仍然得自己去探的次数，listings = 本轮枚举过并留下的目录名单条数。
                // 打在这里（走完枚举之后）才有意义：计划侧跑在枚举之前，那时还一个名单都没有。
                // 取走即清零，下一轮从 0 重新算。
                Logger.i("event=auto_download_probe_cache " + mProbeCache.takeStatsLine()
                        + " listings=" + mProbeCache.listingCount());
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
        return enqueue(found, generation);
    }

    /** @return 真正进了队列的条数（已排过/已落地的会被跳过） */
    private int enqueue(List<Pending> found, int generation) {
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
        // 这份名单是**完整**的（list() 顶到额度就返回 null，不会给半份），所以名字不在里面
        // 就是"远端确实没有"。顺手喂给探测缓存：本轮自动下载里凡是落在这个目录的项，
        // 都能凭名字与长度直接判在不在，一次 PROPFIND 替掉目录内逐文件各一次。
        LinkedHashMap<String, Long> listedFiles = new LinkedHashMap<>();
        for (WebDavClient.RemoteEntry entry : entries) {
            if (!entry.dir && entry.name != null) {
                listedFiles.put(entry.name, entry.size);
            }
        }
        mProbeCache.putListing(remoteDir, listedFiles);
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
            // 高清/原图请求遇到"本地只有我们自己写的预览"时，仍然要排 —— 否则
            // publishPreviewInPlace 与 publishDownloadedOriginal 里那两条"顶掉旧预览"的
            // 分支永远走不到（计划期就被 localExists 挡掉了）。
            boolean localIsRegisteredDerivedCopy = highQuality && normalLocal.isFile()
                    && isRegisteredDerivedCopyFor(remotePath, config, normalLocal);
            if (!shouldPlanDownload(scheduled, done, restoreMissingOriginal, normalLocal.exists(),
                    hostRows.knows(normalLocal), localIsRegisteredDerivedCopy)) {
                continue;
            }
            out.add(new Pending(remotePath, relative, entry.size, target,
                    normalLocal.getAbsolutePath()));
        }
    }

    /**
     * 按注册表把 {@code REMOTE_ONLY} 的行排进下载队列。
     *
     * <p>平板 HyperOS 1 上"接管"登记的行 {@code localFile} 是空的，{@link #walk} 的
     * "宿主还知道这个路径"判据永远不成立，于是原图虽然只在我们的 WebDAV 上却一张都下不回来。
     * 这里直接用注册表里已知的 {@code remotePath} + {@code originalLength} 排任务，不再等
     * 远端目录枚举（接管已经证明过远端对象存在且长度一致）。
     *
     * <p>安全条件全部在 {@link AutoDownloadPolicy} 里（纯函数、可单测），这里只负责查状态、
     * 记账与写日志。判据按条数汇总，不逐行刷屏：1.2 万条里绝大多数是"已经不是 REMOTE_ONLY"。
     *
     * @param out 与本轮远端枚举共用的待下载列表，总数同样不得越过 {@link #MAX_DOWNLOADS_PER_ROUND}
     * @param phase 这一遍跑在枚举之前（{@code registry}）还是之后（{@code after_walk}），只进日志
     */
    private void planAutoDownloads(Config config, int generation, List<Pending> out, String phase) {
        if (!config.isAutoDownload()) {
            Logger.i("event=auto_download_skip reason=disabled");
            return;
        }
        // 这条链路只走 Wi-Fi/以太网：与"仅 Wi-Fi 上传"同一个判定，但不看 wifiOnly 开关 ——
        // 1.2 万张原图自动回填绝不能默认烧蜂窝流量。
        boolean wifi = NetGuard.canUpload(mContext, true);
        if (!wifi) {
            Logger.i("event=auto_download_skip reason=wifi_only");
            return;
        }
        String layout = config.getRemoteLayout();
        if (!Config.LAYOUT_ALBUM.equals(layout)) {
            Logger.i("event=auto_download_skip reason=layout layout=" + layout);
            return;
        }
        List<OriginalStore.Record> records = mOriginalStore.recordsForTarget(config);
        if (records.isEmpty()) {
            Logger.d("event=auto_download_skip reason=empty_registry");
            return;
        }
        String target = OriginalStore.targetKey(config);
        File primaryRoot = new File(PRIMARY_STORAGE_ROOT);
        Set<String> queued = new HashSet<>();
        for (Pending pending : out) {
            queued.add(pending.remotePath);
        }
        Map<AutoDownloadPolicy.Decision, Integer> skipped = new LinkedHashMap<>();
        int planned = 0;
        int unmapped = 0;
        int suppressed = 0;
        for (OriginalStore.Record record : records) {
            if (!isPolicyCurrent(generation) || planned >= MAX_DOWNLOADS_PER_ROUND) {
                // 本轮额度用完就停：剩下的下个相册事件再说，不能一次把 1.2 万条都过一遍
                break;
            }
            if (!mayDownload(mRemoteSuppress, config, record.remotePath)) {
                // 用户主动删掉的副本：远端还在，但它已经不是"缺失的照片"。计划期就拦住 ——
                // 否则每轮都会排进队列、worker 里再跳过一次，白占 200 条配额还刷日志。
                suppressed++;
                continue;
            }
            File albumDestination = AutoDownloadPolicy.targetPath(layout, config.getRemoteRoot(),
                    record.remotePath, primaryRoot);
            File destination = albumDestination == null ? null
                    : registryAwareDestination(config, layout, record, albumDestination);
            if (destination == null) {
                unmapped++;
                continue;
            }
            // 相册路径上那份同名文件如果是本记录登记过的副本（原地瘦身的预览），
            // 就不能算"本地已经有原图"：正确动作是把原图写回去顶掉它。
            boolean derived = albumDestination != null && albumDestination.isFile()
                    && mOriginalStore.isRegisteredDerivedPath(record, albumDestination);
            boolean exists = destination.isFile() && !derived;
            long existingLength = exists ? destination.length() : -1L;
            boolean scheduled;
            boolean done;
            int failures;
            synchronized (mLock) {
                scheduled = isScheduledLocked(record.remotePath);
                done = mDone.containsKey(record.remotePath);
                Integer count = mFailures.get(record.remotePath);
                failures = count == null ? 0 : count;
            }
            AutoDownloadPolicy.Decision decision = AutoDownloadPolicy.decide(
                    true, wifi, true,
                    record.getLocalState() == OriginalStore.LocalState.REMOTE_ONLY,
                    scheduled, done, exists, existingLength, record.originalLength,
                    failures, MAX_FAIL_PER_PATH, planned, MAX_DOWNLOADS_PER_ROUND);
            if (decision == AutoDownloadPolicy.Decision.PLAN) {
                if (!queued.add(record.remotePath)) {
                    continue;
                }
                out.add(new Pending(record.remotePath,
                        AutoDownloadPolicy.relativePath(config.getRemoteRoot(), record.remotePath),
                        record.originalLength, target, destination.getAbsolutePath(), true,
                        record.rowId, record.sha1));
                planned++;
            } else if (decision == AutoDownloadPolicy.Decision.PROMOTE_EXISTING) {
                // 目标文件已经是同长度：说明字节早就在了，登记成 ORIGINAL 即可，不重复下载。
                if (promoteExistingOriginal(config, record, destination)) {
                    planned++;
                    Logger.i("event=auto_download_done row=" + row(record)
                            + " path=" + destination.getAbsolutePath()
                            + " bytes=" + destination.length() + " source=existing");
                }
            } else {
                Integer count = skipped.get(decision);
                skipped.put(decision, count == null ? 1 : count + 1);
            }
        }
        if (unmapped > 0) {
            Logger.w("event=auto_download_skip reason=unmapped_remote count=" + unmapped
                    + " phase=" + phase);
        }
        if (suppressed > 0) {
            // 用户删掉的副本：远端还在，但账本已经写过"别再下回来"。这里只报条数。
            Logger.i("event=auto_download_skip reason=suppressed count=" + suppressed
                    + " phase=" + phase);
        }
        if (planned > 0) {
            Logger.i("event=auto_download_planned count=" + planned
                    + " phase=" + phase + " queued=" + out.size()
                    + " catalog=" + records.size());
            // 这批任务会被几路自动下载并行执行、以及本轮总共排了多少：
            // 单看"下了几张"分不清是路数没生效还是服务端慢，这两个数字能分开
            Logger.i("event=auto_download_batch planned=" + planned
                    + " parallel=" + mAutoConcurrency + " phase=" + phase
                    + " queued=" + out.size() + " catalog=" + records.size());
        }
        for (Map.Entry<AutoDownloadPolicy.Decision, Integer> entry : skipped.entrySet()) {
            String reason = autoDownloadReason(entry.getKey());
            String line = "event=auto_download_skip reason=" + reason + " count=" + entry.getValue();
            if (entry.getKey() == AutoDownloadPolicy.Decision.SKIP_NOT_REMOTE_ONLY) {
                // 稳态：整库都是 ORIGINAL 时这一条每轮都会出现，压到 debug
                Logger.d(line);
            } else {
                Logger.i(line);
            }
        }
    }

    /** 日志里的行标识：优先相册行号，其次 sha1（接管行两者都可能缺，缺了写 unknown）。 */
    private static String row(OriginalStore.Record record) {
        if (record == null) {
            return "unknown";
        }
        if (record.rowId != null && !record.rowId.isEmpty()) {
            return record.rowId;
        }
        return record.sha1 == null ? "unknown" : record.sha1;
    }

    /** 与 {@link #row(OriginalStore.Record)} 同一口径，给进度/日志用的待下载项版本。 */
    private static String rowLabel(Pending pending) {
        if (pending == null) {
            return "unknown";
        }
        if (pending.rowId != null && !pending.rowId.isEmpty()) {
            return pending.rowId;
        }
        return pending.sha1 == null || pending.sha1.isEmpty() ? "unknown" : pending.sha1;
    }

    /**
     * 把"本地已经有同长度原图"的 {@code REMOTE_ONLY} 行直接升级成 ORIGINAL。
     *
     * <p>摘要在这里算：这一步每个文件一辈子只发生一次（升级后状态就不是 REMOTE_ONLY 了），
     * 而上传侧只有拿到真实摘要才敢把这份文件当"已备份的原图"。
     */
    private boolean promoteExistingOriginal(Config config, OriginalStore.Record record,
            File destination) {
        String digest = null;
        try {
            digest = sha256(destination);
        } catch (IOException e) {
            Logger.d("event=auto_download_hash_failed path=" + destination + " reason=" + e);
        }
        return mOriginalStore.markAutoDownloaded(config, record, destination, digest) != null;
    }

    /** 纯字符串映射，日志里的 reason 必须一眼能认出来。 */
    private static String autoDownloadReason(AutoDownloadPolicy.Decision decision) {
        switch (decision) {
            case SKIP_DISABLED: return "disabled";
            case SKIP_NOT_WIFI: return "wifi_only";
            case SKIP_LAYOUT: return "layout";
            case SKIP_NOT_REMOTE_ONLY: return "not_remote_only";
            case SKIP_ALREADY_SCHEDULED: return "already_scheduled";
            case SKIP_ALREADY_DONE: return "already_done";
            case SKIP_FAILURE_LIMIT: return "failure_limit";
            case SKIP_ROUND_LIMIT: return "round_limit";
            case SKIP_EXISTS_DIFFERENT: return "exists_different_length";
            case SKIP_BAD_LENGTH: return "bad_length";
            default: return "unknown";
        }
    }

    /**
     * 目标路数之下，真正需要**补开**的通道下标。
     *
     * <p>抽成纯函数是为了能直接单测：判据只有两条 —— 目标路数之内的每一路，
     * 线程还不存在（第一次开）或已经不活了（之前收缩时退出了）就要开；已在跑的不动，
     * 否则每次改设置都会把进行中的下载掐断，白记一次失败。
     *
     * @param target  期望的路数（调用方已夹到 1..{@link Config#MAX_DOWNLOAD_CONCURRENCY}）
     * @param workers 逐路线程表，长度不小于 {@code target}；元素可为 null
     */
    static int[] lanesToStart(int target, Thread[] workers) {
        if (target <= 0 || workers == null) {
            return new int[0];
        }
        int limit = Math.min(target, workers.length);
        int[] wanted = new int[limit];
        int count = 0;
        for (int i = 0; i < limit; i++) {
            Thread worker = workers[i];
            if (worker == null || !worker.isAlive()) {
                wanted[count++] = i;
            }
        }
        int[] out = new int[count];
        System.arraycopy(wanted, 0, out, 0, count);
        return out;
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
        return shouldPlanDownload(scheduled, done, restoreMissingOriginal, localExists,
                hostKnowsLocalPath, false);
    }

    /**
     * 同上，并额外承认"本地这份字节是本记录自己登记过的副本"。
     *
     * <p>为什么需要这一条：原地瘦身把原图换成同名预览之后，{@code localExists=true} 会把
     * 整条链路挡在计划之外 —— 用户点"下载原图"也永远不会触发替换。可那份文件是我们自己
     * 写的预览，正确动作恰恰是"下回整份原图、顶掉预览"。判据由调用方按注册表给出
     * （{@code isRegisteredDerivedPath}，纯路径比较，不做哈希），用户文件永远不满足。
     */
    static boolean shouldPlanDownload(boolean scheduled, boolean done,
            boolean restoreMissingOriginal, boolean localExists, boolean hostKnowsLocalPath,
            boolean localIsRegisteredDerivedCopy) {
        if (scheduled) {
            return false;
        }
        if (done && !restoreMissingOriginal && !localIsRegisteredDerivedCopy) {
            return false;
        }
        if (localExists && !restoreMissingOriginal && !localIsRegisteredDerivedCopy) {
            return false;
        }
        return restoreMissingOriginal || localIsRegisteredDerivedCopy || hostKnowsLocalPath;
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
            // 自动下载的项归 auto 那几路领：通用两路的数量是给"远端枚举出来的补齐项"配的，
            // 让它们也抢自动项的话，几千条 REMOTE_ONLY 会把补齐项永远压在队尾
            if (pending.autoDownload) {
                continue;
            }
            Pending claimed = claimLocked(it, pending);
            if (claimed != null) {
                return claimed;
            }
        }
        return null;
    }

    /**
     * 领取一项自动下载：只认 {@code autoDownload} 的项。
     *
     * <p>与 {@link #takeLocked()} 共用 {@link #claimLocked} 的判据，所以"同一 remotePath
     * 不重复下载""失败上限""超限项自动出队"这三条对两条链路完全一致 ——
     * 领走的一刻就把路径记进 {@link #mInflight}，别的路再也领不到同一项。
     */
    private Pending takeAutoLocked() {
        Iterator<Pending> it = mPending.iterator();
        while (it.hasNext()) {
            Pending pending = it.next();
            if (!pending.autoDownload) {
                continue;
            }
            Pending claimed = claimLocked(it, pending);
            if (claimed != null) {
                return claimed;
            }
        }
        return null;
    }

    /**
     * 试着领走一项；领不到返回 null（正在别处下载 / 已达失败上限）。
     *
     * @return 领到的那一项（调用方负责 {@code download(...)}）；超限项在这里顺手移出队列
     */
    private Pending claimLocked(Iterator<Pending> it, Pending pending) {
        if (mInflight.contains(pending.remotePath)) {
            return null;
        }
        Integer failures = mFailures.get(pending.remotePath);
        if (failures != null && failures >= MAX_FAIL_PER_PATH) {
            it.remove();
            mPendingVersion++;
            Logger.w("event=download_give_up remote=" + pending.remotePath
                    + " attempts=" + failures);
            return null;
        }
        mInflight.add(pending.remotePath);
        return pending;
    }

    /**
     * 自动下载的一路 worker。
     *
     * <p>与 {@link #workerLoop()} 只差三处：
     * <ol>
     *   <li>只领 {@code autoDownload} 的项（通用 worker 反过来只领普通项，两边不交叉）；</li>
     *   <li>网络闸门固定用 {@code NetGuard.canUpload(mContext, true)}：这条链路**永远**只走
     *       Wi-Fi/以太网，与 {@link #planAutoDownloads} 的判据同口径。用
     *       {@code config.isWifiOnly()} 的话，用户关掉"仅 Wi-Fi 上传"时自动下载会在蜂窝上
     *       被领走、再在 {@code download} 里取消，白转一圈；</li>
     *   <li>路数可变：{@code index} 越界就退出（{@link #applyAutoConcurrency} 调小时靠这个收线程）。</li>
     * </ol>
     */
    private void autoWorkerLoop(int index) {
        while (mRunning && index < mAutoConcurrency) {
            Config config = mConfig;
            if (!refreshPolicyState() || config == null || !config.isReady()
                    || !NetGuard.canUpload(mContext, true)) {
                waitForSignal();
                continue;
            }
            Pending next = null;
            int generation;
            synchronized (mLock) {
                while (mRunning && next == null && index < mAutoConcurrency) {
                    next = takeAutoLocked();
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
            if (next != null) {
                download(next, config, generation);
            }
        }
        Logger.d("event=auto_download_worker_exit index=" + index
                + " concurrency=" + mAutoConcurrency);
    }

    private void download(Pending pending, Config config, int generation) {
        int index = workerIndex();
        UploadProgressPublisher publisher = mPublisher;
        long size = Math.max(pending.size, 0L);
        File normalDest = new File(pending.localPath);
        File dest = normalDest;
        String key = key(pending);
        boolean ok = false;
        /** 失败原因：只用于自动下载的终态日志（成功/取消时保持 null） */
        String failureReason = null;
        /** 这一项是否已经按原地模式提交（预览就在原路径上，DB 行也已经精确改过） */
        boolean inplaceCommitted = false;
        if (publisher != null) {
            publisher.taskStart(key, baseName(pending.relativePath), normalDest.getAbsolutePath(),
                    size, false, ProgressProtocol.DIR_DOWN);
            if (pending.autoDownload) {
                // 自动下载的每条都在进度页占一行（DIR_DOWN），与手动下载同一套字段
                Logger.d("event=auto_download_progress_publish row=" + rowLabel(pending)
                        + " bytes=0/" + size + " state=running");
            }
        }
        File raw = null;
        File staged = null;
        File compressed = null;
        String publishedDigest = null;
        long registeredOriginalMtime = 0L;
        /** 进度日志的节流状态：[0]=上次记录的字节数，[1]=上次记录的时刻 */
        final long[] lastReport = new long[] {0L, SystemClock.elapsedRealtime()};
        try {
            ensureWorkCurrent(config, generation);
            if (pending.autoDownload && !NetGuard.canUpload(mContext, true)) {
                // 计划之后网络从 Wi-Fi 掉到蜂窝：取消这一项，既不算失败也不动注册表，
                // 回到 Wi-Fi 后同一项还会被重排（Pending 留在队里）。
                throw new DownloadCancelledException();
            }
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
            // 注册表接管行要的是"真的把原图取回来"：不走压缩预览分支，否则落地的是一张
            // 预览、状态也不会变成 ORIGINAL，下一轮又排一遍。
            boolean qualityRequested = !pending.autoDownload && isHighQualityRequested()
                    && isCompressibleExtension(normalDest.getName()) && pending.size > 0L;
            // 本地已经有同名文件：默认一律不覆盖 —— 用户可能自己改过它，也可能同名但是
            // 别的东西。唯一例外是"这份字节正是本条记录登记过的副本"（原地瘦身写下的
            // 预览）：那时整份原图正是要把它顶掉，而下面 publishPreviewInPlace /
            // publishDownloadedOriginal 里的替换分支本来就是为这种情况写的（此前被这里
            // 一刀切挡掉，等于死代码）。守卫仍是 isRegisteredDerivedCopy：用户文件进不来。
            boolean replacingRegisteredDerived = normalDest.isFile()
                    && isRegisteredDerivedCopyFor(pending.remotePath, config, normalDest);
            if (normalDest.exists() && !replacingRegisteredDerived) {
                Logger.i("event=download_skip_local_exists remote=" + pending.remotePath
                        + " row=" + rowLabel(pending) + " bytes=" + normalDest.length()
                        + " local=" + normalDest);
                markDone(pending.remotePath, pending.target);
                ok = true;
                return;
            }
            if (replacingRegisteredDerived) {
                Logger.i("event=download_replace_registered_preview row=" + rowLabel(pending)
                        + " bytes=" + normalDest.length() + " path=" + normalDest
                        + " remote=" + pending.remotePath);
            }
            if (!mayDownload(mRemoteSuppress, config, pending.remotePath)) {
                // 计划之后账本又多了这条（删除线程与下载线程并行）：到 GET 之前再拦一次
                Logger.i("event=download_skip_suppressed remote=" + pending.remotePath);
                ok = true;
                return;
            }
            if (pending.autoDownload) {
                // 远端在不在，本轮可能已经知道了：目录名单里有名字 = 在（还能顺带核对长度），
                // 名单里没有 = 不在；两者都不必再为"这一步"握一次手。拿不到结论时照旧直接下载，
                // 不额外加探测 —— 加一次 PROPFIND 只会让正常路径更慢。
                DownloadProbeCache.Presence presence =
                        mProbeCache.probe(pending.remotePath, pending.size);
                if (presence == DownloadProbeCache.Presence.MISSING) {
                    // 远端这一轮确实没有这个名字（目录名单/探测给出的结论）。这不是失败：
                    // 不记失败次数、不标 done —— 远端对象要是又出现了，下一轮照样会排。
                    // 旧实现把它抛成 IOException 走失败记账，白白烧掉重试额度。
                    Logger.i("event=download_absent row=" + rowLabel(pending)
                            + " path=" + pending.remotePath + " bytes=" + pending.size
                            + " reason=probe_missing");
                    ok = true;
                    return;
                }
                if (presence == DownloadProbeCache.Presence.PRESENT) {
                    long listed = mProbeCache.listedSize(pending.remotePath);
                    if (listed > 0L && pending.size > 0L && listed != pending.size) {
                        // 远端长度与注册表不一致：整份下来也过不了长度校验，不如省掉这次传输
                        Logger.w("event=auto_download_skip reason=probe_size_mismatch remote="
                                + pending.remotePath + " listed=" + listed
                                + " expected=" + pending.size);
                        throw new DownloadFailurePolicy.PayloadException(
                                DownloadFailurePolicy.Cause.LENGTH_MISMATCH,
                                "remote size changed listed=" + listed
                                        + " expected=" + pending.size);
                    }
                }
            }
            WebDavClient client = clientFor(config, index);
            markTransferStarted(pending.remotePath, client);
            client.setProgressListener((done, total) -> {
                // 每 64KB 一次。发布器自己按 2Hz 发帧，这里只负责两件事：
                // 1) 把数字塞给进度页；2) 按 256KB / 500ms 记一行可对账的字节进度，
                //    并刷新停滞看门狗的心跳。日志节流做成纯函数，边界可单测。
                long now = SystemClock.elapsedRealtime();
                markTransferByte(pending.remotePath, now);
                if (!isWorkCurrent(config, generation)) {
                    client.close();
                    return;
                }
                UploadProgressPublisher target = mPublisher;
                if (target != null) {
                    if (pending.autoDownload) {
                        target.taskProgress(key, rowLabel(pending), done, size);
                    } else {
                        target.taskBytes(key, done);
                    }
                }
                if (DownloadProgressPolicy.due(lastReport[0], lastReport[1], done, size, now)) {
                    long elapsed = lastReport[1] <= 0L ? 0L : now - lastReport[1];
                    long rate = DownloadProgressPolicy.bytesPerSecond(done, lastReport[0], elapsed);
                    Logger.i("event=download_progress row=" + rowLabel(pending)
                            + " bytes=" + done + "/" + size
                            + " percent=" + DownloadProgressPolicy.percent(done, size)
                            + " rate=" + rate
                            + " elapsed=" + elapsed
                            + " path=" + pending.remotePath);
                    lastReport[0] = done;
                    lastReport[1] = now;
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
                verifyDownloadedContent(config, pending, raw, digest);
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
                    sweepStaleStaging(previewDest);
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
                // 长度对、摘要不对 = 中间层改了内容（真机那批"下完 355235 字节但登记被拒"
                // 里最可疑的就是全 0 内容）。注册表里已知的 sha256 是唯一的可信对照。
                verifyDownloadedContent(config, pending, raw, digest);
                publishedDigest = digest;
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
                // 先清掉上一次进程被杀时留下的同目录临时件（点开头、扫描器看不见）
                sweepStaleStaging(normalDest);
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
            // 已经下成了：这一条不需要"在不在"的结论了，别让它留在缓存里占位
            mProbeCache.forget(pending.remotePath, pending.size);
            boolean rowAdopted = false;
            if (pending.autoDownload) {
                // 接管行本来就有一行相册记录：把 localFile 精确指回原路径，绝不让扫描器建第二行。
                rowAdopted = adoptAutoDownloadedRow(pending, config, dest, publishedDigest);
            }
            // 原地发布已经把既有行精确改成“本地文件 = 原路径、缩略图为空”：这时再交给宿主
            // 扫描器只会让它在同一路径上多建一行，所以原地成功分支刻意跳过通知。
            if (!inplaceCommitted && !rowAdopted) {
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
                failureReason = String.valueOf(e.getMessage());
                fail(pending, causeOfTransferFailure(pending, e), failureReason);
            } else {
                Logger.d("event=download_cancelled remote=" + pending.remotePath);
            }
        } catch (IOException e) {
            cleanupUnpublished(raw, staged, compressed);
            noteProbeFailure(pending, e);
            if (isWorkCurrent(config, generation)) {
                failureReason = String.valueOf(e.getMessage());
                fail(pending, causeOfTransferFailure(pending, e), failureReason);
            } else {
                Logger.d("event=download_cancelled remote=" + pending.remotePath);
            }
        } catch (Throwable t) {
            cleanupUnpublished(raw, staged, compressed);
            if (isWorkCurrent(config, generation)) {
                failureReason = String.valueOf(t);
                // 未预期异常（历史上就是这里吞掉了登记闸门抛的 IllegalStateException）：
                // 归 UNKNOWN/可重试，但把原始堆栈留下，归类不靠猜。
                fail(pending, DownloadFailurePolicy.Cause.UNKNOWN, failureReason);
                Logger.w("event=download_error remote=" + pending.remotePath
                        + " reason=" + t, t);
            } else {
                Logger.d("event=download_cancelled remote=" + pending.remotePath);
            }
        } finally {
            // 这条传输结束了：从停滞看门狗的表里摘掉，别让已经收尾的项被误判成停滞
            markTransferEnded(pending.remotePath);
            if (publisher != null) {
                if (pending.autoDownload) {
                    // 终态收敛：项从在传集合里摘掉（进度页不再显示"进行中"），并记一行终态日志
                    publisher.autoDownloadEnd(key, rowLabel(pending), ok, failureReason);
                } else {
                    publisher.taskEnd(key, ok);
                }
            }
            synchronized (mLock) {
                mInflight.remove(pending.remotePath);
                if (ok) {
                    removePendingLocked(pending.remotePath);
                    mFailures.remove(pending.remotePath);
                    mFailureTimes.remove(pending.remotePath);
                    mFailureKinds.remove(pending.remotePath);
                    mPendingVersion++;
                }
                mLock.notifyAll();
            }
        }
    }

    /**
     * 一次"按注册表自动下载"落地之后的收尾：注册表升级成 ORIGINAL + 既有云行指回原路径。
     *
     * <p>顺序要紧：先改注册表（状态与摘要），再改相册行。两步都是幂等的，任一步失败都不会
     * 让已经落地的文件被删；注册表改失败时只留一条 warning，`mDone` 与本地文件仍然保证
     * 下一轮不会重复下载（下一轮会走 {@code PROMOTE_EXISTING} 把状态补上）。
     *
     * @return 是否真的改到了相册 cloud 行；false 时调用方回落到宿主扫描器
     */
    private boolean adoptAutoDownloadedRow(Pending pending, Config config, File dest,
            String digest) {
        OriginalStore.Record record = mOriginalStore.findByRemotePath(config, pending.remotePath,
                dest);
        if (record == null) {
            record = mOriginalStore.find(config, pending.rowId, pending.sha1);
        }
        OriginalStore.Record promoted = record == null ? null
                : mOriginalStore.markAutoDownloaded(config, record, dest, digest);
        Long parsedRow = parseRowId(pending.rowId);
        Ingest ingest = mIngest;
        boolean rowUpdated = false;
        if (ingest != null && parsedRow != null && parsedRow > 0L) {
            rowUpdated = ingest.updateExistingRow(dest, parsedRow, pending.sha1, "");
        }
        Logger.i("event=auto_download_done row=" + (pending.rowId == null ? "unknown"
                : pending.rowId) + " path=" + dest.getAbsolutePath()
                + " bytes=" + dest.length()
                + " state=" + (promoted == null ? "unknown"
                        : promoted.getLocalState().wire())
                + " row_updated=" + rowUpdated);
        if (promoted == null) {
            Logger.w("event=auto_download_registry_pending row=" + pending.rowId
                    + " path=" + dest.getAbsolutePath());
        }
        return rowUpdated;
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
        // 发布路径都从这里出去，所以闸门放在这一层。判据按**目标**的扩展名（staged 的名字
        // 是 .tmp，按它判等于没判），不合规就抛带原因标签的 IOException，
        // 由 download() 的 catch 分支清理临时文件并按一次失败记账（不发布半成品）。
        DownloadedPayloadGate.Verdict verdict = DownloadedPayloadGate.inspect(staged, dest, 0L);
        if (!verdict.ok()) {
            Logger.w("event=download_gate_rejected reason=" + verdict.wire()
                    + " path=" + (staged == null ? "null" : staged.getAbsolutePath())
                    + " dest=" + (dest == null ? "null" : dest.getAbsolutePath())
                    + " bytes=" + (staged == null ? -1L : staged.length()));
            throw new DownloadFailurePolicy.PayloadException(
                    DownloadedPayloadGate.causeOf(verdict),
                    "download produced an invalid payload reason=" + verdict.wire());
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
        sweepStaleStaging(normalDest);
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
     * （{@code .jpg/.jpeg}，以及 {@code preserveHeic} 打开时的 {@code .heic/.heif}）：
     * 预览就覆盖在原路径、原文件名上，不再另起一份。
     *
     * <p>PNG 不再原地覆盖：无损重编省不了字节、超大图还有 OOM 风险，空间优化整类跳过它；
     * 下载高清分支仍可能遇到 png，这时仍按老侧车规则落一份 JPEG 预览
     * （{@code <相册目录>/.gallerysync-slim/<stem>.jpg}，见 {@link #legacyPreviewPathFor}）。
     * 其余容器（webp/bmp/gif/视频）与 {@code preserveHeic=false} 的 HEIC 同样走侧车。
     *
     * <p>判定全部交给 {@link InplaceSlimPolicy} 与 {@link PreviewEncodePolicy}，调用方不要再自己
     * 拼目录或文件名。规则幂等：同一张图片永远得到同一个路径。返回原图路径表示“这个类型就地覆盖”。
     */
    public static File previewPathFor(File original, boolean preserveHeic) {
        String format = InplaceSlimPolicy.targetFormat(original.getName());
        if ("jpg".equals(format)) {
            return InplaceSlimPolicy.targetPath(original);
        }
        if ("heic".equals(format) && preserveHeic) {
            return InplaceSlimPolicy.targetPath(original);
        }
        // png（以及 webp/bmp/gif/视频、关掉 preserveHeic 的 heic）落侧车目录
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
        OriginalStore.Record record = mOriginalStore.findByRemotePath(config, remotePath, album);
        // 相册里那份同名文件是本记录登记过的副本（原地瘦身预览）：写回它，不另存镜像
        boolean albumIsRegisteredDerived = album.isFile()
                && mOriginalStore.isRegisteredDerivedPath(record, album);
        return DownloadDestinationPolicy.resolve(album, mirror, sameNameSameLength,
                record == null ? null : record.originalLocalPath, albumIsRegisteredDerived);
    }

    /**
     * 注册表驱动的自动下载落点：与 {@link #downloadDestinationFor} 同一套判据。
     *
     * <p>为什么不能直接用 {@link AutoDownloadPolicy#targetPath}：那个纯函数只按远端相对路径
     * 算相册路径，<b>看不到注册表</b>。被用户改名/移动过的接管行因此会被算到第二个路径，
     * 下完就多一份文件、相册多一行。这里补上"账本优先"，与远端枚举那条链路口径一致。
     */
    private File registryAwareDestination(Config config, String layout,
            OriginalStore.Record record, File albumDestination) {
        String relative = AutoDownloadPolicy.relativePath(config.getRemoteRoot(), record.remotePath);
        File mirror = relative == null ? null : new File(MIRROR_ROOT, relative);
        if (mirror == null) {
            return albumDestination;
        }
        boolean sameNameSameLength = albumDestination.isFile()
                && record.originalLength >= 0L
                && albumDestination.length() == record.originalLength;
        return DownloadDestinationPolicy.resolve(albumDestination, mirror, sameNameSameLength,
                record.originalLocalPath, false);
    }

    /**
     * 这条远端路径在本地登记过的副本是不是就是 {@code file}。
     *
     * <p>只认"这条记录自己登记过的副本"：用户自己的同名文件永远不满足，所以它只可能被
     * 用来放行"顶掉我们写下的预览"，不会变成"覆盖用户文件"。
     */
    private boolean isRegisteredDerivedCopyFor(String remotePath, Config config, File file) {
        if (file == null || !file.isFile() || file.length() <= 0L) {
            return false;
        }
        OriginalStore.Record record = mOriginalStore.findByRemotePath(config, remotePath, file);
        return record != null && mOriginalStore.isRegisteredDerivedCopy(record, file);
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

    /**
     * 记一次失败：次数只增不减，冷却长短按种类与次数算。
     *
     * <p>旧实现（本次修掉的根因）：冷却一过就把次数删掉，于是一个"每次都返回坏字节"的
     * 远端对象会被每分钟重下一整份。现在次数保留，冷却指数增长（可重试 60s 起、
     * 终态 30min 起、上限 24h），只有<b>成功</b>才清账。
     */
    private void fail(Pending pending, DownloadFailurePolicy.Cause cause, String reason) {
        DownloadFailurePolicy.Kind kind = DownloadFailurePolicy.kindOf(cause);
        int count;
        long now = SystemClock.elapsedRealtime();
        synchronized (mLock) {
            Integer previous = mFailures.get(pending.remotePath);
            count = (previous == null ? 0 : previous)
                    + DownloadFailurePolicy.attemptCost(cause, MAX_FAIL_PER_PATH);
            mFailures.put(pending.remotePath, count);
            mFailureKinds.put(pending.remotePath, kind);
            mFailureTimes.put(pending.remotePath, now);
            trimFailuresLocked();
        }
        long cooldown = DownloadFailurePolicy.cooldownMs(kind, count, MAX_FAIL_PER_PATH);
        boolean blocked = DownloadFailurePolicy.blocked(kind, count, now, now, MAX_FAIL_PER_PATH);
        Logger.w("event=download_fail row=" + rowLabel(pending)
                + " path=" + pending.remotePath
                + " bytes=" + pending.size
                + " kind=" + kind.name().toLowerCase(Locale.US)
                + " cause=" + (cause == null ? "unknown" : cause.wire())
                + " attempts=" + count
                + " cooldown=" + cooldown
                + " blocked=" + blocked
                + " reason=" + reason);
        if (pending.autoDownload) {
            // 失败只记账，不动注册表：状态仍是 REMOTE_ONLY，等下一轮（或冷却之后）重试。
            Logger.w("event=auto_download_failed row=" + (pending.rowId == null ? "unknown"
                    : pending.rowId) + " kind=" + kind.name().toLowerCase(Locale.US)
                    + " attempts=" + count + " reason=" + reason);
        }
    }

    /**
     * 异常 → 失败原因。
     *
     * <p>分类只影响冷却长短与日志，不影响正确性：认不出来的一律归
     * {@link DownloadFailurePolicy.Cause#UNKNOWN}（按可重试处理），绝不把未知故障记成
     * 终态而永久挡住一张照片。
     */
    private static DownloadFailurePolicy.Cause causeOf(Throwable t) {
        if (t == null) {
            return DownloadFailurePolicy.Cause.UNKNOWN;
        }
        if (t instanceof DownloadFailurePolicy.PayloadException) {
            return ((DownloadFailurePolicy.PayloadException) t).cause();
        }
        if (t instanceof WebDavClient.HttpStatusException) {
            return DownloadFailurePolicy.causeOfStatus(
                    ((WebDavClient.HttpStatusException) t).code);
        }
        if (t instanceof WebDavClient.TransportFailure) {
            return DownloadFailurePolicy.Cause.TRANSPORT;
        }
        if (t instanceof java.net.SocketTimeoutException) {
            return DownloadFailurePolicy.Cause.TRANSPORT;
        }
        return DownloadFailurePolicy.causeOfMessage(t.getMessage());
    }

    /**
     * 传输类异常的失败原因：先看"是不是被看门狗按停滞关掉的"。
     *
     * <p>关掉连接之后抛出来的只有 {@code SocketException: Socket closed}，从文本上分不出
     * 是服务器卡住还是别的原因；标记表能给出确定答案（且取走即清，不污染下一次尝试）。
     */
    private DownloadFailurePolicy.Cause causeOfTransferFailure(Pending pending, Throwable t) {
        if (pending != null) {
            synchronized (mLock) {
                if (mStalledPaths.remove(pending.remotePath)) {
                    return DownloadFailurePolicy.Cause.STALLED;
                }
            }
        }
        return causeOf(t);
    }

    /** 失败账本不能无界增长：超上限先丢最早的（与 {@link #trimDoneLocked} 同一口径）。 */
    private void trimFailuresLocked() {
        if (mFailures.size() <= MAX_DONE_RECORDS) {
            return;
        }
        Iterator<String> it = mFailures.keySet().iterator();
        while (it.hasNext() && mFailures.size() > MAX_DONE_RECORDS) {
            String key = it.next();
            it.remove();
            mFailureTimes.remove(key);
            mFailureKinds.remove(key);
        }
    }

    // ------------------------------------------------------- 字节进度与停滞看门狗

    /** 这条项开始传输：登记它的连接，供停滞看门狗在必要时关闭。 */
    private void markTransferStarted(String remotePath, WebDavClient client) {
        synchronized (mLock) {
            mLastByteAt.put(remotePath, SystemClock.elapsedRealtime());
            mActiveClients.put(remotePath, client);
        }
    }

    /** 收到字节：刷新心跳。回调在传输线程上跑，只碰受锁保护的 Map。 */
    private void markTransferByte(String remotePath, long now) {
        synchronized (mLock) {
            mLastByteAt.put(remotePath, now);
        }
    }

    /** 这条项收尾（成功/失败/取消都走这里）：摘掉心跳与连接登记。 */
    private void markTransferEnded(String remotePath) {
        synchronized (mLock) {
            mLastByteAt.remove(remotePath);
            mActiveClients.remove(remotePath);
            // 正常收尾就说明没有被看门狗判定过停滞；残留标记只会让下一次尝试误分类
            mStalledPaths.remove(remotePath);
        }
    }

    /**
     * 停滞看门狗：每 {@link #WATCHDOG_INTERVAL_MS} 扫一次"最后收到字节到现在多久"。
     *
     * <p>为什么不能只靠 socket 超时：WebDavClient 的直连 302 缓存路径在服务端不吐字节时
     * 会一直挂到 {@code READ_TIMEOUT_MAX}（分钟级），而且缓存 3 分钟内每次重试都卡同一条。
     * 队列能做的只有一件事 —— 到点主动关掉那条连接，让读循环抛异常走失败记账。
     */
    private void watchdogLoop() {
        while (mRunning) {
            synchronized (mLock) {
                if (mRunning) {
                    try {
                        mLock.wait(WATCHDOG_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            long now = SystemClock.elapsedRealtime();
            List<String> stalled = new ArrayList<>();
            Map<String, WebDavClient> clientsByPath = new HashMap<>();
            synchronized (mLock) {
                for (Map.Entry<String, Long> entry : mLastByteAt.entrySet()) {
                    Long last = entry.getValue();
                    if (last == null
                            || !DownloadProgressPolicy.stalled(last, now,
                                    DownloadProgressPolicy.STALL_MS)) {
                        continue;
                    }
                    stalled.add(entry.getKey());
                    WebDavClient client = mActiveClients.get(entry.getKey());
                    if (client != null) {
                        clientsByPath.put(entry.getKey(), client);
                    }
                }
            }
            for (String remotePath : stalled) {
                long last;
                synchronized (mLock) {
                    Long at = mLastByteAt.get(remotePath);
                    last = at == null ? now : at;
                    // 先摘掉心跳，避免同一项被连续关两次
                    mLastByteAt.remove(remotePath);
                    mActiveClients.remove(remotePath);
                    // 留下"这次是停滞"的标记，供 download() 的失败分类使用
                    mStalledPaths.add(remotePath);
                }
                Logger.w("event=download_stalled row=unknown path=" + remotePath
                        + " elapsed=" + Math.max(0L, now - last)
                        + " reason=no_bytes_for_" + DownloadProgressPolicy.STALL_MS + "ms");
                WebDavClient client = clientsByPath.get(remotePath);
                if (client != null) {
                    // close() 让正在阻塞的读立即返回，get() 随即抛 IOException，
                    // 由 download() 的 catch 归成可重试失败（次数只增、冷却递增）。
                    client.close();
                }
            }
        }
    }

    // ------------------------------------------------------------- 残留临时件

    /**
     * 清掉同一目录里"上一辈子"留下的下载临时件。
     *
     * <p>进程被杀时没人走 {@code cleanupUnpublished}，而 {@code .<name>.mgs-*.tmp} 以点
     * 开头、媒体扫描器看不见，于是只会安静地越积越多。判据是纯函数
     * （{@link DownloadResiduePolicy}）：名字形状对 + 足够旧，绝不碰本路或别的路刚写的文件。
     */
    private static void sweepStaleStaging(File destination) {
        if (destination == null) {
            return;
        }
        File parent = destination.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.isDirectory()) {
            return;
        }
        String[] names;
        try {
            names = parent.list();
        } catch (Throwable t) {
            names = null;
        }
        if (names == null || names.length == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        int removed = 0;
        for (String name : names) {
            File candidate = new File(parent, name);
            if (!DownloadResiduePolicy.isSweepable(name, destination.getName(), now,
                    candidate.lastModified())) {
                continue;
            }
            if (candidate.delete()) {
                removed++;
            }
        }
        if (removed > 0) {
            Logger.i("event=download_residue_cleaned dir=" + parent.getAbsolutePath()
                    + " dest=" + destination.getName() + " removed=" + removed);
        }
    }

    /**
     * 摘要校验：注册表里这一行记着 sha256 时，下下来的字节必须对得上。
     *
     * <p>真机故障里"长度对、内容全 0"这种情况只有这一步能抓稳（签名闸对未知后缀失效，
     * 而全 0 的 JPEG 名文件也能蒙过只看头部的判据）。注册表里的摘要本身是全 0 摘要时
     * 跳过 —— 那是账本自己坏了，不能反过来判一份正常下载失败。
     */
    private void verifyDownloadedContent(Config config, Pending pending, File file, String digest)
            throws IOException {
        OriginalStore.Record record = mOriginalStore.findByRemotePath(config, pending.remotePath,
                file);
        if (record == null || record.sha256 == null || record.sha256.length() != 64
                || digest == null) {
            return;
        }
        if (MediaPayload.isZeroDigest(record.sha256, record.originalLength)) {
            Logger.w("event=download_digest_unusable row=" + rowLabel(pending)
                    + " path=" + pending.remotePath + " reason=registry_zero_digest");
            return;
        }
        if (!record.sha256.equalsIgnoreCase(digest)) {
            Logger.w("event=download_digest_mismatch row=" + rowLabel(pending)
                    + " path=" + pending.remotePath + " bytes=" + file.length()
                    + " expected=" + record.sha256 + " actual=" + digest);
            throw new DownloadFailurePolicy.PayloadException(
                    DownloadFailurePolicy.Cause.CONTENT_MISMATCH,
                    "downloaded bytes do not match the registered digest expected="
                            + record.sha256 + " actual=" + digest + " bytes=" + file.length());
        }
    }

    /**
     * 下载失败时顺手修正探测缓存：只有"服务端明确说 404"才算"远端没有"。
     *
     * <p>别的失败（超时、401、5xx、磁盘写不进去）都不说明远端在不在，绝不能记成
     * MISSING —— 那会让下一轮直接跳过这张图，用户永远下不回原图。命中缓存却拿到 404
     * 也说明缓存已经过时（或名字被别处改了），{@link DownloadProbeCache#recordProbe}
     * 会把它覆盖成 MISSING，下一轮就不会再为它握手。
     */
    private void noteProbeFailure(Pending pending, IOException e) {
        if (!pending.autoDownload) {
            return;
        }
        if (!(e instanceof WebDavClient.HttpStatusException)) {
            return;
        }
        if (((WebDavClient.HttpStatusException) e).code != 404) {
            return;
        }
        mProbeCache.recordProbe(pending.remotePath, pending.size, false);
        Logger.d("event=auto_download_probe_absent remote=" + pending.remotePath);
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

    /**
     * 每个 worker 一路连接，所以只需要区分"我是第几路"。
     *
     * <p>自动下载的几路排在通用两路之后（{@code THREADS + i}），因此两拨线程
     * 绝不会共用同一条连接：共用的话一路的 302 会让另一路读到别人的响应体，
     * 表现为"下回来的图是本机另一张"。认不出来（不该发生的线程）退到 0 号。
     */
    private int workerIndex() {
        Thread current = Thread.currentThread();
        for (int i = 0; i < mWorkers.length; i++) {
            if (mWorkers[i] == current) {
                return i;
            }
        }
        for (int i = 0; i < mAutoWorkers.length; i++) {
            if (mAutoWorkers[i] == current) {
                return THREADS + i;
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
