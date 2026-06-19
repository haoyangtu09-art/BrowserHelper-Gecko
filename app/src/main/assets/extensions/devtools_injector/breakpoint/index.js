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
	  var netPlainCandidates = [];
	  var netPlainSeq = 0;

// 把 netRequests 截到最多 200 条，并清理被截掉条目在 netReqMap 中残留的关联，
// 避免 keep-alive 上「只入列、永远等不到响应」的条目把 netReqMap 撑爆。
function trimNetRequests() {
  if (netRequests.length <= 200) return;
  var removed = netRequests.splice(200);
  for (var i = 0; i < removed.length; i++) {
    if (removed[i] && removed[i].reqId) delete netReqMap[removed[i].reqId];
  }
}

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
    // 收到请求即入列（pending，状态显示 …）。代理模式下 keep-alive 连接上的后续
    // 请求拿不到独立响应（响应方向只读首个响应头），若等 resp 才入列会永远不显示。
    netRequests.unshift(entry);
    trimNetRequests();
    if (netPanelVisible) renderNetList();
  } else if (d.type === 'resp') {
    var entry = netReqMap[d.reqId];
    if (entry) {
      entry.status = d.status;
      entry.respHeaders = d.respHeaders || {};
      entry.respBody = d.respBody || null;
      entry.duration = d.duration;
      entry.error = d.error || null;
      if (!entry.plain || !entry.plain.length) entry.plain = collectPlainCandidates(entry);
      // 条目已在 req 时入列，这里只就地更新，不再 unshift（避免重复）。
      delete netReqMap[d.reqId];
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
  } else if (d.type === 'reqBody') {
    // 代理旁路补传的请求体：填回 netReqMap 里已存在（req 时已入列）的请求条目。
    var bodyEntry = netReqMap[d.reqId];
    if (bodyEntry) {
      bodyEntry.reqBody = d.reqBody || null;
      if (!bodyEntry.plain || !bodyEntry.plain.length) bodyEntry.plain = collectPlainCandidates(bodyEntry);
      maybeFocusPayload(bodyEntry);
      if (netPanelVisible) renderNetList();
      if (netPanelVisible) renderDetail();
    }
  } else if (d.type === 'respBody') {
    // 代理旁路补传的响应体（在 resp 之后到）。resp 已把条目移出 netReqMap，
    // 用 findReqEntry 回退到列表里查。
    var respEntry = netReqMap[d.reqId] || findReqEntry(d.reqId);
    if (respEntry) {
      respEntry.respBody = d.respBody || null;
      if (d.truncated) respEntry.respTruncated = true;
      if (d.encoding) respEntry.respEncoding = d.encoding;
      if (!respEntry.plain || !respEntry.plain.length) respEntry.plain = collectPlainCandidates(respEntry);
      if (netPanelVisible) renderDetail();
    }
  } else if (d.type === 'plain') {
    recordPlainCandidate(d);
  }
});

// 命中断点时的编辑框：放行(可改)/中止
