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

    @Test public void jpegPreviewKeepsOriginalNameAndOtherFormatsUseJpegExtension() {
        File jpeg = DownloadQueue.previewPathFor(new File("album/photo.jpg"));
        File jpegLongExtension = DownloadQueue.previewPathFor(new File("album/photo.jpeg"));
        File png = DownloadQueue.previewPathFor(new File("album/photo.png"));
        File webp = DownloadQueue.previewPathFor(new File("album/photo.webp"));
        File heic = new File("album/photo.heic");
        assertEquals(new File("album/photo.jpg"), jpeg);
        assertEquals(new File("album/photo.jpeg"), jpegLongExtension);
        assertEquals("photo.jpg", png.getName());
        assertEquals("photo.jpg", webp.getName());
        assertEquals(heic, DownloadQueue.previewPathFor(heic));
        assertEquals(new File("album"), png.getParentFile());
    }

    @Test public void nonJpegPreviewCollisionIsReportedWithoutTreatingJpegAsCollision()
            throws IOException {
        File directory = temporaryFolder.newFolder("collision");
        File png = new File(directory, "photo.png");
        File collidingJpeg = new File(directory, "photo.jpg");
        assertTrue(png.createNewFile());
        assertTrue(collidingJpeg.createNewFile());

        assertTrue(DownloadQueue.previewPathOccupiedByAnotherFile(png));
        assertFalse(DownloadQueue.previewPathOccupiedByAnotherFile(collidingJpeg));
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
