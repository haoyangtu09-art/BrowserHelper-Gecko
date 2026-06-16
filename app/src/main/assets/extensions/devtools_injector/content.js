// Content script: eruda.min.js is loaded before this file by the manifest.
(function () {
  var port = null;
  var erudaActive = false;

  function toggle() {
    if (erudaActive) {
      try { eruda.destroy(); } catch (e) {}
      erudaActive = false;
    } else {
      try {
        if (typeof eruda !== 'undefined' && eruda._isInit) {
          eruda._isInit = false;
          eruda._container = null;
          eruda._shadowRoot = null;
        }
        // Disable shadow DOM — content script world's attachShadow may not
        // render visibly in GeckoView's isolated context.
        eruda.init({ useShadowDom: false });
        erudaActive = true;
      } catch (e) {}
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

