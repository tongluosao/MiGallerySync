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
    val dir: String
) {
    val uploading: Boolean get() = sent != null

    val downloading: Boolean get() = dir == ProgressProtocol.DIR_DOWN

    /** 进度条用。size 为 0 时按 0 处理，别除出 NaN */
    val fraction: Float =
        if (sent == null || size <= 0L) 0f
        else (sent.toDouble() / size).coerceIn(0.0, 1.0).toFloat()

    /** 右侧百分比。未在传时为 null，那时显示体积 */
    val percent: Int? = if (sent == null) null else (fraction * 100f).toInt()
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
    val pending: Int = 0,
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
            ProgressUiState(
                state = root.optString("state", ProgressProtocol.STATE_IDLE),
                active = active,
                queued = queued,
                tasks = tasks,
                pending = root.optInt("pending", 0),
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
