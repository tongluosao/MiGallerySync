package com.tongluosao.gallerysync.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 整体下载速度的单测。
 *
 * <p>速度是"最近 4 秒的总字节差 ÷ 时间差"，全是纯数据运算（不碰 Android、不读时钟），
 * 所以这里直接喂采样列表和 now。重点盯四类容易翻车的地方：空/单点、单调增长、
 * 多文件并行的叠加、以及回退与窗口滑动 —— 最后两类算错会让界面显示一个负速度
 * 或者一直挂着一个早已过时的峰值。
 */
class DownloadSpeedTest {

    @Test
    fun emptyAndSingleSampleGiveZero() {
        assertEquals(0L, DownloadSpeed.bytesPerSecond(emptyList(), nowMs = 1_000L))
        assertEquals(
            0L,
            DownloadSpeed.bytesPerSecond(listOf(SpeedSample(0L, 0L)), nowMs = 1_000L)
        )
        // 窗口长度为 0 时不做除法
        assertEquals(
            0L,
            DownloadSpeed.bytesPerSecond(
                listOf(SpeedSample(0L, 0L), SpeedSample(1_000L, 4_000_000L)),
                nowMs = 1_000L,
                windowMs = 0L
            )
        )
    }

    @Test
    fun monotonicallyGrowingSingleFile() {
        // 2 秒里涨了 2 MB → 1 MB/s
        val samples = listOf(
            SpeedSample(0L, 0L),
            SpeedSample(1_000L, 1_048_576L),
            SpeedSample(2_000L, 2_097_152L)
        )
        assertEquals(1_048_576L, DownloadSpeed.bytesPerSecond(samples, nowMs = 2_000L))
    }

    @Test
    fun parallelFilesSumIntoAggregateRate() {
        // 两个文件各 512 KB/s，采样是"所有在传项之和"，聚合就该是 1 MB/s
        val samples = listOf(
            SpeedSample(0L, 0L),
            SpeedSample(1_000L, 1_048_576L),
            SpeedSample(2_000L, 2_097_152L)
        )
        assertEquals(1_048_576L, DownloadSpeed.bytesPerSecond(samples, nowMs = 2_100L))
    }

    @Test
    fun rollbackRestartsWindowAndNeverReportsNegative() {
        // 队列重置/某项被移出活动集合：总字节变小
        val afterRollback = DownloadSpeed.append(
            listOf(SpeedSample(0L, 0L), SpeedSample(1_000L, 5_000_000L)),
            SpeedSample(2_000L, 3_000_000L)
        )
        assertEquals(listOf(SpeedSample(2_000L, 3_000_000L)), afterRollback)
        assertEquals(0L, DownloadSpeed.bytesPerSecond(afterRollback, nowMs = 2_000L))

        // 历史里本来就带一个回退点（例如外部直接构造），净增量为负时也是 0
        val withDrop = listOf(
            SpeedSample(0L, 5_000_000L),
            SpeedSample(1_000L, 3_000_000L)
        )
        assertEquals(0L, DownloadSpeed.bytesPerSecond(withDrop, nowMs = 1_000L))
    }

    @Test
    fun timeGoingBackwardsRestartsWindow() {
        val out = DownloadSpeed.append(
            listOf(SpeedSample(5_000L, 1_000L)),
            SpeedSample(1_000L, 2_000L)
        )
        assertEquals(listOf(SpeedSample(1_000L, 2_000L)), out)
    }

    @Test
    fun samplesOutsideWindowAreIgnored() {
        // t=0 那个大跳变早于窗口（现在 2s、窗口 1s），只能看 1s→2s 这一段：1s 涨 1MB
        val samples = listOf(
            SpeedSample(0L, 0L),
            SpeedSample(1_000L, 4_000_000L),
            SpeedSample(2_000L, 5_000_000L)
        )
        assertEquals(
            1_000_000L,
            DownloadSpeed.bytesPerSecond(samples, nowMs = 2_000L, windowMs = 1_000L)
        )
    }

    @Test
    fun tooShortSpanGivesZeroInsteadOfSpike() {
        // 两帧只差 100ms：宁可显示 0，也不要闪一个 10 MB/s 的假峰值
        val samples = listOf(SpeedSample(0L, 0L), SpeedSample(100L, 1_000_000L))
        assertEquals(0L, DownloadSpeed.bytesPerSecond(samples, nowMs = 100L))
    }

    @Test
    fun appendKeepsOrderAndTrimsToMax() {
        var samples = emptyList<SpeedSample>()
        repeat(DownloadSpeed.MAX_SAMPLES + 5) { i ->
            samples = DownloadSpeed.append(samples, SpeedSample(i * 500L, i * 1_000L))
        }
        assertEquals(DownloadSpeed.MAX_SAMPLES, samples.size)
        assertEquals(SpeedSample((DownloadSpeed.MAX_SAMPLES + 4) * 500L, (DownloadSpeed.MAX_SAMPLES + 4) * 1_000L), samples.last())
        // 保留的是最近的一段，时间仍然升序
        assertEquals(samples.sortedBy { it.atMs }, samples)
    }

    @Test
    fun formatUsesBinaryUnits() {
        assertEquals("0 B/s", DownloadSpeed.format(0L))
        assertEquals("1023 B/s", DownloadSpeed.format(1023L))
        assertEquals("1.0 KB/s", DownloadSpeed.format(1024L))
        assertEquals("1.2 MB/s", DownloadSpeed.format(1_258_291L))
        assertEquals("2.0 GB/s", DownloadSpeed.format(2L * 1024 * 1024 * 1024))
    }
}
