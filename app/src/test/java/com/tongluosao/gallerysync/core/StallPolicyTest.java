package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 停滞阈值与处置的边界。
 *
 * <p>真机背景：4.5 分钟窗口内 22 条 {@code stalled}，旧的"60 秒无字节"单一阈值把
 * "服务端一个字节都不吐"和"大文件慢速但活着"混在一起判死。这里钉住三条边界：
 * <ul>
 *   <li>小对象（≤1MB）与中对象（=10MB）用基础阈值 20s / 90s；</li>
 *   <li>大对象（≥500MB）放宽到上限 45s / 150s；</li>
 *   <li>已有字节 = 空闲阶段，零字节 = 首字节阶段 —— 阶段不同，截止时间与处置都不同。</li>
 * </ul>
 */
public class StallPolicyTest {

    private static final long MB = 1024L * 1024L;

    @Test
    public void smallObjectsUseBaseTimeouts() {
        assertEquals(StallPolicy.FIRST_BYTE_TIMEOUT_MS, StallPolicy.firstByteTimeoutMs(0L));
        assertEquals(20_000L, StallPolicy.firstByteTimeoutMs(1L));
        assertEquals(20_000L, StallPolicy.firstByteTimeoutMs(900_000L));
        assertEquals(20_000L, StallPolicy.firstByteTimeoutMs(1L * MB));
        assertEquals(StallPolicy.IDLE_TIMEOUT_MS, StallPolicy.idleTimeoutMs(1L * MB));
        assertEquals(90_000L, StallPolicy.idleTimeoutMs(1L * MB));
    }

    @Test
    public void tenMegabytesIsStillTheBase() {
        // 边界：=10MB 属于"基础阈值"那一侧，不是过渡区
        assertEquals(10L * MB, StallPolicy.SMALL_BYTES);
        assertEquals(20_000L, StallPolicy.firstByteTimeoutMs(10L * MB));
        assertEquals(90_000L, StallPolicy.idleTimeoutMs(10L * MB));
        // 刚过边界就开始放宽，但绝不会低于基础值
        assertTrue(StallPolicy.idleTimeoutMs(10L * MB + 1L) >= 90_000L);
        assertTrue(StallPolicy.firstByteTimeoutMs(10L * MB + 1L) >= 20_000L);
    }

    @Test
    public void hugeObjectsUseTheCeiling() {
        assertEquals(500L * MB, StallPolicy.LARGE_BYTES);
        assertEquals(StallPolicy.FIRST_BYTE_TIMEOUT_MAX_MS, StallPolicy.firstByteTimeoutMs(500L * MB));
        assertEquals(StallPolicy.IDLE_TIMEOUT_MAX_MS, StallPolicy.idleTimeoutMs(500L * MB));
        // 1GB 视频（真机就有）：仍是上限，不会无限放大
        assertEquals(150_000L, StallPolicy.idleTimeoutMs(1024L * MB));
        assertEquals(45_000L, StallPolicy.firstByteTimeoutMs(1024L * MB));
        // 中间过渡区必须单调、且夹在两端之间
        long previous = 0L;
        for (long total = 10L * MB; total <= 500L * MB; total += 25L * MB) {
            long idle = StallPolicy.idleTimeoutMs(total);
            assertTrue("total=" + total + " idle=" + idle,
                    idle >= 90_000L && idle <= 150_000L);
            assertTrue("total=" + total + " idle=" + idle + " previous=" + previous,
                    idle >= previous);
            previous = idle;
        }
    }

    @Test
    public void unknownLengthIsTreatedAsSmall() {
        // 负数/0 表示"远端没给长度"：不能成为"多等"的理由
        assertEquals(20_000L, StallPolicy.firstByteTimeoutMs(-1L));
        assertEquals(90_000L, StallPolicy.idleTimeoutMs(-1L));
        assertEquals(StallPolicy.Phase.FIRST_BYTE,
                StallPolicy.phaseOf(false));
        assertEquals(StallPolicy.Phase.IDLE,
                StallPolicy.phaseOf(true));
    }

    @Test
    public void zeroBytesWaitsUntilFirstByteDeadline() {
        long total = 1L * MB;
        // 首字节阶段：19.9s 还等，20s 到点
        assertEquals(StallPolicy.Action.WAIT, StallPolicy.stallAction(false, 19_999L, total, false));
        assertEquals(StallPolicy.Action.ABORT, StallPolicy.stallAction(false, 20_000L, total, false));
        // 走直链：先退直链而不是直接中止
        assertEquals(StallPolicy.Action.RETRY_WITHOUT_DIRECT,
                StallPolicy.stallAction(false, 20_000L, total, true));
    }

    @Test
    public void bytesAlreadyArrivedUseIdleDeadline() {
        long total = 1L * MB;
        // 已经收到字节：20 秒（首字节阈值）不该中止，90 秒才到点
        assertEquals(StallPolicy.Action.WAIT, StallPolicy.stallAction(true, 20_000L, total, false));
        assertEquals(StallPolicy.Action.WAIT, StallPolicy.stallAction(true, 89_999L, total, false));
        assertEquals(StallPolicy.Action.ABORT, StallPolicy.stallAction(true, 90_000L, total, false));
        assertEquals(StallPolicy.Action.RETRY_WITHOUT_DIRECT,
                StallPolicy.stallAction(true, 90_000L, total, true));
    }

    @Test
    public void largeObjectSurvivesSlowButAliveTransfers() {
        // 600MB 视频：90 秒的旧阈值会误杀，现在有 150 秒
        long total = 600L * MB;
        assertEquals(StallPolicy.Action.WAIT,
                StallPolicy.stallAction(true, 120_000L, total, false));
        assertEquals(StallPolicy.Action.ABORT,
                StallPolicy.stallAction(true, 150_000L, total, false));
        // 首字节阶段同样放宽，但 45 秒一到就处置
        assertEquals(StallPolicy.Action.WAIT,
                StallPolicy.stallAction(false, 44_999L, total, false));
        assertEquals(StallPolicy.Action.ABORT,
                StallPolicy.stallAction(false, 45_000L, total, false));
    }

    @Test
    public void clockGoingBackwardsNeverAborts() {
        // 重启后 elapsedRealtime 归零：当作刚开始，绝不因为负的 elapsed 误杀
        assertFalse(StallPolicy.timedOut(false, -5_000L, 0L));
        assertFalse(StallPolicy.timedOut(true, -1L, 0L));
        assertEquals(StallPolicy.Action.WAIT, StallPolicy.stallAction(false, -1L, 0L, true));
        assertEquals(StallPolicy.Action.WAIT, StallPolicy.stallAction(true, -1L, 0L, false));
    }

    @Test
    public void phaseWireNamesAreStableForLogs() {
        // 日志靠 phase=first_byte|idle 分类统计，名字必须稳定
        assertEquals("first_byte", StallPolicy.Phase.FIRST_BYTE.wire());
        assertEquals("idle", StallPolicy.Phase.IDLE.wire());
    }
}
