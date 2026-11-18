package com.tongluosao.gallerysync.core;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import static org.junit.Assert.*;

/**
 * 第 4 波：高清副本的落点判据。
 *
 * <p>原地格式（jpg/jpeg 家族、png，以及 preserveHeic 打开时的 heic/heif）返回原文件本身 ——
 * 预览覆盖在原路径上；其余容器仍落在隐藏侧车目录（{@code .gallerysync-slim}），
 * 不可压缩的 gif/视频不产生副本。
 */
public class DownloadQueuePreviewPathTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void inplaceFormatsReturnTheOriginalPath() {
        File jpeg = new File("album/photo.jpg");
        File jpegLongExtension = new File("album/photo.jpeg");
        File heic = new File("album/IMG_0001.HEIC");
        File heif = new File("album/IMG_0002.heif");
        File png = new File("album/screenshot.png");

        assertEquals(jpeg, DownloadQueue.previewPathFor(jpeg, false));
        assertEquals(jpegLongExtension, DownloadQueue.previewPathFor(jpegLongExtension, false));
        // preserveHeic 只是编码器选择，落点仍是原路径
        assertEquals(heic, DownloadQueue.previewPathFor(heic, true));
        assertEquals(heif, DownloadQueue.previewPathFor(heif, true));
        // preserveHeic=false 不影响 jpg 家族原地覆盖
        assertEquals(jpeg, DownloadQueue.previewPathFor(jpeg, true));
        // PNG 有自己的无损编码器：无论“保留 HEIC”开不开都原地覆盖，且不会转成 jpg
        assertEquals(png, DownloadQueue.previewPathFor(png, true));
        assertEquals(png, DownloadQueue.previewPathFor(png, false));
    }

    @Test public void sidecarFormatsKeepTheHiddenDirectoryPreview() {
        File webp = DownloadQueue.previewPathFor(new File("album/photo.webp"), false);
        File bmp = DownloadQueue.previewPathFor(new File("album/photo.bmp"), false);
        assertEquals(new File("album/.gallerysync-slim/photo.jpg"), webp);
        assertEquals(new File("album/.gallerysync-slim/photo.jpg"), bmp);
    }

    @Test public void heicWithoutPreserveFallsBackToTheJpegSidecar() {
        File source = new File("album/IMG_0001.HEIC");
        assertEquals(new File("album/.gallerysync-slim/IMG_0001.jpg"),
                DownloadQueue.previewPathFor(source, false));
        File heif = new File("album/IMG_0002.heif");
        assertEquals(new File("album/.gallerysync-slim/IMG_0002.jpg"),
                DownloadQueue.previewPathFor(heif, false));
    }

    @Test public void nonCompressibleTypesKeepTheOriginalPath() {
        // gif/视频没有可写的预览编码器，依旧“不产生副本”（第 3 波起的既有行为，本波不改）
        File gif = new File("album/anim.gif");
        File video = new File("album/movie.mp4");
        assertEquals(gif, DownloadQueue.previewPathFor(gif, true));
        assertEquals(video, DownloadQueue.previewPathFor(video, true));
        assertEquals(video, DownloadQueue.previewPathFor(video, false));
    }

    @Test public void legacySidecarPathIsStillAvailableForMigration() {
        // migrateLegacyDownload 用它在老副本的位置上找字节；新链路不得调用
        assertEquals(new File("album/.gallerysync-slim/photo.jpg"),
                DownloadQueue.legacyPreviewPathFor(new File("album/photo.jpg"), false));
        assertEquals(new File("album/.gallerysync-slim/IMG_0001.heic"),
                DownloadQueue.legacyPreviewPathFor(new File("album/IMG_0001.HEIC"), true));
        assertEquals(new File("album/.gallerysync-slim/IMG_0001.jpg"),
                DownloadQueue.legacyPreviewPathFor(new File("album/IMG_0001.HEIC"), false));
        File video = new File("album/movie.mp4");
        assertEquals(video, DownloadQueue.legacyPreviewPathFor(video, true));
    }

    @Test public void previewPathIsDeterministicAndUniquePerPhoto() {
        File first = DownloadQueue.previewPathFor(new File("album/photo.webp"), false);
        File second = DownloadQueue.previewPathFor(new File("album/photo.webp"), false);
        assertEquals(first, second);
        assertNotEquals(first,
                DownloadQueue.previewPathFor(new File("album/other.webp"), false));
        assertNotEquals(first, DownloadQueue.previewPathFor(new File("album2/photo.webp"), false));
        // 原地格式同样幂等：同一张 png 永远得到它自己的路径
        File png = new File("album/photo.png");
        assertEquals(png, DownloadQueue.previewPathFor(png, false));
        assertEquals(png, DownloadQueue.previewPathFor(png, true));
    }

    @Test public void slimDestinationKeepsThePhotoStemInsideTheHiddenDirectory() {
        // 只有侧车格式才进隐藏目录，并且与原图同 stem
        File[] originals = {
                new File("album/IMG_0001.HEIC"),
                new File("album/photo.webp"),
                new File("album/photo.bmp"),
        };
        for (File original : originals) {
            File preview = DownloadQueue.previewPathFor(original, false);
            assertEquals(new File("album/.gallerysync-slim"), preview.getParentFile());
            String stem = preview.getName().substring(0, preview.getName().lastIndexOf('.'));
            String originalStem = original.getName()
                    .substring(0, original.getName().lastIndexOf('.'));
            assertEquals(originalStem, stem);
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

    @Test public void registeredOriginalWinsOverTheLengthMismatch() throws IOException {
        File primaryRoot = temporaryFolder.newFolder("primary");
        File mirrorRoot = temporaryFolder.newFolder("mirror");
        File album = new File(primaryRoot, "Pictures/taobao/photo.jpg");
        assertTrue(album.getParentFile().mkdirs());
        // 原图已被同名预览顶替：长度远小于远端对象
        Files.write(album.toPath(), new byte[]{1, 2, 3});

        File destination = DownloadQueue.destinationFor(Config.LAYOUT_ALBUM,
                "Pictures/taobao/photo.jpg", 900L, primaryRoot, mirrorRoot,
                album.getAbsolutePath());

        assertEquals(album, destination);
    }

    @Test public void unregisteredLengthMismatchStillGoesToTheMirror() throws IOException {
        File primaryRoot = temporaryFolder.newFolder("primary");
        File mirrorRoot = temporaryFolder.newFolder("mirror");
        File album = new File(primaryRoot, "Pictures/taobao/photo.jpg");
        assertTrue(album.getParentFile().mkdirs());
        Files.write(album.toPath(), new byte[]{1, 2, 3});

        File destination = DownloadQueue.destinationFor(Config.LAYOUT_ALBUM,
                "Pictures/taobao/photo.jpg", 900L, primaryRoot, mirrorRoot, null);

        assertEquals(new File(mirrorRoot, "Pictures/taobao/photo.jpg"), destination);
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
