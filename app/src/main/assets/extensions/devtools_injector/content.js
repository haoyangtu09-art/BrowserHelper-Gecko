// Content script: loads patched eruda into the page's real window.
// Uses fetch (from content script, which bypasses page CSP) to get the
// eruda source, creates a Blob URL, and injects it via <script src>.
// This runs eruda in the page's true window, avoiding isolated-world limits.
(function () {
  var port = null;
  var blobUrl = null;
  var erudaReady = false;
  var erudaActive = false;

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

  function toggle() {
    if (erudaActive) {
      runInPage('try{eruda.destroy();}catch(e){}');
      erudaActive = false;
      port.postMessage({ status: 'destroyed' });
    } else {
      loadEruda(function (err) {
        if (err) { port.postMessage({ status: 'load error: ' + err }); return; }
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
        erudaActive = true;
        port.postMessage({ status: 'ok' });
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
