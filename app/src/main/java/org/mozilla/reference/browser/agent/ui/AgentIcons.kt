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
        val sw = this.size.minDimension * 0.09f
        val w = this.size.width
        val h = this.size.height
        // Open square (bottom-left bracket) + a pen diagonal across the top-right.
        val square = Path().apply {
            moveTo(w * 0.78f, h * 0.20f)
            lineTo(w * 0.20f, h * 0.20f)
            lineTo(w * 0.20f, h * 0.80f)
            lineTo(w * 0.80f, h * 0.80f)
            lineTo(w * 0.80f, h * 0.46f)
        }
        drawPath(square, color, style = Stroke(width = sw, cap = StrokeCap.Round))
        drawLine(color, Offset(w * 0.52f, h * 0.50f), Offset(w * 0.86f, h * 0.16f), sw, StrokeCap.Round)
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
