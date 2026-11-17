package com.tongluosao.gallerysync.core;

/**
 * 视频流式播放的判定与公式。纯 Java、不依赖 Android 类，方便在 JVM 单测里覆盖：
 * 领先窗口、尾部索引覆盖、本地文件可读性、以及 {@code .part} 续写还是重建的判定。
 */
public final class VideoStreamPolicy {
    /** {@code .part} 还在，直接从当前长度续写。 */
    public static final int RESUME = 0;
    /** {@code .part} 丢了或变短，需要重建目录后从头再来。 */
    public static final int LOST = 1;
    /** 领先窗口的时长基准：120 秒。 */
    public static final long WINDOW_SECONDS = 120L;

    private VideoStreamPolicy() { }

    public static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(value, max));
    }

    /**
     * 下载领先播放位置的字节数：{@code clamp(bps*120, min, max)}；播放器还没发过请求
     * （{@code playhead<=0}）时放宽到至少 {@code startWindow}，避免首块之后立刻卡住。
     */
    public static long windowBytes(long bytesPerSecond, long playhead, long min, long max,
            long startWindow) {
        long window = clamp(bytesPerSecond * WINDOW_SECONDS, min, max);
        return playhead <= 0L ? Math.max(window, startWindow) : window;
    }

    /** 尾部预取区的起点；长度未知返回 0，{@code tailBytes<=0} 表示没有尾部区。 */
    public static long tailStart(long length, int tailBytes) {
        if (length <= 0L) return 0L;
        if (tailBytes <= 0) return length;
        return Math.max(0L, length - tailBytes);
    }

    /** 请求起点是否落在尾部索引区（能直接用内存里的尾部缓存服务）。 */
    public static boolean coveredByTail(long rangeStart, long length, int tailBytes) {
        return rangeStart >= tailStart(length, tailBytes);
    }

    /** 本地文件是否已经覆盖请求起点：严格大于，长度相等时起点之外没有字节。 */
    public static boolean canServeFromLocal(long fileLength, long rangeStart) {
        return fileLength > rangeStart;
    }

    /** {@code .part} 续写判定：文件不存在（-1）或比 resumeFrom 短才算真的丢了。 */
    public static int resumeDecision(long fileLength, long resumeFrom) {
        if (fileLength < 0L || fileLength < resumeFrom) return LOST;
        return RESUME;
    }
}
