// Isolated-world XMLHttpRequest proxy installed with exportFunction.
function installIsolatedXhrInterceptor(pw) {
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

  // ── 替换 page world 的 XMLHttpRequest ──
	    var _OrigXHR = pw.__bhRestoreXHR || pw.XMLHttpRequest;
  exportFunction(function () {
    if (netConsoleBypass) { return new _OrigXHR(); }
    var xhr = new _OrigXHR();
    var w = xhr.wrappedJSObject || xhr;
    var _method = 'GET', _url = '', _reqHeaders = {}, _reqBody = null, _reqId = uid(), _t0 = 0, _done = false, _pausing = false;
    var origAdd = xhr.addEventListener.bind(xhr);
    var _on = {}, _reg = { readystatechange: [], load: [], loadend: [], error: [], timeout: [], abort: [], progress: [], loadstart: [] };
    function _emit(type) {
      var ev; try { ev = new pw.Event(type); } catch (e) { ev = { type: type }; }
      var on = _on['on' + type];
      if (typeof on === 'function') { try { on.call(w, ev); } catch (e2) {} }
      var a = _reg[type] || [];
      for (var i = 0; i < a.length; i++) { try { a[i].call(w, ev); } catch (e3) {} }
    }
    function _readRespHeaders() {
      var h = {};
      try {
        (xhr.getAllResponseHeaders() || '').split('\r\n').forEach(function (l) {
          var i = l.indexOf(':'); if (i < 0) return;
          h[l.slice(0, i).trim()] = l.slice(i + 1).trim();
        });
      } catch (e) {}
      return h;
    }
    try {
      Object.defineProperty(w, 'addEventListener', { configurable: true, value: exportFunction(function (type, fn) {
        if (_reg[type] && typeof fn === 'function') { _reg[type].push(fn); return; }
        return xhr.addEventListener.apply(xhr, arguments);
      }, pw) });
      Object.defineProperty(w, 'removeEventListener', { configurable: true, value: exportFunction(function (type, fn) {
        if (_reg[type]) { var a = _reg[type], i = a.indexOf(fn); if (i >= 0) a.splice(i, 1); return; }
        return xhr.removeEventListener.apply(xhr, arguments);
      }, pw) });
      ['onreadystatechange', 'onload', 'onloadend', 'onerror', 'ontimeout', 'onabort', 'onprogress', 'onloadstart'].forEach(function (p) {
        Object.defineProperty(w, p, { configurable: true,
          get: exportFunction(function () { return _on[p] || null; }, pw),
          set: exportFunction(function (v) { _on[p] = v || null; }, pw) });
      });
    } catch (e) {}
    origAdd('loadstart', function () { _emit('loadstart'); });
    origAdd('progress', function () { _emit('progress'); });
    origAdd('abort', function () { if (_done || _pausing) return; _done = true; _emit('readystatechange'); _emit('abort'); _emit('loadend'); });
    origAdd('readystatechange', function () {
      var rs = xhr.readyState;
      if (rs < 4) { _emit('readystatechange'); return; }
      if (_done || _pausing) return;
      var st = xhr.status; var respHeaders = _readRespHeaders();
      var rtype = xhr.responseType; var textual = (rtype === '' || rtype === 'text');
      var origBody = textual ? (xhr.responseText || '') : '';
      var _doRR = textual && (netReplaceScope === 'resp' || netReplaceScope === 'both') && netReplaceEnabled && netReplaceRules.length > 0;
      var body = _doRR ? applyReplaceRules(origBody, 'resp') : origBody;
      var _irRule = interceptRespMatch(_url, _method, _reqBody);
      var _ir = _irRule || (!!netGlobalRespInterceptEnabled && (!!netGlobalInterceptNoise || !isNoiseBeforeSend(_url, _method, _reqBody, _reqHeaders)));
      var _bpr = bpMatch(_url);
      var respStop = st !== 0 && textual && ((!!_bpr && (_bpr.stage === 'resp' || _bpr.stage === 'both')) || _ir);
      if (respStop && _ir && netScopeFilterSuppressResp && isSuppressResp(st, body)) respStop = false;
      var respVia = _ir ? 'intercept' : 'breakpoint';
      if (respStop) {
        _pausing = true;
        sendNet({ type: 'resp', reqId: _reqId, status: st, respHeaders: respHeaders, respBody: body.slice(0, 102400), duration: Date.now() - _t0 });
        waitRespBp(_reqId, st, respHeaders, body, respVia).then(function (r) {
          _pausing = false; _done = true;
          if (r.action === 'abort') {
            sendNet({ type: 'resp', reqId: _reqId, status: 0, error: '响应已被中止', duration: Date.now() - _t0 });
            _emit('readystatechange'); _emit('error'); _emit('loadend'); return;
          }
          var nb = (r.respBody != null) ? applyReplaceRules(r.respBody, 'resp') : body; var ns = r.status || st; var nh = stripCL(Object.assign({}, r.respHeaders || respHeaders));
          var rb = nullBodyStatus(ns) ? '' : nb;
          try { Object.defineProperty(w, 'status', { configurable: true, get: exportFunction(function () { return ns; }, pw) }); } catch (e) {}
          try { Object.defineProperty(w, 'responseText', { configurable: true, get: exportFunction(function () { return rb; }, pw) }); } catch (e) {}
          try { Object.defineProperty(w, 'response', { configurable: true, get: exportFunction(function () { return rb; }, pw) }); } catch (e) {}
          try { Object.defineProperty(w, 'getAllResponseHeaders', { configurable: true, value: exportFunction(function () { return Object.keys(nh).map(function (k) { return k + ': ' + nh[k]; }).join('\r\n'); }, pw) }); } catch (e) {}
          try { Object.defineProperty(w, 'getResponseHeader', { configurable: true, value: exportFunction(function (k) { var t = String(k).toLowerCase(), f = null; Object.keys(nh).forEach(function (h) { if (h.toLowerCase() === t) f = nh[h]; }); return f; }, pw) }); } catch (e) {}
          _emit('readystatechange'); _emit('load'); _emit('loadend');
        });
        return;
      }
      _done = true;
      sendNet({ type: 'resp', reqId: _reqId, status: st, respHeaders: respHeaders, respBody: body.slice(0, 102400), duration: Date.now() - _t0 });
      if (_doRR && body !== origBody) {
        try { Object.defineProperty(w, 'responseText', { configurable: true, get: exportFunction(function () { return body; }, pw) }); } catch (e) {}
        try { Object.defineProperty(w, 'response', { configurable: true, get: exportFunction(function () { return body; }, pw) }); } catch (e) {}
      }
      _emit('readystatechange');
      if (st === 0) { _emit('error'); } else { _emit('load'); }
      _emit('loadend');
    });
    exportFunction(function (method, url) { _method = method.toUpperCase(); _url = url; return xhr.open.apply(xhr, arguments); }, xhr, { defineAs: 'open' });
    exportFunction(function (k, v) { _reqHeaders[k] = v; return xhr.setRequestHeader(k, v); }, xhr, { defineAs: 'setRequestHeader' });
    exportFunction(function (body) {
      _reqBody = body != null ? String(body) : null;
      var _rb2 = applyReplaceRules(_reqBody, 'req');
      if (_rb2 !== _reqBody) { _reqBody = _rb2; body = _rb2; }
      _t0 = Date.now();
      sendNet({ type: 'req', reqId: _reqId, url: _url, method: _method, reqHeaders: _reqHeaders, reqBody: _reqBody });
      var mock = checkMock(_url);
      if (mock) {
        var _mb = mock.body || '', _ms = mock.status || 200;
        sendNet({ type: 'resp', reqId: _reqId, status: _ms,
          respHeaders: { 'x-mock': '1' }, respBody: _mb, duration: Date.now() - _t0 });
        // 用 wrappedJSObject 在 page world 上下文里伪造 readyState/status/responseText 并派发事件
        try {
          Object.defineProperty(w, 'readyState', { configurable: true, get: exportFunction(function () { return 4; }, pw) });
          Object.defineProperty(w, 'status', { configurable: true, get: exportFunction(function () { return _ms; }, pw) });
          Object.defineProperty(w, 'responseText', { configurable: true, get: exportFunction(function () { return _mb; }, pw) });
          Object.defineProperty(w, 'response', { configurable: true, get: exportFunction(function () { return _mb; }, pw) });
          Object.defineProperty(w, 'getAllResponseHeaders', { configurable: true, value: exportFunction(function () { return 'x-mock: 1'; }, pw) });
        } catch (e) {}
        _done = true;
        pw.setTimeout(exportFunction(function () { _emit('readystatechange'); _emit('load'); _emit('loadend'); }, pw), 0);
        return;
      }
      var doSend = function () { xhr.send(body); };
      var thr = pw.__bhThrottle;
      var d = (thr && thr.enabled && thr.latencyMs > 0) ? thr.latencyMs : 0;
      var fireSend = function () { if (d > 0) { setTimeout(doSend, d); } else { doSend(); } };
	        var _bpx = bpMatch(_url);
	        var bpMode = (_bpx && _bpx.stage !== 'resp') ? 'breakpoint' : (checkGlobalIntercept(_url, _method, _reqBody, _reqHeaders) ? 'intercept' : '');
      if (bpMode) {
        waitBp(_reqId, _url, _method, _reqHeaders, _reqBody, bpMode).then(function (r) {
          if (r.action === 'abort') {
            sendNet({ type: 'resp', reqId: _reqId, status: 0, error: '已被断点中止', duration: Date.now() - _t0 });
            return;
          }
          var nm = r.method || _method, nu = r.url || _url;
          if (nm !== _method || nu !== _url) { try { xhr.open(nm, nu); } catch (e) {} }
          if (r.reqHeaders) { try { Object.keys(r.reqHeaders).forEach(function (k) { xhr.setRequestHeader(k, r.reqHeaders[k]); }); } catch (e) {} }
          if (r.reqBody != null && r.reqBody !== '') body = r.reqBody;
          fireSend();
        });
        return;
      }
      fireSend();
    }, xhr, { defineAs: 'send' });
    return xhr;
  }, pw, { defineAs: 'XMLHttpRequest' });
}
