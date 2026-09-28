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

        /**
         * 还没轮到的项，按队列顺序最多 [limit] 个。
         *
         * <p>进度页要把"还在排队的还有哪些"也显示出来，不然只能看到正在传的 3 张，
         * 完全看不出后面还积压了多少。
         */
        List<PendingItem> pendingItems(int limit);

        /** {@link #pendingItems} 的结构版本；变了才需要重传整张列表 */
        int pendingVersion();

        /** WebDAV 是否可用；false 时进度页要说明"这一轮已交回小米云" */
        boolean healthy();
    }

    /** 队列里一张还没轮到的照片。字段与 {@link Active} 对齐，客户端可以共用一套解析 */
    public static final class PendingItem {
        public final String key;
        public final String name;
        public final String path;
        public final long size;
        public final boolean video;

        public PendingItem(String key, String name, String path, long size, boolean video) {
            this.key = key;
            this.name = name;
            this.path = path;
            this.size = size;
            this.video = video;
        }
    }

    /** 一张正在传的照片 */
    private static final class Active {
        final String key;
        final String name;
        final String path;
        final long size;
        final boolean video;
        /** 由 worker 线程写、心跳线程读 */
        volatile long sent;

        Active(String key, String name, String path, long size, boolean video) {
            this.key = key;
            this.name = name;
            this.path = path;
            this.size = size;
            this.video = video;
        }
    }

    private final Context mContext;
    private final Object mLock = new Object();
    /** 在传集合，插入序 = 开始顺序，客户端按 key 复用卡片所以顺序只影响首次布局 */
    private final Map<String, Active> mActive = new LinkedHashMap<>();
    /** 结构性变化后不等心跳、立刻发帧 */
    private boolean mDirty = true;

    private volatile QueueView mQueueView;
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
     * 上一次序列化好的排队列表对应的队列版本，以及上次重建的时刻。
     *
     * <p>只有心跳线程碰它，不需要加锁。初始版本给 MIN_VALUE，保证第一帧一定带上列表。
     */
    private int mLastPendingVersion = Integer.MIN_VALUE;
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

    public void setQueueView(QueueView view) {
        mQueueView = view;
    }

    /** 某项开始上传。size 取自 Task（入队时记下的），不读磁盘 */
    public void taskStart(String key, String name, String path, long size, boolean video) {
        boolean sessionStarted = false;
        synchronized (mLock) {
            if (!mSessionOpen) {
                mSessionOpen = true;
                mDoneItems = 0;
                mDoneBytes = 0;
                sessionStarted = true;
            }
            mActive.put(key, new Active(key, name, path, size, video));
            mDirty = true;
            mLock.notifyAll();
        }
        if (sessionStarted) {
            Logger.i("event=progress_session_start key=" + key + " size=" + size);
        }
        Logger.d("event=progress_task_start key=" + key + " name=" + name + " size=" + size);
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
            }
            publish();
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
        QueueView view = mQueueView;
        int pendingCount = view == null ? 0 : view.pendingCount();
        long pendingBytes = view == null ? 0 : view.pendingBytes();
        // 队列还没接线时按"健康"处理：宁可显示正常，也不要凭空吓用户一跳
        boolean healthy = view == null || view.healthy();

        List<Active> snapshot = new ArrayList<>();
        int doneItems;
        long doneBytes;
        synchronized (mLock) {
            snapshot.addAll(mActive.values());
            doneItems = mDoneItems;
            doneBytes = mDoneBytes;
        }

        long activeBytes = 0;
        JSONArray activeArray = new JSONArray();
        for (Active active : snapshot) {
            activeBytes += active.size;
            JSONObject item = new JSONObject();
            item.put("k", active.key);
            item.put("n", active.name);
            item.put("p", active.path);
            item.put("s", active.size);
            item.put("b", Math.min(active.sent, active.size));
            item.put("vid", active.video);
            // 预留下载：将来加下载不用改通道，客户端也已经认识这个字段
            item.put("dir", ProgressProtocol.DIR_UP);
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
        // 排队列表：只在队列增删过、或隔几秒兜一次底的时候才重新序列化。整张列表十几 KB，
        // 2Hz 地发等于把 Binder 当水管用；平时这一项就省掉了，客户端沿用上一份
        JSONArray pendingArray = pendingArrayFor(view, pendingCount);
        if (pendingArray != null) {
            root.put("pl", pendingArray);
        }
        // 会话总量 = 已完成 + 正在传 + 还没轮到。中途新入队的项也计入，所以进度条可能
        // 微退 —— 诚实优于假装单调
        root.put("sDoneItems", doneItems);
        root.put("sTotalItems", doneItems + snapshot.size() + pendingCount);
        root.put("sDoneBytes", doneBytes);
        root.put("sTotalBytes", doneBytes + activeBytes + pendingBytes);
        return root.toString();
    }

    /**
     * 取（必要时重建）排队列表的 JSON。
     *
     * @return 需要重传时返回新的数组，否则返回 null 表示"沿用上一份"
     */
    private JSONArray pendingArrayFor(QueueView view, int pendingCount) {
        if (view == null) {
            return null;
        }
        int version = view.pendingVersion();
        long now = System.currentTimeMillis();
        boolean changed = version != mLastPendingVersion;
        if (!changed && now - mLastPendingAt < PENDING_REFRESH_MS) {
            return null;
        }
        mLastPendingVersion = version;
        mLastPendingAt = now;
        // 队列空了就明确发一个空数组：客户端要靠它把列表清掉，不能只靠"这一帧没带 pl"
        if (pendingCount <= 0) {
            return new JSONArray();
        }
        JSONArray array = new JSONArray();
        try {
            for (PendingItem item : view.pendingItems(MAX_PENDING_ITEMS)) {
                JSONObject obj = new JSONObject();
                obj.put("k", item.key);
                obj.put("n", item.name);
                obj.put("p", item.path);
                obj.put("s", item.size);
                obj.put("vid", item.video);
                obj.put("dir", ProgressProtocol.DIR_UP);
                array.put(obj);
            }
        } catch (Throwable t) {
            // 队列侧取列表失败不该让整帧作废：这一帧少一段，下一帧再试
            Logger.w("event=progress_pending_failed detail=" + t);
            return null;
        }
        return array;
    }
}
