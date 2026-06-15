let contentPort = null;

function parseDocumentCookies() {
  const raw = document.cookie || "";
  if (!raw.trim()) {
    return [];
  }
  return raw.split(";").map((item) => {
    const index = item.indexOf("=");
    const name = (index >= 0 ? item.slice(0, index) : item).trim();
    const value = index >= 0 ? item.slice(index + 1).trim() : "";
    return {
      name,
      value,
      domain: location.hostname,
      path: "/",
      secure: location.protocol === "https:",
      httpOnly: false,
      sameSite: "Lax"
    };
  }).filter((cookie) => cookie.name.length > 0);
}

function handleNativeMessage(message) {
  try {
    contentPort.postMessage({
      id: message.id,
      cookies: parseDocumentCookies()
    });
  } catch (error) {
    contentPort.postMessage({
      id: message.id,
      error: String(error && error.message ? error.message : error)
    });
  }
}

function connectContentPort() {
  try {
    contentPort = browser.runtime.connectNative("cookie_export");
    contentPort.onMessage.addListener(handleNativeMessage);
    contentPort.onDisconnect.addListener(() => {
      contentPort = null;
      setTimeout(connectContentPort, 1000);
    });
  } catch (error) {
    contentPort = null;
    setTimeout(connectContentPort, 1000);
  }
}

connectContentPort();
