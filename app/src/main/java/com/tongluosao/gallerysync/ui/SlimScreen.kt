package com.tongluosao.gallerysync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tongluosao.gallerysync.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 默认并行路数。设置页里能改，这里只是"开始"按钮的初值 —— 3 路是经验上相册 IO 不卡的上限 */
private const val DEFAULT_CONCURRENCY = 3

/** 文件名超过这个长度就中间省略：HEIC 的尾巴（扩展名）比开头更有辨识度 */
private const val MAX_NAME_CHARS = 32

/** 进度条高度，跟进度页保持一致 */
private val SlimProgressHeight = 8.dp

/**
 * 「空间优化」页。
 *
 * 这页**只是观察窗**：优化本身在相册进程里由模块的队列跑，这里既不算体积也不删文件，
 * 所以极端情况（相册被杀、没人上报）下它只能显示上一次收到的帧，而不是"实时"状态 ——
 * [SlimState.updatedAt] 为 0 时说明连一帧都没收到，直接说等待上报，别假装一切正常。
 */
@Composable
fun SlimScreen(
    state: SlimState,
    onStart: (Int) -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.slim_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .clickable(onClick = onBack)
                            .padding(horizontal = ScreenMargin, vertical = HintGap),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                }
            )
        },
        bottomBar = {
            SlimBottomBar(state = state, onStop = onStop, onBack = onBack)
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(BlockGap)
        ) {
            Spacer(Modifier.height(SectionGap))
            StatusCard(state)
            if (!state.running) {
                IdleCard(onStart = onStart)
            }
            NativeSlimEntry()
            Spacer(Modifier.height(BottomSpace))
        }
    }
}

/** 状态卡：一行标题（右挂百分比）+ 进度条 + 三个计数 */
@Composable
private fun StatusCard(state: SlimState) {
    Card(
        modifier = Modifier.padding(horizontal = ScreenMargin),
        insideMargin = PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(HintGap)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusTitle(state),
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.body1
                )
                val percent = state.percent
                if (state.running && percent != null) {
                    Text(
                        text = "$percent%",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary
                    )
                }
            }
            if (!state.received) {
                // 一帧都没收到：可能相册没在跑，也可能模块还没注入。别把 0/0 当"已完成"展示
                Text(
                    text = "等待相册进程上报…",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            } else {
                if (state.running) {
                    // total 还没数出来时进度条走不确定态，比画一条 0% 的实心条诚实
                    LinearProgressIndicator(
                        progress = if (state.total > 0) state.fraction else null,
                        modifier = Modifier.fillMaxWidth(),
                        height = SlimProgressHeight
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "已完成 ${state.done} / ${state.total}",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Text(
                        text = "已节省 ${formatBytes(state.saved)}",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                val current = state.current
                if (current != null) {
                    Text(
                        text = middleEllipsis(current),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                if (state.failed > 0) {
                    // 失败数只在真的失败过时出现：0 是一直在的常态，写出来只会让人以为有问题
                    Text(
                        text = "失败 ${state.failed} 个，已跳过",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/** 没在跑时的说明 + 开始按钮 */
@Composable
private fun IdleCard(onStart: (Int) -> Unit) {
    Card(
        modifier = Modifier.padding(horizontal = ScreenMargin),
        insideMargin = PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(BlockGap)) {
            Text(
                text = "由模块自己按队列优化，可在设置里调整并行路数",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Button(
                onClick = { onStart(DEFAULT_CONCURRENCY) },
                minWidth = 96.dp,
                colors = ButtonDefaults.buttonColorsPrimary(
                    // 与设置页一致：默认禁用态白字压浅蓝底，几乎读不清
                    disabledContentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            ) {
                Text(text = "开始优化")
            }
        }
    }
}

/**
 * 逃生口：相册自带的「空间优化」页面。
 *
 * 模块自己的页面只是薄薄一层，真要按相册的原生策略优化时得回到它自己的入口；
 * 相册被禁用/卸载时 [openNativeSlim] 返回 false，就地提示，不弹对话框打断。
 */
@Composable
private fun NativeSlimEntry() {
    val context = LocalContext.current
    var unavailable by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.padding(horizontal = HintMargin),
        verticalArrangement = Arrangement.spacedBy(HintGap)
    ) {
        Text(
            text = "用相册自带的优化",
            modifier = Modifier.clickable { unavailable = !openNativeSlim(context) },
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary
        )
        if (unavailable) {
            Text(
                text = "相册不可用",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error
            )
        }
    }
}

/** 底部：在跑时只有"停止"，停下来之后只剩"返回" */
@Composable
private fun SlimBottomBar(state: SlimState, onStop: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = ScreenMargin, vertical = BlockGap)
    ) {
        if (state.running) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                // 已经点了停止就别再下发第二次，否则队列会收到一串重复的停止命令
                enabled = !state.stopping
            ) {
                Text(text = if (state.stopping) "正在停止…" else "停止")
            }
        } else {
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(text = "返回")
            }
        }
    }
}

private fun statusTitle(state: SlimState): String = when (state.state) {
    SlimProtocol.STATE_RUNNING -> "正在优化"
    SlimProtocol.STATE_STOPPING -> "正在停止"
    SlimProtocol.STATE_DONE -> "优化完成"
    else -> "未在运行"
}

/**
 * 中间省略。
 *
 * Compose 的 `overflow = TextOverflow.Ellipsis` 只会砍尾巴，而相册文件名是
 * `IMG_20260101_120000.heic` 这种"前面是时间、后面是扩展名"的形状，砍掉尾巴等于把
 * 唯一的线索砍掉，所以自己按字符数从中间截。
 */
private fun middleEllipsis(text: String, max: Int = MAX_NAME_CHARS): String {
    if (text.length <= max) {
        return text
    }
    val keep = (max - 1) / 2
    return text.take(keep) + "…" + text.takeLast(keep)
}
