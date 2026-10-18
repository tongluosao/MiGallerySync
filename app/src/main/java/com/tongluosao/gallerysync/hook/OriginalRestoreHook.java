package com.tongluosao.gallerysync.hook;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
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
    private volatile boolean mMenuHookReady;

    OriginalRestoreHook(XposedInterface xposed, ClassLoader loader) {
        mXposed = xposed;
        mLoader = loader;
    }

    void setConfig(Config config) { mConfig = config; }

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

    private void installMenuHook() {
        try {
            Class<?> menu = mLoader.loadClass(DOWNLOAD_MENU);
            Class<?> item = mLoader.loadClass("com.miui.gallery.model.BaseDataItem");
            Method filter = menu.getDeclaredMethod("onFilterFinished", item, LinkedHashMap.class);
            Method refreshItem = menu.getDeclaredMethod("refreshDownloadItem", item,
                    boolean.class, boolean.class);
            Method refreshState = menu.getDeclaredMethod("refreshDownloadItem",
                    boolean.class, boolean.class);
            Class<?> baseDelegate = mLoader.loadClass(
                    "com.miui.gallery.ui.photoPage.bars.menuitem.BaseMenuItemDelegate");
            Method isSupport = baseDelegate.getDeclaredMethod("isSupport");
            filter.setAccessible(true);
            refreshItem.setAccessible(true);
            refreshState.setAccessible(true);
            isSupport.setAccessible(true);
            mXposed.hook(isSupport).setId("gallery_managed_original_support")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        Object delegate = chain.getThisObject();
                        if (delegate != null && DOWNLOAD_MENU.equals(delegate.getClass().getName())
                                && mOriginHookReady && mImageLoaderHookReady
                                && managedItem(currentItemFromMenu(delegate))) {
                            return true;
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
                                refreshState.invoke(chain.getThisObject(), true, true);
                                refreshItem.invoke(chain.getThisObject(), dataItem, true, true);
                                mMenuHookReady = true;
                                Logger.d("event=managed_original_menu_enabled");
                            } catch (Throwable t) {
                                Logger.w("event=managed_original_menu_enable_failed", t);
                            }
                        }
                        return result;
                    });
            mMenuHookReady = true;
            Logger.i("event=hook_registered method=BaseMenuItemDelegate.isSupport + DownloadOriginal.onFilterFinished(BaseDataItem,LinkedHashMap)");
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
            if (destination == null || destination.isEmpty()) return null;
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
            String path = stringCall(item, getter);
            if (path == null || path.isEmpty()) continue;
            OriginalStore.Record record = store.findByLocalPath(new File(path));
            if (record != null && target.equals(record.target)) {
                // Bind lazily too: a scanner may finish after the bounded background lookup.
                Object key = call(item, "getKey");
                String id = key == null ? null : String.valueOf(key);
                String sha1 = stringCall(item, "getSha1");
                if (store.find(config, id, sha1) == null) {
                    store.bindIdentity(new File(path), id, sha1);
                }
                return store.findByLocalPath(new File(path));
            }
        }
        Object key = call(item, "getKey");
        return store.find(config, key == null ? null : String.valueOf(key),
                stringCall(item, "getSha1"));
    }

    private Object currentItemFromMenu(Object delegate) {
        try {
            Object provider = field(delegate, "mDataProvider");
            Object data = call(provider, "getFieldData");
            Object current = field(data, "mCurrent");
            Object itemView = field(current, "itemView");
            return field(itemView, "mDataItem");
        } catch (Throwable t) {
            Logger.d("event=managed_original_support_lookup_failed detail=" + t);
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

    void shutdown() { mWorker.shutdownNow(); }

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
