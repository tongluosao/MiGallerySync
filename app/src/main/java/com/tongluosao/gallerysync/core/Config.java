package com.tongluosao.gallerysync.core;

import android.content.SharedPreferences;

/**
 * 模块配置。运行在相册进程时通过 RemotePreferences 读取模块 App 下发的配置；
 * 配置缺失或 service 不可用时全部回退到默认值，保证最坏情况下不影响相册原有行为。
 */
public final class Config {

    public static final String PREF_GROUP = "gallerysync";

    public static final String KEY_REPLACE_MODE = "replace_mode";
    public static final String KEY_BASE_URL = "base_url";
    public static final String KEY_USERNAME = "username";
    public static final String KEY_PASSWORD = "password";
    public static final String KEY_REMOTE_ROOT = "remote_root";
    public static final String KEY_WIFI_ONLY = "wifi_only";
    public static final String KEY_MAX_ITEMS_PER_SYNC = "max_items_per_sync";
    public static final String KEY_MAX_RETRY = "max_retry";
    public static final String KEY_VERBOSE_LOG = "verbose_log";
    public static final String KEY_UI_MASK = "ui_mask";
    /**
     * 远端目录的分法。见 {@link #LAYOUT_ALBUM} 等三个取值。
     * 老版本只有一个 {@code flat_path} 布尔，见构造器里的兼容读取。
     */
    public static final String KEY_REMOTE_LAYOUT = "remote_layout";
    /** 旧键：只表示"扁平"。留着只为把老配置读出来，新写入一律走 remote_layout */
    public static final String KEY_FLAT_PATH = "flat_path";
    /**
     * 并行上传的路数，1..{@link #MAX_UPLOAD_CONCURRENCY}。
     *
     * <p>以前是写死的 3 路。每路各持一条 keep-alive 连接，能开多大取决于服务器：
     * 直连自建 WebDAV 时多路明显更快，而 OpenList 挂网盘那一类并发再高收益递减，所以交给用户。
     */
    public static final String KEY_UPLOAD_CONCURRENCY = "upload_concurrency";
    /**
     * 100% 之后不等复核，立刻让出上传通道。
     *
     * <p>PUT 的请求体发完（界面走到 100%）之后还有 HEAD/PROPFIND 复核，那是为了识破
     * "回 2xx 却没落盘"的假写存储。这段等待原本占着 worker 槽位，整条队列跟着一起等；
     * 打开这个开关后复核挪到独立线程，槽位立刻空出来去传下一项。
     */
    public static final String KEY_VERIFY_ASYNC = "verify_async";

    /** 替换：拦截原链路，只上传 WebDAV */
    public static final String MODE_REPLACE = "replace";
    /** 双写：原链路保留，额外镜像一份到 WebDAV */
    public static final String MODE_MIRROR = "mirror";
    /** 关闭：完全不介入 */
    public static final String MODE_OFF = "off";

    /** 按相册分：<root>/<相册目录>/<文件名>，例如 <root>/DCIM/Camera/IMG.jpg */
    public static final String LAYOUT_ALBUM = "album";
    /** 按时间分：<root>/<yyyy>/<MM>/<文件名> */
    public static final String LAYOUT_DATE = "date";
    /** 全平铺：<root>/<文件名>，供建不出子目录的服务器使用 */
    public static final String LAYOUT_FLAT = "flat";

    public static final String DEFAULT_REPLACE_MODE = MODE_REPLACE;
    public static final String DEFAULT_REMOTE_ROOT = "MiGallerySync";
    /**
     * 默认按相册分。
     *
     * <p>相册界面上本来就是按文件夹分组的，备份出去只留年/月的话，用户在 WebDAV 上
     * 就再也分不清哪张照片来自哪个相册 —— 相册文件夹这一层信息是有价值的，不该丢。
     */
    public static final String DEFAULT_REMOTE_LAYOUT = LAYOUT_ALBUM;
    /**
     * 每轮入队上限。相册只在它自己的排期上枚举脏项（空闲时一两小时才一轮），
     * 这个值决定"一轮能喂给模块多少项"，所以对全量首备的耗时影响很大：
     * 200 时一万两千张要几十轮，1000 时几轮就喂完了。队列会持久化且按内容去重，
     * 调大只是让单次入队多一点，不会重复上传。
     */
    public static final int DEFAULT_MAX_ITEMS_PER_SYNC = 1000;
    public static final int DEFAULT_MAX_RETRY = 5;
    /** 默认与老版本写死的路数一致：升级后行为不变，想要更快在设置页自己调 */
    public static final int DEFAULT_UPLOAD_CONCURRENCY = 3;
    /** 上限压住连接数：再高对自建服务器与网盘网关都是压测，收益已经很小 */
    public static final int MAX_UPLOAD_CONCURRENCY = 8;
    /**
     * 默认开启。用户抱怨的就是"到 100% 还要等"，默认就该是修好的状态；
     * 复核本身没有取消，只是挪到了后台，假写存储照样会被抓住并重传。
     */
    public static final boolean DEFAULT_VERIFY_ASYNC = true;

    private final String replaceMode;
    private final String baseUrl;
    private final String username;
    private final String password;
    private final String remoteRoot;
    private final boolean wifiOnly;
    private final int maxItemsPerSync;
    private final int maxRetry;
    private final boolean uiMask;
    private final boolean verboseLog;
    private final String remoteLayout;
    private final int uploadConcurrency;
    private final boolean verifyAsync;

    private Config(SharedPreferences prefs) {
        if (prefs == null) {
            replaceMode = DEFAULT_REPLACE_MODE;
            baseUrl = "";
            username = "";
            password = "";
            remoteRoot = DEFAULT_REMOTE_ROOT;
            wifiOnly = true;
            maxItemsPerSync = DEFAULT_MAX_ITEMS_PER_SYNC;
            maxRetry = DEFAULT_MAX_RETRY;
            uiMask = false;
            verboseLog = true;
            remoteLayout = DEFAULT_REMOTE_LAYOUT;
            uploadConcurrency = DEFAULT_UPLOAD_CONCURRENCY;
            verifyAsync = DEFAULT_VERIFY_ASYNC;
        } else {
            replaceMode = prefs.getString(KEY_REPLACE_MODE, DEFAULT_REPLACE_MODE);
            baseUrl = prefs.getString(KEY_BASE_URL, "");
            username = prefs.getString(KEY_USERNAME, "");
            password = prefs.getString(KEY_PASSWORD, "");
            remoteRoot = prefs.getString(KEY_REMOTE_ROOT, DEFAULT_REMOTE_ROOT);
            wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, true);
            maxItemsPerSync = prefs.getInt(KEY_MAX_ITEMS_PER_SYNC, DEFAULT_MAX_ITEMS_PER_SYNC);
            maxRetry = prefs.getInt(KEY_MAX_RETRY, DEFAULT_MAX_RETRY);
            uiMask = prefs.getBoolean(KEY_UI_MASK, false);
            verboseLog = prefs.getBoolean(KEY_VERBOSE_LOG, true);
            remoteLayout = readLayout(prefs);
            uploadConcurrency = clampConcurrency(
                    prefs.getInt(KEY_UPLOAD_CONCURRENCY, DEFAULT_UPLOAD_CONCURRENCY));
            verifyAsync = prefs.getBoolean(KEY_VERIFY_ASYNC, DEFAULT_VERIFY_ASYNC);
        }
    }

    /**
     * 夹进 1..{@link #MAX_UPLOAD_CONCURRENCY}。
     *
     * <p>共享偏好是别的进程写进来的，值可能来自旧版本、手改的 XML 或写坏的配置；
     * 0 会让上传一路都不启动（等于"备份停了"），过大则把服务器打满，两头都必须在读进来时兜住。
     */
    public static int clampConcurrency(int value) {
        if (value < 1) {
            return 1;
        }
        return Math.min(value, MAX_UPLOAD_CONCURRENCY);
    }

    /**
     * 读分目录方式。老配置里没有 {@code remote_layout}，只有一个 {@code flat_path} 布尔：
     * 它当初是 true 的，说明用户就是想要全平铺，别在升级后悄悄给人家改成按相册分。
     */
    private static String readLayout(SharedPreferences prefs) {
        String layout = prefs.getString(KEY_REMOTE_LAYOUT, null);
        if (layout != null && !layout.trim().isEmpty()) {
            return layout;
        }
        if (prefs.contains(KEY_FLAT_PATH) && prefs.getBoolean(KEY_FLAT_PATH, false)) {
            return LAYOUT_FLAT;
        }
        return DEFAULT_REMOTE_LAYOUT;
    }

    public static Config from(SharedPreferences prefs) {
        return new Config(prefs);
    }

    public boolean isOff() {
        return MODE_OFF.equals(replaceMode);
    }

    public boolean isReplace() {
        return MODE_REPLACE.equals(replaceMode);
    }

    /**
     * WebDAV 的地址填了没有。
     *
     * <p>注意它**不再**决定"要不要拦原链路"：替换模式下一律拦住，地址空着也是拦住
     * （用户明确要求不可用时也不回退小米云，见 {@code GalleryHooks.handleInvokeList}）。
     * 这里的判据只用来决定队列的 worker 要不要开工。
     */
    public boolean isReady() {
        return !isOff() && baseUrl != null && !baseUrl.trim().isEmpty();
    }

    public String getReplaceMode() {
        return replaceMode;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getRemoteRoot() {
        return remoteRoot;
    }

    public boolean isWifiOnly() {
        return wifiOnly;
    }

    public int getMaxItemsPerSync() {
        return maxItemsPerSync;
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    public boolean isUiMask() {
        return uiMask;
    }

    public boolean isVerboseLog() {
        return verboseLog;
    }

    public String getRemoteLayout() {
        return remoteLayout;
    }

    /** 全平铺：所有文件直接落在 remote_root 下，不再建任何子目录 */
    public boolean isFlatPath() {
        return LAYOUT_FLAT.equals(remoteLayout);
    }

    /** 并行上传路数，已保证在 1..{@link #MAX_UPLOAD_CONCURRENCY} 内 */
    public int getUploadConcurrency() {
        return uploadConcurrency;
    }

    /** true 表示 PUT 发完就走，落盘复核交给后台线程 */
    public boolean isVerifyAsync() {
        return verifyAsync;
    }

    @Override
    public String toString() {
        return "Config{mode=" + replaceMode
                + ", host=" + safeHost(baseUrl)
                + ", root=" + remoteRoot
                + ", layout=" + remoteLayout
                + ", wifiOnly=" + wifiOnly
                + ", maxItems=" + maxItemsPerSync
                + ", workers=" + uploadConcurrency
                + ", verifyAsync=" + verifyAsync + "}";
    }

    /** 只保留 scheme + host + port，避免凭据与路径出现在日志里 */
    public static String safeHost(String url) {
        if (url == null || url.isEmpty()) {
            return "<empty>";
        }
        try {
            int schemeEnd = url.indexOf("://");
            int start = schemeEnd >= 0 ? schemeEnd + 3 : 0;
            int end = url.length();
            for (int i = start; i < url.length(); i++) {
                char c = url.charAt(i);
                if (c == '/' || c == '?' || c == '#') {
                    end = i;
                    break;
                }
            }
            String host = url.substring(start, end);
            int at = host.indexOf('@');
            if (at >= 0) {
                host = host.substring(at + 1);
            }
            return host;
        } catch (Throwable t) {
            return "<masked>";
        }
    }
}
