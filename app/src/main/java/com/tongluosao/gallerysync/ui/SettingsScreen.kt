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

        item(key = "section_webdav") {
            SettingsSection(stringResource(R.string.section_webdav)) {
                WebDavSection(model)
            }
        }

        item(key = "section_behavior") {
            SettingsSection(stringResource(R.string.section_behavior)) {
                BehaviorSection(model)
            }
        }

        item(key = "section_maintenance") {
            SettingsSection(stringResource(R.string.section_maintenance)) {
                MaintenanceSection(model)
            }
        }

        item(key = "section_appearance") {
            SettingsSection(stringResource(R.string.section_appearance)) {
                AppearanceSection(model)
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

@Composable
private fun WebDavSection(model: SettingsModel) {
    val focusManager = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(BlockGap)) {
        Column(verticalArrangement = Arrangement.spacedBy(HintGap)) {
            SettingsField(
                value = model.baseUrl,
                onValueChange = model::onBaseUrlChange,
                label = stringResource(R.string.hint_base_url),
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                focusManager = focusManager
            )
            HintLine(stringResource(R.string.summary_base_url))
        }
        SettingsField(
            value = model.username,
            onValueChange = model::onUsernameChange,
            label = stringResource(R.string.hint_username),
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Next,
            focusManager = focusManager
        )
        Column(verticalArrangement = Arrangement.spacedBy(HintGap)) {
            SettingsField(
                value = model.password,
                onValueChange = model::onPasswordChange,
                label = stringResource(R.string.hint_password),
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next,
                focusManager = focusManager,
                masked = true
            )
            HintLine(stringResource(R.string.summary_password))
        }
        Column(verticalArrangement = Arrangement.spacedBy(HintGap)) {
            SettingsField(
                value = model.remoteRoot,
                onValueChange = model::onRemoteRootChange,
                label = stringResource(R.string.hint_remote_root),
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
                focusManager = focusManager
            )
            HintLine(stringResource(R.string.summary_remote_root))
        }
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

@Composable
private fun BehaviorSection(model: SettingsModel) {
    Column(verticalArrangement = Arrangement.spacedBy(BlockGap)) {
        Card(modifier = Modifier.padding(horizontal = ScreenMargin)) {
            OverlayDropdownPreference(
                items = listOf(
                    stringResource(R.string.mode_replace),
                    stringResource(R.string.mode_mirror),
                    stringResource(R.string.mode_off)
                ),
                selectedIndex = model.replaceModeIndex,
                title = stringResource(R.string.label_replace_mode),
                onSelectedIndexChange = model::onReplaceModeChange
            )
            SwitchPreference(
                checked = model.wifiOnly,
                onCheckedChange = model::onWifiOnlyChange,
                title = stringResource(R.string.label_wifi_only),
                summary = stringResource(R.string.summary_wifi_only)
            )
            SwitchPreference(
                checked = model.uiMask,
                onCheckedChange = model::onUiMaskChange,
                title = stringResource(R.string.label_ui_mask),
                summary = stringResource(R.string.summary_ui_mask)
            )
            OverlayDropdownPreference(
                items = listOf(
                    stringResource(R.string.layout_album),
                    stringResource(R.string.layout_date),
                    stringResource(R.string.layout_flat)
                ),
                selectedIndex = model.layoutIndex,
                title = stringResource(R.string.label_remote_layout),
                onSelectedIndexChange = model::onLayoutChange
            )
            SwitchPreference(
                checked = model.verifyAsync,
                onCheckedChange = model::onVerifyAsyncChange,
                title = stringResource(R.string.label_verify_async),
                summary = stringResource(R.string.summary_verify_async)
            )
            SwitchPreference(
                checked = model.skipExistingRemote,
                onCheckedChange = model::onSkipExistingRemoteChange,
                title = stringResource(R.string.label_skip_existing_remote),
                summary = stringResource(R.string.summary_skip_existing_remote)
            )
            OverlayDropdownPreference(
                items = listOf(
                    stringResource(R.string.flag_mode_shared),
                    stringResource(R.string.flag_mode_per_target)
                ),
                selectedIndex = model.localFlagModeIndex,
                title = stringResource(R.string.label_local_flag_mode),
                summary = stringResource(R.string.summary_local_flag_mode),
                onSelectedIndexChange = model::onLocalFlagModeChange
            )
            SwitchPreference(
                checked = model.downloadEnabled,
                onCheckedChange = model::onDownloadEnabledChange,
                title = stringResource(R.string.label_download_enabled),
                summary = stringResource(R.string.summary_download_enabled)
            )
            OverlayDropdownPreference(
                items = SettingsModel.CONCURRENCY_ITEMS,
                selectedIndex = model.concurrencyIndex,
                title = stringResource(R.string.label_upload_concurrency),
                summary = stringResource(R.string.summary_upload_concurrency),
                onSelectedIndexChange = model::onConcurrencyChange
            )
        }
        if (model.isReplaceMode) {
            HintLine(stringResource(R.string.warn_replace_mode), error = true)
        }
    }
}

/**
 * 维护区：只放"会真的动相册数据"的动作。
 *
 * <p>单独一个分区而不是塞进"同步"里，是因为这一项与其它配置项性质不同 ——
 * 其余项改了只是换个行为，这一项点下去是**不可逆的重传**，要给用户一个明显的边界。
 */
@Composable
private fun MaintenanceSection(model: SettingsModel) {
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

@Composable
private fun AppearanceSection(model: SettingsModel) {    Card(modifier = Modifier.padding(horizontal = ScreenMargin)) {
        OverlayDropdownPreference(
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
