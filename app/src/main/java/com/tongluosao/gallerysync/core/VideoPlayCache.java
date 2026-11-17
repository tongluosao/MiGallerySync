package com.tongluosao.gallerysync.core;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 视频在线播放的本地缓存目录。
 *
 * 中继流式播放时相册播放器多次重连后解码器拿不到帧，因此改为「先把瘦身原片整份下载到应用缓存，
 * 再交给相册原生播放器播本地文件」。文件名只保留 {@code [A-Za-z0-9._-]}，避免播放器/相册对特殊
 * 字符路径的解析差异；命名以 rowId 或 sha1 前 16 位开头，同一部片重进页面直接命中。
 */
public final class VideoPlayCache {

    private static final String DIR_NAME = "video-play";
    private static final String DEFAULT_EXTENSION = ".mp4";
    private static final String FALLBACK_NAME = "video";
    private static final int MAX_NAME_LENGTH = 96;
    private static final int MAX_EXTENSION_LENGTH = 8;
    private static final int SHA1_PREFIX_LENGTH = 16;
    /** 进行中的下载文件至少这么大岁数才允许被容量回收，避免删掉正在写入的 .part。 */
    private static final long ACTIVE_FILE_MILLIS = 5L * 60L * 1000L;

    private VideoPlayCache() {
    }

    /** 缓存目录本身：{@code <app cache>/video-play}。 */
    public static File dir(Context context) {
        return new File(context.getCacheDir(), DIR_NAME);
    }

    /**
     * 某条原片记录对应的缓存文件。rowId 为正数时用它，否则用 sha1 前 16 位，扩展名取远端路径后缀
     * （没有就 {@code .mp4}）。
     */
    public static File fileFor(Context context, OriginalStore.Record record) {
        String key = record == null ? "unknown" : keyOf(record);
        String remotePath = record == null ? null : record.remotePath;
        String extension = record == null ? DEFAULT_EXTENSION : extension(remotePath);
        String stem = sanitize(key + "-" + baseName(remotePath));
        int budget = MAX_NAME_LENGTH - extension.length();
        if (budget > 0 && stem.length() > budget) stem = stem.substring(0, budget);
        if (stem.isEmpty()) stem = FALLBACK_NAME;
        return new File(dir(context), stem + extension);
    }

    /** 只有长度也完全一致才算命中，半截文件一律重新下载。 */
    public static boolean isReady(File file, long expectedLength) {
        try {
            return file != null && expectedLength > 0L && file.isFile()
                    && file.length() == expectedLength;
        } catch (Throwable t) {
            Logger.w("event=video_play_cache_ready_check_failed message=" + t);
            return false;
        }
    }

    /** 缓存目录当前占用字节数，用于下载前的限额判断。 */
    public static long totalBytes(Context context) {
        try {
            File[] files = dir(context).listFiles();
            if (files == null) return 0L;
            long total = 0L;
            for (File file : files) {
                if (file != null && file.isFile()) total += file.length();
            }
            return total;
        } catch (Throwable t) {
            Logger.w("event=video_play_cache_size_failed message=" + t);
            return 0L;
        }
    }

    /**
     * 回收旧缓存：先删超过 {@code keepMillis} 的文件，再按 lastModified 从旧到新删到总量不超过
     * {@code maxTotalBytes}。{@code keepMillis}/{@code maxTotalBytes} 传 0 或负数表示不限。
     */
    public static void prune(Context context, long keepMillis, long maxTotalBytes) {
        try {
            File[] files = dir(context).listFiles();
            if (files == null || files.length == 0) return;
            List<File> ordered = new ArrayList<>(Arrays.asList(files));
            Collections.sort(ordered, new Comparator<File>() {
                @Override
                public int compare(File left, File right) {
                    return Long.compare(left.lastModified(), right.lastModified());
                }
            });
            long now = System.currentTimeMillis();
            long total = 0L;
            List<File> kept = new ArrayList<>(ordered.size());
            for (File file : ordered) {
                if (file == null || !file.isFile()) continue;
                if (keepMillis > 0L && now - file.lastModified() > keepMillis) {
                    if (delete(file)) continue;
                }
                total += file.length();
                kept.add(file);
            }
            if (maxTotalBytes <= 0L) return;
            for (File file : kept) {
                if (total <= maxTotalBytes) break;
                if (now - file.lastModified() < ACTIVE_FILE_MILLIS) continue;
                long length = file.length();
                if (delete(file)) total -= length;
            }
        } catch (Throwable t) {
            Logger.w("event=video_play_cache_prune_failed message=" + t);
        }
    }

    private static boolean delete(File file) {
        try {
            return file.delete();
        } catch (Throwable t) {
            Logger.w("event=video_play_cache_delete_failed message=" + t);
            return false;
        }
    }

    private static String keyOf(OriginalStore.Record record) {
        String rowId = record.rowId == null ? "" : record.rowId.trim();
        if (isPositiveNumber(rowId)) return sanitize(rowId);
        String sha1 = record.sha1 == null ? "" : record.sha1.trim().toLowerCase(Locale.US);
        if (sha1.length() >= SHA1_PREFIX_LENGTH) {
            return sanitize(sha1.substring(0, SHA1_PREFIX_LENGTH));
        }
        if (!sha1.isEmpty()) return sanitize(sha1);
        String remotePath = record.remotePath;
        if (remotePath != null && !remotePath.isEmpty()) {
            return "p" + Integer.toHexString(remotePath.hashCode());
        }
        return "unknown";
    }

    private static boolean isPositiveNumber(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            return Long.parseLong(value) > 0L;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String baseName(String remotePath) {
        if (remotePath == null) return FALLBACK_NAME;
        int slash = remotePath.lastIndexOf('/');
        String name = slash >= 0 && slash + 1 <= remotePath.length()
                ? remotePath.substring(slash + 1) : remotePath;
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        String clean = sanitize(name);
        return clean.isEmpty() ? FALLBACK_NAME : clean;
    }

    private static String extension(String remotePath) {
        if (remotePath != null) {
            int slash = remotePath.lastIndexOf('/');
            String name = slash >= 0 && slash + 1 <= remotePath.length()
                    ? remotePath.substring(slash + 1) : remotePath;
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < name.length()) {
                String ext = sanitize(name.substring(dot + 1));
                if (!ext.isEmpty() && ext.length() <= MAX_EXTENSION_LENGTH) {
                    return "." + ext.toLowerCase(Locale.US);
                }
            }
        }
        return DEFAULT_EXTENSION;
    }

    /** 只保留 {@code [A-Za-z0-9._-]}，其它字符一律换成下划线。 */
    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            builder.append(allowed ? c : '_');
        }
        return builder.toString();
    }
}
