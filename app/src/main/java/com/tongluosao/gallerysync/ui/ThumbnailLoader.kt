package com.tongluosao.gallerysync.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.tongluosao.gallerysync.core.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 进度页的缩略图加载。
 *
 * <p>不引第三方图片库（Glide / Coil）：进度页最多几十个方格、每格只要 320px，
 * 一个内存 LRU 加一次采样解码就够了，为此多背一个库不值得。
 *
 * <p>按**绝对路径**直接解码，不走 MediaStore：上传队列里拿到的就是 DBImage 给的绝对路径，
 * 反查 _id 再 loadThumbnail 要多一次跨进程查询，而 Android 13+ 的媒体权限对
 * "自己读自己的媒体文件"本来就是放行的。
 */
internal object ThumbnailLoader {

    /**
     * 方格的目标边长。
     *
     * <p>进度页是 3 列贴屏幕边，411dp 屏每格约 135dp；420dpi 下约 355px。
     * 取 320 而不是 355：`inSampleSize` 只按 2 的幂降采样，取 320 与 360 实际解出的尺寸
     * 完全一样（一张 4000×3000 都解到 500×375），所以取小的那个不损失清晰度。
     */
    const val TILE_PX = 320

    /** 解码失败的路径上限。同一张照片在一轮备份里会反复出现在帧里，失败一次就别再试 */
    private const val MAX_FAILED = 256

    private val cache = object : LruCache<String, ImageBitmap>(maxCacheBytes()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.width * value.height * 4
    }

    private val failed: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * 解码线程池。
     *
     * <p>不用 {@code Dispatchers.IO}：它的默认并发是 64，方格列表一滚下去可能同时抛出几十个
     * 解码请求，一次解码就是几百 KB 的位图，并发太高只会把堆打爆、把滚动拖卡。
     * 三路够用 —— 方格是懒加载的，真正同时在解的就那么几张。
     */
    private val dispatcher: CoroutineDispatcher =
        Executors.newFixedThreadPool(3) { runnable ->
            Thread(runnable, "gallerysync-thumb").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    /**
     * 取一张缩略图；失败或没有权限时返回 null，调用方退回占位底。
     *
     * <p>没有在途去重：同一帧里不会有重复路径（去重键就含路径），而不同帧之间同一个路径
     * 第二次来的时候缓存已经填好了。
     */
    suspend fun load(path: String, video: Boolean, targetPx: Int = TILE_PX): ImageBitmap? {
        if (path.isEmpty()) {
            return null
        }
        val cacheKey = targetPx.toString() + "|" + path
        cache.get(cacheKey)?.let { return it }
        if (failed.contains(cacheKey)) {
            return null
        }
        val image = withContext(dispatcher) {
            decode(path, video, targetPx)?.asImageBitmap()
        }
        if (image == null) {
            if (failed.size >= MAX_FAILED) {
                failed.clear()
            }
            failed.add(cacheKey)
            return null
        }
        cache.put(cacheKey, image)
        return image
    }

    private fun decode(path: String, video: Boolean, targetPx: Int): Bitmap? {
        return try {
            if (!File(path).isFile) {
                null
            } else if (video) {
                decodeVideo(path, targetPx)
            } else {
                decodeImage(path, targetPx)
            }
        } catch (t: Throwable) {
            // 权限被拒、文件被删、格式解不了都会走到这里，卡片退回占位底即可
            Logger.w("event=thumb_decode_failed path=" + path + " detail=" + t)
            null
        }
    }

    private fun decodeVideo(path: String, targetPx: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ThumbnailUtils.createVideoThumbnail(File(path), Size(targetPx, targetPx), null)
        } else {
            @Suppress("DEPRECATION")
            ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND)
        }

    private fun decodeImage(path: String, targetPx: Int): Bitmap? {
        // 先只读尺寸：直接解码一张 5000×4000 的原图，光是这一步就可能 OOM
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
            // RGB_565：进度页只显示、不编辑，不需要 alpha 也不需要广色域，内存直接减半
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(path, options)
    }

    /** 只按 2 的幂降采样，一路降到两边都不超过 [targetPx] */
    private fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= targetPx || h / 2 >= targetPx) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    /** 缓存上限取堆的八分之一，再封顶 32MB：手机相册动辄几千张，不封顶会一路吃到 OOM */
    private fun maxCacheBytes(): Int {
        val budget = Runtime.getRuntime().maxMemory() / 8
        return budget.coerceAtMost(32L * 1024 * 1024).toInt()
    }
}
