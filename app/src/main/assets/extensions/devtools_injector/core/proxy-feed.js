// Native MITM proxy ↔ DevTools panel data bridge.
// 接收来自原生 ProxyProbe 的代理事件，转换为面板记录格式；
// 发送面板的编辑/控制命令到代理。
// 注意：复用 utils.js 的全局 port 变量，避免重复连接

var proxyFlowIdMap = {}; // flowId -> reqId 映射（原生流 ID 到面板请求 ID）
var proxyReqIdSeq = 0;

// 获取或建立 native port 连接
// 复用全局 port（来自 connect()）而不是新建连接
function getProxyPort() {
  return typeof port !== 'undefined' ? port : null;
}

function proxyConnectPort() {
  // 延迟确保 connect() 已建立 port 连接
  var maxRetries = 50;
  var retries = 0;

  var retry = function() {
    var currentPort = getProxyPort();
    if (currentPort) {
      currentPort.onMessage.addListener(proxyOnPortMessage);
      proxyInitialSync();
    } else if (retries < maxRetries) {
      retries++;
      setTimeout(retry, 100);
    }
  };

  retry();
}

function proxyOnPortMessage(msg) {
  // 代理事件处理（通过全局 port 接收）
  if (!msg || !msg.ch) return;
  if (msg.ch !== 'proxy') return;

  var type = msg.type;
  if (type === 'flowReq') {
    proxyOnFlowReq(msg);
  } else if (type === 'flowResp') {
    proxyOnFlowResp(msg);
  } else if (type === 'reqPaused') {
    proxyOnReqPaused(msg);
  } else if (type === 'respPaused') {
    proxyOnRespPaused(msg);
  }
}

function proxyOnPortDisconnect() {
  // port 已由 utils.js 的 connect() 处理重连
  proxyFlowIdMap = {};
}

function proxyOnFlowReq(msg) {
  // 新请求到达：{type:'flowReq', flowId, url, method, host, ts, reqHeaders:{}, reqBody}
  var flowId = msg.flowId;
  var reqId = 'proxy_' + (++proxyReqIdSeq);
  proxyFlowIdMap[flowId] = reqId;

  var entry = {
    reqId: reqId,
    url: msg.url || '',
    method: msg.method || 'GET',
    reqHeaders: msg.reqHeaders || {},
    reqBody: msg.reqBody || null,
    status: null,
    respHeaders: {},
    respBody: null,
    duration: null,
    error: null,
    ts: msg.ts || Date.now(),
    tag: null,
    plain: [],
  };

  entry.plain = collectPlainCandidates(entry);
  netReqMap[reqId] = entry;

  // 模拟页内拦截器的 postMessage 流程，但来源是原生代理
  window.postMessage({
    __bhNet: true,
    type: 'req',
    reqId: reqId,
    url: entry.url,
    method: entry.method,
    reqHeaders: entry.reqHeaders,
    reqBody: entry.reqBody,
  }, '*');
}

function proxyOnFlowResp(msg) {
  // 响应完成：{type:'flowResp', flowId, status, duration, respHeaders:{}, respBody}
  var flowId = msg.flowId;
  var reqId = proxyFlowIdMap[flowId];
  if (!reqId) return;

  var entry = netReqMap[reqId];
  if (entry) {
    entry.status = msg.status || 0;
    entry.respHeaders = msg.respHeaders || {};
    entry.respBody = msg.respBody || null;
    entry.duration = msg.duration || 0;
    entry.error = msg.error || null;
    if (!entry.plain || !entry.plain.length) entry.plain = collectPlainCandidates(entry);
    delete netReqMap[reqId];

    // 放到列表头部，最多保留 200 条
    netRequests.unshift(entry);
    if (netRequests.length > 200) netRequests.length = 200;
    maybeFocusPayload(entry);
    if (netPanelVisible) renderNetList();
    if (netPanelVisible) renderDetail();
  }

  delete proxyFlowIdMap[flowId];
}

function proxyOnReqPaused(msg) {
  // 请求被暂停：{type:'reqPaused', flowId, url, method, ...}
  // 转换为面板的断点流程，类似旧的 intercept 流程
  var flowId = msg.flowId;
  var reqId = proxyFlowIdMap[flowId];
  if (!reqId) return;

  var entry = netReqMap[reqId];
  if (!entry) return;

  // 存储 flowId 以便 resolve 时用
  entry._proxyFlowId = flowId;

  // 弹出编辑框（调用已有的 enqueueIntercept）
  enqueueIntercept({
    mode: 'intercept',  // 重要：标记为拦截模式（会进队列），不是 breakpoint
    reqId: reqId,
    url: entry.url,
    method: entry.method,
    reqHeaders: entry.reqHeaders,
    reqBody: entry.reqBody,
  });
}

function proxyOnRespPaused(msg) {
  // 响应被暂停：{type:'respPaused', flowId, status, respHeaders:{}, respBody, ...}
  var flowId = msg.flowId;
  var reqId = proxyFlowIdMap[flowId];
  if (!reqId) return;

  var entry = netReqMap[reqId];
  if (!entry) return;

  entry.status = msg.status || 0;
  entry.respHeaders = msg.respHeaders || {};
  entry.respBody = msg.respBody || null;
  entry.duration = msg.duration || 0;
  entry._proxyFlowId = flowId;

  // 弹出响应编辑框
  enqueueRespIntercept({
    type: 'respBreakpoint',
    reqId: reqId,
    url: entry.url,
    method: entry.method,
    status: entry.status,
    respHeaders: entry.respHeaders,
    respBody: entry.respBody,
  });
}

function proxyInitialSync() {
  var currentPort = getProxyPort();
  if (!currentPort) {
    // Port 未连接，稍后重试
    setTimeout(proxyInitialSync, 200);
    return;
  }

  // 在 port 连接时立即发送初始配置 + 规则
  var config = {
    ch: 'proxy',
    type: 'config',
    scopeHosts: [],  // 默认空 = 全解密；可扩展为过滤列表
    displayFilter: '',
    throttle: (typeof netThrottle !== 'undefined') ? netThrottle : { enabled: false, latencyMs: 0, kbps: 0 },
    interceptOn: (typeof netInterceptMaster !== 'undefined') && netScopeReq,
    respInterceptOn: (typeof netInterceptMaster !== 'undefined') && netScopeResp,
    interceptNoise: (typeof netInterceptMaster !== 'undefined') && netScopeNoise,
  };

  try {
    currentPort.postMessage(config);
  } catch (e) {
    // port 已断开，稍后重试
    setTimeout(proxyInitialSync, 200);
    return;
  }

  // 发送现有规则
  proxyOnConfigChanged();
}

// 当配置/规则变更时调用（来自面板 UI）
function proxyOnConfigChanged() {
  var currentPort = getProxyPort();
  if (!currentPort) return;

  var msg = {
    ch: 'proxy',
    type: 'rules',
    interceptRules: netInterceptRules || [],
    replaceRules: netReplaceRules || [],
  };

  try {
    currentPort.postMessage(msg);
  } catch (e) {
    // port 已断开
  }
}

function proxySendConfig() {
  var currentPort = getProxyPort();
  if (!currentPort) return;

  var config = {
    ch: 'proxy',
    type: 'config',
    scopeHosts: [],
    displayFilter: '',
    throttle: netThrottle || { enabled: false, latencyMs: 0, kbps: 0 },
    interceptOn: netInterceptMaster && netScopeReq,
    respInterceptOn: netInterceptMaster && netScopeResp,
    interceptNoise: netInterceptMaster && netScopeNoise,
  };

  try {
    currentPort.postMessage(config);
  } catch (e) {}
}

function proxyResolveReq(reqId, action, url, method, reqHeaders, reqBody) {
  var entry = netReqMap[reqId];
  if (!entry || !entry._proxyFlowId) return;

  var currentPort = getProxyPort();
  if (!currentPort) return;

  var msg = {
    ch: 'proxy',
    type: 'resolveReq',
    flowId: entry._proxyFlowId,
    action: action,  // 'continue' | 'abort'
    url: url || entry.url,
    method: method || entry.method,
    reqHeaders: reqHeaders || entry.reqHeaders,
    reqBody: reqBody || entry.reqBody,
  };

  try {
    currentPort.postMessage(msg);
  } catch (e) {}

  delete entry._proxyFlowId;
}

function proxyResolveResp(reqId, action, status, respHeaders, respBody) {
  var entry = netReqMap[reqId];
  if (!entry || !entry._proxyFlowId) return;

  var currentPort = getProxyPort();
  if (!currentPort) return;

  var msg = {
    ch: 'proxy',
    type: 'resolveResp',
    flowId: entry._proxyFlowId,
    action: action,  // 'continue' | 'abort'
    status: status !== undefined ? status : entry.status,
    respHeaders: respHeaders || entry.respHeaders,
    respBody: respBody || entry.respBody,
  };

  try {
    currentPort.postMessage(msg);
  } catch (e) {}

  delete entry._proxyFlowId;
}

function proxyReleaseAll() {
  var currentPort = getProxyPort();
  if (!currentPort) return;

  var msg = {
    ch: 'proxy',
    type: 'releaseAll',
  };

  try {
    currentPort.postMessage(msg);
  } catch (e) {}
}

// 初始化：延迟连接 native port，确保运行环境已就绪
function proxyFeedInit() {
  // 延迟 100ms 让其他初始化完成
  setTimeout(function () {
    proxyConnectPort();
  }, 100);
}
