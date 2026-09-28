package com.tongluosao.gallerysync.core;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * 统一日志出口：同时写入 logcat 与 LSPosed 框架日志，便于在没有 root shell 时也能排查。
 * 日志只输出主机与本地文件路径，不输出凭据、账号与云端响应内容。
 */
public final class Logger {

    public static final String TAG = "MiGallerySync";

    private static volatile XposedInterface sXposed;
    private static volatile boolean sVerbose = true;

    private Logger() {
    }

    public static void attach(XposedInterface xposed) {
        sXposed = xposed;
    }

    public static void setVerbose(boolean verbose) {
        sVerbose = verbose;
    }

    public static boolean isVerbose() {
        return sVerbose;
    }

    public static void d(String msg) {
        if (sVerbose) {
            log(Log.DEBUG, msg, null);
        }
    }

    public static void i(String msg) {
        log(Log.INFO, msg, null);
    }

    public static void w(String msg) {
        log(Log.WARN, msg, null);
    }

    public static void w(String msg, Throwable tr) {
        log(Log.WARN, msg, tr);
    }

    public static void e(String msg) {
        log(Log.ERROR, msg, null);
    }

    public static void e(String msg, Throwable tr) {
        log(Log.ERROR, msg, tr);
    }

    private static void log(int priority, String msg, Throwable tr) {
        if (tr != null) {
            Log.println(priority, TAG, msg + " | " + tr);
        } else {
            Log.println(priority, TAG, msg);
        }
        XposedInterface xposed = sXposed;
        if (xposed != null) {
            try {
                if (tr != null) {
                    xposed.log(priority, TAG, msg, tr);
                } else {
                    xposed.log(priority, TAG, msg);
                }
            } catch (Throwable ignored) {
                // 框架日志不可用时退回 logcat，不影响宿主
            }
        }
    }
}
