package com.tongluosao.gallerysync.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 上传前探测的收场判定：什么时候能跳过，什么时候必须照传。
 *
 * <p>真机事故（2026-11-17，平板 + OpenList/S3）：探测卡在 {@code probe_start} 90 秒没下文，
 * PUT 一个字节都没发。所以这里把"超时 → 照传"钉成断言 —— 探测只是优化，
 * 它答不上来时唯一正确的动作就是继续上传（同路径覆盖写、幂等）。
 */
public class ProbeDecisionTest {

    /** 正常返回"远端有"，且回读比过字节：唯一可以跳过的一支 */
    @Test
    public void presentAndSameBytesSkips() {
        assertEquals(ProbeDecision.Action.SKIP,
                ProbeDecision.decide(ProbeDecision.Outcome.PRESENT, true, 120L));
    }

    /** 同名但内容不同：远端那份不是这一份，必须覆盖写 */
    @Test
    public void presentWithDifferentBytesProceeds() {
        assertEquals(ProbeDecision.Action.PROCEED,
                ProbeDecision.decide(ProbeDecision.Outcome.PRESENT, false, 120L));
    }

    /** 远端没有：正常上传 */
    @Test
    public void absentProceeds() {
        assertEquals(ProbeDecision.Action.PROCEED,
                ProbeDecision.decide(ProbeDecision.Outcome.ABSENT, false, 200L));
    }

    /** 超过硬截止：不知道有没有，照传（事故那一支） */
    @Test
    public void timeoutProceeds() {
        assertEquals(ProbeDecision.Action.PROCEED,
                ProbeDecision.decide(ProbeDecision.Outcome.TIMEOUT, false, 8_001L));
        // 就算调用方手里攥着一个"字节一致"的旧结论，超时这一支也不许跳过：
        // 那说明探测根本没答上来，结论无从谈起
        assertEquals(ProbeDecision.Action.PROCEED,
                ProbeDecision.decide(ProbeDecision.Outcome.TIMEOUT, true, 8_001L));
    }

    /** 探测抛异常 / 提交就失败：同上，照传 */
    @Test
    public void errorProceeds() {
        assertEquals(ProbeDecision.Action.PROCEED,
                ProbeDecision.decide(ProbeDecision.Outcome.ERROR, false, 30L));
        assertEquals(ProbeDecision.Action.PROCEED,
                ProbeDecision.decide(null, false, 30L));
    }

    /** 负的等待时长说明连时长都没量出来（提交被拒之类），按问不出来处理 */
    @Test
    public void negativeWaitedProceeds() {
        assertEquals(ProbeDecision.Action.PROCEED,
                ProbeDecision.decide(ProbeDecision.Outcome.PRESENT, true, -1L));
    }

    /** 等得久不影响跳过：跳过与否是字节层面的事实，与等了多久无关 */
    @Test
    public void slowButVerifiedPresentStillSkips() {
        assertEquals(ProbeDecision.Action.SKIP,
                ProbeDecision.decide(ProbeDecision.Outcome.PRESENT, true, 7_999L));
    }

    /** "没答上来"只包括超时/异常/坏输入，正常的在/不在都不算 */
    @Test
    public void unknownOnlyCoversDeadlineAndErrors() {
        assertTrue(ProbeDecision.isUnknown(ProbeDecision.Outcome.TIMEOUT, 8_000L));
        assertTrue(ProbeDecision.isUnknown(ProbeDecision.Outcome.ERROR, 1L));
        assertTrue(ProbeDecision.isUnknown(null, 0L));
        assertTrue(ProbeDecision.isUnknown(ProbeDecision.Outcome.PRESENT, -5L));
        assertFalse(ProbeDecision.isUnknown(ProbeDecision.Outcome.PRESENT, 100L));
        assertFalse(ProbeDecision.isUnknown(ProbeDecision.Outcome.ABSENT, 100L));
    }

    /** 日志值是小写下划线，可以直接当键值对写出去 */
    @Test
    public void describeIsSnakeCase() {
        assertEquals("present", ProbeDecision.describe(ProbeDecision.Outcome.PRESENT));
        assertEquals("absent", ProbeDecision.describe(ProbeDecision.Outcome.ABSENT));
        assertEquals("timeout", ProbeDecision.describe(ProbeDecision.Outcome.TIMEOUT));
        assertEquals("error", ProbeDecision.describe(ProbeDecision.Outcome.ERROR));
        assertEquals("error", ProbeDecision.describe(null));
    }
}
