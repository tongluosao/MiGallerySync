package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.io.File;

import org.junit.Test;

/**
 * "按注册表把 {@code REMOTE_ONLY} 行排进下载队列"的判据单测。
 *
 * <p>{@link AutoDownloadPolicy} 刻意不碰 Android / SharedPreferences / 磁盘，所以每条安全
 * 条件都能直接钉死。这里覆盖验收里点名的八种情形：开关关闭、非 Wi-Fi、已存在同长度、
 * 已存在不同长度、达到单轮上限、失败次数超限、只有 {@code REMOTE_ONLY} 才排、其它状态不排。
 */
public final class AutoDownloadPolicyTest {

    private static final long LENGTH = 4_194_304L;
    private static final String ROOT = "MiGallerySync";
    private static final String REMOTE = "MiGallerySync/DCIM/Camera/IMG_20260928_114513.HEIC";

    /** 默认一切正常：开关开、在 Wi-Fi、按相册分、只有远端、没排过、目标不存在。 */
    private static AutoDownloadPolicy.Decision decide(boolean enabled, boolean wifi,
            boolean remoteOnly, boolean scheduled, boolean done, boolean exists,
            long targetLength, long expectedLength, int failures, int planned, int maxPerRound) {
        return AutoDownloadPolicy.decide(enabled, wifi, true, remoteOnly, scheduled, done,
                exists, targetLength, expectedLength, failures, 3, planned, maxPerRound);
    }

    private static AutoDownloadPolicy.Decision decideDefault() {
        return decide(true, true, true, false, false, false, -1L, LENGTH, 0, 0, 200);
    }

    @Test
    public void happyPathPlansRemoteOnlyRow() {
        assertSame(AutoDownloadPolicy.Decision.PLAN, decideDefault());
    }

    @Test
    public void switchOffNeverPlans() {
        assertSame(AutoDownloadPolicy.Decision.SKIP_DISABLED,
                decide(false, true, true, false, false, false, -1L, LENGTH, 0, 0, 200));
    }

    @Test
    public void cellularNetworkNeverPlans() {
        assertSame(AutoDownloadPolicy.Decision.SKIP_NOT_WIFI,
                decide(true, false, true, false, false, false, -1L, LENGTH, 0, 0, 200));
    }

    @Test
    public void existingFileWithSameLengthIsPromotedWithoutDownload() {
        assertSame(AutoDownloadPolicy.Decision.PROMOTE_EXISTING,
                decide(true, true, true, false, false, true, LENGTH, LENGTH, 0, 0, 200));
    }

    @Test
    public void existingFileWithDifferentLengthIsNeverOverwritten() {
        assertSame(AutoDownloadPolicy.Decision.SKIP_EXISTS_DIFFERENT,
                decide(true, true, true, false, false, true, LENGTH - 1L, LENGTH, 0, 0, 200));
        assertSame(AutoDownloadPolicy.Decision.SKIP_EXISTS_DIFFERENT,
                decide(true, true, true, false, false, true, 0L, LENGTH, 0, 0, 200));
    }

    @Test
    public void roundLimitStopsPlanning() {
        assertSame(AutoDownloadPolicy.Decision.SKIP_ROUND_LIMIT,
                decide(true, true, true, false, false, false, -1L, LENGTH, 0, 200, 200));
        // 差一条到上限时仍然可以排
        assertSame(AutoDownloadPolicy.Decision.PLAN,
                decide(true, true, true, false, false, false, -1L, LENGTH, 0, 199, 200));
    }

    @Test
    public void failureLimitStopsRetrying() {
        assertSame(AutoDownloadPolicy.Decision.SKIP_FAILURE_LIMIT,
                decide(true, true, true, false, false, false, -1L, LENGTH, 3, 0, 200));
        assertSame(AutoDownloadPolicy.Decision.PLAN,
                decide(true, true, true, false, false, false, -1L, LENGTH, 2, 0, 200));
    }

    @Test
    public void onlyRemoteOnlyRowsArePlanned() {
        assertSame(AutoDownloadPolicy.Decision.SKIP_NOT_REMOTE_ONLY,
                decide(true, true, false, false, false, false, -1L, LENGTH, 0, 0, 200));
    }

    @Test
    public void scheduledOrDoneRowsAreNotQueuedTwice() {
        assertSame(AutoDownloadPolicy.Decision.SKIP_ALREADY_SCHEDULED,
                decide(true, true, true, true, false, false, -1L, LENGTH, 0, 0, 200));
        assertSame(AutoDownloadPolicy.Decision.SKIP_ALREADY_DONE,
                decide(true, true, true, false, true, false, -1L, LENGTH, 0, 0, 200));
    }

    @Test
    public void unknownRemoteLengthIsRejected() {
        assertSame(AutoDownloadPolicy.Decision.SKIP_BAD_LENGTH,
                decide(true, true, true, false, false, false, -1L, 0L, 0, 0, 200));
    }

    @Test
    public void nonAlbumLayoutIsRejectedBeforeState() {
        AutoDownloadPolicy.Decision decision = AutoDownloadPolicy.decide(true, true, false, true,
                false, false, false, -1L, LENGTH, 0, 3, 0, 200);
        assertSame(AutoDownloadPolicy.Decision.SKIP_LAYOUT, decision);
    }

    @Test
    public void targetPathMatchesAlbumBranch() {
        File root = new File("/storage/emulated/0");
        File expected = new File(root, "DCIM/Camera/IMG_20260928_114513.HEIC");
        File actual = AutoDownloadPolicy.targetPath(Config.LAYOUT_ALBUM, ROOT, REMOTE, root);
        assertEquals(expected.getAbsolutePath(), actual.getAbsolutePath());
        // 绝不落到隐藏的瘦身目录里
        assertEquals(false, actual.getAbsolutePath().contains(".gallerysync-slim"));
    }

    @Test
    public void targetPathRejectsNonAlbumLayoutAndForeignRoot() {
        File root = new File("/storage/emulated/0");
        assertNull(AutoDownloadPolicy.targetPath(Config.LAYOUT_DATE, ROOT, REMOTE, root));
        assertNull(AutoDownloadPolicy.targetPath(Config.LAYOUT_ALBUM, ROOT,
                "OtherRoot/DCIM/Camera/IMG_1.HEIC", root));
        assertNull(AutoDownloadPolicy.targetPath(Config.LAYOUT_ALBUM, ROOT, ROOT, root));
        assertNull(AutoDownloadPolicy.targetPath(Config.LAYOUT_ALBUM, ROOT,
                "MiGallerySync/../escape.HEIC", root));
        assertNull(AutoDownloadPolicy.targetPath(Config.LAYOUT_ALBUM, ROOT, null, root));
    }

    @Test
    public void relativePathKeepsSubdirectoriesUnderRemoteRoot() {
        assertEquals("DCIM/Camera/IMG_20260928_114513.HEIC",
                AutoDownloadPolicy.relativePath(ROOT, "/" + REMOTE));
        assertEquals("DCIM/Camera/IMG_20260928_114513.HEIC",
                AutoDownloadPolicy.relativePath("/" + ROOT + "/", REMOTE));
        assertNull(AutoDownloadPolicy.relativePath(ROOT, "MiGallerySync2/DCIM/IMG.HEIC"));
    }
}
