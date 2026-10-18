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

import com.tongluosao.gallerysync.core.Config;
import com.tongluosao.gallerysync.core.Logger;
import com.tongluosao.gallerysync.core.OriginalStore;

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
    private static final ThreadLocal<Request> ACTIVE_REQUEST = new ThreadLocal<>();

    private final XposedInterface mXposed;
    private final ClassLoader mLoader;
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "gallery-original-restore");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile Config mConfig;
    private volatile OriginalStore mStore;
    private volatile boolean mOriginHookReady;
    private volatile boolean mImageLoaderHookReady;
    private volatile boolean mVideoPlaybackHookReady;
    private volatile boolean mMenuHookReady;

    OriginalRestoreHook(XposedInterface xposed, ClassLoader loader) {
        mXposed = xposed;
        mLoader = loader;
    }

    void setConfig(Config config) { mConfig = config; }

    boolean canRestoreVideos() {
        Config config = mConfig;
        return mOriginHookReady && mImageLoaderHookReady && mVideoPlaybackHookReady
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
            Class<?> downloadType = mLoader.loadClass(DOWNLOAD_TYPE);
            Class<?> managerType = mLoader.loadClass(DOWNLOAD_MANAGER);
            Method handleOnlineRequest = videoItem.getDeclaredMethod("handleOnlineRequest");
            Method downloadOrigin = managerType.getDeclaredMethod("downloadOrigin", downloadType);
            Object origin = downloadType.getField("ORIGIN").get(null);
            handleOnlineRequest.setAccessible(true);
            downloadOrigin.setAccessible(true);
            mXposed.hook(handleOnlineRequest).setId("gallery_managed_video_playback_restore")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        if (!mOriginHookReady || !mImageLoaderHookReady) return chain.proceed();
                        Object video = chain.getThisObject();
                        Object item;
                        OriginalStore.Record record;
                        try {
                            item = field(video, "mDataItem");
                            if (item == null) return chain.proceed();
                            Config config = mConfig;
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
                            // VideoDownloadManager inherits this typed overload. Its native
                            // completion callback updates the item's original path and resumes
                            // playback after the video view is added.
                            downloadOrigin.invoke(manager, origin);
                            Logger.i("event=managed_video_playback_restore_started row="
                                    + record.rowId);
                        } catch (Throwable t) {
                            Logger.e("event=managed_video_playback_restore_start_failed row="
                                    + record.rowId, t);
                        }
                        // Do not fall through to Gallery's Xiaomi OnlineVideo URL request for
                        // a managed item whose registered local original is absent.
                        return Boolean.TRUE;
                    });
            mVideoPlaybackHookReady = true;
            Logger.i("event=hook_registered method=PhotoPageVideoItem.handleOnlineRequest()");
        } catch (Throwable t) {
            Logger.e("event=managed_video_playback_restore_hook_missing method=PhotoPageVideoItem.handleOnlineRequest()", t);
        }
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
                            OriginalStore.Record record = recordForItem(dataItem, store(), mConfig);
                            if (record == null || record.originalLength <= 0
                                    || Boolean.TRUE.equals(optionalCall(dataItem, "isBurstItem"))) {
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
                            if (managedItem(currentDataItem)) {
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
                            if (managedItem(currentItemFromMenu(delegate))) {
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
                        if (mOriginHookReady && mImageLoaderHookReady && managedItem(dataItem)) {
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

    private boolean managedItem(Object item) {
        if (!mOriginHookReady || !mImageLoaderHookReady || item == null) return false;
        Config config = mConfig;
        if (config == null || !config.isReady()) return false;
        try {
            return recordForItem(item, store(), config) != null;
        } catch (Throwable t) {
            Logger.d("event=managed_original_lookup_failed detail=" + t);
            return false;
        }
    }

    private OriginalStore.Record recordForItem(Object item, OriginalStore store, Config config)
            throws Exception {
        if (store == null || config == null || item == null) return null;
        String target = OriginalStore.targetKey(config);
        for (String getter : new String[]{"getOriginalPath", "getThumnailPath", "getMicroPath"}) {
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
                } catch (Throwable t) {
                    failure = t;
                }
                File finalRestored = restored;
                Throwable finalFailure = failure;
                mMain.post(() -> deliver(request, uri, type, view, listener,
                        finalRestored, finalFailure));
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
        mVideoPlaybackHookReady = false;
        mOriginHookReady = false;
        mImageLoaderHookReady = false;
        mConfig = null;
        mWorker.shutdownNow();
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
