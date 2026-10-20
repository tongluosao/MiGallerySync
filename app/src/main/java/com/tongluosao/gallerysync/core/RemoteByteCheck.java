package com.tongluosao.gallerysync.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 传完之后问一句："远端那份字节，真的和我发出去的一模一样吗？"
 *
 * <p>为什么需要它：上传收尾时的复核（{@link WebDavClient#requireStoredAs}）只比
 * <b>名字和长度</b>。{@code event=upload_ok} 之后写下的远端旁车（{@link RemoteHashes}）
 * 里那个摘要，是发送之前对<b>本地文件</b>算的（{@code UploadQueue} 里的
 * {@code uploadedSha256}）—— 它证明的是"我发了什么"，不是"服务器收成了什么"。
 * 空间优化会删掉本地原图，所以这个空格必须在删之前补上：否则上传链路一旦把字节
 * 弄坏而长度不变（截断后补零、填充），旁车就会把坏件盖章成合格，原图再也没了。
 *
 * <p>代价与取舍（用户 2026-10-20 决定）：WebDAV/OpenList 上传本身就带传输层与对象层
 * 校验（PUT 只有真正落盘才回 201/204，S3 后端还会比 MD5），所以这里不必整份重读。
 * {@link #FULL_CHECK_LIMIT} 以内整份回读（反正很小）；更大的文件只读**头尾各
 * {@link #SAMPLE_CHUNK}**——截断、补零、只写了开头这几种最现实的损坏都会被抓住，
 * 代价固定 200 KiB。
 */
public final class RemoteByteCheck {

    /** 这个大小以内整份回读算 SHA-256；更大的文件只读头尾两段。 */
    public static final long FULL_CHECK_LIMIT = 256L * 1024L;

    /** 大文件抽样时头/尾各读的长度。 */
    public static final long SAMPLE_CHUNK = 100L * 1024L;

    private RemoteByteCheck() {
    }

    /** 小文件整份校验，大文件抽样校验。 */
    public static boolean isFullCheck(long size) {
        return size <= FULL_CHECK_LIMIT;
    }

    /**
     * 抽样位置：头一段 + 尾一段。文件比一段还小时只返回 {@code {0}}——此时头尾本来就
     * 重合，读 {@code min(chunk, size)} 个字节就是整份。
     */
    public static long[] sampleOffsets(long size, long chunk) {
        if (size <= 0L || chunk <= 0L) {
            return new long[0];
        }
        if (size <= chunk) {
            return new long[]{0L};
        }
        return new long[]{0L, size - chunk};
    }

    /**
     * 校验远端字节与本地 {@code local} 一致。
     *
     * @param expectedSha256 本地整份的 SHA-256；给 null 时自己算一遍本地文件
     * @return true 表示"字节逐字相同"（大文件是抽样相同的意义下）
     */
    public static boolean verify(WebDavClient client, String remotePath, File local, long size,
            String expectedSha256) throws IOException {
        if (client == null || remotePath == null || remotePath.isEmpty()
                || local == null || !local.isFile() || size <= 0L) {
            return false;
        }
        if (local.length() != size) {
            return false;
        }
        if (isFullCheck(size)) {
            String expected = expectedSha256;
            if (expected == null || expected.length() != 64) {
                expected = OriginalStore.sha256(local);
            }
            return expected != null && expected.equalsIgnoreCase(sha256OfRemote(client, remotePath,
                    0L, size));
        }
        for (long offset : sampleOffsets(size, SAMPLE_CHUNK)) {
            long length = Math.min(SAMPLE_CHUNK, size - offset);
            String remote = sha256OfRemote(client, remotePath, offset, length);
            String mine = sha256OfLocalRange(local, offset, length);
            if (remote == null || !remote.equalsIgnoreCase(mine)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把“这次核对过的头尾两段”算成可写进旁车的证据。
     *
     * <p>两段不重叠是硬要求（{@link RemoteHashes#formatChunked} 会拒掉重叠的写法）：
     * 文件不足两段时退化成“一段覆盖整份 + 长度为 0 的尾段”，语义上仍是“整份核对过”。
     */
    public static RemoteHashes.Entry chunkedEntry(File local, String sha256) throws IOException {
        if (local == null || !local.isFile() || local.length() <= 0L || sha256 == null
                || sha256.length() != 64) {
            return null;
        }
        long size = local.length();
        if (size <= 2L * SAMPLE_CHUNK) {
            String whole = sha256OfLocalRange(local, 0L, size);
            return whole == null ? null
                    : new RemoteHashes.Entry(sha256, size, whole, size, whole, 0L);
        }
        String head = sha256OfLocalRange(local, 0L, SAMPLE_CHUNK);
        String tail = sha256OfLocalRange(local, size - SAMPLE_CHUNK, SAMPLE_CHUNK);
        if (head == null || tail == null) {
            return null;
        }
        return new RemoteHashes.Entry(sha256, size, head, SAMPLE_CHUNK, tail, SAMPLE_CHUNK);
    }

    /** 读远端 {@code [offset, offset+length)} 并算 SHA-256；读到的字节数不对就当失败。 */
    static String sha256OfRemote(WebDavClient client, String remotePath, long offset, long length)
            throws IOException {
        if (length <= 0L) {
            return null;
        }
        WebDavClient.RangeResponse response =
                client.streamRange(remotePath, offset, offset + length - 1L);
        if (response == null) {
            return null;
        }
        long expectedBytes = length;
        try (InputStream in = response.body()) {
            if (in == null) {
                return null;
            }
            MessageDigest digest = newDigest();
            byte[] buffer = new byte[64 * 1024];
            long total = 0L;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > expectedBytes) {
                    // 服务器给了比要的还多：这一段不可信，直接判失败
                    return null;
                }
                digest.update(buffer, 0, read);
            }
            if (total != expectedBytes) {
                return null;
            }
            return hex(digest.digest());
        }
    }

    /** 本地文件 {@code [offset, offset+length)} 的 SHA-256。 */
    static String sha256OfLocalRange(File file, long offset, long length) throws IOException {
        if (file == null || !file.isFile() || offset < 0L || length <= 0L) {
            return null;
        }
        if (offset + length > file.length()) {
            return null;
        }
        MessageDigest digest = newDigest();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0L) {
                int want = (int) Math.min(buffer.length, remaining);
                int read = raf.read(buffer, 0, want);
                if (read <= 0) {
                    return null;
                }
                digest.update(buffer, 0, read);
                remaining -= read;
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String hex(byte[] value) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) {
            out[i * 2] = digits[(value[i] >> 4) & 0x0f];
            out[i * 2 + 1] = digits[value[i] & 0x0f];
        }
        return new String(out);
    }
}
