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
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
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

/** 汇总卡里进度条的高度，跟优化页保持一致 */
private val SummaryProgressHeight = 8.dp

/**
 * 进度页：上半「上传」、下半「下载」，两块结构完全一样。
 *
 * <p>为什么要分块：上传是"把本机照片推到 WebDAV"，下载是"把云端原图拉回平板"，
 * 两条链路各走各的队列、各占各的带宽。以前把下载挤成上传卡旁边的一栏，用户既看不出
 * 谁在动，也没地方挂其中一边的整体速度。现在两块各占约一半、各自滚动，
 * 数据不同但渲染走同一个 [ProgressBlock]，字号、进度条、间距天然一致。
 *
 * <p>**不给任何行加变暗、模糊、退场之类的效果** —— 用户明确说不要了，普通进度条即可。
 */
@Composable
fun ProgressContent(
    progress: ProgressModel,
    innerPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    onRequestMediaPermission: () -> Unit
) {
    val state = progress.ui
    // 1Hz 走时钟：数据新鲜度只能在时间轴上判，而帧本身只带发送时刻；速度窗口也靠它滑动
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            now = System.currentTimeMillis()
        }
    }
    val stale = state.received && now - state.timestamp > STALE_MS

    val uploadSpeed = DownloadSpeed.bytesPerSecond(progress.uploadSpeedSamples, now)
    val downloadSpeed = DownloadSpeed.bytesPerSecond(progress.speedSamples, now)
    val uploadSection = ProgressSections.upload(state, uploadSpeed)
    val downloadSection = ProgressSections.download(state, downloadSpeed)
    val uploadRows = state.uploads
    val downloadRows = state.downloads

    // 折叠状态按块各存一份：展开上传不该把下载也展开。转屏后要留在原样
    var uploadExpanded by rememberSaveable { mutableStateOf(false) }
    var downloadExpanded by rememberSaveable { mutableStateOf(false) }

    val (uploadWeight, downloadWeight) = ProgressSections.blockWeights(
        uploadRows.size,
        downloadRows.size
    )
    val direction = LocalLayoutDirection.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = innerPadding.calculateStartPadding(direction),
                end = innerPadding.calculateEndPadding(direction),
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding()
            )
            // 两条列表共用一条嵌套滚动连接：顶栏收缩只该有一套状态
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) {
        // 页面级提示都跟"哪个方向"无关：通道没连上、数据过期、WebDAV 挂了。
        // 放进某一块的卡里会让人以为只有那个方向有问题
        if (!progress.mediaPermission) {
            PermissionBanner(onRequestMediaPermission)
        }
        if (!progress.everReceived) {
            NoticeLine(stringResource(R.string.progress_no_data))
        } else if (stale) {
            NoticeLine(stringResource(R.string.progress_stale))
        }
        if (state.unhealthy) {
            NoticeLine(stringResource(R.string.progress_unhealthy), error = true)
        }

        ProgressBlock(
            title = stringResource(R.string.progress_upload_section),
            section = uploadSection,
            rows = uploadRows,
            speeds = progress.taskSpeeds,
            nowMs = now,
            emptyText = stringResource(R.string.progress_upload_idle),
            permissionGranted = progress.mediaPermission,
            expanded = uploadExpanded,
            onToggleExpanded = { uploadExpanded = !uploadExpanded },
            footerNote = if (state.queuedHidden > 0) {
                stringResource(R.string.progress_queued_more, state.queuedHidden)
            } else {
                null
            },
            // 没内容就不参与分配高度：空区块按内容高，另一边拿走剩下的空间
            modifier = if (uploadRows.isEmpty()) Modifier else Modifier.weight(uploadWeight)
        )
        ProgressBlock(
            title = stringResource(R.string.progress_download_section),
            section = downloadSection,
            rows = downloadRows,
            speeds = progress.taskSpeeds,
            nowMs = now,
            emptyText = stringResource(R.string.progress_download_idle),
            permissionGranted = progress.mediaPermission,
            expanded = downloadExpanded,
            onToggleExpanded = { downloadExpanded = !downloadExpanded },
            footerNote = null,
            modifier = if (downloadRows.isEmpty()) Modifier else Modifier.weight(downloadWeight)
        )
    }
}

/**
 * 一个区块：标题 + 汇总卡 + 逐项列表，上传与下载共用这一个 composable。
 *
 * <p>[section] 决定汇总卡上的数字，[rows] 决定列表，两者来自同一个方向 —— 这里不认识
 * "上传"或"下载"，只认识"一份汇总 + 一列任务"，所以两块的眼睛看过去必须长得一样。
 *
 * <p>列表里的行数超过阈值就折叠：默认只留进行中的前几项与最近完成的几项，
 * 折叠按钮放在滚动区**外面**，滚到列表底部也还能点。
 */
@Composable
private fun ProgressBlock(
    title: String,
    section: SectionProgress,
    rows: List<ProgressTask>,
    speeds: TaskSpeeds,
    nowMs: Long,
    emptyText: String,
    permissionGranted: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    footerNote: String?,
    modifier: Modifier = Modifier
) {
    val fold = ProgressSections.fold(rows, expanded)
    Column(modifier = modifier.fillMaxWidth()) {
        SmallTitle(text = title)
        // 一条数据都没有时整张汇总卡都不出现：半屏一张"已完成 0 / 0 项 · 0%"的卡片
        // 就是用户说的那种无意义空态，那时只有下面那句"暂无进行中的…"
        if (section.totalItems > 0 || section.totalBytes > 0L) {
            SectionSummaryCard(section)
        }
        if (rows.isEmpty()) {
            // 空态只说一句"暂无进行中的…"，不报"0 项/0 B"这种没信息的数字。
            // 不加 weight：之前它在半屏高的盒子里垂直居中，两句"暂无"之间隔着一整屏空白，
            // 看着像页面坏了。现在紧贴标题，和下面的区块连成一段。
            Text(
                text = emptyText,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = HintMargin, end = ScreenMargin, top = SectionGap)
            )
            return@Column
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .overScrollVertical(),
            contentPadding = PaddingValues(top = SectionGap, bottom = BottomSpace),
            verticalArrangement = Arrangement.spacedBy(BlockGap)
        ) {
            // key 用帧里带的去重键（路径+大小+时间），不是列表下标：
            // 一项传完消失时，剩下的行不该被重新绑定到别人的数据上
            items(fold.visible, key = { it.key }) { task ->
                TaskRow(
                    task = task,
                    speedBytesPerSecond = speeds.rate(task.key, nowMs),
                    permissionGranted = permissionGranted,
                    modifier = Modifier.animateItem()
                )
            }
        }
        if (footerNote != null) {
            Text(
                text = footerNote,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = HintMargin)
            )
        }
        if (fold.canExpand || fold.canCollapse) {
            // Miuix 0.9.4 没有「可展开卡片」这种容器；带箭头的 Preference 行就是这套设计里
            // "点了会展开"的标准写法，与上面的卡片同宽、同圆角，收起时也不用换一种控件
            Card(
                modifier = Modifier
                    .padding(horizontal = ScreenMargin)
                    .padding(top = SectionGap)
            ) {
                ArrowPreference(
                    title = if (fold.canExpand) {
                        stringResource(R.string.progress_expand_all, fold.hidden)
                    } else {
                        stringResource(R.string.progress_collapse)
                    },
                    onClick = onToggleExpanded
                )
            }
        }
    }
}

/**
 * 区块的汇总卡：已完成/总数、百分比、进度条、总速度、剩余项与剩余时间。
 *
 * <p>四行信息固定在同一个顺序上（条数 → 条 → 速度 → 剩多少），上下两块因此可以
 * 直接对比着看；数字不成立时整行让位，不留一个空标签。
 */
@Composable
private fun SectionSummaryCard(section: SectionProgress) {
    Card(
        modifier = Modifier.padding(horizontal = ScreenMargin),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.progress_items,
                        section.doneItems,
                        section.totalItems
                    ),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.progress_percent, section.percent),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            LinearProgressIndicator(
                progress = section.fraction,
                modifier = Modifier.fillMaxWidth(),
                height = SummaryProgressHeight
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = speedText(section),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = remainingText(section),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

/** 左侧那一段：有活时报总速度，干完了就报"全部完成"，别挂一个 0 B/s */
@Composable
private fun speedText(section: SectionProgress): String {
    return if (!section.busy && section.totalItems > 0) {
        stringResource(R.string.progress_all_done)
    } else {
        stringResource(
            R.string.progress_speed,
            ProgressSections.formatRate(section.speedBytesPerSecond)
        )
    }
}

/** 右侧那一段：剩余项数，能估出时间就把时间接在后面 */
@Composable
private fun remainingText(section: SectionProgress): String {
    if (section.remainingItems <= 0 && section.totalItems > 0) {
        return ""
    }
    val items = stringResource(R.string.progress_remaining_items, section.remainingItems)
    val seconds = section.remainingSeconds ?: return items
    return items + " · " + etaText(seconds)
}

@Composable
private fun etaText(seconds: Long): String {
    return when {
        seconds < 60L -> stringResource(R.string.progress_eta_seconds, seconds)
        seconds < 3600L -> stringResource(
            R.string.progress_eta_minutes,
            seconds / 60L,
            seconds % 60L
        )
        else -> stringResource(
            R.string.progress_eta_hours,
            seconds / 3600L,
            (seconds % 3600L) / 60L
        )
    }
}

/**
 * 一项任务占一行：缩略图 + 文件名 + 进度条，右侧是百分比与这一项自己的速度。
 *
 * <p>速度是逐项算的（见 [ProgressSections.trackTaskSpeeds]）：只显示聚合速度时，
 * 3 路并行看到 6 MB/s 却不知道是哪一项在拖后腿。
 */
@Composable
private fun TaskRow(
    task: ProgressTask,
    speedBytesPerSecond: Long,
    permissionGranted: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.padding(horizontal = ScreenMargin),
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
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
                if (task.uploading || task.completed) {
                    LinearProgressIndicator(
                        progress = task.fraction,
                        height = RowProgressHeight,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = stringResource(R.string.progress_task_queued),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = trailingText(task),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                val speed = taskSpeedText(task, speedBytesPerSecond)
                if (speed != null) {
                    Text(
                        text = speed,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}

/** 右侧第一段：刚完成给"已完成"，在传给百分比，排队时给体积 */
@Composable
private fun trailingText(task: ProgressTask): String {
    if (task.completed) {
        return stringResource(R.string.progress_task_done)
    }
    val percent = task.percent
    if (percent == null) {
        return formatBytes(task.size)
    }
    return stringResource(R.string.progress_percent, percent)
}

/** 右侧第二段：只有真在传、且速度已经能算出来时才显示 */
@Composable
private fun taskSpeedText(task: ProgressTask, speedBytesPerSecond: Long): String? {
    if (!task.uploading || speedBytesPerSecond <= 0L) {
        return null
    }
    return ProgressSections.formatRate(speedBytesPerSecond)
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
        modifier = Modifier
            .padding(horizontal = ScreenMargin, vertical = 4.dp),
        insideMargin = PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(BlockGap)) {
            Text(
                text = stringResource(R.string.progress_permission_denied),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error
            )
            Button(onClick = onRequest, minWidth = 96.dp) {
                Text(text = stringResource(R.string.action_grant_permission))
            }
        }
    }
}

/** 页面级提示：与方向无关的通道/服务状态，一行字，不占卡片的垂直空间 */
@Composable
private fun NoticeLine(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = if (error) {
            MiuixTheme.colorScheme.error
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
        modifier = Modifier.padding(horizontal = HintMargin, vertical = 2.dp)
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
