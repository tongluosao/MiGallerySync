package com.tongluosao.gallerysync.ui

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tongluosao.gallerysync.R
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

/** 照片卡片的高宽比。1.6 比正方形矮一些，三张在传时一屏能看到两张多一点 */
private const val PHOTO_RATIO = 1.6f

/** 照片卡片的圆角，与 Miuix 卡片的默认值一致 */
private val PhotoCorner = 16.dp

/** 排队行的小缩略图边长。一眼能认出是哪张就够，不做大图 */
private val QueuedThumb = 44.dp

/** 排队行缩略图的圆角，比卡片小一号 */
private val QueuedCorner = 10.dp

/** 进度页的滚动区。与设置页同一套内边距，切页时左边缘与底部留白不会跳 */
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
        verticalArrangement = Arrangement.spacedBy(SectionGap)
    ) {
        if (!progress.mediaPermission) {
            item(key = "banner_permission") {
                PermissionBanner(onRequestMediaPermission)
            }
        }
        item(key = "summary") {
            TotalProgressCard(state, stale)
        }
        // 在传的项：key 用帧里带的去重键（路径+大小+时间），不是列表下标 ——
        // 一张传完消失时，剩下的卡片不该被重新绑定到别人的数据上，水位也不该跳
        items(state.active, key = { it.key }) { item ->
            UploadPhotoCard(item, progress.mediaPermission)
        }
        // 还没轮到的项。相册一轮能把上万项交给队列，进度页只显示正在传的 3 张的话，
        // 用户完全看不出后面还积压了多少 —— 这一段的数字才是"还要多久"的答案
        if (state.queued.isNotEmpty()) {
            item(key = "queued_header") {
                QueuedHeader(state.pending)
            }
            // 前缀 q：排队项的 key 与大卡片的 key 是同一套（同一个任务），
            // 万一某一帧去重没赶上，也不至于让 LazyColumn 撞上重复 key
            items(state.queued, key = { "q" + it.key }) { item ->
                QueuedRow(item, progress.mediaPermission)
            }
            if (state.queuedHidden > 0) {
                item(key = "queued_more") {
                    QueuedMore(state.queuedHidden)
                }
            }
        }
        // 两个列表都空才叫"没有在传"。只在 active 空时说这句是错的：
        // 熔断或批间空隙时在传集合会空一下，而队列里还压着几千项
        if (state.active.isEmpty() && state.queued.isEmpty()) {
            item(key = "empty") {
                EmptyHint(everReceived = progress.everReceived, stale = stale)
            }
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

/**
 * 一张正在传的照片：缩略图打底，水面从下往上升，粒子从水面往下飘。
 *
 * 传完的项下一帧就不在列表里了，卡片随之消失 —— 不需要额外的退场动画，
 * 用户看的是"还剩哪几张"，不是"刚才那张去哪了"。
 */
@Composable
private fun UploadPhotoCard(item: ActiveUpload, permissionGranted: Boolean) {
    val thumbnail = rememberThumbnail(item.path, item.video, permissionGranted)
    Card(
        modifier = Modifier.padding(horizontal = ScreenMargin),
        // 图片要贴到卡片边缘，内边距由卡片内部的文字自己加
        insideMargin = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PHOTO_RATIO)
                .clip(RoundedCornerShape(PhotoCorner))
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            } else {
                // 没授权或解码失败时给一块中性底：水面动画照常画，不至于整张卡片是空的
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                )
            }
            WaterOverlay(
                fraction = item.fraction,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.matchParentSize()
            )
            // 底部压一层黑：白字要压在任意一张照片上都读得清
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.6f)
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = item.name,
                    color = Color.White,
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        R.string.progress_item_detail,
                        (item.fraction * 100).toInt(),
                        formatBytes(item.sent),
                        formatBytes(item.size)
                    ),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MiuixTheme.textStyles.footnote1
                )
            }
        }
    }
}

/**
 * 排队区的标题：交代后面还有多少项没轮到。
 *
 * 数字用队列的总量（[ProgressUiState.pending]）而不是列表长度 —— 列表只带前几十条。
 */
@Composable
private fun QueuedHeader(pending: Int) {
    Text(
        text = stringResource(R.string.progress_queued, pending),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = HintMargin)
    )
}

/**
 * 排队中的一项：小缩略图 + 文件名 + 体积。
 *
 * 不画水面 —— 它还没开始传，水位恒为 0，画出来只是一层空动画；而且几十行各自挂着
 * 无限动画，滚动时白白吃掉帧。传到了自然会升成大卡片。
 */
@Composable
private fun QueuedRow(item: QueuedUpload, permissionGranted: Boolean) {
    val thumbnail =
        rememberThumbnail(item.path, item.video, permissionGranted, ThumbnailLoader.ROW_PX)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HintMargin),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(QueuedThumb)
                .clip(RoundedCornerShape(QueuedCorner))
                .background(MiuixTheme.colorScheme.surfaceContainer)
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MiuixTheme.textStyles.body1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatBytes(item.size),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

/** 列表带不下的部分。不写这一行，用户会以为队列真的只有这几十项 */
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
 * 缩略图。没有权限或解码失败时返回 null，卡片会退回占位底 —— 进度动画不依赖图片，
 * 所以读不到图不该让整页变成空白。
 *
 * @param targetPx 期望边长。卡片和排队行差一个数量级，用同一张 512px 的图渲染 44dp
 *                 的行是白占内存，所以缓存键里带上尺寸，两种规格各存一份
 */
@Composable
private fun rememberThumbnail(
    path: String,
    video: Boolean,
    enabled: Boolean,
    targetPx: Int = ThumbnailLoader.CARD_PX
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
