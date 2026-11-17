package com.tongluosao.gallerysync.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import com.tongluosao.gallerysync.core.Config;
import com.tongluosao.gallerysync.core.DownloadQueue;
import com.tongluosao.gallerysync.core.InplacePreviewWriter;
import com.tongluosao.gallerysync.core.InplaceSlimPolicy;
import com.tongluosao.gallerysync.core.Logger;
import com.tongluosao.gallerysync.core.OriginalStore;
import com.tongluosao.gallerysync.core.UploadQueue;
import com.tongluosao.gallerysync.core.WebDavClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import io.github.libxposed.api.XposedInterface;

/** Keeps a verified WebDAV original safe while Gallery's storage slim operation replaces it. */
final class SlimHook {

    private static final String CLS_CONTROLLER = "com.miui.gallery.cleaner.slim.SlimController";
    private static final String CLS_MEDIA_ITEM = CLS_CONTROLLER + "$MediaItem";
    private static final String CLS_CLOUD_CONTRACT = "com.miui.gallery.provider.GalleryContract$Cloud";

    /**
     * {@link #runSlim} 的返回值：这张不属于模块管理，调用方必须把它原样交还给相册自己的流程
     * （命中条件是"没登记过、也不在当前目标的保护名单里"）。真实结果只有 0 和正数两种。
     */
    static final long PASSTHROUGH = Long.MIN_VALUE;

    private final XposedInterface mXposed;
    private final ClassLoader mClassLoader;
    private final Object mConfigLock = new Object();
    private volatile Config mConfig;
    private volatile SlimScannerHook mScannerHook;
    private volatile BooleanSupplier mVideoRestorePolicy = () -> false;
    /** 宿主 {@code SlimController.saveToDB(int,String,String)}，install() 时解析一次。 */
    private volatile Method mSaveToDb;

    /** 上一张处理完的时刻，用来量相册自己在这张上花的时间（见 slim_timing 的 gap）。 */
    private static volatile long sLastDoneNanos = 0L;

    SlimHook(XposedInterface xposed, ClassLoader classLoader) {
        mXposed = xposed;
        mClassLoader = classLoader;
    }

    void setConfig(Config config) {
        synchronized (mConfigLock) {
            mConfig = config;
            SlimScannerHook scanner = mScannerHook;
            if (scanner != null) scanner.setConfig(config);
        }
    }

    void setVideoRestorePolicy(BooleanSupplier policy) {
        synchronized (mConfigLock) {
            mVideoRestorePolicy = policy == null ? () -> false : policy;
            SlimScannerHook scanner = mScannerHook;
            if (scanner != null) scanner.setVideoRestorePolicy(mVideoRestorePolicy);
        }
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
            mSaveToDb = saveToDb;

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
                        // 单张流程抽成了 runSlim：模块自己的工作线程池（SlimWorker）也调它，
                        // 两条入口共用同一份校验/编码/租约/发布/删除逻辑，日志一字不差。
                        long saved = runSlim(item);
                        if (saved != PASSTHROUGH) {
                            return saved;
                        }
                        return chain.proceed();
                    });
            Logger.i("event=hook_registered method=SlimController.handleSlim(MediaItem,ArrayList)");

            SlimScannerHook scanner = new SlimScannerHook(mXposed, mClassLoader);
            scanner.setConfig(mConfig);
            scanner.setVideoRestorePolicy(mVideoRestorePolicy);
            scanner.install();
            mScannerHook = scanner;
            // 把宿主 ClassLoader 与本实例交给工作线程池（SlimWorker 自己驱动队列时要用）
            SlimWorker.attach(this, mClassLoader);
        } catch (ClassNotFoundException e) {
            Logger.e("event=slim_hook_class_not_found class=" + CLS_CONTROLLER, e);
        } catch (NoSuchMethodException e) {
            Logger.e("event=slim_hook_method_not_found method=SlimController.handleSlim/saveToDB", e);
        } catch (Throwable t) {
            Logger.e("event=slim_hook_failed", t);
        }
    }

    /**
     * 跑完一张的完整流程：校验远端 → 原地分支 / 侧车分支。
     *
     * <p>两条分支共用前面整段安全闸门（身份、target、远端已校验、本地原图未变），分流点见
     * {@link #useInplace}：
     * <ul>
     *   <li>HEIC/JPG 家族：编码到同目录临时件 → {@link InplacePreviewWriter#publish} 覆盖原路径
     *       （租约 → 意图 → 改名 → 复核 → DB）；<b>不删原图</b>；</li>
     *   <li>视频与 png/webp/gif 等：保持老侧车流程逐字不变（发布副本 → saveToDB → 删原图 → 回滚）。</li>
     * </ul>
     *
     * <p>相册自己的 {@code SlimController.handleSlim} 与本模块的工作线程池（{@link SlimWorker}）
     * 都走这一份实现：能省下字节就返回省下的字节，跳过或失败返回 0，
     * 而"这张根本不归模块管"时返回 {@link #PASSTHROUGH}，由调用方决定怎么交还给相册。
     */
    long runSlim(SlimItem item) {
        Method saveToDb = mSaveToDb;
        if (saveToDb == null) {
            Logger.e("event=slim_save_db_unavailable");
            return PASSTHROUGH;
        }
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
        boolean verifiedByMigration = false;
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
                boolean mayMigrateLegacy = item.serverType == 1
                        ? hasIdentity(item) : item.serverType == 2
                        && mayMigrateLegacyVideo(original, item);
                if (!mayMigrateLegacy) {
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
                    verifiedByMigration = true;
                } catch (Throwable t) {
                    Logger.e("event=slim_legacy_migration_failed path=" + item.path, t);
                    return 0L;
                }
            } else {
                // Do not intercept ordinary Gallery cleanup. A fail-closed registry still marks
                // every path protected, which distinguishes it from an unregistered original.
                try {
                    if (!store.isProtectedUpload(original, item.rowId, item.sha1)) {
                        // 普通相册清理：交还给宿主自己的流程（工作线程池里没有 chain 可用）
                        return PASSTHROUGH;
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

        if (item.serverType != 1 && item.serverType != 2) {
            Logger.i("event=slim_skipped reason=unsupported_media_type type=" + item.serverType
                    + " path=" + item.path);
            return 0L;
        }
        if (item.serverType == 2 && (!videoRestoreReady()
                || item.sha1 == null || item.sha1.isEmpty()
                || item.fileName == null || item.fileName.isEmpty())) {
            Logger.i("event=slim_skipped reason=video_restore_not_ready_or_metadata_missing path="
                    + item.path);
            return 0L;
        }
        if (!localOriginalMatches(original, record)) {
            Logger.w("event=slim_skipped reason=original_changed_or_protected path=" + item.path);
            return 0L;
        }

        long t0 = System.nanoTime();
        long lastDone = sLastDoneNanos;
        long gapMillis = lastDone > 0L ? (t0 - lastDone) / 1_000_000L : -1L;
        long tVerifyEnd = 0L;
        long tEncodeEnd = 0L;
        long tLeaseEnd = 0L;
        long tPublishEnd = 0L;
        long tDbEnd = 0L;
        try {
            if (!verifiedByMigration
                    && !store.ensureVerifiedRemoteOriginal(config, item.rowId, item.sha1)) {
                Logger.w("event=slim_skipped reason=remote_original_unverified path=" + item.path);
                return 0L;
            }
        } catch (Throwable t) {
            Logger.e("event=slim_remote_verify_failed path=" + item.path, t);
            return 0L;
        }
        tVerifyEnd = System.nanoTime();

        // 分流点：HEIC/JPG 家族走原地覆盖（原路径原文件名），其余容器与视频继续走侧车方案。
        // 两条分支共用上面这一整段安全闸门（远端已校验、身份对得上、本地原图未变）。
        if (useInplace(item, original, config)) {
            return runInplace(item, original, record, store, config, saveToDb, context, t0,
                    gapMillis, tVerifyEnd);
        }

        // 下载高清图与空间优化共用同一个副本路径：隐藏目录里一个与原图同名的文件。
        File preview;
        try {
            preview = item.serverType == 1
                    ? DownloadQueue.previewPathFor(original, config.isPreserveHeicFormat())
                    : DownloadQueue.videoPosterPathFor(original);
        } catch (Throwable t) {
            Logger.w("event=slim_skipped reason=preview_path_unavailable path=" + item.path);
            return 0L;
        }
        if (item.serverType == 1 && isSameFile(preview, original)) {
            // 不可压缩的类型没有同名副本可写，绝不覆盖原图字节，交给相册自己的流程。
            Logger.i("event=slim_skipped reason=unsupported_preview_format path=" + item.path);
            return 0L;
        }
        File parent = preview.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            Logger.w("event=slim_skipped reason=preview_directory_unavailable path=" + item.path);
            return 0L;
        }
        // 目标位置上只允许覆盖这张照片自己登记过的旧副本；任何别的文件都不碰。
        if (preview.exists() && !store.isRegisteredDerivedCopy(record, preview)) {
            Logger.w("event=slim_skipped reason=destination_occupied path=" + item.path
                    + " preview=" + preview.getAbsolutePath());
            return 0L;
        }
        File staged = null;
        OriginalStore.PathLease originalLease = null;
        OriginalStore.PathLease previewLease = null;
        // 只有三步都成立才算落地：文件已发布、DB 已确认、原图已删除。
        // 中间任何一步失败都把刚发布的副本删掉，原图字节保持不动。
        boolean previewReplaced = false;
        boolean previewCommitted = false;
        try {
            staged = DownloadQueue.stageBesideDestination(preview);
            boolean previewCreated = item.serverType == 1
                    ? DownloadQueue.createCompressedPreview(original, staged, original.getName(), config)
                    : createNativeVideoPoster(original, staged, parent, item);
            if (!previewCreated
                    || !staged.isFile() || staged.length() <= 0
                    || staged.length() >= original.length()) {
                Logger.i("event=slim_skipped reason=preview_not_smaller path=" + item.path);
                return 0L;
            }
            tEncodeEnd = System.nanoTime();

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

            // Persist the final path and hold its write lease before publishing the preview. The
            // post-move registration stores its fingerprint for future upload classification.
            previewLease = store.beginDerivedMutation(config, item.rowId, item.sha1, preview);
            if (previewLease == null) {
                Logger.w("event=slim_skipped reason=preview_registration_failed path=" + item.path);
                return 0L;
            }
            tLeaseEnd = System.nanoTime();

            synchronized (mConfigLock) {
                if (!isCurrentTargetLocked(config)) {
                    Logger.w("event=slim_skipped reason=webdav_target_changed_before_publish path="
                            + item.path);
                    return 0L;
                }

                publish(staged, preview);
                previewReplaced = true;
                PreviewFileTime.copyModifiedTime(original, preview);
                tPublishEnd = System.nanoTime();
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
                previewCommitted = true;
                tDbEnd = System.nanoTime();
                // Native publication may touch the thumbnail; keep screenshot chronology even
                // when no EXIF capture date exists.
                PreviewFileTime.copyModifiedTime(original, preview);

                // Keep the config lock through DB publication and deletion. A config callback
                // cannot switch the active restore target between this check and the irreversible
                // source deletion.
                if (!isCurrentTargetLocked(config)) {
                    Logger.w("event=slim_skipped reason=webdav_target_changed_before_delete path="
                            + item.path);
                    return 0L;
                }
                if (item.serverType == 2 && !videoRestoreReady()) {
                    Logger.w("event=slim_video_restore_lost_before_delete path=" + item.path);
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
            }
        } catch (Throwable t) {
            Logger.e("event=slim_optimization_failed path=" + item.path, t);
            // A published preview remains registered and the original remains until the DB has
            // been confirmed to point at it.
            return 0L;
        } finally {
            long tEnd = System.nanoTime();
            sLastDoneNanos = tEnd;
            Logger.i("event=slim_timing stage=sidecar total=" + elapsedMillis(t0, tEnd)
                    + " verify=" + elapsedMillis(t0, tVerifyEnd)
                    + " encode=" + elapsedMillis(tVerifyEnd, tEncodeEnd)
                    + " lease=" + elapsedMillis(tEncodeEnd, tLeaseEnd)
                    + " publish=" + elapsedMillis(tLeaseEnd, tPublishEnd)
                    + " db=" + elapsedMillis(tPublishEnd, tDbEnd)
                    + " tail=" + elapsedMillis(tDbEnd, tEnd)
                + " gap=" + gapMillis
                    + " path=" + item.path);
            if (staged != null && staged.exists() && !staged.delete()) {
                Logger.d("event=slim_temp_cleanup_failed path=" + staged.getAbsolutePath());
            }
            // 发布成功但 DB 没有确认：撤掉这次写下的副本，原图仍在，照片不会悬空。
            if (previewReplaced && !previewCommitted && !isSameFile(preview, original)
                    && preview.isFile() && !preview.delete()) {
                Logger.d("event=slim_rollback_cleanup_failed path=" + preview.getAbsolutePath());
            }
            if (previewLease != null) previewLease.close();
            if (originalLease != null) originalLease.close();
        }
    }

    private static long elapsedMillis(long from, long to) {
        if (from <= 0L || to <= 0L || to < from) return -1L;
        return (to - from) / 1_000_000L;
    }

    /**
     * 这张图片能不能原地覆盖。
     *
     * <p>除了容器本身（HEIC/JPG 家族），HEIF 还多一个前提：“保留 HEIC 格式”必须开着。
     * 关掉时老逻辑的编码器会产出 JPEG，而原地覆盖不允许改扩展名 —— 把 JPEG 写进 {@code .heic}
     * 就是容器不匹配，相册与远端都会把它当坏图，所以这种情况继续走侧车。
     */
    private static boolean useInplace(SlimItem item, File original, Config config) {
        if (item.serverType != 1 || original == null || config == null
                || !InplaceSlimPolicy.canOverwriteInPlace(original.getName())) {
            return false;
        }
        return !"heic".equals(InplaceSlimPolicy.targetFormat(original.getName()))
                || config.isPreserveHeicFormat();
    }

    /**
     * 原地覆盖分支：编码到同目录临时件，再交给 {@link InplacePreviewWriter} 按固定顺序提交。
     *
     * <p>相对侧车分支少了两件事：预览不再搬进隐藏目录（目标就是原文件本身），也<b>不再删原图</b>
     * —— 覆盖之后原路径上那份字节就是预览，删掉等于把照片弄丢。DB 也反过来写：
     * {@code localFile=原路径}、{@code thumbnailFile=""}，相册因此认为原图仍在本机。
     */
    private long runInplace(SlimItem item, File original, OriginalStore.Record record,
            OriginalStore store, Config config, Method saveToDb, Context context, long t0,
            long gapMillis, long tVerifyEnd) {
        InplaceCallbacks callbacks = new InplaceCallbacks(item, original, record, store, config,
                saveToDb, context);
        InplacePreviewWriter.Outcome outcome = null;
        long tEncodeEnd = 0L;
        long tPublishEnd = 0L;
        long result = 0L;
        try {
            File staged = stagedInplacePreview(item, original, config, callbacks);
            if (staged == null) {
                return 0L;
            }
            String previewSha = InplacePreviewWriter.sha256Of(staged);
            if (previewSha == null) {
                Logger.w("event=slim_inplace_failed row=" + item.rowId
                        + " reason=preview_hash_failed path=" + item.path);
                return 0L;
            }
            tEncodeEnd = System.nanoTime();
            InplacePreviewWriter.Request request = new InplacePreviewWriter.Request();
            request.original = InplaceSlimPolicy.targetPath(original);
            request.originalLength = record.originalLength;
            request.originalSha256 = record.sha256;
            request.staged = staged;
            request.previewLength = staged.length();
            request.previewSha256 = previewSha;
            request.previewFormat = InplaceSlimPolicy.targetFormat(original.getName());
            request.rowId = item.rowId;
            // 与侧车分支一样：从这里到 DB 提交不允许切换还原目标，否则可能按旧目标覆盖本地原图
            synchronized (mConfigLock) {
                if (!isCurrentTargetLocked(config)) {
                    Logger.w("event=slim_inplace_failed row=" + item.rowId
                            + " reason=webdav_target_changed_before_publish path=" + item.path);
                    return 0L;
                }
                outcome = InplacePreviewWriter.publish(request, callbacks);
            }
            tPublishEnd = System.nanoTime();
            if (outcome.success) {
                result = Math.max(0L, record.originalLength - request.previewLength);
                Logger.i("event=slim_inplace_done row=" + item.rowId + " saved=" + result
                        + " path=" + item.path);
            } else {
                Logger.w("event=slim_inplace_failed row=" + item.rowId + " reason="
                        + outcome.reason + " path=" + item.path);
            }
        } catch (Throwable t) {
            Logger.e("event=slim_inplace_failed row=" + item.rowId + " reason=exception path="
                    + item.path, t);
        } finally {
            logInplaceTiming(item, t0, gapMillis, tVerifyEnd, tEncodeEnd, tPublishEnd, callbacks);
            callbacks.close();
        }
        return result;
    }

    /**
     * 把压缩预览编码到“与原文件同目录”的隐藏临时件。
     *
     * <p>同目录是硬要求：跨目录的 atomic rename 不成立，那就不再是原子发布。编码器按原文件名
     * 选容器（heic/heif 出 HEIF、jpg/jpeg 出 JPEG），调用前已经确认过扩展名与编码器匹配。
     * 失败返回 {@code null}，原因已经打进 {@code slim_inplace_failed}。
     */
    private File stagedInplacePreview(SlimItem item, File original, Config config,
            InplaceCallbacks callbacks) {
        File parent = original.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs()
                && !parent.isDirectory())) {
            Logger.w("event=slim_inplace_failed row=" + item.rowId
                    + " reason=target_directory_unavailable path=" + item.path);
            return null;
        }
        File staged = new File(parent, "." + original.getName() + ".mgs-tmp-"
                + UUID.randomUUID());
        callbacks.staged = staged;
        boolean encoded = DownloadQueue.createCompressedPreview(original, staged,
                original.getName(), config);
        if (!encoded || !staged.isFile() || staged.length() <= 0L
                || staged.length() >= original.length()) {
            Logger.i("event=slim_inplace_failed row=" + item.rowId
                    + " reason=preview_not_smaller path=" + item.path);
            return null;
        }
        return staged;
    }

    /** 原地分支的计时：字段与侧车一致，用 stage= 区分方案，便于对比两条路的耗时分布。 */
    private static void logInplaceTiming(SlimItem item, long t0, long gapMillis, long tVerifyEnd,
            long tEncodeEnd, long tPublishEnd, InplaceCallbacks callbacks) {
        long tEnd = System.nanoTime();
        sLastDoneNanos = tEnd;
        Logger.i("event=slim_timing stage=inplace total=" + elapsedMillis(t0, tEnd)
                + " verify=" + elapsedMillis(t0, tVerifyEnd)
                + " encode=" + elapsedMillis(tVerifyEnd, tEncodeEnd)
                + " lease=" + elapsedMillis(tEncodeEnd, callbacks.leaseEndNanos)
                + " publish=" + elapsedMillis(callbacks.leaseEndNanos, tPublishEnd)
                + " db=" + elapsedMillis(callbacks.dbStartNanos, callbacks.dbEndNanos)
                + " tail=" + elapsedMillis(tPublishEnd, tEnd)
                + " gap=" + gapMillis
                + " path=" + item.path);
    }

    /**
     * 原地覆盖的六个副作用：租约、意图、DB 回读、状态提交、损坏标记、原图回填。
     *
     * <p>全部经 {@link OriginalStore} 与宿主 {@code saveToDB} 实现；每个回调自己吞掉异常并返回
     * false/void，让引擎只需要关心 reason，不必区分“抛了异常”与“返回了失败”。
     */
    private final class InplaceCallbacks implements InplacePreviewWriter.Callbacks {
        private final SlimItem mItem;
        private final File mOriginal;
        private final OriginalStore.Record mRecord;
        private final OriginalStore mStore;
        private final Config mConfig;
        private final Method mSaveToDb;
        private final Context mContext;
        private OriginalStore.PathLease mLease;
        File staged;
        long leaseEndNanos;
        long dbStartNanos;
        long dbEndNanos;

        InplaceCallbacks(SlimItem item, File original, OriginalStore.Record record,
                OriginalStore store, Config config, Method saveToDb, Context context) {
            mItem = item;
            mOriginal = original;
            mRecord = record;
            mStore = store;
            mConfig = config;
            mSaveToDb = saveToDb;
            mContext = context;
        }

        @Override
        public boolean beginLease() {
            try {
                // 等持有读租约的上传结束，并在整个覆盖+DB 切换期间挡住新的上传
                mLease = mStore.beginDerivedMutation(mConfig, mItem.rowId, mItem.sha1, mOriginal);
                if (mLease == null) {
                    return false;
                }
                leaseEndNanos = System.nanoTime();
                // 侧车分支拿到租约后会重验原图字节；原地覆盖更要在动字节之前再确认一次：
                // 否则用户刚刚改过的照片会被一份预览静默覆盖（主机侧看不到任何回滚机会）
                if (!mOriginal.isFile() || mOriginal.length() != mRecord.originalLength
                        || !mRecord.sha256.equals(InplacePreviewWriter.sha256Of(mOriginal))) {
                    Logger.w("event=slim_inplace_failed row=" + mItem.rowId
                            + " reason=original_changed_before_publish path=" + mItem.path);
                    return false;
                }
                return true;
            } catch (Throwable t) {
                Logger.w("event=slim_inplace_lease_failed path=" + mItem.path, t);
                return false;
            }
        }

        @Override
        public boolean markPending(long len, String sha256, String format) {
            try {
                return mStore.markInplacePending(mConfig, mRecord, len, sha256, format) != null;
            } catch (Throwable t) {
                Logger.w("event=slim_inplace_pending_failed path=" + mItem.path, t);
                return false;
            }
        }

        @Override
        public boolean commitDatabase(File original) {
            dbStartNanos = System.nanoTime();
            try {
                // arg2=thumbnailFile 置空、arg3=localFile=原路径：相册认为原图还在本机
                mSaveToDb.invoke(null, mItem.id, "", original.getAbsolutePath());
                return databasePointsToInplace(mContext, mItem.id, original.getAbsolutePath());
            } catch (Throwable t) {
                Logger.e("event=slim_inplace_db_failed path=" + mItem.path, t);
                return false;
            } finally {
                dbEndNanos = System.nanoTime();
            }
        }

        @Override
        public void markInPlace(long len, String sha256, String format) {
            try {
                mStore.markInplaceCommitted(mConfig, mRecord, len, sha256, format);
            } catch (Throwable t) {
                Logger.w("event=slim_inplace_commit_failed path=" + mItem.path, t);
            }
        }

        @Override
        public void markDamaged(String reason) {
            try {
                mStore.markDamaged(mConfig, mRecord, reason);
            } catch (Throwable t) {
                Logger.w("event=slim_inplace_damaged_failed path=" + mItem.path, t);
            }
        }

        @Override
        public void restoreOriginal() {
            try {
                // 复核失败时原路径上已经是这次写下的字节：先登记成派生副本，恢复流程才肯覆盖它
                mStore.registerDerivedPath(mConfig, mItem.rowId, mItem.sha1, mOriginal);
            } catch (Throwable t) {
                Logger.w("event=slim_inplace_restore_mark_failed path=" + mItem.path, t);
            }
            try {
                mStore.restoreOriginal(mConfig, mRecord, mOriginal);
            } catch (Throwable t) {
                Logger.e("event=slim_inplace_restore_failed path=" + mItem.path, t);
            }
        }

        /** 释放租约并清掉没被引擎消费掉的临时件（成功路径上它已经被改名走了）。 */
        void close() {
            if (mLease != null) {
                mLease.close();
                mLease = null;
            }
            if (staged != null && staged.exists() && !staged.delete()) {
                Logger.d("event=slim_temp_cleanup_failed path=" + staged.getAbsolutePath());
            }
        }
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

    private boolean isCurrentTargetLocked(Config expected) {
        Config active = mConfig;
        if (expected == null || active == null || !expected.isReady() || !active.isReady()) {
            return false;
        }
        return OriginalStore.targetKey(expected).equals(OriginalStore.targetKey(active))
                && Objects.equals(expected.getPassword(), active.getPassword())
                && Objects.equals(expected.getRemoteLayout(), active.getRemoteLayout());
    }

    private boolean mayMigrateLegacyVideo(File original, SlimItem item) {
        if (!videoRestoreReady() || !hasIdentity(item)
                || item.sha1 == null || item.sha1.isEmpty()
                || item.fileName == null || item.fileName.isEmpty()
                || original == null || !original.isFile() || original.length() <= 0L) {
            return false;
        }
        try {
            return UploadQueue.wasVerifiedUploadCurrentTarget(original.getCanonicalPath(),
                    original.length(), original.lastModified());
        } catch (IOException e) {
            return false;
        }
    }

    /** Generate Gallery's normal video slim poster in an operation-owned staging directory. */
    private boolean createNativeVideoPoster(File original, File staged, File parent,
            SlimItem item) throws Exception {
        File work = new File(parent, ".video-build-" + UUID.randomUUID());
        if (!work.mkdir()) return false;
        try {
            Class<?> metaWriterClass = mClassLoader.loadClass(
                    "com.miui.gallery.data.ThumbnailMetaWriter");
            Constructor<?> metaWriterConstructor = metaWriterClass.getDeclaredConstructor(
                    String.class, String.class, long.class, String.class, String.class,
                    String.class);
            metaWriterConstructor.setAccessible(true);
            Object metaWriter = metaWriterConstructor.newInstance(item.sha1, item.fileName,
                    item.date, item.dateTime, item.gpsDateStamp, item.gpsTimeStamp);

            Class<?> cloudUtils = mClassLoader.loadClass("com.miui.gallery.cloud.utils.CloudUtils");
            Method buildVideoPoster = cloudUtils.getDeclaredMethod("buildBigThumbnailsForVideo",
                    String.class, String.class, String.class, String.class, metaWriterClass);
            buildVideoPoster.setAccessible(true);
            String outputName = ".gallerysync-video-" + item.id + "-"
                    + UUID.randomUUID() + ".jpg";
            Object result = buildVideoPoster.invoke(null, original.getAbsolutePath(), item.sha1,
                    work.getAbsolutePath(), outputName, metaWriter);
            if (!(result instanceof String) || ((String) result).isEmpty()) return false;

            File generated = new File((String) result);
            String workPath = work.getCanonicalPath() + File.separator;
            String generatedPath = generated.getCanonicalPath();
            if (!generatedPath.startsWith(workPath) || !generated.isFile()
                    || generated.length() <= 0 || generated.length() >= original.length()) {
                return false;
            }
            PreviewFileTime.copyModifiedTime(original, generated);
            Files.move(generated.toPath(), staged.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return staged.isFile() && staged.length() > 0
                    && staged.length() < original.length();
        } finally {
            File[] leftovers = work.listFiles();
            if (leftovers != null) {
                for (File leftover : leftovers) {
                    if (leftover.isFile()) leftover.delete();
                }
            }
            work.delete();
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

    /**
     * 原地模式的回读确认：{@code localFile} 必须等于原路径、{@code thumbnailFile} 必须为空。
     *
     * <p>与侧车模式正好相反 —— 侧车把预览写进 thumbnailFile 并清空 localFile，好让相册认为
     * 原图不在本机；原地模式必须让相册认为原图就在原路径上，否则下一次扫描会把这张照片当成
     * 云端独有、再从远端下一份完整原图回来。
     */
    private boolean databasePointsToInplace(Context context, int id, String localPath)
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
            return samePathValue(localPath, actualLocal)
                    && (actualThumbnail == null || actualThumbnail.isEmpty());
        }
    }

    /** 路径相等的宽松比较：宿主可能存绝对路径，也可能存经过符号链接归一化的路径。 */
    private static boolean samePathValue(String expected, String actual) {
        if (expected == null || actual == null) return false;
        if (expected.equals(actual)) return true;
        try {
            return new File(expected).getCanonicalPath()
                    .equals(new File(actual).getCanonicalPath());
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean migrateLegacyOriginal(OriginalStore store, Config config,
            File original, SlimItem item) throws IOException {
        String candidate = UploadQueue.remotePathFor(config, item.path,
                item.serverType == 2, item.date);
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

    private static boolean isSameFile(File first, File second) {
        if (first == null || second == null) return first == second;
        try {
            return first.getCanonicalPath().equals(second.getCanonicalPath());
        } catch (IOException e) {
            return first.getAbsoluteFile().equals(second.getAbsoluteFile());
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

    static final class ItemFields {
        final Field id;
        final Field path;
        final Field thumbnailPath;
        final Field sha1;
        final Field serverType;
        final Field mixDateTime;
        final Field fileName;
        final Field dateTime;
        final Field gpsDateStamp;
        final Field gpsTimeStamp;

        ItemFields(Class<?> type) throws NoSuchFieldException {
            id = field(type, "id");
            path = field(type, "localFilePath");
            thumbnailPath = field(type, "thumbnailFilePath");
            sha1 = field(type, "sha1");
            serverType = field(type, "serverType");
            mixDateTime = field(type, "mixDateTime");
            fileName = field(type, "fileName");
            dateTime = field(type, "dateTime");
            gpsDateStamp = field(type, "gpsDateStamp");
            gpsTimeStamp = field(type, "gpsTimeStamp");
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
            String itemFileName = (String) fileName.get(media);
            String itemDateTime = (String) dateTime.get(media);
            String itemGpsDate = (String) gpsDateStamp.get(media);
            String itemGpsTime = (String) gpsTimeStamp.get(media);
            return new SlimItem(itemId, local, thumbnail, itemSha1,
                    itemId < 0 ? null : String.valueOf(itemId), type, date, itemFileName,
                    itemDateTime, itemGpsDate, itemGpsTime);
        }
    }

    static final class SlimItem {
        final int id;
        final String path;
        final String thumbnailFilePath;
        final String sha1;
        final String rowId;
        final int serverType;
        final long date;
        final String fileName;
        final String dateTime;
        final String gpsDateStamp;
        final String gpsTimeStamp;

        SlimItem(int id, String path, String thumbnailFilePath, String sha1, String rowId,
                int serverType, long date, String fileName, String dateTime, String gpsDateStamp,
                String gpsTimeStamp) {
            this.id = id;
            this.path = path;
            this.thumbnailFilePath = thumbnailFilePath;
            this.sha1 = sha1;
            this.rowId = rowId;
            this.serverType = serverType;
            this.date = date;
            this.fileName = fileName;
            this.dateTime = dateTime;
            this.gpsDateStamp = gpsDateStamp;
            this.gpsTimeStamp = gpsTimeStamp;
        }
    }
}
