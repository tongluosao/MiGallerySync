package com.tongluosao.gallerysync.core;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 原地覆盖策略的每条分支。
 *
 * <p>这四条判定（容器能不能覆盖、老侧车怎么迁移、能不能当原图上传、要不要取回原图）
 * 一旦判错，代价是相册里的原图被预览覆盖、或者预览被当成原图传上去 —— 都是不可逆的事故。
 * 所以这里把每个分支都钉成断言，包括 null / 空串 / 大小写扩展名 / 预览更大 / 预览缺失这些边角。
 */
public class InplaceSlimPolicyTest {

    private static final String SHA_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SHA_B =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    // ---------------------------------------------------------------- 1. 能不能原地覆盖

    /** HEIF 系与 JPEG 系可以原地覆盖；PNG 已被排除在空间优化之外 */
    @Test
    public void inplaceContainersAreHeifAndJpeg() {
        assertTrue(InplaceSlimPolicy.canOverwriteInPlace("IMG_20261117_101010.heic"));
        assertTrue(InplaceSlimPolicy.canOverwriteInPlace("IMG_20261117_101010.heif"));
        assertTrue(InplaceSlimPolicy.canOverwriteInPlace("IMG_20261117_101010.jpg"));
        assertTrue(InplaceSlimPolicy.canOverwriteInPlace("IMG_20261117_101010.jpeg"));
        // PNG 无损重编收益小、超大图还有 OOM 风险：一律不覆盖原图
        assertFalse(InplaceSlimPolicy.canOverwriteInPlace("shot.png"));
        assertFalse(InplaceSlimPolicy.canOverwriteInPlace("shot.PNG"));
    }

    /** 扩展名大小写不影响判定（相册里成片的 .HEIC/.JPG/.PNG） */
    @Test
    public void extensionCaseIsIgnored() {
        assertTrue(InplaceSlimPolicy.canOverwriteInPlace("IMG_1.HEIC"));
        assertTrue(InplaceSlimPolicy.canOverwriteInPlace("IMG_1.JpEg"));
        assertFalse(InplaceSlimPolicy.canOverwriteInPlace("IMG_1.Png"));
    }

    /** 完整路径也认：调用方常常把整条路径当文件名传进来 */
    @Test
    public void fullPathsAreAccepted() {
        assertTrue(InplaceSlimPolicy.canOverwriteInPlace(
                "/storage/emulated/0/DCIM/Camera/IMG_1.JPG"));
        assertTrue(InplaceSlimPolicy.canOverwriteInPlace("DCIM\\Camera\\IMG_1.heic"));
        // 目录名里带点不能当成扩展名
        assertFalse(InplaceSlimPolicy.canOverwriteInPlace("/storage/emulated/0/DCIM/v1.2/IMG_9"));
    }

    /** webp/bmp/gif 与视频一律不许原地覆盖（没有能写出同名容器的编码器） */
    @Test
    public void otherContainersCannotBeOverwritten() {
        assertFalse(InplaceSlimPolicy.canOverwriteInPlace("shot.webp"));
        assertFalse(InplaceSlimPolicy.canOverwriteInPlace("shot.bmp"));
        assertFalse(InplaceSlimPolicy.canOverwriteInPlace("anim.gif"));
        assertFalse(InplaceSlimPolicy.canOverwriteInPlace("VID_1.mp4"));
        assertFalse(InplaceSlimPolicy.canOverwriteInPlace("VID_1.mov"));
    }

    /** null / 空串 / 无扩展名 / 点结尾：认不出来就回 false，绝不猜 */
    @Test
    public void unknownNamesCannotBeOverwritten() {
        assertFalse(InplaceSlimPolicy.canOverwriteInPlace(null));
        assertFalse(InplaceSlimPolicy.canOverwriteInPlace(""));
        assertFalse(InplaceSlimPolicy.canOverwriteInPlace("   "));
        assertFalse(InplaceSlimPolicy.canOverwriteInPlace("IMG_1"));
        assertFalse(InplaceSlimPolicy.canOverwriteInPlace("IMG_1."));
        // 点开头是隐藏文件，不是“jpg 扩展名”
        assertFalse(InplaceSlimPolicy.canOverwriteInPlace(".jpg"));
    }

    // ---------------------------------------------------------------- 2. 写盘格式

    /** 目标格式就是容器对应的编码器输出格式 */
    @Test
    public void targetFormatMapsContainers() {
        assertEquals("heic", InplaceSlimPolicy.targetFormat("IMG_1.heic"));
        assertEquals("heic", InplaceSlimPolicy.targetFormat("IMG_1.HEIC"));
        // .heif 容器装的就是 HEVC 静态图：写 heic 码流、文件名保持不变
        assertEquals("heic", InplaceSlimPolicy.targetFormat("IMG_1.heif"));
        assertEquals("jpg", InplaceSlimPolicy.targetFormat("IMG_1.jpg"));
        assertEquals("jpg", InplaceSlimPolicy.targetFormat("IMG_1.JPEG"));
        // 原地覆盖 PNG 必须还是 PNG：回 jpg 就意味着透明通道被压成白底
        assertEquals("png", InplaceSlimPolicy.targetFormat("shot.png"));
        assertEquals("png", InplaceSlimPolicy.targetFormat("SHOT.PNG"));
        assertEquals("png", InplaceSlimPolicy.targetFormat("/DCIM/Camera/shot.png"));
    }

    /** 不可原地覆盖的容器没有目标格式 */
    @Test
    public void targetFormatIsNullForOtherContainers() {
        assertNull(InplaceSlimPolicy.targetFormat("shot.webp"));
        assertNull(InplaceSlimPolicy.targetFormat("VID_1.mp4"));
        assertNull(InplaceSlimPolicy.targetFormat(null));
        assertNull(InplaceSlimPolicy.targetFormat(""));
        assertNull(InplaceSlimPolicy.targetFormat("IMG_1."));
    }

    // ---------------------------------------------------------------- 3. 目标路径

    /** 原地目标就是原文件本身：不改名、不落侧车目录，相册那一行不用改 */
    @Test
    public void targetPathIsTheOriginalFile() {
        File original = new File("/storage/emulated/0/DCIM/Camera/IMG_1.heic");
        assertSame(original, InplaceSlimPolicy.targetPath(original));
        assertNull(InplaceSlimPolicy.targetPath(null));
    }

    // ---------------------------------------------------------------- 4. 老侧车迁移动作

    /** 同扩展名且预览更小：直接把预览搬到原路径，码流一点不改 */
    @Test
    public void sameExtensionAndSmallerRenames() {
        assertEquals(InplaceSlimPolicy.MigrationAction.RENAME,
                InplaceSlimPolicy.migrationAction("IMG_1.jpg", "IMG_1.jpg", "jpg", true, true));
        assertEquals(InplaceSlimPolicy.MigrationAction.RENAME,
                InplaceSlimPolicy.migrationAction("IMG_1.HEIC", "img_1.heic", "heic", true, true));
    }

    /**
     * png 原图：迁移动作一律 SKIP —— 不 RENAME、不 TRANSCODE、也不 SIDECAR。
     *
     * <p>空间优化整类跳过 png（无损重编收益小、超大图还有 OOM 风险），历史侧车预览就原样留着，
     * 既不搬到原路径、也不重编码，原图字节一定不会被覆盖。
     */
    @Test
    public void pngMigrationAlwaysSkips() {
        // 同扩展名的侧车预览：上一版会 RENAME 到原路径，现在必须跳过
        assertEquals(InplaceSlimPolicy.MigrationAction.SKIP,
                InplaceSlimPolicy.migrationAction("shot.png", "shot.png", "png", true, true));
        assertEquals(InplaceSlimPolicy.MigrationAction.SKIP,
                InplaceSlimPolicy.migrationAction("shot.PNG", "shot.png", null, true, true));
        assertEquals(InplaceSlimPolicy.MigrationAction.SKIP,
                InplaceSlimPolicy.migrationAction("shot.png", "shot.png", "Png", true, true));
        // 不同扩展名的预览：既不允许转码，也不允许继续当侧车
        assertEquals(InplaceSlimPolicy.MigrationAction.SKIP,
                InplaceSlimPolicy.migrationAction("shot.png", "shot.jpg", "jpg", true, true));
        assertEquals(InplaceSlimPolicy.MigrationAction.SKIP,
                InplaceSlimPolicy.migrationAction("shot.png", "shot.heic", "heic", true, true));
        // 预览缺失 / 不更小：同样跳过（与“png 不进迁移”一致）
        assertEquals(InplaceSlimPolicy.MigrationAction.SKIP,
                InplaceSlimPolicy.migrationAction("shot.png", "shot.png", "png", false, true));
        assertEquals(InplaceSlimPolicy.MigrationAction.SKIP,
                InplaceSlimPolicy.migrationAction("shot.png", "shot.png", "png", true, false));
    }

    /**
     * png 的“不可原地覆盖”只影响空间优化：{@link InplaceSlimPolicy#targetFormat} 仍回 {@code "png"}，
     * 因为下载高清等调用点还要按原容器选 PNG 编码器（透明通道不能被压成白底）。
     */
    @Test
    public void pngKeepsItsTargetFormatButNeverInPlace() {
        assertFalse(InplaceSlimPolicy.canOverwriteInPlace("shot.png"));
        assertFalse(InplaceSlimPolicy.canOverwriteInPlace("/storage/emulated/0/DCIM/Camera/shot.PNG"));
        assertEquals("png", InplaceSlimPolicy.targetFormat("shot.png"));
        assertEquals("png", InplaceSlimPolicy.targetFormat("shot.PNG"));
    }

    /** 同扩展名不重新编码，所以 webp/视频也能安全地 RENAME（容器约束不适用） */
    @Test
    public void sameExtensionRenamesEvenForNonEncodableContainers() {
        assertEquals(InplaceSlimPolicy.MigrationAction.RENAME,
                InplaceSlimPolicy.migrationAction("clip.webp", "clip.webp", "webp", true, true));
        assertEquals(InplaceSlimPolicy.MigrationAction.RENAME,
                InplaceSlimPolicy.migrationAction("VID_1.mp4", "VID_1.mp4", "hevc", true, true));
    }

    /** 预览缺失 / 预览并不更小：什么都不做（先看值不值得动） */
    @Test
    public void missingOrBiggerPreviewSkips() {
        assertEquals(InplaceSlimPolicy.MigrationAction.SKIP,
                InplaceSlimPolicy.migrationAction("IMG_1.jpg", "IMG_1.jpg", "jpg", false, true));
        assertEquals(InplaceSlimPolicy.MigrationAction.SKIP,
                InplaceSlimPolicy.migrationAction("IMG_1.jpg", "IMG_1.jpg", "jpg", true, false));
        assertEquals(InplaceSlimPolicy.MigrationAction.SKIP,
                InplaceSlimPolicy.migrationAction("IMG_1.heic", "IMG_1.jpg", "jpg", false, false));
    }

    /** 扩展名不同但原容器可编码：解码预览后按原格式重编 */
    @Test
    public void encodableOriginalWithOtherPreviewTranscodes() {
        assertEquals(InplaceSlimPolicy.MigrationAction.TRANSCODE,
                InplaceSlimPolicy.migrationAction("IMG_1.heic", "IMG_1.jpg", "jpg", true, true));
        assertEquals(InplaceSlimPolicy.MigrationAction.TRANSCODE,
                InplaceSlimPolicy.migrationAction("IMG_1.jpg", "IMG_1.heic", "heic", true, true));
        assertEquals(InplaceSlimPolicy.MigrationAction.TRANSCODE,
                InplaceSlimPolicy.migrationAction("IMG_1.heif", "IMG_1.jpeg", "jpeg", true, true));
    }

    /** webp 等不可原地覆盖的容器：继续留在侧车目录 */
    @Test
    public void nonEncodableContainersStaySidecar() {
        assertEquals(InplaceSlimPolicy.MigrationAction.SIDECAR,
                InplaceSlimPolicy.migrationAction("shot.webp", "shot.jpg", "jpg", true, true));
        assertEquals(InplaceSlimPolicy.MigrationAction.SIDECAR,
                InplaceSlimPolicy.migrationAction("VID_1.mp4", "VID_1.jpg", "jpg", true, true));
        // 同一族的扩展名只是叫法不同，直接改名即可
        assertEquals(InplaceSlimPolicy.MigrationAction.RENAME,
                InplaceSlimPolicy.migrationAction("shot.jpeg", "shot.jpg", "jpg", true, true));
        assertEquals(InplaceSlimPolicy.MigrationAction.RENAME,
                InplaceSlimPolicy.migrationAction("shot.heif", "shot.heic", "heic", true, true));
    }

    /** 预览码流认不出来就不敢转码：宁可继续占侧车目录，也不往原路径写不明字节 */
    @Test
    public void undecodablePreviewStaysSidecar() {
        assertEquals(InplaceSlimPolicy.MigrationAction.SIDECAR,
                InplaceSlimPolicy.migrationAction("IMG_1.heic", "IMG_1.bin", null, true, true));
        assertEquals(InplaceSlimPolicy.MigrationAction.SIDECAR,
                InplaceSlimPolicy.migrationAction("IMG_1.heic", "IMG_1.bin", "avif", true, true));
    }

    /** 显式格式优先于文件名：文件名看不出格式时，注册的 previewFormat 说了算 */
    @Test
    public void explicitPreviewFormatWins() {
        assertEquals(InplaceSlimPolicy.MigrationAction.TRANSCODE,
                InplaceSlimPolicy.migrationAction("IMG_1.heic", "IMG_1.bin", "jpg", true, true));
        assertEquals(InplaceSlimPolicy.MigrationAction.TRANSCODE,
                InplaceSlimPolicy.migrationAction("IMG_1.heic", "IMG_1.bin", ".HEIC", true, true));
        // 空白 / 字面量 null 等同于“没写格式”，回落到扩展名
        assertEquals(InplaceSlimPolicy.MigrationAction.SIDECAR,
                InplaceSlimPolicy.migrationAction("IMG_1.heic", "IMG_1.bin", "  ", true, true));
        assertEquals(InplaceSlimPolicy.MigrationAction.SIDECAR,
                InplaceSlimPolicy.migrationAction("IMG_1.heic", "IMG_1.bin", "null", true, true));
    }

    /** 没有原文件名（或原文件名没有扩展名）：无从判断，保持侧车 */
    @Test
    public void unknownOriginalNameStaysSidecar() {
        assertEquals(InplaceSlimPolicy.MigrationAction.SIDECAR,
                InplaceSlimPolicy.migrationAction(null, "IMG_1.jpg", "jpg", true, true));
        assertEquals(InplaceSlimPolicy.MigrationAction.SIDECAR,
                InplaceSlimPolicy.migrationAction("", "IMG_1.jpg", "jpg", true, true));
        assertEquals(InplaceSlimPolicy.MigrationAction.SIDECAR,
                InplaceSlimPolicy.migrationAction("IMG_1", "IMG_1.jpg", "jpg", true, true));
    }

    // ---------------------------------------------------------------- 5. 能不能当原图上传

    /** 原地预览是快速路径：状态一说不是原图，长度/摘要再像也不看 */
    @Test
    public void inplacePreviewAlwaysBlocks() {
        assertEquals(InplaceSlimPolicy.UploadVerdict.BLOCK_PREVIEW,
                InplaceSlimPolicy.uploadVerdict("INPLACE_PREVIEW", 1024L, 1024L, SHA_A, SHA_A));
        assertEquals(InplaceSlimPolicy.UploadVerdict.BLOCK_PREVIEW,
                InplaceSlimPolicy.uploadVerdict("inplace_preview", 9L, 8L, SHA_B, SHA_A));
        assertEquals(InplaceSlimPolicy.UploadVerdict.BLOCK_PREVIEW,
                InplaceSlimPolicy.uploadVerdict("inplace-preview", 1L, 2L, null, null));
    }

    /** 其余“已知不是原图”的状态同样一律拦下 */
    @Test
    public void otherNonOriginalStatesBlock() {
        assertEquals(InplaceSlimPolicy.UploadVerdict.BLOCK_PREVIEW,
                InplaceSlimPolicy.uploadVerdict("PENDING_INPLACE", 1024L, 1024L, SHA_A, SHA_A));
        assertEquals(InplaceSlimPolicy.UploadVerdict.BLOCK_PREVIEW,
                InplaceSlimPolicy.uploadVerdict("SIDECAR_PREVIEW", 1024L, 1024L, SHA_A, SHA_A));
        assertEquals(InplaceSlimPolicy.UploadVerdict.BLOCK_PREVIEW,
                InplaceSlimPolicy.uploadVerdict("DAMAGED", 1024L, 1024L, SHA_A, SHA_A));
    }

    /** ORIGINAL + 长度与摘要都一致：唯一放行的一支 */
    @Test
    public void originalWithMatchingBytesAllows() {
        assertEquals(InplaceSlimPolicy.UploadVerdict.ALLOW,
                InplaceSlimPolicy.uploadVerdict("ORIGINAL", 4096L, 4096L, SHA_A, SHA_A));
        // 摘要比对不区分大小写（远端哈希有大小写两种来源）
        assertEquals(InplaceSlimPolicy.UploadVerdict.ALLOW,
                InplaceSlimPolicy.uploadVerdict("ORIGINAL", 4096L, 4096L, SHA_A.toUpperCase(),
                        SHA_A));
    }

    /** ORIGINAL 但字节对不上：同长度换过内容、或长度就不同，都算预览 */
    @Test
    public void originalWithMismatchingBytesBlocks() {
        assertEquals(InplaceSlimPolicy.UploadVerdict.BLOCK_PREVIEW,
                InplaceSlimPolicy.uploadVerdict("ORIGINAL", 4096L, 4096L, SHA_B, SHA_A));
        assertEquals(InplaceSlimPolicy.UploadVerdict.BLOCK_PREVIEW,
                InplaceSlimPolicy.uploadVerdict("ORIGINAL", 1024L, 4096L, SHA_A, SHA_A));
    }

    /** 登记里有摘要、本地却没算出摘要：缺证据不放行（fail-closed） */
    @Test
    public void originalWithoutLocalHashBlocksWhenHashKnown() {
        assertEquals(InplaceSlimPolicy.UploadVerdict.BLOCK_PREVIEW,
                InplaceSlimPolicy.uploadVerdict("ORIGINAL", 4096L, 4096L, null, SHA_A));
        assertEquals(InplaceSlimPolicy.UploadVerdict.BLOCK_PREVIEW,
                InplaceSlimPolicy.uploadVerdict("ORIGINAL", 4096L, 4096L, "  ", SHA_A));
    }

    /** 两边都没摘要时只能比长度：长度一致即放行（老记录没有可比摘要的兜底） */
    @Test
    public void originalWithoutAnyHashFallsBackToLength() {
        assertEquals(InplaceSlimPolicy.UploadVerdict.ALLOW,
                InplaceSlimPolicy.uploadVerdict("ORIGINAL", 4096L, 4096L, null, null));
    }

    /** state 为 null / 未知（旧记录、手改线名）：按长度/摘要判定，不一致就拦 */
    @Test
    public void unknownStateFallsBackToBytes() {
        assertEquals(InplaceSlimPolicy.UploadVerdict.ALLOW,
                InplaceSlimPolicy.uploadVerdict(null, 4096L, 4096L, SHA_A, SHA_A));
        assertEquals(InplaceSlimPolicy.UploadVerdict.BLOCK_PREVIEW,
                InplaceSlimPolicy.uploadVerdict(null, 1024L, 4096L, SHA_A, SHA_A));
        assertEquals(InplaceSlimPolicy.UploadVerdict.BLOCK_PREVIEW,
                InplaceSlimPolicy.uploadVerdict("banana", 4096L, 4096L, SHA_B, SHA_A));
        assertEquals(InplaceSlimPolicy.UploadVerdict.ALLOW,
                InplaceSlimPolicy.uploadVerdict("banana", 4096L, 4096L, SHA_A, SHA_A));
    }

    /** 长度没登记 / 本地为空：没有可比的大小时一律拦下 */
    @Test
    public void missingLengthsBlock() {
        assertEquals(InplaceSlimPolicy.UploadVerdict.BLOCK_PREVIEW,
                InplaceSlimPolicy.uploadVerdict("ORIGINAL", 4096L, 0L, SHA_A, SHA_A));
        assertEquals(InplaceSlimPolicy.UploadVerdict.BLOCK_PREVIEW,
                InplaceSlimPolicy.uploadVerdict("ORIGINAL", 0L, 4096L, SHA_A, SHA_A));
        assertEquals(InplaceSlimPolicy.UploadVerdict.BLOCK_PREVIEW,
                InplaceSlimPolicy.uploadVerdict(null, 0L, 0L, null, null));
    }

    // ---------------------------------------------------------------- 6. 要不要取回原图

    /** ORIGINAL 永远不需要取回：长度对不上说明文件被别的东西改过，取回原图修不了它 */
    @Test
    public void originalStateNeverNeedsRestore() {
        assertFalse(InplaceSlimPolicy.needsOriginalRestore("ORIGINAL", 1024L, 4096L));
        assertFalse(InplaceSlimPolicy.needsOriginalRestore("original", 4096L, 4096L));
    }

    /** 原地预览：本地比原图短（正常情况）→ 需要取回；恰好等大 → 取不取回都不改变占用 */
    @Test
    public void inplacePreviewRestoresOnlyWhenLengthDiffers() {
        assertTrue(InplaceSlimPolicy.needsOriginalRestore("INPLACE_PREVIEW", 1024L, 4096L));
        assertFalse(InplaceSlimPolicy.needsOriginalRestore("INPLACE_PREVIEW", 4096L, 4096L));
    }

    /** 侧车预览与旧记录（state 缺失）沿用长度比较，行为与升级前一致 */
    @Test
    public void legacyAndSidecarStatesFallBackToLength() {
        assertTrue(InplaceSlimPolicy.needsOriginalRestore(null, 1024L, 4096L));
        assertFalse(InplaceSlimPolicy.needsOriginalRestore(null, 4096L, 4096L));
        assertTrue(InplaceSlimPolicy.needsOriginalRestore("SIDECAR_PREVIEW", 1024L, 4096L));
        assertTrue(InplaceSlimPolicy.needsOriginalRestore("banana", 1024L, 4096L));
    }

    /** 没登记原图长度：菜单没有意义，永不提示 */
    @Test
    public void unknownOriginalLengthNeverRestores() {
        assertFalse(InplaceSlimPolicy.needsOriginalRestore("INPLACE_PREVIEW", 1024L, 0L));
        assertFalse(InplaceSlimPolicy.needsOriginalRestore(null, 1024L, 0L));
    }
}
