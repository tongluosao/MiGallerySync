package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 自动下载进度日志的节流判据：5% 或 1MB，先到先记。
 *
 * <p>纯函数单测：不碰 Android，也不启动发布器线程。
 */
public class UploadProgressPublisherThrottleTest {

    private static final long MB = 1024L * 1024L;

    @Test
    public void logsEveryFivePercentOfKnownTotal() {
        long total = 20_000_000L;
        // 0 → 2%：两个阈值都没到，不记
        assertFalse(UploadProgressPublisher.progressLogDue(0L, 400_000L, total));
        // 0 → 5%：百分比到了
        assertTrue(UploadProgressPublisher.progressLogDue(0L, 1_000_000L, total));
        // 从上一次 5% 再到 10%
        assertTrue(UploadProgressPublisher.progressLogDue(1_000_000L, 2_000_000L, total));
        // 5% → 7%：不足 5 个百分点
        assertFalse(UploadProgressPublisher.progressLogDue(1_000_000L, 1_400_000L, total));
    }

    @Test
    public void logsEveryMegabyteEvenBelowFivePercent() {
        long total = 1_000_000_000L;
        // 100MB 的总量里 1MB 还不到 5%，但字节阈值到了
        assertTrue(UploadProgressPublisher.progressLogDue(0L, MB, total));
        assertTrue(UploadProgressPublisher.progressLogDue(MB, 2L * MB, total));
        assertFalse(UploadProgressPublisher.progressLogDue(MB, MB + 512L * 1024L, total));
    }

    @Test
    public void ignoresBackwardsOrRepeatedProgress() {
        long total = 20_000_000L;
        assertFalse(UploadProgressPublisher.progressLogDue(1_000_000L, 1_000_000L, total));
        // 远端可能报回退的进度：不能再记一行，否则日志与实际方向相反
        assertFalse(UploadProgressPublisher.progressLogDue(2_000_000L, 1_500_000L, total));
        assertFalse(UploadProgressPublisher.progressLogDue(0L, 0L, total));
    }

    @Test
    public void unknownTotalFallsBackToMegabyteStep() {
        assertFalse(UploadProgressPublisher.progressLogDue(0L, 900_000L, 0L));
        assertTrue(UploadProgressPublisher.progressLogDue(0L, MB, 0L));
        // 未知/非法总量时仍按字节阈值判断，绝不能因为 total 不可读就整段静默
        assertFalse(UploadProgressPublisher.progressLogDue(0L, 900_000L, -1L));
        assertTrue(UploadProgressPublisher.progressLogDue(0L, MB, -1L));
    }
}
