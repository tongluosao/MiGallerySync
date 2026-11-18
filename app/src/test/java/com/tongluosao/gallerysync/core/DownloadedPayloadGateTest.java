package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 下载载荷闸门的单测：真机那次 {@code cannot register invalid payload} 就发生在这一层。
 *
 * <p>重点钉住三件事：
 * <ol>
 *   <li>判据按<b>最终路径</b>的扩展名，不按 {@code .tmp} 临时名；</li>
 *   <li>0 字节 / 首窗全 0 / 截断 / 签名不符 分别给出可分类的结论；</li>
 *   <li>JPEG 的 EOI 允许在尾部 64KiB 内（旧的 4KiB 判据会误杀带尾数据的合法图）。</li>
 * </ol>
 */
public class DownloadedPayloadGateTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void validJpegPasses() throws IOException {
        File staged = write(".photo.jpg.mgs-1234.tmp", jpeg(4096));
        File dest = temporaryFolder.newFile("unused-name-placeholder.jpg");
        assertEquals(DownloadedPayloadGate.Verdict.OK,
                DownloadedPayloadGate.inspect(staged, new File(dest.getParentFile(), "photo.jpg"),
                        staged.length()));
    }

    /** 现场故障：staged 名是 bash 的 .tmp，最终目标是 .jpg。必须按最终名判。 */
    @Test
    public void extensionComesFromFinalPathNotStagedName() throws IOException {
        File staged = write(".photo.jpg.mgs-1234.tmp", jpeg(2048));
        File finalPath = new File(temporaryFolder.getRoot(), "photo.jpg");
        assertEquals(DownloadedPayloadGate.Verdict.OK,
                DownloadedPayloadGate.inspect(staged, finalPath, staged.length()));

        // 同样是 .tmp 外壳，但内容是 PNG、目标是 .jpg：按最终名判就该拒
        File pngInTmp = write(".shot.jpg.mgs-9999.tmp", png(1024));
        assertEquals(DownloadedPayloadGate.Verdict.NO_SIGNATURE,
                DownloadedPayloadGate.inspect(pngInTmp,
                        new File(temporaryFolder.getRoot(), "shot.jpg"), 0L));
    }

    @Test
    public void emptyAndMissingFileAreRejected() throws IOException {
        File empty = write("empty.jpg", new byte[0]);
        assertEquals(DownloadedPayloadGate.Verdict.EMPTY,
                DownloadedPayloadGate.inspect(empty, empty, 0L));
        assertEquals(DownloadedPayloadGate.Verdict.EMPTY,
                DownloadedPayloadGate.inspect(new File(temporaryFolder.getRoot(), "nope.jpg"),
                        null, 0L));
        assertEquals(DownloadedPayloadGate.Verdict.EMPTY,
                DownloadedPayloadGate.inspect(null, null, 0L));
    }

    /** 中间层返回全 0（长度还是对的）——这是现场最可能的坏字节形态。 */
    @Test
    public void allZeroHeadIsRejected() throws IOException {
        File staged = write(".zero.jpg.mgs-1.tmp", new byte[128 * 1024]);
        assertEquals(DownloadedPayloadGate.Verdict.ALL_ZERO,
                DownloadedPayloadGate.inspect(staged, new File(temporaryFolder.getRoot(), "zero.jpg"),
                        128L * 1024L));
    }

    @Test
    public void truncatedPayloadIsShort() throws IOException {
        File staged = write(".cut.jpg.mgs-2.tmp", jpeg(1000));
        assertEquals(DownloadedPayloadGate.Verdict.SHORT,
                DownloadedPayloadGate.inspect(staged, new File(temporaryFolder.getRoot(), "cut.jpg"),
                        5000L));
    }

    @Test
    public void wrongSignatureIsRejected() throws IOException {
        File staged = write("fake.jpg", "this is definitely not a jpeg payload at all"
                .getBytes(StandardCharsets.US_ASCII));
        assertEquals(DownloadedPayloadGate.Verdict.NO_SIGNATURE,
                DownloadedPayloadGate.inspect(staged, staged, 0L));
    }

    /** 未知扩展名一律放行：闸门只拦"媒体名 + 坏内容"，不拦普通文件。 */
    @Test
    public void unknownExtensionPasses() throws IOException {
        File staged = write("notes.txt", "hello".getBytes(StandardCharsets.US_ASCII));
        assertEquals(DownloadedPayloadGate.Verdict.OK,
                DownloadedPayloadGate.inspect(staged, staged, 0L));
        File noext = write("README", "hello".getBytes(StandardCharsets.US_ASCII));
        assertEquals(DownloadedPayloadGate.Verdict.OK,
                DownloadedPayloadGate.inspect(noext, noext, 0L));
    }

    /**
     * EOI 落在最后 4KiB 之外、64KiB 之内：合法 JPEG（后面跟着 40KB 的附属数据），
     * 旧判据会误杀，新判据放行。
     */
    @Test
    public void jpegEoiBeyondFourKilobytesStillPasses() throws IOException {
        byte[] bytes = new byte[200_000];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        int eoi = bytes.length - 40_000;
        bytes[eoi] = (byte) 0xFF;
        bytes[eoi + 1] = (byte) 0xD9;
        File staged = write("wide.jpg", bytes);
        // 先确认这份数据确实不在"最后 4KiB"里，否则这条测试没有意义
        assertTrue(eoi < bytes.length - 4096);
        assertEquals(DownloadedPayloadGate.Verdict.OK,
                DownloadedPayloadGate.inspect(staged, staged, bytes.length));
    }

    @Test
    public void containerSignaturesAreRecognized() throws IOException {
        byte[] heic = new byte[64];
        heic[4] = 'f';
        heic[5] = 't';
        heic[6] = 'y';
        heic[7] = 'p';
        assertEquals(DownloadedPayloadGate.Verdict.OK,
                DownloadedPayloadGate.inspect(write("a.heic", heic),
                        new File(temporaryFolder.getRoot(), "a.heic"), 64L));

        byte[] mkv = new byte[64];
        mkv[0] = 0x1A;
        mkv[1] = 0x45;
        mkv[2] = (byte) 0xDF;
        mkv[3] = (byte) 0xA3;
        assertEquals(DownloadedPayloadGate.Verdict.OK,
                DownloadedPayloadGate.inspect(write("a.mkv", mkv),
                        new File(temporaryFolder.getRoot(), "a.mkv"), 64L));

        byte[] bogusMp4 = new byte[64];
        bogusMp4[0] = 1;
        assertEquals(DownloadedPayloadGate.Verdict.NO_SIGNATURE,
                DownloadedPayloadGate.inspect(write("b.mp4", bogusMp4),
                        new File(temporaryFolder.getRoot(), "b.mp4"), 64L));
    }

    @Test
    public void requirePublishableThrowsClassifiedIOException() throws IOException {
        File staged = write(".zero.jpg.mgs-3.tmp", new byte[70 * 1024]);
        File dest = new File(temporaryFolder.getRoot(), "Screenshot_1.jpg");
        try {
            DownloadedPayloadGate.requirePublishable(staged, dest, 70L * 1024L);
            fail("all-zero payload must be rejected");
        } catch (DownloadFailurePolicy.PayloadException e) {
            assertEquals(DownloadFailurePolicy.Cause.INVALID_PAYLOAD, e.cause());
            // 有意变更：内容损坏从 RETRYABLE 改成 TERMINAL（服务端那份字节不会自己变好）。
            // 队列侧在第一次确认时就写 DamagedRemotes 并让同路径任务直接退出，
            // 这里把"分类语义"钉死在终态，避免以后又退回"每分钟重下一份"。
            assertEquals(DownloadFailurePolicy.Kind.TERMINAL, e.kind());
            assertTrue(e instanceof IOException);
            assertTrue(e.getMessage().contains("reason=all_zero"));
            assertTrue(e.getMessage().contains("dest=" + dest.getAbsolutePath()));
            assertTrue(e.getMessage().contains("bytes=71680"));
        }
    }

    @Test
    public void requirePublishableAcceptsGoodPayload() throws IOException {
        File staged = write(".ok.jpg.mgs-4.tmp", jpeg(1024));
        DownloadedPayloadGate.requirePublishable(staged,
                new File(temporaryFolder.getRoot(), "ok.jpg"), staged.length());
    }

    @Test
    public void verdictAndCauseMappingIsStable() {
        assertEquals(DownloadFailurePolicy.Cause.EMPTY_PAYLOAD,
                DownloadedPayloadGate.causeOf(DownloadedPayloadGate.Verdict.EMPTY));
        assertEquals(DownloadFailurePolicy.Cause.LENGTH_MISMATCH,
                DownloadedPayloadGate.causeOf(DownloadedPayloadGate.Verdict.SHORT));
        assertEquals(DownloadFailurePolicy.Cause.INVALID_PAYLOAD,
                DownloadedPayloadGate.causeOf(DownloadedPayloadGate.Verdict.ALL_ZERO));
        assertEquals(DownloadFailurePolicy.Cause.INVALID_PAYLOAD,
                DownloadedPayloadGate.causeOf(DownloadedPayloadGate.Verdict.NO_SIGNATURE));
        assertEquals(DownloadFailurePolicy.Cause.IO,
                DownloadedPayloadGate.causeOf(DownloadedPayloadGate.Verdict.UNREADABLE));
        assertEquals(DownloadFailurePolicy.Cause.UNKNOWN,
                DownloadedPayloadGate.causeOf(null));
        assertTrue(DownloadedPayloadGate.Verdict.OK.ok());
        assertFalse(DownloadedPayloadGate.Verdict.SHORT.ok());
    }

    @Test
    public void extensionOfHandlesHiddenAndTrailingDots() {
        assertEquals("jpg", DownloadedPayloadGate.extensionOf("Screenshot_1.jpg"));
        assertEquals("jpg", DownloadedPayloadGate.extensionOf("A.JPG"));
        // staging 名：最后一段是 tmp —— 这正是"按临时名判等于没判"的原因
        assertEquals("tmp", DownloadedPayloadGate.extensionOf(".Screenshot_1.jpg.mgs-abc.tmp"));
        assertEquals("", DownloadedPayloadGate.extensionOf("noext"));
        assertEquals("", DownloadedPayloadGate.extensionOf("trailing."));
        assertEquals("", DownloadedPayloadGate.extensionOf(null));
    }

    private File write(String name, byte[] bytes) throws IOException {
        File file = new File(temporaryFolder.getRoot(), name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        return file;
    }

    private static byte[] jpeg(int size) {
        byte[] bytes = new byte[Math.max(size, 4)];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[bytes.length - 2] = (byte) 0xFF;
        bytes[bytes.length - 1] = (byte) 0xD9;
        return bytes;
    }

    private static byte[] png(int size) {
        byte[] bytes = new byte[Math.max(size, 8)];
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        return bytes;
    }
}
