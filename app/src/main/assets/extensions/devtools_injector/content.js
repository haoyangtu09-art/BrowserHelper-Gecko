// Content script: dynamically loads eruda via fetch+Function to avoid
// content-script size limits and ensure proper global assignment.
(function () {
  var port = null;
  var erudaActive = false;
  var erudaReady = false;

  function loadEruda(cb) {
    if (erudaReady) { cb(null); return; }
    fetch(browser.runtime.getURL('eruda.min.js'))
      .then(function (r) { return r.text(); })
      .then(function (code) {
        // Execute in content script scope via Function constructor so that
        // `self` refers to this isolated world's global, making eruda available.
        try {
          // Wrap to capture return value in case UMD exports via module
          var fn = new Function(code + '\nreturn typeof eruda !== "undefined" ? eruda : null;');
          var result = fn();
          if (result && !self.eruda) self.eruda = result;
          erudaReady = typeof self.eruda !== 'undefined';
          cb(erudaReady ? null : 'eruda still undefined after Function exec');
        } catch (e) {
          cb(String(e && e.message ? e.message : e));
        }
      })
      .catch(function (e) { cb('fetch failed: ' + e); });
  }

  function toggle() {
    if (erudaActive) {
      try { self.eruda.destroy(); } catch (e) {}
      erudaActive = false;
      port.postMessage({ status: 'destroyed' });
    } else {
      loadEruda(function (err) {
        if (err) {
          port.postMessage({ status: 'load error: ' + err });
          return;
        }
        try {
          if (self.eruda._isInit) {
            self.eruda._isInit = false;
            self.eruda._container = null;
            self.eruda._shadowRoot = null;
          }
          self.eruda.init({ useShadowDom: false });
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
