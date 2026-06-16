// Content script: eruda.min.js is loaded before this file by the manifest,
// so `eruda` is available directly in this content script context.
(function () {
  var port = null;
  var erudaActive = false;

  function toggle() {
    if (erudaActive) {
      try { eruda.destroy(); } catch (e) {}
      erudaActive = false;
    } else {
      try { eruda.init(); erudaActive = true; } catch (e) {}
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
