// Toolbar buttons, filters, mock rules, throttle controls, and panel construction.
function openMockModal() {
  var listHtml = netMockRules.map(function (rule, i) {
    return '<div style="display:flex;gap:6px;align-items:center;margin-bottom:4px;">' +
      '<span style="flex:1;font-size:12px;font-family:monospace;">' + escHtml(rule.pattern) + ' → ' + rule.status + '</span>' +
      '<button data-mi="' + i + '" style="font-size:11px;padding:2px 6px;">删除</button></div>';
  }).join('') || '<div style="color:#888;font-size:12px;">暂无 Mock 规则</div>';
  openModal('Mock 自动响应',
    listHtml +
    '<label style="margin-top:8px;">URL 关键词</label><input id="bh-mock-pattern" placeholder="如: /api/user">' +
    '<label>响应状态码</label><input id="bh-mock-status" value="200">' +
    '<label>响应体</label><textarea id="bh-mock-body" placeholder=\'{"code":0}\'></textarea>',
    function (el) {
      var pattern = el.querySelector('#bh-mock-pattern').value.trim();
      var status = parseInt(el.querySelector('#bh-mock-status').value) || 200;
      var body = el.querySelector('#bh-mock-body').value;
      if (pattern) {
        netMockRules.push({ pattern: pattern, status: status, headers: {}, body: body });
        // 同步到 page world
        injectMockRules();
      }
      closeModal();
    }
  );
  setTimeout(function () {
    if (!netModal) return;
    Array.prototype.forEach.call(netModal.querySelectorAll('[data-mi]'), function (btn) {
      btn.addEventListener('click', function () {
        var i = parseInt(btn.getAttribute('data-mi'));
        netMockRules.splice(i, 1);
        injectMockRules();
        openMockModal();
      });
    });
  }, 50);
}

// 拦截配置弹窗（长按"拦截"按钮打开）：全局勾选拦截发出去的请求 / 拦截服务器响应。
function openInterceptConfigModal() {
  function row(label, on, act) {
    return '<button data-icfg="' + act + '" style="display:flex;align-items:center;gap:8px;width:100%;text-align:left;font-size:13px;padding:8px 6px;margin-bottom:4px;background:none;border:1px solid #444;border-radius:4px;color:inherit;">' +
      '<span style="font-size:15px;">' + (on ? '☑' : '☐') + '</span>' +
      '<span>' + escHtml(label) + '</span></button>';
  }
  var hint = netInterceptMaster
    ? '拦截功能已开启。以下为作用域：勾选项决定拦哪些。'
    : '以下为作用域设置，不会开启拦截。设好后点工具栏"拦截"按钮才生效。';
  openModal('拦截配置',
    row('拦截发出去的请求', netScopeReq, 'req') +
    row('拦截服务器响应', netScopeResp, 'resp') +
    row('拦截噪音包（心跳/遥测等）', netScopeNoise, 'noise') +
    row('过滤遥测ACK（204/空体等压制响应）', netScopeFilterSuppressResp, 'suppress') +
    '<div style="color:#888;font-size:11px;margin-top:6px;">' + escHtml(hint) + '默认过滤心跳/遥测等噪音包，勾选"拦截噪音包"可一并拦截。命中后在面板里手动放行。</div>',
    function () { closeModal(); }
  );
  setTimeout(function () {
    if (!netModal) return;
    Array.prototype.forEach.call(netModal.querySelectorAll('[data-icfg]'), function (btn) {
      btn.addEventListener('click', function () {
        var act = btn.getAttribute('data-icfg');
        if (act === 'req') {
          netScopeReq = !netScopeReq;
        } else if (act === 'resp') {
          netScopeResp = !netScopeResp;
        } else if (act === 'noise') {
          netScopeNoise = !netScopeNoise;
        } else if (act === 'suppress') {
          netScopeFilterSuppressResp = !netScopeFilterSuppressResp;
          syncFilterSuppressResp();
          saveNetConfig();
          openInterceptConfigModal();
          return;
        }
        recomputeIntercept();
        // 主开关开启时，关掉某作用域要释放对应已入队的拦截
        if (!netGlobalInterceptEnabled && netInterceptQueue.length) releaseAllIntercepts();
        if (!netGlobalRespInterceptEnabled && netRespInterceptQueue.length) releaseAllRespIntercepts();
        saveNetConfig();
        updateInterceptBtn();
        renderNetList();
        openInterceptConfigModal();
      });
    });
  }, 50);
}

function injectMockRules() {
  var rulesJson = JSON.stringify(netMockRules);
  // isolated 模式（强 CSP）：runInPage 的 blob <script> 可能被 CSP 拦截，
  // 直接通过 wrappedJSObject + cloneInto 写到 page world，确保拦截器能读到。
  if (erudaMode !== 'page' && typeof window.wrappedJSObject !== 'undefined' && typeof cloneInto !== 'undefined') {
    try { window.wrappedJSObject.__bhMockRules = cloneInto(netMockRules, window); return; } catch (e) {}
  }
  runInPage('(function(){window.__bhMockRules=' + rulesJson + ';})();');
}

function injectBreakpoints() {
  if (erudaMode !== 'page' && typeof window.wrappedJSObject !== 'undefined' && typeof cloneInto !== 'undefined') {
    try { window.wrappedJSObject.__bhBreakpoints = cloneInto(netBreakpoints, window); return; } catch (e) {}
  }
  runInPage('(function(){window.__bhBreakpoints=' + JSON.stringify(netBreakpoints) + ';})();');
}

function bpStageLabel(s) { return s === 'resp' ? '响应' : (s === 'both' ? '请求+响应' : '请求'); }

function openBreakpointModal() {
  var listHtml = netBreakpoints.map(function (bp, i) {
    return '<div style="display:flex;gap:6px;align-items:center;margin-bottom:4px;">' +
      '<span style="flex:1;font-size:12px;font-family:monospace;">' + escHtml(bp.pattern) + '</span>' +
      '<span style="font-size:10px;color:#8ab4f8;">' + bpStageLabel(bp.stage) + '</span>' +
      '<button data-bpi="' + i + '" style="font-size:11px;padding:2px 6px;">删除</button></div>';
  }).join('') || '<div style="color:#888;font-size:12px;">暂无断点</div>';
  openModal('断点管理',
    listHtml +
    '<label style="margin-top:8px;">新增断点 URL 关键词</label>' +
    '<input id="bh-bp-input" placeholder="如: api.example.com/login">' +
    '<label style="margin-top:8px;">断点阶段</label>' +
    '<div id="bh-bp-stage" data-sel="req" style="display:flex;gap:6px;">' +
      '<button type="button" data-stage="req" style="flex:1;padding:4px;">请求</button>' +
      '<button type="button" data-stage="resp" style="flex:1;padding:4px;">响应</button>' +
      '<button type="button" data-stage="both" style="flex:1;padding:4px;">请求+响应</button>' +
    '</div>',
    function (el) {
      var val = el.querySelector('#bh-bp-input').value.trim();
      var stageEl = el.querySelector('#bh-bp-stage');
      var stage = stageEl ? (stageEl.getAttribute('data-sel') || 'req') : 'req';
      if (val) netBreakpoints.push({ pattern: val, id: Date.now(), stage: stage });
      injectBreakpoints();
      closeModal();
      renderNetList();
    }
  );
  setTimeout(function () {
    if (!netModal) return;
    Array.prototype.forEach.call(netModal.querySelectorAll('[data-bpi]'), function (btn) {
      btn.addEventListener('click', function () {
        var i = parseInt(btn.getAttribute('data-bpi'));
        netBreakpoints.splice(i, 1);
        injectBreakpoints();
        openBreakpointModal();
      });
    });
    var stageWrap = netModal.querySelector('#bh-bp-stage');
    if (stageWrap) {
      var stageBtns = stageWrap.querySelectorAll('[data-stage]');
      var paintStage = function () {
        var sel = stageWrap.getAttribute('data-sel');
        Array.prototype.forEach.call(stageBtns, function (b) {
          var on = b.getAttribute('data-stage') === sel;
          b.style.background = on ? '#1a73e8' : '';
          b.style.color = on ? '#fff' : '';
        });
      };
      Array.prototype.forEach.call(stageBtns, function (b) {
        b.addEventListener('click', function () {
          stageWrap.setAttribute('data-sel', b.getAttribute('data-stage'));
          paintStage();
        });
      });
      paintStage();
    }
  }, 50);
}

// ── 功能：弱网模拟 ──
var THROTTLE_PRESETS = [
  { label: '关闭', latencyMs: 0, kbps: 0 },
  { label: '2G (300ms/50KB)', latencyMs: 300, kbps: 50 },
  { label: '3G (100ms/200KB)', latencyMs: 100, kbps: 200 },
  { label: '4G (20ms/1500KB)', latencyMs: 20, kbps: 1500 },
  { label: '自定义…', latencyMs: -1, kbps: -1 },
];

function applyThrottle(cfg) {
  if (cfg.latencyMs > 0 || cfg.kbps > 0) {
    netThrottle._last = { latencyMs: cfg.latencyMs, kbps: cfg.kbps };
  }
  netThrottle.latencyMs = cfg.latencyMs;
  netThrottle.kbps = cfg.kbps;
  netThrottle.enabled = cfg.latencyMs > 0 || cfg.kbps > 0;
  // isolated 模式直写 page world，避免 blob <script> 被强 CSP 拦截
  if (erudaMode !== 'page' && typeof window.wrappedJSObject !== 'undefined' && typeof cloneInto !== 'undefined') {
    try { window.wrappedJSObject.__bhThrottle = cloneInto(netThrottle, window); }
    catch (e) { runInPage('(function(){window.__bhThrottle=' + JSON.stringify(netThrottle) + ';})();'); }
  } else {
    runInPage('(function(){window.__bhThrottle=' + JSON.stringify(netThrottle) + ';})();');
  }
  updateThrottleBtn();
}

function updateThrottleBtn() {
  var btn = (netPanel && netPanel.querySelector('#bh-thr-btn')) || document.getElementById('bh-thr-btn');
  if (!btn) return;
  btn.textContent = (netThrottle.enabled ? '● ' : '○ ') + '弱网';
  btn.style.color = netThrottle.enabled ? '#ea580c' : '#888';
  updateExtraMenu();
}

function updateFilterButtons() {
  var filterBtn = netPanel && netPanel.querySelector('#bh-filter-menu-btn');
  var filterMenu = netPanel && netPanel.querySelector('#bh-filter-menu');
  var noiseBtn = netPanel && netPanel.querySelector('#bh-noise-btn');
  var payloadBtn = netPanel && netPanel.querySelector('#bh-payload-btn');
  var plainBtn = netPanel && netPanel.querySelector('#bh-plain-btn');
  var active = netHideTunnelNoise || netPayloadOnly || netPlainProbeEnabled;
  if (filterBtn) {
    filterBtn.textContent = '过滤 ▾';
    filterBtn.style.color = active ? '#2563eb' : '#111';
    filterBtn.style.borderColor = active ? '#93c5fd' : '#d0d7de';
  }
  if (filterMenu) {
    if (netFilterMenuOpen) filterMenu.classList.add('open');
    else filterMenu.classList.remove('open');
  }
  if (noiseBtn) {
    noiseBtn.textContent = (netHideTunnelNoise ? '● ' : '○ ') + '净化';
    noiseBtn.style.color = netHideTunnelNoise ? '#2563eb' : '#888';
  }
  if (payloadBtn) {
    payloadBtn.textContent = (netPayloadOnly ? '● ' : '○ ') + '正文';
    payloadBtn.style.color = netPayloadOnly ? '#16a34a' : '#888';
  }
  if (plainBtn) {
    plainBtn.textContent = (netPlainProbeEnabled ? '● ' : '○ ') + '明文';
    plainBtn.style.color = netPlainProbeEnabled ? '#dc2626' : '#888';
  }
}

	  function updateInterceptBtn() {
	    var btn = netPanel && netPanel.querySelector('#bh-intercept-btn');
	    if (!btn) return;
	    var total = netInterceptQueue.length + netRespInterceptQueue.length;
	    var suffix = total ? ' ' + total : '';
	    btn.textContent = (netInterceptMaster ? '● ' : '○ ') + '拦截' + suffix;
	    btn.style.color = netInterceptMaster ? '#dc2626' : '#888';
	  }

	  function updateRulesBtn() {
	    var btn = netPanel && netPanel.querySelector('#bh-rules-btn');
	    if (!btn) return;
	    var count = sanitizeInterceptRules(netInterceptRules).length;
	    btn.textContent = count ? ('标记 ' + count) : '标记';
	    btn.style.color = count ? '#2563eb' : '#111';
	    updateExtraMenu();
	  }

function updateExtraMenu() {
  var btn = netPanel && netPanel.querySelector('#bh-extra-btn');
  var menu = netPanel && netPanel.querySelector('#bh-extra-menu');
  if (menu) {
    if (netExtraMenuOpen) menu.classList.add('open');
    else menu.classList.remove('open');
  }
  if (btn) {
    var active = netThrottle.enabled || sanitizeInterceptRules(netInterceptRules).length;
    btn.style.color = active ? '#2563eb' : '#111';
    btn.style.borderColor = active ? '#93c5fd' : '#d0d7de';
  }
}

function updateReplaceBtn() {
  var btn = netPanel && netPanel.querySelector('#bh-replace-btn');
  if (!btn) return;
  var count = netReplaceRules.filter(function (r) { return r.enabled; }).length;
  var suffix = count ? ' ' + count : (netReplaceRules.length ? ' ' + netReplaceRules.length : '');
  btn.textContent = (netReplaceEnabled ? '● ' : '○ ') + '替换' + suffix;
  btn.style.color = netReplaceEnabled ? '#7c3aed' : '#888';
  syncReplaceRules();
}

function openThrottleCustom() {
  openModal('自定义弱网',
    '<label>延迟 (ms)</label><input id="bh-thr-lat" type="number" value="' + netThrottle.latencyMs + '">' +
    '<label>限速 (KB/s, 0=不限)</label><input id="bh-thr-kbps" type="number" value="' + netThrottle.kbps + '">',
    function (el) {
      var lat = parseInt(el.querySelector('#bh-thr-lat').value) || 0;
      var kbps = parseInt(el.querySelector('#bh-thr-kbps').value) || 0;
      closeModal();
      applyThrottle({ latencyMs: lat, kbps: kbps });
    }
  );
}

function openThrottleMenu() {
  var items = THROTTLE_PRESETS.map(function (p, i) {
    var active = netThrottle.latencyMs === p.latencyMs && netThrottle.kbps === p.kbps;
    return '<button style="display:block;width:100%;text-align:left;padding:10px 12px;' +
      'font-size:13px;border:none;border-bottom:1px solid #d0d7de;background:' +
      (active ? '#dbeafe' : '#fff') + ';cursor:pointer;" data-pi="' + i + '">' +
      (active ? '✓ ' : '') + p.label + '</button>';
  }).join('');
  openModal('弱网模拟', items, function () { closeModal(); });
  setTimeout(function () {
    if (!netModal) return;
    // 隐藏确认按钮（点预设即生效）
    var ok = netModal.querySelector('#bh-btn-ok');
    if (ok) ok.style.display = 'none';
    Array.prototype.forEach.call(netModal.querySelectorAll('[data-pi]'), function (btn) {
      btn.addEventListener('click', function () {
        var p = THROTTLE_PRESETS[parseInt(btn.getAttribute('data-pi'))];
        closeModal();
        if (p.latencyMs === -1) { openThrottleCustom(); return; }
        applyThrottle(p);
      });
    });
  }, 30);
}

// ── 构建面板根 DOM ──
function buildNetPanel() {
	    var wrap = document.createElement('div');
	    wrap.id = 'bh-net';
	    netPanel = wrap;
	    ['pointerdown','pointerup','touchstart','touchend','mousedown','mouseup','click','dblclick','contextmenu'].forEach(function (type) {
	      wrap.addEventListener(type, function (e) { e.stopPropagation(); }, false);
	    });

	    // 样式注入：eruda tool 面板在 shadow root 里渲染，document.head 的样式不生效。
  // 把 <style> 放到 wrap 内部，eruda 把 wrap 挂进 shadow root 时样式随之进入。
  var style = document.createElement('style');
  style.textContent = NET_STYLE;
  wrap.appendChild(style);

  // 顶部工具栏
  var bar = document.createElement('div');
  bar.id = 'bh-bar';
  bar.innerHTML =
    '<button id="bh-toggle">● 监听中</button>' +
    '<button id="bh-clear">清空</button>' +
    '<button id="bh-intercept-btn">○ 拦截</button>' +
    '<button id="bh-replace-btn">○ 替换</button>' +
    '<button id="bh-ext-btn">拓展</button>' +
    '<span id="bh-extra-wrap">' +
      '<button id="bh-extra-btn">额外 ▾</button>' +
      '<span id="bh-extra-menu">' +
        '<button id="bh-export-har">导出 HAR</button>' +
        '<button id="bh-bp-btn">断点</button>' +
        '<button id="bh-mock-btn">Mock</button>' +
        '<button id="bh-thr-btn">○ 弱网</button>' +
        '<button id="bh-rules-btn">标记</button>' +
      '</span>' +
    '</span>' +
    '<span id="bh-filter-wrap">' +
      '<button id="bh-filter-menu-btn">过滤 ▾</button>' +
      '<span id="bh-filter-menu">' +
        '<button id="bh-noise-btn">○ 净化</button>' +
        '<button id="bh-payload-btn">○ 正文</button>' +
        '<button id="bh-plain-btn">○ 明文</button>' +
      '</span>' +
    '</span>' +
    '<input id="bh-filter" placeholder="搜索 URL…">';
  wrap.appendChild(bar);
  netFilterEl = bar.querySelector('#bh-filter');
  netEnableBtn = bar.querySelector('#bh-toggle');

	    netEnableBtn.addEventListener('click', function () {
	      setNetworkCaptureEnabled(!netEnabled);
	    });
  bar.querySelector('#bh-clear').addEventListener('click', function () {
    netRequests = []; netPlainCandidates = []; netSelReq = null; renderNetList(); renderDetail();
  });
  bar.querySelector('#bh-export-har').addEventListener('click', exportHAR);
  bar.querySelector('#bh-bp-btn').addEventListener('click', openBreakpointModal);
  bar.querySelector('#bh-mock-btn').addEventListener('click', openMockModal);
	    var interceptBtn = bar.querySelector('#bh-intercept-btn');
	    interceptBtn.addEventListener('click', function () {
	      netInterceptMaster = !netInterceptMaster;
	      recomputeIntercept();
	      if (!netInterceptMaster) {
	        if (netInterceptQueue.length) releaseAllIntercepts();
	        if (netRespInterceptQueue.length) releaseAllRespIntercepts();
	      }
	      saveNetConfig();
	      updateInterceptBtn();
	      renderNetList();
	    });
	    bindLongPress(interceptBtn, openInterceptConfigModal);
	    bar.querySelector('#bh-rules-btn').addEventListener('click', openRulesView);
  bar.querySelector('#bh-extra-btn').addEventListener('click', function (e) {
    try { e.stopPropagation(); } catch (err) {}
    netExtraMenuOpen = !netExtraMenuOpen;
    updateExtraMenu();
  });
  bar.querySelector('#bh-extra-wrap').addEventListener('click', function (e) {
    try { e.stopPropagation(); } catch (err) {}
  });
  (function () {
    var replBtn = bar.querySelector('#bh-replace-btn');
    var replLong = null, replFired = false, replX = 0, replY = 0, replPtrMode = false;
    function replDown(x, y) {
      replFired = false; replX = x; replY = y;
      if (replLong) clearTimeout(replLong);
      replLong = setTimeout(function () { replFired = true; replLong = null; openReplaceModal(); }, 600);
    }
    function replUp(x, y) {
      if (replLong) { clearTimeout(replLong); replLong = null; }
      if (replFired) { replFired = false; return; }
      if (Math.abs(x - replX) > 12 || Math.abs(y - replY) > 12) return;
      netReplaceEnabled = !netReplaceEnabled;
      saveNetConfig();
      updateReplaceBtn();
    }
    function replCancel() { if (replLong) { clearTimeout(replLong); replLong = null; } replFired = false; }
    replBtn.addEventListener('pointerdown', function (e) { replPtrMode = true; replDown(e.clientX, e.clientY); });
    replBtn.addEventListener('pointerup', function (e) { replPtrMode = true; replUp(e.clientX, e.clientY); });
    replBtn.addEventListener('pointercancel', replCancel);
    replBtn.addEventListener('touchstart', function (e) {
      if (replPtrMode) return;
      var t = e.touches && e.touches[0]; if (t) replDown(t.clientX, t.clientY);
    }, { passive: true });
    replBtn.addEventListener('touchend', function (e) {
      if (replPtrMode) return;
      var t = e.changedTouches && e.changedTouches[0];
      if (t) replUp(t.clientX, t.clientY); else replUp(replX, replY);
      e.preventDefault();
    });
    replBtn.addEventListener('contextmenu', function (e) { e.preventDefault(); });
    replBtn.removeEventListener('click', function () {});
  }());
  bar.querySelector('#bh-filter-menu-btn').addEventListener('click', function (e) {
    try { e.stopPropagation(); } catch (err) {}
    netFilterMenuOpen = !netFilterMenuOpen;
    updateFilterButtons();
  });
  bar.querySelector('#bh-filter-wrap').addEventListener('click', function (e) {
    try { e.stopPropagation(); } catch (err) {}
  });
  wrap.addEventListener('click', function () {
    var changed = false;
    if (netFilterMenuOpen) { netFilterMenuOpen = false; changed = true; }
    if (netExtraMenuOpen) { netExtraMenuOpen = false; updateExtraMenu(); }
    if (changed) updateFilterButtons();
  });
  bar.querySelector('#bh-noise-btn').addEventListener('click', function () {
    netHideTunnelNoise = !netHideTunnelNoise;
    saveNetConfig();
    updateFilterButtons();
    renderNetList();
    if (netSelReq && getVisibleRequests().indexOf(netSelReq) === -1) selectFirstVisibleRequest(false);
  });
  bar.querySelector('#bh-payload-btn').addEventListener('click', function () {
    netPayloadOnly = !netPayloadOnly;
    saveNetConfig();
    updateFilterButtons();
    if (netPayloadOnly) selectFirstVisibleRequest(true);
    else { renderNetList(); renderDetail(true); }
  });
  bar.querySelector('#bh-plain-btn').addEventListener('click', function () {
    netPlainProbeEnabled = !netPlainProbeEnabled;
    saveNetConfig();
    updateFilterButtons();
    injectPlainProbe();
    if (netSelReq) {
      if (netPlainProbeEnabled && (!netSelReq.plain || !netSelReq.plain.length)) {
        netSelReq.plain = collectPlainCandidates(netSelReq);
      }
      netDetailTab = 4;
      renderDetail(true);
    }
  });
  // 弱网按钮：单点切换开/关，长按弹菜单选预设
  // 不依赖合成 click（Gecko/Android 上 pointer 事件后 click 可能不触发），
  // 直接用 pointerdown/pointerup 自洽处理，并加 touch 兜底。
  (function () {
    var thrBtn = bar.querySelector('#bh-thr-btn');
    var longT = null;
    var fired = false;
    var startX = 0, startY = 0;
    var pointerMode = false;
    function toggleThrottle() {
      if (netThrottle.enabled) {
        applyThrottle({ latencyMs: 0, kbps: 0 });
      } else {
        var last = netThrottle._last || { latencyMs: 100, kbps: 200 };
        applyThrottle(last);
      }
    }
    function onDown(x, y) {
      fired = false;
      startX = x; startY = y;
      if (longT) clearTimeout(longT);
      longT = setTimeout(function () {
        fired = true;
        longT = null;
        openThrottleMenu();
      }, 600);
    }
    function onUp(x, y) {
      if (longT) { clearTimeout(longT); longT = null; }
      if (fired) { fired = false; return; }          // 长按已处理
      // 移动过多视为滑动，不触发切换
      if (Math.abs(x - startX) > 12 || Math.abs(y - startY) > 12) return;
      toggleThrottle();
    }
    function onCancel() { if (longT) { clearTimeout(longT); longT = null; } fired = false; }
    thrBtn.addEventListener('pointerdown', function (e) { pointerMode = true; onDown(e.clientX, e.clientY); });
    thrBtn.addEventListener('pointerup', function (e) { pointerMode = true; onUp(e.clientX, e.clientY); });
    thrBtn.addEventListener('pointercancel', onCancel);
    // touch 兜底（部分 Gecko 版本 pointer 事件不可靠）
    thrBtn.addEventListener('touchstart', function (e) {
      if (pointerMode) return;
      var t = e.touches && e.touches[0]; if (t) onDown(t.clientX, t.clientY);
    }, { passive: true });
    thrBtn.addEventListener('touchend', function (e) {
      if (pointerMode) return;
      var t = e.changedTouches && e.changedTouches[0];
      if (t) onUp(t.clientX, t.clientY); else onUp(startX, startY);
      e.preventDefault();
    });
    thrBtn.addEventListener('contextmenu', function (e) { e.preventDefault(); });
  }());
	    updateThrottleBtn();
	    updateFilterButtons();
	    updateInterceptBtn();
	    updateRulesBtn();
	    updateReplaceBtn();
	    updateExtraMenu();
	    // 搜索框也在 shadow root 内，同样改成"点按→light DOM 编辑层"避免 IME 崩坏
	    netFilterEl.readOnly = true;
	    netFilterEl.addEventListener('click', function () {
	      openEditOverlay('搜索 URL', netFilterEl.value, function (text) {
	        netFilterEl.value = text;
	        renderNetList();
	      });
	    });

  // 请求列表
  netListEl = document.createElement('div');
  netListEl.id = 'bh-list';
  netListEl.innerHTML = '<div id="bh-empty">暂无请求记录</div>';
  wrap.appendChild(netListEl);

  // 详情区
  netDetailEl = document.createElement('div');
  netDetailEl.id = 'bh-detail';
  netDetailEl.style.display = 'none';
  netDetailEl.innerHTML =
    '<div id="bh-detail-head">' +
      '<div id="bh-detail-tabs"></div>' +
      '<button id="bh-detail-close" type="button" aria-label="关闭详情">×</button>' +
    '</div>' +
    '<div id="bh-detail-search">' +
      '<input id="bh-dsearch" placeholder="搜索内容…" autocomplete="off" spellcheck="false">' +
      '<span id="bh-dsearch-count"></span>' +
      '<span id="bh-dsearch-nav">' +
        '<button id="bh-dsearch-prev" type="button">↑</button>' +
        '<button id="bh-dsearch-next" type="button">↓</button>' +
      '</span>' +
    '</div>' +
	      '<textarea id="bh-detail-body" spellcheck="false" wrap="soft"></textarea>' +
    '<div id="bh-detail-acts">' +
      '<button id="bh-act-replay">重放</button>' +
      '<button id="bh-act-copy">复制全部</button>' +
      '<button id="bh-act-clear">清空</button>' +
      '<button id="bh-act-curl">复制 curl</button>' +
      '<button id="bh-act-bp">设断点</button>' +
    '</div>';
	    netDetailBody = netDetailEl.querySelector('#bh-detail-body');
	    netDetailActs = netDetailEl.querySelector('#bh-detail-acts');
	    wrap.appendChild(netDetailEl);

	    netDetailEl.querySelector('#bh-detail-close').addEventListener('click', closeNetDetail);

	    // 搜索框：在 shadow root 内，改用 light DOM 编辑层输入，绕过 Gecko IME bug
	    var dsearchEl = netDetailEl.querySelector('#bh-dsearch');
	    dsearchEl.readOnly = true;
	    dsearchEl.addEventListener('click', function () {
	      openEditOverlay('搜索内容', dsearchEl.value, function (text) {
	        dsearchEl.value = text;
	        netDetailSearchText = text.trim();
	        runDetailSearch();
	      });
	    });
	    netDetailEl.querySelector('#bh-dsearch-prev').addEventListener('click', function () {
	      stepDetailSearch(-1);
	    });
	    netDetailEl.querySelector('#bh-dsearch-next').addEventListener('click', function () {
	      stepDetailSearch(1);
	    });

	    ['pointerdown','mousedown','touchstart'].forEach(function (type) {
	      netDetailActs.addEventListener(type, function (e) {
	        syncCurrentDetailEdit();
	        setNetEditing(false);
	        e.stopPropagation();
	      }, true);
	    });

	    // shadow root 内的 textarea 在 Android 软键盘下 IME 合成会崩坏（Gecko bug），
	    // 因此设为只读（仍可长按选中/复制），点按时改用 light DOM 编辑层输入。
	    netDetailBody.readOnly = true;
	    ['pointerdown','pointerup','mousedown','mouseup','dblclick','touchstart','touchend'].forEach(function (type) {
	      netDetailBody.addEventListener(type, function (e) {
	        if (e.stopImmediatePropagation) e.stopImmediatePropagation();
	        else e.stopPropagation();
	      }, true);
	    });
	    netDetailBody.addEventListener('click', function () {
	      openDetailEditor();
	    });

	    netDetailActs.querySelector('#bh-act-replay').addEventListener('click', function () {
	      syncCurrentDetailEdit();
	      setNetEditing(false);
	      if (netSelRespIntercept) { releaseSelectedRespIntercept(); return; }
	      if (netSelIntercept) { sendSelectedIntercept(); return; }
	      if (!netSelReq) return;
	      // 如果当前在"请求体"tab 且用户编辑了内容，用编辑后的内容重放
	      if (netDetailTab === 1 && netDetailBody) {
	        var editedBody = netDetailDirty ? bodyValueFromTextarea(true) : netSelReq.reqBody;
	        replayReq(Object.assign({}, netSelReq, { reqBody: editedBody || null }), '重放');
	      } else {
	        replayReq(netSelReq);
	      }
	    });
  netDetailActs.querySelector('#bh-act-copy').addEventListener('click', function () {
    if (!netDetailBody) return;
    var text = netDetailBody.value || '';
    // 优先用 textarea 选区复制（shadow root 内 navigator.clipboard 有时受限）
    try {
      netDetailBody.focus();
      netDetailBody.select();
      var ok = document.execCommand && document.execCommand('copy');
      if (!ok && navigator.clipboard) navigator.clipboard.writeText(text).catch(function () {});
    } catch (e) {
      if (navigator.clipboard) navigator.clipboard.writeText(text).catch(function () {});
    }
    flashBtn(this, '已复制');
  });
	    netDetailActs.querySelector('#bh-act-clear').addEventListener('click', function () {
	      if (!netDetailBody) return;
	      netDetailBody.value = '';
	      netDetailDirty = true;
	      syncCurrentDetailEdit();
	      renderDetail(true);
	    });
	    netDetailActs.querySelector('#bh-act-curl').addEventListener('click', function () {
	      syncCurrentDetailEdit();
	      setNetEditing(false);
	      if (netSelRespIntercept) return;
	      var target = netSelIntercept ? interceptAsReq(netSelIntercept) : netSelReq;
	      if (!target) return;
	      var text = toCurl(target);
	      navigator.clipboard && navigator.clipboard.writeText(text).catch(function () {});
	      flashBtn(this, '已复制');
	    });
	    netDetailActs.querySelector('#bh-act-bp').addEventListener('click', function () {
	      syncCurrentDetailEdit();
	      setNetEditing(false);
	      if (netSelRespIntercept) { abortSelectedRespIntercept(); return; }
	      if (netSelIntercept) { abortSelectedIntercept(); return; }
	      if (!netSelReq) return;
	      try { var u = new URL(netSelReq.url); netBreakpoints.push({ pattern: u.pathname, id: Date.now(), stage: 'req' }); }
    catch (e) { netBreakpoints.push({ pattern: netSelReq.url.slice(0, 40), id: Date.now(), stage: 'req' }); }
    injectBreakpoints();
    flashBtn(this, '已设断点');
	      renderNetList();
	    });

	    netRulesViewEl = document.createElement('div');
	    netRulesViewEl.id = 'bh-rules-view';
	    wrap.appendChild(netRulesViewEl);
	    renderRulesView();

  initExtensionsPanel(wrap, bar);

	    netPanel = wrap;
	    return wrap;
}
