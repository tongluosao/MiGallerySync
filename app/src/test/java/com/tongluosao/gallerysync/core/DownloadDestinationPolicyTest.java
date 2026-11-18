package com.tongluosao.gallerysync.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 下载落点判据（第 4 波）：注册表命中必须回流到相册原路径，未登记才允许分流镜像。
 *
 * <p>历史上“同名不同长就改写镜像”是产生第二份文件/第二行的根因，这里把每条分支钉住。
 */
public class DownloadDestinationPolicyTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File album() throws IOException {
        File primary = temporaryFolder.newFolder("primary");
        File album = new File(primary, "Pictures/taobao/photo.jpg");
        assertTrue(album.getParentFile().mkdirs());
        Files.write(album.toPath(), new byte[]{1, 2, 3});
        return album;
    }

    private File mirror() throws IOException {
        return new File(temporaryFolder.newFolder("mirror"), "Pictures/taobao/photo.jpg");
    }

    @Test public void registeredOriginalWinsEvenWhenLengthDiffers() throws IOException {
        File album = album();
        File mirror = mirror();

        File resolved = DownloadDestinationPolicy.resolve(album, mirror, false,
                album.getAbsolutePath());

        assertEquals(album, resolved);
    }

    @Test public void registeredOriginalWinsWithoutAnyLocalFile() throws IOException {
        File album = album();
        File mirror = mirror();
        assertTrue(album.delete());

        File resolved = DownloadDestinationPolicy.resolve(album, mirror, false,
                album.getAbsolutePath());

        assertEquals(album, resolved);
    }

    @Test public void unregisteredSameNameDifferentLengthGoesToTheMirror() throws IOException {
        File album = album();
        File mirror = mirror();

        File resolved = DownloadDestinationPolicy.resolve(album, mirror, false, null);

        assertEquals(mirror, resolved);
    }

    @Test public void unregisteredSameNameSameLengthStaysInTheAlbum() throws IOException {
        File album = album();
        File mirror = mirror();

        File resolved = DownloadDestinationPolicy.resolve(album, mirror, true, null);

        assertEquals(album, resolved);
    }

    @Test public void missingAlbumFileWithoutRegistrationStaysInTheAlbum() throws IOException {
        File primary = temporaryFolder.newFolder("primary");
        File album = new File(primary, "Pictures/taobao/missing.jpg");
        File mirror = new File(temporaryFolder.newFolder("mirror"), "Pictures/taobao/missing.jpg");

        File resolved = DownloadDestinationPolicy.resolve(album, mirror, false, "");

        assertEquals(album, resolved);
    }

    @Test public void renamedFileInTheRegisteredAlbumDirectoryWins() throws IOException {
        File album = album();
        File mirror = mirror();
        File renamed = new File(album.getParentFile(), "photo-renamed.jpg");

        File resolved = DownloadDestinationPolicy.resolve(album, mirror, false,
                renamed.getAbsolutePath());

        assertEquals(renamed, resolved);
    }

    @Test public void registrationOutsideTheAlbumDirectoryDoesNotRedirect() throws IOException {
        File album = album();
        File mirror = mirror();
        File elsewhere = new File(temporaryFolder.newFolder("elsewhere"), "photo.jpg");
        Files.write(elsewhere.toPath(), new byte[]{7});

        File resolved = DownloadDestinationPolicy.resolve(album, mirror, false,
                elsewhere.getAbsolutePath());

        // 账本记的是别的目录里的副本：既存同名文件长度不一致，仍按老规则分流镜像
        assertEquals(mirror, resolved);
    }

    @Test public void mirrorRegistrationDoesNotDragAlbumDownloadsToTheMirror() throws IOException {
        File album = album();
        File mirror = mirror();
        File mirrorCopy = new File(mirror.getParentFile(), "photo.jpg");
        assertTrue(mirrorCopy.getParentFile().mkdirs());
        Files.write(mirrorCopy.toPath(), new byte[]{1, 2, 3});

        File resolved = DownloadDestinationPolicy.resolve(album, mirror, false,
                mirrorCopy.getAbsolutePath());

        assertEquals(mirror, resolved);
    }

    @Test public void nullAlbumPathResolvesToTheMirror() throws IOException {
        File mirror = mirror();

        assertEquals(mirror, DownloadDestinationPolicy.resolve(null, mirror, true, null));
    }

    @Test public void blankRegistrationIsIgnored() throws IOException {
        File album = album();
        File mirror = mirror();

        assertEquals(album, DownloadDestinationPolicy.resolve(album, mirror, true, "   "));
        assertEquals(mirror, DownloadDestinationPolicy.resolve(album, mirror, false, ""));
    }

    @Test public void sameAlbumDirectoryComparesCanonicalParents() throws IOException {
        File album = album();
        File sameDir = new File(album.getParentFile(), "other.jpg");
        File otherDir = new File(temporaryFolder.newFolder("other"), "photo.jpg");

        assertTrue(DownloadDestinationPolicy.sameAlbumDirectory(sameDir, album));
        assertTrue(DownloadDestinationPolicy.sameAlbumDirectory(album, album));
        assertFalse(DownloadDestinationPolicy.sameAlbumDirectory(otherDir, album));
    }

    // ------------------------------------------------------------------------------------
    // 接管行（REMOTE_ONLY）：注册表里没有 localFile/albumId，远端相对路径就是相册内位置。
    // 相册目录里那份同名但更小的文件是系统生成的占位副本 —— 落点仍是相册，并原地顶掉它。
    // ------------------------------------------------------------------------------------

    private File primaryRoot() throws IOException {
        return temporaryFolder.newFolder("album-root-" + nextId());
    }

    private File mirrorRoot() throws IOException {
        return temporaryFolder.newFolder("mirror-root-" + nextId());
    }

    private static int sId;

    private static synchronized int nextId() {
        return ++sId;
    }

    private static void write(File file, int length) throws IOException {
        assertTrue(file.getParentFile().mkdirs());
        Files.write(file.toPath(), new byte[length]);
    }

    @Test public void relativePathMapsBackToTheAlbumWhenNothingIsRegistered() throws IOException {
        File primary = primaryRoot();
        File mirrorRoot = mirrorRoot();

        DownloadDestinationPolicy.Decision decision =
                DownloadDestinationPolicy.fromRelativePath("DCIM/Screenshots/a.jpg", primary,
                        mirrorRoot, 1340898L, null, false, true, 1340898L);

        assertEquals(new File(primary, "DCIM/Screenshots/a.jpg"), decision.path);
        assertTrue(decision.album());
        assertEquals("album", decision.kind.wire);
        assertFalse(decision.replacesExistingAlbumFile);
    }

    @Test public void takeoverStandInPreviewIsReplacedInPlace() throws IOException {
        File primary = primaryRoot();
        File album = new File(primary, "DCIM/Screenshots/a.jpg");
        write(album, 300);

        DownloadDestinationPolicy.Decision decision =
                DownloadDestinationPolicy.fromRelativePath("DCIM/Screenshots/a.jpg", primary,
                        mirrorRoot(), 1000L, null, false, true, 1000L);

        // 占位副本：同名、图片、比远端小、注册表期望长度与远端一致 → 相册 + 原地顶掉
        assertEquals(album, decision.path);
        assertTrue(decision.album());
        assertTrue(decision.replacesExistingAlbumFile);
    }

    @Test public void sameByteAlbumFileIsReusedWithoutReplacing() throws IOException {
        File primary = primaryRoot();
        File album = new File(primary, "DCIM/Camera/a.heic");
        write(album, 1000);

        DownloadDestinationPolicy.Decision decision =
                DownloadDestinationPolicy.fromRelativePath("DCIM/Camera/a.heic", primary,
                        mirrorRoot(), 1000L, null, false, true, 1000L);

        assertEquals(album, decision.path);
        assertTrue(decision.album());
        assertFalse("同字节不是顶掉，是复用", decision.replacesExistingAlbumFile);
    }

    @Test public void standInLargerThanTheRemoteFallsBackToTheMirror() throws IOException {
        File primary = primaryRoot();
        File mirrorRoot = mirrorRoot();
        File album = new File(primary, "DCIM/Screenshots/a.jpg");
        write(album, 2000);

        DownloadDestinationPolicy.Decision decision =
                DownloadDestinationPolicy.fromRelativePath("DCIM/Screenshots/a.jpg", primary,
                        mirrorRoot, 1000L, null, false, true, 1000L);

        // 比远端还大：不是"同一张照片的占位副本"，按不同字节文件处理
        assertFalse(decision.album());
        assertEquals(new File(mirrorRoot, "DCIM/Screenshots/a.jpg"), decision.path);
    }

    @Test public void nonImageStandInFallsBackToTheMirror() throws IOException {
        File primary = primaryRoot();
        File mirrorRoot = mirrorRoot();
        File album = new File(primary, "DCIM/Screenshots/notes.txt");
        write(album, 300);

        DownloadDestinationPolicy.Decision decision =
                DownloadDestinationPolicy.fromRelativePath("DCIM/Screenshots/notes.txt", primary,
                        mirrorRoot, 1000L, null, false, true, 1000L);

        assertFalse(decision.album());
        assertEquals(new File(mirrorRoot, "DCIM/Screenshots/notes.txt"), decision.path);
    }

    @Test public void differentBytesForANonTakeoverRowFallBackToTheMirror() throws IOException {
        File primary = primaryRoot();
        File mirrorRoot = mirrorRoot();
        File album = new File(primary, "DCIM/Screenshots/a.jpg");
        write(album, 300);

        DownloadDestinationPolicy.Decision decision =
                DownloadDestinationPolicy.fromRelativePath("DCIM/Screenshots/a.jpg", primary,
                        mirrorRoot, 1000L, null, false, false, 1000L);

        // 不是接管行、注册表也没登记这条原路径：既有不同字节文件不覆盖，分流镜像
        assertFalse(decision.album());
        assertEquals(new File(mirrorRoot, "DCIM/Screenshots/a.jpg"), decision.path);
    }

    @Test public void registeredOriginalOutsideTheAlbumDoesNotRedirect() throws IOException {
        File primary = primaryRoot();
        File mirrorRoot = mirrorRoot();
        File album = new File(primary, "DCIM/Camera/a.heic");
        write(album, 10);
        File registered = new File(primary, "Pictures/renamed.heic");
        write(registered, 20);

        DownloadDestinationPolicy.Decision decision =
                DownloadDestinationPolicy.fromRelativePath("DCIM/Camera/a.heic", primary,
                        mirrorRoot, 1000L, registered.getAbsolutePath(), false, false, 1000L);

        // 账本记的是别的目录里的副本：与老 resolve 一致，只认"同一相册目录"，这里仍分流镜像
        assertFalse(decision.album());
        assertEquals(new File(mirrorRoot, "DCIM/Camera/a.heic"), decision.path);
    }

    @Test public void unsafeRelativePathsFallBackToTheMirror() throws IOException {
        File primary = primaryRoot();
        File mirrorRoot = mirrorRoot();

        String[] unsafe = {null, "", "/abs/a.jpg", "\\abs\\a.jpg", "../a.jpg",
                "DCIM/../../a.jpg", "DCIM//a.jpg", "DCIM/./a.jpg", "DCIM/a\0.jpg"};
        for (String relative : unsafe) {
            DownloadDestinationPolicy.Decision decision =
                    DownloadDestinationPolicy.fromRelativePath(relative, primary, mirrorRoot,
                            1000L, null, false, true, 1000L);
            assertFalse("相对路径不合法就不能映射到相册: " + relative, decision.album());
        }
        // 越界路径绝不能拼出相册之外的落点
        assertNull(DownloadDestinationPolicy.albumPath(primary, "../a.jpg"));
        assertNull(DownloadDestinationPolicy.albumPath(primary, null));
        assertEquals(new File(primary, "DCIM/a.jpg"),
                DownloadDestinationPolicy.albumPath(primary, "DCIM/a.jpg"));
    }

    @Test public void mirrorDecisionKeepsTheMirrorPathAndWireValue() {
        File mirror = new File("/storage/emulated/0/DCIM/MiGallerySync/DCIM/Camera/a.heic");

        DownloadDestinationPolicy.Decision decision =
                DownloadDestinationPolicy.mirrorDecision(mirror);

        assertEquals(mirror, decision.path);
        assertFalse(decision.album());
        assertEquals("mirror", decision.kind.wire);
        assertFalse(decision.replacesExistingAlbumFile);
    }
}
