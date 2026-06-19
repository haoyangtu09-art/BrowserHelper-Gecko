// Request breakpoint queue, editor, and finishBreakpoint.
function bpResolve(reqId, payload) {
  if (payload && payload.action === 'continue') {
    var entry = netReqMap[reqId];
    if (entry) {
      entry.url = payload.url || entry.url;
      entry.method = payload.method || entry.method;
      entry.reqHeaders = payload.reqHeaders || entry.reqHeaders;
      if (payload.reqBody != null) entry.reqBody = payload.reqBody;
    }
  }
  // 原生代理拦截：暂停在 ProxyProbe 的请求线程，回复走 control port（按 flowId 关联）。
  var flowId = (typeof proxyFlowIdForReqId === 'function') ? proxyFlowIdForReqId(reqId) : null;
  if (flowId && typeof port !== 'undefined' && port) {
    var msg = { action: 'resolveIntercept', flowId: flowId, decision: payload.action };
    if (payload.action === 'continue') {
      msg.url = payload.url;
      msg.method = payload.method;
      msg.reqHeaders = payload.reqHeaders;
      msg.reqBody = payload.reqBody;
      msg.headEdited = !!payload.headEdited;
    }
    try { port.postMessage(msg); } catch (e) {}
    return;
  }
  // 回退：page world 拦截器（旧路径）。content/page 共享 window 的 message 通道
  payload.__bhNet = true;
  payload.type = 'bpResolve';
  payload.reqId = reqId;
  try { window.postMessage(payload, '*'); } catch (e) {}
}

// 响应断点编辑结果：放行(可改 status/头/体)/中止

function findReqEntry(reqId) {
  for (var i = 0; i < netRequests.length; i++) { if (netRequests[i].reqId === reqId) return netRequests[i]; }
  return null;
}

function enqueueBreakpoint(d) {
  if (d.mode === 'intercept') {
    enqueueIntercept(d);
    return;
  }
  netBreakpointQueue.push(d);
  if (!netActiveBreakpoint) openNextBreakpoint();
}


	  function enqueueIntercept(d) {
	    d.ts = Date.now();
	    d.reqHeaders = d.reqHeaders || {};
	    d.reqBody = d.reqBody || '';
	    netInterceptQueue.push(d);
	    updateInterceptBtn();
	    if (netPanelVisible) {
	      renderNetList();
	      renderDetail();
	    }
	  }

function openNextBreakpoint() {
  if (netActiveBreakpoint || !netBreakpointQueue.length) return;
  netActiveBreakpoint = netBreakpointQueue.shift();
  if (netActiveBreakpoint.__resp) openRespBreakpointEditor(netActiveBreakpoint);
  else openBreakpointEditor(netActiveBreakpoint);
}

function finishBreakpoint(d, payload) {
  if (!d || d.__done) return;
  d.__done = true;
  if (d.__resp) respResolve(d.reqId, payload);
  else bpResolve(d.reqId, payload);
  closeModal();
  if (netActiveBreakpoint === d) netActiveBreakpoint = null;
  setTimeout(openNextBreakpoint, 20);
}

function openBreakpointEditor(d) {
  var isGlobal = d.mode === 'intercept';
  var headersStr = JSON.stringify(d.reqHeaders || {}, null, 2);
  openModal(isGlobal ? '请求拦截 — 编辑后发送' : '断点命中 — 编辑请求',
    '<label>URL</label><input id="bh-bpe-url" value="' + escHtml(d.url) + '">' +
    '<label>方法</label><input id="bh-bpe-method" value="' + escHtml(d.method) + '">' +
    '<label>请求头 (JSON)</label><textarea id="bh-bpe-headers">' + escHtml(headersStr) + '</textarea>' +
    '<label>请求体</label><textarea id="bh-bpe-body">' + escHtml(d.reqBody || '') + '</textarea>',
    function (el) {
      var headers = {};
      try { headers = JSON.parse(el.querySelector('#bh-bpe-headers').value); } catch (e) {}
      finishBreakpoint(d, {
        action: 'continue',
        url: el.querySelector('#bh-bpe-url').value,
        method: el.querySelector('#bh-bpe-method').value.toUpperCase(),
        reqHeaders: headers,
        reqBody: el.querySelector('#bh-bpe-body').value,
      });
    }
  );
  // 确认按钮文案改成"放行/发送"，并加一个"中止"按钮
  setTimeout(function () {
    if (!netModal) return;
    var ok = netModal.querySelector('#bh-btn-ok');
    if (ok) ok.textContent = isGlobal ? '发送' : '放行';
    var cancel = netModal.querySelector('#bh-btn-cancel');
    if (cancel) {
      cancel.textContent = '中止请求';
      cancel.onclick = function () {
        finishBreakpoint(d, { action: 'abort' });
      };
    }
    netModal.addEventListener('click', function (e) {
      if (e.target !== netModal) return;
      try { e.preventDefault(); e.stopImmediatePropagation(); } catch (err) {}
      finishBreakpoint(d, { action: 'abort' });
    }, true);
  }, 20);
}

