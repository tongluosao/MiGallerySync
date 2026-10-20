package com.tongluosao.gallerysync.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 旁车三态判定，以及旁车优先校验的三条分支（命中走捷径 / 过期重验并重写 / 缺失回填）。
 *
 * <p>用假的 {@link RemoteHashes.Io} 与假的 {@link OriginalStore.RemoteGateway}，
 * 全程不碰网络：这里验证的是"校验结论从哪来"，而不是 WebDAV 实现。
 */
public class RemoteVerifyDecisionTest {

    private static final String ROOT = "/MiGallerySync";
    private static final String PATH = "/MiGallerySync/2026/IMG_1.jpg";
    private static final String SHA =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String OTHER_SHA =
            "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
    private static final long LENGTH = 4096L;

    /** 内存版旁车存储。 */
    private static final class FakeIo implements RemoteHashes.Io {
        final Map<String, byte[]> files = new HashMap<>();

        @Override
        public byte[] read(String remotePath) {
            byte[] body = files.get(remotePath);
            return body == null ? null : body.clone();
        }

        @Override
        public void write(String remotePath, byte[] body) {
            files.put(remotePath, body.clone());
        }

        @Override
        public boolean delete(String remotePath) {
            return files.remove(remotePath) != null;
        }
    }

    /** 记录调用次数的假远端动作：用来证明"命中时一次整份回读都没发生"。 */
    private static final class FakeGateway implements OriginalStore.RemoteGateway {
        boolean storedAs = true;
        boolean bytesMatch = true;
        int stillStoredCalls;
        int byteChecks;

        @Override
        public boolean stillStored(String remotePath, long expectedLength) {
            stillStoredCalls++;
            return storedAs;
        }

        @Override
        public boolean matchesRemoteBytes(String remotePath, String expectedSha256,
                long expectedLength) {
            byteChecks++;
            return bytesMatch;
        }
    }

    private static String sidecarPath() {
        return RemoteHashes.hashPathFor(ROOT, PATH);
    }

    // ------------------------------------------------------------ 三态判定

    @Test
    public void nullEntryIsMissNeverHit() {
        assertEquals(OriginalStore.Decision.MISS,
                OriginalStore.decideRemoteHash(null, SHA, LENGTH));
    }

    @Test
    public void matchingEntryIsHit() {
        RemoteHashes.Entry entry = new RemoteHashes.Entry(SHA, LENGTH);
        assertEquals(OriginalStore.Decision.HIT,
                OriginalStore.decideRemoteHash(entry, SHA, LENGTH));
    }

    @Test
    public void lengthMismatchIsStale() {
        RemoteHashes.Entry entry = new RemoteHashes.Entry(SHA, LENGTH + 1);
        assertEquals(OriginalStore.Decision.STALE,
                OriginalStore.decideRemoteHash(entry, SHA, LENGTH));
    }

    @Test
    public void digestMismatchIsStale() {
        RemoteHashes.Entry entry = new RemoteHashes.Entry(OTHER_SHA, LENGTH);
        assertEquals(OriginalStore.Decision.STALE,
                OriginalStore.decideRemoteHash(entry, SHA, LENGTH));
    }

    // ------------------------------------------------------------ 校验分支

    /** 命中：只碰 storedAs，整份回读一次都不做，也不重写旁车。 */
    @Test
    public void hitTakesShortcutWithoutDownload() throws Exception {
        FakeIo io = new FakeIo();
        io.files.put(sidecarPath(),
                RemoteHashes.format(SHA, LENGTH).getBytes(StandardCharsets.US_ASCII));
        FakeGateway gateway = new FakeGateway();
        RemoteHashes hashes = new RemoteHashes(ROOT, io);

        OriginalStore.RemoteVerifyResult result =
                OriginalStore.verifyRemote(PATH, SHA, LENGTH, hashes, gateway);

        assertTrue(result.verified);
        assertTrue(result.hashHit);
        assertEquals(0, gateway.byteChecks);
        assertEquals(1, gateway.stillStoredCalls);
    }

    /** 过期：重验通过后，旁车被真实摘要重写（否则下一次还会再回读一遍）。 */
    @Test
    public void staleIsReverifiedAndSidecarRewritten() throws Exception {
        FakeIo io = new FakeIo();
        io.files.put(sidecarPath(),
                RemoteHashes.format(OTHER_SHA, LENGTH + 7).getBytes(StandardCharsets.US_ASCII));
        FakeGateway gateway = new FakeGateway();
        RemoteHashes hashes = new RemoteHashes(ROOT, io);

        OriginalStore.RemoteVerifyResult result =
                OriginalStore.verifyRemote(PATH, SHA, LENGTH, hashes, gateway);

        assertTrue(result.verified);
        assertFalse(result.hashHit);
        assertTrue(result.stale);
        assertEquals(1, gateway.byteChecks);
        RemoteHashes.Entry rewritten = hashes.readOrNull(PATH);
        assertNotNull(rewritten);
        assertEquals(SHA, rewritten.sha256);
        assertEquals(LENGTH, rewritten.length);
    }

    /** 缺失：整份回读通过后回填旁车。 */
    @Test
    public void missingSidecarIsBackfilled() throws Exception {
        FakeIo io = new FakeIo();
        FakeGateway gateway = new FakeGateway();
        RemoteHashes hashes = new RemoteHashes(ROOT, io);

        OriginalStore.RemoteVerifyResult result =
                OriginalStore.verifyRemote(PATH, SHA, LENGTH, hashes, gateway);

        assertTrue(result.verified);
        assertFalse(result.hashHit);
        assertFalse(result.stale);
        assertEquals(1, gateway.byteChecks);
        assertEquals(OriginalStore.Decision.HIT,
                OriginalStore.decideRemoteHash(hashes.readOrNull(PATH), SHA, LENGTH));
    }

    /** 整份回读不通过：判失败，并且绝不写旁车（写进去就是一条假证据）。 */
    @Test
    public void failedVerificationNeverWritesSidecar() throws Exception {
        FakeIo io = new FakeIo();
        FakeGateway gateway = new FakeGateway();
        gateway.bytesMatch = false;
        RemoteHashes hashes = new RemoteHashes(ROOT, io);

        OriginalStore.RemoteVerifyResult result =
                OriginalStore.verifyRemote(PATH, SHA, LENGTH, hashes, gateway);

        assertFalse(result.verified);
        assertTrue(io.files.isEmpty());
    }

    /** 旁车说命中、但对象已经不在：退回整份回读，仍然不通过就判失败（绝不 fail-open）。 */
    @Test
    public void hitWithMissingObjectFallsBackAndFailsClosed() throws Exception {
        FakeIo io = new FakeIo();
        io.files.put(sidecarPath(),
                RemoteHashes.format(SHA, LENGTH).getBytes(StandardCharsets.US_ASCII));
        FakeGateway gateway = new FakeGateway();
        gateway.storedAs = false;
        gateway.bytesMatch = false;
        RemoteHashes hashes = new RemoteHashes(ROOT, io);

        OriginalStore.RemoteVerifyResult result =
                OriginalStore.verifyRemote(PATH, SHA, LENGTH, hashes, gateway);

        assertFalse(result.verified);
        assertEquals(1, gateway.byteChecks);
    }

    /** 没有旁车（hashes 为 null）时只是退回回读，不能因此放行。 */
    @Test
    public void nullHashesDegradesToDownloadOnly() throws Exception {
        FakeGateway gateway = new FakeGateway();
        assertTrue(OriginalStore.verifyRemote(PATH, SHA, LENGTH, null, gateway).verified);
        assertEquals(1, gateway.byteChecks);

        gateway.bytesMatch = false;
        assertFalse(OriginalStore.verifyRemote(PATH, SHA, LENGTH, null, gateway).verified);
    }
}
