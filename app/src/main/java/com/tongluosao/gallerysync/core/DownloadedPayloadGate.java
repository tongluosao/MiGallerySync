package com.tongluosao.gallerysync.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Locale;

/**
 * 下载载荷闸门：一份"刚下完的字节"能不能被登记 / 发布进相册。
 *
 * <p>这是 2026-11 真机故障的直接修复点。现场日志：
 *
 * <pre>
 * event=auto_download_progress_publish row=1179 bytes=355235/355235 state=failed
 *   reason=java.lang.IllegalStateException: cannot register invalid payload
 *   role=downloaded_original path=/storage/emulated/0/DCIM/Screenshots/.Screenshot_….jpg.mgs-7580544c-….tmp
 * </pre>
 *
 * <p>字节数已经对上了，登记却被拒。三个问题叠在一起：
 *
 * <ol>
 *   <li><b>用临时文件名判类型</b>：登记闸门吃的是 staging 文件（{@code .jpg.mgs-….tmp}），
 *       扩展名是 {@code tmp}，签名规则对未知扩展名一律放行，于是"真正的媒体校验"其实没做；
 *       换成已知扩展名时又会因为临时名里带着原后缀而误判。必须按<b>最终路径</b>的扩展名判。</li>
 *   <li><b>全 0 内容判不出来</b>：旧判据要求首 64KiB 与尾 4KiB <b>同时</b>全 0 才拒。中间层
 *       返回全 0 时长度是对的、尾部也常常是 0，但真正的加固是"首 64KiB 全 0 就直接拒" ——
 *       任何相册媒体都不可能以 64KiB 的 0 开头。</li>
 *   <li><b>抛的是 unchecked 异常</b>：{@code IllegalStateException} 落进
 *       {@code catch (Throwable)} 分支，只记一行 {@code download_error}，不进
 *       "可重试/不可重试"的分类，于是一直按可重试处理、反复重下。</li>
 * </ol>
 *
 * <p>所以本类只做三件事：按最终扩展名验签名、按首窗判全 0、把结论变成带原因标签的
 * {@link DownloadFailurePolicy.PayloadException}（IOException 子类，进得了分类）。
 * 纯 JVM 实现，不依赖 Android，可直接单测。
 */
public final class DownloadedPayloadGate {

    /** 首窗：判全 0 与文件签名。 */
    public static final int HEAD_BYTES = 64 * 1024;
    /**
     * 尾窗：JPEG 的 EOI 允许落在尾部填充之后，旧实现只看最后 4KiB，带尾部数据的合法
     * JPEG 会被误杀。这里放宽到 64KiB，成本一样（一次 seek + 一次读）。
     */
    public static final int TAIL_BYTES = 64 * 1024;

    private DownloadedPayloadGate() {
    }

    /** 检查结论；{@link #wire()} 直接进日志。 */
    public enum Verdict {
        OK("ok"),
        /** 文件不存在或 0 字节。 */
        EMPTY("empty"),
        /** 首窗全 0：中间层返回了空内容。 */
        ALL_ZERO("all_zero"),
        /** 长度与远端声明不一致（被截断）。 */
        SHORT("short"),
        /** 已知扩展名但签名对不上（内容与名字不符 / 截断在头部）。 */
        NO_SIGNATURE("no_signature"),
        /** 读不出来。 */
        UNREADABLE("unreadable");

        private final String wire;

        Verdict(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public boolean ok() {
            return this == OK;
        }
    }

    /**
     * 看一眼这份字节能不能发布。
     *
     * @param staged         刚下完的临时文件（可为 {@code .tmp} 名）
     * @param finalPath      将要发布到的最终路径；为空时退回按 staged 的名字判扩展名
     * @param expectedLength 期望长度，未知传 0 或负数（此时不做长度检查）
     */
    public static Verdict inspect(File staged, File finalPath, long expectedLength) {
        if (staged == null || !staged.isFile()) {
            return Verdict.EMPTY;
        }
        long length = staged.length();
        if (length <= 0L) {
            return Verdict.EMPTY;
        }
        if (expectedLength > 0L && length != expectedLength) {
            return Verdict.SHORT;
        }
        byte[] head;
        byte[] tail;
        try {
            head = read(staged, 0L, (int) Math.min(length, (long) HEAD_BYTES));
            long tailOffset = Math.max(0L, length - TAIL_BYTES);
            tail = read(staged, tailOffset, (int) Math.min(length - tailOffset, (long) TAIL_BYTES));
        } catch (IOException e) {
            return Verdict.UNREADABLE;
        }
        if (isAllZero(head)) {
            return Verdict.ALL_ZERO;
        }
        String extension = extensionOf(finalPath != null ? finalPath.getName() : staged.getName());
        if (!signatureMatches(extension, head, tail)) {
            return Verdict.NO_SIGNATURE;
        }
        return Verdict.OK;
    }

    /**
     * 不通过就抛 {@link DownloadFailurePolicy.PayloadException}，原因由结论决定。
     *
     * <p>消息里带齐 {@code reason=} / {@code bytes=} / {@code path=} / {@code dest=}，
     * 队列只负责再包一层 {@code event=download_payload_rejected} 日志，不必自己拼。
     */
    public static void requirePublishable(File staged, File finalPath, long expectedLength)
            throws DownloadFailurePolicy.PayloadException {
        Verdict verdict = inspect(staged, finalPath, expectedLength);
        if (verdict.ok()) {
            return;
        }
        long length = staged == null || !staged.isFile() ? -1L : staged.length();
        String message = "downloaded payload rejected reason=" + verdict.wire()
                + " path=" + (staged == null ? "null" : staged.getAbsolutePath())
                + " dest=" + (finalPath == null ? "null" : finalPath.getAbsolutePath())
                + " bytes=" + length
                + " expected=" + expectedLength;
        throw new DownloadFailurePolicy.PayloadException(causeOf(verdict), message);
    }

    /** 结论 → 失败原因；分错只会影响退避长短，不影响正确性。 */
    public static DownloadFailurePolicy.Cause causeOf(Verdict verdict) {
        if (verdict == null) {
            return DownloadFailurePolicy.Cause.UNKNOWN;
        }
        switch (verdict) {
            case EMPTY:
                return DownloadFailurePolicy.Cause.EMPTY_PAYLOAD;
            case SHORT:
                return DownloadFailurePolicy.Cause.LENGTH_MISMATCH;
            case ALL_ZERO:
            case NO_SIGNATURE:
                return DownloadFailurePolicy.Cause.INVALID_PAYLOAD;
            case UNREADABLE:
                return DownloadFailurePolicy.Cause.IO;
            default:
                return DownloadFailurePolicy.Cause.UNKNOWN;
        }
    }

    /**
     * 取扩展名（小写、不含点）。没有点时返回空串，点开头的隐藏文件也按"无扩展名"处理，
     * 例如 {@code .nomedia} → {@code nomedia}，{@code .jpg.mgs-x.tmp} → {@code tmp}。
     */
    public static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    /** 已知扩展名的签名规则；未知扩展名放行（不拦非媒体文件，与 MediaPayload 同口径）。 */
    static boolean signatureMatches(String extension, byte[] head, byte[] tail) {
        if (extension == null) {
            return true;
        }
        switch (extension) {
            case "jpg":
            case "jpeg":
                return matches(head, 0, new byte[] {(byte) 0xFF, (byte) 0xD8})
                        && indexOf(tail, new byte[] {(byte) 0xFF, (byte) 0xD9}) >= 0;
            case "png":
                return matches(head, 0, new byte[] {
                        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
            case "gif":
                return matches(head, 0, new byte[] {'G', 'I', 'F', '8'});
            case "bmp":
                return matches(head, 0, new byte[] {'B', 'M'});
            case "webp":
                return matches(head, 0, new byte[] {'R', 'I', 'F', 'F'})
                        && matches(head, 8, new byte[] {'W', 'E', 'B', 'P'});
            case "avi":
                return matches(head, 0, new byte[] {'R', 'I', 'F', 'F'})
                        && matches(head, 8, new byte[] {'A', 'V', 'I', ' '});
            case "heic":
            case "heif":
            case "heics":
            case "avif":
                // ISO-BMFF：偏移 4 处是 "ftyp"
                return head.length >= 12 && matches(head, 4, new byte[] {'f', 't', 'y', 'p'});
            case "mp4":
            case "mov":
            case "m4v":
            case "3gp":
            case "3g2":
                return head.length >= 12 && matches(head, 4, new byte[] {'f', 't', 'y', 'p'});
            case "mkv":
            case "webm":
                return matches(head, 0, new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3});
            default:
                return true;
        }
    }

    private static byte[] read(File file, long offset, int length) throws IOException {
        if (length <= 0) {
            return new byte[0];
        }
        byte[] buffer = new byte[length];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            int read = 0;
            while (read < length) {
                int count = raf.read(buffer, read, length - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            if (read == length) {
                return buffer;
            }
            byte[] shorter = new byte[read];
            System.arraycopy(buffer, 0, shorter, 0, read);
            return shorter;
        }
    }

    private static boolean isAllZero(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(byte[] data, int offset, byte[] expected) {
        if (data == null || expected == null || offset < 0 || data.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] data, byte[] needle) {
        if (data == null || needle == null || needle.length == 0 || data.length < needle.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
