# DevTools 功能开发现状

## 项目信息
- 仓库：haoyangtu09-art/BrowserHelper-Gecko
- 分支：main
- 当前最新 commit：db406f39
- 平台：GeckoView + Android Components v153

---

## 功能目标
菜单"开发者工具"入口 → 在当前页面注入 Eruda（类 Chrome DevTools 面板），支持 Console / Elements / Resources / Sources / Info 面板。

---

## 已完成的部分

### 1. 原生侧（Kotlin）——已稳定工作

**文件：** `app/src/main/java/org/mozilla/reference/browser/devtools/DevToolsHelper.kt`

- 使用 `BuiltInWebExtensionController`（与 CookieExportHelper 同一模式）
- 安装扩展成功后立即为当前 tab 注册 content message handler
- 用 `BrowserStore.flow()` 观察 tab 变化，切换 tab 时自动重新注册 handler
- `portConnected()` 检查通道，连上则 `sendContentMessage({action:"toggle"})`
- 通道未就绪时自动 reload 当前页面（content script 在页面加载时才执行）
- `onPortMessage` 把 content script 返回的 status 显示为 Toast（调试用）

**文件：** `app/src/main/java/org/mozilla/reference/browser/BrowserApplication.kt`
- `DevToolsHelper.install(engine, store, sessionUseCases, this)` 在 `onCreate` 里调用

**文件：** `app/src/main/java/org/mozilla/reference/browser/browser/ToolbarIntegration.kt`
- 菜单项"开发者工具"调用 `DevToolsHelper.toggle(context)`

### 2. WebExtension——基本框架正确，Eruda 注入仍有问题

**文件：** `app/src/main/assets/extensions/devtools_injector/manifest.json`
```json
{
  "manifest_version": 2,
  "browser_specific_settings": { "gecko": { "id": "devtools-injector@browserhelper.local" } },
  "permissions": ["mozillaAddons", "geckoViewAddons", "nativeMessaging", "nativeMessagingFromContent", "<all_urls>"],
  "content_scripts": [{ "matches": ["http://*/*","https://*/*"], "js": ["content.js"], "run_at": "document_idle" }],
  "web_accessible_resources": ["eruda.min.js"]
}
```
关键权限：`nativeMessagingFromContent`（content script 调用 `connectNative` 必须有此权限，参考 ReaderView）

**文件：** `app/src/main/assets/extensions/devtools_injector/content.js`

当前方案（最新，commit db406f39）：
1. Content script 里用 `fetch(browser.runtime.getURL('eruda.min.js'))` 获取源码为 Blob
2. 创建 Blob URL，注入 `<script src=blobUrl>` 到页面 DOM → Eruda 在页面真实 window 运行
3. 再注入内联 `<script>` 调用 `window.eruda.init({useShadowDom:false, tool:[...]})`
4. content script ↔ native 侧通过 `connectNative('devtools_inject')` 的 port 通信

**文件：** `app/src/main/assets/extensions/devtools_injector/eruda.min.js`

原始版本：Eruda v3.4.3（来自 cdn.jsdelivr.net，489KB）

已对原始文件做了以下外科式修改（均用 Python 脚本处理）：
1. 默认 tool 列表去掉 `"network"` → Network 模块不初始化
2. 移除 `window.fetch = function(){...}` 赋值块（替换为空块）
3. 移除 `,window.WebSocket = t` 赋值（Network 模块的 WebSocket patch）
4. 所有裸的 `getComputedStyle(` → `window.getComputedStyle(`

---

## 问题历程

| 症状 | 原因 | 状态 |
|------|------|------|
| "通道未就绪" | `nativeMessagingFromContent` 权限缺失 | ✅ 已修 |
| "通道未就绪" | handler 注册时机晚于 content script connectNative | ✅ 已修（onSuccess 时立即注册） |
| 悬浮窗不出现 | `eruda.min.js` 作为 content_scripts 加载后 `eruda` 变量是 undefined（GeckoView 不暴露到 content script global） | ✅ 已修（改为动态加载） |
| `fetch is read-only` | content script isolated world 里 `window.fetch` 不可写，Eruda Network 模块 patch 它时崩溃 | ✅ 已修（eruda.min.js 里移除该赋值） |
| `WebSocket is read-only` | 同上，WebSocket | ✅ 已修 |
| `missing } after property list` | 替换 WebSocket 赋值时多删了前面的逗号，破坏语法 | ✅ 已修 |
| `getComputedStyle called on object that does not implement Window` | `new Function()` 执行 Eruda 时，裸的 `getComputedStyle()` 丢失 Window this | ✅ 已修（改为 Blob URL 注入到页面真实 window） |

---

## 当前未解决的问题

**最新状态（commit db406f39）尚未测试结果。**

采用 Blob URL 方案后，理论上 Eruda 在页面真实 window 里运行，所有 API 都有正确的 this 绑定。但还需验证：

1. **`<script src=blobUrl>` 是否被页面 CSP 阻止** — Blob URL 在某些严格 CSP 页面（`script-src 'self'` 不包含 `blob:`）会被阻止。如果被阻止，`script.onerror` 触发，toast 显示 `load error: blob script blocked`。
   - 备选方案：改用内联 `<script>` 注入完整 Eruda 源码文本（textContent），但这会被 `script-src` 不含 `unsafe-inline` 的 CSP 阻止
   - 更好的备选：在 eruda.min.js 里直接用内联方式，或者放弃 CSP 限制的页面

2. **`eruda.init()` 的内联 script 是否被 CSP 阻止** — `runInPage` 用的是 `s.textContent = js`，即内联脚本，会被 `script-src` 无 `unsafe-inline` 的 CSP 阻止

3. **即使 CSP 不阻止，悬浮窗是否能正常显示** — 之前曾短暂出现过悬浮窗，但 init 随后报错

---

## 下一步建议

如果 Blob URL 方案被 CSP 阻止，下一个方案：

**方案：完全绕过 CSP——用 XHR + eval 替代 script 标签**

Content script 的 `eval()` 和 `new Function()` 不受页面 CSP 限制（因为它们在 isolated world 执行），但问题是 Eruda 在 isolated world 里有 `getComputedStyle` 等问题。

**真正的解决方案**：修改 Eruda 源码，把所有 `getComputedStyle(` 改成 `(window.getComputedStyle||document.defaultView.getComputedStyle).call(window,`，这样在任何 world 都能正确工作，然后继续用 `new Function()` 方式（不依赖 DOM 注入，不受 CSP 限制）。

具体要做：
1. 在 `eruda.min.js` 里用正则把所有 `getComputedStyle(` 替换为 `(document.defaultView.getComputedStyle)(` — `document.defaultView` 在任何 world 都是真实的 Window 对象
2. content.js 回到 `new Function(code)` 方式，不需要 Blob URL 和 DOM 注入

---

## 关键技术点备忘

- **GeckoView content script isolated world**：content script 和页面共享 DOM，但各自有独立的 JS global（`window`/`self`）。content script 的 `window.getComputedStyle` 等方法在 `new Function()` 内部调用时会丢失 `this` 绑定。
- **`nativeMessagingFromContent` 权限**：content script 调用 `browser.runtime.connectNative()` 必须有此权限，否则静默失败（不报错，port 就是连不上）。参考 ReaderView 扩展的 manifest。
- **`BuiltInWebExtensionController.registerContentMessageHandler`**：每次调用只保存最新一个 session 的 handler 函数（源码确认）。handler 必须在 content script `connectNative()` 之前注册，否则 `onPortConnected` 回调不触发。
- **handler 注册时机**：在 `controller.install()` 的 `onSuccess` 里立即注册当前 tab，并用 `BrowserStore.flow()` 监听 tab 变化持续注册。
- **GeckoView tabs.executeScript**：不支持，不能用。只能用 content_scripts 声明 + connectNative 通信。
- **eruda.min.js 的 patch 脚本**：每次修改都需要从原始文件 `$HOME/eruda.min.js` 重新执行全部 patch，因为 git 里存的是已 patch 的版本，但中间改错了会重新从原始文件来。现在 git 里的版本是正确 patch 后的版本（commit 6bafe1ed）。
