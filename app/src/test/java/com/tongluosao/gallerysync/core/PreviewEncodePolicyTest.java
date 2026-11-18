package com.tongluosao.gallerysync.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 预览编码策略：格式选择与缩放数学。
 *
 * <p>这两个纯函数决定“写进原路径的字节到底是什么”：png 容器一旦被编成 JPEG，透明通道就永久
 * 丢失；缩放算错则会让预览比原图还大、或者长边超过上限。两种错都会直接毁掉相册里的原图，
 * 所以每条分支都钉成断言（本类不碰 Android，纯 JVM 可跑）。
 */
public class PreviewEncodePolicyTest {

    private static final int MAX_EDGE = 2560;

    // ---------------------------------------------------------------- 1. 格式选择

    /** 核心规则：png 容器必须输出 png，与“保留 HEIC”开关无关 */
    @Test
    public void pngAlwaysEncodesPng() {
        assertEquals("png", PreviewEncodePolicy.encodeFormat("png", false));
        assertEquals("png", PreviewEncodePolicy.encodeFormat("png", true));
        // 大小写与空白不改变结论
        assertEquals("png", PreviewEncodePolicy.encodeFormat(" PNG ", false));
    }

    /** .png 原图经过策略链路后，编码格式仍然是 png（“png 必须输出 png”的端到端断言） */
    @Test
    public void pngTargetFlowsThroughPolicyAsPng() {
        assertEquals("png", InplaceSlimPolicy.targetFormat("shot.PNG"));
        assertEquals("png", PreviewEncodePolicy.encodeFormat(
                InplaceSlimPolicy.targetFormat("shot.PNG"), false));
        assertEquals("png", PreviewEncodePolicy.encodeFormat(
                InplaceSlimPolicy.targetFormat("/DCIM/Camera/shot.png"), true));
    }

    /** HEIC 家族看“保留 HEIC”开关：开着写 heic，关掉写 jpg（关掉时不能原地覆盖 .heic） */
    @Test
    public void heicFollowsThePreserveSwitch() {
        assertEquals("heic", PreviewEncodePolicy.encodeFormat("heic", true));
        assertEquals("heic", PreviewEncodePolicy.encodeFormat("heif", true));
        assertEquals("jpg", PreviewEncodePolicy.encodeFormat("heic", false));
        assertEquals("jpg", PreviewEncodePolicy.encodeFormat("heif", false));
    }

    /** JPEG 家族恒为 jpg，png 分支之外不会被升成 heic */
    @Test
    public void jpegStaysJpeg() {
        assertEquals("jpg", PreviewEncodePolicy.encodeFormat("jpg", true));
        assertEquals("jpg", PreviewEncodePolicy.encodeFormat("jpeg", false));
        assertEquals("jpg", PreviewEncodePolicy.encodeFormat("JPEG", true));
    }

    /** 没有目标格式（webp/bmp/gif/视频/未知）时回 null：调用方据此退回老逻辑 */
    @Test
    public void unknownTargetFormatsHaveNoEncoding() {
        assertNull(PreviewEncodePolicy.encodeFormat(null, true));
        assertNull(PreviewEncodePolicy.encodeFormat("", true));
        assertNull(PreviewEncodePolicy.encodeFormat("   ", true));
        assertNull(PreviewEncodePolicy.encodeFormat("null", true));
        assertNull(PreviewEncodePolicy.encodeFormat("webp", true));
        assertNull(PreviewEncodePolicy.encodeFormat("bmp", true));
        assertNull(PreviewEncodePolicy.encodeFormat("gif", true));
        assertNull(PreviewEncodePolicy.encodeFormat("mp4", false));
    }

    /** 只有 png 需要 alpha 通道：JPEG/HEIC 会铺白底 */
    @Test
    public void onlyPngKeepsAlpha() {
        assertTrue(PreviewEncodePolicy.keepsAlpha("png"));
        assertTrue(PreviewEncodePolicy.keepsAlpha("PNG"));
        assertFalse(PreviewEncodePolicy.keepsAlpha("jpg"));
        assertFalse(PreviewEncodePolicy.keepsAlpha("heic"));
        assertFalse(PreviewEncodePolicy.keepsAlpha(null));
        assertFalse(PreviewEncodePolicy.keepsAlpha(""));
    }

    // ---------------------------------------------------------------- 2. 降采样倍率

    /** 与历史循环逐位一致：长边降到 maxEdge*2 以内，倍率是 2 的幂，不继续降到 maxEdge */
    @Test
    public void sampleSizeHalvesUntilTwiceTheLimit() {
        // 6000 > 5120 → 2；3000 不再大于 5120，停
        assertEquals(2, PreviewEncodePolicy.sampleSize(6000, 4000, MAX_EDGE));
        // 12000 → 2(6000) → 4(3000)
        assertEquals(4, PreviewEncodePolicy.sampleSize(12000, 8000, MAX_EDGE));
        // 刚好等于 2 倍上限不算超
        assertEquals(1, PreviewEncodePolicy.sampleSize(5120, 100, MAX_EDGE));
        assertEquals(2, PreviewEncodePolicy.sampleSize(5121, 100, MAX_EDGE));
    }

    /** 小图不降采样；尺寸读不出来时保守回 1（让解码器自己决定） */
    @Test
    public void sampleSizeIsOneForSmallOrUnknownImages() {
        assertEquals(1, PreviewEncodePolicy.sampleSize(1000, 800, MAX_EDGE));
        assertEquals(1, PreviewEncodePolicy.sampleSize(MAX_EDGE, MAX_EDGE, MAX_EDGE));
        assertEquals(1, PreviewEncodePolicy.sampleSize(0, 0, MAX_EDGE));
        assertEquals(1, PreviewEncodePolicy.sampleSize(-5, 100, MAX_EDGE));
        assertEquals(1, PreviewEncodePolicy.sampleSize(6000, 4000, 0));
    }

    /** 病态尺寸也不能死循环：倍率仍落在 2 的幂上 */
    @Test
    public void sampleSizeSurvivesAbsurdDimensions() {
        int sample = PreviewEncodePolicy.sampleSize(Integer.MAX_VALUE, Integer.MAX_VALUE,
                MAX_EDGE);
        assertTrue(sample > 0);
        assertEquals(0, sample & (sample - 1));
        assertTrue(sample <= (1 << 29));
    }

    // ---------------------------------------------------------------- 3. 等比缩放

    /** 长边缩到上限，短边按同一比例四舍五入（6000x4000 → 2560x1707） */
    @Test
    public void scaleKeepsAspectRatioOnTheLongEdge() {
        assertEquals(2560, PreviewEncodePolicy.scaledWidth(6000, 4000, MAX_EDGE));
        assertEquals(1707, PreviewEncodePolicy.scaledHeight(6000, 4000, MAX_EDGE));
        // 竖图：高是长边
        assertEquals(1920, PreviewEncodePolicy.scaledWidth(3000, 4000, MAX_EDGE));
        assertEquals(2560, PreviewEncodePolicy.scaledHeight(3000, 4000, MAX_EDGE));
    }

    /** 不放大：长边已经在上限以内时原样返回 */
    @Test
    public void scaleNeverUpgrades() {
        assertEquals(1000, PreviewEncodePolicy.scaledWidth(1000, 800, MAX_EDGE));
        assertEquals(800, PreviewEncodePolicy.scaledHeight(1000, 800, MAX_EDGE));
        assertEquals(MAX_EDGE, PreviewEncodePolicy.scaledWidth(MAX_EDGE, 100, MAX_EDGE));
    }

    /** 极端长条也至少留 1 像素，不会缩成 0 */
    @Test
    public void scaleKeepsAtLeastOnePixel() {
        assertEquals(1, PreviewEncodePolicy.scaledWidth(1, 10000, MAX_EDGE));
        assertEquals(2560, PreviewEncodePolicy.scaledHeight(1, 10000, MAX_EDGE));
        assertEquals(1, PreviewEncodePolicy.scaledWidth(0, 10000, MAX_EDGE));
    }

    /** 要不要缩放的判据只看长边 */
    @Test
    public void needsResizeLooksAtTheLongEdge() {
        assertTrue(PreviewEncodePolicy.needsResize(6000, 4000, MAX_EDGE));
        assertTrue(PreviewEncodePolicy.needsResize(1000, 4000, MAX_EDGE));
        assertFalse(PreviewEncodePolicy.needsResize(2560, 1440, MAX_EDGE));
        assertFalse(PreviewEncodePolicy.needsResize(0, 0, MAX_EDGE));
        assertFalse(PreviewEncodePolicy.needsResize(6000, 4000, 0));
    }

    /** 落盘后复核 PNG 头部：只有带 alpha 的 color type 才算透明通道没丢 */
    @Test
    public void pngColorTypeKeepsAlphaOnlyForAlphaChannels() {
        assertTrue(PreviewEncodePolicy.pngColorTypeKeepsAlpha(6));  // RGBA
        assertTrue(PreviewEncodePolicy.pngColorTypeKeepsAlpha(4));  // 灰度 + alpha
        assertTrue(PreviewEncodePolicy.pngColorTypeKeepsAlpha(3));  // 调色板（可带 tRNS）
        assertFalse(PreviewEncodePolicy.pngColorTypeKeepsAlpha(2)); // RGB
        assertFalse(PreviewEncodePolicy.pngColorTypeKeepsAlpha(0)); // 灰度
        assertFalse(PreviewEncodePolicy.pngColorTypeKeepsAlpha(-1)); // 不是 PNG
    }
}
