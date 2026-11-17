package com.tongluosao.gallerysync.core;

import java.io.File;
import java.io.IOException;

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
 * <p>全部只依赖文件名/长度/注册表里的字符串，<b>纯函数</b>、不碰 Android、没有可变状态，
 * 每条分支都能在 JVM 单测里钉住（见 {@code DownloadDestinationPolicyTest}）。
 */
public final class DownloadDestinationPolicy {

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
        if (albumPath == null) {
            return mirrorPath;
        }
        File registered = registeredFile(registeredOriginalPath);
        if (registered != null && sameAlbumDirectory(registered, albumPath)) {
            // 账本已经把这一行绑到相册目录里的这份文件：回流到它，绝不再产第二份
            return registered;
        }
        if (sameNameSameLength) {
            return albumPath;
        }
        return albumPath.exists() ? mirrorPath : albumPath;
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
