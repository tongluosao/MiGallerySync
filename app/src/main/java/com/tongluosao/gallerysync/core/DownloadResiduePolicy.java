package com.tongluosao.gallerysync.core;

/**
 * 下载临时件的清理判据（纯函数）。
 *
 * <p>下载与"原地瘦身"共用同一套同目录临时命名，写完后原子改名到目标：
 *
 * <ul>
 *   <li>{@code .<目标名>.mgs-<uuid>.tmp} —— {@code DownloadQueue.stageBesideDestination}</li>
 *   <li>{@code .mgs-tmp-<uuid>} —— 原地预览写到相册目录里的临时件</li>
 * </ul>
 *
 * <p>正常失败路径会删掉它们，但<b>进程被杀</b>（国产 ROM 上很常见）时没人收尾。这些名字
 * 以点开头，媒体扫描器看不见，于是它们既不会出现在相册里，也不会被任何清理逻辑扫到，
 * 只会安静地把相册目录越占越满。
 *
 * <p>清理不能"见到点文件就删"：同一目录里可能还有<b>别的下载路</b>正在写的临时件。
 * 所以判据只有两条 —— 名字形状对得上、且足够旧（{@link #MIN_AGE_MS}，远大于一次
 * 355KB 下载的耗时）。两条都满足才删，任何一条不满足都当作"别人的、还在写的"。
 */
public final class DownloadResiduePolicy {

    /** 只清理这么久以前（按 mtime）的临时件，避免删掉正在写入的那一份。 */
    public static final long MIN_AGE_MS = 10L * 60_000L;

    private DownloadResiduePolicy() {
    }

    /**
     * 这个名字是不是"目标文件的下载/瘦身临时件"。
     *
     * @param candidate 目录里读到的文件名
     * @param destName  本次要发布到的目标文件名（例如 {@code Screenshot_1.jpg}）
     */
    public static boolean isStagingNameFor(String candidate, String destName) {
        if (candidate == null || destName == null || destName.isEmpty()) {
            return false;
        }
        if (!candidate.startsWith(".")) {
            return false;
        }
        String rest = candidate.substring(1);
        if (rest.startsWith("mgs-tmp-")) {
            // 原地预览的临时件不带目标名（写入引擎自己在同一目录里建的），形状对上就算
            return rest.length() > "mgs-tmp-".length();
        }
        if (!rest.startsWith(destName + ".mgs-")) {
            return false;
        }
        return rest.length() > destName.length() + ".mgs-".length();
    }

    /**
     * 这一刻能不能删。
     *
     * @param candidate 目录里读到的文件名
     * @param destName  本次要发布到的目标文件名
     * @param nowMs     当前时刻（{@code System.currentTimeMillis()}）
     * @param mtimeMs   该文件的修改时刻；读不到（0 或负数）一律不删
     */
    public static boolean isSweepable(String candidate, String destName, long nowMs, long mtimeMs) {
        if (!isStagingNameFor(candidate, destName)) {
            return false;
        }
        if (mtimeMs <= 0L || nowMs <= 0L) {
            return false;
        }
        long age = nowMs - mtimeMs;
        if (age < 0L) {
            // mtime 在未来（时钟抖动）：判断不可靠，宁可留着
            return false;
        }
        return age >= MIN_AGE_MS;
    }
}
