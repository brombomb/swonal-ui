package com.swonal.kiosk.server

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import org.bouncycastle.util.encoders.Base64 as BcBase64

/**
 * Generates and persists a local self-signed Root CA + LAN server certificate (PKCS#12)
 * for the optional on-device HTTPS Companion listener (:53443).
 *
 * Also exports the Root CA as PEM (`swonal-local-ca.crt`) so mobile browsers can download
 * and trust it via `GET /api/tls/ca.crt` if desired.
 */
class LocalTlsCertManager(private val tlsDir: File) {

    private val keystoreFile = File(tlsDir, "swonal-local-https.p12")
    private val caCertPemFile = File(tlsDir, "swonal-local-ca.crt")
    private val sanStampFile = File(tlsDir, "san-ips.txt")
    private val storePassword = "swonal-local-tls".toCharArray()

    @Synchronized
    fun ensureKeystore(lanIps: List<String>): File {
        tlsDir.mkdirs()
        val normalizedIps = (lanIps + listOf("127.0.0.1")).distinct().sorted()
        val stamp = normalizedIps.joinToString(",")
        if (keystoreFile.exists() && caCertPemFile.exists() && sanStampFile.exists() &&
            runCatching { sanStampFile.readText().trim() == stamp }.getOrDefault(false)
        ) {
            return keystoreFile
        }

        val rng = SecureRandom()
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, rng) }
        val caPair = kpg.generateKeyPair()
        val srvPair = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60L * 60L * 1000L)
        val notAfter = Date(now + 10L * 365L * 24L * 60L * 60L * 1000L)
        val caName = X500Name("CN=Swonal Local Root CA, O=Swonal")
        val srvName = X500Name("CN=Swonal Tonal Companion, O=Swonal")

        val caSigner = JcaContentSignerBuilder("SHA256withRSA").build(caPair.private)
        val caBuilder = JcaX509v3CertificateBuilder(
            caName,
            BigInteger(64, rng).abs().max(BigInteger.ONE),
            notBefore,
            notAfter,
            caName,
            caPair.public
        )
        caBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        caBuilder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )
        val caCert: X509Certificate = JcaX509CertificateConverter().getCertificate(caBuilder.build(caSigner))

        val srvBuilder = JcaX509v3CertificateBuilder(
            caName,
            BigInteger(64, rng).abs().max(BigInteger.ONE),
            notBefore,
            notAfter,
            srvName,
            srvPair.public
        )
        srvBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        srvBuilder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        )
        val sanList = mutableListOf<GeneralName>(GeneralName(GeneralName.dNSName, "localhost"))
        normalizedIps.forEach { ip ->
            sanList += GeneralName(GeneralName.iPAddress, ip)
        }
        srvBuilder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(sanList.toTypedArray())
        )
        val srvCert: X509Certificate = JcaX509CertificateConverter().getCertificate(srvBuilder.build(caSigner))

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, storePassword)
        ks.setKeyEntry("swonal", srvPair.private, storePassword, arrayOf(srvCert, caCert))
        FileOutputStream(keystoreFile).use { ks.store(it, storePassword) }

        val rawB64 = BcBase64.toBase64String(caCert.encoded)
        val wrapped = rawB64.chunked(64).joinToString("\n")
        caCertPemFile.writeText("-----BEGIN CERTIFICATE-----\n$wrapped\n-----END CERTIFICATE-----\n")
        sanStampFile.writeText(stamp)
        return keystoreFile
    }

    fun createServerSocketFactory(lanIps: List<String>): SSLServerSocketFactory {
        val ksFile = ensureKeystore(lanIps)
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(ksFile).use { ks.load(it, storePassword) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, storePassword)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, SecureRandom())
        return ctx.serverSocketFactory
    }

    fun getCaCertificatePem(lanIps: List<String>): String {
        ensureKeystore(lanIps)
        return caCertPemFile.readText()
    }
}
