package com.tongluosao.gallerysync.core;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 主动删除抑制账本：抑制/幂等、落盘往返、目标隔离、条目上限、坏文件兜底、远端路径反推。
 */
public class RemoteSuppressTest {

    private static final String REMOTE = "MiGallerySync/DCIM/Camera/IMG_20261019_202401.jpg";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    /** 默认配置（album 布局、远端根 MiGallerySync）就够用，没有能注入自定义值的工厂方法。 */
    private static Config config() {
        return Config.from(null);
    }

    private static File ledgerFile(File directory) {
        return new File(directory, "suppressed.json");
    }

    /**
     * 单测 JVM 里的 org.json 来自 android.jar 的打桩实现，调用即抛 "not mocked"
     * （见 {@code MetadataRestoreJobStaleRowsRequestTest} 的同名探针）。不可用时
     * 需要真实落盘/解析的那几条用例跳过 —— 账本的内存语义在剩下的用例里照样全覆盖。
     */
    private static boolean jsonUsable() {
        try {
            return new org.json.JSONObject("{\"probe\":1}").getInt("probe") == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    @Test
    public void suppressIsIdempotentAndVisibleImmediately() throws Exception {
        File directory = temporaryFolder.newFolder("ledger");
        RemoteSuppress ledger = new RemoteSuppress(directory);

        assertFalse(ledger.isSuppressed(config(), REMOTE));
        ledger.suppress(config(), REMOTE);
        ledger.suppress(config(), REMOTE);

        assertTrue(ledger.isSuppressed(config(), REMOTE));
        assertEquals(1, ledger.size());
        // 同一对象的另一种写法（带前导斜杠/反斜杠）也要命中
        assertTrue(ledger.isSuppressed(config(), "/" + REMOTE));
        assertTrue(ledger.isSuppressed(config(), REMOTE.replace('/', '\\')));
    }

    @Test
    public void ledgerRoundTripsThroughTheJsonFile() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File directory = temporaryFolder.newFolder("ledger");
        new RemoteSuppress(directory).suppress(config(), REMOTE);

        File file = ledgerFile(directory);
        assertTrue(file.isFile());
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue(json.contains(REMOTE));

        RemoteSuppress reopened = new RemoteSuppress(directory);
        assertTrue(reopened.isSuppressed(config(), REMOTE));
        assertFalse(reopened.isSuppressed(config(), "MiGallerySync/DCIM/Camera/other.jpg"));
    }

    @Test
    public void ledgerIsScopedToOneTarget() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File directory = temporaryFolder.newFolder("ledger");
        // 手写一条"别的目标"的条目：账本按 目标 + 远端路径 记账，换账号/换根目录不该生效
        String foreign = "{\"version\":1,\"entries\":[{\"key\":\"some-other-target\\n"
                + REMOTE + "\",\"at\":1}]}";
        Files.write(ledgerFile(directory).toPath(), foreign.getBytes(StandardCharsets.UTF_8));

        RemoteSuppress ledger = new RemoteSuppress(directory);
        assertEquals(1, ledger.size());
        assertFalse(ledger.isSuppressed(config(), REMOTE));

        ledger.suppress(config(), REMOTE);
        assertTrue(ledger.isSuppressed(config(), REMOTE));
        assertEquals(2, ledger.size());
    }

    @Test
    public void suppressionWithoutConfigurationIsIgnored() throws Exception {
        File directory = temporaryFolder.newFolder("ledger");
        RemoteSuppress ledger = new RemoteSuppress(directory);
        // 没有配置就算不出目标，宁可漏掉一条抑制也不要记进"任何目标都命中"的口袋
        ledger.suppress(null, REMOTE);
        assertEquals(0, ledger.size());
        assertFalse(ledger.isSuppressed(null, REMOTE));
    }

    @Test
    public void capDropsOldestEntriesFirst() throws Exception {
        File directory = temporaryFolder.newFolder("ledger");
        RemoteSuppress ledger = new RemoteSuppress(directory, 3);
        assertEquals(3, ledger.maxEntries());

        for (int i = 0; i < 5; i++) {
            ledger.suppress(config(), "MiGallerySync/DCIM/Camera/IMG_" + i + ".jpg");
        }

        assertEquals(3, ledger.size());
        assertFalse(ledger.isSuppressed(config(), "MiGallerySync/DCIM/Camera/IMG_0.jpg"));
        assertFalse(ledger.isSuppressed(config(), "MiGallerySync/DCIM/Camera/IMG_1.jpg"));
        assertTrue(ledger.isSuppressed(config(), "MiGallerySync/DCIM/Camera/IMG_2.jpg"));
        assertTrue(ledger.isSuppressed(config(), "MiGallerySync/DCIM/Camera/IMG_4.jpg"));
    }

    @Test
    public void capSurvivesReload() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File directory = temporaryFolder.newFolder("ledger");
        RemoteSuppress ledger = new RemoteSuppress(directory, 3);
        for (int i = 0; i < 5; i++) {
            ledger.suppress(config(), "MiGallerySync/DCIM/Camera/IMG_" + i + ".jpg");
        }

        RemoteSuppress reopened = new RemoteSuppress(directory, 3);
        assertEquals(3, reopened.size());
        assertTrue(reopened.isSuppressed(config(), "MiGallerySync/DCIM/Camera/IMG_4.jpg"));
    }

    @Test
    public void malformedLedgerFailsSafeAndIsNeverOverwritten() throws Exception {
        File directory = temporaryFolder.newFolder("ledger");
        File file = ledgerFile(directory);
        byte[] broken = "{not json".getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), broken);

        RemoteSuppress ledger = new RemoteSuppress(directory);
        assertFalse(ledger.isSuppressed(config(), REMOTE));
        // 账本坏了就不再往上面写：留着原文，等人工看一眼，别把有效条目一起冲掉
        ledger.suppress(config(), REMOTE);
        assertFalse(ledger.isSuppressed(config(), REMOTE));
        assertTrue(Files.readAllBytes(file.toPath()).length == broken.length);
    }

    @Test
    public void structurallyInvalidLedgerFailsSafeToo() throws Exception {
        File directory = temporaryFolder.newFolder("ledger");
        Files.write(ledgerFile(directory).toPath(),
                "{\"version\":1,\"entries\":5}".getBytes(StandardCharsets.UTF_8));

        RemoteSuppress ledger = new RemoteSuppress(directory);
        assertFalse(ledger.isSuppressed(config(), REMOTE));
        assertEquals(0, ledger.size());
    }

    @Test
    public void registeredRemotePathWinsForDeletedCopies() {
        assertEquals(REMOTE, RemoteSuppress.remotePathFor(config(), "/" + REMOTE,
                "/storage/emulated/0/Pictures/whatever.jpg"));
        // 登记在瘦身目录里也照登记走（注册表是唯一权威来源）
        assertEquals("MiGallerySync/DCIM/Camera/a.jpg", RemoteSuppress.remotePathFor(config(),
                "MiGallerySync/DCIM/Camera/a.jpg",
                "/storage/emulated/0/DCIM/Camera/.gallerysync-slim/a.jpg"));
    }

    @Test
    public void albumLayoutDerivesTheRemotePathFromTheStorageRoot() {
        assertEquals("MiGallerySync/DCIM/Camera/IMG_20261019_120142.jpg",
                RemoteSuppress.remotePathFor(config(), null,
                        "/storage/emulated/0/DCIM/Camera/IMG_20261019_120142.jpg"));
        assertEquals("MiGallerySync/Pictures/Screenshots/s.png",
                RemoteSuppress.remotePathFor(config(), null,
                        "/storage/emulated/0/Pictures/Screenshots/s.png"));
    }

    @Test
    public void derivationNeverGuesses() {
        // 没有配置就没有远端根，无从反推
        assertNull(RemoteSuppress.remotePathFor(null, null, "/storage/emulated/0/DCIM/Camera/a.jpg"));
        // 瘦身副本目录里的文件从来没有对应的远端对象
        assertNull(RemoteSuppress.remotePathFor(config(), null,
                "/storage/emulated/0/DCIM/Camera/.gallerysync-slim/a.jpg"));
        // 存储根之外
        assertNull(RemoteSuppress.remotePathFor(config(), null, "/data/local/tmp/a.jpg"));
        assertNull(RemoteSuppress.remotePathFor(config(), null, "/storage/emulated/0/"));
        assertNull(RemoteSuppress.remotePathFor(config(), null, null));
        assertNull(RemoteSuppress.remotePathFor(config(), "   ", "sdcard/a.jpg"));
    }
}
