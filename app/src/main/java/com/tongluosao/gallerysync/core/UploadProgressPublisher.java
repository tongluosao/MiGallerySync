package com.tongluosao.gallerysync.core;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把上传队列的实时状态汇总成帧，用定向广播发给模块 App。
 *
 * <p>跑在相册进程里。上游是 {@link UploadQueue}（谁在传、传到哪）与
 * {@link WebDavClient.ProgressListener}（字节数），下游是模块 App 的进度页。
 *
 * <p><b>为什么要节流</b>：字节回调是按 64KB 一块来的，一个 600MB 的视频会回调上万次；
 * 每次都发一帧广播等于把 Binder 打满。所以回调只更新内存里的数字，真正发帧由一条
 * 心跳线程按固定间隔做 —— 2Hz 足够让客户端的插值动画看起来是连续的。
 * 结构性变化（开始/结束一项、状态切换）不等心跳，立刻补一帧，否则"某张照片开始传"
 * 要等最多 500ms 才出现在界面上。
 *
 * <p><b>会话</b>：从"在传集合由空变非空"开始累计已完成项与字节，直到在传集合空置超过
 * {@link #SESSION_GAP_MS} 才收尾。中间短暂的间隙不算新会话 —— 相册是按批枚举的，
 * 批次之间在传集合本来就会空一下，若那时就重置，总进度条会反复跳回 0。
 */
public final class UploadProgressPublisher {

    /** 心跳间隔：2Hz。客户端用 500ms 的插值动画桥接，肉眼是连续的 */
    private static final long HEARTBEAT_MS = 500;
    /**
     * 没有会话也没有变化时的长睡间隔。
     *
     * <p>不能彻底停发：客户端靠 {@code ts} 判"数据过期"。停发的话进度页会一直显示
     * "连接可能已断开"，而其实只是队列空了。5s 一帧的开销可以忽略
     * （App 没在跑时这帧广播直接被丢弃，也不会把它拉起来）。
     */
    private static final long IDLE_WAIT_MS = 5_000;
    /** 在传集合空置超过这么久就认为本轮会话结束；下次开始新的一项时累计清零 */
    private static final long SESSION_GAP_MS = 30_000;

    /**
     * 下载会话收尾前的静默期。
     *
     * <p>{@link DownloadQueue} 只有在"计划线程完整跑过一遍、一个新项都没认出、队列与在传集合
     * 都空"的时候才会报 {@code autoDownloadDrained} —— 那是"没有待下项"的强信号。这里再等
     * 一个静默期才真的收尾，是为了盖住"相册下一次批事件刚好在路上"的竞态：那一批若是
     * 又计划出东西，{@link #autoDownloadPlanned} 会先把 drained 清掉，会话继续累计，
     * 总任务也就不会在批次之间掉下来。
     */
    private static final long DOWN_SETTLE_MS = 20_000;

    /** 下载会话采样日志的间隔：2 秒一行，够看出单调性又不刷屏 */
    private static final long DOWN_PROGRESS_LOG_MS = 2_000;

    /**
     * 一帧里最多带多少个排队中的项。
     *
     * <p>全量首备时队列里能有上万项，全塞进广播就是每帧几百 KB。列表本身是给人看的，
     * 60 条已经够滚半天了，剩下的用 {@code pending} 计数告诉客户端"还有多少没列出来"。
     */
    private static final int MAX_PENDING_ITEMS = 60;

    /**
     * 排队列表最长多久重传一次。
     *
     * <p>正常情况下靠版本号判断（只有增删才重传），这个兜底是为了客户端后开的情况：
     * 界面刚打开时如果列表恰好没变过，它就一条也看不到，只能等到下次队列增删。
     */
    private static final long PENDING_REFRESH_MS = 3_000;

    /**
     * 自动下载进度日志的节流步长。
     *
     * <p>下载回调按 64KB 一片来，一张 20MB 的原图会回调三百多次；每片都记一行会把相册日志
     * 刷满，而进度页本身只按 2Hz 发帧，根本看不到更细的粒度。所以日志按"5% 或 1MB，先到先记"。
     */
    private static final long PROGRESS_LOG_STEP_BYTES = 1024L * 1024L;

    /** 百分比节流步长：5% */
    private static final int PROGRESS_LOG_STEP_PERCENT = 5;

    /**
     * 队列侧的几个数字与列表，由 {@link UploadQueue} 提供。
     *
     * <p>用接口而不是直接持有 UploadQueue：队列的字段都在它自己的锁里，进度线程不该
     * 伸手进去翻，让队列自己在锁内算好交出来。
     */
    public interface QueueView {
        /** 还没轮到的项数（不含正在传的） */
        int pendingCount();

        /** 还没轮到的项的总字节 */
        long pendingBytes();

        /** 还没轮到的图片数（与 {@link #pendingCount()} 同口径，不含正在传的） */
        int pendingPhotos();

        /** 还没轮到的视频数（与 {@link #pendingCount()} 同口径，不含正在传的） */
        int pendingVideos();

        /**
         * 整个队列按插入序的前 [limit] 项，**含正在传的几项**。
         *
         * <p>进度页排队部分的顺序直接取这一份，所以这里不跳过在传项：客户端拿到列表后
         * 把在传项拎到最前面显示，两边是同一个键，重复的那几项必须由客户端按 key 去掉
         * （{@code ProgressModel.buildTasks}），否则列表会 key 重复。
         */
        List<PendingItem> pendingItems(int limit);

        /** {@link #pendingItems} 的结构版本；变了才需要重传整张列表 */
        int pendingVersion();

        /** WebDAV 是否可用；false 时进度页要说明"正在退避重试、不会去小米云" */
        boolean healthy();
    }

    /** 队列里一张还没轮到的照片。字段与 {@link Active} 对齐，客户端可以共用一套解析 */
    public static final class PendingItem {
        public final String key;
        public final String name;
        public final String path;
        public final long size;
        public final boolean video;
        /** {@link ProgressProtocol#DIR_UP} 或 {@link ProgressProtocol#DIR_DOWN} */
        public final String dir;

        public PendingItem(String key, String name, String path, long size, boolean video,
                String dir) {
            this.key = key;
            this.name = name;
            this.path = path;
            this.size = size;
            this.video = video;
            this.dir = dir;
        }
    }

    /** 一张正在传的照片 */
    private static final class Active {
        final String key;
        final String name;
        final String path;
        final long size;
        final boolean video;
        /** {@link ProgressProtocol#DIR_UP} 或 {@link ProgressProtocol#DIR_DOWN} */
        final String dir;
        /** 由 worker 线程写、心跳线程读 */
        volatile long sent;
        /** 上一次记进度日志时的字节数（只在 {@link #taskProgress} 里读写） */
        long loggedSent;

        Active(String key, String name, String path, long size, boolean video, String dir) {
            this.key = key;
            this.name = name;
            this.path = path;
            this.size = size;
            this.video = video;
            this.dir = dir;
        }
    }

    private final Context mContext;
    private final Object mLock = new Object();
    /** 在传集合，插入序 = 开始顺序，客户端按 key 复用卡片所以顺序只影响首次布局 */
    private final Map<String, Active> mActive = new LinkedHashMap<>();
    /** 结构性变化后不等心跳、立刻发帧 */
    private boolean mDirty = true;

    /**
     * 挂上来的队列视图。**可以有多个**：上传与下载各是一条队列，都往同一张进度页上报，
     * 由这里把计数相加、把列表拼起来 —— 两张进度页（一张上传、一张下载）在界面上没必要，
     * 用户要看的是"这台手机正在跟远端交换哪些文件"。
     */
    private final List<QueueView> mQueueViews = new ArrayList<>();
    private volatile boolean mRunning = true;
    private Thread mThread;

    /** 本轮会话是否还开着 */
    private boolean mSessionOpen;
    /** 在传集合变空的那一刻；用来判会话是否该收尾 */
    private long mSessionIdleSince;
    /** 本轮会话已完成 */
    private int mDoneItems;
    private long mDoneBytes;

    /**
     * 下载方向的会话计数，与上面的 {@code mDoneItems/mDoneBytes} 分开。
     *
     * <p>上面的累计是**上传下载混在一起**的（{@code s*} 那组字段的语义，不能动）；
     * 进度页的下载区块要的是"本次要下多少、下完了多少"，混进上传项就会越算越多。
     */
    private boolean mDownSessionOpen;
    /** 会话已经明确结束：帧里带 {@code dEnded}，客户端据此重置 */
    private boolean mDownEnded;
    /** 下载队列自己确认过"没有待下项"（计划线程跑完一轮、队列与在传都空） */
    private boolean mDownDrained;
    /** 本次下载会话累计计划过的条数/字节（只增，批次之间不清零） */
    private int mDownPlannedItems;
    private long mDownPlannedBytes;
    /** 本次下载会话已完成（成功落地）的条数/字节 */
    private int mDownDoneItems;
    private long mDownDoneBytes;
    /** 下载方向最近一次有动静的时刻：静默期从它开始算 */
    private long mDownLastActivityAt;
    /** 上一次打下载会话采样日志的时刻（只在心跳线程里读写） */
    private long mLastDownProgressLogAt;

    /**
     * 上一次序列化好的排队列表对应的队列版本（每个队列视图一个），以及上次重建的时刻。
     *
     * <p>只有心跳线程碰它，不需要加锁。初始给 MIN_VALUE，保证第一帧一定带上列表。
     */
    private int[] mLastPendingVersions = new int[0];
    private long mLastPendingAt;

    private UploadProgressPublisher(Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * 起一条心跳线程。
     *
     * <p>daemon：进度广播绝不能拖住相册进程退出。相册被杀时它跟着消失，队列状态在
     * state.json 里，下次启动接着传。
     */
    public static UploadProgressPublisher start(Context context) {
        UploadProgressPublisher publisher = new UploadProgressPublisher(context);
        publisher.mSessionIdleSince = System.currentTimeMillis();
        Thread thread = new Thread(publisher::loop, "MiGallerySync-progress");
        thread.setDaemon(true);
        publisher.mThread = thread;
        thread.start();
        return publisher;
    }

    /** 挂一个队列视图（上传一条、下载一条）。挂着的一律不再摘：队列是进程级的单例 */
    public void addQueueView(QueueView view) {
        if (view == null) {
            return;
        }
        synchronized (mLock) {
            mQueueViews.add(view);
            mDirty = true;
            mLock.notifyAll();
        }
    }

    /** 某项开始传输。size 取自任务（入队时记下的），不读磁盘 */
    public void taskStart(String key, String name, String path, long size, boolean video,
            String dir) {
        boolean sessionStarted = false;
        boolean downStarted = false;
        synchronized (mLock) {
            if (!mSessionOpen) {
                mSessionOpen = true;
                mDoneItems = 0;
                mDoneBytes = 0;
                sessionStarted = true;
            }
            if (ProgressProtocol.DIR_DOWN.equals(dir)) {
                downStarted = openDownloadSessionLocked();
                mDownDrained = false;
                mDownLastActivityAt = System.currentTimeMillis();
            }
            mActive.put(key, new Active(key, name, path, size, video, dir));
            mDirty = true;
            mLock.notifyAll();
        }
        if (sessionStarted) {
            Logger.i("event=progress_session_start key=" + key + " size=" + size);
        }
        if (downStarted) {
            Logger.i("event=auto_download_session_start key=" + key + " size=" + size);
        }
        Logger.d("event=progress_task_start key=" + key + " name=" + name + " size=" + size
                + " dir=" + dir);
    }

    /**
     * 打开下载会话（已经在开着的就不动）。返回 true 表示这次真的开始了一轮新的。
     *
     * <p>会话一开始计数就清零：上一轮结束时已经把 {@code dEnded} 发给客户端了，
     * 这一轮必须从 0 重新累计，不能接着上一轮的 266 往下走。
     */
    private boolean openDownloadSessionLocked() {
        if (mDownSessionOpen) {
            return false;
        }
        mDownSessionOpen = true;
        mDownEnded = false;
        mDownPlannedItems = 0;
        mDownPlannedBytes = 0L;
        mDownDoneItems = 0;
        mDownDoneBytes = 0L;
        mDownDrained = false;
        mDownLastActivityAt = System.currentTimeMillis();
        mDirty = true;
        return true;
    }

    /**
     * 下载队列报"这一轮计划进了多少项"。
     *
     * <p><b>只增</b>：每次计划都往会话总量上加，批次跑完不清零 —— 那正是"总任务一直变小"
     * 的根因（旧实现拿到的是"这一轮还剩多少"）。会话结束由 {@link #autoDownloadDrained()}
     * 通知，不是"这一批跑完了"就算。
     *
     * @param items 这一轮新入队的条数
     * @param bytes 这一轮新入队项的总字节
     */
    public void autoDownloadPlanned(int items, long bytes) {
        if (items <= 0 && bytes <= 0L) {
            return;
        }
        boolean started;
        synchronized (mLock) {
            started = openDownloadSessionLocked();
            mDownPlannedItems += Math.max(items, 0);
            mDownPlannedBytes += Math.max(bytes, 0L);
            mDownDrained = false;
            mDownLastActivityAt = System.currentTimeMillis();
            mDirty = true;
            mLock.notifyAll();
        }
        if (started) {
            Logger.i("event=auto_download_session_start items=" + items + " bytes=" + bytes);
        }
        Logger.d("event=auto_download_planned_publish items=" + items + " bytes=" + bytes);
    }

    /**
     * 下载队列确认"没有待下项了"：计划线程完整跑过一轮却一个新项都没认出，且队列与在传
     * 集合都空。真正的收尾还要再等 {@link #DOWN_SETTLE_MS} 的静默期，见 {@link #loop()}。
     *
     * <p>之所以要由队列来报而不是发布端自己猜：只有队列知道"注册表里也没有待下项"
     * （那一轮计划跑完、什么都没认出来）；光看"在传集合空了"会把批次之间的间隙
     * 当成结束，总任务就会在批次之间被打回 0。
     */
    public void autoDownloadDrained() {
        synchronized (mLock) {
            mDownDrained = true;
            mDirty = true;
            mLock.notifyAll();
        }
    }

    /** 兼容"只有上传"的旧调用点：方向按上传处理 */
    public void taskStart(String key, String name, String path, long size, boolean video) {
        taskStart(key, name, path, size, video, ProgressProtocol.DIR_UP);
    }

    /**
     * 某项的字节进度。按 64KB 一块回调，这里只更新数字，发帧交给心跳。
     *
     * <p>结束之后迟到的回调直接丢：任务已经不在 {@link #mActive} 里了，
     * 补记上去只会让下一帧出现一个"幽灵照片"。
     */
    public void taskBytes(String key, long sent) {
        Active active;
        synchronized (mLock) {
            active = mActive.get(key);
        }
        if (active != null) {
            active.sent = sent;
        }
    }

    /**
     * 自动下载的单步字节进度：与 {@link #taskBytes} 走同一条在传通道（同一个 key、同一条
     * {@link ProgressProtocol#DIR_DOWN} 项），只额外按"5% 或 1MB"节流记一行 debug 日志。
     *
     * <p>这条链路不自己发帧：帧仍由心跳线程统一发，所以这里只更新内存里的数字。
     * 传完之后迟到的回调直接丢（项已经不在 {@link #mActive} 里），避免"幽灵进度"把已经
     * 收敛的项又点亮。
     *
     * @param row 相册行号或 sha1，只用于日志定位是哪一条自动下载
     */
    public void taskProgress(String key, String row, long sent, long total) {
        synchronized (mLock) {
            Active active = mActive.get(key);
            if (active == null) {
                return;
            }
            active.sent = sent;
            if (!progressLogDue(active.loggedSent, sent, total)) {
                return;
            }
            active.loggedSent = sent;
        }
        Logger.d("event=auto_download_progress_publish row=" + row + " bytes=" + sent + "/" + total);
    }

    /**
     * 自动下载结束：先按常规 {@link #taskEnd} 把该项从在传集合里收敛掉（进度页立刻变完成态），
     * 再补一行带终态的日志。成功、失败、取消都走这里 —— 无论哪条路径，这一项都不会永久
     * 停在"进行中"。
     *
     * @param reason 失败原因；成功或取消时传 null
     */
    public void autoDownloadEnd(String key, String row, boolean ok, String reason) {
        Active active;
        synchronized (mLock) {
            active = mActive.get(key);
        }
        long sent = active == null ? 0L : active.sent;
        long total = active == null ? 0L : active.size;
        taskEnd(key, ok);
        String state = ok ? "done" : (reason == null ? "cancelled" : "failed");
        Logger.d("event=auto_download_progress_publish row=" + row + " bytes=" + sent + "/" + total
                + " state=" + state + (ok || reason == null ? "" : " reason=" + reason));
    }

    /**
     * 这条字节进度是否值得记一行：5% 或 1MB，先到者记。纯函数，方便单测。
     *
     * <p>完成时不会额外多记一行 —— {@link #autoDownloadEnd} 会打终态行；
     * 这里只在"真的往前走了且跨过阈值"时返回 true。
     */
    static boolean progressLogDue(long lastLogged, long sent, long total) {
        if (sent <= lastLogged) {
            return false;
        }
        if (sent - lastLogged >= PROGRESS_LOG_STEP_BYTES) {
            return true;
        }
        if (total <= 0L) {
            return false;
        }
        long before = Math.min(100L, lastLogged * 100L / total);
        long now = Math.min(100L, sent * 100L / total);
        return now - before >= PROGRESS_LOG_STEP_PERCENT;
    }

    /**
     * 某项结束。
     *
     * @param ok 是否真的传成功。失败/跳过的项不计入已完成字节 —— 它会退回 pending
     *           （退避后重试），进度条上不该少掉它
     */
    public void taskEnd(String key, boolean ok) {
        Active removed;
        synchronized (mLock) {
            removed = mActive.remove(key);
            if (removed != null && ok) {
                mDoneItems++;
                mDoneBytes += removed.size;
            }
            if (removed != null && ProgressProtocol.DIR_DOWN.equals(removed.dir)) {
                // 下载的完成数只算"真的落地了"的项：失败/取消的会退回队列重试，
                // 记进完成数就等于把还没下完的东西算成下完了
                if (ok) {
                    mDownDoneItems++;
                    mDownDoneBytes += Math.max(removed.size, 0L);
                }
                mDownDrained = false;
                mDownLastActivityAt = System.currentTimeMillis();
            }
            if (mActive.isEmpty()) {
                mSessionIdleSince = System.currentTimeMillis();
            }
            mDirty = true;
            mLock.notifyAll();
        }
        if (removed != null) {
            Logger.d("event=progress_task_end key=" + key + " ok=" + ok
                    + " sent=" + removed.sent + " size=" + removed.size);
        }
    }

    /** 在传集合里还有没有下载项（上传项不影响下载会话的收尾） */
    private boolean hasActiveDownloadLocked() {
        for (Active active : mActive.values()) {
            if (ProgressProtocol.DIR_DOWN.equals(active.dir)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 下载会话的采样日志（2 秒一行）。
     *
     * <p>现场要判断的就是"总任务有没有变小、完成数有没有停住"，光看 2Hz 的广播是看不出来的
     * （模块 App 侧不一定在跑）。所以会话开着的时候把累加量按 2 秒记一行：日志里
     * {@code total} 只增不减、{@code done} 单调向上，就是这条链路的验收证据。
     */
    private void logDownloadSessionProgress() {
        int totalItems;
        long totalBytes;
        int doneItems;
        long doneBytes;
        long activeBytes = 0L;
        synchronized (mLock) {
            if (!mDownSessionOpen) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - mLastDownProgressLogAt < DOWN_PROGRESS_LOG_MS) {
                return;
            }
            mLastDownProgressLogAt = now;
            totalItems = mDownPlannedItems;
            totalBytes = mDownPlannedBytes;
            doneItems = mDownDoneItems;
            doneBytes = mDownDoneBytes;
            for (Active active : mActive.values()) {
                if (ProgressProtocol.DIR_DOWN.equals(active.dir)) {
                    activeBytes += Math.min(Math.max(active.sent, 0L),
                            Math.max(active.size, 0L));
                }
            }
        }
        Logger.i("event=download_session_progress total_items=" + totalItems
                + " done_items=" + doneItems + " total_bytes=" + totalBytes
                + " done_bytes=" + doneBytes + " active_bytes=" + activeBytes);
    }

    /** 关停心跳线程；热重载交接与队列 shutdown 时调 */
    public void stop() {
        Thread thread;
        synchronized (mLock) {
            mRunning = false;
            thread = mThread;
            mThread = null;
            mLock.notifyAll();
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void loop() {
        while (mRunning) {
            boolean dirty;
            int activeCount;
            boolean downEndedNow = false;
            int endedItems = 0;
            long endedBytes = 0L;
            int endedPlanned = 0;
            synchronized (mLock) {
                dirty = mDirty;
                mDirty = false;
                activeCount = mActive.size();
                // 会话收尾：在传集合空置够久就关掉，下次开始新项时累计清零。
                // 放在这里而不是 taskEnd 里，是因为"够久"只能在时间轴上判
                if (mSessionOpen && activeCount == 0
                        && System.currentTimeMillis() - mSessionIdleSince >= SESSION_GAP_MS) {
                    mSessionOpen = false;
                }
                // 下载会话收尾：队列确认过"没有待下项"、下载侧也没有在传项、且静默够久。
                // 三条同时成立才算结束 —— 少任何一条都会在批次之间把总任务打回 0
                if (mDownSessionOpen && mDownDrained && !hasActiveDownloadLocked()
                        && System.currentTimeMillis() - mDownLastActivityAt >= DOWN_SETTLE_MS) {
                    endedPlanned = mDownPlannedItems;
                    endedItems = mDownDoneItems;
                    endedBytes = mDownDoneBytes;
                    mDownSessionOpen = false;
                    mDownEnded = true;
                    mDownDrained = false;
                    mDownPlannedItems = 0;
                    mDownPlannedBytes = 0L;
                    mDownDoneItems = 0;
                    mDownDoneBytes = 0L;
                    downEndedNow = true;
                    mDirty = true;
                }
            }
            if (downEndedNow) {
                Logger.i("event=auto_download_session_end planned=" + endedPlanned
                        + " done_items=" + endedItems + " done_bytes=" + endedBytes);
            }
            publish();
            logDownloadSessionProgress();
            long wait = (dirty || activeCount > 0) ? HEARTBEAT_MS : IDLE_WAIT_MS;
            synchronized (mLock) {
                // 发帧期间又有变化就别睡了，立刻补一帧
                if (mRunning && !mDirty) {
                    try {
                        mLock.wait(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private void publish() {
        String payload;
        try {
            payload = buildPayload();
        } catch (Throwable t) {
            Logger.w("event=progress_publish_failed detail=" + t);
            return;
        }
        try {
            Intent intent = new Intent(ProgressProtocol.ACTION);
            // 定向：只有模块 App 收得到。App 没在跑时这帧被静默丢弃，也不会把它拉起来
            intent.setPackage(ProgressProtocol.MODULE_PACKAGE);
            intent.putExtra(ProgressProtocol.EXTRA_PAYLOAD, payload);
            mContext.sendBroadcast(intent);
        } catch (Throwable t) {
            // 广播发不出去不影响上传，只记一笔
            Logger.w("event=progress_publish_failed detail=" + t);
        }
    }

    private String buildPayload() throws Exception {
        List<QueueView> views = new ArrayList<>();
        synchronized (mLock) {
            views.addAll(mQueueViews);
        }
        int pendingCount = 0;
        long pendingBytes = 0;
        int pendingPhotos = 0;
        int pendingVideos = 0;
        boolean healthy = true;
        for (QueueView view : views) {
            pendingCount += view.pendingCount();
            pendingBytes += view.pendingBytes();
            pendingPhotos += view.pendingPhotos();
            pendingVideos += view.pendingVideos();
            // 队列还没接线时按"健康"处理：宁可显示正常，也不要凭空吓用户一跳
            healthy = healthy && view.healthy();
        }

        List<Active> snapshot = new ArrayList<>();
        int doneItems;
        long doneBytes;
        int downPlannedItems;
        long downPlannedBytes;
        int downDoneItems;
        long downDoneBytes;
        boolean downEnded;
        synchronized (mLock) {
            snapshot.addAll(mActive.values());
            doneItems = mDoneItems;
            doneBytes = mDoneBytes;
            downPlannedItems = mDownPlannedItems;
            downPlannedBytes = mDownPlannedBytes;
            downDoneItems = mDownDoneItems;
            downDoneBytes = mDownDoneBytes;
            downEnded = mDownEnded;
        }

        long activeBytes = 0;
        long downActiveBytes = 0;
        JSONArray activeArray = new JSONArray();
        for (Active active : snapshot) {
            activeBytes += active.size;
            if (ProgressProtocol.DIR_DOWN.equals(active.dir)) {
                // 在传项的已下字节：让进度条在一张 20MB 的原图内部也往前走。
                // 完成数仍只按"整个文件落地"算（见 mDownDoneItems）
                downActiveBytes += Math.min(Math.max(active.sent, 0L), Math.max(active.size, 0L));
            }
            JSONObject item = new JSONObject();
            item.put("k", active.key);
            item.put("n", active.name);
            item.put("p", active.path);
            item.put("s", active.size);
            item.put("b", Math.min(active.sent, active.size));
            item.put("vid", active.video);
            item.put("dir", active.dir);
            activeArray.put(item);
        }

        String state;
        if (!healthy) {
            state = ProgressProtocol.STATE_UNHEALTHY;
        } else if (!snapshot.isEmpty()) {
            state = ProgressProtocol.STATE_UPLOADING;
        } else {
            state = ProgressProtocol.STATE_IDLE;
        }

        JSONObject root = new JSONObject();
        root.put("v", ProgressProtocol.VERSION);
        root.put("ts", System.currentTimeMillis());
        root.put("state", state);
        root.put("active", activeArray);
        root.put("pending", pendingCount);
        // 队列里图片/视频各占多少：界面拿它把"还剩 N 项"说成人话
        root.put("ph", pendingPhotos);
        root.put("pv", pendingVideos);
        // 排队列表：只在队列增删过、或隔几秒兜一次底的时候才重新序列化。整张列表十几 KB，
        // 2Hz 地发等于把 Binder 当水管用；平时这一项就省掉了，客户端沿用上一份
        JSONArray pendingArray = pendingArrayFor(views);
        if (pendingArray != null) {
            root.put("pl", pendingArray);
        }
        // 会话总量 = 已完成 + 正在传 + 还没轮到。中途新入队的项也计入，所以进度条可能
        // 微退 —— 诚实优于假装单调
        root.put("sDoneItems", doneItems);
        root.put("sTotalItems", doneItems + snapshot.size() + pendingCount);
        root.put("sDoneBytes", doneBytes);
        root.put("sTotalBytes", doneBytes + activeBytes + pendingBytes);
        // 下载会话的累计计数（只增）：与上面的 s* 是两套语义，s* 一个字都没改。
        // s* 里的 total 会随着队列被消费而变小，只能当"还剩多少活"；这里给的才是
        // "本次要下多少 / 下完了多少"
        root.put(ProgressProtocol.DOWN_PLANNED_ITEMS, downPlannedItems);
        root.put(ProgressProtocol.DOWN_PLANNED_BYTES, downPlannedBytes);
        root.put(ProgressProtocol.DOWN_DONE_ITEMS, downDoneItems);
        root.put(ProgressProtocol.DOWN_DONE_BYTES, downDoneBytes);
        root.put(ProgressProtocol.DOWN_ACTIVE_BYTES, downActiveBytes);
        root.put(ProgressProtocol.DOWN_ENDED, downEnded);
        return root.toString();
    }

    /**
     * 取（必要时重建）排队列表的 JSON，把每个队列视图的列表拼在一起。
     *
     * @return 需要重传时返回新的数组，否则返回 null 表示"沿用上一份"
     */
    private JSONArray pendingArrayFor(List<QueueView> views) {
        if (views.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (mLastPendingVersions.length != views.size()) {
            // 队列接线的时机晚于心跳线程启动，数组长度要跟着长
            int[] grown = new int[views.size()];
            java.util.Arrays.fill(grown, Integer.MIN_VALUE);
            System.arraycopy(mLastPendingVersions, 0, grown, 0,
                    Math.min(mLastPendingVersions.length, grown.length));
            mLastPendingVersions = grown;
        }
        boolean changed = false;
        for (int i = 0; i < views.size(); i++) {
            int version = views.get(i).pendingVersion();
            if (version != mLastPendingVersions[i]) {
                mLastPendingVersions[i] = version;
                changed = true;
            }
        }
        if (!changed && now - mLastPendingAt < PENDING_REFRESH_MS) {
            return null;
        }
        mLastPendingAt = now;
        JSONArray array = new JSONArray();
        try {
            for (QueueView view : views) {
                // 这里**不能**用 pendingCount（"还没轮到的项数"）判空：队列只剩 3 项且全在传时
                // 它等于 0，会把列表发成空数组，客户端的排队部分直接清空、在传项也没了。
                // 列表是否为空只由 pendingItems 的实际返回值决定
                for (PendingItem item : view.pendingItems(MAX_PENDING_ITEMS)) {
                    JSONObject obj = new JSONObject();
                    obj.put("k", item.key);
                    obj.put("n", item.name);
                    obj.put("p", item.path);
                    obj.put("s", item.size);
                    obj.put("vid", item.video);
                    obj.put("dir", item.dir);
                    array.put(obj);
                }
            }
        } catch (Throwable t) {
            // 队列侧取列表失败不该让整帧作废：这一帧少一段，下一帧再试
            Logger.w("event=progress_pending_failed detail=" + t);
            return null;
        }
        return array;
    }
}
