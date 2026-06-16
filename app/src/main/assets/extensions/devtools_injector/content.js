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
      .then(function (r) { return r.blob(); })
      .then(function (blob) {
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
    var btn = getEntryButton();
    if (!btn) return false;
    try {
      var rect = btn.getBoundingClientRect();
      var view = document.defaultView || window;
      var style = view.getComputedStyle(btn);
      return rect.width > 0 &&
        rect.height > 0 &&
        rect.right > 0 &&
        rect.bottom > 0 &&
        rect.left < window.innerWidth &&
        rect.top < window.innerHeight &&
        style.display !== 'none' &&
        style.visibility !== 'hidden' &&
        style.opacity !== '0';
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
    style.textContent = [
      '#eruda{all:initial!important;z-index:2147483647!important;}',
      '#eruda .eruda-entry-btn{visibility:visible!important;opacity:1!important;z-index:2147483647!important;}',
      '#eruda .eruda-dev-tools{background:#fff!important;opacity:1!important;color:#111!important;}',
      '#eruda .eruda-tools,#eruda .eruda-tool{background:#fff!important;}',
      '#eruda .eruda-tab,#eruda .eruda-notification,#eruda .eruda-modal{background:#fff!important;}',
    ].join('\n');
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
    applyStyles(host.querySelector('.eruda-container'), vars);
    applyStyles(host.querySelector('.eruda-dev-tools'), {
      background: '#fff',
      color: '#111',
      opacity: '1',
      'backdrop-filter': 'none',
      '-webkit-backdrop-filter': 'none',
    });
    Array.prototype.forEach.call(
      host.querySelectorAll('.eruda-tools,.eruda-tool,.eruda-tab,.eruda-notification,.eruda-modal'),
      function (el) {
        applyStyles(el, {
          background: '#fff',
          opacity: '1',
        });
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
    runInPage(
      '(function(){' +
      '  try{' +
      '    if(window.eruda&&window.eruda._isInit){' +
      '      window.eruda._isInit=false;' +
      '      window.eruda._container=null;' +
      '      window.eruda._shadowRoot=null;' +
      '    }' +
      '    window.eruda.init({useShadowDom:false,tool:["console","elements","resources","sources","info"]});' +
      '    try{window.eruda.hide&&window.eruda.hide();}catch(e){}' +
      '    function centerEntry(){' +
      '      try{' +
      '        var btn=document.querySelector("#eruda .eruda-entry-btn");' +
      '        if(!btn||!window.eruda.position)return;' +
      '        var r=btn.getBoundingClientRect();' +
      '        var w=r.width||btn.offsetWidth||40;' +
      '        var h=r.height||btn.offsetHeight||40;' +
      '        window.eruda.position({' +
      '          x:Math.max(0,Math.round((window.innerWidth-w)/2)),' +
      '          y:Math.max(0,Math.round((window.innerHeight-h)/2))' +
      '        });' +
      '      }catch(e){}' +
      '    }' +
      '    centerEntry();' +
      '    requestAnimationFrame(centerEntry);' +
      '    setTimeout(centerEntry,300);' +
      '  }catch(e){}' +
      '})();'
    );

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
          useShadowDom: false,
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
          postStatus('ok');
        });
        return;
      }

      initPageEruda(function (pageErr) {
        if (!pageErr) {
          erudaActive = true;
          postStatus('ok');
          return;
        }
        initIsolatedEruda(function (isolatedErr) {
          if (isolatedErr) {
            postStatus('load error: ' + pageErr + '; ' + isolatedErr);
            return;
          }
          erudaActive = true;
          postStatus('ok');
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

  connect();
})();
