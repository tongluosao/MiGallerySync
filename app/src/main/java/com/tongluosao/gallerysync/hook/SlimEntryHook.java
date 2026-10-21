package com.tongluosao.gallerysync.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.tongluosao.gallerysync.core.Config;
import com.tongluosao.gallerysync.core.Logger;

import java.lang.reflect.Method;
import java.util.function.Supplier;

import io.github.libxposed.api.XposedInterface;

/**
 * 把相册自己的"空间优化"页面换成模块自己的页面。
 *
 * <p>为什么要换：相册的优化是**同步串行**的 —— 它在自己的线程里逐张调用
 * {@code SlimController.handleSlim}，等我们返回才继续下一张，而它自己那部分（刷新列表、
 * 更新 MediaStore）每张要 3–5 秒。进度条还按它的估算跑，跟实际完全脱节。我们自己在
 * 后台并发跑队列、自己画进度，既准又能把吞吐拉起来（见 {@code SlimWorker}）。
 *
 * <p>拦截点：{@code com.miui.gallery.activity.PhotoSlimActivity#onCreate}。这是用户
 * 从清理页点"立即优化"后真正打开的那个页面（布局在 {@code ui/PhotoSlimFragment}）。
 * 我们在它 onPreCreate 之后开自己的 Activity，再把宿主页面 finish 掉 —— 注意必须先让
 * 宿主的 onCreate 跑完再 finish，直接不调用 {@code proceed()} 会留下一个没初始化的
 * Activity，系统会当场崩。
 *
 * <p>逃生口：模块自己的页面里有一个"用相册自带的优化"入口，它带的 Intent extra
 * {@link #EXTRA_NATIVE_SLIM} 会让这里直接放行，回到原生流程。
 */
final class SlimEntryHook {

    /** 用户从模块页面主动要求走原生流程时带的 extra。 */
    static final String EXTRA_NATIVE_SLIM = "mgs_native_slim";

    private static final String GALLERY_ACTIVITY = "com.miui.gallery.activity.PhotoSlimActivity";
    private static final String MODULE_PACKAGE = "com.tongluosao.gallerysync";
    private static final String MODULE_ACTIVITY = "com.tongluosao.gallerysync.ui.SlimActivity";

    private final XposedInterface mXposed;
    private final ClassLoader mClassLoader;
    private final Supplier<Config> mConfig;

    SlimEntryHook(XposedInterface xposed, ClassLoader classLoader, Supplier<Config> config) {
        mXposed = xposed;
        mClassLoader = classLoader;
        mConfig = config;
    }

    void install() {
        try {
            Class<?> host = Class.forName(GALLERY_ACTIVITY, false, mClassLoader);
            Method onCreate = host.getDeclaredMethod("onCreate", Bundle.class);
            mXposed.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    takeover(chain.getThisObject());
                } catch (Throwable t) {
                    // 拦不住也必须让相册页面照常工作，绝不能让异常冒到宿主的生命周期里
                    Logger.w("event=slim_takeover_failed detail=" + t, t);
                }
                return result;
            });
            Logger.i("event=slim_entry_hook_installed");
        } catch (Throwable t) {
            Logger.w("event=slim_entry_hook_missing", t);
        }
    }

    private void takeover(Object activity) {
        if (!(activity instanceof Activity)) {
            return;
        }
        Activity host = (Activity) activity;
        if (host.isFinishing()) {
            return;
        }
        Config config = mConfig == null ? null : mConfig.get();
        if (config == null || !config.isReady()) {
            Logger.d("event=slim_takeover_skipped reason=config_not_ready");
            return;
        }
        Intent intent = host.getIntent();
        if (intent != null && intent.getBooleanExtra(EXTRA_NATIVE_SLIM, false)) {
            Logger.i("event=slim_takeover_skipped reason=user_requested_native");
            return;
        }
        Context context = host.getApplicationContext();
        if (context == null) {
            return;
        }
        Intent own = new Intent();
        own.setClassName(MODULE_PACKAGE, MODULE_ACTIVITY);
        own.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(own);
        } catch (Throwable t) {
            // 模块 App 被卸载或禁用时这里会抛，那就老老实实留在原生页面
            Logger.w("event=slim_takeover_launch_failed detail=" + t, t);
            return;
        }
        host.finish();
        Logger.i("event=slim_takeover_launched");
    }
}
