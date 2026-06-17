# CLAUDE.md

Current working context for BrowserHelper-Gecko. Prefer this file over older chat history; old
debugging notes may refer to reverted code.

## Current Code State

On 2026-06-17 the Network panel was rolled back to the state before the large
search/batch-injection update.

- Reverted commits: `9ec32734` and `5fdd5ae6`.
- Revert commit: `04243ec4 Revert network editing and injection update`.
- `content.js` was verified to match `c2eea7f1` after the revert.
- The uncommitted `CLAUDE.md` cleanup is documentation-only unless explicitly committed later.

Features from `9ec32734` / `5fdd5ae6` are **not active** after this rollback:

- Full-screen request/response string search with blue match highlights.
- Batch injection module (`注入`) and `bhNetInject*` storage keys.
- Request/response direction expansion for user-mark intercept rules.
- Fetch response intercept/edit path added by that update.
- Toolbar `额外 ▾` regrouping from that update.
- The later noise-filter tweak that tried to fix `拦截中 = 0`.

The user reported that APK `5fdd5ae6` still showed `拦截中` count `0` after enabling intercept.
That failed fix is now reverted; do not treat it as a solved root cause if the feature is
reintroduced.

## Project Overview

BrowserHelper-Gecko is a fork of Mozilla Reference Browser using GeckoView + Android Components
(v153 nightly). Project-specific additions:

- Chinese UI localization.
- Cookie export/view through built-in WebExtension `cookie_export_helper`.
- Eruda DevTools through built-in WebExtension `devtools_injector`.
- Custom Network capture/intercept panel implemented in
  `app/src/main/assets/extensions/devtools_injector/content.js`.
- Custom top tab strip, density override, and Google as default search engine.

## Build And Deploy

Use the repository explicitly with `gh`; the local `gh` default repo may point at upstream
`mozilla-mobile/reference-browser`.

```bash
./gradlew :app:assembleDebug --no-daemon
gh workflow run build.yml --ref main -R haoyangtu09-art/BrowserHelper-Gecko
gh run watch <run-id> -R haoyangtu09-art/BrowserHelper-Gecko --exit-status
gh run download <run-id> -R haoyangtu09-art/BrowserHelper-Gecko --dir artifacts/run-<run-id>
```

Local Termux builds need Java 17. If local Gradle fails because Java 17 is missing, use GitHub
Actions; `.github/workflows/build.yml` runs `:app:assembleDebug` with Temurin 17.

Output:

- Local APK: `app/build/outputs/apk/debug/*arm64-v8a*.apk`
- CI artifact: `BrowserHelper-Gecko-<short-sha>-arm64.apk`

No unit tests. Usual checks: `node --check content.js`, `git diff --check`, GitHub Actions build,
and manual on-device testing.

## Key Files

| Purpose | Path |
|---------|------|
| Main DevTools content script | `app/src/main/assets/extensions/devtools_injector/content.js` |
| Eruda JS bundle, patched v3.4.3 | `app/src/main/assets/extensions/devtools_injector/eruda.min.js` |
| DevTools manifest | `app/src/main/assets/extensions/devtools_injector/manifest.json` |
| Native DevTools helper | `app/src/main/java/org/mozilla/reference/browser/devtools/DevToolsHelper.kt` |
| App extension install | `app/src/main/java/org/mozilla/reference/browser/BrowserApplication.kt` |
| Toolbar menu | `app/src/main/java/org/mozilla/reference/browser/browser/ToolbarIntegration.kt` |
| GeckoEngine/density config | `app/src/main/java/org/mozilla/reference/browser/components/Core.kt` |
| Cookie extension reference | `app/src/main/assets/extensions/cookie_export_helper/` |

## Built-In WebExtension Pattern

DevTools is a built-in content-script WebExtension.

- Native port: `devtools_inject`.
- Required permission: `nativeMessagingFromContent`; content scripts cannot reliably
  `connectNative()` without it.
- Storage permission is used for persistent Network intercept marking rules.
- Entry point: app menu `开发者工具` -> `DevToolsHelper.toggle(context)` -> native port message.
- `BrowserApplication` installs the extension and registers the content message handler in
  `onSuccess`.

## GeckoView Execution Worlds

Every page load has two JS worlds sharing the DOM but not globals.

| Mode | Eruda loading | Network hook path | Typical site |
|------|---------------|-------------------|--------------|
| `page` | fetch `eruda.min.js`, inject Blob/script into page | `runInPage(INTERCEPT_JS)` | Normal pages |
| `isolated` | `new Function(src)()` in content script global | `exportFunction` + `wrappedJSObject` | Strong-CSP pages |

Important constraints:

- `tabs.executeScript` is not available; use declared content scripts.
- Strong-CSP pages may block inline/script injection; isolated mode must use Gecko-only
  `exportFunction`, `cloneInto`, and `window.wrappedJSObject`.
- `new Function()` loses Window `this`; patched eruda must use `window.getComputedStyle()`.
- `@font-face` inside shadow root is ignored in Gecko; inject fonts into document light DOM.

## Eruda Bundle Patch Requirements

`eruda.min.js` is a patched Eruda v3.4.3 bundle. If regenerating it, re-apply:

1. Remove `"network"` from Eruda's default tool list. The custom Network panel replaces it.
2. Remove Eruda's `window.fetch = ...` patch.
3. Remove Eruda's `window.WebSocket = ...` patch.
4. Replace bare `getComputedStyle(` calls with `window.getComputedStyle(`.

Eruda tab names are lowercase in the DOM; i18n keys for tab labels must be lowercase.

## Network Panel Architecture

The Network panel is a custom Eruda tool built in `content.js`. Eruda calls only
`tool.init($el)`, so panel DOM, state, and event binding live in the content script.

Registration differs by world:

- Page mode: `injectNetToolPageWorld()` builds hidden DOM in content world, injects a page-world
  Eruda tool script, then moves `#bh-net` into Eruda's container.
- Isolated mode: `registerNetTool(self.eruda)` calls `eruda.add(tool)` directly.

All network bridge messages use `window.postMessage` with `__bhNet: true`:

- `req`: interceptor captured a request.
- `resp`: interceptor captured a response, error, or mock result.
- `breakpoint`: request is paused and needs user action.
- `bpResolve`: UI tells the interceptor to continue or abort.
- `plain`: optional plaintext probe candidate.
- `panelShow` / `panelHide`: Eruda tool visibility changed.

## Network Interceptor Current Behavior

Both page-world and isolated-world paths hook `window.fetch` and `XMLHttpRequest`.

Fetch request-side timing in the reverted/current code:

1. Collect URL, method, headers, and a synchronously readable `init.body` string when available.
2. Send `req` message to content world.
3. Check mock rules.
4. Check manual breakpoints and global/user-mark request intercept.
5. If intercepted, call `waitBp()` before `_origFetch`; page request waits for `bpResolve`.
6. Call original fetch.
7. Read `resp.clone().text()` asynchronously for display/logging, send `resp`, then return the
   original `Response` to the page.

Current code does **not** do batch injection or response-body replacement.

XHR request-side timing is similar for request interception. XHR mock can synthesize events.
True XHR response editing/interception is not implemented.

Config mirrored into page world:

- `__bhMockRules`
- `__bhBreakpoints`
- `__bhThrottle`
- `__bhGlobalInterceptEnabled`
- `__bhInterceptRules`
- `__bhPlainProbeEnabled`

Persistent storage key:

- `bhNetInterceptRules`

Request history is capped at 200 records.

## Global Intercept UI Model

Global request intercept does not open a modal for every request. Pending items live in
`netInterceptQueue` and render in the top `拦截中` list section.

Expected flow:

1. `#bh-intercept-btn` toggles `netGlobalInterceptEnabled`.
2. `syncGlobalInterceptEnabled()` mirrors the flag into page world when needed.
3. A fetch/XHR hook calls `checkGlobalIntercept(...)`.
4. The hook calls `waitBp(..., mode:'intercept')`.
5. A `breakpoint` message reaches the content-world listener.
6. `enqueueBreakpoint()` routes `mode === 'intercept'` to `enqueueIntercept()`.
7. `netInterceptQueue.length` increases and `renderNetList()` shows `拦截中 N`.

When a pending intercept row is selected:

- `netSelIntercept` is set and `netSelReq` is cleared.
- Request headers/body are editable; response tabs are placeholders until the request is sent.
- Bottom actions are relabeled: `重放` -> `发送`, `设断点` -> `中止`.
- `sendSelectedIntercept()` posts `bpResolve` with edited fields.
- `abortSelectedIntercept()` posts `bpResolve` with `action:'abort'`.

Manual URL breakpoints still use `openBreakpointEditor()` and the separate
`netBreakpointQueue` / `netActiveBreakpoint` modal queue.

## Current Network UI Features

Active after rollback:

- URL filter field (`#bh-filter`) using light-DOM edit overlay.
- Export HAR (`exportHAR()`).
- Manual breakpoint management.
- Mock response rules.
- Weak network simulation.
- Request pass/intercept marking rules (`bhNetInterceptRules`).
- Plaintext probe (`明文`).
- Detail editor overlay for request headers/body.

Not active after rollback:

- Full body/header string search with blue match highlights.
- Batch injection/replacement rules.
- Response-side intercept/edit rules.
- `额外 ▾` toolbar regrouping.

## Detail Editing And IME Constraint

GeckoView Android has a practical IME issue with editable fields inside shadow DOM: soft-keyboard
composition can lose characters or route input to the page. The safe rule is:

**Any text entry used by the DevTools UI must happen in a light-DOM overlay attached to
`document.body`, not directly inside Eruda's shadow root.**

Current model:

- `#bh-detail-body` is a read-only display textarea.
- Tapping editable tabs opens `openEditOverlay()`.
- `#bh-filter` is read-only and opens the overlay for search text.
- Modal fields are made read-only and bound through `bindModalFieldEditors()`.
- `openModal()` must not stop click/touch events in capture phase; target controls need their own
  events before modal propagation is stopped.

Editable detail tabs:

- Tab 0: request headers.
- Tab 1: request body.
- Tabs 2-4: response headers, response body, plaintext candidates; read-only.

`renderDetail(force)` should not overwrite dirty edit state unless forced.

## Key Functions

| Function | Purpose |
|----------|---------|
| `buildNetPanel()` | Builds the Network panel DOM and toolbar. |
| `renderNetList()` | Renders history plus `拦截中`; binds row selection and long-press menus. |
| `renderDetail(force)` | Renders selected request/intercept details and tabs. |
| `injectInterceptor()` | Selects page-world or isolated-world interceptor path. |
| `INTERCEPT_JS` | Page-world fetch/XHR hook source string. |
| `injectInterceptorViaExportFunction()` | Isolated-mode fetch/XHR hook implementation. |
| `syncGlobalInterceptEnabled()` | Mirrors global intercept toggle into page world. |
| `checkGlobalIntercept()` | Decides request pause from rules/global toggle/noise filter. Exists in both hook paths. |
| `enqueueIntercept()` | Pushes global/user-mark intercepted requests into `netInterceptQueue`. |
| `sendSelectedIntercept()` | Continues selected queued intercept with edits. |
| `abortSelectedIntercept()` | Aborts selected queued intercept. |
| `releaseAllIntercepts()` | Continues queued intercepts when capture is disabled. |
| `openBreakpointModal()` | Manual breakpoint config UI. |
| `openMockModal()` | Mock response config UI. |
| `openThrottleMenu()` | Weak network config UI. |
| `openRulesView()` | Saved pass/intercept marking rules view. |
| `exportHAR()` | Exports captured network records. |
| `openEditOverlay()` | Light-DOM full-screen text editor. |
| `bindModalFieldEditors()` | Routes modal inputs/textareas through the light-DOM editor. |

## Future Branch: Streaming Intercept Shell

Do not start this work until the user explicitly says **"开始做流式拦截壳"**.

This is a future design for response-side replacement if that feature is reintroduced. The
reverted/current code only reads `resp.clone().text()` for capture and returns the original
`Response`; it does not rebuild responses for replacement.

Future direction:

- Use a `TransformStream`-based response path instead of read-all-then-rebuild.
- `Response` can accept a `ReadableStream` as body.
- Matching/replacement must handle strings split across chunk boundaries by keeping a small overlap
  buffer.
- Keep conservative filters: pass through `event-stream`, binary content, large files, media,
  WASM, and unknown/high-risk types.
- This should replace any future read-all-then-rebuild response modification path, not add another
  user-facing feature.
