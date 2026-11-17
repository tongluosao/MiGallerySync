package com.tongluosao.gallerysync.core;

import java.io.FileNotFoundException;
import java.util.Locale;

/**
 * 视频渐进缓存「首块等待失败」的重试判定与「目录被清空」的 IO 失败识别。
 *
 * <p>真机现象：相册 cache 目录被系统清空后，首块请求连着两次以
 * {@code FileNotFoundException: ... .part: open failed: ENOENT} 失败；一旦退回纯中继，播放器
 * 重连多次也拿不到一帧。正确做法是重建目录、换一个新线程重试一次，两次都失败才退回中继。
 *
 * <p>纯 Java、不引用 Android 类，方便在 JVM 单测里覆盖。
 */
public final class VideoCacheRetryPolicy {
    /** 首块最多尝试几次：首次 + 重试一次。 */
    public static final int MAX_FIRST_CHUNK_ATTEMPTS = 2;

    private VideoCacheRetryPolicy() { }

    /**
     * 首块等待失败后是否还值得重试。{@code attempt} 是刚用完的尝试序号（0 表示第一次）。
     * 目录或 {@code .part} 不在（被系统清空）才重试；两者都在说明失败与目录无关，重试也没用。
     */
    public static boolean shouldRetryFirstChunk(int attempt, boolean partExists,
            boolean dirExists) {
        if (attempt >= MAX_FIRST_CHUNK_ATTEMPTS) return false;
        return !dirExists || !partExists;
    }

    /**
     * 是否属于「目录被清空」这类可重建恢复的 IO 失败：{@link FileNotFoundException}（含被包装成
     * 普通 {@code IOException} 的内层原因），或消息里带 {@code ENOENT} / {@code No such file}。
     */
    public static boolean isRecoverableIoFailure(Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof FileNotFoundException) return true;
            String message = cause.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.US);
                if (lower.contains("enoent") || lower.contains("no such file")) return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
