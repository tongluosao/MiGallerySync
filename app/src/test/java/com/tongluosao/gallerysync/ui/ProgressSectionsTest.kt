package com.tongluosao.gallerysync.ui

import com.tongluosao.gallerysync.core.ProgressProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度页纯逻辑的单测：总进度、剩余时间、折叠条数、速度聚合。
 *
 * <p>这些规则算错了，界面上表现成"进度条倒退""剩余 0 秒卡半小时""展开一次按钮就没了"
 * 这类很难复现的怪状，所以这里全部按数据喂进去验，不碰 Compose、不碰时钟。
 */
class ProgressSectionsTest {

    private fun upTask(
        key: String,
        size: Long = 100L,
        sent: Long? = null,
        completed: Boolean = false
    ): ProgressTask = ProgressTask(
        key = key,
        name = key,
        path = "/sdcard/DCIM/$key.jpg",
        size = size,
        video = false,
        sent = sent,
        dir = ProgressProtocol.DIR_UP,
        completed = completed
    )

    private fun downTask(
        key: String,
        size: Long = 100L,
        sent: Long? = null,
        completed: Boolean = false
    ): ProgressTask = ProgressTask(
        key = key,
        name = key,
        path = "/sdcard/DCIM/$key.jpg",
        size = size,
        video = false,
        sent = sent,
        dir = ProgressProtocol.DIR_DOWN,
        completed = completed
    )

    // ---- 总进度 ----

    @Test
    fun uploadSummaryUsesFrameTotals() {
        val state = ProgressUiState(
            doneItems = 3,
            totalItems = 10,
            doneBytes = 300L,
            totalBytes = 1_000L
        )
        val section = ProgressSections.upload(state, 100L)
        assertEquals(SectionKind.UPLOAD, section.kind)
        assertEquals(3, section.doneItems)
        assertEquals(10, section.totalItems)
        // 有字节总量就按字节算，不按条数
        assertEquals(0.3f, section.fraction, 0.0001f)
        assertEquals(30, section.percent)
        assertEquals(7, section.remainingItems)
        assertEquals(700L, section.remainingBytes)
        // 700 字节 ÷ 100 B/s = 7 秒
        assertEquals(7L, section.remainingSeconds)
        assertTrue(section.busy)
    }

    @Test
    fun uploadSummaryFallsBackToItemCountWhenBytesMissing() {
        val state = ProgressUiState(doneItems = 1, totalItems = 4)
        val section = ProgressSections.upload(state, 0L)
        assertEquals(0.25f, section.fraction, 0.0001f)
        assertEquals(25, section.percent)
        assertEquals(3, section.remainingItems)
        // 没有字节总量就算不出剩余时间，界面那时不该写"剩余 0 秒"
        assertNull(section.remainingSeconds)
    }

    @Test
    fun uploadRemainingFallsBackToQueueWhenTotalUnknown() {
        // 老版本帧没有 s* 字段：排队 5 项 + 在传 1 项（上传），还剩 6 项
        val state = ProgressUiState(
            active = listOf(
                ActiveUpload(
                    key = "a",
                    name = "a",
                    path = "/a.jpg",
                    size = 100L,
                    sent = 40L,
                    video = false
                )
            ),
            activeDownloads = 0,
            pending = 5
        )
        val section = ProgressSections.upload(state, 0L)
        assertEquals(0, section.totalItems)
        assertEquals(6, section.remainingItems)
    }

    @Test
    fun uploadClampsInconsistentTotals() {
        // 重传会让 done 超过 total：夹住，别让进度条跑出 100% 之外的数
        val state = ProgressUiState(
            doneItems = 5,
            totalItems = 2,
            doneBytes = 2_000L,
            totalBytes = 1_000L
        )
        val section = ProgressSections.upload(state, 0L)
        assertEquals(2, section.doneItems)
        assertEquals(1_000L, section.doneBytes)
        assertEquals(1f, section.fraction, 0.0001f)
        assertEquals(100, section.percent)
        assertEquals(0, section.remainingItems)
        assertFalse(section.busy)
    }

    @Test
    fun downloadSummaryFromListedRows() {
        // 在传 50/100、刚完成 100/100、排队 0/100
        val state = ProgressUiState(
            tasks = listOf(
                downTask("run", size = 100L, sent = 50L),
                downTask("queue", size = 100L, sent = null)
            ),
            completed = listOf(downTask("done", size = 100L, sent = 100L, completed = true))
        )
        val section = ProgressSections.download(state, 100L)
        assertEquals(SectionKind.DOWNLOAD, section.kind)
        assertEquals(3, section.totalItems)
        assertEquals(1, section.doneItems)
        assertEquals(150L, section.doneBytes)
        assertEquals(300L, section.totalBytes)
        assertEquals(0.5f, section.fraction, 0.0001f)
        assertEquals(50, section.percent)
        assertEquals(2, section.remainingItems)
        assertEquals(150L, section.remainingBytes)
        assertEquals(2L, section.remainingSeconds)
    }

    @Test
    fun downloadSummaryToleratesUnknownSize() {
        // 体积未知（帧里 s=0）：已传字节照记，但总量算不出来 —— 进度退回按条数，不会除出 NaN
        val state = ProgressUiState(
            tasks = listOf(downTask("run", size = 0L, sent = 40L))
        )
        val section = ProgressSections.download(state, 0L)
        assertEquals(0L, section.totalBytes)
        assertEquals(40L, section.doneBytes)
        assertEquals(0f, section.fraction, 0.0001f)
        assertEquals(1, section.remainingItems)
        assertNull(section.remainingSeconds)
    }

    @Test
    fun remainingSecondsRoundsUpAndRejectsUnknownInput() {
        // 1000 字节 ÷ 300 B/s = 3.33s → 4s（向上取整，不显示"3 秒"然后超时）
        assertEquals(4L, ProgressSections.remainingSeconds(1_000L, 300L))
        // 剩 1 字节也至少说 1 秒，不能显示 0
        assertEquals(1L, ProgressSections.remainingSeconds(1L, 1_000_000L))
        assertEquals(60L, ProgressSections.remainingSeconds(60_000L, 1_000L))
        // 干完了 / 速度未知：都不该给出一个数字
        assertNull(ProgressSections.remainingSeconds(0L, 1_000L))
        assertNull(ProgressSections.remainingSeconds(1_000L, 0L))
        assertNull(ProgressSections.remainingSeconds(1_000L, -5L))
    }

    // ---- 折叠 ----

    @Test
    fun foldKeepsAllRowsUnderLimit() {
        val rows = (0 until ProgressSections.COLLAPSE_LIMIT).map { upTask("q$it") }
        val fold = ProgressSections.fold(rows, expanded = false)
        assertEquals(rows, fold.visible)
        assertEquals(0, fold.hidden)
        assertFalse(fold.collapsible)
        assertFalse(fold.canExpand)
        assertFalse(fold.canCollapse)
    }

    @Test
    fun foldShowsRunningThenRecentCompleted() {
        val running = (0 until 3).map { downTask("run$it", size = 100L, sent = 10L) }
        val completed = (0 until 4).map {
            downTask("done$it", size = 100L, sent = 100L, completed = true)
        }
        val queued = (0 until 13).map { downTask("queue$it") }
        val rows = running + completed + queued

        val fold = ProgressSections.fold(rows, expanded = false)
        // 进行中的 3 条 + 最近完成的 3 条，空位用排队项补齐到 8 条
        assertEquals(
            listOf("run0", "run1", "run2", "done0", "done1", "done2", "queue0", "queue1"),
            fold.visible.map { it.key }
        )
        assertEquals(12, fold.hidden)
        assertTrue(fold.canExpand)
        assertFalse(fold.canCollapse)
        // 折叠只该少显示几行，不该让剩下的行换位置
        assertEquals(fold.visible.map { rows.indexOf(it) }.sorted(), fold.visible.map { rows.indexOf(it) })
    }

    @Test
    fun foldHidesQueuedRowsByDefault() {
        val rows = (0 until 12).map { upTask("q$it") }
        val fold = ProgressSections.fold(rows, expanded = false)
        // 一条进行中的都没有时退回"前 N 条"：否则会折成一片空白 + "还剩 12 项"
        assertEquals(8, fold.visible.size)
        assertEquals("q0", fold.visible.first().key)
        assertEquals(4, fold.hidden)
        assertTrue(fold.canExpand)
    }

    @Test
    fun foldExpandedShowsEveryRow() {
        val rows = (0 until 20).map { upTask("q$it") }
        val fold = ProgressSections.fold(rows, expanded = true)
        assertEquals(20, fold.visible.size)
        assertEquals(0, fold.hidden)
        assertTrue(fold.collapsible)
        assertFalse(fold.canExpand)
        // 展开后按钮要变成"收起"，所以 collapsible 必须还留着
        assertTrue(fold.canCollapse)
    }

    // ---- 速度聚合 ----

    @Test
    fun aggregateBytesOnlyCountsOneDirection() {
        val active = listOf(
            ActiveUpload("u1", "u1", "/u1", size = 20L, sent = 10L, video = false),
            // sent > size：重传会让字节短暂超出去，采样要夹住，否则窗口里出现尖峰
            ActiveUpload("u2", "u2", "/u2", size = 20L, sent = 30L, video = false),
            ActiveUpload(
                "d1",
                "d1",
                "/d1",
                size = 100L,
                sent = 50L,
                video = false,
                dir = ProgressProtocol.DIR_DOWN
            )
        )
        assertEquals(30L, ProgressSections.aggregateBytes(active, down = false))
        assertEquals(50L, ProgressSections.aggregateBytes(active, down = true))
    }

    @Test
    fun nextWindowClearsWhenNothingIsActive() {
        val previous = listOf(SpeedSample(0L, 0L), SpeedSample(1_000L, 1_000L))
        // 方向空了就把窗口丢掉，否则下次开传会和几小时前的旧采样相减
        assertEquals(
            emptyList<SpeedSample>(),
            ProgressSections.nextWindow(previous, frameBytes = 0L, activeCount = 0, nowMs = 2_000L)
        )
        val kept = ProgressSections.nextWindow(previous, 2_000L, 1, 2_000L)
        assertEquals(SpeedSample(2_000L, 2_000L), kept.last())
        assertEquals(3, kept.size)
    }

    @Test
    fun trackTaskSpeedsGivesEachRowItsOwnRate() {
        val frame1 = listOf(
            ActiveUpload("a", "a", "/a", size = 10_000_000L, sent = 0L, video = false),
            ActiveUpload("b", "b", "/b", size = 10_000_000L, sent = 0L, video = false)
        )
        val afterFirst = ProgressSections.trackTaskSpeeds(TaskSpeeds.EMPTY, frame1, 0L)
        assertEquals(setOf("a", "b"), afterFirst.windows.keys)
        // 只有一条采样，还差一个时间点，速率是 0
        assertEquals(0L, afterFirst.rate("a", 0L))

        val frame2 = listOf(
            ActiveUpload("a", "a", "/a", size = 10_000_000L, sent = 1_048_576L, video = false),
            ActiveUpload("b", "b", "/b", size = 10_000_000L, sent = 524_288L, video = false)
        )
        val afterSecond = ProgressSections.trackTaskSpeeds(afterFirst, frame2, 2_000L)
        // 2 秒里 a 涨 1 MB、b 涨 0.5 MB → 各 512 KB/s、256 KB/s
        assertEquals(524_288L, afterSecond.rate("a", 2_000L))
        assertEquals(262_144L, afterSecond.rate("b", 2_000L))
        // 速率不存快照：同一份窗口按更晚的时刻算，窗口过期后就是 0，
        // 不会出现「汇总速度已经 0 B/s、行里还挂着 5 MB/s」
        assertEquals(0L, afterSecond.rate("a", 20_000L))
    }

    @Test
    fun trackTaskSpeedsDropsFinishedRowsAndResetsOnRollback() {
        val frame = listOf(
            ActiveUpload("a", "a", "/a", size = 100L, sent = 0L, video = false),
            ActiveUpload("b", "b", "/b", size = 100L, sent = 0L, video = false)
        )
        val first = ProgressSections.trackTaskSpeeds(TaskSpeeds.EMPTY, frame, 0L)
        val second = ProgressSections.trackTaskSpeeds(
            first,
            listOf(
                ActiveUpload("a", "a", "/a", size = 100L, sent = 60L, video = false),
                ActiveUpload("b", "b", "/b", size = 100L, sent = 60L, video = false)
            ),
            1_000L
        )
        assertEquals(setOf("a", "b"), second.windows.keys)

        // b 传完了：只留 a，b 的窗口不能继续挂在内存里
        val third = ProgressSections.trackTaskSpeeds(
            second,
            listOf(ActiveUpload("a", "a", "/a", size = 100L, sent = 90L, video = false)),
            2_000L
        )
        assertEquals(setOf("a"), third.windows.keys)

        // 重传让字节回退：窗口从头开始，速率回到 0 而不是负数
        val afterRollback = ProgressSections.trackTaskSpeeds(
            third,
            listOf(ActiveUpload("a", "a", "/a", size = 100L, sent = 0L, video = false)),
            3_000L
        )
        assertEquals(listOf(SpeedSample(3_000L, 0L)), afterRollback.windows["a"])
        assertEquals(0L, afterRollback.rate("a", 3_000L))
    }

    // ---- 两块的高度份额 ----

    @Test
    fun blockWeightsSplitEvenlyUnlessOneSideIsEmpty() {
        // 两块都有内容：对半分，谁高谁低会让人以为下面是次要的
        assertEquals(1f, ProgressSections.blockWeights(3, 5).first, 0.0001f)
        assertEquals(1f, ProgressSections.blockWeights(3, 5).second, 0.0001f)
        // 空的让出份额，另一块不至于挤在半屏里滚
        assertEquals(1.3f, ProgressSections.blockWeights(3, 0).first, 0.0001f)
        assertEquals(0.7f, ProgressSections.blockWeights(3, 0).second, 0.0001f)
        assertEquals(0.7f, ProgressSections.blockWeights(0, 4).first, 0.0001f)
        // 两边都空：还是对半，不能出现"两个小块挤在顶部"
        assertEquals(1f, ProgressSections.blockWeights(0, 0).first, 0.0001f)
        assertEquals(1f, ProgressSections.blockWeights(0, 0).second, 0.0001f)
    }

    @Test
    fun formatRateIsDirectionNeutral() {
        assertEquals("1.2 MB/s", ProgressSections.formatRate(1_258_291L))
        assertEquals("0 B/s", ProgressSections.formatRate(0L))
    }
}
