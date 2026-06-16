// Content script: dynamically loads eruda via fetch+Function.
// eruda.min.js has the window.fetch patch removed to avoid
// read-only errors in the content script isolated world.
(function () {
  var port = null;
  var erudaReady = false;
  var erudaActive = false;

  function loadEruda(cb) {
    if (erudaReady) { cb(null); return; }
    fetch(browser.runtime.getURL('eruda.min.js'))
      .then(function (r) { return r.text(); })
      .then(function (code) {
        try {
          var fn = new Function(code + '\nreturn typeof eruda!=="undefined"?eruda:null;');
          var result = fn();
          if (result && !self.eruda) self.eruda = result;
          erudaReady = typeof self.eruda !== 'undefined';
          cb(erudaReady ? null : 'eruda undefined after exec');
        } catch (e) {
          cb('exec error: ' + String(e && e.message ? e.message : e));
        }
      })
      .catch(function (e) { cb('fetch error: ' + e); });
  }

  function toggle() {
    if (erudaActive) {
      try { self.eruda.destroy(); } catch (e) {}
      erudaActive = false;
      port.postMessage({ status: 'destroyed' });
    } else {
      loadEruda(function (err) {
        if (err) { port.postMessage({ status: 'load error: ' + err }); return; }
        try {
          if (self.eruda._isInit) {
            self.eruda._isInit = false;
            self.eruda._container = null;
            self.eruda._shadowRoot = null;
          }
          self.eruda.init({
            useShadowDom: false,
            tool: ['console', 'elements', 'resources', 'sources', 'info'],
          });
          erudaActive = true;
          port.postMessage({ status: 'ok' });
        } catch (e) {
          port.postMessage({ status: 'init error: ' + String(e && e.message ? e.message : e) });
        }
      });
    }
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
