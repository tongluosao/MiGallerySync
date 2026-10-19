package com.tongluosao.gallerysync.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Durable mapping between a local gallery item and its verified WebDAV original.
 *
 * <p>The registry is intentionally separate from the upload/download queue ledger. Queue state is
 * disposable and target-scoped; these mappings must survive queue resets so an optimized preview
 * can never be mistaken for a new original and the remote original remains discoverable.
 */
public final class OriginalStore {

    private static final String DIR = "webdav_sync";
    private static final String FILE_NAME = "originals.json";
    private static final int MAX_RECORDS = 100_000;
    private static volatile OriginalStore sInstance;
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> PATH_LOCKS =
            new ConcurrentHashMap<>();

    private final Object mLock = new Object();
    private final File mFile;
    private final LinkedHashMap<String, Record> mRecords = new LinkedHashMap<>();
    /** A corrupt registry disables uploads until it can be inspected or repaired. */
    private boolean mHealthy = true;

    private OriginalStore(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            mHealthy = false;
            Logger.w("event=original_store_dir_failed dir=" + dir.getAbsolutePath());
        }
        mFile = new File(dir, FILE_NAME);
        load();
    }

    /** Package-private for deterministic persistence tests. */
    OriginalStore(File directory) {
        if (!directory.exists() && !directory.mkdirs()) {
            mHealthy = false;
        }
        mFile = new File(directory, FILE_NAME);
        load();
    }

    public static OriginalStore get(Context context) {
        if (sInstance == null) {
            synchronized (OriginalStore.class) {
                if (sInstance == null) {
                    sInstance = new OriginalStore(context);
                }
            }
        }
        return sInstance;
    }

    /** Stable target identity without storing credentials. Password rotation keeps old mappings usable. */
    public static String targetKey(Config config) {
        if (config == null) {
            return "";
        }
        String base = safeBaseUrl(config.getBaseUrl());
        return sha256((base + "\n" + safe(config.getUsername()) + "\n"
                + safe(config.getRemoteRoot())).getBytes(StandardCharsets.UTF_8));
    }

    /** Immutable metadata returned to hook code. */
    public static final class Record {
        public final String target;
        public final String remotePath;
        public final long originalLength;
        public final String sha256;
        public final String originalLocalPath;
        public final String previewLocalPath;
        public final String rowId;
        public final String sha1;
        public final long originalLocalMtime;
        public final Set<String> derivedPaths;
        /** SHA-256 and byte length of generated copies, catching copied/renamed previews too. */
        public final Map<String, Long> derivedHashes;
        private final Set<String> identities;
        private final Set<String> localAliases;

        private Record(String target, String remotePath, long originalLength, String sha256,
                String originalLocalPath, String previewLocalPath, String rowId, String sha1,
                long originalLocalMtime, Set<String> derivedPaths) {
            this(target, remotePath, originalLength, sha256, originalLocalPath, previewLocalPath,
                    rowId, sha1, originalLocalMtime, derivedPaths,
                    initialIdentities(rowId, sha1), initialAliases(originalLocalPath,
                            previewLocalPath, derivedPaths), Collections.emptyMap());
        }

        private Record(String target, String remotePath, long originalLength, String sha256,
                String originalLocalPath, String previewLocalPath, String rowId, String sha1,
                long originalLocalMtime, Set<String> derivedPaths, Set<String> identities,
                Set<String> localAliases, Map<String, Long> derivedHashes) {
            this.target = target;
            this.remotePath = remotePath;
            this.originalLength = originalLength;
            this.sha256 = sha256;
            this.originalLocalPath = originalLocalPath;
            this.previewLocalPath = previewLocalPath;
            this.rowId = rowId;
            this.sha1 = sha1;
            this.originalLocalMtime = originalLocalMtime;
            Set<String> paths = new LinkedHashSet<>(derivedPaths);
            if (previewLocalPath != null) paths.add(previewLocalPath);
            this.derivedPaths = Collections.unmodifiableSet(paths);
            Set<String> allIdentities = new LinkedHashSet<>(identities);
            addIdentity(allIdentities, rowId, sha1);
            this.identities = Collections.unmodifiableSet(allIdentities);
            Set<String> allAliases = new LinkedHashSet<>(localAliases);
            if (originalLocalPath != null) allAliases.add(originalLocalPath);
            if (previewLocalPath != null) allAliases.add(previewLocalPath);
            allAliases.addAll(paths);
            this.localAliases = Collections.unmodifiableSet(allAliases);
            this.derivedHashes = Collections.unmodifiableMap(new LinkedHashMap<>(derivedHashes));
        }

        private static Set<String> initialIdentities(String rowId, String sha1) {
            Set<String> result = new LinkedHashSet<>();
            addIdentity(result, rowId, sha1);
            return result;
        }

        private static Set<String> initialAliases(String original, String preview,
                Set<String> derived) {
            Set<String> result = new LinkedHashSet<>();
            if (original != null) result.add(original);
            if (preview != null) result.add(preview);
            if (derived != null) result.addAll(derived);
            return result;
        }

        private static void addIdentity(Set<String> into, String rowId, String sha1) {
            String id = clean(rowId);
            String hash = clean(sha1);
            if (id != null) into.add("r:" + id);
            if (hash != null) into.add("s:" + hash.toLowerCase(Locale.US));
        }

        private Record withIdentity(String nextRowId, String nextSha1) {
            Set<String> aliases = new LinkedHashSet<>(identities);
            addIdentity(aliases, nextRowId, nextSha1);
            return new Record(target, remotePath, originalLength, sha256, originalLocalPath,
                    previewLocalPath, rowId == null ? clean(nextRowId) : rowId,
                    sha1 == null ? clean(nextSha1) : sha1, originalLocalMtime,
                    derivedPaths, aliases, localAliases, derivedHashes);
        }

        private Record withRenamedDerivedAlias(String path, String nextRowId, String nextSha1) {
            Set<String> paths = new LinkedHashSet<>(derivedPaths);
            paths.add(path);
            Set<String> aliases = new LinkedHashSet<>(localAliases);
            aliases.add(path);
            Set<String> allIdentities = new LinkedHashSet<>(identities);
            addIdentity(allIdentities, nextRowId, nextSha1);
            return new Record(target, remotePath, originalLength, sha256, originalLocalPath,
                    previewLocalPath, rowId, sha1, originalLocalMtime, paths, allIdentities,
                    aliases, derivedHashes);
        }

        private Record withDerivedPath(String path) {
            Set<String> paths = new LinkedHashSet<>(derivedPaths);
            paths.add(path);
            Set<String> aliases = new LinkedHashSet<>(localAliases);
            aliases.add(path);
            Map<String, Long> hashes = new LinkedHashMap<>(derivedHashes);
            return new Record(target, remotePath, originalLength, sha256, originalLocalPath,
                    previewLocalPath, rowId, sha1, originalLocalMtime, paths,
                    identities, aliases, hashes);
        }

        private Record withDerivedHash(String hash, long length) {
            Map<String, Long> hashes = new LinkedHashMap<>(derivedHashes);
            hashes.put(hash, length);
            return new Record(target, remotePath, originalLength, sha256, originalLocalPath,
                    previewLocalPath, rowId, sha1, originalLocalMtime, derivedPaths,
                    identities, localAliases, hashes);
        }

        private Record withRestoredLocal(File file) throws IOException {
            String path = canonical(file);
            Set<String> paths = new LinkedHashSet<>(derivedPaths);
            paths.remove(path);
            Set<String> aliases = new LinkedHashSet<>(localAliases);
            aliases.add(path);
            String preview = path.equals(previewLocalPath) ? null : previewLocalPath;
            return new Record(target, remotePath, originalLength, sha256, path, preview,
                    rowId, sha1, originalLocalMtime > 0L ? originalLocalMtime : file.lastModified(),
                    paths, identities, aliases, derivedHashes);
        }
    }

    /**
     * Register an upload only after PUT and remote length verification succeeded.
     * The current file digest is retained so later in-place compression is detectable.
     */
    public void registerUploadedOriginal(Config config, String remotePath, File localOriginal,
            String rowId, String sha1) throws IOException {
        register(config, remotePath, localOriginal, null, localOriginal, rowId, sha1);
    }

    /**
     * Register the source fingerprint captured before PUT. Callers may finish verification on a
     * different thread, after the source has changed, so hashing it at registration time would
     * describe current local bytes rather than the bytes supplied to the upload.
     */
    public void registerUploadedOriginal(Config config, String remotePath, File localOriginal,
            String rowId, String sha1, String uploadedSha256, long uploadedLength,
            long uploadedMtime) throws IOException {
        if (config == null || remotePath == null || remotePath.isEmpty() || localOriginal == null
                || uploadedLength <= 0 || !isSha256(uploadedSha256)) {
            throw new IOException("cannot register missing uploaded original fingerprint");
        }
        persist(new Record(targetKey(config), remotePath, uploadedLength,
                uploadedSha256.toLowerCase(Locale.US), canonical(localOriginal), null, rowId, sha1,
                uploadedMtime, Collections.emptySet()));
    }

    /** Register a completed normal download; mirror files remain protected even after restart. */
    public void registerDownloadedOriginal(Config config, String remotePath, File localOriginal,
            long originalLength, String originalSha256) throws IOException {
        if (localOriginal == null || !localOriginal.isFile()) {
            throw new IOException("download original is missing");
        }
        String digest = originalSha256 == null || originalSha256.isEmpty()
                ? sha256(localOriginal) : originalSha256;
        if (localOriginal.length() != originalLength || !digest.equals(sha256(localOriginal))) {
            throw new IOException("download original changed before registration");
        }
        persist(new Record(targetKey(config), remotePath, originalLength, digest,
                canonical(localOriginal), null, null, null, localOriginal.lastModified(),
                Collections.emptySet()));
    }

    /** Persist protection for a staged normal download before publishing it at its final path. */
    public void registerDownloadedOriginal(Config config, String remotePath, File stagedOriginal,
            File finalPath, long originalLength, String originalSha256) throws IOException {
        if (stagedOriginal == null || !stagedOriginal.isFile() || finalPath == null
                || stagedOriginal.length() != originalLength
                || !originalSha256.equals(sha256(stagedOriginal))) {
            throw new IOException("staged download changed before registration");
        }
        persist(new Record(targetKey(config), remotePath, originalLength, originalSha256,
                canonical(finalPath), null, null, null, 0L, Collections.emptySet()));
    }

    /** Register a downloaded compressed preview before its final path is made visible to scanners. */
    public void registerDownloadedPreview(Config config, String remotePath, long originalLength,
            String originalSha256, File preview) throws IOException {
        registerDownloadedPreview(config, remotePath, originalLength, originalSha256,
                null, preview);
    }

    /** Register both intended raw destination and the separately named compressed preview. */
    public void registerDownloadedPreview(Config config, String remotePath, long originalLength,
            String originalSha256, File originalDestination, File preview) throws IOException {
        registerDownloadedPreview(config, remotePath, originalLength, originalSha256,
                originalDestination, preview, preview != null && preview.isFile() ? preview : null);
    }

    /** Protect the final path and persist the staged image fingerprint before publishing it. */
    public void registerDownloadedPreview(Config config, String remotePath, long originalLength,
            String originalSha256, File originalDestination, File preview, File stagedPreview)
            throws IOException {
        if (preview == null) {
            throw new IOException("preview path is missing");
        }
        if (stagedPreview != null && (!stagedPreview.isFile() || stagedPreview.length() <= 0)) {
            throw new IOException("staged preview is missing");
        }
        String path = canonical(preview);
        Record record = new Record(targetKey(config), remotePath, originalLength, originalSha256,
                originalDestination == null ? null : canonical(originalDestination),
                path, null, null, 0L, Collections.singleton(path));
        if (stagedPreview != null) {
            record = record.withDerivedHash(sha256(stagedPreview), stagedPreview.length());
        }
        persist(record);
    }

    /**
     * Confirm an old backup that predates the registry. A size match alone is insufficient: GET
     * the configured remote object, compare its digest with the current local original, then record.
     */
    public boolean backfillFromRemote(Config config, String remotePath, File localOriginal,
            String rowId, String sha1) throws IOException {
        if (config == null || remotePath == null || remotePath.isEmpty()
                || localOriginal == null || !localOriginal.isFile() || localOriginal.length() <= 0) {
            return false;
        }
        String localDigest = sha256(localOriginal);
        File tempDir = new File(mFile.getParentFile(), "original_verify");
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IOException("cannot create original verification directory");
        }
        File remoteCopy = File.createTempFile("verify-", ".tmp", tempDir);
        WebDavClient client = new WebDavClient(config.getBaseUrl(), config.getUsername(),
                config.getPassword());
        try {
            client.get(remotePath, remoteCopy);
            if (remoteCopy.length() != localOriginal.length()
                    || !localDigest.equals(sha256(remoteCopy))) {
                return false;
            }
            persist(new Record(targetKey(config), remotePath, localOriginal.length(), localDigest,
                    canonical(localOriginal), null, rowId, sha1,
                    localOriginal.lastModified(), Collections.emptySet()));
            return true;
        } finally {
            client.close();
            if (remoteCopy.exists() && !remoteCopy.delete()) {
                Logger.d("event=original_verify_temp_cleanup_failed path="
                        + remoteCopy.getAbsolutePath());
            }
        }
    }

    /** Add cloud row identity after a scanner has created the row for a downloaded preview. */
    public boolean bindIdentity(File localPath, String rowId, String sha1) throws IOException {
        if (localPath == null || (clean(rowId) == null && clean(sha1) == null)) {
            return false;
        }
        String path = canonical(localPath);
        synchronized (mLock) {
            requireHealthyLocked();
            for (Map.Entry<String, Record> entry : mRecords.entrySet()) {
                Record record = entry.getValue();
                if (containsLocalPath(record, path)) {
                    Record updated = record.withIdentity(rowId, sha1);
                    mRecords.put(entry.getKey(), updated);
                    saveLocked();
                    return true;
                }
            }
        }
        return false;
    }

    /** Alias used by ScannerIngest after the gallery creates a row for a downloaded preview. */
    public boolean registerScannedAlias(File localPath, String rowId, String sha1)
            throws IOException {
        return bindIdentity(localPath, rowId, sha1);
    }

    /**
     * Register a scanner-renamed file only when it still matches a known derived fingerprint.
     * The record key pins the operation to the captured target, remote path, and original digest.
     */
    public boolean registerRenamedDerivedAlias(Record expected, File newPath, String rowId,
            String sha1, String expectedHash, long expectedLength) throws IOException {
        if (expected == null || newPath == null || expectedLength <= 0L
                || !isSha256(expectedHash)
                || (clean(rowId) == null && clean(sha1) == null)) {
            return false;
        }
        String digest = expectedHash.toLowerCase(Locale.US);
        Long capturedLength = expected.derivedHashes.get(digest);
        if (capturedLength == null || capturedLength.longValue() != expectedLength) {
            return false;
        }
        String path = canonical(newPath);
        synchronized (mLock) {
            requireHealthyLocked();
            String key = recordKey(expected);
            Record current = mRecords.get(key);
            if (current == null) return false;
            Long currentLength = current.derivedHashes.get(digest);
            if (currentLength == null || currentLength.longValue() != expectedLength
                    || !newPath.isFile() || newPath.length() != expectedLength
                    || !digest.equals(sha256(newPath))) {
                return false;
            }
            Record updated = current.withRenamedDerivedAlias(path, rowId, sha1);
            mRecords.put(key, updated);
            saveLocked();
            return true;
        }
    }

    /** Find a mapping by any known local original, preview, or derived path. */
    public Record findByLocalPath(File localPath) {
        if (localPath == null) {
            return null;
        }
        String path;
        try {
            path = canonical(localPath);
        } catch (IOException e) {
            return null;
        }
        synchronized (mLock) {
            for (Record record : mRecords.values()) {
                if (containsLocalPath(record, path)) {
                    return record;
                }
            }
        }
        return null;
    }

    /** Find a local alias belonging to one exact target and remote object. */
    public Record findByRemotePath(Config config, String remotePath, File localPath) {
        if (config == null || remotePath == null || remotePath.isEmpty() || localPath == null) {
            return null;
        }
        String path;
        try {
            path = canonical(localPath);
        } catch (IOException e) {
            return null;
        }
        String target = targetKey(config);
        synchronized (mLock) {
            for (Record record : mRecords.values()) {
                if (target.equals(record.target) && remotePath.equals(record.remotePath)
                        && containsLocalPath(record, path)) {
                    return record;
                }
            }
        }
        return null;
    }

    /** Find by gallery identity under the currently configured remote target. */
    public Record find(Config config, String rowId, String sha1) {
        String target = targetKey(config);
        synchronized (mLock) {
            Record latest = null;
            for (Record record : mRecords.values()) {
                if (target.equals(record.target) && identityMatches(record, rowId, sha1)) {
                    latest = record;
                }
            }
            return latest;
        }
    }

    /** Return true only when a registered original still exists remotely at its recorded length. */
    public boolean hasRemoteOriginal(Config config, String rowId, String sha1) throws IOException {
        Record record = find(config, rowId, sha1);
        if (record == null || record.originalLength <= 0 || record.remotePath.isEmpty()) {
            return false;
        }
        WebDavClient client = new WebDavClient(config.getBaseUrl(), config.getUsername(),
                config.getPassword());
        try {
            return client.storedAs(record.remotePath, record.originalLength);
        } finally {
            client.close();
        }
    }

    /** Verify the actual remote bytes against the registry digest before optimizing local storage. */
    public boolean ensureVerifiedRemoteOriginal(Config config, String rowId, String sha1)
            throws IOException {
        Record record = find(config, rowId, sha1);
        return record != null && verifyRemote(record, config);
    }

    /**
     * Safe migration path for originals uploaded before this registry existed. A matching size is
     * never sufficient: the remote GET must match the current local original byte-for-byte.
     */
    public boolean ensureVerifiedRemoteOriginal(Config config, String remotePath,
            File localOriginal, String rowId, String sha1) throws IOException {
        return backfillFromRemote(config, remotePath, localOriginal, rowId, sha1);
    }

    /**
     * Restore the registered original with GET only. The existing preview remains intact unless
     * length and SHA-256 both match, then a same-directory atomic replacement publishes the bytes.
     */
    public File restoreOriginal(Config config, String rowId, String sha1, File destination)
            throws IOException {
        Record record = find(config, rowId, sha1);
        if (record == null) {
            throw new IOException("no registered remote original for gallery item");
        }
        return restoreOriginal(config, record, destination);
    }

    /**
     * Restore a registered original when a download mapping has not yet received a gallery row
     * identity. The immutable record must still belong to this target and remain in the registry.
     */
    public File restoreOriginal(Config config, Record requested, File destination)
            throws IOException {
        if (destination == null) {
            throw new IOException("restore destination is missing");
        }
        if (requested == null || !targetKey(config).equals(requested.target)) {
            throw new IOException("no registered remote original for gallery item");
        }
        Record record;
        synchronized (mLock) {
            requireHealthyLocked();
            record = mRecords.get(recordKey(requested));
        }
        if (record == null || !targetKey(config).equals(record.target)) {
            throw new IOException("registered remote original is no longer available");
        }
        PathLease restoreLease = lockPath(destination, true);
        try {
        RestoreDestinationSnapshot initial = snapshotRestoreDestination(record, destination);
        if (initial.original) {
            restoreOriginalTimestamp(record, destination);
            saveRestoredLocal(config, record, destination);
            return destination;
        }
        File parent = destination.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create restore destination directory");
        }
        File staged = File.createTempFile(".mgs-original-", ".tmp", parent);
        WebDavClient client = new WebDavClient(config.getBaseUrl(), config.getUsername(),
                config.getPassword());
        try {
            client.get(record.remotePath, staged);
            if (staged.length() != record.originalLength
                    || !record.sha256.equals(sha256(staged))) {
                throw new IOException("remote original failed length or SHA-256 verification");
            }
            RestoreDestinationSnapshot beforePublish = snapshotRestoreDestination(record,
                    destination);
            if (beforePublish.original) {
                restoreOriginalTimestamp(record, destination);
                saveRestoredLocal(config, record, destination);
                return destination;
            }
            if (!canPublishOver(initial, beforePublish)) {
                throw new IOException("restore destination changed during download");
            }
            try {
                if (recheckRestoreDestinationBeforePublish(record, destination, beforePublish)) {
                    restoreOriginalTimestamp(record, destination);
                    saveRestoredLocal(config, record, destination);
                    return destination;
                }
                Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailure) {
                try {
                    if (recheckRestoreDestinationBeforePublish(record, destination,
                            beforePublish)) {
                        restoreOriginalTimestamp(record, destination);
                        saveRestoredLocal(config, record, destination);
                        return destination;
                    }
                    Files.move(staged.toPath(), destination.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException moveFailure) {
                    moveFailure.addSuppressed(atomicFailure);
                    throw moveFailure;
                }
            }
            restoreOriginalTimestamp(record, destination);
            saveRestoredLocal(config, record, destination);
            return destination;
        } finally {
            client.close();
            if (staged.exists() && !staged.delete()) {
                Logger.d("event=restore_temp_cleanup_failed path=" + staged.getAbsolutePath());
            }
        }
        } finally {
            restoreLease.close();
        }
    }

    /** Snapshot only missing paths or exact registered bytes before a restore can replace them. */
    private static RestoreDestinationSnapshot snapshotRestoreDestination(Record record,
            File destination) throws IOException {
        if (!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return RestoreDestinationSnapshot.missing();
        }
        if (!destination.isFile()) {
            throw new IOException("restore destination contains unrecognized local bytes");
        }
        long length = destination.length();
        long modified = destination.lastModified();
        String digest = sha256(destination);
        if (!destination.isFile() || destination.length() != length
                || destination.lastModified() != modified) {
            throw new IOException("restore destination changed during verification");
        }
        if (length == record.originalLength && record.sha256.equals(digest)) {
            return RestoreDestinationSnapshot.original();
        }
        Long derivedLength = record.derivedHashes.get(digest);
        if (!containsLocalPath(record, canonical(destination)) || derivedLength == null
                || derivedLength.longValue() != length) {
            throw new IOException("restore destination contains unrecognized local bytes");
        }
        return RestoreDestinationSnapshot.derived(digest, length, modified);
    }

    /** Recheck immediately before each move, accepting only unchanged bytes or a finished restore. */
    private static boolean recheckRestoreDestinationBeforePublish(Record record, File destination,
            RestoreDestinationSnapshot expected) throws IOException {
        RestoreDestinationSnapshot current = snapshotRestoreDestination(record, destination);
        if (current.original) return true;
        if (!sameRestoreDestination(expected, current)) {
            throw new IOException("restore destination changed before publish");
        }
        return false;
    }

    private static boolean canPublishOver(RestoreDestinationSnapshot initial,
            RestoreDestinationSnapshot current) {
        if (initial.missing) {
            // A concurrently created copy is replaceable only when it is already registered.
            return current.missing || current.derived;
        }
        return sameRestoreDestination(initial, current);
    }

    private static boolean sameRestoreDestination(RestoreDestinationSnapshot first,
            RestoreDestinationSnapshot second) {
        if (first.missing || second.missing) return first.missing && second.missing;
        return first.derived && second.derived && first.length == second.length
                && first.modified == second.modified && first.sha256.equals(second.sha256);
    }

    private static final class RestoreDestinationSnapshot {
        final boolean missing;
        final boolean original;
        final boolean derived;
        final long length;
        final long modified;
        final String sha256;

        private RestoreDestinationSnapshot(boolean missing, boolean original, boolean derived,
                long length, long modified, String sha256) {
            this.missing = missing;
            this.original = original;
            this.derived = derived;
            this.length = length;
            this.modified = modified;
            this.sha256 = sha256;
        }

        static RestoreDestinationSnapshot missing() {
            return new RestoreDestinationSnapshot(true, false, false, 0L, 0L, "");
        }

        static RestoreDestinationSnapshot original() {
            return new RestoreDestinationSnapshot(false, true, false, 0L, 0L, "");
        }

        static RestoreDestinationSnapshot derived(String sha256, long length, long modified) {
            return new RestoreDestinationSnapshot(false, false, true, length, modified, sha256);
        }
    }

    private static void restoreOriginalTimestamp(Record record, File destination)
            throws IOException {
        if (record.originalLocalMtime > 0L
                && destination.lastModified() != record.originalLocalMtime
                && !destination.setLastModified(record.originalLocalMtime)) {
            throw new IOException("cannot restore original file modification time");
        }
    }

    private void saveRestoredLocal(Config config, Record record, File destination)
            throws IOException {
        synchronized (mLock) {
            Record latest = mRecords.get(recordKey(record));
            if (latest != null) {
                Record restored = latest.withRestoredLocal(destination);
                mRecords.put(recordKey(restored), restored);
                saveLocked();
            }
        }
    }

    /** Persist protection before a hook publishes a generated or in-place optimized copy. */
    public boolean registerDerivedPath(Config config, String rowId, String sha1, File path)
            throws IOException {
        return registerDerivedPath(config, rowId, sha1, path, true);
    }

    private boolean registerDerivedPath(Config config, String rowId, String sha1, File path,
            boolean fingerprintCurrentBytes) throws IOException {
        if (path == null) {
            return false;
        }
        String target = targetKey(config);
        String canonicalPath = canonical(path);
        synchronized (mLock) {
            requireHealthyLocked();
            Record record = findLocked(target, rowId, sha1);
            if (record == null) {
                return false;
            }
            Record updated = record.withDerivedPath(canonicalPath);
            if (fingerprintCurrentBytes && path.isFile()) {
                updated = updated.withDerivedHash(sha256(path), path.length());
            }
            mRecords.put(recordKey(updated), updated);
            saveLocked();
            return true;
        }
    }

    /**
     * Take the shared per-path write lock and persist protection before replacing a source file.
     * The caller must close the returned lease after the entire mutation/publish operation.
     */
    public PathLease beginDerivedMutation(Config config, String rowId, String sha1, File path)
            throws IOException {
        if (path == null) return null;
        PathLease lease = lockPath(path, true);
        boolean marked = false;
        try {
            if (!registerDerivedPath(config, rowId, sha1, path, false)) return null;
            marked = true;
            return lease;
        } finally {
            if (!marked) lease.close();
        }
    }

    /** Read lease held from upload classification until the PUT has finished reading this path. */
    public PathLease lockForUpload(File path) throws IOException {
        if (path == null) throw new IOException("upload path is missing");
        return lockPath(path, false);
    }

    public static final class PathLease implements AutoCloseable {
        private final ReentrantReadWriteLock lock;
        private final boolean write;
        private boolean closed;

        private PathLease(ReentrantReadWriteLock lock, boolean write) {
            this.lock = lock;
            this.write = write;
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            if (write) lock.writeLock().unlock(); else lock.readLock().unlock();
        }
    }

    private static PathLease lockPath(File path, boolean write) throws IOException {
        String canonical = canonical(path);
        ReentrantReadWriteLock lock = PATH_LOCKS.computeIfAbsent(canonical,
                ignored -> new ReentrantReadWriteLock(true));
        if (write) lock.writeLock().lock(); else lock.readLock().lock();
        return new PathLease(lock, write);
    }

    /**
     * Fail-closed upload classifier. It blocks reserved download mirrors, registered previews,
     * derived paths, and any original path whose bytes changed since the verified upload.
     */
    public boolean isProtectedUpload(File candidate, String rowId, String sha1) {
        if (candidate == null) {
            return true;
        }
        String path;
        try {
            path = canonical(candidate);
        } catch (IOException e) {
            return true;
        }
        if (isReservedDownloadPath(path)) {
            return true;
        }
        synchronized (mLock) {
            if (!mHealthy) {
                return true;
            }
            for (Record record : mRecords.values()) {
                if (record.derivedPaths.contains(path)
                        || path.equals(record.previewLocalPath)) {
                    return true;
                }
                if (path.equals(record.originalLocalPath)) {
                    if (!candidate.isFile() || candidate.length() != record.originalLength) {
                        return true;
                    }
                    // Catch same-size replacements too. The worker calls this again immediately
                    // before PUT, so an already queued optimized copy cannot slip through.
                    try {
                        if (!record.sha256.equals(sha256(candidate))) {
                            return true;
                        }
                    } catch (IOException e) {
                        return true;
                    }
                }
            }
            if (candidate.isFile()) {
                long length = candidate.length();
                String digest = null;
                for (Record record : mRecords.values()) {
                    for (Map.Entry<String, Long> entry : record.derivedHashes.entrySet()) {
                        if (entry.getValue() != null && entry.getValue() == length) {
                            try {
                                if (digest == null) digest = sha256(candidate);
                            } catch (IOException e) {
                                return true;
                            }
                            if (entry.getKey().equals(digest)) return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Exact reserved mirror root; this remains a safety net even if registry writes fail. */
    public static boolean isReservedDownloadPath(String canonicalPath) {
        if (canonicalPath == null) {
            return false;
        }
        String path = normalizeStorageAlias(canonicalPath);
        String root = "/storage/emulated/0/dcim/migallerysync";
        return path.equals(root) || path.startsWith(root + "/");
    }

    /** Pure policy helper exposed package-private for unit tests. */
    static boolean identityMatches(Record record, String rowId, String sha1) {
        if (record == null) {
            return false;
        }
        String id = clean(rowId);
        String hash = clean(sha1);
        boolean idMatches = id != null && (id.equals(record.rowId)
                || record.identities.contains("r:" + id));
        boolean shaMatches = hash != null && (hash.equalsIgnoreCase(safe(record.sha1))
                || record.identities.contains("s:" + hash.toLowerCase(Locale.US)));
        return (id != null || hash != null) && (idMatches || shaMatches);
    }

    private void register(Config config, String remotePath, File source, File preview,
            File localOriginal, String rowId, String sha1) throws IOException {
        if (config == null || remotePath == null || remotePath.isEmpty() || source == null
                || !source.isFile() || source.length() <= 0) {
            throw new IOException("cannot register missing original");
        }
        long length = source.length();
        String digest = sha256(source);
        String originalPath = localOriginal == null ? null : canonical(localOriginal);
        String previewPath = preview == null ? null : canonical(preview);
        Set<String> derived = previewPath == null
                ? Collections.emptySet() : Collections.singleton(previewPath);
        persist(new Record(targetKey(config), remotePath, length, digest, originalPath,
                previewPath, rowId, sha1,
                localOriginal == null ? 0L : localOriginal.lastModified(), derived));
    }

    private void persist(Record record) throws IOException {
        synchronized (mLock) {
            requireHealthyLocked();
            if (!mRecords.containsKey(recordKey(record)) && mRecords.size() >= MAX_RECORDS) {
                throw new IOException("original registry is full");
            }
            String key = recordKey(record);
            Record existing = mRecords.get(key);
            mRecords.put(key, existing == null ? record : merge(existing, record));
            saveLocked();
        }
    }

    private static Record merge(Record old, Record incoming) {
        Set<String> derived = new LinkedHashSet<>(old.derivedPaths);
        derived.addAll(incoming.derivedPaths);
        Set<String> identities = new LinkedHashSet<>(old.identities);
        identities.addAll(incoming.identities);
        Set<String> aliases = new LinkedHashSet<>(old.localAliases);
        aliases.addAll(incoming.localAliases);
        if (old.originalLocalPath != null) aliases.add(old.originalLocalPath);
        if (old.previewLocalPath != null) { aliases.add(old.previewLocalPath); derived.add(old.previewLocalPath); }
        if (incoming.originalLocalPath != null) aliases.add(incoming.originalLocalPath);
        if (incoming.previewLocalPath != null) { aliases.add(incoming.previewLocalPath); derived.add(incoming.previewLocalPath); }
        Map<String, Long> hashes = new LinkedHashMap<>(old.derivedHashes);
        hashes.putAll(incoming.derivedHashes);
        return new Record(incoming.target, incoming.remotePath, incoming.originalLength,
                incoming.sha256, incoming.originalLocalPath == null ? old.originalLocalPath
                        : incoming.originalLocalPath,
                incoming.previewLocalPath == null ? old.previewLocalPath
                        : incoming.previewLocalPath,
                incoming.rowId == null ? old.rowId : incoming.rowId,
                incoming.sha1 == null ? old.sha1 : incoming.sha1,
                incoming.originalLocalMtime == 0L ? old.originalLocalMtime
                        : incoming.originalLocalMtime,
                derived, identities, aliases, hashes);
    }

    private void saveLocked() throws IOException {
        File tmp = new File(mFile.getParentFile(), FILE_NAME + ".tmp");
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            JSONArray records = new JSONArray();
            for (Record record : mRecords.values()) {
                JSONObject item = new JSONObject();
                item.put("target", record.target);
                item.put("remote", record.remotePath);
                item.put("length", record.originalLength);
                item.put("sha256", record.sha256);
                item.put("original", nullable(record.originalLocalPath));
                item.put("preview", nullable(record.previewLocalPath));
                item.put("row", nullable(record.rowId));
                item.put("sha1", nullable(record.sha1));
                item.put("mtime", record.originalLocalMtime);
                JSONArray derived = new JSONArray();
                for (String path : record.derivedPaths) {
                    derived.put(path);
                }
                item.put("derived", derived);
                JSONArray aliases = new JSONArray();
                for (String path : record.localAliases) aliases.put(path);
                item.put("aliases", aliases);
                JSONArray identities = new JSONArray();
                for (String identity : record.identities) identities.put(identity);
                item.put("identities", identities);
                JSONArray fingerprints = new JSONArray();
                for (Map.Entry<String, Long> entry : record.derivedHashes.entrySet()) {
                    JSONObject fingerprint = new JSONObject();
                    fingerprint.put("sha256", entry.getKey());
                    fingerprint.put("length", entry.getValue());
                    fingerprints.put(fingerprint);
                }
                item.put("derived_hashes", fingerprints);
                records.put(item);
            }
            root.put("records", records);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            try {
                Files.move(tmp.toPath(), mFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailure) {
                Files.move(tmp.toPath(), mFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            mHealthy = true;
        } catch (Throwable t) {
            mHealthy = false;
            try {
                Files.deleteIfExists(tmp.toPath());
            } catch (Throwable ignored) {
                // The failed registry remains fail-closed for uploads.
            }
            throw new IOException("cannot persist original registry", t);
        }
    }

    private void load() {
        if (!mFile.exists()) {
            return;
        }
        try {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(mFile.toPath()),
                    StandardCharsets.UTF_8));
            JSONArray records = root.optJSONArray("records");
            if (records == null || records.length() > MAX_RECORDS) {
                throw new IOException("invalid original registry record list");
            }
            for (int i = 0; i < records.length(); i++) {
                JSONObject item = records.getJSONObject(i);
                Set<String> derived = new LinkedHashSet<>();
                JSONArray paths = item.optJSONArray("derived");
                if (paths != null) {
                    for (int j = 0; j < paths.length(); j++) {
                        String path = clean(paths.optString(j, ""));
                        if (path != null) {
                            derived.add(path);
                        }
                    }
                }
                Set<String> aliases = readStringSet(item.optJSONArray("aliases"));
                Set<String> identities = readStringSet(item.optJSONArray("identities"));
                Map<String, Long> hashes = new LinkedHashMap<>();
                JSONArray fingerprints = item.optJSONArray("derived_hashes");
                if (fingerprints != null) {
                    for (int j = 0; j < fingerprints.length(); j++) {
                        JSONObject fingerprint = fingerprints.optJSONObject(j);
                        if (fingerprint == null) continue;
                        String hash = clean(fingerprint.optString("sha256", ""));
                        long length = fingerprint.optLong("length", -1L);
                        if (hash != null && hash.length() == 64 && length > 0) {
                            hashes.put(hash, length);
                        }
                    }
                }
                Record record = new Record(item.optString("target", ""),
                        item.optString("remote", ""), item.optLong("length", 0L),
                        item.optString("sha256", ""), clean(item.optString("original", "")),
                        clean(item.optString("preview", "")),
                        clean(item.optString("row", "")), clean(item.optString("sha1", "")),
                        item.optLong("mtime", 0L), derived, identities, aliases, hashes);
                if (record.target.isEmpty() || record.remotePath.isEmpty()
                        || record.originalLength <= 0 || record.sha256.length() != 64) {
                    throw new IOException("invalid original registry record");
                }
                String key = recordKey(record);
                Record old = mRecords.get(key);
                mRecords.put(key, old == null ? record : merge(old, record));
            }
        } catch (Throwable t) {
            mHealthy = false;
            Logger.e("event=original_store_load_failed", t);
        }
    }

    private void requireHealthyLocked() throws IOException {
        if (!mHealthy) {
            throw new IOException("original registry is unavailable; uploads are fail-closed");
        }
    }

    private Record findLocked(String target, String rowId, String sha1) {
        Record latest = null;
        for (Record record : mRecords.values()) {
            if (target.equals(record.target) && identityMatches(record, rowId, sha1)) {
                latest = record;
            }
        }
        return latest;
    }

    private boolean verifyRemote(Record record, Config config) throws IOException {
        File tempDir = new File(mFile.getParentFile(), "original_verify");
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IOException("cannot create original verification directory");
        }
        File remoteCopy = File.createTempFile("verify-", ".tmp", tempDir);
        WebDavClient client = new WebDavClient(config.getBaseUrl(), config.getUsername(),
                config.getPassword());
        try {
            client.get(record.remotePath, remoteCopy);
            return remoteCopy.length() == record.originalLength
                    && record.sha256.equals(sha256(remoteCopy));
        } finally {
            client.close();
            if (remoteCopy.exists() && !remoteCopy.delete()) {
                Logger.d("event=original_verify_temp_cleanup_failed path="
                        + remoteCopy.getAbsolutePath());
            }
        }
    }

    private static boolean containsLocalPath(Record record, String path) {
        return record.localAliases.contains(path) || path.equals(record.originalLocalPath)
                || path.equals(record.previewLocalPath) || record.derivedPaths.contains(path);
    }

    private static Set<String> readStringSet(JSONArray values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (int i = 0; i < values.length(); i++) {
                String value = clean(values.optString(i, ""));
                if (value != null) result.add(value);
            }
        }
        return result;
    }

    private static String recordKey(Record record) {
        return record.target + "\n" + record.remotePath + "\n" + record.sha256;
    }

    private static String canonical(File file) throws IOException {
        return file.getCanonicalPath();
    }

    private static String nullable(String value) {
        return value == null ? JSONObject.NULL.toString() : value;
    }

    private static String clean(String value) {
        return value == null || value.trim().isEmpty() || "null".equals(value)
                ? null : value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safeBaseUrl(String value) {
        if (value == null) {
            return "";
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
            String host = uri.getHost() == null ? Config.safeHost(value) : uri.getHost().toLowerCase(Locale.US);
            int port = uri.getPort();
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            while (path.endsWith("/") && !path.isEmpty()) {
                path = path.substring(0, path.length() - 1);
            }
            return scheme + "://" + host + (port < 0 ? "" : ":" + port) + path;
        } catch (Throwable ignored) {
            return Config.safeHost(value);
        }
    }

    private static String normalizeStorageAlias(String value) {
        String path = value.replace('\\', '/');
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String lower = path.toLowerCase(Locale.US);
        if (lower.equals("/sdcard") || lower.startsWith("/sdcard/")) {
            path = "/storage/emulated/0" + path.substring("/sdcard".length());
        } else if (lower.equals("/mnt/sdcard") || lower.startsWith("/mnt/sdcard/")) {
            path = "/storage/emulated/0" + path.substring("/mnt/sdcard".length());
        } else if (lower.equals("/storage/emulated/legacy")
                || lower.startsWith("/storage/emulated/legacy/")) {
            path = "/storage/emulated/0" + path.substring("/storage/emulated/legacy".length());
        } else if (lower.equals("/storage/self/primary")
                || lower.startsWith("/storage/self/primary/")) {
            path = "/storage/emulated/0" + path.substring("/storage/self/primary".length());
        }
        return path.toLowerCase(Locale.US);
    }

    static String sha256(File file) throws IOException {
        MessageDigest digest = newDigest();
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private static boolean isSha256(String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    static String sha256(byte[] value) {
        return hex(newDigest().digest(value));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by Android", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return out.toString();
    }
}
