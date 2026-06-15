const port = browser.runtime.connectNative("cookie_export");

function normalizeSameSite(value) {
  if (value === "strict") {
    return "Strict";
  }
  if (value === "no_restriction" || value === "none") {
    return "None";
  }
  return "Lax";
}

port.onMessage.addListener(async (message) => {
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
});
