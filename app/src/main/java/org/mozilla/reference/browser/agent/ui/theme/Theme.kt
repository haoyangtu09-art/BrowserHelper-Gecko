/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Plain design tokens for the overlay Agent UI. The overlay has no MaterialTheme
 * ambient (it lives in a bare WindowManager window), so colors / shapes / text styles
 * are exposed as constants and the UI uses foundation primitives (BasicText /
 * BasicTextField) with explicit styling rather than material components.
 */
object AgentColors {
    val Bg = Color(0xFFF8F8F8)
    val Panel = Color(0xFFFFFFFF)
    // Panel surface sits a touch below pure white so the white floating buttons /
    // popups read as hovering above it purely by tone (no drop shadow needed).
    val Surface = Color(0xFFF1F2F4)
    val TextPrimary = Color(0xFF111111)
    val TextSecondary = Color(0xFF666666)
    val TextTertiary = Color(0xFF999999)
    val Hairline = Color(0xFFB0B0B0)
    // Faint hairline for the in-panel border and the model-selector divider — softer
    // than the heavier Hairline so it reads as a subtle inset stroke, not a hard rule.
    val HairlineFaint = Color(0x1F000000)
    val Control = Color(0xFFEAEAEA)
    // User-message bubble: a solid gray clearly darker than the chat background (Bg/Surface)
    // so the bubble reads as a filled block — no border/outline (which looked bad).
    val UserBubble = Color(0xFFE4E6EA)
    val Accent = Color(0xFF4285F4)
    val Stop = Color(0xFF111111)
    // Tint behind the floating top-bar buttons' drop shadow.
    val ShadowTint = Color(0x33000000)
}

object AgentShapes {
    val Pill = RoundedCornerShape(percent = 50)
    val Panel = RoundedCornerShape(20.dp)
    val Upload = RoundedCornerShape(20.dp)
    val Field = RoundedCornerShape(24.dp)
    val Sheet = RoundedCornerShape(14.dp)
    // Ball squircle: a rounded square so large it nearly reads as a circle, yet on close
    // inspection keeps a square-ish outline (per the ChatGPT-style floating ball).
    val Squircle = RoundedCornerShape(percent = 38)
}

/** Shared elevation for the white floating top-bar buttons so they read as hovering. */
object AgentElevation {
    val Floating = 4.dp
}

object AgentText {
    // Sizes kept deliberately small/compact (the UI previously read as oversized). Body at
    // 13sp fits noticeably more characters per line in the narrow overlay.
    val Title = TextStyle(color = AgentColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val Body = TextStyle(color = AgentColors.TextPrimary, fontSize = 13.sp)
    val Secondary = TextStyle(color = AgentColors.TextSecondary, fontSize = 12.sp)
    val Hint = TextStyle(color = AgentColors.TextTertiary, fontSize = 13.sp)
    val Label = TextStyle(color = AgentColors.TextSecondary, fontSize = 11.sp)
}
