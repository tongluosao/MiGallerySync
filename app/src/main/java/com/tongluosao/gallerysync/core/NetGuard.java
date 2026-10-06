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
            if (isWifiLike(caps)) {
                return true;
            }
            // **VPN 要按它的底层网络判定**：设备上装了纯组网 VPN（EasyTier，接口 tun2）时，
            // getActiveNetwork() 返回的就是那张 VPN 网络（它按 UID 接管，相册 UID 在里面），
            // 而它自己的 transport 只有 VPN —— 按 transport 直接判会得出"不是 Wi-Fi"，
            // 于是"仅 Wi-Fi 上传"变成"永不上传"。VPN 只是个隧道，底下的载体才是答案。
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return underlyingIsWifi(cm, network, caps);
            }
            return false;
        } catch (Throwable t) {
            Logger.d("event=net_guard_failed fallback=allow");
            return true;
        }
    }

    private static boolean isWifiLike(NetworkCapabilities caps) {
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
    }

    /**
     * VPN 之下是不是 Wi-Fi/以太网。
     *
     * <p>顺序是"能问到就不猜"：
     * <ol>
     *   <li>问 VPN 网络自己的能力 `NetworkCapabilities.getUnderlyingNetworks()`（本机可用，
     *       dex 里是 `PUBLIC` + `SDK`，返回 `List&lt;Network&gt;`）；</li>
     *   <li>退回 `Network.getUnderlyingNetworks()`（老平台才有，返回 `Network[]`）；</li>
     *   <li>两条都问不到才用近似判断 —— "系统里存在一个可用的 Wi-Fi/以太网网络"。</li>
     * </ol>
     * 前两条是精确答案，第三条宁可放行：因为 ROM 差异让"仅 Wi-Fi 上传"变成"永不上传"
     * 比偶尔多传一次糟得多。
     *
     * <p><b>别写 `ConnectivityManager.getUnderlyingNetworksForVpn`</b> —— 本机（Android 15
     * 那套 connectivity APEX）根本没有这个方法，全量搜过
     * `/apex/com.android.tethering/javalib/framework-connectivity.jar` 的 classes.dex：
     * `getUnderlyingNetworksForVpn` 0 次命中，`getUnderlyingNetworks` 只有
     * `NetworkCapabilities` 上那一个。写了就是一个永远抛 NoSuchMethodException 的死分支。
     */
    private static boolean underlyingIsWifi(ConnectivityManager cm, Network vpn,
            NetworkCapabilities vpnCaps) {
        Network[] underlying = underlyingOf(cm, vpn, vpnCaps);
        if (underlying != null && underlying.length > 0) {
            for (Network candidate : underlying) {
                NetworkCapabilities under = cm.getNetworkCapabilities(candidate);
                if (under != null && isWifiLike(under)) {
                    return true;
                }
            }
            // 明确给了底层网络、里面却没有 Wi-Fi：那就是移动网络，如实拒绝
            return false;
        }
        try {
            for (Network candidate : cm.getAllNetworks()) {
                NetworkCapabilities under = cm.getNetworkCapabilities(candidate);
                if (under != null && isWifiLike(under)
                        && !under.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        && under.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Logger.d("event=net_guard_underlying_failed detail=" + t);
        }
        return false;
    }

    /** 问 VPN 的底层网络；问不到返回 null（调用方退回近似判断） */
    private static Network[] underlyingOf(ConnectivityManager cm, Network vpn,
            NetworkCapabilities vpnCaps) {
        try {
            java.lang.reflect.Method m = NetworkCapabilities.class
                    .getMethod("getUnderlyingNetworks");
            Object result = m.invoke(vpnCaps);
            if (result instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) result;
                Network[] arr = new Network[list.size()];
                int n = 0;
                for (Object item : list) {
                    // 列表里允许有 null（表示该 netId 那一族没有底层网络），跳掉
                    if (item instanceof Network) {
                        arr[n++] = (Network) item;
                    }
                }
                if (n > 0) {
                    return java.util.Arrays.copyOf(arr, n);
                }
            }
        } catch (Throwable t) {
            // 没有这个方法（API < 35）就试下一条
        }
        try {
            java.lang.reflect.Method m = Network.class.getMethod("getUnderlyingNetworks");
            Object result = m.invoke(vpn);
            if (result instanceof Network[]) {
                return (Network[]) result;
            }
        } catch (Throwable t) {
            // 同样没有就返回 null，让调用方退回近似判断
        }
        return null;
    }
}
