package com.tongluosao.gallerysync.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link SlimWorker} 里那点纯逻辑的单测：去重、limit、停止后不再领任务、计数与 saved 累加。
 *
 * <p>刻意只碰 {@link SlimWorker#plan}、{@link SlimWorker#clampConcurrency}、
 * {@link SlimWorker.Cursor}、{@link SlimWorker.Tally}——不起线程、不碰 Android 类，
 * 所以能在普通 JVM 上跑。真正干活那条路径依赖宿主的 {@code SlimController}，只能设备上验。
 */
public final class SlimWorkerQueueTest {

    private static List<Long> ids(Long... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    // ------------------------------------------------------------ 候选去重

    @Test
    public void planKeepsFirstOccurrenceOrderWhenDeduplicating() {
        List<Long> planned = SlimWorker.plan(ids(7L, 3L, 7L, 9L, 3L), 0);
        assertEquals(Arrays.asList(7L, 3L, 9L), planned);
    }

    @Test
    public void planDropsNullAndNonPositiveIds() {
        List<Long> planned = SlimWorker.plan(ids(null, 0L, -4L, 11L, null, 2L), 0);
        assertEquals(Arrays.asList(11L, 2L), planned);
    }

    @Test
    public void planOnNullOrEmptyCandidatesIsEmpty() {
        assertTrue(SlimWorker.plan(null, 3).isEmpty());
        assertTrue(SlimWorker.plan(Collections.emptyList(), 3).isEmpty());
        // 全是无效 id 也一样：不能因为"表非空"就起一轮空跑
        assertTrue(SlimWorker.plan(ids(null, 0L, -1L), 3).isEmpty());
    }

    // ------------------------------------------------------------ limit 生效

    @Test
    public void planTruncatesToLimitAfterDeduplicating() {
        // 先去重再截断：3 和 5 重复掉之后只剩 4 张，limit=3 取前三
        List<Long> planned = SlimWorker.plan(ids(3L, 3L, 5L, 8L, 5L, 13L, 21L), 3);
        assertEquals(Arrays.asList(3L, 5L, 8L), planned);
        assertEquals(3, planned.size());
    }

    @Test
    public void planLeavesShortListIntactWhenLimitExceedsSize() {
        assertEquals(Arrays.asList(1L, 2L), SlimWorker.plan(ids(1L, 2L), 9));
    }

    @Test
    public void planTreatsNonPositiveLimitAsUnlimited() {
        List<Long> all = ids(1L, 2L, 3L, 4L);
        assertEquals(all, SlimWorker.plan(all, 0));
        assertEquals(all, SlimWorker.plan(all, -1));
    }

    @Test
    public void planDoesNotMutateCallerList() {
        List<Long> input = ids(4L, 4L, 6L);
        SlimWorker.plan(input, 1);
        assertEquals(Arrays.asList(4L, 4L, 6L), input);
    }

    // ------------------------------------------------------------ 并发数

    @Test
    public void clampConcurrencyStaysWithinOneToEight() {
        assertEquals(1, SlimWorker.clampConcurrency(1));
        assertEquals(2, SlimWorker.clampConcurrency(2));
        assertEquals(4, SlimWorker.clampConcurrency(4));
        assertEquals(8, SlimWorker.clampConcurrency(8));
        assertEquals(8, SlimWorker.clampConcurrency(17));
        // 调用方没指定（<=0）就走默认 6，而不是被夹成 1
        assertEquals(6, SlimWorker.clampConcurrency(-3));
        assertEquals(6, SlimWorker.clampConcurrency(0));
    }

    // ------------------------------------------------------------ 停止后不再取任务

    @Test
    public void cursorStopsHandingOutTasksOnceStopRequested() {
        SlimWorker.Cursor cursor = new SlimWorker.Cursor(ids(31L, 32L, 33L));
        assertEquals(Long.valueOf(31L), cursor.next(false));
        // 停止之后即使还有剩，也不再发新的；已经拿到的 31 由工作线程跑完
        assertNull(cursor.next(true));
        assertNull(cursor.next(true));
        assertEquals(1, cursor.index());
    }

    @Test
    public void cursorReturnsNullWhenExhausted() {
        SlimWorker.Cursor cursor = new SlimWorker.Cursor(ids(41L));
        assertEquals(Long.valueOf(41L), cursor.next(false));
        assertNull(cursor.next(false));
        assertNull(cursor.next(false));
        assertEquals(1, cursor.index());
    }

    // ------------------------------------------------------------ 计数与 saved 累加

    @Test
    public void tallyAccumulatesDoneAndSaved() {
        SlimWorker.Tally tally = new SlimWorker.Tally();
        tally.recordSaved(1200L);
        tally.recordSaved(300L);
        assertEquals(2, tally.done());
        assertEquals(1500L, tally.saved());
        assertEquals(0, tally.failed());
        assertEquals(2, tally.processed());
    }

    @Test
    public void tallyCountsFailedSeparatelyAndSumProcessed() {
        SlimWorker.Tally tally = new SlimWorker.Tally();
        tally.recordSaved(800L);
        tally.recordFailed();
        tally.recordFailed();
        tally.recordSaved(200L);
        assertEquals(2, tally.done());
        assertEquals(2, tally.failed());
        assertEquals(1000L, tally.saved());
        assertEquals(4, tally.processed());
    }

    @Test
    public void tallyNeverSubtractsNegativeSaved() {
        SlimWorker.Tally tally = new SlimWorker.Tally();
        tally.recordSaved(-50L);
        tally.recordSaved(400L);
        assertEquals(2, tally.done());
        assertEquals(400L, tally.saved());
    }

    @Test
    public void tallyResetClearsEverything() {
        SlimWorker.Tally tally = new SlimWorker.Tally();
        tally.recordSaved(900L);
        tally.recordFailed();
        tally.reset();
        assertEquals(0, tally.done());
        assertEquals(0, tally.failed());
        assertEquals(0L, tally.saved());
        assertEquals(0, tally.processed());
    }
}
