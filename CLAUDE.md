# BrowserHelper-Gecko — 项目指南

Android GeckoView 浏览器，内置 DevTools 扩展：Eruda 控制台 + 自研 Network 抓包/拦截/替换/断点面板。

## 关键路径

- `app/src/main/assets/extensions/devtools_injector/manifest.json`：WebExtension 清单。`content_scripts` 使用 `run_at: document_start`，并按顺序加载所有模块。
- `app/src/main/assets/extensions/devtools_injector/content.js`：入口文件。只负责最终初始化：`connect()`、刷新后自动恢复、早期拦截恢复流程。
- `app/src/main/assets/extensions/devtools_injector/eruda.min.js`：Eruda v3.4.3，已处理 Gecko shadow-root 字体问题。
- `app/src/main/java/org/mozilla/reference/browser/devtools/DevToolsHelper.kt`：Kotlin 原生侧，连接 native port、发送 toggle、把 content script `postStatus` 显示为 Toast。
- `app/src/main/java/org/mozilla/reference/browser/devtools/ProxyProbe.kt`：原生 MITM 抓包代理（本地 TLS 终止）。工具栏菜单「代理探针 开/关」切换，默认关闭。详见下文「MITM 抓包代理」。
- `app/src/main/java/org/mozilla/reference/browser/devtools/MitmCa.kt`：内置根 CA + 按域名签发叶子证书，供代理终止 TLS。
- `app/src/main/java/org/mozilla/reference/browser/EngineProvider.kt`：构建 GeckoRuntime，设 `enterpriseRootsEnabled(true)`，冷启动调 `ProxyProbe.resetProxyStateOnStartup()` 复位代理 pref。

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

## MITM 抓包代理（原生网络层）

`ProxyProbe.kt` + `MitmCa.kt`。Charles/Fiddler 等价的本地 TLS 终止代理：只抓本机自己浏览器的流量、根证书由用户手动安装、不外传。

### 当前能用的实现（基线 commit `a7b65e87`，已验证可解密 chatgpt）

**核心模型 = 解密 + 双向盲转发，不解析 HTTP**——这是它能用的关键：

1. `applyProxyPrefs()` 设 `network.proxy.type=1`，`http`/`ssl` 都指向 `127.0.0.1:<port>`，GeckoView 全部流量走本地 `ServerSocket`。
2. `handle()` 读请求第一行：`CONNECT host:443` → 回 `200 Connection established` → 进 `mitm()`；普通 HTTP → 盲转发到 `Host:80`。
3. `mitm()`：
   - 用 `MitmCa.serverContextFor(host)` 给浏览器**终止 TLS**（叶子证书，`useClientMode=false`，不广告 ALPN h2 → 强制 HTTP/1.1）。
   - 向真实服务器开一条上游 TLS（`SSLSocketFactory.getDefault()`，默认不广告 ALPN → 上游也走 HTTP/1.1）。
   - `readReqLine()` 只读**第一行明文请求行**，用于「已解密 HTTPS ✅」toast 证明，并转发给上游。
   - 之后 `pump(cIn,uOut)` + `pump(uIn,cOut)` **双向盲转发字节**。

→ 不缓冲 body、不重组 Content-Length/chunked、不改 header、不暂停。所以不会错帧、不会卡死页面加载。

### CA 信任链（已验证）

- `EngineProvider`：`builder.enterpriseRootsEnabled(true)` —— 仅在 GeckoRuntime 创建那一刻抓取一次 Android 用户 CA 库。
- `ProxyProbe.reimportEnterpriseRoots()`：每次开代理时把 `security.enterprise_roots.enabled` 做一次 **false→true**。Gecko `nsNSSComponent` 监听该 pref 的**变化**，false→true 会重新抓取系统 CA 库，**无需重启 App** —— 所以冷启动之后才安装的证书也能即时生效。这是信任链必需的一环，不要删。
- 叶子证书 issuer 必须用根证书的**精确 subject DER**（`MitmCa`：`JcaX509CertificateHolder(root).subject`），不要走 RFC2253 字符串往返（会反转 RDN 顺序 / 改 ASN.1 串类型 → mozilla::pkix 按精确 DER 链接 issuer↔subject 失败 → `SEC_ERROR_UNKNOWN_ISSUER`）。
- 根证书友好名直接烤进 subject（`CN=抓包前置`）；菜单「导出抓包根证书」写到 Download，用户手动在 设置→安全→安装证书→CA 证书 安装（Android 11+ 应用不能直接装 CA）。

### 持久化约束（已修）

- 代理 pref 写 `PREF_BRANCH_USER` 会持久化、下次启动恢复；但 `ServerSocket` 随进程死。后台被杀+重启后 Gecko 指向死端口 → 全部请求 `NS_ERROR_PROXY_CONNECTION_REFUSED`。
- `EngineProvider.getOrCreateRuntime()` 每次冷启动调 `ProxyProbe.resetProxyStateOnStartup()` 设 `network.proxy.type=0`（直连），用户再手动开。

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

### 4. ⚠️ MITM 代理不要做完整 HTTP/1.1 解析（Phase 1 教训，已回退）

Phase 1（已回退的 commit `a7220126`）把 `mitm()` 的「解密 + 盲转发」换成了完整 HTTP/1.1 解析循环：`readMessage()` 按 Content-Length / Transfer-Encoding 重组整个 body、强制 `Accept-Encoding: identity`、整体缓冲、上报面板、暂停拦截。**结果：开代理后网页加载不出来、Eruda 也打不开。**

- Eruda 打不开是**下游症状**：`DevToolsHelper.toggle()` 在 content port 未连接时会 `reload()` 页面等待；页面因代理挂了加载不完 → port 永不连接 → Eruda 卡住。
- 根因：手写的 HTTP/1.1 重组在真实流量上错帧/挂起（keep-alive、chunked、无 Content-Length 的流式响应会 `readToEnd` 阻塞、WebSocket / h2-over-tunnel 等）。

**经验教训**：

- 能用的模型是**解密后双向 `pump`**，不要替换成阻塞式整体解析。
- 「开代理后页面加载不出来」先怀疑 `mitm()` 的转发/解析逻辑卡死，**不要先怀疑证书信任**（证书问题表现为握手失败 / 证书警告，不是加载卡住）。
- **纠正一个曾经的误判**：以为 Phase 1 页面打不开是删了 `reimportEnterpriseRoots()`。其实把它加回去后（commit `3d0ef650`）页面**依然**打不开 —— 真正元凶是完整 HTTP 解析。`enterprise_roots` 只影响「能否解密 / 信任」，不影响「页面能否加载」，两者是独立的两条线。
- 以后给面板加抓包数据流 / 过滤 / 拦截，必须在**不破坏 pump 模型**的前提下增量加（例如旁路 tee 一份数据给面板，而不是把转发改成阻塞解析），每加一步单独构建验证。

#### 字符替换的「唯一受控例外」（不要当成回退坑#4 的 bug）

响应方向字符替换**必须改写 body**，这与盲转发天生冲突。`pumpResponses` 里对此开了**两类受控缓冲**，其余一切仍走 forward-as-read：

1. **明文定长**：`Content-Length` 已知、非 chunked、有 body、`≤REPLACE_CAP(1MB)` → `readExact` 缓冲、`decodeForReplace` 解压、替换、以 identity 下发（删 `Content-Encoding`、改 `Content-Length`）。
2. **chunked 文档型**（`isReplaceableDoc`：html/json/js/css/xml/text，**显式排除 `event-stream`**）→ `readChunkedToBuffer` 整体去分块（带 `REPLACE_CHUNK_RAW_CAP(4MB)` 上限 + `CHUNK_REPLACE_TIMEOUT_MS(30s)` 读超时），再解压/替换/identity 下发。

**安全边界（务必保持）**：
- 替换**关闭**或 scope 不含 resp 时，`replaceActiveForResp()=false` → 两类缓冲都不进，字节级等同盲转发。
- **SSE / 无 Content-Length 流式 / WebSocket / 未命中文档型 content-type 的 chunked** 永远不缓冲，仍走 `relayChunked`/`pumpInline`。这是不碰坑#4 的关键。
- chunked 缓冲一旦命中**超时 / 超上限 / 错帧**：已消费的字节无法回退 → **关连接**（该资源降级重载），这是开「激进模式」时用户已接受的代价；靠 content-type 闸门 + 30s 超时把概率压到最低。
- 解不开（未知编码 / 解压炸弹）就**原样转发已去分块字节**（保留 `Content-Encoding`，不替换），页面照常。

> 判据：凡是**只对小/有界/文档型响应缓冲、对流式一律放行**的改动，是受控例外；凡是把转发**整体换成**阻塞解析、或对流式也缓冲的，才是坑#4。

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
