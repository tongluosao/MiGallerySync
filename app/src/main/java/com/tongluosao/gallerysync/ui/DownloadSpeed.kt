package com.tongluosao.gallerysync.ui

import java.util.Locale

/**
 * 一个"总字节数"采样点：某一时刻某个方向上所有**进行中**项的已传字节之和。
 *
 * <p>上传、下载各喂各的窗口（见 [ProgressModel.updateSpeedSamples]）：两个方向抢同一条带宽，
 * 混在一起算出来的数字既不是上传速度也不是下载速度。文件名带 Download 是历史原因 ——
 * 下载是这条路第一个使用者，后来上传也用了同一套窗口算法。
 */
data class SpeedSample(val atMs: Long, val bytes: Long)

/**
 * 整体速度（某个方向）：滑动窗口内的字节差 ÷ 时间差。
 *
 * <p>为什么不用相邻两帧的差值：帧是 2Hz，单帧差值只是一瞬间的抖动（64KB 一块的回调
 * 落点也不均匀）；用最近 3–5 秒的窗口既能把抖动摊平，又不会慢到看不出"下载停了"。
 *
 * <p>纯函数，不碰 Android、不读时钟 —— 测试直接给采样列表与 `now` 即可。
 */
object DownloadSpeed {

    /** 窗口长度：4 秒。2Hz 的帧在窗口里约 8 个采样，足够平滑 */
    const val WINDOW_MS = 4_000L

    /**
     * 窗口两端至少要隔这么久才算速度。
     *
     * <p>两帧挨得太近（例如页面刚打开、只有两帧）时，字节差除以一个毫秒级的分母会
     * 得到荒唐的峰值；宁可先显示 0，也不要在界面上闪一个 800 MB/s。
     */
    const val MIN_SPAN_MS = 600L

    /** 最多保留的采样条数：2Hz × 5s = 10，多留几条给帧抖动 */
    const val MAX_SAMPLES = 16

    /**
     * 聚合速度（字节/秒）。
     *
     * <p>采样是"所有进行中下载项的已下载字节之和"，所以多文件并行会自然叠加成总速度。
     * 窗口内总量回退时返回 0：某项下载完成被移出活动集合、队列重置、服务器要求重传，
     * 都会让总和变小 —— 负速度没有意义，不要把回退当成"倒着下载"。
     *
     * @param samples 按时间升序的采样（[append] 会保证顺序；这里再排一次以容错）
     * @param nowMs 当前时刻，与采样同一个时钟
     */
    fun bytesPerSecond(
        samples: List<SpeedSample>,
        nowMs: Long,
        windowMs: Long = WINDOW_MS
    ): Long {
        if (samples.size < 2 || windowMs <= 0L) {
            return 0L
        }
        val cutoff = nowMs - windowMs
        val window = samples.filter { it.atMs in cutoff..nowMs }.sortedBy { it.atMs }
        if (window.size < 2) {
            return 0L
        }
        val first = window.first()
        val last = window.last()
        val span = last.atMs - first.atMs
        if (span < MIN_SPAN_MS) {
            return 0L
        }
        val delta = last.bytes - first.bytes
        if (delta <= 0L) {
            return 0L
        }
        return delta * 1000L / span
    }

    /**
     * 把新采样追加进历史，返回裁剪后的列表（不修改入参，便于单测与 Compose 快照对比）。
     *
     * <p>两种情况直接丢掉旧窗口、从这一帧重新开始：
     * <ul>
     *   <li><b>回退</b>：总字节比上一采样还少（某项完成被移出、队列重置、重传），
     *       旧字节数已经不能和新字节数相减；</li>
     *   <li><b>时间倒退</b>：设备改过时钟，旧采样全在窗口外。</li>
     * </ul>
     */
    fun append(samples: List<SpeedSample>, sample: SpeedSample): List<SpeedSample> {
        val last = samples.lastOrNull()
        if (last != null && (sample.bytes < last.bytes || sample.atMs < last.atMs)) {
            return listOf(sample)
        }
        val out = ArrayList<SpeedSample>(samples.size + 1)
        for (s in samples) {
            out.add(s)
        }
        out.add(sample)
        val from = (out.size - MAX_SAMPLES).coerceAtLeast(0)
        return ArrayList(out.subList(from, out.size))
    }

    /** 人类可读速度：1024 进制，保留一位小数；小于 1KB/s 时给整数，避免 "0.0 KB/s" */
    fun format(bytesPerSecond: Long): String {
        val rate = bytesPerSecond.coerceAtLeast(0L)
        if (rate < 1024L) {
            return "$rate B/s"
        }
        val units = arrayOf("KB/s", "MB/s", "GB/s")
        var value = rate.toDouble() / 1024.0
        var unit = 0
        while (value >= 1024.0 && unit < units.size - 1) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
