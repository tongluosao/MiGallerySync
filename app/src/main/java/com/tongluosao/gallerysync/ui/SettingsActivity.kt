package com.tongluosao.gallerysync.ui

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * 模块配置界面。全限定名必须保持不变：相册进程内的 UiMaskHook 用
 * SettingsActivity.class 直接构造 Intent 跳转过来。
 */
class SettingsActivity : ComponentActivity() {

    private val model = SettingsModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ApplySystemBarStyle(this, isDarkTheme())
            SettingsRoot(model)
        }
    }

    override fun onPause() {
        super.onPause()
        model.flushPending()
    }

    override fun onDestroy() {
        super.onDestroy()
        model.dispose()
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
