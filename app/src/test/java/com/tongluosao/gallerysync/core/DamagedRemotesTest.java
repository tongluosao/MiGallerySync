package com.tongluosao.gallerysync.core;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 「远端已知损坏」台账：标记/查询/去重/清除、键包含目标、条数上限、坏文件兜底、并发幂等，
 * 以及它与下载计划的关系（命中即不再自动排期）。
 */
public class DamagedRemotesTest {

    private static final String REMOTE = "MiGallerySync/DCIM/Camera/IMG_20261118_120000.jpg";
    private static final String TARGET_A = "target-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TARGET_B = "target-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final long SIZE = 355_235L;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static File ledgerFile(File directory) {
        return new File(directory, "damaged-remotes.json");
    }

    /**
     * 单测 JVM 里的 org.json 来自 android.jar 的打桩实现，调用即抛 "not mocked"
     * （与 {@link RemoteSuppressTest} 同一处理）。需要真实落盘/解析的用例跳过；
     * 内存语义（标记、去重、上限、并发、目标隔离）在剩下的用例里照样全覆盖。
     */
    private static boolean jsonUsable() {
        try {
            return new org.json.JSONObject("{\"probe\":1}").getInt("probe") == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    @Test
    public void markIsVisibleImmediatelyAndDuplicateOnlyRefreshesTheTime() throws Exception {
        DamagedRemotes ledger = new DamagedRemotes(temporaryFolder.newFolder("ledger"));

        assertFalse(ledger.isDamaged(TARGET_A, REMOTE));
        ledger.markDamaged(TARGET_A, REMOTE, "all_zero", SIZE);
        long first = ledger.find(TARGET_A, REMOTE).markedAt();

        // 同一个坏对象会被好几次下载尝试各撞一次：只刷新时间，不多出一条
        Thread.sleep(5L);
        ledger.markDamaged(TARGET_A, REMOTE, "invalid_payload", SIZE);
        DamagedRemotes.Damage damage = ledger.find(TARGET_A, REMOTE);

        assertTrue(ledger.isDamaged(TARGET_A, REMOTE));
        assertEquals(1, ledger.count());
        assertEquals("invalid_payload", damage.reason());
        assertEquals(SIZE, damage.size());
        assertTrue(damage.markedAt() >= first);
    }

    @Test
    public void pathSpellingDoesNotCreateASecondEntry() throws Exception {
        DamagedRemotes ledger = new DamagedRemotes(temporaryFolder.newFolder("ledger"));
        ledger.markDamaged(TARGET_A, REMOTE, "all_zero", SIZE);

        // walk 拼出来的路径与注册表里的可能差一个前导斜杠、反斜杠或重复斜杠
        assertTrue(ledger.isDamaged(TARGET_A, "/" + REMOTE));
        assertTrue(ledger.isDamaged(TARGET_A, REMOTE.replace('/', '\\')));
        assertTrue(ledger.isDamaged(TARGET_A, "MiGallerySync//DCIM/Camera/" + "IMG_20261118_120000.jpg"));
        assertEquals(1, ledger.count());
    }

    @Test
    public void clearRemovesEveryEntry() throws Exception {
        DamagedRemotes ledger = new DamagedRemotes(temporaryFolder.newFolder("ledger"));
        ledger.markDamaged(TARGET_A, REMOTE, "all_zero", SIZE);
        ledger.markDamaged(TARGET_A, "MiGallerySync/DCIM/Camera/other.jpg", "all_zero", 10L);
        assertEquals(2, ledger.count());

        ledger.clear();

        assertEquals(0, ledger.count());
        assertFalse(ledger.isDamaged(TARGET_A, REMOTE));
        assertNull(ledger.find(TARGET_A, REMOTE));
        // 清空之后可以重新记（排障：要点"再试一次"的场景）
        ledger.markDamaged(TARGET_A, REMOTE, "all_zero", SIZE);
        assertTrue(ledger.isDamaged(TARGET_A, REMOTE));
    }

    @Test
    public void forgetRemovesOnlyOneEntry() throws Exception {
        DamagedRemotes ledger = new DamagedRemotes(temporaryFolder.newFolder("ledger"));
        ledger.markDamaged(TARGET_A, REMOTE, "all_zero", SIZE);
        ledger.markDamaged(TARGET_A, "MiGallerySync/DCIM/Camera/other.jpg", "all_zero", 10L);

        assertTrue(ledger.forget(TARGET_A, REMOTE, "download_ok"));
        assertFalse(ledger.isDamaged(TARGET_A, REMOTE));
        assertTrue(ledger.isDamaged(TARGET_A, "MiGallerySync/DCIM/Camera/other.jpg"));
        assertEquals(1, ledger.count());
        // 没记过的路径再撤一次：什么都不做，也不报错
        assertFalse(ledger.forget(TARGET_A, REMOTE));
    }

    @Test
    public void entryKeyCarriesTheTargetSoAccountsNeverBleedIntoEachOther() throws Exception {
        DamagedRemotes ledger = new DamagedRemotes(temporaryFolder.newFolder("ledger"));
        ledger.markDamaged(TARGET_A, REMOTE, "all_zero", SIZE);

        // 换账号/换远端根目录是另一个远端世界：同名对象在那边可能完全正常
        assertFalse(ledger.isDamaged(TARGET_B, REMOTE));
        assertNull(ledger.find(TARGET_B, REMOTE));
        assertEquals(1, ledger.count());

        ledger.markDamaged(TARGET_B, REMOTE, "all_zero", SIZE);
        assertEquals(2, ledger.count());
        assertTrue(ledger.isDamaged(TARGET_A, REMOTE));
        assertTrue(ledger.isDamaged(TARGET_B, REMOTE));
    }

    @Test
    public void keyWithoutTargetOrPathIsNeverRecorded() throws Exception {
        DamagedRemotes ledger = new DamagedRemotes(temporaryFolder.newFolder("ledger"));

        // 算不出键就"不记也不问"：绝不能记进一个"任何目标都命中"的口袋
        ledger.markDamaged(null, REMOTE, "all_zero", SIZE);
        ledger.markDamaged("", REMOTE, "all_zero", SIZE);
        ledger.markDamaged("   ", REMOTE, "all_zero", SIZE);
        ledger.markDamaged(TARGET_A, null, "all_zero", SIZE);
        ledger.markDamaged(TARGET_A, "  ", "all_zero", SIZE);

        assertEquals(0, ledger.count());
        assertFalse(ledger.isDamaged(TARGET_A, null));
        assertFalse(ledger.isDamaged(null, REMOTE));
        assertNull(DamagedRemotes.keyOf(null, REMOTE));
        assertNull(DamagedRemotes.keyOf(TARGET_A, ""));
        assertNotNull(DamagedRemotes.keyOf(TARGET_A, REMOTE));
        // 键里必须真的带上目标（不是只按路径记）
        assertTrue(DamagedRemotes.keyOf(TARGET_A, REMOTE).startsWith(TARGET_A));
        assertTrue(DamagedRemotes.keyOf(TARGET_A, REMOTE)
                .endsWith("\n" + RemoteSuppress.normalizeRemotePath(REMOTE)));
    }

    @Test
    public void brokenJsonFailsSafeAndIsNeverOverwritten() throws Exception {
        File directory = temporaryFolder.newFolder("ledger");
        File file = ledgerFile(directory);
        byte[] broken = "{not json".getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), broken);

        DamagedRemotes ledger = new DamagedRemotes(directory);
        assertFalse(ledger.isDamaged(TARGET_A, REMOTE));
        assertEquals(0, ledger.count());

        // 账本坏了就不再往上面写：留着原文等人工看一眼，别把还没读出来的有效条目一起冲掉
        ledger.markDamaged(TARGET_A, REMOTE, "all_zero", SIZE);
        assertFalse(ledger.isDamaged(TARGET_A, REMOTE));
        assertEquals(0, ledger.count());
        assertEquals(broken.length, Files.readAllBytes(file.toPath()).length);
    }

    @Test
    public void structurallyInvalidJsonFailsSafeToo() throws Exception {
        File directory = temporaryFolder.newFolder("ledger");
        Files.write(ledgerFile(directory).toPath(),
                "{\"version\":1,\"entries\":5}".getBytes(StandardCharsets.UTF_8));

        DamagedRemotes ledger = new DamagedRemotes(directory);
        assertFalse(ledger.isDamaged(TARGET_A, REMOTE));
        assertEquals(0, ledger.count());
    }

    @Test
    public void capDropsTheLeastRecentlyMarkedEntry() throws Exception {
        DamagedRemotes ledger = new DamagedRemotes(temporaryFolder.newFolder("ledger"), 3);
        assertEquals(3, ledger.maxEntries());
        for (int i = 0; i < 3; i++) {
            ledger.markDamaged(TARGET_A, path(i), "all_zero", SIZE);
        }
        // 重新确认第 0 条：它成了"最近标记"的那一条，淘汰时不该先丢它
        ledger.markDamaged(TARGET_A, path(0), "all_zero", SIZE);
        ledger.markDamaged(TARGET_A, path(3), "all_zero", SIZE);

        assertEquals(3, ledger.count());
        assertTrue(ledger.isDamaged(TARGET_A, path(0)));
        assertFalse(ledger.isDamaged(TARGET_A, path(1)));
        assertTrue(ledger.isDamaged(TARGET_A, path(2)));
        assertTrue(ledger.isDamaged(TARGET_A, path(3)));
    }

    @Test
    public void concurrentMarksOfTheSamePathStayIdempotent() throws Exception {
        final DamagedRemotes ledger = new DamagedRemotes(temporaryFolder.newFolder("ledger"));
        final int threads = 8;
        final int perThread = 25;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger failures = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ledger.markDamaged(TARGET_A, REMOTE, "all_zero", SIZE);
                    }
                } catch (Throwable error) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }, "damaged-remotes-test-" + t);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        assertTrue(done.await(30L, TimeUnit.SECONDS));
        for (Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(5L));
        }

        assertEquals(0, failures.get());
        assertEquals(1, ledger.count());
        assertTrue(ledger.isDamaged(TARGET_A, REMOTE));
    }

    @Test
    public void ledgerRoundTripsThroughTheJsonFile() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File directory = temporaryFolder.newFolder("ledger");
        DamagedRemotes ledger = new DamagedRemotes(directory);
        ledger.markDamaged(TARGET_A, REMOTE, "all_zero", SIZE);

        File file = ledgerFile(directory);
        assertTrue(file.isFile());
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue(json.contains(TARGET_A));
        assertTrue(json.contains("all_zero"));

        DamagedRemotes reopened = new DamagedRemotes(directory);
        assertEquals(1, reopened.count());
        assertTrue(reopened.isDamaged(TARGET_A, REMOTE));
        assertEquals("all_zero", reopened.find(TARGET_A, REMOTE).reason());
        assertEquals(SIZE, reopened.find(TARGET_A, REMOTE).size());
        assertFalse(reopened.isDamaged(TARGET_A, "MiGallerySync/DCIM/Camera/other.jpg"));
        // 换目标还是读不到（键里带目标）
        assertFalse(reopened.isDamaged(TARGET_B, REMOTE));

        reopened.forget(TARGET_A, REMOTE, "download_ok");
        DamagedRemotes reopenedAgain = new DamagedRemotes(directory);
        assertEquals(0, reopenedAgain.count());
        assertFalse(reopenedAgain.isDamaged(TARGET_A, REMOTE));
    }

    @Test
    public void degradedLedgerNeverClaimsDamage() {
        // 目录不可用（这里是 null）：只降级、不抛，也绝不据此挡住任何下载
        DamagedRemotes degraded = new DamagedRemotes((File) null);
        degraded.markDamaged(TARGET_A, REMOTE, "all_zero", SIZE);
        assertFalse(degraded.isDamaged(TARGET_A, REMOTE));
        assertEquals(0, degraded.count());
        degraded.clear();
        assertFalse(degraded.forget(TARGET_A, REMOTE));
    }

    @Test
    public void onlyContentClassFailuresAreRecorded() {
        // 字节本身不对的两类：才值得记账
        assertTrue(DamagedRemotes.isContentDamage(
                DownloadFailurePolicy.Cause.INVALID_PAYLOAD));
        assertTrue(DamagedRemotes.isContentDamage(
                DownloadFailurePolicy.Cause.CONTENT_MISMATCH));

        // 会自愈/与内容无关的一律不记：网络、超时、403、长度不符、本地 IO、未知……
        DownloadFailurePolicy.Cause[] retryable = {
                DownloadFailurePolicy.Cause.TRANSPORT,
                DownloadFailurePolicy.Cause.STALLED,
                DownloadFailurePolicy.Cause.SERVER,
                DownloadFailurePolicy.Cause.AUTH,
                DownloadFailurePolicy.Cause.BAD_REQUEST,
                DownloadFailurePolicy.Cause.LOW_SPACE,
                DownloadFailurePolicy.Cause.IO,
                DownloadFailurePolicy.Cause.EMPTY_PAYLOAD,
                DownloadFailurePolicy.Cause.LENGTH_MISMATCH,
                DownloadFailurePolicy.Cause.DESTINATION_CONFLICT,
                DownloadFailurePolicy.Cause.REGISTRY_REJECTED,
                DownloadFailurePolicy.Cause.NOT_FOUND,
                DownloadFailurePolicy.Cause.PROBE_MISSING,
                DownloadFailurePolicy.Cause.UNKNOWN,
        };
        for (DownloadFailurePolicy.Cause cause : retryable) {
            assertFalse("不该把 " + cause + " 记成远端损坏",
                    DamagedRemotes.isContentDamage(cause));
        }
        assertFalse(DamagedRemotes.isContentDamage(null));
    }

    @Test
    public void downloadPlanningAsksTheLedgerBeforeScheduling() throws Exception {
        DamagedRemotes ledger = new DamagedRemotes(temporaryFolder.newFolder("ledger"));

        // 没记过：计划侧照旧排期（walk/planAutoDownloads 都会走到 damageOf 这一步）
        assertNull(DownloadQueue.damageOf(ledger, TARGET_A, REMOTE));
        ledger.markDamaged(TARGET_A, REMOTE, "all_zero", SIZE);
        assertNotNull(DownloadQueue.damageOf(ledger, TARGET_A, REMOTE));
        // 写法的差异不该漏掉：walk 拼的路径可能带前导斜杠
        assertNotNull(DownloadQueue.damageOf(ledger, TARGET_A, "/" + REMOTE));
        // 别的目标、别的路径照常排期
        assertNull(DownloadQueue.damageOf(ledger, TARGET_B, REMOTE));
        assertNull(DownloadQueue.damageOf(ledger, TARGET_A,
                "MiGallerySync/DCIM/Camera/other.jpg"));
        // 台账不可用时行为必须与引入台账之前完全一致
        assertNull(DownloadQueue.damageOf(null, TARGET_A, REMOTE));
        assertNull(DownloadQueue.damageOf(new DamagedRemotes((File) null), TARGET_A, REMOTE));
    }

    private static String path(int index) {
        return "MiGallerySync/DCIM/Camera/IMG_" + index + ".jpg";
    }
}
