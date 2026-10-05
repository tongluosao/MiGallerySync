package com.tongluosao.gallerysync;

import android.app.Application;

import androidx.annotation.NonNull;

import com.tongluosao.gallerysync.core.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块 App 侧入口：只负责绑定框架 service，供配置界面写入 RemotePreferences。
 * service 未绑定时界面应给出明确提示，而不是假装配置已生效。
 */
public final class App extends Application implements XposedServiceHelper.OnServiceListener {

    private static volatile XposedService sService;
    /** 进程内 Application 实例。广播必须从一个真实 Context 发出，界面侧拿不到别的 Context */
    private static volatile App sInstance;
    private static final List<Consumer<XposedService>> sListeners = new CopyOnWriteArrayList<>();

    /** 本进程的 Application；界面发命令广播时要用它当 Context（也用来读包名） */
    public static App application() {
        return sInstance;
    }

    public static XposedService getService() {
        return sService;
    }

    /**
     * 订阅框架绑定状态，回调参数为 null 表示 service 已死亡。
     * 回调发生在框架广播线程而非主线程，订阅方需自行切线程。
     */
    public static void addServiceListener(Consumer<XposedService> listener) {
        sListeners.add(listener);
        XposedService service = sService;
        // 注册时若已绑定就补一次回调，否则后开的界面会永远停在"未连接"
        if (service != null) {
            dispatch(listener, service);
        }
    }

    public static void removeServiceListener(Consumer<XposedService> listener) {
        sListeners.remove(listener);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        Logger.attach(null);
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(@NonNull XposedService service) {
        sService = service;
        Logger.i("event=service_bound framework=" + service.getFrameworkName()
                + " version=" + service.getFrameworkVersion()
                + " api=" + service.getApiVersion());
        dispatch(service);
    }

    @Override
    public void onServiceDied(@NonNull XposedService service) {
        if (sService == service) {
            sService = null;
        }
        Logger.w("event=service_died");
        // 可能还绑着另一个框架，按剩余的那个回答
        dispatch(sService);
    }

    private void dispatch(XposedService service) {
        for (Consumer<XposedService> listener : sListeners) {
            dispatch(listener, service);
        }
    }

    private static void dispatch(Consumer<XposedService> listener, XposedService service) {
        try {
            listener.accept(service);
        } catch (Throwable t) {
            Logger.w("event=service_listener_failed", t);
        }
    }
}
