package com.tongluosao.gallerysync.ui

import com.tongluosao.gallerysync.core.ProgressProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载会话计数的单测。
 *
 * <p>现场的病是"总任务一直变小（266 → 240 → 180）、完成数停在 12"：总量取的是"这一轮计划里
 * 还没做完的数量"，完成数取的是当前批次内的完成数。这里的规则是：
 * 总量 = 本次会话计划累计的**最大值**，完成数 = min(总量, 已完成累计)，两者只增不减；
 * 只有明确的"没有待下项了"信号才允许重置，仅仅"这一批跑完了"不行。
 */
class DownloadSessionCounterTest {

    private fun downTaskCompleted(key: String, size: Long = 100L): ProgressTask = ProgressTask(
        key = key,
        name = key,
        path = "/sdcard/DCIM/$key.jpg",
        size = size,
        video = false,
        sent = size,
        dir = ProgressProtocol.DIR_DOWN,
        completed = true
    )

    // ---- 总量只增不减 ----

    @Test
    fun totalNeverShrinksAcrossBatches() {
        val counter = DownloadSessionCounter()
        var state = counter.advance(
            DownloadSessionFrame(plannedItems = 266, plannedBytes = 26_600L)
        )
        assertEquals(266, state.totalItems)
        assertEquals(26_600L, state.totalBytes)

        // 第二批帧里只报"这一轮还剩 240"：旧实现拿它当总量，于是 12/266 → 12/240
        state = counter.advance(
            DownloadSessionFrame(
                plannedItems = 240,
                plannedBytes = 24_000L,
                doneItems = 12,
                doneBytes = 1_200L
            )
        )
        assertEquals(266, state.totalItems)
        assertEquals(26_600L, state.totalBytes)

        // 批次之间的空档（计划量 0）：总量还是不动
        state = counter.advance(
            DownloadSessionFrame(doneItems = 40, doneBytes = 4_000L)
        )
        assertEquals(266, state.totalItems)
        assertEquals(40, state.doneItems)

        // 下一批又计划了 140 项（发布端报的累计量 406）：总量涨上去，完成数不倒退
        state = counter.advance(
            DownloadSessionFrame(
                plannedItems = 406,
                plannedBytes = 40_600L,
                doneItems = 40,
                doneBytes = 4_000L
            )
        )
        assertEquals(406, state.totalItems)
        assertEquals(40, state.doneItems)
        assertEquals(366, state.totalItems - state.doneItems)
    }

    // ---- 完成数单调递增、不超过总量 ----

    @Test
    fun doneIsMonotonicAndCappedByTotal() {
        val counter = DownloadSessionCounter()
        counter.advance(DownloadSessionFrame(plannedItems = 10, plannedBytes = 1_000L))

        var state = counter.advance(
            DownloadSessionFrame(plannedItems = 10, plannedBytes = 1_000L, doneItems = 3, doneBytes = 300L)
        )
        assertEquals(3, state.doneItems)
        state = counter.advance(
            DownloadSessionFrame(plannedItems = 10, plannedBytes = 1_000L, doneItems = 7, doneBytes = 700L)
        )
        assertEquals(7, state.doneItems)

        // 重试/批次切换会让"本批次完成数"回退：会话累计不能跟着退
        state = counter.advance(
            DownloadSessionFrame(plannedItems = 10, plannedBytes = 1_000L, doneItems = 2, doneBytes = 200L)
        )
        assertEquals(7, state.doneItems)
        assertEquals(700L, state.doneBytes)

        // 一个离谱的完成数也不会超过总量
        state = counter.advance(
            DownloadSessionFrame(plannedItems = 10, plannedBytes = 1_000L, doneItems = 99, doneBytes = 99_000L)
        )
        assertEquals(10, state.doneItems)
        assertEquals(1_000L, state.doneBytes)
        assertTrue(state.doneItems <= state.totalItems)
    }

    @Test
    fun inFlightBytesAdvanceButNeverGoBackwards() {
        val counter = DownloadSessionCounter()
        counter.advance(DownloadSessionFrame(plannedItems = 3, plannedBytes = 300L))
        var state = counter.advance(
            DownloadSessionFrame(plannedItems = 3, plannedBytes = 300L, activeBytes = 120L)
        )
        assertEquals(120L, state.doneBytes)

        // 这一项失败/取消：在传字节掉了，但会话的"已完成字节"不该跟着退
        state = counter.advance(DownloadSessionFrame(plannedItems = 3, plannedBytes = 300L))
        assertEquals(120L, state.doneBytes)

        // 重试后涨回来，也不会超过总量
        state = counter.advance(
            DownloadSessionFrame(
                plannedItems = 3,
                plannedBytes = 300L,
                doneBytes = 100L,
                activeBytes = 250L
            )
        )
        assertEquals(300L, state.doneBytes)
    }

    // ---- 会话边界 ----

    @Test
    fun batchEndDoesNotReset() {
        val counter = DownloadSessionCounter()
        counter.advance(
            DownloadSessionFrame(
                plannedItems = 266,
                plannedBytes = 26_600L,
                doneItems = 30,
                doneBytes = 3_000L
            )
        )
        // 这一批跑完了：在传清零、本轮计划量报 0，但没有"没有待下项"的结束信号
        val state = counter.advance(
            DownloadSessionFrame(
                plannedItems = 0,
                plannedBytes = 0L,
                doneItems = 30,
                doneBytes = 3_000L,
                activeBytes = 0L
            )
        )
        assertEquals(266, state.totalItems)
        assertEquals(30, state.doneItems)
        assertFalse(state.ended)
    }

    @Test
    fun onlyExplicitEndResets() {
        val counter = DownloadSessionCounter()
        counter.advance(
            DownloadSessionFrame(
                plannedItems = 266,
                plannedBytes = 26_600L,
                doneItems = 266,
                doneBytes = 26_600L
            )
        )
        val ended = counter.advance(DownloadSessionFrame(ended = true))
        assertTrue(ended.ended)
        assertEquals(0, ended.totalItems)
        assertEquals(0, ended.doneItems)
        assertEquals(0L, ended.totalBytes)
        assertEquals(0L, ended.doneBytes)

        // 结束之后反复收到的结束帧不会把旧数字复活
        val again = counter.advance(DownloadSessionFrame(ended = true))
        assertEquals(0, again.totalItems)

        // 下一轮下载从 0 开始累计，不带上一轮的 266
        val next = counter.advance(
            DownloadSessionFrame(plannedItems = 8, plannedBytes = 800L)
        )
        assertEquals(8, next.totalItems)
        assertEquals(0, next.doneItems)
        assertFalse(next.ended)
    }

    @Test
    fun emptyFrameKeepsNumbers() {
        val counter = DownloadSessionCounter()
        counter.advance(
            DownloadSessionFrame(
                plannedItems = 50,
                plannedBytes = 5_000L,
                doneItems = 20,
                doneBytes = 2_000L
            )
        )
        val state = counter.advance(DownloadSessionFrame())
        assertEquals(50, state.totalItems)
        assertEquals(20, state.doneItems)
        assertEquals(5_000L, state.totalBytes)
        assertEquals(2_000L, state.doneBytes)
        assertFalse(state.ended)
    }

    // ---- 渲染层直接用会话数字 ----

    @Test
    fun sectionUsesSessionTotals() {
        val session = DownloadSessionState(
            totalItems = 266,
            doneItems = 12,
            totalBytes = 0L,
            doneBytes = 0L
        )
        val section = ProgressSections.download(
            ProgressUiState(downloadSession = session),
            1_000L
        )
        assertEquals(SectionKind.DOWNLOAD, section.kind)
        assertEquals(266, section.totalItems)
        assertEquals(12, section.doneItems)
        assertEquals(254, section.remainingItems)
        // 没有字节总量：退回按条目算，12/266 = 4.5%
        assertEquals(0.045f, section.fraction, 0.001f)
        assertEquals(4, section.percent)
        assertTrue(section.busy)
    }

    @Test
    fun sectionPercentPrefersBytes() {
        val session = DownloadSessionState(
            totalItems = 10,
            doneItems = 9,
            totalBytes = 1_000L,
            doneBytes = 500L
        )
        val section = ProgressSections.download(ProgressUiState(downloadSession = session), 0L)
        // 按字节 50%，不是按条目 90%：优先级必须保持"字节优先"
        assertEquals(0.5f, section.fraction, 0.0001f)
        assertEquals(50, section.percent)
        assertEquals(500L, section.remainingBytes)

        // 字节走完就是"全部完成"，块里不再报速度
        val finished = ProgressSections.download(
            ProgressUiState(downloadSession = session.copy(doneItems = 10, doneBytes = 1_000L)),
            0L
        )
        assertEquals(100, finished.percent)
        assertFalse(finished.busy)
    }

    @Test
    fun endedSectionDoesNotFallBackToRows() {
        // 会话已经结束后，不能又拿"这一轮列出的行"顶上（否则归零后立刻变回 12/N）
        val rows = (0 until 3).map { downTaskCompleted("done$it") }
        val section = ProgressSections.download(
            ProgressUiState(
                completed = rows,
                downloadSession = DownloadSessionState(ended = true)
            ),
            0L
        )
        assertEquals(0, section.totalItems)
        assertEquals(0, section.doneItems)
        assertEquals(0, section.percent)
    }

    @Test
    fun zeroTotalHasNoDivisionByZero() {
        val empty = ProgressSections.download(ProgressUiState(), 0L)
        assertEquals(0, empty.totalItems)
        assertEquals(0f, empty.fraction, 0.0001f)
        assertEquals(0, empty.percent)
        assertFalse(empty.busy)

        // 总量 0 但完成数被写过（防御性）：仍按 0 处理，不能除出 NaN
        val zeroTotal = ProgressSections.download(
            ProgressUiState(
                downloadSession = DownloadSessionState(
                    totalItems = 0,
                    doneItems = 5,
                    totalBytes = 0L,
                    doneBytes = 0L
                )
            ),
            0L
        )
        assertEquals(0, zeroTotal.doneItems)
        assertEquals(0f, zeroTotal.fraction, 0.0001f)
    }
}
