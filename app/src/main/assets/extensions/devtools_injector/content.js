// Content script: loads patched eruda into the page's real window.
// Uses fetch (from content script, which bypasses page CSP) to get the
// eruda source, creates a Blob URL, and injects it via <script src>.
// This runs eruda in the page's true window, avoiding isolated-world limits.
(function () {
  var port = null;
  var blobUrl = null;
  var erudaReady = false;
  var erudaActive = false;
  var fallbackHost = null;

  function loadEruda(cb) {
    if (erudaReady) { cb(null); return; }

    // fetch() in content script bypasses page CSP.
    fetch(browser.runtime.getURL('eruda.min.js'))
      .then(function (r) { return r.blob(); })
      .then(function (blob) {
        blobUrl = URL.createObjectURL(blob);
        var script = document.createElement('script');
        script.src = blobUrl;
        script.onload = function () {
          script.remove();
          erudaReady = true;
          cb(null);
        };
        script.onerror = function () {
          script.remove();
          cb('blob script blocked');
        };
        (document.head || document.documentElement).appendChild(script);
      })
      .catch(function (e) { cb('fetch error: ' + e); });
  }

  function runInPage(js) {
    var s = document.createElement('script');
    s.textContent = js;
    (document.head || document.documentElement).appendChild(s);
    s.remove();
  }

  function postStatus(status) {
    try {
      if (port) port.postMessage({ status: status });
    } catch (e) {}
  }

  function applyStyles(el, styles) {
    Object.keys(styles).forEach(function (key) {
      el.style.setProperty(key, styles[key], 'important');
    });
  }

  function realEntryVisible() {
    var btn = document.querySelector('#eruda .eruda-entry-btn');
    if (!btn) return false;
    var rect = btn.getBoundingClientRect();
    var style = window.getComputedStyle(btn);
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

  function verifyRealEntry(cb) {
    var tries = 0;
    function check() {
      if (realEntryVisible()) {
        cb(true);
        return;
      }
      tries += 1;
      if (tries < 12) {
        setTimeout(check, 100);
      } else {
        cb(false);
      }
    }
    check();
  }

  function removeFallbackButton() {
    if (fallbackHost) {
      fallbackHost.remove();
      fallbackHost = null;
    }
  }

  function showFallbackButton() {
    removeFallbackButton();

    fallbackHost = document.createElement('div');
    fallbackHost.id = 'browserhelper-devtools-fallback';
    applyStyles(fallbackHost, {
      position: 'fixed',
      left: '50%',
      top: '50%',
      transform: 'translate(-50%, -50%)',
      width: '56px',
      height: '56px',
      'z-index': '2147483647',
      'pointer-events': 'auto',
    });

    var button = document.createElement('button');
    button.type = 'button';
    button.setAttribute('aria-label', 'DevTools');
    button.innerHTML = '&#9881;';
    applyStyles(button, {
      width: '56px',
      height: '56px',
      margin: '0',
      padding: '0',
      border: '0',
      'border-radius': '50%',
      background: '#111827',
      color: '#ffffff',
      'box-shadow': '0 8px 24px rgba(0,0,0,.35)',
      'font-size': '28px',
      'line-height': '56px',
      'font-family': 'sans-serif',
      'text-align': 'center',
      cursor: 'pointer',
      opacity: '1',
      'pointer-events': 'auto',
    });
    button.addEventListener('click', function (event) {
      event.preventDefault();
      event.stopPropagation();
      var realBtn = document.querySelector('#eruda .eruda-entry-btn');
      if (realEntryVisible() && realBtn) {
        realBtn.click();
      } else {
        postStatus('ok');
      }
    }, true);
    fallbackHost.appendChild(button);
    (document.documentElement || document.body).appendChild(fallbackHost);
  }

  function toggle() {
    if (erudaActive) {
      runInPage('try{eruda.destroy();}catch(e){}');
      removeFallbackButton();
      erudaActive = false;
      port.postMessage({ status: 'destroyed' });
    } else {
      loadEruda(function (err) {
        if (err) {
          showFallbackButton();
          erudaActive = true;
          port.postMessage({ status: 'ok' });
          return;
        }
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
        verifyRealEntry(function (visible) {
          if (!visible) showFallbackButton();
          erudaActive = true;
          port.postMessage({ status: 'ok' });
        });
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
