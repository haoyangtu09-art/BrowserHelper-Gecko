let port = null;
let erudaCode = null; // cached content of eruda.min.js

async function loadEruda() {
  if (erudaCode) return erudaCode;
  const url = browser.runtime.getURL("eruda.min.js");
  const resp = await fetch(url);
  erudaCode = await resp.text();
  return erudaCode;
}

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
          const code = await loadEruda();

          // Step 1: run in content script world to check/flip the toggle flag
          const results = await browser.tabs.executeScript(tabId, {
            code: `window.__erudaActive || false`,
            runAt: "document_idle"
          });
          const isActive = results && results[0] === true;

          if (isActive) {
            // Destroy: flip flag and remove the Eruda container from the page
            await browser.tabs.executeScript(tabId, {
              code: `
                (function() {
                  window.__erudaActive = false;
                  var el = document.getElementById('eruda');
                  if (el) el.remove();
                })();
              `,
              runAt: "document_idle"
            });
          } else {
            // Inject Eruda inline via a <script> tag so it runs in MAIN world.
            // The script content is inlined to bypass page CSP restrictions on
            // external URLs. JSON.stringify safely escapes the source string.
            const escaped = JSON.stringify(code);
            await browser.tabs.executeScript(tabId, {
              code: `
                (function() {
                  if (window.__erudaActive) return;
                  var s = document.createElement('script');
                  s.textContent = ${escaped};
                  (document.head || document.documentElement).appendChild(s);
                  s.remove();
                  try { eruda.init(); window.__erudaActive = true; } catch(e) {}
                })();
              `,
              runAt: "document_idle"
            });
          }
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
