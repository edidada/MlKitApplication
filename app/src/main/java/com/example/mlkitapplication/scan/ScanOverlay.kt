package com.example.mlkitapplication.scan

import android.graphics.RectF
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

private val Accent = Color(0xFF3BE08C)
private val AccentSoft = Color(0x593BE08C)
private val Scrim = Color(0x99000000)
private val Bracket = Color(0xFFFFFFFF)

/**
 * 扫码取景遮罩：暗化蒙版 + 取景框四角 + 扫描线 + 检出码跟随框。
 * 只画几何，文字交给上层 Compose。
 */
@Composable
fun ScanOverlay(
    boxes: List<TrackedBox>,
    scanning: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "scanline")
    val animated = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1700), RepeatMode.Reverse),
        label = "progress",
    ).value
    val progress = if (scanning && boxes.isEmpty()) animated else 0.5f

    Canvas(modifier) {
        val short = minOf(size.width, size.height)
        val side = (short * BarcodeScanEngine.REGION_NORM).coerceAtMost(size.width * 0.86f)
        val left = (size.width - side) / 2f
        val top = size.height * 0.42f - side / 2f
        val radius = CornerRadius(short * 0.06f)

        drawScrim(left, top, side)
        drawViewfinder(left, top, side, radius, short)
        if (scanning && boxes.isEmpty()) drawScanLine(left, top, side, progress, short)
        boxes.forEach { drawTracked(it.rect, short) }
    }
}

/** 取景框外的暗化区：四个矩形拼出，不依赖奇偶填充路径。 */
private fun DrawScope.drawScrim(left: Float, top: Float, side: Float) {
    drawRect(color = Scrim, size = Size(size.width, top))
    drawRect(color = Scrim, topLeft = Offset(0f, top + side), size = Size(size.width, size.height - top - side))
    drawRect(color = Scrim, size = Size(left, side), topLeft = Offset(0f, top))
    drawRect(color = Scrim, topLeft = Offset(left + side, top), size = Size(size.width - left - side, side))
}

private fun DrawScope.drawViewfinder(
    left: Float,
    top: Float,
    side: Float,
    radius: CornerRadius,
    short: Float,
) {
    drawRoundRect(
        color = Color.White.copy(alpha = 0.20f),
        cornerRadius = radius,
        topLeft = Offset(left, top),
        size = Size(side, side),
        style = Stroke(width = short * 0.004f),
    )
    val arm = side * 0.18f
    val w = short * 0.016f
    val corners = listOf(
        Triple(Offset(left, top), 1f, 1f),
        Triple(Offset(left + side, top), -1f, 1f),
        Triple(Offset(left, top + side), 1f, -1f),
        Triple(Offset(left + side, top + side), -1f, -1f),
    )
    corners.forEach { (c, sx, sy) ->
        drawLine(Bracket, c, Offset(c.x + sx * arm, c.y), w, cap = StrokeCap.Round)
        drawLine(Bracket, c, Offset(c.x, c.y + sy * arm), w, cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawScanLine(
    left: Float,
    top: Float,
    side: Float,
    progress: Float,
    short: Float,
) {
    val y = top + side * (0.06f + 0.88f * progress)
    val h = side * 0.10f
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, AccentSoft, Color.Transparent),
            startY = y - h / 2f,
            endY = y + h / 2f,
        ),
        topLeft = Offset(left + side * 0.015f, y - h / 2f),
        size = Size(side * 0.97f, h),
    )
    drawLine(
        color = Accent.copy(alpha = 0.9f),
        start = Offset(left + side * 0.05f, y),
        end = Offset(left + side * 0.95f, y),
        strokeWidth = short * 0.005f,
        cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(side * 0.05f, side * 0.02f), 0f),
    )
}

/** 检出码的跟随框（rect 已是预览视口像素坐标）。 */
private fun DrawScope.drawTracked(rect: RectF, short: Float) {
    val pad = short * 0.02f
    val left = rect.left - pad
    val top = rect.top - pad
    val w = rect.width() + pad * 2f
    val h = rect.height() + pad * 2f
    val color = Accent
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(short * 0.03f),
        topLeft = Offset(left, top),
        size = Size(w, h),
        style = Stroke(width = short * 0.006f, cap = StrokeCap.Round),
    )
    val tick = minOf(w, h) * 0.24f
    val thick = short * 0.012f
    listOf(
        Triple(Offset(left, top), 1f, 1f),
        Triple(Offset(left + w, top), -1f, 1f),
        Triple(Offset(left, top + h), 1f, -1f),
        Triple(Offset(left + w, top + h), -1f, -1f),
    ).forEach { (c, sx, sy) ->
        drawLine(color, c, Offset(c.x + sx * tick, c.y), thick, cap = StrokeCap.Round)
        drawLine(color, c, Offset(c.x, c.y + sy * tick), thick, cap = StrokeCap.Round)
    }
}
