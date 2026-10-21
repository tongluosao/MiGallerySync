package com.tongluosao.gallerysync.ui

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 「空间优化」页的宿主。
 *
 * 独立 Activity 而不是并进主界面：这一页是模块**接管**相册那个原生优化入口后跳来的
 * （见 `hook/SlimEntryHook`），打开路径是"用户在相册里点了空间优化"，概念上属于相册，
 * 不该把主界面顶上的两个 tab 一起带出来。主题也不单独定义 —— 跟主界面一样让
 * [MiuixTheme] 按系统深浅色自己出配色，省一套容易和主界面不一致的方案。
 */
class SlimActivity : ComponentActivity() {

    private val model = SlimModel()

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            leave()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backCallback)
        setContent {
            ApplySlimSystemBarStyle(activity = this@SlimActivity, dark = isDarkTheme())
            MiuixTheme(
                controller = remember { ThemeController(colorSchemeMode = ColorSchemeMode.System) }
            ) {
                val state by model.state.collectAsState()
                SlimScreen(
                    state = state,
                    onStart = { concurrency -> model.requestStart(this@SlimActivity, concurrency) },
                    onStop = { model.requestStop(this@SlimActivity) },
                    onBack = { leave() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 收帧的注册挂在这里而不是页内的 DisposableEffect：从相册回来时组合会重建，
        // 但接收窗口不该跟着抖一下，否则停顿时那几帧正好丢掉
        model.start(this)
    }

    override fun onStop() {
        model.stop(this)
        super.onStop()
    }

    /**
     * 退出页面。
     *
     * 任务还在跑就先下发停止再 finish：优化是会**真删原图**的，用户按返回多半是想让它停，
     * 若只是关掉这个观察窗口、后台继续删，和他按下按钮时的预期正好相反。
     */
    private fun leave() {
        if (model.state.value.running) {
            model.requestStop(this)
        }
        finish()
    }

    private fun isDarkTheme(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
}

/** 状态栏与导航栏透明，交给 MiuixTheme 自己出前景色；与设置页保持同一套处理 */
@Composable
private fun ApplySlimSystemBarStyle(activity: ComponentActivity, dark: Boolean) {
    DisposableEffect(dark) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 不加这行，小米系统会在透明导航栏上盖一层浅色蒙版
            activity.window.isNavigationBarContrastEnforced = false
        }
        onDispose {}
    }
}
