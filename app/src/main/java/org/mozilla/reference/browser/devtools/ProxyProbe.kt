/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.devtools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.mozilla.geckoview.GeckoPreferenceController
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Phase 0 spike (MITM-proxy plan): a toggleable, transparent local proxy that
 * proves GeckoView routes its traffic through a local port (the "0a" question).
 *
 * It is a blind tunnel: HTTPS CONNECTs are relayed byte-for-byte (TLS is NOT
 * terminated), so browsing keeps working while it is on. It does not yet prove
 * CA trust (that needs TLS termination, the next step). Default OFF; flip via
 * the toolbar menu. On the first observed CONNECT it shows a Toast so routing
 * can be confirmed on-device.
 */
object ProxyProbe {
    private const val TAG = "BHProxySpike"
    private const val LOCALHOST = "127.0.0.1"

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var firstConnectReported = false
    @Volatile private var firstHttpReported = false
    @Volatile private var firstUpstreamErrReported = false
    @Volatile private var firstMitmReported = false
    @Volatile private var firstMitmErrReported = false
    private var appContext: Context? = null

    @Synchronized
    fun toggle(context: Context) {
        appContext = context.applicationContext
        if (running) stop() else start()
    }

    /**
     * Called once when the GeckoRuntime is (re)created. The proxy prefs are
     * written to PREF_BRANCH_USER, which persists to disk and is restored on the
     * next process launch — but the ServerSocket lives only in this in-memory
     * object and dies with the process. So after a background kill + relaunch,
     * Gecko points at a dead local port and every request fails with
     * NS_ERROR_PROXY_CONNECTION_REFUSED until the probe is re-armed. Reset to
     * direct on startup so a cold launch is always clean; the user re-toggles to
     * re-arm (a fresh socket + fresh port).
     */
    @Synchronized
    fun resetProxyStateOnStartup(context: Context) {
        appContext = context.applicationContext
        running = false
        setInt("network.proxy.type", 0)
    }

    private fun toast(msg: String) {
        Log.i(TAG, msg)
        val ctx = appContext ?: return
        main.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
    }

    private fun start() {
        try {
            appContext?.let { MitmCa.ensureRootCa(it) }
            val srv = ServerSocket(0, 50, InetAddress.getByName(LOCALHOST))
            server = srv
            running = true
            firstConnectReported = false
            firstHttpReported = false
            firstUpstreamErrReported = false
            firstMitmReported = false
            firstMitmErrReported = false
            val port = srv.localPort
            Thread({ acceptLoop(srv) }, "bh-proxy-accept").apply { isDaemon = true }.start()
            applyProxyPrefs(port)
            toast("PROXY-SPIKE: probe ON, port=$port (打开一个 https 网站看是否弹出 CONNECT)")
        } catch (t: Throwable) {
            running = false
            toast("PROXY-SPIKE: start FAILED: ${t.message}")
        }
    }

    private fun stop() {
        running = false
        try { server?.close() } catch (_: Throwable) {}
        server = null
        clearProxyPrefs()
        toast("PROXY-SPIKE: probe OFF (已恢复直连)")
    }

    private fun acceptLoop(srv: ServerSocket) {
        while (running) {
            val client = try {
                srv.accept()
            } catch (_: Throwable) {
                break
            }
            Thread({ handle(client) }, "bh-proxy-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) {
        try {
            val cin = client.getInputStream()
            val cout = client.getOutputStream()
            val head = readHead(cin) ?: run { client.close(); return }
            val firstLine = head.substringBefore("\r\n")
            val parts = firstLine.split(" ")
            if (parts.size >= 2 && parts[0].equals("CONNECT", ignoreCase = true)) {
                val hostPort = parts[1]
                val host = hostPort.substringBefore(":")
                val pPort = hostPort.substringAfter(":", "443").toIntOrNull() ?: 443
                if (!firstConnectReported) {
                    firstConnectReported = true
                    toast("PROXY-SPIKE: 收到 CONNECT $hostPort —— GeckoView 已走本地代理 ✅")
                }
                // Tell the browser the tunnel is up, then MITM the TLS inside it.
                cout.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                cout.flush()
                mitm(client, host, pPort)
            } else {
                // Plain HTTP: best-effort blind forward to Host:80.
                val host = headerValue(head, "Host")
                if (host == null) { client.close(); return }
                val h = host.substringBefore(":")
                val p = host.substringAfter(":", "80").toIntOrNull() ?: 80
                if (!firstHttpReported) {
                    firstHttpReported = true
                    toast("PROXY-SPIKE: 收到 HTTP $h —— GeckoView 已走本地代理 ✅")
                }
                val upstream = Socket(h, p)
                upstream.getOutputStream().write(head.toByteArray())
                upstream.getOutputStream().flush()
                pump(cin, upstream.getOutputStream())
                pump(upstream.getInputStream(), cout)
            }
        } catch (_: Throwable) {
            try { client.close() } catch (_: Throwable) {}
        }
    }

    // Phase 0b: terminate TLS inside the CONNECT tunnel. We present a leaf cert
    // for [host] (signed by our installed root CA) to the browser, open a real
    // TLS connection upstream, then relay the now-plaintext HTTP both ways —
    // sniffing the first request line to prove decryption works. Forces HTTP/1.1
    // (no ALPN h2 advertised). This only inspects the user's own traffic on the
    // user's own device; nothing is exfiltrated.
    private fun mitm(client: Socket, host: String, port: Int) {
        val tlsClient = try {
            val ctx = MitmCa.serverContextFor(host)
            (ctx.socketFactory.createSocket(client, host, port, true) as SSLSocket).apply {
                useClientMode = false
                startHandshake()
            }
        } catch (e: Throwable) {
            if (!firstMitmErrReported) {
                firstMitmErrReported = true
                toast("PROXY-SPIKE: 与浏览器 TLS 握手失败 $host: ${e.message}（是否已安装并信任根证书？）")
            }
            try { client.close() } catch (_: Throwable) {}
            return
        }
        val upstream = try {
            (SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket).apply { startHandshake() }
        } catch (e: Throwable) {
            if (!firstUpstreamErrReported) {
                firstUpstreamErrReported = true
                toast("PROXY-SPIKE: 上游 TLS 失败 $host: ${e.message}")
            }
            try { tlsClient.close() } catch (_: Throwable) {}
            return
        }
        try {
            val cIn = tlsClient.inputStream
            val cOut = tlsClient.outputStream
            val uIn = upstream.inputStream
            val uOut = upstream.outputStream
            val line = readReqLine(cIn)
            if (line != null) {
                if (!firstMitmReported) {
                    firstMitmReported = true
                    toast("PROXY-SPIKE: 已解密 HTTPS ✅ $host ▶ ${line.toString(Charsets.ISO_8859_1).trim()}")
                }
                uOut.write(line)
                uOut.flush()
            }
            pump(cIn, uOut)
            pump(uIn, cOut)
        } catch (_: Throwable) {
            try { tlsClient.close() } catch (_: Throwable) {}
            try { upstream.close() } catch (_: Throwable) {}
        }
    }

    // Read one line (up to and including \n) from a now-plaintext stream.
    private fun readReqLine(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() > 0) out.toByteArray() else null
            out.write(b)
            if (b == '\n'.code) return out.toByteArray()
            if (out.size() > 16 * 1024) return out.toByteArray()
        }
    }

    // Relay bytes on a daemon thread; closes nothing so both directions can run.
    private fun pump(from: InputStream, to: OutputStream) {
        Thread({
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = from.read(buf)
                    if (n < 0) break
                    to.write(buf, 0, n)
                    to.flush()
                }
            } catch (_: Throwable) {
            } finally {
                try { to.close() } catch (_: Throwable) {}
                try { from.close() } catch (_: Throwable) {}
            }
        }, "bh-proxy-pump").apply { isDaemon = true }.start()
    }

    // Read request head (request line + headers) up to the blank line, byte by
    // byte so we never consume the tunneled body / TLS bytes that follow.
    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var state = 0 // counts \r \n \r \n
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() > 0) out.toString("ISO-8859-1") else null
            out.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
            if (state == 4) return out.toString("ISO-8859-1")
            if (out.size() > 64 * 1024) return out.toString("ISO-8859-1")
        }
    }

    private fun headerValue(head: String, name: String): String? {
        head.split("\r\n").forEach { line ->
            val i = line.indexOf(':')
            if (i > 0 && line.substring(0, i).trim().equals(name, ignoreCase = true)) {
                return line.substring(i + 1).trim()
            }
        }
        return null
    }

    private fun applyProxyPrefs(port: Int) {
        setInt("network.proxy.type", 1)
        setStr("network.proxy.http", LOCALHOST)
        setInt("network.proxy.http_port", port)
        setStr("network.proxy.ssl", LOCALHOST)
        setInt("network.proxy.ssl_port", port)
        setBool("network.proxy.share_proxy_settings", true)
    }

    private fun clearProxyPrefs() {
        setInt("network.proxy.type", 0)
    }

    private fun setInt(name: String, value: Int) {
        try {
            GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER)
                .accept({ _ -> }, { e -> toast("PROXY-SPIKE: pref FAIL $name: ${e?.message}") })
        } catch (t: Throwable) { toast("PROXY-SPIKE: pref THREW $name: ${t.message}") }
    }

    private fun setStr(name: String, value: String) {
        try {
            GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER)
                .accept({ _ -> }, { e -> toast("PROXY-SPIKE: pref FAIL $name: ${e?.message}") })
        } catch (t: Throwable) { toast("PROXY-SPIKE: pref THREW $name: ${t.message}") }
    }

    private fun setBool(name: String, value: Boolean) {
        try {
            GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER)
                .accept({ _ -> }, { e -> toast("PROXY-SPIKE: pref FAIL $name: ${e?.message}") })
        } catch (t: Throwable) { toast("PROXY-SPIKE: pref THREW $name: ${t.message}") }
    }
}
