package com.tongluosao.gallerysync.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import com.tongluosao.gallerysync.core.Config;
import com.tongluosao.gallerysync.core.DownloadQueue;
import com.tongluosao.gallerysync.core.Logger;
import com.tongluosao.gallerysync.core.OriginalStore;
import com.tongluosao.gallerysync.core.UploadQueue;
import com.tongluosao.gallerysync.core.WebDavClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;

/** Keeps a verified WebDAV original safe while Gallery's storage slim operation replaces it. */
final class SlimHook {

    private static final String CLS_CONTROLLER = "com.miui.gallery.cleaner.slim.SlimController";
    private static final String CLS_MEDIA_ITEM = CLS_CONTROLLER + "$MediaItem";
    private static final String CLS_CLOUD_CONTRACT = "com.miui.gallery.provider.GalleryContract$Cloud";

    private final XposedInterface mXposed;
    private final ClassLoader mClassLoader;
    private volatile Config mConfig;

    SlimHook(XposedInterface xposed, ClassLoader classLoader) {
        mXposed = xposed;
        mClassLoader = classLoader;
    }

    void setConfig(Config config) {
        mConfig = config;
    }

    void install() {
        try {
            Class<?> controller = mClassLoader.loadClass(CLS_CONTROLLER);
            Class<?> mediaItem = mClassLoader.loadClass(CLS_MEDIA_ITEM);
            Method handleSlim = controller.getDeclaredMethod("handleSlim", mediaItem,
                    ArrayList.class);
            handleSlim.setAccessible(true);
            if (handleSlim.getReturnType() != long.class) {
                throw new NoSuchMethodException("handleSlim return type is not long");
            }

            ItemFields fields = new ItemFields(mediaItem);
            Class<?> saveOwner = controller;
            Method saveToDb = saveOwner.getDeclaredMethod("saveToDB", int.class, String.class,
                    String.class);
            saveToDb.setAccessible(true);
            if (saveToDb.getReturnType() != void.class
                    || !Modifier.isStatic(saveToDb.getModifiers())) {
                throw new NoSuchMethodException("saveToDB is not static void");
            }

            mXposed.hook(handleSlim)
                    .setId("gallery_slim_registered_original")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        Object media = chain.getArg(0);
                        if (media == null) {
                            return chain.proceed();
                        }
                        SlimItem item;
                        try {
                            item = fields.read(media);
                        } catch (Throwable t) {
                            Logger.e("event=slim_item_read_failed", t);
                            // The native path can delete a managed original. If classification
                            // fails, leave this one item alone.
                            return 0L;
                        }
                        if (item.path == null || item.path.isEmpty()) {
                            return chain.proceed();
                        }
                        return interceptManaged(item, saveToDb, chain);
                    });
            Logger.i("event=hook_registered method=SlimController.handleSlim(MediaItem,ArrayList)");
        } catch (ClassNotFoundException e) {
            Logger.e("event=slim_hook_class_not_found class=" + CLS_CONTROLLER, e);
        } catch (NoSuchMethodException e) {
            Logger.e("event=slim_hook_method_not_found method=SlimController.handleSlim/saveToDB", e);
        } catch (Throwable t) {
            Logger.e("event=slim_hook_failed", t);
        }
    }

    private Object interceptManaged(SlimItem item, Method saveToDb,
            XposedInterface.Chain chain) throws Throwable {
        File original = new File(item.path);
        Context context = HostContext.get(mClassLoader);
        if (context == null) {
            Logger.w("event=slim_skipped reason=no_host_context path=" + item.path);
            return 0L;
        }

        OriginalStore store;
        OriginalStore.Record byPath;
        try {
            store = OriginalStore.get(context.getApplicationContext());
            byPath = store.findByLocalPath(original);
        } catch (Throwable t) {
            Logger.e("event=slim_registry_unavailable path=" + item.path, t);
            return 0L;
        }

        Config config = mConfig;
        OriginalStore.Record record = null;
        if (config != null && hasIdentity(item)) {
            try {
                record = store.find(config, item.rowId, item.sha1);
            } catch (Throwable t) {
                Logger.e("event=slim_registry_lookup_failed path=" + item.path, t);
                return 0L;
            }
        }

        if (byPath == null && record == null) {
            if (config != null && config.isReady() && config.isReplace()) {
                if (item.serverType != 1 || !hasIdentity(item)) {
                    Logger.w("event=slim_skipped reason=legacy_original_unverified path=" + item.path);
                    return 0L;
                }
                try {
                    if (!migrateLegacyOriginal(store, config, original, item)) {
                        Logger.w("event=slim_skipped reason=legacy_remote_original_unverified path="
                                + item.path);
                        return 0L;
                    }
                    byPath = store.findByLocalPath(original);
                    record = store.find(config, item.rowId, item.sha1);
                } catch (Throwable t) {
                    Logger.e("event=slim_legacy_migration_failed path=" + item.path, t);
                    return 0L;
                }
            } else {
                // Do not intercept ordinary Gallery cleanup. A fail-closed registry still marks
                // every path protected, which distinguishes it from an unregistered original.
                try {
                    if (!store.isProtectedUpload(original, item.rowId, item.sha1)) {
                        return chain.proceed();
                    }
                } catch (Throwable t) {
                    Logger.e("event=slim_classification_failed path=" + item.path, t);
                }
                Logger.w("event=slim_skipped reason=protected_path_unclassified path=" + item.path);
                return 0L;
            }
        }

        if (config == null || record == null || byPath == null
                || !sameRecord(record, byPath)
                || !OriginalStore.targetKey(config).equals(record.target)
                || !samePath(record.originalLocalPath, original)
                || !matchesItemIdentity(record, item)) {
            Logger.i("event=slim_skipped reason=registered_path_not_current_original path="
                    + item.path);
            return 0L;
        }

        // Gallery's JPEG preview builder cannot preserve video originals. Keep managed videos
        // untouched rather than allowing native slimming to remove their only local copy.
        if (item.serverType != 1) {
            Logger.i("event=slim_skipped reason=unsupported_media_type path=" + item.path);
            return 0L;
        }
        if (!localOriginalMatches(original, record)) {
            Logger.w("event=slim_skipped reason=original_changed_or_protected path=" + item.path);
            return 0L;
        }

        try {
            if (!store.ensureVerifiedRemoteOriginal(config, item.rowId, item.sha1)) {
                Logger.w("event=slim_skipped reason=remote_original_unverified path=" + item.path);
                return 0L;
            }
        } catch (Throwable t) {
            Logger.e("event=slim_remote_verify_failed path=" + item.path, t);
            return 0L;
        }

        File parent;
        try {
            parent = previewDirectory(original);
        } catch (IOException e) {
            Logger.w("event=slim_skipped reason=preview_directory_unavailable path=" + item.path);
            return 0L;
        }
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            Logger.w("event=slim_skipped reason=preview_directory_unavailable path=" + item.path);
            return 0L;
        }
        File staged = null;
        File preview = null;
        OriginalStore.PathLease originalLease = null;
        OriginalStore.PathLease previewLease = null;
        boolean previewPublished = false;
        try {
            staged = File.createTempFile(".slim-", ".jpg", parent);
            if (!DownloadQueue.createCompressedPreview(original, staged)
                    || !staged.isFile() || staged.length() <= 0
                    || staged.length() >= original.length()) {
                Logger.i("event=slim_skipped reason=preview_not_smaller path=" + item.path);
                return 0L;
            }

            // Acquire the source write lease before registering it as derived. This waits for any
            // native upload holding the matching read lease, and then protects the source through
            // the DB switch and deletion.
            originalLease = store.beginDerivedMutation(config, item.rowId, item.sha1, original);
            if (originalLease == null) {
                Logger.w("event=slim_skipped reason=source_lease_unavailable path=" + item.path);
                return 0L;
            }
            if (!original.isFile() || original.length() != record.originalLength
                    || !record.sha256.equals(sha256(original))) {
                Logger.w("event=slim_skipped reason=original_changed_during_prepare path=" + item.path);
                return 0L;
            }

            // Create an exclusive, unique destination. Replacing this placeholder publishes only
            // a path allocated by this operation, never an existing user's file.
            preview = File.createTempFile(".slim-preview-", ".jpg", parent);
            // Persist the final path and hold its write lease before publishing the preview. The
            // post-move registration stores its fingerprint for future upload classification.
            if (!store.registerDerivedPath(config, item.rowId, item.sha1, preview)) {
                Logger.w("event=slim_skipped reason=preview_registration_failed path=" + item.path);
                return 0L;
            }
            previewLease = store.beginDerivedMutation(config, item.rowId, item.sha1, preview);
            if (previewLease == null) {
                Logger.w("event=slim_skipped reason=preview_lease_unavailable path=" + item.path);
                return 0L;
            }

            publish(staged, preview);
            previewPublished = true;
            if (!store.registerDerivedPath(config, item.rowId, item.sha1, preview)) {
                Logger.w("event=slim_db_update_skipped reason=preview_fingerprint_failed path="
                        + item.path);
                return 0L;
            }

            try {
                // Dex evidence confirms saveToDB(int, String, String): arg 2 is thumbnailFile,
                // arg 3 is localFile. Native slimming stores its generated preview as
                // thumbnailFile and clears localFile to make Gallery treat the original as absent.
                saveToDb.invoke(null, item.id, preview.getAbsolutePath(), "");
            } catch (Throwable t) {
                Logger.e("event=slim_db_update_call_failed path=" + item.path, t);
            }

            if (!databasePointsToPreview(context, item.id, preview.getAbsolutePath())) {
                Logger.w("event=slim_db_update_unconfirmed path=" + item.path
                        + " preview=" + preview.getAbsolutePath());
                return 0L;
            }

            long sourceLength = original.length();
            long previewLength = preview.length();
            if (!original.delete()) {
                Logger.w("event=slim_original_delete_failed path=" + item.path
                        + " preview=" + preview.getAbsolutePath());
                return 0L;
            }
            Logger.i("event=slim_original_replaced path=" + item.path
                    + " preview=" + preview.getAbsolutePath()
                    + " saved=" + Math.max(0L, sourceLength - previewLength));
            return Math.max(0L, sourceLength - previewLength);
        } catch (Throwable t) {
            Logger.e("event=slim_optimization_failed path=" + item.path, t);
            // A published preview remains registered and the original remains until the DB has
            // been confirmed to point at it.
            return 0L;
        } finally {
            if (staged != null && staged.exists() && !staged.delete()) {
                Logger.d("event=slim_temp_cleanup_failed path=" + staged.getAbsolutePath());
            }
            if (!previewPublished && preview != null && preview.exists() && !preview.delete()) {
                Logger.d("event=slim_placeholder_cleanup_failed path=" + preview.getAbsolutePath());
            }
            if (previewLease != null) previewLease.close();
            if (originalLease != null) originalLease.close();
        }
    }

    private boolean databasePointsToPreview(Context context, int id, String thumbnailPath)
            throws Exception {
        Class<?> cloud = mClassLoader.loadClass(CLS_CLOUD_CONTRACT);
        Field uriField = cloud.getField("CLOUD_URI");
        Uri uri = (Uri) uriField.get(null);
        if (uri == null) return false;
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(uri,
                new String[]{"localFile", "thumbnailFile"}, "_id = ?",
                new String[]{String.valueOf(id)}, null)) {
            if (cursor == null || !cursor.moveToFirst()) return false;
            int localColumn = cursor.getColumnIndex("localFile");
            int thumbnailColumn = cursor.getColumnIndex("thumbnailFile");
            if (localColumn < 0 || thumbnailColumn < 0) return false;
            String actualLocal = cursor.getString(localColumn);
            String actualThumbnail = cursor.getString(thumbnailColumn);
            return (actualLocal == null || actualLocal.isEmpty())
                    && thumbnailPath.equals(actualThumbnail);
        }
    }

    private static boolean migrateLegacyOriginal(OriginalStore store, Config config,
            File original, SlimItem item) throws IOException {
        String candidate = UploadQueue.remotePathFor(config, item.path, false, item.date);
        if (candidate == null || candidate.isEmpty()) return false;
        int slash = candidate.lastIndexOf('/');
        String requestedParent = slash < 0 ? "" : candidate.substring(0, slash);
        String name = slash < 0 ? candidate : candidate.substring(slash + 1);
        WebDavClient client = new WebDavClient(config.getBaseUrl(), config.getUsername(),
                config.getPassword());
        try {
            // This resolves existing components and their actual server casing without creating
            // collections while checking whether a historical backup exists.
            String actualParent = client.resolveExistingCollectionPath(requestedParent);
            if (actualParent == null || name.isEmpty()) return false;
            String remotePath = WebDavClient.joinPath(actualParent, name);
            return store.backfillFromRemote(config, remotePath, original,
                    item.rowId, item.sha1);
        } finally {
            client.close();
        }
    }

    private static File previewDirectory(File original) throws IOException {
        File parent = original.getAbsoluteFile().getParentFile();
        if (parent == null) throw new IOException("original has no parent directory");
        return new File(parent, ".gallerysync-slim");
    }

    private static void publish(File staged, File destination) throws IOException {
        try {
            Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailure) {
            try {
                Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailure) {
                moveFailure.addSuppressed(atomicFailure);
                throw moveFailure;
            }
        }
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder value = new StringBuilder(64);
            for (byte b : digest.digest()) value.append(String.format(Locale.US, "%02x", b & 0xff));
            return value.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by Android", e);
        }
    }

    private static boolean samePath(String expected, File path) {
        if (expected == null || path == null) return false;
        try {
            return expected.equals(path.getCanonicalPath());
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean sameRecord(OriginalStore.Record first, OriginalStore.Record second) {
        return first != null && second != null && first.target.equals(second.target)
                && first.remotePath.equals(second.remotePath) && first.sha256.equals(second.sha256);
    }

    private static boolean matchesItemIdentity(OriginalStore.Record record, SlimItem item) {
        boolean rowMatches = item.rowId != null && !item.rowId.isEmpty()
                && item.rowId.equals(record.rowId);
        boolean shaMatches = item.sha1 != null && !item.sha1.isEmpty()
                && item.sha1.equalsIgnoreCase(record.sha1 == null ? "" : record.sha1);
        if (item.rowId != null && !item.rowId.isEmpty()
                && record.rowId != null && !item.rowId.equals(record.rowId)) return false;
        if (item.sha1 != null && !item.sha1.isEmpty()
                && record.sha1 != null && !item.sha1.equalsIgnoreCase(record.sha1)) return false;
        return rowMatches || shaMatches;
    }

    private static boolean hasIdentity(SlimItem item) {
        return (item.rowId != null && !item.rowId.isEmpty())
                || (item.sha1 != null && !item.sha1.isEmpty());
    }

    private static boolean localOriginalMatches(File original, OriginalStore.Record record) {
        if (original == null || record == null || !original.isFile()
                || original.length() != record.originalLength) return false;
        try {
            return record.originalLocalPath != null
                    && record.originalLocalPath.equals(original.getCanonicalPath())
                    && record.sha256.equals(sha256(original))
                    && !OriginalStore.isReservedDownloadPath(original.getCanonicalPath());
        } catch (IOException e) {
            return false;
        }
    }

    private static final class ItemFields {
        final Field id;
        final Field path;
        final Field thumbnailPath;
        final Field sha1;
        final Field serverType;
        final Field mixDateTime;

        ItemFields(Class<?> type) throws NoSuchFieldException {
            id = field(type, "id");
            path = field(type, "localFilePath");
            thumbnailPath = field(type, "thumbnailFilePath");
            sha1 = field(type, "sha1");
            serverType = field(type, "serverType");
            mixDateTime = field(type, "mixDateTime");
        }

        private static Field field(Class<?> type, String name) throws NoSuchFieldException {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        SlimItem read(Object media) throws IllegalAccessException {
            int itemId = id.getInt(media);
            String local = (String) path.get(media);
            String thumbnail = (String) thumbnailPath.get(media);
            String itemSha1 = (String) sha1.get(media);
            int type = serverType.getInt(media);
            long date = mixDateTime.getLong(media);
            return new SlimItem(itemId, local, thumbnail, itemSha1,
                    itemId < 0 ? null : String.valueOf(itemId), type, date);
        }
    }

    private static final class SlimItem {
        final int id;
        final String path;
        final String thumbnailFilePath;
        final String sha1;
        final String rowId;
        final int serverType;
        final long date;

        SlimItem(int id, String path, String thumbnailFilePath, String sha1, String rowId,
                int serverType, long date) {
            this.id = id;
            this.path = path;
            this.thumbnailFilePath = thumbnailFilePath;
            this.sha1 = sha1;
            this.rowId = rowId;
            this.serverType = serverType;
            this.date = date;
        }
    }
}
