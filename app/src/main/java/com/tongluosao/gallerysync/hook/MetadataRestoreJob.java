package com.tongluosao.gallerysync.hook;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;

import com.tongluosao.gallerysync.core.Config;
import com.tongluosao.gallerysync.core.Logger;
import com.tongluosao.gallerysync.core.OriginalStore;
import com.tongluosao.gallerysync.core.RemoteSuppress;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Executes one explicitly requested batch of metadata-original restores in Gallery's process. */
public final class MetadataRestoreJob {
    private static final String REQUEST_NAME = "metadata-restore-request.json";
    private static final String RESULT_NAME = "metadata-restore-result.json";
    private static final String DIRECTORY_NAME = "webdav_sync";
    private static final String STORAGE_ROOT = "/storage/emulated/0";
    private static final long MAX_REQUEST_BYTES = 1024L * 1024L;
    private static final long SPACE_RESERVE_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_ITEMS = 5000;
    private static final int MAX_RELATED_ROWS = 64;
    private static final int MAX_TEMP_FILES = 500;
    private static final Uri CLOUD_URI = cloudUri();
    /** stale_rows 判定要看的列：路径、指纹、远端状态与尺寸。 */
    private static final String[] STALE_ROW_COLUMNS = new String[]{"_id", "localFile",
            "thumbnailFile", "sha1", "serverId", "localFlag", "size", "realSize", "dateTaken"};
    /** orphan_rows 判定要看的列：路径两个字段、指纹、远端状态、本地状态与文件名。 */
    private static final String[] ORPHAN_ROW_COLUMNS = new String[]{"_id", "localFile",
            "thumbnailFile", "sha1", "serverId", "localFlag", "fileName"};

    /** 单元测试的 JVM 里没有 Android 实现；解析失败时退化成 null（设备上不会发生）。 */
    private static Uri cloudUri() {
        try {
            return Uri.parse("content://com.miui.gallery.cloud.provider/cloud");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private final Object mLock = new Object();
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "gallery-metadata-original-restore");
        thread.setDaemon(true);
        return thread;
    });
    private final ClassLoader mLoader;
    private volatile Config mConfig;
    private Future<?> mFuture;
    private boolean mRunning;
    private boolean mClosed;

    MetadataRestoreJob(ClassLoader loader) {
        mLoader = loader;
    }

    void setConfig(Config config) {
        synchronized (mLock) {
            String previousTarget = OriginalStore.targetKey(mConfig);
            String nextTarget = OriginalStore.targetKey(config);
            mConfig = config;
            if (mRunning && !previousTarget.equals(nextTarget) && mFuture != null) {
                mFuture.cancel(true);
                Logger.i("event=metadata_restore_job_cancelled status=target_changed");
            }
        }
    }

    boolean start(Context context, Config config) {
        if (context == null || config == null || !config.isReady()) return false;
        Context appContext = context.getApplicationContext();
        if (appContext == null) appContext = context;
        synchronized (mLock) {
            if (mClosed || mRunning) {
                Logger.i("event=metadata_restore_job_skipped status="
                        + (mClosed ? "closed" : "already_running"));
                return false;
            }
            Config current = mConfig;
            if (current == null || !OriginalStore.targetKey(config)
                    .equals(OriginalStore.targetKey(current))) {
                mConfig = config;
            }
            mRunning = true;
            final Config jobConfig = config;
            final Context jobContext = appContext;
            mFuture = mWorker.submit(() -> {
                try {
                    execute(jobContext, jobConfig);
                } catch (Throwable failure) {
                    Logger.e("event=metadata_restore_job_failed status=internal_error");
                } finally {
                    synchronized (mLock) {
                        mRunning = false;
                        mFuture = null;
                    }
                }
            });
            Logger.i("event=metadata_restore_job_started status=queued");
            return true;
        }
    }

    void shutdown() {
        synchronized (mLock) {
            mClosed = true;
            if (mFuture != null) mFuture.cancel(true);
        }
        mWorker.shutdownNow();
    }

    private void execute(Context context, Config config) {
        File directory = new File(context.getFilesDir(), DIRECTORY_NAME);
        File requestFile = new File(directory, REQUEST_NAME);
        File resultFile = new File(directory, RESULT_NAME);
        if (!requestFile.isFile()) return;
        String jobId = "";
        String target = OriginalStore.targetKey(config);
        List<ItemResult> results = new ArrayList<>();
        Request request;
        try {
            request = parseRequest(requestFile);
            jobId = request.jobId;
            target = request.target;
            if (!target.equals(OriginalStore.targetKey(config))) {
                writeResult(resultFile, jobId, target, "failed", "target_mismatch", results);
                logStatus("failed");
                return;
            }
        } catch (Throwable failure) {
            writeResult(resultFile, jobId, target, "failed", safeReason(failure), results);
            logStatus("failed");
            return;
        }

        if (!writeResult(resultFile, jobId, target, "running", "", results)) {
            logStatus("result_write_failed");
            return;
        }
        OriginalStore store;
        try {
            store = OriginalStore.get(context);
        } catch (Throwable failure) {
            writeResult(resultFile, jobId, target, "failed", "registry_unavailable", results);
            logStatus("failed");
            return;
        }
        boolean stoppedForSpace = false;
        boolean cancelled = false;
        for (int index = 0; index < request.items.size(); index++) {
            RequestItem item = request.items.get(index);
            if (!isCurrent(target)) {
                cancelled = true;
                addRemaining(results, request.items, index, "cancelled", "job_stopped");
                break;
            }
            ItemResult result;
            try {
                if ("stale_rows".equals(item.mode)) {
                    result = staleRowsOne(context, config, store, target, item);
                } else if ("orphan_rows".equals(item.mode)) {
                    result = orphanRowsOne(context, config, store, target, item);
                } else if ("broken_payload".equals(item.mode)) {
                    result = brokenPayloadOne(context, config, store, target, item);
                } else if ("unslim".equals(item.mode)) {
                    result = unslimOne(context, config, store, target, item);
                } else if ("legacy_copies".equals(item.mode)) {
                    result = legacyCopiesOne(context, config, store, target, item);
                } else if ("purge_items".equals(item.mode)) {
                    result = purgeItemsOne(context, config, store, target, item);
                } else {
                    result = restoreOne(context, config, store, target, item);
                }
            } catch (Throwable failure) {
                if (!isCurrent(target)) {
                    cancelled = true;
                    addRemaining(results, request.items, index, "cancelled", "job_stopped");
                    break;
                }
                result = new ItemResult(item.row, "failed", safeReason(failure));
            }
            results.add(result);
            if (!writeResult(resultFile, jobId, target, "running", "", results)) {
                logStatus("result_write_failed");
                return;
            }
            logRowStatus(item.row, result.status);
            if ("needs_space".equals(result.reason)) {
                stoppedForSpace = true;
                addRemaining(results, request.items, index + 1, "skipped", "needs_space");
                break;
            }
            if (!isCurrent(target)) {
                cancelled = true;
                addRemaining(results, request.items, index + 1, "cancelled", "job_stopped");
                break;
            }
        }
        if (!cancelled && !request.tempFiles.isEmpty()) {
            for (int index = 0; index < request.tempFiles.size(); index++) {
                if (!isCurrent(target)) {
                    cancelled = true;
                    addRemainingTemp(results, request.tempFiles, index, "cancelled", "job_stopped");
                    break;
                }
                ItemResult result;
                try {
                    result = deleteTempFile(context, request.tempFiles.get(index));
                } catch (Throwable failure) {
                    result = new ItemResult("temp", "failed", safeReason(failure));
                }
                results.add(result);
                if (!writeResult(resultFile, jobId, target, "running", "", results)) {
                    logStatus("result_write_failed");
                    return;
                }
                logRowStatus("temp", result.status);
            }
        }

        String status = cancelled ? "cancelled" : stoppedForSpace ? "needs_space"
                : hasFailures(results) ? "partial" : "complete";
        if (writeResult(resultFile, jobId, target, status,
                stoppedForSpace ? "needs_space" : "", results)) {
            logStatus(status);
        } else {
            logStatus("result_write_failed");
        }
    }

    private ItemResult restoreOne(Context context, Config config, OriginalStore store,
            String target, RequestItem item) throws Exception {
        if (!isCurrent(target)) return new ItemResult(item.row, "cancelled", "job_stopped");
        OriginalStore.Record record = store.find(config, item.row, item.sha1);
        if (record == null) return new ItemResult(item.row, "failed", "no_registry_record");
        if (!target.equals(record.target) || !record.sha256.equalsIgnoreCase(item.sha256)
                || record.originalLength != item.length) {
            return new ItemResult(item.row, "failed", "registry_metadata_mismatch");
        }
        File original = safeOriginalFile(record.originalLocalPath);
        if (original == null) return new ItemResult(item.row, "failed", "unsafe_original_path");

        CloudRow row = readCloudRow(context, item.row, item.sha1, record);
        if (row == null) return new ItemResult(item.row, "failed", "gallery_row_mismatch");

        if (!hasRequiredSpace(record.originalLength)) {
            return new ItemResult(item.row, "failed", "needs_space");
        }
        if (!isCurrent(target)) return new ItemResult(item.row, "cancelled", "job_stopped");

        File restored = store.restoreOriginal(config, record, original);
        if (!restored.isFile() || restored.length() != record.originalLength) {
            return new ItemResult(item.row, "failed", "restore_verification_failed");
        }
        if (!isCurrent(target)) return new ItemResult(item.row, "cancelled", "job_stopped");

        if (!record.originalLocalPath.equals(row.localFile)) {
            ContentValues values = new ContentValues(1);
            values.put("localFile", record.originalLocalPath);
            String selection = "_id = ? AND sha1 = ? AND "
                    + (row.localFileWasNull ? "localFile IS NULL" : "localFile = ?");
            String[] args = row.localFileWasNull
                    ? new String[]{item.row, row.sha1}
                    : new String[]{item.row, row.sha1, row.localFile};
            int updated = context.getContentResolver().update(CLOUD_URI, values, selection, args);
            if (updated != 1) {
                CloudRow current = readCloudRow(context, item.row, item.sha1, record);
                if (current == null || !record.originalLocalPath.equals(current.localFile)) {
                    return new ItemResult(item.row, "failed", "gallery_row_update_failed");
                }
            }
        }
        if (!isCurrent(target)) return new ItemResult(item.row, "cancelled", "job_stopped");
        RepairCounts repairs = repairDerivedCopies(context, item.row, record, row);
        if (!isCurrent(target)) return new ItemResult(item.row, "cancelled", "job_stopped");

        int cleanedDuplicates = 0;
        String cleanupFailure = "";
        for (long relatedRowId : item.relatedRows) {
            if (!isCurrent(target)) {
                return new ItemResult(item.row, "cancelled", "job_stopped",
                        repairs.copies, repairs.rows, cleanedDuplicates);
            }
            CleanupResult cleanup = cleanupRelatedRow(context, config, target, record,
                    item.row, relatedRowId, original);
            if (cleanup.cleaned) {
                cleanedDuplicates++;
            } else if ("job_stopped".equals(cleanup.reason)) {
                return new ItemResult(item.row, "cancelled", "job_stopped",
                        repairs.copies, repairs.rows, cleanedDuplicates);
            } else if (cleanupFailure.isEmpty()) {
                cleanupFailure = cleanup.reason;
            }
        }
        // The existing cloud row was updated above; rescanning this path can create another row
        // when Gallery's stored file identity still reflects the slim/derived bytes.
        return new ItemResult(item.row, cleanupFailure.isEmpty() ? "success" : "partial",
                cleanupFailure, repairs.copies, repairs.rows, cleanedDuplicates);
    }

    /**
     * 撤销旧版本的瘦身：先把 Gallery 行里指向预览的 thumbnail/local 字段清掉，确认行已经指向
     * 原始照片，才删除那个不再被引用的预览文件。这个模式绝不下载，也绝不删除原始照片。
     */
    private ItemResult unslimOne(Context context, Config config, OriginalStore store,
            String target, RequestItem item) throws Exception {
        if (!isCurrent(target)) return new ItemResult(item.row, "cancelled", "job_stopped");
        OriginalStore.Record record = store.find(config, item.row, item.sha1);
        if (record == null) return new ItemResult(item.row, "failed", "no_registry_record");
        if (!target.equals(record.target) || !record.sha256.equalsIgnoreCase(item.sha256)
                || record.originalLength != item.length) {
            return new ItemResult(item.row, "failed", "registry_metadata_mismatch");
        }
        String originalCanonical = canonicalGalleryPath(record.originalLocalPath);
        File original = safeOriginalFile(record.originalLocalPath);
        if (original == null || originalCanonical == null) {
            return new ItemResult(item.row, "failed", "unsafe_original_path");
        }
        // 这个模式不做修复下载：原始照片必须已经在磁盘上并且校验通过。
        if (!isOriginalVerified(original, record)) {
            return new ItemResult(item.row, "failed", "original_missing");
        }
        Method saveToDb = slimSaveToDbMethod();
        if (saveToDb == null) return new ItemResult(item.row, "failed", "thumbnail_clear_failed");

        UnslimState state = new UnslimState();
        Set<String> candidates = new LinkedHashSet<>();
        if (record.previewLocalPath != null && !record.previewLocalPath.isEmpty()) {
            candidates.add(record.previewLocalPath);
        }
        candidates.addAll(record.derivedPaths);
        for (String candidate : candidates) {
            if (!isCurrent(target)) return unslimFailure(item, "job_stopped", state, "cancelled");
            String canonical = canonicalGalleryPath(candidate);
            // Android 外部存储大小写不敏感：/storage/emulated/0/pictures 与 /Pictures 是同一个
            // 目录，注册表里可能同时留下两种拼写。这里必须忽略大小写，否则原图会被当成派生副本，
            // 走到下面的"不可删除"保护后整项失败（derived_unverified），行永远清不掉。
            if (canonical == null || canonical.equalsIgnoreCase(originalCanonical)) continue;
            // 预览文件可能已经不在磁盘上，但指向它的行仍然必须被修好。
            UnslimClear clear = clearPreviewRows(context, saveToDb, record, candidate, canonical);
            if (!clear.reason.isEmpty()) return unslimFailure(item, clear.reason, state, "failed");
            state.clearedRows += clear.rows;
            File preview = new File(canonical);
            if (!preview.exists()) continue;
            // 只有确认没有任何行还引用这个路径，才允许删除文件。
            if (!isPathUnreferenced(context, preview, singlePath(candidate))) {
                return unslimFailure(item, "thumbnail_clear_failed", state, "failed");
            }
            if (!isDerivedDeletable(preview, record, true)) {
                return unslimFailure(item, "derived_unverified", state, "failed");
            }
            if (!preview.delete() || preview.exists()) {
                return unslimFailure(item, "derived_delete_failed", state, "failed");
            }
            state.deletedCopies++;
        }
        // 历史遗留：item 行的 thumbnailFile 指向 .gallerysync-slim 里未登记的预览。
        String legacyFailure = clearLegacyThumbnail(context, saveToDb, record, item.row, state);
        if (!legacyFailure.isEmpty()) return unslimFailure(item, legacyFailure, state, "failed");
        if (!isCurrent(target)) return unslimFailure(item, "job_stopped", state, "cancelled");
        return new ItemResult(item.row, "success", "", 0, state.clearedRows, state.deletedCopies);
    }

    /**
     * 清掉所有仍然把某个预览登记为 thumbnail/local 的行；写库成功后才返回，删文件由调用者决定。
     */
    private static UnslimClear clearPreviewRows(Context context, Method saveToDb,
            OriginalStore.Record record, String registeredPath, String canonical) {
        Set<String> rowIds = new LinkedHashSet<>();
        for (String queryPath : new String[]{registeredPath, canonical}) {
            if (queryPath == null || queryPath.isEmpty()) continue;
            try (Cursor cursor = context.getContentResolver().query(CLOUD_URI,
                    new String[]{"_id", "localFile", "thumbnailFile"},
                    "localFile = ? OR thumbnailFile = ?", new String[]{queryPath, queryPath}, null)) {
                if (cursor == null) return UnslimClear.failed("thumbnail_clear_failed");
                int idColumn = cursor.getColumnIndex("_id");
                int localColumn = cursor.getColumnIndex("localFile");
                int thumbnailColumn = cursor.getColumnIndex("thumbnailFile");
                if (idColumn < 0 || localColumn < 0 || thumbnailColumn < 0) {
                    return UnslimClear.failed("thumbnail_clear_failed");
                }
                while (cursor.moveToNext()) {
                    String rowId = cursor.getString(idColumn);
                    String local = cursor.isNull(localColumn) ? null : cursor.getString(localColumn);
                    String thumbnail = cursor.isNull(thumbnailColumn)
                            ? null : cursor.getString(thumbnailColumn);
                    if (rowId == null) continue;
                    if (!pathMatchesCopy(local, canonical)
                            && !pathMatchesCopy(thumbnail, canonical)) continue;
                    rowIds.add(rowId);
                }
            } catch (Throwable ignored) {
                return UnslimClear.failed("thumbnail_clear_failed");
            }
        }
        int cleared = 0;
        for (String rowId : rowIds) {
            String failure = clearThumbnailRow(context, saveToDb, record, rowId);
            if (failure != null) return UnslimClear.failed(failure);
            cleared++;
        }
        if (!rowIds.isEmpty() && !isPathUnreferenced(context, new File(canonical),
                singlePath(registeredPath))) {
            return UnslimClear.failed("thumbnail_clear_failed");
        }
        return new UnslimClear(cleared, "");
    }

    /** 历史遗留的 .gallerysync-slim 预览：先清库，再删除未被引用的侧目录文件。 */
    private static String clearLegacyThumbnail(Context context, Method saveToDb,
            OriginalStore.Record record, String itemRowId, UnslimState state) {
        String thumbnail = readThumbnailPath(context, itemRowId);
        if (thumbnail == null || thumbnail.trim().isEmpty()) return "";
        String canonical = canonicalGalleryPath(thumbnail);
        if (canonical == null || canonical.equals(canonicalGalleryPath(record.originalLocalPath))) {
            return "";
        }
        if (!canonical.toLowerCase(Locale.ROOT).replace('\\', '/')
                .contains("/.gallerysync-slim/")) return "";
        String failure = clearThumbnailRow(context, saveToDb, record, itemRowId);
        if (failure != null) return failure;
        state.clearedRows++;
        File preview = new File(canonical);
        if (!preview.exists()) return "";
        if (!isPathUnreferenced(context, preview, singlePath(thumbnail))) {
            return "thumbnail_clear_failed";
        }
        if (!isDerivedDeletable(preview, record, false)) return "derived_unverified";
        if (!preview.delete() || preview.exists()) return "derived_delete_failed";
        state.deletedCopies++;
        return "";
    }

    /** 调用 SlimController.saveToDB 清掉缩略图并把行指回原始照片，然后复读校验。 */
    private static String clearThumbnailRow(Context context, Method saveToDb,
            OriginalStore.Record record, String rowId) {
        int id;
        try {
            id = Integer.parseInt(rowId);
        } catch (NumberFormatException invalid) {
            return "unslim_row_id_invalid";
        }
        try {
            // saveToDB(int, String, String)：第 2 个参数是 thumbnailFile，第 3 个是 localFile。
            saveToDb.invoke(null, id, "", record.originalLocalPath);
        } catch (Throwable failure) {
            return "thumbnail_clear_failed";
        }
        return verifyThumbnailCleared(context, rowId, record.originalLocalPath);
    }

    /** 要求该行 thumbnailFile 为空且 localFile 已指回校验过的原始照片。 */
    private static String verifyThumbnailCleared(Context context, String rowId, String originalPath) {
        try (Cursor cursor = context.getContentResolver().query(CLOUD_URI,
                new String[]{"_id", "localFile", "thumbnailFile"},
                "_id = ?", new String[]{rowId}, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            int localColumn = cursor.getColumnIndex("localFile");
            int thumbnailColumn = cursor.getColumnIndex("thumbnailFile");
            if (localColumn < 0 || thumbnailColumn < 0) return "thumbnail_clear_failed";
            String local = cursor.isNull(localColumn) ? null : cursor.getString(localColumn);
            String thumbnail = cursor.isNull(thumbnailColumn)
                    ? null : cursor.getString(thumbnailColumn);
            if (cursor.moveToNext()) return "thumbnail_clear_failed";
            if (thumbnail != null && !thumbnail.trim().isEmpty()) return "thumbnail_clear_failed";
            String canonicalOriginal = canonicalGalleryPath(originalPath);
            if (canonicalOriginal == null || !pathMatchesCopy(local, canonicalOriginal)) {
                return "thumbnail_clear_failed";
            }
            return null;
        } catch (Throwable ignored) {
            return "thumbnail_clear_failed";
        }
    }

    /** 已登记的预览必须字节校验通过；历史侧目录预览则要求更小且位于 .gallerysync-slim。 */
    private static boolean isDerivedDeletable(File preview, OriginalStore.Record record,
            boolean registered) {
        if (preview == null || sameCanonical(preview.getPath(), record.originalLocalPath)) {
            return false;
        }
        if (registered) return verifyDerivedCopy(preview.getPath(), record) != null;
        String lower = preview.getPath().toLowerCase(Locale.ROOT).replace('\\', '/');
        return lower.contains("/.gallerysync-slim/") && preview.length() < record.originalLength;
    }

    private static String readThumbnailPath(Context context, String rowId) {
        try (Cursor cursor = context.getContentResolver().query(CLOUD_URI,
                new String[]{"_id", "thumbnailFile"}, "_id = ?", new String[]{rowId}, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            int column = cursor.getColumnIndex("thumbnailFile");
            if (column < 0) return null;
            String thumbnail = cursor.isNull(column) ? null : cursor.getString(column);
            if (cursor.moveToNext()) return null;
            return thumbnail;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 解析 SlimController.saveToDB(int,String,String)；不可用时返回 null。 */
    private Method slimSaveToDbMethod() {
        try {
            Class<?> controller = Class.forName("com.miui.gallery.cleaner.slim.SlimController",
                    true, mLoader);
            try {
                return controller.getMethod("saveToDB", int.class, String.class, String.class);
            } catch (NoSuchMethodException hidden) {
                Method declared = controller.getDeclaredMethod("saveToDB", int.class, String.class,
                        String.class);
                declared.setAccessible(true);
                return declared;
            }
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * 删除旧版本写在真照片旁边的降级副本行与文件；真照片只读校验，绝不修改。
     */
    private ItemResult legacyCopiesOne(Context context, Config config, OriginalStore store,
            String target, RequestItem item) throws Exception {
        if (!isCurrent(target)) return new ItemResult(item.row, "cancelled", "job_stopped");
        RelatedCloudRow junk = readRelatedCloudRow(context, item.row);
        if (junk == null) return new ItemResult(item.row, "failed", "related_row_missing");
        if (junk.serverId != null && !junk.serverId.trim().isEmpty()) {
            return new ItemResult(item.row, "failed", "related_row_has_server_id");
        }
        if (junk.localFlag != 7 && junk.localFlag != 8) {
            return new ItemResult(item.row, "failed", "related_row_state_unsafe");
        }
        File junkFile = safeJunkFile(junk.localFile, junk.thumbnailFile);
        if (junkFile == null) return new ItemResult(item.row, "failed", "related_file_path_unsafe");
        String junkCanonical = junkFile.getPath();
        String junkSha1;
        try {
            junkSha1 = sha1(junkFile);
        } catch (Throwable failure) {
            return new ItemResult(item.row, "failed", "related_file_hash_failed");
        }
        if (junk.sha1 == null || !junk.sha1.equalsIgnoreCase(junkSha1)) {
            return new ItemResult(item.row, "failed", "related_file_sha1_mismatch");
        }
        if (!onlyReferencedByRow(context, item.row, junk.localFile, junkCanonical)) {
            return new ItemResult(item.row, "failed", "related_file_shared");
        }
        String baseFailure = verifyLegacyBase(context, store, item, junkCanonical);
        if (!baseFailure.isEmpty()) return new ItemResult(item.row, "failed", baseFailure);
        if (!isCurrent(target)) return new ItemResult(item.row, "cancelled", "job_stopped");
        long junkId;
        try {
            junkId = Long.parseLong(item.row);
        } catch (NumberFormatException invalid) {
            return new ItemResult(item.row, "failed", "related_row_missing");
        }
        try {
            Class<?> cloudUtils = mLoader.loadClass("com.miui.gallery.provider.CloudUtils");
            Method deleteById = cloudUtils.getMethod("deleteById", Context.class, int.class,
                    int.class, long[].class);
            deleteById.invoke(null, context, 1, 0, new long[]{junkId});
        } catch (Throwable failure) {
            if (junkFile.exists()) return new ItemResult(item.row, "failed", "local_delete_api_failed");
        }
        if (junkFile.exists()) return new ItemResult(item.row, "failed", "duplicate_file_remains");
        suppressDeletedCopy(context, config, store, junkCanonical);
        return new ItemResult(item.row, "success", "", 0, 0, 1);
    }

    /**
     * 删除一条"重复/失效的相册云行"：隐藏目录误入库行（baseRow 证明真照片还在别处）、
     * 同文件重复登记行（diskLength 证明本行的 size 是过期值），或者同一份文件被登记了两次、
     * 本行是较新的那条（length == diskLength，另一行同 sha1 + 同路径，大小写不同算同一路径）。
     * 删行用 legacy_copies 同一条 CloudUtils.deleteById 通道；只有 .gallerysync-slim/ 内、已无人
     * 引用的文件才一并删除：第 3 类删的是重复登记，文件由保留行继续拥有，不动。
     */
    private ItemResult staleRowsOne(Context context, Config config, OriginalStore store,
            String target, RequestItem item) {
        if (!isCurrent(target)) return new ItemResult(item.row, "cancelled", "job_stopped");
        StaleRowSnapshot row = readStaleRowSnapshot(context, item.row);
        StaleRowFacts facts = collectStaleRowFacts(context, item, row);
        String failure = StaleRows.decision(facts);
        if (!failure.isEmpty()) return new ItemResult(item.row, "failed", failure);
        long rowId;
        try {
            rowId = Long.parseLong(item.row);
        } catch (NumberFormatException invalid) {
            return new ItemResult(item.row, "failed", "stale_row_missing");
        }
        if (!isCurrent(target)) return new ItemResult(item.row, "cancelled", "job_stopped");
        boolean invoked = false;
        try {
            Class<?> cloudUtils = mLoader.loadClass("com.miui.gallery.provider.CloudUtils");
            Method deleteById = cloudUtils.getMethod("deleteById", Context.class, int.class,
                    int.class, long[].class);
            deleteById.invoke(null, context, 1, 0, new long[]{rowId});
            invoked = true;
        } catch (Throwable failure2) {
            invoked = false;
        }
        // 删除必须可验证：API 抛异常或删完复查仍能看到这一行，都算失败。
        if (!invoked || readStaleRowSnapshot(context, item.row) != null) {
            return new ItemResult(item.row, "failed", "local_delete_api_failed");
        }
        // 行没了，但这行指向的那份副本在远端可能还有一份（2026-10-19 那 277 行就指向
        // DCIM/Camera/.gallerysync-slim/）：记账，别让下载侧看到"远端有、本地没了"又取回来。
        suppressDeletedCopy(context, config, store, row == null ? null : row.localFile);
        if (!facts.slimDirectory && !facts.classThree) {
            return new ItemResult(item.row, "success", "", 0, 0, 1);
        }
        File file = staleLocalFile(row == null ? null : row.localFile);
        if (file == null || !file.exists()) return new ItemResult(item.row, "success", "", 0, 0, 1);
        // 第 3 类删的只是重复登记，文件由保留行继续拥有。isPathUnreferenced 的路径比较区分大小写，
        // 保留行的 localFile 只有大小写不同时它会把这份文件误判成"没人引用"，所以有保留行时一律
        // 不碰文件 —— 这是预期路径，不是错误。
        if (facts.survivorFound
                || !isPathUnreferenced(context, file, singlePath(row.localFile))) {
            return new ItemResult(item.row, "success", "", 0, 0, 1);
        }
        if (!file.delete() || file.exists()) {
            return new ItemResult(item.row, "partial", "stale_file_delete_failed", 0, 0, 1);
        }
        return new ItemResult(item.row, "success", "", 0, 0, 1);
    }

    /**
     * 删除一份"内容就是全零"的坏副本行。
     *
     * <p>别的模式都要求"另有一份完好的原图"才肯删（base 行 / 唯一性 / 原图复校验）；全零文件
     * 恰恰没有这样的见证者 —— 它自己就是那段坏数据。因此这一模式的证据是内生的：请求的 sha1
     * 必须同时等于本行记录的 sha1、磁盘那份文件的实际 sha1，以及 {@code size} 个 0 字节的
     * SHA1。三者一致才证明这份文件没有任何可恢复内容。sha1 对得上但内容不是零（正常照片）
     * 时一律 {@code payload_not_broken}：这个模式绝不允许删掉真照片。
     */
    private ItemResult brokenPayloadOne(Context context, Config config, OriginalStore store,
            String target, RequestItem item) {
        if (!isCurrent(target)) return brokenPayloadResult(item, "cancelled", "job_stopped", 0);
        StaleRowSnapshot row = readStaleRowSnapshot(context, item.row);
        BrokenPayloadFacts facts = collectBrokenPayloadFacts(context, item, row);
        String failure = BrokenPayload.decision(facts);
        if (!failure.isEmpty()) return brokenPayloadResult(item, "failed", failure, 0);
        long rowId;
        try {
            rowId = Long.parseLong(item.row);
        } catch (NumberFormatException invalid) {
            return brokenPayloadResult(item, "failed", "broken_payload_missing", 0);
        }
        if (!isCurrent(target)) return brokenPayloadResult(item, "cancelled", "job_stopped", 0);
        boolean invoked = false;
        try {
            Class<?> cloudUtils = mLoader.loadClass("com.miui.gallery.provider.CloudUtils");
            Method deleteById = cloudUtils.getMethod("deleteById", Context.class, int.class,
                    int.class, long[].class);
            deleteById.invoke(null, context, 1, 0, new long[]{rowId});
            invoked = true;
        } catch (Throwable failure2) {
            invoked = false;
        }
        // 与 legacy_copies/stale_rows 同一条可验证删除通道：API 抛异常或删完复查仍能看到行都算失败。
        if (!invoked || readStaleRowSnapshot(context, item.row) != null) {
            return brokenPayloadResult(item, "failed", "local_delete_api_failed", 0);
        }
        // 行没了，但远端那份"全零对象"可能还在：记账，别让下载侧看到"远端有、本地没了"又取回来。
        suppressDeletedCopy(context, config, store, row == null ? null : row.localFile);
        File file = row == null ? null : safeBaseFile(row.localFile);
        // 文件已经不在盘上同样算成功：要删的东西已经没了。
        if (file == null || !file.exists()) return brokenPayloadResult(item, "success", "", 1);
        if (!isPathUnreferenced(context, file, singlePath(row.localFile))) {
            return brokenPayloadResult(item, "success", "", 1);
        }
        if (!file.delete() || file.exists()) {
            return brokenPayloadResult(item, "partial", "stale_file_delete_failed", 1);
        }
        return brokenPayloadResult(item, "success", "", 1);
    }

    /** 采集 broken_payload 判定需要的全部事实；判定本身在 BrokenPayload.decision 里，不碰 Android。 */
    private static BrokenPayloadFacts collectBrokenPayloadFacts(Context context, RequestItem item,
            StaleRowSnapshot row) {
        BrokenPayloadFacts facts = new BrokenPayloadFacts();
        facts.rowId = item.row;
        facts.rowExists = row != null;
        if (row == null) return facts;
        facts.sha1Matches = row.sha1 != null && row.sha1.equalsIgnoreCase(item.sha1);
        facts.hasServerId = row.serverId != null && !row.serverId.trim().isEmpty();
        facts.localFlag = row.localFlag;
        String canonical = canonicalGalleryPath(row.localFile);
        facts.localFileResolved = isStorageLocalFile(canonical);
        File file = facts.localFileResolved ? safeBaseFile(row.localFile) : null;
        facts.filePresent = file != null;
        // 请求里的 length 也必须与本行 size 一致，否则"零字节 sha1"证明的大小与磁盘对不上。
        facts.fileLengthMatches = file != null && item.length == row.size
                && file.length() == row.size;
        // 长度对不上就没必要整份哈希：判定在比对 sha1 之前先看长度。
        String fileSha1 = null;
        if (facts.fileLengthMatches) {
            try {
                fileSha1 = sha1(file);
            } catch (Throwable ignored) {
                fileSha1 = null;
            }
        }
        facts.fileSha1Matches = fileSha1 != null && fileSha1.equalsIgnoreCase(item.sha1);
        facts.payloadAllZero = BrokenPayload.isZeroPayloadSha1(item.sha1, row.size);
        List<String> referencing = referencingRowIds(context, row.localFile,
                file != null ? file.getPath() : canonical, row.thumbnailFile);
        facts.referenceQueryFailed = referencing == null;
        facts.referencingRowIds = referencing == null ? new ArrayList<>() : referencing;
        return facts;
    }

    /** 每条 broken_payload 结果都留一行日志；result 是拒绝理由，成功时是状态。 */
    private static ItemResult brokenPayloadResult(RequestItem item, String status, String reason,
            int deletedCopies) {
        Logger.i("event=broken_payload_item row=" + item.row + " result="
                + (reason == null || reason.isEmpty() ? status : reason));
        return new ItemResult(item.row, status, reason, 0, 0, deletedCopies);
    }

    /**
     * 按行精确删除一张照片（purge_items：行 + 可选本地文件 + 远端抑制）。
     *
     * <p>其余模式都是"证明这一行是垃圾"：stale_rows 要 base 行或 diskLength 作证，legacy_copies
     * 要同名的真照片，broken_payload 要内容可证明全零，orphan_rows 要两个路径字段都空。这个模式
     * 面对的是运维侧已经精确挑出来的一行（调用方按行 id 逐个下发），判据改成本行 + 磁盘的自洽性：
     * 行还在、{@code sha1} 与请求一致、没有 {@code serverId}、本地状态是 0/7/8、同路径的其它行里
     * 没有登记着<b>不同 sha1</b> 的行，本地文件存在时长度与 {@code sha1} 都要和请求逐字节对上。
     * 判定顺序写在 {@link PurgeItems#decision}；这里只负责采集事实和执行删除。
     *
     * <p>动作顺序刻意与 broken_payload 一致：先走 Gallery 自己的 {@code CloudUtils.deleteById}
     * 删行并回读确认，再记账 suppression（否则补齐侧会把远端对象又下回来），最后才删文件。
     * 文件删不掉时行已经没了，因此报 {@code partial} 而不是 {@code failed}。
     */
    private ItemResult purgeItemsOne(Context context, Config config, OriginalStore store,
            String target, RequestItem item) {
        if (!isCurrent(target)) return purgeItemResult(item, "cancelled", "job_stopped", 0);
        StaleRowSnapshot row = readStaleRowSnapshot(context, item.row);
        PurgeItemFacts facts = collectPurgeItemFacts(context, item, row);
        String failure = PurgeItems.decision(facts);
        if (!failure.isEmpty()) return purgeItemResult(item, "failed", failure, 0);
        long rowId;
        try {
            rowId = Long.parseLong(item.row);
        } catch (NumberFormatException invalid) {
            return purgeItemResult(item, "failed", "purge_row_missing", 0);
        }
        if (!isCurrent(target)) return purgeItemResult(item, "cancelled", "job_stopped", 0);
        boolean invoked = false;
        try {
            Class<?> cloudUtils = mLoader.loadClass("com.miui.gallery.provider.CloudUtils");
            Method deleteById = cloudUtils.getMethod("deleteById", Context.class, int.class,
                    int.class, long[].class);
            deleteById.invoke(null, context, 1, 0, new long[]{rowId});
            invoked = true;
        } catch (Throwable deleteFailure) {
            invoked = false;
        }
        // 与其余删除通道同一套可验证判据：API 抛异常或删完回读仍能看到行都不算删掉。
        if (!invoked || readStaleRowSnapshot(context, item.row) != null) {
            return purgeItemResult(item, "failed", "purge_delete_failed", 0);
        }
        String localFile = row == null ? null : row.localFile;
        // 行没了，但远端那份对象可能还在：不记账，补齐侧看到"远端有、本地没了"就会下回来。
        suppressDeletedCopy(context, config, store, localFile);
        File file = safeBaseFile(localFile);
        // 文件本来就不在盘上（或这一行没有本地文件）：要删的东西已经没了，只删行就够。
        if (file == null || !file.exists()) return purgeItemResult(item, "success", "", 1);
        // 同路径 + 同 sha1 的重复登记行还在指着这份文件时把它留下：行删了，文件留给还在的那条
        // 登记（它登记的是同一张照片，删掉文件只会让它变成下一条残骸）。
        if (!isPathUnreferenced(context, file, singlePath(localFile))) {
            return purgeItemResult(item, "success", "", 1);
        }
        if (!file.delete() || file.exists()) {
            return purgeItemResult(item, "partial", "purge_file_delete_failed", 1);
        }
        return purgeItemResult(item, "success", "", 1);
    }

    /** 采集 purge_items 判定需要的全部事实；判定本身在 PurgeItems.decision 里，不碰 Android。 */
    private static PurgeItemFacts collectPurgeItemFacts(Context context, RequestItem item,
            StaleRowSnapshot row) {
        PurgeItemFacts facts = new PurgeItemFacts();
        facts.rowId = item.row;
        facts.requestSha1 = item.sha1;
        facts.requestedLength = item.length;
        facts.rowExists = row != null;
        if (row == null) return facts;
        facts.sha1Matches = row.sha1 != null && row.sha1.equalsIgnoreCase(item.sha1);
        facts.hasServerId = row.serverId != null && !row.serverId.trim().isEmpty();
        facts.localFlag = row.localFlag;
        facts.localFileBlank = isBlank(row.localFile);
        String canonical = canonicalGalleryPath(row.localFile);
        facts.localFileResolved = isStorageLocalFile(canonical);
        File file = facts.localFileResolved ? safeBaseFile(row.localFile) : null;
        facts.filePresent = file != null;
        // 只有文件真的在盘上才谈得上长度与内容；不在盘上就跳过这两条（规格 7）。
        facts.lengthMatches = file != null && file.length() == item.length;
        // 长度对不上就不整份哈希：先按长度拒，省的每次都用 I/O 换一个必然不一致的结果。
        String fileSha1 = null;
        if (facts.lengthMatches) {
            try {
                fileSha1 = sha1(file);
            } catch (Throwable ignored) {
                fileSha1 = null;
            }
        }
        facts.fileSha1Matches = fileSha1 != null && fileSha1.equalsIgnoreCase(item.sha1);
        // 同路径的行要连 sha1 一起取：同路径 + 同 sha1 是同一张照片的重复登记，允许删；
        // 同路径但 sha1 不同说明这份文件是另一张照片的（例如视频及其封面），必须拒。
        Map<String, String> references = referencingRowsByPath(context, row.localFile,
                file != null ? file.getPath() : canonical, row.thumbnailFile);
        facts.referenceQueryFailed = references == null;
        if (references != null) {
            for (Map.Entry<String, String> reference : references.entrySet()) {
                facts.references.add(new PurgeReference(reference.getKey(), reference.getValue()));
            }
        }
        return facts;
    }

    /** 每条 purge_items 结果都留一行日志；result 是拒绝理由，成功时是状态。 */
    private static ItemResult purgeItemResult(RequestItem item, String status, String reason,
            int cleanedDuplicates) {
        Logger.i("event=purge_item row=" + item.row + " result="
                + (reason == null || reason.isEmpty() ? status : reason));
        return new ItemResult(item.row, status, reason, 0, 0, cleanedDuplicates);
    }

    /**
     * 删除一条"什么本地文件都没有"的云行（orphan_rows）。
     *
     * <p>这些行是模块早期探针留下的残骸：{@code localFile} 为空、{@code thumbnailFile} 为空、
     * {@code serverId} 为空、{@code localFlag = 7}，却仍然挂在相册里，既渲染不出来、也没有任何
     * 东西能重新生成它们。设备上没有 sqlite3，唯一合法的删除通道就是 Gallery 自己的
     * {@code CloudUtils.deleteById}，所以这个模式把"删掉它不会丢掉任何证据"的判据写死在
     * {@link OrphanRows#decision} 里：必须同时证明行还在、sha1 对得上、两个路径字段都空、
     * 没有 serverId、本地状态是 0/7/8、文件名非空、该 sha1 在本库没有别的行、注册表也没有记录。
     *
     * <p>这个模式不碰任何文件（本来就没有文件可碰），也不记账 suppression：抑制账本只对
     * "本地删了、远端还有"的副本有意义，而这类行既没有本地路径可反推远端路径，注册表记录
     * 又已经被 {@code orphan_row_registered} 全部拒掉，没有任何远端路径可解析。
     */
    private ItemResult orphanRowsOne(Context context, Config config, OriginalStore store,
            String target, RequestItem item) {
        if (!isCurrent(target)) return orphanRowResult(item, "cancelled", "job_stopped", 0);
        OrphanRowSnapshot row = readOrphanRowSnapshot(context, item.row);
        OrphanRowFacts facts = collectOrphanRowFacts(context, config, store, item, row);
        String failure = OrphanRows.decision(facts);
        if (!failure.isEmpty()) return orphanRowResult(item, "failed", failure, 0);
        long rowId;
        try {
            rowId = Long.parseLong(item.row);
        } catch (NumberFormatException invalid) {
            return orphanRowResult(item, "failed", "orphan_row_missing", 0);
        }
        if (!isCurrent(target)) return orphanRowResult(item, "cancelled", "job_stopped", 0);
        boolean invoked = false;
        try {
            Class<?> cloudUtils = mLoader.loadClass("com.miui.gallery.provider.CloudUtils");
            Method deleteById = cloudUtils.getMethod("deleteById", Context.class, int.class,
                    int.class, long[].class);
            deleteById.invoke(null, context, 1, 0, new long[]{rowId});
            invoked = true;
        } catch (Throwable failure2) {
            invoked = false;
        }
        // 与 stale_rows/broken_payload 同一条可验证删除通道：API 抛异常或删完复查仍能看到行都算失败。
        if (!invoked || readOrphanRowSnapshot(context, item.row) != null) {
            return orphanRowResult(item, "failed", "local_delete_api_failed", 0);
        }
        // 刻意不调用 suppressDeletedCopy：这一行没有任何本地文件（规则 2），也没有注册表记录
        // （规则 7 已经把有记录的行拒成 orphan_row_registered），因此不存在可以解析出来的远端
        // 路径；按 remotePathOfDeletedCopy 的反推规则去猜只会给出一个错误的远端对象。
        return orphanRowResult(item, "success", "", 1);
    }

    /** 采集 orphan_rows 判定需要的全部事实；判定本身在 OrphanRows.decision 里，不碰 Android。 */
    private static OrphanRowFacts collectOrphanRowFacts(Context context, Config config,
            OriginalStore store, RequestItem item, OrphanRowSnapshot row) {
        OrphanRowFacts facts = new OrphanRowFacts();
        facts.rowId = item.row;
        facts.rowExists = row != null;
        // 注册表查询失败按"已登记"处理：读不到就绝不删。
        boolean registered = true;
        try {
            registered = store != null && store.find(config, item.row, item.sha1) != null;
        } catch (Throwable ignored) {
            registered = true;
        }
        facts.registryRegistered = registered;
        if (row == null) return facts;
        facts.sha1Matches = row.sha1 != null && !row.sha1.trim().isEmpty()
                && row.sha1.equalsIgnoreCase(item.sha1);
        facts.localFileBlank = isBlank(row.localFile);
        facts.thumbnailFileBlank = isBlank(row.thumbnailFile);
        facts.hasServerId = row.serverId != null && !row.serverId.trim().isEmpty();
        facts.localFlag = row.localFlag;
        facts.fileNameBlank = isBlank(row.fileName);
        // 规则 6（已收窄）：真正算"共享"的只有同 sha1 且还能产出内容的别的行——本地有非空
        // localFile，或远端有非空 serverId。同为无文件残骸的孤儿行不算：真机上 17 条残骸里有
        // 12 条共用空文件的 sha1，若按"同 sha1 行数 > 1"判，它们会把彼此永久钉住，一条也清不掉。
        // 两种 sha1 写法各查一次取大值：SQLite 的 "=" 区分大小写，大小写不同的重复行不能漏。
        // 查询失败返回 -1，判定按"仍被共享"拒删（fail closed）。
        long byRequestSha1 = countMaterialisableRowsBySha1(context, item.sha1, item.row);
        long byRowSha1 = countMaterialisableRowsBySha1(context, row.sha1, item.row);
        facts.materialisableSha1Rows = byRequestSha1 < 0L || byRowSha1 < 0L
                ? -1L : Math.max(byRequestSha1, byRowSha1);
        return facts;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 每条 orphan_rows 结果都留一行日志；result 是拒绝理由，成功时是状态。 */
    private static ItemResult orphanRowResult(RequestItem item, String status, String reason,
            int cleanedDuplicates) {
        Logger.i("event=orphan_row_item row=" + item.row + " result="
                + (reason == null || reason.isEmpty() ? status : reason));
        return new ItemResult(item.row, status, reason, 0, 0, cleanedDuplicates);
    }

    private static OrphanRowSnapshot readOrphanRowSnapshot(Context context, String rowId) {
        try (Cursor cursor = context.getContentResolver().query(CLOUD_URI, ORPHAN_ROW_COLUMNS,
                "_id = ?", new String[]{rowId}, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            OrphanRowSnapshot snapshot = readOrphanRowSnapshot(cursor);
            if (cursor.moveToNext()) return null;
            return snapshot;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static OrphanRowSnapshot readOrphanRowSnapshot(Cursor cursor) {
        int idColumn = cursor.getColumnIndex("_id");
        if (idColumn < 0) return null;
        String rowId = cursor.getString(idColumn);
        if (rowId == null) return null;
        return new OrphanRowSnapshot(rowId, stringColumn(cursor, "localFile"),
                stringColumn(cursor, "thumbnailFile"), stringColumn(cursor, "sha1"),
                stringColumn(cursor, "serverId"), intColumn(cursor, "localFlag"),
                stringColumn(cursor, "fileName"));
    }

    /**
     * 把刚删掉的那份垃圾副本记进抑制账本（{@link RemoteSuppress}）。
     *
     * <p>删除本身不构成"远端也该消失"的证据：远端那份对象还可能好好的，而下载侧的补齐判据
     * 只看"远端有、本地没了"。不留下这条痕迹，就是删一次被下回来一次 —— 2026-10-19
     * 20:24-20:37 那 493 个文件正是这么反复回来的。抑制失败只记日志：账本坏了顶多回到旧行为，
     * 不能让一次清理任务因此失败。
     */
    private static void suppressDeletedCopy(Context context, Config config, OriginalStore store,
            String localPath) {
        String remote = remotePathOfDeletedCopy(config, store, localPath);
        if (remote == null) return;
        try {
            RemoteSuppress ledger = RemoteSuppress.get(context);
            ledger.suppress(config, remote);
            Logger.i("event=remote_suppress_note local=" + localPath + " remote=" + remote);
        } catch (Throwable failure) {
            Logger.w("event=remote_suppress_note_failed remote=" + remote
                    + " reason=" + failure);
        }
    }

    /**
     * 被删掉的垃圾副本对应的远端路径：注册表登记过就用登记的那条（唯一权威来源，按时间/全平铺
     * 布局也拿得到），没有登记才在 album 布局下按"远端根 + 存储根之后的相对路径"反推。
     * 两条都不成立时返回 {@code null}：宁可这条不抑制，也不要猜错路径。
     */
    static String remotePathOfDeletedCopy(Config config, OriginalStore store, String localPath) {
        String registered = null;
        if (store != null && localPath != null) {
            try {
                String canonical = canonicalGalleryPath(localPath);
                OriginalStore.Record record = store.findByLocalPath(new File(localPath));
                // 只有"删掉的这份就是记录里的那张原图"才谈得上远端对象。删的是它的派生/预览
                // 素材（旧方案的 stem.jpg 副本、.hq.jpg 之类）时，远端并没有对应的副本对象，
                // 用记录的 remotePath 反而会把原图挡在门外：2026-10-19 那轮里 54 张云端原图
                // 就是这样被抑制，补齐缺失原图再也下不回来。
                if (record != null && canonical != null
                        && sameCanonical(canonical, record.originalLocalPath)) {
                    registered = record.remotePath;
                }
            } catch (Throwable ignored) {
                // 查不到就当没登记，交给下面的反推
            }
        }
        return RemoteSuppress.remotePathFor(config, registered, localPath);
    }

    /** 采集 stale_rows 判定需要的全部事实；判定本身在 StaleRows.decision 里，不碰 Android。 */
    private static StaleRowFacts collectStaleRowFacts(Context context, RequestItem item,
            StaleRowSnapshot row) {
        StaleRowFacts facts = new StaleRowFacts();
        facts.rowId = item.row;
        facts.rowExists = row != null;
        facts.classOne = item.baseRow != null;
        // 第 3 类：length == diskLength 表示"本行和另一行是同一份文件的两次登记"，缺了长度或长度
        // 对不上就还是第 2 类的"size 过期"故事。两条证据同时出现时只归第 3 类。
        facts.classThree = item.diskLength > 0L && item.length > 0L && item.length == item.diskLength;
        facts.classTwo = item.diskLength > 0L && !facts.classThree;
        facts.diskLengthPositive = item.diskLength > 0L;
        if (row == null) return facts;
        facts.sha1Matches = row.sha1 != null && row.sha1.equalsIgnoreCase(item.sha1);
        facts.hasServerId = row.serverId != null && !row.serverId.trim().isEmpty();
        facts.localFlag = row.localFlag;
        facts.slimDirectory = StaleRows.isSlimDirectoryPath(row.localFile);
        String canonical = canonicalGalleryPath(row.localFile);
        facts.localFileResolved = isStorageLocalFile(canonical);
        List<String> referencing = referencingRowIds(context, row.localFile, canonical,
                row.thumbnailFile);
        facts.referenceQueryFailed = referencing == null;
        facts.referencingRowIds = referencing == null ? new ArrayList<>() : referencing;
        long sha1Rows = countRowsBySha1(context, row.sha1);
        // 查询失败(-1)按"唯一"保守处理：读不到就绝不删。
        facts.onlyRowForSha1 = sha1Rows >= 0L && sha1Rows <= 1L;
        if (facts.classOne) {
            facts.baseRowValid = verifyStaleBase(context, item.baseRow, item.row, row);
        }
        // 偏差说明：第 1 类的 base 行是同一张照片仍在盘上的另一份文件，sha1 与瘦身行不同、只有
        // dateTaken 相同；若只按 sha1 判"唯一"，任务给出的两条 .gallerysync-slim 重复行都会被
        // stale_row_would_lose_file 拒掉，本模式将永远删不掉任何一行。base 行校验通过即证明这张
        // 照片在本库仍有记录，因此这里清掉唯一性标记（没有任何行能证明存活时才保留）。
        if (facts.baseRowValid) facts.onlyRowForSha1 = false;
        if (facts.classTwo) {
            StaleRowSnapshot twin = findStaleTwin(context, row, item.diskLength);
            facts.twinFound = twin != null;
            facts.twinRowId = twin == null ? null : twin.rowId;
            facts.rowSizeStale = row.size != item.diskLength;
            facts.diskLengthMatchesFile = diskLengthMatchesFile(canonical, item.diskLength);
        }
        if (facts.classThree) {
            collectDuplicateFacts(context, item, row, canonical, facts);
        }
        return facts;
    }

    /**
     * 第 3 类的事实：同一份文件被登记了两次（同 sha1、同 localFile，大小写不同算同一路径）。
     *
     * <p>保留 id 最小的那条登记（最老的），本行只有比它新才允许删；保留行必须还活着、大小与磁盘
     * 一致，并且没有第三行（包括通过 thumbnailFile 引用）也指着这份文件。
     */
    private static void collectDuplicateFacts(Context context, RequestItem item,
            StaleRowSnapshot row, String canonical, StaleRowFacts facts) {
        facts.duplicateFilePresent = safeBaseFile(row.localFile) != null;
        facts.rowSizeMatchesDisk = row.size == item.diskLength;
        facts.diskLengthMatchesFile = diskLengthMatchesFile(canonical, item.diskLength);
        long rowId = parseRowId(row.rowId);
        if (rowId <= 0L) return;
        StaleRowSnapshot survivor = findDuplicateRow(context, row, item.sha1, rowId);
        facts.survivorFound = survivor != null;
        if (survivor == null) return;
        long survivorId = parseRowId(survivor.rowId);
        facts.survivorRowId = survivor.rowId;
        // 只留最老的登记：保留行的 id 必须严格小于本行，否则该留下的是本行（duplicate_survivor_newer）。
        facts.survivorNewer = survivorId <= 0L || survivorId >= rowId;
        facts.survivorSha1Matches = survivor.sha1 != null && !survivor.sha1.trim().isEmpty()
                && survivor.sha1.equalsIgnoreCase(item.sha1);
        facts.survivorStateSafe = StaleRows.isDuplicateLocalFlag(survivor.localFlag);
        facts.survivorSizeMatchesFile = survivor.size == item.diskLength;
    }

    /**
     * 第 3 类的另一半：同 sha1、且 localFile 与本行是同一路径（外部存储大小写不敏感）的另一行；
     * 有多行时取 id 最小的那条（要保留的是最老的登记）。
     *
     * <p>按 sha1 查出候选、再在 Java 里比路径：内容提供者的 {@code localFile = ?} 区分大小写，
     * 而 {@code /storage/emulated/0/pictures/…} 与 {@code /Pictures/…} 在这台设备上是同一份文件，
     * 只在 SQL 里按路径查就会漏掉它们。查询失败返回 null，判定按"没有保留行"保守拒绝。
     */
    private static StaleRowSnapshot findDuplicateRow(Context context, StaleRowSnapshot row,
            String requestSha1, long rowId) {
        String rowCanonical = canonicalGalleryPath(row.localFile);
        if (rowCanonical == null) return null;
        StaleRowSnapshot oldest = null;
        long oldestId = Long.MAX_VALUE;
        Set<String> queriedSha1 = new HashSet<>();
        for (String sha1 : new String[]{row.sha1, requestSha1}) {
            if (sha1 == null || sha1.trim().isEmpty() || !queriedSha1.add(sha1.trim())) continue;
            try (Cursor cursor = context.getContentResolver().query(CLOUD_URI, STALE_ROW_COLUMNS,
                    "sha1 = ?", new String[]{sha1.trim()}, null)) {
                if (cursor == null) return null;
                while (cursor.moveToNext()) {
                    StaleRowSnapshot candidate = readStaleRowSnapshot(cursor);
                    if (candidate == null) continue;
                    long candidateId = parseRowId(candidate.rowId);
                    if (candidateId <= 0L || candidateId == rowId) continue;
                    if (!StaleRows.isSameDuplicatePath(rowCanonical,
                            canonicalGalleryPath(candidate.localFile))) {
                        continue;
                    }
                    if (candidateId < oldestId) {
                        oldestId = candidateId;
                        oldest = candidate;
                    }
                }
            } catch (Throwable ignored) {
                return null;
            }
        }
        return oldest;
    }

    /** 解析行号；解析不出来返回 -1（判定按"没有这一行"处理）。 */
    private static long parseRowId(String rowId) {
        if (rowId == null) return -1L;
        try {
            return Long.parseLong(rowId.trim());
        } catch (NumberFormatException invalid) {
            return -1L;
        }
    }

    /** base 行必须仍在盘上，并且与本行同 sha1 或同 dateTaken（证明待删行不是唯一一份）。 */
    private static boolean verifyStaleBase(Context context, String baseRowId, String rowId,
            StaleRowSnapshot row) {
        if (baseRowId == null || baseRowId.equals(rowId)) return false;
        StaleRowSnapshot base = readStaleRowSnapshot(context, baseRowId);
        if (base == null) return false;
        boolean sameSha1 = base.sha1 != null && row.sha1 != null
                && base.sha1.equalsIgnoreCase(row.sha1);
        boolean sameDate = base.dateTaken > 0L && base.dateTaken == row.dateTaken;
        if (!sameSha1 && !sameDate) return false;
        File baseFile = safeBaseFile(base.localFile);
        return baseFile != null && base.size > 0L && baseFile.length() == base.size;
    }

    /** 第 2 类的另一行：同 sha1、同 localFile，且 size 正是磁盘上的字节数。 */
    private static StaleRowSnapshot findStaleTwin(Context context, StaleRowSnapshot row,
            long diskLength) {
        String rowCanonical = canonicalGalleryPath(row.localFile);
        if (rowCanonical == null) return null;
        Set<String> seenPaths = new HashSet<>();
        for (String path : new String[]{row.localFile, rowCanonical}) {
            if (path == null || path.isEmpty() || !seenPaths.add(path)) continue;
            try (Cursor cursor = context.getContentResolver().query(CLOUD_URI, STALE_ROW_COLUMNS,
                    "localFile = ? OR thumbnailFile = ?", new String[]{path, path}, null)) {
                if (cursor == null) return null;
                while (cursor.moveToNext()) {
                    StaleRowSnapshot twin = readStaleRowSnapshot(cursor);
                    if (twin == null || twin.rowId.equals(row.rowId) || row.sha1 == null
                            || twin.sha1 == null || !twin.sha1.equalsIgnoreCase(row.sha1)) {
                        continue;
                    }
                    if (!pathMatchesCopy(twin.localFile, rowCanonical)) continue;
                    if (twin.size == diskLength) return twin;
                }
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** 本行的 localFile/thumbnailFile 仍被哪些行引用；查询失败时返回 null（判定按共享处理）。 */
    private static List<String> referencingRowIds(Context context, String localFile,
            String canonical, String thumbnailFile) {
        List<String> rowIds = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        for (String path : new String[]{localFile, canonical}) {
            if (!addReferencingRowIds(context, rowIds, seenPaths, path)) return null;
        }
        if (thumbnailFile != null && !thumbnailFile.trim().isEmpty()) {
            for (String path : new String[]{thumbnailFile, canonicalGalleryPath(thumbnailFile)}) {
                if (!addReferencingRowIds(context, rowIds, seenPaths, path)) return null;
            }
        }
        return rowIds;
    }

    private static boolean addReferencingRowIds(Context context, List<String> rowIds,
            Set<String> seenPaths, String path) {
        if (path == null || path.isEmpty() || !seenPaths.add(path)) return true;
        try (Cursor cursor = context.getContentResolver().query(CLOUD_URI, new String[]{"_id"},
                "localFile = ? OR thumbnailFile = ?", new String[]{path, path}, null)) {
            if (cursor == null) return false;
            int idColumn = cursor.getColumnIndex("_id");
            if (idColumn < 0) return false;
            while (cursor.moveToNext()) {
                String id = cursor.getString(idColumn);
                if (id != null && !rowIds.contains(id)) rowIds.add(id);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 与本行共享 localFile/thumbnailFile 的所有行：行 id → 该行登记的 sha1（查不到时为 null）。
     *
     * <p>purge_items 需要知道"同路径的那些行是不是同一张照片"，所以这里不能只取 {@code _id}：
     * 同路径 + 同 sha1 是同一张照片的重复登记（允许删其中一行），同路径 + 不同 sha1 说明这份
     * 文件被另一张照片当封面用（必须拒）。任一查询失败返回 {@code null}，判定按"被共享"处理。
     */
    private static Map<String, String> referencingRowsByPath(Context context, String localFile,
            String canonical, String thumbnailFile) {
        Map<String, String> rows = new LinkedHashMap<>();
        Set<String> seenPaths = new HashSet<>();
        for (String path : new String[]{localFile, canonical}) {
            if (!addReferencingRows(context, rows, seenPaths, path)) return null;
        }
        if (thumbnailFile != null && !thumbnailFile.trim().isEmpty()) {
            for (String path : new String[]{thumbnailFile, canonicalGalleryPath(thumbnailFile)}) {
                if (!addReferencingRows(context, rows, seenPaths, path)) return null;
            }
        }
        return rows;
    }

    private static boolean addReferencingRows(Context context, Map<String, String> rows,
            Set<String> seenPaths, String path) {
        if (path == null || path.isEmpty() || !seenPaths.add(path)) return true;
        try (Cursor cursor = context.getContentResolver().query(CLOUD_URI,
                new String[]{"_id", "sha1"}, "localFile = ? OR thumbnailFile = ?",
                new String[]{path, path}, null)) {
            if (cursor == null) return false;
            int idColumn = cursor.getColumnIndex("_id");
            if (idColumn < 0) return false;
            int sha1Column = cursor.getColumnIndex("sha1");
            while (cursor.moveToNext()) {
                String id = cursor.getString(idColumn);
                if (id == null || rows.containsKey(id)) continue;
                rows.put(id, sha1Column < 0 || cursor.isNull(sha1Column)
                        ? null : cursor.getString(sha1Column));
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 该 sha1 在本库中的行数；查询失败返回 -1，让判定按"唯一行"保守处理。 */
    private static long countRowsBySha1(Context context, String sha1) {
        if (sha1 == null || sha1.isEmpty()) return -1L;
        try (Cursor cursor = context.getContentResolver().query(CLOUD_URI, new String[]{"_id"},
                "sha1 = ?", new String[]{sha1}, null)) {
            if (cursor == null) return -1L;
            return cursor.getCount();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /**
     * 与本行同 sha1、且仍能产出内容的其它行数（不含本行自身）。
     *
     * <p>"能产出内容"= 有非空 {@code localFile}（盘上还有一份可读文件）或有非空 {@code serverId}
     * （远端还有对象可下载）。同为无文件残骸的孤儿行不算共享：它们自己也交付不出任何内容，
     * 把它们计成共享只会让共用空文件 sha1 的一整批残骸互相锁死。查询失败返回 -1，
     * 判定按"仍被共享"拒删（fail closed）。
     */
    private static long countMaterialisableRowsBySha1(Context context, String sha1,
            String selfRowId) {
        if (sha1 == null || sha1.trim().isEmpty()) return -1L;
        try (Cursor cursor = context.getContentResolver().query(CLOUD_URI,
                new String[]{"_id", "localFile", "serverId"}, "sha1 = ?", new String[]{sha1},
                null)) {
            if (cursor == null) return -1L;
            long count = 0L;
            while (cursor.moveToNext()) {
                String id = stringColumn(cursor, "_id");
                if (selfRowId != null && selfRowId.equals(id)) continue;
                if (!isBlank(stringColumn(cursor, "localFile"))
                        || !isBlank(stringColumn(cursor, "serverId"))) {
                    count++;
                }
            }
            return count;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static boolean diskLengthMatchesFile(String canonicalPath, long diskLength) {
        if (canonicalPath == null) return false;
        File file = new File(canonicalPath);
        // 文件已经不在盘上时无法反证 diskLength；存在则必须与请求里的字节数一致。
        return !file.isFile() || file.length() == diskLength;
    }

    /** 共享存储根目录内的规范化路径（不要求文件存在）。 */
    private static boolean isStorageLocalFile(String canonicalPath) {
        if (canonicalPath == null) return false;
        try {
            File root = new File(STORAGE_ROOT).getCanonicalFile();
            return canonicalPath.startsWith(root.getPath() + File.separator);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static File staleLocalFile(String rawPath) {
        String canonical = canonicalGalleryPath(rawPath);
        return isStorageLocalFile(canonical) ? new File(canonical) : null;
    }

    private static StaleRowSnapshot readStaleRowSnapshot(Context context, String rowId) {
        try (Cursor cursor = context.getContentResolver().query(CLOUD_URI, STALE_ROW_COLUMNS,
                "_id = ?", new String[]{rowId}, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            StaleRowSnapshot snapshot = readStaleRowSnapshot(cursor);
            if (cursor.moveToNext()) return null;
            return snapshot;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static StaleRowSnapshot readStaleRowSnapshot(Cursor cursor) {
        int idColumn = cursor.getColumnIndex("_id");
        if (idColumn < 0) return null;
        String rowId = cursor.getString(idColumn);
        if (rowId == null) return null;
        return new StaleRowSnapshot(rowId, stringColumn(cursor, "localFile"),
                stringColumn(cursor, "thumbnailFile"), stringColumn(cursor, "sha1"),
                stringColumn(cursor, "serverId"), intColumn(cursor, "localFlag"),
                longColumn(cursor, "size"), longColumn(cursor, "realSize"),
                longColumn(cursor, "dateTaken"));
    }

    private static String stringColumn(Cursor cursor, String name) {
        int index = cursor.getColumnIndex(name);
        return index < 0 || cursor.isNull(index) ? null : cursor.getString(index);
    }

    private static int intColumn(Cursor cursor, String name) {
        int index = cursor.getColumnIndex(name);
        return index < 0 || cursor.isNull(index) ? 0 : cursor.getInt(index);
    }

    private static long longColumn(Cursor cursor, String name) {
        int index = cursor.getColumnIndex(name);
        return index < 0 || cursor.isNull(index) ? 0L : cursor.getLong(index);
    }

    /** 校验副本旁边的真照片：优先用 baseRow+baseSha1，否则由文件名反推原始记录。 */
    private String verifyLegacyBase(Context context, OriginalStore store, RequestItem item,
            String junkCanonical) {
        if (item.baseRow != null) {
            RelatedCloudRow base = readRelatedCloudRow(context, item.baseRow);
            if (base == null) return "original_recheck_failed";
            File baseFile = safeBaseFile(base.localFile);
            if (baseFile == null) return "original_recheck_failed";
            if (baseFile.getPath().equals(junkCanonical)) return "related_file_is_original";
            String digest;
            try {
                digest = sha1(baseFile);
            } catch (Throwable failure) {
                return "original_recheck_failed";
            }
            if (item.baseSha1 == null || !item.baseSha1.equalsIgnoreCase(digest)) {
                return "original_recheck_failed";
            }
            return "";
        }
        // 没有 baseRow 时，按去掉尾部 _<digits> 的名字反推真照片并让原始记录校验它。
        String expected = stripLegacySuffix(junkCanonical);
        if (expected == null) return "original_recheck_failed";
        OriginalStore.Record record = store.findByLocalPath(new File(expected));
        if (record == null) return "no_registry_record";
        String registered = canonicalGalleryPath(record.originalLocalPath);
        File original = safeOriginalFile(record.originalLocalPath);
        if (registered == null || !registered.equals(expected) || original == null
                || pathMatchesCopy(original.getPath(), junkCanonical)) {
            return "original_recheck_failed";
        }
        if (!isOriginalVerified(original, record)) return "original_recheck_failed";
        return "";
    }

    /** 去掉旧副本名尾部的 _<digits>；目录与扩展名保持不变。 */
    private static String stripLegacySuffix(String canonicalPath) {
        if (canonicalPath == null || canonicalPath.isEmpty()) return null;
        int slash = canonicalPath.lastIndexOf(File.separatorChar);
        if (slash < 0) return null;
        String parent = canonicalPath.substring(0, slash);
        String name = canonicalPath.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return null;
        String stem = name.substring(0, dot);
        int underscore = stem.lastIndexOf('_');
        if (underscore <= 0) return null;
        String digits = stem.substring(underscore + 1);
        if (!digits.matches("[0-9]+")) return null;
        return parent + File.separator + stem.substring(0, underscore) + name.substring(dot);
    }

    /** 只接受共享存储根目录下已存在的普通文件作为待删副本。 */
    private static File safeJunkFile(String localPath, String thumbnailPath) {
        if (localPath == null || localPath.trim().isEmpty()) return null;
        try {
            File root = new File(STORAGE_ROOT).getCanonicalFile();
            File candidate = new File(localPath).getCanonicalFile();
            String path = candidate.getPath();
            if (!candidate.isFile() || !path.startsWith(root.getPath() + File.separator)) {
                return null;
            }
            if (thumbnailPath != null && !thumbnailPath.trim().isEmpty()
                    && !path.equals(canonicalGalleryPath(thumbnailPath))) return null;
            return candidate;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static File safeBaseFile(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) return null;
        try {
            File root = new File(STORAGE_ROOT).getCanonicalFile();
            File candidate = new File(rawPath).getCanonicalFile();
            if (!candidate.isFile()
                    || !candidate.getPath().startsWith(root.getPath() + File.separator)) {
                return null;
            }
            return candidate;
        } catch (IOException ignored) {
            return null;
        }
    }

    /**
     * 删除一个只由本模块生成的临时文件：路径与文件名都要符合约定，且没有任何 cloud 行引用它。
     */
    private static ItemResult deleteTempFile(Context context, String rawPath) {
        File file = safeTempFile(rawPath);
        if (file == null) return new ItemResult("temp", "failed", "temp_path_unsafe");
        if (!tempFileNameAllowed(file.getName())) {
            return new ItemResult("temp", "failed", "temp_name_not_allowed");
        }
        if (!isPathUnreferenced(context, file, singlePath(rawPath))) {
            return new ItemResult("temp", "failed", "temp_file_shared");
        }
        if (!file.exists()) return new ItemResult("temp", "success", "");
        if (!file.isFile()) return new ItemResult("temp", "failed", "temp_path_unsafe");
        if (!file.delete() || file.exists()) {
            return new ItemResult("temp", "failed", "temp_delete_failed");
        }
        return new ItemResult("temp", "success", "");
    }

    private static File safeTempFile(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) return null;
        String path = rawPath.trim();
        if (!path.startsWith("/")) return null;
        try {
            File root = new File(STORAGE_ROOT).getCanonicalFile();
            File file = new File(path).getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath() + File.separator)) return null;
            return file;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean tempFileNameAllowed(String name) {
        if (name == null || name.isEmpty()) return false;
        return name.matches("^\\..*\\.mgs-[0-9A-Za-z-]+\\.tmp") || name.contains(".tmp.tmp-");
    }

    /** 没有任何 cloud 行在 localFile/thumbnailFile 里指向这个路径时为真。 */
    private static boolean isPathUnreferenced(Context context, File file, Set<String> extraPaths) {
        String canonical = file.getPath();
        Set<String> queryPaths = new HashSet<>();
        queryPaths.add(canonical);
        if (extraPaths != null) queryPaths.addAll(extraPaths);
        for (String path : queryPaths) {
            if (path == null || path.isEmpty()) continue;
            try (Cursor cursor = context.getContentResolver().query(CLOUD_URI,
                    new String[]{"_id", "localFile", "thumbnailFile"},
                    "localFile = ? OR thumbnailFile = ?", new String[]{path, path}, null)) {
                if (cursor == null) return false;
                int localColumn = cursor.getColumnIndex("localFile");
                int thumbnailColumn = cursor.getColumnIndex("thumbnailFile");
                if (localColumn < 0 || thumbnailColumn < 0) return false;
                while (cursor.moveToNext()) {
                    String local = cursor.isNull(localColumn) ? null : cursor.getString(localColumn);
                    String thumbnail = cursor.isNull(thumbnailColumn)
                            ? null : cursor.getString(thumbnailColumn);
                    if (pathMatchesCopy(local, canonical) || pathMatchesCopy(thumbnail, canonical)) {
                        return false;
                    }
                }
            } catch (Throwable ignored) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> singlePath(String path) {
        Set<String> paths = new HashSet<>();
        if (path != null && !path.isEmpty()) paths.add(path);
        return paths;
    }

    private static ItemResult unslimFailure(RequestItem item, String reason, UnslimState state,
            String status) {
        return new ItemResult(item.row, status, reason, 0, state.clearedRows, state.deletedCopies);
    }

    private static CloudRow readCloudRow(Context context, String rowId, String sha1,
            OriginalStore.Record record) {
        try (Cursor cursor = context.getContentResolver().query(CLOUD_URI,
                new String[]{"_id", "sha1", "localFile", "thumbnailFile", "dateTaken",
                        "mixedDateTime", "dateModified", "realDateModified"},
                "_id = ?", new String[]{rowId}, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            int idColumn = cursor.getColumnIndex("_id");
            int shaColumn = cursor.getColumnIndex("sha1");
            int localColumn = cursor.getColumnIndex("localFile");
            int thumbnailColumn = cursor.getColumnIndex("thumbnailFile");
            if (idColumn < 0 || shaColumn < 0 || localColumn < 0
                    || !rowId.equals(cursor.getString(idColumn))
                    || !sha1.equalsIgnoreCase(cursor.getString(shaColumn))) return null;
            String gallerySha1 = cursor.getString(shaColumn);
            boolean localNull = cursor.isNull(localColumn);
            String local = localNull ? null : cursor.getString(localColumn);
            String thumbnail = thumbnailColumn < 0 || cursor.isNull(thumbnailColumn)
                    ? null : cursor.getString(thumbnailColumn);
            Map<String, Long> dates = readPositiveDates(cursor);
            if (cursor.moveToNext()) return null;
            if (!isRegisteredPath(local, record) && !isRegisteredPath(thumbnail, record)) {
                return null;
            }
            return new CloudRow(gallerySha1, local, localNull, dates);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Map<String, Long> readPositiveDates(Cursor cursor) {
        Map<String, Long> dates = new LinkedHashMap<>();
        for (String column : new String[]{"dateTaken", "mixedDateTime",
                "dateModified", "realDateModified"}) {
            int index = cursor.getColumnIndex(column);
            if (index < 0 || cursor.isNull(index)) continue;
            long value = cursor.getLong(index);
            if (value > 0L) dates.put(column, value);
        }
        return dates;
    }

    /** Repair only registered derived files whose current bytes still match their fingerprints. */
    private static RepairCounts repairDerivedCopies(Context context, String originalRowId,
            OriginalStore.Record record, CloudRow originalRow) {
        if (record.originalLocalMtime <= 0L || originalRow.dates.isEmpty()) {
            return RepairCounts.EMPTY;
        }
        Set<String> visited = new HashSet<>();
        int repairedCopies = 0;
        int repairedRows = 0;
        for (String registeredPath : record.derivedPaths) {
            if (Thread.currentThread().isInterrupted()) break;
            VerifiedCopy copy = verifyDerivedCopy(registeredPath, record);
            if (copy == null || sameCanonical(copy.file.getPath(), record.originalLocalPath)
                    || !visited.add(copy.file.getPath())) continue;
            if (Thread.currentThread().isInterrupted()) break;
            if (copy.file.lastModified() != record.originalLocalMtime
                    && !copy.file.setLastModified(record.originalLocalMtime)) continue;
            repairedCopies++;
            repairedRows += repairGalleryRows(context, originalRowId, copy, originalRow.dates);
        }
        return new RepairCounts(repairedCopies, repairedRows);
    }

    private static VerifiedCopy verifyDerivedCopy(String registeredPath,
            OriginalStore.Record record) {
        if (registeredPath == null || registeredPath.isEmpty()) return null;
        try {
            File root = new File(STORAGE_ROOT).getCanonicalFile();
            File file = new File(registeredPath).getCanonicalFile();
            if (!file.isAbsolute() || !file.getPath().startsWith(root.getPath() + File.separator)
                    || !file.isFile()) return null;
            long length = file.length();
            String digest = sha256(file);
            Long registeredLength = record.derivedHashes.get(digest);
            if (registeredLength == null || registeredLength.longValue() != length) return null;
            return new VerifiedCopy(registeredPath, file, digest);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("job_stopped");
                }
                digest.update(buffer, 0, read);
            }
        }
        char[] alphabet = "0123456789abcdef".toCharArray();
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(alphabet[(value >>> 4) & 0x0f]);
            hex.append(alphabet[value & 0x0f]);
        }
        return hex.toString();
    }

    private static int repairGalleryRows(Context context, String originalRowId, VerifiedCopy copy,
            Map<String, Long> dates) {
        int repaired = 0;
        Set<String> repairedIds = new HashSet<>();
        String canonicalPath = copy.file.getPath();
        for (String queryPath : new String[]{copy.registeredPath, canonicalPath}) {
            try (Cursor cursor = context.getContentResolver().query(CLOUD_URI,
                    new String[]{"_id", "localFile", "thumbnailFile"},
                    "localFile = ? OR thumbnailFile = ?", new String[]{queryPath, queryPath}, null)) {
                if (cursor == null) continue;
                int idColumn = cursor.getColumnIndex("_id");
                int localColumn = cursor.getColumnIndex("localFile");
                int thumbnailColumn = cursor.getColumnIndex("thumbnailFile");
                if (idColumn < 0 || localColumn < 0 || thumbnailColumn < 0) continue;
                while (cursor.moveToNext()) {
                    String rowId = cursor.getString(idColumn);
                    String local = cursor.isNull(localColumn) ? null : cursor.getString(localColumn);
                    String thumbnail = cursor.isNull(thumbnailColumn)
                            ? null : cursor.getString(thumbnailColumn);
                    if (rowId == null || rowId.equals(originalRowId)
                            || !pathMatchesCopy(local, canonicalPath)
                            && !pathMatchesCopy(thumbnail, canonicalPath)
                            || !repairedIds.add(rowId)) continue;
                    int updated = updateRowDates(context, rowId, local, thumbnail, dates);
                    if (updated == 1) repaired++;
                }
            } catch (Throwable ignored) {
                // A stale or inaccessible alias is skipped; verified bytes remain untouched.
            }
        }
        return repaired;
    }

    private static boolean pathMatchesCopy(String path, String canonicalCopyPath) {
        String canonical = canonicalGalleryPath(path);
        return canonicalCopyPath.equals(canonical);
    }

    private static int updateRowDates(Context context, String rowId, String oldLocal,
            String oldThumbnail, Map<String, Long> dates) {
        ContentValues values = new ContentValues(dates.size());
        for (Map.Entry<String, Long> date : dates.entrySet()) {
            if (date.getValue() > 0L) values.put(date.getKey(), date.getValue());
        }
        if (values.size() == 0) return 0;
        String selection = "_id = ? AND "
                + (oldLocal == null ? "localFile IS NULL" : "localFile = ?") + " AND "
                + (oldThumbnail == null ? "thumbnailFile IS NULL" : "thumbnailFile = ?");
        List<String> args = new ArrayList<>();
        args.add(rowId);
        if (oldLocal != null) args.add(oldLocal);
        if (oldThumbnail != null) args.add(oldThumbnail);
        try {
            return context.getContentResolver().update(CLOUD_URI, values, selection,
                    args.toArray(new String[0]));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private CleanupResult cleanupRelatedRow(Context context, Config config, String target,
            OriginalStore.Record record, String originalRowId, long relatedRowId, File original) {
        String relatedId = Long.toString(relatedRowId);
        if (relatedId.equals(originalRowId)) return CleanupResult.failed("related_row_is_original");
        if (!isCurrent(target)) return CleanupResult.failed("job_stopped");
        RelatedCloudRow row = readRelatedCloudRow(context, relatedId);
        if (row == null) return CleanupResult.failed("related_row_missing");
        if (row.serverId != null && !row.serverId.trim().isEmpty()) {
            return CleanupResult.failed("related_row_has_server_id");
        }
        if (row.localFlag != 7 && row.localFlag != 8) {
            return CleanupResult.failed("related_row_state_unsafe");
        }
        File candidate = safeDuplicateFile(row.localFile, row.thumbnailFile, record);
        if (candidate == null) return CleanupResult.failed("related_file_path_unsafe");
        VerifiedCopy verified = verifyDerivedCopy(row.localFile, record);
        if (verified == null) return CleanupResult.failed("related_file_not_registered");
        if (record.sha256.equalsIgnoreCase(verified.digest)) {
            return CleanupResult.failed("related_file_is_original");
        }
        String candidateSha1;
        try {
            candidateSha1 = sha1(candidate);
        } catch (Throwable failure) {
            return CleanupResult.failed("related_file_hash_failed");
        }
        if (row.sha1 == null || !row.sha1.equalsIgnoreCase(candidateSha1)) {
            return CleanupResult.failed("related_file_sha1_mismatch");
        }
        if (!onlyReferencedByRow(context, relatedId, row.localFile, candidate.getPath())) {
            return CleanupResult.failed("related_file_shared");
        }
        if (!isOriginalVerified(original, record)) {
            return CleanupResult.failed("original_recheck_failed");
        }
        if (!isCurrent(target)) return CleanupResult.failed("job_stopped");

        try {
            Class<?> cloudUtils = mLoader.loadClass("com.miui.gallery.provider.CloudUtils");
            Method deleteById = cloudUtils.getMethod("deleteById", Context.class, int.class,
                    int.class, long[].class);
            deleteById.invoke(null, context, 1, 0, new long[]{relatedRowId});
        } catch (Throwable failure) {
            if (candidate.exists()) return CleanupResult.failed("local_delete_api_failed");
        }

        if (!isOriginalVerified(original, record)) {
            return CleanupResult.failed("original_integrity_failed");
        }
        if (candidate.exists()) return CleanupResult.failed("duplicate_file_remains");
        return CleanupResult.cleaned();
    }

    private static RelatedCloudRow readRelatedCloudRow(Context context, String rowId) {
        try (Cursor cursor = context.getContentResolver().query(CLOUD_URI,
                new String[]{"_id", "localFile", "thumbnailFile", "sha1", "serverId", "localFlag"},
                "_id = ?", new String[]{rowId}, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            int idColumn = cursor.getColumnIndex("_id");
            int localColumn = cursor.getColumnIndex("localFile");
            int thumbnailColumn = cursor.getColumnIndex("thumbnailFile");
            int sha1Column = cursor.getColumnIndex("sha1");
            int serverIdColumn = cursor.getColumnIndex("serverId");
            int localFlagColumn = cursor.getColumnIndex("localFlag");
            if (idColumn < 0 || localColumn < 0 || thumbnailColumn < 0 || sha1Column < 0
                    || serverIdColumn < 0 || localFlagColumn < 0
                    || !rowId.equals(cursor.getString(idColumn))) return null;
            String local = cursor.isNull(localColumn) ? null : cursor.getString(localColumn);
            String thumbnail = cursor.isNull(thumbnailColumn)
                    ? null : cursor.getString(thumbnailColumn);
            String sha1 = cursor.isNull(sha1Column) ? null : cursor.getString(sha1Column);
            String serverId = cursor.isNull(serverIdColumn) ? null
                    : cursor.getString(serverIdColumn);
            int localFlag = cursor.getInt(localFlagColumn);
            if (cursor.moveToNext()) return null;
            return new RelatedCloudRow(local, thumbnail, sha1, serverId, localFlag);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static File safeDuplicateFile(String localPath, String thumbnailPath,
            OriginalStore.Record record) {
        if (localPath == null || localPath.trim().isEmpty()) return null;
        try {
            File root = new File(STORAGE_ROOT).getCanonicalFile();
            File candidate = new File(localPath).getCanonicalFile();
            String path = candidate.getPath();
            if (!candidate.isFile() || !path.startsWith(root.getPath() + File.separator)) {
                return null;
            }
            String lowerPath = path.toLowerCase(Locale.ROOT).replace('\\', '/');
            if (lowerPath.contains("/.gallerysync-slim/")) return null;
            if (sameCanonical(path, record.originalLocalPath)
                    || sameCanonical(path, record.previewLocalPath)) return null;
            if (thumbnailPath != null && !thumbnailPath.trim().isEmpty()
                    && !path.equals(canonicalGalleryPath(thumbnailPath))) return null;
            return candidate;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean onlyReferencedByRow(Context context, String expectedRowId,
            String registeredPath, String canonicalPath) {
        Set<String> queryPaths = new HashSet<>();
        queryPaths.add(registeredPath);
        queryPaths.add(canonicalPath);
        boolean foundExpected = false;
        for (String path : queryPaths) {
            try (Cursor cursor = context.getContentResolver().query(CLOUD_URI,
                    new String[]{"_id", "localFile", "thumbnailFile"},
                    "localFile = ? OR thumbnailFile = ?", new String[]{path, path}, null)) {
                if (cursor == null) return false;
                int idColumn = cursor.getColumnIndex("_id");
                int localColumn = cursor.getColumnIndex("localFile");
                int thumbnailColumn = cursor.getColumnIndex("thumbnailFile");
                if (idColumn < 0 || localColumn < 0 || thumbnailColumn < 0) return false;
                while (cursor.moveToNext()) {
                    String rowId = cursor.getString(idColumn);
                    String local = cursor.isNull(localColumn) ? null : cursor.getString(localColumn);
                    String thumbnail = cursor.isNull(thumbnailColumn)
                            ? null : cursor.getString(thumbnailColumn);
                    if (!pathMatchesCopy(local, canonicalPath)
                            && !pathMatchesCopy(thumbnail, canonicalPath)) continue;
                    if (!expectedRowId.equals(rowId)) return false;
                    foundExpected = true;
                }
            } catch (Throwable ignored) {
                return false;
            }
        }
        return foundExpected;
    }

    private static boolean isOriginalVerified(File original, OriginalStore.Record record) {
        try {
            return original.isFile() && original.length() == record.originalLength
                    && record.sha256.equalsIgnoreCase(sha256(original));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String sha1(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("job_stopped");
                digest.update(buffer, 0, read);
            }
        }
        char[] alphabet = "0123456789abcdef".toCharArray();
        StringBuilder hex = new StringBuilder(40);
        for (byte value : digest.digest()) {
            hex.append(alphabet[(value >>> 4) & 0x0f]);
            hex.append(alphabet[value & 0x0f]);
        }
        return hex.toString();
    }

    private static boolean isRegisteredPath(String rawPath, OriginalStore.Record record) {
        String path = canonicalGalleryPath(rawPath);
        if (path == null) return false;
        if (sameCanonical(path, record.originalLocalPath)
                || sameCanonical(path, record.previewLocalPath)) return true;
        for (String derived : record.derivedPaths) {
            if (sameCanonical(path, derived)) return true;
        }
        return false;
    }

    private static boolean sameCanonical(String first, String second) {
        if (second == null || second.isEmpty()) return false;
        String canonical = canonicalGalleryPath(second);
        // 外部存储大小写不敏感，pictures/ 与 Pictures/ 指向同一份文件，必须忽略大小写比较，
        // 否则同一张照片会被当成两个不同的路径（曾导致 unslim 把原图当派生副本而整项失败）。
        return canonical != null && canonical.equalsIgnoreCase(first);
    }

    private static String canonicalGalleryPath(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) return null;
        String path = rawPath.trim();
        if (!path.startsWith("/")) path = STORAGE_ROOT + "/" + path;
        try {
            return new File(path).getCanonicalPath();
        } catch (IOException ignored) {
            return null;
        }
    }

    private static File safeOriginalFile(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) return null;
        try {
            File original = new File(rawPath).getCanonicalFile();
            File root = new File(STORAGE_ROOT).getCanonicalFile();
            String rootPrefix = root.getPath() + File.separator;
            if (!original.isAbsolute() || !original.getPath().startsWith(rootPrefix)) return null;
            return new File(rawPath);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean hasRequiredSpace(long originalLength) {
        try {
            long available = new StatFs(STORAGE_ROOT).getAvailableBytes();
            long required = Math.addExact(originalLength, SPACE_RESERVE_BYTES);
            return available >= required;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isCurrent(String target) {
        if (Thread.currentThread().isInterrupted()) return false;
        synchronized (mLock) {
            Config current = mConfig;
            return !mClosed && current != null && current.isReady()
                    && target.equals(OriginalStore.targetKey(current));
        }
    }

    private static Request parseRequest(File file) throws Exception {
        if (!file.isFile()) throw new IOException("missing_request");
        File directory = file.getParentFile().getCanonicalFile();
        if (!file.getCanonicalPath().startsWith(directory.getPath() + File.separator)) {
            throw new IOException("invalid_request_path");
        }
        if (file.length() <= 0L || file.length() > MAX_REQUEST_BYTES) {
            throw new IOException("invalid_request_size");
        }
        byte[] bytes;
        try (FileInputStream input = new FileInputStream(file);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_REQUEST_BYTES) throw new IOException("invalid_request_size");
                output.write(buffer, 0, read);
            }
            bytes = output.toByteArray();
        }
        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        String jobId = root.getString("jobId");
        String target = root.getString("target");
        if (!jobId.matches("[A-Za-z0-9_.-]{1,128}")) {
            throw new IOException("invalid_job_id");
        }
        if (!target.matches("[0-9a-f]{64}")) throw new IOException("invalid_target");
        JSONArray array = root.getJSONArray("items");
        if (array.length() == 0 || array.length() > MAX_ITEMS) {
            throw new IOException("invalid_item_count");
        }
        List<String> tempFiles = new ArrayList<>();
        if (root.has("tempFiles") && !root.isNull("tempFiles")) {
            JSONArray temps = root.getJSONArray("tempFiles");
            if (temps.length() > MAX_TEMP_FILES) throw new IOException("too_many_temp_files");
            for (int i = 0; i < temps.length(); i++) {
                Object raw = temps.get(i);
                if (!(raw instanceof String)) throw new IOException("invalid_temp_path");
                String path = (String) raw;
                if (path.isEmpty() || path.length() > 4096 || !path.startsWith("/")) {
                    throw new IOException("invalid_temp_path");
                }
                tempFiles.add(path);
            }
        }
        List<RequestItem> items = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            java.util.Iterator<String> keys = item.keys();
            while (keys.hasNext()) {
                String key = keys.next().toLowerCase(Locale.ROOT);
                if (key.contains("path") || key.contains("destination")) {
                    throw new IOException("request_path_not_allowed");
                }
            }
            String mode = item.has("mode") && !item.isNull("mode")
                    ? item.getString("mode") : "restore";
            if (!"restore".equals(mode) && !"unslim".equals(mode)
                    && !"legacy_copies".equals(mode) && !"stale_rows".equals(mode)
                    && !"broken_payload".equals(mode) && !"orphan_rows".equals(mode)
                    && !"purge_items".equals(mode)) {
                throw new IOException("invalid_item_mode");
            }
            // broken_payload 只接受 row/sha1/length：它的证据是"内容可证明全零"，不借 base 行；
            // 大小写都查一遍，避免 {"baserow": ...} 这种写法绕过。
            if ("broken_payload".equals(mode)) {
                java.util.Iterator<String> baseKeys = item.keys();
                while (baseKeys.hasNext()) {
                    String key = baseKeys.next().toLowerCase(Locale.ROOT);
                    if ("baserow".equals(key) || "basesha1".equals(key)) {
                        throw new IOException("invalid_broken_payload_item");
                    }
                }
            }
            // purge_items 的判据全在"本行 + 磁盘上那份文件"上，没有见证行这回事：请求里出现
            // baseRow/baseSha1，说明它是按 legacy_copies/stale_rows 的形状造出来的，整单拒掉。
            if ("purge_items".equals(mode)) {
                java.util.Iterator<String> purgeKeys = item.keys();
                while (purgeKeys.hasNext()) {
                    String key = purgeKeys.next().toLowerCase(Locale.ROOT);
                    if ("baserow".equals(key) || "basesha1".equals(key)) {
                        throw new IOException("invalid_item_mode");
                    }
                }
            }
            String row = item.getString("row");
            String sha1 = item.getString("sha1");
            // legacy_copies 与 stale_rows 的条目没有 sha256；其余模式保持原来的取值顺序与异常行为。
            boolean optionalSha256 = "legacy_copies".equals(mode) || "stale_rows".equals(mode)
                    || "broken_payload".equals(mode) || "orphan_rows".equals(mode)
                    || "purge_items".equals(mode);
            String sha256;
            if (optionalSha256) {
                sha256 = item.has("sha256") && !item.isNull("sha256")
                        ? item.getString("sha256") : null;
            } else {
                sha256 = item.getString("sha256");
            }
            // stale_rows 用 baseRow / diskLength 证明这行可以删，不再要求重复上报文件长度；
            // broken_payload 必须给出 size，它就是"全零内容"证明的长度；
            // purge_items 必须给出 length：本地文件存在时要拿它逐字节核对；
            // orphan_rows 面对的行根本没有本地文件，没有长度可言，length 一律可选。
            Object rawLength = "stale_rows".equals(mode) || "broken_payload".equals(mode)
                    || "orphan_rows".equals(mode) || "purge_items".equals(mode)
                    ? (item.has("length") && !item.isNull("length") ? item.get("length") : null)
                    : item.get("length");
            if (rawLength == null && ("broken_payload".equals(mode) || "purge_items".equals(mode))) {
                throw new IOException("invalid_item_length");
            }
            boolean sha256Valid = optionalSha256
                    ? sha256 == null || sha256.matches("(?i)[0-9a-f]{64}")
                    : sha256 != null && sha256.matches("(?i)[0-9a-f]{64}");
            boolean lengthValid = rawLength == null
                    || rawLength instanceof Integer || rawLength instanceof Long;
            if (row.isEmpty() || row.length() > 128 || !sha1.matches("(?i)[0-9a-f]{40}")
                    || !sha256Valid || !lengthValid) {
                throw new IOException("invalid_item_metadata");
            }
            long length = 0L;
            if (rawLength != null) {
                length = ((Number) rawLength).longValue();
                if (length <= 0L) throw new IOException("invalid_item_length");
            }
            String baseRow = null;
            String baseSha1 = null;
            if ("legacy_copies".equals(mode) || "stale_rows".equals(mode)) {
                if (item.has("baseRow") && !item.isNull("baseRow")) {
                    Object rawBaseRow = item.get("baseRow");
                    if (!(rawBaseRow instanceof String)) throw new IOException("invalid_base_row");
                    baseRow = (String) rawBaseRow;
                    if (!baseRow.matches("0*[1-9][0-9]{0,18}")) {
                        throw new IOException("invalid_base_row");
                    }
                }
                if (item.has("baseSha1") && !item.isNull("baseSha1")) {
                    Object rawBaseSha1 = item.get("baseSha1");
                    if (!(rawBaseSha1 instanceof String)) throw new IOException("invalid_base_sha1");
                    baseSha1 = (String) rawBaseSha1;
                    if (!baseSha1.matches("(?i)[0-9a-f]{40}")) {
                        throw new IOException("invalid_base_sha1");
                    }
                }
                if ("legacy_copies".equals(mode) && (baseRow == null) != (baseSha1 == null)) {
                    throw new IOException("invalid_base_row");
                }
            }
            long diskLength = 0L;
            if ("stale_rows".equals(mode)) {
                if (item.has("diskLength") && !item.isNull("diskLength")) {
                    Object rawDiskLength = item.get("diskLength");
                    if (!(rawDiskLength instanceof Integer) && !(rawDiskLength instanceof Long)) {
                        throw new IOException("invalid_item_mode");
                    }
                    diskLength = ((Number) rawDiskLength).longValue();
                    if (diskLength <= 0L) throw new IOException("invalid_item_mode");
                }
                // 第 1 类给 baseRow，第 2/3 类给 diskLength；两者必须恰好给出一个。
                if ((baseRow == null) == (diskLength <= 0L)) {
                    throw new IOException("invalid_item_mode");
                }
            }
            List<Long> relatedRows = new ArrayList<>();
            if (item.has("relatedRows")) {
                JSONArray related = item.getJSONArray("relatedRows");
                if (related.length() > MAX_RELATED_ROWS) {
                    throw new IOException("too_many_related_rows");
                }
                Set<Long> uniqueRows = new HashSet<>();
                for (int relatedIndex = 0; relatedIndex < related.length(); relatedIndex++) {
                    Object rawRow = related.get(relatedIndex);
                    if (!(rawRow instanceof String)) {
                        throw new IOException("invalid_related_row");
                    }
                    String relatedRow = (String) rawRow;
                    if (!relatedRow.matches("[0-9]{1,19}")) {
                        throw new IOException("invalid_related_row");
                    }
                    long relatedId;
                    try {
                        relatedId = Long.parseLong(relatedRow);
                    } catch (NumberFormatException invalidId) {
                        throw new IOException("invalid_related_row");
                    }
                    if (relatedId <= 0L || !Long.toString(relatedId).equals(relatedRow)
                            || relatedRow.equals(row) || !uniqueRows.add(relatedId)) {
                        throw new IOException("invalid_related_row");
                    }
                    relatedRows.add(relatedId);
                }
            }
            items.add(new RequestItem(row, sha1.toLowerCase(Locale.ROOT),
                    sha256 == null ? "" : sha256.toLowerCase(Locale.ROOT), length, relatedRows,
                    mode, baseRow, baseSha1 == null ? null : baseSha1.toLowerCase(Locale.ROOT),
                    diskLength));
        }
        return new Request(jobId, target.toLowerCase(Locale.ROOT), items, tempFiles);
    }

    private static void addRemainingTemp(List<ItemResult> results, List<String> tempFiles,
            int start, String status, String reason) {
        for (int i = start; i < tempFiles.size(); i++) {
            results.add(new ItemResult("temp", status, reason));
        }
    }

    private static void addRemaining(List<ItemResult> results, List<RequestItem> items,
            int start, String status, String reason) {
        for (int i = start; i < items.size(); i++) {
            results.add(new ItemResult(items.get(i).row, status, reason));
        }
    }

    private static boolean hasFailures(List<ItemResult> results) {
        for (ItemResult result : results) {
            if (!"success".equals(result.status)) return true;
        }
        return false;
    }

    private static String safeReason(Throwable failure) {
        if (failure instanceof IOException) {
            String message = failure.getMessage();
            if (message != null && message.matches("[a-z_]+")) return message;
        }
        return "internal_error";
    }

    private static boolean writeResult(File resultFile, String jobId, String target,
            String status, String reason, List<ItemResult> results) {
        try {
            JSONObject root = new JSONObject();
            root.put("jobId", jobId == null ? "" : jobId);
            root.put("target", target == null ? "" : target);
            root.put("status", status);
            if (reason != null && !reason.isEmpty()) root.put("reason", reason);
            JSONArray items = new JSONArray();
            int repairedCopies = 0;
            int repairedRows = 0;
            int cleanedDuplicates = 0;
            for (ItemResult result : results) {
                JSONObject item = new JSONObject();
                item.put("row", result.row);
                item.put("status", result.status);
                if (!result.reason.isEmpty()) item.put("reason", result.reason);
                if (result.repairedCopies > 0) {
                    item.put("repairedCopies", result.repairedCopies);
                }
                if (result.repairedRows > 0) {
                    item.put("repairedRows", result.repairedRows);
                }
                repairedCopies += result.repairedCopies;
                repairedRows += result.repairedRows;
                cleanedDuplicates += result.cleanedDuplicates;
                if (result.cleanedDuplicates > 0) item.put("cleanedDuplicates", result.cleanedDuplicates);
                items.put(item);
            }
            root.put("items", items);
            if (repairedCopies > 0) root.put("repairedCopies", repairedCopies);
            if (repairedRows > 0) root.put("repairedRows", repairedRows);
            if (cleanedDuplicates > 0) root.put("cleanedDuplicates", cleanedDuplicates);
            File temporary = new File(resultFile.getParentFile(), resultFile.getName() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write(root.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), resultFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailure) {
                Files.move(temporary.toPath(), resultFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            // 读取方按完整结果推进，落盘前必须确认这份结果真的可见。
            return resultFile.isFile() && resultFile.length() > 0L;
        } catch (Throwable failure) {
            Logger.e("event=metadata_restore_result_write_failed status=io_error");
            return false;
        }
    }

    private static void logRowStatus(String row, String status) {
        Logger.i("event=metadata_restore_item row=" + row + " status=" + status);
    }

    private static void logStatus(String status) {
        Logger.i("event=metadata_restore_job_finished status=" + status);
    }

    private static final class Request {
        final String jobId;
        final String target;
        final List<RequestItem> items;
        final List<String> tempFiles;

        Request(String jobId, String target, List<RequestItem> items, List<String> tempFiles) {
            this.jobId = jobId;
            this.target = target;
            this.items = items;
            this.tempFiles = tempFiles;
        }
    }

    private static final class RequestItem {
        final String row;
        final String sha1;
        final String sha256;
        final long length;
        final List<Long> relatedRows;
        final String mode;
        final String baseRow;
        final String baseSha1;
        /** stale_rows 第 2/3 类的磁盘实际字节数；未提供时为 0。 */
        final long diskLength;

        RequestItem(String row, String sha1, String sha256, long length, List<Long> relatedRows,
                String mode, String baseRow, String baseSha1) {
            this(row, sha1, sha256, length, relatedRows, mode, baseRow, baseSha1, 0L);
        }

        RequestItem(String row, String sha1, String sha256, long length, List<Long> relatedRows,
                String mode, String baseRow, String baseSha1, long diskLength) {
            this.row = row;
            this.sha1 = sha1;
            this.sha256 = sha256;
            this.length = length;
            this.relatedRows = relatedRows;
            this.mode = mode;
            this.baseRow = baseRow;
            this.baseSha1 = baseSha1;
            this.diskLength = diskLength;
        }
    }

    /** unslim 模式在条目内的累计计数。 */
    private static final class UnslimState {
        int clearedRows;
        int deletedCopies;
    }

    /** 一个候选预览路径的行清理结果；reason 非空表示失败。 */
    private static final class UnslimClear {
        final int rows;
        final String reason;

        private UnslimClear(int rows, String reason) {
            this.rows = rows;
            this.reason = reason;
        }

        static UnslimClear cleared(int rows) { return new UnslimClear(rows, ""); }
        static UnslimClear failed(String reason) { return new UnslimClear(0, reason); }
    }

    private static final class ItemResult {
        final String row;
        final String status;
        final String reason;
        final int repairedCopies;
        final int repairedRows;
        final int cleanedDuplicates;

        ItemResult(String row, String status, String reason) {
            this(row, status, reason, 0, 0);
        }

        ItemResult(String row, String status, String reason, int repairedCopies, int repairedRows) {
            this(row, status, reason, repairedCopies, repairedRows, 0);
        }

        ItemResult(String row, String status, String reason, int repairedCopies, int repairedRows,
                int cleanedDuplicates) {
            this.row = row;
            this.status = status;
            this.reason = reason == null ? "" : reason;
            this.repairedCopies = repairedCopies;
            this.repairedRows = repairedRows;
            this.cleanedDuplicates = cleanedDuplicates;
        }
    }

    private static final class CloudRow {
        final String localFile;
        final boolean localFileWasNull;
        final String sha1;
        final Map<String, Long> dates;

        CloudRow(String sha1, String localFile, boolean localFileWasNull,
                Map<String, Long> dates) {
            this.sha1 = sha1;
            this.localFile = localFile;
            this.localFileWasNull = localFileWasNull;
            this.dates = dates;
        }
    }

    private static final class VerifiedCopy {
        final String registeredPath;
        final File file;
        final String digest;

        VerifiedCopy(String registeredPath, File file, String digest) {
            this.registeredPath = registeredPath;
            this.file = file;
            this.digest = digest;
        }
    }

    /** stale_rows 判定要看的行快照；只保留判定需要的列。 */
    static final class StaleRowSnapshot {
        final String rowId;
        final String localFile;
        final String thumbnailFile;
        final String sha1;
        final String serverId;
        final int localFlag;
        final long size;
        final long realSize;
        final long dateTaken;

        StaleRowSnapshot(String rowId, String localFile, String thumbnailFile, String sha1,
                String serverId, int localFlag, long size, long realSize, long dateTaken) {
            this.rowId = rowId;
            this.localFile = localFile;
            this.thumbnailFile = thumbnailFile;
            this.sha1 = sha1;
            this.serverId = serverId;
            this.localFlag = localFlag;
            this.size = size;
            this.realSize = realSize;
            this.dateTaken = dateTaken;
        }
    }

    /** orphan_rows 判定要看的行快照：只要证明"这行没有文件"的那些列。 */
    static final class OrphanRowSnapshot {
        final String rowId;
        final String localFile;
        final String thumbnailFile;
        final String sha1;
        final String serverId;
        final int localFlag;
        final String fileName;

        OrphanRowSnapshot(String rowId, String localFile, String thumbnailFile, String sha1,
                String serverId, int localFlag, String fileName) {
            this.rowId = rowId;
            this.localFile = localFile;
            this.thumbnailFile = thumbnailFile;
            this.sha1 = sha1;
            this.serverId = serverId;
            this.localFlag = localFlag;
            this.fileName = fileName;
        }
    }

    /** stale_rows 判定的输入事实：由 Android 侧采集，判定只读这些字段。 */
    static final class StaleRowFacts {
        String rowId = "";
        boolean rowExists;
        boolean sha1Matches;
        boolean hasServerId;
        int localFlag;
        /** localFile 非空且能规范化到共享存储根目录内。 */
        boolean localFileResolved;
        /** localFile 位于 .gallerysync-slim/ 目录内（第 1 类的判定条件）。 */
        boolean slimDirectory;
        /** 引用行查询失败；失败时按"文件仍被共享"保守处理。 */
        boolean referenceQueryFailed;
        List<String> referencingRowIds = new ArrayList<>();
        boolean onlyRowForSha1;
        boolean classOne;
        boolean baseRowValid;
        boolean classTwo;
        boolean diskLengthPositive;
        boolean twinFound;
        String twinRowId;
        boolean rowSizeStale;
        boolean diskLengthMatchesFile;
        /** 第 3 类：同 sha1 + 同路径（忽略大小写）的另一行也登记着这份文件，本行是重复登记。 */
        boolean classThree;
        /** 本行的 localFile 是盘上一个真实存在、非目录的文件。 */
        boolean duplicateFilePresent;
        /** 本行记录的 size 等于请求里的 diskLength（也等于 length）。 */
        boolean rowSizeMatchesDisk;
        /** 找到同 sha1 + 同路径的另一行（要保留的那一行）。 */
        boolean survivorFound;
        String survivorRowId;
        /** 保留行的 id 不比本行小：本行才是最老的登记，不能删。 */
        boolean survivorNewer;
        /** 保留行的 sha1 非空且与请求一致。 */
        boolean survivorSha1Matches;
        /** 保留行的 localFlag 属于第 3 类允许的本地状态（0/7/8）。 */
        boolean survivorStateSafe;
        /** 保留行记录的 size 等于磁盘上这份文件的字节数。 */
        boolean survivorSizeMatchesFile;
    }

    /** stale_rows 的纯逻辑判定：不依赖 Android、不碰磁盘，可直接单测。 */
    static final class StaleRows {
        /** 只在本机、尚未上传的行才允许删除。 */
        static final int FLAG_LOCAL_ONLY = 7;
        static final int FLAG_LOCAL_ONLY_SYNCED = 8;
        /**
         * Gallery 自己建的本地稳定行：第 3 类里它是同一份文件最老的那条登记，和 7/8 一样属于
         * "本地有、远端没有"的合法状态。
         */
        static final int FLAG_LOCAL_STABLE = 0;

        private StaleRows() {
        }

        /**
         * 第 3 类允许的行状态：0（Gallery 自建）与 7/8（本模块建的）都表示"这份文件只在本机"。
         * 这条放宽只对重复登记行生效，第 1/2 类仍然只认 7/8。
         */
        static boolean isDuplicateLocalFlag(int flag) {
            return flag == FLAG_LOCAL_STABLE || flag == FLAG_LOCAL_ONLY
                    || flag == FLAG_LOCAL_ONLY_SYNCED;
        }

        /**
         * 两个 localFile 是否指向同一份文件：外部存储大小写不敏感，
         * {@code /storage/emulated/0/pictures/…} 与 {@code /Pictures/…} 是同一路径。
         * 纯字符串判定，不访问磁盘；调用方传进来的应当是已经规范化过的路径。
         */
        static boolean isSameDuplicatePath(String first, String second) {
            if (first == null || second == null) return false;
            String left = first.trim().replace('\\', '/');
            String right = second.trim().replace('\\', '/');
            if (left.isEmpty() || right.isEmpty()) return false;
            return left.equalsIgnoreCase(right);
        }

        /** 路径是否位于 .gallerysync-slim/ 目录内（纯字符串判定，不访问磁盘）。 */
        static boolean isSlimDirectoryPath(String rawPath) {
            if (rawPath == null) return false;
            String path = rawPath.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (path.isEmpty()) return false;
            return path.contains("/.gallerysync-slim/") || path.endsWith("/.gallerysync-slim");
        }

        /**
         * 文件是否仍被本行以外的行引用。第 2 类里提供 diskLength 证据的那一行（twin）是这条
         * 重复登记行的另一半，不算"其它引用"；除它以外任何一行命中都算共享。
         */
        static boolean sharesFileWithOtherRow(List<String> referencingRowIds, String rowId,
                String twinRowId) {
            if (referencingRowIds == null) return true;
            for (String id : referencingRowIds) {
                if (id == null || id.equals(rowId)) continue;
                if (twinRowId != null && id.equals(twinRowId)) continue;
                return true;
            }
            return false;
        }

        /** 空串表示这条行可以删；否则返回拒绝理由。 */
        static String decision(StaleRowFacts facts) {
            if (!facts.rowExists) return "stale_row_missing";
            if (!facts.sha1Matches) return "stale_row_sha1_mismatch";
            if (facts.hasServerId) return "stale_row_has_server_id";
            // 第 3 类走自己的判定：它单独放宽了本地状态（0 也算合法行），其余条件更严；
            // 第 1/2 类的判定顺序与理由一个字都不动。
            if (facts.classThree) return duplicateDecision(facts);
            if (facts.localFlag != FLAG_LOCAL_ONLY && facts.localFlag != FLAG_LOCAL_ONLY_SYNCED) {
                return "stale_row_state_unsafe";
            }
            if (!facts.localFileResolved) return "stale_row_state_unsafe";
            String twin = facts.classTwo && facts.twinFound ? facts.twinRowId : null;
            if (facts.referenceQueryFailed
                    || sharesFileWithOtherRow(facts.referencingRowIds, facts.rowId, twin)) {
                return "stale_row_file_shared";
            }
            if (facts.onlyRowForSha1) return "stale_row_would_lose_file";
            if (facts.classOne == facts.classTwo) return "invalid_item_mode";
            if (facts.classOne && !facts.slimDirectory) return "invalid_item_mode";
            if (facts.classOne && !facts.baseRowValid) return "invalid_base_row";
            if (facts.classTwo && !facts.diskLengthPositive) return "invalid_item_mode";
            if (facts.classTwo && (!facts.twinFound || !facts.rowSizeStale
                    || !facts.diskLengthMatchesFile)) {
                return "stale_row_length_mismatch";
            }
            return "";
        }

        /**
         * 第 3 类（同一份文件的重复登记行）的判定：删掉其中较新的那一条，保留 id 最小的那条登记。
         *
         * <p>两行的状态都放宽到 0/7/8：真机上 201 组重复行里有 154 组的最老那行是 Gallery 自己
         * 建的 {@code localFlag = 0}，本模块补登记的新行才是 7；它们指向同一份文件，保留行留着文件，
         * 所以删掉新的那行不会丢任何数据。其它模式仍只认 7/8。
         */
        private static String duplicateDecision(StaleRowFacts facts) {
            if (!isDuplicateLocalFlag(facts.localFlag)) {
                return "duplicate_unsafe_state";
            }
            if (!facts.localFileResolved || !facts.duplicateFilePresent) {
                return "duplicate_missing";
            }
            if (!facts.survivorFound || facts.survivorRowId == null) {
                return "duplicate_no_survivor";
            }
            // 只留最老的登记：保留行的 id 必须比本行小，否则本行才是该留下的那一条。
            if (facts.survivorNewer) return "duplicate_survivor_newer";
            if (!facts.survivorSha1Matches) return "duplicate_sha1_mismatch";
            if (!facts.survivorStateSafe) return "duplicate_unsafe_state";
            if (!facts.rowSizeMatchesDisk || !facts.diskLengthMatchesFile
                    || !facts.survivorSizeMatchesFile) {
                return "duplicate_length_mismatch";
            }
            if (facts.onlyRowForSha1) return "stale_row_would_lose_file";
            // 本行与保留行之外还有人引用这份文件（包括只通过 thumbnailFile 引用）就不能删。
            if (facts.referenceQueryFailed || sharesFileWithOtherRow(facts.referencingRowIds,
                    facts.rowId, facts.survivorRowId)) {
                return "duplicate_file_shared";
            }
            return "";
        }
    }

    /** broken_payload 判定的输入事实：由 Android 侧采集，判定只读这些字段。 */
    public static final class BrokenPayloadFacts {
        public String rowId = "";
        public boolean rowExists;
        /** 本行记录的 sha1 与请求一致。 */
        public boolean sha1Matches;
        public boolean hasServerId;
        public int localFlag;
        /** localFile 非空且能规范化到共享存储根目录内。 */
        public boolean localFileResolved;
        /** localFile 是一个存在的普通文件。 */
        public boolean filePresent;
        /** 请求长度、本行 size、磁盘字节数三者一致。 */
        public boolean fileLengthMatches;
        /** 磁盘那份文件的实际 sha1（流式计算）与请求一致。 */
        public boolean fileSha1Matches;
        /** 请求的 sha1 正是 size 个 0 字节的 SHA1：内容可证明全零。 */
        public boolean payloadAllZero;
        /** 引用行查询失败；失败时按"文件仍被共享"保守处理。 */
        public boolean referenceQueryFailed;
        public List<String> referencingRowIds = new ArrayList<>();
    }

    /** broken_payload 的纯逻辑（零字节 sha1 与删除判定），不依赖 Android、不碰磁盘，可直接单测。 */
    public static final class BrokenPayload {
        /** 只在本机、没有远端对应物的三种 localFlag。 */
        public static final int FLAG_LOCAL_ONLY = 7;
        public static final int FLAG_LOCAL_ONLY_SYNCED = 8;
        public static final int FLAG_LOCAL_ONLY_PENDING = 2;
        /** 用一段空 buffer 分块喂 MessageDigest，length 再大也不额外占内存。 */
        private static final int ZERO_CHUNK_BYTES = 64 * 1024;

        private BrokenPayload() {
        }

        /**
         * {@code length} 个 0 字节的 SHA1（十六进制小写）；{@code length <= 0} 返回 null。
         *
         * <p>必须现算而不是查表/写常量：这个值就是"内容可证明全零"的唯一依据，写死的常量一旦
         * 与长度对不上，这个模式就会把真实照片当成坏数据删掉。
         */
        public static String zeroSha1(long length) {
            if (length <= 0L) return null;
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                byte[] zeros = new byte[ZERO_CHUNK_BYTES];
                long remaining = length;
                while (remaining > 0L) {
                    int chunk = (int) Math.min((long) zeros.length, remaining);
                    digest.update(zeros, 0, chunk);
                    remaining -= chunk;
                }
                char[] alphabet = "0123456789abcdef".toCharArray();
                StringBuilder hex = new StringBuilder(40);
                for (byte value : digest.digest()) {
                    hex.append(alphabet[(value >>> 4) & 0x0f]);
                    hex.append(alphabet[value & 0x0f]);
                }
                return hex.toString();
            } catch (NoSuchAlgorithmException unavailable) {
                return null;
            }
        }

        /** 该 sha1 是否就是 {@code length} 个 0 字节的 sha1（大小写不敏感）。 */
        public static boolean isZeroPayloadSha1(String sha1, long length) {
            String expected = zeroSha1(length);
            return expected != null && sha1 != null && expected.equalsIgnoreCase(sha1);
        }

        /** 本行之外还有行引用同一路径；引用列表里必然包含本行，必须排除。 */
        public static boolean hasOtherReferences(List<String> referencingRowIds, String rowId) {
            if (referencingRowIds == null) return true;
            for (String id : referencingRowIds) {
                if (id == null || id.equals(rowId)) continue;
                return true;
            }
            return false;
        }

        /** 空串表示这份全零副本可以删；否则返回拒绝理由。 */
        public static String decision(BrokenPayloadFacts facts) {
            if (facts == null || !facts.rowExists) return "broken_payload_missing";
            if (!facts.sha1Matches) return "broken_payload_sha1_mismatch";
            if (facts.hasServerId) return "broken_payload_has_server_id";
            if (facts.localFlag != FLAG_LOCAL_ONLY && facts.localFlag != FLAG_LOCAL_ONLY_SYNCED
                    && facts.localFlag != FLAG_LOCAL_ONLY_PENDING) {
                return "broken_payload_state_unsafe";
            }
            if (!facts.localFileResolved || !facts.filePresent) {
                return "broken_payload_file_missing";
            }
            if (!facts.fileLengthMatches) return "broken_payload_length_mismatch";
            // 先证明"磁盘内容 == 请求的 sha1"，再证明"请求的 sha1 == 全零 sha1"：
            // 两条都成立才说明这份文件确实是全零；sha1 对得上但内容不是零 → 真照片，拒绝。
            if (!facts.fileSha1Matches) return "broken_payload_file_sha1_mismatch";
            if (!facts.payloadAllZero) return "payload_not_broken";
            if (facts.referenceQueryFailed
                    || hasOtherReferences(facts.referencingRowIds, facts.rowId)) {
                return "broken_payload_file_shared";
            }
            return "";
        }
    }

    /** purge_items 判定里的一条"同路径的其它行"：行 id 与它自己登记的 sha1。 */
    public static final class PurgeReference {
        public final String rowId;
        public final String sha1;

        public PurgeReference(String rowId, String sha1) {
            this.rowId = rowId;
            this.sha1 = sha1;
        }
    }

    /** purge_items 判定的输入事实：由 Android 侧采集，判定只读这些字段。 */
    public static final class PurgeItemFacts {
        public String rowId = "";
        /** 请求里要求的 sha1（已小写）：行、磁盘文件、同路径的其它行都拿它比。 */
        public String requestSha1 = "";
        /** 请求里要求的本地文件字节数。 */
        public long requestedLength;
        public boolean rowExists;
        /** 本行记录的 sha1 非空且与请求一致。 */
        public boolean sha1Matches;
        public boolean hasServerId;
        public int localFlag;
        /** localFile 为 NULL 或空白：这一行没有本地文件，跳过文件长度与 sha1 校验。 */
        public boolean localFileBlank;
        /** localFile 非空且能规范化到共享存储根目录内。 */
        public boolean localFileResolved;
        /** localFile 是一个存在的普通文件。 */
        public boolean filePresent;
        /** 磁盘上这份文件的字节数等于请求的 length（只有文件存在时才成立）。 */
        public boolean lengthMatches;
        /** 磁盘这份文件的实际 sha1（流式计算）与请求一致。 */
        public boolean fileSha1Matches;
        /** 引用行查询失败；失败时按"文件被别的照片共享"保守处理。 */
        public boolean referenceQueryFailed;
        /** 与本行共享 localFile/thumbnailFile 的所有行（含本行自身）。 */
        public List<PurgeReference> references = new ArrayList<>();
    }

    /**
     * purge_items 的纯逻辑判定：不依赖 Android、不碰磁盘，可直接单测。
     *
     * <p>拒绝理由集中在这里（也就是结果里上报的 reason），全部是"不删"的理由：
     * <ul>
     *   <li>{@code invalid_purge_item} 条目本身不自洽：localFile 有值但规范化不到存储根目录内，
     *       或本地文件存在却没给出正的长度 —— 都没有可核对的证据；</li>
     *   <li>{@code purge_row_missing} 行已经不在了；</li>
     *   <li>{@code purge_sha1_mismatch} 行记录的 sha1 与请求不一致；</li>
     *   <li>{@code purge_has_server_id} 行有 serverId：远端还有对象，删了本地会触发重新下载；</li>
     *   <li>{@code purge_state_unsafe} localFlag 不是 0/7/8，且不是"文件已不存在的回收站行"；</li>
     *   <li>{@code purge_file_shared} 同路径的其它行里存在 sha1 不同的行，或查询失败；</li>
     *   <li>{@code purge_length_mismatch} 本地文件长度与请求不一致；</li>
     *   <li>{@code purge_file_sha1_mismatch} 本地文件的实际 sha1 与请求不一致；</li>
     *   <li>{@code purge_delete_failed} 删除通道抛异常或回读仍能看到行（由执行侧产生）；</li>
     *   <li>{@code purge_file_delete_failed} 行已删、文件删不掉（由执行侧产生，报 partial）。</li>
     * </ul>
     */
    public static final class PurgeItems {
        /** 只在本机、没有远端对应物的三种 localFlag（与 stale_rows 第 3 类同宽）。 */
        public static final int FLAG_LOCAL_STABLE = 0;
        public static final int FLAG_LOCAL_ONLY = 7;
        public static final int FLAG_LOCAL_ONLY_SYNCED = 8;
        /** 已在相册回收站里的行。只有它引用的文件已经不存在时才允许连行删掉。 */
        public static final int FLAG_LOCAL_TRASHED = 2;

        private PurgeItems() {
        }

        public static boolean isAllowedLocalFlag(int flag) {
            return flag == FLAG_LOCAL_STABLE || flag == FLAG_LOCAL_ONLY
                    || flag == FLAG_LOCAL_ONLY_SYNCED;
        }

        /**
         * 是否有别的行用<b>不同 sha1</b> 登记着同一份文件。
         *
         * <p>同路径 + 同 sha1 是同一张照片的多次登记（真机上"补齐缺失原图"之后宿主又新建一行，
         * 两行 localFile/sha1/size 完全相同），删其中一行不影响另一行；同路径但 sha1 不同说明这份
         * 文件被另一张照片当封面用（视频 + 封面），删掉就是损坏别人的数据。{@code null} 列表
         * 按"被共享"处理（fail closed）。
         */
        public static boolean hasConflictingReference(List<PurgeReference> references, String rowId,
                String sha1) {
            if (references == null) return true;
            for (PurgeReference reference : references) {
                if (reference == null || reference.rowId == null
                        || reference.rowId.equals(rowId)) {
                    continue;
                }
                if (reference.sha1 == null || sha1 == null
                        || !reference.sha1.equalsIgnoreCase(sha1)) {
                    return true;
                }
            }
            return false;
        }

        /** 空串表示这一行可以按请求精确删除；否则返回拒绝理由。 */
        public static String decision(PurgeItemFacts facts) {
            if (facts == null || !facts.rowExists) return "purge_row_missing";
            if (!facts.sha1Matches) return "purge_sha1_mismatch";
            if (facts.hasServerId) return "purge_has_server_id";
            if (!isAllowedLocalFlag(facts.localFlag)) {
                // 回收站行（localFlag=2）只在文件已经不在了才顺手删：文件还在就是相册自己的
                // 回收站条目，删行会连"从回收站恢复"的机会一起丢掉。
                boolean trashedWithoutFile = facts.localFlag == FLAG_LOCAL_TRASHED && !facts.filePresent;
                if (!trashedWithoutFile) return "purge_state_unsafe";
            }
            // localFile 有值却规范化不到存储根目录内：既核不了文件，也删不了文件，绝不删行。
            if (!facts.localFileBlank && !facts.localFileResolved) return "invalid_purge_item";
            if (facts.referenceQueryFailed
                    || hasConflictingReference(facts.references, facts.rowId,
                    facts.requestSha1)) {
                return "purge_file_shared";
            }
            // 本地文件不存在：只删行（规格 7），长度/sha1 一律跳过。
            if (!facts.filePresent) return "";
            if (facts.requestedLength <= 0L) return "invalid_purge_item";
            if (!facts.lengthMatches) return "purge_length_mismatch";
            if (!facts.fileSha1Matches) return "purge_file_sha1_mismatch";
            return "";
        }
    }

    /** orphan_rows 判定的输入事实：由 Android 侧采集，判定只读这些字段。 */
    static final class OrphanRowFacts {
        String rowId = "";
        /** 请求的那一行仍然存在。 */
        boolean rowExists;
        /** 本行的 sha1 非空且与请求一致（大小写不敏感）。 */
        boolean sha1Matches;
        /** localFile 为 NULL 或空白。 */
        boolean localFileBlank;
        /** thumbnailFile 为 NULL 或空白。 */
        boolean thumbnailFileBlank;
        boolean hasServerId;
        int localFlag;
        /** fileName 为 NULL 或空白。 */
        boolean fileNameBlank;
        /**
         * 与本行同 sha1、且仍能产出内容的其它行数（不含本行）：对方有非空 localFile 或非空
         * serverId 才计数。同为无文件残骸的行不算共享。-1 表示查询失败，按"仍被共享"拒删。
         */
        long materialisableSha1Rows = -1L;
        /** 注册表里已经登记了这一行（按 row/sha1 匹配）。 */
        boolean registryRegistered;
    }

    /** orphan_rows 的纯逻辑判定：不依赖 Android、不碰磁盘，可直接单测。 */
    static final class OrphanRows {
        /** 只在本机、没有远端对应物的三种 localFlag（与 stale_rows 第 3 类同宽）。 */
        static final int FLAG_LOCAL_STABLE = 0;
        static final int FLAG_LOCAL_ONLY = 7;
        static final int FLAG_LOCAL_ONLY_SYNCED = 8;

        private OrphanRows() {
        }

        static boolean isAllowedLocalFlag(int flag) {
            return flag == FLAG_LOCAL_STABLE || flag == FLAG_LOCAL_ONLY
                    || flag == FLAG_LOCAL_ONLY_SYNCED;
        }

        /**
         * 空串表示这条"无文件云行"可以删；否则返回拒绝理由（也就是结果里上报的 reason）。
         *
         * <p>请求里的 sha1 为空/空白/不是 40 位十六进制时根本走不到这里：{@code parseRequest}
         * 直接以 {@code invalid_item_metadata} 拒掉整份请求。这里的判定只证明"删掉这一行不会
         * 丢掉任何本地文件、也不会让别的行失去引用"。
         */
        static String decision(OrphanRowFacts facts) {
            if (facts == null || !facts.rowExists) return "orphan_row_missing";
            if (!facts.sha1Matches) return "orphan_row_sha1_mismatch";
            // 只要还有任何一个本地路径字段有值，这就不是"无文件行"，一律不碰。
            if (!facts.localFileBlank || !facts.thumbnailFileBlank) {
                return "orphan_row_has_local_file";
            }
            if (facts.hasServerId) return "orphan_row_has_server_id";
            if (!isAllowedLocalFlag(facts.localFlag)) return "orphan_row_state_unsafe";
            if (facts.fileNameBlank) return "orphan_row_no_filename";
            // 只有"同 sha1 且还能产出内容的其它行"才算共享；同为无文件残骸的行不算。
            // 查询失败(-1)按"仍被共享"保守处理：读不到就绝不删。
            if (facts.materialisableSha1Rows < 0L || facts.materialisableSha1Rows > 0L) {
                return "orphan_row_shared";
            }
            if (facts.registryRegistered) return "orphan_row_registered";
            return "";
        }
    }

    private static final class RelatedCloudRow {
        final String localFile, thumbnailFile, sha1, serverId;
        final int localFlag;
        RelatedCloudRow(String localFile, String thumbnailFile, String sha1, String serverId,
                int localFlag) {
            this.localFile = localFile;
            this.thumbnailFile = thumbnailFile;
            this.sha1 = sha1;
            this.serverId = serverId;
            this.localFlag = localFlag;
        }
    }

    private static final class CleanupResult {
        final boolean cleaned;
        final String reason;
        private CleanupResult(boolean cleaned, String reason) {
            this.cleaned = cleaned;
            this.reason = reason;
        }
        static CleanupResult cleaned() { return new CleanupResult(true, ""); }
        static CleanupResult failed(String reason) { return new CleanupResult(false, reason); }
    }

    private static final class RepairCounts {
        static final RepairCounts EMPTY = new RepairCounts(0, 0);
        final int copies;
        final int rows;

        RepairCounts(int copies, int rows) {
            this.copies = copies;
            this.rows = rows;
        }
    }
}
