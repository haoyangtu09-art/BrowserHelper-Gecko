# 悬浮窗 Agent UI 接力文档（AGENT_UI.md）

> 用途：新对话时只读这一个文件 + `CLAUDE.md`，就能立刻进入「原生悬浮 Agent UI」工作状态。
> 本文件只覆盖 `org.mozilla.reference.browser.agent` 这一层（Jetpack Compose 原生悬浮窗 UI）。
> 抓包代理 / DevTools 扩展那条线看 `CLAUDE.md`，两者基本独立。
> 末次更新对应本轮内置后端接入（2026-06-21，未提交）。改了 UI 后请同步更新本文件。

---

## 0. 一句话定位

浏览器之上叠一个**原生 Android 悬浮窗 Agent**：平时是白色小球贴边停靠，点开展开成
ChatGPT 风格聊天面板（可拖动 / 等比缩放 / 收起）。当前已接入内置模型后端：
OpenAI 兼容 Chat Completions 支持 tool calls，Anthropic 格式支持普通对话；工具执行走
APK 内部 `AgentToolRegistry`，不依赖外部 MCP。所有工具调用（含 S1 读取）都通过悬浮窗
`ApprovalSheet` 二次确认，用户可按“工具 + 具体资源/请求范围”在本会话记住授权；API 设置 / 模型 / 权限层 / 个性化 / 记忆持久化到 App 私有
`SharedPreferences("bh_agent_overlay")`。

---

## 1. 启停链路（插件 → 原生 Service）

```
DevTools「拓展」面板点「HTTP Agent」启用
  → extensions/presets/index.js: onEnable → ctx.port.postMessage({action:'agentOverlayEnable'})
  → DevToolsHelper.kt:185 收到 agentOverlayEnable
  → AgentOverlayController.enable(ctx)           （AgentOverlayController.kt）
      · 无悬浮窗权限 → Toast + 跳系统授权页 + pendingStart=true
        BrowserApplication.onActivityResumed → onActivityResumed() 授权返回即自动启动
      · 有权限 → startService(AgentOverlayService)
  → AgentOverlayService 用 WindowManager 加一个 TYPE_APPLICATION_OVERLAY 窗口
关闭：onDisable → agentOverlayDisable → AgentOverlayController.disable → stopService
```

- 插件描述符在 `app/src/main/assets/extensions/devtools_injector/extensions/presets/index.js`，
  id=`web-agent`，name=`HTTP Agent`，version=`0.3`，带 `detail`/`usage` 字段供详情弹窗读。
- 卡片 UI 在 `extensions/index.js`（方形卡 + 详情弹窗），属于 DevTools 扩展那条线，非 Compose。

---

## 2. 文件地图（agent 包）

### 窗口/服务层（非 Compose，原生 Android）
- `agent/AgentOverlayService.kt` —— **悬浮窗宿主**。单个 WindowManager 窗口，WRAP_CONTENT
  随 Compose 内容大小。管理：展开/收缩、贴边吸附动画、空闲变暗半隐、拖动、**等比缩放**、
  焦点 flag 切换。所有「窗口像素级」行为在这里，Compose 侧只管内容生长。
- `agent/AgentOverlayController.kt` —— 启停闸 + 悬浮窗权限申请 + 授权返回自动续启。
- `agent/OverlayLifecycleOwner.kt` —— 给悬浮窗 ComposeView 提供 Lifecycle/ViewModelStore/
  SavedStateRegistry owner（否则 Compose 报 ViewTreeLifecycleOwner not found 崩溃）。

### Compose UI 层（`agent/ui/`）
- `PanelState.kt` —— **唯一状态容器**。对话内存态；设置/模型/权限/个性化/记忆由
  `AgentSettingsStore` 持久化；`rememberPanelState()`。
- `AgentEngine.kt` —— 内置 Agent turn loop：组 prompt → 调模型 → 处理 OpenAI tool calls →
  悬浮窗确认 → 执行内部工具 → 把 tool result 回写模型。
- `AgentSettingsStore.kt` —— `SharedPreferences("bh_agent_overlay")` 持久化 API key/url、
  搜索 API、模型、权限层、推理档、个性化与记忆。
- `OverlayRoot.kt` —— 顶层 `AnimatedContent(expanded)`：小球 ⇄ 面板。含 `AgentBall`、
  `AgentPanel`（窗口 chrome：蓝拖把 + 收起减号）、`ResizeHandle`（右下缩放手柄）。
- `AgentPanelHost.kt` —— 面板**内部导航宿主**：聊天 + 抽屉/设置/个性化/记忆/高级/模型选择 overlay，
  + Plan 卡片 + 二次确认 sheet。大部分子屏在这；高级页列出所有内部工具并可逐个/全部自检。
- `ChatScreen.kt` —— 聊天主面：顶栏（菜单/Agent/右侧动作按钮）+ 消息列表 + 输入栏 +
  加号悬浮弹窗 + assistant 复制条 + 代码块解析。
- `AgentIcons.kt` —— 全部用 Canvas 画的线性图标（无 vector 资源）。
- `ui/theme/Theme.kt` —— 设计 token：`AgentColors` / `AgentShapes` / `AgentText` /
  `AgentElevation`。**没有 MaterialTheme**（裸 WindowManager 窗口），全用 foundation 原语
  （BasicText / BasicTextField）+ 显式 token。

---

## 3. 设计 token（Theme.kt）— 改样式先查这里

### AgentColors
| token | 值 | 用途 |
|---|---|---|
| Bg | `#F8F8F8` | 子屏/输入框底 |
| Panel | `#FFFFFF` | 抽屉/设置纯白底 |
| Surface | `#F1F2F4` | **面板底**（略低于纯白，让白按钮/弹窗靠色差浮起，无阴影） |
| TextPrimary | `#111111` | 主文字/图标 |
| TextSecondary | `#666666` | 次文字 |
| TextTertiary | `#999999` | 占位提示 |
| Hairline | `#B0B0B0` | 重分隔线 |
| HairlineFaint | `#1F000000` | 面板内描边/细分隔（柔） |
| Control | `#EAEAEA` | 开关轨道/头像底 |
| UserBubble | `#F2F2F2` | 用户气泡 |
| Accent | `#4285F4` | 蓝色强调（发送/选中/滑块 knob） |
| Stop | `#111111` | 生成中停止键底 |

### AgentShapes
`Pill = 50%`、`Panel/Upload = 20dp`、`Field = 24dp`、`Sheet = 14dp`、`Squircle = 38%`（小球）。

### AgentText
`Title 16/SemiBold`、`Body 15`、`Secondary 13`、`Hint 15/Tertiary`、`Label 12/Secondary`。

### 缩放/尺寸常量（**两处必须一致**：OverlayRoot 与 AgentOverlayService）
- `PANEL_BASE_W_DP = 320f`、`PANEL_BASE_H_DP = 520f`（面板基准内容尺寸）。
- `MIN_PANEL_SCALE = 0.75f`、`MAX_PANEL_SCALE = 1.6f`（缩放夹值，还受屏幕边界夹）。
- `PANEL_PADDING_DP = 16f`、`BALL_TOTAL_DP = 61f`、`IDLE_DELAY_MS = 2500L`。
- 缩放模型：面板内容**永远在 base 尺寸布局**，外层 `graphicsLayer{scaleX/scaleY=scale}` 等比缩放，
  外层 Box 实际尺寸 = `base*scale`。所以一个 scale 因子整体放大缩小全 UI（按钮/文字/输入一起）。

---

### Core 后端层（`agent/core/`）
- `AgentModels.kt` —— provider config、message/tool call 模型、S1/S2/S3 权限枚举、
  悬浮审批请求/决策对象。
- `OpenAiBackend.kt` / `AnthropicBackend.kt` —— OpenAI 兼容 / Anthropic wire backend。
  OpenAI 支持 `tools`；Anthropic 当前只做普通对话。
- `AgentTools.kt` —— 内部工具注册表与执行器，复用 `NetFlowStore`、`ProxyProbe`、`PageChannel`。
  工具不会通过外部 MCP 调用；`BrowserBridge` 仍保留给外部 bhcodex 兼容。

---

## 4. PanelState 全字段（当前状态）

```kotlin
enum PanelNav { Chat, Drawer, Settings, ModelSelector, Personalization, Memory, Advanced }
enum ReasonTier { Low, Middle, High }   // 模型选择「智能」三档推理强度
enum AgentPermissionTier { S1, S2, S3 } // 权限滑块三档
data class AgentApprovalRequest(kind, title, detail, scopeKey, scopeLabel, approveLabel, approveSessionLabel, denyLabel)

messages: List<ChatMsg(fromUser, text)>  // 当前会话
input / generating / tempChat            // 输入框 / 生成中闪点 / 临时聊天
nav: PanelNav                            // 当前内部导航
tier: ReasonTier                         // 推理档（默认 Middle）
models / selectedModel                   // 模型列表（空→「暂无模型」）
recentChats                              // 抽屉「最近」（空→只剩标签）
persona（默认"平衡"）/ memoryEnabled      // 我的 Agent：个性化 / 记忆开关
memories: List<String>                   // 已保存记忆（增删，空→「还没有记忆」）
toolChecks: Map<String, ToolCheckState>  // 高级页工具自检状态/原始失败文本
toolsChecking                            // 全部自检运行中
permTier（默认 S1）                       // 权限滑块选中档
pendingApproval: AgentApprovalRequest?   // 非空→底部二次确认 sheet 弹出
planMode / planText                      // Plan 模式开关 + 计划文本
apiKey / apiUrl / searchKey / searchUrl / anthropicFormat  // 持久化设置

send() / stop() / newChat()              // send 触发 AgentEngine
requestApproval() / resolveApproval()    // 工具调用的悬浮窗二次确认等待/决议
hasApprovalGrant(scopeKey)               // 本会话内按具体范围跳过重复确认
onToolSelfTest / onToolSelfTestAll       // 高级页工具自检入口
```

---

## 5. 第三轮 14 项改动落点（R1–R14）

| # | 需求 | 落点 | 关键参数/做法 |
|---|---|---|---|
| R1 | 展开/收缩更顺不闪现 | `OverlayRoot.kt` transitionSpec | 对称 `tween(260,FastOutSlowInEasing)` + scaleIn/Out `initialScale=0.82` + 从锚定角 `TransformOrigin` 生长；保留 `SizeTransform(clip=false){_,_->snap()}` |
| R2 | 点外关闭不灰闪 | `ChatScreen.noRippleClickable` / `AgentPanelHost.Scrim` | `clickable(interactionSource=remember{MutableInteractionSource()}, indication=null)` 去 ripple |
| R3 | 加号→悬浮弹窗非底部 | `ChatScreen.kt` showUpload | `AnimatedVisibility` scaleIn 左下角(`TransformOrigin(0,1)`)生长，`align(BottomStart).padding(start=16,bottom=66)`；`UploadSheet` 宽 170dp 窄卡 |
| R4 | 首条消息按钮变形 | `ChatScreen.ChatTopBar` | `AnimatedContent(messages.isEmpty())` + `SizeTransform{tween(260)}` + fade，圆按钮↔笔+点 pill 拉长变形 |
| R5 | 二次确认（照搬 Codex） | `AgentPanelHost.ApprovalSheet` + `PanelState.requestApproval` | 底部 `slideInVertically{it}` 白卡；按钮文案按文件/网络/页面 JS 等场景生成。`ApproveSession` 记住 `scopeKey`，只跳过同一文件、同一 host、同一 flow 或同一 JS 片段。 |
| R6 | Plan 模式 | `AgentPanelHost.PlanCard` | 顶部 `slideInVertically{-it}` 卡；标题「计划模式」+ planText +「批准并开始/继续编辑」 |
| R7 | AI 输出复制按钮（两张纸） | `AgentIcons.CopyIcon` + `ChatScreen.MessageList` | assistant 每条下方复制条，已接 Android `ClipboardManager`；CopyIcon=后纸 L 形描边+前纸圆角矩形描边。 |
| R8 | 代码块圆角框+右上复制 | `ChatScreen.parseSegments/CodeBlock` | 按 ``` 三反引号拆段；CodeBlock `RoundedCornerShape(12)` 深底 `#1E1E1E`，右上 CopyIcon，已接 Android `ClipboardManager`。 |
| R9 | 权限滑块 S1/S2/S3 | `AgentPanelHost.PermissionSlider` + `AgentToolRegistry` | 模型选择「智能」行右侧；`Pill` 椭圆轨 + `animateFloatAsState` knob 平移；segW=30dp；滑块决定模型可见工具清单。 |
| R10 | 设置重排 | `AgentPanelHost.SettingsScreen` | 「我的 Agent」(个性化/记忆)置顶，API key/url/搜索/请求格式置底；加 verticalScroll |
| R11 | 记忆 UI 做全 | `AgentPanelHost.MemoryScreen` | 启用开关 + 记忆摘要 + 「已保存的记忆」列表(增删 ×) + 空态「还没有记忆」+ PlusIcon 添加 |
| R12 | 改名 HTTP Agent | `presets/index.js` | `name:'网页 Agent'→'HTTP Agent'` |
| R13 | 方形卡+版本+详情+0.3 | `extensions/index.js` + `presets/index.js` | `.bh-ext-card aspect-ratio:1/1`；版本占描述位；`showExtDetailDialog`；v0.3 + detail/usage |
| R14 | 小尺寸 resize 手柄不消失/不飞出 | `OverlayRoot.ResizeHandle` + `AgentOverlayService.resizePanelBy` | 固定 26dp 触摸区 + 半透明底 `#33000000` + 白色双斜线；锚在缩放后面板的圆角内，使用 inverse scale 保持触摸尺寸，Service 每次 scale 变化后 `requestLayout()+updateViewLayout()`。 |

> R6 Plan 卡片仍是手动入口/展示态；R5/R7/R8/R9 已接真实后端或系统能力。

---

## 6. 演示触发点

抽屉底部「演示」区仍保留手动触发：
- 「二次确认演示」→ 置一个 `page_exec` 演示审批请求（含 `scopeKey=demo:page-js`）→ 关抽屉 → 底部 sheet 弹出。
- 「进入计划模式」→ 置 planText + `planMode=true` → 关抽屉 → 顶部 Plan 卡片下滑。

真实工具调用也会使用同一个二次确认 sheet；复制按钮 / 代码块在 assistant 消息里直接可用。

---

## 7. 关键约束 & 坑（动 UI 前必读）

1. **没有 MaterialTheme**：悬浮窗是裸 WindowManager 窗口，没有 Material ambient。
   只能用 foundation 原语（`BasicText`/`BasicTextField`/`Box`/`Canvas`）+ Theme.kt token，
   **不要引 `androidx.compose.material*` 组件**（会缺 theme 崩或不符风格）。
2. **`allWarningsAsErrors=true`**：未用 import、弃用 API（如 `quadraticBezierTo` 已弃用→用
   `quadraticTo`）都会让 CI **编译失败**。加 import 后务必确认全部被用到。
3. **infix `using`**：`ContentTransform using SizeTransform` 在 transitionSpec scope 内
   **不需要 import**（OverlayRoot/ChatScreen 都没 import 也编译通过）。别画蛇添足 import
   `androidx.compose.animation.using`（会被判未用 → fail）。
4. **缩放常量两处同步**：`PANEL_BASE_W_DP/H_DP` 在 `OverlayRoot.kt` 和 `AgentOverlayService.kt`
   各有一份，改一处必须改另一处，否则窗口像素与内容布局错位。
5. **resize 手柄必须跟随缩放后圆角**：当前做法是放在缩放面板内部右下角，并给手柄本身做
   inverse scale 保持 26dp 触摸区；`AgentOverlayService.resizePanelBy()` 每次 scale 变化后必须
   `requestLayout()+updateViewLayout()`，否则缩小时手柄可能停在旧窗口尺寸外侧。
6. **窗口焦点 flag**：收起=`FLAG_NOT_FOCUSABLE`（小球不抢焦点/返回键）；展开=清除该 flag
   （面板输入框才能收 IME）。这也是聊天 UI 用原生而非 Gecko shadow-DOM input 的原因。
7. **崩溃排查**：`AgentOverlayService.installCrashDump()` 把崩溃栈写到
   `Download/agent_crash.txt`（无需 root/logcat）。稳定后可移除。

---

## 8. 真机已确认的交互细节（别改坏）

- 小球：白色 squircle（38% 圆角，非正圆）+ 半透明白外环（`#80FFFFFF` 5dp，「最外层那条线不要动」）；
  空闲 2.5s 变暗(0.4)并半隐贴边，触摸即唤醒滑回；拖动贴边吸附 380ms DecelerateInterpolator。
- 面板：圆角 20dp，底 `Surface`(#F1F2F4) + HairlineFaint 内描边；白按钮/弹窗靠色差浮起**无阴影**。
- 窗口 chrome：顶部蓝色拖把(`#4285F4` 42×3dp)，按住时灰椭圆(`#DADADA` 60×22)显形；
  右上收起=灰圆(`#EAEAEA` 14dp)内减号。
- 展开方向：小球在哪边就从哪边生长（`anchorRight` 由小球中心相对屏幕中线判定）。
- 模型选择：锚定 Agent 按钮下方(`TopStart, start=10,top=44`)窄白卡 220dp；模型列表内联手风琴
  (`expandVertically`)展开在「模型」行下方；空→「暂无模型」。

---

## 9. 构建 / 验证 / CI

- 本地 Termux 需要指定 Java17 toolchain：
  `-Dorg.gradle.java.installations.paths=/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk`。
  当前机器缺 Android SDK / `local.properties:sdk.dir` 时，Kotlin/Android 编译仍会在 SDK 检查处停止。
- DevTools JS 改动必跑：
  ```sh
  find app/src/main/assets/extensions/devtools_injector -name '*.js' ! -name 'eruda.min.js' -print0 | xargs -0 -n1 node --check
  ```
  + manifest 顺序拼接 `node --check`（脚本见 CLAUDE.md）。
- CI：push 触发 GitHub Actions，仓库 `-R haoyangtu09-art/BrowserHelper-Gecko`。
  `gh run watch <id> --exit-status` 等绿；产物 `BrowserHelper-Gecko-<SHA>-arm64.apk`（~262MB）。
  分支 `main`，PR→`master`。
- **content-filter 误判**：一次性输出大段代码块可能触发 Error 400 → 用**小步 Edit**而非大块 Write。
- 提交：单 commit（每轮一个），中文 commit message + `Co-Authored-By: Claude Opus 4.6` trailer。

---

## 10. 将来要接（本轮外，结构已预留）

- Plan 模式已由 `create_plan` / `update_plan` 工具写入；后续可继续接成更完整的“计划批准后批处理”。
- 上传菜单（相机/照片/文件/插件）仍是 UI 入口，尚未接真实能力。
- 如需更强密钥保护，可把 `AgentSettingsStore` 从普通 App 私有 `SharedPreferences` 换成
  EncryptedSharedPreferences。
- 详见 CLAUDE.md「Codex 源码分析与浏览器 Agent 计划」与「落地计划」。

---

## 11. 迭代历史 & 已修 bug（防回退，按时间倒序）

> 给「全新对话（无记忆）」防回退用。改 UI 前看一眼，别把已修的坑改回去。
> 完整 diff 用 `git show <SHA>`。

### 本轮后端补齐（2026-06-21，未提交）
- 原生悬浮窗接入 `AgentEngine`：OpenAI 兼容 Chat Completions 支持 tool calls，Anthropic 当前普通对话。
- `AgentToolRegistry` 改为 APK 内部工具层；S1/S2/S3 各自生成工具注册表，低权限层看不到高权限工具。
- 二次确认改为 Codex 风格的按范围授权：文件按路径/路径组、网络按 method+host、拦截按 flow、JS 按片段 hash 记住；新聊天清空 grant。
- 新增内部工具：`batch_edit_files`、`browser_request`、`page_fetch`、`container_serve_url`、`private_batch_edit_files`。
- 新增全权限工具：`create_plan`、`update_plan`、`write_code_file`、`delete_code_from_file`；四个工具注册为 S1，因此 S1/S2/S3 都可见。代码文件写删只作用于 Agent 外部容器。
- 设置页新增「高级」子页：展示所有内部工具、最低权限层和自检状态；点工具会用固定测试参数真实调用后端工具，OK 显示 OK，失败直接显示原始失败文本；支持「全部自检」。
- Agent 容器改到外部 app-files `agent_container`，不再用 `/data/data` 下的容器目录；`container_serve_url` 启动 `127.0.0.1` 只读文件服务供网页加载容器资源。
- 修复设置/设置子页 overlay 左右互换和露底闪一下的问题：抽屉仍从左出，设置与子页共用右侧全屏层；返回箭头左移。
- 修复右下 resize 手柄缩小时飞出圆角：手柄锚在缩放后面板内部，手柄自身 inverse scale。

### 第三轮 `3c79a488`（2026-06-21，CI 绿+真机待验）
动画打磨 + 二次确认/Plan/代码块 + 权限滑块 + 设置重排 + 记忆 + 拓展方形卡。
即第 5 节 R1–R14 全部。当时二次确认/Plan/复制/代码块/权限滑块主要是 UI 外壳，后端已在本轮接入。

### 第二轮 `6958326d`（2026-06-21，真机验证 OK）
- **色差悬浮感**：面板底改 `Surface(#F1F2F4)`，白按钮/弹窗靠色差浮起，**去掉 drop shadow**。
- **等比缩放**：引入 `PANEL_BASE_W/H_DP` + 单一 `scale` 因子，`graphicsLayer` 整体缩放
  （之前是各控件各自尺寸，缩放会错位）。resize 手柄驱动 scale。
- 输入栏收成**单椭圆**（加号+输入+发送同一白 Pill）；面板圆角收紧；设置拆子页
  （个性化/记忆/模型选择独立 overlay）。

### CI 修复 `77e0bb38`（2026-06-21）
- **`quadraticBezierTo` 已弃用** → 全改 `quadraticTo`。根因：`allWarningsAsErrors=true`
  下弃用 API = 编译失败。**教训：本地无 Java17 不能编译，弃用/未用 import 只能 CI 才暴露，
  写 Canvas 图标用 `quadraticTo` 不要用旧名。**

### ChatGPT 大改 `056cf788` / 面板外壳 `43dc8a0b`（2026-06-21）
- 把裸面板做成贴近官方 ChatGPT app 的纯 UI 外壳：顶栏（菜单/Agent/动作）、消息列表、
  输入栏、抽屉、设置、模型选择器，全 mock 内存态。这是后续两轮的基线。

### 第一轮手感打磨 `b37c5837` / `35a0f2e5` / `14376915`（2026-06-21）
- 小球：纯白 squircle + 半透明白外环（`#80FFFFFF` 5dp，**「最外层那条线不要动」**）+ a 图标、
  缩小 1/4；空闲半隐贴边变暗；吸边/展开/收起动画；面板蓝拖把 + 收起减号。
- 蓝线变细、按住椭圆**全程**显形（一个 gesture loop 同时驱动按压反馈和拖动，避免拖起后椭圆消失）、
  动画放慢、减号缩小、白球外圈贴合。

### 已修 bug：右侧展开小球先左跳 `e2065730`（2026-06-21）
- 现象：小球在屏幕右侧时，点开展开瞬间小球先「跳到左边」再长出面板。
- 根因：`AnimatedContent` 默认把共享内容按容器 `TopStart` 布局，而右侧面板窗口是向左生长，
  小球被摆到左上角 → 视觉左跳。
- 修复：`AnimatedContent(contentAlignment = if(anchorRight) TopEnd else TopStart)`，
  把共享内容钉在锚定侧。**不要改回默认对齐。**

### 已修崩溃：ViewTreeLifecycleOwner not found `33ca3f47`（2026-06-21）
- 现象：悬浮窗一加就崩。
- 根因：Compose 建 window recomposer 时从**根 View 向上**找 `ViewTreeLifecycleOwner`；
  只把 owner 设在子 `ComposeView` 上，查找从 FrameLayout 根开始就找不到 → 崩。
- 修复：`OverlayLifecycleOwner` 的三个 owner（Lifecycle/ViewModelStore/SavedStateRegistry）
  必须设在**窗口根 View（FrameLayout container）**上，不是子 ComposeView。**别挪回子 View。**

### 诊断设施 `b22330b7`（2026-06-21）
- 崩溃栈写 `Download/agent_crash.txt`（无需 root/logcat，链到原 handler 保留正常崩溃流程）；
  Service 改 `START_NOT_STICKY`。稳定后可移除 `installCrashDump()`。

### Phase 1 管线打通 `e49cd81a`（2026-06-21）
- 「web-agent」插件 → 原生悬浮窗管线打通：裸小球 + 空白面板 + 悬浮窗权限申请/授权返回续启。
  这是整个 Agent UI 的起点。

> 注：拓展插件框架本身（`81378531` 方块卡 + 生命周期、`f8fed3bf`「拓展」升为顶级 Eruda tool、
> 截流插件 `2effaa19`/`8d1d4175`）属于 DevTools 扩展那条线，细节看 CLAUDE.md。本轮只把
> 拓展卡片改方形 + 详情弹窗、把 web-agent 改名 HTTP Agent + v0.3。

---

## 12. Agent 工具层（后端工具清单 & 用法）

> ⚠️ **两个关键澄清，先读：**
> 1. **原生悬浮窗 Agent 当前不通过 MCP 调工具**。内置模型 loop 直接调用
>    `agent/core/AgentTools.kt:AgentToolRegistry`，该注册表在 APK 内复用 `NetFlowStore`、
>    `ProxyProbe`、`PageChannel`。`BrowserBridge.kt` 仍保留给外部 bhcodex/MCP 兼容，但不是
>    悬浮窗后端依赖。
> 2. `create_plan` / `update_plan` 只属于原生悬浮窗内部工具层，会写入 `PlanCard`；
>    外部 `BrowserBridge` 兼容通道仍没有 plan 工具。

### 12.0 内置 Agent 工具层（悬浮窗）

权限层由模型选择卡上的 S1/S2/S3 滑块控制。`AgentToolRegistry` 为三层分别生成工具注册表，
模型只会看到当前层级允许的工具；即使工具可见，首次调用仍必须通过悬浮窗 `ApprovalSheet`
二次确认。点“允许，并记住...”后只记住当前 `scopeKey`，例如同一个文件、同一组文件、同一
method+host、同一条拦截 flow 或同一段 JS；换资源会重新确认。

| 级 | 含义 | 工具例子 |
|---|---|---|
| **S1** | 浏览器层只读 + Agent 本地容器读写 + 计划/容器代码文件工具；每次确认 | `create_plan/update_plan`、`write_code_file/delete_code_from_file`、`page_index/page_search/page_query`、`network_list/network_get`、`container_*`、`web_search` |
| **S2** | S1 + 写当前页面 DOM + 网络改写/拦截/Mock/弱网 + 浏览器进程请求 + 批量写容器；每次确认 | `page_set_text/page_set_html/page_set_attr`、`proxy_*`、`intercept_*`、`replace_set/mock_set/throttle_set`、`batch_edit_files`、`browser_request`、`container_serve_url` |
| **S3** | S2 + page-origin fetch + 任意 page world JS + 凭证明文 + 浏览器私有目录读写；每次确认 | `page_fetch`、`page_exec`、`cookie_reveal`、`private_file_*`、`private_batch_edit_files` |

S1 的 `container_*` 路径限制在外部 app-files `agent_container`，优先使用
`Context.getExternalFilesDir("agent_container")`，没有外部目录时才回退到 app 私有 fallback。
S3 的 `private_file_*` 路径限制在 App 私有 dataDir 内。两者都拒绝绝对路径和 `..` 逃逸。
`container_serve_url` 会启动只监听 `127.0.0.1` 的只读 HTTP 文件服务，返回
`/agent-container/<path>` URL，供当前网页加载容器资源。`page_exec` 和 `page_fetch` 均为 S3-only。

### 12.0.1 本轮新增/补齐的内部工具

- `create_plan {title,steps,content}`（S1）— 创建/显示悬浮窗计划卡片，S1/S2/S3 都可见。
- `update_plan {title,steps,content}`（S1）— 更新当前计划卡片，S1/S2/S3 都可见。
- `write_code_file {path,code,mode}`（S1）— 向 Agent 外部容器代码文件写入/追加代码，S1/S2/S3 都可见。
- `delete_code_from_file {path,all,startLine,endLine,startMarker,endMarker,code}`（S1）— 删除容器代码文件的指定行/标记片段/精确片段，S1/S2/S3 都可见。
- `batch_edit_files {edits:[{path,content}]}`（S2）— 一次写多个 Agent 外部容器文本文件，审批范围为排序后的目标路径组。
- `browser_request {url,method,headers,body,timeoutMs,followRedirects}`（S2）— 从浏览器 App 进程发 HTTP(S) 请求，返回状态、响应头、截断响应体。
- `container_serve_url {path}`（S2）— 为容器文件生成本地 `http://127.0.0.1:<port>/agent-container/...` URL。
- `page_fetch {url,method,headers,body,credentials,mode,requiredPageUrlContains,timeoutMs}`（S3）— 在当前网页 page world 执行 `fetch`，可用 `requiredPageUrlContains` 校验来源页面。
- `private_batch_edit_files {edits:[{path,content}]}`（S3）— 一次写多个浏览器私有目录文本文件。

### 12.0.2 高级页工具自检

- 设置 → 高级：`AdvancedScreen` 通过 `agentToolInfos()` 列出所有内部工具和最低权限层。
- 点单个工具：`AgentEngine.testTool()` → `AgentToolRegistry.selfTest(name)`。该路径不走模型，也不弹二次确认；用户点击即视为本次测试授权。
- 点「全部自检」：按工具注册顺序逐个执行。页面类工具会真实打到当前 PageChannel；没有活动页面时直接显示 PageChannel 原始错误。搜索 API 未配置、抓包库无 flow、目标页不支持 fetch 等都会按工具返回值展示。
- 自检使用临时路径 `.agent_self_test/*`，计划工具会真实写入 Plan 卡片；网络规则类工具用禁用/空规则最小调用，`proxy_start/proxy_stop` 会尽量恢复测试前运行状态。

### 12.1 外部 bhcodex ↔ APK（兼容通道）

- `BrowserBridge` 是手搓最小 HTTP/1.1 + JSON-RPC 的 **streamable-HTTP MCP server**，
  监听 `127.0.0.1`，端口候选 `8771/8772/8773/8780`（占用则取随机空闲口）。
- 启动入口：工具栏菜单触发 → `ToolbarIntegration.kt:202 BrowserBridge.start()`；
  另外 `DevToolsHelper.kt:70` 也会 `start()`。token 持久化在 `SharedPreferences("bh_devtools"):bridge_token`。
- 注册命令（`connectCommand()` 生成，菜单可一键复制）：
  ```sh
  export BH_BRIDGE_TOKEN=<token>
  bhcodex mcp add browserhelper --url http://127.0.0.1:<port>/mcp \
       --bearer-token-env-var BH_BRIDGE_TOKEN
  ```
- **鉴权**：`POST /mcp` 必须带 `Authorization: Bearer <token>`，否则 401。这是挡住本机其他
  app 驱动 Agent 的唯一闸（Termux 与 app 不同 UID，token 在 app 内显示、手动粘进 bhcodex 一次）。
- 协议：`initialize` / `tools/list` / `tools/call` / `ping`；通知(无 id)→202。`GET`→405（无 SSE 流）。
- MCP 版本 `2025-06-18`，serverInfo `browserhelper/0.1`，请求体上限 1MB。

### 12.2 外部 BrowserBridge 权限分级（L1/L2/L3）

| 级 | 含义 | 闸门 |
|---|---|---|
| **L1** | 只读、非敏感 | 无需确认 |
| **L2** | 改页面/网络配置（代理/限速/替换/Mock/拦截） | 无需确认（本轮；将来可挂权限层） |
| **L3** | 读 cookie/auth 明文、执行任意 JS | **每次必过 `AgentConfirm` 原生弹窗** |

- L3 集合（硬编码）：`page_exec`、`cookie_reveal`。
- `AgentConfirm.requireConfirm(title, summary, 60s)`：**fail-closed**——无前台 Activity / 超时 /
  点「拒绝」一律 false。**审批只能由真人点「允许」产生**，模型/Termux/页面都不能传 `confirmed=true`
  绕过（防网页 prompt-injection 声称「用户已同意」）。这是 bhcodex 架构铁律。
- 前台 Activity 由 `BrowserApplication` 生命周期 `AgentConfirm.setTopActivity()` 跟踪。

### 12.3 凭证占位符（原始密钥不进模型）

`intercept_resolve` / `resp_intercept_resolve` 的 header/body 值支持占位符，APK 在转发前替换成真值：
- `{{cookie:<flowId>}}` → 该 flow 的真实 Cookie
- `{{auth:<flowId>}}` → 真实 Authorization
- `{{header:<flowId>:<headerName>}}` → 指定头真值

真值从 `NetFlowStore`（未脱敏内部库）取，**替换发生在 APK 内，模型永远看不到明文**。
对应 `network_get` 输出里凭证头被 `***redacted(N)***` 脱敏，要看明文须 `cookie_reveal`（走 L3 确认）。

### 12.4 工具清单（17 个，源：`BrowserBridge.toolsList()`）

**网络抓包（L1 读）**
- `network_list` — 列最近 MITM 抓到的 HTTP(S) flow（新→旧）。参数 `method` / `urlContains` /
  `sinceSeconds` / `limit`(默认 50)。返回摘要（id/ts/method/url/host/status/contentType/body 长度）。
- `network_get {id*}` — 取单条 flow 完整请求+响应头/体。凭证头脱敏为 `***redacted(N)***`。

**页面内容（L1 读，需 DevTools 在目标页打开）**
- `page_index` — 索引当前页：抓 outerHTML 进 JS 侧缓冲（512KB 上限），只返回元信息
  （title/url/headings/forms/links）。**page_search 前必须先调它**。底层 `PageChannel.exec("getSource")`。
- `page_search {query*, limit=10}` — 在已索引源里搜文本，返回 ±150 字 snippet。底层 `searchSource`。
- `page_query {selector*, limit=20}` — 对 live DOM 跑 CSS 选择器，返回元素(tag/text/attrs/outerHTML 片段)。底层 `queryDOM`。

**代理控制（L2）**
- `proxy_status` / `proxy_start` / `proxy_stop` — 查/开/关 MITM 代理（开=抓全部浏览器 HTTPS）。

**网络塑形（L2）**
- `throttle_set {enabled, latencyMs, kbps}` — 弱网模拟（每响应延迟 + 下行限速）。`enabled=false` 关闭。
- `replace_set {enabled, scope, rules:[{from,to}]}` — 请求/响应体字符替换。scope=`req|resp|both`(默认 both)。
- `mock_set {rules:[{pattern,status,body,headers}]}*` — URL 子串(不分大小写)命中即返回合成响应、不连服务器。空数组清空。

**拦截改包（L2）**
- `intercept_set {reqAll, respAll, rules:[{host,path,method,hasBody,interceptResp}]}` —
  配置请求/响应拦截规则，命中即**暂停**。`reqAll/respAll=true` 拦全部。
- `intercept_pending` — 列当前暂停等决策的 flow（`[{flowId,type:req|resp,url,method,ts}]`），`intercept_set` 后轮询它。
- `intercept_resolve {flowId*, decision*, url, method, reqHeaders, reqBody}` — 决议被暂停的**请求**：
  `continue`(可带编辑转发) / `abort`(返 403)。header 值支持 12.3 占位符。
- `resp_intercept_resolve {flowId*, decision*, status, respHeaders, respBody}` — 决议被暂停的**响应**，同款占位符。

**敏感（L3，每次原生确认）**
- `cookie_reveal {id*, name=authorization}` — 读某 flow 某凭证头的**明文**（真实 bearer/Cookie）。
  每次弹原生确认，点「允许」才返回。
- `page_exec {code*, timeoutMs=10000}` — 在 page world 执行**任意 JS**（全浏览器 API：
  fetch/document.cookie/window/history/localStorage…，Gecko wrappedJSObject 无 CSP）。返回值 JSON 序列化。
  底层 `PageChannel.exec("evalJS")`，每次原生确认。

### 12.5 相关文件
- `devtools/BrowserBridge.kt` — MCP server + 工具声明/路由/执行（唯一真实执行方）。
- `devtools/AgentConfirm.kt` — L3 原生审批闸（fail-closed）。
- `devtools/PageChannel.kt` — native worker → 当前页 content script 异步桥（page_* 走它，带 timeout/fail-safe）。
- `assets/extensions/devtools_injector/agent.js` — 页面侧 `bhHandleAgentCmd`：getSource/searchSource/queryDOM/evalJS。
- `devtools/NetFlowStore` — 未脱敏 flow 内部库（network_*/cookie_reveal/占位符取真值）。
- 详见 CLAUDE.md「BrowserHelper 现有工作层映射」「可借用/不要搬运」「硬边界」。
