package com.tongluosao.gallerysync.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
}
