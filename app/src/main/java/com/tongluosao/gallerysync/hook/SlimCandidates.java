package com.tongluosao.gallerysync.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import com.tongluosao.gallerysync.core.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 空间优化的候选照片清单：模块自己那条优化队列从哪来。
 *
 * <p>原生流程的候选是 {@code SlimScanner.getSelection()} 算出来的（一堆跟小米云备份状态绑定的
 * SQL 条件）。我们要自己驱动队列，就不能依赖那次扫描 —— 它只在相册的优化页面里跑，而我们
 * 从入口处就把那个页面换掉了。
 *
 * <p>所以这里用一套**自己写死、偏保守**的条件直接查 {@code cloud} 表。宁可少收几张，
 * 也不要收进不该动的行：真正的安全校验（远端原件是否验证过、格式、体积、租约）在后面
 * {@code SlimHook.runSlim} 里逐项再做一遍，这里只是决定"要不要试"。
 *
 * <p>条件与理由：
 * <ul>
 *   <li>{@code localFile} 在 {@code /storage/emulated/0/} 下：只优化本机真实存在的照片，
 *       镜像目录与回收站都不碰。</li>
 *   <li>不含 {@code /.gallerysync-slim/}：那里面已经是优化副本，再优化就是套娃。</li>
 *   <li>{@code serverType IN (1,2)}：只处理图片与视频，跳过未知行。</li>
 *   <li>{@code localFlag = 7}：相册自己的"仅本地、未同步"状态；其余状态说明这张照片
 *       还有别的流程在动它。</li>
 *   <li>{@code size > 128 KiB}：几百 KB 以下的照片压不出多少空间，失败的编码尝试
 *       只会白烧 CPU。</li>
 *   <li>{@code sha1} 长度 40：没有摘要就没法跟远端原件对账，直接跳过。</li>
 * </ul>
 */
public final class SlimCandidates {

    /** 跟 {@code MetadataRestoreJob} 用的是同一个 provider。 */
    private static final String CLOUD_URI = "content://com.miui.gallery.cloud.provider/cloud";

    /** 一次最多取这么多候选：再多也没有一条队列能在一轮里跑完。 */
    private static final int MAX_CANDIDATES = 20_000;

    /** 小于这个体积的照片优化收益太低，直接排除。 */
    private static final long MIN_SIZE = 128L * 1024L;

    private static final String[] PROJECTION = {"_id"};

    private SlimCandidates() {
    }

    /**
     * 查一遍候选 id，按拍摄时间从新到旧。
     *
     * <p>查询失败返回空表而不是抛异常：调用方（命令广播、界面）只能显示"没有可优化的照片"，
     * 让它自己异常退出只会把相册进程的接收线程带下去。
     */
    public static List<Long> collect(Context context) {
        if (context == null) {
            return Collections.emptyList();
        }
        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) {
            return Collections.emptyList();
        }
        List<Long> ids = new ArrayList<>();
        String where = "localFile IS NOT NULL"
                + " AND localFile <> ''"
                + " AND localFile LIKE '/storage/emulated/0/%'"
                + " AND localFile NOT LIKE '%/.gallerysync-slim/%'"
                + " AND (thumbnailFile IS NULL OR thumbnailFile NOT LIKE '%/.gallerysync-slim/%')"
                + " AND serverType IN (1,2)"
                + " AND localFlag = 7"
                + " AND size > " + MIN_SIZE
                + " AND sha1 IS NOT NULL"
                + " AND length(sha1) = 40";
        try (Cursor cursor = resolver.query(Uri.parse(CLOUD_URI), PROJECTION, where, null,
                "dateTaken DESC")) {
            if (cursor == null) {
                Logger.w("event=slim_candidates_empty reason=cursor_null");
                return Collections.emptyList();
            }
            int index = cursor.getColumnIndex("_id");
            if (index < 0) {
                Logger.w("event=slim_candidates_empty reason=no_id_column");
                return Collections.emptyList();
            }
            while (cursor.moveToNext() && ids.size() < MAX_CANDIDATES) {
                ids.add(cursor.getLong(index));
            }
        } catch (Throwable t) {
            Logger.w("event=slim_candidates_failed detail=" + t, t);
            return Collections.emptyList();
        }
        Logger.i("event=slim_candidates_collected count=" + ids.size());
        return ids;
    }
}
