package com.tongluosao.gallerysync.hook;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;

import com.tongluosao.gallerysync.core.Config;
import com.tongluosao.gallerysync.core.Logger;
import com.tongluosao.gallerysync.core.OriginalStore;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Executes one explicitly requested batch of metadata-original restores in Gallery's process. */
final class MetadataRestoreJob {
    private static final String REQUEST_NAME = "metadata-restore-request.json";
    private static final String RESULT_NAME = "metadata-restore-result.json";
    private static final String DIRECTORY_NAME = "webdav_sync";
    private static final String STORAGE_ROOT = "/storage/emulated/0";
    private static final long MAX_REQUEST_BYTES = 1024L * 1024L;
    private static final long SPACE_RESERVE_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_ITEMS = 5000;
    private static final int MAX_RELATED_ROWS = 64;
    private static final Uri CLOUD_URI =
            Uri.parse("content://com.miui.gallery.cloud.provider/cloud");

    private final Object mLock = new Object();
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "gallery-metadata-original-restore");
        thread.setDaemon(true);
        return thread;
    });
    private final ScannerIngest mScanner;
    private final ClassLoader mLoader;
    private volatile Config mConfig;
    private Future<?> mFuture;
    private boolean mRunning;
    private boolean mClosed;

    MetadataRestoreJob(ClassLoader loader) {
        mLoader = loader;
        mScanner = new ScannerIngest(loader);
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
                result = restoreOne(context, config, store, target, item);
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
        mScanner.ingest(record.originalLocalPath);
        return new ItemResult(item.row, cleanupFailure.isEmpty() ? "success" : "partial",
                cleanupFailure, repairs.copies, repairs.rows, cleanedDuplicates);
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
        return canonical != null && canonical.equals(first);
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
            String row = item.getString("row");
            String sha1 = item.getString("sha1");
            String sha256 = item.getString("sha256");
            Object rawLength = item.get("length");
            if (row.isEmpty() || row.length() > 128 || !sha1.matches("(?i)[0-9a-f]{40}")
                    || !sha256.matches("(?i)[0-9a-f]{64}")
                    || !(rawLength instanceof Integer) && !(rawLength instanceof Long)) {
                throw new IOException("invalid_item_metadata");
            }
            Number number = (Number) rawLength;
            long length = number.longValue();
            if (length <= 0L) throw new IOException("invalid_item_length");
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
                    sha256.toLowerCase(Locale.ROOT), length, relatedRows));
        }
        return new Request(jobId, target.toLowerCase(Locale.ROOT), items);
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
            return true;
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

        Request(String jobId, String target, List<RequestItem> items) {
            this.jobId = jobId;
            this.target = target;
            this.items = items;
        }
    }

    private static final class RequestItem {
        final String row;
        final String sha1;
        final String sha256;
        final long length;
        final List<Long> relatedRows;

        RequestItem(String row, String sha1, String sha256, long length, List<Long> relatedRows) {
            this.row = row;
            this.sha1 = sha1;
            this.sha256 = sha256;
            this.length = length;
            this.relatedRows = relatedRows;
        }
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
