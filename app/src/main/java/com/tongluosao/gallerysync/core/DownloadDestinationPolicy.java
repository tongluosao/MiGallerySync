package com.tongluosao.gallerysync.core;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * 下载落点判据：这份远端字节该写回相册原路径，还是分流到保留镜像目录。
 *
 * <p>背景：历史上 {@code destinationFor} 只看“相册里有没有同名同长文件”。空间优化把原路径上的
 * 原图换成同名的预览之后，长度必然对不上，于是下一次下载被判成“那里是别的东西”，改写到
 * {@code DCIM/MiGallerySync/...}。结果就是同一张照片在本地有两份文件、相册里多出两行 ——
 * 这正是要修掉的根因。
 *
 * <p>所以判据里多了一条<b>注册表优先</b>：只要这一行在注册表里登记过相册目录下的原路径，
 * 落点就是那条路径，长度对不对都不影响。长度只用来判断“未登记时的既存同名文件是不是同一份”。
 *
 * <p>接管行（{@code REMOTE_ONLY}）是个例外：它们由“接管远端”新建，注册表里既没有原路径也没有
 * 派生路径，可相册目录里往往已经躺着一份同名但更小的预览/占位副本（系统自己生成的）。老判据看见
 * “同名不同长”就分流镜像，于是这些接管行每张都多出一份镜像副本。远端路径本身就带着相册信息
 * （{@code <remoteRoot>/<relative>}），所以这里补一条<b>远端相对路径</b>判据：没有登记原路径时，
 * 把相对路径映射到 {@code /storage/emulated/0/<relative>}；只有那条路径被<b>另一份不同字节的文件</b>
 * 占着才退回镜像。被我们自己的占位副本占着时，落点仍是相册路径，并在原地顶掉那份副本
 * （{@link Decision#replacesExistingAlbumFile}）。
 *
 * <p>全部只依赖文件名/长度/注册表里的字符串，<b>纯函数</b>、不碰 Android、没有可变状态，
 * 每条分支都能在 JVM 单测里钉住（见 {@code DownloadDestinationPolicyTest}）。
 */
public final class DownloadDestinationPolicy {

    /** 落点属于哪一侧，只用于日志统计（{@code dest_kind=album|mirror}）。 */
    public enum Kind {
        ALBUM("album"),
        MIRROR("mirror");

        /** 日志里用的字面值。 */
        public final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        @Override
        public String toString() {
            return wire;
        }
    }

    /** 一次判定：落到哪、属于哪一侧、是否需要顶掉相册里已有的占位副本。 */
    public static final class Decision {
        /** 目标文件，可能为空（镜像路径缺失时）。 */
        public final File path;
        public final Kind kind;
        /**
         * 目标路径上已有一份不同字节的占位副本（接管行的瘦身预览），这次要原地顶掉它。
         *
         * <p>只有"相册路径 + 该行是 REMOTE_ONLY + 尺寸与远端一致 + 既有文件更小 + 图片扩展名"
         * 同时成立才为 true；用户自己改过的同名文件永远不会满足，所以它不会变成"覆盖用户文件"。
         */
        public final boolean replacesExistingAlbumFile;

        Decision(File path, Kind kind, boolean replacesExistingAlbumFile) {
            this.path = path;
            this.kind = kind;
            this.replacesExistingAlbumFile = replacesExistingAlbumFile;
        }

        public boolean album() {
            return kind == Kind.ALBUM;
        }
    }

    private static final Kind ALBUM = Kind.ALBUM;
    private static final Kind MIRROR = Kind.MIRROR;

    /** 可能被原地顶掉的占位副本扩展名（相册/系统生成的预览都是这些容器）。 */
    private static final String[] STAND_IN_EXTENSIONS = {
            ".jpg", ".jpeg", ".png", ".heic", ".heif", ".webp",
    };

    private DownloadDestinationPolicy() {
    }

    /**
     * 决定下载落点。
     *
     * <p>判定顺序（先看“账本认不认”，再看“盘上有什么”）：
     * <ol>
     *   <li>不能映射回相册（非 album 布局、相对路径不安全、没有候选路径）→ 镜像目录；</li>
     *   <li>注册表里已登记这个相册目录下的原路径 → 那条路径（{@code sameNameSameLength} 不再参与）；</li>
     *   <li>相册里已有同名同长文件 → 它（既存的那份就是同一张照片）；</li>
     *   <li>相册里那份同名文件是本记录登记过的派生副本 → 它（原地替换，不另存镜像）；</li>
     *   <li>相册里是接管行的占位副本（更小的图片）→ 它，并要求原地顶掉；</li>
     *   <li>相册里已有同名但长度不同的文件、且没有任何登记 → 镜像目录（不覆盖别人的文件）；</li>
     *   <li>相册里没有同名文件 → 相册路径。</li>
     * </ol>
     *
     * @param albumPath              按远端相对路径还原出来的相册候选路径，可空
     * @param mirrorPath             保留镜像目录下的同名路径
     * @param sameNameSameLength     相册里已有同名文件，且字节数与远端对象一致
     * @param registeredOriginalPath 注册表里这一行登记的原图本地路径，可空
     */
    public static File resolve(File albumPath, File mirrorPath, boolean sameNameSameLength,
            String registeredOriginalPath) {
        return resolve(albumPath, mirrorPath, sameNameSameLength, registeredOriginalPath, false);
    }

    /**
     * 决定下载落点，并把"相册里那份同名文件是本记录自己登记过的副本"当作回流条件。
     *
     * <p>为什么需要第五个参数：原地瘦身会把原图换成<b>同名同路径</b>的预览，长度必然短于
     * 远端对象。此时前三条判据全部落空（没登记成原路径 —— 因为登记的是"原图在别处/已瘦身"，
     * 长度也不同），于是老逻辑改写到镜像目录，同一张照片出现第二份文件、相册多出第二行。
     * 但那份字节<b>是我们自己写的副本</b>，正确动作是"把原图写回它、顶掉预览"，而不是另存。
     *
     * <p>参数只在调用方已经按注册表确认过（{@code isRegisteredDerivedPath}）时才置 true，
     * 用户自己的同名文件永远不会走到这条分支。
     */
    public static File resolve(File albumPath, File mirrorPath, boolean sameNameSameLength,
            String registeredOriginalPath, boolean albumIsRegisteredDerived) {
        return decide(albumPath, mirrorPath, -1L, registeredOriginalPath,
                albumIsRegisteredDerived, sameNameSameLength, false).path;
    }

    /**
     * 由远端相对路径推相册路径的完整判定（下载链路的正式入口）。
     *
     * <p>{@code relativePath} 是远端路径去掉 {@code <remoteRoot>/} 之后的部分，形如
     * {@code DCIM/Screenshots/xxx.jpg}；映射结果是 {@code /storage/emulated/0/<relativePath>}，
     * 与远端"相册内位置"一一对应。相对路径为空、含 {@code ..}、绝对路径、Windows 盘符等
     * 不安全形状一律退回镜像目录，绝不拼出相册之外的路径。
     *
     * @param relativePath           远端相对路径，可空
     * @param primaryRoot            相册根，通常是 {@code /storage/emulated/0}
     * @param mirrorRoot             保留镜像根，通常是 {@code /storage/emulated/0/DCIM/MiGallerySync}
     * @param remoteSize             远端对象字节数，未知传 -1
     * @param registeredOriginalPath 注册表里这一行登记的原图本地路径，可空
     * @param albumIsRegisteredDerived 相册路径上那份文件是本记录登记过的派生副本
     * @param recordRemoteOnly       这一行是接管行（注册表里只有远端，没有本地原图）
     * @param registeredLength       注册表里这一行期望的原图字节数，未知传 -1
     */
    public static Decision fromRelativePath(String relativePath, File primaryRoot, File mirrorRoot,
            long remoteSize, String registeredOriginalPath, boolean albumIsRegisteredDerived,
            boolean recordRemoteOnly, long registeredLength) {
        File album = albumPath(primaryRoot, relativePath);
        File mirror = mirrorRoot == null || !isMappable(relativePath)
                ? null : new File(mirrorRoot, relativePath);
        if (album == null) {
            return new Decision(mirror, MIRROR, false);
        }
        boolean sameNameSameLength = album.isFile() && remoteSize >= 0L
                && album.length() == remoteSize;
        boolean albumHoldsStandIn = recordRemoteOnly && remoteSize > 0L
                && registeredLength == remoteSize && isStandInCandidate(album, remoteSize);
        return decide(album, mirror, remoteSize, registeredOriginalPath, albumIsRegisteredDerived,
                sameNameSameLength, albumHoldsStandIn);
    }

    /** 由远端相对路径还原相册路径；不安全或为空时返回 null。 */
    public static File albumPath(File primaryRoot, String relativePath) {
        if (primaryRoot == null || !isMappable(relativePath)) {
            return null;
        }
        return new File(primaryRoot, relativePath);
    }

    /** 直接把落点钉成镜像目录（非 album 布局等场景）。 */
    public static Decision mirrorDecision(File mirrorPath) {
        return new Decision(mirrorPath, MIRROR, false);
    }

    /** 把落点钉成相册路径。 */
    public static Decision albumDecision(File albumPath) {
        return new Decision(albumPath, ALBUM, false);
    }

    /**
     * 纯判定核心：{@code album}/{@code mirror} 都已经是算好的候选路径。
     *
     * @param albumHoldsStandIn 相册路径上那份文件是接管行的占位副本（更小），可以原地顶掉
     */
    static Decision decide(File album, File mirror, long remoteSize,
            String registeredOriginalPath, boolean albumIsRegisteredDerived,
            boolean sameNameSameLength, boolean albumHoldsStandIn) {
        if (album == null) {
            return new Decision(mirror, MIRROR, false);
        }
        File registered = registeredFile(registeredOriginalPath);
        if (registered != null && sameAlbumDirectory(registered, album)) {
            // 账本已经把这一行绑到相册目录里的这份文件：回流到它，绝不再产第二份
            return new Decision(registered, ALBUM, false);
        }
        if (sameNameSameLength) {
            return new Decision(album, ALBUM, false);
        }
        if (albumIsRegisteredDerived && album.isFile()) {
            // 相册里那份同名文件是本记录登记过的派生副本：写回它（原地替换），不另存镜像
            return new Decision(album, ALBUM, false);
        }
        if (albumHoldsStandIn && album.isFile() && !sameNameSameLength) {
            // 接管行的占位副本：尺寸和远端一致、本地这份更小 —— 同一张照片的预览，顶掉它
            return new Decision(album, ALBUM, true);
        }
        return album.exists() ? new Decision(mirror, MIRROR, false)
                : new Decision(album, ALBUM, false);
    }

    /**
     * 相册路径上那份文件是否像"同一张照片的占位副本"：普通文件、非空、图片扩展名、
     * 且比远端对象<b>小</b>（大了说明是另一份更清楚的版本，绝不能顶掉）。
     */
    private static boolean isStandInCandidate(File album, long remoteSize) {
        if (!album.isFile() || album.length() <= 0L || album.length() >= remoteSize) {
            return false;
        }
        return isImageName(album.getName());
    }

    static boolean isImageName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : STAND_IN_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 相对路径是否可以映射到相册：非空、非绝对、无 {@code ..}、无 {@code //} 与反斜杠。
     *
     * <p>与 {@link AutoDownloadPolicy#isSafeRelativePath} 同一口径；这里再判一次，避免调用方
     * 漏判时拼出相册之外的路径。
     */
    static boolean isMappable(String relativePath) {
        return AutoDownloadPolicy.isSafeRelativePath(relativePath);
    }

    /**
     * 注册表里的路径是否就在候选相册路径所在的目录（同路径也算）。
     *
     * <p>只比目录而不比文件名：改名之后的照片仍是同一行，落点应该跟着账本走，而不是按新
     * 文件名在相册里另起一份。canonical 解析失败时退回绝对路径比较。
     */
    static boolean sameAlbumDirectory(File registered, File albumPath) {
        if (sameFile(registered, albumPath)) {
            return true;
        }
        File registeredParent = canonicalParent(registered);
        File albumParent = canonicalParent(albumPath);
        return registeredParent != null && registeredParent.equals(albumParent);
    }

    private static File registeredFile(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.trim();
        return trimmed.isEmpty() ? null : new File(trimmed);
    }

    private static File canonicalParent(File file) {
        try {
            return file.getCanonicalFile().getParentFile();
        } catch (IOException ignored) {
            return file.getAbsoluteFile().getParentFile();
        }
    }

    private static boolean sameFile(File first, File second) {
        try {
            return first.getCanonicalPath().equals(second.getCanonicalPath());
        } catch (IOException ignored) {
            return first.getAbsoluteFile().equals(second.getAbsoluteFile());
        }
    }
}
