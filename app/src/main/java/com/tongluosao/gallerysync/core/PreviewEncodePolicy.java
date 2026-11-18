package com.tongluosao.gallerysync.core;

import java.util.Locale;

/**
 * 预览编码的纯策略：把“目标容器 + 保留 HEIC 开关”翻成“必须写成哪个编码格式”，
 * 再给出降采样倍率与等比缩放后的边长。
 *
 * <p>这里刻意<b>不引用 Android</b>（也就没有 {@code Bitmap.CompressFormat}）：格式到编码器的
 * 最后一跳由调用方完成，而“png 必须输出 png”“png 要保 alpha”这类规则可以纯 JVM 单测钉住。
 * 缩放数学也集中在这里，JPEG/PNG 两条编码路径共用同一份，不再各写一套循环。
 */
public final class PreviewEncodePolicy {

    /** 与历史实现一致：先按 2 的幂降采样，长边落在 {@code maxEdge*2} 以内再等比缩到 maxEdge。 */
    private static final int SAMPLE_FACTOR = 2;

    /** 降采样的上限：再大也没有意义，避免病态尺寸把循环拖长。 */
    private static final int MAX_SAMPLE = 1 << 29;

    private PreviewEncodePolicy() {
    }

    /**
     * 目标容器必须使用的预览编码格式。
     *
     * <ul>
     *   <li>{@code png} → {@code png}：PNG 只有无损编码，写成 JPEG 会丢掉透明通道，
     *       所以与“保留 HEIC”开关无关；</li>
     *   <li>{@code heic}/{@code heif} → {@code preserveHeic ? "heic" : "jpg"}；</li>
     *   <li>{@code jpg}/{@code jpeg} → {@code jpg}；</li>
     *   <li>其余容器（webp/bmp/gif/视频/未知）→ {@code null}，由调用方退回老逻辑。</li>
     * </ul>
     */
    public static String encodeFormat(String targetFormat, boolean preserveHeic) {
        String format = clean(targetFormat);
        if (format == null) {
            return null;
        }
        switch (format.toLowerCase(Locale.US)) {
            case "png":
                return "png";
            case "heic":
            case "heif":
                return preserveHeic ? "heic" : "jpg";
            case "jpg":
            case "jpeg":
                return "jpg";
            default:
                return null;
        }
    }

    /** 这份预览编码是否要保留 alpha（只有 png；JPEG/HEIC 会铺白底把透明压平）。 */
    public static boolean keepsAlpha(String encodeFormat) {
        String format = clean(encodeFormat);
        return format != null && "png".equals(format.toLowerCase(Locale.US));
    }

    /**
     * PNG IHDR 的 color type 带不带 alpha 通道。
     *
     * <p>0=灰度、2=RGB 都没有 alpha；4=灰度+alpha、6=RGBA 有；3=调色板（可能靠 tRNS 透明，
     * Android 编码器不会产出，这里保守放行）。用于落盘后复核“透明通道真的还在”。
     */
    public static boolean pngColorTypeKeepsAlpha(int colorType) {
        switch (colorType) {
            case 3:
            case 4:
            case 6:
                return true;
            default:
                return false;
        }
    }

    /**
     * 解码时的 {@code inSampleSize}：长边降到 {@code maxEdge*2} 以内，与历史实现逐位一致
     * （2 的幂；即使降完仍大于 maxEdge 也不继续降，剩下的交给等比缩放）。
     */
    public static int sampleSize(int width, int height, int maxEdge) {
        if (width <= 0 || height <= 0) {
            return 1;
        }
        long limit = maxEdge > 0 ? (long) maxEdge * 2L : Long.MAX_VALUE;
        int sample = 1;
        while (Math.max(width / (long) sample, height / (long) sample) > limit) {
            if (sample >= MAX_SAMPLE) {
                break;
            }
            sample *= SAMPLE_FACTOR;
        }
        return sample;
    }

    /** 等比缩放后的宽：长边超过 maxEdge 时按同一比例缩，至少 1 像素。 */
    public static int scaledWidth(int width, int height, int maxEdge) {
        return scaleEdge(width, Math.max(width, height), maxEdge);
    }

    /** 等比缩放后的高。 */
    public static int scaledHeight(int width, int height, int maxEdge) {
        return scaleEdge(height, Math.max(width, height), maxEdge);
    }

    /** 需不需要缩放：长边超过 maxEdge。 */
    public static boolean needsResize(int width, int height, int maxEdge) {
        return width > 0 && height > 0 && maxEdge > 0 && Math.max(width, height) > maxEdge;
    }

    private static int scaleEdge(int edge, int longEdge, int maxEdge) {
        if (edge <= 0) {
            return 1;
        }
        if (maxEdge <= 0 || longEdge <= maxEdge) {
            return edge;
        }
        float scale = (float) maxEdge / (float) longEdge;
        return Math.max(1, Math.round(edge * scale));
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equals(trimmed) ? null : trimmed;
    }
}
