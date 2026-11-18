package com.tongluosao.gallerysync.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * “下载原图”点击判定：只有远端确认存在且大小与登记一致才接管，其余一律回退小米云。
 *
 * <p>回归场景：登记过的行在被点的时候远端已经被删/改名/覆盖成另一个大小。旧实现不做任何探测就
 * 接管还原，用户只看到“还原失败”，而小米云上明明有那份原图。判定纯函数在
 * {@link OriginalClickProbe#classify}，网络那半（有界探测）在 OriginalRestoreHook 里。
 */
public class OriginalClickProbeTest {

    private static final long ORIGINAL_LENGTH = 4_194_304L;

    /** 远端大小与登记一致 → 接管 WebDAV 还原。 */
    @Test public void matchingSizeTakesOver() {
        assertEquals(OriginalClickProbe.Outcome.PRESENT,
                OriginalClickProbe.classify(ORIGINAL_LENGTH, ORIGINAL_LENGTH));
        assertTrue(OriginalClickProbe.usesWebDav(OriginalClickProbe.Outcome.PRESENT));
        assertEquals(OriginalClickProbe.Source.WEBDAV,
                OriginalClickProbe.source(OriginalClickProbe.Outcome.PRESENT));
    }

    /** 远端还在但大小变了（被别的设备覆盖、只补了一半）→ 不能拿它覆盖本地，回退小米云。 */
    @Test public void sizeMismatchFallsBack() {
        assertEquals(OriginalClickProbe.Outcome.SIZE_MISMATCH,
                OriginalClickProbe.classify(ORIGINAL_LENGTH + 1L, ORIGINAL_LENGTH));
        assertEquals(OriginalClickProbe.Outcome.SIZE_MISMATCH,
                OriginalClickProbe.classify(ORIGINAL_LENGTH - 1L, ORIGINAL_LENGTH));
        assertEquals(OriginalClickProbe.Outcome.SIZE_MISMATCH,
                OriginalClickProbe.classify(1L, ORIGINAL_LENGTH));
        assertFalse(OriginalClickProbe.usesWebDav(OriginalClickProbe.Outcome.SIZE_MISMATCH));
        assertEquals(OriginalClickProbe.Source.XIAOMI,
                OriginalClickProbe.source(OriginalClickProbe.Outcome.SIZE_MISMATCH));
    }

    /** 登记里没有原图长度（老记录/脏记录）：远端有大小就按“是这一份”接管。 */
    @Test public void unknownRegisteredLengthAcceptsAnyPositiveSize() {
        assertEquals(OriginalClickProbe.Outcome.PRESENT, OriginalClickProbe.classify(1L, 0L));
        assertEquals(OriginalClickProbe.Outcome.PRESENT,
                OriginalClickProbe.classify(ORIGINAL_LENGTH, 0L));
        assertEquals(OriginalClickProbe.Outcome.PRESENT,
                OriginalClickProbe.classify(ORIGINAL_LENGTH, -1L));
    }

    /** 远端明确不存在（被删/改名/目录没了）→ 回退。 */
    @Test public void absentRemoteFallsBack() {
        assertEquals(OriginalClickProbe.Outcome.MISSING,
                OriginalClickProbe.classify(-1L, ORIGINAL_LENGTH));
        assertFalse(OriginalClickProbe.usesWebDav(OriginalClickProbe.Outcome.MISSING));
        assertEquals(OriginalClickProbe.Source.XIAOMI,
                OriginalClickProbe.source(OriginalClickProbe.Outcome.MISSING));
    }

    /** 存在但服务器没给大小（0）：判不了，按问不出来回退。 */
    @Test public void existingWithoutSizeFallsBack() {
        assertEquals(OriginalClickProbe.Outcome.UNKNOWN,
                OriginalClickProbe.classify(0L, ORIGINAL_LENGTH));
        assertEquals(OriginalClickProbe.Outcome.UNKNOWN, OriginalClickProbe.classify(0L, 0L));
    }

    /** -2（不支持 PROPFIND / 网络失败）以及其它负数：问不出来 → 回退。 */
    @Test public void unanswerableProbeFallsBack() {
        assertEquals(OriginalClickProbe.Outcome.UNKNOWN,
                OriginalClickProbe.classify(-2L, ORIGINAL_LENGTH));
        assertEquals(OriginalClickProbe.Outcome.UNKNOWN,
                OriginalClickProbe.classify(-7L, ORIGINAL_LENGTH));
        assertEquals(OriginalClickProbe.Outcome.UNKNOWN,
                OriginalClickProbe.classify(Long.MIN_VALUE, ORIGINAL_LENGTH));
        assertEquals(OriginalClickProbe.Source.XIAOMI,
                OriginalClickProbe.source(OriginalClickProbe.Outcome.UNKNOWN));
    }

    /** 超时/异常也走回退，且日志标记与 missing/size_mismatch 区分得开。 */
    @Test public void timeoutAndErrorFallBack() {
        assertEquals(OriginalClickProbe.Source.XIAOMI,
                OriginalClickProbe.source(OriginalClickProbe.Outcome.TIMEOUT));
        assertEquals(OriginalClickProbe.Source.XIAOMI,
                OriginalClickProbe.source(OriginalClickProbe.Outcome.ERROR));
        assertEquals(OriginalClickProbe.Source.XIAOMI,
                OriginalClickProbe.source(null));
        assertEquals("timeout", OriginalClickProbe.logToken(OriginalClickProbe.Outcome.TIMEOUT));
        assertEquals("error", OriginalClickProbe.logToken(OriginalClickProbe.Outcome.ERROR));
        assertEquals("error", OriginalClickProbe.logToken(null));
    }

    /** 日志键值：{@code probe=missing|unknown|size_mismatch|present}，可直接 grep。 */
    @Test public void logTokensAreStable() {
        assertEquals("present", OriginalClickProbe.logToken(OriginalClickProbe.Outcome.PRESENT));
        assertEquals("missing", OriginalClickProbe.logToken(OriginalClickProbe.Outcome.MISSING));
        assertEquals("unknown", OriginalClickProbe.logToken(OriginalClickProbe.Outcome.UNKNOWN));
        assertEquals("size_mismatch",
                OriginalClickProbe.logToken(OriginalClickProbe.Outcome.SIZE_MISMATCH));
    }

    /** 只有 PRESENT 接管；其余枚举全部回退 —— 防止以后新增分支忘了加回退。 */
    @Test public void everyOtherOutcomeFallsBack() {
        for (OriginalClickProbe.Outcome outcome : OriginalClickProbe.Outcome.values()) {
            boolean takeOver = OriginalClickProbe.usesWebDav(outcome);
            assertEquals(outcome == OriginalClickProbe.Outcome.PRESENT, takeOver);
            assertEquals(takeOver ? OriginalClickProbe.Source.WEBDAV
                    : OriginalClickProbe.Source.XIAOMI, OriginalClickProbe.source(outcome));
        }
    }
}
