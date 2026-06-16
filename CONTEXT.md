# BrowserHelper-Gecko 项目完整上下文与开发者工具现状

## 一、项目基本信息

- **仓库**：https://github.com/haoyangtu09-art/BrowserHelper-Gecko
- **本地路径**：`/data/data/com.termux/files/home/BrowserHelper-Gecko`
- **当前分支**：main
- **最新 commit**：5a305043（Add DEVTOOLS_STATUS.md）
- **Android Components 版本**：153.0.20260614093100（nightly）
- **GeckoView 版本**：同上
- **CI**：GitHub Actions，workflow 文件 `.github/workflows/build.yml`，推送 main 自动构建，产物为 arm64-v8a APK

---

## 二、项目结构（关键文件路径）

```
BrowserHelper-Gecko/
├── app/src/main/
│   ├── assets/extensions/
│   │   ├── cookie_export_helper/          # Cookie 导出扩展（已完成，工作正常）
│   │   │   ├── manifest.json
│   │   │   └── background.js
│   │   └── devtools_injector/             # 开发者工具扩展（开发中）
│   │       ├── manifest.json
│   │       ├── content.js
│   │       └── eruda.min.js               # Eruda v3.4.3，已做外科式修改
│   ├── java/org/mozilla/reference/browser/
│   │   ├── BrowserApplication.kt          # Application 入口，安装所有扩展
│   │   ├── BrowserActivity.kt             # 主 Activity
│   │   ├── EngineProvider.kt              # GeckoRuntime 配置
│   │   ├── browser/
│   │   │   ├── BaseBrowserFragment.kt     # 浏览器核心 Fragment
│   │   │   ├── BrowserFragment.kt
│   │   │   └── ToolbarIntegration.kt      # 工具栏菜单（有开发者工具入口）
│   │   ├── components/
│   │   │   ├── Core.kt                    # 核心组件（Engine, Store 等）
│   │   │   └── UseCases.kt
│   │   ├── cookie/
│   │   │   └── CookieExportHelper.kt      # Cookie 功能（参考模板）
│   │   └── devtools/
│   │       └── DevToolsHelper.kt          # 开发者工具原生侧
│   └── res/
│       ├── layout/fragment_browser.xml    # 浏览器布局
│       └── values/strings.xml             # 中文字符串
```

---

## 三、已完成且稳定的功能

### 1. Cookie 查看/导出
- 菜单：查看 Cookie / 导出 Cookie(JSON) / 导出 Cookie(完整) / 导出 Cookie 到下载器
- 原理：background script + `connectNative` + `browser.cookies.getAll`
- 关键权限：`mozillaAddons`, `geckoViewAddons`, `nativeMessaging`
- 原生侧：`CookieExportHelper.kt`，使用 `BuiltInWebExtensionController`
- 状态：**完全正常，能读取 httpOnly Cookie**

### 2. UI 汉化
- `strings.xml` 全部翻译为中文
- `ToolbarIntegration.kt` 菜单项全部中文
- 应用名：Browser.helper

### 3. 页面缩放修复
- `Core.kt`：`automaticFontSizeAdjustment = false, fontSizeFactor = 1.0f`
- `EngineProvider.kt`：`displayDensityOverride(deviceDensity * 0.85f)`

### 4. 布局修复（顶部工具栏）
- `fragment_browser.xml`：工具栏在顶部，tab 条在最上方
- `BaseBrowserFragment.kt`：`EngineViewClippingBehavior` 用 `getDimensionPixelSize(R.dimen.browser_toolbar_height)` 而非 `toolbar.height`（修复布局偏移）

### 5. 默认搜索引擎
- `BrowserApplication.kt`：启动时等搜索引擎加载完成后自动选择 Google

---

## 四、开发者工具功能——详细现状

### 目标
菜单"开发者工具" → 当前页面右下角出现 Eruda 悬浮按钮 → 点击展开 Console/Elements/Resources/Sources/Info 面板。

### 入口（已完成）
**`ToolbarIntegration.kt`** 菜单：
```kotlin
TextMenuCandidate(text = "开发者工具") {
    DevToolsHelper.toggle(context)
}
```

### 原生侧 `DevToolsHelper.kt`（逻辑正确，已稳定）

```
路径：app/src/main/java/org/mozilla/reference/browser/devtools/DevToolsHelper.kt
```

工作流程：
1. `BrowserApplication.onCreate` 调用 `DevToolsHelper.install(engine, store, sessionUseCases, this)`
2. `install` 内调用 `controller.install(runtime, onSuccess={...})`
3. 安装成功后立即为当前 tab 的 EngineSession 注册 content message handler
4. 用 `BrowserStore.flow()` 观察 tab 变化，每次切换 tab 重新注册 handler
5. 用户点"开发者工具"→ `toggle(context)` → 检查 `portConnected` → 发送 `{action:"toggle"}` 消息
6. 若通道未就绪则 reload 当前页面，reload 后 content script 执行，port 连上后自动触发 toggle
7. content script 回传 `{status:"ok"|"init error:..."|"load error:..."}` 显示为 Toast

**重要技术细节**：
- 使用 `BuiltInWebExtensionController`（与 CookieExportHelper 完全相同模式）
- `portConnected(engineSession, CONTENT_PORT)` 检查当前 tab 的 content script 是否已连接
- `registeredSessions: MutableSet<EngineSession>` 避免重复注册
- `pendingToggle: Boolean` 在 reload 后自动触发

### WebExtension 侧

#### manifest.json
```json
{
  "manifest_version": 2,
  "browser_specific_settings": { "gecko": { "id": "devtools-injector@browserhelper.local" } },
  "permissions": [
    "mozillaAddons",
    "geckoViewAddons",
    "nativeMessaging",
    "nativeMessagingFromContent",
    "<all_urls>"
  ],
  "content_scripts": [{
    "matches": ["http://*/*", "https://*/*"],
    "js": ["content.js"],
    "run_at": "document_idle"
  }],
  "web_accessible_resources": ["eruda.min.js"]
}
```
**关键**：`nativeMessagingFromContent` 是 content script 调用 `connectNative()` 的必须权限（参考 ReaderView 扩展）。

#### content.js（最新方案，commit db406f39，尚未验证结果）

```javascript
// 1. connectNative('devtools_inject') 建立与原生侧的 port
// 2. 收到 {action:'toggle'} 时：
//    a. fetch(browser.runtime.getURL('eruda.min.js')) 获取 eruda 源码（content script fetch 绕过页面 CSP）
//    b. 转成 Blob URL
//    c. <script src=blobUrl> 注入 DOM → Eruda 在页面真实 window 运行（避开 isolated world 限制）
//    d. 内联 <script> 调用 window.eruda.init({useShadowDom:false, tool:[...]})
// 3. 通过 port.postMessage({status:'ok'|'error:...'}) 回报结果
```

#### eruda.min.js（已做的外科式修改）

原始文件：Eruda v3.4.3，489KB，来自 jsdelivr CDN

**在原始文件基础上做了以下修改**（Python 脚本处理，必须从原始文件重新应用）：

```python
# 1. 从默认 tool 列表移除 "network"（Network 模块会 patch fetch/WebSocket）
old = '["console","elements","network","resources","sources","info","snippets"]'
new = '["console","elements","resources","sources","info","snippets"]'

# 2. 移除 window.fetch 赋值块
old = ',e){var t=window.fetch;window.fetch=function(){...}}'
new = ',e){}'

# 3. 移除 window.WebSocket 赋值（含前面的逗号）
old = ',window.WebSocket=t}()'
new = '}()'

# 4. 把所有裸的 getComputedStyle( 改为 window.getComputedStyle(
import re
content = re.sub(r'(?<![.\w])getComputedStyle\(', 'window.getComputedStyle(', content)
```

**原始文件保存在**：`/data/data/com.termux/files/home/eruda.min.js`

---

## 五、开发者工具问题历程

| # | 症状 | 根因 | 解法 | 状态 |
|---|------|------|------|------|
| 1 | "通道未就绪" portConnected=false | `nativeMessagingFromContent` 权限缺失，content script connectNative 静默失败 | 加权限 | ✅ |
| 2 | "通道未就绪" registered=false | handler 注册时机晚于 content script connectNative | onSuccess 里立即注册 + store.flow 观察 tab 变化 | ✅ |
| 3 | 无悬浮窗，无报错 | eruda.min.js 作为 content_scripts 加载后 eruda 变量是 undefined（GeckoView content script global 不继承页面 window） | 改为动态 fetch 加载 | ✅ |
| 4 | `fetch is read-only` | content script isolated world 里 window.fetch 不可写，Eruda Network 模块试图覆盖 | eruda.min.js 里移除 fetch 赋值 | ✅ |
| 5 | `WebSocket is read-only` | 同上 WebSocket | 移除 WebSocket 赋值 | ✅ |
| 6 | `missing } after property list` | 替换 WebSocket 时多删了前面的逗号 | 修正替换范围 | ✅ |
| 7 | `getComputedStyle called on object that does not implement Window`（偶发，加载阶段） | new Function() 执行 Eruda 时 getComputedStyle 丢失 Window this | 改为 Blob URL 注入到页面真实 window | ✅ 待验证 |
| 8 | `getComputedStyle called on object...`（init 阶段） | eruda.init() 在 isolated world 执行，getComputedStyle 仍丢失 this | 改为页面真实 window 执行（Blob URL 方案） | ✅ 待验证 |

---

## 六、当前最新方案（待测试）

**commit db406f39**：Blob URL 注入方案

流程：
1. content script 用自己的 `fetch()` 获取 eruda.min.js 源码（content script 的网络请求不受页面 CSP 限制）
2. `URL.createObjectURL(blob)` 创建 Blob URL
3. `<script src=blobUrl>` 注入页面 DOM → Eruda 代码在页面真实 window 里执行
4. 内联 `<script>` 调用 `window.eruda.init(...)` 在页面真实 window 里初始化

**优点**：Eruda 在页面真实 window 里，getComputedStyle/getSelection 等 API 的 this 绑定正确。

**潜在风险**：
- 部分严格 CSP 页面（`script-src` 不含 `blob:`）可能阻止 `<script src=blob:...>`，toast 会显示 `load error: blob script blocked`
- 内联 `<script>` 调用 init 可能被 `script-src` 无 `unsafe-inline` 的 CSP 阻止

---

## 七、如果 Blob URL 方案失败——下一步方案

**方案：修改 eruda.min.js 让 getComputedStyle 在任何 world 都工作，回到 new Function() 方式**

在 eruda.min.js 里把所有 `getComputedStyle(` 替换为 `(document.defaultView.getComputedStyle)(`：
- `document.defaultView` 在 content script 和页面 world 里都指向真实的 Window 对象
- 不依赖 `this` 绑定，不受 isolated world 影响
- 不需要 DOM 注入，不受 CSP 限制

```python
import re
content = re.sub(
    r'(?<![.\w])getComputedStyle\(',
    '(document.defaultView.getComputedStyle)(',
    content
)
```

content.js 回到 `new Function(code)` 执行方式，不需要 Blob URL 和 `<script>` 标签注入。

---

## 八、关键技术备忘

### GeckoView WebExtension 限制
- **`tabs.executeScript`**：不支持，调用会静默失败
- **content_scripts 里的 eruda**：加载后 `eruda` 变量是 undefined，不暴露到 content script global
- **content script isolated world**：与页面共享 DOM，但 JS global 独立。裸调用 `getComputedStyle()` 在 `new Function()` 内部丢失 Window this
- **`nativeMessagingFromContent`**：content script 调用 `connectNative()` 必须有此权限，否则静默失败
- **`BuiltInWebExtensionController`**：每次 `registerContentMessageHandler` 只保存最新一个 session 的 handler

### Cookie 扩展成功的关键（可参考）
- background script（不是 content script）
- `connectNative` 在 background 里调用（不需要 `nativeMessagingFromContent`）
- 完全不需要注入页面，直接用 `browser.cookies.getAll({url})`
- 权限：`mozillaAddons`, `geckoViewAddons`, `nativeMessaging`, `cookies`, `<all_urls>`

### ReaderView 参考（Mozilla 官方，同版本）
- content script 里调用 `connectNative`（有 `nativeMessagingFromContent` 权限）
- 原生侧在 `install onSuccess` 里立即 `registerContentMessageHandler`
- 用 BrowserStore flow 观察 tab 变化，切换 tab 时重新注册

### 构建和部署
- 推送到 main → GitHub Actions 自动构建
- `gh run list -R haoyangtu09-art/BrowserHelper-Gecko` 查看构建状态
- APK 在 Actions 产物里，用户自己下载

---

## 九、当前 git 状态

```
5a305043 Add DEVTOOLS_STATUS.md: full current state for handoff
db406f39 DevTools: inject eruda via Blob URL into page real window  ← 待测试
6bafe1ed DevTools: reapply all eruda.min.js patches in one shot
8ddb9e84 DevTools: qualify all bare getComputedStyle() calls in eruda.min.js
bdc75dd3 DevTools: bind getComputedStyle/getSelection/matchMedia in Function scope
5957644b DevTools: remove network from default tools + fix all read-only patches
...
ad957ab2 Fix top toolbar offset: use dimension instead of unmeasured height
1f8bf56e Localize UI to Chinese, fix bottom blank, default to Google search
```

工作树干净，无未提交修改。
