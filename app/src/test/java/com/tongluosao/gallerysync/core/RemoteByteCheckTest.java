package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** {@link RemoteByteCheck} 的纯逻辑：抽样位置与本地分段摘要。 */
public class RemoteByteCheckTest {

    private static final long MIB = 1024L * 1024L;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void smallFilesAreCheckedInFull() {
        assertTrue(RemoteByteCheck.isFullCheck(1L));
        assertTrue(RemoteByteCheck.isFullCheck(RemoteByteCheck.FULL_CHECK_LIMIT));
        assertFalse(RemoteByteCheck.isFullCheck(RemoteByteCheck.FULL_CHECK_LIMIT + 1L));
    }

    @Test
    public void samplesCoverHeadAndTailOnly() {
        long size = 200L * MIB;
        long chunk = RemoteByteCheck.SAMPLE_CHUNK;
        assertArrayEquals(new long[]{0L, size - chunk},
                RemoteByteCheck.sampleOffsets(size, chunk));
    }

    @Test
    public void middleOfLargeFileIsNotSampled() {
        // 只读头尾：中间的偏移量一个都不该出现（这正是省带宽的来源）
        long size = 4L * MIB;
        long chunk = RemoteByteCheck.SAMPLE_CHUNK;
        for (long offset : RemoteByteCheck.sampleOffsets(size, chunk)) {
            assertTrue("middle offset sampled: " + offset, offset == 0L || offset == size - chunk);
        }
    }

    @Test
    public void fileNoLargerThanOneChunkIsSampledOnce() {
        long chunk = RemoteByteCheck.SAMPLE_CHUNK;
        assertArrayEquals(new long[]{0L}, RemoteByteCheck.sampleOffsets(chunk, chunk));
        assertArrayEquals(new long[]{0L}, RemoteByteCheck.sampleOffsets(1024L, chunk));
        assertArrayEquals(new long[]{0L, 1024L},
                RemoteByteCheck.sampleOffsets(chunk + 1024L, chunk));
    }

    @Test
    public void offsetsAreClampedInsideTheFile() {
        long size = 3L * MIB + 7L;
        long chunk = RemoteByteCheck.SAMPLE_CHUNK;
        long[] offsets = RemoteByteCheck.sampleOffsets(size, chunk);
        assertEquals(0L, offsets[0]);
        for (long offset : offsets) {
            assertTrue("offset " + offset + " must leave room for a whole chunk",
                    offset + chunk <= size);
        }
    }

    @Test
    public void emptySizeHasNoOffsets() {
        assertEquals(0, RemoteByteCheck.sampleOffsets(0L, RemoteByteCheck.SAMPLE_CHUNK).length);
        assertEquals(0, RemoteByteCheck.sampleOffsets(-5L, RemoteByteCheck.SAMPLE_CHUNK).length);
        assertEquals(0, RemoteByteCheck.sampleOffsets(1024L, 0L).length);
    }

    @Test
    public void localRangeDigestMatchesTheSameSliceOfTheFile() throws Exception {
        File file = folder.newFile("sample.bin");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("0123456789abcdefghij".getBytes(StandardCharsets.US_ASCII));
        }
        String expected = sha256("456789".getBytes(StandardCharsets.US_ASCII));
        assertEquals(expected, RemoteByteCheck.sha256OfLocalRange(file, 4L, 6L));
    }

    @Test
    public void localRangeOutOfBoundsIsRejected() throws Exception {
        File file = folder.newFile("short.bin");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[]{1, 2, 3, 4});
        }
        assertNull(RemoteByteCheck.sha256OfLocalRange(file, 2L, 10L));
        assertNull(RemoteByteCheck.sha256OfLocalRange(file, -1L, 2L));
        assertNull(RemoteByteCheck.sha256OfLocalRange(file, 0L, 0L));
        assertNull(RemoteByteCheck.sha256OfLocalRange(new File(file.getParentFile(), "nope"),
                0L, 1L));
    }

    @Test
    public void verifyRejectsMissingLocalFile() throws IOException {
        assertFalse(RemoteByteCheck.verify(null, "a/b", null, 0L, null));
        File missing = new File(folder.getRoot(), "missing.bin");
        assertFalse(RemoteByteCheck.verify(null, "a/b", missing, 10L, null));
    }

    // ------------------------------------------------ v2 旁车证据（chunkedEntry）

    /**
     * 文件不超过两段时退化成"头段覆盖整份、尾段长度 0"：语义上仍是整份核对过，
     * 且两段不重叠（RemoteHashes.formatChunked 会拒掉重叠写法）。
     */
    @Test
    public void chunkedEntryFoldsSmallFileIntoHead() throws Exception {
        File file = folder.newFile("small.bin");
        byte[] body = bytes((int) (2L * RemoteByteCheck.SAMPLE_CHUNK));
        write(file, body);
        String sha = RemoteByteCheck.sha256OfLocalRange(file, 0L, body.length);
        RemoteHashes.Entry entry = RemoteByteCheck.chunkedEntry(file, sha);
        assertNotNull(entry);
        assertEquals(body.length, entry.length);
        assertEquals(sha, entry.sha256);
        assertEquals(body.length, entry.headLength);
        assertEquals(0L, entry.tailLength);
        assertEquals(sha, entry.headSha256);
        assertTrue(entry.hasChunks());
    }

    /** 超过两段：只记头尾各一段，长度都等于 SAMPLE_CHUNK，且两段摘要不同。 */
    @Test
    public void chunkedEntrySplitsLargeFileIntoHeadAndTail() throws Exception {
        File file = folder.newFile("large.bin");
        int size = (int) (2L * RemoteByteCheck.SAMPLE_CHUNK + 1L);
        byte[] body = bytes(size);
        write(file, body);
        String sha = RemoteByteCheck.sha256OfLocalRange(file, 0L, size);
        RemoteHashes.Entry entry = RemoteByteCheck.chunkedEntry(file, sha);
        assertNotNull(entry);
        assertEquals(size, entry.length);
        assertEquals(RemoteByteCheck.SAMPLE_CHUNK, entry.headLength);
        assertEquals(RemoteByteCheck.SAMPLE_CHUNK, entry.tailLength);
        assertEquals(RemoteByteCheck.SAMPLE_CHUNK + RemoteByteCheck.SAMPLE_CHUNK,
                entry.headLength + entry.tailLength);
        assertFalse(entry.headSha256.equals(entry.tailSha256));
        assertTrue(entry.hasChunks());
    }

    /** 0 字节文件、坏摘要、缺失文件都写不出证据：返回 null，绝不写一条自相矛盾的行。 */
    @Test
    public void chunkedEntryRejectsInvalidInputs() throws Exception {
        File empty = folder.newFile("empty.bin");
        assertNull(RemoteByteCheck.chunkedEntry(empty, zerosSha()));
        File one = folder.newFile("one.bin");
        write(one, bytes(1));
        assertNull(RemoteByteCheck.chunkedEntry(one, "not-a-digest"));
        assertNull(RemoteByteCheck.chunkedEntry(one, null));
        assertNull(RemoteByteCheck.chunkedEntry(new File(folder.getRoot(), "nope"), zerosSha()));
        assertNull(RemoteByteCheck.chunkedEntry(null, zerosSha()));
    }

    private static byte[] bytes(int length) {
        byte[] body = new byte[length];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i * 31 + 7);
        }
        return body;
    }

    private static String zerosSha() {
        return "0".repeat(64);
    }

    private static void write(File file, byte[] body) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(body);
        }
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }
}
