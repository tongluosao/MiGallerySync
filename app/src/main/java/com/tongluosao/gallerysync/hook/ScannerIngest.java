package com.tongluosao.gallerysync.hook;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import com.tongluosao.gallerysync.core.DownloadQueue;
import com.tongluosao.gallerysync.core.Logger;
import com.tongluosao.gallerysync.core.OriginalStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
public final class ScannerIngest implements DownloadQueue.Ingest {

    private static final Uri CLOUD_URI =
            Uri.parse("content://com.miui.gallery.cloud.provider/cloud");
    /** 与 SlimHook 用的是同一套宿主 API：saveToDB(int id, String thumbnailFile, String localFile) */
    private static final String CLS_SLIM_CONTROLLER =
            "com.miui.gallery.cleaner.slim.SlimController";
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
     *
     * <p><b>隐藏副本目录里的预览一律拒绝</b>：扫描器会把它当成一张新照片入库，相册里在原图行
     * 之外就会多出一行"副本"（设备实证：cloud 表 _id=15989/15991）。这类文件只能走
     * {@link #adoptPreview(String, String)}。
     */
    @Override
    public void ingest(String absolutePath) {
        if (absolutePath == null || absolutePath.isEmpty()) {
            return;
        }
        if (!DownloadQueue.hostScanAllowed(absolutePath)) {
            Logger.w("event=download_scan_refused reason=slim_preview_path path=" + absolutePath);
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
     * 把 {@code <相册目录>/.gallerysync-slim/} 下的预览挂到**既有云行**上，全程**不调用宿主扫描器**。
     *
     * <p>与 {@code SlimHook} 对齐：{@code SlimController.saveToDB(int id, String thumbnailFile,
     * String localFile)}，把该行改成"本地表示 = 这张预览"（thumbnailFile = 预览路径，
     * localFile 置空）。相册因此仍然只有一行原图，不会多出"副本"。
     *
     * <p><b>失败一律什么都不做</b>：查不到行、命中不止一行、反射不可用、写库抛错、写库后复核不过，
     * 全都在这里返回，绝不再退回 {@link #ingest}（那会新建一行）。
     */
    @Override
    public void adoptPreview(String albumPath, String previewPath) {
        try {
            adopt(albumPath, previewPath);
        } catch (Throwable t) {
            Logger.w("event=download_preview_adoption_failed album=" + albumPath
                    + " preview=" + previewPath, t);
        }
    }

    private void adopt(String albumPath, String previewPath) throws Exception {
        if (albumPath == null || albumPath.isEmpty()
                || previewPath == null || previewPath.isEmpty()) {
            Logger.w("event=download_preview_adoption_skipped reason=missing_path");
            return;
        }
        if (DownloadQueue.hostScanAllowed(previewPath)) {
            // 不在隐藏目录里就不是"预览"这件事，交给 ingest，绝不在这里改库
            Logger.w("event=download_preview_adoption_skipped reason=not_a_slim_preview path="
                    + previewPath);
            return;
        }
        if (!new File(previewPath).isFile()) {
            Logger.w("event=download_preview_adoption_skipped reason=preview_missing path="
                    + previewPath);
            return;
        }
        Context context = HostContext.get(mLoader);
        if (context == null) {
            Logger.w("event=download_preview_adoption_skipped reason=no_host_context path="
                    + previewPath);
            return;
        }
        // 1) 按相册路径（canonical + 大小写变体）找既有行；只有恰好 1 行才认
        Set<String> albumCandidates = pathCandidates(albumPath);
        Integer rowId = uniqueRowId(context, albumCandidates);
        if (rowId == null) {
            return;
        }
        // 2) 反射 SlimController.saveToDB(int, String, String)
        Method saveToDb = saveToDbMethod();
        if (saveToDb == null) {
            Logger.w("event=download_preview_adoption_skipped reason=save_to_db_unavailable row="
                    + rowId);
            return;
        }
        try {
            saveToDb.invoke(null, rowId.intValue(), previewPath, "");
        } catch (Throwable t) {
            Logger.e("event=download_preview_adoption_call_failed row=" + rowId, t);
            return;
        }
        // 3) 回读该行复核：thumbnailFile 必须等于预览路径，localFile 必须为空或仍指向原图
        if (databasePointsToPreview(context, rowId.intValue(), previewPath, albumCandidates)) {
            Logger.i("event=download_preview_adopted row=" + rowId + " preview=" + previewPath);
        } else {
            // 复核不过就到此为止：预览留在隐藏目录里、原图没动，绝不让扫描器补一行
            Logger.w("event=download_preview_adoption_unconfirmed row=" + rowId
                    + " preview=" + previewPath);
        }
    }

    /** 按相册路径找既有云行；0 行（用户已丢进回收站等）或多行时一律返回 null。 */
    private Integer uniqueRowId(Context context, Set<String> albumCandidates) {
        Set<Integer> rows = new LinkedHashSet<>();
        boolean ambiguous = false;
        for (String candidate : albumCandidates) {
            try (Cursor cursor = context.getContentResolver().query(CLOUD_URI,
                    new String[]{"_id"}, "localFile = ? OR thumbnailFile = ?",
                    new String[]{candidate, candidate}, null)) {
                if (cursor == null) {
                    continue;
                }
                int idColumn = cursor.getColumnIndex("_id");
                if (idColumn < 0) {
                    continue;
                }
                while (cursor.moveToNext()) {
                    String raw = cursor.getString(idColumn);
                    int rowId;
                    try {
                        rowId = Integer.parseInt(raw);
                    } catch (RuntimeException unreadable) {
                        Logger.w("event=download_preview_adoption_skipped"
                                + " reason=row_id_unreadable row=" + raw);
                        return null;
                    }
                    rows.add(rowId);
                    if (rows.size() > 1) {
                        ambiguous = true;
                        break;
                    }
                }
            } catch (Throwable t) {
                Logger.w("event=download_preview_adoption_query_failed candidate=" + candidate, t);
                return null;
            }
            if (ambiguous) {
                break;
            }
        }
        if (rows.isEmpty()) {
            Logger.w("event=download_preview_adoption_skipped reason=no_existing_row"
                    + " candidates=" + albumCandidates.size());
            return null;
        }
        if (rows.size() != 1) {
            Logger.w("event=download_preview_adoption_skipped reason=ambiguous_rows rows="
                    + rows.size());
            return null;
        }
        return rows.iterator().next();
    }

    /** 宿主 Dex 里 saveToDB 是 private static void(int, String, String)；签名不符就当不可用。 */
    private Method saveToDbMethod() {
        try {
            Class<?> controller = Class.forName(CLS_SLIM_CONTROLLER, false, mLoader);
            Method saveToDb = controller.getDeclaredMethod("saveToDB", int.class, String.class,
                    String.class);
            saveToDb.setAccessible(true);
            if (saveToDb.getReturnType() != void.class
                    || !Modifier.isStatic(saveToDb.getModifiers())) {
                return null;
            }
            return saveToDb;
        } catch (Throwable t) {
            Logger.d("event=download_preview_adoption_fallback detail=" + t);
            return null;
        }
    }

    /** 写库后的复核：这一行必须真的指向预览，且原图字段没有被指向别的文件。 */
    private static boolean databasePointsToPreview(Context context, int rowId, String previewPath,
            Set<String> albumCandidates) {
        try (Cursor cursor = context.getContentResolver().query(CLOUD_URI,
                new String[]{"localFile", "thumbnailFile"}, "_id = ?",
                new String[]{String.valueOf(rowId)}, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return false;
            }
            int localColumn = cursor.getColumnIndex("localFile");
            int thumbnailColumn = cursor.getColumnIndex("thumbnailFile");
            if (localColumn < 0 || thumbnailColumn < 0) {
                return false;
            }
            String local = cursor.getString(localColumn);
            String thumbnail = cursor.getString(thumbnailColumn);
            if (!samePath(thumbnail, previewPath)) {
                return false;
            }
            if (local == null || local.isEmpty()) {
                // 与 SlimHook 写库后的状态一致：本地没有原图，只有预览
                return true;
            }
            String normalized = local.replace('\\', '/');
            return albumCandidates.contains(normalized)
                    || albumCandidates.contains(normalized.toLowerCase(Locale.US));
        } catch (Throwable t) {
            Logger.w("event=download_preview_adoption_verify_failed row=" + rowId, t);
            return false;
        }
    }

    /** 相册路径的全部可比较写法：原样、绝对、canonical，以及大小写变体（.HEIC/.heic）。 */
    private static Set<String> pathCandidates(String path) {
        Set<String> candidates = new LinkedHashSet<>();
        addCandidate(candidates, path);
        File file = new File(path);
        addCandidate(candidates, file.getAbsolutePath());
        try {
            addCandidate(candidates, file.getCanonicalPath());
        } catch (IOException ignored) {
            // 文件系统不可解析时只留前面两种写法
        }
        for (String candidate : new ArrayList<>(candidates)) {
            addCandidate(candidates, candidate.toLowerCase(Locale.US));
            int dot = candidate.lastIndexOf('.');
            int separator = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
            if (dot > separator && dot > 0) {
                String stem = candidate.substring(0, dot);
                String suffix = candidate.substring(dot);
                addCandidate(candidates, stem + suffix.toUpperCase(Locale.US));
                addCandidate(candidates, stem + suffix.toLowerCase(Locale.US));
            }
        }
        return candidates;
    }

    private static void addCandidate(Set<String> candidates, String value) {
        if (value != null && !value.isEmpty()) {
            candidates.add(value);
        }
    }

    private static boolean samePath(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.equals(second)) {
            return true;
        }
        try {
            return new File(first).getCanonicalPath()
                    .equals(new File(second).getCanonicalPath());
        } catch (IOException ignored) {
            return first.equalsIgnoreCase(second);
        }
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
