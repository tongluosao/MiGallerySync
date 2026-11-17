package com.tongluosao.gallerysync.core;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * “空间优化”改为原地同名同扩展名覆盖之后的纯函数策略。
 *
 * <p>背景：老模式把预览写在 {@code <相册>/.gallerysync-slim/<原名>.<ext>} 侧车里，原路径上的
 * 原图被删掉；新模式把预览直接写回原路径（同名同扩展名），远端仍是完整原图的唯一真身。
 * 于是每个调用点都要回答四个问题：这个容器能不能原地覆盖？必须编成什么格式？老侧车预览怎么迁移？
 * 归属路径上的这份字节还能不能当原图上传？
 *
 * <p>这些问题全部只依赖文件名、字节数和摘要，所以集中在这样一个<b>纯函数</b>类里：
 * 不依赖 Android、不碰文件系统、没有可变状态，单测可以直接把每条分支钉住。
 * 调用方（{@code SlimHook} / {@code DownloadQueue} / 上传分类）只负责提供事实。
 */
public final class InplaceSlimPolicy {

    /** 能原地覆盖的扩展名：HEIF 系与 JPEG 系。其余容器（png/webp/bmp/gif/视频）一律不许动。 */
    private static final Set<String> INPLACE_EXTENSIONS =
            new HashSet<>(Arrays.asList("heic", "heif", "jpg", "jpeg"));

    /** 预览码流能被解码重编的格式；认不出来就不敢转码，退回侧车。 */
    private static final Set<String> DECODABLE_PREVIEW_FORMATS =
            new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "heic", "heif", "webp", "bmp"));

    private InplaceSlimPolicy() {
    }

    /**
     * 这个容器能不能原地覆盖：只有 {@code .heic/.heif/.jpg/.jpeg} 可以。
     *
     * <p>与 {@link #targetFormat(String)} 严格一致 —— 能原地覆盖就一定能编码出目标格式，
     * 不为“能覆盖但不知道写什么格式”留第二种答案。
     */
    public static boolean canOverwriteInPlace(String fileName) {
        return targetFormat(fileName) != null;
    }

    /**
     * 原地写盘必须使用的编码格式：{@code heic}/{@code heif} → {@code "heic"}，
     * {@code jpg}/{@code jpeg} → {@code "jpg"}，其它容器（含视频、无扩展名）→ {@code null}。
     *
     * <p>{@code .heif} 也回 {@code "heic"}：HEIF 容器本来就能装 HEVC 静态图，写进去的码流就是
     * heic 编码，文件名保持 {@code .heif} 不变（原地覆盖不改名）。
     */
    public static String targetFormat(String fileName) {
        String extension = extension(fileName);
        if (extension == null) {
            return null;
        }
        switch (extension) {
            case "heic":
            case "heif":
                return "heic";
            case "jpg":
            case "jpeg":
                return "jpg";
            default:
                return null;
        }
    }

    /**
     * 原地覆盖的目标路径：就是原文件本身。
     *
     * <p>不做 {@code getAbsoluteFile()}、不改名、不落侧车目录 —— 引擎按这个对象写盘，
     * 相册数据库里那一行指向的路径因此不需要改。{@code null} 进 {@code null} 出。
     */
    public static File targetPath(File original) {
        return original;
    }

    /** 老侧车预览的迁移动作。 */
    public enum MigrationAction {
        /** 预览已是同扩展名（可以直接搬到原路径，码流一点不改）。 */
        RENAME,
        /** 扩展名不同，但原容器可编码：解码预览后按原格式重编再覆盖原路径。 */
        TRANSCODE,
        /** 原容器不可原地覆盖（png/webp/视频/未知）：继续留在侧车目录。 */
        SIDECAR,
        /** 没有预览、或预览并不更小：什么都不做。 */
        SKIP
    }

    /**
     * 决定一条老侧车预览怎么迁移。
     *
     * <p>判定顺序（先看“值不值得动”，再看“能不能动”）：
     * <ol>
     *   <li>预览不存在，或并不比原图小 → {@link MigrationAction#SKIP}；</li>
     *   <li>预览与原图同扩展名 → {@link MigrationAction#RENAME}：直接把预览搬到原路径即可，
     *       码流不变、容器约束不适用（连 png 这种不能编码的容器也安全）；</li>
     *   <li>扩展名不同、原格式可编码（heic/jpg）、且预览码流能解码 → {@link MigrationAction#TRANSCODE}；</li>
     *   <li>其余情况（png/webp/视频/未知预览格式/没有原文件名）→ {@link MigrationAction#SIDECAR}，
     *       宁可继续占侧车目录，也不把不相干的字节写到原路径上。</li>
     * </ol>
     *
     * @param previewFormat 预览的实际编码格式，可空；为空时按预览文件名的扩展名判断
     */
    public static MigrationAction migrationAction(String originalName, String previewName,
            String previewFormat, boolean previewExists, boolean previewSmaller) {
        if (!previewExists || !previewSmaller) {
            return MigrationAction.SKIP;
        }
        String originalExtension = extension(originalName);
        if (originalExtension == null) {
            // 连原文件名都认不出来（空串/null/无扩展名）：不猜，保持侧车原样
            return MigrationAction.SIDECAR;
        }
        if (originalExtension.equals(extension(previewName))) {
            return MigrationAction.RENAME;
        }
        if (targetFormat(originalName) != null && isDecodable(previewFormat, previewName)) {
            return MigrationAction.TRANSCODE;
        }
        return MigrationAction.SIDECAR;
    }

    /** 上传分类结论。 */
    public enum UploadVerdict {
        /** 归属路径上的字节可以当原图上传。 */
        ALLOW,
        /** 归属路径上已知（或疑似）是预览：不许上传。 */
        BLOCK_PREVIEW
    }

    /**
     * 上传前的收场判定：这份本地字节能不能当“原图”传上去。
     *
     * <p>顺序按代价从低到高，<b>已知不是原图的状态连哈希都不看</b>：
     * <ol>
     *   <li>{@code INPLACE_PREVIEW} / {@code PENDING_INPLACE} / {@code SIDECAR_PREVIEW} /
     *       {@code DAMAGED} → {@link UploadVerdict#BLOCK_PREVIEW}（快速路径）；</li>
     *   <li>{@code ORIGINAL} 或 null/未知状态 → 只能靠字节事实：长度和摘要两项都对得上才放行；</li>
     *   <li>缺事实（长度未登记、本地长度为 0、登记里有摘要而本地没算出摘要）一律拦下 ——
     *       这里是 fail-closed，一个没验证过的上传宁可晚一点再传。</li>
     * </ol>
     *
     * @param localState     注册表里的 {@code localState} 线名，可空
     * @param localLength    本地当前字节数
     * @param originalLength 注册表里远端完整原图的字节数
     * @param localSha256    本地当前摘要，可空
     * @param originalSha256 注册表里远端完整原图的摘要，可空
     */
    public static UploadVerdict uploadVerdict(String localState, long localLength,
            long originalLength, String localSha256, String originalSha256) {
        String state = normalizeState(localState);
        if ("inplace_preview".equals(state) || "pending_inplace".equals(state)
                || "sidecar_preview".equals(state) || "damaged".equals(state)) {
            return UploadVerdict.BLOCK_PREVIEW;
        }
        if (originalLength <= 0L || localLength <= 0L) {
            return UploadVerdict.BLOCK_PREVIEW;
        }
        if (localLength != originalLength) {
            return UploadVerdict.BLOCK_PREVIEW;
        }
        String expected = clean(originalSha256);
        if (expected != null) {
            String actual = clean(localSha256);
            if (actual == null || !expected.equalsIgnoreCase(actual)) {
                return UploadVerdict.BLOCK_PREVIEW;
            }
        }
        return UploadVerdict.ALLOW;
    }

    /**
     * 需不需要从远端把原图取回来（“下载原图”菜单判据）。
     *
     * <p>{@code ORIGINAL} 直接回 false：状态已经说明本地就是原图，长度对不上只可能是文件
     * 被别的东西改过，那不是“取回原图”能修的事。其余状态（原地预览/待覆盖/侧车/未知）
     * 只在本地长度与登记原图长度不一致时回 true —— 长度一致说明这份预览恰好等大，
     * 取回与否都改变不了占用，不值得再下一次。
     */
    public static boolean needsOriginalRestore(String localState, long localLength,
            long originalLength) {
        if ("original".equals(normalizeState(localState))) {
            return false;
        }
        if (originalLength <= 0L) {
            return false;
        }
        return localLength != originalLength;
    }

    /** 预览码流能不能解码：先看显式格式，再看文件名扩展名。 */
    private static boolean isDecodable(String previewFormat, String previewName) {
        String value = clean(previewFormat);
        if (value != null && value.startsWith(".")) {
            value = value.substring(1);
        }
        if (value == null) {
            value = extension(previewName);
        }
        return value != null && DECODABLE_PREVIEW_FORMATS.contains(value.toLowerCase(Locale.US));
    }

    /**
     * 取小写扩展名：{@code IMG_1.HEIC} → {@code heic}。
     * 无扩展名、点开头的隐藏文件、点结尾的怪名字一律回 {@code null}（不猜）。
     */
    private static String extension(String fileName) {
        if (fileName == null) {
            return null;
        }
        String name = fileName.trim();
        if (name.isEmpty()) {
            return null;
        }
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    /** 状态线名归一化：去空白、转小写、连字符统一成下划线（兼容手改名/旧日志里的写法）。 */
    private static String normalizeState(String localState) {
        String value = clean(localState);
        return value == null ? null : value.toLowerCase(Locale.US).replace('-', '_');
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equals(trimmed) ? null : trimmed;
    }
}
