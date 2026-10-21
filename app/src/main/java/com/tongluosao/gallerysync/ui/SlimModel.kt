package com.tongluosao.gallerysync.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.tongluosao.gallerysync.core.ProgressProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 「空间优化」进度的一帧。
 *
 * 与进度页的 [ProgressUiState] 一样，默认值表示"从未收到过"，用 [updatedAt] 是否为 0 区分 ——
 * 队列真的空着时模块也会继续上报（只是计数不再变），所以不能用 [total] 是否为 0 判有没有连上。
 */
data class SlimState(
    val running: Boolean = false,
    val state: String = SlimProtocol.STATE_IDLE,
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val saved: Long = 0L,
    val current: String? = null,
    val updatedAt: Long = 0L
) {

    /** 曾经收到过上报。用来区分"没在优化"和"相册根本没在跑" */
    val received: Boolean get() = updatedAt > 0L

    /** 已经点了停止、正在收尾。这期间仍然算在跑，但"停止"按钮要禁用，避免重复下发 */
    val stopping: Boolean get() = state == SlimProtocol.STATE_STOPPING

    /** 进度条用。总数为 0 时按 0 处理，别除出 NaN */
    val fraction: Float =
        if (total <= 0) 0f else (done.toDouble() / total).coerceIn(0.0, 1.0).toFloat()

    /** 右上角百分比。总数未知时为 null，那时进度条走不确定态、也不显示数字 */
    val percent: Int? = if (total <= 0) null else (fraction * 100f).toInt()
}

/**
 * 空间优化进度通道的线协议。
 *
 * libxposed 没有推送通道，进度只能由相册进程（模块装进去的那一侧）主动发定向广播，
 * 所以 action 名是把模块包名与「slim」后缀拼起来的**独占**名字：
 * 和备份进度的 [ProgressProtocol.ACTION] 分开，两个 receiver 才不会互相收到对方的数据。
 *
 * 上报载荷（JSON）：
 * ```
 * {"v":1,"state":"running","total":40,"done":12,"failed":1,
 *  "saved":1331691520,"current":"IMG_20260101_120000.heic","updatedAt":1760000000000}
 * ```
 * - 必填：`v`、`state`、`total`、`done`
 * - 选填：`failed`、`saved`、`cur`、`ts`（`running` 也接受，缺省由 `state` 推出来）
 *
 * 反方向的命令不走这里：[ProgressProtocol.ACTION_COMMAND] 是**已有的**命令通道，
 * 命令串与参数也复用它的常量（见 [SlimModel.requestStart]），别另造一套。
 */
object SlimProtocol {

    /** 载荷版本。对不上就整帧丢掉，宁可显示旧数字也不要读错字段 */
    const val VERSION = 1

    /** 模块 → 界面的进度广播 */
    const val ACTION = ProgressProtocol.ACTION + ".slim"

    /** 进度广播里的载荷字段名 */
    const val EXTRA = "payload"

    /** 本模块自己的包名 */
    const val MODULE_PACKAGE = "com.tongluosao.gallerysync"

    /** 相册包名。实际执行优化的进程，也是"用相册自带的优化"入口的目标 */
    const val GALLERY_PACKAGE = "com.miui.gallery"

    /** 相册自带的优化页面（由 SlimEntryHook 接管；我们只在逃生口里主动打开它） */
    const val NATIVE_SLIM_ACTIVITY = "com.miui.gallery.activity.PhotoSlimActivity"

    /**
     * 逃生口 extra：名必须逐字是 `mgs_native_slim`。
     *
     * <p>和 `hook/SlimEntryHook.EXTRA_NATIVE_SLIM` 是同一个字符串 —— 那边见到它就放行，
     * 回退到相册自己的串行优化流程。
     */
    const val EXTRA_NATIVE_SLIM = "mgs_native_slim"

    /** 正在优化 */
    const val STATE_RUNNING = "running"

    /** 已收到停止命令、正在收尾 */
    const val STATE_STOPPING = "stopping"

    /** 本轮优化结束 */
    const val STATE_DONE = "done"

    /** 没在优化 */
    const val STATE_IDLE = "idle"

    const val KEY_VERSION = "v"
    const val KEY_STATE = "state"
    const val KEY_RUNNING = "running"
    const val KEY_TOTAL = "total"
    const val KEY_DONE = "done"
    const val KEY_FAILED = "failed"
    const val KEY_SAVED = "saved"
    const val KEY_CURRENT = "current"
    const val KEY_TIMESTAMP = "updatedAt"

    /** 认识的状态值。别的一律当坏帧 */
    fun isKnownState(state: String): Boolean =
        state == STATE_RUNNING || state == STATE_STOPPING ||
            state == STATE_DONE || state == STATE_IDLE
}

/**
 * 解析一帧上报。
 *
 * 与进度页那边"字段缺失走默认值"的策略不同：这里必填字段缺一个就返回 null。
 * 空间优化是**会真的删原图**的操作，界面上的"已完成 12 / 40"要能对得上账；
 * 一帧残帧带来的错数字比空一帧更糟。
 */
fun parse(payload: String?): SlimState? {
    if (payload.isNullOrEmpty()) {
        return null
    }
    return try {
        val root = JSONObject(payload)
        if (root.optInt(SlimProtocol.KEY_VERSION, 0) != SlimProtocol.VERSION) {
            return null
        }
        if (!root.has(SlimProtocol.KEY_STATE) ||
            !root.has(SlimProtocol.KEY_TOTAL) ||
            !root.has(SlimProtocol.KEY_DONE)
        ) {
            return null
        }
        val state = root.optString(SlimProtocol.KEY_STATE, "")
        if (!SlimProtocol.isKnownState(state)) {
            return null
        }
        val total = root.optInt(SlimProtocol.KEY_TOTAL, 0).coerceAtLeast(0)
        val done = root.optInt(SlimProtocol.KEY_DONE, 0).coerceAtLeast(0)
        SlimState(
            // 老版本只发 state，没带 running；由 state 推出来，stopping 也算在跑
            running = root.optBoolean(
                SlimProtocol.KEY_RUNNING,
                state == SlimProtocol.STATE_RUNNING || state == SlimProtocol.STATE_STOPPING
            ),
            state = state,
            total = total,
            done = done,
            failed = root.optInt(SlimProtocol.KEY_FAILED, 0).coerceAtLeast(0),
            saved = root.optLong(SlimProtocol.KEY_SAVED, 0L).coerceAtLeast(0L),
            // 空文件名当"没有当前项"，省得界面显示一行空占位
            current = root.optString(SlimProtocol.KEY_CURRENT, "").takeIf { it.isNotEmpty() },
            // 没带时间戳就拿收到它的时刻：这一帧确实是现在到的，不能算成"从未上报"
            updatedAt = root.optLong(SlimProtocol.KEY_TIMESTAMP, System.currentTimeMillis())
        )
    } catch (t: Throwable) {
        // 载荷是另一个进程拼的，格式永远可能不对；解析失败就当没收到，不要冒泡到主线程
        null
    }
}

/**
 * 打开相册自带的「空间优化」页面（模块页面的逃生口）。
 *
 * 相册被禁用、卸载，或那个 Activity 不再导出时 startActivity 会抛，返回 false 让界面提示
 * "相册不可用"—— 这个入口只是保底的，失败不该把模块自己的页面一起弄崩。
 */
fun openNativeSlim(context: Context): Boolean {
    val intent = Intent()
        .setClassName(SlimProtocol.GALLERY_PACKAGE, SlimProtocol.NATIVE_SLIM_ACTIVITY)
        .putExtra(SlimProtocol.EXTRA_NATIVE_SLIM, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        false
    }
}

/**
 * 空间优化页的状态。
 *
 * 注册挂在 Activity 的 onStart/onStop（见 [SlimActivity]）而不是页内的 DisposableEffect：
 * 相册跳进来、切出去再回来时 receiver 不该跟着组合一起重建，否则中间那几帧会白丢。
 *
 * 用 [MutableStateFlow] 而不是 Compose 的 `mutableStateOf`：界面是独立 Activity，
 * 只有一个订阅者，Flow 的"当前值"语义正合适，也让 [SlimActivity] 用 `collectAsState` 一次拿全。
 */
class SlimModel {

    private val mutable = MutableStateFlow(SlimState())

    /** 当前帧。没收到过任何上报时是 [SlimState] 的默认值（`updatedAt == 0`） */
    val state: StateFlow<SlimState> = mutable.asStateFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != SlimProtocol.ACTION) {
                return
            }
            val frame = parse(intent.getStringExtra(SlimProtocol.EXTRA)) ?: return
            mutable.value = frame
        }
    }

    /** 开始收帧。重复调用是空操作（onStart 可能连着来两次） */
    fun start(context: Context) {
        if (registered) {
            return
        }
        val filter = IntentFilter(SlimProtocol.ACTION)
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
        } catch (t: Throwable) {
            // 注册失败不该让整页打不开：只是收不到进度，按钮仍然能下发命令
            registered = false
        }
    }

    /** 停止收帧。界面不可见后继续收广播只会白耗电 */
    fun stop(context: Context) {
        if (!registered) {
            return
        }
        registered = false
        try {
            context.unregisterReceiver(receiver)
        } catch (t: Throwable) {
            // 已经跟着进程一起没了，忽略
        }
    }

    /**
     * 请相册进程开始优化。
     *
     * 走的是**已有**的命令通道 [ProgressProtocol.ACTION_COMMAND]，命令串与参数也用它的常量；
     * 相册没在跑时这条广播没人接，属正常情况。
     */
    fun requestStart(context: Context, concurrency: Int) {
        val intent = Intent(ProgressProtocol.ACTION_COMMAND)
            .setPackage(ProgressProtocol.TARGET_PACKAGE)
            .putExtra(ProgressProtocol.EXTRA_COMMAND, ProgressProtocol.CMD_SLIM_START)
            .putExtra(ProgressProtocol.EXTRA_CONCURRENCY, concurrency)
        send(context, intent)
    }

    /** 请相册进程停止优化。已经停下的任务会自己回一帧 idle */
    fun requestStop(context: Context) {
        val intent = Intent(ProgressProtocol.ACTION_COMMAND)
            .setPackage(ProgressProtocol.TARGET_PACKAGE)
            .putExtra(ProgressProtocol.EXTRA_COMMAND, ProgressProtocol.CMD_SLIM_STOP)
        send(context, intent)
    }

    /**
     * 定向广播只在相册进程活着时有人接收；没接收者、或系统拦了隐性广播，都属正常，
     * 界面上靠"迟迟收不到新帧"自己就能看出来，这里不打扰用户。
     */
    private fun send(context: Context, intent: Intent) {
        try {
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            // 无接收者 / 权限变化，忽略
        }
    }
}
