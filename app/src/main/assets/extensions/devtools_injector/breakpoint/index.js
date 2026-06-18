// Breakpoint/intercept message bridge and queues.
// content script 接收 page world 发来的消息
var netReqMap = {}; // reqId -> 请求条目（等待响应）
	  var _bpIsoPending = {}; // isolated 模式断点等待表 reqId -> resolve
	  var _respBpIsoPending = {}; // isolated 模式响应断点等待表 reqId -> resolve
	  var netBreakpointQueue = [];
	  var netActiveBreakpoint = null;
	  var netInterceptQueue = [];
	  var netSelIntercept = null;
	  var netRespInterceptQueue = [];
	  var netSelRespIntercept = null;
	  var netInterceptRules = [];
	  var netRulesViewEl = null;
var netExtViewEl = null;
	  var netPlainCandidates = [];
	  var netPlainSeq = 0;
window.addEventListener('message', function (e) {
  if (!e.data || !e.data.__bhNet) return;
  var d = e.data;
  if (d.type === 'req') {
    var entry = {
      reqId: d.reqId, url: d.url, method: d.method,
      reqHeaders: d.reqHeaders || {}, reqBody: d.reqBody || null,
      status: null, respHeaders: {}, respBody: null,
      duration: null, error: null, ts: Date.now(), tag: null,
      plain: [],
    };
    entry.plain = collectPlainCandidates(entry);
    netReqMap[d.reqId] = entry;
  } else if (d.type === 'resp') {
    var entry = netReqMap[d.reqId];
    if (entry) {
      entry.status = d.status;
      entry.respHeaders = d.respHeaders || {};
      entry.respBody = d.respBody || null;
      entry.duration = d.duration;
      entry.error = d.error || null;
      if (!entry.plain || !entry.plain.length) entry.plain = collectPlainCandidates(entry);
      delete netReqMap[d.reqId];
      // 放到列表头部，最多保留 200 条
      netRequests.unshift(entry);
      if (netRequests.length > 200) netRequests.length = 200;
      maybeFocusPayload(entry);
      if (netPanelVisible) renderNetList();
      if (netPanelVisible) renderDetail();
    }
  } else if (d.type === 'panelShow') {
    // page world 的 eruda tool.show() 通过 postMessage 通知 content world 渲染
    netPanelVisible = true;
    renderNetList();
    renderDetail();
  } else if (d.type === 'panelHide') {
    netPanelVisible = false;
  } else if (d.type === 'breakpoint') {
    // 命中断点/全局拦截：page world 已暂停请求，排队弹编辑框，防止多个请求互相覆盖
    enqueueBreakpoint(d);
  } else if (d.type === 'respBreakpoint') {
    // 命中响应断点：响应已到（流式已攒成整段），排队弹响应编辑框
    enqueueRespBreakpoint(d);
  } else if (d.type === 'bpResolve') {
    // isolated 模式：断点等待在 content world，直接 resolve（page 模式拦截器自己监听）
    var fn = _bpIsoPending[d.reqId];
    if (fn) { delete _bpIsoPending[d.reqId]; (fn.resolve || fn)(d); }
  } else if (d.type === 'respResolve') {
    var rf = _respBpIsoPending[d.reqId];
    if (rf) { delete _respBpIsoPending[d.reqId]; (rf.resolve || rf)(d); }
  } else if (d.type === 'plain') {
    recordPlainCandidate(d);
  }
});

// 命中断点时的编辑框：放行(可改)/中止
