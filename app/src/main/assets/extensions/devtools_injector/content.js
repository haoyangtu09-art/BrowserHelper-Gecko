// Content script: runs in every http/https page.
// Opens a native port so the Android app can send toggle commands.
(function () {
  var port = null;
  var erudaCode = null;  // cached source text
  var erudaActive = false;

  function fetchEruda(cb) {
    if (erudaCode !== null) { cb(erudaCode); return; }
    fetch(browser.runtime.getURL('eruda.min.js'))
      .then(function (r) { return r.text(); })
      .then(function (text) { erudaCode = text; cb(erudaCode); })
      .catch(function () { cb(null); });
  }

  function injectAndInit(code) {
    // Run Eruda in the page's MAIN world by injecting an inline <script> tag.
    // This lets Eruda patch fetch/XHR for Network panel monitoring.
    var s = document.createElement('script');
    s.textContent = code + '\neruda.init();';
    (document.head || document.documentElement).appendChild(s);
    s.remove();
    erudaActive = true;
  }

  function toggle() {
    if (erudaActive) {
      var s = document.createElement('script');
      s.textContent = 'try { eruda.destroy(); } catch(e) {}';
      (document.head || document.documentElement).appendChild(s);
      s.remove();
      erudaActive = false;
    } else {
      fetchEruda(function (code) {
        if (code) injectAndInit(code);
      });
    }
  }

  function connect() {
    try {
      port = browser.runtime.connectNative('devtools_inject');
      port.onMessage.addListener(function (msg) {
        if (msg && msg.action === 'toggle') {
          toggle();
        }
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
