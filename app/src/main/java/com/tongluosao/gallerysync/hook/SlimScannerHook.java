package com.tongluosao.gallerysync.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import com.tongluosao.gallerysync.core.Config;
import com.tongluosao.gallerysync.core.Logger;
import com.tongluosao.gallerysync.core.OriginalStore;
import com.tongluosao.gallerysync.core.UploadQueue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.BooleanSupplier;

import io.github.libxposed.api.XposedInterface;

/** Adds verified WebDAV originals to Gallery's synced slim scan in per-target mode. */
final class SlimScannerHook {

    private static final String CLS_SCANNER = "com.miui.gallery.cleaner.slim.SlimScanner";
    private static final String CLS_MEDIA_CONTRACT =
            "com.miui.gallery.provider.GalleryContract$Media";
    private static final String MEDIA_URI_FIELD = "URI_OWNER_ALBUM_DETAIL_MEDIA";
    private static final String SYNC_GATE = "alias_sync_state = 0 AND ";
    private static final int MAX_CANDIDATE_IDS = 25_000;
    private static final String[] PROJECTION = {
            "_id", "localFile", "sha1", "serverType", "localFlag"
    };

    private final XposedInterface mXposed;
    private final ClassLoader mClassLoader;
    private volatile Config mConfig;
    private volatile BooleanSupplier mVideoRestorePolicy = () -> false;

    SlimScannerHook(XposedInterface xposed, ClassLoader classLoader) {
        mXposed = xposed;
        mClassLoader = classLoader;
    }

    void setConfig(Config config) {
        mConfig = config;
    }

    void setVideoRestorePolicy(BooleanSupplier policy) {
        mVideoRestorePolicy = policy == null ? () -> false : policy;
    }

    void install() {
        try {
            Class<?> scanner = mClassLoader.loadClass(CLS_SCANNER);
            Method getSelection = scanner.getDeclaredMethod("getSelection");
            getSelection.setAccessible(true);
            if (getSelection.getReturnType() != String.class
                    || Modifier.isStatic(getSelection.getModifiers())) {
                throw new NoSuchMethodException("SlimScanner.getSelection has unexpected shape");
            }

            mXposed.hook(getSelection)
                    .setId("gallery_slim_per_target_selection")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (!(result instanceof String)) return result;
                        String selection = (String) result;
                        Config config = mConfig;
                        if (config == null || !config.isReady() || !config.isReplace()
                                || !config.isLocalFlagPerTarget()
                                || !selection.startsWith(SYNC_GATE)) {
                            return result;
                        }
                        try {
                            String ids = findSafePhotoCandidates(config, selection);
                            if (ids.isEmpty()) {
                                Logger.d("event=slim_scan_webdav_candidates count=0");
                                return result;
                            }
                            // Replace only Gallery's cloud-sync gate. Its own format, age, local
                            // path, album, and other slim filters remain in effect for both
                            // Xiaomi-synced rows and the explicitly listed WebDAV rows.
                            String rest = selection.substring(SYNC_GATE.length());
                            String extended = "(alias_sync_state = 0 OR _id IN (" + ids
                                    + ")) AND " + rest;
                            Logger.i("event=slim_scan_webdav_candidates count="
                                    + countIds(ids));
                            return extended;
                        } catch (Throwable t) {
                            Logger.w("event=slim_scan_candidates_failed", t);
                            return result;
                        }
                    });
            Logger.i("event=hook_registered method=SlimScanner.getSelection()");
        } catch (ClassNotFoundException e) {
            Logger.e("event=slim_scanner_class_not_found class=" + CLS_SCANNER, e);
        } catch (NoSuchMethodException e) {
            Logger.e("event=slim_scanner_method_not_found method=SlimScanner.getSelection", e);
        } catch (Throwable t) {
            Logger.e("event=slim_scanner_hook_failed", t);
        }
    }

    /**
     * Build a literal numeric ID list from rows that already pass Gallery's remaining slim
     * filters. Registered rows must still match the current target, identity, path, length, and
     * mtime in OriginalStore. The final handler rechecks the file SHA-256 and remote bytes before
     * it can delete anything. Registry-less legacy rows require a current-target completion-ledger
     * hit here and a GET plus SHA-256 backfill in SlimHook before optimization.
     */
    private String findSafePhotoCandidates(Config config, String selection) throws Exception {
        Context host = HostContext.get(mClassLoader);
        if (host == null) return "";

        Class<?> mediaContract = mClassLoader.loadClass(CLS_MEDIA_CONTRACT);
        Field uriField = mediaContract.getField(MEDIA_URI_FIELD);
        Uri uri = (Uri) uriField.get(null);
        if (uri == null) return "";

        String gallerySlimFilters = selection.substring(SYNC_GATE.length());
        String candidateSelection = "(" + gallerySlimFilters + ")"
                + " AND (localFlag = 7 OR localFlag = 8)"
                + " AND (serverType = 1 OR serverType = 2)";
        ContentResolver resolver = host.getContentResolver();
        OriginalStore store = OriginalStore.get(host.getApplicationContext());
        String target = OriginalStore.targetKey(config);
        StringBuilder ids = new StringBuilder();
        int candidateCount = 0;
        try (Cursor cursor = resolver.query(uri, PROJECTION, candidateSelection, null, null)) {
            if (cursor == null) return "";
            int idColumn = cursor.getColumnIndex("_id");
            int pathColumn = cursor.getColumnIndex("localFile");
            int sha1Column = cursor.getColumnIndex("sha1");
            int typeColumn = cursor.getColumnIndex("serverType");
            int flagColumn = cursor.getColumnIndex("localFlag");
            if (idColumn < 0 || pathColumn < 0 || sha1Column < 0
                    || typeColumn < 0 || flagColumn < 0) {
                return "";
            }

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                String path = cursor.getString(pathColumn);
                String sha1 = cursor.isNull(sha1Column) ? null : cursor.getString(sha1Column);
                int serverType = cursor.getInt(typeColumn);
                int localFlag = cursor.getInt(flagColumn);
                if (id <= 0 || (localFlag != 7 && localFlag != 8)
                        || (serverType != 1 && serverType != 2)
                        || path == null || path.isEmpty()) {
                    continue;
                }
                if (serverType == 2 && (!videoRestoreReady()
                        || sha1 == null || sha1.isEmpty()
                        || new File(path).getName().isEmpty())) {
                    continue;
                }

                File file = new File(path);
                if (!file.isFile() || file.length() <= 0) continue;
                String canonicalPath = file.getCanonicalPath();
                String rowId = String.valueOf(id);
                OriginalStore.Record record = store.find(config, rowId, sha1);
                boolean eligible;
                if (record != null) {
                    eligible = matchesRegisteredOriginal(record, target, canonicalPath,
                            rowId, sha1, file)
                            && (serverType != 2 || (record.sha1 != null
                            && !record.sha1.isEmpty()
                            && sha1.equalsIgnoreCase(record.sha1)));
                } else {
                    eligible = UploadQueue.wasVerifiedUploadCurrentTarget(canonicalPath,
                            file.length(), file.lastModified());
                }
                if (!eligible) continue;

                if (++candidateCount > MAX_CANDIDATE_IDS) {
                    Logger.w("event=slim_scan_candidates_too_many count=" + candidateCount);
                    return "";
                }
                if (ids.length() > 0) ids.append(',');
                ids.append(id);
            }
        }
        return ids.toString();
    }

    private boolean videoRestoreReady() {
        BooleanSupplier policy = mVideoRestorePolicy;
        if (policy == null) return false;
        try {
            return policy.getAsBoolean();
        } catch (Throwable t) {
            Logger.w("event=slim_video_restore_policy_failed", t);
            return false;
        }
    }

    private static boolean matchesRegisteredOriginal(OriginalStore.Record record, String target,
            String canonicalPath, String rowId, String sha1, File file) {
        if (!target.equals(record.target) || record.originalLocalPath == null
                || !record.originalLocalPath.equals(canonicalPath)
                || record.originalLength != file.length()
                || record.originalLocalMtime <= 0L
                || record.originalLocalMtime != file.lastModified()
                || record.sha256 == null || record.sha256.length() != 64) {
            return false;
        }

        boolean rowMatches = rowId.equals(record.rowId);
        boolean shaMatches = sha1 != null && !sha1.isEmpty() && record.sha1 != null
                && sha1.equalsIgnoreCase(record.sha1);
        if (record.rowId != null && !rowId.equals(record.rowId)) return false;
        if (sha1 != null && !sha1.isEmpty() && record.sha1 != null
                && !sha1.equalsIgnoreCase(record.sha1)) return false;
        return rowMatches || shaMatches;
    }

    private static int countIds(String ids) {
        if (ids.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < ids.length(); i++) {
            if (ids.charAt(i) == ',') count++;
        }
        return count;
    }
}
