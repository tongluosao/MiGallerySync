package com.tongluosao.gallerysync.core;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 原地覆盖写入引擎的每一步与每个失败分支。
 *
 * <p>这里钉的不是“功能对不对”，而是<b>不可逆操作之前的顺序</b>：租约必须早于任何写盘、意图必须
 * 早于原子改名、DB 必须晚于逐字节复核。顺序一旦被改乱，代价是相册里的原图被静默覆盖、
 * 或者相册行指向一个没有任何字节的路径。
 *
 * <p>全部用例只用真实临时文件（不 mock 文件系统）：失败分支要么靠回调返回 false，要么靠
 * “原路径其实是个非空目录”让改名真的失败，不依赖 Android 组件，所以没有 Assume 跳过。
 */
public class InplacePreviewWriterTest {

    private static final byte[] ORIGINAL_BYTES =
            "ORIGINAL-HEIC-PAYLOAD-0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PREVIEW_BYTES = "PREVIEW-JPEG".getBytes(StandardCharsets.UTF_8);
    private static final long ORIGINAL_MTIME = 1_600_000_000_000L;
    private static final String MISSING_SHA =
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    private final List<File> mDirectories = new ArrayList<>();

    @After
    public void cleanUp() {
        for (File directory : mDirectories) {
            deleteTree(directory);
        }
        mDirectories.clear();
    }

    // ---------------------------------------------------------------- 成功路径

    /** 覆盖成功：原路径上就是预览字节、mtime 沿用原图、临时件被改名消费、DB 先于状态提交 */
    @Test
    public void publishOverwritesOriginalAndCommitsDatabase() throws Exception {
        Fixture fixture = fixture();

        InplacePreviewWriter.Outcome outcome =
                InplacePreviewWriter.publish(fixture.request, fixture.callbacks);

        assertTrue(outcome.success);
        assertEquals("", outcome.reason);
        assertFalse(outcome.restored);
        assertFalse("临时件应当被改名消费掉", fixture.staged.exists());
        assertArrayEquals(PREVIEW_BYTES, readBytes(fixture.original));
        assertEquals("截图/拍摄时间必须保留", ORIGINAL_MTIME, fixture.original.lastModified());

        assertEquals(1, fixture.callbacks.leaseCalls);
        assertEquals(1, fixture.callbacks.pendingCalls);
        assertEquals(PREVIEW_BYTES.length, fixture.callbacks.pendingLength);
        assertEquals(fixture.request.previewSha256, fixture.callbacks.pendingSha);
        assertEquals("heic", fixture.callbacks.pendingFormat);
        assertEquals(1, fixture.callbacks.commitCalls);
        assertEquals(fixture.original, fixture.callbacks.committedPath);
        assertEquals(1, fixture.callbacks.markInPlaceCalls);
        assertEquals(PREVIEW_BYTES.length, fixture.callbacks.inPlaceLength);
        assertEquals(fixture.request.previewSha256, fixture.callbacks.inPlaceSha);
        assertEquals("heic", fixture.callbacks.inPlaceFormat);
        assertEquals(0, fixture.callbacks.markDamagedCalls);
        assertEquals(0, fixture.callbacks.restoreCalls);
        // 提交顺序：租约 → 意图 → DB（这条顺序就是“宁可下次重试，也不留半成品”的全部内容）
        assertEquals("lease,pending,commit,mark", fixture.callbacks.order());
    }

    // ---------------------------------------------------------------- invalid_staged

    /** 临时件不见了：自检就失败，连租约都不去碰 */
    @Test
    public void missingStagedIsRejectedBeforeAnySideEffect() throws Exception {
        Fixture fixture = fixture();
        assertTrue(fixture.staged.delete());

        InplacePreviewWriter.Outcome outcome =
                InplacePreviewWriter.publish(fixture.request, fixture.callbacks);

        assertFalse(outcome.success);
        assertEquals("invalid_staged", outcome.reason);
        assertArrayEquals(ORIGINAL_BYTES, readBytes(fixture.original));
        assertEquals(0, fixture.callbacks.leaseCalls);
        assertEquals(0, fixture.callbacks.commitCalls);
    }

    /** 长度对不上（编码器写了一半）：同样在这条分支拦下，原文件不动 */
    @Test
    public void stagedLengthMismatchIsRejected() throws Exception {
        Fixture fixture = fixture();
        writeBytes(fixture.staged, concat(PREVIEW_BYTES, (byte) 0x01));

        InplacePreviewWriter.Outcome outcome =
                InplacePreviewWriter.publish(fixture.request, fixture.callbacks);

        assertFalse(outcome.success);
        assertEquals("invalid_staged", outcome.reason);
        assertArrayEquals(ORIGINAL_BYTES, readBytes(fixture.original));
        // 第 1 步失败时“什么都不动”：临时件留给调用方判断，原文件一个字节都没改
        assertTrue("自检失败不删临时件", fixture.staged.exists());
        assertEquals(0, fixture.callbacks.leaseCalls);
    }

    /** 摘要对不上（同长度但内容被换过）：长度检查过了也不能放行 */
    @Test
    public void stagedDigestMismatchIsRejected() throws Exception {
        Fixture fixture = fixture();
        writeBytes(fixture.staged, new byte[PREVIEW_BYTES.length]);
        assertEquals(PREVIEW_BYTES.length, fixture.staged.length());

        InplacePreviewWriter.Outcome outcome =
                InplacePreviewWriter.publish(fixture.request, fixture.callbacks);

        assertFalse(outcome.success);
        assertEquals("invalid_staged", outcome.reason);
        assertArrayEquals(ORIGINAL_BYTES, readBytes(fixture.original));
        assertEquals(0, fixture.callbacks.leaseCalls);
    }

    /** 登记摘要与实际摘要不符（调用方自己算错）：同样拦在自检 */
    @Test
    public void wrongExpectedDigestIsRejected() throws Exception {
        Fixture fixture = fixture();
        fixture.request.previewSha256 = MISSING_SHA;

        InplacePreviewWriter.Outcome outcome =
                InplacePreviewWriter.publish(fixture.request, fixture.callbacks);

        assertFalse(outcome.success);
        assertEquals("invalid_staged", outcome.reason);
        assertArrayEquals(ORIGINAL_BYTES, readBytes(fixture.original));
    }

    /** 临时件不在原文件同目录：跨目录 move 不是原子发布，必须在自检就拒绝 */
    @Test
    public void stagedOutsideOriginalDirectoryIsRejected() throws Exception {
        Fixture fixture = fixture();
        File elsewhere = new File(newDirectory(), "staged.heic");
        writeBytes(elsewhere, PREVIEW_BYTES);
        fixture.request.staged = elsewhere;
        fixture.request.previewSha256 = InplacePreviewWriter.sha256Of(elsewhere);
        fixture.callbacks.staged = elsewhere;

        InplacePreviewWriter.Outcome outcome =
                InplacePreviewWriter.publish(fixture.request, fixture.callbacks);

        assertFalse(outcome.success);
        assertEquals("invalid_staged", outcome.reason);
        assertArrayEquals(ORIGINAL_BYTES, readBytes(fixture.original));
        assertTrue(elsewhere.exists());
        assertTrue(fixture.staged.exists());
    }

    // ---------------------------------------------------------------- 三个“还没动字节”的失败

    /** 租约拿不到（有上传正在读这张）：删临时件，原图不动 */
    @Test
    public void leaseFailureDeletesStagedAndKeepsOriginal() throws Exception {
        Fixture fixture = fixture();
        fixture.callbacks.leaseGranted = false;

        InplacePreviewWriter.Outcome outcome =
                InplacePreviewWriter.publish(fixture.request, fixture.callbacks);

        assertFalse(outcome.success);
        assertEquals("lease_failed", outcome.reason);
        assertFalse(fixture.staged.exists());
        assertArrayEquals(ORIGINAL_BYTES, readBytes(fixture.original));
        assertEquals(0, fixture.callbacks.pendingCalls);
        assertEquals(0, fixture.callbacks.commitCalls);
    }

    /** 意图落不下去（注册表不可写）：绝不能动字节，删临时件收工 */
    @Test
    public void pendingFailureDeletesStagedAndKeepsOriginal() throws Exception {
        Fixture fixture = fixture();
        fixture.callbacks.pendingOk = false;

        InplacePreviewWriter.Outcome outcome =
                InplacePreviewWriter.publish(fixture.request, fixture.callbacks);

        assertFalse(outcome.success);
        assertEquals("pending_failed", outcome.reason);
        assertFalse(fixture.staged.exists());
        assertArrayEquals(ORIGINAL_BYTES, readBytes(fixture.original));
        assertEquals(0, fixture.callbacks.commitCalls);
    }

    /** 原子改名本身失败（目标是个非空目录）：删临时件，原文件（目录）原样保留 */
    @Test
    public void moveFailureDeletesStagedAndKeepsOriginal() throws Exception {
        File directory = newDirectory();
        File original = new File(directory, "IMG_1.heic");
        assertTrue(original.mkdir());
        assertTrue(new File(original, "keep.txt").createNewFile());
        File staged = new File(directory, ".IMG_1.heic.mgs-tmp-test");
        writeBytes(staged, PREVIEW_BYTES);
        Stub callbacks = new Stub(original, ORIGINAL_BYTES);
        callbacks.staged = staged;

        InplacePreviewWriter.Outcome outcome = InplacePreviewWriter.publish(
                request(original, staged, "heic", callbacks), callbacks);

        assertFalse(outcome.success);
        assertEquals("publish_failed", outcome.reason);
        assertFalse(staged.exists());
        assertTrue("原目标必须还是那个目录", original.isDirectory());
        assertTrue(new File(original, "keep.txt").isFile());
        assertEquals(0, callbacks.commitCalls);
    }

    // ---------------------------------------------------------------- 复核失败

    /** 复核失败但回填成功：复原因子交给“原文件是否真的复原”，不看回调自称 */
    @Test
    public void verifyFailureWithSuccessfulRestoreReportsRestored() throws Exception {
        Fixture fixture = fixture();
        fixture.callbacks.corruptStagedTo = concat(PREVIEW_BYTES, (byte) 0x7f);
        fixture.callbacks.restoreRewrites = true;

        InplacePreviewWriter.Outcome outcome =
                InplacePreviewWriter.publish(fixture.request, fixture.callbacks);

        assertFalse(outcome.success);
        assertEquals("verify_failed_restored", outcome.reason);
        assertTrue(outcome.restored);
        assertEquals(1, fixture.callbacks.restoreCalls);
        assertEquals(0, fixture.callbacks.commitCalls);
        assertArrayEquals("回填后原路径必须又是原图字节", ORIGINAL_BYTES,
                readBytes(fixture.original));
    }

    /** 复核失败且回填失败：明确报 restore_failed，绝不冒充“已恢复” */
    @Test
    public void verifyFailureWithoutRestoreReportsFailure() throws Exception {
        Fixture fixture = fixture();
        fixture.callbacks.corruptStagedTo = concat(PREVIEW_BYTES, (byte) 0x7f);
        fixture.callbacks.restoreRewrites = false;

        InplacePreviewWriter.Outcome outcome =
                InplacePreviewWriter.publish(fixture.request, fixture.callbacks);

        assertFalse(outcome.success);
        assertEquals("verify_failed_restore_failed", outcome.reason);
        assertFalse(outcome.restored);
        assertEquals(1, fixture.callbacks.restoreCalls);
        assertEquals(0, fixture.callbacks.commitCalls);
        // 回填失败时原路径上留着这次写下的（损坏的）字节，记录状态仍由调用方决定
        assertArrayEquals(fixture.callbacks.corruptStagedTo, readBytes(fixture.original));
    }

    // ---------------------------------------------------------------- DB 失败

    /** DB 没切过来：文件已是合法预览，不回滚、只标 DAMAGED（回滚要联网重下原图） */
    @Test
    public void databaseFailureMarksDamagedAndKeepsPreviewBytes() throws Exception {
        Fixture fixture = fixture();
        fixture.callbacks.commitOk = false;

        InplacePreviewWriter.Outcome outcome =
                InplacePreviewWriter.publish(fixture.request, fixture.callbacks);

        assertFalse(outcome.success);
        assertEquals("db_commit_failed", outcome.reason);
        assertFalse(outcome.restored);
        assertEquals(1, fixture.callbacks.markDamagedCalls);
        assertEquals("db_commit_failed", fixture.callbacks.damagedReason);
        assertEquals(0, fixture.callbacks.markInPlaceCalls);
        assertArrayEquals("字节已发布成功，不回滚", PREVIEW_BYTES, readBytes(fixture.original));
    }

    // ---------------------------------------------------------------- 记录访问器

    /** 新记录默认 ORIGINAL；withPreview 之后三个预览值经访问器可读回（hook/ 在别的包里） */
    @Test
    public void recordAccessorsExposeStateAndPreview() {
        OriginalStore.Record record = new OriginalStore.Record("target-key",
                "MiGallerySync/DCIM/Camera/IMG_1.jpg", 4096L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "/storage/emulated/0/DCIM/Camera/IMG_1.jpg", null, "row-1", "sha1-0001", 0L,
                Collections.emptySet());

        assertSame(OriginalStore.LocalState.ORIGINAL, record.getLocalState());
        assertEquals(0L, record.getPreviewLength());
        assertNull(record.getPreviewSha256());
        assertNull(record.getPreviewFormat());

        OriginalStore.Record previewed = record.withPreview(123L, "ab", "jpg");
        assertEquals(123L, previewed.getPreviewLength());
        assertEquals("ab", previewed.getPreviewSha256());
        assertEquals("jpg", previewed.getPreviewFormat());
        assertSame(OriginalStore.LocalState.ORIGINAL, previewed.getLocalState());
        // 不可变：原记录不受影响
        assertEquals(0L, record.getPreviewLength());
        assertNull(record.getPreviewSha256());
    }

    // ---------------------------------------------------------------- 夹具

    private static final class Fixture {
        File original;
        File staged;
        InplacePreviewWriter.Request request;
        Stub callbacks;
    }

    private Fixture fixture() throws IOException {
        File directory = newDirectory();
        File original = new File(directory, "IMG_20261117_101010.heic");
        writeBytes(original, ORIGINAL_BYTES);
        assertTrue("测试依赖可写的 mtime", original.setLastModified(ORIGINAL_MTIME));
        File staged = new File(directory, ".IMG_20261117_101010.heic.mgs-tmp-test");
        writeBytes(staged, PREVIEW_BYTES);

        Fixture fixture = new Fixture();
        fixture.original = original;
        fixture.staged = staged;
        fixture.callbacks = new Stub(original, ORIGINAL_BYTES);
        fixture.callbacks.staged = staged;
        fixture.request = request(original, staged, "heic", fixture.callbacks);
        return fixture;
    }

    private static InplacePreviewWriter.Request request(File original, File staged, String format,
            Stub callbacks) {
        InplacePreviewWriter.Request request = new InplacePreviewWriter.Request();
        request.original = original;
        request.originalLength = callbacks.originalBytes.length;
        request.originalSha256 = sha256(callbacks.originalBytes);
        request.staged = staged;
        request.previewLength = staged.length();
        request.previewSha256 = InplacePreviewWriter.sha256Of(staged);
        request.previewFormat = format;
        request.rowId = "row-1";
        return request;
    }

    /** 记录调用顺序，同时能按开关制造各种失败。 */
    private static final class Stub implements InplacePreviewWriter.Callbacks {
        private final File mOriginal;
        final byte[] originalBytes;
        File staged;
        boolean leaseGranted = true;
        boolean pendingOk = true;
        boolean commitOk = true;
        boolean restoreRewrites;
        byte[] corruptStagedTo;
        int leaseCalls;
        int pendingCalls;
        int commitCalls;
        int markInPlaceCalls;
        int markDamagedCalls;
        int restoreCalls;
        long pendingLength = -1L;
        String pendingSha;
        String pendingFormat;
        long inPlaceLength = -1L;
        String inPlaceSha;
        String inPlaceFormat;
        File committedPath;
        String damagedReason;
        private final StringBuilder mOrder = new StringBuilder();

        Stub(File original, byte[] originalBytes) {
            mOriginal = original;
            this.originalBytes = originalBytes;
        }

        String order() {
            return mOrder.toString();
        }

        private void record(String step) {
            if (mOrder.length() > 0) mOrder.append(',');
            mOrder.append(step);
        }

        @Override
        public boolean beginLease() {
            leaseCalls++;
            record("lease");
            return leaseGranted;
        }

        @Override
        public boolean markPending(long len, String sha256, String format) {
            pendingCalls++;
            record("pending");
            pendingLength = len;
            pendingSha = sha256;
            pendingFormat = format;
            if (corruptStagedTo != null && staged != null) {
                // 模拟“自检通过之后、改名之前字节被写坏”：只有这样才能让第 7 步复核失败
                try {
                    writeBytes(staged, corruptStagedTo);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return pendingOk;
        }

        @Override
        public boolean commitDatabase(File original) {
            commitCalls++;
            record("commit");
            committedPath = original;
            return commitOk;
        }

        @Override
        public void markInPlace(long len, String sha256, String format) {
            markInPlaceCalls++;
            record("mark");
            inPlaceLength = len;
            inPlaceSha = sha256;
            inPlaceFormat = format;
        }

        @Override
        public void markDamaged(String reason) {
            markDamagedCalls++;
            damagedReason = reason;
        }

        @Override
        public void restoreOriginal() {
            restoreCalls++;
            record("restore");
            if (restoreRewrites) {
                try {
                    writeBytes(mOriginal, originalBytes);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                mOriginal.setLastModified(ORIGINAL_MTIME);
            }
        }
    }

    // ---------------------------------------------------------------- 文件工具

    private File newDirectory() throws IOException {
        File directory = Files.createTempDirectory("mgs-inplace-").toFile();
        mDirectories.add(directory);
        return directory;
    }

    private static void writeBytes(File file, byte[] bytes) throws IOException {
        Files.write(file.toPath(), bytes);
    }

    private static byte[] readBytes(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    private static byte[] concat(byte[] head, byte tail) {
        byte[] result = new byte[head.length + 1];
        System.arraycopy(head, 0, result, 0, head.length);
        result[head.length] = tail;
        return result;
    }

    /** 测试用的摘要：与生产实现同源，避免两套十六进制写法互相“验证” */
    private static String sha256(byte[] bytes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder value = new StringBuilder(64);
            for (byte b : digest.digest(bytes)) {
                value.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            }
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        // 测试清理失败不影响断言结果
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
