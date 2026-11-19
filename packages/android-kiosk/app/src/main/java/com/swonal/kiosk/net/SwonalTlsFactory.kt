package com.swonal.kiosk.net

import android.content.Context
import com.swonal.kiosk.R
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class SwonalTlsConfig(
    val sslSocketFactory: SSLSocketFactory,
    val trustManager: X509TrustManager
)

/**
 * Bundles modern Root CA certificates (Let's Encrypt ISRG Root X1/X2, Google Trust Services R1/R4,
 * DigiCert Global Root G2/G3) merged with Android's platform trust store so Android 6.0 (API 23)
 * negotiates TLS handshakes cleanly with modern HTTPS reverse proxies.
 */
object SwonalTlsFactory {

    fun create(context: Context): SwonalTlsConfig {
        return context.resources.openRawResource(R.raw.swonal_extra_roots).use { pemStream ->
            createFromPemStream(pemStream)
        }
    }

    fun createFromPemStream(pemStream: InputStream): SwonalTlsConfig {
        val platformTm = defaultSystemTrustManager()
        val extraTm = trustManagerFromPem(pemStream)
        val compositeTm = CompositeX509TrustManager(listOf(platformTm, extraTm))
        val sslContext = SSLContext.getInstance("TLSv1.2").apply {
            init(null, arrayOf(compositeTm), null)
        }
        return SwonalTlsConfig(
            sslSocketFactory = sslContext.socketFactory,
            trustManager = compositeTm
        )
    }

    private fun defaultSystemTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun trustManagerFromPem(pemStream: InputStream): X509TrustManager {
        val cf = CertificateFactory.getInstance("X.509")
        val certs = cf.generateCertificates(pemStream)
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certs.forEachIndexed { idx, cert ->
                setCertificateEntry("swonal_root_$idx", cert)
            }
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private class CompositeX509TrustManager(
        private val delegates: List<X509TrustManager>
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            var lastError: CertificateException? = null
            for (tm in delegates) {
                try {
                    tm.checkClientTrusted(chain, authType)
                    return
                } catch (e: CertificateException) {
                    lastError = e
                }
            }
            throw lastError ?: CertificateException("Untrusted client certificate")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            var lastError: CertificateException? = null
            for (tm in delegates) {
                try {
                    tm.checkServerTrusted(chain, authType)
                    return
                } catch (e: CertificateException) {
                    lastError = e
                }
            }
            throw lastError ?: CertificateException("Untrusted server certificate")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return delegates.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
        }
    }
}
