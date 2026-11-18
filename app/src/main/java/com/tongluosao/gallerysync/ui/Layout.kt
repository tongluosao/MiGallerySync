package com.tongluosao.gallerysync.ui

import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.SmallTitleDefaults

/**
 * 两个页面共用的排版基线。
 *
 * <p>两条竖线不从零算，而是直接取 Miuix 自己的内边距：
 * - 标题行（[SmallTitle]）左缩进 28dp，卡片里每一行（[BasicComponent]）自带 16dp 内边距，
 *   卡片再让出 12dp 外边距，三者落在同一条竖线上。
 * - 提示文字按标题那条线对齐，所以 [HintMargin] 就是 `SmallTitleDefaults` 的起始内边距；
 *   [ScreenMargin] 由它减去卡片行自己的内边距得到 —— 换主题或改 Miuix 默认值时自动跟着走。
 */
internal val HintMargin: Dp
    @Composable get() = SmallTitleDefaults.InsideMargin
        .calculateStartPadding(LocalLayoutDirection.current)

/** 卡片外边距：提示线与卡片内文字之间隔着的正好是 Preference 自带的 16dp */
internal val ScreenMargin: Dp
    @Composable get() = HintMargin -
        BasicComponentDefaults.InsideMargin.calculateStartPadding(LocalLayoutDirection.current)

/*
 * 下面三个是「块与块之间」的缝隙。Miuix 0.9.4 没有公开间距 token（只有各自组件内部的
 * InsideMargin），所以只能按 8dp 网格给数值；改它们只会影响缝隙，不会破坏上面那条对齐线。
 */

/** 同组内相邻块的间距 */
internal val BlockGap = 12.dp

/** 提示与它所解释的输入框之间的间距，比块间距紧一点，视觉上才像一对 */
internal val HintGap = 8.dp

/** 分组之间的间距，叠上 SmallTitle 自带的 8dp 上内边距后约 16dp */
internal val SectionGap = 8.dp

/** 列表底部留白，避免最后一项贴着导航栏 */
internal val BottomSpace = 24.dp
