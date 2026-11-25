package com.swonal.kiosk.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.cert.X509Certificate

class LocalTlsCertManagerTest {

    @Test
    fun generatesAndReloadsPkcs12AndPemWithLanSans(@TempDir tempDir: File) {
        val tlsDir = File(tempDir, "tls")
        val manager = LocalTlsCertManager(tlsDir)

        val ksFile = manager.ensureKeystore(listOf("192.168.0.140"))
        assertTrue(ksFile.exists())
        val firstMtime = ksFile.lastModified()

        // Calling again with identical IPs reuses cached keystore
        val ksFile2 = manager.ensureKeystore(listOf("192.168.0.140"))
        assertEquals(ksFile.absolutePath, ksFile2.absolutePath)
        assertEquals(firstMtime, ksFile2.lastModified())

        // Verify PKCS#12 chain and SAN entries
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(ksFile).use { ks.load(it, "swonal-local-tls".toCharArray()) }
        val chain = ks.getCertificateChain("swonal")
        assertNotNull(chain)
        assertEquals(2, chain.size)

        val leaf = chain[0] as X509Certificate
        val ca = chain[1] as X509Certificate
        assertTrue(ca.basicConstraints >= 0)
        assertEquals(-1, leaf.basicConstraints)

        val sans = leaf.subjectAlternativeNames?.map { it[1].toString() }.orEmpty()
        assertTrue(sans.contains("localhost"), "Expected localhost DNS SAN, got $sans")
        assertTrue(sans.contains("127.0.0.1"), "Expected 127.0.0.1 IP SAN, got $sans")
        assertTrue(sans.contains("192.168.0.140"), "Expected LAN IP SAN, got $sans")

        // Verify PEM export and SSLServerSocketFactory creation
        val pem = manager.getCaCertificatePem(listOf("192.168.0.140"))
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue(pem.trimEnd().endsWith("-----END CERTIFICATE-----"))

        val factory = manager.createServerSocketFactory(listOf("192.168.0.140"))
        assertNotNull(factory)
    }
}
