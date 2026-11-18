package com.tongluosao.gallerysync.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.tongluosao.gallerysync.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

private const val TAB_UPLOAD_DOWNLOAD = 0
private const val TAB_SLIM = 1
private const val TAB_SETTINGS = 2

/** 设置页没有打开二级页 */
private const val NO_PAGE = -1

/**
 * 模块的主界面：底栏在「设置」「上传下载」「空间优化」之间切换。
 *
 * 「设置」既是第一位也是落地页 —— 从相册设置页跳进来的人多半是来改配置的，先给进度页等于每次都要多点一下。
 *
 * 主题控制器、顶栏与底栏都提到这一层，页面只交出滚动区：放在页里的话每次切页都会
 * 重建底栏，能看见它闪一下，而且滚动位置与顶栏的收缩状态也会丢。
 *
 * 设置页是「导航主页 + 二级页」两级结构：主页只有四个条目，点进哪一条由这里的
 * [pageIndex] 决定。二级页占用同一个 Scaffold —— 顶栏换成页名加返回，底栏收起，
 * 这样从二级页返回时列表的滚动位置还在。
 *
 * 空间优化的进度由外面（[SettingsActivity]）注册的 [SlimModel] 提供：底栏进来时也得能实时收到帧，
 * 所以 receiver 不能只挂在 SlimActivity 上。
 *
 * 页签与二级页都用 rememberSaveable：转屏、以及从相册跳进来时进程被回收后重建，
 * 都该停在原来那一页。
 */
@Composable
fun MainScreen(
    settings: SettingsModel,
    progress: ProgressModel,
    slim: SlimState,
    onRequestMediaPermission: () -> Unit,
    onSlimStart: (Int) -> Unit,
    onSlimStop: () -> Unit
) {
    val schemeMode = when (settings.themeModeIndex) {
        SettingsModel.THEME_LIGHT -> ColorSchemeMode.Light
        SettingsModel.THEME_DARK -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }
    val controller = remember(schemeMode) { ThemeController(colorSchemeMode = schemeMode) }
    MiuixTheme(controller = controller) {
        var tab by rememberSaveable { mutableIntStateOf(TAB_SETTINGS) }
        var pageIndex by rememberSaveable { mutableIntStateOf(NO_PAGE) }
        // 只认识设置页的二级页：切到别的页签时 pageIndex 留着，回来还在原来那一页
        val detail = if (tab == TAB_SETTINGS) PAGES.getOrNull(pageIndex) else null
        // 未连接时顶部横幅已经在说同一件事，副标题再重复一遍只是噪音
        val statusText = if (settings.frameworkName.isEmpty()) {
            ""
        } else {
            stringResource(
                R.string.framework_status_connected,
                settings.frameworkName,
                settings.frameworkVersion,
                settings.frameworkApi
            )
        }
        val scrollBehavior = MiuixScrollBehavior()
        Scaffold(
            topBar = {
                if (detail == null) {
                    SmallTopAppBar(
                        title = stringResource(R.string.app_name),
                        subtitle = statusText,
                        scrollBehavior = scrollBehavior
                    )
                } else {
                    SmallTopAppBar(
                        title = stringResource(pageTitle(detail)),
                        scrollBehavior = scrollBehavior,
                        navigationIcon = {
                            // 用 Miuix 的 IconButton 而不是自己拼 Box + clickable：
                            // 点击热区、按下反馈、无障碍角色都由组件给，与顶栏其它图标一致
                            IconButton(onClick = { pageIndex = NO_PAGE }) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = stringResource(R.string.action_back)
                                )
                            }
                        }
                    )
                }
            },
            bottomBar = {
                // 二级页里底栏收起：那一页只做一件事，底栏会让人误以为还能横着切走
                if (detail == null) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == TAB_SETTINGS,
                            onClick = { tab = TAB_SETTINGS },
                            icon = MiuixIcons.Settings,
                            label = stringResource(R.string.tab_settings)
                        )
                        NavigationBarItem(
                            selected = tab == TAB_UPLOAD_DOWNLOAD,
                            onClick = { tab = TAB_UPLOAD_DOWNLOAD },
                            icon = MiuixIcons.Backup,
                            label = stringResource(R.string.tab_upload_download)
                        )
                        NavigationBarItem(
                            selected = tab == TAB_SLIM,
                            onClick = { tab = TAB_SLIM },
                            icon = MiuixIcons.Delete,
                            label = stringResource(R.string.tab_slim)
                        )
                    }
                }
            }
        ) { innerPadding ->
            when (tab) {
                TAB_UPLOAD_DOWNLOAD -> ProgressContent(
                    progress,
                    innerPadding,
                    scrollBehavior,
                    onRequestMediaPermission
                )
                TAB_SLIM -> SlimContent(
                    state = slim,
                    innerPadding = innerPadding,
                    scrollBehavior = scrollBehavior,
                    onStart = onSlimStart,
                    onStop = onSlimStop
                )
                else -> SettingsContent(
                    model = settings,
                    innerPadding = innerPadding,
                    scrollBehavior = scrollBehavior,
                    page = detail,
                    onOpenPage = { page -> pageIndex = PAGES.indexOf(page) }
                )
            }
        }
        // 系统返回键：在二级页里先回主页，而不是直接退出整个界面
        BackHandler(enabled = detail != null) { pageIndex = NO_PAGE }
    }
}

/** 二级页的顶栏标题。标题放这里而不是数据里：数据要能在纯 JVM 单测里读，不能引用 R */
private fun pageTitle(page: SettingsPage): Int = when (page) {
    SettingsPage.SERVER -> R.string.page_server
    SettingsPage.SYNC -> R.string.page_sync
    SettingsPage.SPACE -> R.string.page_space
    SettingsPage.MAINTENANCE -> R.string.page_maintenance
}
