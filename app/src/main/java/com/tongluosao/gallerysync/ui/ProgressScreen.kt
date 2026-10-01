package com.tongluosao.gallerysync.ui

import android.annotation.SuppressLint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tongluosao.gallerysync.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
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

/** 方格之间的缝隙。相册就是这么紧，大了就不像相册了 */
private val TileGap = 3.dp

/** 方格圆角 */
private val TileCorner = 8.dp

/**
 * 排队中（还没轮到）的压暗量。
 *
 * <p>水波时代这个值来自水色调色板，现在固定下来：0.28 是"一眼看出还没轮到、但还看得出
 * 拍的是什么"的折中 —— 再暗就成了空白格，再亮就跟正在传的分不出来。
 */
private const val QUEUED_DIM = 0.28f

/**
 * 方格底部那条进度条的高度。
 *
 * <p>不用 Miuix 默认的 6dp：格子只有屏幕的三分之一宽，压在照片上的细条要更细才不像
 * 贴了一条胶带；4dp 在这个尺寸下仍然看得清增长。
 */
private val TileProgressHeight = 4.dp

/**
 * 退场动画时长。
 *
 * <p>一格的进度走到 1.0 时它**不会**消失（那时只是暗了一半），真正消失是 worker 把它
 * 从队列里摘掉、下一帧里不再带它的时候。这个时长就是给那一刻留的：先压到全黑，再上模糊，
 * 最后整体淡出。
 */
private const val EXIT_MS = 460L

/**
 * 一个方格项。[exiting] 表示它已经从帧里消失了、正在播退场动画。
 */
private data class TileEntry(val tile: UploadTile, val exiting: Boolean)

/**
 * 把帧里的方格列表变成"粘性"的：消失的项先标记退场，动画播完才真正移除。
 *
 * <p>为什么要粘住：Compose 的 item 一旦从列表移除就不再组合，写在它内部的动画会当场
 * 中断，"全黑 + 模糊"根本放不完。多留 [EXIT_MS] 毫秒才播得出来。
 *
 * <p>退场中的项**留在原来的位置**，所以后面的格子会在它彻底消失之后才补位 ——
 * 顺序是"先看它沉没，再补位"，而不是边沉边挤。
 */
@Composable
private fun rememberStickyTiles(tiles: List<UploadTile>): SnapshotStateList<TileEntry> {
    val entries = remember { mutableStateListOf<TileEntry>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(tiles) {
        val byKey = tiles.associateBy { it.key }
        for (i in entries.indices) {
            val entry = entries[i]
            val fresh = byKey[entry.tile.key]
            if (fresh != null) {
                // 原地更新：key 没变就不该换位置，水面也不该跳
                entries[i] = entry.copy(tile = fresh, exiting = false)
            } else if (!entry.exiting) {
                entries[i] = entry.copy(exiting = true)
                val key = entry.tile.key
                scope.launch {
                    delay(EXIT_MS)
                    val at = entries.indexOfFirst { it.tile.key == key }
                    // 二次确认：这 [EXIT_MS] 里它可能又被重新入队（重试），那就别删
                    if (at >= 0 && entries[at].exiting) {
                        entries.removeAt(at)
                    }
                }
            }
        }
        val known = entries.mapTo(HashSet()) { it.tile.key }
        for (tile in tiles) {
            if (known.add(tile.key)) {
                entries.add(TileEntry(tile, exiting = false))
            }
        }
    }
    return entries
}

/**
 * 进度页：相册式的方格墙。
 *
 * 顶部总进度卡、权限横幅这些"整行"的东西用 [GridItemSpan] 占满一行，其余格子一视同仁 ——
 * 正在传的那几格不是被拎到最前面，而是在**它原来的队列位置**上长出水面，所以任何一格
 * 开始上传或传完，其余格子只是原地滑动补位，不会整片重排。
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

    // 粘住刚消失的格子，让"全黑 + 模糊"的退场动画有机会放完
    val entries = rememberStickyTiles(state.tiles)

    val direction = LocalLayoutDirection.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        // 贴屏幕边（相册的做法）：只留 innerPadding，不再额外内缩。
        // 整行的卡片自己带 ScreenMargin，所以它们仍与设置页对齐
        contentPadding = PaddingValues(
            start = innerPadding.calculateStartPadding(direction),
            end = innerPadding.calculateEndPadding(direction),
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + BottomSpace
        ),
        horizontalArrangement = Arrangement.spacedBy(TileGap),
        verticalArrangement = Arrangement.spacedBy(TileGap)
    ) {
        if (!progress.mediaPermission) {
            item(key = "banner_permission", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.padding(vertical = SectionGap)) {
                    PermissionBanner(onRequestMediaPermission)
                }
            }
        }
        item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.padding(vertical = SectionGap)) {
                TotalProgressCard(state, stale)
            }
        }
        // key 用帧里带的去重键（路径+大小+时间），不是列表下标：
        // 一格传完消失时，剩下的格子不该被重新绑定到别人的数据上，水面也不该跳
        items(entries, key = { it.tile.key }) { entry ->
            PhotoTile(
                tile = entry.tile,
                exiting = entry.exiting,
                permissionGranted = progress.mediaPermission,
                modifier = Modifier.animateItem()
            )
        }
        if (state.queuedHidden > 0) {
            item(key = "queued_more", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.padding(top = BlockGap)) {
                    QueuedMore(state.queuedHidden)
                }
            }
        }
        // 用 entries 判空：正在播退场动画的格子还占着位置，这时不该冒出"队列空闲"
        if (entries.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.padding(top = SectionGap)) {
                    EmptyHint(everReceived = progress.everReceived, stale = stale)
                }
            }
        }
    }
}

/**
 * 一个方格。
 *
 * 正在上传的：照片底部有一条细进度条，按字节占比增长；传满后仍看得见（等复核把它摘掉）。
 * 排队中的：压暗表示"还没轮到"。不给格子塞百分比文字 —— 相册的方格是纯图，塞满文字就不像相册了。
 *
 * [exiting] 为真表示它已经从帧里消失，正在播"全黑 + 模糊 → 淡出"的退场动画。
 */
@SuppressLint("NewApi")
@Composable
private fun PhotoTile(
    tile: UploadTile,
    exiting: Boolean,
    permissionGranted: Boolean,
    modifier: Modifier = Modifier
) {
    val thumbnail = rememberThumbnail(tile.path, tile.video, permissionGranted)
    // 500ms 线性插值把 2Hz 的帧桥成连续的增长
    val fraction = animateFloatAsState(
        targetValue = tile.fraction,
        animationSpec = tween(durationMillis = 500, easing = LinearEasing),
        label = "sink"
    )
    // 退场：0 → 1。压黑 → 模糊 → 淡出，三段在一条时间轴上
    val exit = remember { Animatable(0f) }
    LaunchedEffect(exiting) {
        if (exiting) {
            exit.animateTo(1f, tween(durationMillis = EXIT_MS.toInt(), easing = LinearEasing))
        } else {
            exit.snapTo(0f)
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(TileCorner))
            .graphicsLayer {
                // 在 lambda 里读 exit，只让图层失效，不触发重组
                val e = exit.value
                // 淡出压到后半程才开始：前面先让用户看清它变黑
                alpha = (2.5f - 2.5f * e).coerceIn(0f, 1f)
                val radius = e * 18.dp.toPx()
                // 模糊要 API 31；低版本上就只有"变黑 + 淡出"，不影响可用性
                renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && radius > 0.5f) {
                    RenderEffect
                        .createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                        .asComposeRenderEffect()
                } else {
                    null
                }
            }
    ) {
        // 内层只放照片本体。clip 在外、blur 在 graphicsLayer 上的顺序不能反，
        // 否则模糊会糊到圆角外面去
        Box(modifier = Modifier.matchParentSize()) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            } else {
                // 没授权或解码失败时给一块中性底，不至于整格是空的
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                )
            }
        }
        if (!tile.uploading) {
            // 排队中：压暗，一眼看出"还没轮到"
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = QUEUED_DIM))
            )
        } else {
            // 底部压一层黑：进度条要压在任意一张照片上都看得清
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.62f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.55f)
                        )
                    )
            )
            // 进度条用 Miuix 原生实现。它自带圆角与 determinate 的插值，
            // 比自绘的水面既省一层渲染，观感也跟系统/相册一致
            LinearProgressIndicator(
                progress = fraction.value,
                colors = ProgressIndicatorDefaults.progressIndicatorColors(
                    foregroundColor = MiuixTheme.colorScheme.primary,
                    // 默认轨道色是 secondaryContainer，压在照片上对比不足，
                    // 会跟照片里的浅色区域糊在一起
                    backgroundColor = Color.White.copy(alpha = 0.25f)
                ),
                height = TileProgressHeight,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        // 退场用的黑幕，盖在所有内容之上（包括百分比文字）。0.7 处就到全黑，
        // 之后剩下的时间交给模糊与淡出 —— 这就是"到达全黑 + 模糊后消失"
        if (exiting) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = (exit.value * 1.43f).coerceAtMost(1f) }
                    .background(Color.Black)
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
                    formatBytes(state.totalBytes),
                    state.pending
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

/** 方格只带得下前几十项，剩下的用一行数字交代，别让用户以为队列就这么点 */
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
 * 缩略图。没有权限或解码失败时返回 null，格子会退回占位底 —— 水面动画不依赖图片，
 * 所以读不到图不该让整页变成空白。
 */
@Composable
private fun rememberThumbnail(
    path: String,
    video: Boolean,
    enabled: Boolean,
    targetPx: Int = ThumbnailLoader.TILE_PX
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
