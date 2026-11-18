package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * "缺失的远端文件要不要补下来"的判据。
 *
 * <p>5 参版本是既有语义（老测试钉着，不许改）；6 参版本多一条
 * {@code localIsRegisteredDerivedCopy}：本地那份字节是<b>本记录自己登记过的派生副本</b>
 * （原地瘦身留下的同名预览）时，允许计划"下回整份原图、顶掉预览"。旧实现里
 * {@code localExists=true} 直接把这条链路挡死，于是"下载原图"对原地瘦身过的照片永远无效。
 */
public class DownloadQueueShouldPlanDownloadTest {

    // (scheduled, done, restoreMissingOriginal, localExists, hostKnowsLocalPath, derived)

    @Test
    public void fiveArgOverloadKeepsExistingSemantics() {
        assertFalse(DownloadQueue.shouldPlanDownload(false, false, false, true, true));
        assertFalse(DownloadQueue.shouldPlanDownload(false, true, false, true, true));
        assertTrue(DownloadQueue.shouldPlanDownload(false, false, false, false, true));
        assertTrue(DownloadQueue.shouldPlanDownload(false, true, true, true, false));
        // 已经排进队列 / 已完成且不补 / 本地存在且无恢复理由：都不再计划
        assertFalse(DownloadQueue.shouldPlanDownload(true, false, true, false, true));
        assertFalse(DownloadQueue.shouldPlanDownload(false, true, false, false, false));
    }

    @Test
    public void registeredDerivedCopyAllowsReplacingTheSlimPreview() {
        // 本地有文件、相册也知道这个路径 —— 旧版本在这里返回 false，导致"下载原图"永不触发
        assertFalse(DownloadQueue.shouldPlanDownload(false, false, false, true, true, false));
        assertTrue(DownloadQueue.shouldPlanDownload(false, false, false, true, true, true));
    }

    @Test
    public void derivedCopyAlsoLiftsTheDoneGate() {
        // 账本记着"已完成"，但原路径上只剩我们自己的预览：仍然要补
        assertFalse(DownloadQueue.shouldPlanDownload(false, true, false, true, true, false));
        assertTrue(DownloadQueue.shouldPlanDownload(false, true, false, true, true, true));
    }

    @Test
    public void scheduledAlwaysWins() {
        assertFalse(DownloadQueue.shouldPlanDownload(true, false, true, false, true, true));
        assertFalse(DownloadQueue.shouldPlanDownload(true, true, true, true, true, true));
    }

    @Test
    public void derivedCopyDoesNotMeanEverythingIsPlannable() {
        // 本地没有、相册也不知道：仍然不补（否则又会走回 200 项误下的老路）
        assertFalse(DownloadQueue.shouldPlanDownload(false, false, false, false, false, false));
        // 本地没有、账本已完成、也没有恢复理由：不补
        assertFalse(DownloadQueue.shouldPlanDownload(false, true, false, false, false, false));
        // 只有"相册还认这个路径"才补
        assertTrue(DownloadQueue.shouldPlanDownload(false, false, false, false, true, false));
    }
}
