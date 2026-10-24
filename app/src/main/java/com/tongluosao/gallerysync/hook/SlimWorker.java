package com.tongluosao.gallerysync.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;

import android.net.Uri;

import com.tongluosao.gallerysync.core.Config;
import com.tongluosao.gallerysync.core.Logger;
import com.tongluosao.gallerysync.core.ProgressProtocol;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模块自己驱动的空间优化队列：按候选 id 列表并发跑 {@link SlimHook#runSlim}。
 *
 * <p>为什么不用相册那条：它的 {@code SlimController} 是串行的，每张之间还要空等 4–5 秒
 * （实测 CPU 0–3%），我们自己的部分是 1.0 s（JPEG）/2.3 s（HEIC）。相册那边改成对每张
 * 立即返回 0 之后，真正干活的就是这里：固定大小的线程池，把同一份单张流程并发跑起来。
 *
 * <p>分工：
 * <ul>
 *   <li>候选 id 由调用方给（{@link SlimCandidates#collect} 或上一轮扫描的结果），本类不猜。</li>
 *   <li>单张的安全校验、编码、租约、发布、删原图、回滚全在 {@link SlimHook#runSlim} 里，
 *       和相册自己的入口是同一份实现；这里只负责"取回条目、排队、计数、报进度"。</li>
 *   <li>同一 id 只入队一次；{@link #stop()} 只置停止标志，工作线程做完手上这张就退出。</li>
 * </ul>
 *
 * <p>纯逻辑（去重/limit/停止取任务/计数累加）刻意抽成了 {@link #plan}、{@link Cursor}、
 * {@link Tally} 三个不碰线程也不碰 Android 的小东西，单测见 {@code SlimWorkerQueueTest}。
 */
public final class SlimWorker {

    /** 线程名前缀：{@code MiGallerySync-slim-1}、{@code -2}…… 抓线程栈时一眼能认出来。 */
    private static final String THREAD_PREFIX = "MiGallerySync-slim-";

    /** 相册自己的云表（模块只能在这个进程里读它） */
    private static final String CLOUD_URI = "content://com.miui.gallery.cloud.provider/cloud";

    /**
     * 取条目用的列。刻意只取模块真正要用的那几个：{@code SlimHook.SlimItem} 的字段就在这里一一对应
     * 拼出来，不再经过宿主的 {@code MediaItem}（那个只能从 {@code queryMediaItem} 拿，而它写死了
     * {@code localFlag = 0}，本机这些照片全是 7 的本地行，一条都取不出来）。
     */
    private static final String[] ITEM_COLUMNS = {
            "_id", "localFile", "thumbnailFile", "sha1", "serverType",
            "mixedDateTime", "fileName", "exifDateTime", "exifGPSDateStamp", "exifGPSTimeStamp"};

    private static final String CLS_CONTROLLER = "com.miui.gallery.cleaner.slim.SlimController";

    /** 并发路数夹到这个区间：再低没必要，再高抢相册自己的前台线程。 */
    private static final int MIN_CONCURRENCY = 1;
    private static final int MAX_CONCURRENCY = 8;
    private static final int DEFAULT_CONCURRENCY = 6;

    /** 进度广播的最小间隔：一张一张刷会让接收端每秒重建几十次界面。 */
    private static final long PROGRESS_INTERVAL_MS = 200L;

    /** 分项原因最长的字符数：异常消息可能很长，界面一行放不下，广播也不该被它撑大。 */
    private static final int REASON_MAX_CHARS = 160;

    /** "这张不归模块管"在分项里的原因串：与失败区分开，界面据此说"已跳过"而不是"失败" */
    private static final String REASON_SKIPPED = "skipped";

    /** 取不到条目（行没了/被相册改过/查询被拦）时的原因串 */
    private static final String REASON_RESOLVE_FAILED = "resolve_failed";

    private static final String STATE_RUNNING = "running";
    private static final String STATE_STOPPING = "stopping";
    private static final String STATE_DONE = "done";

    /** 进度广播的 action：模块自己的备份进度名再挂一个 {@code .slim}，两个 receiver 不会串台。 */
    private static final String ACTION_SLIM = ProgressProtocol.ACTION + ".slim";

    private static final SlimWorker INSTANCE = new SlimWorker();

    /** 宿主 ClassLoader 与 {@link SlimHook} 实例：{@code SlimHook.install()} 成功后才非空。 */
    private static volatile SlimHook sHook;
    private static volatile ClassLoader sClassLoader;

    /** 全部可变状态都躺在这把锁后面；工作线程只在取任务/记账时短暂持有。 */
    private final Object mLock = new Object();
    private final Object mLookupLock = new Object();

    private final Tally mTally = new Tally();

    private boolean mRunning;
    private boolean mStopping;
    private String mState = ProgressProtocol.STATE_IDLE;
    private int mTotal;
    private String mCurrent = "";
    private long mUpdatedAt;
    private long mLastProgressAt;

    /**
     * 本轮开始时刻（epoch ms），随每帧发出去，界面据此算速率与剩余时间。
     * 界面自己没有"这一轮从哪开始"的锚点：它可能在轮次中间才被打开。
     */
    private long mStartedAt;

    /**
     * 本轮的逐项流水，**最新在前**、最多 {@link ProgressProtocol#ITEMS_MAX} 条。
     * 与 {@link #mTally} 一样只在这把锁后面改；收尾会原地更新同一行（不重排），
     * 否则一项做完就跳到列表最前面，看着像凭空多出来一行。
     */
    private final ArrayDeque<ItemRow> mItems = new ArrayDeque<>();

    private int mActiveThreads;
    private Cursor mCursor = new Cursor(Collections.emptyList());
    private ExecutorService mExecutor;

    /** {@code SlimController} 的反射句柄，懒解析一次。 */
    private Object mController;
    private Method mQuery;
    private SlimHook.ItemFields mFields;

    private SlimWorker() {
    }

    public static SlimWorker get() {
        return INSTANCE;
    }

    /** 由 {@link SlimHook#install()} 调用：没有宿主 ClassLoader 就没法按 id 取回条目。 */
    static void attach(SlimHook hook, ClassLoader classLoader) {
        sHook = hook;
        sClassLoader = classLoader;
    }

    /**
     * 起一轮优化。候选 id 由调用方给，null/空表直接返回 false（不启动）。
     *
     * @param config      当前生效的配置；不 ready 时拒绝启动（单张校验也依赖它）
     * @param candidateIds 候选门槛的 cloud id；重复的会在 {@link #plan} 里去掉
     * @param concurrency 并发路数，夹到 1..4，&lt;=0 用默认 3
     * @param limit       最多处理多少张，&lt;=0 表示不限制
     * @return 是否真的起了线程池
     */
    public boolean start(Config config, List<Long> candidateIds, int concurrency, int limit) {
        if (config == null || !config.isReady()) {
            Logger.w("event=slim_worker_rejected reason=config_not_ready");
            return false;
        }
        SlimHook hook = sHook;
        ClassLoader classLoader = sClassLoader;
        if (hook == null || classLoader == null) {
            Logger.w("event=slim_worker_rejected reason=slim_hook_not_installed");
            return false;
        }
        List<Long> planned = plan(candidateIds, limit);
        if (planned.isEmpty()) {
            Logger.d("event=slim_worker_rejected reason=no_candidates");
            return false;
        }
        int width = clampConcurrency(concurrency);
        ExecutorService executor;
        synchronized (mLock) {
            if (mRunning) {
                Logger.w("event=slim_worker_rejected reason=already_running");
                return false;
            }
            mRunning = true;
            mStopping = false;
            mState = STATE_RUNNING;
            mTotal = planned.size();
            mTally.reset();
            mCurrent = "";
            mUpdatedAt = System.currentTimeMillis();
            mLastProgressAt = 0L;
            mStartedAt = mUpdatedAt;
            // 新一轮一开始就把上一轮的分项清掉：界面上不该混着两次优化的行
            mItems.clear();
            mActiveThreads = width;
            mCursor = new Cursor(planned);
            mExecutor = Executors.newFixedThreadPool(width, new WorkerThreadFactory());
            executor = mExecutor;
        }
        Logger.i("event=slim_worker_start total=" + planned.size() + " concurrency=" + width);
        publish(true);
        for (int i = 0; i < width; i++) {
            try {
                executor.execute(() -> runWorker(hook, classLoader));
            } catch (Throwable t) {
                // 线程池拒了就少一路；记账照走，否则 isRunning() 会永远挂着
                Logger.e("event=slim_worker_task_rejected", t);
                onWorkerExit();
            }
        }
        return true;
    }

    /** 停止：不再领新任务，手上这张做完就退出。重复调用是空操作。 */
    public void stop() {
        boolean changed = false;
        synchronized (mLock) {
            if (mRunning && !mStopping) {
                mStopping = true;
                mState = STATE_STOPPING;
                mUpdatedAt = System.currentTimeMillis();
                changed = true;
            }
        }
        if (changed) {
            Logger.i("event=slim_worker_stop_requested");
            publish(true);
        }
    }

    /** 最后一个工作线程退出后才变 false。 */
    public boolean isRunning() {
        synchronized (mLock) {
            return mRunning;
        }
    }

    /** 当前这一帧的载荷（与广播出去的是同一个东西），给命令回执/界面兜底用。 */
    public String snapshotJson() {
        synchronized (mLock) {
            return snapshotJsonLocked(System.currentTimeMillis());
        }
    }

    // ---------------------------------------------------------------- 工作线程

    private void runWorker(SlimHook hook, ClassLoader classLoader) {
        try {
            for (;;) {
                long id;
                synchronized (mLock) {
                    Long next = mCursor.next(mStopping);
                    if (next == null) {
                        return;
                    }
                    id = next;
                }
                long stepStart = System.nanoTime();
                SlimHook.SlimItem item;
                try {
                    item = resolve(classLoader, id);
                } catch (Throwable t) {
                    Logger.e("event=slim_worker_resolve_failed id=" + id, t);
                    item = null;
                }
                if (item == null) {
                    // 取不到条目（行没了、被相册改过、或查询被拦）不算错，跳过并记账
                    failed(id, "", elapsedMs(stepStart), REASON_RESOLVE_FAILED);
                    continue;
                }
                String name = fileNameOf(item);
                synchronized (mLock) {
                    mCurrent = name;
                    // 先落一行"进行中"再广播：界面能立刻看到这一项，而不是等它做完才出现
                    beginItemLocked(id, name);
                }
                publish(false);
                long resolveMs = (System.nanoTime() - stepStart) / 1000000L;
                long slimStart = System.nanoTime();
                long saved;
                try {
                    saved = hook.runSlim(item);
                } catch (Throwable t) {
                    Logger.e("event=slim_worker_item_failed id=" + id, t);
                    failed(id, name, elapsedMs(stepStart), reasonOf(t));
                    continue;
                }
                if (saved == SlimHook.PASSTHROUGH) {
                    // 这张不归模块管：runSlim 内部已经按原样打过 slim_skipped
                    completed(id, name, 0L, elapsedMs(stepStart), REASON_SKIPPED);
                    continue;
                }
                long slimMs = (System.nanoTime() - slimStart) / 1000000L;
                Logger.i("event=slim_worker_step id=" + id + " resolve=" + resolveMs + " slim=" + slimMs);
                completed(id, name, saved, elapsedMs(stepStart), null);
            }
        } finally {
            onWorkerExit();
        }
    }

    /**
     * 按 id 取回 {@link SlimHook.SlimItem}。
     *
     * <p>走宿主自己的 {@code SlimController.queryMediaItem(long[])}：它按 {@code _id IN (...)}
     * 查 cloud 表、保留原生 widget 排除条件、再用 {@code MediaItem} 的构造器读成条目。
     * 模块 App 进程没有这个 provider 的读权限，只能在相册进程里调。
     */
    /**
     * 按 id 取回一条 {@link SlimHook.SlimItem}。
     *
     * <p>直接查相册自己的 {@code cloud} provider，再拼出 SlimItem —— 不走宿主的
     * {@code SlimController.queryMediaItem}：它在 SQL 里写死了 {@code localFlag = 0}，而本机这些照片
     * 全是 {@code localFlag = 7} 的"仅本地"行（云端早就被模块接管，相册从来没把它们标成已同步），
     * 用它查出来永远是空表。列名与 {@code SlimController$MediaItem} 的投影一致。
     */
    private SlimHook.SlimItem resolve(ClassLoader classLoader, long id) throws Exception {
        Context context = HostContext.get(classLoader);
        if (context == null) {
            return null;
        }
        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) {
            return null;
        }
        try (android.database.Cursor cursor = resolver.query(Uri.parse(CLOUD_URI), ITEM_COLUMNS,
                "_id = ?", new String[]{Long.toString(id)}, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            String path = text(cursor, "localFile");
            String sha1 = text(cursor, "sha1");
            if (path == null || path.isEmpty() || sha1 == null || sha1.isEmpty()) {
                return null;
            }
            return new SlimHook.SlimItem(
                    (int) id,
                    path,
                    text(cursor, "thumbnailFile"),
                    sha1,
                    Long.toString(id),
                    number(cursor, "serverType", 0),
                    number(cursor, "mixedDateTime", 0L),
                    text(cursor, "fileName"),
                    text(cursor, "exifDateTime"),
                    text(cursor, "exifGPSDateStamp"),
                    text(cursor, "exifGPSTimeStamp"));
        }
    }

    private static String text(android.database.Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) {
            return null;
        }
        return cursor.getString(index);
    }

    private static int number(android.database.Cursor cursor, String column, int fallback) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) {
            return fallback;
        }
        return cursor.getInt(index);
    }

    private static long number(android.database.Cursor cursor, String column, long fallback) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) {
            return fallback;
        }
        return cursor.getLong(index);
    }

    private SlimHook.ItemFields fieldsFor(Class<?> mediaType) throws Exception {
        SlimHook.ItemFields fields = mFields;
        if (fields == null) {
            fields = new SlimHook.ItemFields(mediaType);
            mFields = fields;
        }
        return fields;
    }

    private static String fileNameOf(SlimHook.SlimItem item) {
        if (item.fileName != null && !item.fileName.isEmpty()) return item.fileName;
        if (item.path == null || item.path.isEmpty()) return "";
        int slash = item.path.lastIndexOf('/');
        return slash < 0 ? item.path : item.path.substring(slash + 1);
    }

    private void completed(long id, String name, long saved, long ms, String reason) {
        long savedBytes = Math.max(0L, saved);
        synchronized (mLock) {
            mTally.recordSaved(savedBytes);
            mUpdatedAt = System.currentTimeMillis();
            finishItemLocked(id, name, true, ms, reason);
        }
        Logger.i("event=slim_worker_item id=" + id + " saved=" + savedBytes + " ms=" + ms);
        publish(false);
    }

    private void failed(long id, String name, long ms, String reason) {
        synchronized (mLock) {
            mTally.recordFailed();
            mUpdatedAt = System.currentTimeMillis();
            finishItemLocked(id, name, false, ms, reason);
        }
        Logger.w("event=slim_worker_item_failed id=" + id + " ms=" + ms + " reason=" + reason);
        publish(false);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1000000L;
    }

    /**
     * 把异常压成一行可读的原因。
     *
     * <p>相册进程里抛上来的东西五花八门（反射的 {@code InvocationTargetException}、provider 的
     * {@code IllegalArgumentException} 等），界面上只需要"哪儿错了"这一个线索；类名 + message
     * 足够，堆栈留在日志里。
     */
    private static String reasonOf(Throwable t) {
        if (t == null) {
            return "";
        }
        String name = t.getClass().getSimpleName();
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            // 反射调用失败时真正的异常在 cause 里，包在外面的那个名字没有信息量
            name = cause.getClass().getSimpleName();
            t = cause;
        }
        String message = t.getMessage();
        return message == null || message.isEmpty() ? name : name + ": " + message;
    }

    // ---------------------------------------------------------------- 分项流水

    /**
     * 一项开始：插到队首（最新在前）。同一 id 已经有行（重试/重复入队）就原地重置。
     * 调用方必须持有 {@link #mLock}。
     */
    private void beginItemLocked(long id, String name) {
        for (ItemRow row : mItems) {
            if (row.id == id) {
                row.name = name == null ? "" : name;
                row.state = ProgressProtocol.ITEM_RUNNING;
                row.ms = 0L;
                row.reason = null;
                return;
            }
        }
        mItems.addFirst(new ItemRow(id, name));
        trimItemsLocked();
    }

    /**
     * 一项收尾：原地改成 done/failed 并记下耗时与原因。
     *
     * <p>找不到那一行时要补插一条：开始那一帧可能正好落在 200ms 节流窗口里没发出去，
     * 否则界面上这一项会"凭空出现"在别的位置。调用方必须持有 {@link #mLock}。
     */
    private void finishItemLocked(long id, String name, boolean ok, long ms, String reason) {
        ItemRow target = null;
        for (ItemRow row : mItems) {
            if (row.id == id) {
                target = row;
                break;
            }
        }
        if (target == null) {
            target = new ItemRow(id, name);
            mItems.addFirst(target);
            trimItemsLocked();
        }
        target.state = ok ? ProgressProtocol.ITEM_DONE : ProgressProtocol.ITEM_FAILED;
        target.ms = Math.max(0L, ms);
        target.reason = reason;
    }

    private void trimItemsLocked() {
        while (mItems.size() > ProgressProtocol.ITEMS_MAX) {
            mItems.removeLast();
        }
    }

    /** 分项列表的 JSON 数组串；空列表发 `[]`（合法载荷，接收端据此知道是"这一轮还没开始"）。 */
    private String itemsJsonLocked() {
        StringBuilder out = new StringBuilder(mItems.size() * 96 + 2);
        out.append('[');
        boolean first = true;
        for (ItemRow row : mItems) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('{')
                    .append('"').append(ProgressProtocol.ITEM_KEY_ID).append("\":\"").append(row.id).append('"')
                    .append(",\"").append(ProgressProtocol.ITEM_KEY_NAME).append("\":\"")
                    .append(escape(row.name)).append('"')
                    .append(",\"").append(ProgressProtocol.ITEM_KEY_STATE).append("\":\"").append(row.state).append('"')
                    .append(",\"").append(ProgressProtocol.ITEM_KEY_MS).append("\":").append(row.ms)
                    .append(",\"").append(ProgressProtocol.ITEM_KEY_REASON).append("\":\"")
                    .append(escape(clipReason(row.reason))).append('"')
                    .append('}');
        }
        out.append(']');
        return out.toString();
    }

    private static String clipReason(String reason) {
        if (reason == null || reason.isEmpty()) {
            return "";
        }
        return reason.length() <= REASON_MAX_CHARS
                ? reason
                : reason.substring(0, REASON_MAX_CHARS - 3) + "...";
    }

    /** 一帧分项进度里的一行。字段名与 {@link ProgressProtocol} 的 {@code ITEM_KEY_*} 一一对应。 */
    private static final class ItemRow {
        final long id;
        String name;
        String state = ProgressProtocol.ITEM_RUNNING;
        long ms;
        String reason;

        ItemRow(long id, String name) {
            this.id = id;
            this.name = name == null ? "" : name;
        }
    }

    private void onWorkerExit() {
        int done;
        int failed;
        int total;
        long saved;
        ExecutorService executor;
        synchronized (mLock) {
            if (mActiveThreads > 0) mActiveThreads--;
            if (mActiveThreads > 0) return;
            mRunning = false;
            mStopping = false;
            mState = STATE_DONE;
            mCurrent = "";
            mUpdatedAt = System.currentTimeMillis();
            done = mTally.done();
            failed = mTally.failed();
            total = mTotal;
            saved = mTally.saved();
            executor = mExecutor;
            mExecutor = null;
        }
        Logger.i("event=slim_worker_stop done=" + done + " failed=" + failed);
        publish(true);
        Logger.i("event=slim_worker_done total=" + total + " saved=" + saved);
        if (executor != null) executor.shutdown();
    }

    // ---------------------------------------------------------------- 进度广播

    private void publish(boolean force) {
        String json;
        String items;
        synchronized (mLock) {
            long now = System.currentTimeMillis();
            if (!force && now - mLastProgressAt < PROGRESS_INTERVAL_MS) return;
            mLastProgressAt = now;
            mUpdatedAt = now;
            json = snapshotJsonLocked(now);
            items = itemsJsonLocked();
        }
        try {
            Context context = HostContext.get(sClassLoader);
            if (context == null) return;
            Intent intent = new Intent(ACTION_SLIM);
            intent.setPackage(ProgressProtocol.MODULE_PACKAGE);
            intent.putExtra(ProgressProtocol.EXTRA_PAYLOAD, json);
            // 分项走独立的 extra：老接收端不认识这个键，照样按帧显示总进度，不会整帧弃用
            intent.putExtra(ProgressProtocol.EXTRA_ITEMS, items);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            // 无接收者 / 系统拦了隐性广播，都属正常：进度丢了不影响已经删掉的原图
            Logger.d("event=slim_worker_broadcast_failed detail=" + t);
        }
    }

    private String snapshotJsonLocked(long now) {
        return "{\"v\":" + ProgressProtocol.VERSION
                + ",\"state\":\"" + mState + '"'
                + ",\"total\":" + mTotal
                + ",\"done\":" + mTally.done()
                + ",\"failed\":" + mTally.failed()
                + ",\"saved\":" + mTally.saved()
                + ",\"current\":\"" + escape(mCurrent) + '"'
                // 本轮开始时刻：界面靠它算速率与剩余时间，自己在半路打开时也能对得上账
                + ",\"startedAt\":" + mStartedAt
                + ",\"updatedAt\":" + now
                + '}';
    }

    private static String escape(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- 纯逻辑（单测覆盖）

    /**
     * 把调用方给的候选整理成本轮真正要跑的清单：丢掉 null/非正 id，按原顺序去重，再按
     * {@code limit} 截断（{@code limit <= 0} 表示不限）。
     */
    static List<Long> plan(List<Long> candidateIds, int limit) {
        if (candidateIds == null || candidateIds.isEmpty()) return Collections.emptyList();
        Set<Long> unique = new LinkedHashSet<>();
        for (Long id : candidateIds) {
            if (id == null || id <= 0L) continue;
            unique.add(id);
        }
        List<Long> planned = new ArrayList<>(unique);
        if (limit > 0 && planned.size() > limit) {
            return new ArrayList<>(planned.subList(0, limit));
        }
        return planned;
    }

    /** 并发数夹到 1..4；{@code <= 0} 用默认 3。 */
    static int clampConcurrency(int requested) {
        if (requested <= 0) return DEFAULT_CONCURRENCY;
        if (requested < MIN_CONCURRENCY) return MIN_CONCURRENCY;
        if (requested > MAX_CONCURRENCY) return MAX_CONCURRENCY;
        return requested;
    }

    /**
     * 领取下一个 id 的游标：{@code stopRequested} 一置真就再也不发新任务（已经发出去的那张
     * 仍然跑完），数组走完也返回 null。工作线程会在这把锁里调，所以方法本身就是同步的。
     */
    static final class Cursor {
        private final List<Long> mIds;
        private int mIndex;

        Cursor(List<Long> ids) {
            mIds = ids == null ? Collections.emptyList() : ids;
        }

        synchronized Long next(boolean stopRequested) {
            if (stopRequested) return null;
            if (mIndex >= mIds.size()) return null;
            return mIds.get(mIndex++);
        }

        synchronized int index() {
            return mIndex;
        }
    }

    /** 本轮进度的纯累加器：done 只记跑完的，failed 单记，saved 只累加非负字节。 */
    static final class Tally {
        private int mDone;
        private int mFailed;
        private long mSaved;

        void reset() {
            mDone = 0;
            mFailed = 0;
            mSaved = 0L;
        }

        void recordSaved(long savedBytes) {
            mDone++;
            mSaved += Math.max(0L, savedBytes);
        }

        void recordFailed() {
            mFailed++;
        }

        int done() {
            return mDone;
        }

        int failed() {
            return mFailed;
        }

        long saved() {
            return mSaved;
        }

        int processed() {
            return mDone + mFailed;
        }
    }

    /** 线程名要能一眼看出是模块的池子，并且从 1 开始编号。 */
    private static final class WorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger mCount = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, THREAD_PREFIX + mCount.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
