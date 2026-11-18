package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link LedgerCheckPolicy} 的判定方向单测。
 *
 * <p>这组断言钉住的是"宁可多问一次远端，也不要在没证据时跳过"：
 * 只有四种明确的"这一轮问不了"才跳过，且跳过时账本一行不动。
 */
public class LedgerCheckPolicyTest {

    @Test
    public void verifyWhenFreshAndReadyAndBudgetLeft() {
        assertEquals(LedgerCheckPolicy.Action.VERIFY,
                LedgerCheckPolicy.decide(false, 256, true, true));
    }

    @Test
    public void trackedKeyIsNeverQueuedTwice() {
        // 已确认/正在确认的 key 不再问：这是"省额度"的核心分支
        assertEquals(LedgerCheckPolicy.Action.SKIP_TRACKED,
                LedgerCheckPolicy.decide(true, 256, true, true));
        // 即使额度为 0，已跟踪也仍然是 SKIP_TRACKED（原因不同，行为一致）
        assertEquals(LedgerCheckPolicy.Action.SKIP_TRACKED,
                LedgerCheckPolicy.decide(true, 0, true, true));
    }

    @Test
    public void notReadyWinsOverBudget() {
        assertEquals(LedgerCheckPolicy.Action.SKIP_NOT_READY,
                LedgerCheckPolicy.decide(false, 0, false, false));
        assertEquals(LedgerCheckPolicy.Action.SKIP_NOT_READY,
                LedgerCheckPolicy.decide(false, 256, false, true));
    }

    @Test
    public void budgetExhaustedSkipsButKeepsLedger() {
        assertEquals(LedgerCheckPolicy.Action.SKIP_NO_BUDGET,
                LedgerCheckPolicy.decide(false, 0, true, true));
        assertEquals(LedgerCheckPolicy.Action.SKIP_NO_BUDGET,
                LedgerCheckPolicy.decide(false, -1, true, true));
    }

    @Test
    public void pathUnknownSkips() {
        assertEquals(LedgerCheckPolicy.Action.SKIP_NO_PATH,
                LedgerCheckPolicy.decide(false, 256, true, false));
    }

    @Test
    public void reasonsAreDistinctAndNonEmpty() {
        String[] all = new String[LedgerCheckPolicy.Action.values().length];
        int i = 0;
        for (LedgerCheckPolicy.Action action : LedgerCheckPolicy.Action.values()) {
            String reason = LedgerCheckPolicy.reason(action);
            assertTrue(reason != null && !reason.isEmpty());
            for (int j = 0; j < i; j++) {
                assertFalse("reason 必须能区分分支: " + reason, reason.equals(all[j]));
            }
            all[i++] = reason;
        }
    }

    @Test
    public void nullActionIsSafe() {
        assertEquals("unknown", LedgerCheckPolicy.reason((LedgerCheckPolicy.Action) null));
    }

    /** 账本自检结论 → 日志 reason：四种状态必须区分得开，未知值不崩。 */
    @Test
    public void remoteStateReasonsAreDistinct() {
        assertEquals("remote_missing", LedgerCheckPolicy.reason(
                EnqueueLedgerDecision.RemoteState.MISSING));
        assertEquals("size_mismatch", LedgerCheckPolicy.reason(
                EnqueueLedgerDecision.RemoteState.PRESENT_DIFFERENT_SIZE));
        assertEquals("present_same_size", LedgerCheckPolicy.reason(
                EnqueueLedgerDecision.RemoteState.PRESENT_SAME_SIZE));
        assertEquals("unverifiable", LedgerCheckPolicy.reason(
                EnqueueLedgerDecision.RemoteState.UNKNOWN));
        assertEquals("unknown_state", LedgerCheckPolicy.reason(
                (EnqueueLedgerDecision.RemoteState) null));
    }
}
