package com.tongluosao.gallerysync.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 介质内容闸门：判断一个待上传/待发布/待登记的文件里**到底有没有有效图像字节**。
 *
 * <p>由来（2026-10 真机取证）：相册 cloud 表里有 80 行指向全 0x00 的 {@code .jpg}
 * （字节数正确，但没有 SOI/EOI），其中 24 条在 {@code webdav_sync/originals.json} 里
 * 都有登记记录，{@code sha256} 恰好等于 SHA256(N 个 0)。也就是说模块把"空内容"
 * 当成正常照片指纹化并上传了。这份闸门只做纯静态判断，不依赖任何 Android API，
 * 便于在 JVM 单测里直接覆盖。
 *
 * <p>性能约定：照片是几 MB 级，绝不整份读入。判定只采样**首 64KiB** 与**尾 4KiB**，
 * 两者足够判掉全 0、截断与"根本不是这种格式"三类事故。
 */
public final class MediaPayload {

    /** 采样窗口：首部字节（签名区）与尾部字节（JPEG EOI 区）。 */
    private static final int HEAD_BYTES = 64 * 1024;
    private static final int TAIL_BYTES = 4 * 1024;

    /** 超过这个长度不再预计算"全 0 摘要"：那段计算本身已不比一次上传便宜。 */
    private static final long MAX_ZERO_DIGEST_BYTES = 64L * 1024L * 1024L;

    /** SHA256(N 个 0) 的缓存，按长度索引；只会被 <= 64MiB 的长度写入。 */
    private static final Map<Long, String> ZERO_DIGESTS = new ConcurrentHashMap<>();

    private static final byte[] FTYP = ascii("ftyp");
    private static final byte[] PNG_SIGNATURE = new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final byte[][] HEIF_BRANDS = new byte[][] {
            ascii("heic"), ascii("heix"), ascii("hevc"), ascii("mif1"),
            ascii("msf1"), ascii("heim"), ascii("heis")
    };

    private MediaPayload() {
    }

    /**
     * 采样字节是否全为 0。
     *
     * @param head   采样得到的字节
     * @param length 要检查的有效长度（超过数组长度时按数组长度算）
     * @return true 表示有内容且全部为 0x00；空数组或 null 返回 false（没有证据不下结论）
     */
    public static boolean isAllZero(byte[] head, int length) {
        if (head == null || length <= 0) {
            return false;
        }
        int count = Math.min(length, head.length);
        if (count <= 0) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            if (head[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 只看内容的"空/全 0"判定：首 64KiB 与尾 4KiB 都为 0 即认定为无效载荷。
     * 小文件（两个窗口重叠）等价于"整份内容全 0"。
     */
    public static boolean looksInvalid(File file) {
        if (file == null || !file.isFile() || file.length() <= 0L) {
            return true;
        }
        try {
            Sample sample = sample(file);
            return isAllZero(sample.head, sample.head.length)
                    && isAllZero(sample.tail, sample.tail.length);
        } catch (IOException e) {
            // 读不出来就不放行：闸门的默认方向是拒绝
            return true;
        }
    }

    /**
     * 文件头/尾是否带有该扩展名应有的签名。
     *
     * <p>未知扩展名一律返回 true（不拦非媒体文件）；已知扩展名缺签名则返回 false，
     * 覆盖"全 0"、"被截断"、"扩展名与实际内容不符"三种情况。
     */
    public static boolean hasValidSignature(File file) {
        if (file == null || !file.isFile() || file.length() <= 0L) {
            return false;
        }
        try {
            Sample sample = sample(file);
            return hasSignature(extensionOf(file.getName()), sample.head, sample.tail);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 这个文件能不能当成一份媒体内容去上传 / 发布 / 登记。
     *
     * <p>返回 false 的情形正是事故现场那几类：空文件、全 0 文件、缺少签名或被截断的
     * 已知格式。扩展名未知的文件不拦。
     */
    public static boolean isUsableMedia(File file) {
        if (file == null || !file.isFile() || file.length() <= 0L) {
            return false;
        }
        try {
            Sample sample = sample(file);
            boolean allZero = isAllZero(sample.head, sample.head.length)
                    && isAllZero(sample.tail, sample.tail.length);
            if (allZero) {
                return false;
            }
            return hasSignature(extensionOf(file.getName()), sample.head, sample.tail);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 已知扩展名的签名规则；未知扩展名返回 true（不拦非媒体文件）。
     * 已知扩展名缺签名则返回 false，覆盖"全 0"、"被截断"、"扩展名与实际内容不符"三种情况。
     */
    private static boolean hasSignature(String extension, byte[] head, byte[] tail) {
        switch (extension) {
            case "jpg":
            case "jpeg":
                // SOI 必须在 0 偏移；EOI 落在尾部窗口内（JPEG 允许尾部有零星填充）
                return matches(head, 0, new byte[] {(byte) 0xFF, (byte) 0xD8})
                        && indexOf(tail, new byte[] {(byte) 0xFF, (byte) 0xD9}) >= 0;
            case "heic":
            case "heif":
                return head.length >= 12 && matches(head, 4, FTYP) && hasHeifBrand(head);
            case "png":
                return matches(head, 0, PNG_SIGNATURE);
            case "webp":
                return head.length >= 12 && matches(head, 0, ascii("RIFF"))
                        && matches(head, 8, ascii("WEBP"));
            case "bmp":
                return matches(head, 0, new byte[] {'B', 'M'});
            case "mp4":
            case "mov":
                return head.length >= 12 && matches(head, 4, FTYP);
            default:
                return true;
        }
    }

    /**
     * 摘要是不是"长度 bytes 的全 0 内容"的 SHA-256。
     *
     * <p>事故里 24 条登记记录的 sha256 正是这个值。计算按长度缓存（最多 64MiB），
     * 超长直接返回 false —— 那种长度的上传失败与"全 0 内容"无关。
     *
     * @param sha256 16 进制摘要，大小写不敏感
     * @param length 该摘要对应的字节长度
     */
    public static boolean isZeroDigest(String sha256, long length) {
        if (sha256 == null || sha256.length() != 64 || length <= 0L
                || length > MAX_ZERO_DIGEST_BYTES) {
            return false;
        }
        return zeroDigest(length).equals(sha256.toLowerCase(Locale.US));
    }

    /** 只取首 64KiB 与尾 4KiB；小文件两个窗口按实际长度截断（可能重叠）。 */
    private static Sample sample(File file) throws IOException {
        long length = file.length();
        if (length <= 0L) {
            return new Sample(new byte[0], new byte[0]);
        }
        int headLength = (int) Math.min(HEAD_BYTES, length);
        int tailLength = (int) Math.min(TAIL_BYTES, length);
        byte[] head = new byte[headLength];
        byte[] tail = new byte[tailLength];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.readFully(head);
            if (tailLength > 0) {
                raf.seek(length - tailLength);
                raf.readFully(tail);
            }
        }
        return new Sample(head, tail);
    }

    private static String zeroDigest(long length) {
        String cached = ZERO_DIGESTS.get(length);
        if (cached != null) {
            return cached;
        }
        MessageDigest digest = newDigest();
        byte[] zeros = new byte[64 * 1024];
        long remaining = length;
        while (remaining > 0L) {
            int chunk = (int) Math.min(zeros.length, remaining);
            digest.update(zeros, 0, chunk);
            remaining -= chunk;
        }
        String value = hex(digest.digest());
        ZERO_DIGESTS.put(length, value);
        return value;
    }

    /**
     * HEIF 的品牌判定：主品牌在 8 偏移，兼容品牌表从 16 偏移起每 4 字节一个。
     * 从 8 起按 4 字节步进扫描，等于同时接受"主品牌"和"兼容品牌里声明的 heic"。
     */
    private static boolean hasHeifBrand(byte[] head) {
        for (int offset = 8; offset + 4 <= head.length; offset += 4) {
            for (byte[] brand : HEIF_BRANDS) {
                if (matches(head, offset, brand)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matches(byte[] data, int offset, byte[] want) {
        if (data == null || want == null || offset < 0 || offset + want.length > data.length) {
            return false;
        }
        for (int i = 0; i < want.length; i++) {
            if (data[offset + i] != want[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] data, byte[] want) {
        if (data == null || want == null || want.length == 0 || data.length < want.length) {
            return -1;
        }
        int limit = data.length - want.length;
        for (int i = 0; i <= limit; i++) {
            if (matches(data, i, want)) {
                return i;
            }
        }
        return -1;
    }

    private static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return out.toString();
    }

    /** 一次采样同时带回首部与尾部窗口，避免同一个文件被打开两次。 */
    private static final class Sample {
        final byte[] head;
        final byte[] tail;

        Sample(byte[] head, byte[] tail) {
            this.head = head;
            this.tail = tail;
        }
    }
}
