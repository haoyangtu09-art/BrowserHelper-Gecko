// Response breakpoint queue, editor, and respResolve.
function respResolve(reqId, payload) {
  if (payload && payload.action === 'continue') {
    var entry = netReqMap[reqId] || findReqEntry(reqId);
    if (entry) {
      if (payload.status != null) entry.status = payload.status;
      entry.respHeaders = payload.respHeaders || entry.respHeaders;
      if (payload.respBody != null) entry.respBody = payload.respBody;
    }
  }
  payload.__bhNet = true;
  payload.type = 'respResolve';
  payload.reqId = reqId;
  try { window.postMessage(payload, '*'); } catch (e) {}
}

function enqueueRespBreakpoint(d) {
  if (d.via === 'intercept') { enqueueRespIntercept(d); return; }
  d.__resp = true;
  netBreakpointQueue.push(d);
  if (!netActiveBreakpoint) openNextBreakpoint();
}

function enqueueRespIntercept(d) {
  d.ts = Date.now();
  d.respHeaders = d.respHeaders || {};
  d.respBody = d.respBody == null ? '' : d.respBody;
  if (d.status == null) d.status = 200;
  // respBreakpoint 消息不带 url/method/请求信息，从同 reqId 的请求记录补全（供详情页展示）
  var src = netRequests.find(function (x) { return x.reqId === d.reqId; });
  if (src) {
    if (d.url == null) d.url = src.url;
    if (d.method == null) d.method = src.method;
    if (d.reqHeaders == null) d.reqHeaders = src.reqHeaders;
    if (d.reqBody == null) d.reqBody = src.reqBody;
  }
  netRespInterceptQueue.push(d);
  updateInterceptBtn();
  if (netPanelVisible) { renderNetList(); renderDetail(); }
}

function openRespBreakpointEditor(d) {
  var headersStr = JSON.stringify(d.respHeaders || {}, null, 2);
  openModal('响应断点 — 编辑后放行',
    '<label>状态码</label><input id="bh-rbe-status" value="' + escHtml(String(d.status == null ? 200 : d.status)) + '">' +
    '<label>响应头 (JSON)</label><textarea id="bh-rbe-headers">' + escHtml(headersStr) + '</textarea>' +
    '<label>响应体</label><textarea id="bh-rbe-body">' + escHtml(d.respBody || '') + '</textarea>',
    function (el) {
      var headers = {};
      try { headers = JSON.parse(el.querySelector('#bh-rbe-headers').value); } catch (e) {}
      finishBreakpoint(d, {
        action: 'continue',
        status: parseInt(el.querySelector('#bh-rbe-status').value, 10) || (d.status == null ? 200 : d.status),
        respHeaders: headers,
        respBody: el.querySelector('#bh-rbe-body').value,
      });
    }
  );
  setTimeout(function () {
    if (!netModal) return;
    var ok = netModal.querySelector('#bh-btn-ok');
    if (ok) ok.textContent = '放行';
    var cancel = netModal.querySelector('#bh-btn-cancel');
    if (cancel) {
      cancel.textContent = '中止响应';
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
