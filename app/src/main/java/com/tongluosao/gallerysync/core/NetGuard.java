package com.tongluosao.gallerysync.core;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * 上传前的网络条件判定。失败时一律放行（保守策略）：
 * 宁可在移动网络下多传一次，也不要因为权限或 ROM 差异永远不上传。
 */
public final class NetGuard {

    private NetGuard() {
    }

    public static boolean canUpload(Context context, boolean wifiOnly) {
        if (!wifiOnly) {
            return true;
        }
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return true;
            }
            Network network = cm.getActiveNetwork();
            if (network == null) {
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) {
                return false;
            }
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        } catch (Throwable t) {
            Logger.d("event=net_guard_failed fallback=allow");
            return true;
        }
    }
}
