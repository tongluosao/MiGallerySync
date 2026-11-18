package com.tongluosao.gallerysync.hook;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.tongluosao.gallerysync.core.Config;
import com.tongluosao.gallerysync.core.Logger;
import com.tongluosao.gallerysync.core.OriginalClickProbe;
import com.tongluosao.gallerysync.core.OriginalStore;
import com.tongluosao.gallerysync.core.PreviewStateRules;
import com.tongluosao.gallerysync.core.VideoCacheRetryPolicy;
import com.tongluosao.gallerysync.core.VideoPlayCache;
import com.tongluosao.gallerysync.core.VideoStreamPolicy;
import com.tongluosao.gallerysync.core.WebDavClient;
import com.tongluosao.gallerysync.core.WebDavVideoSource;

import io.github.libxposed.api.XposedInterface;

/** Routes the verified Gallery "Download original" request to the WebDAV original registry. */
final class OriginalRestoreHook {
    private static final String PAGE_ITEM = "com.miui.gallery.ui.PhotoPageItem";
    private static final String DOWNLOAD_MANAGER = PAGE_ITEM + "$DownloadManager";
    private static final String CLOUD_LOADER = "com.miui.gallery.util.glide.CloudImageLoader";
    private static final String DOWNLOAD_TYPE = "com.miui.gallery.sdk.download.DownloadType";
    private static final String LISTENER = "com.miui.gallery.util.cloudimageloader.CloudImageLoadingListener";
    private static final String ERROR_CODE = "com.miui.gallery.error.core.ErrorCode";
    private static final String DOWNLOAD_MENU = "com.miui.gallery.ui.photoPage.bars.menuitem.DownloadOriginal";
    /** 菜单过滤回调的方法名；各版相册参数表不同，靠 {@link MenuHookSignature} 逐档放宽。 */
    private static final String MENU_FILTER_METHOD = "onFilterFinished";
    /** 旧日志里的缺失描述，保持原样，方便继续按它 grep。 */
    private static final String MENU_FILTER_MISSING = "DownloadOriginal.onFilterFinished/2";
    private static final String MENU_HOLDER = "com.miui.gallery.ui.photoPage.bars.PhotoPageMenuManager$MenuItemHolder";
    private static final String MENU_ITEM_TYPE = "com.miui.gallery.ui.photoPage.bars.PhotoPageMenuManager$MenuItemType";
    /** BaseDataItem 上可能指向本机完整原图的候选路径，顺序即优先级。 */
    private static final String[] ITEM_LOCAL_PATH_GETTERS =
            {"getOriginalPath", "getThumnailPath", "getMicroPath"};
    private static final ThreadLocal<Request> ACTIVE_REQUEST = new ThreadLocal<>();
    /**
     * 真机实测：中继流式播放首字节 0.6-1 秒，播放器多次重连后解码器仍拿不到帧。改为先把原片整份
     * 下载到应用缓存，再让相册原生播放器播本地文件。中继分支保留（{@link #prepareVideoStream}）
     * 以便随时回滚。
     */
    private static final boolean PLAY_FROM_CACHE = true;
    private static final long VIDEO_CACHE_KEEP_MILLIS = 24L * 60L * 60L * 1000L;
    private static final long VIDEO_CACHE_MAX_BYTES = 800L * 1024L * 1024L;
    private static final int VIDEO_CACHE_BUFFER_BYTES = 64 * 1024;
    /** 先同步备好首块再交地址给播放器，避免中继第一请求扑空走上游。 */
    private static final long VIDEO_CACHE_FIRST_CHUNK = 1024L * 1024L;
    private static final long VIDEO_CACHE_FIRST_CHUNK_WAIT_MS = 3_000L;
    /** 目录/文件被系统清掉时重建目录并重试的次数。 */
    private static final int VIDEO_CACHE_DOWNLOAD_ATTEMPTS = 3;
    /** 下载过程中目录被清空后最多重建几次，超过就按失败处理。 */
    private static final int VIDEO_CACHE_DIR_REBUILD_ATTEMPTS = 2;
    /** 顺序下载每写 1MB 复查一次「领先播放位置多少」，超过窗口就停下来等播放追上来。 */
    private static final long VIDEO_CACHE_CHECKPOINT_BYTES = 1024L * 1024L;
    /** 领先窗口的时长基准见 {@link VideoStreamPolicy#WINDOW_SECONDS}。 */
    private static final long VIDEO_CACHE_MIN_WINDOW = 4L * 1024L * 1024L;
    private static final long VIDEO_CACHE_MAX_WINDOW = 64L * 1024L * 1024L;
    /** 播放器还没发请求（playhead 为 0）时至少放行这么多，避免首块之后立刻卡住。 */
    private static final long VIDEO_CACHE_START_WINDOW = 8L * 1024L * 1024L;
    /** 播放速率初值 2MB/s，之后按播放位置增量做指数平滑。 */
    private static final double VIDEO_CACHE_INITIAL_RATE = 2.0 * 1024.0 * 1024.0;
    /** 播放位置连续 30 秒不动且已不在播放，就放开窗口把文件补完。 */
    private static final long VIDEO_CACHE_IDLE_FINISH_MS = 30_000L;
    private static final long VIDEO_CACHE_THROTTLE_POLL_MS = 200L;
    private static final long VIDEO_CACHE_THROTTLE_LOG_MS = 5_000L;
    /**
     * “下载原图”点击前那次远端探测的总预算（含排队），与 {@code UploadQueue.PROBE_DEADLINE_MS}
     * 同一套口径：截止一到就当“问不出来”，立刻回退小米云。
     *
     * <p>为什么必须有上限：平板 + OpenList(S3) 上对一个**不存在**的对象做 Depth 0 PROPFIND，
     * 冷启动能等十几秒（见 {@code ProbeDecision} 的实测记录）。onClick 落在相册主线程，干等就是
     * ANR，所以只等到 6 秒，剩下的交给原生下载。
     */
    private static final long PROBE_DEADLINE_MS = 6_000L;
    /**
     * 探测结论缓存的有效期。只缓存“远端确认在且大小相符”的结论，过期就重问：远端被删这件事
     * 最多让用户多试一次 WebDAV 还原，而缓存“不在”会把明明能还原的那一份误判成回退。
     */
    private static final long PROBE_HIT_TTL_MS = 120_000L;
    /** 缓存条数上限：翻几千张照片的菜单不该把这张表撑大，超了直接清空重来。 */
    private static final int PROBE_HIT_LIMIT = 256;

    private final XposedInterface mXposed;
    private final ClassLoader mLoader;
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "gallery-original-restore");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService mVideoSourceWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "gallery-webdav-video-source");
        thread.setDaemon(true);
        return thread;
    });
    /**
     * 点击探测专用线程：{@code chain.proceed()} 只能在拦截器线程同步调用，所以“等结果”留在
     * 拦截器里，“发 PROPFIND”必须甩到这条线程上。队列 0 格（{@code SynchronousQueue}）：
     * 上一次探测还没收尾就立刻拒绝，绝不排队 —— 排队等待正是这次要消除的“点了没反应”。
     */
    private final ExecutorService mProbeWorker = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new SynchronousQueue<>(), r -> {
                Thread thread = new Thread(r, "gallery-original-probe");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    /**
     * 菜单判定时顺手预热的那条线程，与点击探测**分开**：预热再慢也挤不掉真正的那次探测
     * （共用一条线程时，被拒绝的会是用户刚点下去的那一次，直接误回退小米云）。
     */
    private final ExecutorService mProbePrefetchWorker = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new SynchronousQueue<>(), r -> {
                Thread thread = new Thread(r, "gallery-original-probe-prefetch");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardPolicy());
    /** 探测结论缓存：key = {@code target|remotePath}，value = {远端大小, 记录时刻}。 */
    private final Map<String, long[]> mProbeHits = new ConcurrentHashMap<>();
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Object mVideoSourceLock = new Object();
    private final AtomicLong mVideoPlaybackGeneration = new AtomicLong();
    private volatile VideoPlaybackRequest mActiveVideoPlayback;
    private volatile Config mConfig;
    private volatile OriginalStore mStore;
    private volatile WebDavVideoSource mVideoSource;
    private volatile Method mDownloadOriginMethod;
    private volatile Object mOriginDownloadType;
    private volatile Method mPlayDifferentVideoMethod;
    private volatile Method mInitGalleryStateMethod;
    private volatile java.lang.reflect.Constructor<?> mVideoPreviewManagerConstructor;
    private volatile boolean mOriginHookReady;
    private volatile boolean mImageLoaderHookReady;
    private volatile boolean mVideoPlaybackHookReady;
    private volatile boolean mVideoErrorHookReady;
    private volatile boolean mMenuHookReady;
    private volatile boolean mMenuClickHookReady;

    OriginalRestoreHook(XposedInterface xposed, ClassLoader loader) {
        mXposed = xposed;
        mLoader = loader;
    }

    void setConfig(Config config) {
        synchronized (mVideoSourceLock) {
            Config previous = mConfig;
            mConfig = config;
            if (!sameVideoSourceConfig(previous, config)) {
                mVideoPlaybackGeneration.incrementAndGet();
                mActiveVideoPlayback = null;
                closeVideoSourceLocked();
                // 换了服务器/账号，缓存里的“远端在”就是另一个空间的结论，必须整体作废
                mProbeHits.clear();
            }
        }
    }

    boolean canRestoreVideos() {
        Config config = mConfig;
        return mOriginHookReady && mImageLoaderHookReady && mVideoPlaybackHookReady
                && mVideoErrorHookReady
                && config != null && config.isReady();
    }

    void onAppStart() {
        Context context = HostContext.get(mLoader);
        if (context != null) {
            try {
                mStore = OriginalStore.get(context.getApplicationContext());
            } catch (Throwable t) {
                Logger.e("event=original_restore_registry_unavailable", t);
            }
        }
    }

    void install() {
        installDownloadContextHook();
        installCloudLoaderHook();
        installVideoPlaybackRestoreHook();
        installMenuHook();
        installManagedOriginalClickHook();
    }

    private void installDownloadContextHook() {
        try {
            Class<?> manager = mLoader.loadClass(DOWNLOAD_MANAGER);
            Class<?> type = mLoader.loadClass(DOWNLOAD_TYPE);
            Method download = manager.getDeclaredMethod("downloadOrigin", type);
            download.setAccessible(true);
            mXposed.hook(download).setId("gallery_origin_request_context")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        Request previous = ACTIVE_REQUEST.get();
                        Request request = requestForManager(chain.getThisObject());
                        if (request != null) ACTIVE_REQUEST.set(request);
                        try {
                            return chain.proceed();
                        } finally {
                            if (previous == null) ACTIVE_REQUEST.remove();
                            else ACTIVE_REQUEST.set(previous);
                        }
                    });
            mOriginHookReady = true;
            Logger.i("event=hook_registered method=PhotoPageItem$DownloadManager.downloadOrigin(DownloadType)");
        } catch (Throwable t) {
            Logger.e("event=original_restore_hook_missing method=PhotoPageItem$DownloadManager.downloadOrigin(DownloadType)", t);
        }
    }

    private void installCloudLoaderHook() {
        try {
            Class<?> loader = mLoader.loadClass(CLOUD_LOADER);
            Class<?> type = mLoader.loadClass(DOWNLOAD_TYPE);
            Class<?> listener = mLoader.loadClass(LISTENER);
            Method load = loader.getDeclaredMethod("loadImage", Uri.class, type, boolean.class,
                    listener, mLoader.loadClass(
                            "com.miui.gallery.util.cloudimageloader.CloudImageLoadingProgressListener"));
            load.setAccessible(true);
            mXposed.hook(load).setId("gallery_managed_origin_restore")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        Request request = ACTIVE_REQUEST.get();
                        Object downloadType = chain.getArg(1);
                        if (request == null || !isOrigin(downloadType)) return chain.proceed();
                        Uri uri = chain.getArg(0) instanceof Uri ? (Uri) chain.getArg(0) : null;
                        if (request.uri != null && uri != null && !request.uri.equals(uri)) {
                            Logger.w("event=managed_original_restore_uri_mismatch row="
                                    + request.record.rowId);
                            return chain.proceed();
                        }
                        // The request is a managed WebDAV item. Never let Gallery fall back to its
                        // Xiaomi cloud loader, including when configuration or registry validation fails.
                        restoreOffThread(request, uri, downloadType, chain.getArg(2) instanceof View
                                ? (View) chain.getArg(2) : null, chain.getArg(3));
                        return null;
                    });
            mImageLoaderHookReady = true;
            Logger.i("event=hook_registered method=CloudImageLoader.loadImage(Uri,DownloadType,boolean,CloudImageLoadingListener,CloudImageLoadingProgressListener)");
        } catch (Throwable t) {
            Logger.e("event=original_restore_hook_missing method=CloudImageLoader.loadImage/5", t);
        }
    }

    private void installVideoPlaybackRestoreHook() {
        try {
            Class<?> videoItem = mLoader.loadClass("com.miui.gallery.ui.PhotoPageVideoItem");
            Class<?> pageItem = mLoader.loadClass(PAGE_ITEM);
            Class<?> downloadType = mLoader.loadClass(DOWNLOAD_TYPE);
            Class<?> managerType = mLoader.loadClass(DOWNLOAD_MANAGER);
            Method handleOnlineRequest = videoItem.getDeclaredMethod("handleOnlineRequest");
            Method onUnSelected = pageItem.getDeclaredMethod("onUnSelected",
                    boolean.class, int.class);
            Method downloadOrigin = managerType.getDeclaredMethod("downloadOrigin", downloadType);
            Object origin = downloadType.getField("ORIGIN").get(null);
            mDownloadOriginMethod = downloadOrigin;
            mOriginDownloadType = origin;
            try {
                Class<?> playManager = mLoader.loadClass("com.miui.gallery.video.VideoPlayManager");
                Class<?> baseDataItem = mLoader.loadClass("com.miui.gallery.model.BaseDataItem");
                Method playDifferentVideo = playManager.getDeclaredMethod("doPlayDifferentVideo",
                        baseDataItem, String.class, String.class, byte[].class, byte[].class,
                        String.class);
                playDifferentVideo.setAccessible(true);
                mPlayDifferentVideoMethod = playDifferentVideo;
                Logger.i("event=hook_video_player_entry_ready method=VideoPlayManager.doPlayDifferentVideo(BaseDataItem,String,String,byte[],byte[],String)");
            } catch (Throwable t) {
                // The full original restore remains available if this Gallery build changes its
                // native player entry point.
                mPlayDifferentVideoMethod = null;
                Logger.w("event=managed_video_player_entry_missing method=VideoPlayManager.doPlayDifferentVideo/6", t);
            }
            handleOnlineRequest.setAccessible(true);
            onUnSelected.setAccessible(true);
            downloadOrigin.setAccessible(true);
            try {
                Class<?> previewManagerType = null;
                for (Class<?> inner : videoItem.getDeclaredClasses()) {
                    if ("VideoPreviewManager".equals(inner.getSimpleName())) {
                        previewManagerType = inner;
                        break;
                    }
                }
                if (previewManagerType != null) {
                    Method initGalleryState = previewManagerType.getDeclaredMethod(
                            "initGalleryState", boolean.class);
                    initGalleryState.setAccessible(true);
                    mInitGalleryStateMethod = initGalleryState;
                    for (java.lang.reflect.Constructor<?> constructor
                            : previewManagerType.getDeclaredConstructors()) {
                        if (constructor.getParameterCount() == 2) {
                            constructor.setAccessible(true);
                            mVideoPreviewManagerConstructor = constructor;
                            break;
                        }
                    }
                    Logger.i("event=hook_video_gallery_state_ready"
                            + " method=VideoPreviewManager.initGalleryState(boolean)");
                }
            } catch (Throwable t) {
                mInitGalleryStateMethod = null;
                mVideoPreviewManagerConstructor = null;
                Logger.w("event=managed_video_gallery_state_missing", t);
            }
            mXposed.hook(handleOnlineRequest).setId("gallery_managed_video_playback_restore")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        if (!mOriginHookReady || !mImageLoaderHookReady) return chain.proceed();
                        Object video = chain.getThisObject();
                        Object item;
                        OriginalStore.Record record;
                        Config config;
                        try {
                            item = field(video, "mDataItem");
                            if (item == null) return chain.proceed();
                            config = mConfig;
                            if (config == null || !config.isReady()) return chain.proceed();
                            OriginalStore originalStore = store();
                            if (originalStore == null) return chain.proceed();
                            record = recordForItem(item, originalStore, config);
                        } catch (Throwable t) {
                            Logger.w("event=managed_video_playback_lookup_failed", t);
                            return chain.proceed();
                        }
                        if (record == null) return chain.proceed();
                        if (PLAY_FROM_CACHE && (record.originalLength <= 0L
                                || record.remotePath == null)) {
                            Logger.w("event=video_play_cache_unavailable row=" + record.rowId);
                            return chain.proceed();
                        }

                        // A verified original can already exist at the registered destination
                        // while this page item still has no original path (for example, when
                        // Gallery was launched with VIEW on an HQ file). Only trust the path
                        // Gallery itself will play.
                        String itemOriginalPath = stringCall(item, "getOriginalPath");
                        if (itemOriginalPath != null && !itemOriginalPath.isEmpty()
                                && new File(itemOriginalPath).isFile()) {
                            return chain.proceed();
                        }

                        Object manager;
                        Object playManager;
                        try {
                            manager = field(video, "mDownloadManager");
                            if (manager == null) {
                                Logger.w("event=managed_video_playback_restore_missing_manager");
                                return Boolean.TRUE;
                            }
                            if (isOrigin(call(manager, "getCurDownloadType"))) {
                                Logger.d("event=managed_video_playback_restore_already_active");
                                return Boolean.TRUE;
                            }
                            // DEX declares PhotoPageVideoItem.mPlayManager as VideoPlayManager.
                            // Its doPlayDifferentVideo(BaseDataItem,String,String,byte[],byte[],
                            // String) entry accepts the localhost relay URL without changing
                            // the BaseDataItem's original path.
                            playManager = field(video, "mPlayManager");
                        } catch (Throwable t) {
                            Logger.w("event=managed_video_playback_manager_lookup_failed row="
                                    + record.rowId, t);
                            return Boolean.TRUE;
                        }
                        VideoPlaybackRequest playback = new VideoPlaybackRequest(video, item,
                                manager, playManager, record, config,
                                mVideoPlaybackGeneration.incrementAndGet());
                        mActiveVideoPlayback = playback;
                        if (playManager == null || mPlayDifferentVideoMethod == null
                                || !mVideoErrorHookReady) {
                            startFullVideoRestore(playback, null);
                            return Boolean.TRUE;
                        }
                        if (PLAY_FROM_CACHE) {
                            prepareVideoCache(playback);
                        } else {
                            prepareVideoStream(playback);
                        }
                        // Do not fall through to Gallery's Xiaomi OnlineVideo URL request for
                        // a managed item whose registered local original is absent.
                        return Boolean.TRUE;
                    });
            mVideoPlaybackHookReady = true;
            Logger.i("event=hook_registered method=PhotoPageVideoItem.handleOnlineRequest()");
            try {
                Method onSelected = videoItem.getDeclaredMethod("doOnSelected", boolean.class,
                        boolean.class, boolean.class);
                onSelected.setAccessible(true);
                mXposed.hook(onSelected).setId("gallery_managed_video_prewarm")
                        .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                        .intercept(chain -> {
                            prewarmVideoItemAsync(chain.getThisObject());
                            return chain.proceed();
                        });
                Logger.i("event=hook_registered method=PhotoPageVideoItem.doOnSelected(ZZZ)V");
            } catch (Throwable t) {
                Logger.w("event=managed_video_prewarm_hook_missing"
                        + " method=PhotoPageVideoItem.doOnSelected(ZZZ)V", t);
            }
            mXposed.hook(onUnSelected).setId("gallery_managed_video_stream_page_exit_cleanup")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object page = chain.getThisObject();
                        Object pausedValue = chain.getArg(0);
                        boolean paused = pausedValue instanceof Boolean
                                && (Boolean) pausedValue;
                        if (!paused && videoItem.isInstance(page)) {
                            releaseVideoSourceForVideo(page);
                        }
                        return result;
                    });
            Logger.i("event=hook_registered method=PhotoPageItem.onUnSelected(boolean,int)");
            try {
                Method handleError = videoItem.getDeclaredMethod("handleError", String.class);
                handleError.setAccessible(true);
                mXposed.hook(handleError).setId("gallery_managed_video_stream_error_fallback")
                        .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                        .intercept(chain -> {
                            VideoPlaybackRequest playback = mActiveVideoPlayback;
                            WebDavVideoSource source = playback == null
                                    ? null : playback.streamSource;
                            if (playback == null || playback.video != chain.getThisObject()
                                    || source == null || playback.fallbackStarted.get()) {
                                return chain.proceed();
                            }
                            Object result = chain.proceed();
                            if (!mMain.post(() -> {
                                if (mActiveVideoPlayback != playback
                                        || playback.streamSource != source
                                        || mVideoSource != source) return;
                                Logger.w("event=managed_video_stream_async_error row="
                                        + playback.record.rowId);
                                startFullVideoRestore(playback,
                                        new IllegalStateException("Gallery video playback failed"));
                            })) {
                                Logger.w("event=managed_video_stream_error_callback_rejected row="
                                        + playback.record.rowId);
                            }
                            return result;
                        });
                mVideoErrorHookReady = true;
                Logger.i("event=hook_registered method=PhotoPageVideoItem.handleError(String)");
            } catch (Throwable t) {
                mVideoErrorHookReady = false;
                Logger.w("event=managed_video_stream_error_hook_missing method=PhotoPageVideoItem.handleError(String)", t);
            }
        } catch (Throwable t) {
            Logger.e("event=managed_video_playback_restore_hook_missing method=PhotoPageVideoItem.handleOnlineRequest()", t);
        }
    }

    /**
     * 缓存优先的接管分支：整份缓存命中就播本地文件（秒开）；未命中则起一个顺序下载线程写
     * {@code .part}，同时立刻走中继让播放器先播起来，中继从 {@code .part} 里读已下好的部分。
     */
    private void prepareVideoCache(VideoPlaybackRequest playback) {
        OriginalStore.Record record = playback.record;
        if (record == null) {
            startFullVideoRestore(playback, null);
            return;
        }
        try {
            Context host = HostContext.get(mLoader);
            Context context = host == null ? null : host.getApplicationContext();
            if (context == null) {
                Logger.w("event=video_play_cache_failed row=" + record.rowId
                        + " message=host context unavailable");
                startFullVideoRestore(playback, null);
                return;
            }
            if (VideoPlayCache.totalBytes(context) > VIDEO_CACHE_MAX_BYTES) {
                VideoPlayCache.prune(context, VIDEO_CACHE_KEEP_MILLIS, VIDEO_CACHE_MAX_BYTES);
            }
            File cache = VideoPlayCache.fileFor(context, record);
            if (VideoPlayCache.isReady(cache, record.originalLength)) {
                Logger.i("event=video_play_cache_hit row=" + record.rowId
                        + " bytes=" + record.originalLength);
                playCachedFileOnMain(playback, cache);
                return;
            }
            File part = VideoPlayCache.partFileFor(context, record);
            Logger.i("event=video_play_cache_progressive row=" + record.rowId
                    + " bytes=" + record.originalLength);
            // 目录可能刚被系统清空：先重建目录再起下载线程，否则首块必然 ENOENT。
            ensureCacheDir(part, record.rowId);
            // 先把首块落到 .part 再把地址交给播放器：否则中继第一次请求必然扑空，静默走上游。
            boolean firstChunkReady = false;
            for (int attempt = 0;
                    attempt < VideoCacheRetryPolicy.MAX_FIRST_CHUNK_ATTEMPTS; attempt++) {
                if (attempt > 0) {
                    // 首块失败多半是相册 cache 目录被清掉：重建目录、换新线程再等一次。
                    Logger.w("event=video_play_cache_first_chunk_retry row=" + record.rowId
                            + " attempt=" + attempt);
                    ensureCacheDir(part, record.rowId);
                }
                java.util.concurrent.CountDownLatch firstChunk =
                        new java.util.concurrent.CountDownLatch(1);
                startProgressiveDownload(playback, context, part, firstChunk, attempt);
                firstChunkReady = awaitFirstChunk(part, cache, record, firstChunk);
                if (firstChunkReady) break;
                Logger.w("event=video_play_cache_first_chunk_failed row=" + record.rowId
                        + " attempt=" + attempt
                        + " part=" + (part.isFile() ? part.length() : -1L)
                        + " bytes=" + record.originalLength);
                if (!VideoCacheRetryPolicy.shouldRetryFirstChunk(attempt, part.isFile(),
                        isCacheDirPresent(part))) {
                    break;
                }
            }
            if (!firstChunkReady) {
                // 两次都没备好首块就退回纯中继，progressivePart 保持 null，让中继自己走上游。
                prepareVideoStream(playback);
                return;
            }
            Logger.i("event=video_play_cache_first_chunk row=" + record.rowId
                    + " bytes=" + Math.min(record.originalLength, VIDEO_CACHE_FIRST_CHUNK));
            playback.progressivePart = part;
            prepareVideoStream(playback);
        } catch (Throwable t) {
            Logger.w("event=video_play_cache_failed row=" + record.rowId
                    + " message=" + t);
            startFullVideoRestore(playback, t);
        }
    }

    /** 等首块落盘（最多 {@link #VIDEO_CACHE_FIRST_CHUNK_WAIT_MS}），以文件长度为最终判据。 */
    private boolean awaitFirstChunk(File part, File cache, OriginalStore.Record record,
            java.util.concurrent.CountDownLatch firstChunk) {
        try {
            firstChunk.await(VIDEO_CACHE_FIRST_CHUNK_WAIT_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        long need = Math.min(record.originalLength, VIDEO_CACHE_FIRST_CHUNK);
        return (part.isFile() && part.length() >= need)
                || VideoPlayCache.isReady(cache, record.originalLength);
    }

    private static boolean isCacheDirPresent(File part) {
        File parent = part == null ? null : part.getParentFile();
        return parent != null && parent.isDirectory();
    }

    /**
     * 确保 {@code .part} 的父目录存在。相册 cache 目录被系统清掉后 {@code getCacheDir()} 仍返回同一
     * 路径，但不 mkdirs 就写文件会直接 ENOENT，所以每次起下载线程前都重建一遍。
     */
    private static boolean ensureCacheDir(File part, String rowId) {
        File parent = part == null ? null : part.getParentFile();
        if (parent == null) return false;
        try {
            if (parent.isDirectory()) return true;
            if (parent.mkdirs() || parent.isDirectory()) {
                Logger.i("event=video_play_cache_dir_recreated row=" + rowId
                        + " dir=" + parent.getName());
                return true;
            }
        } catch (Throwable t) {
            Logger.w("event=video_play_cache_dir_recreate_failed row=" + rowId
                    + " message=" + t);
        }
        return false;
    }

    /** 用本地文件替换原来的中继 URL 起播；两条原生入口都失败才退回完整下载还原。 */
    private void playCachedFile(VideoPlaybackRequest playback, File cache) {
        String url = Uri.fromFile(cache).toString();
        if (startGalleryOnlinePlay(playback, url)) {
            Logger.i("event=video_play_cache_started row=" + playback.record.rowId);
            return;
        }
        try {
            Method play = mPlayDifferentVideoMethod;
            if (play == null || playback.playManager == null) {
                throw new NoSuchMethodException("native video player entry missing");
            }
            play.invoke(playback.playManager, playback.item, url, null, null, null, null);
            Logger.i("event=video_play_cache_started_legacy row=" + playback.record.rowId);
        } catch (Throwable t) {
            Logger.w("event=video_play_cache_start_failed row="
                    + playback.record.rowId, t);
            startFullVideoRestore(playback, t);
        }
    }

    private void playCachedFileOnMain(final VideoPlaybackRequest playback, final File cache) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            playCachedFile(playback, cache);
            return;
        }
        if (!mMain.post(() -> playCachedFile(playback, cache))) {
            Logger.w("event=video_play_cache_callback_rejected row="
                    + playback.record.rowId);
        }
    }

    /**
     * 同一个 record 只跑一个顺序下载线程：key 是 {@code .part} 的绝对路径，线程结束时释放。
     * 中继同时在读同一个文件，所以下载不阻塞起播。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> sActive =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 起一个顺序下载线程。{@code attempt>0} 表示首块失败后的重试：换一个新 token 与新的下载序号，
     * 老线程会在下一个缓冲点自行退出，避免两个写者同时写同一个 {@code .part}。
     */
    private void startProgressiveDownload(final VideoPlaybackRequest playback,
            final Context context, final File part,
            final java.util.concurrent.CountDownLatch firstChunk, final int attempt) {
        final OriginalStore.Record record = playback.record;
        final String key = part.getAbsolutePath();
        final Object token = new Object();
        final int run = playback.downloadRun.incrementAndGet();
        if (attempt == 0) {
            if (sActive.putIfAbsent(key, token) != null) {
                firstChunk.countDown();
                return;
            }
        } else {
            sActive.put(key, token);
            ensureCacheDir(part, record.rowId);
        }
        Thread thread = new Thread(() -> {
            try {
                downloadProgressivePart(playback, context, part, firstChunk, run);
            } finally {
                sActive.remove(key, token);
            }
        }, "mgs-video-cache");
        thread.setDaemon(true);
        try {
            thread.start();
        } catch (Throwable t) {
            sActive.remove(key, token);
            firstChunk.countDown();
            Logger.w("event=video_play_cache_failed row=" + record.rowId + " message=" + t);
            toastOnMain(context, "视频准备失败，请重试");
        }
    }

    /**
     * 后台线程：从 {@code .part} 当前长度续写（Range 起点=已有字节），只领先播放位置一个缓冲窗口，
     * 避免一进场就像整份下载。目录/文件被系统清掉时重建目录并从 0 重来；校验长度后改名成正式缓存。
     */
    private void downloadProgressivePart(VideoPlaybackRequest playback, Context context,
            File part, java.util.concurrent.CountDownLatch firstChunk, int run) {
        OriginalStore.Record record = playback.record;
        long startedAt = System.currentTimeMillis();
        long firstChunkBytes = Math.min(record.originalLength, VIDEO_CACHE_FIRST_CHUNK);
        int dirRebuilds = 0;
        try {
            for (int attempt = 0; attempt < VIDEO_CACHE_DOWNLOAD_ATTEMPTS; attempt++) {
                if (playback.downloadRun.get() != run) {
                    Logger.i("event=video_play_cache_download_superseded row=" + record.rowId);
                    return;
                }
                long resumeFrom = part.isFile() ? part.length() : 0L;
                if (resumeFrom > record.originalLength) resumeFrom = 0L;
                if (resumeFrom >= record.originalLength) break;
                if (resumeFrom >= firstChunkBytes) firstChunk.countDown();
                // 线程启动前先确保目录存在：cache 目录可能刚被系统清空。
                ensureParentDir(part, record);
                try {
                    downloadPartRange(playback, context, part, resumeFrom, firstChunk, run);
                    break;
                } catch (DownloadSupersededException e) {
                    Logger.i("event=video_play_cache_download_superseded row=" + record.rowId);
                    return;
                } catch (CacheFileLostException e) {
                    // 一律以 part.length() 为准：文件还在、只是比已写计数短，就直接按当前长度续写。
                    long current = part.isFile() ? part.length() : -1L;
                    if (VideoStreamPolicy.resumeDecision(current, resumeFrom)
                            == VideoStreamPolicy.RESUME) {
                        Logger.w("event=video_play_cache_resume row=" + record.rowId
                                + " bytes=" + current);
                        ensureParentDir(part, record);
                        continue;
                    }
                    Logger.w("event=video_play_cache_part_lost row=" + record.rowId
                            + " written=" + current + " resumeFrom=" + resumeFrom);
                    deleteQuietly(part);
                    ensureParentDir(part, record);
                } catch (java.io.IOException e) {
                    // 目录被系统清掉（ENOENT / No such file）：重建目录后从 0 重来，不要直接失败退出。
                    if (!VideoCacheRetryPolicy.isRecoverableIoFailure(e)
                            || dirRebuilds >= VIDEO_CACHE_DIR_REBUILD_ATTEMPTS) {
                        throw e;
                    }
                    dirRebuilds++;
                    Logger.w("event=video_play_cache_dir_recreated row=" + record.rowId
                            + " rebuild=" + dirRebuilds
                            + " bytes=" + (part.isFile() ? part.length() : -1L)
                            + " cause=" + e);
                    deleteQuietly(part);
                    ensureParentDir(part, record);
                }
            }
            if (!part.isFile() || part.length() != record.originalLength) {
                throw new IOException("cache length mismatch "
                        + (part.isFile() ? part.length() : -1L));
            }
            File cache = VideoPlayCache.fileFor(context, record);
            if (!part.renameTo(cache)) {
                copyCacheFile(part, cache, record.originalLength);
            }
            Logger.i("event=video_play_cache_ready row=" + record.rowId
                    + " bytes=" + record.originalLength
                    + " elapsed=" + (System.currentTimeMillis() - startedAt));
            VideoPlayCache.prune(context, VIDEO_CACHE_KEEP_MILLIS, VIDEO_CACHE_MAX_BYTES);
        } catch (Throwable t) {
            deleteQuietly(part);
            Logger.w("event=video_play_cache_failed row=" + record.rowId + " message=" + t);
            toastOnMain(context, "视频准备失败，请重试");
        } finally {
            firstChunk.countDown();
        }
    }

    /** 拉 [resumeFrom, len-1] 这一段写进 {@code .part}（追加模式）。 */
    private void downloadPartRange(VideoPlaybackRequest playback, Context context, File part,
            long resumeFrom, java.util.concurrent.CountDownLatch firstChunk, int run)
            throws IOException {
        OriginalStore.Record record = playback.record;
        Config config = playback.config;
        WebDavClient client = new WebDavClient(config.getBaseUrl(), config.getUsername(),
                config.getPassword());
        WebDavClient.RangeResponse response = client.streamRange(record.remotePath, resumeFrom,
                record.originalLength - 1L);
        try {
            writeCacheBodyThrottled(response.body(), part, resumeFrom, record.originalLength,
                    playback, firstChunk, run);
        } finally {
            response.close();
        }
    }

    private static void ensureParentDir(File part, OriginalStore.Record record) throws IOException {
        if (!ensureCacheDir(part, record == null ? null : record.rowId)) {
            throw new IOException("video cache dir unavailable");
        }
    }

    /** {@code .part} 被外部（系统清理/用户）删掉：由下载循环重建目录并从头续写。 */
    private static final class CacheFileLostException extends IOException {
        CacheFileLostException() {
            super("video cache file lost");
        }
    }

    /** 该下载已被重试替代：安静退出，既不删文件也不弹 Toast。 */
    private static final class DownloadSupersededException extends IOException {
        DownloadSupersededException() {
            super("video cache download superseded");
        }
    }

    /**
     * 限领先量的顺序写入：每写 1MB 用「已写字节 - 播放位置」比对窗口，超过窗口就每 200ms 轮询，
     * 等播放追上来（回落到 3/4 窗口）再继续；播放结束（页面关闭且播放位置 30 秒不动）才放开限制
     * 把文件补完，保证下次能秒开。
     */
    private void writeCacheBodyThrottled(InputStream body, File target, long resumeFrom,
            long expectedLength, VideoPlaybackRequest playback,
            java.util.concurrent.CountDownLatch firstChunk, int run) throws IOException {
        ensureParentDir(target, playback.record);
        long firstChunkBytes = Math.min(expectedLength, VIDEO_CACHE_FIRST_CHUNK);
        FileOutputStream out = new FileOutputStream(target, true);
        try {
            byte[] buffer = new byte[VIDEO_CACHE_BUFFER_BYTES];
            long total = resumeFrom;
            long checkpoint = resumeFrom;
            long playhead = Math.max(0L, playheadOf(playback));
            long lastPlayheadAt = System.currentTimeMillis();
            long lastAdvanceAt = lastPlayheadAt;
            long lastThrottleLogAt = 0L;
            double bytesPerSecond = VIDEO_CACHE_INITIAL_RATE;
            boolean idleFinishLogged = false;
            boolean firstChunkDone = total >= firstChunkBytes;
            if (firstChunkDone) firstChunk.countDown();
            while (true) {
                // 被重试替代 / 目录被系统清掉就交给下载循环处理，不要在这里抛出去终止播放
                if (playback.downloadRun.get() != run) throw new DownloadSupersededException();
                ensureParentDir(target, playback.record);
                if (!target.exists()) throw new CacheFileLostException();
                int read = body.read(buffer);
                if (read < 0) break;
                if (read == 0) continue;
                if (playback.downloadRun.get() != run) throw new DownloadSupersededException();
                total += read;
                if (total > expectedLength) throw new IOException("cache download overflow");
                out.write(buffer, 0, read);
                if (!firstChunkDone && total >= firstChunkBytes) {
                    // 首块落盘后立刻放行等待方，让中继第一次请求就能命中本地字节
                    out.flush();
                    firstChunkDone = true;
                    firstChunk.countDown();
                }
                if (total - checkpoint < VIDEO_CACHE_CHECKPOINT_BYTES) continue;
                checkpoint = total;
                long now = System.currentTimeMillis();
                long observed = playheadOf(playback);
                if (observed >= 0L && observed > playhead) {
                    long deltaMs = now - lastPlayheadAt;
                    if (deltaMs >= 200L) {
                        bytesPerSecond = smoothRate(bytesPerSecond, observed - playhead, deltaMs);
                        playhead = observed;
                        lastPlayheadAt = now;
                    }
                    lastAdvanceAt = now;
                }
                long window = VideoStreamPolicy.windowBytes((long) bytesPerSecond, playhead,
                        VIDEO_CACHE_MIN_WINDOW, VIDEO_CACHE_MAX_WINDOW,
                        VIDEO_CACHE_START_WINDOW);
                long ahead = Math.max(0L, total - playhead);
                if (ahead <= window) continue;
                while (ahead > window * 3L / 4L) {
                    if (playback.downloadRun.get() != run) {
                        throw new DownloadSupersededException();
                    }
                    if (!target.exists()) throw new CacheFileLostException();
                    long waitNow = System.currentTimeMillis();
                    long head = playheadOf(playback);
                    if (head > playhead) {
                        long deltaMs = waitNow - lastPlayheadAt;
                        if (deltaMs >= 200L) {
                            bytesPerSecond = smoothRate(bytesPerSecond, head - playhead, deltaMs);
                            playhead = head;
                            lastPlayheadAt = waitNow;
                        }
                        lastAdvanceAt = waitNow;
                        window = VideoStreamPolicy.windowBytes((long) bytesPerSecond, playhead,
                                VIDEO_CACHE_MIN_WINDOW, VIDEO_CACHE_MAX_WINDOW,
                                VIDEO_CACHE_START_WINDOW);
                    }
                    ahead = Math.max(0L, total - playhead);
                    if (ahead <= window * 3L / 4L) break;
                    if (!isPlaybackActive(playback)
                            && waitNow - lastAdvanceAt > VIDEO_CACHE_IDLE_FINISH_MS) {
                        if (!idleFinishLogged) {
                            idleFinishLogged = true;
                            Logger.i("event=video_cache_finish_after_idle written=" + total);
                        }
                        break;
                    }
                    if (waitNow - lastThrottleLogAt >= VIDEO_CACHE_THROTTLE_LOG_MS) {
                        lastThrottleLogAt = waitNow;
                        Logger.i("event=video_cache_throttled ahead=" + ahead
                                + " window=" + window);
                    }
                    try {
                        Thread.sleep(VIDEO_CACHE_THROTTLE_POLL_MS);
                    } catch (InterruptedException e) {
                        // 线程被中断就放开限制继续补文件，不把异常抛出去
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            out.flush();
            out.getFD().sync();
        } finally {
            try {
                out.close();
            } catch (Throwable ignored) {
                // 关不掉也不能把异常冒到下载线程外
            }
        }
    }

    /** 播放位置；中继已随页面关闭销毁时返回 -1，调用方沿用上一次观测值。 */
    private static long playheadOf(VideoPlaybackRequest playback) {
        if (playback == null) return -1L;
        WebDavVideoSource source = playback.relaySource;
        if (source == null) source = playback.streamSource;
        if (source == null) return -1L;
        try {
            return source.playheadBytes();
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 仍在播放：还是当前播放请求，且中继没有被页面退出关掉。 */
    private boolean isPlaybackActive(VideoPlaybackRequest playback) {
        return playback != null && mActiveVideoPlayback == playback
                && (playback.relaySource != null || playback.streamSource != null);
    }

    private static double smoothRate(double previous, long deltaBytes, long deltaMs) {
        double instant = deltaBytes * 1000.0 / deltaMs;
        return previous * 0.7 + instant * 0.3;
    }

    private static void copyCacheFile(File source, File target, long expectedLength)
            throws IOException {
        FileInputStream in = new FileInputStream(source);
        try {
            FileOutputStream out = new FileOutputStream(target);
            try {
                byte[] buffer = new byte[VIDEO_CACHE_BUFFER_BYTES];
                long total = 0L;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (read == 0) continue;
                    total += read;
                    if (total > expectedLength) throw new IOException("cache copy overflow");
                    out.write(buffer, 0, read);
                }
                out.flush();
                out.getFD().sync();
            } finally {
                try {
                    out.close();
                } catch (Throwable ignored) {
                    // 同上：缓存文件复制失败由调用方的长度校验兜底
                }
            }
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
                // 同上
            }
        }
        if (target.length() != expectedLength) throw new IOException("cache copy mismatch");
        deleteQuietly(source);
    }

    private static void deleteQuietly(File file) {
        if (file == null) return;
        try {
            if (file.exists() && !file.delete()) {
                Logger.w("event=video_play_cache_delete_failed");
            }
        } catch (Throwable ignored) {
            // 残留文件由 prune 后续清理
        }
    }

    private void toastOnMain(final Context context, final String text) {
        if (context == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (!mMain.post(() -> toast(context, text))) {
                Logger.w("event=video_play_cache_toast_rejected");
            }
            return;
        }
        toast(context, text);
    }

    private void toast(Context context, String text) {
        try {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Logger.w("event=video_play_cache_toast_failed message=" + t);
        }
    }

    private void prepareVideoStream(VideoPlaybackRequest playback) {
        try {
            mVideoSourceWorker.execute(() -> {
                WebDavVideoSource source = new WebDavVideoSource();
                source.setProgressiveCache(playback.progressivePart,
                        playback.record.originalLength);
                String url;
                try {
                    synchronized (mVideoSourceLock) {
                        if (!isPlaybackConfigCurrent(playback)) return;
                        WebDavVideoSource previous = mVideoSource;
                        mVideoSource = source;
                        playback.relaySource = source;
                        if (previous != null) previous.close();
                        url = source.open(playback.config, playback.record);
                        if (mVideoSource != source || !isPlaybackConfigCurrent(playback)) {
                            if (mVideoSource == source) mVideoSource = null;
                            source.close();
                            return;
                        }
                    }
                } catch (Throwable t) {
                    closeVideoSource(source);
                    Logger.w("event=managed_video_stream_open_failed row="
                            + playback.record.rowId, t);
                    if (!mMain.post(() -> startFullVideoRestore(playback, t))) {
                        Logger.w("event=managed_video_stream_fallback_callback_rejected row="
                                + playback.record.rowId);
                    }
                    return;
                }
                if (!mMain.post(() -> playVideoStream(playback, source, url))) {
                    closeVideoSource(source);
                    Logger.w("event=managed_video_stream_callback_rejected row="
                            + playback.record.rowId);
                }
            });
        } catch (Throwable t) {
            Logger.w("event=managed_video_stream_worker_unavailable row="
                    + playback.record.rowId, t);
            startFullVideoRestore(playback, t);
        }
    }

    private void playVideoStream(VideoPlaybackRequest playback, WebDavVideoSource source,
            String url) {
        if (!isVideoPlaybackCurrent(playback) || mVideoSource != source) {
            closeVideoSource(source);
            return;
        }
        playback.streamSource = source;
        if (startGalleryOnlinePlay(playback, url)) {
            Logger.i("event=managed_video_stream_started row=" + playback.record.rowId);
            return;
        }
        try {
            Method play = mPlayDifferentVideoMethod;
            if (play == null) throw new NoSuchMethodException("native video player entry missing");
            play.invoke(playback.playManager, playback.item, url, null, null, null, null);
            Logger.i("event=managed_video_stream_started_legacy row=" + playback.record.rowId);
        } catch (Throwable t) {
            closeVideoSource(source);
            Logger.w("event=managed_video_stream_start_failed row="
                    + playback.record.rowId, t);
            startFullVideoRestore(playback, t);
        }
    }

    /**
     * Mirrors {@code PhotoPageVideoItem.lambda$handleFileNotExisting$12}: the photo page keeps the
     * remote URL in {@code mOnlineUrl} and lets {@code VideoPreviewManager.initGalleryState(true)}
     * create the xiaomi gallery player. Calling {@code VideoPlayManager.doPlayDifferentVideo}
     * instead only reaches {@code clickPauseOrPlayButton}, which silently returns when the gallery
     * player state was never created, so the user sees no spinner and no playback.
     */
    private boolean startGalleryOnlinePlay(VideoPlaybackRequest playback, String url) {
        Method initGalleryState = mInitGalleryStateMethod;
        if (initGalleryState == null) return false;
        try {
            Object video = playback.video;
            setField(video, "mOnlineUrl", url);
            optionalSetField(video, "mIsShowPlayIcon", Boolean.FALSE);
            optionalSetField(video, "postMeta", null);
            optionalSetField(video, "recordKey", null);
            optionalSetField(video, "recordIv", null);
            Object previewManager = field(video, "mPreviewManager");
            if (previewManager == null) {
                if (mVideoPreviewManagerConstructor == null) return false;
                previewManager = mVideoPreviewManagerConstructor.newInstance(video, null);
                setField(video, "mPreviewManager", previewManager);
            }
            initGalleryState.invoke(previewManager, Boolean.TRUE);
            startPlaybackAfterPrepare(playback, url);
            return true;
        } catch (Throwable t) {
            Logger.w("event=managed_video_gallery_state_failed row="
                    + playback.record.rowId, t);
            return false;
        }
    }

    /**
     * Native {@code handleOnlineRequest} does two things: create the gallery player state, then ask
     * {@code VideoPlayManager.doPlayDifferentVideo} to start it. Our interception replaced both, so
     * only the state was created and the user had to tap play again. This replays the second step so
     * a single tap starts playback; a failure here only costs that convenience, not the state.
     */
    private void startPlaybackAfterPrepare(VideoPlaybackRequest playback, String url) {
        Method play = mPlayDifferentVideoMethod;
        if (play == null || playback.playManager == null || playback.item == null) {
            Logger.w("event=managed_video_play_request_skipped row=" + playback.record.rowId);
            return;
        }
        try {
            play.invoke(playback.playManager, playback.item, url, null, null, null, null);
            Logger.i("event=managed_video_play_requested row=" + playback.record.rowId);
        } catch (Throwable t) {
            Logger.w("event=managed_video_play_request_failed row="
                    + playback.record.rowId, t);
        }
    }

    /** Fallback uses Gallery's existing ORIGIN download callback, preserving its path semantics. */
    private void startFullVideoRestore(VideoPlaybackRequest playback, Throwable streamFailure) {
        if (!playback.fallbackStarted.compareAndSet(false, true)) return;
        if (streamFailure != null) {
            Logger.w("event=managed_video_stream_fallback_to_restore row="
                    + playback.record.rowId, streamFailure);
        }
        WebDavVideoSource source = playback.relaySource;
        playback.relaySource = null;
        playback.streamSource = null;
        if (source != null) closeVideoSource(source);
        if (!isVideoPlaybackCurrent(playback)) return;
        try {
            Object manager = playback.downloadManager;
            if (manager == null) return;
            if (isOrigin(call(manager, "getCurDownloadType"))) {
                Logger.d("event=managed_video_playback_restore_already_active");
                return;
            }
            Method downloadOrigin = mDownloadOriginMethod;
            Object origin = mOriginDownloadType;
            if (downloadOrigin == null || origin == null) {
                Logger.w("event=managed_video_playback_restore_entry_missing");
                return;
            }
            Object downloadUriValue = call(playback.item, "getDownloadUri");
            Uri downloadUri = downloadUriValue instanceof Uri ? (Uri) downloadUriValue : null;
            Request request = new Request(manager, playback.item, playback.record,
                    playback.config, playback.record.originalLocalPath, downloadUri,
                    OriginalStore.targetKey(playback.config).equals(playback.record.target));
            Request previousRequest = ACTIVE_REQUEST.get();
            ACTIVE_REQUEST.set(request);
            try {
                downloadOrigin.invoke(manager, origin);
            } finally {
                if (previousRequest == null) ACTIVE_REQUEST.remove();
                else ACTIVE_REQUEST.set(previousRequest);
            }
            Logger.i("event=managed_video_playback_restore_started row="
                    + playback.record.rowId);
        } catch (Throwable t) {
            Logger.e("event=managed_video_playback_restore_start_failed row="
                    + playback.record.rowId, t);
        }
    }

    private boolean isVideoPlaybackCurrent(VideoPlaybackRequest playback) {
        if (!isPlaybackConfigCurrent(playback)) return false;
        try {
            if (playback.playManager != null) {
                Object currentVideo = call(playback.playManager, "getCurrentItem");
                if (currentVideo != playback.video) return false;
                Object currentItem = optionalCall(currentVideo, "getDataItem");
                return currentItem == null || currentItem == playback.item;
            }
            return playback.item == call(playback.downloadManager, "getCurPageItem");
        } catch (Throwable t) {
            Logger.d("event=managed_video_current_item_check_failed detail=" + t);
            return false;
        }
    }

    private boolean isPlaybackConfigCurrent(VideoPlaybackRequest playback) {
        return playback.generation == mVideoPlaybackGeneration.get()
                && sameVideoSourceConfig(playback.config, mConfig);
    }

    private static boolean sameVideoSourceConfig(Config left, Config right) {
        if (left == right) return true;
        if (left == null || right == null || !left.isReady() || !right.isReady()) return false;
        return OriginalStore.targetKey(left).equals(OriginalStore.targetKey(right))
                && safeConfigValue(left.getPassword()).equals(safeConfigValue(right.getPassword()));
    }

    private static String safeConfigValue(String value) { return value == null ? "" : value; }

    private void closeVideoSource(WebDavVideoSource source) {
        if (source == null) return;
        synchronized (mVideoSourceLock) {
            if (mVideoSource == source) mVideoSource = null;
            source.close();
        }
    }

    private void closeVideoSourceLocked() {
        WebDavVideoSource source = mVideoSource;
        mVideoSource = null;
        if (source != null) source.close();
    }

    private void releaseVideoSourceForVideo(Object video) {
        VideoPlaybackRequest playback = mActiveVideoPlayback;
        if (playback == null || playback.video != video
                || mActiveVideoPlayback != playback) return;
        mActiveVideoPlayback = null;
        mVideoPlaybackGeneration.incrementAndGet();
        WebDavVideoSource source = playback.relaySource;
        playback.relaySource = null;
        playback.streamSource = null;
        if (source != null) closeVideoSource(source);
        Logger.d("event=managed_video_stream_closed_page_unselected row="
                + playback.record.rowId);
    }

    private void installMenuHook() {
        try {
            Class<?> menu = mLoader.loadClass(DOWNLOAD_MENU);
            Class<?> item = mLoader.loadClass("com.miui.gallery.model.BaseDataItem");
            Method originTitle = menu.getDeclaredMethod("getDownloadOriginTitle", item);
            originTitle.setAccessible(true);
            mXposed.hook(originTitle).setId("gallery_managed_original_size_title")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        Object dataItem = chain.getArg(0);
                        if (!menuRestoreReady() || dataItem == null) return chain.proceed();
                        try {
                            OriginalStore store = store();
                            OriginalStore.Record record = recordForItem(dataItem, store, mConfig);
                            if (record == null || record.originalLength <= 0
                                    || Boolean.TRUE.equals(optionalCall(dataItem, "isBurstItem"))
                                    || !localBytesNeedRestore(dataItem, record)) {
                                return chain.proceed();
                            }
                            Context context = HostContext.get(mLoader);
                            if (context == null) return chain.proceed();
                            Class<?> formatter = mLoader.loadClass("com.miui.gallery.util.FormatUtil");
                            String size = (String) formatter.getMethod("formatFileSize",
                                    Context.class, long.class).invoke(null, context, record.originalLength);
                            int resource = context.getResources().getIdentifier(
                                    "photo_download_origin", "string", context.getPackageName());
                            if (resource == 0) return "下载原图 (" + size + ")";
                            return context.getString(resource, size);
                        } catch (Throwable t) {
                            Logger.w("event=managed_original_size_title_failed", t);
                            return chain.proceed();
                        }
                    });
            // 各版相册的菜单过滤回调签名不一致（2 参带 LinkedHashMap / 1 参 / 换参数类型），
            // 先把这个类（含父类）声明的方法都收成签名，再交给 MenuHookSignature 逐档放宽匹配。
            List<String> declaredSignatures = new ArrayList<>();
            Map<String, Method> filterMethods = new LinkedHashMap<>();
            for (Class<?> type = menu; type != null && type != Object.class;
                    type = type.getSuperclass()) {
                for (Method declared : type.getDeclaredMethods()) {
                    String signature = menuSignature(declared);
                    if (filterMethods.containsKey(signature)) continue;
                    filterMethods.put(signature, declared);
                    declaredSignatures.add(signature);
                }
            }
            List<String> filterCandidates =
                    MenuHookSignature.candidatesFor(MENU_FILTER_METHOD, declaredSignatures);
            if (filterCandidates.isEmpty()) {
                throw new NoSuchMethodException(MENU_FILTER_MISSING
                        + " candidates=" + declaredSignatures);
            }
            Method refreshItem = menu.getDeclaredMethod("refreshDownloadItem", item,
                    boolean.class, boolean.class);
            Method refreshState = menu.getDeclaredMethod("refreshDownloadItem",
                    boolean.class, boolean.class);
            Class<?> baseDelegate = mLoader.loadClass(
                    "com.miui.gallery.ui.photoPage.bars.menuitem.BaseMenuItemDelegate");
            Class<?> menuHolder = mLoader.loadClass(MENU_HOLDER);
            Class<?> menuItemType = mLoader.loadClass(MENU_ITEM_TYPE);
            Class<?> galleryMenuItem = mLoader.loadClass("com.miui.gallery.view.menu.IMenuItem");
            Method isSupport = baseDelegate.getDeclaredMethod("isSupport");
            Method isVisible = baseDelegate.getDeclaredMethod("isVisible");
            Method getItem = menuHolder.getDeclaredMethod("getItem", menuItemType);
            Method isBottomVisible = menuHolder.getDeclaredMethod("isBottomMenuItemVisible",
                    galleryMenuItem);
            Object downloadType = menuItemType.getField("DOWNLOAD_ORIGINAL").get(null);
            refreshItem.setAccessible(true);
            refreshState.setAccessible(true);
            isSupport.setAccessible(true);
            isVisible.setAccessible(true);
            getItem.setAccessible(true);
            isBottomVisible.setAccessible(true);
            mXposed.hook(isSupport).setId("gallery_managed_original_support")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        Object delegate = chain.getThisObject();
                        if (delegate != null && DOWNLOAD_MENU.equals(delegate.getClass().getName())
                                && menuRestoreReady()) {
                            // Gallery can ask before its provider exposes the current item.
                            // Keep the delegate in its menu pipeline; onFilterFinished applies
                            // the per-item managed check before making it visible.
                            Logger.d("event=managed_original_menu_support_forced");
                            return true;
                        }
                        return chain.proceed();
                    });
            mXposed.hook(isVisible).setId("gallery_managed_original_visibility")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        Object delegate = chain.getThisObject();
                        if (delegate != null && DOWNLOAD_MENU.equals(delegate.getClass().getName())
                                && menuRestoreReady()) {
                            Object currentDataItem = currentItemFromMenu(delegate);
                            if (restorableItem(currentDataItem)) {
                                // MenuItemHolder.lambda$refreshUI$11 checks visibility before
                                // dispatching onFilterFinished, so managed items must pass this
                                // gate once the provider exposes the current BaseDataItem.
                                Logger.d("event=managed_original_menu_visibility_forced");
                                return true;
                            }
                        }
                        return chain.proceed();
                    });
            mXposed.hook(isBottomVisible).setId("gallery_managed_original_bottom_visibility")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        // Arguments exclude the receiver: getArg(0) is the IMenuItem passed
                        // to MenuItemHolder.isBottomMenuItemVisible(IMenuItem).
                        Object menuItem = chain.getArg(0);
                        if (!(menuItem instanceof MenuItem) || !menuRestoreReady()) {
                            return chain.proceed();
                        }
                        try {
                            // Gallery bypasses the delegate's isSupport() here: it reads
                            // IMenuItem.isSupport() directly from the native menu object.
                            // Resolve the matching delegate and apply the registry check at
                            // this final visibility gate only.
                            Object delegate = getItem.invoke(chain.getThisObject(), downloadType);
                            Object delegateItem = call(delegate, "getItemDataState");
                            if (!(delegateItem instanceof MenuItem)
                                    || ((MenuItem) delegateItem).getItemId()
                                    != ((MenuItem) menuItem).getItemId()) {
                                return chain.proceed();
                            }
                            if (restorableItem(currentItemFromMenu(delegate))) {
                                Logger.d("event=managed_original_bottom_visibility_forced");
                                return true;
                            }
                        } catch (Throwable t) {
                            Logger.w("event=managed_original_bottom_visibility_check_failed", t);
                        }
                        return chain.proceed();
                    });
            int filterHooks = 0;
            for (String signature : filterCandidates) {
                Method filter = filterMethods.get(signature);
                if (filter == null) continue;
                filter.setAccessible(true);
                String hookId = filterHooks == 0 ? "gallery_managed_original_menu"
                        : "gallery_managed_original_menu_" + filterHooks;
                mXposed.hook(filter).setId(hookId)
                        .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            // 各版参数个数不同：先找声明类型为 BaseDataItem 的那个实参，再找
                            // 可过滤的 Map 实参；两者都可能缺席，缺席就只做标题/可见性过滤。
                            Object dataItem = null;
                            Map<?, ?> filterMap = null;
                            for (Object arg : chain.getArgs()) {
                                if (dataItem == null && item.isInstance(arg)) {
                                    dataItem = arg;
                                } else if (filterMap == null && arg instanceof Map) {
                                    filterMap = (Map<?, ?>) arg;
                                }
                            }
                            if (mOriginHookReady && mImageLoaderHookReady
                                    && menu.isInstance(chain.getThisObject())
                                    && restorableItem(dataItem)) {
                                try {
                                    // Keep Gallery's per-item title when initialized, then force
                                    // the visible/enabled state through the overload without its
                                    // isFunctionInit early return. 2 参签名的 LinkedHashMap 只是
                                    // Gallery 自己的过滤结果，本模块从不改动它；1 参签名没有 Map
                                    // 可改，这里同样只做标题/可见性过滤。
                                    refreshItem.invoke(chain.getThisObject(), dataItem, true, true);
                                    refreshState.invoke(chain.getThisObject(), true, true);
                                    mMenuHookReady = true;
                                    Logger.d("event=managed_original_menu_enabled filter="
                                            + (filterMap != null ? "map" : "none"));
                                } catch (Throwable t) {
                                    Logger.w("event=managed_original_menu_enable_failed", t);
                                }
                            }
                            return result;
                        });
                filterHooks++;
                Logger.i("event=original_restore_menu_hook_bound method="
                        + MenuHookSignature.methodNameOf(signature)
                        + "/" + MenuHookSignature.arityOf(signature)
                        + " signature=" + signature);
            }
            if (filterHooks == 0) {
                throw new NoSuchMethodException(MENU_FILTER_MISSING);
            }
            mMenuHookReady = true;
            Logger.i("event=hook_registered method=MenuItemHolder.isBottomMenuItemVisible + BaseMenuItemDelegate.isSupport + DownloadOriginal menu filter/2");
        } catch (Throwable t) {
            Logger.e("event=original_restore_menu_hook_missing method=" + MENU_FILTER_MISSING, t);
        }
    }

    /**
     * 平板相册的“下载原图”直接落在 {@code DownloadOriginal.onClick(BaseDataItem)} 上，不像手机端
     * 那样必经 {@code PhotoPageItem$DownloadManager.downloadOrigin(DownloadType)}
     * → {@code CloudImageLoader.loadImage(...)}（后者由 {@link #installCloudLoaderHook()} 接管）。
     * 这里在点击入口再判一次同一张注册表：命中登记的行先做一次**有界远端探测**
     * （{@link #probeRemoteOriginal}，判定见 {@link OriginalClickProbe}），远端确实还在才复用
     * {@link OriginalStore#restoreOriginal} 在后台把原件写回原路径；远端被删/问不出来/大小不符
     * 一律 {@code chain.proceed()} 放行给小米云。未登记（或模块未就绪）的行原样放行，与未装模块时
     * 完全一致。任何反射/线程异常都回落到 {@code chain.proceed()}，不会让用户点了没反应。
     */
    private void installManagedOriginalClickHook() {
        try {
            Class<?> menu = mLoader.loadClass(DOWNLOAD_MENU);
            Class<?> item = mLoader.loadClass("com.miui.gallery.model.BaseDataItem");
            Method click;
            try {
                click = menu.getDeclaredMethod("onClick", item);
            } catch (NoSuchMethodException declaredMissing) {
                // 个别版本把 onClick 声明在父类 BaseMenuItemDelegate 上
                click = menu.getMethod("onClick", item);
            }
            click.setAccessible(true);
            mXposed.hook(click).setId("gallery_managed_original_click")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        Object dataItem = chain.getArg(0);
                        if (dataItem == null) {
                            Logger.d("event=managed_original_click_passthrough");
                            return chain.proceed();
                        }
                        try {
                            // 探测在这里同步做完（内部只等 ≤ PROBE_DEADLINE_MS），因为
                            // chain.proceed() 必须在拦截器线程上调用，不能事后从后台线程补。
                            ClickRequest request = clickRestoreForItem(dataItem);
                            if (request == null) {
                                Logger.d("event=managed_original_click_passthrough");
                                return chain.proceed();
                            }
                            if (!OriginalClickProbe.usesWebDav(request.probe)) {
                                // 远端没有 / 问不出来 / 大小不符：放行原生下载，让小米云接手
                                Logger.i("event=managed_original_click_fallback_xiaomi row="
                                        + request.record.rowId
                                        + " remote=" + request.record.remotePath
                                        + " probe=" + OriginalClickProbe.logToken(request.probe)
                                        + " size=" + request.remoteSize);
                                return chain.proceed();
                            }
                            Logger.i("event=managed_original_click_webdav row="
                                    + request.record.rowId + " remote=" + request.record.remotePath
                                    + " size=" + request.remoteSize);
                            if (!restoreClickOffThread(request)) {
                                Logger.w("event=managed_original_click_fallback"
                                        + " reason=worker_unavailable row=" + request.record.rowId);
                                return chain.proceed();
                            }
                            toastOnMain(HostContext.get(mLoader), "正在从 WebDAV 还原原图");
                            // onClick 是 void：已接管这一行，绝不落到小米云下载
                            return null;
                        } catch (Throwable t) {
                            Logger.w("event=managed_original_click_fallback", t);
                            return chain.proceed();
                        }
                    });
            mMenuClickHookReady = true;
            Logger.i("event=hook_registered method=DownloadOriginal.onClick(BaseDataItem)");
        } catch (Throwable t) {
            mMenuClickHookReady = false;
            Logger.e("event=managed_original_click_hook_missing"
                    + " method=DownloadOriginal.onClick(BaseDataItem)", t);
        }
    }

    /**
     * 点击分支的行判定，与菜单钩子同源：{@link #recordForItem} 认这张注册表，再确认这条记录确实
     * 能还原（有远端路径、有落盘目标），最后做一次有界远端探测。
     *
     * <p>探测放在这里而不是调用方：判定“这一行该不该由 WebDAV 接手”只取决于这张注册表和远端现状，
     * 调用方拿到的 {@link ClickRequest#probe} 就是结论（{@link OriginalClickProbe}）。探测失败不会
     * 让这里返回 null —— 那等于“未登记”，会把“远端没了”和“这行不归我管”混成同一条日志。
     */
    private ClickRequest clickRestoreForItem(Object item) throws Exception {
        Config config = mConfig;
        if (item == null || config == null || !config.isReady()) return null;
        OriginalStore store = store();
        if (store == null) return null;
        OriginalStore.Record record = recordForItem(item, store, config);
        if (record == null) return null;
        String destination = record.originalLocalPath;
        if (record.remotePath == null || record.remotePath.isEmpty()
                || destination == null || destination.isEmpty()) {
            Logger.w("event=managed_original_click_unrestorable row=" + record.rowId
                    + " remote=" + record.remotePath + " dest=" + (destination != null));
            return null;
        }
        boolean targetMatches = OriginalStore.targetKey(config).equals(record.target);
        ProbeResult probe = probeRemoteOriginal(config, record);
        return new ClickRequest(item, record, config, destination, targetMatches,
                probe.outcome, probe.remoteSize);
    }

    /** 探测的一次观测：结论 + 远端报告的大小（判定用不上它，只进日志）。 */
    private static final class ProbeResult {
        final OriginalClickProbe.Outcome outcome;
        final long remoteSize;

        ProbeResult(OriginalClickProbe.Outcome outcome, long remoteSize) {
            this.outcome = outcome;
            this.remoteSize = remoteSize;
        }
    }

    /**
     * 点击前的有界远端探测：{@code PROPFIND Depth 0} 问一次大小，结论交给
     * {@link OriginalClickProbe#classify}。
     *
     * <p><b>为什么网络那一句必须离开调用线程</b>：onClick 落在相册主线程，直接在这里发 PROPFIND
     * 就是一次最长十几秒的 ANR。所以请求提交给 {@link #mProbeWorker}，本方法只等到硬截止
     * （{@link #PROBE_DEADLINE_MS}）—— 与 {@code UploadQueue.probeRemoteExists} 同一套做法：
     * 单线程、0 格队列、{@code Future.get(deadline)}。
     *
     * <p><b>超时不强杀请求</b>：{@code cancel(true)} 会打断它，但 HTTP 读不一定立刻可中断，它可能
     * 在后台跑完。没关系 —— 它用的是自己那条连接（每次都新建 {@link WebDavClient}），结果也只会
     * 进日志，已经不在任何判定里。
     *
     * <p>只有 {@link OriginalClickProbe.Outcome#PRESENT} 才允许接管这次点击；“探测没答上来”不等于
     * “远端有”，一律按回退处理。
     */
    private ProbeResult probeRemoteOriginal(Config config, OriginalStore.Record record) {
        long expected = record.originalLength;
        long cached = cachedProbeSize(record);
        if (cached > 0L) {
            OriginalClickProbe.Outcome hit = OriginalClickProbe.classify(cached, expected);
            Logger.d("event=managed_original_click_probe_hit row=" + record.rowId
                    + " remote=" + record.remotePath + " size=" + cached
                    + " probe=" + OriginalClickProbe.logToken(hit));
            return new ProbeResult(hit, cached);
        }
        final long start = System.currentTimeMillis();
        java.util.concurrent.Callable<Long> probe = () -> remoteSizeForClick(config, record);
        Future<Long> future;
        try {
            future = mProbeWorker.submit(probe);
        } catch (Throwable rejected) {
            // 上一条探测还没收尾：绝不排队等它，直接按“问不出来”回退
            Logger.w("event=managed_original_click_probe_rejected row=" + record.rowId, rejected);
            return new ProbeResult(OriginalClickProbe.Outcome.UNKNOWN, 0L);
        }
        try {
            Long remoteSize = future.get(PROBE_DEADLINE_MS, TimeUnit.MILLISECONDS);
            long waited = System.currentTimeMillis() - start;
            if (remoteSize == null) {
                Logger.d("event=managed_original_click_probe_error row=" + record.rowId
                        + " waited_ms=" + waited);
                return new ProbeResult(OriginalClickProbe.Outcome.ERROR, 0L);
            }
            OriginalClickProbe.Outcome outcome = OriginalClickProbe.classify(remoteSize, expected);
            rememberProbeSize(record, remoteSize);
            Logger.d("event=managed_original_click_probe_done row=" + record.rowId
                    + " remote=" + record.remotePath + " size=" + remoteSize
                    + " expected=" + expected + " probe=" + OriginalClickProbe.logToken(outcome)
                    + " waited_ms=" + waited);
            return new ProbeResult(outcome, remoteSize);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            Logger.w("event=managed_original_click_probe_timeout row=" + record.rowId
                    + " remote=" + record.remotePath + " deadline_ms=" + PROBE_DEADLINE_MS);
            return new ProbeResult(OriginalClickProbe.Outcome.TIMEOUT, 0L);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            // 把中断标志还回去：拦截器线程可能还要靠它退出
            Thread.currentThread().interrupt();
            return new ProbeResult(OriginalClickProbe.Outcome.ERROR, 0L);
        } catch (Throwable failure) {
            future.cancel(true);
            Logger.w("event=managed_original_click_probe_failed row=" + record.rowId
                    + " remote=" + record.remotePath, failure);
            return new ProbeResult(OriginalClickProbe.Outcome.ERROR, 0L);
        }
    }

    /** 真正发请求的那一句，只在 {@link #mProbeWorker} 上跑；每次自带一条连接，用完就关。 */
    private Long remoteSizeForClick(Config config, OriginalStore.Record record) throws IOException {
        long at = System.currentTimeMillis();
        WebDavClient client = new WebDavClient(config.getBaseUrl(), config.getUsername(),
                config.getPassword());
        try {
            long size = client.propfindSize(record.remotePath);
            Logger.d("event=managed_original_click_probe_task row=" + record.rowId
                    + " size=" + size + " probe_ms=" + (System.currentTimeMillis() - at));
            return size;
        } finally {
            client.close();
        }
    }

    /**
     * 菜单判定时的预热：菜单已经显示出来，用户几乎必然紧接着就会点，先把这次探测做掉，
     * 点击时就直接命中缓存、主线程一点都不用等。
     *
     * <p>完全尽力而为：这条线程满了、探测失败、远端是坏的，都只留一行日志，不影响菜单。
     */
    private void prefetchRemoteProbe(final Config config, final OriginalStore.Record record) {
        if (config == null || record == null || record.remotePath == null
                || record.remotePath.isEmpty()) {
            return;
        }
        if (cachedProbeSize(record) > 0L) return;
        try {
            mProbePrefetchWorker.execute(() -> {
                long size;
                try {
                    size = remoteSizeForClick(config, record);
                } catch (Throwable t) {
                    Logger.d("event=managed_original_probe_prefetch_failed row=" + record.rowId
                            + " detail=" + t);
                    return;
                }
                rememberProbeSize(record, size);
                Logger.d("event=managed_original_probe_prefetch row=" + record.rowId
                        + " remote=" + record.remotePath + " size=" + size);
            });
        } catch (Throwable rejected) {
            Logger.d("event=managed_original_probe_prefetch_skipped row=" + record.rowId
                    + " detail=" + rejected);
        }
    }

    /** 缓存键：同一行换了服务器/目标空间就是另一条结论，所以 target 必须进键。 */
    private static String probeKey(OriginalStore.Record record) {
        return String.valueOf(record.target) + '|' + record.remotePath;
    }

    /** 命中且未过期的缓存大小；没有/过期返回 0（合法大小一定 > 0）。 */
    private long cachedProbeSize(OriginalStore.Record record) {
        String key = probeKey(record);
        long[] hit = mProbeHits.get(key);
        if (hit == null) return 0L;
        long age = System.currentTimeMillis() - hit[1];
        if (age < 0L || age > PROBE_HIT_TTL_MS) {
            mProbeHits.remove(key, hit);
            return 0L;
        }
        return hit[0];
    }

    /**
     * 只缓存“远端确认在且给了大小”的结论：缓存“不在/问不出来”会让一次网络抖动把接下来两分钟的
     * 点击全部误判成回退，而缓存“在”最多让用户多试一次 WebDAV 还原。
     */
    private void rememberProbeSize(OriginalStore.Record record, long size) {
        if (record == null || size <= 0L) return;
        if (mProbeHits.size() >= PROBE_HIT_LIMIT) mProbeHits.clear();
        mProbeHits.put(probeKey(record), new long[]{size, System.currentTimeMillis()});
    }

    /**
     * 平板点击分支的后台还原：走的就是手机端那条 {@link OriginalStore#restoreOriginal}（下载、
     * 长度/摘要校验、原子发布、状态落库全在它里面），这里只负责线程切换与结果提示。
     */
    private boolean restoreClickOffThread(final ClickRequest request) {
        try {
            mWorker.execute(() -> {
                File restored = null;
                Throwable failure = null;
                Logger.i("event=managed_original_click_worker_started row=" + request.record.rowId);
                try {
                    if (!request.targetMatches) {
                        throw new IllegalStateException("WebDAV target is not active for this item");
                    }
                    OriginalStore store = store();
                    if (store == null) {
                        throw new IllegalStateException("verified original registry is unavailable");
                    }
                    restored = store.restoreOriginal(request.config, request.record,
                            new File(request.destination));
                    Logger.i("event=managed_original_click_file_ready row="
                            + request.record.rowId + " bytes=" + restored.length());
                } catch (Throwable t) {
                    failure = t;
                }
                File restoredFile = restored;
                Throwable finalFailure = failure;
                if (!mMain.post(() -> announceClickRestore(request, restoredFile, finalFailure))) {
                    Logger.w("event=managed_original_click_callback_rejected row="
                            + request.record.rowId);
                }
            });
            return true;
        } catch (Throwable t) {
            Logger.w("event=managed_original_click_worker_unavailable row="
                    + request.record.rowId, t);
            return false;
        }
    }

    private void announceClickRestore(ClickRequest request, File restored, Throwable failure) {
        Context context = HostContext.get(mLoader);
        if (failure == null && restored != null && restored.isFile()) {
            Logger.i("event=managed_original_click_restored row=" + request.record.rowId
                    + " bytes=" + restored.length());
            toastOnMain(context, "原图已还原，可重新打开查看");
            return;
        }
        Logger.w("event=managed_original_click_restore_failed row=" + request.record.rowId
                + " detail=" + failure);
        toastOnMain(context, "原图还原失败，请重试");
    }

    /** 反射方法转 {@code name(paramType1,paramType2)} 形式的签名，参数类型只留简单名。 */
    private static String menuSignature(Method method) {
        StringBuilder sb = new StringBuilder(method.getName()).append('(');
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(MenuHookSignature.simpleName(params[i].getTypeName()));
        }
        return sb.append(')').toString();
    }

    private Request requestForManager(Object manager) {
        try {
            Object page = field(manager, "this$0");
            Object item = field(page, "mDataItem");
            if (item == null) return null;
            OriginalStore store = store();
            Config config = mConfig;
            OriginalStore.Record record = recordForItem(item, store, config);
            if (record == null) return null;
            Object uriValue = call(item, "getDownloadUri");
            Uri uri = uriValue instanceof Uri ? (Uri) uriValue : null;
            String destination = record.originalLocalPath;
            boolean targetMatches = config != null
                    && OriginalStore.targetKey(config).equals(record.target);
            return new Request(manager, item, record, config, destination, uri, targetMatches);
        } catch (Throwable t) {
            Logger.w("event=original_restore_item_lookup_failed", t);
            return null;
        }
    }

    /**
     * “下载原图”只在本地确实缺少完整原图时出现。
     *
     * <p>第 3 波把判据从“路径/长度/派生副本”换成注册表登记的本地状态：
     * {@link PreviewStateRules#needsOriginalRestore}。旧判据只是“该 item 在注册表里有记录”，
     * 上传成功就会登记，于是从未优化过的普通照片（本地就是完整原图）也显示了菜单；
     * {@code ORIGINAL} 且长度一致时必须不显示。四个预览类状态（原地预览 / 写入中途 /
     * 侧车预览 / 损坏）一律显示。
     */
    private boolean restorableItem(Object item) {
        if (!mOriginHookReady || !mImageLoaderHookReady || item == null) return false;
        Config config = mConfig;
        if (config == null || !config.isReady()) return false;
        OriginalStore store = store();
        try {
            OriginalStore.Record record = recordForItem(item, store, config);
            if (record == null) return false;
            boolean show = localBytesNeedRestore(item, record);
            // 菜单已经要显示出来了：用户几乎必然紧接着就点，先把点击时那次远端探测做掉，
            // 真正点击时就命中缓存、主线程完全不用等那 6 秒预算。
            if (show) prefetchRemoteProbe(config, record);
            // 每次菜单可见性询问都记一行：排查“为什么没优化也显示下载原图”时只需看这一条。
            Logger.d("event=download_original_menu row=" + record.rowId
                    + " state=" + record.getLocalState().wire()
                    + " desc=" + PreviewStateRules.describe(record.getLocalState())
                    + " show=" + show);
            return show;
        } catch (Throwable t) {
            Logger.d("event=managed_original_lookup_failed detail=" + t);
            return false;
        }
    }

    /**
     * 该 item 是否还需要恢复完整原图：按注册表状态判，不再猜“路径是不是在隐藏目录里”。
     *
     * <p>状态不是 {@code ORIGINAL} 时结论与本地长度无关（本地那份文件本来就不是原图），
     * 直接返回，连路径探测都省掉；{@code ORIGINAL} 时取 {@code localFile} 对应当前本地长度，
     * 长度一致（或长度未知）就不显示。只做路径与长度判定，不哈希，主线程安全。
     */
    private boolean localBytesNeedRestore(Object item, OriginalStore.Record record) throws Exception {
        OriginalStore.LocalState state = record.getLocalState();
        if (PreviewStateRules.needsOriginalRestore(state, 0L, record.originalLength)) {
            return true;
        }
        for (String getter : ITEM_LOCAL_PATH_GETTERS) {
            File file = localFileFromPath(optionalStringCall(item, getter));
            if (file == null) continue;
            long localLength = file.isFile() ? file.length() : 0L;
            if (!PreviewStateRules.needsOriginalRestore(state, localLength,
                    record.originalLength)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 视频页面一展示就开始预热：把 302 换直链和两次 TLS 握手都赶在用户按下播放之前做完。
     * 失败完全无害 —— 播放时还会照常再试一次。
     */
    private void prewarmVideoItemAsync(Object item) {
        try {
            Config config = mConfig;
            OriginalStore store = store();
            if (item == null || config == null || store == null || !config.isReady()) {
                return;
            }
            OriginalStore.Record record = recordForItem(item, store, config);
            if (record == null || record.remotePath == null || record.remotePath.isEmpty()) {
                return;
            }
            mVideoSourceWorker.execute(() -> prewarmVideoRecord(config, record));
        } catch (Throwable t) {
            Logger.w("event=video_prewarm_skip", t);
        }
    }

    private void prewarmVideoRecord(Config config, OriginalStore.Record record) {
        long startedAt = System.currentTimeMillis();
        com.tongluosao.gallerysync.core.WebDavClient client =
                new com.tongluosao.gallerysync.core.WebDavClient(config.getBaseUrl(),
                        config.getUsername(), config.getPassword());
        try (com.tongluosao.gallerysync.core.WebDavClient.RangeResponse response =
                     client.newRangeCall(record.remotePath, 0L, 0L).open()) {
            response.body().read();
            Logger.i("event=video_prewarm_ok elapsed="
                    + (System.currentTimeMillis() - startedAt) + " row=" + record.rowId);
        } catch (Throwable t) {
            Logger.w("event=video_prewarm_failed row=" + record.rowId, t);
        } finally {
            client.close();
        }
    }

    private OriginalStore.Record recordForItem(Object item, OriginalStore store, Config config)
            throws Exception {
        if (store == null || config == null || item == null) return null;
        String target = OriginalStore.targetKey(config);
        for (String getter : ITEM_LOCAL_PATH_GETTERS) {
            String path = optionalStringCall(item, getter);
            if (path == null || path.isEmpty()) continue;
            File localFile = localFileFromPath(path);
            if (localFile == null) continue;
            OriginalStore.Record record = store.findByLocalPath(localFile);
            if (record != null && target.equals(record.target)) {
                // Bind lazily too: a scanner may finish after the bounded background lookup.
                Object key = optionalCall(item, "getKey");
                String id = key == null ? null : String.valueOf(key);
                String sha1 = optionalStringCall(item, "getSha1");
                if ((id != null || sha1 != null) && store.find(config, id, sha1) == null) {
                    store.bindIdentity(localFile, id, sha1);
                }
                return store.findByLocalPath(localFile);
            }
        }
        Object key = optionalCall(item, "getKey");
        String id = key == null ? null : String.valueOf(key);
        String sha1 = optionalStringCall(item, "getSha1");
        return id == null && sha1 == null ? null : store.find(config, id, sha1);
    }

    private static File localFileFromPath(String path) {
        if (path == null || path.isEmpty()) return null;
        Uri uri = Uri.parse(path);
        String scheme = uri.getScheme();
        if (scheme == null || scheme.isEmpty()) return new File(path);
        if ("file".equalsIgnoreCase(scheme)) {
            String filePath = uri.getPath();
            return filePath == null || filePath.isEmpty() ? null : new File(filePath);
        }
        return null;
    }

    private boolean menuRestoreReady() {
        Config config = mConfig;
        return mOriginHookReady && mImageLoaderHookReady
                && config != null && config.isReady();
    }

    private Object currentItemFromMenu(Object delegate) {
        try {
            Object provider = field(delegate, "mDataProvider");
            Object data = call(provider, "getFieldData");
            Object current = field(data, "mCurrent");
            // DEX: DataProvider$FieldData$Current.getDataItem() returns BaseDataItem.
            return call(current, "getDataItem");
        } catch (Throwable t) {
            Logger.d("event=managed_original_visibility_lookup_failed detail=" + t);
            return null;
        }
    }

    private void restoreOffThread(Request request, Uri uri, Object type, View view, Object listener) {
        try {
            mWorker.execute(() -> {
                File restored = null;
                Throwable failure = null;
                Logger.i("event=managed_original_restore_worker_started row="
                        + request.record.rowId);
                try {
                    if (!request.targetMatches || request.config == null || !request.config.isReady()) {
                        throw new IllegalStateException("WebDAV target is not active for this managed item");
                    }
                    if (request.destination == null || request.destination.isEmpty()) {
                        throw new IllegalStateException("verified original destination is unavailable");
                    }
                    OriginalStore store = store();
                    if (store == null || request.record.rowId == null && request.record.sha1 == null) {
                        throw new IllegalStateException("verified original identity is unavailable");
                    }
                    restored = store.restoreOriginal(request.config, request.record.rowId,
                            request.record.sha1, new File(request.destination));
                    Logger.i("event=managed_original_restore_file_ready row="
                            + request.record.rowId + " bytes=" + restored.length());
                } catch (Throwable t) {
                    failure = t;
                }
                File finalRestored = restored;
                Throwable finalFailure = failure;
                boolean callbackQueued = mMain.post(() -> deliver(request, uri, type, view,
                        listener, finalRestored, finalFailure));
                if (!callbackQueued) {
                    Logger.e("event=managed_original_restore_callback_rejected row="
                            + request.record.rowId);
                    clearUserDownloadState(request);
                }
            });
        } catch (Throwable t) {
            deliver(request, uri, type, view, listener, null, t);
        }
    }

    private void deliver(Request request, Uri uri, Object type, View view, Object listener,
            File restored, Throwable failure) {
        if (uri == null && !isCurrentItem(request)) {
            Logger.d("event=managed_original_callback_stale_item");
            return;
        }
        if (listener == null || type == null) {
            Logger.e("event=managed_original_restore_callback_unavailable row="
                    + request.record.rowId + " listener=" + (listener != null)
                    + " type=" + (type != null));
            clearUserDownloadState(request);
            return;
        }
        try {
            Class<?> listenerType = mLoader.loadClass(LISTENER);
            if (failure == null && restored != null && restored.isFile()) {
                Method complete = listenerType.getMethod("onDownloadComplete", Uri.class,
                        type.getClass(), View.class, String.class);
                complete.invoke(listener, uri, type, view, restored.getAbsolutePath());
                // onDownloadComplete updates BaseDataItem and starts Gallery's native Glide display
                // from the verified local path. Reset the native user-download state afterwards.
                Method loaded = request.manager.getClass().getMethod("onLoaded");
                loaded.invoke(request.manager);
                Logger.i("event=managed_original_restored row=" + request.record.rowId
                        + " bytes=" + restored.length());
                return;
            }
            Class<?> errorType = mLoader.loadClass(ERROR_CODE);
            Object error = errorType.getField("FILE_OPERATE_ERROR").get(null);
            Method failed = listenerType.getMethod("onLoadingFailed", Uri.class,
                    type.getClass(), View.class, errorType, String.class);
            failed.invoke(listener, uri, type, view, error,
                    failure == null ? "restored original is missing" : failure.toString());
            Logger.w("event=managed_original_restore_failed row=" + request.record.rowId
                    + " detail=" + failure);
        } catch (Throwable t) {
            Logger.e("event=managed_original_callback_failed row=" + request.record.rowId, t);
            // The native failure callback clears this flag itself. If reflection or the
            // callback implementation failed before doing so, onLoaded is a safe final reset.
            clearUserDownloadState(request);
        }
    }

    private boolean isCurrentItem(Request request) {
        try {
            return request.item != null
                    && request.item == call(request.manager, "getCurPageItem");
        } catch (Throwable t) {
            return false;
        }
    }

    private void clearUserDownloadState(Request request) {
        if (!isCurrentItem(request)) return;
        try {
            Method loaded = request.manager.getClass().getMethod("onLoaded");
            loaded.invoke(request.manager);
        } catch (Throwable t) {
            Logger.w("event=managed_original_callback_cleanup_failed row="
                    + request.record.rowId, t);
        }
    }

    private boolean isOrigin(Object type) {
        if (type == null) return false;
        String name = type instanceof Enum ? ((Enum<?>) type).name() : type.toString();
        return "ORIGIN".equals(name) || "ORIGIN_FORCE".equals(name);
    }

    private OriginalStore store() {
        OriginalStore value = mStore;
        if (value != null) return value;
        Context context = HostContext.get(mLoader);
        if (context == null) return null;
        value = OriginalStore.get(context.getApplicationContext());
        mStore = value;
        return value;
    }

    private static Object field(Object target, String name) throws Exception {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) { type = type.getSuperclass(); }
        }
        return null;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        if (target == null) throw new IllegalArgumentException("missing field target " + name);
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private static void optionalSetField(Object target, String name, Object value) {
        try {
            setField(target, name, value);
        } catch (Throwable ignored) {
            // Field names differ between Gallery builds; only the URL is required.
        }
    }

    private static Object call(Object target, String name) throws Exception {
        return target == null ? null : target.getClass().getMethod(name).invoke(target);
    }

    private static String stringCall(Object target, String name) throws Exception {
        Object value = call(target, name);
        return value == null ? null : String.valueOf(value);
    }

    private static Object optionalCall(Object target, String name) throws Exception {
        try {
            return call(target, name);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static String optionalStringCall(Object target, String name) throws Exception {
        Object value = optionalCall(target, name);
        return value == null ? null : String.valueOf(value);
    }

    void shutdown() {
        synchronized (mVideoSourceLock) {
            mVideoPlaybackGeneration.incrementAndGet();
            closeVideoSourceLocked();
            mConfig = null;
        }
        mVideoPlaybackHookReady = false;
        mVideoErrorHookReady = false;
        mMenuClickHookReady = false;
        mActiveVideoPlayback = null;
        mOriginHookReady = false;
        mImageLoaderHookReady = false;
        mProbeHits.clear();
        mProbeWorker.shutdownNow();
        mProbePrefetchWorker.shutdownNow();
        mWorker.shutdownNow();
        mVideoSourceWorker.shutdownNow();
    }

    private static final class VideoPlaybackRequest {
        final Object video;
        final Object item;
        final Object downloadManager;
        final Object playManager;
        final OriginalStore.Record record;
        final Config config;
        final long generation;
        final AtomicBoolean fallbackStarted = new AtomicBoolean();
        /** 每次重试换一个序号：被替换的老下载线程看到序号不符就安静退出。 */
        final AtomicInteger downloadRun = new AtomicInteger();
        volatile WebDavVideoSource relaySource;
        volatile WebDavVideoSource streamSource;
        /** 渐进缓存中的 {@code .part}；中继优先从它读取已下好的区间。 */
        volatile File progressivePart;

        VideoPlaybackRequest(Object video, Object item, Object downloadManager,
                Object playManager, OriginalStore.Record record, Config config, long generation) {
            this.video = video;
            this.item = item;
            this.downloadManager = downloadManager;
            this.playManager = playManager;
            this.record = record;
            this.config = config;
            this.generation = generation;
        }
    }

    /** 平板“下载原图”点击分支捕获到的一行：注册表记录、落盘目标，以及那次远端探测的结论。 */
    private static final class ClickRequest {
        final Object item;
        final OriginalStore.Record record;
        final Config config;
        final String destination;
        final boolean targetMatches;
        /** 远端探测结论；只有 {@link OriginalClickProbe.Outcome#PRESENT} 才由本模块接管这次点击。 */
        final OriginalClickProbe.Outcome probe;
        /** 远端报告的大小（0 = 探测没给出来），只进日志。 */
        final long remoteSize;

        ClickRequest(Object item, OriginalStore.Record record, Config config, String destination,
                boolean targetMatches, OriginalClickProbe.Outcome probe, long remoteSize) {
            this.item = item;
            this.record = record;
            this.config = config;
            this.destination = destination;
            this.targetMatches = targetMatches;
            this.probe = probe;
            this.remoteSize = remoteSize;
        }
    }

    private static final class Request {
        final Object manager;
        final Object item;
        final OriginalStore.Record record;
        final Config config;
        final String destination;
        final Uri uri;
        final boolean targetMatches;
        Request(Object manager, Object item, OriginalStore.Record record, Config config,
                String destination, Uri uri, boolean targetMatches) {
            this.manager = manager;
            this.item = item;
            this.record = record;
            this.config = config;
            this.destination = destination;
            this.uri = uri;
            this.targetMatches = targetMatches;
        }
    }
}
