# BrowserHelper Agent Tools Roadmap

> 生成于 2026-06-22。需求来源：AGENT.md §15「用户原始提示词：工具系统扩展方案」（逐字归档）。
> 本轮**只产出 roadmap 文档**，不一次性实现全部工具（见 §7）。
>
> 关键事实（读码确认）：
> - 内置悬浮窗 Agent 的工具全部在 `agent/core/AgentTools.kt` 注册（`buildToolDefs()` 共 40 个），
>   经 `call(tier, name, rawArgs)` → `approver.approve()` → `execute()`。
> - 工具可见性按注册 **S 层**门禁：S2 registry = S1+S2；S3 registry = 全部。`execute()` 不收 tier。
> - 底层实现四类：`AgentTools`（容器/HTTP/计划）、`PageChannel`→`agent.js`（页面世界）、
>   `ProxyProbe`（MITM 网络）、`AgentConfirm`（S3 原生审批闸）。
> - 另有一条**外部通道** `devtools/BrowserBridge.kt`（17 工具，bearer token，供外部 bhcodex/MCP 调），
>   与内置 Agent 工具部分重叠但不是同一注册表，详见 AGENT.md §12.4。本 roadmap 主体是**内置 Agent 工具**。
> - 两个已知边界：`page_exec` 代码执行但返回值恒 `null`（沙箱）；`page_fetch` 同源 OK、跨域被浏览器安全策略拦。

---

## 1. 当前已有工具清单

格式：`Tool | 功能 | 权限 | 状态 | 审批 | 底层实现 | 备注`
- **状态**：是否接入 AgentEngine（全部经 registry 暴露给引擎 → 均「已接入」）。
- **审批**：是否走 `approver.approve()`。当前**所有**工具都过 approver；S1 读类落入 `approvalRequest()` 的
  `else` 分支「允许 Agent 读取这些浏览器数据吗？」——这与目标 §6「S1 默认允许、不弹确认」**不符，需修复**（见备注）。

### 计划 / 容器文件（AgentTools）

| Tool | 功能 | 权限 | 状态 | 审批 | 底层实现 | 备注 |
|---|---|---|---|---|---|---|
| create_plan | 创建悬浮窗计划卡片 | S1 | 已接入 | 弹「更新计划」确认 | AgentTools(PanelState) | Plan Mode 入口 |
| update_plan | 更新当前计划卡片 | S1 | 已接入 | 弹「更新计划」确认 | AgentTools(PanelState) | |
| write_code_file | 向容器代码文件写入/追加完整代码 | S1 | 已接入 | 弹「修改代码文件」确认 | AgentTools(容器 FS) | S1 但写文件，分层偏低，建议升 S2 |
| delete_code_from_file | 从容器代码文件删片段/行范围 | S1 | 已接入 | 弹「修改代码文件」确认 | AgentTools(容器 FS) | 同上，建议升 S2 |
| container_list | 列容器目录文件 | S1 | 已接入 | 弹「读取文件范围」确认 | AgentTools(容器 FS) | §6 应免确认，需修 |
| container_read | 读容器内小文本文件 | S1 | 已接入 | 弹「读取文件范围」确认 | AgentTools(容器 FS) | 同上 |
| container_write | 写容器内小文本文件 | S1 | 已接入 | 弹「写入文件」确认 | AgentTools(容器 FS) | S1 但写入，建议升 S2 |
| batch_edit_files | 批量写容器多文件 | S2 | 已接入 | 弹「写入文件」确认 | AgentTools(容器 FS) | |
| container_serve_url | 容器文件生成本地可加载 HTTP URL | S2 | 已接入 | 弹「读取」确认 | AgentTools(本地 HTTP) | |

### 页面只读 / 受限写（PageChannel → agent.js）

| Tool | 功能 | 权限 | 状态 | 审批 | 底层实现 | 备注 |
|---|---|---|---|---|---|---|
| page_index | 索引页面源码，返回标题/URL/heading/form/link 摘要 | S1 | 已接入 | 弹「读取」确认 | PageChannel(getSource) | §6 应免确认 |
| page_search | 搜索已索引源码返回片段 | S1 | 已接入 | 弹「读取」确认 | PageChannel(searchSource) | 先 page_index |
| page_query | 读 DOM 匹配 selector 的元素摘要 | S1 | 已接入 | 弹「读取」确认 | PageChannel(queryDOM) | |
| page_set_text | 设元素 textContent | S2 | 已接入 | 弹「修改网页」确认 | PageChannel(setText) | 受限写，不执行 JS |
| page_set_html | 设元素 innerHTML | S2 | 已接入 | 弹「修改网页」确认 | PageChannel(setHTML) | |
| page_set_attr | 设元素属性 | S2 | 已接入 | 弹「修改网页」确认 | PageChannel(setAttr) | |
| page_scroll | 滑动窗口/滚动容器 | S2 | 已接入 | 弹「滑动」确认 | PageChannel(scrollPage) | 受限 UI |
| page_long_press | 长按元素（指针/鼠标/触摸按住释放） | S2 | 已接入 | 弹「长按」确认 | PageChannel(longPress) | |
| page_swipe | 连续/长距离滑动 + 指针拖拽手势 | S3 | 已接入 | 弹「连续滑动」确认 | PageChannel(swipePage) | |
| page_fetch | page world 发起 fetch | S3 | 已接入 | 弹「网络请求」确认 | PageChannel(pageFetch) | **跨域失败（浏览器安全）** |
| page_exec | page world 执行任意 JS | S3 | 已接入 | 弹「执行 JS」确认 | PageChannel(evalJS) | **返回值恒 null（沙箱）** |

### 网络 / 代理（ProxyProbe；network_* 读 NetFlowStore）

| Tool | 功能 | 权限 | 状态 | 审批 | 底层实现 | 备注 |
|---|---|---|---|---|---|---|
| network_list | 列最近抓包摘要（六位 code/大小/latency） | S1 | 已接入 | 弹「读取」确认 | NetFlowStore(ProxyProbe) | §6 应免确认 |
| network_get | 按 code/flowId 读单条详情（凭证脱敏） | S1 | 已接入 | 弹「读取」确认 | NetFlowStore(ProxyProbe) | 已脱敏 |
| proxy_status | 读代理运行状态 | S1 | 已接入 | 弹「读取」确认 | ProxyProbe | |
| proxy_start | 开 MITM 代理 | S2 | 已接入 | 弹「修改网络规则」确认 | ProxyProbe | |
| proxy_stop | 关 MITM 代理 | S2 | 已接入 | 弹「修改网络规则」确认 | ProxyProbe | |
| throttle_set | 弱网：逐响应延迟 + 下行限速 | S2 | 已接入 | 弹「修改网络规则」确认 | ProxyProbe | |
| replace_set | 请求/响应文本替换规则 | S2 | 已接入 | 弹「修改网络规则」确认 | ProxyProbe | |
| mock_set | Mock：URL 子串命中返回合成响应 | S2 | 已接入 | 弹「修改网络规则」确认 | ProxyProbe | |
| intercept_set | 配置请求/响应拦截（低价值流量默认放行） | S2 | 已接入 | 弹「修改网络规则」确认 | ProxyProbe | |
| intercept_pending | 列暂停等待决策的请求/响应 | S2 | 已接入 | 弹「读取」确认 | ProxyProbe | |
| intercept_resolve | 决议暂停请求（continue 可编辑/abort 403） | S2 | 已接入 | 弹「处理暂停请求」确认 | ProxyProbe | |
| resp_intercept_resolve | 决议暂停响应（continue 可编辑/abort 断开） | S2 | 已接入 | 弹「处理暂停请求」确认 | ProxyProbe | |
| intercept_resolve_all | 批量放行暂停请求/响应 | S2 | 已接入 | 弹「批量放行」确认 | ProxyProbe | |
| web_search | 调用户配置搜索 API 只读查询 | S1 | 已接入 | 弹「网络请求」确认 | AgentTools(HTTP) | URL 模板 {q}/{key} |
| browser_request | App 进程发 HTTP(S)，返回截断 body | S2 | 已接入 | 弹「网络请求」确认 | AgentTools(HTTP) | |

### 敏感 / 私有目录（S3 + AgentConfirm 原生审批）

| Tool | 功能 | 权限 | 状态 | 审批 | 底层实现 | 备注 |
|---|---|---|---|---|---|---|
| cookie_reveal | 读某 flow 真实 Cookie/Authorization 明文 | S3 | 已接入 | **AgentConfirm 原生确认** | ProxyProbe+AgentConfirm | 明文不进模型，占位符回填 |
| private_file_list | 列 App 私有目录文件 | S3 | 已接入 | 弹「读取文件范围」确认 | AgentTools(私有 FS) | 建议接 AgentConfirm |
| private_file_read | 读 App 私有目录小文本 | S3 | 已接入 | 弹「读取文件范围」确认 | AgentTools(私有 FS) | 同上 |
| private_file_write | 写 App 私有目录小文本 | S3 | 已接入 | 弹「写入文件」确认 | AgentTools(私有 FS) | 同上 |
| private_batch_edit_files | 批量写 App 私有目录多文件 | S3 | 已接入 | 弹「写入文件」确认 | AgentTools(私有 FS) | 同上 |

**§1 小结**：40 个内置工具全部已接入 AgentEngine。**需修复项**：
1. S1 读类（page_index/search/query、network_list/get、proxy_status、container_list/read）当前仍弹「读取确认」，
   与 §6「S1 默认允许、不弹确认」冲突 → 应在 `call()`/`approvalRequest()` 给 S1 读类开免确认快路径。
2. `write_code_file`/`delete_code_from_file`/`container_write` 标 S1 却能写文件 → 建议升 S2（与 §2 file.* 分层一致）。
3. `private_file_*`（S3）目前走普通审批弹窗，未接 `AgentConfirm` 原生闸 → 应与 `cookie_reveal`/`page_exec` 一致接原生确认。

---

## 2. 建议新增工具清单

> 标注规则：`S?` = 目标权限层；`【已存在: xxx】` = 当前已有等价工具（§8 要求「已有就不用加」）。
> 命名用规格里的 `browser.*` 点号命名空间（未来对外协议名），落地时映射到 AgentTools 注册名。

### 页面信息工具

| 建议工具 | 层 | 说明 / 现状 |
|---|---|---|
| browser.page.get_url | S1 | 取当前 URL。【部分已存在: page_index 含 URL】可单列轻量取值 |
| browser.page.get_title | S1 | 取标题。【部分已存在: page_index 含 title】 |
| browser.page.get_text | S1 | 取元素/页面可见纯文本（page_query 偏结构，缺纯文本） |
| browser.page.get_html_summary | S1 | 结构化 HTML 摘要。【已存在: page_index】 |
| browser.page.screenshot | S2 | 视口截图，需原生 GeckoView 抓帧（重，单独评估） |
| browser.page.element_screenshot | S2 | 元素截图，同上 |
| browser.page.get_selection | S1 | 取用户选区文本 |

### DOM 工具

| 建议工具 | 层 | 说明 / 现状 |
|---|---|---|
| browser.dom.snapshot | S1 | DOM/a11y 快照（grounding）。【部分已存在: page_query】 |
| browser.dom.query | S1 | CSS selector 查元素。【已存在: page_query】 |
| browser.dom.find_by_text | S1 | 按可见文本定位元素 |
| browser.dom.get_attributes | S1 | 取属性/value/checked |
| browser.dom.highlight_element | S2 | 高亮元素（注入临时样式） |
| browser.dom.hide_element | S2 | 隐藏元素 |
| browser.dom.replace_text | S2 | 替换元素文本。【近似: page_set_text】 |
| browser.dom.inject_css | S2 | 注入 CSS |
| browser.dom.exec_js | S3 | 执行任意 JS。【已存在: page_exec（返回值 null）】 |

### 页面操作工具

| 建议工具 | 层 | 说明 / 现状 |
|---|---|---|
| browser.page.click | S2 | 普通点击/轻点（**核心缺口**，当前只有 long_press/swipe，无 click） |
| browser.page.type | S2 | focus 输入框、设 value、派发 input/change，可选提交（**核心缺口**） |
| browser.page.clear | S2 | 清空输入框 |
| browser.page.scroll | S2 | 滚动。【已存在: page_scroll】 |
| browser.page.select | S2 | 选 `<select>` 项 |
| browser.page.wait_for_element | S1 | 轮询等元素出现（自动化链式前提，**P0**） |
| browser.page.wait_for_text | S1 | 等文本出现 |
| browser.page.wait_for_navigation | S1 | 等导航/URL 变化（需原生 url 状态） |
| browser.page.submit_form | S3 | 自动提交表单（§3 列 S3：自动提交属高风险） |

### 标签页工具（全新，接 BrowserStore/SessionManager）

| 建议工具 | 层 | 说明 / 现状 |
|---|---|---|
| browser.tabs.list | S1 | 列标签（**P0 最小闭环**） |
| browser.tabs.current | S1 | 当前标签信息（**P0 最小闭环**） |
| browser.tabs.open | S2 | 新开标签并加载 URL（含原生导航） |
| browser.tabs.switch | S2 | 切标签 |
| browser.tabs.close | S2 | 关标签 |
| browser.tabs.rename | S2 | 重命名标签 |
| browser.tabs.group | S2 | 标签分组 |

> 注：标签 + 导航是**原生 GeckoSession** 能力，不走 agent.js（`location`/`history` 受 CSP/同源限制）。
> 需新增一条 native 导航通道（参考 `DevToolsHelper.sendToggle` 的 native 调度）。

### 网络工具

| 建议工具 | 层 | 说明 / 现状 |
|---|---|---|
| browser.network.list | S1 | 【已存在: network_list】 |
| browser.network.get | S1 | 【已存在: network_get】 |
| browser.network.search | S1 | 按关键字搜 flow（新） |
| browser.network.clear | S2 | 清空已抓 flow（新） |
| browser.network.export_har | S2 | 导出 HAR 到容器（新） |
| browser.network.wait_for_request | S1 | 等某请求发生（**P0**） |
| browser.network.wait_for_response | S1 | 等某响应发生（**P0**） |
| browser.network.replay | S2 | 重放请求（近似 browser_request） |
| browser.network.page_fetch | S3 | 【已存在: page_fetch（跨域失败）】 |

### DevTools 工具

| 建议工具 | 层 | 说明 / 现状 |
|---|---|---|
| browser.console.list | S1 | 取 console 日志（**P0**，需 agent.js 挂 console 钩子） |
| browser.console.clear | S2 | 清 console |
| browser.console.subscribe | S1 | 订阅 console 流（事件推送） |
| browser.storage.local_list | S1 | 列 localStorage 键 |
| browser.storage.local_get | S1 | 读 localStorage 值 |
| browser.storage.local_set | S2 | 写 localStorage |
| browser.storage.session_list | S1 | 列 sessionStorage 键 |
| browser.storage.session_get | S1 | 读 sessionStorage 值 |
| browser.storage.indexeddb_list | S1 | 列 IndexedDB（后续） |

### 代理 / 调试工具

| 建议工具 | 层 | 说明 / 现状 |
|---|---|---|
| browser.mock.add_rule | S2 | 加 Mock 规则（细化）。【近似: mock_set 整表】 |
| browser.mock.remove_rule | S2 | 删 Mock 规则 |
| browser.mock.list_rules | S1 | 列 Mock 规则（现只有 set，缺 list） |
| browser.throttle.set | S2 | 【已存在: throttle_set】 |
| browser.throttle.disable | S2 | 关弱网。【已存在: throttle_set enabled=false】 |
| browser.replace.add_rule | S2 | 加替换规则。【近似: replace_set 整表】 |
| browser.replace.remove_rule | S2 | 删替换规则 |
| browser.intercept.request_enable | S2 | 开请求拦截。【近似: intercept_set】 |
| browser.intercept.response_enable | S2 | 开响应拦截。【近似: intercept_set】 |
| browser.intercept.resolve_request | S2 | 【已存在: intercept_resolve】 |
| browser.intercept.resolve_response | S2 | 【已存在: resp_intercept_resolve】 |
| browser.intercept.release_all | S2 | 【已存在: intercept_resolve_all】 |

### 敏感凭证工具

| 建议工具 | 层 | 说明 / 现状 |
|---|---|---|
| browser.cookie.list_redacted | S2 | 只列 cookie 名（值脱敏）（新） |
| browser.cookie.reveal | S3 | 【已存在: cookie_reveal】必须 AgentConfirm |
| browser.cookie.set | S3 | 写 cookie（新，高风险） |
| browser.cookie.delete | S3 | 删 cookie（新） |
| browser.auth.headers_redacted | S2 | 列 Authorization 等头（脱敏）（新） |
| browser.auth.reveal | S3 | 揭示 auth 头明文（新，必须 AgentConfirm） |
| browser.set_cookie_headers_reveal | S3 | 揭示 Set-Cookie 明文（新，必须 AgentConfirm） |

### Agent 自身工具

| 建议工具 | 层 | 说明 / 现状 |
|---|---|---|
| agent.plan.create | S1 | 【已存在: create_plan】 |
| agent.plan.update | S1 | 【已存在: update_plan】 |
| agent.plan.approve | S1 | 批准计划开始执行（现由 UI 按钮做，可暴露为工具） |
| agent.memory.list | S1 | 列记忆（现 UI 有，缺工具） |
| agent.memory.add | S2 | 加记忆 |
| agent.memory.delete | S2 | 删记忆 |
| agent.history.list | S1 | 列历史会话 |
| agent.history.open | S2 | 打开历史会话 |
| agent.history.rename | S2 | 重命名会话 |
| agent.settings.get | S1 | 读设置（API/模型/层级；敏感字段脱敏） |
| agent.settings.set | S2 | 改设置（API key 等需谨慎，建议 S3） |

### 插件 / 扩展工具

| 建议工具 | 层 | 说明 / 现状 |
|---|---|---|
| plugin.list | S1 | 列插件（现 extensions/loader.js 有，缺工具） |
| plugin.create | S2 | 新建插件 |
| plugin.preview | S2 | 预览插件 |
| plugin.enable | S2 | 启用插件 |
| plugin.disable | S2 | 停用插件 |
| plugin.delete | S2 | 删插件 |
| plugin.update | S2 | 更新插件 |
| plugin.get_permissions | S1 | 读插件权限 |
| plugin.request_permission | S3 | 申请高权限（必须确认） |
| plugin.run_tool | S2/S3 | 调插件工具（视插件权限定层） |

### 文件 / 项目工具

| 建议工具 | 层 | 说明 / 现状 |
|---|---|---|
| file.read | S1/S2 | 读文件。【已存在: container_read(容器 S1) / private_file_read(私有 S3)】 |
| file.write | S2/S3 | 写文件。【已存在: container_write / private_file_write】 |
| file.append | S2 | 追加。【近似: write_code_file】 |
| file.list | S1/S2 | 列目录。【已存在: container_list / private_file_list】 |
| file.search | S1 | 工作区内容搜索（新） |
| file.diff | S1 | 文件 diff（新） |
| file.apply_patch | S2/S3 | 应用补丁（新，写私有目录则 S3） |
| file.delete | S2/S3 | 删文件（新） |

> 分层铁律（§2 用户注明）：file.* 操作 **Agent 私有工作区(容器) = S2**；能写 **App 私有目录 / 覆盖配置 /
> 改插件运行代码 = S3**。当前 `container_*`（容器）与 `private_file_*`（App 私有）正是这条分界。

---

## 3. 权限层级表

### S1 工具（低风险只读，默认可用）

| Tool | 说明 | 为什么是 S1 |
|---|---|---|
| page_index / page_search / page_query | 读页面源码/DOM 摘要 | 只读、不改页面、不执行 JS |
| network_list / network_get | 读抓包摘要/详情 | 凭证已脱敏、只读 |
| proxy_status | 读代理状态 | 纯状态读取 |
| container_list / container_read | 读容器文件 | 限容器内、只读、非 App 私有 |
| web_search | 只读搜索查询 | 用户配置的只读 API |
| create_plan / update_plan | 写计划卡片 | 仅改 Agent UI 态，不触碰页面/网络/凭证 |
| 新增: get_url/get_title/get_text/get_selection、dom.find_by_text/get_attributes、wait_for_*、storage.*_list/_get、console.list、*_list_rules、agent.memory.list/history.list/settings.get、plugin.list | 观察类 | 只观察、脱敏、不改状态 |

### S2 工具（中风险操作，需切到 S2）

| Tool | 说明 | 为什么是 S2 |
|---|---|---|
| page_set_text / page_set_html / page_set_attr | 受限 DOM 写 | 改页面但不执行任意 JS |
| page_scroll / page_long_press | 受限 UI 操作 | 模拟指针/触摸，影响页面交互 |
| proxy_start/stop、throttle_set、replace_set、mock_set、intercept_set/pending/resolve/resolve_all、resp_intercept_resolve | 改网络规则/处理拦截 | 修改/暂停流量，影响请求响应 |
| browser_request、container_serve_url、batch_edit_files | App 发请求/起本地服务/批量写容器 | 发请求或批量改容器文件 |
| 新增: page.click/type/clear/select、dom.highlight/hide/inject_css、tabs.open/switch/close、network.clear/export_har/replay、storage.*_set、cookie.list_redacted、auth.headers_redacted、plugin.enable/disable/create | 操作页面/网络/标签/插件 | 改普通状态、不碰凭证明文 |

### S3 工具（高风险 Root，每次必须原生确认）

| Tool | 说明 | 为什么是 S3 | 必须确认的原因 |
|---|---|---|---|
| page_exec / dom.exec_js | page world 执行任意 JS | 可调用页面/浏览器任意 API | JS 可窃取数据/改任意状态，须用户逐次授权 |
| page_fetch | page world 发 fetch | 以页面身份发请求（带页面 cookie） | 可能携带凭证发敏感请求 |
| page_swipe | 连续/长距离拖拽 | 可触发复杂手势（如解锁/拖放敏感控件） | 大幅交互需确认 |
| cookie_reveal / auth.reveal / set_cookie_headers_reveal | 揭示凭证明文 | 直接读 Cookie/Authorization/Set-Cookie | 凭证明文外泄风险最高 |
| cookie.set / cookie.delete | 改 cookie | 可劫持/伪造会话 | 改会话态须确认 |
| private_file_list/read/write/batch_edit | 读写 App 私有目录 | 触碰应用配置/插件运行代码 | 可改 App 行为，须原生确认 |
| submit_form | 自动提交表单 | 代用户提交（可能下单/转账） | 不可逆操作须确认 |
| plugin.request_permission | 插件申请高权限 | 提权 | 提权须用户批准 |

> **铁律**：S3 确认只能由 `AgentConfirm` 原生 UI 产生，模型/页面 JS 不能传 `confirmed=true` 绕过。
> 当前仅 `cookie_reveal` / `page_exec` 接了原生闸；`private_file_*`、未来 `*.reveal` / `cookie.set` 等都要接。

---

## 4. 工具实现优先级

### P0（马上做，明显提升可用性）

- **browser.tabs.list / browser.tabs.current**（全新，最小闭环必需）
- **browser.page.click**（核心缺口：当前无普通点击）
- **browser.page.type**（核心缺口：表单填写）
- **browser.page.wait_for_element**（自动化链式前提）
- browser.page.scroll【已存在 page_scroll，免】
- browser.tabs.switch、browser.network.wait_for_request、browser.network.wait_for_response
- browser.page.screenshot（重，原生抓帧，单列评估）
- browser.console.list（需 agent.js 挂 console 钩子）

### P1（P0 稳定后）

- browser.mock.add_rule / list_rules、browser.throttle.set【已存在】、browser.replace.add_rule
- browser.dom.highlight_element、browser.dom.inject_css
- agent.history.*（list/open/rename）、agent.memory.*（list/add/delete）

### P2（高级能力）

- browser.intercept.*【多数已存在，补 list/细化】、browser.cookie.reveal【已存在】、browser.auth.reveal（新 S3）
- browser.page.exec_js【已存在 page_exec】、plugin.create、plugin.preview

### P3（插件生态 / 长期）

- plugin marketplace、workflow automation、scheduled tasks、MCP client/server 互通

---

## 5. 工具调用显示规范（Agent UI）

> 现状：`agent/ui/ChatScreen.kt` 已有工具卡片（`ToolCard` name/status/summary/diff）与代码块解析。
> 本规范统一后续所有工具的展示，落到该卡片渲染。

- **默认不塞完整 JSON 进对话**：气泡里只显示一行人话摘要（「正在读取页面信息…」/「✓ 已读取页面标题」）。
- **折叠卡片**：每次工具调用渲染为可折叠卡，标题=工具名，副标题=一句话摘要。
- **状态点**：成功**绿点**、失败**红点**、进行中转圈/灰点。
- **原始结果进详情页**：点开卡片才显示原始参数 + 原始返回（代码块）+ 耗时 + 权限层。
- **敏感字段脱敏**：Cookie/Authorization/Set-Cookie/token 一律 `••••`，明文只在 `cookie_reveal` 经原生确认后、
  以占位符回填链路出现，**不进模型上下文**。
- **大结果截断**：超阈值（如 1400 字符）截断 + 「展开全部」；列表类给计数（「共 87 条，显示前 10」）。
- **代码 / JSON 用代码块**：三反引号围栏渲染圆角框 + 右上复制按钮（CopyIcon 已有）。

示例：

```
正在读取页面信息...
✓ 已读取页面标题

正在执行页面脚本...
✓ 已完成

详情：
browser.dom.snapshot
耗时：320ms
权限：S1
```

---

## 6. 审批规则

- **S1**：默认允许；**不弹确认**；结果必须脱敏。
  - ⚠️ **当前实现不符**：S1 读类仍走 `approver.approve()` 的 `else`「允许读取」弹窗。
    需在 `call()` 给 `tier==S1 && 只读` 开免确认快路径（autoApproveAll 之外的常态免确认）。
- **S2**：用户切到 S2 后允许；对**明显修改页面/发请求**的操作弹**轻量**确认；**不得**读 Cookie/Token/Authorization。
  - 轻量确认可记「本会话不再询问该 scope」（`approver.isApproved(scopeKey)` 已支持）。
- **S3**：每次**必须原生 AgentConfirm**；可选「允许一次」/「本会话允许」；
  - 模型**不能**传 `confirmed=true` 绕过；
  - 确认 scope 必须含 **tool name + origin + tab + host/flowId**（现 scopeKey 已含 name + 资源标识，需补 origin/tab）；
  - 用户**拒绝 → 返回工具错误**，不得继续执行（fail-closed，`AgentConfirm` 已是 fail-closed）。

---

## 7. 本轮不实现全部（最小闭环定义）

本轮**只产出本 roadmap**。若进入实现，**仅做 P0 最小闭环 5 个**：
`browser.tabs.list`、`browser.tabs.current`、`browser.page.click`、`browser.page.type`、`browser.page.wait_for_element`。

每个工具落地必须齐 **5 件套**：
1. **schema**：`buildToolDefs()` 里 `tool(name, tier, desc, schema{...})`。
2. **权限层**：tabs.list/current/wait_for_element=S1；click/type=S2。
3. **错误返回**：`err("...")`（selector 缺失/超时/元素未找到）。
4. **UI 展示摘要**：approval `req()` 文案 + execute 返回的 summary（喂 §5 折叠卡）。
5. **测试/自检**：`runSelfTest()` when 分支固定参数 + agent.js/native 各自单测。

落地接入点（每个工具四处，漏一处即「模型可见但调用炸」或「自检缺项」）：
- agent.js 命令处理器（click/type/wait_for_element 走 page world）或 native 通道（tabs.* 走 GeckoSession）。
- `AgentTools.execute()` when 分支。
- `AgentTools.approvalRequest()` req() 分支（S1 三个可走 else 读取；click/type 给 S2 修改文案）。
- `AgentTools.runSelfTest()` when 分支。
- `AgentTools.buildToolDefs()` tool() 定义（进此即自动上「设置→高级→工具自检」面板，无 tier 过滤）。

> tabs.list/current 需**新建 native 通道**（BrowserStore/SessionManager 读标签），不在 agent.js。
> click/type/wait_for_element 复用现有 `PageChannel` → agent.js（参考 long_press/scroll/queryDOM 模式）。

---

## 8. 验证要求 & 总结

### 验证清单（实现时跑）

- JS：`find app/src/main/assets/extensions/devtools_injector -name '*.js' ! -name 'eruda.min.js' -print0 | xargs -0 -n1 node --check` + manifest 顺序拼接 `node --check`。
- Kotlin：本地无 Java17 → 推 GitHub Actions（`-R haoyangtu09-art/BrowserHelper-Gecko`）。
- `allWarningsAsErrors=true`：自查未用 import、可空接收者 `.toString()`、弃用 API（都会 fail CI）。
- 工具权限表与注册表一致：`buildToolDefs()` 的 tier 必须与本文 §3 分层一致。

### 总结

- **整理对象**：40 个已实现内置工具（§1 全表）+ 约 80 个建议工具（§2 十一类）。
- **权限分布**：S1 只读观察类（页面/网络/容器读、计划、wait_for、storage 读、list 类）；
  S2 受限操作（DOM 写、UI 点击/输入/滚动、网络规则、标签操作、插件启停、容器批量写）；
  S3 高危（任意 JS、page fetch、凭证明文读写、App 私有目录、自动提交表单、插件提权）。
- **已有底层实现（无需新建）**：page.scroll/exec_js、network.list/get/wait（部分）、intercept.*、
  throttle、replace、mock、cookie.reveal、file.*（容器 container_* / 私有 private_file_*）、plan、search。
- **需新建底层支持**：
  - **PageChannel/agent.js 新增**：page.click、page.type、page.wait_for_element/text、get_text/get_selection、
    dom.find_by_text/highlight/inject_css、console.list（挂 console 钩子）、storage.*。
  - **新建 native 通道（GeckoSession/BrowserStore）**：tabs.list/current/open/switch/close、
    page.wait_for_navigation、page.screenshot（抓帧）、navigate/reload/back/forward。
  - **ProxyProbe 扩展**：network.search/clear/export_har、mock/replace 的 add/remove/list 细粒度、
    cookie.list_redacted/set/delete、auth.headers_redacted/reveal、set_cookie_headers_reveal。
  - **AgentConfirm 接线**：private_file_*、未来 *.reveal / cookie.set / submit_form 接原生闸。
- **下一步建议（已有就不加）**：
  1. 先补 **browser.page.click**（最大缺口，当前完全没有普通点击）。
  2. 再补 **browser.page.type**（表单自动化核心）。
  3. 再补 **browser.tabs.list / browser.tabs.current**（需新建 native 通道，先打通这条链）。
  4. **browser.page.wait_for_element** 收口最小闭环。
  5. **不要新增**：scroll、exec_js、network.list/get、intercept.*、throttle、replace、mock、cookie.reveal、
     file 读写（容器/私有）——这些已存在，只在 §1 标注或补 list/细化即可。
- **并行修复项（§1/§6 缺陷）**：① S1 读类免确认快路径；② write/container_write 升 S2；
  ③ private_file_* 接 AgentConfirm；④ S3 scopeKey 补 origin/tab。
