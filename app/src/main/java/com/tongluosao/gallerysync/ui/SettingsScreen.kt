package com.tongluosao.gallerysync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tongluosao.gallerysync.R
import com.tongluosao.gallerysync.ui.SettingsModel.TestState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 设置页的滚动区。主题控制器、顶栏与底栏都由 [MainScreen] 持有，
 * 本页只负责列表本身，切页时才不会连底栏一起重建。
 *
 * <p>分组、每组包含哪些项、先排哪一组，全部来自 [SECTIONS]（见 SettingsSections.kt）：
 * 这里只按数据渲染，不再另写一份顺序，否则加一项就得改两处。
 */
@Composable
fun SettingsContent(
    model: SettingsModel,
    innerPadding: PaddingValues,
    scrollBehavior: ScrollBehavior
) {
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

        SECTIONS.forEach { section ->
            // 一个项都没有的组连标题一起省掉，免得列表里挂一个空标题
            if (section.items.isEmpty()) {
                return@forEach
            }
            item(key = "section_" + section.name) {
                SettingsSection(section.title) {
                    SectionContent(section, model)
                }
            }
        }
    }
}

/** 标题用 SmallTitle 默认内边距，不要再叠横向 padding，否则会比卡片文字多缩 12dp */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        SmallTitle(text = title)
        content()
    }
}

/**
 * 一组的正文。顺序完全按 [SettingsSection.items] 走（见 [runsOf]），相邻的卡片项并成一张卡：
 * 想让某个开关排在按钮前面，改数据就行，不必改这里。
 */
@Composable
private fun SectionContent(section: SettingsSection, model: SettingsModel) {
    val focusManager = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(BlockGap)) {
        runsOf(section).forEach { run ->
            when (run.kind) {
                ItemKind.FIELD -> run.ids.forEach { FieldItem(it, model, focusManager) }
                ItemKind.ACTION -> run.ids.forEach { ActionItem(it, model) }
                ItemKind.PREFERENCE -> PreferenceCard(run.ids, model)
            }
        }
        // 替换模式的后果比其它开关重：云端查看与释放空间会失效，提示挂在卡片外面，
        // 免得被当成卡片里又一行无害的说明
        if (model.isReplaceMode && section.items.contains(SettingId.REPLACE_MODE)) {
            HintLine(stringResource(R.string.warn_replace_mode), error = true)
        }
    }
}

/** 一组相邻的 Preference 共用一张卡片，与分组前的观感一致 */
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
        hint = R.string.summary_base_url,
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

@Composable
private fun FieldItem(id: String, model: SettingsModel, focusManager: FocusManager) {
    val spec = fieldSpec(id, model)
    // 提示语紧贴它说明的那个框（HintGap），与别的输入项之间才用 BlockGap
    Column(verticalArrangement = Arrangement.spacedBy(HintGap)) {
        SettingsField(
            value = spec.value,
            onValueChange = spec.onChange,
            label = stringResource(spec.label),
            keyboardType = spec.keyboardType,
            imeAction = spec.imeAction,
            focusManager = focusManager,
            masked = spec.masked
        )
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

        // 文案直接写中文：本页其余文案走字符串资源，但新增这一项没有对应的 R.string
        // （本波只允许改这几个 ui/*.kt），而本模块界面只有中文一种语言
        SettingId.WEBDAV_TAKEOVER -> SwitchPreference(
            checked = model.webdavTakeover,
            onCheckedChange = model::onWebdavTakeoverChange,
            title = "接管相册已有照片（实验）",
            summary = "把相册里已经上传过的照片登记进注册表；只登记，不下载任何字节"
        )

        SettingId.UPLOAD_CONCURRENCY -> OverlayDropdownPreference(
            items = SettingsModel.CONCURRENCY_ITEMS,
            selectedIndex = model.concurrencyIndex,
            title = stringResource(R.string.label_upload_concurrency),
            summary = stringResource(R.string.summary_upload_concurrency),
            onSelectedIndexChange = model::onConcurrencyChange
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

/** 独立按钮行 */
@Composable
private fun ActionItem(id: String, model: SettingsModel) {
    when (id) {
        SettingId.TEST_CONNECTION -> TestConnectionAction(model)
        SettingId.TAKEOVER_NOW -> TakeoverAction(model)
        SettingId.RESET_UPLOADED -> ResetUploadedAction(model)
        else -> error("没有为 $id 定义按钮行")
    }
}

/**
 * 立即接管：让相册进程把已有的云端行登记进注册表，一个字节都不下载。
 *
 * <p>不做确认框：它只往注册表补记录 —— 不下载、不删除、不改照片与相册行，
 * 点错了再点一次也只是"都已登记"而已。真正要防的是"开关没打开就点"，
 * 所以按钮可用性直接绑在 [SettingsModel.webdavTakeover] 上（模型侧还有一道同样的判断）。
 */
@Composable
private fun TakeoverAction(model: SettingsModel) {
    Column(verticalArrangement = Arrangement.spacedBy(BlockGap)) {
        Row(
            modifier = Modifier.padding(horizontal = ScreenMargin),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BlockGap)
        ) {
            Button(
                onClick = model::takeoverNow,
                enabled = model.ready && model.webdavTakeover
                        && model.takeoverState !is SettingsModel.ResetState.Running,
                minWidth = 96.dp,
                colors = ButtonDefaults.buttonColorsPrimary(
                    disabledContentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            ) {
                Text(text = "立即接管（不下载字节）")
            }
            TakeoverResultText(model.takeoverState, onDismiss = model::dismissTakeoverResult)
        }
        HintLine("先打开上面那条开关。接管只补注册表记录：不下载字节、不删除照片、不改相册行；相册需要在运行。")
        if (!model.webdavTakeover) {
            HintLine("接管还没有打开，按钮不可用。", error = true)
        }
    }
}

@Composable
private fun RowScope.TakeoverResultText(
    state: SettingsModel.ResetState,
    onDismiss: () -> Unit
) {
    val text = when (state) {
        SettingsModel.ResetState.Idle -> null
        SettingsModel.ResetState.Running -> "正在通知相册…"
        SettingsModel.ResetState.Sent -> "已通知相册，处理进度见日志（event=takeover_done）"
        is SettingsModel.ResetState.Failed -> "发送失败：" + state.detail
    }
    val color = when (state) {
        SettingsModel.ResetState.Sent -> MiuixTheme.colorScheme.primary
        SettingsModel.ResetState.Running -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        else -> MiuixTheme.colorScheme.error
    }
    if (text != null) {
        Text(
            text = text,
            modifier = Modifier
                .weight(1f)
                .clickable { onDismiss() },
            style = MiuixTheme.textStyles.body2,
            color = color
        )
    }
}

@Composable
private fun TestConnectionAction(model: SettingsModel) {
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier.padding(horizontal = ScreenMargin),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BlockGap)
    ) {
        Button(
            onClick = {
                // 先收键盘再测，否则"连接成功/失败"那行结果会被输入法盖住
                focusManager.clearFocus()
                model.testConnection()
            },
            enabled = model.testState !is TestState.Running,
            minWidth = 96.dp,
            colors = ButtonDefaults.buttonColorsPrimary(
                // 默认禁用态是白字压在浅蓝底上，测试期间那几百毫秒几乎看不清
                disabledContentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        ) {
            Text(text = stringResource(R.string.action_test))
        }
        TestResultText(model.testState)
    }
}

@Composable
private fun RowScope.TestResultText(state: TestState) {
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
    if (text != null) ResultText(text, color)
}

@Composable
private fun RowScope.ResultText(text: String, color: Color) {
    Text(
        text = text,
        modifier = Modifier.weight(1f),
        style = MiuixTheme.textStyles.body2,
        color = color
    )
}

/**
 * 重置"已上传标记"：只放"会真的动相册数据"的动作。
 *
 * <p>与其它配置项性质不同 —— 其余项改了只是换个行为，这一项点下去是**不可逆的重传**，
 * 所以单独成行、还要过一次确认框。
 */
@Composable
private fun ResetUploadedAction(model: SettingsModel) {
    var confirming by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(BlockGap)) {
        Row(
            modifier = Modifier.padding(horizontal = ScreenMargin),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BlockGap)
        ) {
            Button(
                onClick = { confirming = true },
                enabled = model.ready && model.resetState !is SettingsModel.ResetState.Running,
                minWidth = 96.dp,
                colors = ButtonDefaults.buttonColorsPrimary(
                    disabledContentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            ) {
                Text(text = stringResource(R.string.action_reset_uploaded))
            }
            ResetResultText(model.resetState, onDismiss = model::dismissResetResult)
        }
        HintLine(stringResource(R.string.summary_reset_uploaded))
        // 替换模式下相册的"已上传"标记归模块管，重置才有意义；别的模式下相册自己
        // 还在跟小米云打交道，替它重置等于打乱它自己的账
        if (!model.isReplaceMode) {
            HintLine(stringResource(R.string.warn_reset_needs_replace), error = true)
        }
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

@Composable
private fun RowScope.ResetResultText(
    state: SettingsModel.ResetState,
    onDismiss: () -> Unit
) {
    val text = when (state) {
        SettingsModel.ResetState.Idle -> null
        SettingsModel.ResetState.Running -> stringResource(R.string.msg_reset_running)
        SettingsModel.ResetState.Sent -> stringResource(R.string.msg_reset_sent)
        is SettingsModel.ResetState.Failed -> stringResource(R.string.msg_reset_failed, state.detail)
    }
    val color = when (state) {
        SettingsModel.ResetState.Sent -> MiuixTheme.colorScheme.primary
        SettingsModel.ResetState.Running -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        else -> MiuixTheme.colorScheme.error
    }
    if (text != null) {
        Text(
            text = text,
            modifier = Modifier
                .weight(1f)
                .clickable { onDismiss() },
            style = MiuixTheme.textStyles.body2,
            color = color
        )
    }
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
    masked: Boolean = false
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenMargin),
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
