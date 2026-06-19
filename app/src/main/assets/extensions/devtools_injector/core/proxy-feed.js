// 原生 MITM 代理 → DevTools 面板的旁路数据源（增量 1：仅头部）。
// 复用 utils.js 的全局 `port`（不另开连接），把原生发来的 flowReq/flowResp
// 转成面板已有的 __bhNet req/resp 消息，进入网络列表。
// 只做展示，不参与任何请求转发/拦截。

var proxyFlowIdMap = {}; // 原生 flowId -> { reqId, t0 }
var proxyReqIdSeq = 0;

function proxyGetPort() {
  return typeof port !== 'undefined' ? port : null;
}

function proxyFeedInit() {
  // 等待 connect() 建好全局 port，再挂监听（复用同一条 native 连接）。
  var tries = 0;
  (function wait() {
    var p = proxyGetPort();
    if (p) {
      p.onMessage.addListener(proxyOnMsg);
      return;
    }
    if (tries++ < 50) setTimeout(wait, 100);
  }());
}

function proxyOnMsg(msg) {
  if (!msg || msg.ch !== 'proxy') return;
  if (msg.type === 'flowReq') {
    var reqId = 'proxy_' + (++proxyReqIdSeq);
    // 记录请求到达时间，flowResp 时算 duration（≈ 服务器处理 + 网络往返，到响应头为止）。
    proxyFlowIdMap[msg.flowId] = { reqId: reqId, t0: Date.now() };
    try {
      window.postMessage({
        __bhNet: true,
        type: 'req',
        reqId: reqId,
        url: msg.url || '',
        method: msg.method || 'GET',
        reqHeaders: msg.reqHeaders || {},
        reqBody: null,
      }, '*');
    } catch (e) {}
  } else if (msg.type === 'flowResp') {
    var rec = proxyFlowIdMap[msg.flowId];
    if (!rec) return;
    delete proxyFlowIdMap[msg.flowId];
    try {
      window.postMessage({
        __bhNet: true,
        type: 'resp',
        reqId: rec.reqId,
        status: msg.status || 0,
        respHeaders: msg.respHeaders || {},
        respBody: null,
        duration: Date.now() - rec.t0,
      }, '*');
    } catch (e) {}
  }
}
