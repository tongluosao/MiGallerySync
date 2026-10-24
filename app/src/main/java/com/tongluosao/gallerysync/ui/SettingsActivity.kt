package com.tongluosao.gallerysync.ui

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * 模块配置界面。全限定名必须保持不变：相册进程内的 UiMaskHook 用
 * SettingsActivity.class 直接构造 Intent 跳转过来。
 */
class SettingsActivity : ComponentActivity() {

    private val model = SettingsModel()

    /**
     * 进度页的状态。广播接收器挂在 onStart/onStop 而不是页内的 DisposableEffect：
     * 切到「设置」页时进度页离屏，若 receiver 跟着页销毁，切回来数字会跳一下。
     */
    private val progress = ProgressModel()

    /**
     * 空间优化的进度。
     *
     * 与 [progress] 同理挂在 onStart/onStop：底栏切到「空间优化」时这一页在主界面里，
     * receiver 若只注册在 SlimActivity，从底栏进来就永远收不到帧 —— 那正是这次要修的问题。
     */
    private val slim = SlimModel()

    /**
     * 媒体读取权限。用 launcher 而不是 ActivityCompat.requestPermissions：
     * 项目没引 androidx.core，这个回调形态也不需要额外的兼容层。
     */
    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // 用户可能只给了一半（图片给了、视频没给），所以刷新真实状态，
            // 而不是记一个"用户点过授权了"的结论
            progress.refreshMediaPermission(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ApplySystemBarStyle(this, isDarkTheme())
            val slimState by slim.state.collectAsState()
            MainScreen(
                settings = model,
                progress = progress,
                slim = slimState,
                onRequestMediaPermission = ::requestMediaPermission,
                onSlimStart = { concurrency -> slim.requestStart(this, concurrency) },
                onSlimStop = { slim.requestStop(this) }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        progress.attach(this)
        slim.start(this)
        // 授权对话框、以及去系统设置里改过权限，都可能改变结果，每次回前台都重新判定
        progress.refreshMediaPermission(this)
    }

    override fun onStop() {
        // 界面不可见就不再收帧：后台收广播只会白耗电，而且回到前台时数据本来就会刷新
        progress.detach(this)
        slim.stop(this)
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        model.flushPending()
    }

    override fun onDestroy() {
        super.onDestroy()
        model.dispose()
    }

    private fun requestMediaPermission() {
        val missing = progress.missingMediaPermissions(this)
        if (missing.isEmpty()) {
            // 权限其实已经齐了还点按钮（例如上一次拒绝后又去系统设置里给了），
            // 那就只是刷新一下状态
            progress.refreshMediaPermission(this)
            return
        }
        mediaPermissionLauncher.launch(missing.toTypedArray())
    }

    private fun isDarkTheme(): Boolean = when (model.themeModeIndex) {
        SettingsModel.THEME_LIGHT -> false
        SettingsModel.THEME_DARK -> true
        else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }
}

/** 状态栏与导航栏透明，交给 MiuixTheme 自己出前景色；深色切换时重挂一次 */
@Composable
private fun ApplySystemBarStyle(activity: ComponentActivity, dark: Boolean) {
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
