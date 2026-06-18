// Isolated-world fetch hook installed with exportFunction.
// Gecko content script 专有：exportFunction 把 content world 函数导出到 page world window
function injectInterceptorViaExportFunction() {
  if (typeof exportFunction === 'undefined' || typeof window.wrappedJSObject === 'undefined') {
    // 非 Gecko 或 API 不可用，降级尝试 runInPage（可能被 CSP 阻止）
    runInPage(INTERCEPT_JS);
    return;
  }
	    var pw = window.wrappedJSObject; // page world window
	    if (pw.__bhNetInstalled) return;
	    pw.__bhNetInstalled = true;
	    try {
	      pw.__bhRestoreFetch = pw.__bhRestoreFetch || pw.fetch;
	      pw.__bhRestoreXHR = pw.__bhRestoreXHR || pw.XMLHttpRequest;
	    } catch (e) {}

  // ── 工具函数（在 content world 执行，操作 page world 对象需 cloneInto）──
  function headersToObj(h) {
    if (!h) return {};
    var o = {};
    if (typeof h.forEach === 'function') { h.forEach(function (v, k) { o[k] = v; }); return o; }
    if (Array.isArray(h)) { h.forEach(function (p) { o[p[0]] = p[1]; }); return o; }
    return Object.assign({}, h);
  }
  function uid() { return Math.random().toString(36).slice(2); }
  function sendNet(data) {
    try { window.postMessage(Object.assign({ __bhNet: true }, data), '*'); } catch (e) {}
  }
  function checkMock(url) {
    var rules = pw.__bhMockRules;
    if (!rules || !rules.length) return null;
    for (var i = 0; i < rules.length; i++) {
      if (url.indexOf(rules[i].pattern) !== -1) return rules[i];
    }
    return null;
  }
  // 断点（isolated 模式）：用 content-world 的 Promise 等待编辑结果
  function bpMatch(url) {
    var bps = netBreakpoints;
    if (!bps || !bps.length) return null;
    for (var i = 0; i < bps.length; i++) { if (url.indexOf(bps[i].pattern) !== -1) return bps[i]; }
    return null;
  }
	    function headersCount(headers) {
	      try { return headers ? Object.keys(headers).length : 0; } catch (e) { return 0; }
	    }
	    function bodyHasValue(body) {
	      return body != null && String(body).length > 0;
	    }
	    function matchInterceptRule(url, method, body) {
	      var rules = netInterceptRules || [];
	      if (!rules.length) return '';
	      var u = null;
	      try { u = new URL(String(url || ''), location.href); } catch (e) { return ''; }
	      method = String(method || 'GET').toUpperCase();
	      var hasBody = bodyHasValue(body);
	      for (var i = rules.length - 1; i >= 0; i--) {
	        var r = rules[i] || {};
	        if (r.host && r.host !== u.host) continue;
	        if (r.path && r.path !== u.pathname) continue;
	        if (r.method && String(r.method).toUpperCase() !== method) continue;
	        if (!!r.hasBody !== hasBody) continue;
	        return r.action || '';
	      }
	      return '';
	    }
	    function interceptRespMatch(url, method, body) {
	      var rules = netInterceptRules || [];
	      if (!rules.length) return false;
	      var u = null;
	      try { u = new URL(String(url || ''), location.href); } catch (e) { return false; }
	      method = String(method || 'GET').toUpperCase();
	      var hasBody = bodyHasValue(body);
	      for (var i = rules.length - 1; i >= 0; i--) {
	        var r = rules[i] || {};
	        if (r.host && r.host !== u.host) continue;
	        if (r.path && r.path !== u.pathname) continue;
	        if (r.method && String(r.method).toUpperCase() !== method) continue;
	        if (!!r.hasBody !== hasBody) continue;
	        return !!r.interceptResp;
	      }
	      return false;
	    }
	    function stripCL(h) { if (!h) return h; try { Object.keys(h).forEach(function (k) { if (/^content-length$/i.test(k)) delete h[k]; }); } catch (e) {} return h; }
	    function nullBodyStatus(s) { return s === 101 || s === 204 || s === 205 || s === 304; }
  function isSuppressResp(status, body) {
    if (status === 204 || status === 205) return true;
    if (!body) return true;
    var t = String(body).trim();
    if (t.length > 64) return false;
    return /^(\{\}|\[\]|0|1|ok|true|false|\s*)$/i.test(t);
  }
	    function isNoiseBeforeSend(url, method, body, headers) {
	      url = String(url || '').toLowerCase();
	      method = String(method || 'GET').toUpperCase();
	      if (!url) return true;
	      if (method === 'OPTIONS' || method === 'HEAD') return true;
	      var len = body == null ? 0 : String(body).length;
	      if (len === 0 && headersCount(headers) === 0) return true;
	      var re = /\/(cdn-cgi|collect|telemetry|analytics|metrics|beacon|heartbeat|ping|events?|log|logs|sentry|trace|traces|socket\.io|sockjs|realtime|tunnel|connect|presence|typing|status|health|alive|poll)([/?#_.-]|$)|[?&](ping|heartbeat|keepalive|beacon)=/;
	      if (!re.test(url)) return false;
	      if (len > 512) return false;
	      if (method !== 'GET' && len > 0) return false;
	      return true;
	    }
	    function checkGlobalIntercept(url, method, body, headers) {
	      var rule = matchInterceptRule(url, method, body);
	      if (rule === 'pass') return false;
	      if (rule === 'intercept') return true;
	      return !!netGlobalInterceptEnabled && (!!netGlobalInterceptNoise || !isNoiseBeforeSend(url, method, body, headers));
	    }
  function waitBp(reqId, url, method, reqHeaders, reqBody, mode) {
    return new Promise(function (resolve) {
      _bpIsoPending[reqId] = { resolve: resolve, url: url, method: method, reqHeaders: reqHeaders, reqBody: reqBody };
      sendNet({ type: 'breakpoint', reqId: reqId, url: url, method: method, reqHeaders: reqHeaders, reqBody: reqBody, mode: mode || 'breakpoint' });
    });
  }
  // 响应断点（isolated）：content-world Promise 等待编辑结果
  function waitRespBp(reqId, status, respHeaders, respBody, via) {
    return new Promise(function (resolve) {
      _respBpIsoPending[reqId] = { resolve: resolve, status: status, respHeaders: respHeaders, respBody: respBody };
      sendNet({ type: 'respBreakpoint', reqId: reqId, status: status, respHeaders: respHeaders, respBody: respBody, via: via || 'breakpoint' });
    });
  }

  // ── 替换 page world 的 fetch ──
	    var _origFetch = pw.__bhRestoreFetch || pw.fetch;
  exportFunction(function (input, init) {
    if (netConsoleBypass) { return _origFetch.call(pw, input, init); }
    var url = typeof input === 'string' ? input : (input && input.url) || '';
    var method = ((init && init.method) || (input && input.method) || 'GET').toUpperCase();
    var reqHeaders = headersToObj(init && init.headers);
    var reqBody = (init && init.body != null) ? String(init.body) : null;
    var replacedBody = applyReplaceRules(reqBody, 'req');
    if (replacedBody !== reqBody) {
      reqBody = replacedBody;
      var _ni = Object.assign({}, init || {});
      _ni.body = reqBody;
      init = cloneInto(_ni, pw);
    }
    var reqId = uid();
    var t0 = Date.now();
    sendNet({ type: 'req', reqId: reqId, url: url, method: method, reqHeaders: reqHeaders, reqBody: reqBody });
    var mock = checkMock(url);
    if (mock) {
      sendNet({ type: 'resp', reqId: reqId, status: mock.status || 200,
        respHeaders: { 'x-mock': '1' }, respBody: mock.body || '', duration: Date.now() - t0 });
      // 必须返回 page-world 的 Promise/Response，否则页面访问会 "Permission denied"
      return pw.Promise.resolve(new pw.Response(mock.body || '', cloneInto({ status: mock.status || 200 }, pw)));
    }
    var thr = pw.__bhThrottle;
    var delay = (thr && thr.enabled && thr.latencyMs > 0) ? thr.latencyMs : 0;
    var fInput = input, fInit = init;
    // 抓包日志：挂在 page-world promise 上做副作用，绝不把这条 content-world 链返回给页面。
    // 关键：page-world promise 的 .then/.catch 回调必须用 exportFunction 导出，
    // 否则把 content-world 原始函数传给页面世界的 promise 会触发
    // "Permission denied to access object"（每次请求 settle 时刷屏）。
    function observe(p) {
      p.then(exportFunction(function (resp) {
        var status = resp.status;
        var respHeaders = headersToObj(resp.headers);
        resp.clone().text().then(exportFunction(function (body) {
          sendNet({ type: 'resp', reqId: reqId, status: status, respHeaders: respHeaders,
            respBody: body.slice(0, 102400), duration: Date.now() - t0 });
        }, pw), exportFunction(function () {
          sendNet({ type: 'resp', reqId: reqId, status: status, respHeaders: respHeaders,
            respBody: '(读取失败)', duration: Date.now() - t0 });
        }, pw));
      }, pw), exportFunction(function (err) {
        sendNet({ type: 'resp', reqId: reqId, status: 0, error: String(err), duration: Date.now() - t0 });
      }, pw));
    }
    var _bp = bpMatch(url);
    var _irRule = interceptRespMatch(url, method, reqBody);
    var _ir = _irRule || (!!netGlobalRespInterceptEnabled && (!!netGlobalInterceptNoise || !isNoiseBeforeSend(url, method, reqBody, reqHeaders)));
    var respStop = (!!_bp && (_bp.stage === 'resp' || _bp.stage === 'both')) || _ir;
    var respVia = _ir ? 'intercept' : 'breakpoint';
    // 响应断点（isolated）：读完整 body（流式被攒成整段），暂停编辑后构造 pw.Response 放行。
    // 跨世界：返回值必须是 pw.Response；page-world promise 的回调必须 exportFunction。
    // handleRespBp: reads body, applies suppress filter + resp replace, then pauses or returns
    function handleRespBp(p) {
      return new pw.Promise(exportFunction(function (resolve, reject) {
        p.then(exportFunction(function (resp) {
          var status = resp.status;
          var respHeaders = headersToObj(resp.headers);
          resp.text().then(exportFunction(function (body) {
            // suppress filter: skip intercept for ACK/no-content responses
            var _realStop = true;
            if (_ir && netScopeFilterSuppressResp && isSuppressResp(status, body)) _realStop = false;
            var nb = applyReplaceRules(body, 'resp');
            sendNet({ type: 'resp', reqId: reqId, status: status, respHeaders: respHeaders,
              respBody: (nb || body).slice(0, 102400), duration: Date.now() - t0 });
            if (!_realStop) {
              // Suppressed: skip interception, just return replaced body
              var rh = Object.assign({}, respHeaders); if (nb !== body) stripCL(rh);
              try { resolve(new pw.Response(nb, cloneInto({ status: status, headers: rh }, pw))); }
              catch (e) { try { resolve(new pw.Response(nb, cloneInto({ status: status }, pw))); }
                catch (e2) { try { resolve(new pw.Response(nb)); } catch (e3) { reject(new pw.Error(String(e3))); } } }
              return;
            }
            waitRespBp(reqId, status, respHeaders, nb, respVia).then(function (r) {
              if (r.action === 'abort') {
                sendNet({ type: 'resp', reqId: reqId, status: 0, error: '响应已被中止', duration: Date.now() - t0 });
                reject(new pw.Error('aborted by response intercept'));
                return;
              }
              var fnb = (r.respBody != null) ? applyReplaceRules(r.respBody, 'resp') : nb;
              var ns = r.status || status;
              var nh = stripCL(Object.assign({}, r.respHeaders || respHeaders));
              var rb = nullBodyStatus(ns) ? null : fnb;
              try { resolve(new pw.Response(rb, cloneInto({ status: ns, headers: nh }, pw))); }
              catch (e) {
                try { resolve(new pw.Response(rb, cloneInto({ status: ns }, pw))); }
                catch (e2) { try { resolve(new pw.Response(rb)); } catch (e3) { reject(new pw.Error(String(e3))); } }
              }
            });
          }, pw), exportFunction(function () { resolve(resp); }, pw));
        }, pw), exportFunction(function (err) { reject(err); }, pw));
      }, pw));
    }
    // handleRespReplace: reads body, applies resp replace, returns modified Response (no intercept pause)
    function handleRespReplace(p) {
      return new pw.Promise(exportFunction(function (resolve, reject) {
        p.then(exportFunction(function (resp) {
          var status = resp.status;
          var respHeaders = headersToObj(resp.headers);
          resp.text().then(exportFunction(function (body) {
            var nb = applyReplaceRules(body, 'resp');
            sendNet({ type: 'resp', reqId: reqId, status: status, respHeaders: respHeaders,
              respBody: (nb || body).slice(0, 102400), duration: Date.now() - t0 });
            var rh = Object.assign({}, respHeaders); if (nb !== body) stripCL(rh);
            try { resolve(new pw.Response(nb, cloneInto({ status: status, headers: rh }, pw))); }
            catch (e) { try { resolve(new pw.Response(nb, cloneInto({ status: status }, pw))); }
              catch (e2) { try { resolve(new pw.Response(nb)); } catch (e3) { reject(new pw.Error(String(e3))); } } }
          }, pw), exportFunction(function () { observe(p); resolve(resp); }, pw));
        }, pw), exportFunction(function (err) { reject(err); }, pw));
      }, pw));
    }
    var _doRR = (netReplaceScope === 'resp' || netReplaceScope === 'both') && netReplaceEnabled && netReplaceRules.length > 0;
    var doFetch = function () {
      var p = _origFetch.call(pw, fInput, fInit); // page-world promise
      if (respStop) return handleRespBp(p);
      if (_doRR) return handleRespReplace(p);
      observe(p);
      return p; // 直接返回页面世界的原生 promise，页面可正常 .then/.catch
    };
    var bpMode = (_bp && _bp.stage !== 'resp') ? 'breakpoint' : (checkGlobalIntercept(url, method, reqBody, reqHeaders) ? 'intercept' : '');
    // 无断点/拦截/弱网延迟时走快路径，直接返回页面 promise
    if (!bpMode && delay <= 0) {
      return doFetch();
    }
    // 需要延迟或断点：构造 page-world Promise 以保证返回值属于页面世界
    return new pw.Promise(exportFunction(function (resolve, reject) {
      function proceed() {
        try {
          doFetch().then(resolve, reject);
        } catch (e) { reject(e); }
      }
      if (bpMode) {
        waitBp(reqId, url, method, reqHeaders, reqBody, bpMode).then(function (r) {
          if (r.action === 'abort') {
            sendNet({ type: 'resp', reqId: reqId, status: 0, error: '已被断点中止', duration: Date.now() - t0 });
            reject(new pw.Error('aborted by breakpoint'));
            return;
          }
          var ni = cloneInto(Object.assign({}, (init && {}) || {}, {
            method: r.method || method,
            headers: r.reqHeaders || reqHeaders,
          }), pw);
          if (r.reqBody != null && r.reqBody !== '') ni.body = r.reqBody;
          fInput = r.url || url; fInit = ni;
          if (delay > 0) { pw.setTimeout(exportFunction(proceed, pw), delay); } else { proceed(); }
        });
      } else {
        pw.setTimeout(exportFunction(proceed, pw), delay);
      }
    }, pw));
  }, pw, { defineAs: 'fetch' });

  installIsolatedXhrInterceptor(pw);
}
