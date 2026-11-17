package com.tongluosao.gallerysync.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * “空间优化”的原地覆盖写入引擎：把已经编码好的预览按原路径、原文件名覆盖上去。
 *
 * <p>与侧车方案的差别全在<b>顺序</b>上：老方案先发布副本、再切 DB、最后删原图；原地模式下原路径
 * 就是那份唯一字节，一旦覆盖就不可逆，所以必须先把“我要覆盖了”的意图（{@code PENDING_INPLACE}）
 * 落进注册表，再动字节；覆盖后逐字节复核，只有复核通过才切 DB。本类只依赖
 * {@code java.io} / {@code java.nio.file}：租约、注册表、相册 provider 全部走 {@link Callbacks}，
 * 因此每个失败分支都能在 JVM 单测里钉住。
 *
 * <p>失败语义见 {@link Outcome#reason}：{@code invalid_staged} 什么都没动；{@code lease_failed} /
 * {@code pending_failed} / {@code publish_failed} 已删临时件、原文件未变；{@code verify_failed_*}
 * 已尝试回填并用 {@link Outcome#restored} 说明是否复原；{@code db_commit_failed} 不回滚文件
 * （回滚要联网重下原图），只把记录标成 {@code DAMAGED}。
 */
public final class InplacePreviewWriter {

    private static final int BUFFER_SIZE = 64 * 1024;

    /** 引擎需要的全部副作用：都是“做/不做”的判断题，失败由调用方决定怎么记日志。 */
    public interface Callbacks {

        /** 取写租约并登记原路径为派生位置；失败返回 false。 */
        boolean beginLease();

        /** 落 PENDING_INPLACE 意图；失败返回 false（此时绝不能再动字节）。 */
        boolean markPending(long len, String sha256, String format);

        /** saveToDB(id, "", 原路径) 并回读确认；失败返回 false。 */
        boolean commitDatabase(File original);

        /** 提交 INPLACE_PREVIEW 状态与预览指纹。 */
        void markInPlace(long len, String sha256, String format);

        /** 标记记录损坏（原因只进日志，不新增字段）。 */
        void markDamaged(String reason);

        /** 用注册表里的原图重新落回原路径。 */
        void restoreOriginal();
    }

    /** 一次原地覆盖的全部事实。字段由调用方填，引擎只读。 */
    public static final class Request {
        public File original;
        public long originalLength;
        public String originalSha256;
        public File staged;
        public long previewLength;
        public String previewSha256;
        public String previewFormat;
        public String rowId;
    }

    /** 一次原地覆盖的结论。 */
    public static final class Outcome {
        public boolean success;
        public String reason = "";
        /** 是否已经成功把原图回填到原路径（只有复核失败分支会有 true）。 */
        public boolean restored;

        private Outcome() {
        }
    }

    private InplacePreviewWriter() {
    }

    /**
     * 按固定顺序提交一次原地覆盖：自检 → 租约 → 意图 → 时间戳 → fsync → 原子改名 → 复核 → 切 DB → 提交状态。
     *
     * <p>顺序不能换：租约必须在任何写盘之前拿到（上传线程靠它避开半成品），意图必须早于改名
     * （进程死在第 6 步之后时，注册表里已经有“这份字节不是原图”的记录），DB 必须在复核之后
     * （相册一旦指向原路径，就会把它当原图展示与上传）。
     */
    public static Outcome publish(Request request, Callbacks callbacks) {
        if (!isValid(request, callbacks)) {
            return failed("invalid_staged", false);
        }
        File staged = request.staged;
        File original = request.original;
        if (!beginLease(callbacks)) {
            deleteQuietly(staged);
            return failed("lease_failed", false);
        }
        if (!markPending(callbacks, request)) {
            deleteQuietly(staged);
            return failed("pending_failed", false);
        }
        applyOriginalTimestamp(staged, original);
        syncStaged(staged);
        try {
            Files.move(staged.toPath(), original.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            Logger.w("event=inplace_publish_failed path=" + original.getAbsolutePath(), t);
            deleteQuietly(staged);
            return failed("publish_failed", false);
        }
        if (!matches(original, request.previewLength, request.previewSha256)) {
            return verifyFailed(request, callbacks);
        }
        if (!commitDatabase(callbacks, original)) {
            markDamaged(callbacks, "db_commit_failed");
            return failed("db_commit_failed", false);
        }
        markInPlace(callbacks, request);
        Outcome outcome = new Outcome();
        outcome.success = true;
        outcome.reason = "";
        return outcome;
    }

    /**
     * 文件摘要（小写十六进制）。
     *
     * <p>读不出来时返回 {@code null} 而不是抛异常：调用方都在“失败就放弃这次优化”的位置，
     * 一个抛出的 IOException 只会让它们各自再包一层 try/catch；{@code null} 在 {@link #publish}
     * 与 {@code matches} 里按“对不上”处理，语义一致。
     */
    public static String sha256Of(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            StringBuilder value = new StringBuilder(64);
            for (byte b : digest.digest()) {
                value.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return value.toString();
        } catch (IOException e) {
            Logger.w("event=inplace_sha256_failed path=" + file.getAbsolutePath(), e);
            return null;
        } catch (NoSuchAlgorithmException e) {
            Logger.e("event=inplace_sha256_unavailable", e);
            return null;
        }
    }

    /**
     * 自检：临时件存在、长度与摘要都对得上、而且和原文件在同一个目录。
     *
     * <p>同目录是硬要求：跨目录的 {@code Files.move} 会退化成“复制 + 删除”，那样既不再是原子发布，
     * 也可能把预览写到另一块存储上。
     */
    private static boolean isValid(Request request, Callbacks callbacks) {
        if (request == null || callbacks == null || request.original == null
                || request.staged == null || request.previewLength <= 0L) {
            return false;
        }
        File staged = request.staged;
        if (!staged.isFile() || staged.length() != request.previewLength) {
            return false;
        }
        String expected = trim(request.previewSha256);
        String actual = sha256Of(staged);
        if (expected == null || actual == null || !expected.equalsIgnoreCase(actual)) {
            return false;
        }
        try {
            File stagedParent = staged.getCanonicalFile().getParentFile();
            File originalParent = request.original.getCanonicalFile().getParentFile();
            return stagedParent != null && stagedParent.equals(originalParent);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 复核失败：先用注册表里的原图回填，再靠“原文件是否真的复原”判断回填成败。
     *
     * <p>{@link Callbacks#restoreOriginal()} 不返回值，所以这里以原图长度/摘要为准 —— 这也比
     * 让回调自称成功更可信：网络中断、远端校验失败都只会表现为“原路径还是预览字节”。
     */
    private static Outcome verifyFailed(Request request, Callbacks callbacks) {
        boolean restored = false;
        try {
            callbacks.restoreOriginal();
            restored = matches(request.original, request.originalLength, request.originalSha256);
        } catch (Throwable t) {
            Logger.w("event=inplace_restore_failed path=" + request.original.getAbsolutePath(), t);
        }
        Logger.w("event=inplace_verify_failed restored=" + restored
                + " path=" + request.original.getAbsolutePath());
        if (!restored) {
            // 回填失败时原路径上留着的是预览字节，注册表必须标脏，避免后续把它当原图用。
            markDamaged(callbacks, "restore_failed");
        }
        return failed(restored ? "verify_failed_restored" : "verify_failed_restore_failed",
                restored);
    }

    /** 长度必须一致；登记了原图摘要时再比摘要（摘要缺失只按长度，老记录没有可比的值）。 */
    private static boolean matches(File file, long length, String sha256) {
        if (file == null || !file.isFile() || file.length() != length) {
            return false;
        }
        String expected = trim(sha256);
        if (expected == null) {
            return true;
        }
        String actual = sha256Of(file);
        return actual != null && expected.equalsIgnoreCase(actual);
    }

    /** 保留原图 mtime：相册按截屏/拍摄时间排序，覆盖后不能用“现在”替换它。失败只记 warn。 */
    private static void applyOriginalTimestamp(File staged, File original) {
        long modified = original.lastModified();
        if (modified <= 0L) {
            return;
        }
        if (!staged.setLastModified(modified)) {
            Logger.w("event=inplace_mtime_failed path=" + staged.getAbsolutePath());
        }
    }

    /**
     * fsync 临时件，确保改名后落盘的确实是完整字节。
     *
     * <p>必须用追加模式打开：{@code new FileOutputStream(file)} 会先把文件截成 0 字节，
     * 预览还没发布就被清空了。
     */
    private static void syncStaged(File staged) {
        try (FileOutputStream output = new FileOutputStream(staged, true)) {
            output.getFD().sync();
        } catch (Throwable t) {
            Logger.w("event=inplace_sync_failed path=" + staged.getAbsolutePath(), t);
        }
    }

    /** 以下五个包装：回调抛异常只降级成对应 reason，调用顺序与失败语义仍由 {@link #publish} 决定。 */
    private static boolean beginLease(Callbacks callbacks) {
        try {
            return callbacks.beginLease();
        } catch (Throwable t) {
            Logger.w("event=inplace_lease_failed", t);
            return false;
        }
    }

    private static boolean markPending(Callbacks callbacks, Request request) {
        try {
            return callbacks.markPending(request.previewLength, request.previewSha256,
                    request.previewFormat);
        } catch (Throwable t) {
            Logger.w("event=inplace_pending_failed", t);
            return false;
        }
    }

    private static boolean commitDatabase(Callbacks callbacks, File original) {
        try {
            return callbacks.commitDatabase(original);
        } catch (Throwable t) {
            Logger.w("event=inplace_db_commit_failed path=" + original.getAbsolutePath(), t);
            return false;
        }
    }

    private static void markInPlace(Callbacks callbacks, Request request) {
        try {
            callbacks.markInPlace(request.previewLength, request.previewSha256,
                    request.previewFormat);
        } catch (Throwable t) {
            // 字节与 DB 都已就位：状态没提交只影响上传分类，下一次登记会自愈
            Logger.w("event=inplace_commit_failed", t);
        }
    }

    private static void markDamaged(Callbacks callbacks, String reason) {
        try {
            callbacks.markDamaged(reason);
        } catch (Throwable t) {
            Logger.w("event=inplace_mark_damaged_failed reason=" + reason, t);
        }
    }

    private static void deleteQuietly(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (Throwable t) {
            Logger.d("event=inplace_temp_cleanup_failed path=" + file.getAbsolutePath());
        }
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Outcome failed(String reason, boolean restored) {
        Outcome outcome = new Outcome();
        outcome.success = false;
        outcome.reason = reason;
        outcome.restored = restored;
        return outcome;
    }
}
