package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link TakeoverPlanner} 的纯函数单测：每条规则、每个分支各一条。 */
public final class TakeoverPlannerTest {

    private static final long SIZE = 4096L;

    @Test
    public void noSha1AlwaysSkipsFirstEvenWhenRemoteMissing() {
        assertEquals(TakeoverPlanner.Decision.SKIP_NO_SHA1,
                TakeoverPlanner.decide(false, 0L, SIZE, false, 0L, false));
        assertEquals(TakeoverPlanner.Decision.SKIP_NO_SHA1,
                TakeoverPlanner.decide(true, SIZE, SIZE, true, SIZE, false));
        assertEquals(TakeoverPlanner.Decision.SKIP_NO_SHA1,
                TakeoverPlanner.decide(true, SIZE + 1L, SIZE, true, SIZE + 1L, false));
    }

    @Test
    public void remoteMissingSkipsEvenWhenLocalLooksLikeOriginal() {
        assertEquals(TakeoverPlanner.Decision.SKIP_REMOTE_MISSING,
                TakeoverPlanner.decide(false, 0L, SIZE, true, SIZE, true));
        assertEquals(TakeoverPlanner.Decision.SKIP_REMOTE_MISSING,
                TakeoverPlanner.decide(false, SIZE, SIZE, false, 0L, true));
    }

    @Test
    public void sizeMismatchSkipsRegardlessOfLocal() {
        assertEquals(TakeoverPlanner.Decision.SKIP_SIZE_MISMATCH,
                TakeoverPlanner.decide(true, SIZE + 1L, SIZE, true, SIZE, true));
        assertEquals(TakeoverPlanner.Decision.SKIP_SIZE_MISMATCH,
                TakeoverPlanner.decide(true, SIZE, SIZE + 1L, true, SIZE, true));
        assertEquals(TakeoverPlanner.Decision.SKIP_SIZE_MISMATCH,
                TakeoverPlanner.decide(true, 0L, SIZE, false, 0L, true));
        // 本地字节数与"远端"一致也不行：先过相册行 size 那道闸
        assertEquals(TakeoverPlanner.Decision.SKIP_SIZE_MISMATCH,
                TakeoverPlanner.decide(true, SIZE + 1L, SIZE, true, SIZE + 1L, true));
    }

    @Test
    public void localFileWithSameLengthIsOriginal() {
        assertEquals(TakeoverPlanner.Decision.TAKE_AS_ORIGINAL,
                TakeoverPlanner.decide(true, SIZE, SIZE, true, SIZE, true));
    }

    @Test
    public void localFileWithDifferentLengthIsRemoteOnly() {
        assertEquals(TakeoverPlanner.Decision.TAKE_AS_REMOTE_ONLY,
                TakeoverPlanner.decide(true, SIZE, SIZE, true, SIZE - 1L, true));
        assertEquals(TakeoverPlanner.Decision.TAKE_AS_REMOTE_ONLY,
                TakeoverPlanner.decide(true, SIZE, SIZE, true, SIZE + 1L, true));
        assertEquals(TakeoverPlanner.Decision.TAKE_AS_REMOTE_ONLY,
                TakeoverPlanner.decide(true, SIZE, SIZE, true, 0L, true));
    }

    @Test
    public void localMissingIsRemoteOnly() {
        assertEquals(TakeoverPlanner.Decision.TAKE_AS_REMOTE_ONLY,
                TakeoverPlanner.decide(true, SIZE, SIZE, false, 0L, true));
        // 本地不存在时 localLength 不该被采信
        assertEquals(TakeoverPlanner.Decision.TAKE_AS_REMOTE_ONLY,
                TakeoverPlanner.decide(true, SIZE, SIZE, false, SIZE, true));
    }

    @Test
    public void zeroSizedRowsAreJudgedByTheSameRules() {
        // size>0 的过滤在查询里做；万一漏进来，大小对得上仍按"远端有"处理
        assertEquals(TakeoverPlanner.Decision.TAKE_AS_REMOTE_ONLY,
                TakeoverPlanner.decide(true, 0L, 0L, false, 0L, true));
        assertEquals(TakeoverPlanner.Decision.SKIP_SIZE_MISMATCH,
                TakeoverPlanner.decide(true, 0L, SIZE, false, 0L, true));
    }

    @Test
    public void isTakeOnlyForTakeDecisions() {
        assertTrue(TakeoverPlanner.isTake(TakeoverPlanner.Decision.TAKE_AS_ORIGINAL));
        assertTrue(TakeoverPlanner.isTake(TakeoverPlanner.Decision.TAKE_AS_REMOTE_ONLY));
        assertFalse(TakeoverPlanner.isTake(TakeoverPlanner.Decision.SKIP_NO_SHA1));
        assertFalse(TakeoverPlanner.isTake(TakeoverPlanner.Decision.SKIP_REMOTE_MISSING));
        assertFalse(TakeoverPlanner.isTake(TakeoverPlanner.Decision.SKIP_SIZE_MISMATCH));
        assertFalse(TakeoverPlanner.isTake(TakeoverPlanner.Decision.SKIP_TOO_LARGE_ROW));
        assertFalse(TakeoverPlanner.isTake(null));
    }

    @Test
    public void wireNamesAreStable() {
        assertEquals("take_as_original",
                TakeoverPlanner.Decision.TAKE_AS_ORIGINAL.wire());
        assertEquals("take_as_remote_only",
                TakeoverPlanner.Decision.TAKE_AS_REMOTE_ONLY.wire());
        assertEquals("skip_size_mismatch",
                TakeoverPlanner.Decision.SKIP_SIZE_MISMATCH.wire());
        assertEquals("skip_remote_missing",
                TakeoverPlanner.Decision.SKIP_REMOTE_MISSING.wire());
        assertEquals("skip_no_sha1", TakeoverPlanner.Decision.SKIP_NO_SHA1.wire());
        assertEquals("skip_too_large_row",
                TakeoverPlanner.Decision.SKIP_TOO_LARGE_ROW.wire());
        assertEquals("skip_too_large_row",
                TakeoverPlanner.describe(TakeoverPlanner.Decision.SKIP_TOO_LARGE_ROW));
        assertEquals("unknown", TakeoverPlanner.describe(null));
    }
}
