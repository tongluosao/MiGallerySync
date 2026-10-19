package com.tongluosao.gallerysync.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * “下载原图”菜单判据：只有本地确实缺少完整原图时才显示。
 *
 * <p>回归场景：从未做过“空间优化”的普通照片，本地文件就是完整原图，磁盘大小 ==
 * {@code record.originalLength}。旧判据只要求注册表里有记录，而上传成功就会登记，于是这类照片
 * 也显示了“下载原图”，标题大小还和当前图片一致。
 *
 * <p>钩子侧（OriginalRestoreHook.localBytesNeedRestore）对 getOriginalPath/getThumnailPath/
 * getMicroPath 逐个调用下面这个纯判定；只要有一个候选路径是完整原图就判为不需要恢复。
 */
public class OriginalRestoreMenuGateTest {

    private static final long ORIGINAL_LENGTH = 4_194_304L;
    private static final String ORIGINAL_SHA256 = "ab".repeat(32);

    /** 未优化过的普通照片：本地文件存在、长度等于登记原图长度、不是登记副本 → 不显示。 */
    @Test public void intactLocalOriginalDoesNotOfferRestore() {
        assertFalse(OriginalStore.needsOriginalRestore(ORIGINAL_LENGTH, ORIGINAL_SHA256,
                true, ORIGINAL_LENGTH, false, null));
    }

    /** 空间优化后的照片：原图已删除，Gallery 的 localFile 为空 → 显示。 */
    @Test public void deletedLocalOriginalOffersRestore() {
        assertTrue(OriginalStore.needsOriginalRestore(ORIGINAL_LENGTH, ORIGINAL_SHA256,
                false, 0L, false, null));
    }

    /** 本地只剩 .gallerysync-slim 里的压缩预览（登记副本、体积更小）→ 显示。 */
    @Test public void registeredSlimPreviewOffersRestore() {
        assertTrue(OriginalStore.needsOriginalRestore(ORIGINAL_LENGTH, ORIGINAL_SHA256,
                true, ORIGINAL_LENGTH / 8L, true, null));
    }

    /** 路径本身就是登记的派生副本时，即使长度巧合相同也必须恢复。 */
    @Test public void sameLengthRegisteredDerivedCopyOffersRestore() {
        assertTrue(OriginalStore.needsOriginalRestore(ORIGINAL_LENGTH, ORIGINAL_SHA256,
                true, ORIGINAL_LENGTH, true, null));
    }

    /** 本地文件长度与登记原图不一致（被别的工具改写/只下到一半）→ 显示。 */
    @Test public void differentLocalLengthOffersRestore() {
        assertTrue(OriginalStore.needsOriginalRestore(ORIGINAL_LENGTH, ORIGINAL_SHA256,
                true, ORIGINAL_LENGTH + 1L, false, null));
        assertTrue(OriginalStore.needsOriginalRestore(ORIGINAL_LENGTH, ORIGINAL_SHA256,
                true, ORIGINAL_LENGTH - 1L, false, null));
    }

    /** 调用方已经算好摘要时（长度相同也要）比对摘要：不一致 → 显示。 */
    @Test public void knownDigestMismatchOffersRestore() {
        assertTrue(OriginalStore.needsOriginalRestore(ORIGINAL_LENGTH, ORIGINAL_SHA256,
                true, ORIGINAL_LENGTH, false, "cd".repeat(32)));
    }

    /** 摘要一致（大小写不同也接受）→ 本地就是完整原图 → 不显示。 */
    @Test public void knownDigestMatchDoesNotOfferRestore() {
        assertFalse(OriginalStore.needsOriginalRestore(ORIGINAL_LENGTH, ORIGINAL_SHA256,
                true, ORIGINAL_LENGTH, false, ORIGINAL_SHA256));
        assertFalse(OriginalStore.needsOriginalRestore(ORIGINAL_LENGTH, ORIGINAL_SHA256,
                true, ORIGINAL_LENGTH, false, ORIGINAL_SHA256.toUpperCase(java.util.Locale.US)));
    }

    /** 没登记原图大小（脏记录）时菜单没有意义，绝不能显示。 */
    @Test public void unregisteredOriginalLengthNeverOffersRestore() {
        assertFalse(OriginalStore.needsOriginalRestore(0L, ORIGINAL_SHA256,
                false, 0L, false, null));
        assertFalse(OriginalStore.needsOriginalRestore(-1L, null,
                true, 123L, true, "ef".repeat(32)));
    }

    /** 主线程路径不哈希：没有摘要可比时，长度一致且非登记副本即视为完整原图。 */
    @Test public void missingDigestFallsBackToLengthOnly() {
        assertFalse(OriginalStore.needsOriginalRestore(ORIGINAL_LENGTH, null,
                true, ORIGINAL_LENGTH, false, null));
        assertFalse(OriginalStore.needsOriginalRestore(ORIGINAL_LENGTH, "",
                true, ORIGINAL_LENGTH, false, null));
        // 记录里有摘要但本地未知 sha256（钩子不主动算）时，同样只看长度。
        assertFalse(OriginalStore.needsOriginalRestore(ORIGINAL_LENGTH, ORIGINAL_SHA256,
                true, ORIGINAL_LENGTH, false, null));
    }
}
