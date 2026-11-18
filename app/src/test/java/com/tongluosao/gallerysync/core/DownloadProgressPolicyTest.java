package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 字节进度节流与停滞判据。真机现象：只报过一行 {@code bytes=0/941317}，之后没有增量，
 * 总速度恒 0 B/s —— 这里把"该报/不该报"钉成可测的规则。
 */
public class DownloadProgressPolicyTest {

    @Test
    public void noProgressNeverReports() {
        assertFalse(DownloadProgressPolicy.due(0L, 0L, 0L, 941_317L, 10_000L));
        // 重复回调同一个值：不能当进步
        assertFalse(DownloadProgressPolicy.due(4096L, 0L, 4096L, 941_317L, 10_000L));
        // 字节倒退（重连后从头下）：也不报
        assertFalse(DownloadProgressPolicy.due(8192L, 0L, 1024L, 941_317L, 10_000L));
    }

    @Test
    public void reportsEveryStepBytes() {
        long step = DownloadProgressPolicy.STEP_BYTES;
        assertFalse(DownloadProgressPolicy.due(0L, 0L, step - 1, 10L * step, 1L));
        assertTrue(DownloadProgressPolicy.due(0L, 0L, step, 10L * step, 1L));
        assertTrue(DownloadProgressPolicy.due(step, 1L, 2 * step, 10L * step, 2L));
    }

    @Test
    public void reportsEveryStepMsEvenForSmallDelta() {
        assertFalse(DownloadProgressPolicy.due(0L, 1_000L, 64 * 1024L, 941_317L,
                1_000L + DownloadProgressPolicy.STEP_MS - 1));
        assertTrue(DownloadProgressPolicy.due(0L, 1_000L, 64 * 1024L, 941_317L,
                1_000L + DownloadProgressPolicy.STEP_MS));
    }

    @Test
    public void finalByteAlwaysReported() {
        // 最后一小段既没到 256KB 也没到 500ms：仍然必须报 bytes=N/N
        assertTrue(DownloadProgressPolicy.due(941_000L, 0L, 941_317L, 941_317L, 1L));
        assertTrue(DownloadProgressPolicy.due(0L, 0L, 100L, 100L, 0L));
    }

    @Test
    public void unknownTotalDoesNotForceReport() {
        // total 未知（0）时不能因为 bytes>=total 就误判成"下载完成"
        assertFalse(DownloadProgressPolicy.due(0L, 0L, 1024L, 0L, 1L));
        assertTrue(DownloadProgressPolicy.due(0L, 0L, 1024L, -1L, 1_000L));
    }

    @Test
    public void bytesPerSecondIsSafe() {
        assertEquals(1_048_576L, DownloadProgressPolicy.bytesPerSecond(1_048_576L, 0L, 1_000L));
        assertEquals(0L, DownloadProgressPolicy.bytesPerSecond(1_048_576L, 0L, 0L));
        assertEquals(0L, DownloadProgressPolicy.bytesPerSecond(1_048_576L, 0L, -5L));
        assertEquals(0L, DownloadProgressPolicy.bytesPerSecond(1_000L, 1_000L, 1_000L));
        assertEquals(0L, DownloadProgressPolicy.bytesPerSecond(1_000L, 2_000L, 1_000L));
        // 1MB / 250ms = 4MB/s
        assertEquals(4L * 1_048_576L,
                DownloadProgressPolicy.bytesPerSecond(1_048_576L, 0L, 250L));
    }

    @Test
    public void stallNeedsRealIdleTime() {
        long stall = DownloadProgressPolicy.STALL_MS;
        // 还没收到过任何字节：不算停滞（首包由总超时兜底）
        assertFalse(DownloadProgressPolicy.stalled(0L, 10_000_000L, stall));
        assertFalse(DownloadProgressPolicy.stalled(100_000L, 100_000L + stall - 1, stall));
        assertTrue(DownloadProgressPolicy.stalled(100_000L, 100_000L + stall, stall));
        assertFalse(DownloadProgressPolicy.stalled(100_000L, 10_000L, stall));
        assertFalse(DownloadProgressPolicy.stalled(100_000L, 200_000L, 0L));
    }

    @Test
    public void percentClamps() {
        assertEquals(0, DownloadProgressPolicy.percent(0L, 941_317L));
        assertEquals(0, DownloadProgressPolicy.percent(1_000L, 0L));
        assertEquals(50, DownloadProgressPolicy.percent(500L, 1_000L));
        assertEquals(99, DownloadProgressPolicy.percent(999L, 1_000L));
        assertEquals(100, DownloadProgressPolicy.percent(1_000L, 1_000L));
        assertEquals(100, DownloadProgressPolicy.percent(2_000L, 1_000L));
        assertEquals(0, DownloadProgressPolicy.percent(-1L, 1_000L));
    }

    /** 现场那条 {@code bytes=355235/355235} 必须能算出 100%。 */
    @Test
    public void finalLogLineForRealFailingRowIsHundred() {
        assertEquals(100, DownloadProgressPolicy.percent(355_235L, 355_235L));
    }
}
