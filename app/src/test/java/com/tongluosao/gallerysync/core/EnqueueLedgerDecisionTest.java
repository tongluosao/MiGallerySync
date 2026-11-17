package com.tongluosao.gallerysync.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * "done 账本命中之后该 skip 还是 requeue"这条判定的单测。
 *
 * <p>覆盖的三条硬要求：远端存在 → skip；远端不存在 → requeue；探测失败/未知 → requeue
 * （宁重传不丢）。另外钉住两个容易写错的方向：远端大小不一致要 requeue，
 * "存在但大小未知"（0）与"问不出来"（-2）都不能当成"有"。
 */
public class EnqueueLedgerDecisionTest {

    private static final long SIZE = 30630L;

    // ------------------------------------------------------------ 远端存在

    /** 远端同名同大小：确认过才 skip（这是唯一允许跳过的情形）。 */
    @Test
    public void remotePresentWithSameSizeIsSkipped() {
        EnqueueLedgerDecision.RemoteState state =
                EnqueueLedgerDecision.fromPropfind(SIZE, SIZE);
        assertEquals(EnqueueLedgerDecision.RemoteState.PRESENT_SAME_SIZE, state);
        assertEquals(EnqueueLedgerDecision.Action.SKIP, EnqueueLedgerDecision.decide(state));
    }

    // ------------------------------------------------------------ 远端不存在

    /** 远端明确没有（-1）：账本脏了，必须重新入队，否则这一项永远不再重传。 */
    @Test
    public void remoteMissingIsRequeued() {
        EnqueueLedgerDecision.RemoteState state =
                EnqueueLedgerDecision.fromPropfind(-1L, SIZE);
        assertEquals(EnqueueLedgerDecision.RemoteState.MISSING, state);
        assertEquals(EnqueueLedgerDecision.Action.REQUEUE, EnqueueLedgerDecision.decide(state));
    }

    // ------------------------------------------------------------ 探测失败 / 未知

    /** 问不出来（-2，例如不支持 PROPFIND 或超时）：按"宁重传不丢"重新入队。 */
    @Test
    public void remoteProbeUnknownIsRequeued() {
        EnqueueLedgerDecision.RemoteState state =
                EnqueueLedgerDecision.fromPropfind(-2L, SIZE);
        assertEquals(EnqueueLedgerDecision.RemoteState.UNKNOWN, state);
        assertEquals(EnqueueLedgerDecision.Action.REQUEUE, EnqueueLedgerDecision.decide(state));
    }

    /** 服务器说"有"但没给长度（0）：同样不能当成确认，重新入队。 */
    @Test
    public void remoteSizeZeroIsTreatedAsUnknown() {
        assertEquals(EnqueueLedgerDecision.RemoteState.UNKNOWN,
                EnqueueLedgerDecision.fromPropfind(0L, SIZE));
    }

    /** 探测直接抛异常时调用方映射成 UNKNOWN：绝不能 skip。 */
    @Test
    public void probeFailureIsRequeued() {
        assertEquals(EnqueueLedgerDecision.Action.REQUEUE,
                EnqueueLedgerDecision.decide(EnqueueLedgerDecision.RemoteState.UNKNOWN));
    }

    /** 本地大小为 0 的项无论如何都不许判 skip。 */
    @Test
    public void zeroLocalSizeIsNeverSkipped() {
        assertEquals(EnqueueLedgerDecision.RemoteState.UNKNOWN,
                EnqueueLedgerDecision.fromPropfind(0L, 0L));
        assertEquals(EnqueueLedgerDecision.Action.REQUEUE,
                EnqueueLedgerDecision.decide(
                        EnqueueLedgerDecision.fromPropfind(0L, 0L)));
    }

    // ------------------------------------------------------------ 同名不同大小

    /** 远端同名但大小不同：远端那份不是这一份，覆盖写一遍。 */
    @Test
    public void remoteSizeMismatchIsRequeued() {
        EnqueueLedgerDecision.RemoteState state =
                EnqueueLedgerDecision.fromPropfind(SIZE + 1L, SIZE);
        assertEquals(EnqueueLedgerDecision.RemoteState.PRESENT_DIFFERENT_SIZE, state);
        assertEquals(EnqueueLedgerDecision.Action.REQUEUE, EnqueueLedgerDecision.decide(state));
    }

    // ------------------------------------------------------------ 日志字段

    /** 状态名必须是可直接拼进结构化日志的小写下划线，不含空格与等号。 */
    @Test
    public void describeIsLogFriendly() {
        assertEquals("remote_present", EnqueueLedgerDecision.describe(
                EnqueueLedgerDecision.RemoteState.PRESENT_SAME_SIZE));
        assertEquals("remote_size_mismatch", EnqueueLedgerDecision.describe(
                EnqueueLedgerDecision.RemoteState.PRESENT_DIFFERENT_SIZE));
        assertEquals("remote_missing", EnqueueLedgerDecision.describe(
                EnqueueLedgerDecision.RemoteState.MISSING));
        assertEquals("remote_unknown", EnqueueLedgerDecision.describe(
                EnqueueLedgerDecision.RemoteState.UNKNOWN));
        assertEquals("unknown", EnqueueLedgerDecision.describe(null));
    }
}
