// Network panel request list rendering and request marking rules.
	  function ruleKey(rule) {
	    return [
	      rule.host || '',
	      rule.path || '',
	      String(rule.method || 'GET').toUpperCase(),
	      rule.hasBody ? '1' : '0',
	    ].join('\n');
	  }

	  function sanitizeInterceptRules(rules) {
	    if (!Array.isArray(rules)) return [];
	    return rules.map(function (r) {
	      return {
	        id: String(r.id || (Date.now().toString(36) + Math.random().toString(36).slice(2))),
	        action: r.action === 'intercept' ? 'intercept' : 'pass',
	        host: String(r.host || ''),
	        path: String(r.path || ''),
	        method: String(r.method || 'GET').toUpperCase(),
	        hasBody: !!r.hasBody,
	        interceptResp: !!r.interceptResp,
	        sampleUrl: String(r.sampleUrl || ''),
	        createdAt: r.createdAt || Date.now(),
	        updatedAt: r.updatedAt || r.createdAt || Date.now(),
	      };
	    }).filter(function (r) {
	      return r.action && (r.host || r.path);
	    });
	  }

	  function makeInterceptRuleFromReq(r, action) {
	    var u = normalizeRuleUrl(r && r.url);
	    return {
	      id: Date.now().toString(36) + Math.random().toString(36).slice(2),
	      action: action === 'intercept' ? 'intercept' : 'pass',
	      host: u.host,
	      path: u.path,
	      method: String((r && r.method) || 'GET').toUpperCase(),
	      hasBody: byteLen(r && r.reqBody) > 0,
	      interceptResp: false,
	      sampleUrl: u.sampleUrl,
	      createdAt: Date.now(),
	      updatedAt: Date.now(),
	    };
	  }

	  function syncInterceptRules() {
	    var clean = sanitizeInterceptRules(netInterceptRules);
	    if (erudaMode !== 'page' && typeof window.wrappedJSObject !== 'undefined' && typeof cloneInto !== 'undefined') {
	      try { window.wrappedJSObject.__bhInterceptRules = cloneInto(clean, window); return; } catch (e) {}
	    }
	    runInPage('(function(){window.__bhInterceptRules=' + JSON.stringify(clean) + ';})();');
	  }

	  function saveInterceptRules() {
	    netInterceptRules = sanitizeInterceptRules(netInterceptRules);
	    var st = storageLocal();
	    if (st && st.set) {
	      try { st.set({ bhNetInterceptRules: netInterceptRules }).catch(function () {}); } catch (e) {}
	    }
	    syncInterceptRules();
	    updateRulesBtn();
	    renderRulesView();
	  }

	  function loadInterceptRules() {
	    var st = storageLocal();
	    if (!st || !st.get) {
	      syncInterceptRules();
	      return;
	    }
	    try {
	      st.get('bhNetInterceptRules').then(function (res) {
	        netInterceptRules = sanitizeInterceptRules(res && res.bhNetInterceptRules);
	        syncInterceptRules();
	        updateRulesBtn();
	        renderRulesView();
	      }).catch(function () {
	        syncInterceptRules();
	        updateRulesBtn();
	      });
	    } catch (e) {
	      syncInterceptRules();
	      updateRulesBtn();
	    }
	  }

	  function upsertInterceptRuleFromReq(r, action) {
	    if (!r) return null;
	    var rule = makeInterceptRuleFromReq(r, action);
	    var key = ruleKey(rule);
	    var replaced = false;
	    netInterceptRules = sanitizeInterceptRules(netInterceptRules).map(function (old) {
	      if (ruleKey(old) !== key) return old;
	      replaced = true;
	      rule.id = old.id;
	      rule.createdAt = old.createdAt || rule.createdAt;
	      rule.interceptResp = !!old.interceptResp;
	      rule.updatedAt = Date.now();
	      return rule;
	    });
	    if (!replaced) netInterceptRules.push(rule);
	    saveInterceptRules();
	    return rule;
	  }

	  function findRuleForReq(r) {
	    if (!r) return null;
	    var key = ruleKey(makeInterceptRuleFromReq(r, 'pass'));
	    var found = null;
	    sanitizeInterceptRules(netInterceptRules).forEach(function (x) {
	      if (ruleKey(x) === key) found = x;
	    });
	    return found;
	  }

	  // 长按请求/拦截项里切换"是否拦截服务器响应"。无对应标记时新建一条放行规则只为拦响应。
	  function toggleRespRuleForReq(r) {
	    if (!r) return;
	    var key = ruleKey(makeInterceptRuleFromReq(r, 'pass'));
	    var existing = null;
	    netInterceptRules = sanitizeInterceptRules(netInterceptRules);
	    netInterceptRules.forEach(function (x) { if (ruleKey(x) === key) existing = x; });
	    if (existing) {
	      existing.interceptResp = !existing.interceptResp;
	      existing.updatedAt = Date.now();
	    } else {
	      var rule = makeInterceptRuleFromReq(r, 'pass');
	      rule.interceptResp = true;
	      netInterceptRules.push(rule);
	    }
	    saveInterceptRules();
	  }

	  function removeInterceptRule(ruleId) {
	    netInterceptRules = sanitizeInterceptRules(netInterceptRules).filter(function (r) {
	      return r.id !== ruleId;
	    });
	    saveInterceptRules();
	  }

	  function ruleActionLabel(action) {
	    return action === 'intercept' ? '拦截' : '放行';
	  }

function isPayloadReq(r) {
  var method = String(r.method || 'GET').toUpperCase();
  var rb = byteLen(r.reqBody);
  var sb = byteLen(r.respBody);
  var ct = (headerValue(r.reqHeaders, 'content-type') + ' ' + headerValue(r.respHeaders, 'content-type')).toLowerCase();
  var url = String(r.url || '').toLowerCase();
  if (rb > 0) return true;
  if (/^(POST|PUT|PATCH|DELETE)$/.test(method) && !isTunnelNoiseReq(r)) return true;
  if (sb > 2048 && /json|graphql|event-stream|text\/event-stream|text\/plain/.test(ct)) return true;
  if (/chat|conversation|completion|message|messages|graphql|backend-api|generate|prompt|ask/.test(url) &&
      (method !== 'GET' || sb > 0)) return true;
  return false;
}

// 低价值上报/噪音的共同体积护栏：小请求体、GET 才算、整体小或无响应体。
function lowValueGuard(r) {
  var method = String(r.method || 'GET').toUpperCase();
  var rb = byteLen(r.reqBody);
  var sb = byteLen(r.respBody);
  var total = reqSize(r) + respSize(r);
  if (rb > 512) return false;
  if (method !== 'GET' && rb > 0) return false;
  return total < 8 * 1024 || sb === 0;
}

// 遥测/上报类：collect/analytics/metrics/beacon/sentry/trace/pixel 等。
function isTelemetryReq(r) {
  var url = String(r.url || '').toLowerCase();
  var telRe = /(collect|telemetry|analytics|metrics|beacon|sentry|trace|traces|stats|gtm|gtag|google-analytics|doubleclick|amplitude|mixpanel|segment|datadog|bugsnag|track|pixel|rum)([/?#_.-]|$)/;
  if (!telRe.test(url)) return false;
  return lowValueGuard(r);
}

// 长连接/心跳噪音类：heartbeat/ping/poll/socket.io/realtime/presence 等。
function isNoiseReq(r) {
  var url = String(r.url || '').toLowerCase();
  var noiseRe = /(heartbeat|ping|keepalive|poll|socket\.io|sockjs|realtime|tunnel|presence|typing|status|health|alive|connect|events?)([/?#_.-]|$)|[?&](ping|heartbeat|keepalive|beacon)=/;
  if (!noiseRe.test(url)) return false;
  return lowValueGuard(r);
}

// cookie/session 包：激进过滤——URL 含 cookie/session，或请求带 Cookie 头、响应带 Set-Cookie 头即命中。
// 用户反馈：开代理后 cookie 同步包刷屏，宁可多隐藏也要清掉。
function isCookieReq(r) {
  var url = String(r.url || '').toLowerCase();
  if (/cookie|session/.test(url)) return true;
  if (headerValue(r.reqHeaders, 'cookie')) return true;
  if (headerValue(r.respHeaders, 'set-cookie')) return true;
  return false;
}

// 兼容旧引用：遥测或噪音。
function isTunnelNoiseReq(r) {
  return isTelemetryReq(r) || isNoiseReq(r);
}

function fmtInterceptHead(d) {
  var lines = [
    'METHOD: ' + (d.method || 'GET'),
    'URL: ' + (d.url || ''),
    '',
  ];
  var h = d.reqHeaders || {};
  Object.keys(h).forEach(function (k) {
    lines.push(k + ': ' + h[k]);
  });
  return lines.join('\n');
}

	  function parseInterceptHead(text, d) {
	    var headers = {};
	    String(text || '').split(/\r?\n/).forEach(function (line) {
    if (!line.trim()) return;
    var idx = line.indexOf(':');
    if (idx < 0) return;
    var key = line.slice(0, idx).trim();
    var val = line.slice(idx + 1).trim();
    if (/^method$/i.test(key)) d.method = (val || d.method || 'GET').toUpperCase();
    else if (/^url$/i.test(key)) d.url = val || d.url;
    else headers[key] = val;
	    });
	    d.reqHeaders = headers;
	  }

	  function bodyValueFromTextarea(emptyAsNull) {
	    if (!netDetailBody) return emptyAsNull ? null : '';
	    var value = netDetailBody.value;
	    if (value === '(无)') return emptyAsNull ? null : '';
	    return value;
	  }

	  function syncInterceptEdit() {
	    if (!netSelIntercept || !netDetailBody || !netDetailDirty) return;
	    if (netDetailTab === 0) parseInterceptHead(netDetailBody.value, netSelIntercept);
	    else if (netDetailTab === 1) {
	      var body = bodyValueFromTextarea(false);
	      if (body !== netSelIntercept.reqBody) removeHeaderCI(netSelIntercept.reqHeaders, 'content-length');
	      netSelIntercept.reqBody = body;
	    }
	    netDetailDirty = false;
	  }

	  function syncRespInterceptEdit() {
	    if (!netSelRespIntercept || !netDetailBody || !netDetailDirty) return;
	    if (netDetailTab === 2) {
	      // 响应头：首行 "HTTP/1.1 <code> ..." 解析状态码，其余 "key: value" 解析回 respHeaders
	      var headers = {};
	      String(netDetailBody.value || '').split(/\r?\n/).forEach(function (line) {
	        var m = line.match(/^HTTP\/[\d.]+\s+(\d{3})/i);
	        if (m) { netSelRespIntercept.status = parseInt(m[1], 10); return; }
	        var idx = line.indexOf(':');
	        if (idx < 0) return;
	        headers[line.slice(0, idx).trim()] = line.slice(idx + 1).trim();
	      });
	      netSelRespIntercept.respHeaders = headers;
	    } else if (netDetailTab === 3) {
	      var rb = bodyValueFromTextarea(false);
	      if (rb !== netSelRespIntercept.respBody) removeHeaderCI(netSelRespIntercept.respHeaders, 'content-length');
	      netSelRespIntercept.respBody = rb;
	    }
	    netDetailDirty = false;
	  }

	  function syncCurrentDetailEdit() {
	    if (!netDetailDirty || !netDetailBody) return;
	    if (netSelRespIntercept) {
	      syncRespInterceptEdit();
	      return;
	    }
	    if (netSelIntercept) {
	      syncInterceptEdit();
	      return;
	    }
	    if (netSelReq && netDetailTab === 0) {
	      // 普通请求请求头：解析 "key: value" 格式写回
	      var headers = {};
	      String(netDetailBody.value || '').split(/\r?\n/).forEach(function (line) {
	        var idx = line.indexOf(':');
	        if (idx < 0) return;
	        headers[line.slice(0, idx).trim()] = line.slice(idx + 1).trim();
	      });
	      netSelReq.reqHeaders = headers;
	    }
	    if (netSelReq && netDetailTab === 1) {
	      var body = bodyValueFromTextarea(true);
	      if (body !== netSelReq.reqBody) removeHeaderCI(netSelReq.reqHeaders, 'content-length');
	      netSelReq.reqBody = body;
	    }
	    netDetailDirty = false;
	  }

function interceptAsReq(d) {
  return {
    reqId: d.reqId,
    url: d.url,
    method: d.method,
    reqHeaders: d.reqHeaders || {},
    reqBody: d.reqBody || '',
    status: null,
    respHeaders: {},
    respBody: null,
    duration: null,
    error: null,
    ts: d.ts || Date.now(),
    tag: '拦截中',
    plain: [],
  };
}

	  function finishIntercept(d, payload) {
	    if (!d || d.__done) return;
	    if (netSelIntercept && netSelIntercept.reqId === d.reqId) syncCurrentDetailEdit();
	    d.__done = true;
  bpResolve(d.reqId, payload);
  netInterceptQueue = netInterceptQueue.filter(function (x) { return x.reqId !== d.reqId; });
  if (netSelIntercept && netSelIntercept.reqId === d.reqId) {
    netSelIntercept = netInterceptQueue[0] || null;
    netDetailTab = netSelIntercept && byteLen(netSelIntercept.reqBody) > 0 ? 1 : 0;
    setNetEditing(false);
  }
  updateInterceptBtn();
  renderNetList();
  renderDetail(true);
}

	  function sendSelectedIntercept() {
	    if (!netSelIntercept) return;
	    syncCurrentDetailEdit();
	    finishIntercept(netSelIntercept, {
    action: 'continue',
    url: netSelIntercept.url,
    method: String(netSelIntercept.method || 'GET').toUpperCase(),
    reqHeaders: netSelIntercept.reqHeaders || {},
    reqBody: netSelIntercept.reqBody || '',
  });
}

	  function abortSelectedIntercept() {
	    if (!netSelIntercept) return;
	    finishIntercept(netSelIntercept, { action: 'abort' });
	  }

	  function releaseAllIntercepts() {
	    netInterceptQueue.slice().forEach(function (d) {
	      if (netSelIntercept && netSelIntercept.reqId === d.reqId) syncCurrentDetailEdit();
	      finishIntercept(d, {
	        action: 'continue',
	        url: d.url,
	        method: String(d.method || 'GET').toUpperCase(),
	        reqHeaders: d.reqHeaders || {},
	        reqBody: d.reqBody || '',
	      });
	    });
	  }

	  function finishRespIntercept(d, payload) {
	    if (!d || d.__done) return;
	    d.__done = true;
	    respResolve(d.reqId, payload);
	    netRespInterceptQueue = netRespInterceptQueue.filter(function (x) { return x.reqId !== d.reqId; });
	    if (netSelRespIntercept && netSelRespIntercept.reqId === d.reqId) {
	      netSelRespIntercept = netRespInterceptQueue[0] || null;
	      netDetailTab = netSelRespIntercept && byteLen(netSelRespIntercept.respBody) > 0 ? 3 : 2;
	      setNetEditing(false);
	    }
	    updateInterceptBtn();
	    renderNetList();
	    renderDetail(true);
	  }

	  // 放行选中的响应拦截（用详情页编辑后的状态码/响应头/响应体）
	  function releaseSelectedRespIntercept() {
	    if (!netSelRespIntercept) return;
	    syncCurrentDetailEdit();
	    var d = netSelRespIntercept;
	    finishRespIntercept(d, {
	      action: 'continue',
	      status: d.status,
	      respHeaders: d.respHeaders || {},
	      respBody: d.respBody == null ? '' : d.respBody,
	    });
	  }

	  function abortSelectedRespIntercept() {
	    if (!netSelRespIntercept) return;
	    finishRespIntercept(netSelRespIntercept, { action: 'abort' });
	  }

	  function releaseAllRespIntercepts() {
	    netRespInterceptQueue.slice().forEach(function (d) {
	      if (netSelRespIntercept && netSelRespIntercept.reqId === d.reqId) syncCurrentDetailEdit();
	      finishRespIntercept(d, {
	        action: 'continue',
	        status: d.status,
	        respHeaders: d.respHeaders || {},
	        respBody: d.respBody == null ? '' : d.respBody,
	      });
	    });
	  }

	  function releaseAllPendingIso() {
	    Object.keys(_bpIsoPending).forEach(function (reqId) {
	      var p = _bpIsoPending[reqId];
	      delete _bpIsoPending[reqId];
	      try {
	        (p.resolve || p)({
	          action: 'continue',
	          url: p && p.url,
	          method: String((p && p.method) || 'GET').toUpperCase(),
	          reqHeaders: (p && p.reqHeaders) || {},
	          reqBody: (p && p.reqBody) == null ? '' : p.reqBody,
	        });
	      } catch (e) {}
	    });
	    Object.keys(_respBpIsoPending).forEach(function (reqId) {
	      var p = _respBpIsoPending[reqId];
	      delete _respBpIsoPending[reqId];
	      try {
	        (p.resolve || p)({
	          action: 'continue',
	          status: (p && p.status) == null ? 200 : p.status,
	          respHeaders: (p && p.respHeaders) || {},
	          respBody: (p && p.respBody) == null ? '' : p.respBody,
	        });
	      } catch (e) {}
	    });
	  }

	  function getVisibleRequests() {
  var filter = netFilterEl ? netFilterEl.value.trim().toLowerCase() : '';
  return netRequests.filter(function (r) {
    if (filter && String(r.url || '').toLowerCase().indexOf(filter) === -1) return false;
    if (netPayloadOnly && !isPayloadReq(r)) return false;
    if (netHideTelemetry && isTelemetryReq(r)) return false;
    if (netHideNoise && isNoiseReq(r)) return false;
    if (netHideCookie && isCookieReq(r)) return false;
    return true;
  });
}

function selectFirstVisibleRequest(preferPayload) {
  var rows = getVisibleRequests();
  if (!rows.length) {
    netSelReq = null;
    netSelIntercept = null;
    netSelRespIntercept = null;
    renderNetList();
    renderDetail(true);
    return;
  }
  var picked = rows[0];
  if (preferPayload) {
    picked = rows.find(function (r) { return byteLen(r.reqBody) > 0; }) ||
      rows.find(function (r) { return isPayloadReq(r); }) || rows[0];
  }
  netSelReq = picked;
  netSelIntercept = null;
  netSelRespIntercept = null;
  netDetailTab = byteLen(picked.reqBody) > 0 ? 1 : 3;
  setNetEditing(false);
  renderNetList();
  renderDetail(true);
}

function maybeFocusPayload(entry) {
  if (!netPayloadOnly || netEditing || netSelIntercept || !isPayloadReq(entry)) return;
  // 已有选中请求时不抢焦点：用户正在看某个请求，新包不该把详情切走。
  if (netSelReq) return;
  if (netHideTelemetry && isTelemetryReq(entry)) return;
  if (netHideNoise && isNoiseReq(entry)) return;
  if (netHideCookie && isCookieReq(entry)) return;
  netSelReq = entry;
  netDetailTab = byteLen(entry.reqBody) > 0 ? 1 : 3;
}

function renderNetList() {
  if (!netListEl) return;
  // 重渲染前记下滚动位置和总高。新请求是 unshift 到顶部 → 全量重建后顶部多出几行，
  // 若用户正滚动在下方看某条请求，要按新增高度补偿 scrollTop，让当前查看的行视觉不动。
  var prevTop = netListEl.scrollTop;
  var prevHeight = netListEl.scrollHeight;
  var rows = getVisibleRequests();
  var showIntercepts = netGlobalInterceptEnabled || netInterceptQueue.length > 0 || netRespInterceptQueue.length > 0;
  if (rows.length === 0 && !showIntercepts) {
    netListEl.innerHTML = '<div id="bh-empty">暂无匹配请求</div>';
    return;
  }
  var html = '';
  if (showIntercepts) {
    // 上分栏：发出去被拦截的请求（待发送）
    html += '<div class="bh-section-title">请求拦截 ' + netInterceptQueue.length + '</div>';
    if (!netInterceptQueue.length) {
      html += '<div class="bh-section-empty">暂无拦截请求</div>';
    } else {
      netInterceptQueue.forEach(function (d) {
        var r = interceptAsReq(d);
        var selClass = netSelIntercept && netSelIntercept.reqId === d.reqId ? ' bh-sel' : '';
        var meta = '等待发送 · ↑ ' + fmtBytes(reqSize(r));
        html += '<div class="bh-row bh-intercept' + selClass + '" data-int-id="' + escHtml(d.reqId) + '">' +
          '<span class="bh-method">' + escHtml(d.method || 'GET') + '</span>' +
          '<span class="bh-main">' +
            '<span class="bh-url" title="' + escHtml(d.url || '') + '">' + escHtml(truncUrl(d.url || '')) + '</span>' +
            '<span class="bh-meta">' + escHtml(meta) + '</span>' +
          '</span>' +
          '<span class="bh-tag">待发送</span>' +
          '</div>';
      });
    }
    // 下分栏：发进来被拦截的服务器响应（待放行）
    html += '<div class="bh-section-title">响应拦截 ' + netRespInterceptQueue.length + '</div>';
    if (!netRespInterceptQueue.length) {
      html += '<div class="bh-section-empty">暂无拦截响应</div>';
    } else {
      netRespInterceptQueue.forEach(function (d) {
        var sc = statusClass(d.status, false);
        var statusNum = d.status ? String(d.status) : '…';
        var meta = '待放行 · ↓ ' + fmtBytes(byteLen(d.respBody));
        var selClass = netSelRespIntercept && netSelRespIntercept.reqId === d.reqId ? ' bh-sel' : '';
        html += '<div class="bh-row bh-resp-intercept' + selClass + '" data-rint-id="' + escHtml(d.reqId) + '">' +
          '<span class="bh-method">' + escHtml(d.method || 'GET') + '</span>' +
          '<span class="bh-main">' +
            '<span class="bh-url" title="' + escHtml(d.url || '') + '">' + escHtml(truncUrl(d.url || '')) + '</span>' +
            '<span class="bh-meta">' + escHtml(meta) + '</span>' +
          '</span>' +
          '<span class="bh-status-wrap">' +
            '<span class="bh-status ' + sc + '">' + escHtml(statusNum) + '</span>' +
          '</span>' +
          '</div>';
      });
    }
    if (rows.length) html += '<div class="bh-section-title">请求记录</div>';
  }
  rows.forEach(function (r) {
    var hasErr = !!r.error;
    var sc = statusClass(r.status, hasErr);
    var statusNum = r.status ? String(r.status) : (hasErr ? 'ERR' : '…');
    var statusDesc = r.status ? statusPhrase(r.status) : (hasErr ? shortError(r.error) : '');
    var dur = r.duration != null ? r.duration + 'ms' : '…';
    var meta = '↑ ' + fmtBytes(reqSize(r)) + ' · ↓ ' + fmtBytes(respSize(r)) + ' · ' + dur;
    var tags = [];
    if (r.tag) tags.push(r.tag);
    if (r.plain && r.plain.length) tags.push('明文');
    var tag = tags.map(function (t) { return '<span class="bh-tag">' + escHtml(t) + '</span>'; }).join('');
    var bpClass = netBreakpoints.some(function (bp) {
      return r.url.indexOf(bp.pattern) !== -1;
    }) ? ' bh-bp' : '';
    var selClass = netSelReq && netSelReq.reqId === r.reqId ? ' bh-sel' : '';
    var errTitle = hasErr ? ' title="' + escHtml(r.error) + '"' : '';
    html += '<div class="bh-row' + bpClass + selClass + '" data-id="' + r.reqId + '">' +
      '<span class="bh-method">' + r.method + '</span>' +
      '<span class="bh-main">' +
        '<span class="bh-url" title="' + escHtml(r.url) + '">' + escHtml(truncUrl(r.url)) + '</span>' +
        '<span class="bh-meta">' + escHtml(meta) + '</span>' +
      '</span>' +
      tag +
      '<span class="bh-status-wrap"' + errTitle + '>' +
        '<span class="bh-status ' + sc + '">' + statusNum + '</span>' +
        (statusDesc ? '<span class="bh-status-desc">' + escHtml(statusDesc) + '</span>' : '') +
      '</span>' +
      '</div>';
  });
  netListEl.innerHTML = html;
  // 恢复滚动：用户在顶部(≈0)时保持顶部，让新请求可见；否则按新增高度补偿，保持当前查看行不动。
  var delta = netListEl.scrollHeight - prevHeight;
  if (prevTop > 4 && delta > 0) netListEl.scrollTop = prevTop + delta;
  else netListEl.scrollTop = prevTop;
  // 绑定点击
	    Array.prototype.forEach.call(netListEl.querySelectorAll('.bh-intercept'), function (el) {
	      el.addEventListener('click', function () {
	        syncCurrentDetailEdit();
	        var id = el.getAttribute('data-int-id');
	        netSelIntercept = netInterceptQueue.find(function (r) { return r.reqId === id; }) || null;
      netSelReq = null;
      netSelRespIntercept = null;
      setNetEditing(false);
      netDetailTab = netSelIntercept && byteLen(netSelIntercept.reqBody) > 0 ? 1 : 0;
	        renderNetList();
	        renderDetail(true);
	      });
	      bindLongPress(el, function () {
	        var id = el.getAttribute('data-int-id');
	        var d = netInterceptQueue.find(function (r) { return r.reqId === id; });
	        if (d) openMarkMenu(interceptAsReq(d));
	      });
	    });
	    Array.prototype.forEach.call(netListEl.querySelectorAll('.bh-resp-intercept'), function (el) {
	      el.addEventListener('click', function () {
	        syncCurrentDetailEdit();
	        var id = el.getAttribute('data-rint-id');
	        netSelRespIntercept = netRespInterceptQueue.find(function (r) { return r.reqId === id; }) || null;
	        netSelReq = null;
	        netSelIntercept = null;
	        setNetEditing(false);
	        netDetailTab = netSelRespIntercept && byteLen(netSelRespIntercept.respBody) > 0 ? 3 : 2;
	        renderNetList();
	        renderDetail(true);
	      });
	    });
	    Array.prototype.forEach.call(netListEl.querySelectorAll('.bh-row'), function (el) {
	      if (el.getAttribute('data-int-id') || el.getAttribute('data-rint-id')) return;
	      el.addEventListener('click', function () {
	        syncCurrentDetailEdit();
	        var id = el.getAttribute('data-id');
      netSelReq = netRequests.find(function (r) { return r.reqId === id; }) || null;
      netSelIntercept = null;
      netSelRespIntercept = null;
      setNetEditing(false); // 切换到新请求，退出编辑态以便显示新内容
      // 保留当前 tab，切换请求不重置 tab 选择
	        renderNetList();
	        renderDetail(true);
	      });
	      bindLongPress(el, function () {
	        var id = el.getAttribute('data-id');
	        var r = netRequests.find(function (x) { return x.reqId === id; });
	        openMarkMenu(r);
	      });
	    });
	  }
