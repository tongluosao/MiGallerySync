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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebDAV 上传队列：去重、持久化、**并行上传（路数可调）**、服务器级冷却退避。
 *
 * <p>运行在相册进程，必须在后台线程完成全部 IO 与网络，绝不能阻塞相册的同步线程。
 * 去重键为 路径+大小+修改时间，已完成的记录会保留，避免相册因 localFlag 未清除而反复入队时重复上传。
 *
 * <p>为什么并行而不是串行：单条连接上"发一个请求、等服务器收完再回响应"的往返占了
 * 每张照片的大头，串行时链路大部分时间在空等。每路各持一条 keep-alive 连接，
 * 既能把等待叠起来，又不至于把自建服务器打满。路数由 {@code upload_concurrency} 决定
 * （1..8，默认 3）：真机上是 OpenList 挂网盘时并发再高收益递减，而直连自建 WebDAV
 * 时多开几路明显更快，所以这个值交给用户。
 *
 * <p>"发完了还要等复核"曾经也占着这一路：PUT 的请求体写完之后，为了识破"回 2xx 却没落盘"的
 * 假写存储，还要 HEAD/PROPFIND 复核好几轮（见 {@code WebDavClient.storedAs}），
 * 那段时间界面停在 100% 而并行通道被白白占住。打开 {@code verify_async} 之后，
 * 复核挪到 {@link #mVerifying} 对应的后台线程，通道立刻空出来去传下一项。
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
    /**
     * 放弃之后的间隔上限。
     *
     * <p>见 {@link #giveUpDelay(int)}：每再失败一轮就翻一倍，最后停在这个上限。
     * 封顶 6 小时是"既不放弃这项、也不再拿它换流量"之间的折中 —— 服务器换了、限流解了、
     * 大文件被放行了，半天之内一定会被再试一次；而每 6 小时最多只付一个请求体。
     */
    private static final long RETRY_GIVE_UP_MAX = 6 * 60 * 60_000L;

    /**
     * 撞上"远端路径写锁"（HTTP 423）之后的首次退避。
     *
     * <p>为什么第一次就要 5 分钟，而不是跟普通退避一样 15 秒起：423 不是"抖一下就好了"，
     * 它是服务器进程内那条路径的写锁还没释放（OpenList 用 `webdav.NewMemLS()`，纯内存锁）。
     * 而每一次尝试的代价是**一整个请求体**——因为 OpenList 是读完 body 才判锁的。
     * 真机实测：605MB 的视频在 11 分钟里被普通退避推着重试 5 次，白烧 3.0GB 上行，
     * 远端一个字节都没多。5 分钟起手，就是让"锁还占着"这种情况不再被反复试探。
     */
    private static final long PATH_LOCK_FIRST_DELAY = 5 * 60_000L;
    /** 同一条路径反复撞锁时的间隔上限，与 {@link #RETRY_GIVE_UP_MAX} 一致（6 小时） */
    private static final long PATH_LOCK_MAX_DELAY = 6 * 60 * 60_000L;
    /**
     * 估"这条锁大概还要攥多久"时用的速率：**0.4MB/s，取实测最坏那一档**。
     *
     * <p>依据（2026-10-06 服务端 + 客户端双侧实测）：锁在"服务器把整份文件推给移动云盘"
     * 期间一直被攥着，而那一档的吞吐是 605MB → 约 24 分钟、1.32GB → 实测 56m30s
     * （≈0.42MB/s）；只有 1GB 以下的文件才量到 3.9MB/s。
     * 取最坏档 = 宁可多等，绝不再白烧一份上行。
     */
    private static final long PATH_LOCK_RATE_BYTES_PER_SEC = 400L * 1024L;
    /**
     * 按大小估出来的等待上限。取 60 分钟而不是 30：实测 1.32GB 那笔锁了 56m30s，
     * 卡在 30 分钟会让它下一次仍然撞在锁上 —— 而那一撞的代价是一整份 1.32GB。
     * 60 分钟能盖住实测最大的那一档，再大的文件靠后面的翻倍档继续拉开。
     */
    private static final long PATH_LOCK_GUESS_MAX = 60 * 60_000L;
    /**
     * 多大的文件值得在发送前先花几百字节探一次锁。
     *
     * <p>0 字节探针换来的是"不必白烧一整份文件"，所以门槛越低越省流量；但每一项都探
     * 就多一次往返。取 4MB：这个量级以下最坏损失只有几 MB，而它以上一次白烧就是几十
     * 到几百 MB。已经撞过锁的路径**无视**这个门槛（见上传前的 worthProbing）。
     */
    private static final long PATH_LOCK_PROBE_MIN_BYTES = 4L * 1024 * 1024;
    /**
     * 撞锁后至少要等多久才值得再试一次 —— **按这一项的大小算**。
     *
     * <p>为什么必须按大小算（2026-10-06 实测的完整机制）：OpenList 判写锁是在**读完请求体
     * 之后**，然后才把整份文件推给移动云盘 —— 也就是说**锁是在"推给网盘"这段时间里被攥着的**，
     * 而这段时间与文件大小成正比。实测两个数据点：
     * <pre>
     *   605,134,181 字节（605MB）: 一整个请求体发完约 80 秒，但锁要等到网盘收完才放，
     *                             按实测 0.42MB/s 算约 24 分钟
     *   1,416,598,318 字节（1.32GB）: 服务器日志记着那笔持锁 **56m30s** 才回 201
     *   而小的（200MB）只要 41 秒就整个完成
     * </pre>
     * 于是出现了最要命的一种自撞：**我们自己的上一次尝试就是锁的持有者**。
     * 605MB 那项 14:10:30 发完整份后去推网盘（要 20 多分钟），而普通退避 15~30 秒就把
     * 第二次尝试送上去了 —— 第二次发完 605MB，正好撞上第一次还没放掉的锁，回 423。
     * 14:10 到 14:20 的 5 次重试就是这么串起来的：**每一次都在撞上一次的自己**，
     * 白烧 3.0GB，远端一个字节都没多。
     *
     * <p>所以这里的速率必须按**最坏**的那档取，而不是平均值（实测 3.9MB/s 那档是 1GB 以下的
     * 小文件；一旦到 1.3GB 就掉到 0.42MB/s）。取 0.4MB/s 的后果是"有可能多等几分钟"，
     * 取乐观值的后果是"必然再白烧一整份上行" —— 前者只花时间，后者花时间又占满上行，
     * 所以取向很明确：宁可多等。
     */
    private static long firstPathLockDelay(long size) {
        long guess = size / PATH_LOCK_RATE_BYTES_PER_SEC;   // 0.4MB/s，实测最坏档
        return Math.min(Math.max(guess, PATH_LOCK_FIRST_DELAY), PATH_LOCK_GUESS_MAX);
    }

    /**
     * "设备这一层"出问题时的整队退避档位：60s / 2min / 5min / 15min / 30min（封顶）。
     *
     * <p>与服务器级冷却（{@link #COOLDOWN_BASE} 那套，最长 3 分钟）分开，因为两者的
     * **时间尺度根本不同**：服务器繁忙是几秒到几分钟，而"解析不出域名"这种环境故障
     * 往往是运营商/路由/VPN 的问题，几分钟内好不了，且失败的项**一个字节都没发出去**。
     *
     * <p>关键是它**不计进 {@code attempts}** —— 否则一次 20 分钟的环境故障会把整批
     * `attempts` 已经到上限的项各自推向 4~6 小时之后（真机 2026-10-06 11:20 那次就是这样）。
     * 环境恢复后第一项一试就成，冷却立刻清零，整队马上回到正常节奏。
     */
    private static final long ENV_COOLDOWN_BASE = 60_000L;
    private static final long ENV_COOLDOWN_MAX = 30 * 60_000L;
    private static final long WAIT_NO_TASK = 120_000L;
    private static final long WAIT_NO_NETWORK = 300_000L;
    /**
     * 熔断要攒够几轮失败。
     *
     * <p>一次"整台服务器挂了"会在同一轮里让**每一路**都失败一次，所以阈值必须按轮数折算，
     * 不能按次数写死：3 路时 3 轮 = 9 次，与改造前写死的 9 次一致；8 路时是 24 次，
     * 语义始终是"每路都试了约 3 轮"。定得太低会在服务器偶发抖动时误熔断，
     * 定得太高则真挂时迟迟不让位原链路 —— 后者是本模块最不能出现的结果，所以宁可偏保守。
     */
    private static final int BREAKER_ROUNDS = 3;
    /**
     * 落盘复核的后台线程数。
     *
     * <p>复核就是"发个 HEAD 等服务器回"，纯等待，不需要跟上传一样多：两路足够把复核的等待
     * 叠起来，又不至于为几张照片再开一堆连接。
     */
    private static final int VERIFY_THREADS = 2;
    /**
     * 待复核项的积压上限。满了就退回同步复核（发完自己等），宁可这一项慢一点，
     * 也不能让待复核项无上限地堆在内存里。
     */
    private static final int MAX_VERIFY_BACKLOG = 64;
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

    /**
     * "远端目录里有哪些名字"这份名单能信多久。
     *
     * <p>见 {@link #mRemoteNames}。取一分钟是因为相册一次交下来的项基本都在同一批里传完，
     * 一分钟足够让同目录的几十项共用一次 PROPFIND；再长就会让"别人刚传上去的同名文件"
     * 也被判成远端没有（代价只是多传一次覆盖写，无害），但会让"用户自己刚删掉远端文件"
     * 这件事在整整一分钟里看不见（那才是真的少传一个文件）。
     */
    private static final long REMOTE_DIR_CACHE_MS = 60_000L;
    /** 名单缓存最多留几个目录：按时间分目录时一个月能跨几十个，不给上限就是慢慢涨 */
    private static final int MAX_CACHED_DIRS = 64;
    /**
     * "按目标分开记录"模式下最多同时留几条记录（见 {@link #mLedgers}）。
     *
     * <p>四条够"家里 NAS / 单位服务器 / 旧账号 / 新账号"这种用法来回切；
     * 每条最多 {@link #MAX_DONE_RECORDS} 项，落盘时会一起写进 state.json，所以不能留太多 ——
     * 那是每次落盘都要序列化的体积。
     */
    private static final int MAX_LEDGERS = 4;

    private static volatile UploadQueue sInstance;

    /** Candidate hint only: destructive operations must still verify remote bytes. */
    public static boolean wasVerifiedUploadCurrentTarget(String canonicalPath, long size, long mtime) {
        UploadQueue queue = sInstance;
        if (queue == null || canonicalPath == null) return false;
        synchronized (queue.mLock) {
            String current = targetSignature(queue.mConfig);
            return !current.isEmpty() && current.equals(queue.mDoneSignature)
                    && queue.mDone.containsKey(key(canonicalPath, size, mtime));
        }
    }

    private final Context mContext;
    private final OriginalStore mOriginalStore;
    /** 用户主动删掉的垃圾副本的远端路径账本，见 {@link RemoteSuppress} */
    private final RemoteSuppress mRemoteSuppress;
    private final Object mLock = new Object();
    private final LinkedHashMap<String, Task> mPending = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> mDone = new LinkedHashMap<>();
    /**
     * "按目标分开记录"模式下的**其他目标**各自的已完成记录：目标签名 → 那一条记录。
     *
     * <p>{@link #mDone} 永远装着"当前这个目标"的；换目标时把当前这条存进这里、
     * 再把新目标那条（如果有）取出来装回 {@code mDone}。这样"传完一项"这条热路径
     * 一行都不用改，分开记录只发生在换目标那一瞬间。
     *
     * <p>为什么要有它：{@link #mDone} 是按本地文件（路径+大小+mtime）记的，只说明
     * "这一份传到过某个远端"。用户换回旧账号时期望"不用重传"，换成新账号时期望"确实传一遍" ——
     * 只有把记录按目标分开存才能同时满足。留 {@link #MAX_LEDGERS} 条（LRU）：
     * 用户反复来回切账号时最近那几条还在，更早的丢掉换一轮重传，不是丢数据。
     *
     * <p>受 {@link #mLock} 保护。
     */
    private final LinkedHashMap<String, LinkedHashMap<String, Long>> mLedgers = new LinkedHashMap<>();
    /**
     * {@link #mDone} 这条记录是对着哪个目标攒的。
     *
     * <p>它是"这条记录属于谁"的运行时真相，不能现算：{@code mConfig} 在 {@link #setConfig}
     * 里总是已经换成新目标了，那时再拿它算签名，得到的是新目标的签名 —— 旧记录就会被
     * 记到新目标名下（换账号后一条记录都不用重传）。
     */
    private String mDoneSignature = "";
    /**
     * 正在被某个 worker 传的项（键同 {@link Task#key()}），受 {@link #mLock} 保护。
     *
     * <p>它**只是内存态**，用来让多个 worker 互斥领取任务。在传的项不会被移出
     * {@link #mPending}，所以 state.json 的结构与语义完全不变：进程被杀时这几项还在
     * pending 里，下次启动被重新领取并覆盖写（PUT 幂等），不会丢任务。
     */
    private final Set<String> mInflight = new HashSet<>();
    /**
     * 字节已经发完、正在等落盘复核的项，受 {@link #mLock} 保护。
     *
     * <p>它跟 {@link #mInflight} 是**两个阶段**，分开记正是这次改动的要害：
     * 从 worker 的角度这些项已经结束（上传通道要立刻去传下一项），但从队列的角度它们还没传完
     * （复核失败仍要重试）。所以它们照旧留在 {@link #mPending} 里、不进 {@link #mDone}，
     * 只是不再被新的 worker 领走，也不再计入"我这一路手上还有活"。
     *
     * <p>进程被杀时这里的东西直接丢掉：state.json 里这些项还是 pending，下次启动重新 PUT
     * 一遍（覆盖写，幂等），代价只是再传一次，不是丢数据。
     */
    private final Set<String> mVerifying = new HashSet<>();
    /**
     * 因为超过体积上限被跳过的项（键 = 路径@体积），受 {@link #mLock} 保护。
     *
     * <p>**只为日志去重**：相册每轮同步都会把同一批脏项再交下来，而超限的项永远传不上去，
     * 不记一下就变成每轮刷一屏。它不参与任何判定 —— 上限调大之后同一项会正常入队上传。
     */
    private final Set<String> mTooLargeLogged = new HashSet<>();
    /**
     * 交接给复核线程的队列，受 {@link #mLock} 保护。
     *
     * <p>有界（{@link #MAX_VERIFY_BACKLOG}）：复核比上传快得多，正常不会积压；
     * 真积压了就退回同步复核，绝不让它无限涨。
     */
    private final ArrayDeque<Verify> mVerifyQueue = new ArrayDeque<>();
    /**
     * 当前并行上传路数，取自 {@code upload_concurrency}（1..{@link Config#MAX_UPLOAD_CONCURRENCY}）。
     *
     * <p>可调：用户在设置页改完是跨进程写共享偏好 → 相册进程收到 {@code config_changed} →
     * {@link #setConfig} 在这里生效。调小时越界的那几路会在**传完手上这一项之后**退出，
     * 不会把正在传的请求砍掉。
     */
    private volatile int mConcurrency = Config.DEFAULT_UPLOAD_CONCURRENCY;

    private volatile Config mConfig;
    private volatile SyncMarker mMarker;
    private volatile HostSyncState mProgress;
    /** 上传见底时通知谁；见 {@link IdleListener} */
    private volatile IdleListener mIdleListener;
    /** 多个 worker 会同时自增，Hook 线程还会跨线程读，必须原子 */
    private final AtomicInteger mConsecutiveFailures = new AtomicInteger();
    /**
     * 上传线程数组。**按上限开满**，实际启用几路由 {@link #mConcurrency} 决定：
     * 调大时现开新线程（worker 只在启动时创建，中途不能凭空多出来），
     * 调小时多出来的那几路自己退出循环。
     */
    private final Thread[] mWorkers = new Thread[Config.MAX_UPLOAD_CONCURRENCY];
    /** worker 循环在锁外读它，必须 volatile；热重载时由 shutdown() 置假 */
    private volatile boolean mRunning = true;

    /**
     * 复用的 WebDAV 客户端：连接 keep-alive 的载体，目标变了才换。**每路一个** ——
     * 一条连接上同时发两个请求会串包，而且并行等待正是提速的来源。
     */
    private final WebDavClient[] mClients = new WebDavClient[Config.MAX_UPLOAD_CONCURRENCY];
    private final String[] mClientTargets = new String[Config.MAX_UPLOAD_CONCURRENCY];
    /**
     * 复核线程自己的连接，**必须与上传的 {@link #mClients} 分开**。
     *
     * <p>复核时对应的 worker 已经去传下一项了，两条线程会同时用同一个客户端：
     * 一条连接上并行两个请求会串包，互相把对方的响应读走。
     */
    private final WebDavClient[] mVerifyClients = new WebDavClient[VERIFY_THREADS];
    private final String[] mVerifyTargets = new String[VERIFY_THREADS];
    private final Thread[] mVerifiers = new Thread[VERIFY_THREADS];
    private long mLastSave;
    private boolean mSavePending;
    /** 本轮空转之前传上去了几项。3 个 worker 都会加，只有空闲时那个读 */
    private final AtomicInteger mUploadedSinceIdle = new AtomicInteger();
    /** 连续失败次数，决定队列冷却多久；3 个 worker 都会改 */
    private final AtomicInteger mCooldownFailures = new AtomicInteger();
    /** 冷却档位上次抬升的时刻，用来把并发下同一轮的多个失败并成一次 */
    private long mLastCooldownBumpAt;
    private final Object mCooldownLock = new Object();
    /** "设备这一层"（解析/网络）连续失败的次数，驱动 {@link #envCooldown()}；成功即清零 */
    private final AtomicInteger mEnvFailures = new AtomicInteger();
    private long mLastEnvBumpAt;
    /** 一次性迁移（{@link #repairBackoffDebt}）是否已经做过，落盘在 state.json 的 debt_fixed */
    private boolean mDebtFixed;
    /** 下次请相册枚举的时间点。只在 {@link #onQueueIdle} 里读写，而它同一时刻只有一个 worker 能进 */
    private long mNextIdleSyncAt;
    /** 当前催相册的间隔：入队线程会把它重置，所以得 volatile */
    private volatile long mIdleSyncDelay = IDLE_SYNC_BASE;
    /**
     * 本轮"队列真空闲"是否已经通报过。
     *
     * <p>3 个 worker 会各自发现"没活可干"，但收尾（{@code endAll} + 催相册）只能做一次：
     * 否则催同步的间隔会被连翻三倍，宿主的"正在上传"也会被反复开关。领取到任务或
     * 有新项入队时复位，下次空闲再通报。
     */
    private boolean mIdleNotified;

    /**
     * {@link #mPending} 的结构版本，每次增删都要加一。
     *
     * <p>进度页要显示"还在排队的有哪些"，整张列表序列化出来有十几 KB，2Hz 地发等于把
     * Binder 当水管用。带上版本号之后，客户端只在版本变了（或隔几秒兜一次底）才重传列表，
     * 平时的帧还是几百字节。
     */
    private volatile int mPendingVersion;

    /**
     * 远端目录内容的短期缓存：目录 → 该目录下的直接子项名字集合。
     *
     * <p>上传前那一次"远端有没有同名文件"的判断很便宜，但**每个文件一次** PROPFIND 也不便宜：
     * 一天几千项、每项一次往返，还要在手机上不停地解密连接。按目录缓存之后，
     * 同一批（相册一次交下来的一项一条相册目录，通常几十项同目录）只要列一次。
     *
     * <p>生命周期是 {@link #REMOTE_DIR_CACHE_MS}：缓存太久就会拿一份旧名单去判"远端没有"，
     * 而那期间别的客户端可能已经传上去了（那时多传一次覆盖写，无害）；缓存太短又等于没缓存。
     * 一分钟足够覆盖"同一批连着传完"这个场景。条目数也有上限，防的是用户把
     * {@code remote_layout} 设成按时间分、一个月跨几十个目录时把内存撑起来。
     *
     * <p>只受 {@link #mLock} 保护（多个 worker 并发读）。
     */
    private final LinkedHashMap<String, RemoteNames> mRemoteNames = new LinkedHashMap<>();

    /** {@link #mRemoteNames} 里的一条：名字 → 大小（-1 = 未知/目录）+ 取回来的时刻 */
    private static final class RemoteNames {
        final Map<String, Long> sizes;
        final long at;

        RemoteNames(Map<String, Long> sizes, long at) {
            this.sizes = sizes;
            this.at = at;
        }
    }

    /**
     * 把在传状态发给模块 App 的进度页。
     *
     * <p>由队列自己建而不是让 Hook 侧注入：队列知道每一项的开始/结束与字节数，进度帧要的
     * 就是这些；Hook 侧只负责把队列挂起来，多一层转手没有意义。
     */
    private final UploadProgressPublisher mPublisher;

    private UploadQueue(Context context, Config config) {
        mContext = context.getApplicationContext();
        mConfig = config;
        mOriginalStore = OriginalStore.get(mContext);
        mRemoteSuppress = RemoteSuppress.get(mContext);
        load();
        mPublisher = UploadProgressPublisher.start(mContext);
        mPublisher.addQueueView(new UploadProgressPublisher.QueueView() {
            @Override
            public int pendingCount() {
                synchronized (mLock) {
                    // mPending 里含着正在传的、以及正在后台复核的那几项（都刻意不移出，
                    // 见 mInflight / mVerifying 的注释），对客户端来说"还没轮到"要把它们减掉
                    return Math.max(mPending.size() - mInflight.size() - mVerifying.size(), 0);
                }
            }

            @Override
            public long pendingBytes() {
                synchronized (mLock) {
                    // 先全量求和再减掉在传/复核的那几个，而不是逐项 contains 判断：
                    // 后者要为每个 pending 项拼一次去重键字符串（路径+大小+时间），
                    // 全量首备时 pending 上万个，心跳每 500ms 一次就是每秒上万次分配。
                    // 在传与复核合起来最多十几项，反查的代价可以忽略
                    long total = 0;
                    for (Task task : mPending.values()) {
                        total += task.size;
                    }
                    for (String key : mInflight) {
                        Task task = mPending.get(key);
                        if (task != null) {
                            total -= task.size;
                        }
                    }
                    for (String key : mVerifying) {
                        Task task = mPending.get(key);
                        if (task != null) {
                            total -= task.size;
                        }
                    }
                    return Math.max(total, 0);
                }
            }

            @Override
            public int pendingPhotos() {
                synchronized (mLock) {
                    int count = 0;
                    for (Task task : mPending.values()) {
                        if (task.video) {
                            continue;
                        }
                        String key = task.key();
                        if (mInflight.contains(key) || mVerifying.contains(key)) {
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
                    for (Task task : mPending.values()) {
                        if (!task.video) {
                            continue;
                        }
                        String key = task.key();
                        if (mInflight.contains(key) || mVerifying.contains(key)) {
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
                    List<UploadProgressPublisher.PendingItem> out =
                            new ArrayList<>(Math.min(limit, mPending.size()));
                    // 含正在传的那几项，不跳过：客户端要在传项排在列表最前面，而它需要
                    // 队列序才知道排队部分怎么排；两边共用一个 key，去重由客户端做。
                    // worker 从队首领取，所以这 3 项本来就排在最前，顺序天然是对的。
                    for (Task task : mPending.values()) {
                        if (out.size() >= limit) {
                            break;
                        }
                        out.add(new UploadProgressPublisher.PendingItem(
                                task.key(), baseName(task.path), task.path, task.size, task.video,
                                ProgressProtocol.DIR_UP));
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
                return isHealthy();
            }
        });
        startWorkers();
        startVerifiers();
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
        List<Verify> cancelled;
        synchronized (mLock) {
            // 先把最后一次进度落盘再停：mRunning 置假后 saveLocked 会直接跳过
            flushLocked();
            mRunning = false;
            pending = mPending.size();
            mInflight.clear();
            // 同路径串行的占用也要清：换代之后由新的一份队列自己重新登记，
            // 残留的路径会让新 worker 永远挑不到那几项
            mInflightRemotePaths.clear();
            // 撞锁计数同理：它是"这一轮"的观测，换代后从零开始（服务器侧那条锁
            // 很可能已经自己释放了，不该让新 generation 继承一个几小时的退避）
            mPathLockStrikes.clear();
            // 还没轮到复核的项直接作废。它们仍在 mPending 里（没移出、没记 done），
            // 下次启动会被重新 PUT 一遍（覆盖写、幂等），代价只是再传一次，不是丢数据
            cancelled = new ArrayList<>(mVerifyQueue);
            mVerifyQueue.clear();
            mVerifying.clear();
            mLock.notifyAll();
        }
        // 这些项已经 taskStart 过、也 beginUpload 过，得自己把账还回去
        for (Verify job : cancelled) {
            mPublisher.taskEnd(job.key, false);
        }
        if (!cancelled.isEmpty()) {
            Logger.i("event=queue_shutdown_verify_dropped count=" + cancelled.size());
        }
        // 别让相册的界面一直挂在"正在上传"上。用 endAll 而不是 endUpload：多路并发下
        // "谁最后结束"不确定，endUpload 只是减引用，收不了尾
        HostSyncState progress = mProgress;
        if (progress != null) {
            for (Verify ignored : cancelled) {
                progress.endUpload();
            }
            progress.endAll();
        }
        releaseClient();
        releaseVerifyClients();
        // 心跳线程也得停：它是 daemon 不会拖住进程退出，但留着的话热重载后新旧两条
        // 心跳会一起发帧，客户端收到的进度会在两套数据之间跳
        mPublisher.stop();
        Logger.i("event=queue_shutdown pending=" + pending
                + " workers=" + mConcurrency);
        for (Thread worker : mWorkers) {
            if (worker != null) {
                worker.interrupt();
            }
        }
        for (Thread verifier : mVerifiers) {
            if (verifier != null) {
                verifier.interrupt();
            }
        }
        // 单例也得清掉：worker 已经停了，留着它的话下一次 get() 会拿到一个永远不会动的队列
        synchronized (UploadQueue.class) {
            if (sInstance == this) {
                sInstance = null;
            }
        }
    }

    /** 关掉第 index 路自己的连接 */
    private void releaseClientAt(int index) {
        WebDavClient client = mClients[index];
        mClients[index] = null;
        mClientTargets[index] = null;
        if (client != null) {
            client.close();
        }
    }

    private void releaseClient() {
        for (int i = 0; i < Config.MAX_UPLOAD_CONCURRENCY; i++) {
            releaseClientAt(i);
        }
    }

    /** 关掉第 index 个复核线程自己的连接 */
    private void releaseVerifyClientAt(int index) {
        WebDavClient client = mVerifyClients[index];
        mVerifyClients[index] = null;
        mVerifyTargets[index] = null;
        if (client != null) {
            client.close();
        }
    }

    private void releaseVerifyClients() {
        for (int i = 0; i < VERIFY_THREADS; i++) {
            releaseVerifyClientAt(i);
        }
    }

    /**
     * WebDAV 侧是否可用。**这只是给界面看的观测信号，不再决定放不放行原链路。**
     *
     * <p>早先连续失败到阈值时 Hook 会退回小米云"保底"。现在替换模式的语义是"只传 WebDAV，
     * 一张都不去小米云"：不可用就继续拦着、继续退避重试，队列和相册的 localFlag 都原样保留，
     * 下一轮枚举还会把它们交回来 —— 晚传不等于丢失，传到不想要的云上才是真的错。
     */
    public boolean isHealthy() {
        return mConsecutiveFailures.get() < unhealthyFailures();
    }

    /**
     * 熔断阈值：每路都要试够 {@link #BREAKER_ROUNDS} 轮。
     *
     * <p>路数可调之后阈值不能再写死：8 路时一次"整台服务器挂了"会一轮凑出 8 次失败，
     * 写死 9 就变成"一轮半就熔断"，服务器只是抖动也可能被误判。
     */
    private int unhealthyFailures() {
        return BREAKER_ROUNDS * Math.max(mConcurrency, 1);
    }

    /** 当前连续失败次数，仅用于日志 */
    public int consecutiveFailures() {
        return mConsecutiveFailures.get();
    }

    /**
     * 队列里还没传完的项，按 [照片数, 视频数] 分开。
     *
     * <p>给宿主用：相册界面上那句"正在同步 N 个照片和视频"取的是它自己的
     * {@code SyncStateInfo.getDirtyCount()}，也就是数据库里 {@code localFlag IN (7,8)} 的行数。
     * 替换模式下相册每批只交给我们 3 项（枚举是一批一批来的），那 3 项传完之前 localFlag 一直
     * 是 7/8，于是那个数字永远停在 3，完全看不出后面还积压着几千项。接管它之后这个数字
     * 才是真的"还剩多少要传"。
     *
     * <p>在传的项本来就在 {@link #mPending} 里（刻意不移出），所以这里不用另外加。
     */
    public int[] queuedCounts() {
        synchronized (mLock) {
            int photos = 0;
            int videos = 0;
            for (Task task : mPending.values()) {
                if (task.video) {
                    videos++;
                } else {
                    photos++;
                }
            }
            return new int[]{photos, videos};
        }
    }

    /** 从绝对路径取文件名。只切最后一个斜杠，比 new File(path).getName() 省一次分配 */
    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 && slash + 1 < path.length() ? path.substring(slash + 1) : path;
    }

    /** 上传成功后补写相册落册标记的实现；为 null 时不记（镜像模式由宿主自己记） */
    public void setMarker(SyncMarker marker) {
        mMarker = marker;
    }

    /**
     * 上传队列见底的通知。
     *
     * <p>给下载队列用：上传在动 = 用户正在用这个 App，那时补下载最不容易打扰；
     * 上传长时间不动就说明没人盯着，下载自己按翻倍后的间隔慢慢来。
     */
    public interface IdleListener {
        void onIdle();
    }

    public void setIdleListener(IdleListener listener) {
        mIdleListener = listener;
    }

    /**
     * 进度发布器。下载队列要与上传共用同一个：进度页只有一张，两个队列各挂一个
     * {@link UploadProgressPublisher.QueueView}，计数与列表由发布器合起来。
     */
    public UploadProgressPublisher publisher() {
        return mPublisher;
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
            int failures = mConsecutiveFailures.getAndSet(0);
            if (failures != 0) {
                Logger.i("event=queue_health_reset failures=" + failures);
            }
            // 旧连接是对着旧目标建的，keep-alive 留着只会连错地方
            releaseClient();
            releaseVerifyClients();
            synchronized (mLock) {
                targetChangedLocked(config);
                // 缓存里的远端名单也是对旧目标列的，换目标的这一刻一律作废
                mRemoteNames.clear();
            }
        }
        applyConcurrency(config.getUploadConcurrency());
        // 上限变了就把"已经被跳过"的日志记录清掉：用户把上限调大（或设成不限）之后，
        // 那些文件本来就要重新入队，日志里也该重新出现一条，不然会以为改动没生效
        if (previous != null && previous.getMaxUploadBytes() != config.getMaxUploadBytes()) {
            synchronized (mLock) {
                mTooLargeLogged.clear();
            }
        }
    }

    /**
     * 清掉"已完成"这条记录，让相册重新交下来的项真的重传一遍。用户点"重置备份状态"时调用。
     *
     * <p>为什么非清不可：done 记录是按"本地文件（路径+大小+mtime）已经传到当前位置了"记的。
     * 重置了相册的 {@code localFlag} 之后，相册会把同一批项重新交下来 —— 但这些项在 done 表里
     * 都有记录，队列会按 key 命中直接判成"传过了"跳过，**一个字节都不发**，
     * 重置就成了"相册显示重新开始备份、远端却还是什么都没多"。
     *
     * <p>与 {@link #targetChangedLocked} 的区别：那个是处理"换目标"（分开记录模式下还要换记录），
     * 这里没有目标变化，就是**同一条记录清空**。分开记录模式下也照清 —— 用户点这个按钮的意图
     * 就是"远端缺东西，重传一遍"，留着那条记录等于点了个寂寞。
     *
     * <p>清完**立刻落盘**：进程在下次正常落盘之前被杀（一键清理、崩溃）的话，
     * 旧 done 表会从 state.json 原样读回来，这次重置就白做了。
     */
    public void resetLedger() {
        int done;
        synchronized (mLock) {
            done = mDone.size();
            mDone.clear();
            mLedgers.clear();
            // 退避与失败计数也是对着"上次那批传不动"攒的，一并归零
            mConsecutiveFailures.set(0);
            for (Task task : mPending.values()) {
                task.attempts = 0;
                task.nextAttemptAt = 0L;
            }
            writeStateLocked();
            mLock.notifyAll();
        }
        // 远端名单缓存留着无妨（它是一分钟内有效的目录快照，与"传没传过"无关），
        // 但重置意味着远端确实缺文件，重新列一次更保险
        releaseClient();
        releaseVerifyClients();
        Logger.i("event=ledger_reset done=" + done + " pending=" + mPending.size());
    }

    /**
     * 应用并行路数。
     *
     * <p>调大：立刻补开线程（它们会自己去抢任务）。调小：只更新计数，越界那几路会在
     * 传完手上**这一项之后**自然退出循环 —— 不能在传输中途把请求砍了，那样这一项既没传完、
     * 也没进重试队列，只能等下一轮枚举才回来。
     *
     * <p>不需要 notifyAll：调大是"多了几路来抢"，队列里若真有活，新线程一进去就能看到；
     * 若都在退避，它们的 wait 到期时间本来就是按最早那个 nextAttemptAt 算的，喊醒也没用。
     */
    private void applyConcurrency(int wanted) {
        int clamped = Config.clampConcurrency(wanted);
        int old = mConcurrency;
        if (clamped == old) {
            return;
        }
        mConcurrency = clamped;
        if (clamped > old) {
            for (int i = old; i < clamped; i++) {
                startWorker(i);
            }
        }
        Logger.i("event=queue_concurrency changed=" + old + " to=" + clamped);
    }

    /**
     * 目标变了：处理"已完成"与退避。
     *
     * <p>已完成记录是按"这个位置上已经有了"记的，换服务器/换账号/换远端目录之后它对新位置
     * 完全无效 —— 留在当前这条记录里就是"队列一片绿、新目标上一个文件都没有"。
     * 退避同理，它是旧目标攒出来的，一律清零。
     *
     * <p>分开记录模式（{@link Config#isLocalFlagPerTarget()}）下不是清掉而是**换记录**：
     * 把当前这本按旧签名存进 {@link #mLedgers}，再把新签名那本（以前存过的）取出来当当前账。
     * 于是"换回旧账号"时那条记录里记过的项直接命中，一个字节都不重发；"换成新账号"时
     * 新签名没有账，就是全空 —— 正是用户要的两种行为。共用模式（默认）下照旧清空：
     * 那条记录是相册的 `localFlag` 的镜像，相册那边没有"按目标分开记录"这回事，分开记录反而会让
     * 相册显示已上传、而模块认为该传 —— 两边说法不一致。
     *
     * <p>调用方必须持有 mLock（里面要 notifyAll 唤醒等退避的 worker）。
     */
    private void targetChangedLocked(Config config) {
        int pending = mPending.size();
        int done = mDone.size();
        String nextSignature = targetSignature(config);
        boolean perTarget = config != null && config.isLocalFlagPerTarget();
        if (perTarget && !mDone.isEmpty() && !mDoneSignature.isEmpty()
                && !mDoneSignature.equals(nextSignature)) {
            // 存的是副本：mDone 下面就要被清空/复用，直接放引用会被后续改动带走
            mLedgers.put(mDoneSignature, new LinkedHashMap<>(mDone));
            trimLedgersLocked();
            Logger.i("event=ledger_stashed signature=" + mDoneSignature + " records=" + done);
        }
        mDone.clear();
        if (perTarget) {
            LinkedHashMap<String, Long> stored = mLedgers.remove(nextSignature);
            if (stored != null && !stored.isEmpty()) {
                mDone.putAll(stored);
                Logger.i("event=ledger_restored signature=" + nextSignature
                        + " records=" + mDone.size());
            }
        }
        mDoneSignature = nextSignature;
        for (Task task : mPending.values()) {
            task.attempts = 0;
            task.nextAttemptAt = 0L;
        }
        if (done > 0 || pending > 0) {
            Logger.i("event=queue_target_reset done=" + done + " pending=" + pending
                    + " per_target=" + perTarget);
        }
        // 立刻落盘：不写的话，进程在下次正常落盘之前被杀（一键清理、崩溃），
        // 旧 done 表会从 state.json 里原样读回来，"换了目录"这件事就白做了
        writeStateLocked();
        mLock.notifyAll();
    }

    /** 最多留 {@link #MAX_LEDGERS} 本账，超了就丢最早存进来的那本（下次换回去重传一轮） */
    private void trimLedgersLocked() {
        if (mLedgers.size() <= MAX_LEDGERS) {
            return;
        }
        Iterator<String> it = mLedgers.keySet().iterator();
        while (it.hasNext() && mLedgers.size() > MAX_LEDGERS) {
            it.next();
            it.remove();
        }
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
        int tooLarge = 0;
        Config config = mConfig;
        // 上限在入队这一层判，**不是**在 worker 里：这样超限的项一个字节都不会发出去，
        // 也不会占着并发槽位、不会进重试循环（见 Config.KEY_MAX_UPLOAD_BYTES 的实测依据）。
        long cap = config == null ? Config.DEFAULT_MAX_UPLOAD_BYTES : config.getMaxUploadBytes();
        // This classifier may hash an entire local file. Keep it outside mLock because the
        // Gallery main-thread dirty-count hook also reads queue state under that lock.
        List<CloudItemReader.Item> unprotected = new ArrayList<>(items.size());
        for (CloudItemReader.Item item : items) {
            File file = new File(item.path);
            if (!file.isFile() || file.length() == 0) {
                skipped++;
                continue;
            }
            if (mOriginalStore.isProtectedUpload(file, item.rowId, item.sha1)) {
                skipped++;
                Logger.i("event=upload_skip_protected path=" + item.path
                        + " row=" + item.rowId + " sha1=" + item.sha1);
                continue;
            }
            unprotected.add(item);
        }
        synchronized (mLock) {
            for (CloudItemReader.Item item : unprotected) {
                File file = new File(item.path);
                if (!file.isFile() || file.length() == 0) {
                    skipped++;
                    continue;
                }
                long size = file.length();
                if (cap > 0 && size > cap) {
                    tooLarge++;
                    // 每项只喊一次：相册每轮同步都会把同一批脏项再交下来，逐轮重复打日志
                    // 只会把有用的行淹掉。判据带体积，文件变了会再喊。
                    if (mTooLargeLogged.add(item.path + "@" + size)) {
                        Logger.w("event=upload_skip_too_large path=" + item.path
                                + " size=" + size
                                + " cap=" + Config.describeBytes(cap));
                    }
                    continue;
                }
                String key = key(item.path, size, file.lastModified());
                if (mDone.containsKey(key) || mPending.containsKey(key)) {
                    skipped++;
                    continue;
                }
                mPending.put(key, new Task(item.path, size, file.lastModified(),
                        item.video, item.date, item.rowId, item.sha1));
                added++;
            }
            if (added > 0) {
                // 有新活了，催相册的间隔拉回最短：不然队列刚被喂饱，下一次催促却要等一小时
                mIdleSyncDelay = IDLE_SYNC_BASE;
                // 队列重新有活，下次真空闲要重新通报一次收尾
                mIdleNotified = false;
                mPendingVersion++;
                // 入队是每个批次一次，不是每张照片一次，这里直接落盘
                writeStateLocked();
                mLock.notifyAll();
            }
        }
        // too_large 单独报出来：它不进 pending，也不重试，不说明白的话用户只会看到
        // "相册里的文件没传上去，日志里也没有失败"，那是最难查的一种表现
        Logger.i("event=enqueue added=" + added + " skipped=" + skipped
                + " too_large=" + tooLarge
                + " pending=" + mPending.size() + " done=" + mDone.size());
    }

    private void startWorkers() {
        for (int i = 0; i < mConcurrency; i++) {
            startWorker(i);
        }
    }

    /**
     * 开第 index 路上传线程。线程对象只建一次 —— 重复调用只会把同一路开两遍，
     * 所以 {@link #applyConcurrency} 只在**调大**时补开，调小时靠旧线程自己退出。
     */
    private void startWorker(int index) {
        Thread worker = new Thread(() -> drain(index), "MiGallerySync-upload-" + index);
        // daemon：上传线程绝不能拖住相册进程不让它退出
        worker.setDaemon(true);
        mWorkers[index] = worker;
        worker.start();
    }

    /** 开复核线程。数量固定（{@link #VERIFY_THREADS}），不随上传路数变 */
    private void startVerifiers() {
        for (int i = 0; i < VERIFY_THREADS; i++) {
            final int index = i;
            Thread verifier = new Thread(() -> verifyLoop(index), "MiGallerySync-verify-" + index);
            verifier.setDaemon(true);
            mVerifiers[index] = verifier;
            verifier.start();
        }
    }

    /**
     * 单路 worker 的主循环。
     *
     * <p>领取任务必须在 {@code mLock} 内完成（把 key 记进 {@link #mInflight}），否则 3 路
     * 会挑中同一项、同一个文件被 PUT 三遍。释放同样要兜住：任何一条返回路径漏了
     * {@code mInflight.remove}，那一项就再也没人碰了 —— 既不重试也不失败，永远占着位置。
     *
     * <p>配置与网络的前置检查放在领取**之前**：这两条不满足时任务根本没被领走，
     * 不需要先领再放（那样一旦网络长时间不通，被领走的那几项会一直挂在 in-flight 里）。
     */
    private void drain(int index) {
        while (mRunning && index < mConcurrency) {
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

            Task task = null;
            boolean idle = false;
            long waitMillis = WAIT_NO_TASK;
            synchronized (mLock) {
                long now = System.currentTimeMillis();
                for (Task candidate : mPending.values()) {
                    String candidateKey = candidate.key();
                    if (mInflight.contains(candidateKey) || mVerifying.contains(candidateKey)) {
                        // 同伴正在传它（或在后台复核它）：跳过。它的 nextAttemptAt 也不该算进
                        // "我还要等多久"，它已经在被处理了，不是在排队
                        continue;
                    }
                    if (candidate.nextAttemptAt > now) {
                        waitMillis = Math.min(waitMillis, candidate.nextAttemptAt - now);
                        continue;
                    }
                    // **同一个远端路径一次只能有一个 PUT 在飞**（服务器侧硬约束，见
                    // inflightRemotePathLocked 的说明）。挡住的项不算进 waitMillis ——
                    // 它不是"在等退避"，而是"在等那条同路径的请求结束"，那一刻会被唤醒重挑
                    String claimedPath = remotePathOfLocked(config, candidate);
                    if (mInflightRemotePaths.contains(claimedPath)) {
                        continue;
                    }
                    task = candidate;
                    mInflight.add(candidateKey);
                    // 登记写在任务自己身上：释放时按它删，不重新推一遍（推的话配置中途一改
                    // 就会删错一个键，那条路径就再也没人能传了）
                    candidate.inflightPath = claimedPath;
                    mInflightRemotePaths.add(claimedPath);
                    mIdleNotified = false;
                    break;
                }
                if (task == null) {
                    // 只有"一项都没人在传、也没有还没定论的复核"才算真的空闲。
                    // 否则这一路会替正在传的同伴去收尾（endAll + 催同步），
                    // 把宿主的"正在上传"提前关掉 —— 复核失败的那一项马上又要重传，
                    // 界面就会在"传完了"和"正在上传"之间来回跳
                    if (mInflight.isEmpty() && mVerifying.isEmpty() && !mIdleNotified) {
                        mIdleNotified = true;
                        idle = true;
                    }
                    try {
                        mLock.wait(Math.max(1000L, waitMillis));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (idle) {
                onQueueIdle();
                continue;
            }
            if (task == null) {
                // 没领到活（要么都在被同伴传、要么都在退避）：回去等下一次唤醒，不空转
                continue;
            }

            upload(task, config, index);
        }
        // 路数被调小、或队列整体停机时走到这里。这一路的连接要还回去，
        // 否则它会一直对着目标挂着，直到队列销毁
        releaseClientAt(index);
    }

    /**
     * 没有到期任务时该做的事：把进度收尾，并主动请相册再枚举一次。
     *
     * <p>相册只按自己的排期枚举脏项（真机实测间隔 1~3 小时），干等的话队列空着的时间
     * 远多于在传的时间。这里每空转一次就催一下，间隔逐次翻倍（60s → 2min → … → 1h），
     * 有新项入队就重置回 60s。空手而归时不会自激：催一次就把下次推远一倍。
     */
    private void onQueueIdle() {
        // 上传见底是"该做点别的"的信号：下载队列拿它当节拍器（用户在用的时候多补一点，
        // 没人用的时候就退到几小时一轮）。放在所有 return 之前，否则 mProgress 没接线时
        // 下载永远等不到这个信号
        IdleListener listener = mIdleListener;
        if (listener != null) {
            try {
                listener.onIdle();
            } catch (Throwable t) {
                Logger.d("event=idle_listener_failed detail=" + t);
            }
        }
        // 队列见底或整批在退避，都不该继续占着"正在上传"，否则界面会停在一个不再变化的数字上。
        // 3 路并发下"谁最后结束"不确定，所以这里强制收尾（endAll），而不是减一次引用
        flushState();
        HostSyncState progress = mProgress;
        if (progress == null) {
            return;
        }
        // 判定空闲与收尾之间，同伴可能刚好领走了活（它在锁内登记 mInflight）。
        // 收尾必须在锁内做并复查一次，否则会出现"同伴已经 beginUpload，我们却告诉宿主传完了"——
        // 那之后整批传输期间宿主界面都会停在"未在上传"
        synchronized (mLock) {
            if (!mInflight.isEmpty() || !mVerifying.isEmpty()) {
                return;
            }
            progress.endAll();
        }
        long now = System.currentTimeMillis();
        if (now < mNextIdleSyncAt) {
            return;
        }
        long delay = mIdleSyncDelay;
        mNextIdleSyncAt = now + delay;
        mIdleSyncDelay = Math.min(delay * 2, IDLE_SYNC_MAX);
        Logger.i("event=queue_idle uploaded=" + mUploadedSinceIdle.getAndSet(0)
                + " pending=" + mPending.size() + " next_sync_in=" + delay);
        progress.requestSync(mContext);
    }

    private void upload(Task task, Config config, int index) {
        final String taskKey = task.key();
        OriginalStore.PathLease uploadLease = null;
        // 与 upload_ok 配对，用来核对"同一项只被一个 worker 领走"（verbose 才输出）
        Logger.d("event=upload_begin worker=" + index + " path=" + task.path
                + " size=" + task.size);
        try {
            File file = new File(task.path);
            if (!file.isFile() || file.length() == 0) {
                Logger.w("event=upload_skip reason=local_file_missing path=" + task.path);
                synchronized (mLock) {
                    mPending.remove(taskKey);
                    mPendingVersion++;
                    saveThrottledLocked();
                }
                return;
            }
            try {
                // This read lease shares a per-path lock with quality optimization. It remains
                // held until putSendResolved has finished reading the source file.
                uploadLease = mOriginalStore.lockForUpload(file);
            } catch (IOException e) {
                finalizeFail(task, config, e);
                return;
            }
            final long uploadedLength = file.length();
            final long uploadedMtime = file.lastModified();
            if (mOriginalStore.isProtectedUpload(file, task.rowId, task.sha1)) {
                Logger.w("event=upload_skip_protected_before_send path=" + task.path
                        + " row=" + task.rowId + " sha1=" + task.sha1);
                synchronized (mLock) {
                    mPending.remove(taskKey);
                    mPendingVersion++;
                    saveThrottledLocked();
                }
                return;
            }
            HostSyncState progress = mProgress;
            if (progress != null) {
                progress.beginUpload();
            }
            // 进度页靠这一条把卡片放上去。放在"本地文件还在不在"的检查之后：
            // 文件没了的项会被直接从队列里删掉，不该在界面上闪一下
            mPublisher.taskStart(taskKey, file.getName(), task.path, task.size, task.video);
            boolean ok = false;
            // 提到 try 外面：finally 里要用它清掉进度回调
            WebDavClient client = null;
            // 也提到 try 外面：catch 里要用它做"迟到落盘"的兜底判定（见 lateLanding）
            String remotePath = null;
            String uploadedSha256 = null;
            // 请求体**是不是已经全部发出去了**。这条只有进度回调知道，而它决定了失败之后
            // 要不要回头问一句"远端是不是已经收全了"（见 lateLanding）：
            // 字节发完了还报错，多半是服务器那一侧的收尾问题（真机 423 Locked 就是这种），
            // 而字节都没发完就失败，重试是唯一出路
            final AtomicBoolean bodySent = new AtomicBoolean(false);
            // 交出去之后 worker 这边就不能再碰 endUpload/taskEnd —— 那两条账由复核线程在
            // 真正知道结果之后记（见 verifyLoop），否则会出现"还没确认就告诉宿主传完了"
            boolean handedOff = false;
            try {
                // Capture the upload source fingerprint while the shared read lease is held.
                // Async verification may run after the local file has changed.
                uploadedSha256 = OriginalStore.sha256(file);
                client = clientFor(config, index);
                // 根目录是手填的，前后斜杠、反斜杠都可能带；直接拼会拼出 // 这种空段
                String requestedRemoteDir = remoteDirFor(config, task);
                String remoteDir = client.resolveCollectionPath(requestedRemoteDir);
                if (remoteDir == null) {
                    throw new IOException("cannot resolve remote directory path="
                            + requestedRemoteDir);
                }
                remotePath = WebDavClient.joinPath(remoteDir, file.getName());
                // 防御纵深：用户主动删掉的垃圾副本，远端那份也不许再被 PUT 回去。正常链路走不到
                // 这里（那些副本在 registry 里被标成 derived/受保护），但一旦走到，传上去就等于
                // 把用户删掉的东西又送回远端，下载侧再取回来 —— 闭环必须两边都堵。
                if (mRemoteSuppress.isSuppressed(config, remotePath)) {
                    Logger.w("event=upload_skip_suppressed remote=" + remotePath
                            + " path=" + task.path);
                    synchronized (mLock) {
                        mPending.remove(taskKey);
                        mPendingVersion++;
                        saveThrottledLocked();
                    }
                    return;
                }
                // A name and size match only identifies a candidate. GET and SHA-256 must confirm
                // byte identity before this shortcut can register or mark the item complete.
                if (config.isSkipExistingRemote()
                        && remoteExists(config, index, remoteDir, file.getName(), uploadedLength)) {
                    boolean sameBytes = false;
                    try {
                        sameBytes = mOriginalStore.backfillFromRemote(config, remotePath, file,
                                task.rowId, task.sha1);
                    } catch (IOException e) {
                        Logger.d("event=remote_exists_verify_failed remote=" + remotePath
                                + " reason=" + e);
                    }
                    if (sameBytes) {
                        Logger.i("event=upload_skip_remote_exists path=" + task.path
                                + " remote=" + remotePath + " size=" + uploadedLength
                                + " verified=sha256");
                        // Keep the success progress accounting consistent with a completed PUT.
                        mPublisher.taskBytes(taskKey, uploadedLength);
                        ok = finalizeOk(task, config, remotePath, "remote_exists",
                                uploadedSha256, uploadedLength, uploadedMtime, true, true);
                        return;
                    }
                    Logger.i("event=remote_exists_mismatch remote=" + remotePath
                            + " action=upload");
                }
                // 发字节之前先花几百字节问一句"这条路径被锁着吗"。
                //
                // 为什么必须先问（2026-10-06 真机实测，代价 1.2GB 上行）：OpenList 判写锁是
                // **读完整个请求体之后**才做的，所以被锁的 PUT 一定先把整份文件推完才收到 423。
                // 那个 605,134,181 字节的视频：16:45:45 起、16:47:21 收到 423（sent=605134181），
                // 而这是距上次尝试 31 分钟、无任何并发的一次干净尝试 —— 锁在**服务端那条路径**上
                // 握着不放，与我们的重试间隔毫无关系；16:52 又白烧一份。累计 1.2GB 换两个 423。
                //
                // 423 是按路径判的，判锁只需要一个请求体 —— 那就用 **0 字节**的 PUT 去踩：
                // 锁着就立刻回 423（几乎不耗流量与时间），没锁就成功、顺手删掉那个空对象。
                // 于是"探一次"的成本从"一整个文件"降到"一个空请求"。
                //
                // 只对**大文件**或**已经撞过锁的路径**做：小文件最坏也只是白烧几百 KB，
                // 不值得为每一项都加一次往返。探测自身失败按普通失败处理（抛出去走重试），
                // 绝不把"问不出来"当成"被锁"，否则一次网络抖动就会让这一项无故等上几分钟。
                boolean worthProbing = task.size >= PATH_LOCK_PROBE_MIN_BYTES
                        || mPathLockStrikes.containsKey(task.path);
                if (worthProbing && client.probeLocked(remotePath)) {
                    Logger.w("event=upload_path_locked_probe path=" + task.path
                            + " remote=" + remotePath + " size=" + task.size
                            + " note=body_not_sent");
                    throw new PathLockedException("PUT " + remotePath
                            + " failed code=423 server=Locked (zero-byte probe)");
                }
                // Recheck after directory and lock probes. A quality hook persists its derived-path
                // marker before publishing the replacement, so this catches work already queued.
                if (!file.isFile() || mOriginalStore.isProtectedUpload(file, task.rowId, task.sha1)) {
                    Logger.w("event=upload_skip_protected_before_put path=" + task.path
                            + " row=" + task.rowId + " sha1=" + task.sha1);
                    synchronized (mLock) {
                        mPending.remove(taskKey);
                        mPendingVersion++;
                        saveThrottledLocked();
                    }
                    return;
                }
                // 每 64KB 一块回调，下游按 500ms 节流后才发广播 —— lambda 每任务只建一次，
                // 不在那个循环里分配对象。
                // 顺带在这里当"急停闸"：热重载换代时 shutdown() 已经把 mRunning 置假并关连接，
                // 但**正在写到内核缓冲里的大请求体不会因此停手**（真机上见过新旧两代各传一遍
                // 同一个 1.3GB 文件，服务端对同一路径回 423）。抛出去就地中止这次发送，
                // 粒度是 64KB，不用等剩下的几百 MB 推完。抛的是 IOException，
                // 在 send() 里会被包成 TransportFailure —— 那条路径不计入熔断，正合适。
                client.setProgressListener((sent, total) -> {
                    mPublisher.taskBytes(taskKey, sent);
                    if (!mRunning) {
                        throw new IOException("upload aborted: queue is shutting down");
                    }
                    if (sent >= uploadedLength) {
                        bodySent.set(true);
                    }
                });
                // 绝不预先建目录：OpenList 挂网盘的后端会把 `PUT <dir>/`（空请求体）当成
                // "创建一个 0 字节的文件"，名字撞车时驱动再自动改名成 dir(1)/dir(2)…，
                // 真目录被同名文件一挡，后续文件 PUT 就随机 405/423 —— 探测反而制造了故障。
                // 能自动补父目录的服务器（alist / OpenList / CloudDrive2）本来就不需要这一步；
                // 不需要的由 put() 在 404/409 时补建一次。
                //
                // 载荷闸门：空/全 0/没有有效签名的文件绝不能再走出去。2026-10 事故里
                // 就是<stem>.jpg 这种全 0 内容被 PUT 上去，远端于是躺着 80 个空 jpg。
                // 与上面的"保护上传"同一套处理：从待传队列里摘掉，不重试、不打断宿主。
                if (!MediaPayload.isUsableMedia(file)) {
                    Logger.w("event=upload_rejected reason=invalid_payload path=" + task.path
                            + " size=" + file.length());
                    synchronized (mLock) {
                        mPending.remove(taskKey);
                        mPendingVersion++;
                        saveThrottledLocked();
                    }
                    return;
                }
                // **顺序不能颠倒：先把字节发出去，再谈复核。** 复核（HEAD/PROPFIND）问的是
                // "这个路径在不在"，它对"是我刚传上去的"还是"上一轮就躺在那儿的"一无所知。
                // 一旦只复核不发送，远端早就有的同名文件就会被判成"这次传成功了"，
                // 接着 markSynced 把相册那行记成已上传 —— 相册显示已传、WebDAV 里却没有，
                // 两边数量再也对不上。所以 putSend 必须在 enqueueVerify 之前，
                // 且 putSend 抛异常时这一项绝不会进入复核队列。
                remotePath = client.putSendResolved(remotePath, file);
                int remoteSlash = remotePath.lastIndexOf('/');
                if (remoteSlash >= 0) {
                    remoteDir = remotePath.substring(0, remoteSlash);
                }
                rememberRemoteName(remoteDir, file.getName(), uploadedLength);
                if (config.isVerifyAsync()
                        && enqueueVerify(taskKey, task, config, remotePath, uploadedLength,
                                uploadedSha256, uploadedMtime)) {
                    handedOff = true;
                } else {
                    // 同步路径：复核没过会抛 IOException（与"服务器直接回错误码"同一句话），
                    // 于是走同一套重试逻辑
                    client.requireStoredAs(remotePath, uploadedLength);
                    requireMatchingRemoteBytes(client, remotePath, file, uploadedLength,
                            uploadedSha256);
                    ok = finalizeOk(task, config, remotePath, String.valueOf(index),
                            uploadedSha256, uploadedLength, uploadedMtime, false, true);
                }
            } catch (WebDavClient.TransportFailure e) {
                // 链路层失败里有一种**不是失败**：请求体已经全部发出去了，只是服务器迟迟不给
                // 响应（真机 2026-10-06 实测：1.35GB 的 PUT 在 1800 秒里一个字节没回，
                // 而对象其实已经**完整落盘**，长度分毫不差）。重发一遍要再烧整份上行，
                // 所以这里回头问一句"远端那一份是不是已经跟我一样大"，是就按成功收尾
                if (lateLanding(config, remotePath, task, file, bodySent.get())) {
                    // lateLanding 自己就是"下载远端整份 + 比 SHA-256"，已证明远端字节与本
                    // 地一致，不再重复回读（126 KB 的照片要多等 20 秒，几百 MB 的视频更贵）
                    ok = finalizeOk(task, config, remotePath, "late_landing", uploadedSha256,
                            uploadedLength, uploadedMtime, true, true);
                } else {
                    finalizeFail(task, config, e);
                }
            } catch (IOException e) {
                // 服务器自己回的错误码（含 423 Locked）也走同一条兜底。由头是同一份真机证据：
                // 那个 1.35GB 的视频 13:16:57 开始传，**sent=1416598318（一个字节不差）** 之后
                // 才回 423，而远端那一份早就完整了。字节都发完了还判"失败重传"，
                // 等于每次同步都再烧一份 1.35GB 上行去换同一个 423
                if (lateLanding(config, remotePath, task, file, bodySent.get())) {
                    // 同上：lateLanding 已按 SHA-256 验过整份远端内容
                    ok = finalizeOk(task, config, remotePath, "late_landing", uploadedSha256,
                            uploadedLength, uploadedMtime, true, true);
                } else {
                    finalizeFail(task, config, e);
                }
            } catch (Throwable t) {
                finalizeUnexpected(task, t);
            } finally {
                // 迟到的字节回调由 publisher 自己按 key 丢掉，这里清掉只是不留悬空引用。
                // 交出去的情况也要清：这一路的连接马上要给下一项用
                if (client != null) {
                    client.setProgressListener(null);
                }
                if (!handedOff) {
                    // 与上面的 beginUpload 严格配对（内部是引用计数，只有归零才告诉宿主"传完了"）
                    if (progress != null) {
                        progress.endUpload();
                    }
                    mPublisher.taskEnd(taskKey, ok);
                }
            }
        } finally {
            if (uploadLease != null) {
                uploadLease.close();
            }
            // 任何返回路径都必须把这一项从"在传"里摘掉：漏了的话它既不会被重试也不会被跳过，
            // 永远占着位置 —— 少一路就是永久少一部分吞吐。
            // 交出去的项此时已经在 mVerifying 里（enqueueVerify 在锁内登记的），
            // 所以这里只管 mInflight：对 worker 来说这一项结束了，通道要立刻去传下一项，
            // 对新 worker 来说它还没完（mVerifying 挡着，不能重复领取）——
            // 这就是"复核不占上传通道"的实质
            synchronized (mLock) {
                mInflight.remove(taskKey);
                // 同路径串行的占用一并释放，并喊醒在等它的同伴（那些项没被领走、
                // 也没进 waitMillis，不喊的话要等到下一次唤醒窗口才动）
                if (task.inflightPath != null) {
                    mInflightRemotePaths.remove(task.inflightPath);
                    task.inflightPath = null;
                }
                mLock.notifyAll();
            }
        }
    }

    /**
     * 链路失败之后回头确认一次：**远端那一份是不是已经完整了**。
     *
     * <p>为什么值得单独做（真机 2026-10-06 实测）：这条链路上，请求体"发完"与"服务器应答"
     * 之间有巨大的鸿沟 —— 后端（OpenList 挂移动云盘）要先把整个文件收完才回 201，
     * 上游 1800 秒都不回是常态。于是客户端看到的是"超时失败"，而对象已经躺在远端了：
     * <pre>
     *   PC 侧 curl 直传 1.32GB：sent=1416598318（一个字节不少）→ 1800 秒 0 字节响应
     *   → 事后 PROPFIND 远端：1,416,598,318 字节，完整（手机那一份也早已落盘）
    *   同一台阶梯探测：200MB 41s / 500MB 168s / 1000MB 278s 全部 201 且落盘
     * </pre>
     * 原先的处理是"失败 → 退避 → 重传整份"，代价是每失败一次烧掉一次 1.3GB 上行，
     * 而且这一项会永远重试下去（同时反复占用一条上传通道）。
     *
     * <p>判据必须**严**，因为判错的方向是丢文件/假记账：
     * <ol>
     *   <li>这次请求的字节**确实全部发出去了**（{@code bodySent}，由 64KB 进度回调记下）——</li>
     * </ol>
     * 这一条是整件事的钥匙：它把"这一项传不动"（本地没发完/连接断了）与"服务器收完了却没
     * 好好应答"分开。少了它，任何一次上传失败都会被拿去跟远端比对，而远端那一份可能是
     * **上一轮就存在的**旧文件，那样就会把一次真失败记成成功。
     *
     * <p>再加两条：
     * <ol start="2">
     *   <li>远端必须有这个名字；</li>
     *   <li>GET 的长度和 SHA-256 必须都与本地上传源一致。</li>
     * </ol>
     * 比对失败或 GET 问不出来，就继续原来的重试逻辑。
     *
     * <p>与"先发字节再复核"那条硬性约束的关系：这里**已经发过字节了**（异常正是在发送/
     * 读响应阶段抛的），确认的是"刚发出去的那一份"。它不是 putSend 的替代品，
     * 也不改变"上传前绝不产生成功结论"这条原则。
     *
     * @return true 表示 GET 确认远端字节与本地上传源相同，且已登记该原件
     */
    private boolean lateLanding(Config config, String remotePath, Task task, File localOriginal,
                                boolean bodySent) {
        if (remotePath == null || task == null || localOriginal == null || !bodySent) {
            return false;
        }
        try {
            if (mOriginalStore.backfillFromRemote(config, remotePath, localOriginal,
                    task.rowId, task.sha1)) {
                Logger.w("event=upload_late_landing path=" + remotePath
                        + " size=" + localOriginal.length() + " verified=sha256"
                        + " action=count_as_done");
                return true;
            }
            Logger.i("event=upload_late_mismatch path=" + remotePath
                    + " local=" + localOriginal.length() + " action=retry");
        } catch (Throwable t) {
            // 问不出来就照旧重试 —— 这里绝不能因为"问不到"就判成功
            Logger.d("event=upload_late_probe_failed path=" + remotePath + " reason=" + t);
        }
        return false;
    }

    /**
     * 把"字节已经发完、只剩复核"的项交给复核线程。
     *
     * @return false 表示没交出去（队列在停、或复核积压到上限），调用方必须自己同步复核
     */
    private boolean enqueueVerify(String key, Task task, Config config, String remotePath, long size,
                                  String uploadedSha256, long uploadedMtime) {
        synchronized (mLock) {
            if (!mRunning) {
                return false;
            }
            if (mVerifyQueue.size() >= MAX_VERIFY_BACKLOG) {
                // 积压兜底：宁可这一项慢一点，也不能让待复核项无上限地堆在内存里
                Logger.w("event=verify_async_fallback reason=backlog path=" + task.path);
                return false;
            }
            mVerifyQueue.addLast(new Verify(key, task, config, remotePath, size,
                    uploadedSha256, uploadedMtime));
            // 就在这把锁内登记 mVerifying：等到 worker 的 finally 才登记的话，
            // 复核线程可能已经抢先做完并把 mVerifying 里的它删掉（删了个不存在的），
            // 随后 worker 再加回来 —— 这一项就永远卡在 mVerifying 里没人碰了。
            // 这里跟 mInflight 短暂共存无所谓：两处都只是"别领它"的意思
            mVerifying.add(key);
            Logger.d("event=verify_async_handoff path=" + task.path);
            mLock.notifyAll();
            return true;
        }
    }

    /**
     * 复核线程的主循环：取出一项"字节已发完"的任务，确认它真的落盘了。
     *
     * <p>这是"到底传成功没有"的唯一结论所在：成功才补写标记（标记已同步、清熔断），
     * 失败照常重试。也正因为结论在这里，{@code endUpload()} 与 {@code taskEnd()} 也在这里补
     * —— worker 交出去的时候并没有替这一项告诉宿主"传完了"。
     *
     * <p>复核用自己那一条连接（{@link #verifyClientFor}）：对应的 worker 早就在传下一项了，
     * 跟它共用一条连接会互相把对方的响应读走。
     */
    private void verifyLoop(int index) {
        while (mRunning) {
            Verify job;
            synchronized (mLock) {
                while (mRunning && mVerifyQueue.isEmpty()) {
                    try {
                        mLock.wait(WAIT_NO_TASK);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (!mRunning) {
                    break;
                }
                job = mVerifyQueue.pollFirst();
                if (job == null) {
                    continue;
                }
            }
            boolean ok = false;
            try {
                WebDavClient client = verifyClientFor(job.config, index);
                // 复核是**唯一**的成功判据，同步路径与这里共用 WebDavClient.requireStoredAs：
                // 那句话只有一处定义，日志里同一个故障不会出现两种写法。
                // 复核不过绝不能记成功 —— 一写标记这项就再也不会重传了
                client.requireStoredAs(job.remotePath, job.size);
                // 长度只是"候选"：字节回读核对过才算合格，不合格按普通失败重传 ——
                // 它同时也是写远端旁车的前提（旁车摘要来自本地，不能自己证明自己）
                requireMatchingRemoteBytes(client, job.remotePath, new File(job.task.path),
                        job.size, job.uploadedSha256);
                ok = finalizeOk(job.task, job.config, job.remotePath, "verify-" + index,
                        job.uploadedSha256, job.size, job.uploadedMtime, false, true);
                Logger.i("event=verify_async_done ok=" + ok + " path=" + job.task.path
                        + " remote=" + job.remotePath);
            } catch (WebDavClient.TransportFailure e) {
                finalizeFail(job.task, job.config, e);
            } catch (IOException e) {
                finalizeFail(job.task, job.config, e);
            } catch (Throwable t) {
                finalizeUnexpected(job.task, t);
            } finally {
                HostSyncState progress = mProgress;
                if (progress != null) {
                    progress.endUpload();
                }
                mPublisher.taskEnd(job.key, ok);
                synchronized (mLock) {
                    mVerifying.remove(job.key);
                    mLock.notifyAll();
                }
            }
        }
        // 停机（或线程被中断）时这一条连接要还回去
        releaseVerifyClientAt(index);
    }

    /**
     * 复核成功之后的收尾：移出待传、记已完成、清熔断、写相册落册标记。
     *
     * <p>上传线程与复核线程都走这里，保证"什么才算真的传成功"只有一处定义。
     */
    private boolean finalizeOk(Task task, Config config, String remotePath, String worker,
            String uploadedSha256, long uploadedLength, long uploadedMtime,
            boolean alreadyRegistered, boolean bytesVerified) {
        final String taskKey = task.key();
        if (!alreadyRegistered) {
            try {
                mOriginalStore.registerUploadedOriginal(config, remotePath,
                        new File(task.path), task.rowId, task.sha1, uploadedSha256,
                        uploadedLength, uploadedMtime);
            } catch (IOException e) {
                Logger.e("event=original_registration_failed path=" + task.path, e);
                finalizeFail(task, config, e);
                return false;
            }
        }
        // 原图确实传上去、也登记好了，才把**同一份**摘要写成远端旁车：下次空间优化前的
        // 校验只需要读这几十字节，而不是把整份原图 GET 回来重算 SHA-256。
        // 写失败只是让那一次校验多做一次整份回读，绝不能影响“这一项传成功了没有”，
        // 所以这里只记日志。只给原图写：预览是派生副本，它的摘要不是远端原图的证据。
        //
        // **只在这份字节被回读核对过之后才写**（bytesVerified）：旁车的摘要是发送前对
        // 本地文件算的，它只证明“我们发了什么”。没核对就写，等于让一次损坏的上传
        // 拿到"合格"证明 —— 而空间优化随后会删掉本地原图，那时就没有第二份可比对了。
        if (bytesVerified) {
            writeRemoteHash(config, remotePath, new File(task.path), uploadedSha256,
                    uploadedLength);
        } else {
            Logger.d("event=remote_hash_skipped reason=bytes_not_verified remote=" + remotePath);
        }
        synchronized (mLock) {
            mPending.remove(taskKey);
            mPendingVersion++;
            mDone.put(taskKey, System.currentTimeMillis());
            trimDoneLocked();
            // 这一项总算传上去了，说明那条路径的写锁已经不是问题：把撞击计数清掉，
            // 否则下次它再撞一次就会被当成"第 N 次撞"直接退到几小时
            mPathLockStrikes.remove(task.path);
            saveThrottledLocked();
        }
        int failures = mConsecutiveFailures.getAndSet(0);
        if (failures != 0) {
            Logger.i("event=queue_recovered failures_reset=" + failures);
        }
        // 传通一次就说明服务器是活的：冷却清零、催相册的间隔也拉回最短，
        // 不然一次抖动会把后面几十项的间隔一直撑在最大值上
        mCooldownFailures.set(0);
        mEnvFailures.set(0);
        mIdleSyncDelay = IDLE_SYNC_BASE;
        mUploadedSinceIdle.incrementAndGet();
        Logger.i("event=upload_ok worker=" + worker + " path=" + task.path
                + " remote=" + remotePath
                + " size=" + task.size + " pending=" + mPending.size());
        // 复核确实落盘之后才配得上这条标记；放在锁外做，宿主 provider 的耗时不该把队列锁住
        SyncMarker marker = mMarker;
        if (marker != null) {
            marker.markSynced(task.rowId);
        }
        HostSyncState progress = mProgress;
        if (progress != null) {
            // 标记写下去之后界面上的"待备份 N"才会减一，所以刷新放在标记之后
            progress.refresh();
        }
        return true;
    }

    /**
     * 字节必须与本地文件一致，否则抛异常走重试。
     *
     * <p>为什么"长度对得上"还不够：PUT 是整份请求体，服务器完全可能原样收下一个被中间层
     * 改坏的载荷（长度不变），或者截断后补零。而空间优化之后本地原图就删了，远端那份是
     * 唯一副本 —— 所以长度只是"候选合格"，内容才决定能不能证明。
     *
     * <p>成本按 {@link RemoteByteCheck} 的策略：照片整份回读（{@code ≤64 MiB}），
     * 视频抽头/1/4/1/2/3/4/尾共 5 段。
     */
    private void requireMatchingRemoteBytes(WebDavClient client, String remotePath, File file,
            long size, String expectedSha256) throws IOException {
        if (!RemoteByteCheck.verify(client, remotePath, file, size, expectedSha256)) {
            Logger.w("event=upload_bytes_mismatch remote=" + remotePath + " size=" + size);
            throw new IOException("remote bytes differ from the uploaded file"
                    + " remote=" + remotePath);
        }
        Logger.d("event=upload_bytes_verified remote=" + remotePath + " size=" + size);
    }

    /**
     * 写远端哈希旁车（只给原图写，预览不写）。
     *
     * <p>为什么另开一条连接而不是借用 worker 那条：{@code finalizeOk} 有三个来源
     * ——上传 worker、异步复核线程、以及“迟到落盘”分支，能借到的那条连接属于别的线程。
     * 旁车只有一次 PUT，另开一条比把连接所有权在三个调用点之间传来传去安全得多。
     *
     * <p>旁车只是加速缓存：写失败只记 {@code event=remote_hash_write_failed}，
     * 上传结果（已经登记、已经 markSynced）不受任何影响。
     */
    private void writeRemoteHash(Config config, String remotePath, File file, String sha256,
            long length) {
        if (config == null || remotePath == null || remotePath.isEmpty()
                || sha256 == null || sha256.length() != 64 || length <= 0L) {
            return;
        }
        WebDavClient client = null;
        try {
            client = new WebDavClient(config.getBaseUrl(), config.getUsername(),
                    config.getPassword());
            RemoteHashes hashes = new RemoteHashes(config.getRemoteRoot(),
                    RemoteHashes.forClient(client, hashTempDir()));
            // 写成 v2：这次上传回读核对过的正是头尾两段，旁车里就把这两段的摘要一起记上。
            // 只记整份摘要（v1）证明不了远端那两段真的对过，命中时也就只能比长度。
            RemoteHashes.Entry evidence = RemoteByteCheck.chunkedEntry(file, sha256);
            if (evidence != null) {
                hashes.tryWriteChunked(remotePath, evidence.sha256, evidence.length,
                        evidence.headSha256, evidence.headLength, evidence.tailSha256,
                        evidence.tailLength);
            } else {
                hashes.tryWrite(remotePath, sha256, length);
            }
        } catch (Throwable t) {
            // 走到这里只剩“客户端根本没建起来/关闭前就抛了”这一种情况：
            // tryWrite 自己吞掉了写失败并记过日志，所以这里不重复展开原因
            Logger.w("event=remote_hash_write_failed path=" + remotePath);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    /** 旁车读写的临时目录；只在真的需要 GET 时才建目录（正常上传路径只写不读）。 */
    private File hashTempDir() {
        return new File(mContext.getCacheDir(), "remote_hashes");
    }

    /**
     * 传失败之后的收尾：安排重试，并按失败类型决定要不要喂熔断。
     *
     * <p>连接层失败（{@link WebDavClient.TransportFailure}）只算这一项的账，永不喂熔断：
     * 那是链路抖动，不是"服务器不行"。而服务器自己回的错误码里，只有**小体积的项也失败**
     * 才说明是整台服务器不可用 —— 大文件更容易被边缘节点单独掐掉（实测 3MB 回 554
     * 而小文件全过），把它们算进去会让熔断被单个大文件误触发。
     */
    private void finalizeFail(Task task, Config config, IOException error) {
        if (isPathLocked(error)) {
            // 423 是**服务器侧那条路径被别人攥着**，不是这一项传不动，更不是服务器挂了。
            // 它必须单独走一条退避，理由见 schedulePathLocked：每试一次就白烧一整个请求体
            schedulePathLocked(task, config, error.getMessage());
            return;
        }
        boolean counts = !(error instanceof WebDavClient.TransportFailure)
                && task.size <= BREAKER_PROBE_MAX;
        scheduleRetry(task, config, error.getMessage(), counts, isEnvironmentFailure(error));
    }

    /**
     * "这条路径被锁着"——由**零字节探针**判出来的，一个字节的正文都没发。
     *
     * <p>单独一个类型是为了让日志与统计能把它和"真的发了整份文件才收到 423"分开：
     * 后者白烧了一整份上行，前者只花了一个空请求。两者都进
     * {@link #schedulePathLocked}，但只有前者是"没有代价的等待"。
     */
    private static final class PathLockedException extends IOException {
        PathLockedException(String message) {
            super(message);
        }
    }

    /**
     * 这次失败是不是"远端那条路径正被别人写"（HTTP 423 Locked）。
     *
     * <p>为什么不按普通失败重试（2026-10-06 真机实测，代价很大）：OpenList 的 WebDAV 是
     * **读完整个请求体之后**才判写锁的（`handlePut` 先 `io.Copy` 再 `confirmLocks`），
     * 所以 423 回来的时候**那一整份上行已经烧掉了**。实测一个 605,134,181 字节的视频：
     * <pre>
     *   14:10:30 第 1 次 sent=605134181 → 423
     *   14:12:17 第 2 次 sent=605134181 → 423
     *   14:15:39 第 3 次 sent=605134181 → 423
     *   14:17:48 第 4 次 sent=605134181 → 423
     *   14:19:58 第 5 次 sent=605134181 → 423 → give_up
     * </pre>
     * 十一分钟里烧掉 **3.0GB 上行**，远端仓库一个字节都没多。普通退避（15s/30s…）会把这种
     * 失败当"重试就好了"，正是它把 5 次机会在 11 分钟内全部用光。
     */
    private static boolean isPathLocked(Throwable error) {
        if (error instanceof PathLockedException) {
            // 探针判出来的，确定无疑，不必再走字符串匹配
            return true;
        }        Throwable cursor = error;
        for (int depth = 0; cursor != null && depth < 8; depth++) {
            String msg = cursor.getMessage();
            if (msg != null) {
                // 状态码是硬判据；`server=Locked` 是 OpenList 回的短语，作补充
                if (msg.contains("code=423") || msg.contains("server=Locked")) {
                    return true;
                }
            }
            if (cursor.getCause() == cursor) {
                break;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    /** 同一条远端路径连续撞锁的次数。撞得越多，退得越远（见 {@link #schedulePathLocked}）。 */
    private final java.util.Map<String, Integer> mPathLockStrikes = new java.util.HashMap<>();

    /**
     * 撞上路径写锁之后的退避：**第一次就退到分钟级，之后逐级翻倍到小时级**。
     *
     * <p>为什么不涨 {@code attempts}、也不按普通退避走：这不是"这一项的问题"，也不是
     * "服务器的问题"，而是服务器进程内的一条锁被别人（很可能是上一轮遗留的长请求，
     * 或者一个泄漏了的内存锁）占着。涨 {@code attempts} 的后果是 5 次机会在十几分钟里被
     * 用光，然后这一项要等 30 分钟才轮到下一次 —— 而每一次都要先烧掉一整个请求体。
     *
     * <p>所以这里改成"按路径记撞击次数、撞击次数决定间隔"：第 1 次 5 分钟，
     * 之后 30min → 1h → 2h → 4h → 6h 封顶。同时**把 attempts 顶到 max_retry**，
     * 让它在界面上表现为"暂缓"而不是"正在重试"，也不再跟正常项抢并发槽位
     * （大文件一次要占一条通道好几分钟）。
     *
     * <p>日志给 `event=upload_wait_path_locked`，里面带 `strikes=`，用它就能看出来
     * 是"偶尔撞一下"还是"这条路径被永久锁死了"。
     */
    private void schedulePathLocked(Task task, Config config, String reason) {
        if (!mRunning) {
            return;
        }
        long delay;
        int strikes;
        synchronized (mLock) {
            Integer seen = mPathLockStrikes.get(task.path);
            strikes = (seen == null ? 0 : seen) + 1;
            mPathLockStrikes.put(task.path, strikes);
            // 这一项**在这一轮之前就已经用尽重试**了（attempts 是落盘的，跨代保留）。
            // 那种项本来就该按"放弃档"慢慢试，撞锁不该反倒给它挣回一次快速重试 ——
            // 每次快速重试的代价是一整个请求体（真机 605MB × 11 分钟 5 次 = 3.0GB），
            // 而它显然还没被任何人解决。所以直接接上放弃档（30min 起、逐级到 6h）
            boolean alreadyGivenUp = task.attempts >= config.getMaxRetry();
            task.attempts = Math.max(task.attempts, config.getMaxRetry());
            if (alreadyGivenUp) {
                delay = Math.min(giveUpDelay(strikes - 1), PATH_LOCK_MAX_DELAY);
            } else {
                delay = strikes == 1
                        ? firstPathLockDelay(task.size)
                        : Math.min(firstPathLockDelay(task.size) << Math.min(strikes - 1, 5),
                                   PATH_LOCK_MAX_DELAY);
            }
            task.nextAttemptAt = System.currentTimeMillis() + delay;
            saveThrottledLocked();
        }
        Logger.w("event=upload_wait_path_locked path=" + task.path
                + " strikes=" + strikes + " delay=" + delay
                + " attempts_kept=0 reason=" + reason
                + " note=body_already_burned");
    }

    /**
     * 这个失败是不是"设备/环境这一层"的问题，而不是"这一项传不动"。
     *
     * <p>判据只有一个：**地址没解析出来**（{@code UnknownHostException} 在因果链上）。
     * 它意味着请求连发都没发出去，跟文件大小、跟服务器状态都无关；把它算成这一项的失败，
     * 就是在环境故障期间替用户把整个备份队列往后推。
     * 明确**不含**读超时与连接中断 —— 那些可能真的是这一项（尤其是大文件）传不动。
     */
    private static boolean isEnvironmentFailure(Throwable error) {
        Throwable cursor = error;
        for (int depth = 0; cursor != null && depth < 8; depth++) {
            if (cursor instanceof java.net.UnknownHostException) {
                return true;
            }
            if (cursor.getCause() == cursor) {
                break;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    /**
     * 抬升一档"环境退避"，返回这一档的间隔。
     *
     * <p>与 {@link #bumpCooldown()} 同一个套路（时间闸门把并发下的同一轮失败并成一次），
     * 只是档位更粗、上限更长。
     */
    private long envCooldown() {
        synchronized (mCooldownLock) {
            long now = System.currentTimeMillis();
            if (now - mLastEnvBumpAt >= ENV_COOLDOWN_BASE) {
                mLastEnvBumpAt = now;
                mEnvFailures.updateAndGet(v -> Math.min(v + 1, 8));
            }
            int level = Math.max(mEnvFailures.get(), 1);
            // 60s,2m,5m,15m,30m… 用倍率而不是纯位移：环境故障好得慢，前几档要够快才算"还在试"
            long[] ladder = {ENV_COOLDOWN_BASE, 2 * 60_000L, 5 * 60_000L, 15 * 60_000L, ENV_COOLDOWN_MAX};
            int index = Math.min(level - 1, ladder.length - 1);
            return ladder[index];
        }
    }

    /** 意料之外的异常：不猜它是不是链路问题，只把这一项放远点，别拖累整队 */
    private void finalizeUnexpected(Task task, Throwable error) {
        mConsecutiveFailures.incrementAndGet();
        Logger.e("event=upload_error path=" + task.path, error);
        synchronized (mLock) {
            task.nextAttemptAt = System.currentTimeMillis() + RETRY_GIVE_UP;
            saveThrottledLocked();
        }
    }

    /**
     * 取第 index 路可复用的客户端。
     *
     * <p>一个文件要发 PUT + HEAD（必要时再 PROPFIND），每条请求开一条连接的话，
     * 每张照片都要付两三次 TCP+TLS 握手 —— 上次备份慢主要就慢在这里。
     * 客户端必须跨文件留着，连接的 keep-alive 才有意义。
     *
     * <p>每路各持一份：一条连接上同时发两个请求会串包，而且"并行等待"正是提速的来源。
     * 换目标时只关自己这一路，不要动同伴的（它可能正在传）。
     */
    private WebDavClient clientFor(Config config, int index) {
        String signature = Config.safeHost(config.getBaseUrl()) + "|" + config.getUsername();
        WebDavClient existing = mClients[index];
        if (existing != null && signature.equals(mClientTargets[index])) {
            return existing;
        }
        releaseClientAt(index);
        WebDavClient client =
                new WebDavClient(config.getBaseUrl(), config.getUsername(), config.getPassword());
        mClients[index] = client;
        mClientTargets[index] = signature;
        return client;
    }

    /**
     * 取第 index 个复核线程可复用的客户端。规则同 {@link #clientFor}，只是数组分开：
     * 复核跟上传会同时用连接，共用一条会互相读到对方的响应。
     */
    private WebDavClient verifyClientFor(Config config, int index) {
        String signature = Config.safeHost(config.getBaseUrl()) + "|" + config.getUsername();
        WebDavClient existing = mVerifyClients[index];
        if (existing != null && signature.equals(mVerifyTargets[index])) {
            return existing;
        }
        releaseVerifyClientAt(index);
        WebDavClient client =
                new WebDavClient(config.getBaseUrl(), config.getUsername(), config.getPassword());
        mVerifyClients[index] = client;
        mVerifyTargets[index] = signature;
        return client;
    }

    /**
     * 已放弃的项再失败一次，该等多久。
     *
     * @param rounds 这是第几次"放弃"（0 = {@code max_retry} 刚好用尽的那一次）
     */
    private static long giveUpDelay(int rounds) {
        int shift = Math.min(Math.max(rounds, 0), 5);
        return Math.min(RETRY_GIVE_UP << shift, RETRY_GIVE_UP_MAX);
    }

    /**
     * 安排下一次重试。
     *
     * @param counts 是否计入"目标不可用"的熔断计数。退避本身按项计数，
     *               所以下一轮枚举仍会碰到它（没记 localFlag，不会丢）。
     */
    private void scheduleRetry(Task task, Config config, String reason, boolean counts,
                               boolean envFailure) {
        if (!mRunning) {
            // 这一路是被我们自己掐断的（热重载/停机时关连接或抛急停异常），不是这一项的问题。
            // 记账的后果不小：真机上见过换代时正在跑的 HEAD 被关掉，那一项直接记成
            // `attempts=8 → upload_give_up`，于是**每次更新模块都把它往后推 30 分钟**，
            // 而它其实一个字节都没错。留在 mPending 里，新 generation 立刻会领走它
            Logger.i("event=upload_aborted_shutdown path=" + task.path
                    + " reason=" + reason);
            return;
        }
        if (envFailure) {
            // 设备这一层的问题（解析不出地址 / 根本没有可用网络），**不是这一项传不动**。
            // 计入 attempts 的后果很重：真机 11:20 那次 DNS 抖动里，一批 `attempts` 已经到
            // 8 的项每次失败都被台阶推到 4 小时后，一次 20 分钟的环境故障等于把整批推迟半天。
            // 所以整队按"环境退避"走（逐级拉长、恢复即清零），把这一项原样留在队里
            long envDelay = envCooldown();
            Logger.w("event=upload_wait_env path=" + task.path
                    + " delay=" + envDelay + " attempts_kept=" + task.attempts
                    + " reason=" + reason);
            synchronized (mLock) {
                task.nextAttemptAt = System.currentTimeMillis() + envDelay;
                saveThrottledLocked();
            }
            return;
        }
        task.attempts++;
        if (counts) {
            int failures = mConsecutiveFailures.incrementAndGet();
            if (failures == unhealthyFailures()) {
                Logger.w("event=queue_unhealthy failures=" + failures
                        + " action=keep_blocking_and_retry");
            }
        }
        long delay;
        if (task.attempts >= config.getMaxRetry()) {
            // 这一项自己重试用尽了，单独放远一点；其它项照常轮转，不被它拖住。
            // 间隔按"第几次放弃"逐级翻倍（30min→1h→2h→4h→6h 封顶）：早先固定 30 分钟，
            // 语义上等于"永远每半小时再发一遍**整个请求体**"—— 2026-10-06 那个 1.32GB 的
            // 视频一天下来白烧约 10GB 上行（每次都要 4~8 分钟才失败），而且它每次失败都占着
            // 一条并发槽位。翻倍之后同类项最多一天再碰几次，代价可控且不会丢任务。
            delay = giveUpDelay(task.attempts - config.getMaxRetry());
            Logger.w("event=upload_give_up path=" + task.path
                    + " attempts=" + task.attempts + " delay=" + delay
                    + " reason=" + reason);
        } else {
            // 退避记在"服务器"头上而不是"每一项"头上：整批一起失败时，按项各算各的
            // 会让 1400 多项同时睡满 1~30 分钟，队列整段空转（真机实测连续几分钟零请求）。
            // 连续失败次数封顶 8，移位不会溢出；算出来再被 COOLDOWN_MAX 兜住
            int cooldown = bumpCooldown();
            delay = Math.min(COOLDOWN_BASE << (cooldown - 1), COOLDOWN_MAX);
            Logger.w("event=upload_retry path=" + task.path
                    + " attempts=" + task.attempts + " delay=" + delay
                    + " cooldown=" + cooldown
                    + " breaker=" + (counts ? "counted" : "ignored")
                    + " reason=" + reason);
        }
        synchronized (mLock) {
            task.nextAttemptAt = System.currentTimeMillis() + delay;
            saveThrottledLocked();
        }
    }

    /**
     * 抬升一档服务器级冷却，返回当前档位。
     *
     * <p>3 路并发下同一轮失败会有 3 个调用几乎同时到达。若各抬一档，退避会比串行时快三倍
     * 涨到上限（15s→30s→60s→180s 两轮就满），服务器只是抖动时队列反而更容易长时间空转。
     * 用时间闸门把它们并成一次：一轮失败只抬一档，涨速与串行时一致。
     *
     * <p>闸门窗口取 {@link #COOLDOWN_BASE}：一轮里的几个失败相隔只有几秒，而下一轮最早也要
     * 等上一轮排出的退避（≥15s）之后，两者不会混。
     */
    private int bumpCooldown() {
        synchronized (mCooldownLock) {
            long now = System.currentTimeMillis();
            if (now - mLastCooldownBumpAt >= COOLDOWN_BASE) {
                mLastCooldownBumpAt = now;
                mCooldownFailures.updateAndGet(v -> Math.min(v + 1, 8));
            }
            // 成功会把档位清零，清零后紧接着的失败至少也要退避一档 ——
            // 不能因为闸门没开就退避 0，那等于原地立刻重试
            return Math.max(mCooldownFailures.get(), 1);
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

    /** Build the same remote path as the uploader for a gallery item that predates the registry. */
    public static String remotePathFor(Config config, String localPath, boolean video, long date) {
        if (config == null || localPath == null || localPath.isEmpty()) {
            return null;
        }
        Task task = new Task(localPath, 0L, 0L, video, date, null, null);
        return WebDavClient.joinPath(remoteDirFor(config, task), new File(localPath).getName());
    }

    private static String yearMonthOf(Task task) {
        long time = task.date > 0 ? task.date : task.mtime;
        if (time <= 0) {
            time = System.currentTimeMillis();
        }
        return WebDavClient.monthDir(time);
    }

    /**
     * 远端那个目录里有没有名字与大小都匹配的候选对象。
     *
     * <p>用 {@code PROPFIND Depth 1} 一次拿回整个目录的名字与大小（见 {@link #mRemoteNames}），
     * 而不是对这一个文件发 HEAD：同一相册目录下的几十项只会付一次往返。这个结果只用来
     * 选候选对象；调用方还会 GET 并比较 SHA-256，才会跳过上传或登记原件。
     *
     * <p>判据从"**名字**相同"改成"**名字与大小都**相同"（2026-10-06）：大小在列目录时就
     * 顺手带回来了，多这一条不花任何代价，却把"同名但内容不全/不同"的情况挡在外面 ——
     * 那种情况按名字跳过会把**不完整的一份**当成备份完成，比多传一次危险得多。
     * 唯一放宽的是远端报不出大小（-1）：那时按名字判，与老行为一致。
     *
     * <p><b>任何失败都返回 false（继续上传）</b>。这个判断只是"省一次上传"的优化，
     * 它自己坏掉（不支持 PROPFIND、超时、被拦）绝不该让文件传不上去 —— 判错了方向以后果
     * 最轻：多传一次覆盖写。反过来若在拿不到名单时判"远端有"，那才是丢文件。
     */
    private boolean remoteExists(Config config, int index, String remoteDir, String name,
                                 long size) {
        Map<String, Long> remote;
        synchronized (mLock) {
            RemoteNames cached = mRemoteNames.get(remoteDir);
            if (cached != null && System.currentTimeMillis() - cached.at < REMOTE_DIR_CACHE_MS) {
                return matches(cached.sizes, name, size);
            }
        }
        try {
            WebDavClient client = clientFor(config, index);
            List<WebDavClient.RemoteEntry> entries = client.list(remoteDir);
            if (entries == null) {
                // 目录名单不可用时，单独问目标对象。只有HEAD/Depth 0 PROPFIND给出与本地
                // 完全相同的正字节数才跳过；404、未知、或长度不一致都继续正常上传。
                // 这可避免大目录列举超时后，把已落盘的对象盲目重传一遍。
                long directSize = client.directRemoteSize(WebDavClient.joinPath(remoteDir, name));
                boolean exact = WebDavClient.hasExactSize(directSize, size);
                Logger.d("event=remote_object_fallback size=" + directSize
                        + " expected=" + size + " exact=" + exact);
                return exact;
            }
            Map<String, Long> sizes = new HashMap<>();
            for (WebDavClient.RemoteEntry entry : entries) {
                if (!entry.dir) {
                    sizes.put(entry.name, entry.size);
                }
            }
            synchronized (mLock) {
                mRemoteNames.put(remoteDir, new RemoteNames(sizes, System.currentTimeMillis()));
                trimRemoteNamesLocked();
            }
            remote = sizes;
        } catch (Throwable t) {
            // 连接层失败（TransportFailure）与协议层失败一视同仁：这一项照传，
            // 真传不上去会由下面的 putSend 报出来，不该在这里改判定
            Logger.d("event=remote_list_failed dir=" + remoteDir + " reason=" + t);
            return false;
        }
        return matches(remote, name, size);
    }

    /** 名单里这个名字的大小与本地一致吗；远端大小未知（-1）时按名字算 */
    private static boolean matches(Map<String, Long> remote, String name, long size) {
        Long remoteSize = remote.get(name);
        if (remoteSize == null) {
            return false;
        }
        return remoteSize < 0 || remoteSize == size;
    }

    /**
     * 自己刚传上去的文件名当场记进名单。
     *
     * <p>不记的话，同一目录里紧接着传第二项时用的是**上一次**列出来的名单（一分钟内不重列），
     * 名单里没有刚传完的这一个 —— 如果它恰好与后面某一项同名（同一张照片被相册枚举出两条
     * 不同 row），后一项就会再传一遍。记进去正好把这种情况收掉。
     */
    private void rememberRemoteName(String remoteDir, String name, long size) {
        synchronized (mLock) {
            RemoteNames cached = mRemoteNames.get(remoteDir);
            if (cached == null) {
                return;
            }
            cached.sizes.put(name, size);
        }
    }

    private void trimRemoteNamesLocked() {
        if (mRemoteNames.size() <= MAX_CACHED_DIRS) {
            return;
        }
        Iterator<String> it = mRemoteNames.keySet().iterator();
        while (it.hasNext() && mRemoteNames.size() > MAX_CACHED_DIRS) {
            it.next();
            it.remove();
        }
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
     * 旧记录对新位置完全无效。这两项都不是凭据，落盘没有风险。
     * 密码仍然不落盘，所以这里只取 safeHost 与用户名（运行时那次比对由 {@link #sameTarget} 负责，
     * 它拿得到密码，能额外发现改密码）。
     */
    private String targetSignature() {
        return targetSignature(mConfig);
    }

    /**
     * 目标签名，同时也是"分开记录模式下的记录键"。
     *
     * <p>落盘时要算的是**当前这份配置**的签名（不能用一个可能已经过期的实例字段），
     * 而换目标时要算的是**新配置**的签名（那时 {@link #mDone} 里还是旧目标的账）——
     * 所以做成静态形态，两个调用点各传各的。
     */
    private static String targetSignature(Config config) {
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
                obj.put("h", task.sha1 == null ? "" : task.sha1);
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
            // 一次性迁移标记：修过"环境故障欠下的长退避"就落盘，免得每次启动都重扫一遍
            if (mDebtFixed) {
                root.put("debt_fixed", true);
            }
            // 分开记录模式下把其他目标那几条记录一起带上：不带的话"换回旧账号不用重传"就只在本进程
            // 活着的时候成立，重启一次账就没了（用户看到的还是"又传了一遍"）
            Config config = mConfig;
            if (config != null && config.isLocalFlagPerTarget() && !mLedgers.isEmpty()) {
                JSONObject ledgers = new JSONObject();
                for (Map.Entry<String, LinkedHashMap<String, Long>> ledger : mLedgers.entrySet()) {
                    JSONObject one = new JSONObject();
                    for (Map.Entry<String, Long> entry : ledger.getValue().entrySet()) {
                        one.put(entry.getKey(), entry.getValue());
                    }
                    ledgers.put(ledger.getKey(), one);
                }
                root.put("ledgers", ledgers);
            }
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
            int repaired = 0;
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
                            rowId.isEmpty() ? null : rowId,
                            obj.optString("h", ""));
                    task.attempts = obj.optInt("a", 0);
                    task.nextAttemptAt = obj.optLong("n", 0L);
                    if (!task.path.isEmpty()) {
                        mPending.put(task.key(), task);
                    }
                }
            }
            JSONObject done = root.optJSONObject("done");
            if (done != null) {
                readLedger(done, mDone);
            }
            // 先认下"这次迁移已经做过"这个事实，再决定要不要跑。
            // **必须在 load 里把标记读进字段**：修复分支只在"确实清掉了几项"时才置字段，
            // 于是换代到一个"没东西可修"的新 generation 时字段仍是假，它随后的任何一次落盘
            // （上传成功、退避、退出）都会把 state.json 里的 debt_fixed **抹掉** ——
            // 真机 12:30 修了 1282 项，13:01 换代后就因为这个把标记弄丢了，迁移会一次次重跑
            mDebtFixed = root.optBoolean("debt_fixed", false);
            if (!mDebtFixed) {
                repaired = repairBackoffDebt();
                // 清了几项都要落盘：这个标记本身就是"别再修第二次"的唯一凭据
                synchronized (mLock) {
                    writeStateLocked();
                }
            }
            // 分开记录模式下"其他目标"的记录。老版本没有这个键，读不到就是空的，行为不变
            JSONObject ledgers = root.optJSONObject("ledgers");
            if (ledgers != null) {
                Iterator<String> signatures = ledgers.keys();
                List<String> sigList = new ArrayList<>();
                while (signatures.hasNext()) {
                    sigList.add(signatures.next());
                }
                for (String signature : sigList) {
                    JSONObject one = ledgers.optJSONObject(signature);
                    if (one == null) {
                        continue;
                    }
                    LinkedHashMap<String, Long> ledger = new LinkedHashMap<>();
                    readLedger(one, ledger);
                    if (!ledger.isEmpty()) {
                        mLedgers.put(signature, ledger);
                    }
                }
                trimLedgersLocked();
            }
            mDoneSignature = savedTarget;
            synchronized (mLock) {
                Config config = mConfig;
                if (!savedTarget.isEmpty() && !savedTarget.equals(targetSignature())) {
                    // 落盘时的目标与现在这份配置不是同一个。两种模式都在这里处理：
                    // 共用记录直接清掉（旧记录对新目标无效）；分开记录模式换成新目标那本
                    // （刚读进来的那条记录的签名是 savedTarget，会被存回 mLedgers）。
                    targetChangedLocked(config);
                    // 立刻落盘：否则每开一次进程就重来一次，把"已完成"反复清空、文件反复重传
                    writeStateLocked();
                }
            }
            Logger.i("event=state_loaded pending=" + mPending.size() + " done=" + mDone.size()
                    + " ledgers=" + mLedgers.size()
                    + (repaired > 0 ? " backoff_repaired=" + repaired : ""));
        } catch (Throwable t) {
            Logger.w("event=state_load_failed", t);
        }
    }

    /**
     * 一次性的"时间债"修复：把某一代版本在**环境故障期间**替用户欠下的长退避清掉。
     *
     * <p>背景（真机 2026-10-06）：11:20 起这条链路解析不出地址（VPN 的 UID 路由那套老问题），
     * 而当时的代码把每一次解析失败都算成"这一项传不动"，于是那批 {@code attempts} 已经到
     * 7~9 的项被逐级推到 2、4、6 小时之后。DNS 两分钟就恢复了，**而每一项都在等一个由别人的
     * 故障写下的时间戳**，唯一会重写它的动作是"相册重新枚举"（1~3 小时一次）——用户看到的就是
     * "备份停了"。真机实测：这份 state.json 里 12292 项中有 1282 项带着这种等待
     * （attempts=7 → 2h 共 1041 项、8 → 4h 共 136 项、9/10 → 6h 共 105 项），
     * 其余 11010 项本来就是马上可传的。新代码已经不再这样记账（见
     * {@code scheduleRetry} 的环境退避分支），但**已经写进 state.json 的那一笔要有人还**。
     *
     * <p>判据是一个**只属于阶梯退避**的指纹：等待超过 30 分钟。
     * 正常失败路径最长只能把一项推到 {@link #COOLDOWN_MAX}（3 分钟），
     * 再长只有两条路 —— 这一项自己重试用尽（{@link #RETRY_GIVE_UP} 起，≥30 分钟），
     * 或者环境退避；两者都是"暂时别碰它"。所以**逐项**把这种等待清成"立刻可传"：
     * <ul>
     *   <li>只动等待 &gt; 30 分钟的项，别的项（含正在排队的 1 万多条）一根汗毛都不碰；</li>
     *   <li>这些项本来就是"传不动"的（大文件、残包），清掉退避最多白试一轮，
     *       不会把已经传上去的东西弄坏（幂等覆盖写）；</li>
     *   <li>只修一次（{@code debt_fixed} 落盘，且 {@link #load()} 会把它读回 {@code mDebtFixed}），
     *       之后由 {@link #giveUpDelay} 自己管。</li>
     * </ul>
     *
     * @return 被清掉退避的项数（0 表示没动）
     */
    private int repairBackoffDebt() {
        // 判据用"比 give-up 起点还远"，与 RETRY_GIVE_UP(30min) 严格对齐
        long deadline = System.currentTimeMillis() + RETRY_GIVE_UP;
        int fixed = 0;
        for (Task task : mPending.values()) {
            if (task.nextAttemptAt > deadline) {
                task.attempts = 0;
                task.nextAttemptAt = 0L;
                fixed++;
            }
        }
        mDebtFixed = true;
        Logger.w("event=backoff_debt_fixed items=" + fixed + " pending=" + mPending.size()
                + " reason=environment_outage_backoff");
        return fixed;
    }

    /** 把一段 JSON 对象读成记录（键都是"路径|大小|mtime"） */
    private static void readLedger(JSONObject source, LinkedHashMap<String, Long> into) {
        Iterator<String> keys = source.keys();
        List<String> list = new ArrayList<>();
        while (keys.hasNext()) {
            list.add(keys.next());
        }
        for (String key : list) {
            into.put(key, source.optLong(key, 0L));
        }
    }

    /**
     * 一项"字节已发完、只剩复核"的工作。worker 把 {@link Task} 与算好的远端路径一起交过来，
     * 复核线程照着再做一次 HEAD/PROPFIND 就能定结论，不必重算目录。
     */
    private static final class Verify {
        final String key;
        final Task task;
        final Config config;
        final String remotePath;
        final long size;
        final String uploadedSha256;
        final long uploadedMtime;

        Verify(String key, Task task, Config config, String remotePath, long size,
                String uploadedSha256, long uploadedMtime) {
            this.key = key;
            this.task = task;
            this.config = config;
            this.remotePath = remotePath;
            this.size = size;
            this.uploadedSha256 = uploadedSha256;
            this.uploadedMtime = uploadedMtime;
        }
    }

    /**
     * 该任务会落到哪个远端路径。**领取任务时就要知道**，因为并发写同一个路径会被服务器回 423
     * （机制见下面 inflightRemotePathLocked 的说明）。
     *
     * <p>与 {@code upload()} 里那句 `remoteDirFor(config, task)` + 文件名保持一致：
     * 同一套函数、同一份配置，所以两边算出来的路径必然相同。
     */
    private String remotePathOfLocked(Config config, Task task) {
        String dir = remoteDirFor(config, task);
        return WebDavClient.joinPath(dir, new File(task.path).getName());
    }

    /**
     * 同一个远端路径**不许有两条在飞的 PUT** —— 服务器侧硬约束，不是优化。
     *
     * <p>机制（2026-10-06 服务器侧只读取证拿到确切结论）：OpenList 的 WebDAV 用**进程内的
     * 内存锁**（`server/webdav.go`: `LockSystem: webdav.NewMemLS()`），而 `handlePut` 是
     * **读完整个请求体之后**才判锁。同一路径只要有两个 PUT 重叠，后到的那个就吃
     * **423 Locked**（`webdav: locked`），代价是**把那一整份请求体先收完**（1.3GB = 白烧
     * 一整份上行）。服务器日志把窗口钉死了（UTC）：
     * <pre>
     *   02:29:45 起的那笔持锁 56 分半 → 03:26:15 回 201
     *   落在它窗口里的 02:29:53 与 03:09:21 两笔 → 全部 423
     *   04:30:47 起的那笔持锁 61 分钟，期间 04:59:56 / 05:01:37 / 05:05:55 / 05:07:20 /
     *   05:12:14 / 05:17:03 六笔全部 423
     * </pre>
     * 所以**重试越勤撞得越狠**，每一撞都付一整份上行。堵在源头才是解决办法。
     *
     * <p>为什么本队列真会撞：任务键是 `路径+大小+mtime`，而远端路径是 `远端目录 + 文件名`
     * —— 大小或 mtime 一不同就是两个任务键、同一个远端路径（重名照片、被编辑过的照片、
     * 不同相册目录下的同名文件都是这种）。`mInflight` 只按任务键去重，挡不住。
     *
     * <p>与 {@code mInflight} / {@code mVerifying} 的分工：那两条是"这一项别重复领"，
     * 这一条是"同一个远端路径别并发写"；两者都要，因为一个路径可能对应两个任务键。
     *
     * <p>都在 {@code mLock} 内操作：领取时登记、任何返回路径在 finally 里释放。
     * 漏放会导致那条路径永远没人传（档在"已占用"上）。
     */
    private final Set<String> mInflightRemotePaths = new HashSet<>();

    private static final class Task {
        final String path;
        final long size;
        final long mtime;
        final boolean video;
        final long date;
        /** cloud 表行号，跨进程重启也要留着：上传成功的标记靠它 */
        final String rowId;
        final String sha1;
        int attempts;
        long nextAttemptAt;
        /** 领取时登记的远端路径（同路径串行用），释放时按它删 */
        String inflightPath;

        Task(String path, long size, long mtime, boolean video, long date, String rowId,
                String sha1) {
            this.path = path;
            this.size = size;
            this.mtime = mtime;
            this.video = video;
            this.date = date;
            this.rowId = rowId;
            this.sha1 = sha1;
        }

        String key() {
            return UploadQueue.key(path, size, mtime);
        }
    }
}
