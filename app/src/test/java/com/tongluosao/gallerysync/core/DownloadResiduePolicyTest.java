package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 下载临时件清理判据。进程被杀之后 {@code .<目标>.mgs-<uuid>.tmp} 会永远留在相册目录里，
 * 以点开头所以扫描器看不见；清理时必须只删"形状对得上、且足够旧"的那一类。
 */
public class DownloadResiduePolicyTest {

    private static final String DEST = "Screenshot_2026-05-01-12-00-00-123.jpg";

    @Test
    public void recognizesOwnStagingNames() {
        assertTrue(DownloadResiduePolicy.isStagingNameFor(
                "." + DEST + ".mgs-7580544c-90ab-4c31-9d0e-111111111111.tmp", DEST));
        assertTrue(DownloadResiduePolicy.isStagingNameFor(".mgs-tmp-7580544c.tmp", DEST));
        assertTrue(DownloadResiduePolicy.isStagingNameFor(".mgs-tmp-abc", DEST));
    }

    @Test
    public void rejectsEverythingElse() {
        // 正常目标文件：绝不能删
        assertFalse(DownloadResiduePolicy.isStagingNameFor(DEST, DEST));
        // 别的目标文件的临时件：交给那个下载路自己处理
        assertFalse(DownloadResiduePolicy.isStagingNameFor(".other.jpg.mgs-abc.tmp", DEST));
        // 形状不完整
        assertFalse(DownloadResiduePolicy.isStagingNameFor("." + DEST + ".mgs-", DEST));
        assertFalse(DownloadResiduePolicy.isStagingNameFor(".mgs-tmp-", DEST));
        // 前缀相同但目标名不同（防止截断式匹配误删）
        assertFalse(DownloadResiduePolicy.isStagingNameFor(
                "." + DEST + "X.mgs-abc.tmp", DEST));
        // 无意义输入
        assertFalse(DownloadResiduePolicy.isStagingNameFor(null, DEST));
        assertFalse(DownloadResiduePolicy.isStagingNameFor("." + DEST + ".mgs-a.tmp", null));
        assertFalse(DownloadResiduePolicy.isStagingNameFor("." + DEST + ".mgs-a.tmp", ""));
    }

    @Test
    public void onlySweepsStaleFiles() {
        long now = 1_800_000_000_000L;
        String staging = "." + DEST + ".mgs-abc.tmp";
        long min = DownloadResiduePolicy.MIN_AGE_MS;
        assertFalse(DownloadResiduePolicy.isSweepable(staging, DEST, now, now - min + 1));
        assertTrue(DownloadResiduePolicy.isSweepable(staging, DEST, now, now - min));
        // 很旧的一定能删
        assertTrue(DownloadResiduePolicy.isSweepable(staging, DEST, now, now - 86_400_000L));
        // 刚写的不能删（可能是别的 lane 正在写）
        assertFalse(DownloadResiduePolicy.isSweepable(staging, DEST, now, now - 1_000L));
    }

    @Test
    public void unreadableTimestampsAreLeftAlone() {
        long now = 1_800_000_000_000L;
        String staging = ".mgs-tmp-abc";
        assertFalse(DownloadResiduePolicy.isSweepable(staging, DEST, now, 0L));
        assertFalse(DownloadResiduePolicy.isSweepable(staging, DEST, now, -1L));
        assertFalse(DownloadResiduePolicy.isSweepable(staging, DEST, 0L, now - 1_000_000L));
        // mtime 在未来：时钟抖动，判断不可靠，留着
        assertFalse(DownloadResiduePolicy.isSweepable(staging, DEST, now, now + 60_000L));
    }

    @Test
    public void nameMismatchIsNeverSwept() {
        long now = 1_800_000_000_000L;
        assertFalse(DownloadResiduePolicy.isSweepable(DEST, DEST, now, now - 86_400_000L));
        assertFalse(DownloadResiduePolicy.isSweepable(".other.mgs-abc.tmp", DEST, now,
                now - 86_400_000L));
    }
}
