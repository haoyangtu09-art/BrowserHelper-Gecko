/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.reference.browser.agent.ui.theme.AgentText

/**
 * Interactive fenced-code windows for the overlay Agent chat. The model "actively invokes" one by
 * tagging a fenced block with a renderable language (```html / ```js / ```markdown). Unlike the
 * plain code box, this window has:
 *  - a tap-gesture icon + language label (top-left),
 *  - a copy button + a code↔render capsule toggle (top-right): `[>_]` = source, ▶ = live render,
 *  - syntax-highlighted source (highlight.js, bundled as a local asset) in the code view,
 *  - a live, interactive render in the render view (WebView for html/js, MarkdownContent for md),
 *  - tap-to-fullscreen on the body (an affordance the plain code box deliberately lacks).
 *
 * Other languages keep the flat [MdCodeBlock]; this path is only entered for the three tags above.
 */

private const val HL_BASE = "file:///android_asset/agent/highlight/"
// Gray code surface (matches the plain markdown code box) instead of the old near-black window.
private val WinBg = Color(0xFFEDEEF0)
private val WinHeaderBg = Color(0xFFE2E4E8)
private val WinFg = Color(0xFF57606A)
private val ToggleTrack = Color(0xFF555555)
private val ToggleKnob = Color(0xFFF2F2F2)
// Toggle sits on its own dark track, so its track-side glyph stays light regardless of the
// now-light header foreground.
private val ToggleFg = Color(0xFFE8E8E8)

private enum class CodeMode { CODE, RENDER }

/** Normalizes a fence info string to one of the renderable kinds, or null if it isn't one. */
fun interactiveLang(info: String): String? = when (info.trim().substringBefore(' ').lowercase()) {
    "html", "htm", "xhtml" -> "html"
    "js", "javascript", "mjs" -> "js"
    "markdown", "md" -> "markdown"
    else -> null
}

@Composable
fun InteractiveCodeWindow(code: String, lang: String) {
    var mode by remember { mutableStateOf(CodeMode.CODE) }
    var fullscreen by remember { mutableStateOf(false) }
    val clipboard = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val bodyHeight: Dp by animateDpAsState(if (fullscreen) 520.dp else 220.dp, label = "winHeight")
    val label = when (lang) { "html" -> "HTML"; "js" -> "JS"; else -> "MARKDOWN" }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(WinBg)) {
        // ── header ──
        Row(
            Modifier.fillMaxWidth().background(WinHeaderBg)
                .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TapGestureIcon(size = 16.dp, color = WinFg)
            Spacer(Modifier.width(7.dp))
            BasicText(label, style = AgentText.Label.copy(color = WinFg))
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(24.dp).clip(CircleShape).noRippleClickable {
                    clipboard.setPrimaryClip(ClipData.newPlainText("code", code))
                },
                contentAlignment = Alignment.Center,
            ) { CopyIcon(size = 14.dp, color = WinFg, bg = WinHeaderBg) }
            Spacer(Modifier.width(4.dp))
            ExpandIcon(
                size = 16.dp, color = WinFg,
                modifier = Modifier.size(24.dp).clip(CircleShape).noRippleClickable { fullscreen = !fullscreen },
            )
            Spacer(Modifier.width(6.dp))
            CodeRenderToggle(render = mode == CodeMode.RENDER) {
                mode = if (mode == CodeMode.CODE) CodeMode.RENDER else CodeMode.CODE
            }
        }
        // ── body ──
        val bodyMod = Modifier.fillMaxWidth().height(bodyHeight)
        when {
            mode == CodeMode.CODE ->
                // Source view isn't interactive, so tapping it toggles fullscreen.
                Box(bodyMod.noRippleClickable { fullscreen = !fullscreen }) {
                    CodeWebView(html = highlightHtml(code, lang), baseUrl = HL_BASE, modifier = Modifier.fillMaxWidth().height(bodyHeight))
                }
            lang == "markdown" ->
                Box(bodyMod.background(Color.White).verticalScroll(rememberScrollState()).padding(12.dp)) {
                    MarkdownContent(code)
                }
            lang == "html" ->
                CodeWebView(html = code, baseUrl = null, modifier = bodyMod)
            else -> // js
                CodeWebView(html = jsRunnerHtml(code), baseUrl = null, modifier = bodyMod)
        }
    }
}

// ── WebView host ───────────────────────────────────────────────────────────────────────────────

@Composable
private fun CodeWebView(html: String, baseUrl: String?, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.domStorageEnabled = true
                // Needed so the bundled highlight.js / theme under file:///android_asset load on
                // Android 11+ (where allowFileAccess defaults to false).
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(0xFFEDEEF0.toInt())
                // Let the WebView keep its own gestures so internal scroll works inside the
                // vertically-scrolling transcript.
                setOnTouchListener { v, _ ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }
            }
        },
        update = { wv ->
            if (wv.tag != html) {
                wv.tag = html
                wv.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
            }
        },
    )
}

// ── HTML templates ─────────────────────────────────────────────────────────────────────────────

private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun highlightHtml(code: String, lang: String): String {
    val cls = when (lang) { "js" -> "language-javascript"; "markdown" -> "language-markdown"; else -> "language-html" }
    // Style plain `pre/code` (not just `.hljs`) so the gray bg + small wrapped font hold even if
    // highlight.js fails to load. `white-space:pre-wrap` + `overflow-x:hidden` override the
    // theme's own `overflow-x:auto`, so long lines wrap instead of scrolling sideways. Source font
    // is 10px (12px shrunk by 1/6); the light github theme keeps syntax colors readable on gray.
    return "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<link rel=\"stylesheet\" href=\"github.min.css\">" +
        "<style>html,body{margin:0;padding:0;background:#edeef0;}" +
        "pre,pre code.hljs{margin:0;overflow-x:hidden;background:#edeef0;}" +
        "pre code{display:block;background:#edeef0;color:#24292e;font-family:monospace;" +
        "font-size:10px;line-height:1.5;white-space:pre-wrap;word-break:break-word;padding:10px 12px;}" +
        "</style>" +
        "</head><body><pre><code class=\"" + cls + "\">" + escapeHtml(code) + "</code></pre>" +
        "<script src=\"highlight.min.js\"></script><script>hljs.highlightAll();</script></body></html>"
}

private fun jsRunnerHtml(code: String): String {
    val safe = code.replace("</script>", "<\\/script>")
    return "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<style>html,body{margin:0;padding:8px;font-family:monospace;background:#fff;color:#111;font-size:13px;}" +
        "#__out{white-space:pre-wrap;word-break:break-word;}</style></head><body><div id=\"__out\"></div><script>" +
        "(function(){var o=document.getElementById('__out');" +
        "function w(t){o.textContent+=t+'\\n';}" +
        "var _l=console.log;console.log=function(){w(Array.prototype.map.call(arguments,String).join(' '));if(_l)_l.apply(console,arguments);};" +
        "console.info=console.warn=console.error=console.log;" +
        "try{\n" + safe + "\n}catch(e){w('Error: '+(e&&e.message?e.message:e));}})();" +
        "</script></body></html>"
}

// ── controls / icons ───────────────────────────────────────────────────────────────────────────

/** Gray capsule with a sliding white knob: `[>_]` (source) on the left, ▶ (render) on the right. */
@Composable
private fun CodeRenderToggle(render: Boolean, onToggle: () -> Unit) {
    val w = 52.dp
    val h = 24.dp
    val knob = 24.dp
    val knobX: Dp by animateDpAsState(if (render) w - knob else 0.dp, label = "knobX")
    Box(
        Modifier.size(w, h).clip(RoundedCornerShape(12.dp)).background(ToggleTrack).noRippleClickable { onToggle() },
    ) {
        Box(Modifier.padding(start = knobX).size(knob, h).padding(2.dp).clip(CircleShape).background(ToggleKnob))
        Row(Modifier.fillMaxWidth().height(h).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            TerminalIcon(size = 13.dp, color = if (render) ToggleFg else Color(0xFF222222))
            Spacer(Modifier.weight(1f))
            PlayIcon(size = 11.dp, color = if (render) Color(0xFF222222) else ToggleFg)
        }
    }
}

/** A pointing-tap glyph: an upright finger with a small ripple arc above it. */
@Composable
private fun TapGestureIcon(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val wd = this.size.width
        val ht = this.size.height
        val sw = this.size.minDimension * 0.09f
        // finger: rounded vertical bar
        val finger = androidx.compose.ui.geometry.RoundRect(
            left = wd * 0.42f, top = ht * 0.34f, right = wd * 0.58f, bottom = ht * 0.86f,
            radiusX = wd * 0.08f, radiusY = wd * 0.08f,
        )
        drawPath(Path().apply { addRoundRect(finger) }, color, style = Stroke(width = sw))
        // tap ripple: two short arcs flanking the fingertip
        drawLine(color, androidx.compose.ui.geometry.Offset(wd * 0.26f, ht * 0.26f), androidx.compose.ui.geometry.Offset(wd * 0.34f, ht * 0.18f), sw, StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(wd * 0.66f, ht * 0.18f), androidx.compose.ui.geometry.Offset(wd * 0.74f, ht * 0.26f), sw, StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(wd * 0.50f, ht * 0.22f), androidx.compose.ui.geometry.Offset(wd * 0.50f, ht * 0.12f), sw, StrokeCap.Round)
    }
}

/** `[>_]` terminal glyph. */
@Composable
private fun TerminalIcon(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val wd = this.size.width
        val ht = this.size.height
        val sw = this.size.minDimension * 0.11f
        // prompt chevron ">"
        drawLine(color, androidx.compose.ui.geometry.Offset(wd * 0.24f, ht * 0.34f), androidx.compose.ui.geometry.Offset(wd * 0.46f, ht * 0.52f), sw, StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(wd * 0.46f, ht * 0.52f), androidx.compose.ui.geometry.Offset(wd * 0.24f, ht * 0.70f), sw, StrokeCap.Round)
        // cursor underscore "_"
        drawLine(color, androidx.compose.ui.geometry.Offset(wd * 0.54f, ht * 0.70f), androidx.compose.ui.geometry.Offset(wd * 0.78f, ht * 0.70f), sw, StrokeCap.Round)
    }
}

/** A filled right-pointing play triangle. */
@Composable
private fun PlayIcon(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val wd = this.size.width
        val ht = this.size.height
        val tri = Path().apply {
            moveTo(wd * 0.24f, ht * 0.16f)
            lineTo(wd * 0.84f, ht * 0.50f)
            lineTo(wd * 0.24f, ht * 0.84f)
            close()
        }
        drawPath(tri, color)
    }
}

/** Four corner brackets — the expand/fullscreen glyph. */
@Composable
private fun ExpandIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val wd = this.size.width
            val ht = this.size.height
            val sw = this.size.minDimension * 0.1f
            val a = 0.18f
            val b = 0.82f
            val len = 0.22f
            fun corner(cx: Float, cy: Float, dx: Float, dy: Float) {
                drawLine(color, androidx.compose.ui.geometry.Offset(wd * cx, ht * cy), androidx.compose.ui.geometry.Offset(wd * (cx + dx), ht * cy), sw, StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(wd * cx, ht * cy), androidx.compose.ui.geometry.Offset(wd * cx, ht * (cy + dy)), sw, StrokeCap.Round)
            }
            corner(a, a, len, len)
            corner(b, a, -len, len)
            corner(a, b, len, -len)
            corner(b, b, -len, -len)
        }
    }
}
