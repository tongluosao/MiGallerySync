package com.tongluosao.gallerysync.core;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 载荷闸门的 JVM 侧回归：2026-10 事故里 80 行 cloud 记录指向全 0x00 的 .jpg，
 * 其中 24 条还被登记成了正常原件。这些用例把"哪些内容绝不允许上传/发布/登记"钉住。
 */
public class MediaPayloadTest {

    private final List<Path> mTemps = new ArrayList<>();

    @After
    public void cleanup() {
        for (Path path : mTemps) {
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
                // 临时文件清理失败不影响断言结果
            }
        }
        mTemps.clear();
    }

    // ---- 全 0 / 截断 ----------------------------------------------------------

    @Test
    public void zeroFilledJpgIsRejected() throws Exception {
        File file = write(".jpg", new byte[8192]);
        assertFalse(MediaPayload.isUsableMedia(file));
        assertTrue(MediaPayload.looksInvalid(file));
    }

    @Test
    public void jpgWithoutEoiIsRejected() throws Exception {
        // SOI 在，但没有 EOI：事发现场那种"字节数正确、内容根本不是 JPEG"的文件
        byte[] bytes = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00, 0x00, 0x01};
        File file = write(".jpg", bytes);
        assertFalse(MediaPayload.hasValidSignature(file));
        assertFalse(MediaPayload.isUsableMedia(file));
    }

    @Test
    public void validMinimalJpgIsAccepted() throws Exception {
        byte[] bytes = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00, 0x00, 0x01,
                0x00, 0x00, (byte) 0xFF, (byte) 0xD9};
        File file = write(".jpg", bytes);
        assertTrue(MediaPayload.hasValidSignature(file));
        assertTrue(MediaPayload.isUsableMedia(file));
    }

    // ---- HEIC / HEIF ---------------------------------------------------------

    @Test
    public void heicWithFtypAndBrandIsAccepted() throws Exception {
        File file = write(".heic", heicBytes("heic"));
        assertTrue(MediaPayload.hasValidSignature(file));
        assertTrue(MediaPayload.isUsableMedia(file));
    }

    @Test
    public void heicWithoutFtypIsRejected() throws Exception {
        // 只有品牌字符串，没有 ftyp box：截断或内容与扩展名不符
        File file = write(".heic", "heic0000mif1".getBytes(StandardCharsets.US_ASCII));
        assertFalse(MediaPayload.hasValidSignature(file));
        assertFalse(MediaPayload.isUsableMedia(file));
    }

    @Test
    public void heicWithUnknownBrandIsRejected() throws Exception {
        // 既不是已知主品牌，兼容品牌表里也没有 heic：不是可处理的 HEIF
        File file = write(".heic", heicBytes("abcd", "abcd"));
        assertFalse(MediaPayload.hasValidSignature(file));
        assertFalse(MediaPayload.isUsableMedia(file));
    }

    // ---- PNG / WEBP / BMP / MP4 ---------------------------------------------

    @Test
    public void pngWebpBmpAndMp4SignaturesAreAccepted() throws Exception {
        byte[] pngHead = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'};
        assertTrue(MediaPayload.isUsableMedia(write(".png", pngHead)));

        byte[] webp = new byte[32];
        put(webp, 0, "RIFF");
        put(webp, 4, new byte[] {0x14, 0x00, 0x00, 0x00});
        put(webp, 8, "WEBP");
        assertTrue(MediaPayload.isUsableMedia(write(".webp", webp)));

        byte[] bmp = new byte[] {'B', 'M', 0x36, 0x00, 0x00, 0x00, 0x00, 0x00};
        assertTrue(MediaPayload.isUsableMedia(write(".bmp", bmp)));

        byte[] mp4 = new byte[32];
        put(mp4, 4, "ftyp");
        put(mp4, 8, "mp42");
        assertTrue(MediaPayload.isUsableMedia(write(".mp4", mp4)));
        assertTrue(MediaPayload.isUsableMedia(write(".mov", mp4)));
    }

    @Test
    public void signatureMismatchWithExtensionIsRejected() throws Exception {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'};
        assertFalse(MediaPayload.isUsableMedia(write(".jpg", png)));
        assertFalse(MediaPayload.isUsableMedia(write(".webp", png)));
    }

    @Test
    public void emptyFileIsRejected() throws Exception {
        File file = write(".jpg", new byte[0]);
        assertFalse(MediaPayload.isUsableMedia(file));
    }

    // ---- 未知扩展名 ----------------------------------------------------------

    @Test
    public void unknownExtensionIsAccepted() throws Exception {
        File file = write(".dat", "arbitrary payload".getBytes(StandardCharsets.US_ASCII));
        assertTrue(MediaPayload.hasValidSignature(file));
        assertTrue(MediaPayload.isUsableMedia(file));
    }

    // ---- isAllZero -----------------------------------------------------------

    @Test
    public void isAllZeroHonoursLength() {
        byte[] head = new byte[] {0, 0, 0, 0, 0x01};
        assertTrue(MediaPayload.isAllZero(head, 4));
        assertFalse(MediaPayload.isAllZero(head, 5));
        assertFalse(MediaPayload.isAllZero(new byte[0], 0));
        assertFalse(MediaPayload.isAllZero(null, 4));
    }

    // ---- isZeroDigest --------------------------------------------------------

    @Test
    public void zeroDigestMatchesSha256OfZeroBytes() throws Exception {
        long length = 4096L;
        assertTrue(MediaPayload.isZeroDigest(sha256(new byte[(int) length]), length));
        // 大写摘要同样要认
        assertTrue(MediaPayload.isZeroDigest(
                sha256(new byte[(int) length]).toUpperCase(Locale.US), length));
    }

    @Test
    public void realDigestIsNotAZeroDigest() throws Exception {
        byte[] content = "real photo bytes".getBytes(StandardCharsets.US_ASCII);
        assertFalse(MediaPayload.isZeroDigest(sha256(content), content.length));
        // 长度对不上时也不能碰巧判成 true
        assertFalse(MediaPayload.isZeroDigest(sha256(content), content.length + 1L));
    }

    @Test
    public void hugeLengthsAreNeverTreatedAsZeroDigest() {
        String anyDigest = "0".repeat(64);
        assertFalse(MediaPayload.isZeroDigest(anyDigest, 64L * 1024L * 1024L + 1L));
        assertFalse(MediaPayload.isZeroDigest(null, 16L));
        assertFalse(MediaPayload.isZeroDigest("not-a-digest", 16L));
    }

    // ---- helpers -------------------------------------------------------------

    private File write(String suffix, byte[] bytes) throws Exception {
        Path path = Files.createTempFile("media-payload-", suffix);
        Files.write(path, bytes);
        mTemps.add(path);
        File file = path.toFile();
        assertTrue(file.isFile());
        return file;
    }

    private static byte[] heicBytes(String majorBrand) {
        return heicBytes(majorBrand, "mif1");
    }

    private static byte[] heicBytes(String majorBrand, String compatibleBrand) {
        byte[] brand = majorBrand.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[24];
        put(out, 4, "ftyp");
        put(out, 8, brand);
        put(out, 16, compatibleBrand);
        return out;
    }

    private static void put(byte[] target, int offset, String value) {
        put(target, offset, value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void put(byte[] target, int offset, byte[] value) {
        System.arraycopy(value, 0, target, offset, value.length);
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] value = digest.digest(bytes);
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) {
            out.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return out.toString();
    }
}
