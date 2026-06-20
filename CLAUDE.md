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
- **冷启动按持久化意图自动续开（已改）**：`setEnabled()` 把「监听」开关意图存进 `proxy_enabled`(SharedPreferences)。`EngineProvider.getOrCreateRuntime()` 冷启动调 `ProxyProbe.resetProxyStateOnStartup()`：若上次为开 → 直接 `start()` 绑**新端口**并重写 `network.proxy.type=1` 指向它（避开死端口），抓包无缝续上、面板「监听中」与实际一致；若上次为关 → 设 `network.proxy.type=0`（直连）。**不要再改回「冷启动一律复位直连、等用户手动开」**——那会让面板显示「监听中」但实际没代理。

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

#### 请求拦截暂停的「第二个受控例外」（Phase 1：请求方向）

拦截=转发前停住等用户，天生与盲转发冲突。`pumpRequests` 里只对**命中规则且有界**的请求开「暂停 await」分支，其余一切仍走原模型：

- **只暂停有界请求**：复用 rewrite 的 `bounded` 闸门（非 chunked / 非 Upgrade / 非 100-continue / `Content-Length` 合法且 `≤REPLACE_CAP(1MB)`）。流式 / WebSocket / 无法定长的请求**永不暂停**。
- **命中判定**：`matchInterceptReq(host,path,method,hasBody)`，规则 `action=='intercept'`。`path` 取请求行去掉 query。规则四元组与面板 `ruleKey` 对齐。
- **暂停机制**：`pendingIntercepts[flowId] = CompletableFuture`，请求线程 `fut.get(INTERCEPT_TIMEOUT_MS=60s)` 阻塞。面板 `port.postMessage({action:'resolveIntercept',flowId,decision,...})` → `DevToolsHelper` → `ProxyProbe.resolveIntercept()` 完成 future。
- **超时/面板断开 = 自动放行原件**（fail-open，`decision==null` → 转发原 head+body）。**绝不让 socket 挂死**——这是不碰坑#4 的关键。
- **continue**：用编辑后的 url/method/headers/body 经 `buildReqHead` 重建请求行 + identity body 转发上游；**不再 `emitFlowReq`**（面板已从 `reqIntercept` 事件建好该行并本地反映编辑），只 `flowQueue.add` 保持响应 FIFO 对齐。
- **abort**：给浏览器写合成 `403 + Connection: close` 后 `return`（断连，等价 Charles abort）。
- 暂停的请求体先缓冲（`readExact`，有界）才上报面板;二进制体上报为空（仅文本 API 可编辑）。
- 面板侧复用既有「请求拦截」队列 UI：`proxy-feed.js` 收 `reqIntercept`→postMessage `{type:'req'}`+`{type:'breakpoint',mode:'intercept'}`;`bpResolve` 按 `proxyFlowIdForReqId(reqId)` 反查 flowId 回 native。规则下发 `pushInterceptRulesToNative()`（含 `netInterceptRulesLoaded` 守卫，仿 replace），原生 `loadInterceptConfig()` 冷启动恢复。
- **响应方向拦截（Phase 2）尚未实现**：将在 `pumpResponses` 复用 replace 的解码/identity 重组加同款暂停（`respIntercept`/`resolveRespIntercept`）。

#### 按类拦截（遥测 / 噪音 / cookie 三个独立开关）

「拦截全部低价值流量」的反向开关。原生 `isLowValueUrl(host,target,method,reqBodyLen)` 用 `telemetryRe`/`noiseRe`/`cookieRe` 三条正则 + 小体积/GET 护栏判定某请求是否「低价值」（默认低价值 = 不暂停、直接放行）。三个 `@Volatile` 开关 `interceptTelemetry`/`interceptNoise`/`interceptCookie` 由面板下发：**某类开关打开 → 该类不再算低价值 → 会被拦截暂停**。

- 下发链路：面板「长按拦截配置」勾选 → `pushInterceptRulesToNative()` 带 `interceptTelemetry/Noise/Cookie:(master && netScope*)` → `DevToolsHelper` → `ProxyProbe.setInterceptRules()`。
- 持久化：`saveInterceptConfig()`/`loadInterceptConfig()` 一并存这三个布尔（`intercept_config`）。
- 默认全 off = 旧行为（所有低价值流量都放行）。**坑#4 安全**：只改「是否进入既有暂停分支」的判定，不新增缓冲。

#### 原生 Mock（合成响应，复用 abort 模型）

`pumpRequests` 在拦截判定**之前**先查 `matchMock(host,target)`（子串 URL 匹配 `MockRule.pattern`）。命中且 `bounded` 时：`readExact` 抽干有界请求体 → `emitFlowReq` 上报 → `synchronized(clientSock){ write buildMockResponse(rule) }` → `return`（不连上游）。

- `buildMockResponse`：拼 `HTTP/1.1 {status} {reason}\r\n` + 自定义头 + `Content-Length` + `Connection: close\r\n\r\n` + body。等价 Charles「map local / 合成响应」，靠 `Connection: close` 收尾，不触碰 pump。
- 面板下发：`pushMockRulesToNative()`（`netMockRulesLoaded` 守卫）→ `setMockRules` → `parseMockRules`/`saveMockConfig`（`mock_config`）。
- **只对有界请求生效**；流式/无法定长的请求不 mock。坑#4 安全。

#### 原生弱网 / 限速（ThrottledOutputStream + 逐响应延迟）

两段实现，都不缓冲（坑#4 安全）：

- **限速**：`mitm()` 用 `throttleWrap(tlsClient.outputStream)` 包下行方向。`ThrottledOutputStream` 按 8K 分片**边读边写**（write-through），片间 `sleep` 把速率压到 `throttleKbps*1024 B/s`；关闭时纯透传，永不整体缓冲。
- **延迟**：`pumpResponses` 在处理完 1xx、取 `flowQueue.poll()` 之前调 `throttleLatency()`，按 `throttleLatencyMs` 给每个响应注入延迟。
- 面板下发：`pushThrottleToNative()` → `setThrottle` → `applyThrottleConfig`/`saveThrottleConfig`（`throttle_config`）。字段 `throttleEnabled/throttleLatencyMs/throttleKbps`。

## ⚠️ 未解决 Bug：开 Eruda 后刷新页面 → 整页放大一圈 + 错位（APZ 跳变）

> 这是一个**多次尝试仍未修复**的硬骨头。本节是给「全新对话（无记忆）」的完整交接：症状、
> 已**真机证伪**的全部假设、当前代码状态、未尝试的方向。**新对话务必先读完，不要重复已证伪的尝试。**

### 症状（真机可复现）
- 打开 Eruda 控制台后**刷新网页**：整页突然放大约一圈（视觉约 1.18×，≈1/0.85），页面按钮/布局
  挤错位，Eruda 入口按钮点不开，双指缩放也失效（缩不动）。
- **不开 Eruda 只刷新**：完全正常，不放大。→ 触发与 Eruda 注入强相关。
- 放大后**不会自恢复**（停留几秒、滚动都不回弹）。

### 关键事实
- 放大幅度 ≈ 1/0.85，恰是 `EngineProvider.displayDensityOverride(deviceDensity*0.85f)` 的倒数，
  极具迷惑性——但密度已被证伪（见下）。
- 多份分析（commit `c8e0d19e`）判断：**跳变纯发生在 GeckoView 原生 APZ 合成器层**，DOM 完全测不到
  （`window.devicePixelRatio` / `visualViewport.scale` 在跳变前后均**不变**）。这解释了为什么所有
  JS 侧修法都无效。
  > ⚠️ **该结论本轮被质疑(尚待真机确认)**：当时只测了 `dpr` 和 `visualViewport.scale`——这两者对
  > 「Gecko MobileViewportManager(MVM) 的布局视口/zoom-to-fit 重适配」**本就不变**;真正会变的是
  > `window.innerWidth` / `documentElement.clientWidth`,**从没量过**。且捏合分辨率改变**不会**让点击
  > 命中错位,而本 bug 有「触摸坐标整体错位」——这是**布局视口重适配**的特征,不是捏合 APZ。故新假设:
  > 这是 **MVM 在 reload 后的「下一次 reflow 就重算」窗口里,被 Eruda 注入的 reflow 触发、锁错了
  > 布局视口分辨率**(手动开时 MVM 已锁定故不触发)。HEAD 的 `restoreUiSoon` 已加 `geomReport` 打印
  > 整套几何量(Toast 前缀 `BHZOOM`)做判定:真机刷新后看 `iw=`/`cw=` 是否在 `post-inject`→`post-settle`
  > 间变化——变=MVM 重适配(JS/meta 可修),不变=才是纯原生 APZ。
- **手动开 Eruda（页面已 load 完、静置）从不触发**此 bug。差异疑似在「页面刚 reload、APZ 仍在首屏
  解析阶段」时注入 Eruda。

### 已真机证伪的假设（⛔ 不要再试这些）
1. **`displayDensityOverride`（密度）无罪**：`c8e0d19e` 移除它后，自然密度下 Eruda 注入照样放大+错位
   （`b35f0281` 确认）。放大幅度像 1/0.85 是巧合/APZ 自己挑的 resolution，不是密度乘出来的。当前
   `EngineProvider.kt:53` 仍保留 0.85（用户要它让页面铺满，与 bug 无关）。
2. **注入时机无罪**：推迟到 load 后、逐帧轮询视口稳定+至少 1s（`34f8a9b7`）、甚至等用户**首次真实交互**
   （touchstart/scroll/...）后再注入（`92b087fd`）——**全部照样放大**。等多久都没用、且不自恢复。
3. **viewport meta 重写无罪**：注入后强制重写 `<meta name=viewport>` 逼 GeckoView 重算 resolution
   （`45d7af64`）——无效（DOM 测不到的原生层跳变，meta 改不动它）。副作用：临时 `user-scalable=0`
   可能让「缩不了」更糟。已回退。
4. **滚动无罪**：注入后程序化 scroll 撤销——无效（`c8e0d19e` 记录）。
5. **`useShadowDom:false`（换 light-DOM 注入）更糟，已回退（`61f23eff`）**：本想绕开「fixed shadow host」，
   结果**连手动注入都放大**、且**汉化（i18n）失效**。当前两处 `eruda.init` 已回到 `useShadowDom:true`。
6. **「早期求值 eruda bundle」是另一个独立 bug（已修，勿混淆）**：`6782ac0e` 删掉 phase-2 里 load 期间
   提前 `loadPageEruda`（fetch+求值 bundle+注入 @font-face）后，**手动开 Eruda 不再放大**；但**刷新恢复
   仍放大**。即：早期求值只解释了「手动开就放大」那一半，刷新放大是另一条线，至今未解。

### 当前代码状态（MVM 重适配假设 + 收敛修复 + 判定日志，待真机验证）
- `core/utils.js`：两处 `eruda.init` 均 `useShadowDom:true`（已回退 false 实验）。
- `content.js` 阶段 3 `restoreUiSoon`：**已改**——load 后直接 `toggle()` 注入(不再等手势,时机已证伪),
  注入前后 `geomReport` 打印 `BHZOOM iw/cw/ow/vvw/vvs/dpr/sw`(Toast + console);注入后 `lastReflowSettle`
  把「最后一次 reflow」做成一次干净强制重排逼 MVM 收敛,且仅对已声明移动端 viewport 的页面补
  `initial-scale=1`(桌面型无 viewport 页面只重排不改 meta,避免新回归)。三处量测:`post-inject`/
  `post-settle`/`post-settle-1200`。
  - **真机判定**:刷新后看 `BHZOOM` Toast 的 `iw=`/`cw=` 在注入前后是否变化。变→坐实 MVM 布局视口重适配,
    且若 `lastReflowSettle` 让其收敛回正确值则放大消失=修复成功;若 `iw/cw` 不变但仍放大→才是纯原生
    APZ,需转「原生侧重置 resolution」方向。判定后可删 `geomReport`(纯诊断噪音)。
  - ⚠️ phase-2 块(约 37–49 行)残留 `6782ac0e` 时代旧注释说「真凶是 bundle 求值时机」——已被证伪,
    **以本节为准**。
- `EngineProvider.kt:53`：`displayDensityOverride(deviceDensity*0.85f)` 保留。
- **另一未跑的决定性实验**:页面**还在加载时**点工具栏 toggle(`DevToolsHelper.kt:107` port 未连→reload→
  `pendingToggle`),让「手动」路径也经历一次 reload。若**这样也放大**→触发点是 **reload 本身**而非
  `wasActive` 的 document_start 恢复路径,可不动自动恢复逻辑直接修。

### 尚未尝试 / 候选方向
- **iframe 隔离注入**：把 Eruda 塞进独立 `<iframe>` 文档，主页面布局/APZ 不受其 DOM 影响。注意 Eruda 需
  hook 主页面 console/network，跨 frame 可行性需先验证。
- **彻底不在刷新后自动恢复**：刷新后只留一个**自绘的小 light-DOM 按钮**（非 Eruda 容器），用户点它才走
  「手动开」路径（手动路径已知不触发 bug）。代价：刷新后控制台不自动回来，需点一下。
- **原生侧重置 APZ resolution**：注入后由 Kotlin 侧调 GeckoView API 强制把 resolution 重算回 1.0。
  公开 API 是否存在待查（`PanZoomController` 未暴露 setResolution）。
- **复现最小化**：先确认 bug 是否与 MITM 代理无关（应无关，密度结论里提过「代理不开也这样」）、是否所有站点都复现。

### 给新对话的研判提示
- 别再赌「时机/viewport/滚动/密度」——这四条都已真机证伪。
- 核心矛盾点值得深挖：**为什么「手动开」永不触发，而「刷新自动恢复」必触发**，但「等首次用户交互后再注入」
  却仍触发？这说明差异不在「用户是否已交互」，而可能在**页面 reload 的生命周期阶段本身**（首屏 APZ 解析窗口）
  或 **restore 路径（phase 1/2）与纯手动 toggle 的某个未对齐副作用**。
- 验证任何新猜想前，先想清楚「它能否解释 DOM 测不到的原生 APZ 跳变」，否则大概率又是一次无效构建。

## 持久化

- `browser.storage.local`
  - `bhNetConfig`
  - `bhNetReplaceRules`
  - `bhNetInterceptRules`
- `sessionStorage`
  - `__bhErudaActive`
  - `__bhNetConfigCache`
- 原生 `SharedPreferences("bh_devtools")`（`REPLACE_PREFS`）—— 代理侧配置冷启动恢复：
  - `replace_config` / `intercept_config`（含按类拦截三开关）/ `sse_hold_config`
  - `mock_config` / `throttle_config`
  - `proxy_enabled`（「监听」意图，冷启动按此自动续开新端口）

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

---

# Project Handoff Pack（项目接力包）

> 用于在新对话中无损恢复上下文。本节 = 完整版 Handoff + 精简版 + 启动 Prompt 三合一。
> 与上文规范冲突时，以上文为准；本节是导航与状态快照。

## A. 完整版 Handoff

### 1. Project Overview
- **是什么**：Android GeckoView 浏览器，内置自研 DevTools 扩展（Eruda 控制台 + Network 抓包/拦截/替换/断点/Mock/弱网面板）。应用名「网络调试助手」，包名 `com.example.videodownloader.browserhelper.gecko`，扩展 id `netdebug@browserhelper.local`，版本 1.1。
- **为什么存在**：Android 上缺少 Charles/Fiddler 级、又能在手机里直接看自己浏览器流量的工具。把本地 TLS 终止 MITM 代理塞进浏览器进程 + 页面内悬浮面板 = 移动端随身抓包/改包。
- **核心目标**：不破坏页面加载前提下，对本机浏览器流量做抓包（请求/响应含 body）、拦截改包（请求/响应方向）、字符替换、过滤、Mock、弱网，并以插件扩展。
- **当前阶段**：核心抓包/拦截/替换/插件框架已稳定且真机验证。本轮新增按类拦截拆分、原生 Mock、原生弱网、冷启动自动续开代理、整体改名，CI 编译通过（`77545f35`、`07fbd65a`），**待真机验证**。

### 2. Architecture Overview
```
请求流：Browser → MITM → ProxyProbe → DevToolsHelper → proxy-feed → panel
响应流：Server → MITM → ProxyProbe →            proxy-feed → panel
```
- **GeckoView**：`EngineProvider.kt` 建 GeckoRuntime，`enterpriseRootsEnabled(true)`，冷启动调 `resetProxyStateOnStartup()`。`applyProxyPrefs()` 把 `network.proxy.type=1`、http/ssl 指向 `127.0.0.1:<port>`。
- **ProxyProbe.kt**（Kotlin object 单例）：解密 + 双向盲转发（forward-as-read），不做完整 HTTP 解析。`handle()` 读首行 → `CONNECT` 进 `mitm()`。`mitm()` 用 `MitmCa.serverContextFor(host)` 对浏览器终止 TLS（不广告 ALPN h2 → 强制 HTTP/1.1），对上游开 TLS，`pumpRequests`/`pumpResponses` 双向转发。抓包/拦截/替换/mock/弱网都在 pump 上旁路 tee / 受控暂停 / 有界缓冲。
- **DevToolsHelper.kt**：装扩展、连 content port，`onPortMessage` 路由面板指令（setReplaceRules/setInterceptRules/resolveIntercept/resolveRespIntercept/setSseHoldConfig/setMockRules/setThrottle/proxyStart/proxyStop/exportCa），`emitToPanel` 只发当前选中 tab。
- **proxy-feed.js**：`proxyOnMsg` 收原生 `ch:'proxy'` 事件 → `window.postMessage({__bhNet})`。
- **breakpoint/index.js**：`window 'message'` 监听 `__bhNet`，维护 `netReqMap`、`netInterceptQueue`/`netRespInterceptQueue`、`trimNetRequests`（上限 200）。
- **插件系统**：`loader.js` 中枢（注册/启停/持久化 `storage.local:bhEnabledPlugins`，`bhPluginCtx()` 给 `{port,runInPage,log}`）；插件只 `port.postMessage` 下发配置，原生唯一执行。
- 面板 JS 在 content/isolated world，Eruda 在 page world；**页面世界 fetch/XHR 拦截器已删，原生代理是唯一数据源**。

### 3. Current Stable State
- ✅ 真机验证：请求抓包、响应抓包、请求体、响应体（含 chunked+gzip/deflate/br）、请求拦截、响应拦截、过滤、字符替换、插件框架（截流 v0.1）。
- 🟡 已实现 CI 绿、待真机：按类拦截拆分（遥测/噪音/cookie 三独立开关）、原生 Mock、原生弱网、冷启动自动续开代理、改名。

### 4. Remaining Work
- 真机验证本轮 5 项新特性。
- Mock（已落地）：`pumpRequests` 拦截前 `matchMock` 子串匹配 → 抽干有界 body → `buildMockResponse`(含 `Connection: close`) 直接回写、不连上游。
- 弱网（已落地）：`ThrottledOutputStream` 8K write-through 限速 + `pumpResponses` 逐响应 `throttleLatency()` 延迟，均不缓冲。
- 占位插件（网页 Agent / 本地 GPT Plus-Pro）落地真实逻辑。

### 5. Critical Design Decisions
- **MITM 代替 page-hook**：page-world hook 受 CSP、跨世界对象权限、h2/WebSocket 不可见限制；MITM 后流量全可见、与页面解耦。死全局 `__bhMockRules/__bhThrottle/__bhGlobalInterceptNoise` 勿再写。
- **Keep-Alive/逐请求**：同 TCP 逐条处理，`flowQueue` FIFO 对齐请求↔响应，只解析到能定界为止。
- **Chunked**：默认 `relayChunked` 边读边转；仅响应替换开启且命中文档型 content-type（排除 event-stream）才整体去分块（4MB+30s），超时/超界/错帧 → 关连接降级。
- **压缩**：仅改 body 时 `decodeForReplace` 解 gzip/deflate/br，identity 下发；不改请求 Accept-Encoding。
- **插件**：插件=配置下发方，原生=唯一执行方。

### 6. Known Bugs
- **🔴 未解决：开 Eruda 后刷新页面 → 整页放大一圈 + 按钮错位 + 控制台点不开 + 缩放失效。** 多次尝试未修，
  完整排查记录（症状/已证伪假设/当前状态/候选方向）见上文「## ⚠️ 未解决 Bug：开 Eruda 后刷新页面」一节。
  要点：跳变在原生 APZ 合成器层（DOM 测不到），密度/时机/viewport/滚动/useShadowDom 均已真机证伪。
- 多 tab 归属粗糙（MITM 不知发起 tab；「只看本页」靠 host+Origin/Referer 启发式）。
- chunked 替换降级时关连接重载。
- 新特性未真机。
- 冷启动必绑新端口并重写 proxy pref，否则面板「监听中」假象。

### 7. Historical Pitfalls
- **坑#4（最重要）**：MITM 不要做完整 HTTP/1.1 解析/整体缓冲。问题=把盲转发换成按 Content-Length/chunked 整体重组 + 强制 identity；原因=真实流量(keep-alive/chunked/流式/WebSocket/h2-tunnel)错帧或阻塞；结论=开代理后页面加载不出来+Eruda 打不开，只能用「解密后双向 pump」，新功能旁路增量加。
- **Accept-Encoding 修改风险**：强制 identity 请求头触发错帧，不改。
- **整体缓冲卡死**：流式/SSE/无 CL 缓冲会卡死，只允许小/有界/文档型缓冲。
- **reimportEnterpriseRoots 争议**：曾误判页面打不开是删了它，真凶是完整 HTTP 解析；它只影响能否解密/信任，不影响页面加载；每次开代理 false→true 重抓系统 CA，**勿删**。
- **TLS 信任链**：叶子 issuer 用根证书精确 subject DER，勿走 RFC2253 字符串往返（→SEC_ERROR_UNKNOWN_ISSUER）。
- **Proxy 持久化误区**：ServerSocket 随进程死→死端口；冷启动按 `proxy_enabled` 意图续开新端口，勿改回一律直连。
- **Haiku 回归**：低能力模型大改 pump 引回归；动 pump 小步谨慎。
- **shadow DOM 输入失焦**：面板编辑走 light-DOM `openEditOverlay()`，勿放 Eruda shadow root。

### 8. Important Files
- `ProxyProbe.kt` — MITM 代理。`handle/mitm/pumpRequests/pumpResponses/relayChunked/pumpInline/pump`；`telemetryRe/noiseRe/cookieRe`；`isLowValueUrl`；Mock `MockRule/setMockRules/matchMock/buildMockResponse`；弱网 `setThrottle/throttleLatency/throttleWrap/ThrottledOutputStream`；续开 `saveProxyEnabled/loadProxyEnabled/resetProxyStateOnStartup`。
- `DevToolsHelper.kt` — 控制总线，路由面板指令，发当前 tab。
- `MitmCa.kt` — 根 CA + 叶子签发。
- `EngineProvider.kt` — 建 Runtime，冷启动续开。
- `proxy-feed.js` — 原生→page world 桥。
- `breakpoint/index.js` — `__bhNet` 队列。
- `panel/toolbar.js` — UI（过滤菜单/拦截配置 5 行/Mock/弱网/buildNetPanel）。
- `core/utils.js` — `push*RulesToNative`、全局 port。
- `core/config.js` — `save/loadNetConfig`（mock/throttle/scope* 持久化+旧键迁移）。
- `manifest.json` — MV2，17 JS 顺序拼接，content.js 最后。
- `extensions/{loader,presets/*}.js` — 插件。

### 9. Plugin System
- 架构：`loader.js` 中枢，enabled ids 持久化 `bhEnabledPlugins`，`bhPluginCtx()={port,runInPage,log}`，插件只下发配置。
- 已有：截流 v0.1（下发 `setSseHoldConfig`，原生 `holdSseAndReplay/sseHoldActiveFor` 心跳保活缓冲 event-stream）；网页 Agent（占位，仅 log）；本地 GPT Plus/Pro 伪装（占位，仅 log）。
- 未来：mock/弱网/替换沉淀为插件预设；启用即下发、停用即撤回。

### 10. Current Branch Status
- 分支 `main`（PR→`master`），CI 仓库 `haoyangtu09-art/BrowserHelper-Gecko`。
- 正在开发：按类拦截拆分 + 原生 Mock + 原生弱网 + 冷启动续开 + 改名（收尾）。
- 最近成功：核心功能真机验证；最新两 commit CI 绿，APK `BrowserHelper-Gecko-07fbd65a-arm64.apk` 已下载。
- 最近失败：无构建失败；唯一未完成=新特性待真机。
- 最值得推进：① 真机验证 5 项新特性；② 验证后 mock/弱网做成插件预设；③ 落地占位插件。

## B. 精简版 Handoff
> 核心模型：`ProxyProbe.kt`(Kotlin object) = 本地 TLS 终止 MITM，解密+双向盲转发，绝不完整 HTTP 解析/整体缓冲（坑#4：页面加载不出来+Eruda 打不开）。功能在 pump 上旁路 tee/受控有界缓冲/fail-open 暂停增量加。数据流：请求 `Browser→MITM→ProxyProbe→DevToolsHelper→proxy-feed→panel`；响应 `Server→MITM→ProxyProbe→proxy-feed→panel`。page-world 拦截器已删，原生代理唯一数据源。
> 已稳定(真机)：请求/响应抓包+body、请求/响应拦截、字符替换、过滤、截流。已实现待真机：按类拦截(遥测/噪音/cookie 三开关 gating `isLowValueUrl`)、原生 Mock(`matchMock`+`buildMockResponse`+`Connection:close`，仅有界)、原生弱网(`ThrottledOutputStream` write-through+逐响应延迟)、冷启动续开(`proxy_enabled`+新端口)、改名。
> 铁律：①不碰 pump 模型 ②不改请求 Accept-Encoding ③流式/SSE/WS/无 CL 一律放行不缓冲 ④叶子 issuer 用根 subject 精确 DER ⑤`reimportEnterpriseRoots` 勿删（与页面加载无关）⑥冷启动续开新端口勿改回直连 ⑦面板编辑走 light-DOM `openEditOverlay`。
> 持久化：面板 `storage.local:bhNetConfig/bhNetReplaceRules/bhNetInterceptRules`；原生 `SharedPreferences("bh_devtools")`:`replace_config/intercept_config/sse_hold_config/mock_config/throttle_config/proxy_enabled`。
> 验证：JS 跑 `node --check` 每文件 + manifest 顺序拼接；Kotlin 仅 CI 编译(allWarningsAsErrors)；APK 名带 commit SHA。下一步：真机验证 5 项新特性。

## C. 新对话启动 Prompt
```
你接手 BrowserHelper-Gecko：Android GeckoView 浏览器 + 内置 DevTools 扩展
（Eruda 控制台 + Network 抓包/拦截/替换/断点/Mock/弱网面板）。开始前：

【先读文档】
1. 完整读 CLAUDE.md（含本接力包），理解核心模型与铁律。
2. 核心：ProxyProbe.kt(Kotlin object) = 本地 TLS 终止 MITM，解密+双向盲转发，
   绝不做完整 HTTP 解析/整体缓冲（坑#4：开代理后页面加载不出来、Eruda 打不开）。
   功能都在 pump 上旁路 tee/受控有界缓冲/fail-open 暂停增量加。
3. 数据流：请求 Browser→MITM→ProxyProbe→DevToolsHelper→proxy-feed→panel；
   响应 Server→MITM→ProxyProbe→proxy-feed→panel。page-world 拦截器已删，
   原生代理唯一数据源，勿再写 __bhMockRules/__bhThrottle 死全局。

【当前状态】
- 已稳定(真机)：请求/响应抓包+body、请求/响应拦截、字符替换、过滤、截流插件。
- 已实现待真机(77545f35/07fbd65a,CI 绿)：按类拦截拆分、原生 Mock、原生弱网、
  冷启动自动续开代理、改名。首要任务通常是协助真机验证这 5 项，而非新开特性。

【改特定东西的注意事项】
· 改 mitm()/pumpRequests/pumpResponses：绝不缓冲流式/SSE/WS/无 Content-Length；
  只允许小/有界/文档型受控缓冲，改完须能解释为何不碰坑#4。
· 拦截/暂停必须 fail-open（超时/面板断开=放行原件），绝不让 socket 挂死。
· 不改请求 Accept-Encoding；只在改 body 时对响应解码后 identity 下发。
· Mock 保持「仅有界 + buildMockResponse 带 Connection:close + 不连上游」。
· 弱网保持 ThrottledOutputStream 8K write-through（边读边写、片间 sleep），永不整体缓冲。
· MitmCa 叶子 issuer 用根证书精确 subject DER，勿走 RFC2253 字符串往返。
· 不删 reimportEnterpriseRoots（信任链必需），但页面加载卡死先查 pump，别先怀疑证书。
· 冷启动保持「按 proxy_enabled 意图续开新端口」，绝不改回一律直连。
· 面板可编辑控件走 light-DOM openEditOverlay()，勿放 Eruda shadow root（IME 丢焦）。
· DevTools 是 MV2 普通 content script（非 ES module），17 个 JS manifest 顺序拼接
  共享全局，content.js 必须最后，新增模块同步改 manifest。

【工作方式】
· 优先稳定，不推翻既有设计；改 pump 小步谨慎（曾因大改回归）。
· 小步提交：每个独立改动单独 commit。
· 每步验证：JS 跑 node --check 每文件 + manifest 顺序拼接；Kotlin 仅 CI 编译
  （allWarningsAsErrors，本地 Termux 无 Java17）；推送触发 CI、下载带 SHA 的 APK 真机验证。
· 不确定先读代码/问我，别盲改。

现在请先读 CLAUDE.md，再告诉我你理解的当前架构和建议的下一步。
```
