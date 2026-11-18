package com.tongluosao.gallerysync.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import com.tongluosao.gallerysync.core.Config;
import com.tongluosao.gallerysync.core.Logger;
import com.tongluosao.gallerysync.core.OriginalStore;
import com.tongluosao.gallerysync.core.PreviewStateRules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
 * <p>第 3 波起筛选分两层：SQL 只做粗筛，状态由注册表在 Java 侧判。
 * <ul>
 *   <li>SQL 侧（粗筛）：{@code localFile} 是本机真实路径、不在 {@code .gallerysync-slim}
 *       镜像目录里、不是 {@code .png}、图片/视频、仅本地未同步、体积够大、有 sha1。原来那条
 *       "{@code thumbnailFile} 在隐藏目录里就排除"的条件已经去掉：原地覆盖之后
 *       {@code localFile} 是原路径、{@code thumbnailFile} 为空，这两列根本分不出
 *       "已经优化过"和"还没优化"，继续按列猜只会把该跳过的行放进队列。png 因为不参与空间优化
 *       （{@code SlimHook} 里也会再判一次），直接在 SQL 里排除，免得它占"等待优化"的计数。</li>
 *   <li>Java 侧（精确过滤）：按 {@code _id} / {@code sha1} 查注册表登记的
 *       {@link OriginalStore.LocalState}，用 {@link PreviewStateRules#skipSlimCandidate}
 *       跳过已经优化（原地 / 侧车）、写入中途和损坏的行。没有登记记录的行照常进候选，
 *       交给 {@code SlimHook} 的安全闸门判。</li>
 * </ul>
 */
public final class SlimCandidates {

    /** 跟 {@code MetadataRestoreJob} 用的是同一个 provider。 */
    private static final String CLOUD_URI = "content://com.miui.gallery.cloud.provider/cloud";

    /** 一次最多取这么多候选：再多也没有一条队列能在一轮里跑完。 */
    private static final int MAX_CANDIDATES = 20_000;

    /** 小于这个体积的照片优化收益太低，直接排除。 */
    private static final long MIN_SIZE = 128L * 1024L;

    private static final String[] PROJECTION = {"_id", "sha1"};

    private SlimCandidates() {
    }

    /**
     * 查一遍候选 id，按拍摄时间从新到旧。
     *
     * <p>查询失败返回空表而不是抛异常：调用方（命令广播、界面）只能显示"没有可优化的照片"，
     * 让它自己异常退出只会把相册进程的接收线程带下去。
     *
     * @param store 注册表；为 null 时退化为只做 SQL 粗筛（旧行为），不因取不到注册表就不优化
     * @param config 当前远端目标；为 null 时同样不做状态过滤
     */
    public static List<Long> collect(Context context, Config config, OriginalStore store) {
        if (context == null) {
            return Collections.emptyList();
        }
        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) {
            return Collections.emptyList();
        }
        Map<String, OriginalStore.LocalState> states = stateIndex(config, store);
        List<Long> ids = new ArrayList<>();
        int skippedByState = 0;
        // 细筛只去掉了"thumbnailFile 在隐藏目录就排除"这个硬编码假设：
        // 镜像目录本身仍然不碰（localFile 落在 .gallerysync-slim 里的行是下载镜像，不是相册原件）。
        String where = "localFile IS NOT NULL"
                + " AND localFile <> ''"
                + " AND localFile LIKE '/storage/emulated/0/%'"
                + " AND localFile NOT LIKE '%/.gallerysync-slim/%'"
                // PNG 不参与空间优化：无损重编收益小、超大图还会 OOM，连候选都不该占
                // "等待优化"的计数。LOWER() 让大写的 .PNG 也被排除。
                + " AND LOWER(localFile) NOT LIKE '%.png'"
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
            int sha1Index = cursor.getColumnIndex("sha1");
            while (cursor.moveToNext() && ids.size() < MAX_CANDIDATES) {
                long id = cursor.getLong(index);
                String sha1 = sha1Index < 0 || cursor.isNull(sha1Index)
                        ? null : cursor.getString(sha1Index);
                OriginalStore.LocalState state =
                        OriginalStore.stateForRow(states, Long.toString(id), sha1);
                if (PreviewStateRules.skipSlimCandidate(state)) {
                    skippedByState++;
                    Logger.d("event=slim_candidate_skipped row=" + id
                            + " state=" + state.wire()
                            + " desc=" + PreviewStateRules.describe(state));
                    continue;
                }
                ids.add(id);
            }
        } catch (Throwable t) {
            Logger.w("event=slim_candidates_failed detail=" + t, t);
            return Collections.emptyList();
        }
        Logger.i("event=slim_candidates_collected count=" + ids.size()
                + " skipped_by_state=" + skippedByState);
        return ids;
    }

    /**
     * 一次建好的 rowId/sha1 -> 本地状态快照。
     *
     * <p>必须一次建表：候选最多 2 万条、注册表上限 10 万条，逐条 {@code find()} 是
     * O(候选 x 登记) 的全表扫描。注册表不可用时返回空表（照旧只做 SQL 粗筛），
     * 不能因为查不到状态就把整批候选丢掉。
     */
    private static Map<String, OriginalStore.LocalState> stateIndex(Config config,
            OriginalStore store) {
        if (config == null || store == null) {
            return Collections.emptyMap();
        }
        try {
            return store.localStateIndex(config);
        } catch (Throwable t) {
            Logger.w("event=slim_candidate_state_index_failed detail=" + t);
            return Collections.emptyMap();
        }
    }
}
