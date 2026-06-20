// 「截流」插件 v0.1 —— 拦截 ChatGPT 流式回复（SSE），编辑后放行。
//
// 工作链路（全部复用通用机制，本插件只下发配置 + 提供编辑 UI）：
//   1. onEnable → port.postMessage(setSseHoldConfig)：给原生代理装上「SSE-hold」
//      （仅对 STREAM_HOLD_HOSTS 的 text/event-stream 生效，启用才 arm）。
//   2. 原生 hold 住该响应：心跳维持页面「思考中」假象，把整条去分块的 SSE 原文缓冲，
//      经 control port 发回面板 → proxy-feed.js 收到 {type:'sseHold'} → 调 bhHandleSseHold。
//   3. 本插件弹出 light-DOM 编辑层（坑#3：不放进 shadow root），展示 SSE 原文供编辑。
//   4. 放行 → resolveSseHold：原生把（编辑后或原样）正文一次性回放给页面。
//
// v0.1 不解析 GPT 的 SSE 事件格式：展示什么、编辑什么，就原样回放什么（原生格式无关）。

var STREAM_HOLD_HOSTS = ['chatgpt.com', 'chat.openai.com'];
var STREAM_HOLD_HEARTBEAT = ': ping\n\n';
var bhStreamHoldCtx = null;     // 启用时缓存的 ctx（拿 port）
var bhStreamHoldOverlayEl = null;

// proxy-feed.js 在收到原生 sseHold 事件时调用；仅本插件启用时挂上真实处理器。
var bhHandleSseHold = null;

function bhStreamHoldPort() {
  return (bhStreamHoldCtx && bhStreamHoldCtx.port) || (typeof port !== 'undefined' ? port : null);
}

// 下发/撤销原生 SSE-hold 配置。enabled=false 时清空 host，原生即不再 hold。
function bhStreamHoldSendConfig(enabled) {
  var p = bhStreamHoldPort();
  if (!p) return;
  try {
    p.postMessage({
      action: 'setSseHoldConfig',
      enabled: !!enabled,
      hosts: STREAM_HOLD_HOSTS,
      heartbeat: STREAM_HOLD_HEARTBEAT,
    });
  } catch (e) {}
}

// 放行：把（编辑后或原样）正文回传原生回放。send=false 也走 continue（原样），
// 因为正文已被原生缓冲、连接还活着，必须给一个结果让它收尾。
function bhStreamHoldResolve(flowId, body) {
  var p = bhStreamHoldPort();
  if (!p) return;
  try {
    p.postMessage({
      action: 'resolveSseHold',
      flowId: flowId,
      decision: 'continue',
      body: body == null ? '' : body,
    });
  } catch (e) {}
}

function bhStreamHoldCloseOverlay() {
  if (bhStreamHoldOverlayEl && bhStreamHoldOverlayEl.parentNode) {
    bhStreamHoldOverlayEl.parentNode.removeChild(bhStreamHoldOverlayEl);
  }
  bhStreamHoldOverlayEl = null;
}

// 自建 light-DOM 编辑层：固定全屏，textarea 展示 SSE 原文，底部「放行(编辑后)/原样放行」。
function bhStreamHoldOpenOverlay(flowId, raw) {
  bhStreamHoldCloseOverlay();
  var el = document.createElement('div');
  el.id = 'bh-stream-hold';
  el.style.cssText = [
    'position:fixed;inset:0;z-index:2147483646;display:flex;flex-direction:column;',
    'background:#fff;color:#111;font-size:14px;',
    'font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;',
  ].join('');
  ['pointerdown', 'pointerup', 'touchstart', 'touchend', 'mousedown', 'mouseup', 'click'].forEach(function (t) {
    el.addEventListener(t, function (e) { e.stopPropagation(); }, false);
  });

  var bar = document.createElement('div');
  bar.style.cssText = 'display:flex;align-items:center;gap:8px;padding:10px 12px;border-bottom:1px solid #d0d7de;background:#f6f8fa;';
  var title = document.createElement('div');
  title.style.cssText = 'flex:1;font-weight:700;';
  title.textContent = '截流 — 编辑 GPT 流式回复';
  bar.appendChild(title);
  el.appendChild(bar);

  var ta = document.createElement('textarea');
  ta.spellcheck = false;
  ta.setAttribute('wrap', 'soft');
  ta.style.cssText = 'flex:1;width:100%;box-sizing:border-box;border:none;outline:none;resize:none;padding:12px;font-family:monospace;font-size:13px;line-height:1.5;';
  ta.value = raw == null ? '' : String(raw);
  el.appendChild(ta);

  var foot = document.createElement('div');
  foot.style.cssText = 'display:flex;gap:10px;padding:10px 12px;border-top:1px solid #d0d7de;background:#f6f8fa;';
  function mkBtn(text, bg) {
    var b = document.createElement('button');
    b.type = 'button';
    b.textContent = text;
    b.style.cssText = 'flex:1;padding:10px 0;border:none;border-radius:10px;font-size:14px;font-weight:600;color:#fff;background:' + bg + ';';
    return b;
  }
  var sendBtn = mkBtn('放行（编辑后）', '#16a34a');
  var rawBtn = mkBtn('原样放行', '#6b7280');
  sendBtn.addEventListener('click', function () {
    bhStreamHoldResolve(flowId, ta.value);
    bhStreamHoldCloseOverlay();
  });
  rawBtn.addEventListener('click', function () {
    bhStreamHoldResolve(flowId, raw);
    bhStreamHoldCloseOverlay();
  });
  foot.appendChild(rawBtn);
  foot.appendChild(sendBtn);
  el.appendChild(foot);

  document.body.appendChild(el);
  bhStreamHoldOverlayEl = el;
  try { ta.focus(); } catch (e) {}
}

registerPlugin({
  id: 'stream-hold',
  name: '截流',
  version: '0.1',
  desc: '拦截 ChatGPT 流式回复，心跳维持「思考中」，编辑后放行',
  onEnable: function (ctx) {
    bhStreamHoldCtx = ctx;
    bhHandleSseHold = function (flowId, body) { bhStreamHoldOpenOverlay(flowId, body); };
    bhStreamHoldSendConfig(true);
    ctx.log('stream-hold onEnable: armed for ' + STREAM_HOLD_HOSTS.join(','));
  },
  onDisable: function (ctx) {
    bhStreamHoldSendConfig(false);
    bhHandleSseHold = null;
    bhStreamHoldCloseOverlay();
    bhStreamHoldCtx = null;
    ctx.log('stream-hold onDisable: disarmed');
  },
});
