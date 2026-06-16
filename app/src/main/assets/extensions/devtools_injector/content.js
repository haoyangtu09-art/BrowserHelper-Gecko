// Content script: eruda.min.js is loaded before this file by the manifest.
(function () {
  var port = null;
  var erudaActive = false;

  function toggle() {
    if (erudaActive) {
      try { eruda.destroy(); } catch (e) {}
      erudaActive = false;
      port.postMessage({ status: 'destroyed' });
    } else {
      var errMsg = 'none';
      try {
        if (typeof eruda === 'undefined') {
          errMsg = 'eruda is undefined';
        } else {
          if (eruda._isInit) {
            eruda._isInit = false;
            eruda._container = null;
            eruda._shadowRoot = null;
          }
          eruda.init({ useShadowDom: false });
          erudaActive = true;
          errMsg = 'ok';
        }
      } catch (e) {
        errMsg = String(e && e.message ? e.message : e);
      }
      port.postMessage({ status: errMsg });
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


