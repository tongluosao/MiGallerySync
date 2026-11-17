package com.tongluosao.gallerysync.core;

import java.util.Locale;

/**
 * 元数据请求的读超时与重试策略（纯函数，方便单测）。
 *
 * <p>为什么元数据要单独一套：流式请求（PUT）的读超时按请求体上浮，因为服务器常常要
 * 先把整个请求体收完、落盘，才肯回响应；而 PROPFIND / HEAD / MKCOL 这类请求
 * **体很小、后端很慢** —— 它等的是"服务器去算元数据"，不是"服务器收字节"。
 *
 * <p>2026-11 真机证据（OpenList + S3 后端）：PC 上 {@code curl -X PROPFIND} 同一台服务器
 * Depth 0 冷启动 6832ms、Depth 1 1071ms；平板（Android 13）走模块上传时反复出现
 * {@code event=conn_reopen method=PROPFIND reason=Read timed out} 与
 * {@code event=propfind_failed ... java.net.SocketTimeoutException: Read timed out}，
 * 上传卡在 {@code upload_begin} 之后。30 秒的固定读超时对这条链路不够，
 * 所以元数据请求改成 90 秒，并在超时后换新连接重试一次。
 *
 * <p>手机上的行为不变：这里只放宽"等多久"和"超时后再试一次"，
 * 成功/失败的判定口径（状态码、长度比对）一个字都没动。
 */
final class WebDavTimeoutPolicy {

    /**
     * 元数据请求的读超时（固定值，不按请求体/响应体大小算）。
     *
     * <p>取 90 秒而不是 60 秒：冷启动 6.8 秒已经花掉了 dns + tcp + tls 之外的一大截，
     * 而深度 1 的列目录在 3257 个对象的相册上服务器要现场拼 1MB 级 XML（见
     * {@code LIST_BODY_LIMIT}），再叠加移动网络的抖动与重传，60 秒仍可能擦边。
     * 上限只要**有界**就行 —— 两次尝试合计最多 180 秒，比"判超时后整项失败、
     * 退避一轮再来"便宜得多。
     */
    static final int META_READ_TIMEOUT_MS = 90_000;

    /** 元数据请求最多尝试几次：首试 + 一次换新连接的重试 */
    static final int META_MAX_ATTEMPTS = 2;

    private WebDavTimeoutPolicy() {
    }

    /**
     * 是不是"非流式"的元数据请求。
     *
     * <p>判据是**没有请求体要等服务器收完**，而不是"响应小"：PUT 才是这里的流式请求
     * （大文件要发好几分钟，读超时必须按体量上浮），GET 走的是另一条流式读路径。
     * 不认识的动词一律按流式处理 —— 那套超时更保守，不会让大请求被误判超时。
     */
    static boolean isMetadataMethod(String method) {
        if (method == null) {
            return false;
        }
        switch (method.toUpperCase(Locale.US)) {
            case "PROPFIND":
            case "PROPPATCH":
            case "HEAD":
            case "OPTIONS":
            case "MKCOL":
            case "DELETE":
            case "MOVE":
            case "COPY":
            case "LOCK":
            case "UNLOCK":
                return true;
            default:
                return false;
        }
    }

    /**
     * 选读超时（毫秒）。
     *
     * @param method           HTTP 动词
     * @param bodyLength       请求体字节数（文件长度或内存体的长度）
     * @param streamBaseMs     流式请求的固定部分
     * @param streamPerByteMs  流式请求每字节折多少毫秒（分母）
     * @param streamMaxMs      流式请求的上限
     */
    static int readTimeoutMs(String method, long bodyLength, int streamBaseMs,
            int streamPerByteMs, int streamMaxMs) {
        if (isMetadataMethod(method)) {
            return META_READ_TIMEOUT_MS;
        }
        long bytes = bodyLength > 0L ? bodyLength : 0L;
        if (streamPerByteMs <= 0) {
            return streamMaxMs;
        }
        long allowance = streamBaseMs + bytes / streamPerByteMs;
        return (int) Math.min(allowance, streamMaxMs);
    }

    /**
     * 读到 {@link java.net.SocketTimeoutException} 之后，还要不要再试一次。
     *
     * <p>只对元数据请求重试，且只重试一次：流式请求（PUT）重试等于把整份上行再烧一遍，
     * 那笔账由调用方的退避逻辑去付，不在这里偷偷付。
     *
     * @param failedAttempts 已经失败的尝试次数（首次失败传 1）
     */
    static boolean shouldRetryAfterTimeout(String method, int failedAttempts) {
        return isMetadataMethod(method) && failedAttempts < META_MAX_ATTEMPTS;
    }
}
