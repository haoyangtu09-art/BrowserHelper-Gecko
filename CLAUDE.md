# BrowserHelper-Gecko — 项目指南

Android GeckoView 浏览器，内置 DevTools 扩展：Eruda 控制台 + 自研 Network 抓包/拦截/替换/断点面板。

## 关键路径

- `app/src/main/assets/extensions/devtools_injector/manifest.json`：WebExtension 清单。`content_scripts` 使用 `run_at: document_start`，并按顺序加载所有模块。
- `app/src/main/assets/extensions/devtools_injector/content.js`：入口文件。只负责最终初始化：`connect()`、刷新后自动恢复、早期拦截恢复流程。
- `app/src/main/assets/extensions/devtools_injector/eruda.min.js`：Eruda v3.4.3，已处理 Gecko shadow-root 字体问题。
- `app/src/main/java/org/mozilla/reference/browser/devtools/DevToolsHelper.kt`：Kotlin 原生侧，连接 native port、发送 toggle、把 content script `postStatus` 显示为 Toast。

## DevTools 模块结构

`devtools_injector/content.js` 已拆分。不要再把新逻辑塞回入口文件。

```text
devtools_injector/
├── content.js
├── core/
│   ├── config.js
│   └── utils.js
├── interceptor/
│   ├── fetch_page.js
│   ├── fetch_isolated.js
│   ├── xhr_page.js
│   ├── xhr_isolated.js
│   └── index.js
├── breakpoint/
│   ├── request.js
│   ├── response.js
│   └── index.js
├── replace/
│   ├── rules.js
│   └── apply.js
├── panel/
│   ├── render.js
│   ├── detail.js
│   ├── toolbar.js
│   └── index.js
└── extensions/
    ├── index.js
    └── presets/
        └── index.js
```

## 加载约束

- 这是 Manifest V2 普通 content script，不是 ES module。不要使用 `import`/`export`。
- 所有模块通过 `manifest.json` 的 `content_scripts[0].js` 顺序加载，共享同一个 content-script global。
- `content.js` 必须放在最后。前面的模块只声明状态、函数和必要事件监听，最后由入口启动。
- 如果新增模块，必须同步更新 `manifest.json` 加载顺序。

## Eruda 注入模式

- **page world**（首选）：用 blob URL 把 Eruda bundle 注入页面世界。
- **isolated world**（强 CSP 回退）：在扩展世界执行 Eruda，并用 Gecko `exportFunction`/`cloneInto` 把 fetch/XHR hook 导到页面世界。
- `erudaMode` 记录当前模式；Toast 状态形如 `ok(page)` / `ok(isolated,page-err:...)`。

## 严重坑

### 1. Eruda 与拦截器顺序

Eruda 的 chobitsu 后端在 bundle 求值阶段 patch 原生 XHR/fetch。必须让 Eruda bundle 先看到原生构造器，再安装我们的拦截器。

刷新恢复流程里可以早期注入拦截器抓首包，但在求值 Eruda 前必须 `disableInterceptor()` 还原，求值后再由 `toggle()->installI18n()` 统一重装。

### 2. 跨世界对象

`exportFunction` 导出的 fetch/XHR 逻辑在 content world 执行，返回给页面的 Promise/Response/XHR 必须属于 page world：

- fetch 快路径直接返回 `_origFetch.call(pw, ...)`。
- mock/改写响应用 `pw.Promise` / `pw.Response`。
- 回调传给 page-world Promise 时必须用 `exportFunction`。

否则 Gecko 会报 `Permission denied to access object/property "then"`，页面请求层会崩。

### 3. shadow DOM 输入

Gecko Android 下 shadow-root 内输入容易 IME 丢焦。面板内编辑必须走 light-DOM 编辑层 `openEditOverlay()`，不要直接把可编辑 input/textarea 放进 Eruda shadow root 里接收键盘输入。

## 持久化

- `browser.storage.local`
  - `bhNetConfig`
  - `bhNetReplaceRules`
  - `bhNetInterceptRules`
- `sessionStorage`
  - `__bhErudaActive`
  - `__bhNetConfigCache`

DevTools 关闭且页面未处于自动恢复流程时，不应保留可见 UI 或阻塞请求。

## 验证

改动 DevTools JS 后至少跑：

```sh
find app/src/main/assets/extensions/devtools_injector -name '*.js' ! -name 'eruda.min.js' -print0 | xargs -0 -n 1 node --check
```

还要按 manifest 顺序拼接检查，避免拆分边界漏括号：

```sh
python3 - <<'PY'
from pathlib import Path
import json
base = Path('app/src/main/assets/extensions/devtools_injector')
manifest = json.loads((base / 'manifest.json').read_text())
out = []
for rel in manifest['content_scripts'][0]['js']:
    out.append((base / rel).read_text())
    out.append('\n')
Path('/data/data/com.termux/files/usr/tmp/devtools_injector_bundle_check.js').write_text(''.join(out))
PY
node --check /data/data/com.termux/files/usr/tmp/devtools_injector_bundle_check.js
```

如修改 page-world 注入字符串，还要生成并检查 `INTERCEPT_JS` / `PLAIN_PROBE_JS`。

## 构建备注

本地 Termux 环境可能缺 Java 17 toolchain，Gradle 会在创建 Android 编译任务时报错。能跑构建时再用当前存在的 app debug 变体验证打包；不要假定旧任务名 `assembleLightningPlusDebug` 一定存在。
