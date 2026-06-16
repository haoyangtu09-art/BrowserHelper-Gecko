let port = null;

function connect() {
  try {
    port = browser.runtime.connectNative("devtools_inject");
    port.onMessage.addListener(async (message) => {
      if (!message || !message.action) return;
      try {
        const tabs = await browser.tabs.query({ active: true, currentWindow: true });
        if (!tabs.length) {
          port.postMessage({ id: message.id, error: "No active tab" });
          return;
        }
        const tabId = tabs[0].id;
        if (message.action === "toggle") {
          await browser.tabs.executeScript(tabId, {
            file: "eruda.min.js",
            runAt: "document_idle"
          });
          await browser.tabs.executeScript(tabId, {
            code: `
              (function() {
                if (typeof eruda !== 'undefined') {
                  if (window.__erudaActive) {
                    eruda.destroy();
                    window.__erudaActive = false;
                  } else {
                    eruda.init();
                    window.__erudaActive = true;
                  }
                }
              })();
            `,
            runAt: "document_idle"
          });
          port.postMessage({ id: message.id, ok: true });
        }
      } catch (e) {
        port.postMessage({ id: message.id, error: String(e && e.message ? e.message : e) });
      }
    });
    port.onDisconnect.addListener(() => {
      port = null;
      setTimeout(connect, 1000);
    });
  } catch (e) {
    port = null;
    setTimeout(connect, 1000);
  }
}

connect();
