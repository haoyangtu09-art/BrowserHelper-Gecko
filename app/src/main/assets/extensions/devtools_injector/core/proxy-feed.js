// 原生 MITM 代理 → DevTools 面板的旁路数据源（增量 1：仅头部）。
// 复用 utils.js 的全局 `port`（不另开连接），把原生发来的 flowReq/flowResp
// 转成面板已有的 __bhNet req/resp 消息，进入网络列表。
// 只做展示，不参与任何请求转发/拦截。

var proxyFlowIdMap = {}; // 原生 flowId -> 面板 reqId
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
    proxyFlowIdMap[msg.flowId] = reqId;
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
    var rid = proxyFlowIdMap[msg.flowId];
    if (!rid) return;
    delete proxyFlowIdMap[msg.flowId];
    try {
      window.postMessage({
        __bhNet: true,
        type: 'resp',
        reqId: rid,
        status: msg.status || 0,
        respHeaders: msg.respHeaders || {},
        respBody: null,
        duration: 0,
      }, '*');
    } catch (e) {}
  }
}
