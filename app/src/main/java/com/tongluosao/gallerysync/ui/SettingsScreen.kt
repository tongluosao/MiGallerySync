package com.tongluosao.gallerysync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tongluosao.gallerysync.R
import com.tongluosao.gallerysync.core.Config
import com.tongluosao.gallerysync.ui.SettingsModel.ResetState
import com.tongluosao.gallerysync.ui.SettingsModel.TestState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog

/** 摘要里主机名最多显示几个字符，再长就截断 —— 右端还要放下状态词 */
private const val HOST_MAX_CHARS = 11

/**
 * 设置页的滚动区。主题控制器、顶栏与底栏都由 [MainScreen] 持有，
 * 本页只负责列表本身，切页时才不会连底栏一起重建。
 *
 * <p>两级结构：[page] 为空时是导航主页（四个条目，右侧一行摘要），否则是二级页，
 * 里面才是真正的开关、输入框与文字链接。哪一条属于哪一页全部来自 [PAGES]
 * （见 SettingsSections.kt），这里只按数据渲染。
 */
@Composable
fun SettingsContent(
    model: SettingsModel,
    innerPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    page: SettingsPage?,
    onOpenPage: (SettingsPage) -> Unit
) {
    // 首次进设置页在后台补跑一次接管（同一进程只一次，失败不重试）。
    // 挂在 ready 上：框架没连上时先不消耗那次机会，连上后再来。
    LaunchedEffect(model.ready) {
        if (model.ready) {
            model.autoTakeoverOnce()
        }
    }
    // 横屏与挖屏下左右也要让开，否则卡片会压进系统栏
    val direction = LocalLayoutDirection.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(
            start = innerPadding.calculateStartPadding(direction),
            end = innerPadding.calculateEndPadding(direction),
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + BottomSpace
        ),
        verticalArrangement = Arrangement.spacedBy(SectionGap)
    ) {
        // 两条横幅是条件出现的，不给 key 的话它们消失时后面的项整体前移一格，
        // 正在输入的框会把焦点让给别的框
        if (!model.ready) {
            item(key = "banner_service") {
                StatusBanner(stringResource(R.string.msg_service_unavailable))
            }
        }
        if (model.writeFailed) {
            item(key = "banner_write") {
                StatusBanner(stringResource(R.string.msg_write_failed))
            }
        }

        if (page == null) {
            item(key = "nav_home") {
                SettingsHome(model, onOpenPage)
            }
        } else {
            item(key = "page_" + page.name) {
                PageContent(page, model)
            }
        }
    }
}

/**
 * 导航主页：四条入口，一条一行，右侧是当前值摘要。
 *
 * <p>摘要只写“现在是什么”，不写怎么改 —— 二级页里才有说明。
 */
@Composable
private fun SettingsHome(model: SettingsModel, onOpenPage: (SettingsPage) -> Unit) {
    Card(modifier = Modifier.padding(horizontal = ScreenMargin)) {
        NavRow(R.string.page_server, serverSummary(model)) { onOpenPage(SettingsPage.SERVER) }
        NavRow(R.string.page_sync, syncSummary(model)) { onOpenPage(SettingsPage.SYNC) }
        NavRow(R.string.page_space, spaceSummary(model)) { onOpenPage(SettingsPage.SPACE) }
        NavRow(R.string.page_maintenance, maintenanceSummary(model)) {
            onOpenPage(SettingsPage.MAINTENANCE)
        }
    }
}

/** 一条导航入口：左标题、右摘要、最右箭头（箭头由 ArrowPreference 自己画） */
@Composable
private fun NavRow(title: Int, summary: String, onClick: () -> Unit) {
    ArrowPreference(
        title = stringResource(title),
        onClick = onClick,
        endActions = {
            Text(
                text = summary,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

/** 服务器摘要：主机名 · 连接状态（未通过测试时只报“已配置”，不谎称已连接） */
@Composable
private fun serverSummary(model: SettingsModel): String {
    val url = model.baseUrl.trim()
    if (url.isEmpty()) {
        return stringResource(R.string.nav_value_unset)
    }
    val host = Config.safeHost(url).let { raw ->
        if (raw.length > HOST_MAX_CHARS) raw.take(HOST_MAX_CHARS) + "…" else raw
    }
    val state = when (model.testState) {
        TestState.Success -> R.string.nav_status_connected
        TestState.Running -> R.string.nav_status_testing
        else -> R.string.nav_status_configured
    }
    return stringResource(R.string.nav_server_summary, host, stringResource(state))
}

/** 同步摘要：仅 Wi-Fi · 并行 N 路 · 自动下载开/关 */
@Composable
private fun syncSummary(model: SettingsModel): String = stringResource(
    R.string.nav_sync_summary,
    stringResource(if (model.wifiOnly) R.string.nav_wifi_only else R.string.nav_any_network),
    model.concurrencyIndex + 1,
    stringResource(if (model.autoDownload) R.string.nav_state_on else R.string.nav_state_off)
)

/** 空间优化摘要：保留元数据 · 保持 HEIC · 并行 N 路 */
@Composable
private fun spaceSummary(model: SettingsModel): String = stringResource(
    R.string.nav_space_summary,
    stringResource(
        if (model.preserveMetadata) R.string.nav_keep_metadata else R.string.nav_drop_metadata
    ),
    stringResource(
        if (model.preserveHeicFormat) R.string.nav_keep_heic else R.string.nav_to_jpeg
    ),
    model.downloadConcurrencyIndex + 1
)

/** 维护与关于摘要：标记记录方式 · 主题 */
@Composable
private fun maintenanceSummary(model: SettingsModel): String = stringResource(
    R.string.nav_maintenance_summary,
    stringResource(
        if (model.localFlagModeIndex == 0) R.string.nav_flag_shared else R.string.nav_flag_per_target
    ),
    stringResource(
        when (model.themeModeIndex) {
            SettingsModel.THEME_LIGHT -> R.string.theme_mode_light
            SettingsModel.THEME_DARK -> R.string.theme_mode_dark
            else -> R.string.theme_mode_system
        }
    )
)

/**
 * 一页的正文。顺序完全按 [SettingsPage.items] 走（见 [runsOf]），相邻的卡片项并成一张卡：
 * 想让某个开关排在链接前面，改数据就行，不必改这里。
 */
@Composable
private fun PageContent(page: SettingsPage, model: SettingsModel) {
    val focusManager = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(BlockGap)) {
        runsOf(page.items).forEach { run ->
            when (run.kind) {
                ItemKind.FIELD -> run.ids.forEach { FieldItem(it, model, focusManager) }
                ItemKind.ACTION -> run.ids.forEach { LinkItem(it, model) }
                ItemKind.PREFERENCE -> {
                    PreferenceCard(run.ids, model)
                    // 替换模式的后果比其它开关重：云端查看与释放空间会失效，提示挂在卡片外面，
                    // 免得被当成卡片里又一行无害的说明
                    if (model.isReplaceMode && SettingId.REPLACE_MODE in run.ids) {
                        HintLine(stringResource(R.string.warn_replace_mode), error = true)
                    }
                }
            }
        }
    }
}

/** 一页里相邻的 Preference 共用一张卡片，与分组前的观感一致 */
@Composable
private fun PreferenceCard(ids: List<String>, model: SettingsModel) {
    Card(modifier = Modifier.padding(horizontal = ScreenMargin)) {
        ids.forEach { PrefItem(it, model) }
    }
}

/** 一个输入项的全部参数：值、回调、标签、提示语与键盘行为 */
private class FieldSpec(
    val value: String,
    val onChange: (String) -> Unit,
    val label: Int,
    val hint: Int?,
    val keyboardType: KeyboardType,
    val imeAction: ImeAction,
    val masked: Boolean = false
)

private fun fieldSpec(id: String, model: SettingsModel): FieldSpec = when (id) {
    SettingId.BASE_URL -> FieldSpec(
        value = model.baseUrl,
        onChange = model::onBaseUrlChange,
        label = R.string.hint_base_url,
        // 示例地址属于“怎么填”，二级页里不再重复一行
        hint = null,
        keyboardType = KeyboardType.Uri,
        imeAction = ImeAction.Next
    )

    SettingId.USERNAME -> FieldSpec(
        value = model.username,
        onChange = model::onUsernameChange,
        label = R.string.hint_username,
        hint = null,
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Next
    )

    SettingId.PASSWORD -> FieldSpec(
        value = model.password,
        onChange = model::onPasswordChange,
        label = R.string.hint_password,
        hint = R.string.summary_password,
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Next,
        masked = true
    )

    SettingId.REMOTE_ROOT -> FieldSpec(
        value = model.remoteRoot,
        onChange = model::onRemoteRootChange,
        label = R.string.hint_remote_root,
        hint = R.string.summary_remote_root,
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Done
    )

    else -> error("没有为 $id 定义输入框")
}

/**
 * 一个输入框。挂在 [INLINE_LINK_IDS] 里的行右侧还有一个文字链接（测试连接），
 * 链接不单独占行 —— 它只对那一行有意义。
 */
@Composable
private fun FieldItem(id: String, model: SettingsModel, focusManager: FocusManager) {
    val spec = fieldSpec(id, model)
    val link = INLINE_LINK_IDS[id]
    // 提示语紧贴它说明的那个框（HintGap），与别的输入项之间才用 BlockGap
    Column(verticalArrangement = Arrangement.spacedBy(HintGap)) {
        if (link == null) {
            SettingsField(
                value = spec.value,
                onValueChange = spec.onChange,
                label = stringResource(spec.label),
                keyboardType = spec.keyboardType,
                imeAction = spec.imeAction,
                focusManager = focusManager,
                masked = spec.masked,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenMargin)
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenMargin),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsField(
                    value = spec.value,
                    onValueChange = spec.onChange,
                    label = stringResource(spec.label),
                    keyboardType = spec.keyboardType,
                    imeAction = spec.imeAction,
                    focusManager = focusManager,
                    masked = spec.masked,
                    modifier = Modifier.weight(1f)
                )
                ActionLink(
                    text = stringResource(R.string.action_test),
                    enabled = model.testState !is TestState.Running,
                    modifier = Modifier.padding(start = HintGap),
                    onClick = {
                        // 先收键盘再测，否则"连接成功/失败"那行结果会被输入法盖住
                        focusManager.clearFocus()
                        model.testConnection()
                    }
                )
            }
            TestResultText(model.testState)
        }
        spec.hint?.let { HintLine(stringResource(it)) }
    }
}

/** 卡片里的一个下拉框或开关。默认值、落盘键与回调全部来自 [SettingsModel]，这里只管文案 */
@Composable
private fun PrefItem(id: String, model: SettingsModel) {
    when (id) {
        SettingId.REPLACE_MODE -> OverlayDropdownPreference(
            items = listOf(
                stringResource(R.string.mode_replace),
                stringResource(R.string.mode_mirror),
                stringResource(R.string.mode_off)
            ),
            selectedIndex = model.replaceModeIndex,
            title = stringResource(R.string.label_replace_mode),
            onSelectedIndexChange = model::onReplaceModeChange
        )

        SettingId.WIFI_ONLY -> SwitchPreference(
            checked = model.wifiOnly,
            onCheckedChange = model::onWifiOnlyChange,
            title = stringResource(R.string.label_wifi_only),
            summary = stringResource(R.string.summary_wifi_only)
        )

        SettingId.AUTO_DOWNLOAD -> SwitchPreference(
            checked = model.autoDownload,
            onCheckedChange = model::onAutoDownloadChange,
            title = stringResource(R.string.label_auto_download),
            summary = stringResource(R.string.summary_auto_download)
        )

        SettingId.REMOTE_LAYOUT -> OverlayDropdownPreference(
            items = listOf(
                stringResource(R.string.layout_album),
                stringResource(R.string.layout_date),
                stringResource(R.string.layout_flat)
            ),
            selectedIndex = model.layoutIndex,
            title = stringResource(R.string.label_remote_layout),
            onSelectedIndexChange = model::onLayoutChange
        )

        SettingId.VERIFY_ASYNC -> SwitchPreference(
            checked = model.verifyAsync,
            onCheckedChange = model::onVerifyAsyncChange,
            title = stringResource(R.string.label_verify_async),
            summary = stringResource(R.string.summary_verify_async)
        )

        SettingId.SKIP_EXISTING_REMOTE -> SwitchPreference(
            checked = model.skipExistingRemote,
            onCheckedChange = model::onSkipExistingRemoteChange,
            title = stringResource(R.string.label_skip_existing_remote),
            summary = stringResource(R.string.summary_skip_existing_remote)
        )

        SettingId.WEBDAV_TAKEOVER -> SwitchPreference(
            checked = model.webdavTakeover,
            onCheckedChange = model::onWebdavTakeoverChange,
            title = stringResource(R.string.label_webdav_takeover),
            summary = stringResource(R.string.summary_webdav_takeover)
        )

        SettingId.UPLOAD_CONCURRENCY -> OverlayDropdownPreference(
            items = SettingsModel.CONCURRENCY_ITEMS,
            selectedIndex = model.concurrencyIndex,
            title = stringResource(R.string.label_upload_concurrency),
            summary = stringResource(R.string.summary_upload_concurrency),
            onSelectedIndexChange = model::onConcurrencyChange
        )

        SettingId.DOWNLOAD_CONCURRENCY -> OverlayDropdownPreference(
            items = SettingsModel.DOWNLOAD_CONCURRENCY_ITEMS,
            selectedIndex = model.downloadConcurrencyIndex,
            title = stringResource(R.string.label_download_concurrency),
            summary = stringResource(R.string.summary_download_concurrency),
            onSelectedIndexChange = model::onDownloadConcurrencyChange
        )

        SettingId.MAX_UPLOAD_BYTES -> OverlayDropdownPreference(
            items = listOf(
                stringResource(R.string.size_cap_unlimited),
                stringResource(R.string.size_cap_200mb),
                stringResource(R.string.size_cap_500mb),
                stringResource(R.string.size_cap_1gb),
                stringResource(R.string.size_cap_2gb)
            ),
            selectedIndex = model.maxUploadBytesIndex,
            title = stringResource(R.string.label_max_upload_bytes),
            summary = stringResource(R.string.summary_max_upload_bytes),
            onSelectedIndexChange = model::onMaxUploadBytesChange
        )

        SettingId.PRESERVE_METADATA -> SwitchPreference(
            checked = model.preserveMetadata,
            onCheckedChange = model::onPreserveMetadataChange,
            title = stringResource(R.string.label_preserve_metadata),
            summary = stringResource(R.string.summary_preserve_metadata)
        )

        SettingId.PRESERVE_HEIC_FORMAT -> SwitchPreference(
            checked = model.preserveHeicFormat,
            onCheckedChange = model::onPreserveHeicFormatChange,
            title = stringResource(R.string.label_preserve_heic_format),
            summary = stringResource(R.string.summary_preserve_heic_format)
        )

        SettingId.LOCAL_FLAG_MODE -> OverlayDropdownPreference(
            items = listOf(
                stringResource(R.string.flag_mode_shared),
                stringResource(R.string.flag_mode_per_target)
            ),
            selectedIndex = model.localFlagModeIndex,
            title = stringResource(R.string.label_local_flag_mode),
            summary = stringResource(R.string.summary_local_flag_mode),
            onSelectedIndexChange = model::onLocalFlagModeChange
        )

        SettingId.UI_MASK -> SwitchPreference(
            checked = model.uiMask,
            onCheckedChange = model::onUiMaskChange,
            title = stringResource(R.string.label_ui_mask),
            summary = stringResource(R.string.summary_ui_mask)
        )

        SettingId.THEME_MODE -> OverlayDropdownPreference(
            items = listOf(
                stringResource(R.string.theme_mode_system),
                stringResource(R.string.theme_mode_light),
                stringResource(R.string.theme_mode_dark)
            ),
            selectedIndex = model.themeModeIndex,
            title = stringResource(R.string.label_theme_mode),
            summary = stringResource(R.string.summary_theme_mode),
            onSelectedIndexChange = model::onThemeModeChange
        )

        else -> error("没有为 $id 定义下拉框或开关")
    }
}

/** 独立文字链接（重新扫描接管、重置已上传标记） */
@Composable
private fun LinkItem(id: String, model: SettingsModel) {
    when (id) {
        SettingId.TAKEOVER_NOW -> TakeoverLink(model)
        SettingId.RESET_UPLOADED -> ResetUploadedLink(model)
        else -> error("没有为 $id 定义文字链接")
    }
}

/**
 * 重新扫描未接管的照片：让相册进程把已有的云端行登记进注册表，一个字节都不下载。
 *
 * <p>不做确认框：它只往注册表补记录 —— 不下载、不删除、不改照片与相册行。
 * 相册需要在运行（广播是单向的，相册不在场时会被静默丢弃）。
 */
@Composable
private fun TakeoverLink(model: SettingsModel) {
    Column(verticalArrangement = Arrangement.spacedBy(HintGap)) {
        ActionLink(
            text = stringResource(R.string.action_rescan_takeover),
            enabled = model.ready && model.takeoverState !is ResetState.Running,
            modifier = Modifier.padding(horizontal = HintMargin),
            onClick = model::takeoverNow
        )
        TakeoverResultText(model.takeoverState, onDismiss = model::dismissTakeoverResult)
    }
}

/**
 * 重置“已上传标记”：只放“会真的动相册数据”的动作。
 *
 * <p>与其它配置项性质不同 —— 其余项改了只是换个行为，这一项点下去是**不可逆的重传**，
 * 所以用红字文字链接，还要过一次确认框。
 */
@Composable
private fun ResetUploadedLink(model: SettingsModel) {
    var confirming by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(HintGap)) {
        ActionLink(
            text = stringResource(R.string.action_reset_uploaded),
            enabled = model.ready && model.resetState !is ResetState.Running,
            error = true,
            modifier = Modifier.padding(horizontal = HintMargin),
            onClick = { confirming = true }
        )
        // 替换模式下相册的"已上传"标记归模块管，重置才有意义；别的模式下相册自己
        // 还在跟小米云打交道，替它重置等于打乱它自己的账
        if (!model.isReplaceMode) {
            HintLine(stringResource(R.string.warn_reset_needs_replace), error = true)
        }
        ResetResultText(model.resetState, onDismiss = model::dismissResetResult)
    }

    WindowDialog(
        show = confirming,
        title = stringResource(R.string.dialog_reset_title),
        summary = stringResource(R.string.dialog_reset_summary),
        onDismissRequest = { confirming = false },
        content = {
            val dismissState = LocalDismissState.current
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { dismissState?.invoke() },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.action_reset_confirm),
                    onClick = {
                        model.resetUploaded()
                        dismissState?.invoke()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

/** 单个文字链接。禁用态用弱化色，危险动作用告警色 */
@Composable
private fun ActionLink(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: Boolean = false,
    onClick: () -> Unit
) {
    val color = when {
        !enabled -> MiuixTheme.colorScheme.disabledOnSecondaryVariant
        error -> MiuixTheme.colorScheme.error
        else -> MiuixTheme.colorScheme.primary
    }
    Text(
        text = text,
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = HintGap),
        style = MiuixTheme.textStyles.body1,
        color = color
    )
}

@Composable
private fun TestResultText(state: TestState) {
    val text = when (state) {
        TestState.Idle -> null
        TestState.Running -> stringResource(R.string.msg_testing)
        TestState.NeedUrl -> stringResource(R.string.msg_fill_url)
        TestState.Success -> stringResource(R.string.msg_test_ok)
        TestState.AuthFailed -> stringResource(R.string.msg_test_auth)
        is TestState.NotWritable -> stringResource(R.string.msg_test_not_writable, state.detail)
        is TestState.Failure -> stringResource(R.string.msg_test_failed, state.detail)
    }
    val color = when (state) {
        TestState.Success -> MiuixTheme.colorScheme.primary
        TestState.Running -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        // 除成功与"测试中"之外都是要用户处理的问题，统一用告警色
        else -> MiuixTheme.colorScheme.error
    }
    if (text != null) {
        ResultLine(text, color)
    }
}

@Composable
private fun TakeoverResultText(state: ResetState, onDismiss: () -> Unit) {
    val text = when (state) {
        ResetState.Idle -> null
        ResetState.Running -> stringResource(R.string.msg_takeover_running)
        ResetState.Sent -> stringResource(R.string.msg_takeover_sent)
        is ResetState.Failed -> stringResource(R.string.msg_takeover_failed, state.detail)
    }
    val color = when (state) {
        ResetState.Sent -> MiuixTheme.colorScheme.primary
        ResetState.Running -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        else -> MiuixTheme.colorScheme.error
    }
    if (text != null) {
        ResultLine(text, color, onDismiss = onDismiss)
    }
}

@Composable
private fun ResetResultText(state: ResetState, onDismiss: () -> Unit) {
    val text = when (state) {
        ResetState.Idle -> null
        ResetState.Running -> stringResource(R.string.msg_reset_running)
        ResetState.Sent -> stringResource(R.string.msg_reset_sent)
        is ResetState.Failed -> stringResource(R.string.msg_reset_failed, state.detail)
    }
    val color = when (state) {
        ResetState.Sent -> MiuixTheme.colorScheme.primary
        ResetState.Running -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        else -> MiuixTheme.colorScheme.error
    }
    if (text != null) {
        ResultLine(text, color, onDismiss = onDismiss)
    }
}

/** 动作结果一行：点一下可以收起（不需要用户处理，只是别一直留在页面上） */
@Composable
private fun ResultLine(text: String, color: Color, onDismiss: (() -> Unit)? = null) {
    Text(
        text = text,
        modifier = Modifier
            .padding(horizontal = HintMargin)
            .let { if (onDismiss == null) it else it.clickable { onDismiss() } },
        style = MiuixTheme.textStyles.body2,
        color = color
    )
}

/**
 * 表单输入框。标签走 Miuix 的浮起标签而不是占位符：占位符一旦输入就消失，
 * 四个外观相近的框会分不清哪个是哪个。
 */
@Composable
private fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    focusManager: FocusManager,
    masked: Boolean = false,
    modifier: Modifier = Modifier
) {
    var revealed by remember { mutableStateOf(false) }
    val colors = fieldColors()
    val trailingIcon: (@Composable () -> Unit)? = if (masked) {
        {
            Text(
                text = if (revealed) {
                    stringResource(R.string.action_hide)
                } else {
                    stringResource(R.string.action_show)
                },
                color = MiuixTheme.colorScheme.primary,
                style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier
                    .clickable { revealed = !revealed }
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            )
        }
    } else {
        null
    }
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = true,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        // 不接这些回调时键盘的下一步/完成没有任何反应，输入完还得手动退键盘
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Down) },
            onDone = { focusManager.clearFocus() }
        ),
        visualTransformation = if (masked && !revealed) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        colors = colors,
        trailingIcon = trailingIcon
    )
}

/**
 * 输入框配色不走 Miuix 默认值，两个默认值在本页都不成立：
 * - 背景默认 `secondaryContainer`（浅色 #F0F0F0）与页面底色 `surface`（#F7F7F7）只差 3%，
 *   输入框几乎看不出边界；改用 `surfaceContainer`（浅色纯白）才有白底输入框的层次。
 * - 标签默认 `onSecondaryContainer`（浅色 #A9A9A9）对比度约 2:1，浮起后只有 10sp，基本读不清；
 *   改用与 Preference 副标题同一档的 `onSurfaceVariantSummary`（浅色下合成约 #666）。
 */
@Composable
private fun fieldColors() = TextFieldDefaults.textFieldColors(
    backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
    labelColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    borderColor = MiuixTheme.colorScheme.primary
)

@Composable
private fun HintLine(text: String, error: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = HintMargin),
        style = MiuixTheme.textStyles.body2,
        color = if (error) {
            MiuixTheme.colorScheme.error
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        }
    )
}

/** 框架未连接 / 写入失败时给一条错误底色的横幅，别让用户以为改过了 */
@Composable
private fun StatusBanner(text: String) {
    Card(
        modifier = Modifier.padding(horizontal = ScreenMargin),
        insideMargin = PaddingValues(16.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.errorContainer,
            contentColor = MiuixTheme.colorScheme.onErrorContainer
        )
    ) {
        Text(text = text, style = MiuixTheme.textStyles.body2)
    }
}
