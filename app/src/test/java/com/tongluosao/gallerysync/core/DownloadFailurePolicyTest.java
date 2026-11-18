package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

/**
 * 失败分类与冷却。核心回归：旧实现冷却一过<b>清零失败计数</b>，于是"服务端每次返回坏字节"
 * 会每分钟把整份对象重下一遍（真机同一行 5 分钟重下 4 次）。
 */
public class DownloadFailurePolicyTest {

    @Test
    public void threeKindsAreDistinct() {
        assertEquals(DownloadFailurePolicy.Kind.RETRYABLE,
                DownloadFailurePolicy.Cause.TRANSPORT.kind());
        // 有意变更：INVALID_PAYLOAD 从 RETRYABLE 改成 TERMINAL（见 Cause 上的注释）。
        // 全 0 / 签名对不上是"服务端那份对象本身坏了"，等一分钟再下一遍不会变好；
        // 这一类改由 DamagedRemotes 第一次确认就记账，自动排期直接跳过。
        assertEquals(DownloadFailurePolicy.Kind.TERMINAL,
                DownloadFailurePolicy.Cause.INVALID_PAYLOAD.kind());
        assertEquals(DownloadFailurePolicy.Kind.TERMINAL,
                DownloadFailurePolicy.Cause.CONTENT_MISMATCH.kind());
        assertEquals(DownloadFailurePolicy.Kind.TERMINAL,
                DownloadFailurePolicy.Cause.DESTINATION_CONFLICT.kind());
        assertEquals(DownloadFailurePolicy.Kind.ABSENT,
                DownloadFailurePolicy.Cause.NOT_FOUND.kind());
        assertEquals(DownloadFailurePolicy.Kind.ABSENT,
                DownloadFailurePolicy.Cause.PROBE_MISSING.kind());
    }

    @Test
    public void contentDamageIsExactlyTheTwoByteLevelCauses() {
        // 记账判据只认"字节本身坏了"：全 0/签名不符 + 摘要对不上。
        assertTrue(DownloadFailurePolicy.isContentDamage(
                DownloadFailurePolicy.Cause.INVALID_PAYLOAD));
        assertTrue(DownloadFailurePolicy.isContentDamage(
                DownloadFailurePolicy.Cause.CONTENT_MISMATCH));
        // 会自愈的一律不算：传输、停滞、5xx、403、长度不符（可能是这次截断）……
        assertFalse(DownloadFailurePolicy.isContentDamage(
                DownloadFailurePolicy.Cause.TRANSPORT));
        assertFalse(DownloadFailurePolicy.isContentDamage(
                DownloadFailurePolicy.Cause.STALLED));
        assertFalse(DownloadFailurePolicy.isContentDamage(
                DownloadFailurePolicy.Cause.LENGTH_MISMATCH));
        assertFalse(DownloadFailurePolicy.isContentDamage(
                DownloadFailurePolicy.Cause.EMPTY_PAYLOAD));
        assertFalse(DownloadFailurePolicy.isContentDamage(
                DownloadFailurePolicy.Cause.AUTH));
        assertFalse(DownloadFailurePolicy.isContentDamage(null));
    }

    @Test
    public void nullCauseIsOptimisticallyRetryable() {
        assertEquals(DownloadFailurePolicy.Kind.RETRYABLE, DownloadFailurePolicy.kindOf(null));
    }

    @Test
    public void statusCodesMapToCauses() {
        assertEquals(DownloadFailurePolicy.Cause.NOT_FOUND,
                DownloadFailurePolicy.causeOfStatus(404));
        assertEquals(DownloadFailurePolicy.Cause.NOT_FOUND,
                DownloadFailurePolicy.causeOfStatus(410));
        assertEquals(DownloadFailurePolicy.Cause.AUTH, DownloadFailurePolicy.causeOfStatus(401));
        assertEquals(DownloadFailurePolicy.Cause.AUTH, DownloadFailurePolicy.causeOfStatus(403));
        assertEquals(DownloadFailurePolicy.Cause.SERVER, DownloadFailurePolicy.causeOfStatus(408));
        assertEquals(DownloadFailurePolicy.Cause.SERVER, DownloadFailurePolicy.causeOfStatus(429));
        assertEquals(DownloadFailurePolicy.Cause.SERVER, DownloadFailurePolicy.causeOfStatus(500));
        assertEquals(DownloadFailurePolicy.Cause.SERVER, DownloadFailurePolicy.causeOfStatus(599));
        assertEquals(DownloadFailurePolicy.Cause.BAD_REQUEST,
                DownloadFailurePolicy.causeOfStatus(400));
        assertEquals(DownloadFailurePolicy.Cause.BAD_REQUEST,
                DownloadFailurePolicy.causeOfStatus(416));
        assertEquals(DownloadFailurePolicy.Cause.UNKNOWN, DownloadFailurePolicy.causeOfStatus(0));
        assertEquals(DownloadFailurePolicy.Cause.UNKNOWN, DownloadFailurePolicy.causeOfStatus(200));
    }

    @Test
    public void messagesMapToCauses() {
        assertEquals(DownloadFailurePolicy.Cause.DESTINATION_CONFLICT,
                DownloadFailurePolicy.causeOfMessage(
                        "download destination appeared before publish: /x/y.jpg"));
        assertEquals(DownloadFailurePolicy.Cause.CONTENT_MISMATCH,
                DownloadFailurePolicy.causeOfMessage(
                        "downloaded bytes do not match the registered digest expected=…"));
        assertEquals(DownloadFailurePolicy.Cause.INVALID_PAYLOAD,
                DownloadFailurePolicy.causeOfMessage("downloaded payload rejected reason=all_zero"));
        assertEquals(DownloadFailurePolicy.Cause.INVALID_PAYLOAD,
                DownloadFailurePolicy.causeOfMessage("cannot register invalid payload"));
        assertEquals(DownloadFailurePolicy.Cause.LENGTH_MISMATCH,
                DownloadFailurePolicy.causeOfMessage("download length mismatch: 100 vs 200"));
        assertEquals(DownloadFailurePolicy.Cause.PROBE_MISSING,
                DownloadFailurePolicy.causeOfMessage("remote object is absent"));
        assertEquals(DownloadFailurePolicy.Cause.LOW_SPACE,
                DownloadFailurePolicy.causeOfMessage("no space left on device"));
        assertEquals(DownloadFailurePolicy.Cause.STALLED,
                DownloadFailurePolicy.causeOfMessage("download stalled with no bytes"));
        assertEquals(DownloadFailurePolicy.Cause.UNKNOWN,
                DownloadFailurePolicy.causeOfMessage(""));
        assertEquals(DownloadFailurePolicy.Cause.UNKNOWN,
                DownloadFailurePolicy.causeOfMessage(null));
    }

    @Test
    public void absentIsNeverBlocked() {
        assertFalse(DownloadFailurePolicy.blocked(DownloadFailurePolicy.Kind.ABSENT, 99,
                1_000L, 1_001L, 3));
        assertEquals(0L, DownloadFailurePolicy.cooldownMs(DownloadFailurePolicy.Kind.ABSENT,
                DownloadFailurePolicy.DEFAULT_MAX_ATTEMPTS,
                DownloadFailurePolicy.DEFAULT_MAX_ATTEMPTS));
    }

    @Test
    public void retriesBelowLimitAreNotBlocked() {
        // 短暂掉一次网不该被冷却一分钟
        assertFalse(DownloadFailurePolicy.blocked(DownloadFailurePolicy.Kind.RETRYABLE, 1,
                1_000L, 1_001L, 3));
        assertFalse(DownloadFailurePolicy.blocked(DownloadFailurePolicy.Kind.RETRYABLE, 2,
                1_000L, 1_001L, 3));
        assertFalse(DownloadFailurePolicy.blocked(DownloadFailurePolicy.Kind.RETRYABLE, 0,
                1_000L, 1_001L, 3));
        // 时刻未知：不挡
        assertFalse(DownloadFailurePolicy.blocked(DownloadFailurePolicy.Kind.RETRYABLE, 5,
                0L, 1_001L, 3));
    }

    /** 关键回归：达到上限后冷却期一过就<b>放行</b>，但计数不再被清零（由队列负责）。 */
    @Test
    public void retryableCooledButCounterSurvives() {
        long base = DownloadFailurePolicy.RETRY_COOLDOWN_MS;
        int limit = 3;
        assertTrue(DownloadFailurePolicy.blocked(DownloadFailurePolicy.Kind.RETRYABLE,
                limit, 1_000L, 1_000L + base - 1, limit));
        assertFalse(DownloadFailurePolicy.blocked(DownloadFailurePolicy.Kind.RETRYABLE,
                limit, 1_000L, 1_000L + base, limit));
        // 计数继续涨，冷却跟着指数变长：第 4 次失败要挡 2 分钟
        assertEquals(2 * base, DownloadFailurePolicy.cooldownMs(
                DownloadFailurePolicy.Kind.RETRYABLE, limit + 1, limit));
        assertTrue(DownloadFailurePolicy.blocked(DownloadFailurePolicy.Kind.RETRYABLE,
                limit + 1, 1_000L, 1_000L + base, limit));
    }

    @Test
    public void terminalCooldownStartsAtThirtyMinutes() {
        long base = DownloadFailurePolicy.TERMINAL_COOLDOWN_MS;
        assertEquals(30L * 60_000L, base);
        assertTrue(DownloadFailurePolicy.blocked(DownloadFailurePolicy.Kind.TERMINAL, 3,
                1_000L, 1_000L + 60_000L, 3));
        assertFalse(DownloadFailurePolicy.blocked(DownloadFailurePolicy.Kind.TERMINAL, 3,
                1_000L, 1_000L + base, 3));
    }

    @Test
    public void cooldownDoubleSaturatesInsteadOfOverflowing() {
        long capped = DownloadFailurePolicy.MAX_COOLDOWN_MS;
        for (int attempts = 3; attempts < 400; attempts += 7) {
            long value = DownloadFailurePolicy.cooldownMs(DownloadFailurePolicy.Kind.TERMINAL,
                    attempts, 3);
            assertTrue("attempts=" + attempts + " value=" + value,
                    value > 0L && value <= capped);
            long retry = DownloadFailurePolicy.cooldownMs(
                    DownloadFailurePolicy.Kind.RETRYABLE, attempts, 3);
            assertTrue("attempts=" + attempts + " retry=" + retry,
                    retry > 0L && retry <= capped);
            assertTrue(retry <= value || value == capped);
        }
        assertEquals(capped, DownloadFailurePolicy.cooldownMs(
                DownloadFailurePolicy.Kind.TERMINAL, 200, 3));
        // 可重试类翻 6 档就够（60s → 64min），远不到 24h 上限；不该因为次数多就跳到上限
        assertEquals(DownloadFailurePolicy.RETRY_COOLDOWN_MS << 6,
                DownloadFailurePolicy.cooldownMs(
                        DownloadFailurePolicy.Kind.RETRYABLE, 200, 3));
        assertTrue(DownloadFailurePolicy.cooldownMs(DownloadFailurePolicy.Kind.RETRYABLE, 200, 3)
                < capped);
    }

    @Test
    public void clockGoingBackwardsStaysBlocked() {
        // 重启后 elapsedRealtime 归零：宁可少试一次，也不能立刻重下
        assertTrue(DownloadFailurePolicy.blocked(DownloadFailurePolicy.Kind.RETRYABLE, 3,
                50_000L, 1_000L, 3));
    }

    /**
     * 停滞要一次吃掉整份重试额度：真机上"卡到 60s 被看门狗关掉"如果只记 1 次，
     * 后面两次尝试会立刻重连、再各卡 60s，一张 12MB 的图白等 3 分钟。
     */
    @Test
    public void stallCostsTheWholeRetryBudget() {
        assertEquals(1, DownloadFailurePolicy.attemptCost(
                DownloadFailurePolicy.Cause.TRANSPORT, 3));
        assertEquals(1, DownloadFailurePolicy.attemptCost(
                DownloadFailurePolicy.Cause.INVALID_PAYLOAD, 3));
        assertEquals(3, DownloadFailurePolicy.attemptCost(
                DownloadFailurePolicy.Cause.STALLED, 3));
        assertEquals(5, DownloadFailurePolicy.attemptCost(
                DownloadFailurePolicy.Cause.STALLED, 5));
        assertEquals(DownloadFailurePolicy.DEFAULT_MAX_ATTEMPTS,
                DownloadFailurePolicy.attemptCost(DownloadFailurePolicy.Cause.STALLED, 0));
        // 一次停滞之后计数已经到上限：下一次尝试必须进冷却，而不是立刻重连
        int count = DownloadFailurePolicy.attemptCost(DownloadFailurePolicy.Cause.STALLED, 3);
        assertTrue(DownloadFailurePolicy.blocked(DownloadFailurePolicy.Kind.RETRYABLE, count,
                1_000L, 1_000L, 3));
    }

    @Test
    public void payloadExceptionCarriesCauseAndKind() {
        DownloadFailurePolicy.PayloadException content = new DownloadFailurePolicy.PayloadException(
                DownloadFailurePolicy.Cause.CONTENT_MISMATCH, "digest differs");
        assertTrue(content instanceof IOException);
        assertEquals(DownloadFailurePolicy.Cause.CONTENT_MISMATCH, content.cause());
        assertEquals(DownloadFailurePolicy.Kind.TERMINAL, content.kind());

        DownloadFailurePolicy.PayloadException transport =
                new DownloadFailurePolicy.PayloadException(
                        DownloadFailurePolicy.Cause.TRANSPORT, "socket reset",
                        new IllegalStateException("boom"));
        assertEquals(DownloadFailurePolicy.Kind.RETRYABLE, transport.kind());

        // 给 null 也不能变成终态
        DownloadFailurePolicy.PayloadException unknown = new DownloadFailurePolicy.PayloadException(
                null, "?");
        assertEquals(DownloadFailurePolicy.Cause.UNKNOWN, unknown.cause());
        assertEquals(DownloadFailurePolicy.Kind.RETRYABLE, unknown.kind());
    }
}
