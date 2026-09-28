package com.tongluosao.gallerysync.hook;

import android.content.Context;

import com.tongluosao.gallerysync.core.Logger;

import java.lang.reflect.Method;

/**
 * 获取相册进程的 Context，用于模块自己的队列持久化目录。
 * 相册是单例 App，{@code GalleryApp.sGetAndroidContext()} 在同步发生时一定已可用。
 */
public final class HostContext {

    private static volatile Context sContext;

    private HostContext() {
    }

    public static Context get(ClassLoader classLoader) {
        Context cached = sContext;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> app = classLoader.loadClass("com.miui.gallery.GalleryApp");
            Method method = app.getDeclaredMethod("sGetAndroidContext");
            method.setAccessible(true);
            Object ctx = method.invoke(null);
            if (ctx instanceof Context) {
                sContext = (Context) ctx;
                Logger.i("event=host_context_ready dir="
                        + sContext.getFilesDir().getAbsolutePath());
                return sContext;
            }
        } catch (Throwable t) {
            Logger.w("event=host_context_failed", t);
        }
        return null;
    }
}
