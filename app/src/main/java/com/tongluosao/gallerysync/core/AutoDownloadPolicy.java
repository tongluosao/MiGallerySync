package com.tongluosao.gallerysync.core;

import java.io.File;

/**
 * "接管"之后按注册表把 {@link OriginalStore.LocalState#REMOTE_ONLY} 的行排进下载队列的纯判据。
 *
 * <p>为什么需要它：{@code DownloadQueue.walk(...)} 是"远端有、本地没有"的补齐，判据里还有一条
 * "相册 cloud 表仍然指着这个本地路径"（{@link DownloadQueue#shouldPlanDownload}）。平板
 * HyperOS 1 上接管登记的行 {@code localFile} 是空的，所以 walk 从来不会为它们排任务 ——
 * 注册表已经知道 {@code remotePath} / 长度，没必要再等一次远端目录枚举。
 *
 * <p>这里只做判定，不碰 Android、不碰 IO、不依赖 {@link Config}（它背后是 SharedPreferences，
 * 纯 JVM 单测里没有实现）。所有输入都是调用方查好后传进来的标量，因此每条安全条件都能
 * 在 JUnit 里单独钉死。
 *
 * <p>安全条件的顺序就是"先拦住最便宜的"：开关 → 网络 → 布局 → 状态 → 重复 → 失败 → 上限 →
 * 落点状态。任何一条不满足都返回对应的 {@link Decision}，调用方把它翻译成
 * {@code event=auto_download_skip reason=...}。
 */
public final class AutoDownloadPolicy {

    private AutoDownloadPolicy() {
    }

    /** 一条 {@code REMOTE_ONLY} 行的结论 */
    public enum Decision {
        /** 排进下载队列 */
        PLAN,
        /** 目标文件已经存在且长度一致：直接登记成 ORIGINAL，一个字节都不下 */
        PROMOTE_EXISTING,
        /** 用户关掉了自动下载 */
        SKIP_DISABLED,
        /** 当前不是 Wi-Fi/以太网：这条链路只走 Wi-Fi，与相册自己的"仅 Wi-Fi"同口径 */
        SKIP_NOT_WIFI,
        /** 远端分目录方式不是按相册分：算不出相册落点，宁可不下载 */
        SKIP_LAYOUT,
        /** 注册表里这一行不是 {@code REMOTE_ONLY}（原图已经在本地、或状态不确定） */
        SKIP_NOT_REMOTE_ONLY,
        /** 已经在队列里/正在下载 */
        SKIP_ALREADY_SCHEDULED,
        /** 已经成功下载过（{@code mDone} 账本里有） */
        SKIP_ALREADY_DONE,
        /** 同一远端路径连续失败达到上限 */
        SKIP_FAILURE_LIMIT,
        /** 本轮额度已用完 */
        SKIP_ROUND_LIMIT,
        /** 目标位置已经有文件，但长度与远端原图不一致：绝不覆盖 */
        SKIP_EXISTS_DIFFERENT,
        /** 远端长度未知/为 0：无法校验下载结果 */
        SKIP_BAD_LENGTH
    }

    /**
     * 判定一条 {@code REMOTE_ONLY} 行该怎么处理。
     *
     * @param enabled           {@link Config#isAutoDownload()}
     * @param wifiNetwork       本次计划是否跑在 Wi-Fi/以太网上（{@code NetGuard.canUpload(ctx, true)}）
     * @param albumLayout       远端布局是否为 {@link Config#LAYOUT_ALBUM}
     * @param remoteOnly        注册表里这一行的状态是不是 {@code REMOTE_ONLY}
     * @param scheduled         这条远端路径是否已在队列/正在下载（含失败冷却）
     * @param done              这条远端路径是否已经成功下载过
     * @param targetExists      相册落点上是否已有文件
     * @param targetLength      相册落点文件的字节数（不存在时传 -1）
     * @param expectedLength    远端原图字节数（注册表里的 {@code originalLength}）
     * @param failures          这条远端路径连续失败次数
     * @param maxFailures       失败上限（{@code DownloadQueue.MAX_FAIL_PER_PATH}）
     * @param plannedThisRound  本轮已经排入/登记了多少条
     * @param maxPerRound       单轮上限（{@code DownloadQueue.MAX_DOWNLOADS_PER_ROUND}）
     */
    public static Decision decide(boolean enabled, boolean wifiNetwork, boolean albumLayout,
            boolean remoteOnly, boolean scheduled, boolean done, boolean targetExists,
            long targetLength, long expectedLength, int failures, int maxFailures,
            int plannedThisRound, int maxPerRound) {
        if (!enabled) {
            return Decision.SKIP_DISABLED;
        }
        if (!wifiNetwork) {
            return Decision.SKIP_NOT_WIFI;
        }
        if (!albumLayout) {
            return Decision.SKIP_LAYOUT;
        }
        if (!remoteOnly) {
            return Decision.SKIP_NOT_REMOTE_ONLY;
        }
        if (expectedLength <= 0L) {
            return Decision.SKIP_BAD_LENGTH;
        }
        if (scheduled) {
            return Decision.SKIP_ALREADY_SCHEDULED;
        }
        if (done) {
            return Decision.SKIP_ALREADY_DONE;
        }
        if (failures >= Math.max(maxFailures, 1)) {
            return Decision.SKIP_FAILURE_LIMIT;
        }
        if (plannedThisRound >= Math.max(maxPerRound, 1)) {
            return Decision.SKIP_ROUND_LIMIT;
        }
        if (targetExists) {
            return targetLength == expectedLength
                    ? Decision.PROMOTE_EXISTING : Decision.SKIP_EXISTS_DIFFERENT;
        }
        return Decision.PLAN;
    }

    /**
     * 这条远端路径应该落在相册的哪个文件上。
     *
     * <p>与手机侧 {@link DownloadQueue#destinationFor(Config, String, long)} 的相册分支完全一致：
     * 相对路径 = 远端路径去掉 {@code remoteRoot} 前缀，落点 = {@code primaryRoot + "/" + 相对路径}。
     * 例：{@code MiGallerySync/DCIM/Camera/IMG_1.HEIC} → {@code /storage/emulated/0/DCIM/Camera/IMG_1.HEIC}。
     *
     * <p>不落到镜像目录（{@code DCIM/MiGallerySync}），也不落到 {@code .gallerysync-slim}：
     * 接管行的远端路径本来就是从相册路径推出来的，回到原相册目录才是"补回被清掉的原图"。
     *
     * @return 目标文件；布局不对、路径不在 {@code remoteRoot} 之下、或分段不安全时返回 {@code null}
     *         （调用方按 {@link Decision#SKIP_LAYOUT} 跳过，绝不瞎猜一个落点）
     */
    public static File targetPath(String layout, String remoteRoot, String remotePath,
            File primaryRoot) {
        if (!Config.LAYOUT_ALBUM.equals(layout) || primaryRoot == null) {
            return null;
        }
        String relative = relativePath(remoteRoot, remotePath);
        if (relative == null || !isSafeRelativePath(relative)) {
            return null;
        }
        return new File(primaryRoot, relative);
    }

    /**
     * 远端路径去掉 {@code remoteRoot} 前缀之后的相对路径。
     *
     * <p>只做字符串归一（分隔符、首尾斜杠），不做 canonical 化：这层判据要能在没有文件系统的
     * 单测里跑。远端路径不在根目录之下时返回 {@code null}，而不是退回"只剩文件名" ——
     * 那会把 {@code /storage/emulated/0/IMG_1.HEIC} 之外的乱七八糟位置当成相册路径。
     */
    static String relativePath(String remoteRoot, String remotePath) {
        if (remotePath == null) {
            return null;
        }
        String path = stripSlashes(remotePath.replace('\\', '/'));
        if (path.isEmpty()) {
            return null;
        }
        String root = remoteRoot == null ? "" : stripSlashes(remoteRoot.replace('\\', '/'));
        if (root.isEmpty()) {
            return path;
        }
        if (path.equals(root) || !path.startsWith(root + "/")) {
            return null;
        }
        return path.substring(root.length() + 1);
    }

    private static String stripSlashes(String value) {
        String out = value == null ? "" : value.trim();
        while (out.startsWith("/")) {
            out = out.substring(1);
        }
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /** 与 {@code DownloadQueue.isSafeRelativePath} 同一套判据，保持两边对"能写到哪里"一致。 */
    static boolean isSafeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()
                || relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            return false;
        }
        for (String segment : relativePath.replace('\\', '/').split("/", -1)) {
            if (segment == null || segment.isEmpty() || ".".equals(segment)
                    || "..".equals(segment) || segment.indexOf('\0') >= 0) {
                return false;
            }
        }
        return true;
    }
}
