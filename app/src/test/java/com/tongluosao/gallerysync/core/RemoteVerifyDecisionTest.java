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
    private static final String HEAD_SHA =
            "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String TAIL_SHA =
            "2222222222222222222222222222222222222222222222222222222222222222";

    /** 一份“核对过头尾”的 v2 旁车。 */
    private static byte[] chunkedSidecar(String sha, long length) {
        return RemoteHashes.formatChunked(sha, length, HEAD_SHA, 1024L, TAIL_SHA, 1024L)
                .getBytes(StandardCharsets.US_ASCII);
    }

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
        /** 非 null 时代表“这次核对能给出头尾证据”，于是旁车被写成 v2。 */
        RemoteHashes.Entry evidence;

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

        @Override
        public RemoteHashes.Entry verifiedEvidence(String remotePath, String expectedSha256,
                long expectedLength) {
            return evidence;
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
        io.files.put(sidecarPath(), chunkedSidecar(SHA, LENGTH));
        FakeGateway gateway = new FakeGateway();
        RemoteHashes hashes = new RemoteHashes(ROOT, io);

        OriginalStore.RemoteVerifyResult result =
                OriginalStore.verifyRemote(PATH, SHA, LENGTH, hashes, gateway);

        assertTrue(result.verified);
        assertTrue(result.hashHit);
        assertEquals(0, gateway.byteChecks);
        assertEquals(1, gateway.stillStoredCalls);
    }

    /** 过期：重验通过后，旁车被换成带头尾证据的 v2（否则下一次还会再回读一遍）。 */
    @Test
    public void staleIsReverifiedAndSidecarRewritten() throws Exception {
        FakeIo io = new FakeIo();
        io.files.put(sidecarPath(),
                RemoteHashes.format(OTHER_SHA, LENGTH + 7).getBytes(StandardCharsets.US_ASCII));
        FakeGateway gateway = new FakeGateway();
        gateway.evidence = new RemoteHashes.Entry(SHA, LENGTH, HEAD_SHA, 1024L, TAIL_SHA, 1024L);
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
        assertTrue(rewritten.hasChunks());
        assertEquals(HEAD_SHA, rewritten.headSha256);
        assertEquals(TAIL_SHA, rewritten.tailSha256);
    }

    /** 缺失：核对通过后回填 v2 旁车。 */
    @Test
    public void missingSidecarIsBackfilled() throws Exception {
        FakeIo io = new FakeIo();
        FakeGateway gateway = new FakeGateway();
        gateway.evidence = new RemoteHashes.Entry(SHA, LENGTH, HEAD_SHA, 1024L, TAIL_SHA, 1024L);
        RemoteHashes hashes = new RemoteHashes(ROOT, io);

        OriginalStore.RemoteVerifyResult result =
                OriginalStore.verifyRemote(PATH, SHA, LENGTH, hashes, gateway);

        assertTrue(result.verified);
        assertFalse(result.hashHit);
        assertFalse(result.stale);
        assertEquals(1, gateway.byteChecks);
        RemoteHashes.Entry filled = hashes.readOrNull(PATH);
        assertNotNull(filled);
        assertTrue(filled.hasChunks());
        assertEquals(OriginalStore.Decision.HIT,
                OriginalStore.decideRemoteHash(filled, SHA, LENGTH));
    }

    /**
     * v1 旁车不再被当成命中：它只记了“上传时算过的整份摘要”，没有核对过远端任何字节。
     * 于是必须真去核一次头尾，核过之后才升级成 v2。
     */
    @Test
    public void legacySidecarWithoutChunksIsReverifiedThenUpgraded() throws Exception {
        FakeIo io = new FakeIo();
        io.files.put(sidecarPath(),
                RemoteHashes.format(SHA, LENGTH).getBytes(StandardCharsets.US_ASCII));
        FakeGateway gateway = new FakeGateway();
        gateway.evidence = new RemoteHashes.Entry(SHA, LENGTH, HEAD_SHA, 1024L, TAIL_SHA, 1024L);
        RemoteHashes hashes = new RemoteHashes(ROOT, io);

        OriginalStore.RemoteVerifyResult result =
                OriginalStore.verifyRemote(PATH, SHA, LENGTH, hashes, gateway);

        assertTrue(result.verified);
        assertFalse(result.hashHit);
        assertEquals(1, gateway.byteChecks);
        assertEquals(0, gateway.stillStoredCalls);
        RemoteHashes.Entry upgraded = hashes.readOrNull(PATH);
        assertNotNull(upgraded);
        assertTrue(upgraded.hasChunks());
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
        io.files.put(sidecarPath(), chunkedSidecar(SHA, LENGTH));
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
