package com.tongluosao.gallerysync.hook;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.tongluosao.gallerysync.core.Logger;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.BooleanSupplier;
import io.github.libxposed.api.XposedInterface;

/** Mirrors Gallery's real automatic-download events and policy without an independent timer. */
final class NativeDownloadHook implements BooleanSupplier {
    private static final String MANAGER = "com.miui.gallery.cloud.download.BatchDownloadManager";
    private final XposedInterface xposed;
    private final ClassLoader loader;
    private final Runnable request;
    private volatile Method instanceMethod;
    private volatile Method automaticMethod;
    private volatile Method conditionMethod;
    private volatile Method startMethod;
    private volatile Method foregroundMethod;
    private volatile Method pluggedMethod;
    private volatile Method preferenceMethod;
    private volatile Method typeMethod;
    private final Object lifecycleLock = new Object();
    private volatile Object processLifecycle;
    private volatile Object lifecycleObserver;
    private volatile Method removeLifecycleObserverMethod;
    private final ThreadLocal<Boolean> evaluating = new ThreadLocal<>();
    private volatile boolean ready;
    private volatile boolean stopped;

    NativeDownloadHook(XposedInterface xposed, ClassLoader loader, Runnable request) {
        this.xposed = xposed;
        this.loader = loader;
        this.request = request;
    }

    void install() {
        try {
            Class<?> manager = loader.loadClass(MANAGER);
            instanceMethod = manager.getDeclaredMethod("getInstance");
            automaticMethod = manager.getDeclaredMethod("canAutoDownload");
            conditionMethod = manager.getDeclaredMethod("checkCondition");
            startMethod = manager.getDeclaredMethod("startBatchDownload", Context.class,
                    boolean.class);
            foregroundMethod = loader.loadClass("com.miui.gallery.util.MiscUtil")
                    .getDeclaredMethod("isAppProcessInForeground");
            pluggedMethod = loader.loadClass(
                    "com.miui.gallery.preference.GalleryPreferences$Sync")
                    .getDeclaredMethod("getIsPlugged");
            Method changed = manager.getDeclaredMethod("onConditionChanged", Context.class);
            preferenceMethod = loader.loadClass(
                    "com.miui.gallery.preference.GalleryPreferences$Sync")
                    .getDeclaredMethod("isAutoDownload");
            typeMethod = loader.loadClass(
                    "com.miui.gallery.preference.GalleryPreferences$Sync")
                    .getDeclaredMethod("getDownloadType");
            Method changeType = loader.loadClass(
                    "com.miui.gallery.preference.GalleryPreferences$Sync")
                    .getDeclaredMethod("setDownloadType", loader.loadClass(
                            "com.miui.gallery.sdk.download.DownloadType"));
            Method preference = loader.loadClass(
                    "com.miui.gallery.preference.GalleryPreferences$Sync")
                    .getDeclaredMethod("setAutoDownload", boolean.class);
            for (Method method : new Method[]{instanceMethod, automaticMethod, conditionMethod,
                    startMethod, changed, preference, foregroundMethod, pluggedMethod, preferenceMethod, typeMethod, changeType}) method.setAccessible(true);
            observe(startMethod, "gallery_native_auto_download_start");
            observe(conditionMethod, "gallery_native_download_condition_check");
            observe(changed, "gallery_native_download_conditions");
            observe(changeType, "gallery_native_download_type");
            observe(preference, "gallery_native_auto_download_preference");
            ready = true;
            observeProcessForeground();
            Logger.i("event=hook_registered method=BatchDownloadManager.startBatchDownload/onConditionChanged+Sync.setAutoDownload");
        } catch (Throwable failure) {
            ready = false;
            Logger.e("event=native_download_hook_unavailable downloads_blocked=true", failure);
        }
    }

    private void observe(Method method, String id) {
        xposed.hook(method).setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (!stopped && !Boolean.TRUE.equals(evaluating.get())) {
                        try { request.run(); }
                        catch (Throwable failure) {
                            Logger.w("event=native_download_request_failed", failure);
                        }
                    }
                    return result;
                });
    }

    /** Recheck native policy whenever Gallery returns to the foreground. */
    private void observeProcessForeground() {
        Runnable register = this::registerProcessForegroundObserver;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            register.run();
        } else {
            new Handler(Looper.getMainLooper()).post(register);
        }
    }

    private void registerProcessForegroundObserver() {
        synchronized (lifecycleLock) {
            if (stopped || lifecycleObserver != null) return;
            try {
                Class<?> ownerClass = loader.loadClass(
                        "androidx.lifecycle.ProcessLifecycleOwner");
                Object owner = ownerClass.getMethod("get").invoke(null);
                Object lifecycle = ownerClass.getMethod("getLifecycle").invoke(owner);
                Class<?> lifecycleClass = loader.loadClass("androidx.lifecycle.Lifecycle");
                Class<?> observerMarker = loader.loadClass(
                        "androidx.lifecycle.LifecycleObserver");
                Class<?> eventObserver = loader.loadClass(
                        "androidx.lifecycle.LifecycleEventObserver");
                Method addObserver = lifecycleClass.getMethod("addObserver", observerMarker);
                Method removeObserver = lifecycleClass.getMethod("removeObserver", observerMarker);
                InvocationHandler callback = (proxy, method, args) -> {
                    if ("onStateChanged".equals(method.getName()) && args != null
                            && args.length == 2 && "ON_START".equals(String.valueOf(args[1]))
                            && !stopped) {
                        resume();
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }
                        if ("toString".equals(method.getName())) {
                            return "GallerySyncForegroundObserver";
                        }
                    }
                    return null;
                };
                Object observer = Proxy.newProxyInstance(eventObserver.getClassLoader(),
                        new Class<?>[]{eventObserver}, callback);
                processLifecycle = lifecycle;
                lifecycleObserver = observer;
                removeLifecycleObserverMethod = removeObserver;
                addObserver.invoke(lifecycle, observer);
                Logger.i("event=hook_registered source=ProcessLifecycleOwner.ON_START");
            } catch (Throwable failure) {
                processLifecycle = null;
                lifecycleObserver = null;
                removeLifecycleObserverMethod = null;
                Logger.w("event=native_download_foreground_observer_unavailable", failure);
            }
        }
    }

    @Override public boolean getAsBoolean() {
        if (!ready || stopped) return false;
        try {
            evaluating.set(true);
            if (!Boolean.TRUE.equals(preferenceMethod.invoke(null))) return false;
            String type = downloadType();
            if (!("THUMBNAIL".equals(type) || "ORIGIN".equals(type))) return false;
            // DispatchJob sets requireCharging = !foreground immediately before its native check.
            // Enforce the same rule even before its asynchronous job updates the options builder.
            if (!Boolean.TRUE.equals(foregroundMethod.invoke(null))
                    && !Boolean.TRUE.equals(pluggedMethod.invoke(null))) return false;
            Object manager = instanceMethod.invoke(null);
            return manager != null && Boolean.TRUE.equals(conditionMethod.invoke(manager));
        } catch (Throwable failure) {
            Logger.w("event=native_download_policy_unavailable downloads_blocked=true", failure);
            return false;
        } finally {
            evaluating.remove();
        }
    }

    private String downloadType() throws Exception {
        Object type = typeMethod.invoke(null);
        return type instanceof Enum ? ((Enum<?>) type).name() : String.valueOf(type);
    }

    boolean isHighQuality() {
        if (!ready || stopped) return false;
        try { return "THUMBNAIL".equals(downloadType()); }
        catch (Throwable failure) { return false; }
    }

    /** Re-enter the native start path after module replacement; it retains native scheduling rules. */
    void resume() {
        if (!ready || stopped) return;
        Context context = HostContext.get(loader);
        if (context == null) return;
        try {
            Logger.i("event=native_download_policy auto=" + preferenceMethod.invoke(null)
                    + " type=" + downloadType()
                    + " xiaomiEligible=" + automaticMethod.invoke(null)
                    + " foreground=" + foregroundMethod.invoke(null)
                    + " plugged=" + pluggedMethod.invoke(null)
                    + " allowed=" + getAsBoolean());
            startMethod.invoke(instanceMethod.invoke(null), context, false);
        } catch (Throwable failure) {
            Logger.w("event=native_download_resume_failed", failure);
        }
    }

    void shutdown() {
        stopped = true;
        Object lifecycle;
        Object observer;
        Method removeObserver;
        synchronized (lifecycleLock) {
            lifecycle = processLifecycle;
            observer = lifecycleObserver;
            removeObserver = removeLifecycleObserverMethod;
            processLifecycle = null;
            lifecycleObserver = null;
            removeLifecycleObserverMethod = null;
        }
        if (lifecycle == null || observer == null || removeObserver == null) return;
        Runnable remove = () -> {
            try { removeObserver.invoke(lifecycle, observer); }
            catch (Throwable failure) {
                Logger.w("event=native_download_foreground_observer_remove_failed", failure);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) remove.run();
        else new Handler(Looper.getMainLooper()).post(remove);
    }
}
