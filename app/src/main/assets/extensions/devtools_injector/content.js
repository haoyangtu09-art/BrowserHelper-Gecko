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

  function loadPageEruda(cb) {
    if (pageErudaReady) { cb(null); return; }

    fetch(browser.runtime.getURL('eruda.min.js'))
      .then(function (r) { return r.text(); })
      .then(function (code) {
        // Patch font URLs so iconfont loads correctly from the blob context.
        // eruda embeds a relative url() in @font-face; when run from a blob URL
        // the relative path resolves to blob: origin and 404s.
        var fontBase = browser.runtime.getURL('');
        code = code.replace(
          /url\(["']?([^"')]+\.(?:woff2?|ttf|eot|svg)[^"')]*?)["']?\)/g,
          function (m, path) {
            if (/^(https?|blob|data):/.test(path)) return m;
            return 'url("' + fontBase + path.replace(/^\.?\//, '') + '")';
          }
        );
        // Append init call inside the same script so no separate inline
        // <script> is needed — bypasses pages that block unsafe-inline CSP.
        var initCode = [
          '\n(function(){',
          '  try{',
          '    if(window.eruda&&window.eruda._isInit){',
          '      window.eruda._isInit=false;',
          '      window.eruda._container=null;',
          '      window.eruda._shadowRoot=null;',
          '    }',
          '    window.eruda.init({useShadowDom:true,tool:["console","elements","resources","sources","info"]});',
          '    try{window.eruda.hide&&window.eruda.hide();}catch(e){}',
          '  }catch(e){}',
          '})();',
        ].join('');
        var blob = new Blob([code + initCode], { type: 'application/javascript' });
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
    var s = document.createElement('script');
    s.textContent = js;
    (document.head || document.documentElement).appendChild(s);
    s.remove();
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
    var vars = {
      '--background': '#fff',
      '--darker-background': '#f6f8fa',
      '--foreground': '#111',
      '--border': '#d0d7de',
      '--primary': '#2563eb',
      '--highlight': '#dbeafe',
    };
    applyStyles(host, vars);
    applyStyles(host.querySelector('.eruda-container'), Object.assign({}, vars, {
      position: 'fixed',
      left: '0',
      top: '0',
      width: '100vw',
      height: '100vh',
      'z-index': '2147483647',
      'pointer-events': 'none',
    }));
    applyStyles(host.querySelector('.eruda-entry-btn'), {
      visibility: 'visible',
      display: 'flex',
      'pointer-events': 'auto',
      'z-index': '2147483647',
    });
    Array.prototype.forEach.call(
      host.querySelectorAll('.eruda-tools,.eruda-tool,.eruda-tab,.eruda-notification,.eruda-modal'),
      function (el) {
        applyStyles(el, { 'pointer-events': 'auto' });
      }
    );
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
    // eruda.init() was already called inside the Blob script (loadPageEruda).
    // Just verify the entry button appeared in the DOM.
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
      '  var _origFetch=window.fetch;',
      '  window.fetch=function(input,init){',
      '    var url=typeof input==="string"?input:(input&&input.url)||"";',
      '    var method=((init&&init.method)||(input&&input.method)||"GET").toUpperCase();',
      '    var reqHeaders=headersToObj(init&&init.headers);',
      '    var reqBody=(init&&init.body!=null)?String(init.body):null;',
      '    var reqId=uid();',
      '    var t0=Date.now();',
      '    send({type:"req",reqId:reqId,url:url,method:method,reqHeaders:reqHeaders,reqBody:reqBody});',
      '    var args=arguments;',
      '    return _origFetch.apply(this,args).then(function(resp){',
      '      var status=resp.status;',
      '      var respHeaders=headersToObj(resp.headers);',
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
      '    });',
      '  };',

      // ── XHR 拦截 ──
      '  var _XHR=window.XMLHttpRequest;',
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
      '      _t0=Date.now();',
      '      send({type:"req",reqId:_reqId,url:_url,method:_method,reqHeaders:_reqHeaders,reqBody:_reqBody});',
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
      '      return origSend.apply(xhr,arguments);',
      '    };',
      '    return xhr;',
      '  };',
      '  window.XMLHttpRequest.prototype=_XHR.prototype;',

      '})();',
    ].join('\n');
  }());

  // content script 接收 page world 发来的消息
  var netReqMap = {}; // reqId -> 请求条目（等待响应）
  window.addEventListener('message', function (e) {
    if (!e.data || !e.data.__bhNet) return;
    var d = e.data;
    if (d.type === 'req') {
      var entry = {
        reqId: d.reqId, url: d.url, method: d.method,
        reqHeaders: d.reqHeaders || {}, reqBody: d.reqBody || null,
        status: null, respHeaders: {}, respBody: null,
        duration: null, error: null, ts: Date.now(), tag: null,
      };
      netReqMap[d.reqId] = entry;
    } else if (d.type === 'resp') {
      var entry = netReqMap[d.reqId];
      if (entry) {
        entry.status = d.status;
        entry.respHeaders = d.respHeaders || {};
        entry.respBody = d.respBody || null;
        entry.duration = d.duration;
        entry.error = d.error || null;
        delete netReqMap[d.reqId];
        // 放到列表头部，最多保留 200 条
        netRequests.unshift(entry);
        if (netRequests.length > 200) netRequests.length = 200;
        if (netPanelVisible) renderNetList();
      }
    }
  });

  function injectInterceptor() {
    if (erudaMode === 'page') {
      runInPage(INTERCEPT_JS);
    } else {
      // isolated world 也注入到 page（runInPage 在 content script 里可用）
      runInPage(INTERCEPT_JS);
    }
  }
  // ── /网络拦截层 ──────────────────────────────────────────────────────────────

  // ── Eruda 汉化 ──────────────────────────────────────────────────────────────
  var I18N_MAP = [
    // Console 面板
    ['Clear', '清空'], ['Filter', '过滤'], ['Preserve Log', '保留日志'],
    ['Show Timestamp', '显示时间戳'], ['Log', '日志'], ['Warn', '警告'],
    ['Error', '错误'], ['Info', '信息'], ['Debug', '调试'],
    ['Verbose', '详细'], ['Output', '输出'], ['JS', 'JS'],
    // Elements 面板
    ['Computed', '计算值'], ['Event Listeners', '事件监听器'],
    ['Styles', '样式'], ['Dom Tree', 'DOM 树'],
    // Resources 面板
    ['Local Storage', '本地存储'], ['Session Storage', '会话存储'],
    ['IndexedDB', 'IndexedDB'], ['Cache Storage', '缓存存储'],
    ['ServiceWorker', 'Service Worker'],
    // Info 面板
    ['Location', '页面地址'], ['System', '系统信息'], ['About', '关于'],
    // Settings 面板
    ['Theme', '主题'], ['Transparency', '透明度'], ['Display Size', '显示大小'],
    ['Dark', '深色'], ['Light', '浅色'], ['Apply', '应用'],
    ['Close', '关闭'], ['Default', '默认'], ['Settings', '设置'],
    // 通用按钮/标签
    ['Refresh', '刷新'], ['Copy', '复制'], ['Delete', '删除'],
    ['Expand', '展开'], ['Collapse', '折叠'], ['Search', '搜索'],
    ['Clear All', '全部清空'], ['Select All', '全选'],
    ['Cancel', '取消'], ['Confirm', '确认'], ['Save', '保存'],
    ['Reset', '重置'], ['Enable', '启用'], ['Disable', '禁用'],
    ['Open', '打开'],
    // Sources 面板（部分标签）
    ['Beautify', '格式化'], ['Word Wrap', '自动换行'],
  ];

  function replaceTextNodes(root) {
    if (!root) return;
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
    var node;
    while ((node = walker.nextNode())) {
      var val = node.nodeValue;
      if (!val || !val.trim()) continue;
      var replaced = val;
      for (var i = 0; i < I18N_MAP.length; i++) {
        var en = I18N_MAP[i][0], zh = I18N_MAP[i][1];
        // 只整词替换，避免误伤 URL/header 等技术内容
        if (replaced === en) { replaced = zh; break; }
      }
      if (replaced !== val) node.nodeValue = replaced;
    }
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
        '  var MAP=' + mapJson + ';',
        '  function walk(root){',
        '    var tw=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,null,false);',
        '    var n;',
        '    while((n=tw.nextNode())){',
        '      var v=n.nodeValue;if(!v||!v.trim())continue;',
        '      for(var i=0;i<MAP.length;i++){',
        '        if(v===MAP[i][0]){n.nodeValue=MAP[i][1];break;}',
        '      }',
        '    }',
        '  }',
        '  walk(host.shadowRoot);',
        '  var obs=new MutationObserver(function(muts){',
        '    muts.forEach(function(m){',
        '      m.addedNodes.forEach(function(node){walk(node);});',
        '    });',
        '  });',
        '  obs.observe(host.shadowRoot,{childList:true,subtree:true});',
        '})();',
      ].join(''));
      return;
    }
    // isolated-world 模式：可以直接访问 DOM（包括 shadow root）
    var host = document.getElementById('eruda');
    var root = host && (host.shadowRoot || host);
    if (!root) return;
    replaceTextNodes(root);
    var obs = new MutationObserver(function (muts) {
      muts.forEach(function (m) {
        m.addedNodes.forEach(function (node) { replaceTextNodes(node); });
      });
    });
    obs.observe(root, { childList: true, subtree: true });
  }
  // ── /Eruda 汉化 ─────────────────────────────────────────────────────────────

  // ── 抓包面板 ─────────────────────────────────────────────────────────────────
  var NET_STYLE = [
    '*{box-sizing:border-box;margin:0;padding:0;}',
    // 面板根：固定浅色主题，不依赖 CSS 变量（shadow root 内变量继承不可靠）
    '#bh-net{display:flex;flex-direction:column;height:100%;font-size:13px;',
    '  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;',
    '  background:#fff;color:#111;}',
    // 顶部工具栏
    '#bh-bar{display:flex;align-items:center;gap:6px;padding:6px 8px;',
    '  border-bottom:1px solid #d0d7de;flex-wrap:wrap;background:#f6f8fa;}',
    '#bh-bar button{font-size:12px;padding:4px 8px;border-radius:4px;',
    '  border:1px solid #d0d7de;background:#fff;color:#111;cursor:pointer;white-space:nowrap;}',
    '#bh-bar button:active{background:#e8eaed;}',
    '#bh-filter{flex:1;min-width:80px;font-size:12px;padding:4px 6px;border-radius:4px;',
    '  border:1px solid #d0d7de;background:#fff;color:#111;}',
    // 请求列表
    '#bh-list{flex:1;overflow-y:auto;border-bottom:1px solid #d0d7de;}',
    '.bh-row{display:flex;align-items:center;padding:8px;min-height:44px;',
    '  border-bottom:1px solid #eaecef;cursor:pointer;gap:6px;background:#fff;}',
    '.bh-row:active,.bh-row.bh-sel{background:#dbeafe;}',
    '.bh-method{font-weight:700;min-width:38px;font-size:11px;color:#111;}',
    '.bh-url{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px;color:#111;}',
    '.bh-dur{min-width:38px;text-align:right;font-size:11px;color:#888;}',
    '.bh-tag{font-size:10px;padding:1px 4px;border-radius:3px;background:#6366f1;color:#fff;}',
    // 状态颜色
    '.s2{color:#16a34a;}.s3{color:#2563eb;}.s4{color:#ea580c;}.s5{color:#dc2626;}.s0{color:#888;}.s-err{color:#dc2626;font-style:italic;}',
    '.bh-status-wrap{display:flex;flex-direction:column;align-items:flex-end;min-width:52px;}',
    '.bh-status{font-size:12px;font-weight:700;line-height:1.2;}',
    '.bh-status-desc{font-size:10px;color:#888;line-height:1.2;}',
    // 详情区
    '#bh-detail{max-height:55%;overflow:hidden;display:flex;flex-direction:column;background:#fff;}',
    '#bh-detail-tabs{display:flex;border-bottom:1px solid #d0d7de;overflow-x:auto;background:#f6f8fa;}',
    '.bh-dtab{padding:6px 10px;font-size:12px;cursor:pointer;white-space:nowrap;color:#111;',
    '  border-bottom:2px solid transparent;}',
    '.bh-dtab.active{border-bottom-color:#2563eb;font-weight:700;}',
    '#bh-detail-body{flex:1;overflow-y:auto;padding:8px;font-size:12px;color:#111;',
    '  white-space:pre-wrap;word-break:break-all;font-family:monospace;background:#fff;}',
    '#bh-detail-acts{display:flex;gap:6px;padding:6px 8px;flex-wrap:wrap;',
    '  border-top:1px solid #d0d7de;background:#f6f8fa;}',
    '#bh-detail-acts button{font-size:12px;padding:4px 8px;border-radius:4px;',
    '  border:1px solid #d0d7de;background:#fff;color:#111;cursor:pointer;}',
    '#bh-detail-acts button:active{background:#e8eaed;}',
    // 模态弹窗（挂到 document.body，在 shadow root 外，用固定色）
    '#bh-modal{position:fixed;inset:0;z-index:99999;display:flex;align-items:center;',
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
  ].join('');

  var netPanel = null;   // 根 div
  var netListEl = null;
  var netDetailEl = null;
  var netDetailBody = null;
  var netDetailActs = null;
  var netFilterEl = null;
  var netEnableBtn = null;
  var netSelReq = null;  // 当前选中的请求条目
  var netDetailTab = 0;  // 0=请求头 1=请求体 2=响应头 3=响应体
  var netModal = null;   // 当前弹窗 element

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

  function renderNetList() {
    if (!netListEl) return;
    var filter = netFilterEl ? netFilterEl.value.trim().toLowerCase() : '';
    var rows = netRequests.filter(function (r) {
      return !filter || r.url.toLowerCase().indexOf(filter) !== -1;
    });
    if (rows.length === 0) {
      netListEl.innerHTML = '<div id="bh-empty">暂无请求记录</div>';
      return;
    }
    var html = '';
    rows.forEach(function (r) {
      var hasErr = !!r.error;
      var sc = statusClass(r.status, hasErr);
      var statusNum = r.status ? String(r.status) : (hasErr ? 'ERR' : '…');
      var statusDesc = r.status ? statusPhrase(r.status) : (hasErr ? shortError(r.error) : '');
      var dur = r.duration != null ? r.duration + 'ms' : '…';
      var tag = r.tag ? '<span class="bh-tag">' + r.tag + '</span>' : '';
      var bpClass = netBreakpoints.some(function (bp) {
        return r.url.indexOf(bp.pattern) !== -1;
      }) ? ' bh-bp' : '';
      var selClass = netSelReq && netSelReq.reqId === r.reqId ? ' bh-sel' : '';
      var errTitle = hasErr ? ' title="' + escHtml(r.error) + '"' : '';
      html += '<div class="bh-row' + bpClass + selClass + '" data-id="' + r.reqId + '">' +
        '<span class="bh-method">' + r.method + '</span>' +
        '<span class="bh-url" title="' + escHtml(r.url) + '">' + escHtml(truncUrl(r.url)) + '</span>' +
        tag +
        '<span class="bh-status-wrap"' + errTitle + '>' +
          '<span class="bh-status ' + sc + '">' + statusNum + '</span>' +
          (statusDesc ? '<span class="bh-status-desc">' + escHtml(statusDesc) + '</span>' : '') +
        '</span>' +
        '<span class="bh-dur">' + dur + '</span>' +
        '</div>';
    });
    netListEl.innerHTML = html;
    // 绑定点击
    Array.prototype.forEach.call(netListEl.querySelectorAll('.bh-row'), function (el) {
      el.addEventListener('click', function () {
        var id = el.getAttribute('data-id');
        netSelReq = netRequests.find(function (r) { return r.reqId === id; }) || null;
        renderNetList();
        renderDetail();
      });
    });
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

  function renderDetail() {
    if (!netDetailEl || !netDetailBody) return;
    if (!netSelReq) {
      netDetailEl.style.display = 'none';
      return;
    }
    netDetailEl.style.display = 'flex';
    var r = netSelReq;
    var tabs = ['请求头', '请求体', '响应头', '响应体'];
    var tabsEl = netDetailEl.querySelector('#bh-detail-tabs');
    tabsEl.innerHTML = tabs.map(function (t, i) {
      return '<div class="bh-dtab' + (netDetailTab === i ? ' active' : '') + '" data-i="' + i + '">' + t + '</div>';
    }).join('');
    Array.prototype.forEach.call(tabsEl.querySelectorAll('.bh-dtab'), function (el) {
      el.addEventListener('click', function () {
        netDetailTab = parseInt(el.getAttribute('data-i'));
        renderDetail();
      });
    });
    var content = '';
    if (netDetailTab === 0) content = fmtHeaders(r.reqHeaders);
    else if (netDetailTab === 1) content = r.reqBody != null ? r.reqBody : '(无)';
    else if (netDetailTab === 2) {
      var lines = fmtHeaders(r.respHeaders);
      if (r.status) lines = 'HTTP/1.1 ' + r.status + ' ' + (statusPhrase(r.status) || '') + '\n' + lines;
      content = lines;
    } else {
      if (r.error) {
        content = '── 请求失败 ──\n' + r.error + '\n\n原因: ' + shortError(r.error);
      } else if (r.respBody != null) {
        content = r.respBody;
      } else {
        content = '(等待响应…)';
      }
    }
    netDetailBody.textContent = content;
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
        if (netPanelVisible) renderNetList();
      });
    }).catch(function (err) {
      var entry = {
        reqId: Math.random().toString(36).slice(2),
        url: r.url, method: r.method,
        reqHeaders: r.reqHeaders, reqBody: r.reqBody,
        status: 0, error: String(err), ts: Date.now(), tag: tag || '重放',
      };
      netRequests.unshift(entry);
      if (netPanelVisible) renderNetList();
    });
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
    document.body.appendChild(el);
    netModal = el;
  }
  function closeModal() {
    if (netModal) { try { netModal.remove(); } catch (e) {} netModal = null; }
  }

  // ── 功能：编辑重发 ──
  function editReplay(r) {
    var headersStr = JSON.stringify(r.reqHeaders || {}, null, 2);
    openModal('编辑重发',
      '<label>URL</label><input id="bh-edit-url" value="' + escHtml(r.url) + '">' +
      '<label>方法</label><select id="bh-edit-method">' +
        ['GET','POST','PUT','PATCH','DELETE','HEAD','OPTIONS'].map(function(m){
          return '<option' + (m===r.method?' selected':'') + '>' + m + '</option>';
        }).join('') +
      '</select>' +
      '<label>请求头 (JSON)</label><textarea id="bh-edit-headers">' + escHtml(headersStr) + '</textarea>' +
      '<label>请求体</label><textarea id="bh-edit-body">' + escHtml(r.reqBody || '') + '</textarea>',
      function (el) {
        var url = el.querySelector('#bh-edit-url').value;
        var method = el.querySelector('#bh-edit-method').value;
        var headersRaw = el.querySelector('#bh-edit-headers').value;
        var body = el.querySelector('#bh-edit-body').value;
        var headers = {};
        try { headers = JSON.parse(headersRaw); } catch (e) {}
        closeModal();
        replayReq({ url: url, method: method, reqHeaders: headers, reqBody: body || null }, '编辑重发');
      }
    );
  }

  // ── 功能：断点管理 ──
  function openBreakpointModal() {
    var listHtml = netBreakpoints.map(function (bp, i) {
      return '<div style="display:flex;gap:6px;align-items:center;margin-bottom:4px;">' +
        '<span style="flex:1;font-size:12px;font-family:monospace;">' + escHtml(bp.pattern) + '</span>' +
        '<button data-bpi="' + i + '" style="font-size:11px;padding:2px 6px;">删除</button></div>';
    }).join('') || '<div style="color:#888;font-size:12px;">暂无断点</div>';
    openModal('断点管理',
      listHtml +
      '<label style="margin-top:8px;">新增断点 URL 关键词</label>' +
      '<input id="bh-bp-input" placeholder="如: api.example.com/login">',
      function (el) {
        var val = el.querySelector('#bh-bp-input').value.trim();
        if (val) netBreakpoints.push({ pattern: val, id: Date.now() });
        closeModal();
        renderNetList();
      }
    );
    // 删除按钮
    setTimeout(function () {
      if (!netModal) return;
      Array.prototype.forEach.call(netModal.querySelectorAll('[data-bpi]'), function (btn) {
        btn.addEventListener('click', function () {
          var i = parseInt(btn.getAttribute('data-bpi'));
          netBreakpoints.splice(i, 1);
          openBreakpointModal();
        });
      });
    }, 50);
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
    runInPage('(function(){window.__bhMockRules=' + rulesJson + ';})();');
  }

  // ── 功能：弱网模拟 ──
  function openThrottleModal() {
    openModal('弱网模拟',
      '<label>延迟 (ms, 0=关闭)</label><input id="bh-thr-lat" type="number" value="' + netThrottle.latencyMs + '">' +
      '<label>限速 (KB/s, 0=不限)</label><input id="bh-thr-kbps" type="number" value="' + netThrottle.kbps + '">',
      function (el) {
        netThrottle.latencyMs = parseInt(el.querySelector('#bh-thr-lat').value) || 0;
        netThrottle.kbps = parseInt(el.querySelector('#bh-thr-kbps').value) || 0;
        netThrottle.enabled = netThrottle.latencyMs > 0 || netThrottle.kbps > 0;
        var cfg = JSON.stringify(netThrottle);
        runInPage('(function(){window.__bhThrottle=' + cfg + ';})();');
        closeModal();
      }
    );
  }

  // ── 构建面板根 DOM ──
  function buildNetPanel() {
    var wrap = document.createElement('div');
    wrap.id = 'bh-net';

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
      '<button id="bh-export-har">导出 HAR</button>' +
      '<button id="bh-bp-btn">断点</button>' +
      '<button id="bh-mock-btn">Mock</button>' +
      '<button id="bh-thr-btn">弱网</button>' +
      '<input id="bh-filter" placeholder="过滤 URL…">';
    wrap.appendChild(bar);
    netFilterEl = bar.querySelector('#bh-filter');
    netEnableBtn = bar.querySelector('#bh-toggle');

    netEnableBtn.addEventListener('click', function () {
      netEnabled = !netEnabled;
      netEnableBtn.textContent = netEnabled ? '● 监听中' : '○ 已停止';
      // 停止时从 page world 卸载拦截器
      if (!netEnabled) runInPage('(function(){window.__bhNetInstalled=false;if(window.__bhRestoreFetch){window.fetch=window.__bhRestoreFetch;}if(window.__bhRestoreXHR){window.XMLHttpRequest=window.__bhRestoreXHR;}})();');
      else { injectInterceptor(); }
    });
    bar.querySelector('#bh-clear').addEventListener('click', function () {
      netRequests = []; netSelReq = null; renderNetList(); renderDetail();
    });
    bar.querySelector('#bh-export-har').addEventListener('click', exportHAR);
    bar.querySelector('#bh-bp-btn').addEventListener('click', openBreakpointModal);
    bar.querySelector('#bh-mock-btn').addEventListener('click', openMockModal);
    bar.querySelector('#bh-thr-btn').addEventListener('click', openThrottleModal);
    netFilterEl.addEventListener('input', renderNetList);

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
      '<div id="bh-detail-tabs"></div>' +
      '<div id="bh-detail-body"></div>' +
      '<div id="bh-detail-acts">' +
        '<button id="bh-act-replay">重放</button>' +
        '<button id="bh-act-edit">编辑重发</button>' +
        '<button id="bh-act-curl">复制 curl</button>' +
        '<button id="bh-act-bp">设断点</button>' +
      '</div>';
    netDetailBody = netDetailEl.querySelector('#bh-detail-body');
    netDetailActs = netDetailEl.querySelector('#bh-detail-acts');
    wrap.appendChild(netDetailEl);

    netDetailActs.querySelector('#bh-act-replay').addEventListener('click', function () {
      if (netSelReq) replayReq(netSelReq);
    });
    netDetailActs.querySelector('#bh-act-edit').addEventListener('click', function () {
      if (netSelReq) editReplay(netSelReq);
    });
    netDetailActs.querySelector('#bh-act-curl').addEventListener('click', function () {
      if (!netSelReq) return;
      var text = toCurl(netSelReq);
      navigator.clipboard && navigator.clipboard.writeText(text).catch(function () {});
    });
    netDetailActs.querySelector('#bh-act-bp').addEventListener('click', function () {
      if (!netSelReq) return;
      try { var u = new URL(netSelReq.url); netBreakpoints.push({ pattern: u.pathname, id: Date.now() }); }
      catch (e) { netBreakpoints.push({ pattern: netSelReq.url.slice(0, 40), id: Date.now() }); }
      renderNetList();
    });

    netPanel = wrap;
    return wrap;
  }

  // ── 注册 eruda 自定义 Tool ──
  function registerNetTool(erudaObj) {
    if (!erudaObj || !erudaObj.add) return;
    var tool = {
      name: '抓包',
      $el: null,
      render: function () {
        if (!this.$el) this.$el = buildNetPanel();
        return this.$el;
      },
      show: function () {
        netPanelVisible = true;
        renderNetList();
        renderDetail();
      },
      hide: function () { netPanelVisible = false; },
      destroy: function () { netPanelVisible = false; netPanel = null; netListEl = null; netDetailEl = null; netDetailBody = null; },
    };
    try { erudaObj.add(tool); } catch (e) {}
  }

  // ── 在 installI18n 同一时机注册 Tool & 注入拦截器 ──
  function installI18n() {
    _i18nCore();
    injectInterceptor();
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

  // page-world 模式下通过 runInPage 注册 Tool + 渲染（面板 DOM 由 page world 脚本构建）
  // 因为面板逻辑复杂，page-world 用精简版：只挂一个 iframe-like div 把 content script 的 netPanel 嵌入
  // 实际上 content script DOM 元素无法传递给 page world；改为在 page world 直接建简易面板
  // 但为保持功能完整性，利用 window.postMessage 双向通信实现桥接
  function injectNetToolPageWorld() {
    // 简化：直接把 buildNetPanel() 生成的 DOM 插入页面 document.body，
    // 再用 runInPage 把它注册到 eruda
    // content script 可以把 DOM 元素 appendChild 到页面 DOM
    if (!netPanel) buildNetPanel();
    document.body.appendChild(netPanel);
    // 注入样式到页面 head（已在 buildNetPanel 里做了）
    // 通知 page world 的 eruda 注册 Tool，引用已在 DOM 里的元素
    runInPage([
      '(function(){',
      '  if(!window.eruda||!window.eruda.add)return;',
      '  var el=document.getElementById("bh-net");',
      '  if(!el)return;',
      '  window.eruda.add({',
      '    name:"抓包",',
      '    $el:el,',
      '    render:function(){return el;},',
      '    show:function(){window.__bhNetShow&&window.__bhNetShow();},',
      '    hide:function(){window.__bhNetHide&&window.__bhNetHide();},',
      '    destroy:function(){},',
      '  });',
      '})();',
    ].join(''));
    // 绑定 show/hide 回调到 content script 的变量（通过 runInPage 桥接）
    window.__bhNetShow = function () { netPanelVisible = true; renderNetList(); renderDetail(); };
    window.__bhNetHide = function () { netPanelVisible = false; };
  }
  // ── /抓包面板 ────────────────────────────────────────────────────────────────

  connect();

  // 页面导航后自动恢复：如果上一个页面是激活状态，新页面加载完也自动注入
  if (wasActive()) {
    // 等 DOM ready 再恢复，避免在 document-start 时 body 还不存在
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', function () { toggle(); });
    } else {
      toggle();
    }
  }
})();
