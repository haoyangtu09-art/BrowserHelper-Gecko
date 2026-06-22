// 原生 MITM 代理 → DevTools 面板的旁路数据源（增量 1：仅头部）。
// 复用 utils.js 的全局 `port`（不另开连接），把原生发来的 flowReq/flowResp
// 转成面板已有的 __bhNet req/resp 消息，进入网络列表。
// 只做展示，不参与任何请求转发/拦截。

var proxyFlowIdMap = {}; // 原生 flowId -> { reqId, t0 }
var proxyReqIdToFlow = {}; // 面板 reqId -> 原生 flowId（拦截回复时反查）
var proxyFlowOrder = []; // flowId 到达顺序，用于限长清理
var proxyReqIdSeq = 0;

// 写入 flowId 映射并限长。respBody 在 flowResp 之后才到，所以 flowResp 不能删 map，
// 改用「到达顺序 + 上限」清理，避免无 body 响应的映射无限堆积。
function proxyMapSet(flowId, val) {
  proxyFlowIdMap[flowId] = val;
  if (val && val.reqId) proxyReqIdToFlow[val.reqId] = flowId;
  proxyFlowOrder.push(flowId);
  if (proxyFlowOrder.length > 400) {
    var old = proxyFlowOrder.shift();
    var gone = proxyFlowIdMap[old];
    if (gone && gone.reqId) delete proxyReqIdToFlow[gone.reqId];
    delete proxyFlowIdMap[old];
  }
}

// 拦截回复用：面板 reqId → 原生 flowId（bpResolve/respResolve 调用）。
function proxyFlowIdForReqId(reqId) {
  return proxyReqIdToFlow[reqId] || null;
}

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
      requestNativeInterceptState();
      return;
    }
    if (tries++ < 50) setTimeout(wait, 100);
  }());
}

function proxyRuleKey(rule) {
  return [
    rule && rule.host || '',
    rule && rule.path || '',
    String(rule && rule.method || 'GET').toUpperCase(),
    rule && rule.hasBody ? '1' : '0',
  ].join('\n');
}

function proxyHydrateNativeRules(rules) {
  var prior = {};
  try {
    if (Array.isArray(netInterceptRules)) {
      netInterceptRules.forEach(function (r) { prior[proxyRuleKey(r)] = r; });
    }
  } catch (e) {}
  if (!Array.isArray(rules)) return [];
  return rules.map(function (r) {
    var old = prior[proxyRuleKey(r)] || {};
    return {
      id: old.id || (Date.now().toString(36) + Math.random().toString(36).slice(2)),
      action: r && r.action === 'intercept' ? 'intercept' : 'pass',
      host: String(r && r.host || ''),
      path: String(r && r.path || ''),
      method: String(r && r.method || 'GET').toUpperCase(),
      hasBody: !!(r && r.hasBody),
      interceptResp: !!(r && r.interceptResp),
      sampleUrl: old.sampleUrl || '',
      createdAt: old.createdAt || Date.now(),
      updatedAt: Date.now(),
    };
  }).filter(function (r) {
    return r.host || r.path;
  });
}

function applyNativeInterceptState(msg) {
  try {
    if (typeof netInterceptMaster === 'undefined') return;
    netNativeInterceptStateReceived = true;
    netApplyingNativeInterceptState = true;
    netInterceptMaster = !!msg.enabled;
    netScopeReq = Object.prototype.hasOwnProperty.call(msg, 'reqAll') ? !!msg.reqAll : netScopeReq;
    netScopeResp = Object.prototype.hasOwnProperty.call(msg, 'respAll') ? !!msg.respAll : netScopeResp;
    netScopeTelemetry = !!msg.interceptTelemetry;
    netScopeHeartbeat = !!msg.interceptHeartbeat;
    netScopeNoise = !!msg.interceptNoise;
    netScopeCookie = !!msg.interceptCookie;
    if (Array.isArray(msg.rules)) {
      netInterceptRules = proxyHydrateNativeRules(msg.rules);
      netInterceptRulesLoaded = true;
    }
    if (typeof recomputeIntercept === 'function') recomputeIntercept(true);
    else {
      netGlobalInterceptEnabled = netInterceptMaster && netScopeReq;
      netGlobalRespInterceptEnabled = netInterceptMaster && netScopeResp;
      netGlobalInterceptNoise = netScopeNoise;
    }
    if (typeof syncGlobalInterceptEnabled === 'function') syncGlobalInterceptEnabled();
    if (typeof syncGlobalRespInterceptEnabled === 'function') syncGlobalRespInterceptEnabled();
    if (typeof syncGlobalInterceptNoise === 'function') syncGlobalInterceptNoise();
    if (typeof syncFilterSuppressResp === 'function') syncFilterSuppressResp();
    if (typeof syncInterceptRules === 'function') syncInterceptRules();
    if (typeof saveNetConfig === 'function') saveNetConfig();
    try {
      var st = storageLocal();
      if (st && st.set) st.set({ bhNetInterceptRules: netInterceptRules }).catch(function () {});
    } catch (e) {}
    if (typeof updateInterceptBtn === 'function') updateInterceptBtn();
    if (typeof updateRulesBtn === 'function') updateRulesBtn();
    if (typeof renderRulesView === 'function') renderRulesView();
    if (typeof renderNetList === 'function') renderNetList();
  } catch (e) {
  } finally {
    netApplyingNativeInterceptState = false;
  }
}

function proxyOnMsg(msg) {
  if (!msg || msg.ch !== 'proxy') return;
  if (msg.type === 'proxyState') {
    // 原生回报代理真实开关态（冷启动会被复位为关），同步「监听」按钮文案，避免误显示。
    netEnabled = !!msg.running;
    if (typeof netEnableBtn !== 'undefined' && netEnableBtn) {
      netEnableBtn.textContent = netEnabled ? '\u25cf \u76d1\u542c\u4e2d' : '\u25cb \u5df2\u505c\u6b62';
    }
    return;
  }
  if (msg.type === 'interceptState') {
    applyNativeInterceptState(msg);
    return;
  }
  if (msg.type === 'flowReq') {
    var reqId = 'proxy_' + (++proxyReqIdSeq);
    // 记录请求到达时间，flowResp 时算 duration（≈ 服务器处理 + 网络往返，到响应头为止）。
    proxyMapSet(msg.flowId, { reqId: reqId, t0: Date.now() });
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
  } else if (msg.type === 'flowReqBody') {
    // 请求体到达（在 flowResp 之前），把 tee 的正文补给面板里已建的请求条目。
    var bodyRec = proxyFlowIdMap[msg.flowId];
    if (!bodyRec) return;
    try {
      window.postMessage({
        __bhNet: true,
        type: 'reqBody',
        reqId: bodyRec.reqId,
        reqBody: msg.reqBody || null,
      }, '*');
    } catch (e) {}
  } else if (msg.type === 'flowResp') {
    // 不删 map：respBody 在 flowResp 之后才到，需要保留映射（由 proxyMapSet 限长清理）。
    var rec = proxyFlowIdMap[msg.flowId];
    if (!rec) return;
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
  } else if (msg.type === 'reqIntercept') {
    // 原生代理已暂停这条请求，等面板放行/中止。建立 reqId↔flowId 映射，先建列表条目，
    // 再复用已有「请求拦截」队列 UI（type:'breakpoint', mode:'intercept'）。
    var irRec = proxyFlowIdMap[msg.flowId];
    var irReqId;
    if (irRec && irRec.reqId) {
      irReqId = irRec.reqId;
    } else {
      irReqId = 'proxy_' + (++proxyReqIdSeq);
      proxyMapSet(msg.flowId, { reqId: irReqId, t0: Date.now() });
    }
    try {
      window.postMessage({
        __bhNet: true, type: 'req', reqId: irReqId,
        url: msg.url || '', method: msg.method || 'GET',
        reqHeaders: msg.reqHeaders || {}, reqBody: msg.reqBody || null,
      }, '*');
      window.postMessage({
        __bhNet: true, type: 'breakpoint', mode: 'intercept', reqId: irReqId,
        url: msg.url || '', method: msg.method || 'GET',
        reqHeaders: msg.reqHeaders || {}, reqBody: msg.reqBody || '',
      }, '*');
    } catch (e) {}
  } else if (msg.type === 'respIntercept') {
    // 原生代理已暂停这条响应，等面板放行/中止。请求条目通常已由 flowReq 建好（响应方向
    // 拦截不暂停请求），复用其 reqId；丢失则新建。再复用响应拦截队列 UI（respBreakpoint,
    // via:'intercept'）。
    var rriRec = proxyFlowIdMap[msg.flowId];
    var rriReqId;
    if (rriRec && rriRec.reqId) {
      rriReqId = rriRec.reqId;
    } else {
      rriReqId = 'proxy_' + (++proxyReqIdSeq);
      proxyMapSet(msg.flowId, { reqId: rriReqId, t0: Date.now() });
    }
    try {
      window.postMessage({
        __bhNet: true, type: 'respBreakpoint', via: 'intercept', reqId: rriReqId,
        status: msg.status || 200,
        respHeaders: msg.respHeaders || {},
        respBody: msg.respBody == null ? '' : msg.respBody,
      }, '*');
    } catch (e) {}
  } else if (msg.type === 'flowRespBody') {
    // 响应体（在 flowResp 之后到），补给面板对应条目。
    var respRec = proxyFlowIdMap[msg.flowId];
    if (!respRec) return;
    try {
      window.postMessage({
        __bhNet: true,
        type: 'respBody',
        reqId: respRec.reqId,
        respBody: msg.respBody || null,
        truncated: !!msg.truncated,
        encoding: msg.encoding || '',
      }, '*');
    } catch (e) {}
  }
}
