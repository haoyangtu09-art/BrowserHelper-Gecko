// Request/response detail view, modals, editors, and search.
	  function closeNetDetail() {
	    syncCurrentDetailEdit();
	    netSelReq = null;
  netSelIntercept = null;
  netSelRespIntercept = null;
  setNetEditing(false);
  clearDetailSearch();
  try { if (netDetailBody) netDetailBody.blur(); } catch (e) {}
  renderNetList();
  renderDetail(true);
}

function escHtml(s) {
  return String(s || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}
function truncUrl(url) {
  return url.length > 60 ? url.slice(0, 60) + '…' : url;
}
	  function fmtHeaders(obj) {
	    if (!obj) return '(无)';
	    return Object.keys(obj).map(function (k) { return k + ': ' + obj[k]; }).join('\n') || '(无)';
	  }

	  function isJsonLikeBody(body, headers) {
	    if (body == null) return false;
	    var text = String(body).trim();
	    if (!text) return false;
	    var ct = headerValue(headers, 'content-type').toLowerCase();
	    return /json|graphql/.test(ct) || /^[\[{]/.test(text);
	  }

	  function decodeUnicodeEscapes(text) {
	    if (text.indexOf('\\u') === -1) return text;
	    return text.replace(/\\u([0-9a-fA-F]{4})/g, function (m, hex) {
	      return String.fromCharCode(parseInt(hex, 16));
	    });
	  }

	  function formatBodyForDisplay(body, headers) {
	    if (body == null) return '(无)';
	    var text = String(body);
	    if (!text.trim()) return text;
	    if (isJsonLikeBody(text, headers)) {
	      try { return JSON.stringify(JSON.parse(text), null, 2); } catch (e) {}
	    }
	    return decodeUnicodeEscapes(text);
	  }

	  function removeHeaderCI(headers, name) {
	    if (!headers) return headers;
	    var target = String(name || '').toLowerCase();
	    Object.keys(headers).forEach(function (k) {
	      if (k.toLowerCase() === target) delete headers[k];
	    });
	    return headers;
	  }

	  function renderDetail(force) {
	    if (!netDetailEl || !netDetailBody) return;
	    var pendingIntercept = !!netSelIntercept;
	    var pendingResp = !!netSelRespIntercept;
	    if (!netSelReq && !pendingIntercept && !pendingResp) {
    netDetailEl.style.display = 'none';
    if (netListEl) netListEl.style.flex = '1 1 auto';
    return;
  }
	    netDetailEl.style.display = 'flex';
	    // 详情展开时列表收缩到约 30%，把空间让给请求/响应体
	    if (netListEl) netListEl.style.flex = '0 0 30%';
	    var r = pendingResp ? netSelRespIntercept : (pendingIntercept ? interceptAsReq(netSelIntercept) : netSelReq);
	    updateDetailActionButtons(pendingIntercept, pendingResp);
	    // 编辑中收到网络刷新时不改任何详情 DOM。之前这里仍会重建 tab，
	    // Android/GeckoView 上会让 IME 的目标 textarea 丢焦点，后续字符穿到页面。
	    if ((netEditing || netDetailDirty) && !force) return;
	    var tabs = ['请求头', '请求体', '响应头', '响应体', '明文'];
	    var tabsEl = netDetailEl.querySelector('#bh-detail-tabs');
	    tabsEl.innerHTML = tabs.map(function (t, i) {
	      return '<div class="bh-dtab' + (netDetailTab === i ? ' active' : '') + '" data-i="' + i + '">' + t + '</div>';
	    }).join('');
	    Array.prototype.forEach.call(tabsEl.querySelectorAll('.bh-dtab'), function (el) {
	      el.addEventListener('click', function () {
	        syncCurrentDetailEdit();
	        setNetEditing(false);
	        netDetailTab = parseInt(el.getAttribute('data-i'));
	        renderDetail(true); // 用户主动切 tab，强制刷新内容
	      });
	    });
	    var content = '';
	    if (pendingIntercept && netDetailTab === 0) content = fmtInterceptHead(netSelIntercept);
	    else if (netDetailTab === 0) content = fmtHeaders(r.reqHeaders);
	    else if (netDetailTab === 1) content = formatBodyForDisplay(r.reqBody, r.reqHeaders);
	    else if (netDetailTab === 2) {
	      if (pendingIntercept) {
	        content = '(请求已拦截，尚未发送)';
	      } else {
      var lines = fmtHeaders(r.respHeaders);
      if (r.status) lines = 'HTTP/1.1 ' + r.status + ' ' + (statusPhrase(r.status) || '') + '\n' + lines;
      content = lines;
    }
  } else if (netDetailTab === 3) {
    if (pendingIntercept) {
      content = '(请求已拦截，尚未发送)';
	      } else if (r.error) {
	        content = '── 请求失败 ──\n' + r.error + '\n\n原因: ' + shortError(r.error);
	      } else if (r.respBody != null) {
	        content = formatBodyForDisplay(r.respBody, r.respHeaders);
	      } else {
	        content = '(等待响应…)';
	      }
  } else if (pendingResp) {
    content = '响应拦截中，没有可关联的明文结果。';
  } else if (pendingIntercept) {
    content = '拦截中的请求还未发送，没有可关联的明文结果。';
	    } else {
	      content = fmtPlainCandidates(r);
	    }
	    netDetailBody.value = content;
	    netDetailDirty = false;
	    if (netDetailSearchText) runDetailSearch();
	  }

function updateDetailActionButtons(pendingIntercept, pendingResp) {
  if (!netDetailActs) return;
  var replay = netDetailActs.querySelector('#bh-act-replay');
  var bp = netDetailActs.querySelector('#bh-act-bp');
  if (replay) replay.textContent = pendingResp ? '放行' : (pendingIntercept ? '发送' : '重放');
  if (bp) bp.textContent = pendingResp ? '中止' : (pendingIntercept ? '中止' : '设断点');
}

function setNetEditing(active) {
  netEditing = !!active;
}

// 点按详情 textarea：可编辑的 tab 打开 light DOM 编辑层输入，写回后刷新展示。
// 可编辑范围：拦截请求的 请求头(0)/请求体(1)；普通请求的 请求体(1)。
function openDetailEditor() {
  if (!netDetailBody) return;
  // 同一次点按可能触发多个事件（touchend + mouseup + click），编辑层已开就忽略后续，避免重开闪烁。
  if (netEditOverlay) return;
  // 响应拦截：可编辑 响应头(2)/响应体(3)；请求/请求拦截：可编辑 请求头(0)/请求体(1)
  var editable = netSelRespIntercept
    ? (netDetailTab === 2 || netDetailTab === 3)
    : (netDetailTab === 0 || netDetailTab === 1);
  if (!editable) return;
  var titles = ['编辑请求头', '编辑请求体', '编辑响应头', '编辑响应体', ''];
  var title = titles[netDetailTab] || '编辑';
  openEditOverlay(title, netDetailBody.value, function (text) {
    // 把编辑结果塞进只读 textarea，复用既有的写回逻辑（它从 netDetailBody.value 读取）
    netDetailBody.value = text;
    netDetailDirty = true;
    syncCurrentDetailEdit();
    renderDetail(true);
  });
}

// ── 功能：重放 ──
function replayReq(r, tag) {
  var init = { method: r.method, headers: r.reqHeaders };
  if (r.reqBody) init.body = r.reqBody;
  fetch(r.url, init).then(function (resp) {
    var entry = {
      reqId: Math.random().toString(36).slice(2),
      url: r.url, method: r.method,
      reqHeaders: r.reqHeaders, reqBody: r.reqBody,
      status: resp.status, respHeaders: {}, respBody: null,
      duration: null, error: null, ts: Date.now(), tag: tag || '重放',
    };
    resp.headers.forEach(function (v, k) { entry.respHeaders[k] = v; });
    resp.clone().text().then(function (body) {
      entry.respBody = body.slice(0, 102400);
      entry.duration = 0;
      netRequests.unshift(entry);
      if (netRequests.length > 200) netRequests.length = 200;
      maybeFocusPayload(entry);
      if (netPanelVisible) renderNetList();
      if (netPanelVisible) renderDetail();
    });
  }).catch(function (err) {
    var entry = {
      reqId: Math.random().toString(36).slice(2),
      url: r.url, method: r.method,
      reqHeaders: r.reqHeaders, reqBody: r.reqBody,
      status: 0, error: String(err), ts: Date.now(), tag: tag || '重放',
    };
    netRequests.unshift(entry);
    maybeFocusPayload(entry);
    if (netPanelVisible) renderNetList();
    if (netPanelVisible) renderDetail();
  });
}

// 按钮点击后短暂显示反馈文字，再恢复原文字
function flashBtn(btn, msg) {
  if (!btn) return;
  if (btn._flashT) { clearTimeout(btn._flashT); }
  else { btn._orig = btn.textContent; }
  btn.textContent = msg;
  btn._flashT = setTimeout(function () {
    btn.textContent = btn._orig;
    btn._flashT = null;
  }, 1000);
}

// ── 功能：复制 curl ──
function toCurl(r) {
  var cmd = "curl -X " + r.method + " '" + r.url + "'";
  Object.keys(r.reqHeaders || {}).forEach(function (k) {
    cmd += " \\\n  -H '" + k + ": " + r.reqHeaders[k] + "'";
  });
  if (r.reqBody) cmd += " \\\n  --data '" + r.reqBody + "'";
  return cmd;
}

// ── 功能：导出 HAR ──
function exportHAR() {
  var entries = netRequests.map(function (r) {
    return {
      startedDateTime: new Date(r.ts).toISOString(),
      time: r.duration || 0,
      request: {
        method: r.method, url: r.url, httpVersion: 'HTTP/1.1',
        headers: Object.keys(r.reqHeaders || {}).map(function (k) { return { name: k, value: r.reqHeaders[k] }; }),
        queryString: [], cookies: [],
        bodySize: r.reqBody ? r.reqBody.length : -1,
        postData: r.reqBody ? { mimeType: '', text: r.reqBody } : undefined,
      },
      response: {
        status: r.status || 0, statusText: '',
        httpVersion: 'HTTP/1.1',
        headers: Object.keys(r.respHeaders || {}).map(function (k) { return { name: k, value: r.respHeaders[k] }; }),
        cookies: [], redirectURL: '',
        bodySize: r.respBody ? r.respBody.length : -1,
        content: { size: r.respBody ? r.respBody.length : 0, mimeType: '', text: r.respBody || '' },
      },
      cache: {}, timings: { send: 0, wait: r.duration || 0, receive: 0 },
    };
  });
  var har = JSON.stringify({ log: { version: '1.2', creator: { name: 'BrowserHelper-Gecko', version: '1.0' }, entries: entries } }, null, 2);
  var a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([har], { type: 'application/json' }));
  a.download = 'capture.har';
  a.click();
}

// ── 弹窗工具 ──
function openModal(title, bodyHtml, onOk) {
  closeModal();
  var el = document.createElement('div');
  el.id = 'bh-modal';
  el.innerHTML = '<div id="bh-modal-box">' +
    '<div id="bh-modal-title">' + escHtml(title) + '</div>' +
    '<div id="bh-modal-body">' + bodyHtml + '</div>' +
    '<div id="bh-modal-acts">' +
      '<button id="bh-btn-cancel">取消</button>' +
      '<button id="bh-btn-ok">确认</button>' +
    '</div></div>';
	    el.querySelector('#bh-btn-cancel').addEventListener('click', closeModal);
	    el.querySelector('#bh-btn-ok').addEventListener('click', function () { onOk(el); });
	    el.addEventListener('click', function (e) { if (e.target === el) closeModal(); });
	    var box = el.querySelector('#bh-modal-box');
	    if (box) {
	      // IME/input 事件在 capture 阶段阻止冒泡（防止 eruda 干扰输入）
	      ['keydown','keyup','input','beforeinput','compositionstart','compositionupdate','compositionend'].forEach(function (type) {
	        box.addEventListener(type, function (e) { e.stopPropagation(); }, true);
	      });
	      // click/pointer 在 bubble 阶段阻止（capture 阶段不阻止，否则子按钮收不到 click）
	      ['pointerdown','pointerup','mousedown','mouseup','click'].forEach(function (type) {
	        box.addEventListener(type, function (e) { e.stopPropagation(); }, false);
	      });
	      box.addEventListener('dblclick', function (e) {
	        if (e.target && /^(INPUT|TEXTAREA)$/i.test(e.target.tagName || '')) {
	          e.preventDefault();
	          e.stopPropagation();
	          try { e.target.focus(); } catch (err) {}
	        }
	      }, true);
	    }
	    // 关键：弹窗必须挂进 #bh-net（在 eruda 的 shadow root 内），原因：
  //   1. #bh-modal 的 CSS 写在 NET_STYLE 里，只对 shadow root 内的元素生效，
  //      挂 document.body 会完全没样式；
  //   2. eruda 面板 z-index=9999999，挂 document.body 的弹窗会被它整个盖住，
  //      表现为"点了按钮没反应"（其实弹窗开了但不可见/不可点）。
  // 挂进 #bh-net 后弹窗与面板同处一个堆叠上下文，position:fixed 覆盖视口即可见可点。
  (netPanel || document.body).appendChild(el);
  netModal = el;
  // shadow root 内的输入框在 Android 软键盘下 IME 合成会崩坏（Gecko bug），
  // 因此弹窗内所有 input/textarea 设为只读，点按时改用 light DOM 编辑层输入。
  bindModalFieldEditors(el);
}

// 把弹窗里的 input/textarea 改成"点按→light DOM 编辑层"模式，绕过 shadow-DOM IME bug。
function bindModalFieldEditors(modalEl) {
  var fields = modalEl.querySelectorAll('input, textarea');
  Array.prototype.forEach.call(fields, function (f) {
    var isNumber = (f.tagName === 'INPUT' && f.getAttribute('type') === 'number');
    f.readOnly = true;
    f.addEventListener('click', function () {
      var label = f.getAttribute('placeholder') || '编辑';
      // 找最近的 <label> 文案当标题
      var prev = f.previousElementSibling;
      if (prev && prev.tagName === 'LABEL' && prev.textContent) label = prev.textContent;
      openEditOverlay(label, f.value, function (text) {
        f.value = isNumber ? text.replace(/[^0-9.\-]/g, '') : text;
      });
    });
  });
}

	  function closeModal() {
	    if (netModal) { try { netModal.remove(); } catch (e) {} netModal = null; }
	  }

	  // ── 独立编辑层（light DOM，绕过 Gecko shadow-DOM IME bug）──
	  // Gecko 已知 bug：shadow root 内的可编辑元素在 Android 软键盘下 IME 合成会崩坏
	  // （只进首字符、后续字符穿到页面输入框）。eruda 面板在 shadow root 内，因此把真正
	  // 接收输入的 textarea 放到 document.body（shadow root 外），编辑完写回。
	  var netEditOverlay = null;     // 编辑层根（在 document.body）
	  var netEditTextarea = null;    // 真正接收键盘输入的 textarea（light DOM）
	  var netEditOnDone = null;      // 完成回调，参数为编辑后的文本

	  function ensureEditOverlayStyle() {
	    if (document.getElementById('bh-edit-overlay-style')) return;
	    var st = document.createElement('style');
	    st.id = 'bh-edit-overlay-style';
	    st.textContent = [
	      '#bh-edit-overlay{position:fixed;inset:0;z-index:2147483647;display:flex;flex-direction:column;',
	      '  background:#fff;color:#111;font-family:sans-serif;}',
	      '#bh-edit-overlay-bar{display:flex;align-items:center;gap:8px;padding:8px 10px;flex:0 0 auto;',
	      '  border-bottom:1px solid #d0d7de;background:#f6f8fa;}',
	      '#bh-edit-overlay-title{flex:1 1 auto;font-size:14px;font-weight:700;overflow:hidden;',
	      '  text-overflow:ellipsis;white-space:nowrap;}',
	      '#bh-edit-overlay-bar button{font-size:14px;padding:8px 14px;border-radius:6px;min-height:40px;',
	      '  border:1px solid #d0d7de;background:#fff;color:#111;cursor:pointer;}',
	      '#bh-edit-overlay-ok{background:#2563eb !important;color:#fff !important;border-color:#2563eb !important;}',
	      '#bh-edit-overlay-textarea{flex:1 1 auto;min-height:0;width:100%;padding:10px;font-size:14px;',
	      '  color:#111;background:#fff;border:none;outline:none;resize:none;line-height:1.5;',
	      '  white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;font-family:monospace;}',
	      // 搜索栏
	      '#bh-eo-search{display:flex;align-items:center;gap:6px;padding:4px 10px;flex:0 0 auto;',
	      '  border-bottom:1px solid #d0d7de;background:#f6f8fa;}',
	      '#bh-eo-sinput{flex:1;font-size:13px;padding:5px 8px;border-radius:4px;min-height:32px;',
	      '  border:1px solid #d0d7de;background:#fff;color:#111;font-family:monospace;}',
	      '#bh-eo-scount{font-size:12px;color:#6b7280;white-space:nowrap;min-width:32px;text-align:right;}',
	      '#bh-eo-snav button{font-size:16px;padding:2px 8px;border-radius:4px;min-height:32px;',
	      '  border:1px solid #d0d7de;background:#fff;color:#111;cursor:pointer;}',
	      '#bh-eo-snav button:active{background:#e8eaed;}',
	      // 批量替换栏
	      '#bh-eo-replace{display:flex;align-items:center;gap:6px;padding:4px 10px;flex:0 0 auto;',
	      '  border-bottom:1px solid #d0d7de;background:#f6f8fa;}',
	      '#bh-eo-rfrom,#bh-eo-rto{flex:1;font-size:13px;padding:5px 8px;border-radius:4px;min-height:32px;',
	      '  border:1px solid #d0d7de;background:#fff;color:#111;font-family:monospace;min-width:0;}',
	      '#bh-eo-rdo{font-size:13px;padding:5px 10px;border-radius:4px;min-height:32px;white-space:nowrap;',
	      '  border:1px solid #2563eb;background:#2563eb;color:#fff;cursor:pointer;flex:0 0 auto;}',
	      '#bh-eo-rdo:active{background:#1d4ed8;}',
	    ].join('');
	    (document.head || document.documentElement).appendChild(st);
	  }

	  // 打开编辑层。title 显示用，initial 是初始文本，onDone(text) 在"完成"时回调。
	  function openEditOverlay(title, initial, onDone) {
	    closeEditOverlay();
	    ensureEditOverlayStyle();
	    var el = document.createElement('div');
	    el.id = 'bh-edit-overlay';
	    el.innerHTML =
	      '<div id="bh-edit-overlay-bar">' +
	        '<button id="bh-edit-overlay-cancel" type="button">取消</button>' +
	        '<span id="bh-edit-overlay-title"></span>' +
	        '<button id="bh-edit-overlay-ok" type="button">完成</button>' +
	      '</div>' +
	      '<div id="bh-eo-search">' +
	        '<input id="bh-eo-sinput" placeholder="搜索…" autocomplete="off" spellcheck="false">' +
	        '<span id="bh-eo-scount"></span>' +
	        '<span id="bh-eo-snav">' +
	          '<button id="bh-eo-sprev" type="button">↑</button>' +
	          '<button id="bh-eo-snext" type="button">↓</button>' +
	        '</span>' +
	      '</div>' +
	      '<div id="bh-eo-replace">' +
	        '<input id="bh-eo-rfrom" placeholder="查找" autocomplete="off" spellcheck="false">' +
	        '<input id="bh-eo-rto" placeholder="替换为（空=删除）" autocomplete="off" spellcheck="false">' +
	        '<button id="bh-eo-rdo" type="button">替换</button>' +
	      '</div>' +
	      '<textarea id="bh-edit-overlay-textarea" spellcheck="false" wrap="soft"></textarea>';
	    document.body.appendChild(el);
	    el.querySelector('#bh-edit-overlay-title').textContent = title || '编辑';
	    var ta = el.querySelector('#bh-edit-overlay-textarea');
	    ta.value = initial == null ? '' : String(initial);
	    netEditOverlay = el;
	    netEditTextarea = ta;
	    netEditOnDone = onDone || null;

	    // ── 搜索逻辑 ──
	    var eoMatches = [], eoIdx = 0;
	    var sinput = el.querySelector('#bh-eo-sinput');
	    var scount = el.querySelector('#bh-eo-scount');
	    function eoRunSearch() {
	      eoMatches = [];
	      var q = sinput.value;
	      var body = ta.value;
	      if (q && body) {
	        var lower = body.toLowerCase(), lq = q.toLowerCase(), pos = 0;
	        while (true) {
	          var idx = lower.indexOf(lq, pos);
	          if (idx === -1) break;
	          eoMatches.push({ start: idx, end: idx + q.length });
	          pos = idx + 1;
	          if (eoMatches.length > 500) break;
	        }
	      }
	      eoIdx = 0;
	      eoUpdateSearch();
	    }
	    function eoUpdateSearch() {
	      var n = eoMatches.length;
	      scount.textContent = sinput.value ? (n ? (eoIdx + 1) + '/' + n : '无匹配') : '';
	      if (n > 0) {
	        var m = eoMatches[eoIdx];
	        try { ta.focus(); ta.setSelectionRange(m.start, m.end); } catch (e) {}
	      }
	    }
	    function eoStep(dir) {
	      if (!eoMatches.length) return;
	      eoIdx = (eoIdx + dir + eoMatches.length) % eoMatches.length;
	      eoUpdateSearch();
	    }
	    sinput.addEventListener('input', eoRunSearch);
	    el.querySelector('#bh-eo-sprev').addEventListener('click', function () { eoStep(-1); });
	    el.querySelector('#bh-eo-snext').addEventListener('click', function () { eoStep(1); });

	    // ── 批量替换逻辑 ──
	    var rfrom = el.querySelector('#bh-eo-rfrom');
	    var rto = el.querySelector('#bh-eo-rto');
	    el.querySelector('#bh-eo-rdo').addEventListener('click', function () {
	      var from = rfrom.value;
	      if (!from) return;
	      var to = rto.value;
	      var text = ta.value;
	      var out = '', idx = 0, count = 0;
	      while (true) {
	        var pos = text.indexOf(from, idx);
	        if (pos === -1) { out += text.slice(idx); break; }
	        out += text.slice(idx, pos) + to;
	        idx = pos + from.length;
	        count++;
	      }
	      if (count > 0) {
	        ta.value = out;
	        // 搜索词同步更新
	        if (sinput.value) eoRunSearch();
	      }
	    });

	    el.querySelector('#bh-edit-overlay-cancel').addEventListener('click', function () {
	      closeEditOverlay();
	    });
	    el.querySelector('#bh-edit-overlay-ok').addEventListener('click', function () {
	      var v = netEditTextarea ? netEditTextarea.value : '';
	      var cb = netEditOnDone;
	      closeEditOverlay();
	      if (cb) cb(v);
	    });
	    // 聚焦 textarea 并把光标放到末尾，弹出软键盘
	    setTimeout(function () {
	      try { ta.focus(); ta.setSelectionRange(ta.value.length, ta.value.length); } catch (e) {}
	    }, 30);
	  }

	  function closeEditOverlay() {
	    if (netEditOverlay) { try { netEditOverlay.remove(); } catch (e) {} }
	    netEditOverlay = null;
	    netEditTextarea = null;
	    netEditOnDone = null;
	  }

	  // ── 详情搜索 ──
	  function runDetailSearch() {
	    netDetailSearchMatches = [];
	    netDetailSearchIdx = 0;
	    var text = netDetailSearchText;
	    var body = netDetailBody ? netDetailBody.value : '';
	    if (text && body) {
	      var lower = body.toLowerCase();
	      var lowerText = text.toLowerCase();
	      var pos = 0;
	      while (true) {
	        var idx = lower.indexOf(lowerText, pos);
	        if (idx === -1) break;
	        netDetailSearchMatches.push({ start: idx, end: idx + text.length });
	        pos = idx + 1;
	        if (netDetailSearchMatches.length > 500) break; // 防爆
	      }
	    }
	    updateDetailSearchUI();
	    scrollToCurrentMatch();
	  }

	  function stepDetailSearch(dir) {
	    if (!netDetailSearchMatches.length) return;
	    netDetailSearchIdx = (netDetailSearchIdx + dir + netDetailSearchMatches.length) % netDetailSearchMatches.length;
	    updateDetailSearchUI();
	    scrollToCurrentMatch();
	  }

	  function updateDetailSearchUI() {
	    if (!netDetailEl) return;
	    var countEl = netDetailEl.querySelector('#bh-dsearch-count');
	    var n = netDetailSearchMatches.length;
	    if (countEl) {
	      if (!netDetailSearchText) countEl.textContent = '';
	      else if (!n) countEl.textContent = '无匹配';
	      else countEl.textContent = (netDetailSearchIdx + 1) + '/' + n;
	    }
	    // 用 textarea 的 setSelectionRange 定位到当前匹配（无法高亮，但可滚动+选中）
	    if (netDetailBody && n > 0) {
	      var m = netDetailSearchMatches[netDetailSearchIdx];
	      try {
	        netDetailBody.focus();
	        netDetailBody.setSelectionRange(m.start, m.end);
	      } catch (e) {}
	    }
	  }

	  function scrollToCurrentMatch() {
	    // textarea 没有原生高亮，setSelectionRange 已经定位；不需要额外滚动逻辑
	  }

	  function clearDetailSearch() {
	    netDetailSearchText = '';
	    netDetailSearchMatches = [];
	    netDetailSearchIdx = 0;
	    if (netDetailEl) {
	      var dsearchEl = netDetailEl.querySelector('#bh-dsearch');
	      if (dsearchEl) dsearchEl.value = '';
	      var countEl = netDetailEl.querySelector('#bh-dsearch-count');
	      if (countEl) countEl.textContent = '';
	    }
	  }

	  function bindLongPress(el, fn) {
	    if (!el || el.__bhLongPressBound) return;
	    el.__bhLongPressBound = true;
	    var timer = null;
	    var swallowClick = false;
	    var startX = 0, startY = 0;
	    var pointerMode = false;
	    function pointFromEvent(e) {
	      var t = (e.touches && e.touches[0]) || (e.changedTouches && e.changedTouches[0]) || e;
	      return { x: t.clientX || 0, y: t.clientY || 0 };
	    }
	    function clearTimer() {
	      if (timer) { clearTimeout(timer); timer = null; }
	    }
	    function start(e) {
	      var p = pointFromEvent(e);
	      startX = p.x; startY = p.y;
	      clearTimer();
	      timer = setTimeout(function () {
	        timer = null;
	        swallowClick = true;
	        try { e.preventDefault(); e.stopPropagation(); } catch (err) {}
	        fn(e);
	      }, 650);
	    }
	    function move(e) {
	      if (!timer) return;
	      var p = pointFromEvent(e);
	      if (Math.abs(p.x - startX) > 12 || Math.abs(p.y - startY) > 12) clearTimer();
	    }
	    function end() { clearTimer(); }
	    el.addEventListener('pointerdown', function (e) { pointerMode = true; start(e); });
	    el.addEventListener('pointermove', function (e) { pointerMode = true; move(e); });
	    el.addEventListener('pointerup', function () { pointerMode = true; end(); });
	    el.addEventListener('pointercancel', function () { pointerMode = true; end(); });
	    el.addEventListener('touchstart', function (e) { if (!pointerMode) start(e); }, { passive: false });
	    el.addEventListener('touchmove', function (e) { if (!pointerMode) move(e); }, { passive: true });
	    el.addEventListener('touchend', function () { if (!pointerMode) end(); });
	    el.addEventListener('click', function (e) {
	      if (!swallowClick) return;
	      swallowClick = false;
	      e.preventDefault();
	      e.stopImmediatePropagation();
	    }, true);
	    el.addEventListener('contextmenu', function (e) { e.preventDefault(); });
	  }

	  function requestLabel(r) {
	    var u = normalizeRuleUrl(r && r.url);
	    return (String((r && r.method) || 'GET').toUpperCase()) + ' ' +
	      (u.host ? u.host : '') + (u.path || '');
	  }

	  function openMarkMenu(r) {
	    if (!r) return;
	    var existingRule = findRuleForReq(r);
	    var respOn = !!(existingRule && existingRule.interceptResp);
	    openModal('标记请求',
	      '<div style="font-size:12px;color:#555;word-break:break-all;">' + escHtml(requestLabel(r)) + '</div>' +
	      '<button data-mark="pass" style="padding:10px;border:1px solid #d0d7de;border-radius:6px;background:#fff;text-align:left;">标记为放行</button>' +
	      '<button data-mark="intercept" style="padding:10px;border:1px solid #d0d7de;border-radius:6px;background:#fff;text-align:left;">标记为拦截</button>' +
	      '<button data-mark="resp" style="padding:10px;border:1px solid #d0d7de;border-radius:6px;background:#fff;text-align:left;">' +
	        (respOn ? '取消拦截服务器响应' : '拦截服务器响应') + '</button>',
	      function () { closeModal(); }
	    );
	    setTimeout(function () {
	      if (!netModal) return;
	      var ok = netModal.querySelector('#bh-btn-ok');
	      if (ok) ok.style.display = 'none';
	      Array.prototype.forEach.call(netModal.querySelectorAll('[data-mark]'), function (btn) {
	        btn.addEventListener('click', function () {
	          var action = btn.getAttribute('data-mark');
	          if (action === 'resp') toggleRespRuleForReq(r);
	          else upsertInterceptRuleFromReq(r, action);
	          closeModal();
	          renderNetList();
	        });
	      });
	    }, 20);
	  }

	  function openRuleActionMenu(rule) {
	    if (!rule) return;
	    openModal('修改标记',
	      '<div style="font-size:12px;color:#555;word-break:break-all;">' +
	        escHtml((rule.method || 'GET') + ' ' + (rule.host || '') + (rule.path || '')) +
	      '</div>' +
	      '<button data-rule-act="pass" style="padding:10px;border:1px solid #d0d7de;border-radius:6px;background:#fff;text-align:left;">改为放行</button>' +
	      '<button data-rule-act="intercept" style="padding:10px;border:1px solid #d0d7de;border-radius:6px;background:#fff;text-align:left;">改为拦截</button>' +
	      '<button data-rule-act="resp" style="padding:10px;border:1px solid #d0d7de;border-radius:6px;background:#fff;text-align:left;">' +
	        (rule.interceptResp ? '拦截服务器响应：开 → 关' : '拦截服务器响应：关 → 开') + '</button>' +
	      '<button data-rule-act="clear" style="padding:10px;border:1px solid #fecaca;border-radius:6px;background:#fff5f5;color:#dc2626;text-align:left;">清除标记</button>',
	      function () { closeModal(); }
	    );
	    setTimeout(function () {
	      if (!netModal) return;
	      var ok = netModal.querySelector('#bh-btn-ok');
	      if (ok) ok.style.display = 'none';
	      Array.prototype.forEach.call(netModal.querySelectorAll('[data-rule-act]'), function (btn) {
	        btn.addEventListener('click', function () {
	          var act = btn.getAttribute('data-rule-act');
	          if (act === 'clear') {
	            removeInterceptRule(rule.id);
	          } else if (act === 'resp') {
	            netInterceptRules = sanitizeInterceptRules(netInterceptRules).map(function (r) {
	              if (r.id !== rule.id) return r;
	              r.interceptResp = !r.interceptResp;
	              r.updatedAt = Date.now();
	              return r;
	            });
	            saveInterceptRules();
	          } else {
	            netInterceptRules = sanitizeInterceptRules(netInterceptRules).map(function (r) {
	              if (r.id !== rule.id) return r;
	              r.action = act === 'intercept' ? 'intercept' : 'pass';
	              r.updatedAt = Date.now();
	              return r;
	            });
	            saveInterceptRules();
	          }
	          closeModal();
	          renderNetList();
	        });
	      });
	    }, 20);
	  }

	  function renderRulesView() {
	    if (!netRulesViewEl) return;
	    var rules = sanitizeInterceptRules(netInterceptRules).slice().sort(function (a, b) {
	      return (b.updatedAt || b.createdAt || 0) - (a.updatedAt || a.createdAt || 0);
	    });
	    var rows = rules.map(function (r) {
	      var meta = (r.host || '(无域名)') + ' · ' + (r.hasBody ? '有正文' : '无正文') +
	        (r.interceptResp ? ' · 拦响应' : '') + ' · ' +
	        new Date(r.updatedAt || r.createdAt || Date.now()).toLocaleString();
	      return '<div class="bh-rule-row" data-rule-id="' + escHtml(r.id) + '">' +
	        '<span class="bh-rule-action ' + (r.action === 'intercept' ? 'intercept' : 'pass') + '">' +
	          escHtml(ruleActionLabel(r.action)) + '</span>' +
	        '<span class="bh-rule-main">' +
	          '<span class="bh-rule-url">' + escHtml((r.method || 'GET') + ' ' + (r.path || r.sampleUrl || '/')) + '</span>' +
	          '<span class="bh-rule-meta">' + escHtml(meta) + '</span>' +
	        '</span>' +
	      '</div>';
	    }).join('');
	    netRulesViewEl.innerHTML =
	      '<div id="bh-rules-head">' +
	        '<div id="bh-rules-title">标记的请求 ' + rules.length + '</div>' +
	        '<button id="bh-rules-close" type="button" aria-label="关闭标记">×</button>' +
	      '</div>' +
	      '<div id="bh-rules-list">' + (rows || '<div class="bh-rule-empty">暂无标记</div>') + '</div>';
	    var close = netRulesViewEl.querySelector('#bh-rules-close');
	    if (close) close.addEventListener('click', closeRulesView);
	    Array.prototype.forEach.call(netRulesViewEl.querySelectorAll('.bh-rule-row'), function (el) {
	      var id = el.getAttribute('data-rule-id');
	      var rule = rules.find(function (r) { return r.id === id; });
	      bindLongPress(el, function () { openRuleActionMenu(rule); });
	    });
	    updateRulesBtn();
	  }

	  function openRulesView() {
	    if (!netRulesViewEl) return;
	    renderRulesView();
	    netRulesViewEl.classList.add('open');
	  }

	  function closeRulesView() {
	    if (netRulesViewEl) netRulesViewEl.classList.remove('open');
	  }
