package com.tongluosao.gallerysync.core;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * "某个落盘路径能不能交给宿主扫描器"这条纯逻辑。
 *
 * <p>设备实证：质量下载把预览发布到相册目录下的隐藏目录后，如果仍把它交给 {@code ScannerEngine}，
 * 相册会在原图行之外多出一行"副本"（cloud 表 _id=15989/15991，localFile 指向
 * {@code .gallerysync-slim/IMG_*.heic}）。所以隐藏目录里的路径必须被判为"不许扫"。
 */
public class DownloadQueueHostScanGateTest {

    @Test public void albumDirectoryFileMayBeScanned() {
        assertTrue(DownloadQueue.hostScanAllowed(
                "/storage/emulated/0/DCIM/Camera/IMG_20261019_120142.HEIC"));
        assertTrue(DownloadQueue.hostScanAllowed("/storage/emulated/0/DCIM/Camera/photo.jpg"));
        // 相册目录本身也给宿主（不代表文件，但这里关心的是"不在隐藏副本目录里"）
        assertTrue(DownloadQueue.hostScanAllowed("/storage/emulated/0/DCIM/Camera"));
    }

    @Test public void slimPreviewIsNeverHandedToTheHostScanner() {
        assertFalse(DownloadQueue.hostScanAllowed(
                "/storage/emulated/0/DCIM/Camera/.gallerysync-slim/IMG_20261019_120142.heic"));
        assertFalse(DownloadQueue.hostScanAllowed(
                "album/.gallerysync-slim/photo.jpg"));
        assertFalse(DownloadQueue.hostScanAllowed(
                "/storage/emulated/0/DCIM/MiGallerySync/.gallerysync-slim/a.jpg"));
    }

    @Test public void backslashSeparatedAndMixedCaseSlimPathsAreAlsoRejected() {
        assertFalse(DownloadQueue.hostScanAllowed(
                "D:\\COD\\album\\.gallerysync-slim\\IMG_0001.heic"));
        assertFalse(DownloadQueue.hostScanAllowed(
                "/storage/emulated/0/DCIM/Camera/.GallerySync-Slim/IMG_0001.HEIC"));
        assertFalse(DownloadQueue.hostScanAllowed(
                "/storage/emulated/0/DCIM/Camera/.GALLERYSYNC-SLIM/photo.jpg"));
    }

    @Test public void slimDirectoryAtTheEndOfThePathIsRejectedToo() {
        assertFalse(DownloadQueue.hostScanAllowed(
                "/storage/emulated/0/DCIM/Camera/.gallerysync-slim/"));
        assertFalse(DownloadQueue.hostScanAllowed(
                "/storage/emulated/0/DCIM/Camera/.gallerysync-slim"));
    }

    @Test public void emptyPathIsNeverScanned() {
        assertFalse(DownloadQueue.hostScanAllowed(null));
        assertFalse(DownloadQueue.hostScanAllowed(""));
    }

    @Test public void namesThatMerelyMentionTheDirectoryStillMayBeScanned() {
        // 只有"路径分段恰好是隐藏目录"才算副本，文件名里带这段字符不算
        assertTrue(DownloadQueue.hostScanAllowed(
                "/storage/emulated/0/DCIM/Camera/a.gallerysync-slim.jpg"));
        assertTrue(DownloadQueue.hostScanAllowed(
                "/storage/emulated/0/DCIM/Camera/.gallerysync-slim-old/photo.jpg"));
    }

    @Test public void previewPathForOnlyHidesSidecarCopies() {
        // 第 4 波：原地格式的“副本”就是原路径本身，不产生新的本地文件，
        // 因此它既不需要隐藏、也不会被当成另一张照片交给宿主扫描（发布后直接改既有行）。
        File jpeg = new File("/storage/emulated/0/DCIM/Camera/IMG_0002.jpg");
        assertEquals(jpeg, DownloadQueue.previewPathFor(jpeg, false));
        File heic = new File("/storage/emulated/0/DCIM/Camera/IMG_0001.HEIC");
        assertEquals(heic, DownloadQueue.previewPathFor(heic, true));
        assertTrue(DownloadQueue.hostScanAllowed(jpeg.getAbsolutePath()));

        // 侧车格式仍然藏在隐藏目录里，且一律不许交给宿主扫描器
        File[] sidecarOriginals = {
                new File("/storage/emulated/0/DCIM/Camera/IMG_0001.HEIC"),
                new File("/storage/emulated/0/DCIM/Camera/IMG_0003.png"),
                new File("/storage/emulated/0/DCIM/Camera/IMG_0004.webp"),
        };
        for (File original : sidecarOriginals) {
            File preview = DownloadQueue.previewPathFor(original, false);
            assertNotEquals(original, preview);
            assertFalse(preview.getAbsolutePath(),
                    DownloadQueue.hostScanAllowed(preview.getAbsolutePath()));
            assertTrue(DownloadQueue.hostScanAllowed(original.getAbsolutePath()));
            assertFalse(DownloadQueue.hostScanAllowed(
                    DownloadQueue.videoPosterPathFor(new File(original.getPath() + ".mp4"))
                            .getAbsolutePath()));
        }
    }
}
