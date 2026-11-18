package com.tongluosao.gallerysync.ui

import com.tongluosao.gallerysync.core.ProgressProtocol

/** 进度页的两个区块。上传、下载只差数据来源，渲染是同一个组件 */
enum class SectionKind { UPLOAD, DOWNLOAD }

/**
 * 一个区块的汇总数字（标题下面那张卡）。
 *
 * <p>纯数据：上传的数字来自帧里的汇总字段（`sDoneItems` 那组），下载的数字只能从
 * 已知的任务行里累加 —— 发布端没给下载方向的总量，这里也不假装知道。
 * 所有"进度条怎么算、还剩多久"的规则都在 [ProgressSections] 里，界面只负责摆位置。
 */
data class SectionProgress(
    val kind: SectionKind,
    val doneItems: Int = 0,
    val totalItems: Int = 0,
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSecond: Long = 0L,
    val remainingItems: Int = 0,
    val remainingBytes: Long = 0L
) {
    /**
     * 进度条用。优先按字节：一帧 30 MB、一帧 200 KB 时按条数算出来的百分比会一会儿跳
     * 10%、一会儿一动不动。总量拿不到（下载帧不带 `s*` 字段）时才退回按条数。
     */
    val fraction: Float
        get() = when {
            totalBytes > 0L -> (doneBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
            totalItems > 0 -> (doneItems.toDouble() / totalItems).coerceIn(0.0, 1.0).toFloat()
            else -> 0f
        }

    /** 百分比：跟 [fraction] 同源，避免出现"条走完了但写着 99%" */
    val percent: Int get() = (fraction * 100f).toInt()

    /** 剩余时间（秒）。速度未知、或已经没剩字节时为 null —— 界面那时不该写"0 秒" */
    val remainingSeconds: Long?
        get() = ProgressSections.remainingSeconds(remainingBytes, speedBytesPerSecond)

    /** 这一块还有没有没做完的活。没活时汇总卡只报"全部完成"，不再报速度 */
    val busy: Boolean get() = remainingItems > 0
}

/**
 * 列表折叠结果。
 *
 * [hidden] 是**当前**被藏起来的条数（展开后为 0）；[collapsible] 表示这份列表本来就能折
 * （条数超过阈值），展开后靠它决定还显不显示"收起" —— 没有它，展开一次按钮就消失了。
 */
data class RowFold(
    val visible: List<ProgressTask>,
    val hidden: Int,
    val collapsible: Boolean,
    val expanded: Boolean
) {
    val canExpand: Boolean get() = !expanded && hidden > 0
    val canCollapse: Boolean get() = expanded && collapsible
}

/**
 * 逐项速度：每个在传项一条自己的采样窗口。
 *
 * <p>速率不预先算好存下来，而是留着窗口、由界面在渲染时用**当前时刻**算：
 * 帧是 2Hz 而界面 1Hz 在走时钟，存一份快照的话，队列停了以后汇总速度已经衰减到 0，
 * 行里却还挂着几秒前的 5 MB/s —— 一眼就看得出对不上。
 */
data class TaskSpeeds(
    val windows: Map<String, List<SpeedSample>> = emptyMap()
) {
    fun rate(key: String, nowMs: Long): Long =
        DownloadSpeed.bytesPerSecond(windows[key] ?: emptyList(), nowMs)

    companion object {
        val EMPTY = TaskSpeeds()
    }
}

/**
 * 进度页的纯逻辑：总进度、剩余时间、折叠条数、速度聚合。
 *
 * <p>全部是纯函数（不碰 Android、不读时钟），单测直接给数据即可 —— 这些数字算错了，
 * 界面上是"进度条倒退""剩余 0 秒卡半小时""展开后按钮消失"这类很难复现的怪状。
 */
object ProgressSections {

    /** 折叠阈值：半屏大约放得下 8 行 */
    const val COLLAPSE_LIMIT = 8

    /** 折叠时保留的"最近完成"条数：再多就把正在传的行挤出屏幕 */
    const val RECENT_COMPLETED = 3

    /**
     * 上传区块的汇总。
     *
     * <p>总数/已完成/字节都取帧里的汇总字段（那是发布端按整个队列算的，比列表准）；
     * 只有在没有汇总时（老版本帧、或还没收到过）才退回用队列计数，
     * 这样界面上永远不会出现"已完成 0 / 0 项"却还在动的诡异状态。
     */
    fun upload(state: ProgressUiState, speedBytesPerSecond: Long): SectionProgress {
        // 帧里带了汇总就用汇总（发布端按整个队列算的，比只列几十项的列表准）；
        // 老版本帧没有汇总时退回"已列出的行数"——总比写"已完成 0 / 0 项"却还有一堆行在排队强
        val listed = state.uploads.size
        val totalItems = if (state.totalItems > 0) state.totalItems else listed
        val doneItems = if (totalItems > 0) {
            state.doneItems.coerceIn(0, totalItems)
        } else {
            state.doneItems.coerceAtLeast(0)
        }
        val totalBytes = state.totalBytes.coerceAtLeast(0L)
        val doneBytes = if (totalBytes > 0L) {
            state.doneBytes.coerceIn(0L, totalBytes)
        } else {
            state.doneBytes.coerceAtLeast(0L)
        }
        val activeUp = state.active.count { it.dir != ProgressProtocol.DIR_DOWN }
        val remainingItems = if (totalItems > 0) {
            (totalItems - doneItems).coerceAtLeast(0)
        } else {
            // 没有汇总时：排队 + 在传就是"还没做完的"
            (state.pending + activeUp).coerceAtLeast(0)
        }
        return SectionProgress(
            kind = SectionKind.UPLOAD,
            doneItems = doneItems,
            totalItems = totalItems,
            doneBytes = doneBytes,
            totalBytes = totalBytes,
            speedBytesPerSecond = speedBytesPerSecond.coerceAtLeast(0L),
            remainingItems = remainingItems,
            remainingBytes = (totalBytes - doneBytes).coerceAtLeast(0L)
        )
    }

    /**
     * 下载区块的汇总。
     *
     * <p>发布端没有下载方向的汇总字段，所以只能按"已列出的行"累加：在传的按 [ProgressTask.sent]
     * 计、刚完成的按整份体积计、排队的算 0。行本身是帧里带过来的前几十项，
     * 因此这里的总量是"已列出部分"的意思 —— 界面上不写"共 N 项"这种承诺。
     */
    fun download(state: ProgressUiState, speedBytesPerSecond: Long): SectionProgress {
        val rows = state.downloads
        var doneBytes = 0L
        var totalBytes = 0L
        var doneItems = 0
        for (row in rows) {
            val size = row.size.coerceAtLeast(0L)
            totalBytes += size
            doneBytes += sentBytes(row, size)
            if (row.completed) {
                doneItems++
            }
        }
        return SectionProgress(
            kind = SectionKind.DOWNLOAD,
            doneItems = doneItems,
            totalItems = rows.size,
            doneBytes = doneBytes,
            totalBytes = totalBytes,
            speedBytesPerSecond = speedBytesPerSecond.coerceAtLeast(0L),
            remainingItems = rows.count { !it.completed },
            remainingBytes = (totalBytes - doneBytes).coerceAtLeast(0L)
        )
    }

    /** 一行已经传了多少字节。完成的按整份算，在传的夹在 [0, size]，排队的 0 */
    private fun sentBytes(row: ProgressTask, size: Long): Long {
        if (row.completed) {
            return size
        }
        val sent = row.sent ?: return 0L
        return if (size > 0L) sent.coerceIn(0L, size) else sent.coerceAtLeast(0L)
    }

    /**
     * 列表折叠。
     *
     * <p>默认只给"进行中的前 [limit] 条 + 最近完成的 [recentDone] 条"：用户打开进度页
     * 要看的是"谁在动"，不是那张几十项的排队表。排队项不占这份预算，想看得点开。
     *
     * <p>一条进行中也没有时（全是排队、或全是刚完成）退回"前 [limit] 条" ——
     * 否则会折叠成一片空白加一个"还剩 20 项"，比不折还糟。
     */
    fun fold(
        rows: List<ProgressTask>,
        expanded: Boolean,
        limit: Int = COLLAPSE_LIMIT,
        recentDone: Int = RECENT_COMPLETED
    ): RowFold {
        val cap = limit.coerceAtLeast(1)
        if (rows.size <= cap) {
            return RowFold(rows, hidden = 0, collapsible = false, expanded = expanded)
        }
        if (expanded) {
            return RowFold(rows, hidden = 0, collapsible = true, expanded = true)
        }
        val picked = LinkedHashSet<Int>(cap * 2)
        var running = 0
        for (i in rows.indices) {
            if (running >= cap) {
                break
            }
            val row = rows[i]
            if (row.sent != null && !row.completed) {
                picked.add(i)
                running++
            }
        }
        val recentCap = recentDone.coerceAtLeast(0)
        var done = 0
        for (i in rows.indices) {
            if (done >= recentCap) {
                break
            }
            if (rows[i].completed) {
                picked.add(i)
                done++
            }
        }
        if (picked.isEmpty()) {
            for (i in 0 until cap) {
                picked.add(i)
            }
        } else if (picked.size < cap) {
            // 空位先用排队项补齐：只有 2 项在传时，剩下一半屏幕空着加一句"还剩 10 项"
            // 既不像列表，也让人以为界面没加载完
            for (i in rows.indices) {
                if (picked.size >= cap) {
                    break
                }
                val row = rows[i]
                if (row.sent == null && !row.completed) {
                    picked.add(i)
                }
            }
            // 排队项也不够（例如全是刚完成的）就按原顺序任意补
            for (i in rows.indices) {
                if (picked.size >= cap) {
                    break
                }
                picked.add(i)
            }
        }
        // 按原始顺序输出：折叠只该"少显示几行"，不该让剩下的行换位置
        val visible = rows.filterIndexed { index, _ -> index in picked }
        return RowFold(visible, rows.size - visible.size, collapsible = true, expanded = false)
    }

    /**
     * 剩余秒数（向上取整）。
     *
     * <p>速度未知或没有剩余字节时返回 null：前者界面该留空（不要写"0 秒"），
     * 后者是"活干完了"，那一行显示的是"全部完成"。向上取整是为了不出现"剩余 0 秒"
     * 却还在传的观感 —— 剩 1 字节也要说 1 秒。
     */
    fun remainingSeconds(remainingBytes: Long, bytesPerSecond: Long): Long? {
        if (remainingBytes <= 0L || bytesPerSecond <= 0L) {
            return null
        }
        // 整数向上取整：直接用 (n + d - 1) / d，不引入浮点，也不会因为乘法溢出
        val seconds = (remainingBytes + bytesPerSecond - 1L) / bytesPerSecond
        return seconds.coerceAtLeast(1L)
    }

    /**
     * 一帧里某个方向的"累计已传字节"，也就是速度窗口的采样值。
     *
     * <p>只算一个方向：上传和下载抢同一条链路，混在一起算出来的数字既不是上传速度
     * 也不是下载速度。`sent > size` 时按 size 夹一下 —— 重传会让字节数短暂超出去，
     * 不夹的话速度窗口里会出现一个荒唐的尖峰。
     */
    fun aggregateBytes(items: List<ActiveUpload>, down: Boolean): Long {
        var bytes = 0L
        for (item in items) {
            if ((item.dir == ProgressProtocol.DIR_DOWN) != down) {
                continue
            }
            val sent = item.sent.coerceAtLeast(0L)
            bytes += if (item.size > 0L) sent.coerceAtMost(item.size) else sent
        }
        return bytes
    }

    /**
     * 这一帧的速度窗口。
     *
     * <p>没有在传的项就把窗口丢掉：否则下次开传时，新采样会和几小时前的旧采样相减。
     */
    fun nextWindow(
        previous: List<SpeedSample>,
        frameBytes: Long,
        activeCount: Int,
        nowMs: Long
    ): List<SpeedSample> {
        return if (activeCount <= 0) {
            emptyList()
        } else {
            DownloadSpeed.append(previous, SpeedSample(nowMs, frameBytes))
        }
    }

    /**
     * 逐项速度：给每个在传项维护一条自己的采样窗口。
     *
     * <p>聚合速度回答"这一块整体多快"，行内速度回答"这一项多快"，两者不能互相顶替 ——
     * 3 路并行时看到 6 MB/s 却不知道是哪一项在拖后腿，就是只显示聚合的后果。
     * 不在这一帧里的项直接丢掉：项结束后它的历史没有意义，留着只是内存泄漏。
     */
    fun trackTaskSpeeds(
        previous: TaskSpeeds,
        active: List<ActiveUpload>,
        nowMs: Long
    ): TaskSpeeds {
        if (active.isEmpty()) {
            return TaskSpeeds.EMPTY
        }
        val windows = HashMap<String, List<SpeedSample>>(active.size * 2)
        for (item in active) {
            val sent = item.sent.coerceAtLeast(0L)
            val bytes = if (item.size > 0L) sent.coerceAtMost(item.size) else sent
            windows[item.key] = DownloadSpeed.append(
                previous.windows[item.key] ?: emptyList(),
                SpeedSample(nowMs, bytes)
            )
        }
        return TaskSpeeds(windows)
    }

    /** 速度文案。与方向无关：整体、逐项、上传、下载共用同一套单位 */
    fun formatRate(bytesPerSecond: Long): String = DownloadSpeed.format(bytesPerSecond)

    /**
     * 两个区块的高度份额。
     *
     * <p>默认对半：两块结构完全一样，谁高谁低会让人以为下面那块是次要的。
     * 但一侧完全空着时把它的份额让出去 —— 半屏只有一句"暂无"太浪费，
     * 另一侧的行又得挤在剩下的一半里滚动。
     */
    fun blockWeights(uploadRows: Int, downloadRows: Int): Pair<Float, Float> {
        val upEmpty = uploadRows <= 0
        val downEmpty = downloadRows <= 0
        return when {
            upEmpty && downEmpty -> 1f to 1f
            upEmpty -> 0.7f to 1.3f
            downEmpty -> 1.3f to 0.7f
            else -> 1f to 1f
        }
    }
}
