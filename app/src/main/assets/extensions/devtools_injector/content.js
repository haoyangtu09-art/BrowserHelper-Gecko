// Content script: prefer Eruda in the page's real window. If a strict CSP
// blocks page-world script execution, fall back to Eruda's native entry button
// in the extension isolated world.
(function () {
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

  // ── 网络拦截层 ───────────────────────────────────────────────────────────────
  var netRequests = [];   // 最近 200 条
  var netEnabled = true;
  var netPanelVisible = false;
  var netBreakpoints = []; // [{pattern, id}]
  var netMockRules = [];   // [{pattern, status, headers, body}]
  var netThrottle = { enabled: false, latencyMs: 0, kbps: 0 };
  var netPendingBreaks = {}; // reqId -> {resolve, reject, req}

  // 注入到 page world 的拦截脚本（字符串拼接，避免闭包引用 content script 变量）
  var INTERCEPT_JS = (function () {
    return [
      '(function(){',
	      '  if(window.__bhNetInstalled)return;',
	      '  window.__bhNetInstalled=true;',
	      '  window.__bhRestoreFetch=window.__bhRestoreFetch||window.fetch;',
	      '  window.__bhRestoreXHR=window.__bhRestoreXHR||window.XMLHttpRequest;',
	      '  window.__bhGlobalInterceptEnabled=!!window.__bhGlobalInterceptEnabled;',
	      '  window.__bhInterceptRules=window.__bhInterceptRules||[];',

      // ── 工具 ──
      '  function headersToObj(h){',
      '    var o={};',
      '    if(!h)return o;',
      '    if(typeof h.forEach==="function"){h.forEach(function(v,k){o[k]=v;});return o;}',
      '    if(Array.isArray(h)){h.forEach(function(p){o[p[0]]=p[1];});return o;}',
      '    return Object.assign({},h);',
      '  }',
      '  function uid(){return Math.random().toString(36).slice(2);}',
      '  function send(data){',
      '    try{window.postMessage(Object.assign({__bhNet:true},data),"*");}catch(e){}',
      '  }',

      // ── fetch 拦截 ──
	      '  var _origFetch=window.__bhRestoreFetch||window.fetch;',
      '  function checkMock(url){',
      '    var rules=window.__bhMockRules;',
      '    if(!rules||!rules.length)return null;',
      '    for(var i=0;i<rules.length;i++){',
      '      if(url.indexOf(rules[i].pattern)!==-1)return rules[i];',
      '    }',
      '    return null;',
      '  }',
      // ── 断点：命中则暂停请求，等 content world 编辑后放行/中止 ──
      '  function bpMatch(url){',
      '    var bps=window.__bhBreakpoints;',
      '    if(!bps||!bps.length)return null;',
      '    for(var i=0;i<bps.length;i++){if(url.indexOf(bps[i].pattern)!==-1)return bps[i];}',
      '    return null;',
      '  }',
      '  window.__bhBpPending=window.__bhBpPending||{};',
      '  window.__bhRespBpPending=window.__bhRespBpPending||{};',
      '  window.addEventListener("message",function(e){',
      '    var d=e.data;if(!d||!d.__bhNet)return;',
      '    if(d.type==="bpResolve"){var fn=window.__bhBpPending[d.reqId];if(fn){delete window.__bhBpPending[d.reqId];fn(d);}return;}',
      '    if(d.type==="respResolve"){var rf=window.__bhRespBpPending[d.reqId];if(rf){delete window.__bhRespBpPending[d.reqId];rf(d);}return;}',
      '  });',
	      '  function headersCount(headers){',
	      '    try{return headers?Object.keys(headers).length:0;}catch(e){return 0;}',
	      '  }',
	      '  function bodyHasValue(body){return body!=null&&String(body).length>0;}',
	      '  function matchInterceptRule(url,method,body){',
	      '    var rules=window.__bhInterceptRules||[];',
	      '    if(!rules||!rules.length)return "";',
	      '    var u=null;',
	      '    try{u=new URL(String(url||""),location.href);}catch(e){return "";}',
	      '    method=String(method||"GET").toUpperCase();',
	      '    var hasBody=bodyHasValue(body);',
	      '    for(var i=rules.length-1;i>=0;i--){',
	      '      var r=rules[i]||{};',
	      '      if(r.host&&r.host!==u.host)continue;',
	      '      if(r.path&&r.path!==u.pathname)continue;',
	      '      if(r.method&&String(r.method).toUpperCase()!==method)continue;',
	      '      if(!!r.hasBody!==hasBody)continue;',
	      '      return r.action||"";',
	      '    }',
	      '    return "";',
	      '  }',
	      '  function isNoiseReq(url,method,body,headers){',
	      '    url=String(url||"").toLowerCase();method=String(method||"GET").toUpperCase();',
	      '    if(!url)return true;',
	      '    if(method==="OPTIONS"||method==="HEAD")return true;',
	      '    var len=body==null?0:String(body).length;',
	      '    if(len===0&&headersCount(headers)===0)return true;',
	      '    var re=/\\/(cdn-cgi|collect|telemetry|analytics|metrics|beacon|heartbeat|ping|events?|log|logs|sentry|trace|traces|socket\\.io|sockjs|realtime|tunnel|connect|presence|typing|status|health|alive|poll)([/?#_.-]|$)|[?&](ping|heartbeat|keepalive|beacon)=/;',
	      '    if(!re.test(url))return false;',
	      '    if(len>512)return false;',
	      '    if(method!=="GET"&&len>0)return false;',
	      '    return true;',
	      '  }',
	      '  function checkGlobalIntercept(url,method,body,headers){',
	      '    var rule=matchInterceptRule(url,method,body);',
	      '    if(rule==="pass")return false;',
	      '    if(rule==="intercept")return true;',
	      '    return !!window.__bhGlobalInterceptEnabled&&!isNoiseReq(url,method,body,headers);',
	      '  }',
	      '  function applyReplace(text){',
	      '    var rules=window.__bhReplaceRules;',
	      '    if(!rules||!rules.length||text==null)return text;',
	      '    var s=String(text);',
	      '    rules.forEach(function(r){',
	      '      if(!r.from)return;',
	      '      var out="",idx=0;',
	      '      while(true){var p=s.indexOf(r.from,idx);if(p===-1){out+=s.slice(idx);break;}out+=s.slice(idx,p)+(r.to||"");idx=p+r.from.length;}',
	      '      s=out;',
	      '    });',
	      '    return s;',
	      '  }',
      '  function waitBp(reqId,url,method,reqHeaders,reqBody,mode){',
      '    return new Promise(function(resolve){',
      '      window.__bhBpPending[reqId]=resolve;',
      '      send({type:"breakpoint",reqId:reqId,url:url,method:method,reqHeaders:reqHeaders,reqBody:reqBody,mode:mode||"breakpoint"});',
      '    });',
      '  }',
      // 响应断点：服务器响应已到（流式已被 text() 攒成整段），暂停等编辑后放行/中止
      '  function waitRespBp(reqId,status,respHeaders,respBody){',
      '    return new Promise(function(resolve){',
      '      window.__bhRespBpPending[reqId]=resolve;',
      '      send({type:"respBreakpoint",reqId:reqId,status:status,respHeaders:respHeaders,respBody:respBody});',
      '    });',
      '  }',
      '  window.fetch=function(input,init){',
      '    var url=typeof input==="string"?input:(input&&input.url)||"";',
      '    var method=((init&&init.method)||(input&&input.method)||"GET").toUpperCase();',
      '    var reqHeaders=headersToObj(init&&init.headers);',
      '    var reqBody=(init&&init.body!=null)?String(init.body):null;',
      '    reqBody=applyReplace(reqBody);',
      '    if(reqBody!=null&&init){var _ni=Object.assign({},init);_ni.body=reqBody;init=_ni;}',
      '    var reqId=uid();',
      '    var t0=Date.now();',
      '    send({type:"req",reqId:reqId,url:url,method:method,reqHeaders:reqHeaders,reqBody:reqBody});',
      '    var mock=checkMock(url);',
      '    if(mock){',
      '      var mBody=mock.body||"";',
      '      var mStatus=mock.status||200;',
      '      send({type:"resp",reqId:reqId,status:mStatus,respHeaders:{"x-mock":"1"},',
      '        respBody:mBody,duration:Date.now()-t0});',
      '      return Promise.resolve(new Response(mBody,{status:mStatus,headers:{"x-mock":"1"}}));',
      '    }',
      '    var args=arguments;',
      '    var thr=window.__bhThrottle;',
      '    var delay=(thr&&thr.enabled&&thr.latencyMs>0)?thr.latencyMs:0;',
      '    var doFetch=function(){return _origFetch.apply(this,args).then(function(resp){',
      '      var status=resp.status;',
      '      var respHeaders=headersToObj(resp.headers);',
      // 命中响应断点：读完整 body（流式在此被攒成整段），暂停编辑后用新 Response 放行
      '      if(respStop){',
      '        return resp.text().then(function(body){',
      '          send({type:"resp",reqId:reqId,status:status,respHeaders:respHeaders,respBody:body.slice(0,102400),duration:Date.now()-t0});',
      '          return waitRespBp(reqId,status,respHeaders,body).then(function(r){',
      '            if(r.action==="abort"){send({type:"resp",reqId:reqId,status:0,error:"响应已被断点中止",duration:Date.now()-t0});throw new Error("aborted by response breakpoint");}',
      '            var nb=(r.respBody!=null)?r.respBody:body;var ns=r.status||status;var nh=r.respHeaders||respHeaders;',
      '            try{return new Response(nb,{status:ns,headers:nh});}catch(e){try{return new Response(nb,{status:ns});}catch(e2){return new Response(nb);}}',
      '          });',
      '        });',
      '      }',
      '      var clone=resp.clone();',
      '      clone.text().then(function(body){',
      '        send({type:"resp",reqId:reqId,status:status,respHeaders:respHeaders,',
      '          respBody:body.slice(0,102400),duration:Date.now()-t0});',
      '      }).catch(function(){',
      '        send({type:"resp",reqId:reqId,status:status,respHeaders:respHeaders,',
      '          respBody:"(读取失败)",duration:Date.now()-t0});',
      '      });',
      '      return resp;',
      '    }).catch(function(err){',
      '      send({type:"resp",reqId:reqId,status:0,error:String(err),duration:Date.now()-t0});',
      '      throw err;',
      '    });}.bind(this);',
      '    var runDelay=function(){return delay>0?new Promise(function(res){setTimeout(function(){res(doFetch());},delay);}):doFetch();};',
      // 命中断点：暂停，等编辑结果。修改后的 URL/头/体替换原参数后再发
	      '    var _bp=bpMatch(url);',
	      '    var respStop=!!_bp&&(_bp.stage==="resp"||_bp.stage==="both");',
	      '    var bpMode=(_bp&&_bp.stage!=="resp")?"breakpoint":(checkGlobalIntercept(url,method,reqBody,reqHeaders)?"intercept":"");',
      '    if(bpMode){',
      '      return waitBp(reqId,url,method,reqHeaders,reqBody,bpMode).then(function(r){',
      '        if(r.action==="abort"){send({type:"resp",reqId:reqId,status:0,error:"已被断点中止",duration:Date.now()-t0});return Promise.reject(new Error("aborted by breakpoint"));}',
      '        var ni=Object.assign({},init||{});',
      '        ni.method=r.method||method;',
      '        if(r.reqHeaders)ni.headers=r.reqHeaders;',
      '        if(r.reqBody!=null&&r.reqBody!=="")ni.body=r.reqBody;',
      '        args=[r.url||url,ni];',
      '        return runDelay();',
      '      });',
      '    }',
      '    return runDelay();',
      '  };',

      // ── XHR 拦截 ──
	      '  var _XHR=window.__bhRestoreXHR||window.XMLHttpRequest;',
      '  window.XMLHttpRequest=function(){',
      '    var xhr=new _XHR();',
      '    var _method="GET",_url="",_reqHeaders={},_reqBody=null,_reqId=uid(),_t0=0;',
      '    var origOpen=xhr.open.bind(xhr);',
      '    var origSend=xhr.send.bind(xhr);',
      '    var origSetHeader=xhr.setRequestHeader.bind(xhr);',
      '    xhr.open=function(method,url){_method=method.toUpperCase();_url=url;return origOpen.apply(xhr,arguments);};',
      '    xhr.setRequestHeader=function(k,v){_reqHeaders[k]=v;return origSetHeader(k,v);};',
      '    xhr.send=function(body){',
      '      _reqBody=body!=null?String(body):null;',
      '      _reqBody=applyReplace(_reqBody);if(_reqBody!=null)body=_reqBody;',
      '      _t0=Date.now();',
      '      send({type:"req",reqId:_reqId,url:_url,method:_method,reqHeaders:_reqHeaders,reqBody:_reqBody});',
      '      var mock=checkMock(_url);',
      '      if(mock){',
      '        var _mb=mock.body||"";var _ms=mock.status||200;',
      '        send({type:"resp",reqId:_reqId,status:_ms,',
      '          respHeaders:{"x-mock":"1"},respBody:_mb,duration:Date.now()-_t0});',
      '        try{Object.defineProperty(xhr,"readyState",{configurable:true,get:function(){return 4;}});}catch(e){}',
      '        try{Object.defineProperty(xhr,"status",{configurable:true,get:function(){return _ms;}});}catch(e){}',
      '        try{Object.defineProperty(xhr,"responseText",{configurable:true,get:function(){return _mb;}});}catch(e){}',
      '        try{Object.defineProperty(xhr,"response",{configurable:true,get:function(){return _mb;}});}catch(e){}',
      '        setTimeout(function(){',
      '          try{if(xhr.onreadystatechange)xhr.onreadystatechange();}catch(e){}',
      '          try{xhr.dispatchEvent(new Event("readystatechange"));}catch(e){}',
      '          try{xhr.dispatchEvent(new Event("load"));}catch(e){}',
      '          try{if(xhr.onload)xhr.onload();}catch(e){}',
      '          try{if(xhr.onloadend)xhr.onloadend();}catch(e){}',
      '        },0);',
      '        return;',
      '      }',
      '      var doSend=function(){',
      '      xhr.addEventListener("readystatechange",function(){',
      '        if(xhr.readyState!==4)return;',
      '        var respHeaders={};',
      '        try{',
      '          (xhr.getAllResponseHeaders()||"").split("\\r\\n").forEach(function(l){',
      '            var i=l.indexOf(":");if(i<0)return;',
      '            respHeaders[l.slice(0,i).trim()]=l.slice(i+1).trim();',
      '          });',
      '        }catch(e){}',
      '        send({type:"resp",reqId:_reqId,status:xhr.status,',
      '          respHeaders:respHeaders,respBody:(xhr.responseText||"").slice(0,102400),',
      '          duration:Date.now()-_t0});',
      '      });',
      '      origSend.apply(xhr,arguments);};',
      '      var thr=window.__bhThrottle;var d=(thr&&thr.enabled&&thr.latencyMs>0)?thr.latencyMs:0;',
      '      var fireSend=function(){if(d>0){setTimeout(doSend,d);}else{doSend();}};',
      // 命中断点：暂停，等编辑结果。改了 url/method 需重新 open，改了头需重设
	      '      var _bpx=bpMatch(_url);',
	      '      var bpMode=(_bpx&&_bpx.stage!=="resp")?"breakpoint":(checkGlobalIntercept(_url,_method,_reqBody,_reqHeaders)?"intercept":"");',
      '      if(bpMode){',
      '        waitBp(_reqId,_url,_method,_reqHeaders,_reqBody,bpMode).then(function(r){',
      '          if(r.action==="abort"){send({type:"resp",reqId:_reqId,status:0,error:"已被断点中止",duration:Date.now()-_t0});return;}',
      '          var nm=r.method||_method,nu=r.url||_url;',
      '          if(nm!==_method||nu!==_url){try{origOpen(nm,nu);}catch(e){}}',
      '          if(r.reqHeaders){try{Object.keys(r.reqHeaders).forEach(function(k){origSetHeader(k,r.reqHeaders[k]);});}catch(e){}}',
      '          if(r.reqBody!=null&&r.reqBody!==""){body=r.reqBody;}',
      '          fireSend();',
      '        });',
      '        return;',
      '      }',
      '      fireSend();',
      '    };',
      '    return xhr;',
      '  };',
      '  window.XMLHttpRequest.prototype=_XHR.prototype;',

      '})();',
    ].join('\n');
  }());

  var PLAIN_PROBE_JS = (function () {
    return [
      '(function(){',
      '  if(window.__bhPlainProbeInstalled)return;',
      '  window.__bhPlainProbeInstalled=true;',
      '  window.__bhPlainProbeEnabled=!!window.__bhPlainProbeEnabled;',
      '  var MAX=65536;',
      '  function send(data){try{window.postMessage(Object.assign({__bhNet:true,type:"plain"},data),"*");}catch(e){}}',
      '  function textFrom(v){',
      '    if(v==null)return null;',
      '    if(typeof v==="string")return v;',
      '    try{',
      '      if(v instanceof ArrayBuffer){return new TextDecoder("utf-8",{fatal:false}).decode(new Uint8Array(v));}',
      '      if(ArrayBuffer.isView&&ArrayBuffer.isView(v)){return new TextDecoder("utf-8",{fatal:false}).decode(new Uint8Array(v.buffer,v.byteOffset,v.byteLength));}',
      '    }catch(e){}',
      '    return null;',
      '  }',
      '  function printableRatio(s){',
      '    if(!s)return 0;',
      '    var ok=0,n=Math.min(s.length,2048);',
      '    for(var i=0;i<n;i++){var c=s.charCodeAt(i);if(c===9||c===10||c===13||c>=32)ok++;}',
      '    return n?ok/n:0;',
      '  }',
      '  function meaningful(s,source){',
      '    if(!s||s.length<6)return false;',
      '    if(printableRatio(s)<0.75)return false;',
      '    if(source==="crypto.encrypt")return true;',
      '    return /[\\{\\[]|prompt|message|messages|content|conversation|query|variables|graphql|model|token|auth|session|chat/i.test(s);',
      '  }',
      '  function capture(source,value,meta){',
      '    if(!window.__bhPlainProbeEnabled)return;',
      '    var text=textFrom(value);',
      '    if(!meaningful(text,source))return;',
      '    send({source:source,plainText:text.slice(0,MAX),plainSize:text.length,meta:meta||{},ts:Date.now()});',
      '  }',
      '  try{',
      '    var _stringify=JSON.stringify;',
      '    JSON.stringify=function(){',
      '      var out=_stringify.apply(this,arguments);',
      '      capture("JSON.stringify",out,{});',
      '      return out;',
      '    };',
      '  }catch(e){}',
      '  try{',
      '    var _encode=TextEncoder&&TextEncoder.prototype&&TextEncoder.prototype.encode;',
      '    if(_encode){TextEncoder.prototype.encode=function(input){capture("TextEncoder.encode",input,{});return _encode.apply(this,arguments);};}',
      '  }catch(e){}',
      '  try{',
      '    var subtle=crypto&&crypto.subtle;',
      '    var _encrypt=subtle&&subtle.encrypt;',
      '    if(_encrypt){subtle.encrypt=function(alg,key,data){var name=(alg&&alg.name)||String(alg||"");capture("crypto.encrypt",data,{algorithm:name});return _encrypt.apply(this,arguments);};}',
      '  }catch(e){}',
      '  try{',
      '    var _wsSend=WebSocket&&WebSocket.prototype&&WebSocket.prototype.send;',
      '    if(_wsSend){WebSocket.prototype.send=function(data){capture("WebSocket.send",data,{});return _wsSend.apply(this,arguments);};}',
      '  }catch(e){}',
      '})();',
    ].join('\n');
  }());

  // content script 接收 page world 发来的消息
  var netReqMap = {}; // reqId -> 请求条目（等待响应）
	  var _bpIsoPending = {}; // isolated 模式断点等待表 reqId -> resolve
	  var _respBpIsoPending = {}; // isolated 模式响应断点等待表 reqId -> resolve
	  var netBreakpointQueue = [];
	  var netActiveBreakpoint = null;
	  var netInterceptQueue = [];
	  var netSelIntercept = null;
	  var netInterceptRules = [];
	  var netRulesViewEl = null;
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
      if (fn) { delete _bpIsoPending[d.reqId]; fn(d); }
    } else if (d.type === 'respResolve') {
      var rf = _respBpIsoPending[d.reqId];
      if (rf) { delete _respBpIsoPending[d.reqId]; rf(d); }
    } else if (d.type === 'plain') {
      recordPlainCandidate(d);
    }
  });

  // 命中断点时的编辑框：放行(可改)/中止
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
    payload.__bhNet = true;
    payload.type = 'bpResolve';
    payload.reqId = reqId;
    // 回复 page world（拦截器在那里 await）。content/page 共享 window 的 message 通道
    try { window.postMessage(payload, '*'); } catch (e) {}
  }

  // 响应断点编辑结果：放行(可改 status/头/体)/中止
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

  function enqueueRespBreakpoint(d) {
    d.__resp = true;
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

  // 响应断点编辑框：改 状态码/响应头/响应体 后放行，或中止响应
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

  function injectInterceptor() {
    if (erudaMode === 'page') {
      // page world：直接用 <script> 注入（没有 CSP 问题，因为已经用 blob URL 加载了 eruda）
      runInPage(INTERCEPT_JS);
    } else {
      // isolated world（强 CSP 页面）：<script> 标签被 CSP 阻止。
      // 用 Gecko 专有 exportFunction/cloneInto API 把拦截函数直接导出到 page world，
      // 不经过 <script> 标签，完全绕过 CSP。
      injectInterceptorViaExportFunction();
    }
  }

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
	      return !!netGlobalInterceptEnabled && !isNoiseBeforeSend(url, method, body, headers);
	    }
    function waitBp(reqId, url, method, reqHeaders, reqBody, mode) {
      return new Promise(function (resolve) {
        _bpIsoPending[reqId] = resolve;
        sendNet({ type: 'breakpoint', reqId: reqId, url: url, method: method, reqHeaders: reqHeaders, reqBody: reqBody, mode: mode || 'breakpoint' });
      });
    }
    // 响应断点（isolated）：content-world Promise 等待编辑结果
    function waitRespBp(reqId, status, respHeaders, respBody) {
      return new Promise(function (resolve) {
        _respBpIsoPending[reqId] = resolve;
        sendNet({ type: 'respBreakpoint', reqId: reqId, status: status, respHeaders: respHeaders, respBody: respBody });
      });
    }

    // ── 替换 page world 的 fetch ──
	    var _origFetch = pw.__bhRestoreFetch || pw.fetch;
    exportFunction(function (input, init) {
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      var method = ((init && init.method) || (input && input.method) || 'GET').toUpperCase();
      var reqHeaders = headersToObj(init && init.headers);
      var reqBody = (init && init.body != null) ? String(init.body) : null;
      var replacedBody = applyReplaceRules(reqBody);
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
      var respStop = !!_bp && (_bp.stage === 'resp' || _bp.stage === 'both');
      // 响应断点（isolated）：读完整 body（流式被攒成整段），暂停编辑后构造 pw.Response 放行。
      // 跨世界：返回值必须是 pw.Response；page-world promise 的回调必须 exportFunction。
      function handleRespBp(p) {
        return new pw.Promise(exportFunction(function (resolve, reject) {
          p.then(exportFunction(function (resp) {
            var status = resp.status;
            var respHeaders = headersToObj(resp.headers);
            resp.text().then(exportFunction(function (body) {
              sendNet({ type: 'resp', reqId: reqId, status: status, respHeaders: respHeaders,
                respBody: body.slice(0, 102400), duration: Date.now() - t0 });
              waitRespBp(reqId, status, respHeaders, body).then(function (r) {
                if (r.action === 'abort') {
                  sendNet({ type: 'resp', reqId: reqId, status: 0, error: '响应已被断点中止', duration: Date.now() - t0 });
                  reject(new pw.Error('aborted by response breakpoint'));
                  return;
                }
                var nb = (r.respBody != null) ? r.respBody : body;
                var ns = r.status || status;
                var nh = r.respHeaders || respHeaders;
                try { resolve(new pw.Response(nb, cloneInto({ status: ns, headers: nh }, pw))); }
                catch (e) {
                  try { resolve(new pw.Response(nb, cloneInto({ status: ns }, pw))); }
                  catch (e2) { try { resolve(new pw.Response(nb)); } catch (e3) { reject(new pw.Error(String(e3))); } }
                }
              });
            }, pw), exportFunction(function () { resolve(resp); }, pw));
          }, pw), exportFunction(function (err) { reject(err); }, pw));
        }, pw));
      }
      var doFetch = function () {
        var p = _origFetch.call(pw, fInput, fInit); // page-world promise
        if (respStop) return handleRespBp(p);
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

    // ── 替换 page world 的 XMLHttpRequest ──
	    var _OrigXHR = pw.__bhRestoreXHR || pw.XMLHttpRequest;
    exportFunction(function () {
      var xhr = new _OrigXHR();
      var _method = 'GET', _url = '', _reqHeaders = {}, _reqBody = null, _reqId = uid(), _t0 = 0;
      exportFunction(function (method, url) { _method = method.toUpperCase(); _url = url; return xhr.open.apply(xhr, arguments); }, xhr, { defineAs: 'open' });
      exportFunction(function (k, v) { _reqHeaders[k] = v; return xhr.setRequestHeader(k, v); }, xhr, { defineAs: 'setRequestHeader' });
      exportFunction(function (body) {
        _reqBody = body != null ? String(body) : null;
        var _rb2 = applyReplaceRules(_reqBody);
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
            var w = xhr.wrappedJSObject || xhr;
            Object.defineProperty(w, 'readyState', { configurable: true, get: exportFunction(function () { return 4; }, pw) });
            Object.defineProperty(w, 'status', { configurable: true, get: exportFunction(function () { return _ms; }, pw) });
            Object.defineProperty(w, 'responseText', { configurable: true, get: exportFunction(function () { return _mb; }, pw) });
            Object.defineProperty(w, 'response', { configurable: true, get: exportFunction(function () { return _mb; }, pw) });
          } catch (e) {}
          pw.setTimeout(exportFunction(function () {
            try { if (xhr.onreadystatechange) xhr.onreadystatechange(); } catch (e) {}
            try { xhr.dispatchEvent(new pw.Event('readystatechange')); } catch (e) {}
            try { xhr.dispatchEvent(new pw.Event('load')); } catch (e) {}
            try { if (xhr.onload) xhr.onload(); } catch (e) {}
            try { if (xhr.onloadend) xhr.onloadend(); } catch (e) {}
          }, pw), 0);
          return;
        }
        var doSend = function () {
          xhr.addEventListener('readystatechange', function () {
            if (xhr.readyState !== 4) return;
            var respHeaders = {};
            try {
              (xhr.getAllResponseHeaders() || '').split('\r\n').forEach(function (l) {
                var i = l.indexOf(':'); if (i < 0) return;
                respHeaders[l.slice(0, i).trim()] = l.slice(i + 1).trim();
              });
            } catch (e) {}
            sendNet({ type: 'resp', reqId: _reqId, status: xhr.status,
              respHeaders: respHeaders, respBody: (xhr.responseText || '').slice(0, 102400),
              duration: Date.now() - _t0 });
          });
          xhr.send(body);
        };
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

  function syncPlainProbeEnabled() {
    if (erudaMode !== 'page' && typeof window.wrappedJSObject !== 'undefined') {
      try { window.wrappedJSObject.__bhPlainProbeEnabled = !!netPlainProbeEnabled; return; } catch (e) {}
    }
    runInPage('(function(){window.__bhPlainProbeEnabled=' + (netPlainProbeEnabled ? 'true' : 'false') + ';})();');
  }

	  function syncGlobalInterceptEnabled() {
	    if (erudaMode !== 'page' && typeof window.wrappedJSObject !== 'undefined') {
	      try { window.wrappedJSObject.__bhGlobalInterceptEnabled = !!netGlobalInterceptEnabled; return; } catch (e) {}
	    }
	    runInPage('(function(){window.__bhGlobalInterceptEnabled=' + (netGlobalInterceptEnabled ? 'true' : 'false') + ';})();');
	  }

	  function syncReplaceRules() {
	    var active = netReplaceEnabled ? netReplaceRules.filter(function (r) { return r.enabled; }) : [];
	    if (erudaMode !== 'page' && typeof window.wrappedJSObject !== 'undefined' && typeof cloneInto !== 'undefined') {
	      try { window.wrappedJSObject.__bhReplaceRules = cloneInto(active, window); return; } catch (e) {}
	    }
	    runInPage('(function(){window.__bhReplaceRules=' + JSON.stringify(active) + ';})();');
	  }

	  function disableInterceptor() {
	    if (erudaMode !== 'page' && typeof window.wrappedJSObject !== 'undefined') {
	      try {
	        var pw = window.wrappedJSObject;
	        pw.__bhNetInstalled = false;
	        if (pw.__bhRestoreFetch) pw.fetch = pw.__bhRestoreFetch;
	        if (pw.__bhRestoreXHR) pw.XMLHttpRequest = pw.__bhRestoreXHR;
	        return;
	      } catch (e) {}
	    }
	    runInPage('(function(){window.__bhNetInstalled=false;if(window.__bhRestoreFetch){window.fetch=window.__bhRestoreFetch;}if(window.__bhRestoreXHR){window.XMLHttpRequest=window.__bhRestoreXHR;}})();');
	  }

	  function setNetworkCaptureEnabled(enabled) {
	    netEnabled = !!enabled;
	    if (netEnableBtn) netEnableBtn.textContent = netEnabled ? '● 监听中' : '○ 已停止';
	    if (!netEnabled) {
	      releaseAllIntercepts();
	      disableInterceptor();
	      return;
	    }
	    injectInterceptor();
	    syncGlobalInterceptEnabled();
	    syncInterceptRules();
	    injectBreakpoints();
	    injectMockRules();
	    applyThrottle(netThrottle);
	    injectPlainProbe();
	  }

	  function injectPlainProbe() {
    syncPlainProbeEnabled();
    if (erudaMode === 'page') {
      runInPage(PLAIN_PROBE_JS);
      setTimeout(syncPlainProbeEnabled, 50);
      return;
    }
    injectPlainProbeViaExportFunction();
    syncPlainProbeEnabled();
  }

  function plainTextFromValue(v) {
    if (v == null) return null;
    if (typeof v === 'string') return v;
    try {
      if (v instanceof ArrayBuffer) {
        return new TextDecoder('utf-8', { fatal: false }).decode(new Uint8Array(v));
      }
      if (ArrayBuffer.isView && ArrayBuffer.isView(v)) {
        return new TextDecoder('utf-8', { fatal: false }).decode(new Uint8Array(v.buffer, v.byteOffset, v.byteLength));
      }
    } catch (e) {}
    try {
      if (v && typeof v.byteLength === 'number') {
        return new TextDecoder('utf-8', { fatal: false }).decode(new Uint8Array(v.buffer || v, v.byteOffset || 0, v.byteLength));
      }
    } catch (e2) {}
    return null;
  }

  function plainPrintableRatio(s) {
    if (!s) return 0;
    var ok = 0;
    var n = Math.min(s.length, 2048);
    for (var i = 0; i < n; i++) {
      var c = s.charCodeAt(i);
      if (c === 9 || c === 10 || c === 13 || c >= 32) ok++;
    }
    return n ? ok / n : 0;
  }

  function plainMeaningful(s, source) {
    if (!s || s.length < 6) return false;
    if (plainPrintableRatio(s) < 0.75) return false;
    if (source === 'crypto.encrypt') return true;
    return /[\{\[]|prompt|message|messages|content|conversation|query|variables|graphql|model|token|auth|session|chat/i.test(s);
  }

  function capturePlainValue(source, value, meta) {
    if (!netPlainProbeEnabled) return;
    var text = plainTextFromValue(value);
    if (!plainMeaningful(text, source)) return;
    recordPlainCandidate({
      source: source,
      plainText: text.slice(0, 65536),
      plainSize: text.length,
      meta: meta || {},
      ts: Date.now(),
    });
  }

  function injectPlainProbeViaExportFunction() {
    if (typeof exportFunction === 'undefined' || typeof window.wrappedJSObject === 'undefined') {
      runInPage(PLAIN_PROBE_JS);
      return;
    }
    var pw = window.wrappedJSObject;
    if (pw.__bhPlainProbeInstalled) return;
    pw.__bhPlainProbeInstalled = true;

    try {
      var jsonObj = pw.JSON;
      var _stringify = jsonObj && jsonObj.stringify;
      if (_stringify) {
        exportFunction(function () {
          var out = _stringify.apply(jsonObj, arguments);
          capturePlainValue('JSON.stringify', out, {});
          return out;
        }, jsonObj, { defineAs: 'stringify' });
      }
    } catch (e) {}

    try {
      var encProto = pw.TextEncoder && pw.TextEncoder.prototype;
      var _encode = encProto && encProto.encode;
      if (_encode) {
        exportFunction(function (input) {
          capturePlainValue('TextEncoder.encode', input, {});
          return _encode.apply(this, arguments);
        }, encProto, { defineAs: 'encode' });
      }
    } catch (e2) {}

    try {
      var subtle = pw.crypto && pw.crypto.subtle;
      var _encrypt = subtle && subtle.encrypt;
      if (_encrypt) {
        exportFunction(function (alg, key, data) {
          var name = '';
          try { name = (alg && alg.name) || String(alg || ''); } catch (e) {}
          capturePlainValue('crypto.encrypt', data, { algorithm: name });
          return _encrypt.apply(subtle, arguments);
        }, subtle, { defineAs: 'encrypt' });
      }
    } catch (e3) {}

    try {
      var wsProto = pw.WebSocket && pw.WebSocket.prototype;
      var _wsSend = wsProto && wsProto.send;
      if (_wsSend) {
        exportFunction(function (data) {
          capturePlainValue('WebSocket.send', data, {});
          return _wsSend.apply(this, arguments);
        }, wsProto, { defineAs: 'send' });
      }
    } catch (e4) {}
  }
  // ── /网络拦截层 ──────────────────────────────────────────────────────────────

  // ── Eruda 汉化 ──────────────────────────────────────────────────────────────
  var I18N_MAP = [
    // 顶部 Tab 名（DOM 里是小写，靠 CSS text-transform:capitalize 显示成首字母大写，
    // 所以这里必须用小写键才能匹配到文本节点）
    ['console', '控制台'], ['elements', '元素'], ['network', '网络'],
    ['resources', '存储'], ['sources', '源码'], ['info', '信息'],
    ['snippets', '代码片段'], ['settings', '设置'],
    // Console 面板
    ['Clear', '清空'], ['Filter', '过滤'], ['Preserve Log', '保留日志'],
    ['Show Timestamp', '显示时间戳'], ['Log', '日志'], ['Warn', '警告'],
    ['Error', '错误'], ['Info', '信息'], ['Debug', '调试'],
    ['Verbose', '详细'], ['Output', '输出'], ['JS', 'JS'],
    ['All', '全部'], ['Console', '控制台'],
    // Elements 面板
    ['Computed', '计算值'], ['Event Listeners', '事件监听器'],
    ['Styles', '样式'], ['Dom Tree', 'DOM 树'], ['Attributes', '属性'],
    // Resources 面板
    ['Local Storage', '本地存储'], ['Session Storage', '会话存储'],
    ['IndexedDB', 'IndexedDB'], ['Cache Storage', '缓存存储'],
    ['ServiceWorker', 'Service Worker'], ['Cookie', 'Cookie'],
    ['Scripts', '脚本'], ['Stylesheets', '样式表'], ['Images', '图片'],
    // Info 面板
    ['Location', '页面地址'], ['System', '系统信息'], ['About', '关于'],
    ['Backend', '后端'], ['Screen', '屏幕'],
    ['Browser', '浏览器'], ['Engine', '引擎'], ['OS', '操作系统'],
    ['Device', '设备'], ['CPU', '处理器'], ['Memory', '内存'],
    ['Language', '语言'], ['Languages', '语言列表'], ['Online', '在线'],
    ['Offline', '离线'], ['Platform', '平台'], ['Vendor', '厂商'],
    ['Cookie Enabled', 'Cookie 已启用'], ['Cookies Enabled', 'Cookie 已启用'],
    ['Hardware Concurrency', '硬件线程数'], ['Max Touch Points', '最大触点数'],
    ['Resolution', '分辨率'], ['Viewport', '视口'], ['Pixel Ratio', '像素比'],
    ['Color Depth', '色深'], ['Orientation', '方向'], ['Referrer', '来源页面'],
    ['Title', '标题'], ['Charset', '字符集'], ['Compat Mode', '兼容模式'],
    ['History Length', '历史长度'], ['Protocol', '协议'], ['Host', '主机'],
    ['Hostname', '主机名'], ['Port', '端口'], ['Pathname', '路径'],
    // Settings 面板
    ['Theme', '主题'], ['Transparency', '透明度'], ['Display Size', '显示大小'],
    ['Dark', '深色'], ['Light', '浅色'], ['Apply', '应用'],
    ['Close', '关闭'], ['Default', '默认'], ['Settings', '设置'],
    ['Log Level', '日志级别'], ['Max Log Number', '最大日志数'],
    ['Overflow', '溢出'], ['Wrap Long Lines', '长行换行'],
    // 通用按钮/标签
    ['Refresh', '刷新'], ['Copy', '复制'], ['Delete', '删除'],
    ['Expand', '展开'], ['Collapse', '折叠'], ['Search', '搜索'],
    ['Clear All', '全部清空'], ['Select All', '全选'],
    ['Cancel', '取消'], ['Confirm', '确认'], ['Save', '保存'],
    ['Reset', '重置'], ['Enable', '启用'], ['Disable', '禁用'],
    ['Open', '打开'], ['Add', '添加'], ['Edit', '编辑'],
    // Sources 面板（部分标签）
    ['Beautify', '格式化'], ['Word Wrap', '自动换行'],
    // ── Settings 面板（eruda 真实英文标签，逐字匹配）──
    ['Asynchronous Rendering', '异步渲染'],
    ['Enable JavaScript Execution', '启用 JavaScript 执行'],
    ['Catch Global Errors', '捕获全局错误'],
    ['Override Console', '接管 Console'],
    ['Auto Display If Error Occurs', '出错时自动显示'],
    ['Display Extra Information', '显示额外信息'],
    ['Display Unenumerable Properties', '显示不可枚举属性'],
    ['Access Getter Value', '读取 Getter 值'],
    ['Lazy Evaluation', '延迟求值'],
    ['Catch Event Listeners', '捕获事件监听器'],
    ['Auto Refresh Elements', '自动刷新元素'],
    ['Hide Eruda Setting', '隐藏 Eruda 设置'],
    ['Show Line Numbers', '显示行号'],
    ['Remember Entry Button Position', '记住入口按钮位置'],
    ['Restore defaults and reload', '恢复默认并重新加载'],
    ['System preference', '跟随系统'],
    ['infinite', '不限'],
    // ── Elements 盒模型分类名（DOM 里是小写，靠 capitalize 显示）──
    ['margin', '外边距'], ['border', '边框'], ['padding', '内边距'],
    ['content', '内容'], ['element.style', 'element.style'],
	    // ── Info / Snippets 分区与条目名 ──
	    ['User Agent', '用户代理'], ['Device', '设备'],
	    ['URL', '网址'], ['Url', '网址'], ['Origin', '源'], ['Domain', '域名'],
	    ['Hash', '片段'], ['Query', '查询参数'], ['Document', '文档'],
	    ['App Name', '应用名称'], ['App Version', '应用版本'],
	    ['Browser Version', '浏览器版本'], ['Engine Version', '引擎版本'],
	    ['Product', '产品'], ['Product Sub', '产品子版本'], ['Vendor Sub', '厂商子版本'],
	    ['Build ID', '构建 ID'], ['Do Not Track', '禁止跟踪'],
	    ['Device Pixel Ratio', '设备像素比'], ['Screen Size', '屏幕尺寸'],
	    ['Window Size', '窗口尺寸'], ['Touch Support', '触控支持'],
	    ['Timezone', '时区'], ['Timezone Offset', '时区偏移'],
	    ['Connection', '网络连接'], ['Effective Type', '有效网络类型'],
	    ['Downlink', '下行速度'], ['RTT', '往返延迟'], ['Save Data', '省流量模式'],
	    ['Java Enabled', 'Java 已启用'], ['PDF Viewer Enabled', 'PDF 查看器已启用'],
	    ['Storage', '存储'], ['Quota', '配额'], ['Usage', '用量'],
	    ['Border All', '显示所有边框'], ['Refresh Page', '刷新页面'],
    ['Search Text', '搜索文本'], ['Edit Page', '编辑页面'],
    ['Fit Screen', '适应屏幕'],
  ];

  var I18N_DICT = null;
  // 数据展示区：这些容器里的文本是用户数据（console 输出、DOM 内容、存储的值、
  // JSON/对象值等），绝不能翻译，否则数据会被破坏。
  // 注意：只匹配"真正承载数据值"的容器——
  //   luna-console        控制台输出
  //   luna-data-grid-data 表格的数据行（表头 luna-data-grid-header-* 不在内，分类名可翻译）
  //   luna-dom-viewer     DOM 树内容
  //   luna-object-viewer  对象属性值
  //   luna-json           JSON 数据
  //   bh-net              自定义网络面板（URL/请求体等都是数据）
  // 不再整块屏蔽 eruda-resources/eruda-sources/eruda-logs 面板，
  // 这样面板里的分类名/表头/区块标题可以被翻译。
  var DATA_REGION_RE = /luna-console|luna-data-grid-data|luna-dom-viewer|luna-object-viewer|luna-json|bh-net/;
  function inDataRegion(node) {
    var el = node.parentNode;
    while (el && el.nodeType === 1) {
      var cls = el.className;
      if (typeof cls === 'string' && DATA_REGION_RE.test(cls)) {
        return true;
      }
      el = el.parentNode;
    }
    return false;
  }

  function replaceTextNodes(root) {
    if (!root) return;
    if (!I18N_DICT) {
      I18N_DICT = {};
      for (var j = 0; j < I18N_MAP.length; j++) I18N_DICT[I18N_MAP[j][0]] = I18N_MAP[j][1];
    }
    function tr(node) {
      var v = node.nodeValue;
      if (!v) return;
      var t = v.trim();
      if (!t) return;
      var zh = I18N_DICT[t];
      if (!zh || zh === t) return;
      if (inDataRegion(node)) return; // 数据区不翻译
      node.nodeValue = v.replace(t, zh);
    }
    if (root.nodeType === 3) { tr(root); return; }
    if (root.nodeType !== 1 && root.nodeType !== 11) return;
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
    var node;
    while ((node = walker.nextNode())) tr(node);
  }

  function _i18nCore() {
    // page-world 模式：shadow root 在页面 window 里，content script 无法直接访问
    // 用 runInPage 注入一段脚本在页面 window 里操作
    if (erudaMode === 'page') {
      var mapJson = JSON.stringify(I18N_MAP);
      runInPage([
        '(function(){',
        '  var host=document.getElementById("eruda");',
        '  if(!host||!host.shadowRoot)return;',
        '  var root=host.shadowRoot;',
        '  var MAP=' + mapJson + ';',
        '  var DICT={};for(var k=0;k<MAP.length;k++){DICT[MAP[k][0]]=MAP[k][1];}',
        '  var DATA_RE=/luna-console|luna-data-grid-data|luna-dom-viewer|luna-object-viewer|luna-json|bh-net/;',
        '  function inData(node){',
        '    var el=node.parentNode;',
        '    while(el&&el.nodeType===1){',
        '      var c=el.className;',
        '      if(typeof c==="string"&&DATA_RE.test(c))return true;',
        '      el=el.parentNode;',
        '    }',
        '    return false;',
        '  }',
        '  function tr(node){',
        '    var v=node.nodeValue;if(!v)return;',
        '    var t=v.trim();if(!t)return;',
        '    var zh=DICT[t];',
        '    if(!zh||zh===t)return;',
        '    if(inData(node))return;',  // 数据区不翻译，保护用户数据
        '    node.nodeValue=v.replace(t,zh);',  // 保留原前后空白
        '  }',
        '  function walk(el){',
        '    if(!el)return;',
        '    if(el.nodeType===3){tr(el);return;}',
        '    if(el.nodeType!==1&&el.nodeType!==11)return;',
        '    var tw=document.createTreeWalker(el,NodeFilter.SHOW_TEXT,null,false);',
        '    var n;while((n=tw.nextNode())){tr(n);}',
        '  }',
        '  walk(root);',
        '  var obs=new MutationObserver(function(muts){',
        '    muts.forEach(function(m){',
        '      if(m.type==="characterData"){tr(m.target);return;}',
        '      m.addedNodes.forEach(function(node){walk(node);});',
        '    });',
        '  });',
        '  obs.observe(root,{childList:true,subtree:true,characterData:true});',
        // 懒加载的 tab 面板在切换时才渲染；定时重扫几次兜底
        '  var c=0;var iv=setInterval(function(){walk(root);if(++c>=10)clearInterval(iv);},500);',
        '})();',
      ].join('\n'));
      return;
    }
    // isolated-world 模式：可以直接访问 DOM（包括 shadow root）
    var host = document.getElementById('eruda');
    var root = host && (host.shadowRoot || host);
    if (!root) return;
    replaceTextNodes(root);
    var obs = new MutationObserver(function (muts) {
      muts.forEach(function (m) {
        if (m.type === 'characterData') { replaceTextNodes(m.target); return; }
        m.addedNodes.forEach(function (node) { replaceTextNodes(node); });
      });
    });
    obs.observe(root, { childList: true, subtree: true, characterData: true });
    var c = 0;
    var iv = setInterval(function () { replaceTextNodes(root); if (++c >= 10) clearInterval(iv); }, 500);
  }
  // ── /Eruda 汉化 ─────────────────────────────────────────────────────────────

  // ── 抓包面板 ─────────────────────────────────────────────────────────────────
  var NET_STYLE = [
    '*{box-sizing:border-box;margin:0;padding:0;}',
    // 面板根：固定浅色主题，不依赖 CSS 变量（shadow root 内变量继承不可靠）
    '#bh-net{display:flex;flex-direction:column;height:100%;font-size:15px;',
    '  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;',
    '  background:#fff;color:#111;pointer-events:auto;touch-action:auto;}',
    // 顶部工具栏
    '#bh-bar{position:relative;z-index:2;display:flex;align-items:center;gap:8px;padding:8px 10px;',
    '  border-bottom:1px solid #d0d7de;flex-wrap:wrap;background:#f6f8fa;}',
    '#bh-bar button{font-size:14px;padding:8px 12px;border-radius:6px;min-height:40px;',
    '  border:1px solid #d0d7de;background:#fff;color:#111;cursor:pointer;white-space:nowrap;}',
    '#bh-bar button:active{background:#e8eaed;}',
    '#bh-filter-wrap{position:relative;display:inline-flex;}',
    '#bh-filter-menu{display:none;position:absolute;left:0;top:calc(100% + 5px);min-width:128px;',
    '  padding:4px;background:#fff;border:1px solid #d0d7de;border-radius:6px;',
    '  box-shadow:0 8px 24px rgba(0,0,0,.18);z-index:2147483642;}',
    '#bh-filter-menu.open{display:flex;flex-direction:column;gap:2px;}',
    '#bh-filter-menu button{width:100%;text-align:left;border:none;border-radius:4px;',
    '  background:#fff;min-height:36px;padding:7px 10px;}',
    '#bh-filter-menu button:active{background:#e8eaed;}',
    '#bh-filter{flex:1;min-width:100px;font-size:14px;padding:8px 10px;border-radius:6px;min-height:40px;',
    '  border:1px solid #d0d7de;background:#fff;color:#111;}',
    // 请求列表
	    '#bh-list{flex:1 1 auto;min-height:0;overflow-y:auto;border-bottom:1px solid #d0d7de;}',
	    '#bh-empty{padding:20px;text-align:center;color:#888;font-size:14px;}',
	    '.bh-section-title{position:sticky;top:0;z-index:1;padding:6px 10px;border-bottom:1px solid #d0d7de;',
	    '  background:#f6f8fa;color:#374151;font-size:12px;font-weight:700;}',
	    '.bh-section-empty{padding:12px 10px;border-bottom:1px solid #eaecef;color:#888;font-size:12px;background:#fff;}',
	    '.bh-row{position:relative;display:flex;align-items:center;padding:10px 66px 10px 10px;min-height:58px;',
	    '  border-bottom:1px solid #eaecef;cursor:pointer;gap:8px;background:#fff;}',
	    '.bh-row:active,.bh-row.bh-sel{background:#dbeafe;}',
	    '.bh-row.bh-intercept{background:#fff7ed;}',
	    '.bh-row.bh-intercept.bh-sel,.bh-row.bh-intercept:active{background:#fed7aa;}',
    '.bh-method{font-weight:700;min-width:46px;font-size:13px;color:#111;}',
    '.bh-main{flex:1;min-width:0;display:flex;flex-direction:column;gap:3px;}',
    '.bh-url{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:14px;color:#111;}',
    '.bh-meta{font-size:11px;color:#6b7280;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}',
    '.bh-tag{font-size:11px;padding:2px 6px;border-radius:3px;background:#6366f1;color:#fff;}',
    // 状态颜色
    '.s2{color:#16a34a;}.s3{color:#2563eb;}.s4{color:#ea580c;}.s5{color:#dc2626;}.s0{color:#888;}.s-err{color:#dc2626;font-style:italic;}',
    '.bh-status-wrap{position:absolute;right:8px;top:7px;display:flex;flex-direction:column;align-items:flex-end;min-width:44px;}',
    '.bh-status{font-size:11px;font-weight:700;line-height:1.15;}',
    '.bh-status-desc{font-size:9px;color:#888;line-height:1.1;max-width:52px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}',
    // 详情区：展开后占面板大部分高度，让请求/响应体有足够阅读空间
    '#bh-detail{flex:1 1 auto;min-height:0;overflow:hidden;display:flex;flex-direction:column;background:#fff;}',
    '#bh-detail-head{display:flex;align-items:stretch;border-bottom:1px solid #d0d7de;background:#f6f8fa;flex:0 0 auto;}',
    '#bh-detail-tabs{display:flex;overflow-x:auto;background:#f6f8fa;flex:1 1 auto;min-width:0;}',
    '.bh-dtab{padding:10px 14px;font-size:14px;cursor:pointer;white-space:nowrap;color:#111;',
    '  min-height:42px;display:flex;align-items:center;border-bottom:2px solid transparent;}',
    '.bh-dtab.active{border-bottom-color:#2563eb;font-weight:700;}',
    '#bh-detail-close{flex:0 0 44px;min-width:44px;border:none;border-left:1px solid #d0d7de;',
    '  background:#f6f8fa;color:#555;font-size:24px;line-height:1;cursor:pointer;}',
    '#bh-detail-close:active{background:#e8eaed;color:#111;}',
    // textarea 代替 div：原生支持长按选中复制、单点光标编辑
	    '#bh-detail-body{flex:1 1 auto;min-height:200px;overflow:auto;padding:10px;font-size:13px;color:#111;',
	    '  white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;font-family:monospace;background:#fff;',
    '  border:none;outline:none;resize:none;width:100%;line-height:1.5;}',
    '#bh-detail-acts{display:flex;gap:8px;padding:8px 10px;flex-wrap:wrap;flex:0 0 auto;',
    '  border-top:1px solid #d0d7de;background:#f6f8fa;}',
    '#bh-detail-acts button{font-size:14px;padding:8px 12px;border-radius:6px;min-height:40px;',
    '  border:1px solid #d0d7de;background:#fff;color:#111;cursor:pointer;}',
	    '#bh-detail-acts button:active{background:#e8eaed;}',
	    // 编辑时不再移动详情区，避免 Android 软键盘/滚动联动造成上下弹跳。
	    '#bh-rules-view{display:none;position:fixed;inset:0;z-index:2147483646;background:#fff;color:#111;',
	    '  flex-direction:column;pointer-events:auto;touch-action:auto;}',
	    '#bh-rules-view.open{display:flex;}',
	    '#bh-rules-head{display:flex;align-items:center;gap:8px;padding:10px 12px;border-bottom:1px solid #d0d7de;background:#f6f8fa;}',
	    '#bh-rules-title{flex:1;font-size:15px;font-weight:700;}',
	    '#bh-rules-close{flex:0 0 44px;min-width:44px;border:1px solid #d0d7de;border-radius:6px;background:#fff;color:#555;',
	    '  font-size:24px;line-height:1;min-height:40px;}',
	    '#bh-rules-list{flex:1;min-height:0;overflow-y:auto;}',
	    '.bh-rule-row{display:flex;align-items:center;gap:8px;padding:10px 12px;min-height:62px;border-bottom:1px solid #eaecef;background:#fff;}',
	    '.bh-rule-row:active{background:#dbeafe;}',
	    '.bh-rule-action{flex:0 0 42px;text-align:center;border-radius:4px;padding:3px 0;font-size:12px;font-weight:700;color:#fff;}',
	    '.bh-rule-action.pass{background:#16a34a;}.bh-rule-action.intercept{background:#dc2626;}',
	    '.bh-rule-main{flex:1;min-width:0;display:flex;flex-direction:column;gap:3px;}',
	    '.bh-rule-url{font-size:13px;color:#111;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}',
	    '.bh-rule-meta{font-size:11px;color:#6b7280;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}',
	    '.bh-rule-empty{padding:24px;text-align:center;color:#888;font-size:13px;}',
	    // 模态弹窗（挂进 #bh-net / shadow root 内，z-index 要高于 eruda 面板内部元素）
    '#bh-modal{position:fixed;inset:0;z-index:2147483647;display:flex;align-items:center;',
    '  justify-content:center;background:rgba(0,0,0,.5);}',
    '#bh-modal-box{background:#fff;border-radius:8px;color:#111;',
    '  width:90vw;max-height:85vh;display:flex;flex-direction:column;overflow:hidden;}',
    '#bh-modal-title{padding:10px 12px;font-weight:700;font-size:14px;',
    '  border-bottom:1px solid #d0d7de;}',
    '#bh-modal-body{flex:1;overflow-y:auto;padding:10px 12px;display:flex;flex-direction:column;gap:8px;}',
    '#bh-modal-body label{font-size:12px;font-weight:600;}',
    '#bh-modal-body input,#bh-modal-body textarea,#bh-modal-body select{',
    '  width:100%;font-size:12px;padding:6px;border-radius:4px;',
    '  border:1px solid #d0d7de;background:#f6f8fa;color:#111;font-family:monospace;}',
    '#bh-modal-body textarea{resize:vertical;min-height:80px;}',
    '#bh-modal-acts{display:flex;gap:8px;padding:8px 12px;',
    '  border-top:1px solid #d0d7de;justify-content:flex-end;}',
    '#bh-modal-acts button{font-size:13px;padding:6px 14px;border-radius:4px;',
    '  border:1px solid #d0d7de;cursor:pointer;}',
    '#bh-btn-ok{background:#2563eb;color:#fff;border-color:#2563eb;}',
    '#bh-btn-cancel{background:#f6f8fa;color:#111;}',
    // 空状态
    '#bh-empty{padding:24px;text-align:center;color:#888;font-size:13px;}',
    // 断点高亮
    '.bh-row.bh-bp{background:#fef3c7;}',
    // 额外功能下拉菜单
    '#bh-extra-wrap{position:relative;display:inline-flex;}',
    '#bh-extra-menu{display:none;position:absolute;left:0;top:calc(100% + 5px);min-width:140px;' +
    '  padding:4px;background:#fff;border:1px solid #d0d7de;border-radius:6px;' +
    '  box-shadow:0 8px 24px rgba(0,0,0,.18);z-index:2147483642;}',
    '#bh-extra-menu.open{display:flex;flex-direction:column;gap:2px;}',
    '#bh-extra-menu button{width:100%;text-align:left;border:none;border-radius:4px;' +
    '  background:#fff;min-height:40px;padding:7px 10px;font-size:14px;cursor:pointer;}',
    '#bh-extra-menu button:active{background:#e8eaed;}',
    // 详情搜索栏
    '#bh-detail-search{display:flex;align-items:center;gap:6px;padding:4px 10px;flex:0 0 auto;' +
    '  border-bottom:1px solid #d0d7de;background:#f6f8fa;}',
    '#bh-dsearch{flex:1;font-size:13px;padding:5px 8px;border-radius:4px;min-height:32px;' +
    '  border:1px solid #d0d7de;background:#fff;color:#111;}',
    '#bh-dsearch-count{font-size:12px;color:#6b7280;white-space:nowrap;min-width:32px;text-align:right;}',
    '#bh-dsearch-nav button{font-size:16px;padding:2px 8px;border-radius:4px;min-height:32px;' +
    '  border:1px solid #d0d7de;background:#fff;color:#111;cursor:pointer;}',
    '#bh-dsearch-nav button:active{background:#e8eaed;}',
  ].join('');

  var netPanel = null;   // 根 div
  var netListEl = null;
  var netDetailEl = null;
  var netDetailBody = null;
  var netDetailActs = null;
  var netFilterEl = null;
  var netEnableBtn = null;
	  var netSelReq = null;  // 当前选中的请求条目
	  var netDetailTab = 0;  // 0=请求头 1=请求体 2=响应头 3=响应体 4=明文
	  var netModal = null;   // 当前弹窗 element
	  var netEditing = false; // 详情 textarea 是否正在被编辑（编辑时不覆盖内容）
	  var netDetailDirty = false;
	  var netHideTunnelNoise = false;
  var netPayloadOnly = false;
  var netPlainProbeEnabled = false;
  var netGlobalInterceptEnabled = false;
  var netFilterMenuOpen = false;
  var netExtraMenuOpen = false;
  var netReplaceRules = [];
  var netReplaceEnabled = false;
  var netDetailSearchText = '';
  var netDetailSearchMatches = [];
  var netDetailSearchIdx = 0;

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
	      rule.updatedAt = Date.now();
	      return rule;
	    });
	    if (!replaced) netInterceptRules.push(rule);
	    saveInterceptRules();
	    return rule;
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

  function isTunnelNoiseReq(r) {
    var url = String(r.url || '').toLowerCase();
    var method = String(r.method || 'GET').toUpperCase();
    var rb = byteLen(r.reqBody);
    var sb = byteLen(r.respBody);
    var total = reqSize(r) + respSize(r);
    var noiseRe = /\/(cdn-cgi|collect|telemetry|analytics|metrics|beacon|heartbeat|ping|events?|log|logs|sentry|trace|traces|socket\.io|sockjs|realtime|tunnel|connect|presence|typing|status|health|alive|poll)([/?#_.-]|$)|[?&](ping|heartbeat|keepalive|beacon)=/;
    if (!noiseRe.test(url)) return false;
    if (rb > 512) return false;
    if (method !== 'GET' && rb > 0) return false;
    return total < 8 * 1024 || sb === 0;
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

	  function syncCurrentDetailEdit() {
	    if (!netDetailDirty || !netDetailBody) return;
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

	  function getVisibleRequests() {
    var filter = netFilterEl ? netFilterEl.value.trim().toLowerCase() : '';
    return netRequests.filter(function (r) {
      if (filter && String(r.url || '').toLowerCase().indexOf(filter) === -1) return false;
      if (netHideTunnelNoise && isTunnelNoiseReq(r)) return false;
      if (netPayloadOnly && !isPayloadReq(r)) return false;
      return true;
    });
  }

  function selectFirstVisibleRequest(preferPayload) {
    var rows = getVisibleRequests();
    if (!rows.length) {
      netSelReq = null;
      netSelIntercept = null;
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
    netDetailTab = byteLen(picked.reqBody) > 0 ? 1 : 3;
    setNetEditing(false);
    renderNetList();
    renderDetail(true);
  }

  function maybeFocusPayload(entry) {
    if (!netPayloadOnly || netEditing || netSelIntercept || !isPayloadReq(entry)) return;
    if (netHideTunnelNoise && isTunnelNoiseReq(entry)) return;
    netSelReq = entry;
    netDetailTab = byteLen(entry.reqBody) > 0 ? 1 : 3;
  }

  function renderNetList() {
    if (!netListEl) return;
    var rows = getVisibleRequests();
    var showIntercepts = netGlobalInterceptEnabled || netInterceptQueue.length > 0;
    if (rows.length === 0 && !showIntercepts) {
      netListEl.innerHTML = '<div id="bh-empty">暂无匹配请求</div>';
      return;
    }
    var html = '';
    if (showIntercepts) {
      html += '<div class="bh-section-title">拦截中 ' + netInterceptQueue.length + '</div>';
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
    // 绑定点击
	    Array.prototype.forEach.call(netListEl.querySelectorAll('.bh-intercept'), function (el) {
	      el.addEventListener('click', function () {
	        syncCurrentDetailEdit();
	        var id = el.getAttribute('data-int-id');
	        netSelIntercept = netInterceptQueue.find(function (r) { return r.reqId === id; }) || null;
        netSelReq = null;
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
	    Array.prototype.forEach.call(netListEl.querySelectorAll('.bh-row'), function (el) {
	      if (el.getAttribute('data-int-id')) return;
	      el.addEventListener('click', function () {
	        syncCurrentDetailEdit();
	        var id = el.getAttribute('data-id');
        netSelReq = netRequests.find(function (r) { return r.reqId === id; }) || null;
        netSelIntercept = null;
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

	  function closeNetDetail() {
	    syncCurrentDetailEdit();
	    netSelReq = null;
    netSelIntercept = null;
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

	  function formatBodyForDisplay(body, headers) {
	    if (body == null) return '(无)';
	    var text = String(body);
	    if (!text.trim()) return text;
	    if (isJsonLikeBody(text, headers)) {
	      try { return JSON.stringify(JSON.parse(text), null, 2); } catch (e) {}
	    }
	    return text;
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
	    if (!netSelReq && !pendingIntercept) {
      netDetailEl.style.display = 'none';
      if (netListEl) netListEl.style.flex = '1 1 auto';
      return;
    }
	    netDetailEl.style.display = 'flex';
	    // 详情展开时列表收缩到约 30%，把空间让给请求/响应体
	    if (netListEl) netListEl.style.flex = '0 0 30%';
	    var r = pendingIntercept ? interceptAsReq(netSelIntercept) : netSelReq;
	    updateDetailActionButtons(pendingIntercept);
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
    } else if (pendingIntercept) {
      content = '拦截中的请求还未发送，没有可关联的明文结果。';
	    } else {
	      content = fmtPlainCandidates(r);
	    }
	    netDetailBody.value = content;
	    netDetailDirty = false;
	    if (netDetailSearchText) runDetailSearch();
	  }

  function updateDetailActionButtons(pendingIntercept) {
    if (!netDetailActs) return;
    var replay = netDetailActs.querySelector('#bh-act-replay');
    var bp = netDetailActs.querySelector('#bh-act-bp');
    if (replay) replay.textContent = pendingIntercept ? '发送' : '重放';
    if (bp) bp.textContent = pendingIntercept ? '中止' : '设断点';
  }

  function setNetEditing(active) {
    netEditing = !!active;
  }

  // 点按详情 textarea：可编辑的 tab 打开 light DOM 编辑层输入，写回后刷新展示。
  // 可编辑范围：拦截请求的 请求头(0)/请求体(1)；普通请求的 请求体(1)。
  function openDetailEditor() {
    if (!netDetailBody) return;
    var isIntercept = !!netSelIntercept;
    // tab 0=请求头 1=请求体 可编辑；普通请求也允许编辑请求头(0)和请求体(1)
    var editable = (netDetailTab === 0 || netDetailTab === 1);
    if (!editable) return;
    var titles = ['编辑请求头', '编辑请求体', '', '', ''];
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
	    openModal('标记请求',
	      '<div style="font-size:12px;color:#555;word-break:break-all;">' + escHtml(requestLabel(r)) + '</div>' +
	      '<button data-mark="pass" style="padding:10px;border:1px solid #d0d7de;border-radius:6px;background:#fff;text-align:left;">标记为放行</button>' +
	      '<button data-mark="intercept" style="padding:10px;border:1px solid #d0d7de;border-radius:6px;background:#fff;text-align:left;">标记为拦截</button>',
	      function () { closeModal(); }
	    );
	    setTimeout(function () {
	      if (!netModal) return;
	      var ok = netModal.querySelector('#bh-btn-ok');
	      if (ok) ok.style.display = 'none';
	      Array.prototype.forEach.call(netModal.querySelectorAll('[data-mark]'), function (btn) {
	        btn.addEventListener('click', function () {
	          var action = btn.getAttribute('data-mark');
	          upsertInterceptRuleFromReq(r, action);
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
	      var meta = (r.host || '(无域名)') + ' · ' + (r.hasBody ? '有正文' : '无正文') + ' · ' +
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


	  // ── 功能：断点管理 ──
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

  // 把断点列表同步到 page world，供拦截器命中判断
  function injectBreakpoints() {
    if (erudaMode !== 'page' && typeof window.wrappedJSObject !== 'undefined' && typeof cloneInto !== 'undefined') {
      try { window.wrappedJSObject.__bhBreakpoints = cloneInto(netBreakpoints, window); return; } catch (e) {}
    }
    runInPage('(function(){window.__bhBreakpoints=' + JSON.stringify(netBreakpoints) + ';})();');
  }

  // ── 功能：Mock 规则 ──
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

  function injectMockRules() {
    var rulesJson = JSON.stringify(netMockRules);
    // isolated 模式（强 CSP）：runInPage 的 blob <script> 可能被 CSP 拦截，
    // 直接通过 wrappedJSObject + cloneInto 写到 page world，确保拦截器能读到。
    if (erudaMode !== 'page' && typeof window.wrappedJSObject !== 'undefined' && typeof cloneInto !== 'undefined') {
      try { window.wrappedJSObject.__bhMockRules = cloneInto(netMockRules, window); return; } catch (e) {}
    }
    runInPage('(function(){window.__bhMockRules=' + rulesJson + ';})();');
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
	    var suffix = netInterceptQueue.length ? ' ' + netInterceptQueue.length : '';
	    btn.textContent = (netGlobalInterceptEnabled ? '● ' : '○ ') + '拦截' + suffix;
	    btn.style.color = netGlobalInterceptEnabled ? '#dc2626' : '#888';
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

  function openReplaceModal() {
    var listHtml = netReplaceRules.map(function (rule, i) {
      var fromShort = rule.from.length > 20 ? rule.from.slice(0, 20) + '…' : rule.from;
      var toShort = rule.to.length > 20 ? rule.to.slice(0, 20) + '…' : rule.to;
      return '<div style="display:flex;gap:6px;align-items:center;padding:6px 0;border-bottom:1px solid #eaecef;">' +
        '<span style="flex:1;font-size:12px;font-family:monospace;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">' +
          escHtml(fromShort) + ' → ' + escHtml(toShort) + '</span>' +
        '<button data-ri-toggle="' + i + '" style="font-size:11px;padding:2px 6px;border-radius:3px;border:1px solid #d0d7de;background:' + (rule.enabled ? '#dbeafe' : '#fff') + ';">' +
          (rule.enabled ? '开' : '关') + '</button>' +
        '<button data-ri-del="' + i + '" style="font-size:11px;padding:2px 6px;border-radius:3px;border:1px solid #fecaca;background:#fff5f5;color:#dc2626;">删</button>' +
      '</div>';
    }).join('') || '<div style="color:#888;font-size:12px;padding:8px 0;">暂无替换规则</div>';
    openModal('字符替换',
      listHtml +
      '<label style="margin-top:8px;">被替换字符串</label><input id="bh-repl-from" placeholder="要被替换的内容">' +
      '<label>替换为</label><input id="bh-repl-to" placeholder="替换进去的内容（空=删除）">',
      function (el) {
        var from = el.querySelector('#bh-repl-from').value;
        var to = el.querySelector('#bh-repl-to').value;
        if (from) {
          netReplaceRules.push({ id: Date.now().toString(36), from: from, to: to, enabled: true });
          saveReplaceRules();
          updateReplaceBtn();
        }
        closeModal();
      }
    );
    setTimeout(function () {
      if (!netModal) return;
      Array.prototype.forEach.call(netModal.querySelectorAll('[data-ri-toggle]'), function (btn) {
        btn.addEventListener('click', function () {
          var i = parseInt(btn.getAttribute('data-ri-toggle'));
          if (netReplaceRules[i]) netReplaceRules[i].enabled = !netReplaceRules[i].enabled;
          saveReplaceRules();
          updateReplaceBtn();
          openReplaceModal();
        });
      });
      Array.prototype.forEach.call(netModal.querySelectorAll('[data-ri-del]'), function (btn) {
        btn.addEventListener('click', function () {
          var i = parseInt(btn.getAttribute('data-ri-del'));
          netReplaceRules.splice(i, 1);
          saveReplaceRules();
          updateReplaceBtn();
          openReplaceModal();
        });
      });
    }, 20);
  }

  function saveReplaceRules() {
    var st = storageLocal();
    if (st && st.set) {
      try { st.set({ bhNetReplaceRules: netReplaceRules }).catch(function () {}); } catch (e) {}
    }
  }

  function loadReplaceRules() {
    var st = storageLocal();
    if (!st || !st.get) return;
    try {
      st.get('bhNetReplaceRules').then(function (res) {
        var saved = res && res.bhNetReplaceRules;
        if (Array.isArray(saved)) {
          netReplaceRules = saved.filter(function (r) { return r && r.from; }).map(function (r) {
            return { id: String(r.id || Date.now().toString(36)), from: String(r.from), to: String(r.to || ''), enabled: !!r.enabled };
          });
          updateReplaceBtn();
        }
      }).catch(function () {});
    } catch (e) {}
  }

  // ── 网络配置持久化（拦截开关、过滤状态等）──
  var NET_CONFIG_KEY = 'bhNetConfig';

  function saveNetConfig() {
    var st = storageLocal();
    if (!st || !st.set) return;
    try {
      st.set({ bhNetConfig: {
        globalIntercept: netGlobalInterceptEnabled,
        plainProbe: netPlainProbeEnabled,
        hideTunnelNoise: netHideTunnelNoise,
        payloadOnly: netPayloadOnly,
        replaceEnabled: netReplaceEnabled,
      }}).catch(function () {});
    } catch (e) {}
  }

  function loadNetConfig(cb) {
    var st = storageLocal();
    if (!st || !st.get) { if (cb) cb(); return; }
    try {
      st.get(NET_CONFIG_KEY).then(function (res) {
        var cfg = res && res[NET_CONFIG_KEY];
        if (cfg) {
          netGlobalInterceptEnabled = !!cfg.globalIntercept;
          netPlainProbeEnabled = !!cfg.plainProbe;
          netHideTunnelNoise = !!cfg.hideTunnelNoise;
          netPayloadOnly = !!cfg.payloadOnly;
          netReplaceEnabled = !!cfg.replaceEnabled;
        }
        if (cb) cb();
      }).catch(function () { if (cb) cb(); });
    } catch (e) { if (cb) cb(); }
  }

  // document_start 时提前注入拦截器（不依赖 DOM），让页面第一个请求就能被捕获
  function earlyInjectInterceptor() {
    // isolated world：exportFunction 直接操作 page world，完全不需要 DOM
    if (typeof exportFunction !== 'undefined' && typeof window.wrappedJSObject !== 'undefined') {
      injectInterceptorViaExportFunction();
      return;
    }
    // page world：需要往 DOM 插 <script>。document_start 时 documentElement 存在但 head 可能没有，
    // runInPage 内部已用 document.head || document.documentElement 兜底，可以直接调用。
    runInPage(INTERCEPT_JS);
  }

  function applyReplaceRules(text) {
    if (!netReplaceEnabled || !netReplaceRules.length) return text;
    var result = text;
    netReplaceRules.forEach(function (rule) {
      if (!rule.enabled || !rule.from) return;
      try {
        // 用简单字符串替换（全部匹配）
        var from = rule.from;
        var to = rule.to || '';
        var out = '';
        var idx = 0;
        while (true) {
          var pos = result.indexOf(from, idx);
          if (pos === -1) { out += result.slice(idx); break; }
          out += result.slice(idx, pos) + to;
          idx = pos + from.length;
        }
        result = out;
      } catch (e) {}
    });
    return result;
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
	    bar.querySelector('#bh-intercept-btn').addEventListener('click', function () {
	      netGlobalInterceptEnabled = !netGlobalInterceptEnabled;
	      syncGlobalInterceptEnabled();
	      if (!netGlobalInterceptEnabled && netInterceptQueue.length) releaseAllIntercepts();
	      saveNetConfig();
	      updateInterceptBtn();
	      renderNetList();
	    });
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
	      var target = netSelIntercept ? interceptAsReq(netSelIntercept) : netSelReq;
	      if (!target) return;
	      var text = toCurl(target);
	      navigator.clipboard && navigator.clipboard.writeText(text).catch(function () {});
	      flashBtn(this, '已复制');
	    });
	    netDetailActs.querySelector('#bh-act-bp').addEventListener('click', function () {
	      syncCurrentDetailEdit();
	      setNetEditing(false);
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

	    netPanel = wrap;
	    return wrap;
  }

  // ── 注册 eruda 自定义 Tool（isolated world）──
  // eruda.add 只调用 tool.init($el)，不调用 render()。基类 init 是 this._$el=e。
  function registerNetTool(erudaObj) {
    if (!erudaObj || !erudaObj.add) return;
    var tool = {
      name: '网络',
      _$el: null,
      init: function ($el) {
        this._$el = $el;
        var node = ($el && $el[0]) || $el;
        if (!netPanel) buildNetPanel();
        if (node && node.appendChild) node.appendChild(netPanel);
      },
      show: function () {
        if (this._$el && this._$el.show) this._$el.show();
        netPanelVisible = true;
        renderNetList();
        renderDetail();
        return this;
      },
      hide: function () {
        if (this._$el && this._$el.hide) this._$el.hide();
        netPanelVisible = false;
        return this;
      },
      destroy: function () { netPanelVisible = false; },
    };
    try { erudaObj.add(tool); } catch (e) {}
  }

  // ── 在 installI18n 同一时机注册 Tool & 注入拦截器 ──
	  function installI18n() {
	    _i18nCore();
	    installPointerGuard();
	    // 加载持久化配置（若已在 earlyInjectInterceptor 阶段加载过，这里会覆盖为相同值；若首次开启则在此加载）
	    loadNetConfig(function () {
	      loadInterceptRules();
	      loadReplaceRules();
	      injectInterceptor();
	      syncGlobalInterceptEnabled();
	      syncInterceptRules();
	      syncReplaceRules();
	      injectPlainProbe();
	      // 同步持久化状态到面板 UI（按钮颜色/文字）
	      updateInterceptBtn();
	      updateFilterButtons();
	      updateReplaceBtn();
	    });
    // page-world: eruda 在 window.eruda; isolated: self.eruda
    var erudaObj = (erudaMode === 'page') ? null : (self.eruda || null);
    if (erudaMode === 'page') {
      // page world 的 eruda 对象在页面 window 里，content script 无法直接调用 eruda.add()
      // 改为注入一段脚本在页面 window 里注册 Tool
      injectNetToolPageWorld();
    } else {
      registerNetTool(erudaObj);
    }
  }

  // page-world 模式下注册 eruda 自定义 Tool。
  //
  // 关键：eruda 的 DevTools.add(tool) 只会调用 tool.init($container, devtools)，
  // 不会调用 render()；基类 init 是 this._$el=e，show/hide 走 this._$el.show()/.hide()。
  // 之前的实现用 render() 返回 DOM，eruda 从不调用，所以面板永远空白。
  //
  // 同时存在跨世界问题：抓包面板的 DOM 与全部交互逻辑都在 content-script 世界，
  // 而 eruda 在 page 世界。但两个世界共享同一份 DOM —— 所以做法是：
  //   1. content world 用 buildNetPanel() 构建 #bh-net 元素（事件已绑定在 content world）
  //   2. page world 的 tool.init($el) 拿到 eruda 容器后，用 DOM 查询找到 #bh-net 把它 append 进去
  //   3. show()/hide() 通过 postMessage 通知 content world 渲染（沿用 __bhNet 通道）
  function injectNetToolPageWorld() {
    if (!netPanel) buildNetPanel();
    // 先把面板挂在 body 上（隐藏），等 eruda tool.init 时再移进容器
    netPanel.style.display = 'none';
    document.body.appendChild(netPanel);

    // content world 监听来自 page world 的 show/hide 消息（已在上面的 message 监听里处理 panelShow/panelHide）

    runInPage([
      '(function(){',
      '  if(!window.eruda||!window.eruda.add)return;',
      '  if(window.__bhNetToolAdded)return;',
      '  window.__bhNetToolAdded=true;',
      '  window.eruda.add(function(devtools){',
      '    return {',
      '      name:"网络",',  // tab label
      '      init:function($el){',
      '        this._$el=$el;',
      '        var node=($el&&$el[0])||($el&&$el.get&&$el.get(0))||$el;',
      '        var panel=document.getElementById("bh-net");',
      '        if(panel&&node&&node.appendChild){node.appendChild(panel);panel.style.display="flex";}',
      '      },',
      '      show:function(){',
      '        if(this._$el&&this._$el.show)this._$el.show();',
      '        var panel=document.getElementById("bh-net");if(panel)panel.style.display="flex";',
      '        try{window.postMessage({__bhNet:true,type:"panelShow"},"*");}catch(e){}',
      '        return this;',
      '      },',
      '      hide:function(){',
      '        if(this._$el&&this._$el.hide)this._$el.hide();',
      '        try{window.postMessage({__bhNet:true,type:"panelHide"},"*");}catch(e){}',
      '        return this;',
      '      },',
      '      destroy:function(){},',
      '    };',
      '  });',
      '})();',
    ].join('\n'));
  }
  // ── /抓包面板 ────────────────────────────────────────────────────────────────

  connect();

  // 页面导航后自动恢复：
  //   - document_start 阶段：立刻加载持久化配置并注入拦截器（只 hook fetch/XHR，不碰 eruda）
  //   - 页面加载完成（load 或 DOMContentLoaded + 延迟）后：再初始化 eruda + 面板
  // 页面导航后自动恢复：
  //   关键顺序约束（与首次打开保持一致，避免 page eruda entry not visible）：
  //     1. 先 EVALUATE eruda bundle（loadPageEruda 只求值不 init），让 eruda 的
  //        chobitsu 后端 patch 页面世界的原生 XMLHttpRequest.prototype / fetch；
  //     2. 再注入我们的拦截器，包装页面世界的 fetch/XHR 构造器（叠在 eruda 之上）；
  //     3. 页面渲染完成（load）后再 toggle() 调 eruda.init() 构建 UI。
  //   若先注入拦截器，eruda 求值时会读到被我们 exportFunction 替换过的构造器的 Xray
  //   原型，原型 patch 抛错导致 window.eruda 永不定义。
  if (wasActive()) {
    loadNetConfig(function () {
      loadReplaceRules();
      // 1. 先求值 eruda bundle（patch 原生 fetch/XHR），无论成功失败都继续
      loadPageEruda(function () {
        // 2. 求值后再注入拦截器，保证顺序与首次打开一致
        earlyInjectInterceptor();
        syncGlobalInterceptEnabled();
        syncReplaceRules();
      });
    });

    // 3. eruda UI 初始化必须等页面渲染足够完成，否则 entry button 没尺寸导致 verifyEntry 超时
    function doRestore() {
      if (document.readyState === 'complete') {
        toggle();
      } else {
        window.addEventListener('load', function () { toggle(); }, { once: true });
      }
    }
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', function () {
        setTimeout(doRestore, 300);
      }, { once: true });
    } else {
      setTimeout(doRestore, 300);
    }
  }
})();
