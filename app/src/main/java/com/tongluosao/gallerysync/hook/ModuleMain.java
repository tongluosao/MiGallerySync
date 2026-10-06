package com.tongluosao.gallerysync.hook;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.tongluosao.gallerysync.core.Config;
import com.tongluosao.gallerysync.core.Logger;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 模块入口。只做三件事：记录加载环境、按包名与进程路由、安装 Hook。
 * 业务全部下沉到 {@link GalleryHooks}，入口不堆积反射与网络逻辑。
 *
 * <p>热重载：框架不会为新 generation 重放 {@code onModuleLoaded()} / {@code onPackageReady()}，
 * 所以 {@link #onHotReloaded} 要自己找回宿主 ClassLoader 并重新装 Hook；旧 generation 则在
 * {@link #onHotReloading} 里把自有资源（上传线程、配置 listener）交出去。
 */
public final class ModuleMain extends XposedModule {

    private static final String TARGET_PACKAGE = "com.miui.gallery";
    /** 相册同步服务未声明 android:process，同步发生在主进程 */
    private static final String TARGET_PROCESS = "com.miui.gallery";

    private volatile String mProcessName = "<unknown>";
    private volatile boolean mInstalled;
    private volatile GalleryHooks mHooks;
    private volatile UiMaskHook mUiMaskHook;
    private volatile SharedPreferences mPrefs;
    private volatile SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener;

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        mProcessName = param.getProcessName();
        Logger.attach(this);
        Logger.i("event=module_loaded process=" + mProcessName
                + " api=" + getApiVersion()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion());
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        if (!TARGET_PROCESS.equals(mProcessName)) {
            Logger.d("event=install_skipped process=" + mProcessName
                    + " reason=non_main_process");
            return;
        }
        install(param.getClassLoader());
        // 这里绝不能 detach()：框架会把"已交还入口"的模块直接跳过热重载
        // （日志原文 "The old module has already detached its entry, skipping"），
        // 于是模块更新后只能杀掉目标进程才生效。多余的重入由进程名判断挡住。
    }

    @Override
    public boolean onHotReloading(@NonNull XposedModuleInterface.HotReloadingParam param) {
        Logger.i("event=hot_reloading process=" + mProcessName);
        release();
        return true;
    }

    @Override
    public void onHotReloaded(@NonNull XposedModuleInterface.HotReloadedParam param) {
        Logger.attach(this);
        // 默认实现会 unhook 旧 Hook，一旦覆写就得自己做；漏掉的话旧代码的拦截器还会继续跑
        List<XposedInterface.HookHandle> handles = param.getOldHookHandles();
        for (XposedInterface.HookHandle handle : handles) {
            try {
                handle.unhook();
            } catch (Throwable t) {
                Logger.w("event=hot_reload_unhook_failed", t);
            }
        }
        Logger.i("event=hot_reloaded unhooked=" + handles.size()
                + " process=" + param.getProcessName());
        if (!TARGET_PROCESS.equals(param.getProcessName())) {
            return;
        }
        ClassLoader classLoader = hostClassLoader();
        if (classLoader == null) {
            // 拿不到宿主 ClassLoader 就只能等下次进程启动：不装 Hook 等于放行原链路，
            // 比拿一个错误的 loader 去 loadClass 安全
            Logger.w("event=hot_reload_skipped reason=classloader_unavailable");
            return;
        }
        // 若框架复用了同一个实例，mInstalled 会挡住重装，这里必须先放开
        mInstalled = false;
        install(classLoader);
        // 装完还得把队列自己拉起来：宿主 onCreate 不会被重放，光等下一轮同步要 1~3 小时
        GalleryHooks hooks = mHooks;
        if (hooks != null) {
            hooks.resumeAfterHotReload();
        }
    }

    private synchronized void install(ClassLoader classLoader) {
        if (mInstalled) {
            Logger.i("event=install_skipped reason=already_installed");
            return;
        }
        mInstalled = true;

        GalleryHooks hooks = GalleryHooks.install(this, classLoader);
        UiMaskHook uiMaskHook = UiMaskHook.install(this, classLoader);
        mHooks = hooks;
        mUiMaskHook = uiMaskHook;
        Config config = Config.from(readRemotePreferences());
        applyConfig(config);
        Logger.i("event=install_done " + config);
    }

    /** 交出模块自有的资源：listener 与上传线程都是跨 generation 会打架的东西 */
    private void release() {
        SharedPreferences prefs = mPrefs;
        SharedPreferences.OnSharedPreferenceChangeListener listener = mPrefsListener;
        if (prefs != null && listener != null) {
            try {
                prefs.unregisterOnSharedPreferenceChangeListener(listener);
            } catch (Throwable t) {
                Logger.w("event=hot_reload_listener_release_failed", t);
            }
        }
        mPrefsListener = null;
        mPrefs = null;
        GalleryHooks hooks = mHooks;
        if (hooks != null) {
            hooks.shutdown();
        }
    }

    private void applyConfig(Config config) {
        Logger.setVerbose(config.isVerboseLog());
        GalleryHooks hooks = mHooks;
        if (hooks != null) {
            hooks.setConfig(config);
        }
        UiMaskHook uiMaskHook = mUiMaskHook;
        if (uiMaskHook != null) {
            uiMaskHook.setConfig(config);
        }
    }

    /**
     * 热重载时找回宿主的 ClassLoader。{@code HotReloadedParam} 只继承 ModuleLoadedParam，
     * 拿不到包名与 loader，而 Application 用的就是 onPackageReady 给出的那个 PathClassLoader。
     */
    private static ClassLoader hostClassLoader() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getMethod("currentApplication");
            Object app = currentApplication.invoke(null);
            return app instanceof Application ? ((Context) app).getClassLoader() : null;
        } catch (Throwable t) {
            Logger.w("event=hot_reload_classloader_failed", t);
            return null;
        }
    }

    private SharedPreferences readRemotePreferences() {
        try {
            SharedPreferences prefs = getRemotePreferences(Config.PREF_GROUP);
            // listener 必须持有强引用，否则会被 GC 导致配置变更收不到
            mPrefsListener = (sharedPreferences, key) -> {
                Logger.i("event=config_changed key=" + key);
                applyConfig(Config.from(sharedPreferences));
            };
            prefs.registerOnSharedPreferenceChangeListener(mPrefsListener);
            mPrefs = prefs;
            return prefs;
        } catch (Throwable t) {
            Logger.w("event=remote_prefs_unavailable reason=" + t);
            return null;
        }
    }
}
