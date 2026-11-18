package com.tongluosao.gallerysync.hook;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;

import com.tongluosao.gallerysync.core.Config;
import com.tongluosao.gallerysync.core.InplaceSlimPolicy;
import com.tongluosao.gallerysync.core.Logger;
import com.tongluosao.gallerysync.core.OriginalStore;
import com.tongluosao.gallerysync.core.RemoteSuppress;
import com.tongluosao.gallerysync.core.TakeoverPlanner;
import com.tongluosao.gallerysync.core.UploadQueue;
import com.tongluosao.gallerysync.core.WebDavClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
    /** 老侧车目录名：历史数据把预览写在 {@code <相册>/.gallerysync-slim/<随机名>.<ext>} 里。 */
    private static final String SLIM_DIRECTORY_NAME = ".gallerysync-slim";
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
    /** poster_rewire 的图片行要看的列：两个路径字段、指纹与记录尺寸。 */
    private static final String[] POSTER_ROW_COLUMNS = new String[]{"_id", "localFile",
            "thumbnailFile", "sha1", "size"};
    /** poster_rewire 的视频行要看的列：只有"是不是 mp4"与"封面位是否空着"两条。 */
    private static final String[] VIDEO_ROW_COLUMNS = new String[]{"_id", "fileName",
            "thumbnailFile"};
    /** poster_rewire 里封面路径的最大长度（与 tempFiles 的路径上限一致）。 */
    private static final int MAX_POSTER_PATH = 4096;
    /** reconcile_inplace 里照片绝对路径的最大长度（与 tempFiles 的路径上限一致）。 */
    private static final int MAX_RECONCILE_PATH = 4096;
    /** reconcile_inplace 的 keeper 行要看的列：路径、预览位、指纹、远端状态与登记尺寸。 */
    private static final String[] RECONCILE_KEEPER_COLUMNS = new String[]{"_id", "localFile",
            "thumbnailFile", "sha1", "serverId", "size"};

    /** 请求根上 {@code mode} 的取值：接管相册里已有的云端行，一个字节都不下载。 */
    static final String TAKEOVER_MODE = "takeover";
    /** takeover 枚举候选要看的列：身份、指纹、尺寸、本地路径与远端状态。 */
    private static final String[] TAKEOVER_ROW_COLUMNS = new String[]{"_id", "sha1", "size",
            "localFile", "fileName", "serverType", "localFlag", "serverId"};
    private static final int TAKEOVER_SERVER_TYPE_IMAGE = 1;
    private static final int TAKEOVER_SERVER_TYPE_VIDEO = 2;
    /** {@code localFlag = 7} 是“本地已同步”：只有这类行的 {@code size} 才是当年上传的字节数。 */
    private static final int TAKEOVER_LOCAL_FLAG_SYNCED = 7;
    /** 各版本里“这行是正常本地照片”的 localFlag 取值（手机 7；平板 3.6 上是 0/8）。 */
    private static final java.util.Set<Integer> TAKEOVER_ACCEPTED_FLAGS =
            new java.util.HashSet<>(java.util.Arrays.asList(0, 7, 8));
    /** 远端索引最多走几层：根目录算第 0 层，相册/年/月这种布局两层就到底了。 */
    private static final int TAKEOVER_INDEX_MAX_DEPTH = 4;
    /** 远端索引最多收多少个文件条目：一万多张照片 + 视频留足余量，再大就是远端树出了问题。 */
    private static final int TAKEOVER_INDEX_MAX_ENTRIES = 60_000;
    /** 远端索引最多列多少个目录：远端树异常深/异常宽时不能把整趟接管拖死。 */
    private static final int TAKEOVER_INDEX_MAX_DIRS = 500;

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
            Logger.e("event=metadata_restore_request_failed reason=" + safeReason(failure), failure);
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
        if (TAKEOVER_MODE.equals(request.mode)) {
            // 接管模式自己按相册行枚举，不读 items / tempFiles：两条循环都不进
            TakeoverRun run = runTakeover(context, config, store, target, request);
            String status;
            if (!run.reason.isEmpty()) {
                status = "failed";
            } else if (run.cancelled) {
                status = "cancelled";
            } else {
                status = run.failed > 0 ? "partial" : "complete";
            }
            if (writeTakeoverResult(resultFile, jobId, target, status, run)) {
                logStatus(status);
            } else {
                logStatus("result_write_failed");
            }
            return;
        }
        // 整批只落盘一次：逐项保存会把数 MB 的注册表重写 N 次（三千项约 16 GB 写入）。
        store.beginBatch();
        try {
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
                } else if ("poster_rewire".equals(item.mode)) {
                    result = posterRewireOne(context, config, store, target, item);
                } else if ("migrate_inplace".equals(item.mode)) {
                    result = migrateInplaceOne(context, config, store, target, item, request.dryRun);
                } else if ("repair_state".equals(item.mode)) {
                    result = repairStateOne(context, config, store, target, item);
                } else if ("reconcile_inplace".equals(item.mode)) {
                    result = reconcileInplaceOne(context, config, store, target, item);
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
        } finally {
            store.endBatch();
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

    /**
     * 接管：把相册里已有的云端行登记进注册表，<b>一个字节都不下载</b>。
     *
     * <p>三步，顺序不能换：
     * <ol>
     *   <li>查相册库拿候选行：远端有对象（{@code serverId} 非空）、本地已同步
     *       （{@code localFlag = 7}）、40 位 {@code sha1}、{@code size > 0}；</li>
     *   <li>整趟只建一次远端索引（BFS 列目录，见 {@link #indexRemoteFiles}）—— 逐文件
     *       {@code PROPFIND} 在一万张老照片上就是一万次往返，和“不下载字节”这个前提自相矛盾；</li>
     *   <li>逐行解析远端路径（有 {@code localFile} 走上传算法，否则按 文件名 + 字节数 唯一命中），
     *       再问 {@link TakeoverPlanner}，只有 TAKE_* 才写注册表；dryRun 只统计不落盘。</li>
     * </ol>
     *
     * <p>任何一步问不出来（索引里没有、同名多义、登记被拒）都只跳过那一行：
     * 接管是补登记，跳过不会让现状更差；登记错了却会让上传侧以为这些字节已经验证过。
     */
    private TakeoverRun runTakeover(Context context, Config config, OriginalStore store,
            String target, Request request) {
        TakeoverRun run = new TakeoverRun(request.dryRun);
        List<TakeoverRow> rows;
        try {
            rows = readTakeoverRows(context, request.includeVideos);
        } catch (Throwable failure) {
            Logger.e("event=takeover_query_failed", failure);
            run.reason = "takeover_query_failed";
            return run;
        }
        Logger.i("event=takeover_rows rows=" + rows.size() + " includeVideos="
                + request.includeVideos + " dryRun=" + request.dryRun);
        Set<String> registered = store.remotePathsForTarget(config);
        WebDavClient client;
        try {
            client = new WebDavClient(config.getBaseUrl(), config.getUsername(),
                    config.getPassword());
        } catch (Throwable failure) {
            Logger.e("event=takeover_client_failed", failure);
            run.reason = "takeover_client_failed";
            return run;
        }
        try {
            // 远端树只走这一遍：索引按文件名归组，路径/大小都在里面，后面全是纯内存判断
            Map<String, List<RemoteHit>> index = indexRemoteFiles(client, config.getRemoteRoot());
            // 整趟只落盘一次：逐行保存会把注册表重写 N 次
            store.beginBatch();
            try {
                int resolvedByName = 0;
                for (TakeoverRow row : rows) {
                    if (!isCurrent(target)) {
                        run.cancelled = true;
                        break;
                    }
                    Long remoteSize;
                    if (row.localFile != null && !row.localFile.isEmpty()) {
                        // a. 有本地路径：与上传侧同一套算法拼出远端路径，再按精确路径对字节数
                        String remotePath = UploadQueue.remotePathFor(config, row.localFile,
                                row.serverType == TAKEOVER_SERVER_TYPE_VIDEO, row.mtime);
                        int slash = remotePath == null ? -1 : remotePath.lastIndexOf('/');
                        if (remotePath == null || slash <= 0 || slash >= remotePath.length() - 1) {
                            // 推不出远端路径（不在已知布局下）就没法证明远端有这份文件
                            run.skip("remote_path_unknown");
                            logTakeoverItem(row.rowId, "remote_path_unknown", "");
                            continue;
                        }
                        row.remotePath = remotePath;
                        RemoteHit hit = findIndexHit(index, remotePath);
                        remoteSize = hit == null ? null : Long.valueOf(hit.size);
                    } else {
                        // b. 平板 3.6 的主体：localFile 与 albumId 都是空的，只剩 文件名 + 字节数
                        RemoteResolve resolved = resolveRemotePath(row.fileName, row.size, index);
                        if (resolved.status == RemoteResolve.Status.MISSING) {
                            run.skip("remote_missing");
                            logTakeoverItem(row.rowId, "remote_missing", "");
                            continue;
                        }
                        if (resolved.status == RemoteResolve.Status.AMBIGUOUS) {
                            run.skip("remote_ambiguous");
                            logTakeoverItem(row.rowId, "remote_ambiguous", "");
                            continue;
                        }
                        row.remotePath = resolved.remotePath;
                        remoteSize = Long.valueOf(row.size);
                        resolvedByName++;
                        if (resolvedByName <= 5) {
                            Logger.i("event=takeover_row_resolved_by_name row=" + row.rowId
                                    + " remote=" + row.remotePath);
                        }
                    }
                    evaluateTakeoverRow(config, store, run, row, remoteSize, registered);
                }
            } finally {
                store.endBatch();
            }
        } finally {
            try {
                client.close();
            } catch (Throwable ignored) {
                // 关不掉客户端不影响已经登记的结果
            }
        }
        Logger.i("event=takeover_done planned=" + run.planned + " taken=" + run.taken
                + " skipped=" + run.skipped + " failed=" + run.failed + " dryRun="
                + run.dryRun);
        return run;
    }

    /**
     * 一趟建好“文件名 → 远端命中列表”的索引：BFS 列目录，<b>绝不取字节</b>。
     *
     * <p>为什么是整棵树而不是逐目录：平板 3.6 的云端行既没有 {@code localFile} 也没有
     * {@code albumId}，唯一能拿到的线索是 {@code fileName + size}；要知道这份文件在远端
     * 哪个目录，只能把树走一遍。三个上限（层数/条目数/目录数）是给异常远端树兜底的：
     * 走不完就少认几行，不会把整趟接管拖死。
     *
     * <p>单个目录列失败只记一条 {@code takeover_index_dir_failed} 就继续 ——
     * 一个坏目录不该让整趟接管失败。
     */
    private static Map<String, List<RemoteHit>> indexRemoteFiles(WebDavClient client,
            String remoteRoot) {
        long start = System.currentTimeMillis();
        Map<String, List<RemoteHit>> index = new HashMap<>();
        String root = remoteRoot == null ? "" : remoteRoot;
        if (client == null) {
            Logger.w("event=takeover_index_dir_failed dir=" + root + " reason=client_unavailable");
            return index;
        }
        ArrayDeque<IndexDir> pending = new ArrayDeque<>();
        pending.add(new IndexDir(root, 0));
        int entries = 0;
        int dirs = 0;
        while (!pending.isEmpty() && entries < TAKEOVER_INDEX_MAX_ENTRIES
                && dirs < TAKEOVER_INDEX_MAX_DIRS) {
            IndexDir dir = pending.poll();
            dirs++;
            List<WebDavClient.RemoteEntry> listed;
            try {
                Logger.d("event=takeover_index_list_start dir=" + dir.path + " depth=" + dir.depth);
                listed = client.list(dir.path);
                Logger.d("event=takeover_index_list_done dir=" + dir.path + " entries="
                        + (listed == null ? -1 : listed.size()));
            } catch (IOException failure) {
                Logger.w("event=takeover_index_dir_failed dir=" + dir.path
                        + " reason=list_failed");
                continue;
            } catch (Throwable failure) {
                Logger.w("event=takeover_index_dir_failed dir=" + dir.path
                        + " reason=list_failed");
                continue;
            }
            if (listed == null) {
                // list() 把 404 / 非 207 / 截断的回应变成 null：这个目录当“没列出来”
                Logger.w("event=takeover_index_dir_failed dir=" + dir.path
                        + " reason=list_unavailable");
                continue;
            }
            for (WebDavClient.RemoteEntry entry : listed) {
                if (entry == null || entry.name == null || entry.name.isEmpty()) continue;
                if (entry.dir) {
                    // 点开头的目录（.mgs-hashes 之类）是模块自己的元数据，不是照片
                    if (entry.name.startsWith(".")) continue;
                    if (dir.depth >= TAKEOVER_INDEX_MAX_DEPTH) continue;
                    pending.add(new IndexDir(WebDavClient.joinPath(dir.path, entry.name),
                            dir.depth + 1));
                    continue;
                }
                if (entries >= TAKEOVER_INDEX_MAX_ENTRIES) break;
                entries++;
                List<RemoteHit> hits = index.get(entry.name);
                if (hits == null) {
                    hits = new ArrayList<>(1);
                    index.put(entry.name, hits);
                }
                hits.add(new RemoteHit(WebDavClient.joinPath(dir.path, entry.name), entry.size));
            }
        }
        Logger.i("event=takeover_index entries=" + entries + " dirs=" + dirs
                + " ms=" + (System.currentTimeMillis() - start));
        return index;
    }

    /**
     * 纯解析：文件名 + 相册行登记的字节数 → 远端路径。
     *
     * <p>只有“同名、同字节数、而且只有一份”才算命中：瘦身行没有 {@code localFile}，
     * 这两条同时成立是唯一能证明“远端那一份就是这张照片”的证据。同名同大小出现多份
     * （换过目录/传过两次）时不猜，计入 {@code remote_ambiguous}。
     */
    static RemoteResolve resolveRemotePath(String fileName, long size,
            Map<String, List<RemoteHit>> index) {
        if (fileName == null || fileName.isEmpty() || index == null) {
            return RemoteResolve.missing();
        }
        List<RemoteHit> hits = index.get(fileName);
        if (hits == null || hits.isEmpty()) {
            return RemoteResolve.missing();
        }
        RemoteHit match = null;
        for (RemoteHit hit : hits) {
            if (hit == null || hit.remotePath == null || hit.size != size) continue;
            if (match != null) return RemoteResolve.ambiguous();
            match = hit;
        }
        return match == null ? RemoteResolve.missing() : RemoteResolve.matched(match.remotePath);
    }

    /** 按精确远端路径在索引里找那一项（同名文件可能分布在多个目录）。 */
    private static RemoteHit findIndexHit(Map<String, List<RemoteHit>> index, String remotePath) {
        if (index == null || remotePath == null) return null;
        int slash = remotePath.lastIndexOf('/');
        if (slash < 0 || slash >= remotePath.length() - 1) return null;
        List<RemoteHit> hits = index.get(remotePath.substring(slash + 1));
        if (hits == null) return null;
        for (RemoteHit hit : hits) {
            if (hit != null && remotePath.equals(hit.remotePath)) return hit;
        }
        return null;
    }

    /** 判一行：能证明什么就登记成什么，证明不了就按 {@link TakeoverPlanner} 的结论跳过。 */
    private static void evaluateTakeoverRow(Config config, OriginalStore store, TakeoverRun run,
            TakeoverRow row, Long remoteSize, Set<String> registered) {
        boolean hasSha1 = row.sha1 != null && row.sha1.matches("(?i)[0-9a-f]{40}");
        if (!hasSha1) {
            run.skip("skip_no_sha1");
            logTakeoverItem(row.rowId, "skip_no_sha1", row.remotePath);
            return;
        }
        if (registered.contains(row.remotePath)) {
            // 已登记过（上传/恢复/上一次接管留下的）：不重复登记，也不去动已有的 sha256
            run.skip("already_registered");
            logTakeoverItem(row.rowId, "already_registered", row.remotePath);
            return;
        }
        File local = row.localFile == null ? null : new File(row.localFile);
        boolean localExists = local != null && local.isFile();
        long localLength = localExists ? local.length() : 0L;
        boolean remoteListed = remoteSize != null;
        TakeoverPlanner.Decision decision = TakeoverPlanner.decide(remoteListed,
                remoteListed ? remoteSize.longValue() : 0L, row.size, localExists, localLength,
                true);
        String name = TakeoverPlanner.describe(decision);
        if (!TakeoverPlanner.isTake(decision)) {
            run.skip(name);
            logTakeoverItem(row.rowId, name, row.remotePath);
            return;
        }
        run.planned++;
        if (!run.dryRun) {
            try {
                boolean written = store.registerTakenOver(config, row.remotePath, row.size,
                        row.rowId, row.sha1,
                        decision == TakeoverPlanner.Decision.TAKE_AS_ORIGINAL
                                ? OriginalStore.LocalState.ORIGINAL.wire()
                                : OriginalStore.LocalState.REMOTE_ONLY.wire());
                if (written) {
                    run.taken++;
                } else {
                    run.failed++;
                    run.count("register_rejected");
                    Logger.w("event=takeover_register_rejected row=" + row.rowId
                            + " remote=" + row.remotePath);
                }
            } catch (Throwable failure) {
                run.failed++;
                run.count("register_failed");
                Logger.e("event=takeover_register_failed row=" + row.rowId
                        + " remote=" + row.remotePath, failure);
            }
        }
        logTakeoverItem(row.rowId, name, row.remotePath);
    }

    /**
     * 查接管候选行。
     *
     * <p>选择条件的每一条都是“远端确实有这张照片”的必要证据：{@code serverId} 非空说明有云
     * 对象；{@code localFlag = 7} 是本地已同步；{@code length(sha1) = 40} 是完整指纹；
     * {@code size > 0} 排除占位行。视频默认一起接管，关掉时只取 {@code serverType = 1}。
     *
     * <p>{@code localFile} 为空的行照样留下（平板 3.6 上这是主体，连 {@code albumId} 都是 0）：
     * 路径解析交给 {@link #runTakeover} 的远端索引，这里只负责把证据读全。
     */
    private static List<TakeoverRow> readTakeoverRows(Context context, boolean includeVideos)
            throws IOException {
        if (context == null || CLOUD_URI == null) {
            throw new IOException("takeover_provider_unavailable");
        }
        // localFlag 的含义随版本变（手机上同步完成是 7，平板 3.6 上是 0/8），
        // 所以不把它写进选择条件，读出来后在本地按可接受集合过滤，并把实到的分布打出来。
        String selection = "serverId IS NOT NULL AND size > 0 AND length(sha1) = 40"
                + (includeVideos ? " AND serverType IN (1, 2)" : " AND serverType = 1");
        List<TakeoverRow> rows = new ArrayList<>();
        int flagDropped = 0;
        Map<Integer, Integer> flagCounts = new java.util.TreeMap<>();
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(CLOUD_URI, TAKEOVER_ROW_COLUMNS,
                    selection, null, "_id ASC");
            if (cursor == null) {
                throw new IOException("takeover_query_failed");
            }
            int idColumn = cursor.getColumnIndex("_id");
            int sha1Column = cursor.getColumnIndex("sha1");
            int sizeColumn = cursor.getColumnIndex("size");
            int localColumn = cursor.getColumnIndex("localFile");
            int nameColumn = cursor.getColumnIndex("fileName");
            int typeColumn = cursor.getColumnIndex("serverType");
            int flagColumn = cursor.getColumnIndex("localFlag");
            int serverColumn = cursor.getColumnIndex("serverId");
            while (cursor.moveToNext()) {
                long rowId = idColumn < 0 ? 0L : cursor.getLong(idColumn);
                if (rowId <= 0L) continue;
                String localFile = localColumn < 0 || cursor.isNull(localColumn)
                        ? null : cursor.getString(localColumn);
                long size = sizeColumn < 0 || cursor.isNull(sizeColumn)
                        ? 0L : cursor.getLong(sizeColumn);
                if (size <= 0L) continue;
                String sha1 = sha1Column < 0 || cursor.isNull(sha1Column)
                        ? null : cursor.getString(sha1Column);
                String fileName = nameColumn < 0 || cursor.isNull(nameColumn)
                        ? null : cursor.getString(nameColumn);
                int serverType = typeColumn < 0 || cursor.isNull(typeColumn)
                        ? TAKEOVER_SERVER_TYPE_IMAGE : cursor.getInt(typeColumn);
                int localFlag = flagColumn < 0 || cursor.isNull(flagColumn)
                        ? -1 : cursor.getInt(flagColumn);
                flagCounts.merge(localFlag, 1, Integer::sum);
                if (!TAKEOVER_ACCEPTED_FLAGS.contains(localFlag)) {
                    flagDropped++;
                    continue;
                }
                String serverId = serverColumn < 0 || cursor.isNull(serverColumn)
                        ? null : cursor.getString(serverColumn);
                // localFile 为空时没有 mtime 可用：按时间分目录的布局只能退回 0（索引里会另找）
                long mtime = localFile == null || localFile.isEmpty()
                        ? 0L : new File(localFile).lastModified();
                rows.add(new TakeoverRow(rowId, sha1, size, localFile, fileName, serverType,
                        localFlag, serverId, mtime));
            }
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                    // 关游标失败不影响已经读到的行
                }
            }
        }
        if (!flagCounts.isEmpty()) {
            Logger.i("event=takeover_flags counts=" + flagCounts + " dropped=" + flagDropped);
        }
        return rows;
    }

    /**
     * 写一份 takeover 请求（{@code GalleryHooks} 收到 {@code CMD_TAKEOVER} 时调用）。
     *
     * <p>请求文件由相册进程自己写：模块 App 只发一条广播，不碰这个 JSON，
     * 免得两个进程各写一半。
     */
    static boolean writeTakeoverRequest(Context context, Config config) {
        return writeTakeoverRequest(context, config, false);
    }

    /** 同上，{@code dryRun} 为 true 时只出统计、不写注册表（也不下载任何字节）。 */
    static boolean writeTakeoverRequest(Context context, Config config, boolean dryRun) {
        if (context == null || config == null || !config.isReady()) return false;
        File directory = new File(context.getFilesDir(), DIRECTORY_NAME);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            return false;
        }
        File requestFile = new File(directory, REQUEST_NAME);
        try {
            JSONObject root = new JSONObject();
            root.put("jobId", "takeover-" + System.currentTimeMillis());
            root.put("target", OriginalStore.targetKey(config));
            root.put("mode", TAKEOVER_MODE);
            root.put("dryRun", dryRun);
            root.put("includeVideos", true);
            writeJsonAtomically(requestFile, root);
            return requestFile.isFile() && requestFile.length() > 0L;
        } catch (Throwable failure) {
            Logger.e("event=takeover_request_write_failed", failure);
            return false;
        }
    }

    /** 接管结果：根上只有计数与原因直方图，没有逐项 items（几千行的逐项清单没人读）。 */
    private static boolean writeTakeoverResult(File resultFile, String jobId, String target,
            String status, TakeoverRun run) {
        try {
            JSONObject root = new JSONObject();
            root.put("jobId", jobId == null ? "" : jobId);
            root.put("target", target == null ? "" : target);
            root.put("status", status);
            if (!run.reason.isEmpty()) root.put("reason", run.reason);
            root.put("mode", TAKEOVER_MODE);
            root.put("dryRun", run.dryRun);
            root.put("planned", run.planned);
            root.put("taken", run.taken);
            root.put("skipped", run.skipped);
            root.put("failed", run.failed);
            JSONObject reasons = new JSONObject();
            for (Map.Entry<String, Integer> entry : run.reasons.entrySet()) {
                reasons.put(entry.getKey(), entry.getValue().intValue());
            }
            root.put("reasons", reasons);
            writeJsonAtomically(resultFile, root);
            return resultFile.isFile() && resultFile.length() > 0L;
        } catch (Throwable failure) {
            Logger.e("event=metadata_restore_result_write_failed status=io_error");
            return false;
        }
    }

    /** 原子写 JSON：临时文件 + fsync + 改名，读侧永远看不到写了一半的文件。 */
    private static void writeJsonAtomically(File file, JSONObject root) throws IOException {
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(root.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailure) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void logTakeoverItem(long rowId, String decision, String remotePath) {
        Logger.i("event=takeover_item row=" + rowId + " decision=" + decision + " remote="
                + (remotePath == null ? "" : remotePath));
    }

    private ItemResult restoreOne(Context context, Config config, OriginalStore store,
            String target, RequestItem item) throws Exception {
        if (!isCurrent(target)) return new ItemResult(item.row, "cancelled", "job_stopped");
        OriginalStore.Record record = store.find(config, item.row, item.sha1);
        if (record == null) return new ItemResult(item.row, "failed", "no_registry_record");
        if (!target.equals(record.target) || record.sha256 == null
                || !record.sha256.equalsIgnoreCase(item.sha256)
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
        if (!target.equals(record.target) || record.sha256 == null
                || !record.sha256.equalsIgnoreCase(item.sha256)
                || record.originalLength != item.length) {
            return new ItemResult(item.row, "failed", "registry_metadata_mismatch");
        }
        // 原地模式：归属路径上现在是预览、完整原图只在远端。老逻辑会在下面的
        // isOriginalVerified 处把它当成"原图不存在"拒掉（original_missing），
        // 于是这行永远撤不掉瘦身；这里先把原图从远端回填，再收回状态与行。
        if (record.getLocalState() == OriginalStore.LocalState.INPLACE_PREVIEW) {
            return unslimInplace(context, config, store, target, item, record);
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
     * unslim 的原地分支：把完整原图从远端回填到归属路径（覆盖那里的原地预览），
     * 状态回落到 {@code ORIGINAL}，再把相册行的 thumbnailFile 清空。
     *
     * <p>顺序固定为“先落字节、后改状态、最后改行”：任何一步失败都只留下可重试的中间态，
     * 绝不会出现“行已经指向原路径、而原路径上还是预览”这种上传侧会误判的组合。
     */
    private ItemResult unslimInplace(Context context, Config config, OriginalStore store,
            String target, RequestItem item, OriginalStore.Record record) throws Exception {
        File original = safeOriginalFile(record.originalLocalPath);
        if (original == null) return new ItemResult(item.row, "failed", "unsafe_original_path");
        // 这一步要真的从远端取原图：空间不够时宁可整项失败，也不留下半写的原路径。
        if (!hasRequiredSpace(record.originalLength)) {
            return new ItemResult(item.row, "failed", "needs_space");
        }
        if (!isCurrent(target)) return new ItemResult(item.row, "cancelled", "job_stopped");
        // restoreOriginal 认得“目标上就是登记的派生预览”这种快照，因此可以安全地覆盖它。
        File restored = store.restoreOriginal(config, record, original);
        if (restored == null || !restored.isFile()
                || restored.length() != record.originalLength) {
            return new ItemResult(item.row, "failed", "restore_verification_failed");
        }
        OriginalStore.Record latest = store.find(config, item.row, item.sha1);
        if (latest == null || latest.getLocalState() != OriginalStore.LocalState.ORIGINAL) {
            return new ItemResult(item.row, "failed", "restore_verification_failed");
        }
        int clearedRows = 0;
        String thumbnail = readThumbnailPath(context, item.row);
        if (!isBlank(thumbnail)) {
            Method saveToDb = slimSaveToDbMethod();
            if (saveToDb == null) {
                return new ItemResult(item.row, "failed", "thumbnail_clear_failed");
            }
            String failure = clearThumbnailRow(context, saveToDb, record, item.row);
            if (failure != null) return new ItemResult(item.row, "failed", failure);
            clearedRows = 1;
        }
        Logger.i("event=unslim_inplace_done row=" + item.row
                + " bytes=" + record.originalLength);
        return new ItemResult(item.row, "success", "", 0, clearedRows, 0);
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
     * 把封面引用从"只挂封面的图片行"搬到视频行上，然后删掉那条多余的图片行（poster_rewire）。
     *
     * <p>模块把视频"空间优化"成一张封面 jpg 之后，视频行的 {@code thumbnailFile} 指向封面、
     * {@code localFile} 清空。历史批次里另有一批"只挂封面"的图片行：{@code localFile} 为空、
     * {@code thumbnailFile} 指向同一张封面，在相册里多显示成一张照片；而对应视频行的
     * {@code thumbnailFile} 反而是空的。这个模式把那半截搬家补完：引用挂到视频行上、删掉多余的
     * 图片行，<b>封面文件本身保留不删</b>（整个模式没有任何删文件的路径，因此也没有
     * {@code purge_items} 那种"行已删、文件删不掉"的 partial 结局）。
     *
     * <p>判据全部集中在 {@link PosterRewire#decision}：图片行的路径/指纹/尺寸、封面文件的安全
     * 路径与长度、封面的唯一引用、视频行的 {@code .mp4} 与空封面位。判定通过之后才按 {@code _id}
     * 更新视频行并回读，再走 Gallery 自己的 {@code CloudUtils.deleteById} 删图片行并回读确认，
     * 最后复核封面文件仍在、长度未变。
     */
    private ItemResult posterRewireOne(Context context, Config config, OriginalStore store,
            String target, RequestItem item) {
        if (!isCurrent(target)) return posterRewireResult(item, "cancelled", "job_stopped", 0);
        PosterRowSnapshot posterRow = readPosterRowSnapshot(context, item.row);
        VideoRowSnapshot videoRow = readVideoRowSnapshot(context, item.videoRow);
        PosterRewireFacts facts = collectPosterRewireFacts(context, item, posterRow, videoRow);
        String failure = PosterRewire.decision(facts);
        if (!failure.isEmpty()) return posterRewireResult(item, "failed", failure, 0);
        if (!isCurrent(target)) return posterRewireResult(item, "cancelled", "job_stopped", 0);
        // 6. 可选：视频行的封面位还空着就把这张封面挂上去。挂不上（视频行被 CloudProvider 过滤掉、
        //    已被别的封面占着、并发写入）都不影响本模式的目的 —— 它只删这一条挂载行，绝不动文件。
        if (!isBlank(item.videoRow)) {
            long videoRowId = parseRowId(item.videoRow);
            if (videoRowId > 0L) {
                ContentValues values = new ContentValues(1);
                values.put("thumbnailFile", item.posterPath);
                try {
                    context.getContentResolver().update(CLOUD_URI, values,
                            "_id = ? AND (thumbnailFile IS NULL OR thumbnailFile = '')",
                            new String[]{item.videoRow});
                } catch (Throwable attachFailure) {
                    Logger.w("event=poster_rewire_attach_failed row=" + item.row
                            + " video=" + item.videoRow, attachFailure);
                }
            }
        }

        // 7. 删图片行：与 purge_items/orphan_rows 同一条可验证通道。
        long posterRowId = parseRowId(item.row);
        if (posterRowId <= 0L) return posterRewireResult(item, "failed", "poster_row_delete_failed", 0);
        boolean invoked = false;
        try {
            Class<?> cloudUtils = mLoader.loadClass("com.miui.gallery.provider.CloudUtils");
            Method deleteById = cloudUtils.getMethod("deleteById", Context.class, int.class,
                    int.class, long[].class);
            deleteById.invoke(null, context, 1, 0, new long[]{posterRowId});
            invoked = true;
        } catch (Throwable deleteFailure) {
            invoked = false;
        }
        // 删除通道抛异常与"删完回读仍能看到行"分开上报：前者是调用失败，后者是行没真消失。
        if (!invoked) return posterRewireResult(item, "failed", "poster_row_delete_failed", 0);
        if (readPosterRowSnapshot(context, item.row) != null) {
            return posterRewireResult(item, "failed", "poster_row_still_present", 0);
        }
        // 8. 复核封面文件：只记录，不参与成败 —— 本模式从不删文件，封面在不在都不影响
        //    "这一行只是挂载点"这个结论；缺文件的封面在真机上本来就存在（老版本产物被清过）。
        File posterFile = new File(item.posterPath);
        Logger.i("event=poster_rewire_done row=" + item.row + " poster_present="
                + posterFile.isFile() + " poster_length=" + posterFile.length());
        return posterRewireResult(item, "success", "", 1);
    }

    /** 采集 poster_rewire 判定需要的全部事实；判定本身在 PosterRewire.decision 里，不碰 Android。 */
    private static PosterRewireFacts collectPosterRewireFacts(Context context, RequestItem item,
            PosterRowSnapshot posterRow, VideoRowSnapshot videoRow) {
        PosterRewireFacts facts = new PosterRewireFacts();
        facts.posterRowId = item.row;
        facts.videoRowId = item.videoRow;
        facts.posterPath = item.posterPath;
        facts.requestedLength = item.length;
        // 3. 封面文件：安全路径 + 是文件 + 长度与请求一致。
        facts.posterPathSafe = PosterRewire.isSafePath(item.posterPath);
        File posterFile = facts.posterPathSafe ? new File(item.posterPath) : null;
        facts.posterFilePresent = posterFile != null && posterFile.isFile();
        facts.posterFileLengthMatches = facts.posterFilePresent
                && posterFile.length() == item.length;
        // 2. 图片行：存在、没有本地文件、封面引用逐字相等、指纹与尺寸都与请求一致。
        facts.posterRowExists = posterRow != null;
        if (posterRow != null) {
            facts.posterRowLocalFileBlank = isBlank(posterRow.localFile);
            // 逐字相等（不做大小写/规范化折叠）：请求里给的路径就是这条行必须指向的那一个。
            facts.posterRowThumbnailMatches = item.posterPath.equals(posterRow.thumbnailFile);
            facts.posterRowSha1Matches = posterRow.sha1 != null
                    && posterRow.sha1.equalsIgnoreCase(item.sha1);
            facts.posterRowLengthMatches = posterRow.size == item.length;
        }
        // 4. 唯一引用：除本行外没有任何行的 localFile/thumbnailFile 指向这张封面。
        List<String> referencing = referencingRowIds(context, null, item.posterPath,
                item.posterPath);
        facts.posterReferenceQueryFailed = referencing == null;
        if (referencing != null) {
            int others = 0;
            for (String id : referencing) {
                if (id != null && !id.equals(item.row)) others++;
            }
            facts.otherReferencingRows = others;
        }
        // 5. 视频行：存在、是 mp4、封面位还空着。
        facts.videoRowExists = videoRow != null;
        if (videoRow != null) {
            String name = videoRow.fileName;
            facts.videoNameIsMp4 = name != null
                    && name.trim().toLowerCase(Locale.ROOT).endsWith(".mp4");
            facts.videoThumbnailBlank = isBlank(videoRow.thumbnailFile);
        }
        return facts;
    }

    /** 每条 poster_rewire 结果都留一行日志；result 是拒绝理由，成功时是状态。 */
    private static ItemResult posterRewireResult(RequestItem item, String status, String reason,
            int cleanedDuplicates) {
        Logger.i("event=poster_rewire_item row=" + item.row + " result="
                + (reason == null || reason.isEmpty() ? status : reason));
        return new ItemResult(item.row, status, reason, 0, 0, cleanedDuplicates);
    }

    /** 读一行图片行的判定列；查不到（或不止一行）返回 null。 */
    private static PosterRowSnapshot readPosterRowSnapshot(Context context, String rowId) {
        if (rowId == null) return null;
        try (Cursor cursor = context.getContentResolver().query(CLOUD_URI, POSTER_ROW_COLUMNS,
                "_id = ?", new String[]{rowId}, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            int idColumn = cursor.getColumnIndex("_id");
            if (idColumn < 0 || !rowId.equals(cursor.getString(idColumn))) return null;
            PosterRowSnapshot snapshot = new PosterRowSnapshot(rowId,
                    stringColumn(cursor, "localFile"), stringColumn(cursor, "thumbnailFile"),
                    stringColumn(cursor, "sha1"), longColumn(cursor, "size"));
            if (cursor.moveToNext()) return null;
            return snapshot;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 读一行视频行的判定列；查不到（或不止一行）返回 null。 */
    private static VideoRowSnapshot readVideoRowSnapshot(Context context, String rowId) {
        if (rowId == null) return null;
        try (Cursor cursor = context.getContentResolver().query(CLOUD_URI, VIDEO_ROW_COLUMNS,
                "_id = ?", new String[]{rowId}, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            int idColumn = cursor.getColumnIndex("_id");
            if (idColumn < 0 || !rowId.equals(cursor.getString(idColumn))) return null;
            VideoRowSnapshot snapshot = new VideoRowSnapshot(rowId,
                    stringColumn(cursor, "fileName"), stringColumn(cursor, "thumbnailFile"));
            if (cursor.moveToNext()) return null;
            return snapshot;
        } catch (Throwable ignored) {
            return null;
        }
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

    // --------------------------------------------------------------- 原地覆盖迁移（第 5 波）

    /**
     * migrate_inplace：把老侧车预览 {@code <相册>/.gallerysync-slim/<随机名>.<ext>} 搬回
     * {@code <相册>/<行 fileName>}，让这一行从“侧车预览”变成“原地预览”。
     *
     * <p>判定与执行分两层：行形状/扩展名家族/目标占用全部由 {@link Migration} 的纯逻辑给出
     * 结论（也就是结果里上报的 reason），这里只负责按结论动手，并且每动一步都留下可回滚的
     * 中间态。{@code dryRun} 时连一次 rename 都不做，只回报 planned。
     */
    private ItemResult migrateInplaceOne(Context context, Config config, OriginalStore store,
            String target, RequestItem item, boolean dryRun) throws Exception {
        if (!isCurrent(target)) {
            return migrationResult(item, "cancelled", "job_stopped", null, 0L, false, false);
        }
        MigrationRow row = readMigrationRow(context, item.row, item.sha1);
        if (row == null) {
            return migrationResult(item, "failed", "migrate_row_missing", null, 0L, false, false);
        }
        OriginalStore.Record record = store.find(config, item.row, item.sha1);
        MigrationFacts facts = new MigrationFacts();
        facts.rowExists = true;
        facts.localFile = row.localFile;
        facts.thumbnailFile = row.thumbnailFile;
        facts.fileName = row.fileName;
        facts.rowSize = row.size;
        // 行形状先判：侧车形态与文件名都不需要看磁盘，先拒掉能避免把 IO 噪声当成拒绝理由。
        String rowReason = Migration.rowOnlyReason(facts);
        if (!rowReason.isEmpty()) {
            // “不是侧车行”是本来就不该迁移，算跳过；“没有原名”是数据坏了，必须算失败。
            String status = Migration.isSkipReason(rowReason) ? "skipped" : "failed";
            return migrationResult(item, status, rowReason, null, 0L, false, false);
        }
        File preview = safeStoragePath(row.thumbnailFile);
        if (preview == null) {
            return migrationResult(item, "failed", "migrate_path_unsafe", null, 0L, false, false);
        }
        facts.previewExists = preview.isFile();
        facts.previewLength = facts.previewExists ? preview.length() : 0L;
        facts.previewFormat = record != null && !isBlank(record.getPreviewFormat())
                ? record.getPreviewFormat() : Migration.previewFormatOf(row.thumbnailFile);
        facts.originalSha256 = record == null ? null : record.sha256;
        if (facts.previewExists) {
            String album = Migration.slimAlbumDirectory(row.thumbnailFile);
            String targetPath = Migration.migrationTargetPath(row.thumbnailFile, row.fileName);
            File targetFile = safeStoragePath(targetPath);
            File albumDirectory = safeStoragePath(album);
            File slimDirectory = safeStoragePath(album == null ? null
                    : album + "/" + SLIM_DIRECTORY_NAME);
            // 三条硬条件：相册目录真实存在、目标路径落在根目录内、预览确实就在那个侧车目录里。
            // 缺任何一条都说明这条行描述的不是“相册 + .gallerysync-slim + 预览”这个形状。
            if (targetFile == null || albumDirectory == null || slimDirectory == null
                    || !albumDirectory.isDirectory()
                    || !sameCanonical(slimDirectory.getPath(),
                            preview.getParentFile().getPath())) {
                return migrationResult(item, "failed", "migrate_path_unsafe", null, 0L,
                        false, false);
            }
            facts.targetExists = targetFile.isFile();
            facts.targetLength = facts.targetExists ? targetFile.length() : 0L;
            facts.targetSha256 = facts.targetExists ? sha256(targetFile) : null;
        }
        MigrationDecision decision = Migration.decision(facts);
        // TRANSCODE 不再是“留给下一波”：预览与原图容器不同，改名会把 JPEG 码流写成 .heic。
        // 改走“从 WebDAV 取回原图 → 校验 → 行指回原路径”的专用路径。
        if (decision.action == InplaceSlimPolicy.MigrationAction.TRANSCODE) {
            return migrateTranscodeOne(context, config, store, target, item, row, record,
                    preview, dryRun);
        }
        if (!decision.reason.isEmpty()) {
            String status = Migration.isSkipReason(decision.reason) ? "skipped" : "failed";
            return migrationResult(item, status, decision.reason, decision.action,
                    facts.previewLength, false, false);
        }
        if (dryRun) {
            Logger.i("event=migrate_inplace_planned row=" + item.row
                    + " bytes=" + facts.previewLength + " dryRun=true");
            return migrationResult(item, "planned", "", decision.action,
                    facts.previewLength, false, true);
        }
        if (!isCurrent(target)) {
            return migrationResult(item, "cancelled", "job_stopped", decision.action,
                    facts.previewLength, false, false);
        }
        String targetPath = Migration.migrationTargetPath(row.thumbnailFile, row.fileName);
        File targetFile = safeStoragePath(targetPath);
        if (targetFile == null) {
            return migrationResult(item, "failed", "migrate_path_unsafe", decision.action,
                    facts.previewLength, false, false);
        }
        // 5a：目标上已经是完整原图（长度等于行 size、摘要等于注册表原文摘要）时不再搬文件，
        // 只把行修回原路径 —— 这时路径上的字节就是原图，状态该回落 ORIGINAL 而不是
        // INPLACE_PREVIEW，否则上传分类会把一张真原图永久当成预览。
        if (decision.repairOnly) {
            int updated = updateMigratedRow(context, item.row, row.sha1, targetFile);
            if (updated != 1) {
                return migrationResult(item, "failed", "migrate_db_update_failed",
                        decision.action, 0L, false, false);
            }
            String failure = markOriginalAtPath(config, store, record, targetFile);
            if (!failure.isEmpty()) {
                Logger.w("event=migrate_inplace_registry_warn row=" + item.row + " reason=" + failure);
            }
            deleteEmptySlimDirectory(preview);
            Logger.i("event=migrate_inplace_done row=" + item.row + " bytes=" + facts.previewLength
                    + " dryRun=false");
            return migrationResult(item, "success", "", decision.action, facts.previewLength,
                    true, false);
        }
        String previewSha256;
        try {
            previewSha256 = sha256(preview);
        } catch (Throwable failure) {
            return migrationResult(item, "failed", "migrate_hash_failed", decision.action,
                    facts.previewLength, false, false);
        }
        // 5b：同卷原子改名。FUSE 上 ATOMIC_MOVE 可能不支持，退回普通 move（源已消失时它在
        // 目标已存在这一点上会失败，因此不会静默覆盖别的东西）。
        try {
            Files.move(preview.toPath(), targetFile.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            try {
                Files.move(preview.toPath(), targetFile.toPath());
            } catch (Throwable failure) {
                return migrationResult(item, "failed", "migrate_move_failed", decision.action,
                        facts.previewLength, false, false);
            }
        } catch (Throwable failure) {
            return migrationResult(item, "failed", "migrate_move_failed", decision.action,
                    facts.previewLength, false, false);
        }
        // 5c：长度必须原样；摘要前后各算一次，既证明搬动没改字节，也是注册表要记的 previewSha256。
        if (!targetFile.isFile() || targetFile.length() != facts.previewLength) {
            rollbackMigrationMove(targetFile, preview);
            return migrationResult(item, "failed", "migrate_verify_failed", decision.action,
                    facts.previewLength, false, false);
        }
        String movedSha256;
        try {
            movedSha256 = sha256(targetFile);
        } catch (Throwable failure) {
            rollbackMigrationMove(targetFile, preview);
            return migrationResult(item, "failed", "migrate_hash_failed", decision.action,
                    facts.previewLength, false, false);
        }
        if (!movedSha256.equalsIgnoreCase(previewSha256)) {
            rollbackMigrationMove(targetFile, preview);
            return migrationResult(item, "failed", "migrate_verify_failed", decision.action,
                    facts.previewLength, false, false);
        }
        // 5d：行改成“本地就在原路径、不再有缩略图侧车”。按 _id + sha1 双条件写，0 行说明
        // 行在迁移期间被改过，必须把文件搬回隐藏目录，不能留下无主预览。
        int updated = updateMigratedRow(context, item.row, row.sha1, targetFile);
        if (updated != 1) {
            rollbackMigrationMove(targetFile, preview);
            return migrationResult(item, "failed", "migrate_db_update_failed", decision.action,
                    facts.previewLength, false, false);
        }
        // 5e：注册表登记“原路径上的字节是预览”。找不到记录只告警，不回滚 —— 文件与行已经
        // 一致，回滚只会把一次成功的迁移再拆掉。
        if (record == null) {
            Logger.w("event=migrate_inplace_registry_warn row=" + item.row + " reason=no_record");
        } else {
            OriginalStore.Record committed = store.markInplaceCommitted(config, record,
                    facts.previewLength, previewSha256, facts.previewFormat);
            if (committed == null) {
                Logger.w("event=migrate_inplace_registry_warn row=" + item.row
                        + " reason=mark_failed");
            }
        }
        // 5f：只删空目录；里面还有转码/侧车保留的预览时什么都不做。
        deleteEmptySlimDirectory(preview);
        Logger.i("event=migrate_inplace_done row=" + item.row + " bytes=" + facts.previewLength
                + " dryRun=false");
        return migrationResult(item, "success", "", decision.action, facts.previewLength,
                true, false);
    }

    /**
     * TRANSCODE：预览是 JPEG、原名是 HEIC 这类“容器不同，不能改名”的行。
     *
     * <p>做法不是重编码预览，而是把远端完整原图取回归属路径：{@code restoreOriginal} 会自己
     * 按登记长度/SHA-256 校验远端字节并原子发布；这里再独立复核一次磁盘上的长度与摘要，
     * 然后把相册行改回“本地就在原路径、没有侧车缩略图”，最后把注册表状态落回
     * {@code ORIGINAL} 并删掉隐藏目录里那张预览。任何一步失败都保持现状（宁可下次重试，
     * 也不留下行、文件、注册表三边不一致的半成品）。
     */
    private ItemResult migrateTranscodeOne(Context context, Config config, OriginalStore store,
            String target, RequestItem item, MigrationRow row, OriginalStore.Record record,
            File preview, boolean dryRun) throws Exception {
        String targetPath = Migration.migrationTargetPath(row.thumbnailFile, row.fileName);
        File targetFile = safeStoragePath(targetPath);
        if (targetFile == null) {
            return migrationResult(item, "failed", "migrate_path_unsafe",
                    InplaceSlimPolicy.MigrationAction.TRANSCODE, 0L, false, false);
        }
        TranscodeFacts facts = new TranscodeFacts();
        facts.rowExists = true;
        facts.recordExists = record != null;
        facts.target = targetFile;
        facts.preview = preview;
        facts.rowId = item.row;
        facts.sha1 = row.sha1;
        facts.originalLength = record == null ? 0L : record.originalLength;
        facts.originalSha256 = record == null ? null : record.sha256;
        facts.dryRun = dryRun;
        TranscodeOutcome outcome = Transcode.run(facts, new TranscodeOps() {
            @Override
            public boolean hasRequiredSpace(long originalLength) {
                return MetadataRestoreJob.hasRequiredSpace(originalLength);
            }

            @Override
            public boolean isCurrent() {
                return MetadataRestoreJob.this.isCurrent(target);
            }

            @Override
            public File restoreOriginal(File destination) throws Exception {
                return store.restoreOriginal(config, record, destination);
            }

            @Override
            public int updateRow(String rowId, String sha1, File destination) {
                return updateMigratedRow(context, rowId, sha1, destination);
            }

            @Override
            public boolean markOriginal(File destination) {
                return store.markRestoredOriginal(config, record, destination) != null;
            }
        });
        if (outcome.migrated) {
            Logger.i("event=migrate_transcode_done row=" + item.row + " bytes=" + outcome.bytes
                    + " target=" + targetFile.getPath());
        } else if (outcome.planned) {
            Logger.i("event=migrate_transcode_planned row=" + item.row + " bytes=" + outcome.bytes
                    + " target=" + targetFile.getPath() + " dryRun=true");
        }
        return migrationResult(item, outcome.status, outcome.reason,
                InplaceSlimPolicy.MigrationAction.TRANSCODE, outcome.bytes, outcome.migrated,
                outcome.planned);
    }

    /** 把行改成“本地在 {@code targetFile}、没有缩略图侧车”；影响行数由调用者校验。 */
    private static int updateMigratedRow(Context context, String rowId, String sha1, File targetFile) {
        ContentValues values = new ContentValues(2);
        values.put("localFile", targetFile.getPath());
        values.put("thumbnailFile", "");
        try {
            return context.getContentResolver().update(CLOUD_URI, values,
                    "_id = ? AND sha1 = ?", new String[]{rowId, sha1});
        } catch (Throwable failure) {
            return 0;
        }
    }

    /**
     * 迁移失败时把文件搬回隐藏目录。
     *
     * <p>搬不回去只记日志：下一次 dry-run 会看到“目标已存在”并给出 migrate_target_exists，
     * 人可以从那个理由倒推出磁盘上的中间态。
     */
    private static void rollbackMigrationMove(File moved, File preview) {
        try {
            File parent = preview.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Files.move(moved.toPath(), preview.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable atomicFailure) {
            try {
                Files.move(moved.toPath(), preview.toPath());
            } catch (Throwable ignored) {
                Logger.w("event=migrate_inplace_rollback_failed path=" + moved.getPath());
            }
        }
    }

    /** 侧车目录空了才删；里面有没迁移的预览时保持原样。 */
    private static void deleteEmptySlimDirectory(File preview) {
        File directory = preview.getParentFile();
        if (directory == null) return;
        try {
            String[] remaining = directory.list();
            if (remaining != null && remaining.length == 0 && !directory.delete()) {
                Logger.d("event=migrate_inplace_directory_keep path=" + directory.getPath());
            }
        } catch (Throwable ignored) {
            Logger.d("event=migrate_inplace_directory_keep path=" + directory.getPath());
        }
    }

    /**
     * 把“归属路径上已经是完整原图”这件事落盘：非空 sha256 走 {@code restoreOriginal} 的
     * 快照分支（目标字节就是登记的原文，于是只调 {@code withRestoredLocal}，不发网络请求）。
     * 摘要未知时退回 {@code registerUploadedOriginal}：它按本次核过的长度/摘要把记录重建为
     * {@code ORIGINAL}，这是公开 API 里唯一不改字节就能把状态写回原图的路径。
     */
    private static String markOriginalAtPath(Config config, OriginalStore store,
            OriginalStore.Record record, File targetFile) {
        if (record == null) return "no_record";
        try {
            if (!isBlank(record.sha256)) {
                File restored = store.restoreOriginal(config, record, targetFile);
                return restored != null && restored.isFile() ? "" : "state_not_updated";
            }
            store.registerUploadedOriginal(config, record.remotePath, targetFile, record.rowId,
                    record.sha1, sha256(targetFile), targetFile.length(),
                    targetFile.lastModified());
            return "";
        } catch (Throwable failure) {
            return "state_not_updated";
        }
    }

    /**
     * repair_state：只修注册表状态，不动相册行、不删文件。
     *
     * <p>{@code PENDING_INPLACE}（进程死在第 6 步之前）与 {@code DAMAGED} 都靠它回到一个能判断
     * 的状态：本地字节证明是原图就回 {@code ORIGINAL}，证明是预览就记成原地预览，都不是才
     * 重新从远端取。判定顺序见 {@link RepairState#decision}。
     */
    private ItemResult repairStateOne(Context context, Config config, OriginalStore store,
            String target, RequestItem item) throws Exception {
        if (!isCurrent(target)) return repairResult(item, "cancelled", "job_stopped");
        OriginalStore.Record record = store.find(config, item.row, item.sha1);
        if (record == null) return repairResult(item, "failed", "no_registry_record");
        File destination = safeOriginalFile(record.originalLocalPath);
        if (destination == null) return repairResult(item, "failed", "repair_path_unsafe");
        RepairFacts facts = new RepairFacts();
        facts.recordExists = true;
        facts.alreadyOriginal = record.getLocalState() == OriginalStore.LocalState.ORIGINAL;
        facts.targetPresent = destination.isFile();
        facts.originalLength = record.originalLength;
        facts.originalSha256 = record.sha256;
        facts.previewLength = record.getPreviewLength();
        facts.previewSha256 = record.getPreviewSha256();
        if (facts.targetPresent) {
            facts.targetLength = destination.length();
            facts.targetSha256 = sha256(destination);
        }
        RepairDecision decision = RepairState.decision(facts);
        if (!decision.reason.isEmpty()) {
            String status = "repair_already_original".equals(decision.reason) ? "success"
                    : "repair_missing".equals(decision.reason) ? "skipped" : "failed";
            return repairResult(item, status, decision.reason);
        }
        switch (decision.action) {
            case RepairState.ACTION_RESTORE_LOCAL: {
                String failure = persistRestoredState(config, store, record, destination,
                        facts.targetSha256);
                if (!failure.isEmpty()) return repairResult(item, "failed", failure);
                Logger.i("event=repair_state_done row=" + item.row
                        + " reason=repair_original_restored");
                return repairResult(item, "success", "repair_original_restored");
            }
            case RepairState.ACTION_CONFIRM_PREVIEW: {
                OriginalStore.Record committed = store.markInplaceCommitted(config, record,
                        facts.previewLength, facts.previewSha256, record.getPreviewFormat());
                if (committed == null) return repairResult(item, "failed", "repair_failed");
                Logger.i("event=repair_state_done row=" + item.row
                        + " reason=repair_preview_confirmed");
                return repairResult(item, "success", "repair_preview_confirmed");
            }
            default: {
                if (!hasRequiredSpace(record.originalLength)) {
                    return repairResult(item, "skipped", "needs_space");
                }
                try {
                    File restored = store.restoreOriginal(config, record, destination);
                    if (restored != null && restored.isFile()
                            && restored.length() == record.originalLength) {
                        Logger.i("event=repair_state_done row=" + item.row
                                + " reason=repair_restored_from_remote");
                        return repairResult(item, "success", "repair_restored_from_remote");
                    }
                } catch (Throwable failure) {
                    Logger.w("event=repair_state_restore_failed row=" + item.row);
                }
                store.markDamaged(config, record, "repair_failed");
                return repairResult(item, "failed", "repair_failed");
            }
        }
    }

    /** 本地字节已经是完整原图：按 withRestoredLocal 的路径把状态写回 ORIGINAL。 */
    private static String persistRestoredState(Config config, OriginalStore store,
            OriginalStore.Record record, File destination, String digest) {
        try {
            if (!isBlank(record.sha256)) {
                File restored = store.restoreOriginal(config, record, destination);
                return restored != null && restored.isFile()
                        && restored.length() == record.originalLength
                        ? "" : "repair_failed";
            }
            store.registerUploadedOriginal(config, record.remotePath, destination, record.rowId,
                    record.sha1, digest, destination.length(), destination.lastModified());
            return "";
        } catch (Throwable failure) {
            Logger.w("event=repair_state_persist_failed row=" + record.rowId);
            return "repair_failed";
        }
    }

    // ------------------------------------------------- 原地迁移后的重复行收拾（reconcile_inplace）

    /**
     * reconcile_inplace：原地迁移把 {@code .gallerysync-slim/<原名>} 改名回相册原路径之后，
     * 相册扫描器会把这个“新出现的文件”再插一行（{@code _id} 更大）。于是同一 localFile 上有两行：
     * 幽灵行记着原图大小（几 MB）、keeper 行记着预览大小（几百 KB）。两行都没有 serverId，
     * 所以删幽灵行不影响云端。
     *
     * <p>证据链（任一不成立就拒，绝不删行）：路径在存储根内且是普通文件 → 行按 _id 存在 →
     * 行是幽灵形状（localFile 逐字相符、thumbnailFile 空、serverId 空）→ 行 size 与文件真实
     * 大小不同 → 同路径恰好还有 1 行，且它的 size 就是文件真实大小、无 serverId、无缩略图。
     * 删除后回读确认行真的没了，最后把注册表里指向这张照片的记录改绑到 keeper 行。
     */
    private ItemResult reconcileInplaceOne(Context context, Config config, OriginalStore store,
            String target, RequestItem item) {
        if (!isCurrent(target)) return reconcileResult(item, "cancelled", "job_stopped", "");
        // a. 路径闸门：根目录内、规范化后仍在根内、是普通文件。
        File file = reconcileTarget(item.path);
        if (file == null) return reconcileResult(item, "failed", "reconcile_path_unsafe", "");
        if (!isCurrent(target)) return reconcileResult(item, "cancelled", "job_stopped", "");
        // b–e. 取行、采事实、纯逻辑判定。判定层不碰 Android，单测直接钉它的每一条拒绝理由。
        CloudRow ghost = readCloudRow(context, item.row, null, null);
        ReconcileFacts facts = collectReconcileFacts(context, item, file, ghost);
        String failure = Reconcile.decision(facts);
        if (!failure.isEmpty()) return reconcileResult(item, "failed", failure, "");
        long ghostId = parseRowId(item.row);
        if (ghostId <= 0L) return reconcileResult(item, "failed", "reconcile_row_missing", "");
        if (!isCurrent(target)) return reconcileResult(item, "cancelled", "job_stopped", "");
        // f. 与其余删除通道同一套可验证判据：API 抛异常或删完回读仍能看到行都算失败。
        boolean invoked = false;
        try {
            Class<?> cloudUtils = mLoader.loadClass("com.miui.gallery.provider.CloudUtils");
            Method deleteById = cloudUtils.getMethod("deleteById", Context.class, int.class,
                    int.class, long[].class);
            deleteById.invoke(null, context, 1, 0, new long[]{ghostId});
            invoked = true;
        } catch (Throwable deleteFailure) {
            invoked = false;
        }
        if (!invoked || readCloudRow(context, item.row, null, null) != null) {
            return reconcileResult(item, "failed", "reconcile_delete_failed", "");
        }
        // g. 注册表改绑。找不到记录不是失败：行已经收拾干净，只是没有该照片的登记可改。
        boolean rebound = false;
        try {
            rebound = store.bindRowForLocalPath(config, file, facts.keeperRowId, facts.keeperSha1);
        } catch (Throwable bindFailure) {
            rebound = false;
        }
        Logger.i("event=reconcile_inplace_done row=" + item.row + " keeper="
                + facts.keeperRowId + " path=" + item.path + " rebound=" + rebound);
        return reconcileResult(item, "success", "", "rebound=" + rebound);
    }

    /**
     * reconcile_inplace 的路径闸门：复用 {@link #safeStoragePath}（根内 + 非符号链接），
     * 另外要求它确实是一个普通文件。不合法一律回 null。
     */
    static File reconcileTarget(String rawPath) {
        File file = safeStoragePath(rawPath);
        return file != null && file.isFile() ? file : null;
    }

    /** 采集判定需要的全部事实（幽灵行 + 磁盘长度 + 同路径的 keeper 行）；判定本身在 Reconcile。 */
    private static ReconcileFacts collectReconcileFacts(Context context, RequestItem item,
            File file, CloudRow ghost) {
        ReconcileFacts facts = new ReconcileFacts();
        facts.rowExists = ghost != null;
        if (ghost == null) return facts;
        facts.localFile = ghost.localFile;
        // “逐字等于 path”是硬条件：Gallery 会对路径做自己的规范化，大小写/相对写法不同就说明
        // 这一行不是我们迁移后留下的那一行，删它可能毁掉别的照片的登记。
        facts.localFileMatches = item.path.equals(ghost.localFile);
        facts.thumbnailEmpty = isBlank(ghost.thumbnailFile);
        facts.serverIdEmpty = isBlank(ghost.serverId);
        facts.fileLength = file.length();
        facts.rowSize = ghost.size;
        // 只有 localFile 逐字相符时才去查同路径的其它行：否则这一行根本不归本项管。
        if (!facts.localFileMatches) return facts;
        List<ReconcileRow> keepers = readReconcileKeepers(context, ghost.localFile, item.row);
        facts.keeperCount = keepers.size();
        if (keepers.size() == 1) {
            ReconcileRow keeper = keepers.get(0);
            facts.keeperRowId = keeper.rowId;
            facts.keeperSha1 = keeper.sha1;
            facts.keeperSize = keeper.size;
            facts.keeperSizeMatches = keeper.size == facts.fileLength;
            facts.keeperServerIdEmpty = isBlank(keeper.serverId);
            facts.keeperThumbnailEmpty = isBlank(keeper.thumbnailFile);
        }
        return facts;
    }

    /**
     * 查同一 localFile 上的其它行（{@code _id != 幽灵行}）。
     *
     * <p>查询失败按“没有 keeper”处理：证据缺失时宁可拒掉这一项，也不能把行删掉。
     */
    private static List<ReconcileRow> readReconcileKeepers(Context context, String localFile,
            String ghostId) {
        List<ReconcileRow> rows = new ArrayList<>();
        if (isBlank(localFile)) return rows;
        try (Cursor cursor = context.getContentResolver().query(CLOUD_URI,
                RECONCILE_KEEPER_COLUMNS, "localFile = ? AND _id != ?",
                new String[]{localFile, ghostId}, null)) {
            if (cursor == null) return rows;
            while (cursor.moveToNext()) {
                String rowId = stringColumn(cursor, "_id");
                // selection 已经排除幽灵行；provider 忽略 selection 时这里再兜底一次。
                if (rowId == null || rowId.equals(ghostId)) continue;
                rows.add(new ReconcileRow(rowId, stringColumn(cursor, "localFile"),
                        stringColumn(cursor, "thumbnailFile"), stringColumn(cursor, "sha1"),
                        stringColumn(cursor, "serverId"), longColumn(cursor, "size")));
            }
        } catch (Throwable ignored) {
            rows.clear();
        }
        return rows;
    }

    /** reconcile_inplace 的一条结果：row 恒为幽灵行 _id，message 只在成功时写 rebound。 */
    private static ItemResult reconcileResult(RequestItem item, String status, String reason,
            String message) {
        return new ItemResult(item.row, status, reason, 0, 0, 0, null, null, 0L, false, false,
                message);
    }

    /** 每一条 migrate_inplace 结果都带 mode/action/bytes，跳过与 dry-run 不算失败。 */
    private static ItemResult migrationResult(RequestItem item, String status, String reason,
            InplaceSlimPolicy.MigrationAction action, long bytes, boolean migrated,
            boolean planned) {
        return new ItemResult(item.row, status, reason, 0, 0, 0, "migrate_inplace",
                migrationOutcomeName(action), bytes, migrated, planned);
    }

    private static ItemResult repairResult(RequestItem item, String status, String reason) {
        return new ItemResult(item.row, status, reason, 0, 0, 0, "repair_state", null, 0L,
                false, false);
    }

    /** 结果 JSON / 日志里的迁移动作名；null 回空串。 */
    static String migrationOutcomeName(InplaceSlimPolicy.MigrationAction action) {
        if (action == null) return "";
        switch (action) {
            case RENAME: return "rename";
            case TRANSCODE: return "transcode";
            case SIDECAR: return "sidecar";
            case SKIP: return "skip";
            default: return "";
        }
    }

    /**
     * 只接受共享存储根目录内的普通路径：规范化后再比对前缀，符号链接指向根目录之外时
     * 规范化结果会露馅，直接拒绝。{@code null}/空串/根目录之外/符号链接一律回 null。
     */
    private static File safeStoragePath(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) return null;
        try {
            File root = new File(STORAGE_ROOT).getCanonicalFile();
            File candidate = new File(rawPath).getCanonicalFile();
            if (!candidate.getPath().startsWith(root.getPath() + File.separator)) return null;
            if (Files.isSymbolicLink(candidate.toPath())) return null;
            return candidate;
        } catch (IOException ignored) {
            return null;
        }
    }

    /** 迁移要看的行事实：两个路径字段、文件名、登记字节数、sha1 与 serverType。 */
    private static MigrationRow readMigrationRow(Context context, String rowId, String sha1) {
        try (Cursor cursor = context.getContentResolver().query(CLOUD_URI,
                new String[]{"_id", "sha1", "localFile", "thumbnailFile", "fileName", "size",
                        "serverType"},
                "_id = ?", new String[]{rowId}, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            String storedRowId = stringColumn(cursor, "_id");
            String storedSha1 = stringColumn(cursor, "sha1");
            if (storedRowId == null || !storedRowId.equals(rowId)
                    || storedSha1 == null || !storedSha1.equalsIgnoreCase(sha1)) {
                return null;
            }
            MigrationRow row = new MigrationRow(storedRowId, stringColumn(cursor, "localFile"),
                    stringColumn(cursor, "thumbnailFile"), stringColumn(cursor, "fileName"),
                    stringColumn(cursor, "sha1"), longColumn(cursor, "size"),
                    stringColumn(cursor, "serverType"));
            if (cursor.moveToNext()) return null;
            return row;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 读一行相册云行。{@code sha1} 传 null 表示调用方不校验指纹（reconcile_inplace 的幽灵行
     * 只按 {@code _id} 精确匹配）；{@code record} 传 null 时不要求路径属于某条注册表记录。
     */
    private static CloudRow readCloudRow(Context context, String rowId, String sha1,
            OriginalStore.Record record) {
        try (Cursor cursor = context.getContentResolver().query(CLOUD_URI,
                new String[]{"_id", "sha1", "localFile", "thumbnailFile", "dateTaken",
                        "mixedDateTime", "dateModified", "realDateModified", "size", "serverId"},
                "_id = ?", new String[]{rowId}, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            int idColumn = cursor.getColumnIndex("_id");
            int shaColumn = cursor.getColumnIndex("sha1");
            int localColumn = cursor.getColumnIndex("localFile");
            int thumbnailColumn = cursor.getColumnIndex("thumbnailFile");
            if (idColumn < 0 || localColumn < 0 || !rowId.equals(cursor.getString(idColumn))) {
                return null;
            }
            if (sha1 != null && (shaColumn < 0
                    || !sha1.equalsIgnoreCase(cursor.getString(shaColumn)))) return null;
            String gallerySha1 = shaColumn < 0 || cursor.isNull(shaColumn)
                    ? null : cursor.getString(shaColumn);
            boolean localNull = cursor.isNull(localColumn);
            String local = localNull ? null : cursor.getString(localColumn);
            String thumbnail = thumbnailColumn < 0 || cursor.isNull(thumbnailColumn)
                    ? null : cursor.getString(thumbnailColumn);
            Map<String, Long> dates = readPositiveDates(cursor);
            if (cursor.moveToNext()) return null;
            if (record != null && !isRegisteredPath(local, record)
                    && !isRegisteredPath(thumbnail, record)) {
                return null;
            }
            return new CloudRow(gallerySha1, local, localNull, dates, thumbnail,
                    stringColumn(cursor, "serverId"), longColumn(cursor, "size"));
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
        if (record.sha256 == null) {
            // 接管登记没有摘要：分不清这份副本是不是原图，按 fail-closed 拒绝
            return CleanupResult.failed("original_digest_unknown");
        }
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
        // 根上的 mode：缺省是老的逐项模式；只认 "takeover"，别的值整单拒掉（fail-closed：
        // 一个拼错模式的请求绝不能退化成逐项 restore 去动相册行）。
        String requestMode = root.has("mode") && !root.isNull("mode")
                ? root.getString("mode") : "";
        if (!requestMode.isEmpty() && !TAKEOVER_MODE.equals(requestMode)) {
            throw new IOException("invalid_request_mode");
        }
        // 请求根上的 dryRun：缺省 false，只接受布尔。它只影响 migrate_inplace，其余模式忽略。
        boolean dryRun = parseDryRun(root.has("dryRun") && !root.isNull("dryRun")
                ? root.get("dryRun") : null);
        if (TAKEOVER_MODE.equals(requestMode)) {
            // 接管模式自己按相册行枚举，形状与逐项请求完全不同：出现 items 说明请求造错了，
            // 照老路解析会要求 1..MAX_ITEMS 条逐项清单，最后一条都不执行。
            if (root.has("items")) throw new IOException("takeover_items_not_allowed");
            boolean includeVideos = parseIncludeVideos(root.has("includeVideos")
                    && !root.isNull("includeVideos") ? root.get("includeVideos") : null);
            return new Request(jobId, target.toLowerCase(Locale.ROOT), new ArrayList<>(),
                    new ArrayList<>(), dryRun, TAKEOVER_MODE, includeVideos);
        }
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
            // mode 必须先读出来：poster_rewire 的 posterPath 是它唯一允许出现的路径键。
            String mode = item.has("mode") && !item.isNull("mode")
                    ? item.getString("mode") : "restore";
            java.util.Iterator<String> keys = item.keys();
            while (keys.hasNext()) {
                String key = keys.next().toLowerCase(Locale.ROOT);
                // 这个键是"要挂到视频行上的封面路径"——只读证据，不是要写入的目的地。放行范围
                // 收紧到"mode 恰为 poster_rewire 且键名恰为 posterpath"，其余任何含
                // path/destination 的键（包括其它模式的 posterPath）仍然整单拒掉。
                if ("poster_rewire".equals(mode) && "posterpath".equals(key)) continue;
                // reconcile_inplace 的 path 是“要收拾的那张照片的绝对路径”——只读证据，不是
                // 写入目的地：运行期还要过 reconcile_path_unsafe 的根目录/普通文件校验。
                if ("reconcile_inplace".equals(mode) && "path".equals(key)) continue;
                if (key.contains("path") || key.contains("destination")) {
                    throw new IOException("request_path_not_allowed");
                }
            }
            if (!isSupportedItemMode(mode)) {
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
            // poster_rewire 同理：它的证据是"图片行 + 封面文件 + 视频行"，也不借 base 行。
            // 本波的两个新模式只带 row/sha1，同样不接受 base 行。
            if ("purge_items".equals(mode) || "poster_rewire".equals(mode)
                    || "reconcile_inplace".equals(mode) || isMigrationMode(mode)) {
                java.util.Iterator<String> purgeKeys = item.keys();
                while (purgeKeys.hasNext()) {
                    String key = purgeKeys.next().toLowerCase(Locale.ROOT);
                    if ("baserow".equals(key) || "basesha1".equals(key)) {
                        throw new IOException("invalid_item_mode");
                    }
                }
            }
            // 新模式的 row/sha1 缺失必须给出稳定的 reason（parseRequest 的异常消息会进结果根的
            // reason），不能让它退化成 JSONException 驱动的 internal_error。
            if (isMigrationMode(mode)) {
                String invalidItem = newModeItemInvalidReason(mode,
                        item.has("row") && !item.isNull("row"),
                        item.has("sha1") && !item.isNull("sha1"));
                if (!invalidItem.isEmpty()) throw new IOException(invalidItem);
            }
            // reconcile_inplace 只需要 row：它按 _id 精确取行，不校验 sha1（幽灵行的 sha1 是
            // 扫描器给预览文件现算的，与请求侧无关）。缺 row 仍要给稳定 reason。
            if ("reconcile_inplace".equals(mode) && (!item.has("row") || item.isNull("row"))) {
                throw new IOException("missing_item_row");
            }
            String row = item.getString("row");
            String sha1;
            if ("reconcile_inplace".equals(mode)) {
                sha1 = item.has("sha1") && !item.isNull("sha1") ? item.getString("sha1") : "";
            } else {
                sha1 = item.getString("sha1");
            }
            // legacy_copies 与 stale_rows 的条目没有 sha256；其余模式保持原来的取值顺序与异常行为。
            boolean optionalSha256 = "legacy_copies".equals(mode) || "stale_rows".equals(mode)
                    || "broken_payload".equals(mode) || "orphan_rows".equals(mode)
                    || "purge_items".equals(mode) || "poster_rewire".equals(mode)
                    || "reconcile_inplace".equals(mode) || isMigrationMode(mode);
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
            // poster_rewire 也必须给出 length：它既是图片行的 size，也是封面文件的字节数；
            // orphan_rows 面对的行根本没有本地文件，没有长度可言，length 一律可选；
            // 本波两个新模式的长度证据分别来自相册行的 size 与注册表，length 同样可选。
            Object rawLength = "stale_rows".equals(mode) || "broken_payload".equals(mode)
                    || "orphan_rows".equals(mode) || "purge_items".equals(mode)
                    || "poster_rewire".equals(mode) || "reconcile_inplace".equals(mode)
                    || isMigrationMode(mode)
                    ? (item.has("length") && !item.isNull("length") ? item.get("length") : null)
                    : item.get("length");
            if (rawLength == null && ("broken_payload".equals(mode) || "purge_items".equals(mode)
                    || "poster_rewire".equals(mode) || "reconcile_inplace".equals(mode))) {
                throw new IOException("invalid_item_length");
            }
            boolean sha256Valid = optionalSha256
                    ? sha256 == null || sha256.matches("(?i)[0-9a-f]{64}")
                    : sha256 != null && sha256.matches("(?i)[0-9a-f]{64}");
            boolean lengthValid = rawLength == null
                    || rawLength instanceof Integer || rawLength instanceof Long;
            // reconcile_inplace 的 sha1 可以为空（只按 _id 取行）；其余模式必须 40 位十六进制。
            boolean sha1Valid = sha1.matches("(?i)[0-9a-f]{40}")
                    || ("reconcile_inplace".equals(mode) && sha1.isEmpty());
            if (row.isEmpty() || row.length() > 128 || !sha1Valid
                    || !sha256Valid || !lengthValid) {
                throw new IOException("invalid_item_metadata");
            }
            long length = 0L;
            if (rawLength != null) {
                length = ((Number) rawLength).longValue();
                if (length <= 0L) throw new IOException("invalid_item_length");
            }
            // poster_rewire 专有的两个字段：封面绝对路径与视频行 _id。这里只查"形状"（是字符串、
            // 是绝对路径、长度上界），"是否真的在存储根目录下/是否真的是文件"留给运行期判定
            // （poster_path_unsafe / poster_file_missing），因为那需要看磁盘。
            String posterPath = null;
            String videoRow = null;
            if ("poster_rewire".equals(mode)) {
                Object rawPosterPath = item.has("posterPath") && !item.isNull("posterPath")
                        ? item.get("posterPath") : null;
                if (!(rawPosterPath instanceof String)) throw new IOException("invalid_item_mode");
                posterPath = (String) rawPosterPath;
                if (!posterPath.startsWith("/") || posterPath.length() > MAX_POSTER_PATH) {
                    throw new IOException("invalid_item_mode");
                }
                Object rawVideoRow = item.has("videoRow") && !item.isNull("videoRow")
                        ? item.get("videoRow") : null;
                if (!(rawVideoRow instanceof String)) {
                    throw new IOException("invalid_item_metadata");
                }
                videoRow = (String) rawVideoRow;
                if (videoRow.isEmpty() || videoRow.length() > 128) {
                    throw new IOException("invalid_item_metadata");
                }
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
            // reconcile_inplace 专有的两个字段：照片绝对路径（必须给出）与预览真实字节数（可选）。
            // 这里只查形状（是字符串、以 / 开头、长度上界、正整数），“是否真在存储根内/是不是
            // 普通文件”留给运行期判定（reconcile_path_unsafe），因为那需要看磁盘。
            String reconcilePath = null;
            long previewLength = 0L;
            if ("reconcile_inplace".equals(mode)) {
                Object rawPath = item.has("path") && !item.isNull("path") ? item.get("path") : null;
                if (!(rawPath instanceof String)) throw new IOException("invalid_item_mode");
                reconcilePath = (String) rawPath;
                if (!reconcilePath.startsWith("/")
                        || reconcilePath.length() > MAX_RECONCILE_PATH) {
                    throw new IOException("invalid_item_mode");
                }
                if (item.has("previewLength") && !item.isNull("previewLength")) {
                    Object rawPreviewLength = item.get("previewLength");
                    if (!(rawPreviewLength instanceof Integer)
                            && !(rawPreviewLength instanceof Long)) {
                        throw new IOException("invalid_item_mode");
                    }
                    previewLength = ((Number) rawPreviewLength).longValue();
                    if (previewLength <= 0L) throw new IOException("invalid_item_mode");
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
                    diskLength, posterPath, videoRow, reconcilePath, previewLength));
        }
        return new Request(jobId, target.toLowerCase(Locale.ROOT), items, tempFiles, dryRun);
    }

    /**
     * 请求根上的 dryRun：缺省（或显式 null）为 false，只接受 JSON 布尔；其它类型整单拒绝。
     *
     * <p>抽成 static 纯函数是为了能在 JVM 单测里直接钉住三条分支 —— 这个值决定“要不要真的
     * 搬文件”，解析错了就是把一次演练变成一次不可撤销的迁移。
     */
    static boolean parseDryRun(Object raw) throws IOException {
        if (raw == null) return false;
        if (raw instanceof Boolean) return ((Boolean) raw).booleanValue();
        throw new IOException("invalid_dry_run");
    }

    /**
     * 请求根上的 includeVideos：缺省（或显式 null）为 true，只接受 JSON 布尔。
     *
     * <p>抽成纯函数与 {@link #parseDryRun} 同一个理由：它决定“视频要不要一起接管”，
     * 解析错了就会把视频行按图片行的尺寸规则去比，结果是一整批误导出的跳过。
     */
    static boolean parseIncludeVideos(Object raw) throws IOException {
        if (raw == null) return true;
        if (raw instanceof Boolean) return ((Boolean) raw).booleanValue();
        throw new IOException("invalid_include_videos");
    }

    /** 请求条目模式白名单：老模式逐条保留，本波新增两个原地迁移模式与重复行收拾模式。 */
    static boolean isSupportedItemMode(String mode) {
        return "restore".equals(mode) || "unslim".equals(mode) || "legacy_copies".equals(mode)
                || "stale_rows".equals(mode) || "broken_payload".equals(mode)
                || "orphan_rows".equals(mode) || "purge_items".equals(mode)
                || "poster_rewire".equals(mode) || "migrate_inplace".equals(mode)
                || "repair_state".equals(mode) || "reconcile_inplace".equals(mode);
    }

    /** 本波新增的两个模式：都不带 sha256/length，也都需要先按 row/sha1 找记录或相册行。 */
    static boolean isMigrationMode(String mode) {
        return "migrate_inplace".equals(mode) || "repair_state".equals(mode);
    }

    /**
     * 新模式的条目形状校验：{@code row} 与 {@code sha1} 必须给出。
     *
     * <p>返回值直接当 {@code IOException} 的消息用，因此它会成为结果根上的 {@code reason}；
     * 老模式不走这里，缺 row/sha1 仍然按 JSONException 的原有路径失败（行为不变）。
     */
    static String newModeItemInvalidReason(String mode, boolean hasRow, boolean hasSha1) {
        if (!isMigrationMode(mode)) return "";
        if (!hasRow) return "missing_item_row";
        if (!hasSha1) return "missing_item_sha1";
        return "";
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
            // 本波两个模式的“跳过”与 dryRun 的“计划”都不是失败：一次演练跑完 3000 项
            // 不该把整单报成 partial；真正的失败仍是 failed/partial。
            boolean tolerated = result.mode != null
                    && ("skipped".equals(result.status) || "planned".equals(result.status));
            if (!"success".equals(result.status) && !tolerated) return true;
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
            int migrated = 0;
            int planned = 0;
            int migrationSkipped = 0;
            int migrationFailed = 0;
            boolean countedMigration = false;
            Map<String, Integer> reasonCounts = new LinkedHashMap<>();
            for (ItemResult result : results) {
                JSONObject item = new JSONObject();
                item.put("row", result.row);
                item.put("status", result.status);
                if (!result.reason.isEmpty()) item.put("reason", result.reason);
                // 只有 reconcile_inplace 成功时会写（rebound=true/false）；老请求的结果 JSON 不变。
                if (!result.message.isEmpty()) item.put("message", result.message);
                if (result.mode != null) {
                    // 只有本波两个模式才写这些键：老请求的结果 JSON 保持逐字不变。
                    countedMigration = true;
                    item.put("mode", result.mode);
                    if (result.action != null && !result.action.isEmpty()) {
                        item.put("action", result.action);
                    }
                    if (result.bytes > 0L) item.put("bytes", result.bytes);
                    if (result.migrated) migrated++;
                    if (result.planned) planned++;
                    if ("skipped".equals(result.status)) migrationSkipped++;
                    if ("failed".equals(result.status)) migrationFailed++;
                    if (!result.reason.isEmpty()) {
                        Integer previous = reasonCounts.get(result.reason);
                        reasonCounts.put(result.reason, previous == null ? 1 : previous + 1);
                    }
                }
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
            if (countedMigration) {
                // 迁移相关的四个计数只在请求里真的出现新模式时写：老请求的结果根保持原样。
                // dryRun 下 migrated 恒为 0、planned 才是“本会迁移”的项数。
                root.put("migrated", migrated);
                root.put("skipped", migrationSkipped);
                root.put("failed", migrationFailed);
                if (planned > 0) root.put("planned", planned);
                if (!reasonCounts.isEmpty()) {
                    JSONObject reasons = new JSONObject();
                    for (Map.Entry<String, Integer> entry : reasonCounts.entrySet()) {
                        reasons.put(entry.getKey(), entry.getValue().intValue());
                    }
                    root.put("reasons", reasons);
                }
            }
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
        /** 请求根上的 dryRun：只对 migrate_inplace 有意义，缺省 false。 */
        final boolean dryRun;
        /** 请求根上的 mode："" 是老逐项模式，{@link #TAKEOVER_MODE} 是自枚举接管。 */
        final String mode;
        /** takeover 是否连视频一起接管；其它模式恒为 true（不读）。 */
        final boolean includeVideos;

        Request(String jobId, String target, List<RequestItem> items, List<String> tempFiles,
                boolean dryRun) {
            this(jobId, target, items, tempFiles, dryRun, "", true);
        }

        Request(String jobId, String target, List<RequestItem> items, List<String> tempFiles,
                boolean dryRun, String mode, boolean includeVideos) {
            this.jobId = jobId;
            this.target = target;
            this.items = items;
            this.tempFiles = tempFiles;
            this.dryRun = dryRun;
            this.mode = mode == null ? "" : mode;
            this.includeVideos = includeVideos;
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
        /** poster_rewire 要挂到视频行上的封面绝对路径；其它模式为 null。 */
        final String posterPath;
        /** poster_rewire 的视频行 _id；其它模式为 null。 */
        final String videoRow;
        /** reconcile_inplace 的照片绝对路径；其它模式为 null。 */
        final String path;
        /** reconcile_inplace 的预览真实字节数（可选提示）；未提供时为 0。 */
        final long previewLength;

        RequestItem(String row, String sha1, String sha256, long length, List<Long> relatedRows,
                String mode, String baseRow, String baseSha1) {
            this(row, sha1, sha256, length, relatedRows, mode, baseRow, baseSha1, 0L);
        }

        RequestItem(String row, String sha1, String sha256, long length, List<Long> relatedRows,
                String mode, String baseRow, String baseSha1, long diskLength) {
            this(row, sha1, sha256, length, relatedRows, mode, baseRow, baseSha1, diskLength,
                    null, null);
        }

        RequestItem(String row, String sha1, String sha256, long length, List<Long> relatedRows,
                String mode, String baseRow, String baseSha1, long diskLength, String posterPath,
                String videoRow) {
            this(row, sha1, sha256, length, relatedRows, mode, baseRow, baseSha1, diskLength,
                    posterPath, videoRow, null, 0L);
        }

        RequestItem(String row, String sha1, String sha256, long length, List<Long> relatedRows,
                String mode, String baseRow, String baseSha1, long diskLength, String posterPath,
                String videoRow, String path, long previewLength) {
            this.row = row;
            this.sha1 = sha1;
            this.sha256 = sha256;
            this.length = length;
            this.relatedRows = relatedRows;
            this.mode = mode;
            this.baseRow = baseRow;
            this.baseSha1 = baseSha1;
            this.diskLength = diskLength;
            this.posterPath = posterPath;
            this.videoRow = videoRow;
            this.path = path;
            this.previewLength = previewLength;
        }
    }

    /** unslim 模式在条目内的累计计数。 */
    private static final class UnslimState {
        int clearedRows;
        int deletedCopies;
    }

    /** takeover 的一行候选：相册行字段 + 运行期解析出来的远端路径。 */
    private static final class TakeoverRow {
        final long rowId;
        final String sha1;
        final long size;
        final String localFile;
        final String fileName;
        final int serverType;
        final int localFlag;
        final String serverId;
        /** 本地文件最后修改时间：按时间分目录的布局拿它当 upload date 的近似。 */
        final long mtime;
        String remotePath = "";

        TakeoverRow(long rowId, String sha1, long size, String localFile, String fileName,
                int serverType, int localFlag, String serverId, long mtime) {
            this.rowId = rowId;
            this.sha1 = sha1;
            this.size = size;
            this.localFile = localFile;
            this.fileName = fileName;
            this.serverType = serverType;
            this.localFlag = localFlag;
            this.serverId = serverId;
            this.mtime = mtime;
        }
    }

    /** 远端索引里的一条命中：绝对远端路径 + 列目录时报的字节数。 */
    static final class RemoteHit {
        final String remotePath;
        final long size;

        RemoteHit(String remotePath, long size) {
            this.remotePath = remotePath;
            this.size = size;
        }
    }

    /** 索引 BFS 里的一个待列目录：路径 + 层数（根目录为第 0 层）。 */
    private static final class IndexDir {
        final String path;
        final int depth;

        IndexDir(String path, int depth) {
            this.path = path;
            this.depth = depth;
        }
    }

    /** 按文件名解析远端路径的结论：唯一命中、同名多义、还是没有。 */
    static final class RemoteResolve {
        enum Status { MATCH, AMBIGUOUS, MISSING }

        final Status status;
        /** 只有 {@code MATCH} 时非空 */
        final String remotePath;

        private RemoteResolve(Status status, String remotePath) {
            this.status = status;
            this.remotePath = remotePath;
        }

        static RemoteResolve matched(String remotePath) {
            return new RemoteResolve(Status.MATCH, remotePath);
        }

        static RemoteResolve ambiguous() {
            return new RemoteResolve(Status.AMBIGUOUS, null);
        }

        static RemoteResolve missing() {
            return new RemoteResolve(Status.MISSING, null);
        }
    }

    /** 接管一趟的计数：{@code planned} 是“本会登记”，{@code taken} 是真的写进去了。 */
    private static final class TakeoverRun {
        final boolean dryRun;
        final Map<String, Integer> reasons = new LinkedHashMap<>();
        boolean cancelled;
        /** 整趟失败（查询不了/建不了客户端）的原因；非空时结果 status=failed。 */
        String reason = "";
        int planned;
        int taken;
        int skipped;
        int failed;

        TakeoverRun(boolean dryRun) {
            this.dryRun = dryRun;
        }

        void count(String name) {
            Integer previous = reasons.get(name);
            reasons.put(name, previous == null ? 1 : previous + 1);
        }

        void skip(String name) {
            skipped++;
            count(name);
        }
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
        /** 本波两个新模式的名字；老模式为 null，结果 JSON 形状因此逐字不变。 */
        final String mode;
        /** 迁移动作名（rename/transcode/sidecar/skip）；非迁移模式为 null。 */
        final String action;
        /** 本次迁移搬动的字节数；未知为 0。 */
        final long bytes;
        /** 这一项真的把文件搬回原路径了（累计 migrated 用）。 */
        final boolean migrated;
        /** dryRun 下“本会迁移”的项（累计 planned 用）。 */
        final boolean planned;
        /** 只给 reconcile_inplace 用的附加说明（如 {@code rebound=false}）；其余模式为空串。 */
        final String message;

        ItemResult(String row, String status, String reason) {
            this(row, status, reason, 0, 0);
        }

        ItemResult(String row, String status, String reason, int repairedCopies, int repairedRows) {
            this(row, status, reason, repairedCopies, repairedRows, 0);
        }

        ItemResult(String row, String status, String reason, int repairedCopies, int repairedRows,
                int cleanedDuplicates) {
            this(row, status, reason, repairedCopies, repairedRows, cleanedDuplicates,
                    null, null, 0L, false, false);
        }

        ItemResult(String row, String status, String reason, int repairedCopies, int repairedRows,
                int cleanedDuplicates, String mode, String action, long bytes, boolean migrated,
                boolean planned) {
            this(row, status, reason, repairedCopies, repairedRows, cleanedDuplicates, mode,
                    action, bytes, migrated, planned, "");
        }

        ItemResult(String row, String status, String reason, int repairedCopies, int repairedRows,
                int cleanedDuplicates, String mode, String action, long bytes, boolean migrated,
                boolean planned, String message) {
            this.row = row;
            this.status = status;
            this.reason = reason == null ? "" : reason;
            this.repairedCopies = repairedCopies;
            this.repairedRows = repairedRows;
            this.cleanedDuplicates = cleanedDuplicates;
            this.mode = mode;
            this.action = action;
            this.bytes = bytes;
            this.migrated = migrated;
            this.planned = planned;
            this.message = message == null ? "" : message;
        }
    }

    private static final class CloudRow {
        final String localFile;
        final boolean localFileWasNull;
        final String sha1;
        final Map<String, Long> dates;
        /** 预览侧车路径；没有侧车时为 null。 */
        final String thumbnailFile;
        /** 远端 serverId；纯本地行（含 reconcile 的幽灵行）为 null。 */
        final String serverId;
        /** 行登记的字节数。 */
        final long size;

        CloudRow(String sha1, String localFile, boolean localFileWasNull, Map<String, Long> dates,
                String thumbnailFile, String serverId, long size) {
            this.sha1 = sha1;
            this.localFile = localFile;
            this.localFileWasNull = localFileWasNull;
            this.dates = dates;
            this.thumbnailFile = thumbnailFile;
            this.serverId = serverId;
            this.size = size;
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

    /** poster_rewire 的图片行快照：只保留判定需要的列。 */
    static final class PosterRowSnapshot {
        final String rowId;
        final String localFile;
        final String thumbnailFile;
        final String sha1;
        /** 行里登记的字节数（不是磁盘上的）：必须等于请求里的 length。 */
        final long size;

        PosterRowSnapshot(String rowId, String localFile, String thumbnailFile, String sha1,
                long size) {
            this.rowId = rowId;
            this.localFile = localFile;
            this.thumbnailFile = thumbnailFile;
            this.sha1 = sha1;
            this.size = size;
        }
    }

    /** poster_rewire 的视频行快照：只要"是不是 mp4"与"封面位是否还空着"两条。 */
    static final class VideoRowSnapshot {
        final String rowId;
        final String fileName;
        final String thumbnailFile;

        VideoRowSnapshot(String rowId, String fileName, String thumbnailFile) {
            this.rowId = rowId;
            this.fileName = fileName;
            this.thumbnailFile = thumbnailFile;
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

    /** poster_rewire 判定的输入事实：由 Android 侧采集，判定只读这些字段。 */
    public static final class PosterRewireFacts {
        public String posterRowId = "";
        public String videoRowId = "";
        /** 请求里的封面绝对路径：图片行的 thumbnailFile 必须与它逐字相等。 */
        public String posterPath = "";
        /** 请求里的封面字节数：图片行的 size 与磁盘文件长度都必须等于它。 */
        public long requestedLength;
        /** 图片行还在。 */
        public boolean posterRowExists;
        /** 图片行的 localFile 为 NULL 或空白（"只挂封面"的行本来就没有本地文件）。 */
        public boolean posterRowLocalFileBlank;
        /** 图片行的 thumbnailFile 与请求里的 posterPath 逐字相等。 */
        public boolean posterRowThumbnailMatches;
        /** 图片行的 sha1 非空且与请求一致（大小写不敏感）。 */
        public boolean posterRowSha1Matches;
        /** 图片行登记的 size 等于请求里的 length。 */
        public boolean posterRowLengthMatches;
        /** 封面路径是存储根目录下的绝对路径（不含空段、"."、".."）。 */
        public boolean posterPathSafe;
        /** 封面是一个存在的普通文件。 */
        public boolean posterFilePresent;
        /** 磁盘上这份封面的字节数等于请求的 length。 */
        public boolean posterFileLengthMatches;
        /** 引用行查询失败；失败时按"封面被别的行共享"保守处理。 */
        public boolean posterReferenceQueryFailed;
        /** 除图片行自身外，还有多少行的 localFile/thumbnailFile 指向这张封面。 */
        public int otherReferencingRows;
        /** 视频行还在。 */
        public boolean videoRowExists;
        /** 视频行的 fileName 以 .mp4 结尾（大小写不敏感）。 */
        public boolean videoNameIsMp4;
        /** 视频行的 thumbnailFile 为 NULL 或空白：封面位还空着，可以写进去。 */
        public boolean videoThumbnailBlank;
    }

    /**
     * poster_rewire 的纯逻辑判定：不依赖 Android、不碰磁盘，可直接单测。
     *
     * <p>拒绝理由集中在这里（也就是结果里上报的 reason），全部是"不动手"的理由：
     * <ul>
     *   <li>{@code poster_row_missing} 图片行已经不在了；</li>
     *   <li>{@code poster_row_local_file_not_empty} 图片行还有 localFile：它不是"只挂封面"的行；</li>
     *   <li>{@code poster_row_thumbnail_mismatch} 图片行的 thumbnailFile 与 posterPath 不一致；</li>
     *   <li>{@code poster_path_unsafe} 封面路径不在存储根目录下（相对路径、{@code ..} 逃逸、
     *       空段、超长）；</li>
     *   <li>{@code poster_file_missing} 封面不是一个存在的普通文件；</li>
     *   <li>{@code poster_file_length_mismatch} 封面文件的字节数与请求的 length 不一致；</li>
     *   <li>{@code poster_shared} 除图片行外还有别的行指向这张封面，或查询失败；</li>
     *   <li>{@code video_row_missing} 视频行不在了；</li>
     *   <li>{@code video_row_not_video} 视频行的 fileName 不以 {@code .mp4} 结尾；</li>
     *   <li>{@code video_thumbnail_occupied} 视频行的封面位已经被别的路径占着；</li>
     *   <li>{@code video_row_update_failed} 更新视频行失败或回读对不上（由执行侧产生）；</li>
     *   <li>{@code poster_row_delete_failed} 删除通道抛异常/拒绝执行（由执行侧产生）；</li>
     *   <li>{@code poster_row_still_present} 删除通道返回了，但回读仍能看到图片行（由执行侧产生）。</li>
     * </ul>
     *
     * <p>判定顺序与规格的处理步骤一一对应（图片行 → 封面文件 → 唯一引用 → 视频行），因此每条
     * 理由都只由它自己那一条触发；执行侧的两条复核（更新后回读、删除后回读）在判定之后发生。
     */
    public static final class PosterRewire {
        private PosterRewire() {
        }

        /**
         * 封面路径是不是存储根目录下的一条安全绝对路径。
         *
         * <p>只做字符串层面的判断（可在单测里跑）：必须以 {@code /storage/emulated/0/} 开头、
         * 不能超过 {@link #MAX_POSTER_PATH}，且路径里每一段都不能是空段、{@code "."} 或
         * {@code ".."} —— 否则 {@code /storage/emulated/0/../etc/x} 这种写法会冒充根目录下的
         * 文件，后面"是文件、长度相等"的校验就都落在错误的对象上了。
         */
        public static boolean isSafePath(String rawPath) {
            if (rawPath == null || rawPath.length() > MAX_POSTER_PATH
                    || !rawPath.startsWith(STORAGE_ROOT + "/")) return false;
            String rest = rawPath.substring(STORAGE_ROOT.length() + 1);
            if (rest.isEmpty()) return false;
            for (String segment : rest.split("/", -1)) {
                if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
            }
            return true;
        }

        /** 空串表示可以开始搬家；否则返回拒绝理由。 */
        public static String decision(PosterRewireFacts facts) {
            if (facts == null || !facts.posterRowExists) return "poster_row_missing";
            if (!facts.posterRowLocalFileBlank) return "poster_row_local_file_not_empty";
            if (!facts.posterRowThumbnailMatches) return "poster_row_thumbnail_mismatch";
            // 图片行上的 sha1 / size 是"当初登记这份封面"时的快照：封面重新生成过（编码参数变过、
            // 换了编码器）就再也对不上，真机上一批老封面正是这样 —— 用它们当硬门槛会让整批永远
            // 搬不动。真正的硬证据是路径逐字相等、文件在、长度等于请求的 length、且没有别的行
            // 引用这张封面；这四条已经足够确认"要删的行只是这张封面的挂载点"。
            if (!facts.posterPathSafe) return "poster_path_unsafe";
            // 到这里已经证明：这一行没有自己的本地文件、封面路径逐字相等、路径是存储根目录下的
            // 安全绝对路径。它只是"这张封面的挂载点"，而本模式从不删文件，所以删掉这一行不可能
            // 丢掉任何字节 —— 封面仍在原地，视频行自己的 thumbnailFile 指向它也照旧有效。
            // 因此不再要求"封面文件在/视频行可见/封面位为空"：真机上被优化过的视频行会被
            // CloudProvider 按 localFile 为空过滤掉，拿它当硬门槛会让整批永远搬不动。
            return "";
        }
    }

    /**
     * 迁移判定要用到的全部事实。字段全部可变是为了让调用方"边读盘边填"，但
     * {@link Migration} 只看得到这份快照，因此判定本身是纯函数、可以在 JVM 单测里钉住。
     */
    static final class MigrationFacts {
        boolean rowExists;
        String localFile;
        String thumbnailFile;
        String fileName;
        long rowSize;
        boolean previewExists;
        long previewLength;
        String previewFormat;
        boolean targetExists;
        long targetLength;
        String targetSha256;
        String originalSha256;
    }

    static final class MigrationDecision {
        final String reason;
        final InplaceSlimPolicy.MigrationAction action;
        /** 目标上已经是完整原图：只修数据库与状态，不搬文件。 */
        final boolean repairOnly;

        private MigrationDecision(String reason, InplaceSlimPolicy.MigrationAction action,
                boolean repairOnly) {
            this.reason = reason == null ? "" : reason;
            this.action = action;
            this.repairOnly = repairOnly;
        }

        static MigrationDecision reject(String reason, InplaceSlimPolicy.MigrationAction action) {
            return new MigrationDecision(reason, action, false);
        }

        static MigrationDecision proceed(InplaceSlimPolicy.MigrationAction action,
                boolean repairOnly) {
            return new MigrationDecision("", action, repairOnly);
        }
    }

    /**
     * migrate_inplace 的纯逻辑：行形状、相册目录还原、目标路径、动作映射与拒绝理由。
     *
     * <p>这里不碰 Android、不碰磁盘，{@code dryRun} 与真跑走的是同一条判定，所以演练看到的
     * reason 就是真跑会得到的 reason。
     */
    static final class Migration {
        /** 这些理由只表示"这一项不迁移"，不是失败；其余理由都算失败。 */
        private static final Set<String> SKIP_REASONS = new HashSet<>(Arrays.asList(
                "migrate_not_sidecar", "migrate_sidecar_kept", "migrate_skipped"));

        private Migration() {
        }

        /** 是不是老侧车形态：本地文件为空，且缩略图落在 {@code <相册>/.gallerysync-slim/} 下。 */
        static boolean isSidecarRow(String localFile, String thumbnailFile) {
            return isBlank(localFile) && slimAlbumDirectory(thumbnailFile) != null;
        }

        /**
         * 由缩略图路径还原相册目录：{@code <相册>/.gallerysync-slim/<随机名>.<ext>} → {@code <相册>}。
         * 路径里没有这一段（或这段就在根上）一律回 null，调用方据此判 migrate_not_sidecar。
         */
        static String slimAlbumDirectory(String thumbnailFile) {
            if (isBlank(thumbnailFile)) return null;
            String normalized = thumbnailFile.trim().replace('\\', '/');
            int index = normalized.lastIndexOf("/" + SLIM_DIRECTORY_NAME + "/");
            if (index <= 0) return null;
            return normalized.substring(0, index);
        }

        /**
         * 迁移目标 = 相册目录 + 行上的 fileName。
         *
         * <p>fileName 只接受纯文件名：带分隔符或 {@code ..} 的名字可能把目标指到相册外面，
         * 直接回 null（判定层给 migrate_name_missing 之前先由这里兜底）。
         */
        static String migrationTargetPath(String thumbnailFile, String fileName) {
            String album = slimAlbumDirectory(thumbnailFile);
            if (album == null || isBlank(fileName)) return null;
            String name = fileName.trim();
            if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || "..".equals(name)
                    || ".".equals(name)) return null;
            return album + "/" + name;
        }

        /** 预览的实际编码格式：取扩展名小写；认不出来回 null（交给策略按文件名判断）。 */
        static String previewFormatOf(String previewPath) {
            if (isBlank(previewPath)) return null;
            String name = previewPath.trim().replace('\\', '/');
            int slash = name.lastIndexOf('/');
            if (slash >= 0) name = name.substring(slash + 1);
            int dot = name.lastIndexOf('.');
            if (dot <= 0 || dot == name.length() - 1) return null;
            return name.substring(dot + 1).toLowerCase(Locale.ROOT);
        }

        /** 只看行、不看磁盘的拒绝理由：行不存在 / 不是侧车形态 / 没有原名。 */
        static String rowOnlyReason(MigrationFacts facts) {
            if (facts == null || !facts.rowExists) return "migrate_row_missing";
            if (!isSidecarRow(facts.localFile, facts.thumbnailFile)) return "migrate_not_sidecar";
            if (isBlank(facts.fileName)) return "migrate_name_missing";
            return "";
        }

        /**
         * 迁移结论。返回空 reason 表示可以搬，{@code repairOnly} 表示目标已是原图只修 DB。
         *
         * <p>顺序：先判"值不值得动"（{@link InplaceSlimPolicy#migrationAction} 的 SKIP），
         * 再按动作分流；RENAME 时还要回答"目标被占了吗"，只有"占着的就是原图"才放行。
         */
        static MigrationDecision decision(MigrationFacts facts) {
            String rowReason = rowOnlyReason(facts);
            if (!rowReason.isEmpty()) {
                return MigrationDecision.reject(rowReason, null);
            }
            // rowSize 未知（0）时无法证明"预览更小"，按策略的保守分支走 SKIP，绝不在没有
            // 长度证据的情况下把预览搬到原路径上。
            boolean previewSmaller = facts.rowSize > 0L && facts.previewLength < facts.rowSize;
            InplaceSlimPolicy.MigrationAction action = InplaceSlimPolicy.migrationAction(
                    facts.fileName, facts.thumbnailFile, facts.previewFormat,
                    facts.previewExists, previewSmaller);
            switch (action) {
                case RENAME:
                    if (!facts.targetExists) {
                        return MigrationDecision.proceed(action, false);
                    }
                    // 目标已存在：只有当它就是登记过的完整原图（长度等于行 size、摘要等于
                    // 注册表原文摘要）时才继续 —— 这时路径上的字节本来就是对的，只需修 DB。
                    if (facts.targetLength == facts.rowSize && facts.rowSize > 0L
                            && !isBlank(facts.originalSha256) && facts.targetSha256 != null
                            && facts.targetSha256.equalsIgnoreCase(facts.originalSha256)) {
                        return MigrationDecision.proceed(action, true);
                    }
                    return MigrationDecision.reject("migrate_target_exists", action);
                case TRANSCODE:
                    // 预览容器与原名扩展名不一致，不能改名。放行给执行层去远端取回原图，
                    // 校验后把行还给原路径；这与“重编码预览再覆盖”是两条不同的路。
                    return MigrationDecision.proceed(action, false);
                case SIDECAR:
                    return MigrationDecision.reject("migrate_sidecar_kept", action);
                case SKIP:
                default:
                    return MigrationDecision.reject("migrate_skipped", action);
            }
        }

        static boolean isSkipReason(String reason) {
            return reason != null && SKIP_REASONS.contains(reason);
        }
    }

    /** TRANSCODE 执行要用的行/注册表事实：目标路径、侧车预览、登记摘要与 dryRun。 */
    static final class TranscodeFacts {
        boolean rowExists;
        boolean recordExists;
        File target;
        File preview;
        String rowId;
        String sha1;
        long originalLength;
        String originalSha256;
        boolean dryRun;
    }

    /**
     * TRANSCODE 的外部动作出口。生产实现走 Android ContentResolver / {@link OriginalStore} /
     * WebDAV；单测实现只需记录调用顺序并写临时文件，因此“先校验、再改行、最后删预览”的
     * 顺序可以在 JVM 里被钉死。
     */
    interface TranscodeOps {
        boolean hasRequiredSpace(long originalLength);

        boolean isCurrent();

        File restoreOriginal(File destination) throws Exception;

        int updateRow(String rowId, String sha1, File destination);

        boolean markOriginal(File destination);
    }

    /** TRANSCODE 的纯执行结论：status/reason/bytes 直接映射成 {@link ItemResult}。 */
    static final class TranscodeOutcome {
        final String status;
        final String reason;
        final long bytes;
        final boolean migrated;
        final boolean planned;
        final boolean previewDeleted;
        final boolean registryMarked;

        private TranscodeOutcome(String status, String reason, long bytes, boolean migrated,
                boolean planned, boolean previewDeleted, boolean registryMarked) {
            this.status = status;
            this.reason = reason;
            this.bytes = bytes;
            this.migrated = migrated;
            this.planned = planned;
            this.previewDeleted = previewDeleted;
            this.registryMarked = registryMarked;
        }

        static TranscodeOutcome failed(String reason) {
            return new TranscodeOutcome("failed", reason, 0L, false, false, false, false);
        }

        static TranscodeOutcome cancelled() {
            return new TranscodeOutcome("cancelled", "job_stopped", 0L, false, false, false,
                    false);
        }

        static TranscodeOutcome planned(long bytes) {
            return new TranscodeOutcome("planned", "", bytes, false, true, false, false);
        }

        static TranscodeOutcome success(long bytes, boolean previewDeleted,
                boolean registryMarked) {
            return new TranscodeOutcome("success", "", bytes, true, false, previewDeleted,
                    registryMarked);
        }
    }

    /**
     * TRANSCODE 的纯执行逻辑：取回原图 → 长度/SHA-256 复核 → 修相册行 → 标记注册表 →
     * 删侧车预览。
     *
     * <p>顺序是契约的一部分：<b>先</b>证明磁盘上的字节就是登记原图，<b>再</b>把行改到原路径；
     * 行没改成就保留已恢复的文件（下次重试只需补 DB），绝不先删预览 —— 否则行还指着侧车时
     * 预览已消失，就成了既没原图也没预览的残骸。
     */
    static final class Transcode {
        private Transcode() {
        }

        static TranscodeOutcome run(TranscodeFacts facts, TranscodeOps ops) {
            if (facts == null || !facts.rowExists) {
                return TranscodeOutcome.failed("migrate_row_missing");
            }
            if (!facts.recordExists) {
                return TranscodeOutcome.failed("migrate_no_registry_record");
            }
            if (facts.target == null) {
                return TranscodeOutcome.failed("migrate_path_unsafe");
            }
            // dryRun 只回答“会做多少”，连一次磁盘写入都不做；空间检查留给真跑。
            if (facts.dryRun) {
                return TranscodeOutcome.planned(facts.originalLength);
            }
            if (!ops.hasRequiredSpace(facts.originalLength)) {
                return TranscodeOutcome.failed("migrate_needs_space");
            }
            if (!ops.isCurrent()) {
                return TranscodeOutcome.cancelled();
            }
            File restored;
            try {
                restored = ops.restoreOriginal(facts.target);
            } catch (Throwable failure) {
                return TranscodeOutcome.failed("migrate_restore_failed");
            }
            if (restored == null || !restored.isFile()) {
                return TranscodeOutcome.failed("migrate_restore_failed");
            }
            long length;
            String digest;
            try {
                length = restored.length();
                digest = sha256(restored);
            } catch (Throwable failure) {
                deleteRestored(restored);
                return TranscodeOutcome.failed("migrate_verify_failed");
            }
            if (length != facts.originalLength || isBlank(facts.originalSha256)
                    || !facts.originalSha256.equalsIgnoreCase(digest)) {
                deleteRestored(restored);
                return TranscodeOutcome.failed("migrate_verify_failed");
            }
            int updated;
            try {
                updated = ops.updateRow(facts.rowId, facts.sha1, facts.target);
            } catch (Throwable failure) {
                updated = 0;
            }
            if (updated != 1) {
                // 文件已经校验过，保留它；只让行与注册表停在旧状态，下次重试。
                return TranscodeOutcome.failed("migrate_db_update_failed");
            }
            boolean marked;
            try {
                marked = ops.markOriginal(facts.target);
            } catch (Throwable failure) {
                marked = false;
            }
            boolean previewDeleted = deletePreview(facts.preview);
            return TranscodeOutcome.success(facts.originalLength, previewDeleted, marked);
        }

        /** 校验失败只删刚恢复出来的那份文件，避免把未知字节留在相册路径上。 */
        private static void deleteRestored(File restored) {
            if (restored == null) return;
            try {
                if (restored.isFile() && !restored.delete()) {
                    Logger.w("event=migrate_transcode_delete_restored_failed path="
                            + restored.getPath());
                }
            } catch (Throwable failure) {
                Logger.w("event=migrate_transcode_delete_restored_failed path="
                        + restored.getPath());
            }
        }

        /**
         * 只删侧车目录里的普通预览文件：路径里必须有 {@code /.gallerysync-slim/}，必须是普通
         * 文件（不是目录、不是符号链接）。删不掉只记日志并回 false，不影响这次迁移的成功。
         */
        static boolean deletePreview(File preview) {
            if (preview == null) return false;
            try {
                if (!preview.isFile() || Files.isSymbolicLink(preview.toPath())) return false;
                String normalized = preview.getPath().replace('\\', '/');
                if (!normalized.contains("/" + SLIM_DIRECTORY_NAME + "/")) return false;
                if (preview.delete()) return true;
                Logger.w("event=migrate_transcode_preview_delete_failed path="
                        + preview.getPath());
                return false;
            } catch (Throwable failure) {
                Logger.w("event=migrate_transcode_preview_delete_failed path="
                        + preview.getPath());
                return false;
            }
        }
    }

    static final class MigrationRow {
        final String rowId, localFile, thumbnailFile, fileName, sha1, serverType;
        final long size;

        MigrationRow(String rowId, String localFile, String thumbnailFile, String fileName,
                String sha1, long size, String serverType) {
            this.rowId = rowId;
            this.localFile = localFile;
            this.thumbnailFile = thumbnailFile;
            this.fileName = fileName;
            this.sha1 = sha1;
            this.size = size;
            this.serverType = serverType;
        }
    }

    /** reconcile_inplace 要看的同路径行：keeper 的身份、登记尺寸与两个“不是远端行”的标记。 */
    static final class ReconcileRow {
        final String rowId;
        final String localFile;
        final String thumbnailFile;
        final String sha1;
        final String serverId;
        final long size;

        ReconcileRow(String rowId, String localFile, String thumbnailFile, String sha1,
                String serverId, long size) {
            this.rowId = rowId;
            this.localFile = localFile;
            this.thumbnailFile = thumbnailFile;
            this.sha1 = sha1;
            this.serverId = serverId;
            this.size = size;
        }
    }

    /** reconcile_inplace 判定要用到的事实：幽灵行形状、磁盘真实长度、同路径 keeper 行。 */
    static final class ReconcileFacts {
        boolean rowExists;
        String localFile;
        boolean localFileMatches;
        boolean thumbnailEmpty;
        boolean serverIdEmpty;
        /** 磁盘上的真实字节数。 */
        long fileLength;
        /** 幽灵行登记的 size（旧原图大小）。 */
        long rowSize;
        int keeperCount;
        String keeperRowId;
        String keeperSha1;
        long keeperSize;
        boolean keeperSizeMatches;
        boolean keeperServerIdEmpty;
        boolean keeperThumbnailEmpty;
    }

    /**
     * reconcile_inplace 的纯逻辑：返回空串才允许删幽灵行，返回的字符串就是结果里的 reason。
     *
     * <p>顺序按“证据强度”排：行都不在就没得删；行形状不对说明它根本不是迁移后留下的那一行；
     * 行 size 与磁盘一致说明它记的就是现在这份文件（不能删）；最后才要求同路径上有唯一的
     * keeper 行接住这份文件。
     */
    static final class Reconcile {
        private Reconcile() {
        }

        static String decision(ReconcileFacts facts) {
            if (facts == null || !facts.rowExists) return "reconcile_row_missing";
            if (!facts.localFileMatches || !facts.thumbnailEmpty || !facts.serverIdEmpty) {
                return "reconcile_row_not_ghost";
            }
            if (facts.fileLength == facts.rowSize) return "reconcile_size_matches";
            if (facts.keeperCount <= 0) return "reconcile_keeper_missing";
            if (facts.keeperCount > 1) return "reconcile_keeper_ambiguous";
            if (!facts.keeperSizeMatches || !facts.keeperServerIdEmpty
                    || !facts.keeperThumbnailEmpty) {
                return "reconcile_keeper_missing";
            }
            return "";
        }
    }

    /** repair_state 判定要用到的事实：注册表状态 + 磁盘上那份文件的长度与摘要。 */
    static final class RepairFacts {
        boolean recordExists;
        boolean alreadyOriginal;
        boolean targetPresent;
        long targetLength;
        String targetSha256;
        long originalLength;
        String originalSha256;
        long previewLength;
        String previewSha256;
    }

    static final class RepairDecision {
        final String reason;
        final String action;

        private RepairDecision(String reason, String action) {
            this.reason = reason == null ? "" : reason;
            this.action = action;
        }

        static RepairDecision reason(String reason) {
            return new RepairDecision(reason, null);
        }

        static RepairDecision action(String action) {
            return new RepairDecision("", action);
        }
    }

    /**
     * repair_state 的纯逻辑：判定顺序就是"先信状态、再信字节、最后才走网络"。
     *
     * <p>每一步都比上一步贵：状态是本地字符串，字节要多算一次摘要，远端回填要下载整个原图。
     */
    static final class RepairState {
        static final String ACTION_RESTORE_LOCAL = "restore_local";
        static final String ACTION_CONFIRM_PREVIEW = "confirm_preview";
        static final String ACTION_RESTORE_REMOTE = "restore_remote";

        private RepairState() {
        }

        static RepairDecision decision(RepairFacts facts) {
            if (facts == null || !facts.recordExists) return RepairDecision.reason("no_registry_record");
            // 状态已经是 ORIGINAL：没有可修的东西，重复执行必须幂等。
            if (facts.alreadyOriginal) return RepairDecision.reason("repair_already_original");
            // 目标不存在：不能凭空判定，保留状态等下载；这里不动注册表。
            if (!facts.targetPresent) return RepairDecision.reason("repair_missing");
            // 字节证明是原图：长度对上、登记摘要为空或摘要一致 → 只把状态回落 ORIGINAL。
            if (facts.originalLength > 0L && facts.targetLength == facts.originalLength
                    && (isBlank(facts.originalSha256) || (facts.targetSha256 != null
                    && facts.targetSha256.equalsIgnoreCase(facts.originalSha256)))) {
                return RepairDecision.action(ACTION_RESTORE_LOCAL);
            }
            // 字节证明是预览：长度与摘要都对上登记的预览 → 记成原地预览。
            if (facts.previewLength > 0L && facts.targetLength == facts.previewLength
                    && !isBlank(facts.previewSha256) && facts.targetSha256 != null
                    && facts.targetSha256.equalsIgnoreCase(facts.previewSha256)) {
                return RepairDecision.action(ACTION_CONFIRM_PREVIEW);
            }
            // 两种都不认识：只能重新从远端取原图。
            return RepairDecision.action(ACTION_RESTORE_REMOTE);
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
