package com.tongluosao.gallerysync.core;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import static org.junit.Assert.*;

/**
 * 2026-10-19 19:49 误下事故的回归测试：宿主已删除（cloud 表里没有行）、注册表里也没有记录的
 * 缺失文件，不得再进入下载计划。
 */
public class DownloadQueueDeletedFileGuardTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    /** 事故现场：无 DB 行、无注册表记录、dl_done 里也没有 → 必须拒绝。 */
    @Test public void missingFileWithoutHostRowOrRegistryRecordIsNotPlanned() {
        assertFalse(DownloadQueue.shouldPlanDownload(
                false, false, false, false, false));
    }

    /** 同一批文件的旧版行为：只要没有 done、本地缺就下（这里作为对照，说明判据是必需的）。 */
    @Test public void deletingTheLastConditionWouldHaveReintroducedTheBug() {
        // hostKnowsLocalPath = true 是"宿主还要这张照片"的唯一正向信号：
        // 换机/空间优化后的正当补齐走这条分支。
        assertTrue(DownloadQueue.shouldPlanDownload(false, false, false, false, true));
    }

    /** 注册表登记了"原图被瘦身预览替代"时，即使 DB 查不到也放行（ORIGIN 恢复）。 */
    @Test public void registeredPreviewRestoreStillWinsWithoutHostRow() {
        assertTrue(DownloadQueue.shouldPlanDownload(false, false, true, false, false));
        assertTrue(DownloadQueue.shouldPlanDownload(false, true, true, false, false));
        assertTrue(DownloadQueue.shouldPlanDownload(false, false, true, true, false));
    }

    /** 本地文件还在时什么都不用做；已排队的路径也不要重复入队。 */
    @Test public void presentFilesAndInFlightPathsAreNeverReplanned() {
        assertFalse(DownloadQueue.shouldPlanDownload(false, false, false, true, true));
        assertFalse(DownloadQueue.shouldPlanDownload(true, false, false, false, true));
        assertFalse(DownloadQueue.shouldPlanDownload(true, false, true, false, true));
    }

    /** 已有下载账本（done=true）的路径不再自动补齐，除非是注册过的 ORIGIN 恢复。 */
    @Test public void completedDownloadsStayDoneUnlessTheOriginalMustBeRestored() {
        assertFalse(DownloadQueue.shouldPlanDownload(false, true, false, false, true));
        assertFalse(DownloadQueue.shouldPlanDownload(false, true, false, true, true));
        assertTrue(DownloadQueue.shouldPlanDownload(false, true, true, false, true));
    }

    /** provider 查询失败时快照为 null → 按"宿主不认识"处理，宁可漏一轮也不误下。 */
    @Test public void unavailableHostSnapshotFailsClosed() {
        File local = new File("/storage/emulated/0/DCIM/Camera/IMG_20240822_204647_1733384285931.jpg");
        assertFalse(DownloadQueue.hostKnowsLocalPath(null, local));
        assertFalse(DownloadQueue.hostKnowsLocalPath(Collections.<String>emptySet(), local));
        assertFalse(DownloadQueue.hostKnowsLocalPath(null, null));
    }

    /** 快照命中：路径按 cloud 表 localFile 的原样（绝对路径）比对。 */
    @Test public void hostSnapshotMatchesTheAbsoluteLocalPath() throws IOException {
        File root = temporaryFolder.newFolder("storage");
        File local = new File(root, "DCIM/Camera/photo.jpg");
        assertTrue(local.getParentFile().mkdirs());
        assertTrue(local.createNewFile());

        Set<String> hostRows = new LinkedHashSet<>();
        hostRows.add(local.getAbsolutePath());

        assertTrue(DownloadQueue.hostKnowsLocalPath(hostRows, local));
        assertFalse(DownloadQueue.hostKnowsLocalPath(hostRows,
                new File(root, "DCIM/Camera/other.jpg")));
        // 同名但不同目录不算命中：副本不该因为"名字像"就被放行
        assertFalse(DownloadQueue.hostKnowsLocalPath(hostRows,
                new File(root, "DCIM/MiGallerySync/DCIM/Camera/photo.jpg")));
    }

    /**
     * canonical path 的兜底：宿主行是规范形式、本地路径带冗余段时仍能认出（Android 上
     * /storage/emulated/0 的 canonical 与 absolute 相同，这条只是防御）。符号链接在 Windows
     * 与 Android 上解析策略不同，因此用 "." 段做与平台无关的验证。
     */
    @Test public void hostSnapshotFallsBackToTheCanonicalPath() throws IOException {
        File root = temporaryFolder.newFolder("canonical");
        File file = new File(root, "DCIM/Camera/photo.jpg");
        assertTrue(file.getParentFile().mkdirs());
        assertTrue(file.createNewFile());

        File verbose = new File(root, "DCIM/./Camera/photo.jpg");
        assertNotEquals(verbose.getAbsolutePath(), verbose.getCanonicalPath());

        Set<String> hostRows = new LinkedHashSet<>();
        hostRows.add(file.getCanonicalPath());

        assertTrue(DownloadQueue.hostKnowsLocalPath(hostRows, verbose));
    }
}
