package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void samplesCoverHeadQuarterHalfThreeQuartersAndTail() {
        long size = 200L * MIB;
        long[] offsets = RemoteByteCheck.sampleOffsets(size, RemoteByteCheck.SAMPLE_CHUNK);
        assertArrayEquals(new long[]{0L, 50L * MIB, 100L * MIB, 150L * MIB, size - MIB}, offsets);
    }

    @Test
    public void duplicateOffsetsAreDroppedOnSmallFiles() {
        long size = 4L * MIB;
        // 1/4=1MiB, 1/2=2MiB, 3/4=3MiB, tail=3MiB —— 尾段与 3/4 重合，只留一个
        assertArrayEquals(new long[]{0L, MIB, 2L * MIB, 3L * MIB},
                RemoteByteCheck.sampleOffsets(size, RemoteByteCheck.SAMPLE_CHUNK));
    }

    @Test
    public void fileNoLargerThanOneChunkIsSampledOnce() {
        assertArrayEquals(new long[]{0L},
                RemoteByteCheck.sampleOffsets(MIB, RemoteByteCheck.SAMPLE_CHUNK));
        assertArrayEquals(new long[]{0L},
                RemoteByteCheck.sampleOffsets(1024L, RemoteByteCheck.SAMPLE_CHUNK));
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

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }
}
