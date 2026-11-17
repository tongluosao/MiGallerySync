package com.tongluosao.gallerysync.core;

/**
 * 读侧判据：把注册表里的“本地状态”（{@link OriginalStore.LocalState}）翻译成三个使用方的
 * 结论——能不能上传、要不要提供“下载原图”、空间优化要不要跳过。
 *
 * <p>为什么要这一层：原地覆盖（第 2 波）之后，同一个本地路径上的字节可能是完整原图，也可能
 * 是压缩预览，“路径在 {@code /.gallerysync-slim/} 里”“{@code thumbnailFile} 非空”这些旧判据
 * 都分不出来。唯一可靠的真相是注册表里记的状态，因此上传队列、恢复菜单、候选筛选三处必须
 * 用同一套规则，不能再各自写一份 if。
 *
 * <p>三个约定：
 * <ul>
 *   <li>纯函数、零 Android 依赖：只吃 {@code state} 与两个长度，方便单测逐格穷举；</li>
 *   <li>{@code state == null} 一律按 {@link OriginalStore.LocalState#ORIGINAL} 处理，与
 *       {@code OriginalStore} 反序列化时的兜底保持一致（旧 JSON 没有这个键）；</li>
 *   <li>长度未知（{@code <= 0}）时不判“已改写”：宁可放行也不要误拦，旧行为就是这样。</li>
 * </ul>
 *
 * <p>状态与三个结论的对照（“长度一致”指 {@code localLength == originalLength}）：
 * <pre>
 * 状态                     blocksUpload        needsOriginalRestore   skipSlimCandidate
 * ORIGINAL                 仅长度不一致时 true   仅长度不一致时 true      false
 * INPLACE_PREVIEW          true                true                   true
 * PENDING_INPLACE          true                true                   true
 * SIDECAR_PREVIEW          true                true                   true
 * DAMAGED                  true                true                   true
 * null（按 ORIGINAL）       仅长度不一致时 true   仅长度不一致时 true      false
 * </pre>
 *
 * <p>使用方（都在本波次或后续波次里）：
 * <ul>
 *   <li>{@code OriginalStore.isProtectedUpload}：上传总闸门，四处调用点共用；</li>
 *   <li>{@code UploadQueue}：被状态拦下时记 {@code event=upload_skip_preview_state}；</li>
 *   <li>{@code OriginalRestoreHook}：决定“下载原图”菜单是否显示；</li>
 *   <li>{@code SlimCandidates}：空间优化候选的精确过滤。</li>
 * </ul>
 */
public final class PreviewStateRules {

    private PreviewStateRules() {
    }

    /**
     * 是否禁止把本地这份文件当作原图上传。
     *
     * <p>四个“本地不是原图”的状态一律禁止：原地预览、写入中途、侧车预览、损坏。
     * {@code ORIGINAL} 只在长度确实对不上（且两边长度都已知）时禁止；长度未知时放行，
     * 后面 {@code OriginalStore.isProtectedUpload} 还会按摘要再判一次。
     */
    public static boolean blocksUpload(OriginalStore.LocalState state, long localLength,
            long originalLength) {
        if (isNotOriginal(state)) {
            return true;
        }
        return originalBytesReplaced(localLength, originalLength);
    }

    /**
     * 是否需要提供/自动执行“下载原图”。
     *
     * <p>与 {@link #blocksUpload} 同一组状态：本地不是原图就要能取回原图；{@code ORIGINAL}
     * 只在长度对不上时需要。从未优化过的普通照片（长度一致）不显示菜单，这正是用户抱怨
     * “没优化也显示下载原图”的根因。
     */
    public static boolean needsOriginalRestore(OriginalStore.LocalState state, long localLength,
            long originalLength) {
        if (isNotOriginal(state)) {
            return true;
        }
        return originalBytesReplaced(localLength, originalLength);
    }

    /**
     * 空间优化是否应跳过这一行：已优化（原地/侧车）、写入中途、损坏都要跳过，
     * 否则会拿预览再压一次（套娃）或覆盖掉未完成的状态。没有记录（{@code null}）不跳过，
     * 交给优化流程自己的安全闸门去判。
     */
    public static boolean skipSlimCandidate(OriginalStore.LocalState state) {
        return isNotOriginal(state);
    }

    /** 状态的中文短名，只用于日志；线名见 {@link OriginalStore.LocalState#wire()}。 */
    public static String describe(OriginalStore.LocalState state) {
        if (state == null) {
            return "未登记";
        }
        switch (state) {
            case ORIGINAL:
                return "本地原图";
            case INPLACE_PREVIEW:
                return "原地预览";
            case PENDING_INPLACE:
                return "原地写入中";
            case SIDECAR_PREVIEW:
                return "侧车预览";
            case DAMAGED:
                return "本地损坏";
            default:
                return "未知状态";
        }
    }

    /** 状态本身就是“本地不是完整原图”。{@code null} 按 ORIGINAL 处理，不算。 */
    private static boolean isNotOriginal(OriginalStore.LocalState state) {
        return state != null && state != OriginalStore.LocalState.ORIGINAL;
    }

    /**
     * ORIGINAL 状态下，本地字节是否已经不是登记的那份原图。
     *
     * <p>只看长度、不做摘要：主线程的菜单判据与上传前的快速闸门都不该整份读盘；
     * {@code OriginalStore.isProtectedUpload} 在后面还有一道摘要校验兜底。
     * 任一边长度未知（{@code <= 0}）时返回 false，保持“不误拦”的旧行为。
     */
    private static boolean originalBytesReplaced(long localLength, long originalLength) {
        return localLength > 0L && originalLength > 0L && localLength != originalLength;
    }
}
