# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Project Overview

BrowserHelper-Gecko is a fork of Mozilla's Reference Browser, an Android browser built on
GeckoView + Android Components (v153 nightly). Additions over upstream:
- Chinese UI localization
- Cookie export/view (built-in WebExtension `cookie_export_helper`)
- Eruda DevTools + custom Network capture panel (built-in WebExtension `devtools_injector`, active dev)
- Custom top-tab strip, density override, Google as default search engine

## Build & Deploy

```bash
./gradlew :app:assembleDebug --no-daemon          # local debug APK
gh run list -R haoyangtu09-art/BrowserHelper-Gecko # CI: push main → auto-build arm64 APK
```

Output: `app/build/outputs/apk/debug/*arm64-v8a*.apk`
No unit tests; testing is manual on-device.

## Key File Locations

| Purpose | Path |
|---------|------|
| **Eruda content script (primary edit target)** | `app/src/main/assets/extensions/devtools_injector/content.js` |
| Eruda JS bundle (patched v3.4.3) | `app/src/main/assets/extensions/devtools_injector/eruda.min.js` |
| DevTools manifest | `app/src/main/assets/extensions/devtools_injector/manifest.json` |
| Native DevTools Kotlin helper | `app/src/main/java/org/mozilla/reference/browser/devtools/DevToolsHelper.kt` |
| App entry / extension install | `app/src/main/java/org/mozilla/reference/browser/BrowserApplication.kt` |
| Toolbar menu (Chinese labels) | `app/src/main/java/org/mozilla/reference/browser/browser/ToolbarIntegration.kt` |
| GeckoEngine / density config | `app/src/main/java/org/mozilla/reference/browser/components/Core.kt` |
| Cookie extension (working reference) | `app/src/main/assets/extensions/cookie_export_helper/` |

## Built-in WebExtension Pattern

Uses `BuiltInWebExtensionController` from `mozilla.components.support.webextensions`:
1. Assets in `app/src/main/assets/extensions/<name>/`; URL = `resource://android/assets/extensions/<name>/`
2. `controller.install(runtime, onSuccess, onError)` from `BrowserApplication.onCreate`
3. **Handler registered in `onSuccess`** — before content script calls `connectNative()`
4. `BrowserStore.flow()` observes tab changes to re-register handler per new `EngineSession`
5. `registerContentMessageHandler` keeps only the **latest** session's handler

### DevTools Extension
- **Type**: content script. **Port**: `"devtools_inject"`.
- **Critical permission**: `nativeMessagingFromContent` (content scripts calling `connectNative()` silently fail without it).
- Entry: Menu "开发者工具" → `DevToolsHelper.toggle(context)` → sends `{action:"toggle"}` over port.

## GeckoView Content Script: Two Execution Worlds

Every page load produces two distinct JS environments that share the DOM but have separate globals:

| Mode | How eruda loads | Intercept method | When |
|------|----------------|-----------------|------|
| **page-world** | `fetch(eruda.min.js)` → Blob URL `<script>` injected | `runInPage(INTERCEPT_JS)` string eval via blob `<script>` | Normal sites |
| **isolated-world** | `new Function(src)()` in content script global | `exportFunction` + `window.wrappedJSObject` (Gecko-only) | Strong-CSP sites (chatgpt, github…) |

**`erudaMode`** variable (`'page'` or `'isolated'`) is set at load time and gates all dual-path logic.

## Critical GeckoView Limitations

- `tabs.executeScript` **not supported** — only `content_scripts` declaration works.
- `eruda` global is `undefined` in isolated world after `content_scripts` load — must `fetch()` source and `new Function()` it.
- `new Function()` body loses Window `this` — bare `getComputedStyle()` throws; must use `window.getComputedStyle()`.
- Isolated world shares DOM with page but has its own JS global and its own `window` proxy.
- `@font-face` inside shadow root is ignored (Gecko bug 1714278) — must inject into document light DOM.
- Strong-CSP sites block inline `<script>`; use blob URL in `runInPage()` with inline fallback.
- `exportFunction` / `cloneInto` / `window.wrappedJSObject` are Gecko-only APIs for cross-world bridging.

## eruda.min.js Patches (Eruda v3.4.3, DO NOT regenerate without re-applying)

Original at `$HOME/eruda.min.js`. Patches that must be re-applied if regenerating:
1. Remove `"network"` from default tool list — prevents eruda patching `window.fetch`/`window.WebSocket`
2. Remove `window.fetch = function(){...}` block — isolated world makes `window.fetch` read-only
3. Remove `window.WebSocket = t` assignment — same reason
4. Replace bare `getComputedStyle(` → `window.getComputedStyle(` — needed for isolated-world execution

eruda tab names are **lowercase in DOM** (CSS `text-transform:capitalize` shows them capitalized).
I18n map keys must therefore be lowercase (`'console'`, `'elements'`, etc.) to match text nodes.

## Network Capture Panel (content.js — custom tool added to eruda)

### Architecture

The panel is a custom eruda Tool built entirely in `content.js` (content-script world).
It is registered via `eruda.add(tool)` — eruda only calls `tool.init($el)`, never `render()`.

**Dual-path registration:**
- `erudaMode === 'page'` → `injectNetToolPageWorld()`: builds DOM in content world, appends to `document.body` hidden, then injects page-world script that calls `eruda.add()` and moves `#bh-net` into eruda's container. `show()`/`hide()` communicated back via `postMessage({__bhNet:true, type:'panelShow'/'panelHide'})`.
- `erudaMode === 'isolated'` → `registerNetTool(self.eruda)`: calls `eruda.add(tool)` directly.

### Cross-world Messaging (`window.postMessage` channel)

All messages carry `{__bhNet: true, type: ...}`. Types:
- `req` — interceptor → content: new request captured
- `resp` — interceptor → content: response received (or error/mock)
- `panelShow` / `panelHide` — page-world tool → content: visibility change
- `breakpoint` — interceptor → content: request hit a breakpoint, needs editing
- `bpResolve` — content → interceptor: user decision (`action:'continue'|'abort'` + edited req fields)

### Network Interceptor

**Page mode**: `runInPage(INTERCEPT_JS)` injects a string-built script into page world.
**Isolated mode**: `injectInterceptorViaExportFunction()` uses `exportFunction`/`wrappedJSObject` — no `<script>` needed, bypasses CSP entirely.

Both paths hook `window.fetch` and `window.XMLHttpRequest`. Each request:
1. Checks `window.__bhMockRules` → if matched, returns fake Response immediately (fetch) or fires synthetic XHR events (XHR).
2. Checks `window.__bhBreakpoints` → if matched, calls `waitBp()` which postMessages `breakpoint` to content world, then awaits `bpResolve` message before proceeding.
3. Reads `window.__bhThrottle` for delay/throttle.

**Writing config to page world:**
- `injectMockRules()` — writes `window.__bhMockRules`
- `injectBreakpoints()` — writes `window.__bhBreakpoints`
- `applyThrottle()` — writes `window.__bhThrottle`
All three try `wrappedJSObject`+`cloneInto` first (isolated mode, bypasses CSP), fall back to `runInPage`.

### Key Functions & Line Ranges (approximate, subject to drift)

| Function | Purpose |
|----------|---------|
| `buildNetPanel()` | Creates all panel DOM; binds all button listeners; sets `netPanel` |
| `renderNetList()` | Redraws request list; binds row click → sets `netSelReq`, calls `renderDetail(true)` |
| `renderDetail(force)` | Renders selected request tabs/body; skips textarea overwrite if `netEditing && !force` |
| `injectInterceptor()` | Gates page vs isolated interceptor injection |
| `INTERCEPT_JS` | Page-world interceptor source (string); fetch+XHR hooks + mock/bp/throttle |
| `injectInterceptorViaExportFunction()` | Isolated-mode interceptor via exportFunction |
| `openModal(title, bodyHtml, onOk)` | Modal appended to `netPanel` (inside shadow root); z-index max |
| `openBreakpointEditor(d)` | Breakpoint hit modal: "放行" sends continue, "中止" sends abort |
| `openBreakpointModal()` | Manage breakpoint list; calls `injectBreakpoints()` on change |
| `openMockModal()` | Manage Mock rules; calls `injectMockRules()` on change |
| `openThrottleMenu()` | Preset picker (long-press 弱网); calls `applyThrottle()` |
| `replayReq(r, tag)` | Replays request using content-world `fetch`; result appears in list |
| `_i18nCore()` | Translates eruda UI text nodes; page mode via `runInPage`, isolated direct DOM |
| `installI18n()` | Called after eruda loads; triggers i18n + interceptor + tool registration |
| `injectErudaFontFace(code)` | Extracts `@font-face` from eruda source → injects into `document.head` (both modes) |
| `flashBtn(btn, msg)` | Shows 1s feedback text on a button then restores original label |

### Panel DOM Variables (set in buildNetPanel, used globally)

| Variable | DOM Element |
|----------|-------------|
| `netPanel` | `#bh-net` — panel root (appended into eruda container) |
| `netListEl` | `#bh-list` — request list (flex shrinks to 30% when detail shown) |
| `netDetailEl` | `#bh-detail` — detail area (hidden until row selected) |
| `netDetailBody` | `#bh-detail-body` — textarea; editable; focus sets `netEditing=true` |
| `netDetailActs` | `#bh-detail-acts` — bottom action buttons row |
| `netFilterEl` | `#bh-filter` — URL filter input |
| `netEnableBtn` | `#bh-toggle` — capture on/off toggle |
| `netModal` | current open modal element (null if none) |

State variables: `netRequests[]`, `netSelReq`, `netDetailTab` (0-3), `netEditing`, `netPanelVisible`, `netBreakpoints[]`, `netMockRules[]`, `netThrottle{enabled,latencyMs,kbps}`.

### Modal System

`openModal(title, bodyHtml, onOk)` — appended to `netPanel` (shadow root), z-index `2147483647`.
**Critical**: modals must NOT be appended to `document.body` — eruda panel z-index=9999999 covers it.
Use `closeModal()` to dismiss. `netModal` holds the current element.

### Breakpoint Editing Flow

1. Interceptor calls `waitBp(reqId, ...)` → postMessages `{type:'breakpoint'}` → page-world Promise pending in `window.__bhBpPending[reqId]`.
2. Content world receives `breakpoint` → `openBreakpointEditor(d)` → modal with editable URL/method/headers/body.
3. User clicks "放行" → `bpResolve(reqId, {action:'continue', ...})` → `window.postMessage({type:'bpResolve'})`.
4. Page-world listener resolves the Promise; request proceeds with edited parameters.
5. "中止" → `action:'abort'` → interceptor rejects the Promise / stops XHR.
Isolated mode: pending Promise lives in `_bpIsoPending{}` in content world; `bpResolve` message also received by content world listener.

## I18n (Chinese translation of eruda UI)

- `I18N_MAP` — array of `[englishText, chineseText]` pairs. Keys are **exact** DOM text node values.
- Tab names lowercase (`'console'`/`'elements'` etc.) to match DOM (CSS does capitalization).
- `inDataRegion(node)` — guards against translating raw data values. Blocks nodes inside:
  `luna-console`, `luna-data-grid-data` (rows only, NOT headers), `luna-dom-viewer`,
  `luna-object-viewer`, `luna-json`, `bh-net`. Panel-level containers (`eruda-resources` etc.) are NOT blocked so category/header labels can be translated.
- Settings labels use eruda's real English strings (from `.switch()`/`.select()`/`.range()` calls in eruda source).

## Local Development with Local Dependencies

```
# local.properties
autoPublish.android-components.dir=../android-components
dependencySubstitutions.geckoviewTopsrcdir=/path/to/mozilla-central
```
