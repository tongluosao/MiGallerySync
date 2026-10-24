package com.tongluosao.gallerysync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tongluosao.gallerysync.R
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 默认并行路数。设置页里能改，这里只是"开始"按钮的初值 —— 3 路是经验上相册 IO 不卡的上限 */
private const val DEFAULT_CONCURRENCY = 6

/** 文件名超过这个长度就中间省略：HEIC 的尾巴（扩展名）比开头更有辨识度 */
private const val MAX_NAME_CHARS = 32

/**
 * 多久没收到帧就认为通道断了。跟进度页同一个阈值：优化是重 IO 的活，心跳比上传还稀，
 * 但 12s 已经够用户察觉"它是不是卡住了"。
 */
private const val SLIM_STALE_MS = 12_000L

/** 进度条高度，跟进度页保持一致 */
private val SlimProgressHeight = 8.dp

/** 分项行里那条细进度条的高度，跟进度页的行内条一致 */
private val RowProgressHeight = 4.dp

/**
 * 「空间优化」页（独立 Activity 版）：自带顶栏 + 返回。
 *
 * 页面主体在 [SlimContent] 里，主界面从底栏进来时直接调它，外壳由 MainScreen 提供 ——
 * 两条入口共用同一份排版，改一处两边都变。
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
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        SlimContent(
            state = state,
            innerPadding = innerPadding,
            scrollBehavior = scrollBehavior,
            onStart = onStart,
            onStop = onStop
        )
    }
}

/**
 * 「空间优化」页主体，**没有自己的顶栏和底栏**，可直接嵌进主界面。
 *
 * 这页**只是观察窗**：优化本身在相册进程里由模块的队列跑，这里既不算体积也不删文件，
 * 所以极端情况（相册被杀、没人上报）下它只能显示上一次收到的帧 ——
 * [SlimState.received] 为 false 时说明连一帧都没收到，直接说等待上报，别假装一切正常。
 *
 * 排版对齐进度页：顶部一张总进度卡，下面是逐项的行（最新在前）。1Hz 走时钟，
 * 数据新鲜度、速率、剩余时间都只能在时间轴上算。
 */
@Composable
fun SlimContent(
    state: SlimState,
    innerPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    onStart: (Int) -> Unit,
    onStop: () -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            now = System.currentTimeMillis()
        }
    }
    val stale = state.received && now - state.updatedAt > SLIM_STALE_MS

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
        verticalArrangement = Arrangement.spacedBy(BlockGap)
    ) {
        item(key = "summary") {
            Box(Modifier.padding(top = SectionGap)) {
                SlimSummaryCard(state = state, now = now, stale = stale)
            }
        }
        item(key = "actions") {
            SlimActionRow(state = state, onStart = onStart, onStop = onStop)
        }
        if (state.items.isEmpty()) {
            item(key = "items_empty") {
                Box(Modifier.padding(horizontal = HintMargin)) {
                    Text(
                        text = stringResource(
                            if (state.received) R.string.slim_items_empty
                            else R.string.slim_waiting
                        ),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        } else {
            item(key = "items_title") {
                Box(Modifier.padding(horizontal = HintMargin)) {
                    Text(
                        text = stringResource(R.string.slim_items_title),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
            // key 用条目 id：列表最新在前、会不断插头，按下标绑定会让每一行都重新组合到别人的数据上
            items(state.items, key = { it.id }) { item ->
                SlimItemRow(item = item, modifier = Modifier.animateItem())
            }
            if (state.total > state.items.size) {
                item(key = "items_more") {
                    Box(Modifier.padding(horizontal = HintMargin)) {
                        Text(
                            text = stringResource(
                                R.string.slim_items_more,
                                state.total - state.items.size
                            ),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
        }
    }
}

/**
 * 总进度卡：整批的完成情况。
 *
 * 与进度页的 `TotalProgressCard` 同构 —— 标题 + 右侧「已完成 / 总数」+ 进度条 + 一行字节/速率。
 * 速率只在真的在跑、且有开始时刻时显示；剩余时间跟着速率一起给，写"约"。
 */
@Composable
private fun SlimSummaryCard(state: SlimState, now: Long, stale: Boolean) {
    Card(
        modifier = Modifier.padding(horizontal = ScreenMargin),
        insideMargin = PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(BlockGap)) {
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
                if (state.received && percent != null) {
                    Text(
                        text = stringResource(R.string.progress_percent, percent),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary
                    )
                }
            }
            if (!state.received) {
                // 一帧都没收到：可能相册没在跑，也可能模块还没注入。别把 0/0 当"已完成"展示
                Text(
                    text = stringResource(R.string.slim_waiting),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                return@Column
            }
            // total 还没数出来时进度条走不确定态，比画一条 0% 的实心条诚实
            LinearProgressIndicator(
                progress = if (state.total > 0) state.fraction else null,
                modifier = Modifier.fillMaxWidth(),
                height = SlimProgressHeight
            )
            Text(
                text = stringResource(
                    R.string.slim_state_line,
                    state.done,
                    state.total,
                    state.failed
                ),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.slim_saved, formatBytes(state.saved)),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                val rate = state.rate(now)
                val eta = state.etaMs(now)
                if (rate != null && eta != null) {
                    Text(
                        text = stringResource(R.string.slim_rate, rate) + " · " +
                            stringResource(R.string.slim_eta, durationText(eta)),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
            val current = state.current
            if (current != null) {
                Text(
                    text = stringResource(R.string.slim_current, middleEllipsis(current)),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            if (state.failed > 0) {
                // 失败数只在真的失败过时出现：0 是一直在的常态，写出来只会让人以为有问题
                Text(
                    text = stringResource(
                        R.string.slim_failed_line,
                        state.failed
                    ),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error
                )
            }
            if (stale) {
                Text(
                    text = stringResource(R.string.slim_stale),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 页内的动作行：一个主按钮（开始/停止）+ 相册自带优化入口。
 *
 * 不放进底栏：主界面已经有自己的底栏，再把这一页的按钮塞进 Scaffold 会变成两层底栏。
 */
@Composable
private fun SlimActionRow(state: SlimState, onStart: (Int) -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    var nativeUnavailable by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.padding(horizontal = ScreenMargin),
        insideMargin = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BlockGap)
        ) {
            if (state.running) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    // 已经点了停止就别再下发第二次，否则队列会收到一串重复的停止命令
                    enabled = !state.stopping
                ) {
                    Text(
                        text = stringResource(
                            if (state.stopping) R.string.slim_action_stopping
                            else R.string.slim_action_stop
                        )
                    )
                }
            } else {
                Button(
                    onClick = { onStart(DEFAULT_CONCURRENCY) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(
                        // 与设置页一致：默认禁用态白字压浅蓝底，几乎读不清
                        disabledContentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                ) {
                    Text(text = stringResource(R.string.slim_action_start))
                }
            }
            // 逃生口：相册被禁用/卸载时返回 false，就地提示，不弹对话框打断
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.slim_action_native),
                    modifier = Modifier.clickable {
                        nativeUnavailable = !openNativeSlim(context)
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (nativeUnavailable) {
                    Text(
                        text = stringResource(R.string.slim_native_unavailable),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * 一张照片占一行，排版照着进度页的 `TaskRow`：
 * 文件名 + 状态（处理中给一条不确定进度条，做完/失败给文字）+ 右侧这一项自己的耗时。
 *
 * 失败的原因单独一行写全 —— 一行省略号里塞不下 `IllegalStateException: …`，
 * 而用户看这一行就是为了知道"是哪张、为什么"。跳过（无需优化）同样归到这一栏。
 */
@Composable
private fun SlimItemRow(item: SlimItem, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.padding(horizontal = ScreenMargin),
        insideMargin = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BlockGap)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = middleEllipsis(item.displayName),
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.running) {
                    LinearProgressIndicator(
                        progress = null,
                        height = RowProgressHeight,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    text = itemStateText(item),
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (item.failed) MiuixTheme.colorScheme.error
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                val reason = item.reason
                if (reason != null) {
                    Text(
                        text = reasonText(reason),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (item.ms > 0L) {
                Text(
                    text = durationText(item.ms),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

/** 这一行的状态文字。进行中/完成/失败三种，其余（老载荷不认识的值）按完成画 */
@Composable
private fun itemStateText(item: SlimItem): String = when {
    item.running -> stringResource(R.string.slim_item_running)
    item.failed -> stringResource(R.string.slim_item_failed)
    else -> stringResource(R.string.slim_item_done)
}

/**
 * 失败原因。模块只对"跳过/查不到"这两种已知情况给固定标记，其余是异常原文，
 * 原文直接显示 —— 翻译不了它，但它是用户唯一能拿去搜的线索。
 */
@Composable
private fun reasonText(reason: String): String = when (reason) {
    REASON_SKIPPED -> stringResource(R.string.slim_reason_skipped)
    REASON_RESOLVE_FAILED -> stringResource(R.string.slim_reason_resolve_failed)
    else -> reason
}

/** 与 SlimWorker 里的 REASON_* 常量对应（模块进程与 UI 共用一个字符串即可，别在两边各改各的） */
private const val REASON_SKIPPED = "skipped"
private const val REASON_RESOLVE_FAILED = "resolve_failed"

/** 单项耗时的显示：<1s 给毫秒，<60s 给一位小数的秒，再往上给分秒 */
@Composable
private fun durationText(ms: Long): String {
    if (ms < 0L) {
        return stringResource(R.string.slim_duration_ms, 0)
    }
    if (ms < 1000L) {
        return stringResource(R.string.slim_duration_ms, ms)
    }
    val seconds = ms / 1000.0
    if (seconds < 60.0) {
        return stringResource(R.string.slim_duration_sec, seconds)
    }
    val minutes = (ms / 60_000L).toInt()
    val rest = ((ms % 60_000L) / 1000L).toInt()
    return stringResource(R.string.slim_duration_min, minutes, rest)
}

/** 状态卡的标题。跑着/收尾/做完/从没跑过，四种说法不能混 */
@Composable
private fun statusTitle(state: SlimState): String = when (state.state) {
    SlimProtocol.STATE_RUNNING -> stringResource(R.string.slim_status_running)
    SlimProtocol.STATE_STOPPING -> stringResource(R.string.slim_status_stopping)
    SlimProtocol.STATE_DONE -> stringResource(R.string.slim_status_done)
    else -> stringResource(R.string.slim_status_idle)
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
