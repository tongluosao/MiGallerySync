package com.tongluosao.gallerysync.hook;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.tongluosao.gallerysync.core.Config;
import com.tongluosao.gallerysync.core.Logger;
import com.tongluosao.gallerysync.core.OriginalStore;
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
    private static final String MENU_HOLDER = "com.miui.gallery.ui.photoPage.bars.PhotoPageMenuManager$MenuItemHolder";
    private static final String MENU_ITEM_TYPE = "com.miui.gallery.ui.photoPage.bars.PhotoPageMenuManager$MenuItemType";
    /** BaseDataItem 上可能指向本机完整原图的候选路径，顺序即优先级。 */
    private static final String[] ITEM_LOCAL_PATH_GETTERS =
            {"getOriginalPath", "getThumnailPath", "getMicroPath"};
    private static final ThreadLocal<Request> ACTIVE_REQUEST = new ThreadLocal<>();

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
                        prepareVideoStream(playback);
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

    private void prepareVideoStream(VideoPlaybackRequest playback) {
        try {
            mVideoSourceWorker.execute(() -> {
                WebDavVideoSource source = new WebDavVideoSource();
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
            return true;
        } catch (Throwable t) {
            Logger.w("event=managed_video_gallery_state_failed row="
                    + playback.record.rowId, t);
            return false;
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
                                    || !localBytesNeedRestore(dataItem, store, record)) {
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
            Method filter = menu.getDeclaredMethod("onFilterFinished", item, LinkedHashMap.class);
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
            filter.setAccessible(true);
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
            mXposed.hook(filter).setId("gallery_managed_original_menu")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object dataItem = chain.getArg(0);
                        if (mOriginHookReady && mImageLoaderHookReady && restorableItem(dataItem)) {
                            try {
                                // Keep Gallery's per-item title when initialized, then force the
                                // visible/enabled state through the overload without its
                                // isFunctionInit early return.
                                refreshItem.invoke(chain.getThisObject(), dataItem, true, true);
                                refreshState.invoke(chain.getThisObject(), true, true);
                                mMenuHookReady = true;
                                Logger.d("event=managed_original_menu_enabled");
                            } catch (Throwable t) {
                                Logger.w("event=managed_original_menu_enable_failed", t);
                            }
                        }
                        return result;
                    });
            mMenuHookReady = true;
            Logger.i("event=hook_registered method=MenuItemHolder.isBottomMenuItemVisible + BaseMenuItemDelegate.isSupport + DownloadOriginal.onFilterFinished(BaseDataItem,LinkedHashMap)");
        } catch (Throwable t) {
            Logger.e("event=original_restore_menu_hook_missing method=DownloadOriginal.onFilterFinished/2", t);
        }
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
     * <p>旧判据只是“该 item 在注册表里有记录”，但上传成功就会登记，因此从未优化过的普通照片
     * （本地文件就是完整原图）也满足条件，菜单与标题会错误地显示原图大小。
     * 现在记录之后再加一道 {@link #localBytesNeedRestore} 物理判定：本地已有完整原图 → 不显示。
     */
    private boolean restorableItem(Object item) {
        if (!mOriginHookReady || !mImageLoaderHookReady || item == null) return false;
        Config config = mConfig;
        if (config == null || !config.isReady()) return false;
        OriginalStore store = store();
        try {
            OriginalStore.Record record = recordForItem(item, store, config);
            return record != null && localBytesNeedRestore(item, store, record);
        } catch (Throwable t) {
            Logger.d("event=managed_original_lookup_failed detail=" + t);
            return false;
        }
    }

    /**
     * item 的任一本机候选路径本身就是完整原图（存在、不是登记过的派生副本、长度等于登记原图
     * 长度）→ 不需要恢复。空间优化成功后 Gallery 会把 thumbnailFile 指向 .gallerysync-slim
     * 副本、localFile 清空，所以原图路径缺失、缩略图路径是登记副本，两者都判成“需要恢复”。
     *
     * <p>只做路径与长度判定（{@link OriginalStore#isRegisteredDerivedPath} 不哈希），主线程安全。
     */
    private boolean localBytesNeedRestore(Object item, OriginalStore store,
            OriginalStore.Record record) throws Exception {
        for (String getter : ITEM_LOCAL_PATH_GETTERS) {
            File file = localFileFromPath(optionalStringCall(item, getter));
            if (file == null) continue;
            boolean exists = file.isFile();
            boolean derived = exists && store != null
                    && store.isRegisteredDerivedPath(record, file);
            if (!OriginalStore.needsOriginalRestore(record.originalLength, record.sha256,
                    exists, exists ? file.length() : 0L, derived, null)) {
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
        mActiveVideoPlayback = null;
        mOriginHookReady = false;
        mImageLoaderHookReady = false;
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
        volatile WebDavVideoSource relaySource;
        volatile WebDavVideoSource streamSource;

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
