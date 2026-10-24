package com.tongluosao.gallerysync.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tongluosao.gallerysync.R
import com.tongluosao.gallerysync.core.ProgressProtocol
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 多久没收到帧就认为通道断了。
 *
 * 心跳是 2Hz，但队列空着时会降到 5s 一帧，所以判定阈值必须大于 5s，否则队列一空
 * 界面就开始喊"连接可能已断开"。取 12s：漏掉两帧空闲心跳才算异常。
 */
private const val STALE_MS = 12_000L

/** 行内缩略图的边长 */
private val RowThumbSize = 40.dp

/** 行内缩略图的圆角 */
private val RowThumbCorner = 6.dp

/**
 * 行内进度条的高度。
 *
 * <p>不用 Miuix 默认的 6dp：列表行里 4dp 更接近系统设置的观感，也让整行不至于被一条
 * 粗条压得头重脚轻。
 */
private val RowProgressHeight = 4.dp

/**
 * 进度页：一条普通的任务列表。
 *
 * 顶部总进度卡、权限横幅这些"整行"的东西照旧排在最前面，其余每一项一行：
 * 缩略图 + 文件名 + （在传时一条 Miuix 原生进度条，排队时一行"排队中"）+ 右侧百分比或体积。
 *
 * **不给任何行加变暗、模糊、退场之类的效果** —— 用户明确说不要了，普通进度条即可。
 */
@Composable
fun ProgressContent(
    progress: ProgressModel,
    innerPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    onRequestMediaPermission: () -> Unit
) {
    val state = progress.ui
    // 1Hz 走时钟：数据新鲜度只能在时间轴上判，而帧本身只带发送时刻
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            now = System.currentTimeMillis()
        }
    }
    val stale = state.received && now - state.timestamp > STALE_MS

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
        if (!progress.mediaPermission) {
            item(key = "banner_permission") {
                Box(Modifier.padding(vertical = SectionGap)) {
                    PermissionBanner(onRequestMediaPermission)
                }
            }
        }
        item(key = "summary") {
            Box(Modifier.padding(vertical = SectionGap)) {
                TotalProgressCard(state, stale)
            }
        }
        // key 用帧里带的去重键（路径+大小+时间），不是列表下标：
        // 一项传完消失时，剩下的行不该被重新绑定到别人的数据上
        items(state.tasks, key = { it.key }) { task ->
            TaskRow(
                task = task,
                permissionGranted = progress.mediaPermission,
                modifier = Modifier.animateItem()
            )
        }
        if (state.queuedHidden > 0) {
            item(key = "queued_more") {
                Box(Modifier.padding(top = BlockGap)) {
                    QueuedMore(state.queuedHidden)
                }
            }
        }
        if (state.tasks.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.padding(top = SectionGap)) {
                    EmptyHint(everReceived = progress.everReceived, stale = stale)
                }
            }
        }
    }
}

/**
 * 一项任务占一行。
 *
 * 正在传的：文件名下面一条按字节占比增长的进度条，右侧是百分比（下载项前面带「下载」）。
 * 排队中的：文件名下面一行"排队中"，右侧是这一项的体积。
 */
@Composable
private fun TaskRow(
    task: ProgressTask,
    permissionGranted: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.padding(horizontal = ScreenMargin),
        insideMargin = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BlockGap)
        ) {
            RowThumbnail(task.path, task.video, permissionGranted)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = task.name,
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (task.uploading) {
                    LinearProgressIndicator(
                        progress = task.fraction,
                        height = RowProgressHeight,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = stringResource(
                            if (task.downloading) R.string.progress_task_queued_down
                            else R.string.progress_task_queued
                        ),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
            Text(
                text = trailingText(task),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

/** 右侧那一段：在传时给百分比，排队时给体积 */
@Composable
private fun trailingText(task: ProgressTask): String {
    val percent = task.percent
    if (percent == null) {
        return formatBytes(task.size)
    }
    return if (task.downloading) {
        stringResource(R.string.progress_percent_down, percent)
    } else {
        stringResource(R.string.progress_percent, percent)
    }
}

/**
 * 行内缩略图。没有权限或解码失败时退回占位底 —— 进度动画不依赖图片，
 * 所以读不到图不该让整行塌掉。
 */
@Composable
private fun RowThumbnail(path: String, video: Boolean, permissionGranted: Boolean) {
    val thumbnail = rememberThumbnail(path, video, permissionGranted)
    Box(
        modifier = Modifier
            .size(RowThumbSize)
            .clip(RoundedCornerShape(RowThumbCorner))
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.surfaceContainer)
            )
        }
    }
}

/** 顶部总进度：本轮已完成 / 总量，按字节算，另附队列里还没轮到的项数 */
@Composable
private fun TotalProgressCard(state: ProgressUiState, stale: Boolean) {
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
                    text = stringResource(R.string.progress_total),
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(
                        R.string.progress_items,
                        state.doneItems,
                        state.totalItems
                    ),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            LinearProgressIndicator(
                progress = state.fraction,
                modifier = Modifier.fillMaxWidth(),
                height = 8.dp
            )
            Text(
                text = stringResource(
                    R.string.progress_bytes,
                    formatBytes(state.doneBytes),
                    formatBytes(state.totalBytes)
                ) + "  ·  " + stringResource(
                    R.string.progress_queue,
                    state.pending,
                    state.pendingPhotos,
                    state.pendingVideos
                ),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            // 两条提示是并列的，可能同时成立（例如熔断的同时通道也断了）
            if (state.unhealthy) {
                AlertText(stringResource(R.string.progress_unhealthy))
            }
            if (stale) {
                AlertText(stringResource(R.string.progress_stale))
            }
        }
    }
}

/** 一帧只带得下前几十项，剩下的用一行数字交代，别让用户以为队列就这么点 */
@Composable
private fun QueuedMore(hidden: Int) {
    Text(
        text = stringResource(R.string.progress_queued_more, hidden),
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = HintMargin)
    )
}

/**
 * 缩略图。没有权限或解码失败时返回 null，行里退回占位底 —— 进度条不依赖图片，
 * 所以读不到图不该让整页变成空白。
 */
@Composable
private fun rememberThumbnail(
    path: String,
    video: Boolean,
    enabled: Boolean,
    targetPx: Int = ThumbnailLoader.ROW_PX
): ImageBitmap? {
    var bitmap by remember(path, targetPx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, enabled, targetPx) {
        bitmap = if (enabled) ThumbnailLoader.load(path, video, targetPx) else null
    }
    return bitmap
}

@Composable
private fun PermissionBanner(onRequest: () -> Unit) {
    Card(
        modifier = Modifier.padding(horizontal = ScreenMargin),
        insideMargin = PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(BlockGap)) {
            AlertText(stringResource(R.string.progress_permission_denied))
            Button(onClick = onRequest, minWidth = 96.dp) {
                Text(text = stringResource(R.string.action_grant_permission))
            }
        }
    }
}

@Composable
private fun EmptyHint(everReceived: Boolean, stale: Boolean) {
    val text = when {
        !everReceived -> stringResource(R.string.progress_no_data)
        stale -> stringResource(R.string.progress_stale)
        else -> stringResource(R.string.progress_idle)
    }
    Card(
        modifier = Modifier.padding(horizontal = ScreenMargin),
        insideMargin = PaddingValues(16.dp)
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

@Composable
private fun AlertText(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.error
    )
}

/**
 * 人类可读的字节数。进度页上"12345678 字节"没有意义，用户只关心 11.8 MB 还是 1.2 GB。
 * 用 1024 进制并保留一位小数；小于 1KB 时直接给整数，避免出现 "0.0 KB"。
 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 0L) {
        return "0 B"
    }
    if (bytes < 1024L) {
        return "$bytes B"
    }
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.size - 1) {
        value /= 1024.0
        unit++
    }
    return String.format("%.1f %s", value, units[unit])
}
