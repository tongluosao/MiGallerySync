package com.tongluosao.gallerysync.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 抑制账本与下载计划的关系：命中抑制的远端路径连"要不要补"都不再问。
 */
public class DownloadQueueSuppressedRemoteTest {

    private static final String REMOTE = "MiGallerySync/DCIM/Camera/IMG_20261019_202401.jpg";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static Config config() {
        return Config.from(null);
    }

    @Test
    public void suppressedRemotePathIsSkippedAndNeverPlanned() throws Exception {
        RemoteSuppress ledger = new RemoteSuppress(temporaryFolder.newFolder("ledger"));
        Config config = config();

        // 抑制之前：旧判据（远端有、本地没有、宿主还认识这个本地路径）会把它补齐
        assertTrue(DownloadQueue.shouldPlanDownload(false, false, false, false, true));
        assertTrue(DownloadQueue.mayDownload(ledger, config, REMOTE));

        ledger.suppress(config, REMOTE);

        // 抑制之后：walk() 的第一道闸直接拦掉，连补齐判据都不再走
        assertTrue(ledger.isSuppressed(config, REMOTE));
        assertFalse(DownloadQueue.mayDownload(ledger, config, REMOTE));
        // 写法的差异不该漏掉抑制（walk 拼出来的路径与注册表里的可能差一个前导斜杠）
        assertFalse(DownloadQueue.mayDownload(ledger, config, "/" + REMOTE));
    }

    @Test
    public void unsuppressedRemotePathIsUnchanged() throws Exception {
        RemoteSuppress ledger = new RemoteSuppress(temporaryFolder.newFolder("ledger"));
        Config config = config();
        ledger.suppress(config, "MiGallerySync/DCIM/Camera/IMG_other.jpg");

        assertTrue(DownloadQueue.mayDownload(ledger, config, REMOTE));
        assertTrue(DownloadQueue.shouldPlanDownload(false, false, false, false, true));
        assertTrue(DownloadQueue.shouldPlanDownload(false, false, true, false, false));
        assertFalse(DownloadQueue.shouldPlanDownload(false, false, false, true, true));
    }

    @Test
    public void missingLedgerNeverSuppresses() {
        // 账本不可用时行为必须与引入账本之前完全一致
        assertTrue(DownloadQueue.mayDownload(null, config(), REMOTE));
        assertTrue(DownloadQueue.mayDownload(new RemoteSuppress((File) null), config(), REMOTE));
    }
}
