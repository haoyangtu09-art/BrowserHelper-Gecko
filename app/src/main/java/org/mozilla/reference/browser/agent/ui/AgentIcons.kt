/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Custom line/stroke icons drawn with Canvas so the overlay needs no vector assets. */

@Composable
fun MenuLinesIcon(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = Color(0xFF111111)) {
    Canvas(modifier.size(size)) {
        val sw = this.size.minDimension * 0.10f
        val cx = this.size.width / 2f
        val topY = this.size.height * 0.38f
        val botY = this.size.height * 0.62f
        // Upper line longer, lower line shorter (left-aligned feel, centered here).
        val longHalf = this.size.width * 0.34f
        val shortHalf = this.size.width * 0.20f
        drawLine(color, Offset(cx - longHalf, topY), Offset(cx + longHalf, topY), sw, StrokeCap.Round)
        drawLine(color, Offset(cx - longHalf, botY), Offset(cx + longHalf - (longHalf - shortHalf) * 2, botY), sw, StrokeCap.Round)
    }
}

@Composable
fun ChatBubbleIcon(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = Color(0xFF111111)) {
    Canvas(modifier.size(size)) {
        val sw = this.size.minDimension * 0.09f
        val w = this.size.width
        val h = this.size.height
        val r = w * 0.30f
        val path = Path().apply {
            // Rounded bubble body with a small tail at the bottom-left.
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = w * 0.12f, top = h * 0.14f, right = w * 0.88f, bottom = h * 0.70f,
                    radiusX = r, radiusY = r,
                ),
            )
            moveTo(w * 0.30f, h * 0.66f)
            lineTo(w * 0.22f, h * 0.86f)
            lineTo(w * 0.46f, h * 0.66f)
        }
        drawPath(path, color, style = Stroke(width = sw, cap = StrokeCap.Round))
    }
}

@Composable
fun PenSquareIcon(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = Color(0xFF111111)) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = this.size.minDimension * 0.085f
        // Feather "square-pen": map the 0..24 viewBox onto the canvas.
        fun p(x: Float, y: Float) = Offset(x / 24f * w, y / 24f * h)
        // Open rounded frame (top-right corner left open for the pen):
        // M11 4 H4 a2 2 0 0 0 -2 2 V20 a2 2 0 0 0 2 2 H18 a2 2 0 0 0 2 -2 V13
        val frame = Path().apply {
            moveTo(p(11f, 4f).x, p(11f, 4f).y)
            lineTo(p(6f, 4f).x, p(6f, 4f).y)
            quadraticTo(p(4f, 4f).x, p(4f, 4f).y, p(4f, 6f).x, p(4f, 6f).y)
            lineTo(p(4f, 18f).x, p(4f, 18f).y)
            quadraticTo(p(4f, 20f).x, p(4f, 20f).y, p(6f, 20f).x, p(6f, 20f).y)
            lineTo(p(18f, 20f).x, p(18f, 20f).y)
            quadraticTo(p(20f, 20f).x, p(20f, 20f).y, p(20f, 18f).x, p(20f, 18f).y)
            lineTo(p(20f, 13f).x, p(20f, 13f).y)
        }
        drawPath(frame, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        // Pen quill: M18.5 2.5 a2.12 2.12 0 0 1 3 3 L12 15 l-4 1 1-4 Z
        val pen = Path().apply {
            moveTo(p(18.5f, 2.5f).x, p(18.5f, 2.5f).y)
            quadraticTo(p(21.5f, 2.5f).x, p(21.5f, 2.5f).y, p(21.5f, 5.5f).x, p(21.5f, 5.5f).y)
            lineTo(p(12f, 15f).x, p(12f, 15f).y)
            lineTo(p(8f, 16f).x, p(8f, 16f).y)
            lineTo(p(9f, 12f).x, p(9f, 12f).y)
            close()
        }
        drawPath(pen, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

@Composable
fun MoreDotsIcon(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = Color(0xFF111111)) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension * 0.06f
        val cx = this.size.width / 2f
        val h = this.size.height
        drawCircle(color, r, Offset(cx, h * 0.26f))
        drawCircle(color, r, Offset(cx, h * 0.50f))
        drawCircle(color, r, Offset(cx, h * 0.74f))
    }
}

@Composable
fun PlusIcon(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = Color(0xFF111111)) {
    Canvas(modifier.size(size)) {
        val sw = this.size.minDimension * 0.10f
        val w = this.size.width
        val h = this.size.height
        drawLine(color, Offset(w * 0.5f, h * 0.22f), Offset(w * 0.5f, h * 0.78f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.22f, h * 0.5f), Offset(w * 0.78f, h * 0.5f), sw, StrokeCap.Round)
    }
}

@Composable
fun CameraIcon(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = Color(0xFF111111)) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = this.size.minDimension * 0.09f
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.14f, h * 0.30f),
            size = Size(w * 0.72f, h * 0.48f),
            cornerRadius = CornerRadius(w * 0.13f, w * 0.13f),
            style = Stroke(width = sw),
        )
        drawLine(color, Offset(w * 0.34f, h * 0.30f), Offset(w * 0.40f, h * 0.20f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.40f, h * 0.20f), Offset(w * 0.60f, h * 0.20f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.60f, h * 0.20f), Offset(w * 0.66f, h * 0.30f), sw, StrokeCap.Round)
        drawCircle(color, w * 0.15f, Offset(w * 0.50f, h * 0.54f), style = Stroke(width = sw))
    }
}

@Composable
fun ImageLandscapeIcon(modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val gold = Color(0xFFD4A017)
        val ink = Color(0xFF111111)
        val sw = this.size.minDimension * 0.08f
        drawRoundRect(
            color = gold,
            topLeft = Offset(w * 0.08f, h * 0.20f),
            size = Size(w * 0.84f, h * 0.60f),
            cornerRadius = CornerRadius(w * 0.13f, w * 0.13f),
        )
        drawCircle(Color(0xFFFFE08A), w * 0.09f, Offset(w * 0.68f, h * 0.38f))
        val mountain = Path().apply {
            moveTo(w * 0.17f, h * 0.70f)
            lineTo(w * 0.39f, h * 0.50f)
            lineTo(w * 0.53f, h * 0.63f)
            lineTo(w * 0.63f, h * 0.55f)
            lineTo(w * 0.84f, h * 0.70f)
        }
        drawPath(
            mountain,
            ink,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
        )
    }
}

@Composable
fun SpiralClipIcon(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = Color(0xFF111111)) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = this.size.minDimension * 0.09f
        val path = Path().apply {
            moveTo(w * 0.68f, h * 0.25f)
            cubicTo(w * 0.88f, h * 0.38f, w * 0.82f, h * 0.78f, w * 0.48f, h * 0.80f)
            cubicTo(w * 0.20f, h * 0.82f, w * 0.12f, h * 0.52f, w * 0.34f, h * 0.42f)
            cubicTo(w * 0.56f, h * 0.32f, w * 0.70f, h * 0.54f, w * 0.54f, h * 0.64f)
            cubicTo(w * 0.39f, h * 0.73f, w * 0.27f, h * 0.57f, w * 0.39f, h * 0.48f)
        }
        drawPath(
            path,
            color,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
        )
    }
}

@Composable
fun PluginPlugIcon(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = Color(0xFF111111)) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = this.size.minDimension * 0.08f
        drawCircle(color, w * 0.40f, Offset(w * 0.50f, h * 0.50f), style = Stroke(width = sw))
        drawLine(color, Offset(w * 0.42f, h * 0.36f), Offset(w * 0.42f, h * 0.54f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.58f, h * 0.36f), Offset(w * 0.58f, h * 0.54f), sw, StrokeCap.Round)
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.36f, h * 0.50f),
            size = Size(w * 0.28f, h * 0.22f),
            cornerRadius = CornerRadius(w * 0.07f, w * 0.07f),
            style = Stroke(width = sw),
        )
        drawLine(color, Offset(w * 0.50f, h * 0.72f), Offset(w * 0.50f, h * 0.86f), sw, StrokeCap.Round)
    }
}

@Composable
fun SendArrowIcon(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = Color(0xFFFFFFFF)) {
    Canvas(modifier.size(size)) {
        val sw = this.size.minDimension * 0.12f
        val w = this.size.width
        val h = this.size.height
        // Upward arrow.
        drawLine(color, Offset(w * 0.5f, h * 0.74f), Offset(w * 0.5f, h * 0.28f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.5f, h * 0.28f), Offset(w * 0.30f, h * 0.48f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.5f, h * 0.28f), Offset(w * 0.70f, h * 0.48f), sw, StrokeCap.Round)
    }
}

@Composable
fun GearIcon(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = Color(0xFF111111)) {
    Canvas(modifier.size(size)) {
        val sw = this.size.minDimension * 0.09f
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val rOuter = this.size.minDimension * 0.34f
        val rInner = this.size.minDimension * 0.14f
        drawCircle(color, rOuter, Offset(cx, cy), style = Stroke(width = sw))
        drawCircle(color, rInner, Offset(cx, cy), style = Stroke(width = sw))
        val tooth = this.size.minDimension * 0.12f
        for (i in 0 until 8) {
            val a = Math.toRadians((i * 45).toDouble())
            val sx = cx + (rOuter * Math.cos(a)).toFloat()
            val sy = cy + (rOuter * Math.sin(a)).toFloat()
            val ex = cx + ((rOuter + tooth) * Math.cos(a)).toFloat()
            val ey = cy + ((rOuter + tooth) * Math.sin(a)).toFloat()
            drawLine(color, Offset(sx, sy), Offset(ex, ey), sw, StrokeCap.Round)
        }
    }
}

@Composable
fun BackIcon(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = Color(0xFF111111)) {
    Canvas(modifier.size(size)) {
        val sw = this.size.minDimension * 0.11f
        val w = this.size.width
        val h = this.size.height
        drawLine(color, Offset(w * 0.58f, h * 0.26f), Offset(w * 0.36f, h * 0.5f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.36f, h * 0.5f), Offset(w * 0.58f, h * 0.74f), sw, StrokeCap.Round)
    }
}

@Composable
fun CheckIcon(modifier: Modifier = Modifier, size: Dp = 14.dp, color: Color = Color(0xFFFFFFFF)) {
    Canvas(modifier.size(size)) {
        val sw = this.size.minDimension * 0.16f
        val w = this.size.width
        val h = this.size.height
        drawLine(color, Offset(w * 0.22f, h * 0.52f), Offset(w * 0.42f, h * 0.72f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.42f, h * 0.72f), Offset(w * 0.78f, h * 0.30f), sw, StrokeCap.Round)
    }
}

/** Two offset stacked sheets of paper — the copy glyph (not the word "复制"). */
@Composable
fun CopyIcon(modifier: Modifier = Modifier, size: Dp = 18.dp, color: Color = Color(0xFF111111)) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = this.size.minDimension * 0.09f
        // Back sheet drawn as just its exposed top + right edges (an L), so it reads as a
        // second sheet behind the front one without needing a background-matching fill.
        val back = Path().apply {
            moveTo(w * 0.40f, h * 0.14f)
            lineTo(w * 0.84f, h * 0.14f)
            lineTo(w * 0.84f, h * 0.60f)
        }
        drawPath(back, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        // Front sheet: a full rounded rectangle, bottom-left.
        val front = androidx.compose.ui.geometry.RoundRect(
            left = w * 0.16f, top = h * 0.36f, right = w * 0.64f, bottom = h * 0.86f,
            radiusX = this.size.minDimension * 0.12f, radiusY = this.size.minDimension * 0.12f,
        )
        drawPath(Path().apply { addRoundRect(front) }, color, style = Stroke(width = sw))
    }
}

/** Round face carrying only a smile — no eyes — for the "个性化" entry. */
@Composable
fun FaceMouthIcon(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = Color(0xFF111111)) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = this.size.minDimension * 0.08f
        val cx = w / 2f
        val cy = h / 2f
        val r = this.size.minDimension * 0.40f
        drawCircle(color, r, Offset(cx, cy), style = Stroke(width = sw))
        // Upward-curving mouth in the lower half; eyes intentionally omitted.
        val mouth = Path().apply {
            moveTo(cx - r * 0.45f, cy + r * 0.18f)
            quadraticTo(cx, cy + r * 0.62f, cx + r * 0.45f, cy + r * 0.18f)
        }
        drawPath(mouth, color, style = Stroke(width = sw, cap = StrokeCap.Round))
    }
}

/** An open book (two splayed pages + center spine) for the "记忆" entry. */
@Composable
fun OpenBookIcon(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = Color(0xFF111111)) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = this.size.minDimension * 0.08f
        val cx = w / 2f
        val top = h * 0.30f
        val bot = h * 0.78f
        val left = w * 0.12f
        val right = w * 0.88f
        drawLine(color, Offset(cx, top), Offset(cx, bot), sw, StrokeCap.Round)
        val leftPage = Path().apply {
            moveTo(cx, top)
            quadraticTo(cx - (cx - left) * 0.5f, top - h * 0.04f, left, top + h * 0.04f)
            lineTo(left, bot - h * 0.06f)
            quadraticTo(cx - (cx - left) * 0.5f, bot - h * 0.12f, cx, bot)
        }
        drawPath(
            leftPage,
            color,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
        )
        val rightPage = Path().apply {
            moveTo(cx, top)
            quadraticTo(cx + (right - cx) * 0.5f, top - h * 0.04f, right, top + h * 0.04f)
            lineTo(right, bot - h * 0.06f)
            quadraticTo(cx + (right - cx) * 0.5f, bot - h * 0.12f, cx, bot)
        }
        drawPath(
            rightPage,
            color,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
        )
    }
}
