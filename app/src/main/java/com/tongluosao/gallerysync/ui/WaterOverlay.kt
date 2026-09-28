package com.tongluosao.gallerysync.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 一个从水面往下飘的粒子。
 *
 * 全部字段在 `remember` 里一次性算好，draw 阶段只做算术 —— 每帧新建对象会让
 * 动画在下一次 GC 时抖一下，而这里一秒要画 60 帧。
 */
private class Drop(
    /** 横向基准位置，0..1 */
    val baseX: Float,
    /** 下落相位，0..1；让 14 个粒子错开出发 */
    val life: Float,
    /** 每秒走完几趟，0.6..1.4 */
    val speed: Float,
    /** 横向摆幅（dp） */
    val sway: Float,
    /** 半径（dp） */
    val radius: Float,
    /** 摆动相位 */
    val seed: Float
)

/** 粒子数。上限压在 16 以内：再多肉眼分辨不出，只是白烧 GPU */
private const val DROP_COUNT = 14

/** 水面摇晃一圈的时长。太快像沸腾，太慢看不出在动 */
private const val WAVE_PERIOD_MS = 2000

/** 粒子从水面落到视野底部走完一趟的时长 */
private const val FALL_PERIOD_MS = 6000

/** 波形采样段数。28 段配两个正弦已经看不出折线 */
private const val WAVE_SEGMENTS = 28

/**
 * 盖在照片上的水位动画：**水面从下往上升起，粒子从水面发射后往下飘**。
 *
 * <p>方向是需求指定的。上传明明是"往上送"，水却是从下面涨上来的、颗粒被水面压着往下沉 ——
 * 两者反向反而更容易一眼看出这是上传；将来做下载时把 [down] 取反，
 * 水面从上往下、粒子往上飘，两个方向不会认错。
 *
 * <p>性能上的三个要点：
 * 1. 波形相位与下落时钟都在 `Canvas` 的 draw lambda **里面**读，失效只发生在绘制阶段，
 *    不会触发重组 —— 重组一帧要跑一遍整个卡片，60Hz 下根本扛不住。
 * 2. `Path` 与粒子数组都在 `remember` 里预分配，draw 里不产生新对象。
 * 3. 同时在动的卡片最多 3 张（在传上限就是 3），一张传完就消失，天然限流。
 */
@Composable
internal fun WaterOverlay(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    dropColor: Color = Color.White,
    down: Boolean = true
) {
    // 帧是 2Hz 来的，插值到 500ms 正好桥接两帧之间的空档，看起来是连续涨上来的
    val level by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 500, easing = LinearEasing),
        label = "level"
    )
    val transition = rememberInfiniteTransition(label = "water")
    val wavePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = WAVE_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave"
    )
    val fallClock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = FALL_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "fall"
    )
    val drops = remember { buildDrops() }
    // 两个 Path 复用：一个画填充的水体，一个只画水面那条高亮线
    val fillPath = remember { Path() }
    val linePath = remember { Path() }
    val density = LocalDensity.current
    val stroke = remember(density) { Stroke(width = with(density) { 1.5.dp.toPx() }) }
    val unit = remember(density) { with(density) { 1.dp.toPx() } }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) {
            return@Canvas
        }
        // 水位：上传时水面从底部往上升，所以水面高度 = level
        val surfaceY = if (down) h * (1f - level) else h * level
        val bodyHeight = if (down) h - surfaceY else surfaceY
        if (bodyHeight <= 0.5f) {
            // 一点水都没有时不画波形，省掉一整轮采样
            return@Canvas
        }
        val phase = wavePhase
        val clock = fallClock
        // 振幅跟着水体高度收敛：水位很浅时还甩 5dp 会甩出水面
        val amplitude = min(bodyHeight * 0.25f, unit * 5f)
        val waveLength = w / 1.5f
        val direction = if (down) 1f else -1f

        fillPath.reset()
        linePath.reset()
        for (i in 0..WAVE_SEGMENTS) {
            val x = w * i / WAVE_SEGMENTS
            // 两个不同波长、不同方向的正弦叠一下，波形才不像一条标准正弦那么假
            val y = surfaceY +
                amplitude * 0.7f * sin(2f * PI.toFloat() * x / waveLength + phase) +
                amplitude * 0.3f * sin(2f * PI.toFloat() * x / (waveLength * 0.53f) - phase * 1.3f)
            if (i == 0) {
                fillPath.moveTo(x, y)
                linePath.moveTo(x, y)
            } else {
                fillPath.lineTo(x, y)
                linePath.lineTo(x, y)
            }
        }
        // 把波形线补成一个闭合区域，再填成水体
        fillPath.lineTo(w, if (down) h else 0f)
        fillPath.lineTo(0f, if (down) h else 0f)
        fillPath.close()
        drawPath(fillPath, color = color.copy(alpha = 0.42f))
        drawPath(linePath, color = color.copy(alpha = 0.85f), style = stroke)

        // 粒子：从水面出发，往水体内部飘。行程封顶在卡片的三分之一高度，
        // 免得水位很深时颗粒拉成一条长长的彗尾、看不出"从水面发射"
        val span = min(bodyHeight, h * 0.35f)
        for (drop in drops) {
            val t = (clock * drop.speed + drop.life) % 1f
            val x = drop.baseX * w + sin(phase + drop.seed) * drop.sway * unit
            val y = surfaceY + direction * t * span
            // 刚出水与快沉底时都淡出，两端不突兀
            val alpha = sin(t * PI.toFloat())
            drawCircle(
                color = dropColor.copy(alpha = 0.85f * alpha),
                radius = drop.radius * unit,
                center = Offset(x, y)
            )
        }
    }
}

/** 固定数量、预生成：随机数只在组合期摇一次，draw 阶段一次都不摇 */
private fun buildDrops(): List<Drop> {
    val random = Random(0x5EED)
    return List(DROP_COUNT) { index ->
        Drop(
            // 均匀铺开再加一点抖动，避免排成整齐的一列
            baseX = ((index + 0.5f) / DROP_COUNT + (random.nextFloat() - 0.5f) * 0.06f)
                .coerceIn(0f, 1f),
            life = random.nextFloat(),
            speed = 0.6f + random.nextFloat() * 0.8f,
            sway = 2f + random.nextFloat() * 5f,
            radius = 1.2f + random.nextFloat() * 1.3f,
            seed = random.nextFloat() * 2f * PI.toFloat()
        )
    }
}
