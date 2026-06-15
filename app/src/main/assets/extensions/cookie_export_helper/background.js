let port = null;
let connected = false;
let ackTimer = null;

function normalizeSameSite(value) {
  if (value === "strict") {
    return "Strict";
  }
  if (value === "no_restriction" || value === "none") {
    return "None";
  }
  return "Lax";
}

async function handleCookieRequest(message) {
  try {
    const cookies = await browser.cookies.getAll({ url: message.url });
    port.postMessage({
      id: message.id,
      cookies: cookies.map((cookie) => ({
        name: cookie.name,
        value: cookie.value,
        domain: cookie.domain,
        path: cookie.path || "/",
        secure: !!cookie.secure,
        httpOnly: !!cookie.httpOnly,
        sameSite: normalizeSameSite(cookie.sameSite)
      }))
    });
  } catch (error) {
    port.postMessage({
      id: message.id,
      error: String(error && error.message ? error.message : error)
    });
  }
}

function handleMessage(message) {
  // Control messages from the native side carry a "type" field.
  if (message && message.type === "connected") {
    connected = true;
    if (ackTimer) {
      clearTimeout(ackTimer);
      ackTimer = null;
    }
    return;
  }
  if (message && message.id) {
    handleCookieRequest(message);
  }
}

function cleanup() {
  connected = false;
  if (ackTimer) {
    clearTimeout(ackTimer);
    ackTimer = null;
  }
  port = null;
}

function connect() {
  if (port) {
    return;
  }
  try {
    port = browser.runtime.connectNative("cookie_export");
    port.onMessage.addListener(handleMessage);
    port.onDisconnect.addListener(() => {
      cleanup();
    });
    // The native side acknowledges via {type:"connected"} from onPortConnected.
    // If no ack arrives the port never reached the handler (registered too late
    // or a zombie connection); drop it so the watchdog can reconnect.
    ackTimer = setTimeout(() => {
      ackTimer = null;
      if (!connected && port) {
        try {
          port.disconnect();
        } catch (error) {
          // ignore
        }
        cleanup();
      }
    }, 2000);
  } catch (error) {
    cleanup();
  }
}

setInterval(connect, 1000);
connect();
