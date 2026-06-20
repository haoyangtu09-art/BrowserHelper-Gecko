// 「截流」插件 v0.1 —— 拦截 ChatGPT 流式回复（SSE），编辑后放行。
//
// 本插件只做一件事：启用时给原生代理「装上」SSE-hold（仅对 STREAM_HOLD_HOSTS 的
// text/event-stream 生效），禁用时撤掉。其余完全复用现有「响应拦截」管道：
//   原生 hold 住该 SSE → 心跳维持页面「思考中」假象 → 去分块缓冲整条流 →
//   走和普通响应拦截一样的 respIntercept 事件，落进面板「响应拦截」队列 →
//   在详情框里编辑（放行/搜索/复制） → 放行经 resolveRespIntercept 回原生回放。
//
// 所以这里不需要自建 UI / 处理器，只需下发/撤销配置。v0.1 不解析 GPT 的 SSE
// 事件格式：详情框展示什么、编辑什么，原生就原样回放什么。

var STREAM_HOLD_HOSTS = ['chatgpt.com', 'chat.openai.com'];
var STREAM_HOLD_HEARTBEAT = ': ping\n\n';

function bhStreamHoldPort() {
  return (typeof port !== 'undefined') ? port : null;
}

// 下发/撤销原生 SSE-hold 配置。enabled=false 时原生即不再 hold。
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

registerPlugin({
  id: 'stream-hold',
  name: '截流',
  version: '0.1',
  desc: '拦截 ChatGPT 流式回复，心跳维持「思考中」，进响应拦截队列编辑后放行',
  onEnable: function (ctx) {
    bhStreamHoldSendConfig(true);
    ctx.log('stream-hold onEnable: armed for ' + STREAM_HOLD_HOSTS.join(','));
  },
  onDisable: function (ctx) {
    bhStreamHoldSendConfig(false);
    ctx.log('stream-hold onDisable: disarmed');
  },
});
