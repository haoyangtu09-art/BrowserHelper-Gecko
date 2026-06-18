// Eruda runtime helpers and shared UI/network utilities.
var port = null;
var blobUrl = null;
var pageErudaReady = false;
var isolatedErudaReady = false;
var erudaActive = false;
var erudaMode = null;
var fixStyleId = 'browserhelper-eruda-native-fix';
var tools = ['console', 'elements', 'resources', 'sources', 'info'];

function postStatus(status) {
  try {
    if (port) port.postMessage({ status: status });
  } catch (e) {}
}

function describeError(e) {
  return String(e && e.message ? e.message : e);
}

var fontStyleId = 'browserhelper-eruda-fontface';

// Extract every @font-face rule from the eruda source and inject it into the
// document's light DOM. Gecko ignores @font-face declared inside a shadow
// root (bug 1714278), but a light-DOM @font-face still applies to shadow
// content, so this is what makes eruda's icon glyphs render.
function injectErudaFontFace(code) {
  if (document.getElementById(fontStyleId)) return;
  var rules = code.match(/@font-face\s*\{[^}]*\}/g);
  if (!rules || !rules.length) return;
  var style = document.createElement('style');
  style.id = fontStyleId;
  style.textContent = rules.join('\n');
  (document.head || document.documentElement).appendChild(style);
}

function loadPageEruda(cb) {
  if (pageErudaReady) { cb(null); return; }

  fetch(browser.runtime.getURL('eruda.min.js'))
    .then(function (r) { return r.text(); })
    .then(function (code) {
      // Gecko bug 1714278: an @font-face declared inside a shadow root is
      // ignored. Eruda renders its UI in a shadow root (useShadowDom:true),
      // so its embedded icon font never applies and the gear shows as a
      // rectangle. Fix: pull the @font-face rules out of the eruda source
      // (the font data is base64-embedded, no external path needed) and
      // inject them into the document's light DOM, where Gecko honors them
      // and the rule still cascades into the shadow tree.
      injectErudaFontFace(code);
      // Only EVALUATE the eruda bundle here; do NOT call eruda.init().
      // Evaluating the bundle is what makes eruda's chobitsu backend patch the
      // native window.XMLHttpRequest.prototype / window.fetch. On restore we must
      // let that happen BEFORE our own interceptor wraps the XHR/fetch constructors,
      // otherwise eruda reads our exportFunction'd constructor's Xray prototype,
      // the proto patch throws, the blob aborts, window.eruda is never defined and
      // verifyEntry reports "page eruda entry not visible". init() is deferred to
      // initPageEruda() so first-open and restore share the same ordering:
      //   bundle eval (patch native) -> interceptor -> eruda.init() UI.
      var blob = new Blob([code], { type: 'application/javascript' });
      blobUrl = URL.createObjectURL(blob);
      var script = document.createElement('script');
      script.src = blobUrl;
      script.onload = function () {
        script.remove();
        pageErudaReady = true;
        cb(null);
      };
      script.onerror = function () {
        script.remove();
        cb('blob script blocked');
      };
      (document.head || document.documentElement).appendChild(script);
    })
    .catch(function (e) { cb('fetch error: ' + describeError(e)); });
}

function loadIsolatedEruda(cb) {
  if (isolatedErudaReady && self.eruda) { cb(null); return; }

  fetch(browser.runtime.getURL('eruda.min.js'))
    .then(function (r) { return r.text(); })
    .then(function (code) {
      try {
        // 同 page 模式：把 @font-face 注入 light DOM，否则 isolated 模式下
        // (强 CSP 站点如 github) 图标字体不生效，齿轮显示为长方块。
        injectErudaFontFace(code);
        code = code
          .replace(/\bwindow\.getComputedStyle\(/g, 'getComputedStyle(')
          .replace(/\bwindow\.getSelection\(/g, 'getSelection(')
          .replace(/\bwindow\.matchMedia\(/g, 'matchMedia(')
          .replace(/\br=o\.getComputedStyle,i=o\.document\b/g, 'r=getComputedStyle,i=o.document');
        var preamble = [
          'var __bhWindow=document.defaultView||window;',
          'var getComputedStyle=function(el,pseudo){return __bhWindow.getComputedStyle(el,pseudo);};',
          'var getSelection=function(){return __bhWindow.getSelection?__bhWindow.getSelection():null;};',
          'var matchMedia=function(query){return __bhWindow.matchMedia?__bhWindow.matchMedia(query):{matches:false,addListener:function(){},removeListener:function(){}};};',
        ].join('');
        var fn = new Function(preamble + code + '\nreturn typeof eruda!=="undefined"?eruda:self.eruda;');
        var result = fn.call(self);
        if (result && !self.eruda) self.eruda = result;
        isolatedErudaReady = !!self.eruda;
        cb(isolatedErudaReady ? null : 'eruda undefined after isolated exec');
      } catch (e) {
        cb('isolated exec error: ' + describeError(e));
      }
    })
    .catch(function (e) { cb('isolated fetch error: ' + describeError(e)); });
}

function runInPage(js) {
  // 用 blob URL 注入，而不是 inline <script>。
  // 很多强 CSP 站点（chatgpt 等）允许 blob: script-src 但禁止 unsafe-inline，
  // 之前用 s.textContent 的 inline 脚本会被静默拦截，导致 Network tab 注册、
  // 拦截器注入、汉化注入全部失效。eruda 本体能加载正是因为它走的是 blob URL。
  try {
    var blob = new Blob([js], { type: 'application/javascript' });
    var url = URL.createObjectURL(blob);
    var s = document.createElement('script');
    s.src = url;
    s.onload = function () { s.remove(); URL.revokeObjectURL(url); };
    s.onerror = function () {
      s.remove(); URL.revokeObjectURL(url);
      // blob 也被拦截时回退到 inline（弱 CSP 站点）
      var inl = document.createElement('script');
      inl.textContent = js;
      (document.head || document.documentElement).appendChild(inl);
      inl.remove();
    };
    (document.head || document.documentElement).appendChild(s);
  } catch (e) {
    var s2 = document.createElement('script');
    s2.textContent = js;
    (document.head || document.documentElement).appendChild(s2);
    s2.remove();
  }
}

function getErudaRoot() {
  var host = document.getElementById('eruda');
  if (!host) return null;
  return host.shadowRoot || host;
}

function getEntryButton() {
  var root = getErudaRoot();
  return root ? root.querySelector('.eruda-entry-btn') : null;
}

function entryVisible() {
  var host = document.getElementById('eruda');
  // For page-world mode: just check the host element exists and has dimensions.
  // Shadow DOM internals may not be accessible from content script world.
  if (host && host.offsetWidth > 0 && host.offsetHeight > 0) return true;
  var btn = getEntryButton();
  if (!btn) return false;
  try {
    var rect = btn.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0;
  } catch (e) {
    return false;
  }
}

function verifyEntry(cb) {
  var tries = 0;
  function check() {
    if (entryVisible()) {
      cb(true);
      return;
    }
    tries += 1;
    if (tries < 16) {
      setTimeout(check, 100);
    } else {
      cb(false);
    }
  }
  check();
}

function installFixStyle() {
  var old = document.getElementById(fixStyleId);
  if (old) old.remove();

  var style = document.createElement('style');
  style.id = fixStyleId;
  // Shadow DOM isolates Eruda internals from page CSS, so only the host
  // element (#eruda) needs overrides here.
  style.textContent = '#eruda{z-index:2147483647!important;position:fixed!important;}';
  (document.head || document.documentElement).appendChild(style);
}

function applyStyles(el, styles) {
  if (!el) return;
  Object.keys(styles).forEach(function (key) {
    el.style.setProperty(key, styles[key], 'important');
  });
}

function patchIsolatedPanelStyles() {
  var host = document.getElementById('eruda');
  if (!host) return;
  var root = getErudaRoot() || host;
  var vars = {
    '--background': '#fff',
    '--darker-background': '#f6f8fa',
    '--foreground': '#111',
    '--border': '#d0d7de',
    '--primary': '#2563eb',
    '--highlight': '#dbeafe',
  };
  applyStyles(host, vars);
  applyStyles(root.querySelector('.eruda-container'), Object.assign({}, vars, {
    position: 'fixed',
    left: '0',
    top: '0',
    width: '100vw',
    height: '100vh',
    'z-index': '2147483647',
    'pointer-events': 'none',
  }));
  applyStyles(root.querySelector('.eruda-entry-btn'), {
    visibility: 'visible',
    display: 'flex',
    'pointer-events': 'auto',
    'z-index': '2147483647',
  });
  Array.prototype.forEach.call(
    root.querySelectorAll('.eruda-dev-tools,.eruda-tools,.eruda-tool,.eruda-tab,.eruda-notification,.eruda-modal'),
    function (el) {
      applyStyles(el, { 'pointer-events': 'auto' });
    }
  );
}

var pointerGuardStyleId = 'browserhelper-eruda-pointer-guard';
var POINTER_GUARD_CSS = [
  '.eruda-entry-btn,.eruda-dev-tools,.eruda-tools,.eruda-tool,.eruda-tab,.eruda-notification,.eruda-modal{',
  '  pointer-events:auto!important;',
  '}',
  '.eruda-dev-tools,.eruda-tools,.eruda-tool{',
  '  touch-action:auto!important;',
  '}',
].join('\n');

function installPointerGuard() {
  if (erudaMode === 'page') {
    runInPage([
      '(function(){',
      '  var host=document.getElementById("eruda");',
      '  var root=host&&(host.shadowRoot||host);',
      '  if(!root)return;',
      '  if((root.getElementById&&root.getElementById("' + pointerGuardStyleId + '"))||',
      '     (root.querySelector&&root.querySelector("#' + pointerGuardStyleId + '")))return;',
      '  var style=document.createElement("style");',
      '  style.id="' + pointerGuardStyleId + '";',
      '  style.textContent=' + JSON.stringify(POINTER_GUARD_CSS) + ';',
      '  root.appendChild(style);',
      '})();',
    ].join('\n'));
    return;
  }
  var root = getErudaRoot();
  if (!root) return;
  if ((root.getElementById && root.getElementById(pointerGuardStyleId)) ||
      (root.querySelector && root.querySelector('#' + pointerGuardStyleId))) return;
  var style = document.createElement('style');
  style.id = pointerGuardStyleId;
  style.textContent = POINTER_GUARD_CSS;
  root.appendChild(style);
}

function removeFixStyle() {
  var style = document.getElementById(fixStyleId);
  if (style) style.remove();
}

function centerIsolatedEntry() {
  var entry = getEntryButton();
  if (!entry || !self.eruda || !self.eruda.position) return;
  var rect = entry.getBoundingClientRect();
  var width = rect.width || entry.offsetWidth || 40;
  var height = rect.height || entry.offsetHeight || 40;
  self.eruda.position({
    x: Math.max(0, Math.round((window.innerWidth - width) / 2)),
    y: Math.max(0, Math.round((window.innerHeight - height) / 2)),
  });
}

function resetIsolatedPanel() {
  var devTools = self.eruda && self.eruda._devTools;
  var config = devTools && devTools.config;
  try {
    if (config && config.set) {
      config.set('transparency', 1);
      config.set('displaySize', 80);
    }
    if (self.eruda && self.eruda.hide) self.eruda.hide();
  } catch (e) {}
}

function initPageEruda(cb) {
  // The eruda bundle was only evaluated (not init'd) in loadPageEruda, so its
  // chobitsu backend has already patched the NATIVE window.XMLHttpRequest /
  // window.fetch. Now build the UI via eruda.init() in the page world, then
  // verify the entry button appeared.
  runInPage([
    '(function(){',
    '  try{',
    '    if(!window.eruda)return;',
    '    if(window.eruda._isInit){',
    '      window.eruda._isInit=false;',
    '      window.eruda._container=null;',
    '      window.eruda._shadowRoot=null;',
    '    }',
    '    window.eruda.init({useShadowDom:true,tool:["console","elements","resources","sources","info"]});',
    '    try{window.eruda.hide&&window.eruda.hide();}catch(e){}',
    '  }catch(e){}',
    '})();',
  ].join('\n'));
  verifyEntry(function (visible) {
    if (visible) {
      erudaMode = 'page';
      // patch page-world eruda console.evaluate to bypass our interceptor
      runInPage('(function(){try{var c=window.eruda&&window.eruda.get&&window.eruda.get("console");if(!c||typeof c.evaluate!=="function")return;var o=c.evaluate.bind(c);c.evaluate=function(code){window.__bhNoIntercept=true;try{return o(code);}finally{window.__bhNoIntercept=false;}};}catch(e){}})();');
      cb(null);
    } else {
      cb('page eruda entry not visible');
    }
  });
}

function initIsolatedEruda(cb) {
  loadIsolatedEruda(function (err) {
    if (err) { cb(err); return; }

    try {
      var old = document.getElementById('eruda');
      if (old) old.remove();
      installFixStyle();
      if (self.eruda._isInit) {
        try { self.eruda.destroy(); } catch (e) {}
        self.eruda._isInit = false;
        self.eruda._container = null;
        self.eruda._shadowRoot = null;
      }
      self.eruda.init({
        useShadowDom: true,
        tool: tools,
      });
      resetIsolatedPanel();
      patchIsolatedPanelStyles();
      centerIsolatedEntry();
      requestAnimationFrame(patchIsolatedPanelStyles);
      requestAnimationFrame(centerIsolatedEntry);
      setTimeout(patchIsolatedPanelStyles, 300);
      setTimeout(centerIsolatedEntry, 300);
      verifyEntry(function (visible) {
        if (!visible) {
          removeFixStyle();
          cb('isolated eruda entry not visible');
          return;
        }
        erudaMode = 'isolated';
        cb(null);
      });
    } catch (e) {
      removeFixStyle();
      cb('isolated init error: ' + describeError(e));
    }
  });
}

function destroyActiveEruda() {
  if (erudaMode === 'isolated') {
    try { if (self.eruda && self.eruda._isInit) self.eruda.destroy(); } catch (e) {}
    removeFixStyle();
  } else {
    runInPage('try{eruda.destroy();}catch(e){}');
  }
  erudaMode = null;
  erudaActive = false;
}

function toggle() {
  if (erudaActive) {
    destroyActiveEruda();
    clearActiveState();
    postStatus('destroyed');
    return;
  }

  loadPageEruda(function (err) {
    if (err) {
      initIsolatedEruda(function (isolatedErr) {
        if (isolatedErr) {
          postStatus('load error: ' + isolatedErr);
          return;
        }
        erudaActive = true;
        saveActiveState();
        postStatus('ok(isolated,blob-blocked:' + err + ')');
        setTimeout(installI18n, 500);
      });
      return;
    }

    initPageEruda(function (pageErr) {
      if (!pageErr) {
        erudaActive = true;
        saveActiveState();
        postStatus('ok(page)');
        setTimeout(installI18n, 500);
        return;
      }
      initIsolatedEruda(function (isolatedErr) {
        if (isolatedErr) {
          postStatus('load error: ' + pageErr + '; ' + isolatedErr);
          return;
        }
        erudaActive = true;
        saveActiveState();
        postStatus('ok(isolated,page-err:' + pageErr + ')');
        setTimeout(installI18n, 500);
      });
    });
  });
}

function connect() {
  try {
    port = browser.runtime.connectNative('devtools_inject');
    port.onMessage.addListener(function (msg) {
      if (msg && msg.action === 'toggle') toggle();
    });
    port.onDisconnect.addListener(function () {
      port = null;
      setTimeout(connect, 1000);
    });
  } catch (e) {
    setTimeout(connect, 1000);
  }
}

// 页面导航后自动恢复：把激活状态写入 sessionStorage，
// 新页面的 content script 启动时读取并自动触发 toggle。
function saveActiveState() {
  try { sessionStorage.setItem('__bhErudaActive', '1'); } catch (e) {}
}
function clearActiveState() {
  try { sessionStorage.removeItem('__bhErudaActive'); } catch (e) {}
}
function wasActive() {
  try { return sessionStorage.getItem('__bhErudaActive') === '1'; } catch (e) { return false; }
}

// ── 公共工具函数 ─────────────────────────────────────────────────────────────
function statusClass(s, hasError) {
  if (hasError && !s) return 's-err';
  if (!s) return 's0';
  if (s >= 200 && s < 300) return 's2';
  if (s >= 300 && s < 400) return 's3';
  if (s >= 400 && s < 500) return 's4';
  if (s >= 500) return 's5';
  return 's0';
}

var STATUS_PHRASES = {
  200:'OK',201:'Created',204:'No Content',206:'Partial',
  301:'Moved',302:'Found',304:'Not Modified',307:'Temp Redirect',308:'Perm Redirect',
  400:'Bad Request',401:'Unauthorized',403:'Forbidden',404:'Not Found',
  405:'Method N/A',408:'Timeout',409:'Conflict',410:'Gone',
  413:'Too Large',415:'Unsupported',422:'Unprocessable',429:'Too Many Req',
  500:'Server Error',501:'Not Impl',502:'Bad Gateway',503:'Unavailable',504:'GW Timeout',
};

function statusPhrase(code) {
  return STATUS_PHRASES[code] || '';
}

// 从错误消息里提取简短原因（网络错误、CORS、超时等）
function shortError(errStr) {
  if (!errStr) return '';
  var s = String(errStr);
  if (/cors/i.test(s)) return 'CORS';
  if (/network/i.test(s)) return '网络错误';
  if (/timeout/i.test(s)) return '超时';
  if (/abort/i.test(s)) return '已中止';
  if (/refused/i.test(s)) return '拒绝连接';
  if (/ssl|cert/i.test(s)) return 'SSL错误';
  // 取第一段有意义的词，最多 10 个字符
  return s.replace(/^.*?:\s*/, '').slice(0, 10);
}

function recordPlainCandidate(d) {
  if (!netPlainProbeEnabled) return;
  if (!d || !d.plainText) return;
  var text = String(d.plainText);
  var cand = {
    id: ++netPlainSeq,
    ts: d.ts || Date.now(),
    source: d.source || 'unknown',
    text: text,
    size: d.plainSize || text.length,
    meta: d.meta || {},
  };
  var last = netPlainCandidates[netPlainCandidates.length - 1];
  if (last && last.source === cand.source && last.text === cand.text && cand.ts - last.ts < 500) return;
  netPlainCandidates.push(cand);
  var cutoff = Date.now() - 15000;
  netPlainCandidates = netPlainCandidates.filter(function (x) { return x.ts >= cutoff; });
  if (netPlainCandidates.length > 80) netPlainCandidates = netPlainCandidates.slice(netPlainCandidates.length - 80);
}

function collectPlainCandidates(entry) {
  if (!netPlainProbeEnabled) return [];
  var ts = entry.ts || Date.now();
  var out = [];
  for (var i = netPlainCandidates.length - 1; i >= 0 && out.length < 8; i--) {
    var c = netPlainCandidates[i];
    var delta = ts - c.ts;
    if (delta < -500) continue;
    if (delta > 5000) break;
    var dup = out.some(function (x) { return x.text === c.text; });
    if (!dup) out.push({
      source: c.source,
      text: c.text,
      size: c.size,
      meta: c.meta,
      deltaMs: delta,
    });
  }
  return out;
}

function fmtPlainCandidates(r) {
  if (!netPlainProbeEnabled) {
    return '明文探针未开启。\n\n开启后会尝试捕获 JSON.stringify、TextEncoder.encode、crypto.subtle.encrypt、WebSocket.send 经过的明文候选，并按时间关联到请求。';
  }
  var list = (r && r.plain) || [];
  if (!list.length) {
    return '未捕获到明文候选。\n\n可能原因:\n- 页面没有做应用层加密，直接看“请求体”即可\n- 加密发生在 WASM/原生层或自定义二进制流程中\n- 明文生成和请求发送间隔太久，超过关联窗口\n- 明文太短或不像业务数据，被噪声过滤掉';
  }
  return list.map(function (c, i) {
    var meta = '';
    if (c.meta && c.meta.algorithm) meta = ' · ' + c.meta.algorithm;
    var delta = c.deltaMs != null ? (' · 请求前 ' + Math.max(0, c.deltaMs) + 'ms') : '';
    return '[' + (i + 1) + '] ' + c.source + meta + ' · ' + fmtBytes(c.size) + delta + '\n' + c.text;
  }).join('\n\n────\n\n');
}

function byteLen(v) {
  if (v == null) return 0;
  var s = String(v);
  try { return new Blob([s]).size; } catch (e) {}
  try { return unescape(encodeURIComponent(s)).length; } catch (e2) {}
  return s.length;
}

function headersByteLen(obj) {
  if (!obj) return 0;
  var n = 0;
  Object.keys(obj).forEach(function (k) {
    n += byteLen(k) + 2 + byteLen(obj[k]) + 2;
  });
  return n;
}

function headerValue(obj, name) {
  if (!obj) return '';
  var target = String(name).toLowerCase();
  var keys = Object.keys(obj);
  for (var i = 0; i < keys.length; i++) {
    if (keys[i].toLowerCase() === target) return String(obj[keys[i]]);
  }
  return '';
}

function contentLength(obj) {
  var n = parseInt(headerValue(obj, 'content-length'), 10);
  return isNaN(n) || n < 0 ? null : n;
}

function reqSize(r) {
  return headersByteLen(r.reqHeaders) + byteLen(r.reqBody);
}

function respSize(r) {
  var cl = contentLength(r.respHeaders);
  if (cl != null) return cl;
  return headersByteLen(r.respHeaders) + byteLen(r.respBody);
}

	  function fmtBytes(n) {
	    n = Math.max(0, n || 0);
	    if (n < 1024) return n + 'B';
	    if (n < 1024 * 1024) return (n < 10 * 1024 ? (n / 1024).toFixed(1) : Math.round(n / 1024)) + 'KB';
	    return (n / 1024 / 1024).toFixed(n < 10 * 1024 * 1024 ? 1 : 0) + 'MB';
	  }

	  function storageLocal() {
	    try {
	      return browser && browser.storage && browser.storage.local;
	    } catch (e) {
	      return null;
	    }
	  }

	  function normalizeRuleUrl(url) {
	    try {
	      var u = new URL(String(url || ''), location.href);
	      return { host: u.host, path: u.pathname, sampleUrl: u.href };
	    } catch (e) {
	      var raw = String(url || '');
	      return { host: '', path: raw.split(/[?#]/)[0], sampleUrl: raw };
	    }
	  }
