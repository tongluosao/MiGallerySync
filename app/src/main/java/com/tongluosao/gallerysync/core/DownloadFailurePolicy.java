package com.tongluosao.gallerysync.core;

import java.io.IOException;
import java.util.Locale;

/**
 * 下载失败的分类与冷却判据（纯函数，可在 JVM 单测里逐条钉住）。
 *
 * <p>背景：{@code DownloadQueue} 原来的重试记账只有一个整数 —— 达到
 * {@code MAX_FAIL_PER_PATH} 就挡 {@code FAILURE_COOLDOWN_MS}，冷却一过<b>把计数清零</b>。
 * 于是"服务端每次都返回坏字节""目的地被别人占了"这类永远不会自愈的失败，也会每隔
 * 一分钟把整份对象重下一遍（真机日志里同一行 5 分钟重下了 4 次）。修法不是简单调大
 * 次数，而是先把失败分成三类：
 *
 * <ul>
 *   <li>{@link Kind#RETRYABLE} 传输/服务端临时故障：短冷却，计数<b>不清零</b>，重试越多次
 *       退避越长，用户修好网络后下一次尝试照样立刻成功（成功即清账）。</li>
 *   <li>{@link Kind#TERMINAL} 字节/目的地类问题（内容校验不过、目的地被占）：基础冷却
 *       30 分钟起步，同样只增不清，避免"每分钟重下一份 50MB 视频"。</li>
 *   <li>{@link Kind#ABSENT} 远端本轮确认不存在：<b>不计次数、不算失败</b>。远端对象被删掉
 *       不是"下载失败"，记进失败账本只会让一张已经不存在的照片永久占着重试额度。</li>
 * </ul>
 *
 * <p>本类只做纯计算：不读时钟、不碰文件、不依赖 Android，时间与次数都由调用方传入。
 */
public final class DownloadFailurePolicy {

    /** 未显式传入上限时的重试次数：与 {@code DownloadQueue.MAX_FAIL_PER_PATH} 同口径。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** 可重试故障的基础冷却。 */
    public static final long RETRY_COOLDOWN_MS = 60_000L;
    /** 终态故障的基础冷却：字节类问题不该每分钟重下一整份。 */
    public static final long TERMINAL_COOLDOWN_MS = 30L * 60_000L;
    /** 冷却上限：再久就等于放弃，但账本不清零，用户手动触发仍会立即重试。 */
    public static final long MAX_COOLDOWN_MS = 24L * 60L * 60_000L;
    /** 指数退避最多翻几档，避免移位溢出成负数。 */
    static final int MAX_DOUBLINGS = 6;

    private DownloadFailurePolicy() {
    }

    /** 失败种类：决定"还能不能自己好"。 */
    public enum Kind {
        RETRYABLE,
        TERMINAL,
        ABSENT
    }

    /** 具体原因：只用于日志与冷却长度，不进界面文案。 */
    public enum Cause {
        /** 本轮目录名单/探测明确说"远端没有这个名字"。 */
        PROBE_MISSING("probe_missing", Kind.ABSENT),
        /** 服务端 404/410。 */
        NOT_FOUND("not_found", Kind.ABSENT),
        /** socket 超时、连接被重置、被看门狗关掉连接。 */
        TRANSPORT("transport", Kind.RETRYABLE),
        /** 长时间没有新字节，被停滞看门狗中止。 */
        STALLED("stalled", Kind.RETRYABLE),
        /** 408/429/5xx：服务端临时故障。 */
        SERVER("server", Kind.RETRYABLE),
        /** 401/403：口令/权限不对，用户改配置之前不会自愈，但改完就该立刻能下。 */
        AUTH("auth", Kind.RETRYABLE),
        /** 400/416 一类：请求本身不合法，重试没有意义。 */
        BAD_REQUEST("bad_request", Kind.TERMINAL),
        /** 磁盘满 / 无法建目录。 */
        LOW_SPACE("low_space", Kind.RETRYABLE),
        /** 其它本地 IO（改名、落盘、读注册表）。 */
        IO("io", Kind.RETRYABLE),
        /** 下完是 0 字节。 */
        EMPTY_PAYLOAD("empty_payload", Kind.RETRYABLE),
        /** 下完是空/全 0/缺签名的内容：整份下来也过不了登记闸。 */
        INVALID_PAYLOAD("invalid_payload", Kind.RETRYABLE),
        /** 下完长度与远端声明不一致（截断）。 */
        LENGTH_MISMATCH("length_mismatch", Kind.RETRYABLE),
        /** 长度对、摘要不对：内容被中间层改写（代理返回全 0 就是这一类）。 */
        CONTENT_MISMATCH("content_mismatch", Kind.TERMINAL),
        /** 目的地已经有一份不是本记录副本的文件：不覆盖别人的东西。 */
        DESTINATION_CONFLICT("destination_conflict", Kind.TERMINAL),
        /** 注册表拒绝登记（非内容类拒绝，例如指纹缺失）。 */
        REGISTRY_REJECTED("registry_rejected", Kind.TERMINAL),
        UNKNOWN("unknown", Kind.RETRYABLE);

        private final String wire;
        private final Kind kind;

        Cause(String wire, Kind kind) {
            this.wire = wire;
            this.kind = kind;
        }

        /** 日志里的稳定标识（不要翻译，抓日志靠它）。 */
        public String wire() {
            return wire;
        }

        public Kind kind() {
            return kind;
        }
    }

    /** 该原因的默认种类；没有原因时按可重试处理（宁可多试一次，也不误判成终态）。 */
    public static Kind kindOf(Cause cause) {
        return cause == null ? Kind.RETRYABLE : cause.kind();
    }

    /**
     * HTTP 状态码 → 原因。
     *
     * @param code 0 或负数按"没有状态码"处理
     */
    public static Cause causeOfStatus(int code) {
        if (code == 404 || code == 410) {
            return Cause.NOT_FOUND;
        }
        if (code == 401 || code == 403) {
            return Cause.AUTH;
        }
        if (code == 408 || code == 425 || code == 429 || (code >= 500 && code <= 599)) {
            return Cause.SERVER;
        }
        if (code >= 400 && code <= 499) {
            return Cause.BAD_REQUEST;
        }
        return Cause.UNKNOWN;
    }

    /**
     * 兜底：只有异常文本可用时按前缀认原因。
     *
     * <p>队列自己抛的 IOException 都是固定措辞（本文件与测试一起钉住），所以按文本
     * 归类是确定的，不是"猜"。认识不出来的一律 {@link Cause#UNKNOWN}（按可重试处理），
     * 绝不会把一次未知故障记成"终态"而永久挡住这张照片。
     */
    public static Cause causeOfMessage(String message) {
        if (message == null || message.isEmpty()) {
            return Cause.UNKNOWN;
        }
        String m = message.toLowerCase(Locale.US);
        if (m.contains("destination appeared") || m.contains("destination_conflict")) {
            return Cause.DESTINATION_CONFLICT;
        }
        if (m.contains("content_mismatch") || m.contains("does not match the registered digest")
                || m.contains("do not match the registered digest")) {
            return Cause.CONTENT_MISMATCH;
        }
        if (m.contains("cannot register invalid payload") || m.contains("invalid payload")
                || m.contains("all_zero") || m.contains("no_signature")) {
            return Cause.INVALID_PAYLOAD;
        }
        if (m.contains("length mismatch") || m.contains("changed before registration")
                || m.contains("staged download changed") || m.contains("size changed")) {
            return Cause.LENGTH_MISMATCH;
        }
        if (m.contains("absent") || m.contains("probe_missing")) {
            return Cause.PROBE_MISSING;
        }
        if (m.contains("no space") || m.contains("enospc") || m.contains("low_space")) {
            return Cause.LOW_SPACE;
        }
        if (m.contains("stalled") || m.contains("idle")) {
            return Cause.STALLED;
        }
        if (m.contains("empty original") || m.contains("staged preview is missing")) {
            return Cause.EMPTY_PAYLOAD;
        }
        if (m.contains("missing") || m.contains("cannot create")) {
            return Cause.IO;
        }
        return Cause.UNKNOWN;
    }

    /**
     * 这次失败还要不要挡着。
     *
     * @param kind     失败种类
     * @param attempts 已经失败的次数（含本次）
     * @param failedAt 最近一次失败的时刻（{@code SystemClock.elapsedRealtime()}），未知传 0
     * @param now      当前时刻，同一时钟
     */
    public static boolean blocked(Kind kind, int attempts, long failedAt, long now) {
        return blocked(kind, attempts, failedAt, now, DEFAULT_MAX_ATTEMPTS);
    }

    public static boolean blocked(Kind kind, int attempts, long failedAt, long now, int maxAttempts) {
        if (kind == Kind.ABSENT || attempts <= 0 || failedAt <= 0L) {
            return false;
        }
        int limit = maxAttempts <= 0 ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        // 次数没用完之前照旧立刻重试：短暂掉一次 Wi-Fi 不该被冷却一分钟
        if (attempts < limit) {
            return false;
        }
        long elapsed = now - failedAt;
        if (elapsed < 0L) {
            // 时钟回退（重启后 elapsedRealtime 归零）：按仍在冷却处理，宁可少试一次
            return true;
        }
        return elapsed < cooldownMs(kind, attempts, limit);
    }

    /** 第 {@code attempts} 次失败之后要挡多久。次数只增不减，所以退避是单调的。 */    public static long cooldownMs(Kind kind, int attempts, int maxAttempts) {
        if (kind == Kind.ABSENT) {
            return 0L;
        }
        long base = kind == Kind.TERMINAL ? TERMINAL_COOLDOWN_MS : RETRY_COOLDOWN_MS;
        int limit = maxAttempts <= 0 ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        int extra = attempts - limit;
        if (extra <= 0) {
            return Math.min(base, MAX_COOLDOWN_MS);
        }
        long value = base << Math.min(extra, MAX_DOUBLINGS);
        if (value <= 0L || value > MAX_COOLDOWN_MS) {
            return MAX_COOLDOWN_MS;
        }
        return value;
    }

    /**
     * 这次失败要吃掉几次重试额度。
     *
     * <p>停滞（看门狗关连接）单独按满额记：一份 12MB 的对象卡到 60s 才被中止，如果只记 1 次，
     * 下面两次尝试还会立刻重连、再各卡 60s —— 真机上一张图能白等 3 分钟。按满额记之后，
     * 下一次尝试直接进冷却，把连接让给还在正常跑的对象，等服务器缓过来再试。
     *
     * @param limit 队列的重试上限（{@code DownloadQueue.MAX_FAIL_PER_PATH}）
     */
    public static int attemptCost(Cause cause, int limit) {
        int cap = limit <= 0 ? DEFAULT_MAX_ATTEMPTS : limit;
        return cause == Cause.STALLED ? cap : 1;
    }

    /**
     * 队列自己抛的、带原因标签的 IO 异常。
     *
     * <p>继承 {@link IOException} 是有意的：{@code download()} 的 catch 顺序里 IOException
     * 分支已经做过先取消后记账的处理，这里只要带上原因，分类就跟着原因走，不必再改
     * catch 结构。
     */
    public static final class PayloadException extends IOException {

        private final Cause cause;

        public PayloadException(Cause cause, String message) {
            super(message);
            this.cause = cause == null ? Cause.UNKNOWN : cause;
        }

        public PayloadException(Cause cause, String message, Throwable failure) {
            super(message, failure);
            this.cause = cause == null ? Cause.UNKNOWN : cause;
        }

        public Cause cause() {
            return cause;
        }

        public Kind kind() {
            return kindOf(cause);
        }
    }
}
