package com.tongluosao.gallerysync.core;

/**
 * 传输后收尾（{@code adoptAutoDownloadedRow} + {@code notifyHostAfterPublish}）的调度策略。
 *
 * <p>为什么要把这两步从传输车道挪走：真机上单对象传输只要 0.1–1 s，而
 * {@code get_done → download_ok} 平均 13–15 s，中间既没有网络也没有日志 —— 时间全花在
 * "改相册 cloud 行"和"等宿主扫描器"上。这两步都是**别人的锁 + 别人的队列**，
 * 车道线程陪着等，等于把并发的收益全部还回去。
 *
 * <p>本类只给判据，不持有线程也不碰 Android：线程池、日志、通知都在
 * {@link DownloadQueue} 里，边界值因此可以在纯 JVM 单测里钉死。
 */
public final class FinalizePipeline {

    /** 收尾线程数上限：再多只会让相册 provider 的行更新互相排队。 */
    public static final int MAX_WORKERS = 4;
    /** 收尾预算的固定部分：一次行更新 + 一次扫描请求的正常上限。 */
    public static final long BASE_TIMEOUT_MS = 4_000L;
    /** 每 MiB 追加的预算：大对象的 provider 校验/缩略图生成确实更久。 */
    public static final long TIMEOUT_PER_MIB_MS = 250L;
    /** 收尾预算上限：超时只用于判据与打点，从不强杀线程（provider 调用不可中断）。 */
    public static final long MAX_TIMEOUT_MS = 120_000L;
    private static final long MIB = 1024L * 1024L;

    private FinalizePipeline() {
    }

    /**
     * 收尾池的线程数：{@code clamp(1, 4, (concurrency + 1) / 2)}。
     *
     * <p>取一半的理由：一条车道每传成一个对象只需要一次收尾，而单次收尾（13–15 s）
     * 约是单次传输（0.1–1 s）的十几倍，所以"喂满车道"并不需要与车道等量的收尾线程；
     * 但收尾耗时的方差很大（宿主扫描器 p90 更高），取一半留出追赶余量。
     * 下限 1 是因为 0 线程等于把收尾永久留在车道上；上限 4 是因为这一段的瓶颈是
     * 相册 provider 的串行锁，线程再多也只是把等待从车道搬到另一个队列。
     */
    public static int workerCount(int concurrency) {
        int lanes = Math.max(1, concurrency);
        // 向上取整的一半：3 路配置给 2 个收尾线程，避免小配置下一路独撑。
        // 写成 lanes/2 + lanes%2 而不是 (lanes+1)/2：后者在 Integer.MAX_VALUE 上溢出成负数。
        int half = lanes / 2 + lanes % 2;
        return Math.max(1, Math.min(MAX_WORKERS, half));
    }

    /**
     * 队列满时由调用方（传输车道）同步执行。
     *
     * <p>这是<b>有界</b>池的背压落点：收尾任务每个都可能压着几十秒，无界队列只会把
     * 内存和"已提交但还没生效"的时间一起拉长；满了就地做完，语义上等于"这条车道
     * 先自己收尾再领下一个"，既不会丢任务，也不会无限堆积。
     *
     * @param queued   池队列里当前的任务数（负值 = 池已关闭/不可观测）
     * @param capacity 池队列容量
     */
    public static boolean shouldRunInline(int queued, int capacity) {
        if (capacity <= 0) {
            return true;
        }
        if (queued < 0) {
            // 池已不可用（shutdown 中）：同步做，绝不能把收尾丢掉
            return true;
        }
        return queued >= capacity;
    }

    /**
     * 一次收尾的时间预算：{@code min(120 s, 4 s + ceil(bytes / 1 MiB) * 250 ms)}。
     *
     * <p>只用于"这条收尾已经超出预算"的判据（{@code download_finalize_slow} 打点、
     * 以及后续是否放弃等待的决策），<b>不</b>强杀线程：{@code ContentResolver.update}
     * 和宿主扫描器都不响应 InterruptedException，强杀只会留下半个状态。
     *
     * <p>对字节数严格单调不减（0 字节也有 4 s 底），并与池容量无关。
     */
    public static long timeoutMs(long bytes) {
        long size = Math.max(bytes, 0L);
        // 先夹住 MiB 数再乘，避免 bytes 接近 Long.MAX_VALUE 时溢出成负数
        long mib = size / MIB + (size % MIB == 0L ? 0L : 1L);
        long budgetMib = MAX_TIMEOUT_MS / TIMEOUT_PER_MIB_MS;
        long extra = Math.min(mib, budgetMib) * TIMEOUT_PER_MIB_MS;
        return Math.min(MAX_TIMEOUT_MS, BASE_TIMEOUT_MS + extra);
    }
}
