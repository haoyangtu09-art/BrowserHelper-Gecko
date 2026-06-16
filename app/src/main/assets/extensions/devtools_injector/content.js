// Content script: load patched Eruda inside the extension isolated world.
// Page-world <script> injection is blocked by strict CSP on sites such as
// chatgpt.com, and light-DOM UI is easily corrupted by page CSS.
(function () {
  var port = null;
  var erudaReady = false;
  var erudaActive = false;
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
      style.display !== 'none' &&
      style.visibility !== 'hidden' &&
      style.opacity !== '0';
  }

  function verifyVisible(cb) {
    var attempts = 0;
    function check() {
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
    erudaActive = false;
  }

  function initEruda(cb) {
    try {
      destroyEruda();
      self.eruda.init({
        useShadowDom: true,
        tool: tools,
      });
      if (self.eruda.position) {
        self.eruda.position({
          x: Math.max(0, window.innerWidth - 60),
          y: Math.max(0, window.innerHeight - 90),
        });
      }
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
