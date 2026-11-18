package com.tongluosao.gallerysync.core;

/**
 * 下载字节进度的节流判据与停滞判据（纯函数）。
 *
 * <p>背景：真机进度只出现过 {@code bytes=0/941317} 一行，之后没有任何增量，于是界面上
 * 的"总速度"恒为 0 B/s。读循环本身其实每 64KB 就回调一次
 * （{@code WebDavClient.copyExact/copyChunked} 里的 {@code reportProgress}），问题在队列
 * 这一层：回调只把数字塞给进度发布器，<b>没有一条按字节变化的日志</b>，现场无法判断是
 * "没在下"还是"在下但没报"。所以这里给日志一条明确的节流规则：
 *
 * <ul>
 *   <li>字节每前进 {@link #STEP_BYTES}（256KB）记一行；</li>
 *   <li>或者距上次记录已过 {@link #STEP_MS}（500ms）且字节确实前进了；</li>
 *   <li>字节没有前进时<b>不记</b>（同一行刷屏没有信息量）；</li>
 *   <li>到达总长时强制记一行：最后一行必须是 {@code bytes=N/N}，否则"下完了"看不出来。</li>
 * </ul>
 *
 * <p>另一条判据是停滞：连接卡死时读循环一个字节都不会来，靠回调永远发现不了，
 * 必须由外部定时检查"最后收到字节到现在多久"（{@link #stalled}）。
 *
 * <p>全部纯计算：时间与字节都由调用方传入，不读时钟、不碰文件。
 */
public final class DownloadProgressPolicy {

    /** 字节节流阈值：256KB 一条日志。 */
    public static final long STEP_BYTES = 256L * 1024L;
    /** 时间节流阈值：500ms 一条日志。 */
    public static final long STEP_MS = 500L;
    /** 多久没有新字节就认定这条连接停滞（要关掉重连）。 */
    public static final long STALL_MS = 60_000L;

    private DownloadProgressPolicy() {
    }

    /**
     * 这一刻该不该记一行进度。
     *
     * @param lastBytes  上次记录时的字节数
     * @param lastAt     上次记录的时刻（ms）
     * @param bytes      当前已下载字节数
     * @param total      总长，未知传 0 或负数
     * @param now        当前时刻（ms），与 {@code lastAt} 同一时钟
     */
    public static boolean due(long lastBytes, long lastAt, long bytes, long total, long now) {
        if (bytes <= lastBytes) {
            // 没前进（包括重复回调同一个值）：不记，避免同一行刷屏
            return false;
        }
        if (total > 0L && bytes >= total) {
            // 收尾那一行必须有，否则"下完了"在日志里看不出来
            return true;
        }
        if (bytes - lastBytes >= STEP_BYTES) {
            return true;
        }
        return now - lastAt >= STEP_MS;
    }

    /** 区间速率（B/s）；时间跨度非正或字节没前进时返回 0，绝不返回负数或除零。 */
    public static long bytesPerSecond(long bytes, long lastBytes, long elapsedMs) {
        if (elapsedMs <= 0L || bytes <= lastBytes) {
            return 0L;
        }
        long delta = bytes - lastBytes;
        // 先除后乘会丢精度，先乘后除要防溢出：1GB/1ms 这种量级不可能出现在下载里
        if (delta > Long.MAX_VALUE / 1000L) {
            return delta / (elapsedMs / 1000L + 1L);
        }
        return delta * 1000L / elapsedMs;
    }

    /**
     * 这条传输是不是已经停滞。
     *
     * @param lastByteAt 最后一次收到字节的时刻（ms），从未收到传 0（此时不算停滞：
     *                   连接刚建立、首包还没到，由调用方的总超时兜底）
     * @param now        当前时刻（ms）
     * @param stallMs    停滞阈值
     */
    public static boolean stalled(long lastByteAt, long now, long stallMs) {
        if (lastByteAt <= 0L || stallMs <= 0L) {
            return false;
        }
        long idle = now - lastByteAt;
        return idle >= stallMs;
    }

    /** 进度百分比（0..100），总长未知时返回 0。 */
    public static int percent(long bytes, long total) {
        if (total <= 0L) {
            return 0;
        }
        if (bytes <= 0L) {
            return 0;
        }
        if (bytes >= total) {
            return 100;
        }
        return (int) (bytes * 100L / total);
    }
}
