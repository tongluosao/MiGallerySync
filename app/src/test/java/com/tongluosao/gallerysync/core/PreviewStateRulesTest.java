package com.tongluosao.gallerysync.core;

import com.tongluosao.gallerysync.core.OriginalStore.LocalState;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 读侧判据 {@link PreviewStateRules} 的穷举断言：五个状态 × 长度一致/不一致，外加
 * {@code null} 与长度未知两种兜底。
 *
 * <p>这一层是上传拦截、下载原图菜单、空间优化候选三处共用的唯一真相，所以逐格钉死：
 * 任何一格被改动（例如把 {@code ORIGINAL} 也判成禁止上传）都必须在单测里立刻暴露。
 */
public class PreviewStateRulesTest {

    private static final long ORIGINAL_LENGTH = 4_194_304L;
    private static final long SHORTER = ORIGINAL_LENGTH / 8L;

    /** 除 ORIGINAL 外的四个状态：本地这份文件都不是完整原图。 */
    private static final List<LocalState> PREVIEW_STATES = Arrays.asList(
            LocalState.INPLACE_PREVIEW,
            LocalState.PENDING_INPLACE,
            LocalState.SIDECAR_PREVIEW,
            LocalState.DAMAGED);

    // ---------------------------------------------------------------- blocksUpload

    /** ORIGINAL + 长度一致：本地就是原图，允许上传。 */
    @Test public void originalWithMatchingLengthUploads() {
        assertFalse(PreviewStateRules.blocksUpload(LocalState.ORIGINAL,
                ORIGINAL_LENGTH, ORIGINAL_LENGTH));
    }

    /** ORIGINAL + 长度不一致（变长或变短）：已被改写，禁止上传。 */
    @Test public void originalWithChangedLengthBlocksUpload() {
        assertTrue(PreviewStateRules.blocksUpload(LocalState.ORIGINAL,
                ORIGINAL_LENGTH + 1L, ORIGINAL_LENGTH));
        assertTrue(PreviewStateRules.blocksUpload(LocalState.ORIGINAL,
                SHORTER, ORIGINAL_LENGTH));
    }

    /** 四个预览类状态：无论长度巧合与否，一律禁止上传。 */
    @Test public void previewStatesAlwaysBlockUpload() {
        for (LocalState state : PREVIEW_STATES) {
            assertTrue(state.name(), PreviewStateRules.blocksUpload(state,
                    ORIGINAL_LENGTH, ORIGINAL_LENGTH));
            assertTrue(state.name(), PreviewStateRules.blocksUpload(state,
                    SHORTER, ORIGINAL_LENGTH));
        }
    }

    /** state 为 null：按 ORIGINAL 处理，长度一致放行、不一致拦截。 */
    @Test public void nullStateBehavesLikeOriginal() {
        assertFalse(PreviewStateRules.blocksUpload(null, ORIGINAL_LENGTH, ORIGINAL_LENGTH));
        assertTrue(PreviewStateRules.blocksUpload(null, SHORTER, ORIGINAL_LENGTH));
    }

    /** 长度未知（任一边 <= 0）时不误拦：菜单/上传都退回旧行为，交给摘要判据兜底。 */
    @Test public void unknownLengthNeverBlocksOriginalUpload() {
        assertFalse(PreviewStateRules.blocksUpload(LocalState.ORIGINAL, 0L, ORIGINAL_LENGTH));
        assertFalse(PreviewStateRules.blocksUpload(LocalState.ORIGINAL, ORIGINAL_LENGTH, 0L));
        assertFalse(PreviewStateRules.blocksUpload(LocalState.ORIGINAL, -1L, -1L));
        assertFalse(PreviewStateRules.blocksUpload(null, 0L, 0L));
    }

    // ---------------------------------------------------------------- needsOriginalRestore

    /** ORIGINAL + 长度一致：本地就是原图，不显示“下载原图”。 */
    @Test public void originalWithMatchingLengthNeedsNoRestore() {
        assertFalse(PreviewStateRules.needsOriginalRestore(LocalState.ORIGINAL,
                ORIGINAL_LENGTH, ORIGINAL_LENGTH));
    }

    /** ORIGINAL + 长度不一致：需要恢复。 */
    @Test public void originalWithChangedLengthNeedsRestore() {
        assertTrue(PreviewStateRules.needsOriginalRestore(LocalState.ORIGINAL,
                ORIGINAL_LENGTH + 1L, ORIGINAL_LENGTH));
        assertTrue(PreviewStateRules.needsOriginalRestore(LocalState.ORIGINAL,
                SHORTER, ORIGINAL_LENGTH));
    }

    /** 四个预览类状态：一律需要恢复（其中“原地预览”正是用户要“下载原图”的场景）。 */
    @Test public void previewStatesAlwaysNeedRestore() {
        for (LocalState state : PREVIEW_STATES) {
            assertTrue(state.name(), PreviewStateRules.needsOriginalRestore(state,
                    ORIGINAL_LENGTH, ORIGINAL_LENGTH));
            assertTrue(state.name(), PreviewStateRules.needsOriginalRestore(state,
                    SHORTER, ORIGINAL_LENGTH));
        }
    }

    /** state 为 null 且长度一致：按 ORIGINAL 处理，不显示。 */
    @Test public void nullStateNeedsNoRestoreWhenLengthMatches() {
        assertFalse(PreviewStateRules.needsOriginalRestore(null,
                ORIGINAL_LENGTH, ORIGINAL_LENGTH));
        assertTrue(PreviewStateRules.needsOriginalRestore(null, SHORTER, ORIGINAL_LENGTH));
    }

    // ---------------------------------------------------------------- skipSlimCandidate

    /** 四个预览类状态都跳过；ORIGINAL 与 null 不跳过（普通照片照常进候选）。 */
    @Test public void onlyRegisteredPreviewStatesSkipSlimCandidates() {
        for (LocalState state : PREVIEW_STATES) {
            assertTrue(state.name(), PreviewStateRules.skipSlimCandidate(state));
        }
        assertFalse(PreviewStateRules.skipSlimCandidate(LocalState.ORIGINAL));
        assertFalse(PreviewStateRules.skipSlimCandidate(null));
    }

    // ---------------------------------------------------------------- describe

    /** 日志短名覆盖全部枚举值以及 null，且互不相同（便于 grep 区分）。 */
    @Test public void describeCoversEveryState() {
        assertEquals("未登记", PreviewStateRules.describe(null));
        assertEquals("本地原图", PreviewStateRules.describe(LocalState.ORIGINAL));
        assertEquals("原地预览", PreviewStateRules.describe(LocalState.INPLACE_PREVIEW));
        assertEquals("原地写入中", PreviewStateRules.describe(LocalState.PENDING_INPLACE));
        assertEquals("侧车预览", PreviewStateRules.describe(LocalState.SIDECAR_PREVIEW));
        assertEquals("本地损坏", PreviewStateRules.describe(LocalState.DAMAGED));
    }
}
