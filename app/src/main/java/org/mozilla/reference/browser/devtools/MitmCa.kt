/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.devtools

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
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
    private const val ROOT_DN = "CN=BrowserHelper MITM CA, O=BrowserHelper, OU=DevTools"

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
            Log.i(TAG, "MitmCa: loaded existing root CA")
        } else {
            generateRoot()
            ks.load(null, null)
            ks.setKeyEntry(ROOT_ALIAS, rootKey, KS_PASS, arrayOf(rootCert))
            f.outputStream().use { ks.store(it, KS_PASS) }
            Log.i(TAG, "MitmCa: generated new root CA")
        }
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
        val issuer = X500Name(root.subjectX500Principal.name)
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
