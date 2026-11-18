package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;

/**
 * 自动下载的并行路数：配置侧夹取 + 通道开启判据。
 *
 * <p>并行是这次唯一的"结构性"改动，两边都必须钉死：
 * <ul>
 *   <li>配置侧：落盘值可以是 0、负数、99（老版本没有这个键，手改 XML 也不受约束），
 *       真正生效的必须是 1..[Config#MAX_DOWNLOAD_CONCURRENCY] 之内的那个数。
 *       不回夹的话 {@code new Thread[...]} 会直接按 99 开线程。</li>
 *   <li>通道侧：调大只能**补开**退出过的通道，已经在跑的那几路一动不动 —— 动一下就会
 *       把进行中的下载掐断（那一路的连接被关、项被记成一次失败）。</li>
 * </ul>
 */
public class DownloadConcurrencyTest {

    @Test
    public void defaultIsSixAndCeilingIsEight() {
        // 行为变更（有意的配置调整，不是放宽断言）：下载的瓶颈是"每对象一次握手"的等待，
        // 真机实测 6 路明显优于 3 路，7/8 收益迅速变平。上传默认仍是 3（每个对象要跟相册
        // provider 抢锁），下载传输期间不持 provider 锁，所以可以开得比上传多。
        assertEquals(6, Config.DEFAULT_DOWNLOAD_CONCURRENCY);
        assertEquals(8, Config.MAX_DOWNLOAD_CONCURRENCY);
        assertEquals("download_concurrency", Config.KEY_DOWNLOAD_CONCURRENCY);
    }

    @Test
    public void zeroAndNegativeCollapseToOne() {
        assertEquals(1, Config.clampDownloadConcurrency(0));
        assertEquals(1, Config.clampDownloadConcurrency(-7));
        assertEquals(1, Config.clampDownloadConcurrency(Integer.MIN_VALUE));
    }

    @Test
    public void valuesInsideRangeAreKept() {
        for (int value = 1; value <= Config.MAX_DOWNLOAD_CONCURRENCY; value++) {
            assertEquals(value, Config.clampDownloadConcurrency(value));
        }
    }

    @Test
    public void valuesAboveCeilingAreClampedToEight() {
        assertEquals(8, Config.clampDownloadConcurrency(9));
        assertEquals(8, Config.clampDownloadConcurrency(99));
        assertEquals(8, Config.clampDownloadConcurrency(Integer.MAX_VALUE));
    }

    @Test
    public void uploadConcurrencyIsClampedIndependently() {
        // 两条设置共用一个夹取上限，但落盘键必须不同：共键会让调下载顺手改掉上传
        assertEquals(8, Config.clampConcurrency(9));
        assertTrue(!Config.KEY_UPLOAD_CONCURRENCY.equals(Config.KEY_DOWNLOAD_CONCURRENCY));
    }

    @Test
    public void firstStartOpensEveryLaneUpToTarget() {
        Thread[] workers = new Thread[Config.MAX_DOWNLOAD_CONCURRENCY];
        assertArrayEquals(new int[]{0, 1, 2}, DownloadQueue.lanesToStart(3, workers));
    }

    @Test
    public void liveLanesAreNeverRestarted() throws Exception {
        Thread[] workers = new Thread[Config.MAX_DOWNLOAD_CONCURRENCY];
        // 用真的在跑（阻塞）的线程：空 runnable 会立刻结束，测不出"活着的不重开"
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 3; i++) {
            workers[i] = new Thread(() -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            workers[i].start();
        }
        try {
            // 调大：只补开 3、4 两路，0..2 那三路正在下东西，不能碰
            assertArrayEquals(new int[]{3, 4}, DownloadQueue.lanesToStart(5, workers));
            // 目标没变：什么都不用开
            assertArrayEquals(new int[0], DownloadQueue.lanesToStart(3, workers));
        } finally {
            release.countDown();
            for (int i = 0; i < 3; i++) {
                workers[i].join(5000L);
            }
        }
    }

    @Test
    public void deadLanesAreReopened() throws Exception {
        Thread[] workers = new Thread[Config.MAX_DOWNLOAD_CONCURRENCY];
        Thread dead = new Thread(() -> { });
        dead.start();
        dead.join();
        workers[1] = dead;
        assertTrue(!dead.isAlive());

        // 收缩时退出的那一路（线程已死但对象还在）必须在调大后补开，否则通道永久缺失
        assertArrayEquals(new int[]{0, 1, 2}, DownloadQueue.lanesToStart(3, workers));
    }

    @Test
    public void lanesAreLimitedByBothTargetAndArrayLength() {
        // 夹取之后目标不会超过数组长度，这个判据只是别越界
        assertArrayEquals(new int[]{0, 1}, DownloadQueue.lanesToStart(2, new Thread[8]));
        assertArrayEquals(new int[0], DownloadQueue.lanesToStart(0, new Thread[8]));
        assertArrayEquals(new int[0], DownloadQueue.lanesToStart(4, null));
        assertNotNull(DownloadQueue.lanesToStart(1, new Thread[1]));
    }
}
