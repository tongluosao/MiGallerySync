package com.tongluosao.gallerysync.core;

/**
 * 302 直链的取舍规则：**所有** GET（整份下载、旁车读、字节抽样）都走同一套判据，
 * 不再按"大文件才配用直链"分流。
 *
 * <p>为什么以前只有视频快：直链缓存是"某次传输撞上 302 才顺手记下来"的副产物，
 * 而一个对象**只会被整份 GET 一次**，那次必然是两跳（主域握手 + 问 302，再握手去存储）。
 * 视频不同：播放时同一对象要发几十个 Range 请求，第一个请求记下的直链被后面几十个复用，
 * 于是只有它看起来"走了快路径"。真机带宽差（直链 4.4MB/s vs 整份 0.6–0.75MB/s）
 * 说明这是**服务端对待两种请求的方式不同**：带 Range 的问跳转、整份的自己代理。
 * 所以小对象要走直链，就得像视频预热那样**主动用一次 1 字节 Range 去换直链**，
 * 再用这条直链取体。
 *
 * <p>纯函数、无 Android 依赖：时间和"有没有缓存"都由调用方传进来，便于钉死每一条边界。
 */
public final class DirectLinkPolicy {

    /** 1KB：再小的对象重取一次也就一次握手，直链缓存不值得活太久。 */
    public static final long TINY_OBJECT_BYTES = 1024L;
    /** 1MB：截图/缩略图的典型量级。 */
    public static final long SMALL_OBJECT_BYTES = 1024L * 1024L;
    /** 10MB：普通照片与短视频的分界。 */
    public static final long MEDIUM_OBJECT_BYTES = 10L * 1024L * 1024L;
    /** 500MB：整份下载已经贵到"重来一次"必须避免的量级。 */
    public static final long LARGE_OBJECT_BYTES = 500L * 1024L * 1024L;

    /** 小对象直链缓存存活 60 秒：够覆盖"下载 → 复核 → 预览"这条流水线，又不留过期签名。 */
    public static final long TINY_TTL_MS = 60_000L;
    /** 截图/缩略图：2 分钟。 */
    public static final long SMALL_TTL_MS = 120_000L;
    /** 默认 3 分钟，与改造前 {@code DIRECT_URL_TTL_MS} 一致（真机上验证过的值）。 */
    public static final long DEFAULT_TTL_MS = 3L * 60_000L;
    /** 10MB–500MB：5 分钟，撑过失败退避后的一次重试。 */
    public static final long LARGE_TTL_MS = 5L * 60_000L;
    /** >500MB：10 分钟，重下整份的代价远大于一条可能过期的签名。 */
    public static final long HUGE_TTL_MS = 10L * 60_000L;

    /** 目录级判定的存活时间：服务器对同一目录的处置不会在几分钟内变。 */
    public static final long DIRECTORY_HINT_TTL_MS = 10L * 60_000L;

    /**
     * 目录的直链行为（按目录学习，同一目录下多个对象同源，摊薄发现成本）。
     */
    public enum DirectoryMode {
        /** 还没在这个目录里传过东西。 */
        UNKNOWN,
        /** 整份 GET 就被 302 了：直链本来就在用，不必再探。 */
        REDIRECTS,
        /** 整份 GET 由主域自己代理（慢）：先用 1 字节 Range 探一条直链再取体。 */
        INLINE,
        /** 探过了、Range 也不给 302：这个目录没有直链可用，别再白探。 */
        NO_DIRECT
    }

    /** 一条还在缓存里的直链（{@code url == null} 表示没有）。 */
    public static final class CachedEntry {
        public final String url;
        public final long expiresAtMs;

        public CachedEntry(String url, long expiresAtMs) {
            this.url = url;
            this.expiresAtMs = expiresAtMs;
        }

        public boolean live(long nowMs) {
            return url != null && !url.isEmpty() && nowMs < expiresAtMs;
        }
    }

    private DirectLinkPolicy() {
    }

    /**
     * 这一次 GET 该不该直接用缓存里的直链。
     *
     * <p><b>不按大小分流</b>：1KB 和 600MB 只要缓存活着就走直链 —— 差别只在缓存活多久
     * （{@link #ttlFor(long)}），不在"配不配"。
     *
     * <p>唯一的例外是 0 字节对象：它没有体可省，而"直链回了个空响应"正是下面要当成
     * 直链失效的信号，拿空对象去试只会把一条好缓存误判成坏缓存。
     *
     * @param size     对象大小；{@code < 0} 表示未知（未知也照走直链）
     * @param cached   缓存条目，可以为 null
     * @param nowMs    当前时刻
     */
    public static boolean shouldTryDirect(long size, CachedEntry cached, long nowMs) {
        if (size == 0L) {
            return false;
        }
        return cached != null && cached.live(nowMs);
    }

    /** 同 {@link #shouldTryDirect(long, CachedEntry, long)}，用系统时钟。 */
    public static boolean shouldTryDirect(long size, CachedEntry cached) {
        return shouldTryDirect(size, cached, System.currentTimeMillis());
    }

    /**
     * 这条对象的直链缓存该活多久。
     *
     * <p>小对象活得短：重新发现只要一次握手，宁可重新换一条也不要把过期签名留在热路径上；
     * 大对象活得长：重下整份的代价高，缓存必须撑过失败退避后的那次重试。
     *
     * @return 毫秒；{@code 0} 表示这条对象不缓存直链
     */
    public static long ttlFor(long size) {
        if (size == 0L) {
            return 0L;
        }
        if (size < 0L) {
            return DEFAULT_TTL_MS;
        }
        if (size <= TINY_OBJECT_BYTES) {
            return TINY_TTL_MS;
        }
        if (size <= SMALL_OBJECT_BYTES) {
            return SMALL_TTL_MS;
        }
        if (size <= MEDIUM_OBJECT_BYTES) {
            return DEFAULT_TTL_MS;
        }
        if (size <= LARGE_OBJECT_BYTES) {
            return LARGE_TTL_MS;
        }
        return HUGE_TTL_MS;
    }

    /**
     * 这个目录、这条对象，要不要先花一次 Range 请求去换直链。
     *
     * <p>只在"已知这个目录的整份 GET 被主域代理"时探：对已经 302 的目录探一次是纯浪费，
     * 对探不出直链的目录（{@link DirectoryMode#NO_DIRECT}）也不再重复白探。
     */
    public static boolean shouldProbe(long size, DirectoryMode mode) {
        // 空对象没有体可省，探到的直链也用不上
        return size != 0L && mode == DirectoryMode.INLINE;
    }

    /** 整份 GET 之后这个目录算什么：302 了就是 {@link DirectoryMode#REDIRECTS}，否则是被代理。 */
    public static DirectoryMode modeAfterTransfer(boolean sawRedirect) {
        return sawRedirect ? DirectoryMode.REDIRECTS : DirectoryMode.INLINE;
    }

    /**
     * 探完一次之后这个目录算什么。
     *
     * <p>探到 302 仍然留在 {@link DirectoryMode#INLINE}：这只证明"带 Range 的请求给直链"，
     * 不证明整份 GET 也会被跳转 —— 恰恰相反，整份 GET 被代理才是需要探测的原因。
     * 所以下一个对象还得接着探。
     */
    public static DirectoryMode modeAfterProbe(boolean gotRedirect) {
        return gotRedirect ? DirectoryMode.INLINE : DirectoryMode.NO_DIRECT;
    }

    /**
     * 直链这条路为什么没走成（进 {@code event=direct_link_miss reason=}）。
     *
     * <p>顺序要紧：异常最先（它就是这次失败的直接原因），然后是"有响应但没有体"
     * （后端对直连路径回了成功码却不给字节），再是"根本没有 302"（服务器自己代理整份体），
     * 最后才按状态码归类。
     *
     * @param sawRedirect 这次请求是否真的见到过 302（直链缓存路径传 true；探测路径按实情）
     * @param emptyBody   响应成功但没有体
     * @param status      HTTP 状态码，{@code <= 0} 表示没有拿到状态行
     * @param failure     连接层异常，可以为 null
     * @return 稳定标签，绝不包含签名地址或凭据
     */
    public static String fallbackReason(boolean sawRedirect, boolean emptyBody, int status,
            Throwable failure) {
        if (failure != null) {
            String fromFailure = failureReason(failure);
            if ("http_0".equals(fromFailure) && status > 0) {
                return "http_" + status;
            }
            return fromFailure;
        }
        if (emptyBody) {
            return "empty_response";
        }
        if (!sawRedirect) {
            return "no_302";
        }
        if (status >= 400) {
            return "http_" + status;
        }
        return "unknown";
    }

    /**
     * 异常类型 → 稳定标签。
     *
     * <p>停滞现场抛出来的往往只是 {@code SocketException: Socket closed}（连接被看门狗关掉）
     * 或 {@code SocketTimeoutException}，从异常文本上分不清是签名过期、服务端不吐字节还是
     * 后端对直连路径回了空响应；按类型拆开之后，{@code direct_link_miss} 的 reason 分布
     * 能直接回答"直链到底坏在哪"。
     */
    public static String failureReason(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        if (failure instanceof java.net.SocketTimeoutException) {
            return "timeout";
        }
        if (failure instanceof java.net.SocketException) {
            String message = failure.getMessage();
            return message != null && message.toLowerCase(java.util.Locale.US).contains("closed")
                    ? "socket_closed" : "socket_error";
        }
        String httpCode = httpStatusOf(failure);
        if (httpCode != null) {
            return "http_" + httpCode;
        }
        String message = failure.getMessage();
        if (message != null) {
            String lower = message.toLowerCase(java.util.Locale.US);
            if (lower.contains("no body")) {
                return "empty_response";
            }
            if (lower.contains("unsupported direct url scheme")
                    || lower.contains("unsupported redirect")) {
                return "unsupported_scheme";
            }
            if (lower.contains("invalid get redirect")) {
                return "invalid_url";
            }
        }
        return failure.getClass().getSimpleName().toLowerCase(java.util.Locale.US);
    }

    /**
     * 从异常里取 HTTP 状态码。
     *
     * <p>按类名 + 公有字段 {@code code} 取，避免本类反向依赖 {@link WebDavClient}
     * （它带着 Android 依赖，而这里要能被纯 JVM 单测直接钉死）。
     */
    private static String httpStatusOf(Throwable failure) {
        Class<?> type = failure.getClass();
        String name = type.getSimpleName();
        if (!"HttpStatusException".equals(name)) {
            return null;
        }
        try {
            java.lang.reflect.Field code = type.getField("code");
            Object value = code.get(failure);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
