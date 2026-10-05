package com.tongluosao.gallerysync.hook;

import com.tongluosao.gallerysync.core.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 把刚落地的文件告诉相册，让它进媒体库。
 *
 * <p>下载与上传不同：上传是从相册已有的项出发，文件本来就在库里；下载出来的文件是**凭空出现**
 * 在 DCIM 下的，相册不会自己知道。只写进文件系统的话，用户打开相册看不到它 ——
 * 备份是"相册 → WebDAV"，下载补的是另一半，看不到就等于没下。
 *
 * <p>实现走宿主自己的扫描引擎（{@code ScannerEngine}），而不是往 MediaStore 里插一行：
 * 扫描引擎会算缩略图、EXIF、去重、写自己的数据库，MediaStore 那一行只是结果之一。
 * 自己插行会得到一个"能看到但打不开"的条目。
 *
 * <p>全部反射、全部 try/catch：宿主版本换了签名也只是扫不进来（并留一条日志），
 * 不能让下载本身失败。
 */
public final class ScannerIngest {

    /**
     * 扫描场景码。宿主 {@code ScanTaskConfigFactory.get(int)} 的 13 = "可信来源"、
     * 优先级 32，且**只允许 0..29**（别的值它会直接抛 IllegalArgumentException）。
     * 用 13 是因为下载下来的文件是我们自己核对过长度才改名的，值得标成可信。
     */
    private static final int SCENE_CODE = 13;

    private final ClassLoader mLoader;

    public ScannerIngest(ClassLoader loader) {
        mLoader = loader;
    }

    /**
     * 让相册扫一个刚下载好的文件。
     *
     * <p>优先用 {@code scanPathsAsync(List, int)}（批量接口，一次把整个目录交上去更省），
     * 失败再退回 {@code scanFile(Context, String, int)}。
     */
    public void ingest(String absolutePath) {
        if (absolutePath == null || absolutePath.isEmpty()) {
            return;
        }
        if (scanPaths(absolutePath)) {
            return;
        }
        if (scanFile(absolutePath)) {
            return;
        }
        Logger.w("event=download_scan_failed path=" + absolutePath
                + " reason=no_usable_scanner_api");
    }

    private boolean scanPaths(String absolutePath) {
        try {
            Class<?> engine = Class.forName(
                    "com.miui.gallery.scanner.core.ScannerEngine", true, mLoader);
            Object instance = engine.getMethod("getInstance").invoke(null);
            if (instance == null) {
                return false;
            }
            List<String> paths = new ArrayList<>(1);
            paths.add(absolutePath);
            Method method = engine.getMethod("scanPathsAsync", List.class, int.class);
            method.invoke(instance, paths, SCENE_CODE);
            Logger.d("event=download_scan_requested path=" + absolutePath + " api=scanPathsAsync");
            return true;
        } catch (Throwable t) {
            Logger.d("event=download_scan_fallback api=scanPathsAsync detail=" + t);
            return false;
        }
    }

    private boolean scanFile(String absolutePath) {
        try {
            Class<?> engine = Class.forName(
                    "com.miui.gallery.scanner.core.ScannerEngine", true, mLoader);
            Object instance = engine.getMethod("getInstance").invoke(null);
            if (instance == null) {
                return false;
            }
            // 单文件接口要 Context：用相册进程自己的那个
            Object context = HostContext.get(mLoader);
            if (context == null) {
                return false;
            }
            Method method = engine.getMethod("scanFile",
                    android.content.Context.class, String.class, int.class);
            method.invoke(instance, context, absolutePath, SCENE_CODE);
            Logger.d("event=download_scan_requested path=" + absolutePath + " api=scanFile");
            return true;
        } catch (Throwable t) {
            Logger.d("event=download_scan_fallback api=scanFile detail=" + t);
            return false;
        }
    }
}
