package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 收尾流水线的三条判据：池大小、背压落点、超时预算。
 *
 * <p>这些值直接决定"车道线程会不会又被收尾拖住"，所以边界必须钉死：
 * 0/负数配置不能开出 0 个线程（等于退回同步收尾），16 路以上也不能无脑放大
 * （provider 是串行锁），队列满时必须回落成同步而不是丢任务。
 */
public class FinalizePipelineTest {

    @Test
    public void workerCountHalvesLanesWithinOneToFour() {
        // 1 → 1：不能给 0，否则收尾永远留在车道上
        assertEquals(1, FinalizePipeline.workerCount(1));
        // 2 → 1：单车道 + 1，小配置下两路收尾没有意义
        assertEquals(1, FinalizePipeline.workerCount(2));
        // 6 → 3：(6+1)/2 向上取整
        assertEquals(3, FinalizePipeline.workerCount(6));
        // 8 → 4：默认配置正好吃满上限
        assertEquals(4, FinalizePipeline.workerCount(8));
        // 16 → 4：上限夹住，provider 串行锁不会因为线程多而变快
        assertEquals(4, FinalizePipeline.workerCount(16));
    }

    @Test
    public void workerCountNeverDropsBelowOneOrExceedsCeiling() {
        assertEquals(1, FinalizePipeline.workerCount(0));
        assertEquals(1, FinalizePipeline.workerCount(-7));
        assertEquals(1, FinalizePipeline.workerCount(Integer.MIN_VALUE));
        assertEquals(FinalizePipeline.MAX_WORKERS, FinalizePipeline.workerCount(64));
        assertEquals(FinalizePipeline.MAX_WORKERS, FinalizePipeline.workerCount(Integer.MAX_VALUE));
    }

    @Test
    public void workerCountIsMonotonicNonDecreasing() {
        int previous = FinalizePipeline.workerCount(1);
        for (int concurrency = 2; concurrency <= 64; concurrency++) {
            int current = FinalizePipeline.workerCount(concurrency);
            assertTrue("workerCount must not shrink at concurrency=" + concurrency,
                    current >= previous);
            previous = current;
        }
    }

    @Test
    public void queueNotYetFullKeepsTheLaneFree() {
        assertFalse(FinalizePipeline.shouldRunInline(0, 64));
        assertFalse(FinalizePipeline.shouldRunInline(1, 64));
        assertFalse(FinalizePipeline.shouldRunInline(63, 64));
    }

    @Test
    public void fullQueueRunsInlineSoNothingIsDropped() {
        assertTrue(FinalizePipeline.shouldRunInline(64, 64));
        assertTrue(FinalizePipeline.shouldRunInline(65, 64));
        assertTrue(FinalizePipeline.shouldRunInline(Integer.MAX_VALUE, 64));
    }

    @Test
    public void unusablePoolFallsBackToInline() {
        // capacity<=0：池没配队列（或已关闭）—— 同步做，绝不丢
        assertTrue(FinalizePipeline.shouldRunInline(0, 0));
        assertTrue(FinalizePipeline.shouldRunInline(0, -1));
        // queued<0：池已 shutdown，读不到长度
        assertTrue(FinalizePipeline.shouldRunInline(-1, 64));
    }

    @Test
    public void timeoutIsMonotonicInBytes() {
        long previous = FinalizePipeline.timeoutMs(0L);
        assertTrue("0 字节也要有底", previous > 0L);
        long[] sizes = new long[] {
                0L, 1L, 1024L, 512L * 1024L, 1024L * 1024L - 1L, 1024L * 1024L,
                4L * 1024L * 1024L, 17L * 1024L * 1024L, 200L * 1024L * 1024L,
                2L * 1024L * 1024L * 1024L, Long.MAX_VALUE,
        };
        for (long size : sizes) {
            long current = FinalizePipeline.timeoutMs(size);
            assertTrue("timeout must not shrink at bytes=" + size, current >= previous);
            assertTrue("timeout must respect the ceiling at bytes=" + size,
                    current <= FinalizePipeline.MAX_TIMEOUT_MS);
            previous = current;
        }
    }

    @Test
    public void timeoutStartsAtBaseAndSaturatesAtCeiling() {
        assertEquals(FinalizePipeline.BASE_TIMEOUT_MS, FinalizePipeline.timeoutMs(0L));
        assertEquals(FinalizePipeline.BASE_TIMEOUT_MS, FinalizePipeline.timeoutMs(-1L));
        // 每 MiB 加 250 ms，且向上取整：1 字节也已经占了一个 MiB 的预算
        assertEquals(FinalizePipeline.BASE_TIMEOUT_MS + FinalizePipeline.TIMEOUT_PER_MIB_MS,
                FinalizePipeline.timeoutMs(1L));
        assertEquals(FinalizePipeline.BASE_TIMEOUT_MS + FinalizePipeline.TIMEOUT_PER_MIB_MS,
                FinalizePipeline.timeoutMs(1024L * 1024L));
        assertEquals(FinalizePipeline.MAX_TIMEOUT_MS, FinalizePipeline.timeoutMs(Long.MAX_VALUE));
    }
}
