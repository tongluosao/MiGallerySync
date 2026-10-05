package com.tongluosao.gallerysync.ui

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tongluosao.gallerysync.App
import com.tongluosao.gallerysync.core.Config
import com.tongluosao.gallerysync.core.Logger
import com.tongluosao.gallerysync.core.WebDavClient
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.function.Consumer

/**
 * 设置界面的状态与持久化。所有写入都落到 RemotePreferences 的 gallerysync 组，
 * 类型必须与 [Config] 的读取方式一一对应，否则相册进程侧会读到默认值。
 */
class SettingsModel : CoroutineScope {

    override val coroutineContext = SupervisorJob() + Dispatchers.Main.immediate

    /** service 已绑定且 RemotePreferences 可用，此时写入才会真正落地 */
    var ready by mutableStateOf(false)
        private set

    var frameworkName by mutableStateOf("")
        private set
    var frameworkVersion by mutableStateOf("")
        private set
    var frameworkApi by mutableStateOf(0)
        private set

    var baseUrl by mutableStateOf("")
        private set
    var username by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set
    var remoteRoot by mutableStateOf("")
        private set
    var maxItemsText by mutableStateOf("")
        private set
    var replaceModeIndex by mutableStateOf(0)
        private set
    var wifiOnly by mutableStateOf(true)
        private set
    var uiMask by mutableStateOf(false)
        private set
    /** 远端目录分法，取值下标对应 [LAYOUT_VALUES] */
    var layoutIndex by mutableStateOf(0)
        private set
    /** 打开后 100% 即让出上传通道，落盘复核挪到后台线程 */
    var verifyAsync by mutableStateOf(Config.DEFAULT_VERIFY_ASYNC)
        private set
    /** 上传前先看远端有没有同名文件，有就跳过（不比对内容） */
    var skipExistingRemote by mutableStateOf(Config.DEFAULT_SKIP_EXISTING_REMOTE)
        private set
    /** 账本模式下标：0 = 与相册共用，1 = 按目标分账。对应 [FLAG_MODE_VALUES] */
    var localFlagModeIndex by mutableStateOf(0)
        private set
    /** 打开后把远端有、本地没有的文件下载回本地 */
    var downloadEnabled by mutableStateOf(Config.DEFAULT_DOWNLOAD_ENABLED)
        private set
    /** 并行路数，取值下标 = 路数 - 1，对应 [CONCURRENCY_ITEMS] */
    var concurrencyIndex by mutableStateOf(Config.DEFAULT_UPLOAD_CONCURRENCY - 1)
        private set
    var themeModeIndex by mutableStateOf(THEME_SYSTEM)
        private set

    /** 界面用它决定是否提示"云端功能失效"，MODE_VALUES 首位即 replace */
    val isReplaceMode: Boolean
        get() = replaceModeIndex == 0

    /** 全平铺：所有文件直接落在根目录下，不再建任何子目录 */
    val isFlatLayout: Boolean
        get() = LAYOUT_VALUES[layoutIndex] == Config.LAYOUT_FLAT

    var testState by mutableStateOf<TestState>(TestState.Idle)
        private set

    /** 写入失败（service 掉线或 binder 异常）时置真，下一次成功写入清零 */
    var writeFailed by mutableStateOf(false)
        private set

    private var prefs: SharedPreferences? = null
    private val pendingValues = HashMap<String, Any>()
    private val pendingJobs = HashMap<String, Job>()

    /** 参数可空：service 死亡时框架侧回调的就是 null */
    private val serviceListener = Consumer<XposedService?> { service -> launch { applyService(service) } }

    private val remoteListener = SharedPreferences.OnSharedPreferenceChangeListener { source, key ->
        launch { applyRemote(source, key) }
    }

    init {
        App.addServiceListener(serviceListener)
    }

    // region 界面回调

    fun onBaseUrlChange(value: String) {
        baseUrl = value
        scheduleWrite(Config.KEY_BASE_URL, value.trim())
    }

    fun onUsernameChange(value: String) {
        username = value
        scheduleWrite(Config.KEY_USERNAME, value.trim())
    }

    /** 密码不 trim：空格可能是口令的一部分 */
    fun onPasswordChange(value: String) {
        password = value
        scheduleWrite(Config.KEY_PASSWORD, value)
    }

    fun onRemoteRootChange(value: String) {
        remoteRoot = value
        scheduleWrite(Config.KEY_REMOTE_ROOT, value.trim().ifEmpty { Config.DEFAULT_REMOTE_ROOT })
    }

    /** 上限最小 1：0 会让 Hook 侧一个都入不了队，只能降级放行，填了等于没配置 */
    fun onMaxItemsChange(value: String) {
        val digits = value.filter { it.isDigit() }.take(5).trimStart('0')
        maxItemsText = digits
        scheduleWrite(Config.KEY_MAX_ITEMS_PER_SYNC, digits.toIntOrNull() ?: Config.DEFAULT_MAX_ITEMS_PER_SYNC)
    }

    fun onReplaceModeChange(index: Int) {
        replaceModeIndex = index.coerceIn(0, MODE_VALUES.lastIndex)
        writeNow(Config.KEY_REPLACE_MODE, MODE_VALUES[replaceModeIndex])
    }

    fun onWifiOnlyChange(checked: Boolean) {
        wifiOnly = checked
        writeNow(Config.KEY_WIFI_ONLY, checked)
    }

    fun onUiMaskChange(checked: Boolean) {
        uiMask = checked
        writeNow(Config.KEY_UI_MASK, checked)
    }

    fun onLayoutChange(index: Int) {
        layoutIndex = index.coerceIn(0, LAYOUT_VALUES.lastIndex)
        writeNow(Config.KEY_REMOTE_LAYOUT, LAYOUT_VALUES[layoutIndex])
    }

    fun onVerifyAsyncChange(checked: Boolean) {
        verifyAsync = checked
        writeNow(Config.KEY_VERIFY_ASYNC, checked)
    }

    fun onSkipExistingRemoteChange(checked: Boolean) {
        skipExistingRemote = checked
        writeNow(Config.KEY_SKIP_EXISTING_REMOTE, checked)
    }

    /** 落盘的是字符串（shared / per_target），不是下标 —— 详见 Config.KEY_LOCAL_FLAG_MODE */
    fun onLocalFlagModeChange(index: Int) {
        localFlagModeIndex = index.coerceIn(0, FLAG_MODE_VALUES.lastIndex)
        writeNow(Config.KEY_LOCAL_FLAG_MODE, FLAG_MODE_VALUES[localFlagModeIndex])
    }

    fun onDownloadEnabledChange(checked: Boolean) {
        downloadEnabled = checked
        writeNow(Config.KEY_DOWNLOAD_ENABLED, checked)
    }

    /** 下拉给的是下标，落盘的是真实的并行路数（1..MAX），落盘值与界面顺序必须一致 */
    fun onConcurrencyChange(index: Int) {
        concurrencyIndex = index.coerceIn(0, CONCURRENCY_ITEMS.lastIndex)
        writeNow(Config.KEY_UPLOAD_CONCURRENCY, concurrencyIndex + 1)
    }

    fun onThemeModeChange(index: Int) {
        themeModeIndex = index.coerceIn(0, 2)
        writeNow(KEY_THEME_MODE, themeModeIndex)
    }

    // endregion

    fun testConnection() {
        val url = baseUrl.trim()
        if (url.isEmpty()) {
            testState = TestState.NeedUrl
            return
        }
        if (testState is TestState.Running) {
            return
        }
        testState = TestState.Running
        val user = username.trim()
        val secret = password
        val root = remoteRoot.trim().ifEmpty { Config.DEFAULT_REMOTE_ROOT }
        val client = WebDavClient(url, user, secret)
        launch(Dispatchers.IO) {
            testState = try {
                val code = client.probe("/")
                if (code == 401 || code == 403) {
                    TestState.AuthFailed
                } else {
                    // http 目标在两个进程里都能通：客户端对明文走原生 socket，
                    // 不经平台 HTTP 栈，也就不受相册那份 network_security_config 的明文拦截
                    writeProbe(client, root)
                }
            } catch (t: Throwable) {
                // 详情进日志，界面只给一句话，避免把堆栈或类名甩给用户
                Logger.w("event=ui_test_failed target=" + Config.safeHost(url), t)
                TestState.Failure(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * 真实写入探测。只探可达性会误报"连接成功"：OpenList 挂的夸克能列目录也能建目录，
     * 但 PUT 文件一律 500（Direct put not supported in stateless environment），
     * 于是界面一片绿、相册一个文件都没传上去。按上传时同一条路径写一个文件再删掉，
     * 才是"这里到底收不收文件"的答案。
     */
    private fun writeProbe(client: WebDavClient, root: String): TestState {
        // 探的就是真正要写的那个目录：扁平模式是根目录，其余两种都是"根目录 + 两级子目录"
        // （按相册是 DCIM/Camera 这类，按时间是 年/月），深度一样，建目录那一步都能验到 ——
        // 只探根目录会漏掉"子目录建不出来"这类服务器。
        // 与上传共用 putBytes 的补建逻辑：写不进去时才在 404/409 之后 MKCOL 一次，绝不预先建。
        val dir = if (isFlatLayout) {
            WebDavClient.joinPath(root, "")
        } else {
            WebDavClient.joinPath(root, WebDavClient.monthDir(System.currentTimeMillis()))
        }
        val probe = WebDavClient.joinPath(dir, PROBE_NAME)
        return try {
            client.putBytes(probe, PROBE_NAME.toByteArray())
            TestState.Success
        } catch (t: Throwable) {
            Logger.w("event=ui_write_probe_failed", t)
            TestState.NotWritable(t.message ?: t.javaClass.simpleName)
        } finally {
            try {
                client.delete(probe)
            } catch (t: Throwable) {
                // 删不掉只是留了个探针文件，不影响"可写入"这个结论
                Logger.w("event=ui_probe_cleanup_failed", t)
            }
        }
    }

    /** 离开界面前把防抖中的输入全部落盘，避免最后一次编辑被吞掉 */
    fun flushPending() {
        if (pendingValues.isEmpty()) {
            return
        }
        val snapshot = HashMap(pendingValues)
        pendingValues.clear()
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
        snapshot.forEach { (key, value) -> writeNow(key, value) }
    }

    fun dispose() {
        flushPending()
        App.removeServiceListener(serviceListener)
        try {
            prefs?.unregisterOnSharedPreferenceChangeListener(remoteListener)
        } catch (t: Throwable) {
            Logger.w("event=remote_listener_unregister_failed", t)
        }
        prefs = null
        cancel()
    }

    // region 与框架的往返

    private fun applyService(service: XposedService?) {
        prefs?.let { old ->
            try {
                old.unregisterOnSharedPreferenceChangeListener(remoteListener)
            } catch (t: Throwable) {
                Logger.w("event=remote_listener_unregister_failed", t)
            }
        }
        prefs = null
        if (service == null) {
            ready = false
            frameworkName = ""
            return
        }
        frameworkName = service.frameworkName
        frameworkVersion = service.frameworkVersion
        frameworkApi = service.apiVersion
        val loaded = try {
            service.getRemotePreferences(Config.PREF_GROUP)
        } catch (t: Throwable) {
            Logger.w("event=remote_prefs_failed", t)
            null
        }
        if (loaded == null) {
            ready = false
            return
        }
        prefs = loaded
        // listener 必须是字段强引用，否则会被 GC 掉收不到远端变更
        loaded.registerOnSharedPreferenceChangeListener(remoteListener)
        ready = true
        loadAll(loaded)
    }

    private fun loadAll(source: SharedPreferences) {
        baseUrl = source.getString(Config.KEY_BASE_URL, "") ?: ""
        username = source.getString(Config.KEY_USERNAME, "") ?: ""
        password = source.getString(Config.KEY_PASSWORD, "") ?: ""
        remoteRoot = source.getString(Config.KEY_REMOTE_ROOT, Config.DEFAULT_REMOTE_ROOT) ?: ""
        wifiOnly = source.getBoolean(Config.KEY_WIFI_ONLY, true)
        uiMask = source.getBoolean(Config.KEY_UI_MASK, false)
        layoutIndex = layoutIndexOf(readLayout(source))
        verifyAsync = source.getBoolean(Config.KEY_VERIFY_ASYNC, Config.DEFAULT_VERIFY_ASYNC)
        skipExistingRemote = source.getBoolean(
            Config.KEY_SKIP_EXISTING_REMOTE, Config.DEFAULT_SKIP_EXISTING_REMOTE
        )
        localFlagModeIndex = flagModeIndexOf(
            source.getString(Config.KEY_LOCAL_FLAG_MODE, Config.DEFAULT_LOCAL_FLAG_MODE)
        )
        downloadEnabled = source.getBoolean(
            Config.KEY_DOWNLOAD_ENABLED, Config.DEFAULT_DOWNLOAD_ENABLED
        )
        concurrencyIndex = concurrencyIndexOf(
            source.getInt(Config.KEY_UPLOAD_CONCURRENCY, Config.DEFAULT_UPLOAD_CONCURRENCY)
        )
        maxItemsText = source.getInt(Config.KEY_MAX_ITEMS_PER_SYNC, Config.DEFAULT_MAX_ITEMS_PER_SYNC).toString()
        replaceModeIndex = modeIndexOf(source.getString(Config.KEY_REPLACE_MODE, Config.DEFAULT_REPLACE_MODE))
        themeModeIndex = source.getInt(KEY_THEME_MODE, THEME_SYSTEM).coerceIn(0, 2)
    }

    /** 外部（例如 LSPosed 里手改）变更时同步界面，但不覆盖正在输入中的项 */
    private fun applyRemote(source: SharedPreferences, key: String?) {
        if (key == null) {
            loadAll(source)
            return
        }
        if (pendingValues.containsKey(key)) {
            return
        }
        when (key) {
            Config.KEY_BASE_URL -> baseUrl = source.getString(key, "") ?: ""
            Config.KEY_USERNAME -> username = source.getString(key, "") ?: ""
            Config.KEY_PASSWORD -> password = source.getString(key, "") ?: ""
            Config.KEY_REMOTE_ROOT -> remoteRoot = source.getString(key, Config.DEFAULT_REMOTE_ROOT) ?: ""
            Config.KEY_WIFI_ONLY -> wifiOnly = source.getBoolean(key, true)
            Config.KEY_UI_MASK -> uiMask = source.getBoolean(key, false)
            Config.KEY_REMOTE_LAYOUT -> layoutIndex = layoutIndexOf(readLayout(source))
            Config.KEY_VERIFY_ASYNC -> verifyAsync =
                source.getBoolean(key, Config.DEFAULT_VERIFY_ASYNC)

            Config.KEY_SKIP_EXISTING_REMOTE -> skipExistingRemote =
                source.getBoolean(key, Config.DEFAULT_SKIP_EXISTING_REMOTE)

            Config.KEY_LOCAL_FLAG_MODE -> localFlagModeIndex = flagModeIndexOf(
                source.getString(key, Config.DEFAULT_LOCAL_FLAG_MODE)
            )

            Config.KEY_DOWNLOAD_ENABLED -> downloadEnabled =
                source.getBoolean(key, Config.DEFAULT_DOWNLOAD_ENABLED)

            Config.KEY_UPLOAD_CONCURRENCY -> concurrencyIndex = concurrencyIndexOf(
                source.getInt(key, Config.DEFAULT_UPLOAD_CONCURRENCY)
            )
            Config.KEY_MAX_ITEMS_PER_SYNC -> maxItemsText =
                source.getInt(key, Config.DEFAULT_MAX_ITEMS_PER_SYNC).toString()

            Config.KEY_REPLACE_MODE -> replaceModeIndex =
                modeIndexOf(source.getString(key, Config.DEFAULT_REPLACE_MODE))

            KEY_THEME_MODE -> themeModeIndex = source.getInt(key, THEME_SYSTEM).coerceIn(0, 2)
        }
    }

    private fun scheduleWrite(key: String, value: Any) {
        pendingValues[key] = value
        pendingJobs.remove(key)?.cancel()
        pendingJobs[key] = launch {
            delay(DEBOUNCE_MS)
            val latest = pendingValues.remove(key) ?: return@launch
            pendingJobs.remove(key)
            writeNow(key, latest)
        }
    }

    private fun writeNow(key: String, value: Any) {
        val target = prefs
        if (target == null) {
            writeFailed = true
            return
        }
        try {
            val editor = target.edit()
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
            }
            editor.apply()
            writeFailed = false
        } catch (t: Throwable) {
            writeFailed = true
            Logger.w("event=ui_write_failed key=$key", t)
        }
    }

    // endregion

    sealed interface TestState {
        data object Idle : TestState
        data object Running : TestState
        data object NeedUrl : TestState
        data object Success : TestState
        data object AuthFailed : TestState
        /** 连得上、认证过，但这个远端目录不接受写入（夸克这类挂载就是这种） */
        data class NotWritable(val detail: String) : TestState
        data class Failure(val detail: String) : TestState
    }

    companion object {
        /** 备份模式的落盘值，顺序必须与界面选项一致 */
        val MODE_VALUES = listOf(Config.MODE_REPLACE, Config.MODE_MIRROR, Config.MODE_OFF)

        /** 远端目录分法的落盘值，顺序必须与界面选项一致 */
        val LAYOUT_VALUES = listOf(Config.LAYOUT_ALBUM, Config.LAYOUT_DATE, Config.LAYOUT_FLAT)

        /**
         * 账本模式的落盘值，顺序必须与界面选项一致。
         *
         * <p>与别的下拉框不同，这里落盘的不是下标而是字符串 —— 认不出来的值在
         * [Config.readFlagMode] 里会退回默认（共用相册那本账），那是安全的一侧。
         */
        val FLAG_MODE_VALUES = listOf(Config.FLAG_MODE_SHARED, Config.FLAG_MODE_PER_TARGET)

        /** 并行路数的选项文案，下标 = 路数 - 1 */
        val CONCURRENCY_ITEMS = (1..Config.MAX_UPLOAD_CONCURRENCY).map { it.toString() }

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        /** 仅界面读取的键，[Config] 不认识它，Hook 侧收到变更事件只会重解析一次 */
        private const val KEY_THEME_MODE = "ui_theme_mode"
        private const val DEBOUNCE_MS = 500L
        private const val PROBE_NAME = ".migallerysync_probe"

        private fun modeIndexOf(value: String?): Int {
            val index = MODE_VALUES.indexOf(value)
            return if (index >= 0) index else 0
        }

        private fun layoutIndexOf(value: String?): Int {
            val index = LAYOUT_VALUES.indexOf(value)
            return if (index >= 0) index else 0
        }

        private fun flagModeIndexOf(value: String?): Int {
            val index = FLAG_MODE_VALUES.indexOf(value)
            return if (index >= 0) index else 0
        }

        /**
         * 路数换算成下标，读进来时同样夹一次。
         *
         * <p>与 [Config.clampConcurrency] 同源：老版本或手改的 XML 里可能是 0 或 99，
         * 界面必须显示成真正会生效的那个值，不能让用户以为自己设的是 99。
         */
        private fun concurrencyIndexOf(value: Int): Int {
            return Config.clampConcurrency(value) - 1
        }

        /**
         * 读分目录方式，与 [Config] 的兼容规则保持一致：老配置里只有 flat_path 布尔，
         * 它当初是 true 就说明用户要的是全平铺，别在升级后悄悄改成按相册分。
         */
        private fun readLayout(source: SharedPreferences): String {
            val layout = source.getString(Config.KEY_REMOTE_LAYOUT, null)
            if (!layout.isNullOrBlank()) {
                return layout
            }
            if (source.contains(Config.KEY_FLAT_PATH) && source.getBoolean(Config.KEY_FLAT_PATH, false)) {
                return Config.LAYOUT_FLAT
            }
            return Config.DEFAULT_REMOTE_LAYOUT
        }
    }
}
