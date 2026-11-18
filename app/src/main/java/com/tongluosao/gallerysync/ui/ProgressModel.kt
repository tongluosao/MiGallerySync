package com.tongluosao.gallerysync.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tongluosao.gallerysync.core.Logger
import com.tongluosao.gallerysync.core.ProgressProtocol
import org.json.JSONObject

/**
 * 刚完成的下载项在列表里留多久。
 *
 * <p>自动下载的单张原图在局域网上可能几百毫秒就传完，2Hz 的进度帧本来就跟不上它；
 * 3 分钟足够"打开进度页 → 切个后台再回来"看到这一轮下载过什么，又不会把几小时前的
 * 旧记录一直顶着"刚完成"。
 */
private const val DONE_RETENTION_MS = 3 * 60_000L

/** 下载栏里最多留几行"刚完成"：再多只会把正在传的行挤出屏幕 */
private const val DONE_MAX = 12

/** 一帧里的一项正在传的任务。下载方向的项除了 [dir] 之外与上传项同构 */
data class ActiveUpload(
    val key: String,
    val name: String,
    val path: String,
    val size: Long,
    val sent: Long,
    val video: Boolean,
    val dir: String = ProgressProtocol.DIR_UP
)

/** 队列里一项还没轮到的任务 */
data class QueuedUpload(
    val key: String,
    val name: String,
    val path: String,
    val size: Long,
    val video: Boolean,
    val dir: String = ProgressProtocol.DIR_UP
)

/**
 * 任务列表里的一行。
 *
 * [sent] 非空表示"这一项正在传"（上传或下载，看 [dir]）；为空表示还在排队。
 * 列表顺序由发送端给（`pl` 是整个队列按插入序的前几十项，含在传项），所以某一项开始
 * 传输时它只是**原地**多出一条进度条，不会从排队位置跳到最前面。
 */
data class ProgressTask(
    val key: String,
    val name: String,
    val path: String,
    val size: Long,
    val video: Boolean,
    val sent: Long?,
    val dir: String,
    /**
     * 刚刚完成、暂时留在列表里的一行（只有下载方向会用）。
     *
     * <p>自动下载的单项可能只活几百毫秒：2Hz 的帧刚把它带上来，下一帧它就已经被
     * `taskEnd` 摘掉了。不留一段"已完成"，用户一抬头就什么都看不到了。
     */
    val completed: Boolean = false
) {
    val uploading: Boolean get() = sent != null && !completed

    val downloading: Boolean get() = dir == ProgressProtocol.DIR_DOWN

    /** 进度条用。size 为 0 时按 0 处理，别除出 NaN */
    val fraction: Float =
        if (completed) 1f
        else if (sent == null || size <= 0L) 0f
        else (sent.toDouble() / size).coerceIn(0.0, 1.0).toFloat()

    /** 右侧百分比。未在传时为 null，那时显示体积 */
    val percent: Int? =
        if (completed) 100
        else if (sent == null) null
        else (fraction * 100f).toInt()
}

/**
 * 一帧进度。
 *
 * 默认值是"从未收到过"，用 [timestamp] 是否为 0 区分 —— 队列真的空着时也会收到帧
 * （只是 active 为空），所以不能用 active 是否为空来判有没有连上。
 */
data class ProgressUiState(
    val state: String = ProgressProtocol.STATE_IDLE,
    val active: List<ActiveUpload> = emptyList(),
    val queued: List<QueuedUpload> = emptyList(),
    val tasks: List<ProgressTask> = emptyList(),
    /** 刚完成、还留在下载栏里的项（时间倒序，最新的在前） */
    val completed: List<ProgressTask> = emptyList(),
    /** 下载栏里有几项正在传（排队与刚完成都不算） */
    val activeDownloads: Int = 0,
    /**
     * 下载会话的累计计数（见 [DownloadSessionCounter]）：总任务只增不减、完成数不超过它。
     *
     * <p>帧里没有 `d*` 字段（相册进程还是旧版本）时保持默认值，进度页会退回按行累加。
     */
    val downloadSession: DownloadSessionState = DownloadSessionState.EMPTY,
    val pending: Int = 0,
    val pendingPhotos: Int = 0,
    val pendingVideos: Int = 0,
    val doneItems: Int = 0,
    val totalItems: Int = 0,
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val timestamp: Long = 0L
) {
    val received: Boolean get() = timestamp > 0L
    val uploading: Boolean get() = state == ProgressProtocol.STATE_UPLOADING
    val unhealthy: Boolean get() = state == ProgressProtocol.STATE_UNHEALTHY

    /** 总进度条用。总量为 0 时按 0 处理 */
    val fraction: Float =
        if (totalBytes <= 0L) 0f
        else (doneBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()

    /**
     * 队列里没被列出来的还有多少（一帧只带前几十项，剩下的靠这个数字交代）。
     *
     * `pending` 是"还没轮到的项数"（不含在传），所以要加上在传的几项才是队列总量。
     */
    val queuedHidden: Int get() = (pending + active.size - tasks.size).coerceAtLeast(0)

    /**
     * 下载栏的内容：在传 → 刚完成 → 排队。
     *
     * <p>"刚完成"的键要从排队部分里剔掉：发布端的排队列表含在传项（客户端按 key 去重），
     * 一项刚传完时它可能还留在上一份 `pl` 里，不去重就会出现两行同 key —— LazyColumn 会直接崩。
     */
    val downloads: List<ProgressTask>
        get() {
            val doneKeys = completed.mapTo(HashSet(completed.size * 2)) { it.key }
            val activeDown = tasks.filter { it.downloading && it.uploading }
            val queuedDown = tasks.filter {
                it.downloading && !it.uploading && it.key !in doneKeys
            }
            return activeDown + completed + queuedDown
        }

    /** 下载以外（上传）的行 */
    val uploads: List<ProgressTask> get() = tasks.filter { !it.downloading }
}

/**
 * 进度页的状态。数据来自相册进程发来的定向广播（见 [ProgressProtocol] 的注释：
 * libxposed 没有推送通道，只能自建）。
 *
 * 注册挂在 Activity 的 onStart/onStop 而不是页内的 DisposableEffect：切到「设置」页时
 * 进度页离屏，若 receiver 跟着页销毁，切回来数字会跳。
 */
class ProgressModel {

    var ui by mutableStateOf(ProgressUiState())
        private set

    /** 曾经收到过帧。用来区分"相册没在备份"和"根本没连上" */
    var everReceived by mutableStateOf(false)
        private set

    /**
     * 读相册媒体的权限是否齐了。缺了不致命：进度照常走，只是缩略图换成占位底。
     *
     * <p>Android 13 起媒体读取拆成图片/视频两项，而且用户可能只给一项，
     * 所以每次回前台都重新问一遍真实状态，不缓存"用户点过授权了"这种结论。
     */
    var mediaPermission by mutableStateOf(false)
        private set

    /**
     * 整体下载速度的采样窗口（最近 4 秒的总已下载字节）。
     *
     * <p>放在模型里而不是帧里：速度是"帧与帧之间"的量，发布端 2Hz 只报累计字节，
     * 客户端按收到的时刻自己滑窗。收到非下载帧时清空 —— 队列空掉后旧窗口没有意义。
     */
    var speedSamples by mutableStateOf(emptyList<SpeedSample>())
        private set

    /**
     * 整体上传速度的采样窗口。与 [speedSamples] 同构、方向相反。
     *
     * <p>分成两条窗口而不是一条：上传和下载抢同一条链路，混着算出来的数字谁也不代表。
     */
    var uploadSpeedSamples by mutableStateOf(emptyList<SpeedSample>())
        private set

    /**
     * 逐项速度（行内那一行"1.2 MB/s"）。
     *
     * <p>每项一条自己的窗口：聚合速度只回答"这一块整体多快"，看不出是哪一项在拖后腿。
     * 只保留这一帧还在传的项，项结束了就丢。
     */
    var taskSpeeds by mutableStateOf(TaskSpeeds.EMPTY)
        private set

    /**
     * 已经不在活动集合里、但还留在下载栏的项（按完成时刻倒序）。
     *
     * <p>键是发布端给的去重键：同一项完成后再被重排（失败重试）时先按它清掉旧行，
     * 免得列表里同时出现"已完成"和"排队中"两条同 key。
     */
    private val finishedDown = ArrayList<ProgressTask>()

    /**
     * 下载会话计数。跨帧有状态，所以挂在模型上而不是帧里：
     * 帧只给"本帧的累计量"，单调性与重置时机由 [DownloadSessionCounter] 统一管。
     */
    private val downloadSessionCounter = DownloadSessionCounter()

    private var lastDownCount = 0

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ProgressProtocol.ACTION) {
                return
            }
            val payload = intent.getStringExtra(ProgressProtocol.EXTRA_PAYLOAD) ?: return
            val frame = parse(payload) ?: return
            val previous = ui.state
            ui = frame
            if (!everReceived) {
                everReceived = true
                Logger.i("event=progress_first_frame state=${frame.state}")
            }
            // 状态切换才记，帧本身 2Hz 一帧，每帧都记会把日志刷满
            if (previous != frame.state) {
                Logger.i("event=progress_state state=${frame.state} active=${frame.active.size}")
            }
            // 下载项进出活动集合时记一行：现场排查"进度页里有没有下载行"只看这一处
            if (frame.activeDownloads != lastDownCount) {
                lastDownCount = frame.activeDownloads
                val speed = DownloadSpeed.bytesPerSecond(
                    speedSamples,
                    System.currentTimeMillis()
                )
                Logger.i(
                    "event=progress_download_active active=${frame.activeDownloads}" +
                        " shown=${frame.downloads.size} speed=${DownloadSpeed.format(speed)}"
                )
            }
        }
    }

    fun attach(context: Context) {
        if (registered) {
            return
        }
        val filter = IntentFilter(ProgressProtocol.ACTION)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 必须 EXPORTED：发送方是相册进程（另一个 App 的 UID），而动态注册的
                // receiver 从 Android 13 起默认不导出，不导出就一个字节也收不到
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            registered = true
            Logger.i("event=progress_attach")
        } catch (t: Throwable) {
            Logger.w("event=progress_attach_failed detail=$t")
        }
    }

    fun detach(context: Context) {
        if (!registered) {
            return
        }
        registered = false
        try {
            context.unregisterReceiver(receiver)
            Logger.i("event=progress_detach")
        } catch (t: Throwable) {
            Logger.w("event=progress_detach_failed detail=$t")
        }
    }

    /** 重新判定读媒体的权限。回到前台、以及授权对话框返回后都要调一次 */
    fun refreshMediaPermission(context: Context) {
        mediaPermission = missingMediaPermissions(context).isEmpty()
    }

    /** 还缺哪些权限。为空表示齐了，不用弹对话框 */
    fun missingMediaPermissions(context: Context): List<String> {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 13 起拆成两项，用户可能只给一半 —— 两个都要
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return required.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 解析一帧。字段缺失一律走默认值：通道是单向的，宁可显示一个保守的数字，
     * 也不要因为某个字段没对上就整页空白。
     */
    private fun parse(payload: String): ProgressUiState? {
        return try {
            val root = JSONObject(payload)
            val version = root.optInt("v", 0)
            if (version != ProgressProtocol.VERSION) {
                Logger.w(
                    "event=progress_bad_version got=$version want=${ProgressProtocol.VERSION}"
                )
                return null
            }
            val array = root.optJSONArray("active")
            val active = ArrayList<ActiveUpload>(array?.length() ?: 0)
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val key = item.optString("k")
                    if (key.isEmpty()) {
                        continue
                    }
                    active.add(
                        ActiveUpload(
                            key = key,
                            name = item.optString("n"),
                            path = item.optString("p"),
                            size = item.optLong("s", 0L),
                            sent = item.optLong("b", 0L),
                            video = item.optBoolean("vid", false),
                            // 缺省按上传：下载帧是后加的字段，老版本相册进程发来的帧没有它
                            dir = item.optString("dir", ProgressProtocol.DIR_UP)
                        )
                    )
                }
            }
            // 排队列表只在队列增删过、或隔几秒兜底时才带过来，没带就沿用上一帧的：
            // 整张列表十几 KB，2Hz 地传没必要
            val queuedArray = root.optJSONArray("pl")
            val queued = if (queuedArray != null) {
                ArrayList<QueuedUpload>(queuedArray.length()).also { list ->
                    for (i in 0 until queuedArray.length()) {
                        val item = queuedArray.optJSONObject(i) ?: continue
                        val key = item.optString("k")
                        if (key.isEmpty()) {
                            continue
                        }
                        list.add(
                            QueuedUpload(
                                key = key,
                                name = item.optString("n"),
                                path = item.optString("p"),
                                size = item.optLong("s", 0L),
                                video = item.optBoolean("vid", false),
                                dir = item.optString("dir", ProgressProtocol.DIR_UP)
                            )
                        )
                    }
                }
            } else {
                ui.queued
            }
            val tasks = buildTasks(queued, active)
            val now = System.currentTimeMillis()
            recordFinished(active, now)
            updateSpeedSamples(active, now)
            taskSpeeds = ProgressSections.trackTaskSpeeds(taskSpeeds, active, now)
            // 下载会话计数：帧里的 `d*` 是发布端累加的"本次会话累计计划量/完成量"，
            // 这里只负责"只增不减 + 明确结束才重置"，不让渲染层再算一遍
            val downloadSession = downloadSessionCounter.advance(
                DownloadSessionFrame(
                    plannedItems = root.optInt(ProgressProtocol.DOWN_PLANNED_ITEMS, 0),
                    plannedBytes = root.optLong(ProgressProtocol.DOWN_PLANNED_BYTES, 0L),
                    doneItems = root.optInt(ProgressProtocol.DOWN_DONE_ITEMS, 0),
                    doneBytes = root.optLong(ProgressProtocol.DOWN_DONE_BYTES, 0L),
                    activeBytes = root.optLong(ProgressProtocol.DOWN_ACTIVE_BYTES, 0L),
                    ended = root.optBoolean(ProgressProtocol.DOWN_ENDED, false)
                )
            )
            ProgressUiState(
                state = root.optString("state", ProgressProtocol.STATE_IDLE),
                active = active,
                queued = queued,
                tasks = tasks,
                completed = ArrayList(finishedDown),
                activeDownloads = tasks.count { it.downloading && it.uploading },
                downloadSession = downloadSession,
                pending = root.optInt("pending", 0),
                pendingPhotos = root.optInt("ph", 0),
                pendingVideos = root.optInt("pv", 0),
                doneItems = root.optInt("sDoneItems", 0),
                totalItems = root.optInt("sTotalItems", 0),
                doneBytes = root.optLong("sDoneBytes", 0L),
                totalBytes = root.optLong("sTotalBytes", 0L),
                timestamp = root.optLong("ts", 0L)
            )
        } catch (t: Throwable) {
            Logger.w("event=progress_parse_failed detail=$t")
            null
        }
    }

    /**
     * 把"上一帧还在传、这一帧没了"的下载项记成刚完成。
     *
     * <p>发布端在 `taskEnd` 里就把项从活动集合摘掉，成功、失败、取消一视同仁 ——
     * 对进度页来说三者都是"不再动了"，所以这里不区分，只保证这一行不会凭空消失。
     * 同一项如果重新排队（失败重试），[recordFinished] 会先把旧的完成行删掉。
     */
    private fun recordFinished(active: List<ActiveUpload>, now: Long) {
        val stillActive = HashSet<String>(active.size * 2)
        for (item in active) {
            if (item.dir == ProgressProtocol.DIR_DOWN) {
                stillActive.add(item.key)
            }
        }
        // 重新开始的项：把旧的"已完成"行换成新的进行中行，避免同 key 两行
        finishedDown.removeAll { stillActive.contains(it.key) }
        for (item in ui.active) {
            if (item.dir != ProgressProtocol.DIR_DOWN) {
                continue
            }
            if (stillActive.contains(item.key) || item.size <= 0L) {
                continue
            }
            finishedDown.add(
                0,
                ProgressTask(
                    key = item.key,
                    name = item.name,
                    path = item.path,
                    size = item.size,
                    video = item.video,
                    sent = item.size,
                    dir = ProgressProtocol.DIR_DOWN,
                    completed = true
                )
            )
            finishTimes[item.key] = now
        }
        val expired = finishTimes.filterValues { now - it > DONE_RETENTION_MS }.keys
        if (expired.isNotEmpty()) {
            finishedDown.removeAll { it.key in expired }
            finishTimes.keys.removeAll(expired)
        }
        while (finishedDown.size > DONE_MAX) {
            val removed = finishedDown.removeAt(finishedDown.size - 1)
            finishTimes.remove(removed.key)
        }
    }

    /** 完成行的时刻没有单独字段：按 key 记在模型里，用来做保留期裁剪 */
    private val finishTimes = HashMap<String, Long>()

    /**
     * 用这一帧的活动项更新两条速度窗口。
     *
     * <p>上传、下载各算各的：两个方向抢同一条链路，混在一起算出来的既不是上传速度
     * 也不是下载速度。某个方向没有在传的项时直接清空它那条窗口 —— 否则下次开传，
     * 第一帧会和几小时前的旧采样相减。
     */
    private fun updateSpeedSamples(active: List<ActiveUpload>, now: Long) {
        val uploadCount = active.count { it.dir != ProgressProtocol.DIR_DOWN }
        uploadSpeedSamples = ProgressSections.nextWindow(
            uploadSpeedSamples,
            ProgressSections.aggregateBytes(active, down = false),
            uploadCount,
            now
        )
        val downCount = active.count { it.dir == ProgressProtocol.DIR_DOWN }
        speedSamples = ProgressSections.nextWindow(
            speedSamples,
            ProgressSections.aggregateBytes(active, down = true),
            downCount,
            now
        )
    }

    /**
     * 把"谁在传"与"队列顺序"合成一张任务列表。
     *
     * 顺序是**在传在前、排队在后**（普通列表的读法：一眼就能看到正在动的那几项，不用往下滚）。
     * 在传的项即使这一帧没带 `pl`（`pl` 只在队列增删过、或隔几秒兜底时才发）也必须出现在列表里，
     * 所以先铺 active 再补 queued，不依赖 `pl` 是否到达。
     *
     * 排队部分用的是 `pl` 的顺序（整个队列按插入序的前几十项）。同一项在两边都有时以 active 为准：
     * key 重复会让 `LazyColumn` 直接抛异常。
     */
    private fun buildTasks(queued: List<QueuedUpload>, active: List<ActiveUpload>): List<ProgressTask> {
        val tasks = ArrayList<ProgressTask>(active.size + queued.size)
        val activeKeys = HashSet<String>(active.size * 2)
        for (item in active) {
            activeKeys.add(item.key)
            tasks.add(
                ProgressTask(
                    key = item.key,
                    name = item.name,
                    path = item.path,
                    size = item.size,
                    video = item.video,
                    sent = item.sent,
                    dir = item.dir
                )
            )
        }
        for (item in queued) {
            if (!activeKeys.add(item.key)) {
                continue
            }
            tasks.add(
                ProgressTask(
                    key = item.key,
                    name = item.name,
                    path = item.path,
                    size = item.size,
                    video = item.video,
                    sent = null,
                    dir = item.dir
                )
            )
        }
        return tasks
    }
}
