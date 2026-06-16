// Content script: load patched Eruda inside the extension isolated world.
// Page-world <script> injection is blocked by strict CSP on strict sites.
(function () {
  var port = null;
  var erudaReady = false;
  var erudaActive = false;
  var styleId = 'browserhelper-eruda-fix-style';
  var tools = ['console', 'elements', 'resources', 'sources', 'info'];

  function describeError(e) {
    return String(e && e.message ? e.message : e);
  }

  function postStatus(status) {
    try {
      if (port) port.postMessage({ status: status });
    } catch (e) {}
  }

  function loadEruda(cb) {
    if (erudaReady && self.eruda) { cb(null); return; }

    fetch(browser.runtime.getURL('eruda.min.js'))
      .then(function (r) { return r.text(); })
      .then(function (code) {
        try {
          // Some DOM APIs require Window as `this` when Eruda stores/calls them.
          var preamble = [
            'var getComputedStyle=window.getComputedStyle.bind(window);',
            'var getSelection=window.getSelection?window.getSelection.bind(window):function(){return null;};',
            'var matchMedia=window.matchMedia?window.matchMedia.bind(window):function(){return{matches:false,addListener:function(){},removeListener:function(){}};};',
          ].join('');
          var fn = new Function(preamble + code + '\nreturn typeof eruda!=="undefined"?eruda:self.eruda;');
          var result = fn.call(self);
          if (result && !self.eruda) self.eruda = result;
          erudaReady = !!self.eruda;
          cb(erudaReady ? null : 'eruda undefined after exec');
        } catch (e) {
          cb('exec error: ' + describeError(e));
        }
      })
      .catch(function (e) { cb('fetch error: ' + describeError(e)); });
  }

  function installFixStyle() {
    var old = document.getElementById(styleId);
    if (old) old.remove();

    var style = document.createElement('style');
    style.id = styleId;
    style.textContent = [
      '#eruda{all:initial!important;z-index:2147483647!important;}',
      '#eruda .eruda-entry-btn{',
      '  display:block!important;visibility:visible!important;opacity:1!important;',
      '  pointer-events:auto!important;filter:none!important;mix-blend-mode:normal!important;',
      '  z-index:2147483647!important;',
      '}',
      '#eruda .eruda-entry-btn .eruda-icon-tool,',
      '#eruda .eruda-entry-btn [class*="eruda-icon-"],',
      '#eruda .eruda-entry-btn [class^="eruda-icon-"]{',
      '  display:inline-block!important;font-family:eruda-icon!important;',
      '  font-size:16px!important;font-style:normal!important;',
      '  background:transparent!important;color:#fff!important;',
      '}',
      '#eruda .eruda-entry-btn .eruda-icon-tool:before{content:"\\f113"!important;}',
      '#eruda .eruda-dev-tools{',
      '  background:var(--background,#fff)!important;opacity:1!important;',
      '  backdrop-filter:none!important;-webkit-backdrop-filter:none!important;',
      '}',
      '#eruda .eruda-tools,#eruda .eruda-tool{background:var(--background,#fff)!important;}',
    ].join('\n');
    (document.head || document.documentElement).appendChild(style);
  }

  function removeFixStyle() {
    var style = document.getElementById(styleId);
    if (style) style.remove();
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

  function isVisible(el) {
    if (!el) return false;
    var rect = el.getBoundingClientRect();
    var style = window.getComputedStyle(el);
    return rect.width > 0 &&
      rect.height > 0 &&
      rect.right > 0 &&
      rect.bottom > 0 &&
      rect.left < window.innerWidth &&
      rect.top < window.innerHeight &&
      style.display !== 'none' &&
      style.visibility !== 'hidden' &&
      style.opacity !== '0';
  }

  function centerEntryButton() {
    var entry = getEntryButton();
    if (!entry || !self.eruda || !self.eruda.position) return false;

    var rect = entry.getBoundingClientRect();
    var width = rect.width || entry.offsetWidth || 40;
    var height = rect.height || entry.offsetHeight || 40;
    var x = Math.max(0, Math.round((window.innerWidth - width) / 2));
    var y = Math.max(0, Math.round((window.innerHeight - height) / 2));
    self.eruda.position({ x: x, y: y });
    return true;
  }

  function resetPanelOptions() {
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

  function verifyVisible(cb) {
    var attempts = 0;
    function check() {
      centerEntryButton();
      var entry = getEntryButton();
      if (isVisible(entry)) {
        cb(null);
        return;
      }
      attempts += 1;
      if (attempts < 20) {
        setTimeout(check, 50);
      } else {
        cb('entry button not visible');
      }
    }
    check();
  }

  function destroyEruda() {
    try {
      if (self.eruda && self.eruda._isInit) self.eruda.destroy();
    } catch (e) {
      if (self.eruda) {
        self.eruda._isInit = false;
        self.eruda._container = null;
        self.eruda._shadowRoot = null;
      }
    }
    removeFixStyle();
    erudaActive = false;
  }

  function initEruda(cb) {
    try {
      destroyEruda();
      installFixStyle();
      self.eruda.init({
        useShadowDom: false,
        tool: tools,
      });
      resetPanelOptions();
      centerEntryButton();
      verifyVisible(function (err) {
        if (err) {
          destroyEruda();
          cb(err);
          return;
        }
        erudaActive = true;
        cb(null);
      });
    } catch (e) {
      destroyEruda();
      cb('init error: ' + describeError(e));
    }
  }

  function toggle() {
    if (erudaActive) {
      destroyEruda();
      postStatus('destroyed');
      return;
    }

    loadEruda(function (err) {
      if (err) {
        postStatus('load error: ' + err);
        return;
      }
      initEruda(function (initErr) {
        postStatus(initErr ? initErr : 'ok');
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
