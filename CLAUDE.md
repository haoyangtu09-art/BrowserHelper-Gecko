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

### 4. ⚠️ Native port 连接重复导致消息路由混乱（Phase 1 CRITICAL BUG）

**问题（2026-06-19 发现）**：
- `utils.js: connect()` 建立全局 `port` 连接到 `'devtools_inject'` native port
- Phase 1 新增的 `core/proxy-feed.js: proxyConnectPort()` 试图再次连接同一 port
- 结果：两套系统竞争同一连接，消息路由混乱，代理配置丢失，导致 TLS 握手失败
- 表现：Phase 0b 时 chatgpt.com 能正常解密，Phase 1 后出现 `SSL library protocol error` 

**解决方案**：
- `proxy-feed.js` 不创建新连接，改为复用全局 `port` 变量
- 添加重试机制（`getProxyPort()` + `proxyConnectPort()` 等待 port 就绪）
- 确保所有代理消息通过同一条连接发送到 Kotlin 侧

**教训**：
- Native messaging 端口是宝贵资源，**一个会话只建立一条连接**
- 如果多个模块都需要访问，用全局变量复用而非重复连接
- 消息路由的调试很难 — 一定要在实际设备上测试 TLS/HTTPS 流量

### 5. ⚠️ enterprise_roots 运行期重新导入是必需的（Phase 1 TLS 握手失败的可能根因）

**问题（2026-06-19 怀疑，待设备验证）**：
- 表现：开启代理后所有 HTTPS 打不开，Toast `与浏览器TLS握手失败 … Read error:ssl=…Failure in SSL library, usually a protocol error`
- 这个错误**不是**干净的 `bad_certificate` 告警 —— 是浏览器因不信任叶子证书而中止握手、撕掉连接，Conscrypt 服务端把它报成"协议错误"

**为什么不是握手/证书代码的问题**：
- 对比能用的构建 `a7b65e87`(Phase 0b) 与坏掉的 `a7220126`(Phase 1)：服务端 `ProxyProbe.mitm()`/`startHandshake()`、叶子证书 `MitmCa`、`builder.enterpriseRootsEnabled(true)` **全部逐字节相同**
- Phase 1 只改了 3 个 Kotlin 文件，握手前**唯一**的行为差异是：a7220126 从 `start()` 里删掉了 `reimportEnterpriseRoots()`

**根因**：
- `builder.enterpriseRootsEnabled(true)` 只在 **GeckoRuntime 创建那一刻**抓取一次 Android 系统 CA 库。用户**冷启动之后**才安装的证书（正常流程），builder 抓不到 → 叶子证书不被信任 → 握手中止
- ⚠️ 之前 CLAUDE.md/记忆里"enterprise_roots 只在 NSS 启动导入、运行期切换无效、必须重启"的结论是**错的**。Gecko 源码 `nsNSSComponent.cpp:1711` 有 `else if (prefName.Equals("security.enterprise_roots.enabled"))` —— 是个**运行期 pref 监听器**，false→true 会重新抓取系统 CA 库，**无需重启 App**
- Phase 0b 靠 `reimportEnterpriseRoots()`(false→true) 在每次开启代理时重新导入，所以后装的证书也能即时生效；删掉它就坏了

**解决方案（已在 `ProxyProbe.kt` 实现，未提交）**：
- 恢复 `reimportEnterpriseRoots()`，在 `start()` 每次开启代理时调用（GeckoPreferenceController false→true）
- 加 `rootCaTrustable()` 守卫：根证书的精确 DER 与 `AndroidCAStore` 的 `user:*` 别名逐一比对；若根证书压根没装，提示安装并**拒绝开启**（保持 HTTPS 直连，不静默搞挂浏览）

**教训**：
- enterprise_roots 运行期 false→true 切换**确实有效**，是 MITM 信任链的关键一环，不要再删
- 这类"TLS 握手失败"先怀疑**证书信任**（CA 是否在 NSS 里），不要先怀疑握手/证书生成代码
- 如果根证书确认已在系统库里但 HTTPS 仍失败，下一个怀疑对象才是 `MitmCa` 的叶子证书，而非 enterprise_roots

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

## Phase 1 完整实现（2026-06-19 进行中）

### 架构转变
- **旧**：页内 JavaScript hook（fetch/XHR），覆盖率 ~95%，早期请求漏抓
- **新**：原生 MITM 代理（操作系统网络层），覆盖率 100%，完全独立于页面生命周期

### 改动清单

#### 改动 0：清理诊断代码（完成）
- ✅ `EngineProvider.kt`：删除 `verifyPrefMechanism()`、`diagnoseUserCaStore()`
- ⚠️ `ProxyProbe.kt`：曾删除 `reimportEnterpriseRoots()`（当时误判"运行期修改无效"）—— **这是 Phase 1 TLS 握手失败的根因，已撤销并恢复**，详见上文严重坑 #5

#### 改动 1：原生 HTTP/1.1 MITM 引擎（完成）
- ✅ `ProxyProbe.kt`：~750 行完整 HTTP/1.1 引擎
  - `mitm(client, host, port)` 主循环：完整解析请求→处理→写上游→读响应→处理→写回
  - `HttpMessage` 类：`startLine`, `headers` (MutableList<Pair>), `body` (ByteArray)
  - 低层 I/O：`readChunked()`, `readN()`, `readLineAscii()` 处理 Content-Length 和 Transfer-Encoding: chunked
  - 决策逻辑：`inScope(host)`, `isNoise()`, `ruleMatch()`, `shouldInterceptReq/Resp()`
  - 暂停机制：CountDownLatch-based，socket 级别暂停（进程级、可逆、不丢 Promise）

#### 改动 2：代理↔面板控制通道（完成）
- ✅ `core/proxy-feed.js`：~300 行代理数据源模块
  - **关键修复**：不创建新 port 连接，改为复用全局 `port`（来自 `utils.js: connect()`）
  - `proxyConnectPort()` 延迟等待 port 就绪，避免竞争条件
  - `proxyOnFlowReq/Resp/ReqPaused/RespPaused()` 转换原生事件为面板记录
  - 流量映射：`flowId` ↔ `reqId`，存储 `_proxyFlowId` 便于 resolve
- ✅ `breakpoint/request.js`, `breakpoint/response.js`：新增代理路由
  - `bpResolve()` / `respResolve()` 检查 `entry._proxyFlowId`，若存在则调用 `proxyResolveReq/Resp()`
- ✅ `DevToolsHelper.kt`：`ProxyProbe.setChannel { obj -> emitToPanel(obj) }`，`onPortMessage()` 转发 `ch:'proxy'` 事件

#### 改动 3/4：过滤+拦截/改写（完成）
- ✅ `proxy-feed.js`：`proxyOnReqPaused()` 和 `proxyOnRespPaused()` 触发面板编辑框
- ✅ `panel/render.js`, `core/config.js`：规则自动同步到代理
- 复用现有 `detail.js` 编辑 UI（light-DOM overlay，规避 shadow-DOM IME 坑）

#### 改动 5/6：替换下发+弱网（完成）
- ✅ `proxy-feed.js: proxySendConfig()`：下发 `throttle {enabled, latencyMs, kbps}`
- ✅ `proxy-feed.js: proxyOnConfigChanged()`：下发 `replaceRules[]`
- ✅ ProxyProbe.kt：`applyEditedRequest/Response()` 处理面板编辑，`applyReplace()` 做子串替换

#### 改动 7：退役页内拦截器（完成）
- ✅ 删除 `interceptor/` 目录（5 个文件）
- ✅ 删除 `replace/apply.js`
- ✅ 禁用所有页内注入：`earlyInjectInterceptor()` → NOOP，`injectInterceptor()` 调用移除
- ✅ 禁用 Mock/Breakpoint 页内注入：`injectMockRules()`, `injectBreakpoints()` → NOOP
- ✅ 简化 `content.js`：只负责 Eruda 初始化和 `connect()`, `proxyFeedInit()`

### 关键数据结构

**面板记录格式**（复用旧格式，适配代理源）：
```javascript
{
  reqId: "proxy_123",
  url: "https://api.example.com/endpoint",
  method: "POST",
  reqHeaders: {"Content-Type": "application/json"},
  reqBody: "{...}",
  status: 200,
  respHeaders: {"Content-Length": "512"},
  respBody: "{...}",
  duration: 145,
  error: null,
  ts: 1624128000000,
  tag: null,
  plain: []
}
```

**代理 ↔ 面板 消息格式**（native port）：
```javascript
// 代理 → 面板（事件）
{ch:'proxy', type:'flowReq', flowId, url, method, host, ts, reqHeaders, reqBody}
{ch:'proxy', type:'flowResp', flowId, status, duration, respHeaders, respBody}
{ch:'proxy', type:'reqPaused', flowId, ...}
{ch:'proxy', type:'respPaused', flowId, ...}

// 面板 → 代理（控制）
{ch:'proxy', type:'config', scopeHosts, throttle, interceptOn, respInterceptOn, interceptNoise}
{ch:'proxy', type:'rules', interceptRules, replaceRules}
{ch:'proxy', type:'resolveReq', flowId, action, url, method, reqHeaders, reqBody}
{ch:'proxy', type:'resolveResp', flowId, action, status, respHeaders, respBody}
{ch:'proxy', type:'releaseAll'}
```

### 已知限制与取舍
- ✅ 强制 `Accept-Encoding: identity`（不解压）→ 无需引入 gzip/deflate/brotli
- ✅ WebSocket 升级后转盲 tunnel（不拦截 WS 消息）
- ✅ 响应无 body 的状态（1xx/204/304）不读 body
- ✅ 顶层导航（`Sec-Fetch-Dest: document`）从不暂停 → 页面总能加载 → 面板总能注入

### 当前状态（2026-06-19）
- ✅ 代码全部完成
- ✅ JS 语法检查通过
- ✅ Bundle 拼接检查通过
- ✅ CLAUDE.md 已更新
- ⏳ CI 构建 #27795244637 进行中（后台下载中）
- ⏳ 等待 on-device 测试（特别是 HTTPS/TLS）

## 构建备注

本地 Termux 环境可能缺 Java 17 toolchain，Gradle 会在创建 Android 编译任务时报错。能跑构建时再用当前存在的 app debug 变体验证打包；不要假定旧任务名 `assembleLightningPlusDebug` 一定存在。

### 下一次对话恢复要点
1. **APK 位置**：`~/BrowserHelper-Gecko/artifacts/browser-helper-gecko-arm64-apk/BrowserHelper-Gecko-a7220126-arm64.apk`
2. **主要改动**：proxy-feed.js 复用全局 port（CRITICAL FIX），所有页内拦截已禁用
3. **测试焦点**：HTTPS/TLS 握手是否成功（Phase 0b 能工作 → Phase 1 应该也能）
4. **回滚备选**：如果 TLS 仍失败，优先检查 proxy-feed.js 的 port 连接逻辑而非 MitmCa 证书
