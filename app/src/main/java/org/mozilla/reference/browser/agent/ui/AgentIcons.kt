/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
