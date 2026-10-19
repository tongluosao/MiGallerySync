package com.tongluosao.gallerysync.core;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class DownloadQueuePreviewPathTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void previewIsTheSameNameInTheHiddenSlimDirectory() {
        File jpeg = DownloadQueue.previewPathFor(new File("album/photo.jpg"), false);
        File jpegLongExtension = DownloadQueue.previewPathFor(new File("album/photo.jpeg"), false);
        File png = DownloadQueue.previewPathFor(new File("album/photo.png"), false);
        File webp = DownloadQueue.previewPathFor(new File("album/photo.webp"), false);
        File bmp = DownloadQueue.previewPathFor(new File("album/photo.bmp"), false);
        assertEquals(new File("album/.gallerysync-slim/photo.jpg"), jpeg);
        assertEquals(new File("album/.gallerysync-slim/photo.jpg"), jpegLongExtension);
        assertEquals(new File("album/.gallerysync-slim/photo.jpg"), png);
        assertEquals(new File("album/.gallerysync-slim/photo.jpg"), webp);
        assertEquals(new File("album/.gallerysync-slim/photo.jpg"), bmp);
    }

    @Test public void heicPreviewSuffixFollowsThePreserveSetting() {
        File source = new File("album/IMG_0001.HEIC");
        assertEquals(new File("album/.gallerysync-slim/IMG_0001.heic"),
                DownloadQueue.previewPathFor(source, true));
        assertEquals(new File("album/.gallerysync-slim/IMG_0001.jpg"),
                DownloadQueue.previewPathFor(source, false));
        File heif = new File("album/IMG_0002.heif");
        assertEquals(new File("album/.gallerysync-slim/IMG_0002.heic"),
                DownloadQueue.previewPathFor(heif, true));
        assertEquals(new File("album/.gallerysync-slim/IMG_0002.jpg"),
                DownloadQueue.previewPathFor(heif, false));
    }

    @Test public void nonCompressibleTypesKeepTheOriginalPath() {
        File gif = new File("album/anim.gif");
        File video = new File("album/movie.mp4");
        assertEquals(gif, DownloadQueue.previewPathFor(gif, true));
        assertEquals(video, DownloadQueue.previewPathFor(video, true));
    }

    @Test public void previewPathIsDeterministicAndUniquePerPhoto() {
        File first = DownloadQueue.previewPathFor(new File("album/photo.png"), false);
        File second = DownloadQueue.previewPathFor(new File("album/photo.png"), false);
        assertEquals(first, second);
        assertNotEquals(first,
                DownloadQueue.previewPathFor(new File("album/other.png"), false));
        assertNotEquals(first, DownloadQueue.previewPathFor(new File("album2/photo.png"), false));
    }

    @Test public void slimDestinationKeepsThePhotoStemInsideTheHiddenDirectory() {
        // 空间优化与下载高清图都调用 previewPathFor，所以副本永远与照片同名、同目录
        File[] originals = {
                new File("album/IMG_0001.HEIC"),
                new File("album/photo.jpg"),
                new File("album/photo.png"),
                new File("album/photo.webp"),
        };
        for (File original : originals) {
            for (boolean preserveHeic : new boolean[]{true, false}) {
                File preview = DownloadQueue.previewPathFor(original, preserveHeic);
                assertEquals(new File("album/.gallerysync-slim"), preview.getParentFile());
                String stem = preview.getName().substring(0, preview.getName().lastIndexOf('.'));
                String originalStem = original.getName()
                        .substring(0, original.getName().lastIndexOf('.'));
                assertEquals(originalStem, stem);
            }
        }
    }

    @Test public void videoPosterLivesBesideItsVideoInTheSameDirectory() {
        assertEquals(new File("album/.gallerysync-slim/movie.jpg"),
                DownloadQueue.videoPosterPathFor(new File("album/movie.mp4")));
    }

    @Test public void legacySlimPathIsRecognised() {
        assertTrue(DownloadQueue.isLegacySlimPath("/storage/emulated/0/DCIM/Camera/.gallerysync-slim/a.jpg"));
        assertFalse(DownloadQueue.isLegacySlimPath("/storage/emulated/0/DCIM/Camera/a.jpg"));
        assertFalse(DownloadQueue.isLegacySlimPath(null));
    }

    @Test public void albumDownloadReturnsToTheOriginalAlbumPath() {
        File destination = DownloadQueue.destinationFor(Config.LAYOUT_ALBUM,
                "Pictures/taobao/missing.jpg", 123L);

        assertEquals(new File("/storage/emulated/0/Pictures/taobao/missing.jpg"), destination);
    }

    @Test public void dateAndFlatLayoutsStayInTheReservedMirror() {
        String relative = "2026/10/missing.jpg";

        assertEquals(new File("/storage/emulated/0/DCIM/MiGallerySync", relative),
                DownloadQueue.destinationFor(Config.LAYOUT_DATE, relative, 123L));
        assertEquals(new File("/storage/emulated/0/DCIM/MiGallerySync", relative),
                DownloadQueue.destinationFor(Config.LAYOUT_FLAT, relative, 123L));
    }

    @Test public void differentAlbumFileFallsBackToTheReservedMirror() throws IOException {
        File primaryRoot = temporaryFolder.newFolder("primary");
        File mirrorRoot = temporaryFolder.newFolder("mirror");
        File album = new File(primaryRoot, "Pictures/taobao/photo.jpg");
        assertTrue(album.getParentFile().mkdirs());
        Files.write(album.toPath(), new byte[]{1, 2, 3});

        File destination = DownloadQueue.destinationFor(Config.LAYOUT_ALBUM,
                "Pictures/taobao/photo.jpg", 9L, primaryRoot, mirrorRoot);

        assertEquals(new File(mirrorRoot, "Pictures/taobao/photo.jpg"), destination);
    }

    @Test public void sameSizeAlbumFileRemainsTheMatchingDestination() throws IOException {
        File primaryRoot = temporaryFolder.newFolder("primary");
        File mirrorRoot = temporaryFolder.newFolder("mirror");
        File album = new File(primaryRoot, "Pictures/taobao/photo.jpg");
        assertTrue(album.getParentFile().mkdirs());
        Files.write(album.toPath(), new byte[]{1, 2, 3});

        File destination = DownloadQueue.destinationFor(Config.LAYOUT_ALBUM,
                "Pictures/taobao/photo.jpg", 3L, primaryRoot, mirrorRoot);

        assertEquals(album, destination);
    }

    @Test public void legacyPreviewDoesNotOverrideCorrectAlbumDestination() throws IOException {
        File primaryRoot = temporaryFolder.newFolder("primary");
        File mirrorRoot = temporaryFolder.newFolder("mirror");
        File mirrorOriginal = new File(mirrorRoot, "Pictures/taobao/photo.jpg");
        File legacyPreview = new File(mirrorOriginal.getPath() + ".hq.jpg");
        assertTrue(legacyPreview.getParentFile().mkdirs());
        assertTrue(legacyPreview.createNewFile());

        File destination = DownloadQueue.destinationFor(Config.LAYOUT_ALBUM,
                "Pictures/taobao/photo.jpg", 9L, primaryRoot, mirrorRoot);

        assertEquals(new File(primaryRoot, "Pictures/taobao/photo.jpg"), destination);
    }

    @Test public void unsafeAlbumPathIsRejected() {
        assertFalse(DownloadQueue.isSafeRelativePath("../../outside.jpg"));
        assertFalse(DownloadQueue.isSafeRelativePath("/Pictures/outside.jpg"));
        assertFalse(DownloadQueue.isSafeRelativePath("Pictures//outside.jpg"));
    }
}
