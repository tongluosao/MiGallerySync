package com.tongluosao.gallerysync.core;

import android.content.Context;

import com.tongluosao.gallerysync.hook.CloudItemReader;
import com.tongluosao.gallerysync.hook.HostSyncState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WebDAV 上传队列：去重、持久化、串行上传、服务器级冷却退避。
 *
 * <p>运行在相册进程，必须在后台线程完成全部 IO 与网络，绝不能阻塞相册的同步线程。
 * 去重键为 路径+大小+修改时间，已完成的记录会保留，避免相册因 localFlag 未清除而反复入队时重复上传。
 *
 * <p>队列见底时会主动请相册再枚举一次（间隔逐次翻倍），因为相册自己只在小时级的排期上
 * 枚举脏项 —— 干等的话队列空着的时间远多于在传的时间。
 */
public final class UploadQueue {

    private static final String DIR = "webdav_sync";
    private static final String STATE_FILE = "state.json";
    private static final int MAX_DONE_RECORDS = 20000;
    /**
     * 服务器级冷却：失败一次只让队列停一小会儿，而不是"每一项各自睡一觉"。
     *
     * <p>原来是按项指数退避（1/2/4/8/16 分钟，超过重试上限转 30 分钟）。问题是同一台
     * 服务器出问题时整批项会**一起**失败，于是整个队列同时进入长退避 —— 真机上实测
     * 1400 多项挂起后连续几分钟一个请求都不发，界面上看着就是"备份停了"。
     * 改成按"连续失败了几次"算一个共用的冷却，成功即清零：一次抖动最多停几秒到几分钟，
     * 而且失败项会各自按顺序轮回来，不会被队头那一个坏项把整队挡住。
     */
    private static final long COOLDOWN_BASE = 15_000L;
    private static final long COOLDOWN_MAX = 3 * 60_000L;
    /** 重试次数用尽后的长间隔：只针对这一项，不再拖累整队 */
    private static final long RETRY_GIVE_UP = 30 * 60_000L;
    private static final long WAIT_NO_TASK = 120_000L;
    private static final long WAIT_NO_NETWORK = 300_000L;
    /** 连续失败到这个次数就认为 WebDAV 侧不可用，Hook 侧会临时放行原链路 */
    private static final int UNHEALTHY_FAILURES = 5;
    /** 只有不超过这个体积的项失败才算"整台服务器不行"；更大的项失败只算它自己传不动 */
    private static final long BREAKER_PROBE_MAX = 16L * 1024 * 1024;
    /**
     * 落盘的最小间隔。done 表上限 20000 条、序列化出来约 1.6MB，每传完一张就整份重写一遍，
     * 一万两千张就是十几 GB 的写入 —— 上传本身快起来之后这笔开销就显眼了。
     * 丢了最多是重传（PUT 是覆盖写），不是丢数据。
     */
    private static final long SAVE_INTERVAL = 5_000L;
    /**
     * 队列见底时主动请相册再枚举一次的起始间隔，空手而归就逐次翻倍到上限。
     *
     * <p>相册只按自己的排期枚举脏项（真机实测间隔 1~3 小时），模块干等的话绝大部分时间
     * 都在空转 —— 这是"备份很慢"里比传输本身更大的一块。主动催能把这个间隔压到分钟级；
     * 但也不能一直高频催，所以每催一次就把下次的间隔翻倍，有新项入队再重置回去。
     */
    private static final long IDLE_SYNC_BASE = 60_000L;
    private static final long IDLE_SYNC_MAX = 60 * 60_000L;

    private static volatile UploadQueue sInstance;

    private final Context mContext;
    private final Object mLock = new Object();
    private final LinkedHashMap<String, Task> mPending = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> mDone = new LinkedHashMap<>();

    private volatile Config mConfig;
    private volatile SyncMarker mMarker;
    private volatile HostSyncState mProgress;
    private volatile int mConsecutiveFailures;
    private Thread mWorker;
    /** worker 循环在锁外读它，必须 volatile；热重载时由 shutdown() 置假 */
    private volatile boolean mRunning = true;

    /** 复用的 WebDAV 客户端：连接 keep-alive 的载体，目标变了才换 */
    private WebDavClient mClient;
    private String mClientTarget;
    private long mLastSave;
    private boolean mSavePending;
    /** 本轮空转之前传上去了几项。只在 worker 线程读写，不需要同步 */
    private int mUploadedSinceIdle;
    /** 连续失败次数，决定队列冷却多久；只在 worker 线程读写 */
    private int mCooldownFailures;
    /** 下次请相册枚举的时间点；只在 worker 线程读写 */
    private long mNextIdleSyncAt;
    /** 当前催相册的间隔：入队线程会把它重置，所以得 volatile */
    private volatile long mIdleSyncDelay = IDLE_SYNC_BASE;

    private UploadQueue(Context context, Config config) {
        mContext = context.getApplicationContext();
        mConfig = config;
        load();
        startWorker();
    }

    public static UploadQueue get(Context context, Config config) {
        if (sInstance == null) {
            synchronized (UploadQueue.class) {
                if (sInstance == null) {
                    sInstance = new UploadQueue(context, config);
                }
            }
        }
        return sInstance;
    }

    /**
     * 停掉上传线程。热重载交接时旧 generation 必须调用：否则新旧两套队列同时在跑，
     * 两边都会把各自的视图写回 state.json。
     */
    public void shutdown() {
        int pending;
        synchronized (mLock) {
            // 先把最后一次进度落盘再停：mRunning 置假后 saveLocked 会直接跳过
            flushLocked();
            mRunning = false;
            pending = mPending.size();
            mLock.notifyAll();
        }
        // 别让相册的界面一直挂在"正在上传"上
        HostSyncState progress = mProgress;
        if (progress != null) {
            progress.endUpload();
        }
        releaseClient();
        Logger.i("event=queue_shutdown pending=" + pending);
        Thread worker = mWorker;
        if (worker != null) {
            worker.interrupt();
        }
        // 单例也得清掉：worker 已经停了，留着它的话下一次 get() 会拿到一个永远不会动的队列
        synchronized (UploadQueue.class) {
            if (sInstance == this) {
                sInstance = null;
            }
        }
    }

    private void releaseClient() {
        WebDavClient client = mClient;
        mClient = null;
        mClientTarget = null;
        if (client != null) {
            client.close();
        }
    }

    /**
     * WebDAV 侧是否可用。
     *
     * <p>连续失败达到阈值就返回 false。Hook 侧据此在替换模式下临时放行原链路：
     * 队列一直失败（密码错、服务器挂）时若还继续拦截，相册这边被挡、WebDAV 那边传不上去，
     * 就成了"两边都没传"，那是本模块最不能出现的结果。宁可退回小米云，也不能丢备份。
     */
    public boolean isHealthy() {
        return mConsecutiveFailures < UNHEALTHY_FAILURES;
    }

    /** 当前连续失败次数，仅用于日志 */
    public int consecutiveFailures() {
        return mConsecutiveFailures;
    }

    /** 上传成功后补写相册落册标记的实现；为 null 时不记（镜像模式由宿主自己记） */
    public void setMarker(SyncMarker marker) {
        mMarker = marker;
    }

    /** 驱动相册原生进度界面的桥；为 null 时界面不显示进度，上传照常 */
    public void setProgress(HostSyncState progress) {
        mProgress = progress;
    }

    public void setConfig(Config config) {
        Config previous = mConfig;
        mConfig = config;
        if (previous == null || !sameTarget(previous, config)) {
            // 目标或凭据变了就重新给 WebDAV 机会：用户改完密码/换完服务器，
            // 熔断状态若还留着，替换模式会一直不生效
            if (mConsecutiveFailures != 0) {
                Logger.i("event=queue_health_reset failures=" + mConsecutiveFailures);
                mConsecutiveFailures = 0;
            }
            // 旧连接是对着旧目标建的，keep-alive 留着只会连错地方
            releaseClient();
            synchronized (mLock) {
                targetChangedLocked();
            }
        }
    }

    /**
     * 目标变了就把"已完成"与退避一起清掉。
     * 已完成记录是按"这个位置上已经有了"记的，换服务器/换账号/换远端目录之后它对新位置
     * 完全无效 —— 留着就是"队列一片绿、新目标上一个文件都没有"。退避同理，它是旧目标攒出来的。
     * 调用方必须持有 mLock（里面要 notifyAll 唤醒等退避的 worker）。
     */
    private void targetChangedLocked() {
        int pending = mPending.size();
        int done = mDone.size();
        mDone.clear();
        for (Task task : mPending.values()) {
            task.attempts = 0;
            task.nextAttemptAt = 0L;
        }
        if (done > 0 || pending > 0) {
            Logger.i("event=queue_target_reset done=" + done + " pending=" + pending);
        }
        // 立刻落盘：不写的话，进程在下次正常落盘之前被杀（一键清理、崩溃），
        // 旧 done 表会从 state.json 里原样读回来，"换了目录"这件事就白做了
        writeStateLocked();
        mLock.notifyAll();
    }

    /**
     * "目标"是否没变。凭据、服务器、**以及远端目录**都算目标的一部分。
     *
     * <p>远端根目录与分目录方式必须一起比：已完成记录是按本地文件（路径+大小+mtime）记的，
     * 意思是"这一份已经传到当前这个位置了"。用户把 remote_root 换成另一个目录、
     * 或把分目录方式从按时间改成按相册之后，同一个文件在远端该待的地方就变了 ——
     * 这时若还认为目标没变，done 表会一直说"传过了"，新目录里一个文件都不会有，
     * 表现成"换了目录之后备份就不动了"。PUT 是覆盖写，重来一遍没有数据风险，
     * 代价只是再传一次流量，所以这里宁可保守。
     */
    private static boolean sameTarget(Config a, Config b) {
        if (a == null || b == null) {
            return a == b;
        }
        return eq(a.getBaseUrl(), b.getBaseUrl())
                && eq(a.getUsername(), b.getUsername())
                && eq(a.getPassword(), b.getPassword())
                && eq(a.getRemoteRoot(), b.getRemoteRoot())
                && eq(a.getRemoteLayout(), b.getRemoteLayout());
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 入队：已上传过的直接跳过，暂停中的保留原退避时间 */
    public void enqueue(List<CloudItemReader.Item> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        int added = 0;
        int skipped = 0;
        synchronized (mLock) {
            for (CloudItemReader.Item item : items) {
                File file = new File(item.path);
                if (!file.isFile() || file.length() == 0) {
                    skipped++;
                    continue;
                }
                String key = key(item.path, file.length(), file.lastModified());
                if (mDone.containsKey(key) || mPending.containsKey(key)) {
                    skipped++;
                    continue;
                }
                mPending.put(key, new Task(item.path, file.length(), file.lastModified(),
                        item.video, item.date, item.rowId));
                added++;
            }
            if (added > 0) {
                // 有新活了，催相册的间隔拉回最短：不然队列刚被喂饱，下一次催促却要等一小时
                mIdleSyncDelay = IDLE_SYNC_BASE;
                // 入队是每个批次一次，不是每张照片一次，这里直接落盘
                writeStateLocked();
                mLock.notifyAll();
            }
        }
        Logger.i("event=enqueue added=" + added + " skipped=" + skipped
                + " pending=" + mPending.size() + " done=" + mDone.size());
    }

    private void startWorker() {
        mWorker = new Thread(this::drain, "MiGallerySync-upload");
        mWorker.setDaemon(true);
        mWorker.start();
    }

    private void drain() {
        while (mRunning) {
            Task task = null;
            long waitMillis = WAIT_NO_TASK;
            synchronized (mLock) {
                long now = System.currentTimeMillis();
                for (Task candidate : mPending.values()) {
                    if (candidate.nextAttemptAt <= now) {
                        task = candidate;
                        break;
                    }
                    waitMillis = Math.min(waitMillis, candidate.nextAttemptAt - now);
                }
                if (task == null) {
                    try {
                        mLock.wait(Math.max(1000L, waitMillis));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            if (task == null) {
                onQueueIdle();
                continue;
            }

            Config config = mConfig;
            if (config == null || !config.isReady()) {
                sleepQuietly(WAIT_NO_TASK);
                continue;
            }
            if (!NetGuard.canUpload(mContext, config.isWifiOnly())) {
                Logger.d("event=upload_wait reason=network_condition");
                sleepQuietly(WAIT_NO_NETWORK);
                continue;
            }

            upload(task, config);
        }
    }

    /**
     * 没有到期任务时该做的事：把进度收尾，并主动请相册再枚举一次。
     *
     * <p>相册只按自己的排期枚举脏项（真机实测间隔 1~3 小时），干等的话队列空着的时间
     * 远多于在传的时间。这里每空转一次就催一下，间隔逐次翻倍（60s → 2min → … → 1h），
     * 有新项入队就重置回 60s。空手而归时不会自激：催一次就把下次推远一倍。
     */
    private void onQueueIdle() {
        // 队列见底或整批在退避，都不该继续占着"正在上传"，否则界面会停在一个不再变化的数字上
        flushState();
        HostSyncState progress = mProgress;
        if (progress == null) {
            return;
        }
        progress.endUpload();
        long now = System.currentTimeMillis();
        if (now < mNextIdleSyncAt) {
            return;
        }
        long delay = mIdleSyncDelay;
        mNextIdleSyncAt = now + delay;
        mIdleSyncDelay = Math.min(delay * 2, IDLE_SYNC_MAX);
        Logger.i("event=queue_idle uploaded=" + mUploadedSinceIdle
                + " pending=" + mPending.size() + " next_sync_in=" + delay);
        mUploadedSinceIdle = 0;
        progress.requestSync(mContext);
    }

    private void upload(Task task, Config config) {
        File file = new File(task.path);
        if (!file.isFile() || file.length() == 0) {
            Logger.w("event=upload_skip reason=local_file_missing path=" + task.path);
            synchronized (mLock) {
                mPending.remove(task.key());
                saveThrottledLocked();
            }
            return;
        }
        HostSyncState progress = mProgress;
        if (progress != null) {
            progress.beginUpload();
        }
        WebDavClient client = clientFor(config);
        // 根目录是手填的，前后斜杠、反斜杠都可能带；直接拼会拼出 // 这种空段
        String remoteDir = remoteDirFor(config, task);
        String remotePath = WebDavClient.joinPath(remoteDir, file.getName());
        try {
            // 绝不预先建目录：OpenList 挂网盘的后端会把 `PUT <dir>/`（空请求体）当成
            // "创建一个 0 字节的文件"，名字撞车时驱动再自动改名成 dir(1)/dir(2)…，
            // 真目录被同名文件一挡，后续文件 PUT 就随机 405/423 —— 探测反而制造了故障。
            // 能自动补父目录的服务器（alist / OpenList / CloudDrive2）本来就不需要这一步；
            // 不需要的由 put() 在 404/409 时补建一次。
            client.put(remotePath, file);
            synchronized (mLock) {
                mPending.remove(task.key());
                mDone.put(task.key(), System.currentTimeMillis());
                trimDoneLocked();
                saveThrottledLocked();
            }
            if (mConsecutiveFailures != 0) {
                Logger.i("event=queue_recovered failures_reset=" + mConsecutiveFailures);
                mConsecutiveFailures = 0;
            }
            // 传通一次就说明服务器是活的：冷却清零、催相册的间隔也拉回最短，
            // 不然一次抖动会把后面几十项的间隔一直撑在最大值上
            mCooldownFailures = 0;
            mIdleSyncDelay = IDLE_SYNC_BASE;
            mUploadedSinceIdle++;
            Logger.i("event=upload_ok path=" + task.path + " remote=" + remotePath
                    + " size=" + file.length() + " pending=" + mPending.size());
            // put() 里已经用 HEAD/PROPFIND 复核过确实落盘，才配得上这条记账；
            // 放在锁外做，宿主 provider 的耗时不该把队列锁住
            SyncMarker marker = mMarker;
            if (marker != null) {
                marker.markSynced(task.rowId);
            }
            if (progress != null) {
                // 记账写下去之后界面上的"待备份 N"才会减一，所以刷新放在记账之后
                progress.refresh();
            }
        } catch (WebDavClient.TransportFailure e) {
            // 连接层失败：只算这一项的账，不喂熔断（见 WebDavClient.send 的注释）
            scheduleRetry(task, config, e.getMessage(), false);
        } catch (IOException e) {
            // 服务器自己回的错误码（401/403/5xx/假写）。大文件在这些码上更容易被边缘节点
            // 单独掐掉（实测 3MB 回 554 而小文件全过），所以只有"小体积的项也失败"才说明
            // 是整台服务器不可用，够格喂熔断
            scheduleRetry(task, config, e.getMessage(), task.size <= BREAKER_PROBE_MAX);
        } catch (Throwable t) {
            mConsecutiveFailures++;
            Logger.e("event=upload_error path=" + task.path, t);
            synchronized (mLock) {
                task.nextAttemptAt = System.currentTimeMillis() + RETRY_GIVE_UP;
                saveThrottledLocked();
            }
        }
    }

    /**
     * 取一个可复用的客户端。
     *
     * <p>一个文件要发 PUT + HEAD（必要时再 PROPFIND），每条请求开一条连接的话，
     * 每张照片都要付两三次 TCP+TLS 握手 —— 上次备份慢主要就慢在这里。
     * 客户端必须跨文件留着，连接的 keep-alive 才有意义。
     */
    private WebDavClient clientFor(Config config) {
        String signature = Config.safeHost(config.getBaseUrl()) + "|" + config.getUsername();
        if (mClient != null && signature.equals(mClientTarget)) {
            return mClient;
        }
        releaseClient();
        mClient = new WebDavClient(config.getBaseUrl(), config.getUsername(), config.getPassword());
        mClientTarget = signature;
        return mClient;
    }

    /**
     * 安排下一次重试。
     *
     * @param counts 是否计入"目标不可用"的熔断计数。退避本身按项计数，
     *               所以下一轮枚举仍会碰到它（没记 localFlag，不会丢）。
     */
    private void scheduleRetry(Task task, Config config, String reason, boolean counts) {
        task.attempts++;
        if (counts) {
            int failures = ++mConsecutiveFailures;
            if (failures == UNHEALTHY_FAILURES) {
                Logger.w("event=queue_unhealthy failures=" + failures
                        + " action=hook_will_yield_to_original");
            }
        }
        long delay;
        if (task.attempts >= config.getMaxRetry()) {
            // 这一项自己重试用尽了，单独放远一点；其它项照常轮转，不被它拖住
            delay = RETRY_GIVE_UP;
            Logger.w("event=upload_give_up path=" + task.path
                    + " attempts=" + task.attempts + " reason=" + reason);
        } else {
            // 退避记在"服务器"头上而不是"每一项"头上：整批一起失败时，按项各算各的
            // 会让 1400 多项同时睡满 1~30 分钟，队列整段空转（真机实测连续几分钟零请求）。
            // 连续失败次数封顶 8，移位不会溢出；算出来再被 COOLDOWN_MAX 兜住
            mCooldownFailures = Math.min(mCooldownFailures + 1, 8);
            delay = Math.min(COOLDOWN_BASE << (mCooldownFailures - 1), COOLDOWN_MAX);
            Logger.w("event=upload_retry path=" + task.path
                    + " attempts=" + task.attempts + " delay=" + delay
                    + " cooldown=" + mCooldownFailures
                    + " breaker=" + (counts ? "counted" : "ignored")
                    + " reason=" + reason);
        }
        synchronized (mLock) {
            task.nextAttemptAt = System.currentTimeMillis() + delay;
            saveThrottledLocked();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 远端目录怎么分，由 {@code remote_layout} 决定。
     *
     * <p>默认按相册分：相册界面上本来就是按文件夹分组的，只留年/月的话用户到了 WebDAV 上
     * 就分不清哪张来自哪个相册。另两种（按时间、全平铺）保留给"建不出子目录"的服务器
     * —— 三种写法都会在 PUT 报 404/409 时逐级 MKCOL，服务器连 MKCOL 都不支持时只有全平铺能用。
     */
    private static String remoteDirFor(Config config, Task task) {
        String remoteRoot = config.getRemoteRoot();
        String layout = config.getRemoteLayout();
        if (Config.LAYOUT_FLAT.equals(layout)) {
            return WebDavClient.joinPath(remoteRoot, "");
        }
        if (Config.LAYOUT_ALBUM.equals(layout)) {
            String album = WebDavClient.albumDirOf(task.path);
            if (!album.isEmpty()) {
                return WebDavClient.joinPath(remoteRoot, album);
            }
            // 推不出相册目录（文件不在已知存储根下）时退回按时间分：
            // 换个分法总比把文件全堆在根目录里强
        }
        return WebDavClient.joinPath(remoteRoot, yearMonthOf(task));
    }

    private static String yearMonthOf(Task task) {
        long time = task.date > 0 ? task.date : task.mtime;
        if (time <= 0) {
            time = System.currentTimeMillis();
        }
        return WebDavClient.monthDir(time);
    }

    private static String key(String path, long size, long mtime) {
        return path + "|" + size + "|" + mtime;
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

    private File stateFile() {
        File dir = new File(mContext.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Logger.w("event=state_dir_failed dir=" + dir.getAbsolutePath());
        }
        return new File(dir, STATE_FILE);
    }

    /**
     * 队列状态里记一份"这些已完成记录与退避是对着哪个目标攒出来的"。
     * 用户在相册被杀掉之后才改好配置时，新进程只能靠它判断 state.json 里那套账还能不能信：
     * 目标变了就清空 done 与退避，否则换完服务器会看到一个"全都传过了"的空队列。
     *
     * <p>远端根目录与分目录方式也要算进来：done 记录的意思是"这一份已经传到当前这个位置了"，
     * 用户把目录换掉、或把分目录方式从按时间改成按相册之后，同一个文件该待的位置就变了，
     * 旧账对新位置完全无效。这两项都不是凭据，落盘没有风险。
     * 密码仍然不落盘，所以这里只取 safeHost 与用户名（运行时那次比对由 {@link #sameTarget} 负责，
     * 它拿得到密码，能额外发现改密码）。
     */
    private String targetSignature() {
        Config config = mConfig;
        if (config == null) {
            return "";
        }
        return Config.safeHost(config.getBaseUrl()) + "|" + config.getUsername()
                + "|" + config.getRemoteRoot() + "|" + config.getRemoteLayout();
    }

    /**
     * 记一次落盘请求，但不立刻写。
     *
     * <p>done 表上限 20000 条、序列化出来约 1.6MB，每传完一张就整份重写一遍的话，
     * 一万两千张照片就是十几 GB 的写入。上传本身提速之后这笔开销就成了主要成本之一。
     * 间隔内的改动只打标记，等 {@link #flushState()} 或下一张到期时再补写。
     * 丢了最多是重传（PUT 是覆盖写），不会丢数据。
     */
    private void saveThrottledLocked() {
        long now = System.currentTimeMillis();
        if (now - mLastSave >= SAVE_INTERVAL) {
            writeStateLocked();
            return;
        }
        mSavePending = true;
    }

    /** 把节流期间攒下的改动补写掉；调用方必须持有 mLock */
    private void flushLocked() {
        if (mSavePending) {
            writeStateLocked();
        }
    }

    private void flushState() {
        synchronized (mLock) {
            flushLocked();
        }
    }

    private void writeStateLocked() {
        if (!mRunning) {
            // 热重载交接后新 generation 会自己从 state.json 重建；旧 worker 这时再落一次盘，
            // 会把新队列刚写入的视图整个盖掉
            return;
        }
        File file = stateFile();
        File tmp = new File(file.getParentFile(), STATE_FILE + ".tmp");
        mSavePending = false;
        mLastSave = System.currentTimeMillis();
        try {
            JSONObject root = new JSONObject();
            JSONArray pending = new JSONArray();
            for (Task task : mPending.values()) {
                JSONObject obj = new JSONObject();
                obj.put("p", task.path);
                obj.put("s", task.size);
                obj.put("m", task.mtime);
                obj.put("v", task.video);
                obj.put("d", task.date);
                if (task.rowId != null) {
                    obj.put("r", task.rowId);
                }
                obj.put("a", task.attempts);
                obj.put("n", task.nextAttemptAt);
                pending.put(obj);
            }
            JSONObject done = new JSONObject();
            for (Map.Entry<String, Long> entry : mDone.entrySet()) {
                done.put(entry.getKey(), entry.getValue());
            }
            root.put("target", targetSignature());
            root.put("pending", pending);
            root.put("done", done);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) {
                Files.deleteIfExists(tmp.toPath());
            }
        } catch (Throwable t) {
            Logger.w("event=state_save_failed", t);
        }
    }

    private void load() {
        File file = stateFile();
        if (!file.exists()) {
            return;
        }
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            String savedTarget = root.optString("target", "");
            JSONArray pending = root.optJSONArray("pending");
            if (pending != null) {
                for (int i = 0; i < pending.length(); i++) {
                    JSONObject obj = pending.getJSONObject(i);
                    String rowId = obj.optString("r", "");
                    Task task = new Task(
                            obj.optString("p", ""),
                            obj.optLong("s", 0L),
                            obj.optLong("m", 0L),
                            obj.optBoolean("v", false),
                            obj.optLong("d", 0L),
                            rowId.isEmpty() ? null : rowId);
                    task.attempts = obj.optInt("a", 0);
                    task.nextAttemptAt = obj.optLong("n", 0L);
                    if (!task.path.isEmpty()) {
                        mPending.put(task.key(), task);
                    }
                }
            }
            JSONObject done = root.optJSONObject("done");
            if (done != null) {
                Iterator<String> keys = done.keys();
                List<String> list = new ArrayList<>();
                while (keys.hasNext()) {
                    list.add(keys.next());
                }
                for (String key : list) {
                    mDone.put(key, done.optLong(key, 0L));
                }
            }
            synchronized (mLock) {
                if (!savedTarget.isEmpty() && !savedTarget.equals(targetSignature())) {
                    targetChangedLocked();
                    // 立刻落盘：否则每开一次进程就重来一次，把"已完成"反复清空、文件反复重传
                    writeStateLocked();
                }
            }
            Logger.i("event=state_loaded pending=" + mPending.size() + " done=" + mDone.size());
        } catch (Throwable t) {
            Logger.w("event=state_load_failed", t);
        }
    }

    private static final class Task {
        final String path;
        final long size;
        final long mtime;
        final boolean video;
        final long date;
        /** cloud 表行号，跨进程重启也要留着：上传成功的记账靠它 */
        final String rowId;
        int attempts;
        long nextAttemptAt;

        Task(String path, long size, long mtime, boolean video, long date, String rowId) {
            this.path = path;
            this.size = size;
            this.mtime = mtime;
            this.video = video;
            this.date = date;
            this.rowId = rowId;
        }

        String key() {
            return UploadQueue.key(path, size, mtime);
        }
    }
}
