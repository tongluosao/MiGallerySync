package com.tongluosao.gallerysync.ui

import androidx.compose.ui.unit.dp

/**
 * 两个页面共用的排版基线。
 *
 * 卡片与输入框统一 12dp 外边距，卡片内文字与 Preference 自带 16dp 内边距，
 * 两者相加正好落在 SmallTitle 默认的 28dp 内缩上——标题、卡片文字、提示文字同一条竖线。
 * 进度页沿用同一套数字，切页时左边缘不会跳。
 */
internal val ScreenMargin = 12.dp

/** 提示文字与卡片内文字对齐（12 + 16） */
internal val HintMargin = 28.dp

/** 同组内相邻块的间距 */
internal val BlockGap = 12.dp

/** 提示与它所解释的输入框之间的间距，比块间距紧一点，视觉上才像一对 */
internal val HintGap = 8.dp

/** 分组之间的间距，叠上 SmallTitle 自带的 8dp 上内边距后约 16dp */
internal val SectionGap = 8.dp

/** 列表底部留白，避免最后一项贴着导航栏 */
internal val BottomSpace = 24.dp
