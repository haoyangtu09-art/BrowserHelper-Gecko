/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.devtools

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Phase 0b (MITM-proxy plan): an in-app root CA + per-host leaf minting, so the
 * local debug proxy can terminate TLS for the user's own browser traffic
 * (a network inspector like Charles/Fiddler, on the user's own device, with the
 * root cert installed manually by the user — no root required).
 *
 * The root CA is generated once and persisted in app-private storage. For each
 * target host we mint a short-lived leaf signed by the root and build a cached
 * server [SSLContext] presenting [leaf, root].
 */
object MitmCa {
    private const val TAG = "BHProxySpike"
    private const val KS_FILE = "bh-mitm-root.p12"
    private val KS_PASS = "bhmitm".toCharArray()
    private const val ROOT_ALIAS = "root"
    // The Android "Trusted credentials" list shows the cert's own subject name,
    // and the manual CA install flow doesn't let the user rename it, so bake the
    // friendly name straight into the subject.
    private const val ROOT_CN = "抓包前置"
    private const val ROOT_DN = "CN=$ROOT_CN, O=$ROOT_CN"
    private const val CERT_FILE = "抓包前置.crt"

    private val rng = SecureRandom()
    private val leafCache = ConcurrentHashMap<String, SSLContext>()

    @Volatile private var rootCert: X509Certificate? = null
    @Volatile private var rootKey: PrivateKey? = null

    init {
        // Register the real BouncyCastle provider (distinct from Android's hidden
        // com.android.org.bouncycastle) so cert building/signing is available.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Synchronized
    fun ensureRootCa(context: Context) {
        if (rootCert != null && rootKey != null) return
        val f = File(context.applicationContext.filesDir, KS_FILE)
        val ks = KeyStore.getInstance("PKCS12")
        if (f.exists()) {
            f.inputStream().use { ks.load(it, KS_PASS) }
            rootKey = ks.getKey(ROOT_ALIAS, KS_PASS) as PrivateKey
            rootCert = ks.getCertificate(ROOT_ALIAS) as X509Certificate
            // A root persisted before the friendly-name change carries the old
            // subject; regenerate so the installed cert shows the right name.
            if (rootCert!!.subjectX500Principal.name.contains(ROOT_CN)) {
                Log.i(TAG, "MitmCa: loaded existing root CA")
                return
            }
            Log.i(TAG, "MitmCa: stale root CA name, regenerating")
        }
        generateRoot()
        leafCache.clear()
        ks.load(null, null)
        ks.setKeyEntry(ROOT_ALIAS, rootKey, KS_PASS, arrayOf(rootCert))
        f.outputStream().use { ks.store(it, KS_PASS) }
        Log.i(TAG, "MitmCa: generated new root CA")
    }

    /**
     * Save the root cert (DER `.crt`) to the public Downloads folder so the user
     * can install it manually via Settings → Security → Install a certificate →
     * CA certificate (apps can no longer install CA certs directly on Android 11+).
     * Returns a human-readable location for a Toast.
     */
    fun exportRootCert(context: Context): String {
        val der = rootCertDer(context)
        val ctx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(CERT_FILE),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    resolver.delete(ContentUris.withAppendedId(collection, c.getLong(0)), null, null)
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, CERT_FILE)
                put(MediaStore.Downloads.MIME_TYPE, "application/x-x509-ca-cert")
            }
            val uri = resolver.insert(collection, values) ?: error("无法写入下载目录")
            resolver.openOutputStream(uri)?.use { it.write(der) } ?: error("无法打开输出流")
            return "Download/$CERT_FILE"
        }
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val out = File(dir, CERT_FILE)
        out.outputStream().use { it.write(der) }
        return out.absolutePath
    }

    /** DER bytes of the root cert, for KeyChain.createInstallIntent(). */
    fun rootCertDer(context: Context): ByteArray {
        ensureRootCa(context)
        return rootCert!!.encoded
    }

    /** Server-side SSLContext that presents a freshly minted leaf for [host]. */
    fun serverContextFor(host: String): SSLContext {
        return leafCache.getOrPut(host) { buildLeafContext(host) }
    }

    private fun generateRoot() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, rng) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 3600 * 1000)
        val notAfter = Date(now + 3650L * 24 * 3600 * 1000)
        val dn = X500Name(ROOT_DN)
        val serial = BigInteger.valueOf(now)
        val builder = JcaX509v3CertificateBuilder(dn, serial, notBefore, notAfter, dn, kp.public)
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        rootCert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        rootKey = kp.private
    }

    private fun buildLeafContext(host: String): SSLContext {
        val root = rootCert ?: error("root CA not initialized")
        val signerKey = rootKey ?: error("root CA not initialized")
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, rng) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 3600 * 1000)
        val notAfter = Date(now + 825L * 24 * 3600 * 1000)
        // Use the root's exact subject DER as the leaf's issuer. Going through
        // subjectX500Principal.name (an RFC2253 string) and re-parsing reverses
        // RDN order and can change the ASN.1 string type, so the leaf's issuer
        // bytes no longer byte-match the root's subject bytes — mozilla::pkix
        // chains issuer↔subject by exact DER, so the leaf would fail to link to
        // the (even trusted) root and the browser reports SEC_ERROR_UNKNOWN_ISSUER.
        val issuer = JcaX509CertificateHolder(root).subject
        val serial = BigInteger(64, rng).abs().add(BigInteger.ONE)
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            serial,
            notBefore,
            notAfter,
            X500Name("CN=$host"),
            kp.public,
        )
        builder.addExtension(Extension.basicConstraints, false, BasicConstraints(false))
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(GeneralName(GeneralName.dNSName, host)),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(signerKey)
        val leaf = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("leaf", kp.private, CharArray(0), arrayOf(leaf, root))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, CharArray(0))
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }
}
