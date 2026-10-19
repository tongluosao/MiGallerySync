package com.tongluosao.gallerysync.hook;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import com.tongluosao.gallerysync.core.Logger;
import com.tongluosao.gallerysync.core.OriginalStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把刚落地的文件告诉相册，让它进媒体库。
 *
 * <p>下载与上传不同：上传是从相册已有的项出发，文件本来就在库里；下载出来的文件是**凭空出现**
 * 在 DCIM 下的，相册不会自己知道。只写进文件系统的话，用户打开相册看不到它 ——
 * 备份是"相册 → WebDAV"，下载补的是另一半，看不到就等于没下。
 *
 * <p>实现走宿主自己的扫描引擎（{@code ScannerEngine}），而不是往 MediaStore 里插一行：
 * 扫描引擎会算缩略图、EXIF、去重、写自己的数据库，MediaStore 那一行只是结果之一。
 * 自己插行会得到一个"能看到但打不开"的条目。
 *
 * <p>全部反射、全部 try/catch：宿主版本换了签名也只是扫不进来（并留一条日志），
 * 不能让下载本身失败。
 */
public final class ScannerIngest {

    private static final Uri CLOUD_URI =
            Uri.parse("content://com.miui.gallery.cloud.provider/cloud");
    private static final ExecutorService IDENTITY_BINDER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "gallery-scanned-alias");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 扫描场景码。宿主 {@code ScanTaskConfigFactory.get(int)} 的 13 = "可信来源"、
     * 优先级 32，且**只允许 0..29**（别的值它会直接抛 IllegalArgumentException）。
     * 用 13 是因为下载下来的文件是我们自己核对过长度才改名的，值得标成可信。
     */
    private static final int SCENE_CODE = 13;
    private static final int MAX_RENAMED_CANDIDATES = 16;

    private static final class SourceSnapshot {
        final OriginalStore.Record record;
        final long length;
        final String sha1;
        final String sha256;

        SourceSnapshot(OriginalStore.Record record, long length, String sha1, String sha256) {
            this.record = record;
            this.length = length;
            this.sha1 = sha1;
            this.sha256 = sha256;
        }
    }

    private final ClassLoader mLoader;

    public ScannerIngest(ClassLoader loader) {
        mLoader = loader;
    }

    /**
     * 让相册扫一个刚下载好的文件。
     *
     * <p>优先用 {@code scanPathsAsync(List, int)}（批量接口，一次把整个目录交上去更省），
     * 失败再退回 {@code scanFile(Context, String, int)}。
     */
    public void ingest(String absolutePath) {
        if (absolutePath == null || absolutePath.isEmpty()) {
            return;
        }
        SourceSnapshot source = captureSource(absolutePath);
        if (scanFile(absolutePath)) {
            bindIdentityAfterScan(absolutePath, source);
            return;
        }
        if (scanPaths(absolutePath)) {
            bindIdentityAfterScan(absolutePath, source);
            return;
        }
        Logger.w("event=download_scan_failed path=" + absolutePath
                + " reason=no_usable_scanner_api");
    }

    /**
     * ScannerEngine is asynchronous. Resolve the cloud row on a worker after its scan request;
     * the registry alias keeps the new preview sha1/row id connected to its verified original.
     */
    private void bindIdentityAfterScan(String path, SourceSnapshot source) {
        try {
            IDENTITY_BINDER.execute(() -> {
                Context context = HostContext.get(mLoader);
                if (context == null) {
                    Logger.w("event=scan_alias_skipped reason=no_host_context path=" + path);
                    return;
                }
                OriginalStore store = OriginalStore.get(context.getApplicationContext());
                for (int attempt = 0; attempt < 20; attempt++) {
                    try (Cursor cursor = context.getContentResolver().query(CLOUD_URI,
                            new String[]{"_id", "sha1"}, "localFile = ? OR thumbnailFile = ?",
                            new String[]{path, path}, null)) {
                        if (cursor != null && cursor.moveToFirst()) {
                            int idColumn = cursor.getColumnIndex("_id");
                            int shaColumn = cursor.getColumnIndex("sha1");
                            String rowId = idColumn < 0 ? null : cursor.getString(idColumn);
                            String sha1 = shaColumn < 0 ? null : cursor.getString(shaColumn);
                            if (store.registerScannedAlias(new File(path), rowId, sha1)) {
                                Logger.i("event=scan_alias_registered row=" + rowId
                                        + " sha1=" + (sha1 == null ? "" : sha1));
                            } else {
                                Logger.d("event=scan_alias_no_original_mapping row=" + rowId);
                            }
                            return;
                        }
                    } catch (Throwable t) {
                        Logger.d("event=scan_alias_query_retry attempt=" + attempt + " detail=" + t);
                    }
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (source != null) {
                    bindRenamedAliasAfterScan(context, store, path, source);
                    return;
                }
                Logger.w("event=scan_alias_timeout path=" + path);
            });
        } catch (Throwable t) {
            Logger.w("event=scan_alias_schedule_failed path=" + path, t);
        }
    }

    private SourceSnapshot captureSource(String path) {
        File file = new File(path);
        if (!file.isFile() || file.length() <= 0L) return null;
        try {
            Context context = HostContext.get(mLoader);
            if (context == null) return null;
            OriginalStore.Record record = OriginalStore.get(context.getApplicationContext())
                    .findByLocalPath(file);
            if (record == null) return null;
            long length = file.length();
            boolean knownDerivedLength = false;
            for (Long derivedLength : record.derivedHashes.values()) {
                if (derivedLength != null && derivedLength.longValue() == length) {
                    knownDerivedLength = true;
                    break;
                }
            }
            if (!knownDerivedLength) return null;
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    sha1.update(buffer, 0, count);
                    sha256.update(buffer, 0, count);
                }
            }
            if (file.length() != length) return null;
            String sourceSha256 = hex(sha256.digest());
            Long registeredLength = record.derivedHashes.get(sourceSha256);
            if (registeredLength == null || registeredLength.longValue() != length) return null;
            return new SourceSnapshot(record, length, hex(sha1.digest()), sourceSha256);
        } catch (Throwable t) {
            Logger.d("event=scan_alias_source_fingerprint_failed path=" + path + " detail=" + t);
            return null;
        }
    }

    private void bindRenamedAliasAfterScan(Context context, OriginalStore store, String sourcePath,
            SourceSnapshot source) {
        int examined = 0;
        try (Cursor cursor = context.getContentResolver().query(CLOUD_URI,
                new String[]{"_id", "sha1", "localFile", "thumbnailFile"}, "sha1 = ?",
                new String[]{source.sha1}, null)) {
            while (cursor != null && examined < MAX_RENAMED_CANDIDATES
                    && cursor.moveToNext()) {
                examined++;
                String rowId = cursor.getString(cursor.getColumnIndex("_id"));
                String rowSha1 = cursor.getString(cursor.getColumnIndex("sha1"));
                if (rowSha1 == null || !source.sha1.equalsIgnoreCase(rowSha1)) continue;
                String[] paths = {
                    cursor.getString(cursor.getColumnIndex("localFile")),
                    cursor.getString(cursor.getColumnIndex("thumbnailFile"))
                };
                for (String candidatePath : paths) {
                    if (candidatePath == null || candidatePath.isEmpty()) continue;
                    File candidate = new File(candidatePath);
                    if (candidate.getCanonicalPath().equals(
                            new File(sourcePath).getCanonicalPath())) {
                        continue;
                    }
                    if (!candidate.isFile() || candidate.length() != source.length
                            || !source.sha256.equals(sha256(candidate))) {
                        continue;
                    }
                    if (store.registerRenamedDerivedAlias(source.record, candidate, rowId,
                            rowSha1, source.sha256, source.length)) {
                        Logger.i("event=scan_renamed_alias_registered row=" + rowId
                                + " path=" + candidatePath);
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            Logger.w("event=scan_renamed_alias_query_failed source=" + sourcePath, t);
            return;
        }
        Logger.w("event=scan_alias_timeout path=" + sourcePath
                + " renamed_candidates=" + examined);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = digits[value >>> 4];
            result[i * 2 + 1] = digits[value & 0x0f];
        }
        return new String(result);
    }

    private boolean scanPaths(String absolutePath) {
        try {
            Class<?> engine = Class.forName(
                    "com.miui.gallery.scanner.core.ScannerEngine", true, mLoader);
            Object instance = engine.getMethod("getInstance").invoke(null);
            if (instance == null) {
                return false;
            }
            List<String> paths = new ArrayList<>(1);
            paths.add(absolutePath);
            Method method = engine.getMethod("scanPathsAsync", List.class, int.class);
            method.invoke(instance, paths, SCENE_CODE);
            Logger.d("event=download_scan_requested path=" + absolutePath + " api=scanPathsAsync");
            return true;
        } catch (Throwable t) {
            Logger.d("event=download_scan_fallback api=scanPathsAsync detail=" + t);
            return false;
        }
    }

    private boolean scanFile(String absolutePath) {
        try {
            Class<?> engine = Class.forName(
                    "com.miui.gallery.scanner.core.ScannerEngine", true, mLoader);
            Object instance = engine.getMethod("getInstance").invoke(null);
            if (instance == null) {
                return false;
            }
            // 单文件接口要 Context：用相册进程自己的那个
            Object context = HostContext.get(mLoader);
            if (context == null) {
                return false;
            }
            Method method = engine.getMethod("scanFile",
                    android.content.Context.class, String.class, int.class);
            Object scanResult = method.invoke(instance, context, absolutePath, SCENE_CODE);
            String result = scanResultValue(scanResult, "getResult");
            String reason = scanResultValue(scanResult, "getReasonCode");
            Logger.i("event=download_scan_result api=scanFile path=" + absolutePath
                    + " result=" + result + " reason=" + reason);
            boolean rejected = "FAILED".equals(result) || "RETRY".equals(result);
            if (scanResult == null
                    || rejected && !"ALREADY_EXISTS".equals(reason)) {
                Logger.w("event=download_scan_fallback api=scanFile path=" + absolutePath
                        + " result=" + result + " reason=" + reason);
                return false;
            }
            Logger.d("event=download_scan_requested path=" + absolutePath + " api=scanFile");
            return true;
        } catch (Throwable t) {
            Logger.d("event=download_scan_fallback api=scanFile detail=" + t);
            return false;
        }
    }

    /** Read the outcome exposed by Gallery versions whose scanFile API returns ScanResult. */
    private static String scanResultValue(Object scanResult, String accessor) {
        if (scanResult == null) {
            return "null";
        }
        try {
            Object value = scanResult.getClass().getMethod(accessor).invoke(scanResult);
            return value == null ? "null" : value.toString();
        } catch (Throwable ignored) {
            // Keep older Gallery builds usable when the result accessors are absent.
            return "unavailable";
        }
    }
}
