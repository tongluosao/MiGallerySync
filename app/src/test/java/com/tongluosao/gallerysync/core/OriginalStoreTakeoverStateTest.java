package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * “接管”登记用到的两件纯逻辑：新状态 {@code remote_only} 的线名/解析，
 * 以及“非 ORIGINAL 一律拦上传”这条既有规则对新状态的覆盖。
 *
 * <p>不构造 [OriginalStore] 本身：它要 [Config]（背后是 SharedPreferences），
 * 单测 JVM 里没有实现；落盘解析见 {@code OriginalStoreModelTest} 的既有约定。
 */
public final class OriginalStoreTakeoverStateTest {

    @Test
    public void remoteOnlyWireNameIsStable() {
        assertEquals("remote_only", OriginalStore.LocalState.REMOTE_ONLY.wire());
        assertEquals("remote_only", OriginalStore.LocalState.fromWire("remote_only").wire());
        // 大小写/连字符/空白都按既有 normalizeWire 的规则归一
        assertSame(OriginalStore.LocalState.REMOTE_ONLY,
                OriginalStore.LocalState.fromWire(" Remote-Only "));
    }

    @Test
    public void unknownWireStillFallsBackToOriginal() {
        // 旧 JSON 没有这个键、或者读到认不出来的值：按 ORIGINAL，绝不抛
        assertSame(OriginalStore.LocalState.ORIGINAL,
                OriginalStore.LocalState.fromWire(null));
        assertSame(OriginalStore.LocalState.ORIGINAL,
                OriginalStore.LocalState.fromWire(""));
        assertSame(OriginalStore.LocalState.ORIGINAL,
                OriginalStore.LocalState.fromWire("remote-only-typo"));
    }

    @Test
    public void remoteOnlyBlocksUpload() {
        // 只登记了“远端有”、本地没有校验过的字节：按非 ORIGINAL 处理，上传侧保守拦下
        assertTrue(PreviewStateRules.blocksUpload(OriginalStore.LocalState.REMOTE_ONLY, 0L, 4096L));
        assertTrue(PreviewStateRules.blocksUpload(OriginalStore.LocalState.REMOTE_ONLY,
                4096L, 4096L));
        assertFalse(PreviewStateRules.blocksUpload(OriginalStore.LocalState.ORIGINAL,
                4096L, 4096L));
    }
}
