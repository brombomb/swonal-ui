package com.swonal.kiosk.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NostrCryptoTest {

    @Test
    fun `matches nostr-tools reference keys and BIP-340 Schnorr vector`() {
        val skHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val expectedPubHex = "4646ae5047316b4230d0086c8acec687f00b1cd9d1dc634f6cb358ac0a9a8fff"
        val expectedNsec = "nsec1qy352euf40x77qfrg4ncn27dauqjx3t83x4ummcpydzk0zdtehhs80zqrl"
        val expectedNpub = "npub1ger2u5z8x945yvxsppkg4nkxslcqk8xe68wxxnmvkdv2cz563lls9fwehy"

        val derivedPub = NostrCrypto.getPublicKeyHex(skHex)
        assertEquals(expectedPubHex, derivedPub)
        assertEquals(expectedNsec, NostrCrypto.nsecEncode(skHex))
        assertEquals(expectedNpub, NostrCrypto.npubEncode(derivedPub))

        val (normHex, normNpub) = NostrCrypto.normalizePubkey(expectedNpub)
        assertEquals(expectedPubHex, normHex)
        assertEquals(expectedNpub, normNpub)

        val jsSignedEvent = NostrSignedEvent(
            id = "fa5b715706e69c57834609576399cdf4217b1b9eb42cce1c3df521251b98477f",
            pubkey = expectedPubHex,
            created_at = 1700000000L,
            kind = 30101,
            tags = listOf(listOf("d", "swonal-test"), listOf("t", "swonal")),
            content = "{\"type\":\"workout_completion\",\"totalVolumeLb\":1200}",
            sig = "7fefd4bb7d374c48a3b46daa60cf8433612bb29e0b8814ee60399bee6e1aae389dc5b0ebb4801ad97a8b3ed1cddf442b3937ba313f5c260db3842d9420077aa9"
        )
        assertTrue(NostrCrypto.verifyEvent(jsSignedEvent))

        val tamperedContent = jsSignedEvent.copy(content = "{\"type\":\"workout_completion\",\"totalVolumeLb\":9999}")
        assertFalse(NostrCrypto.verifyEvent(tamperedContent))

        val flippedSig = "8" + jsSignedEvent.sig.drop(1)
        assertFalse(NostrCrypto.verifyEvent(jsSignedEvent.copy(sig = flippedSig)))
    }

    @Test
    fun `generated keys sign and verify cleanly`() {
        val sk = NostrCrypto.generateSecretKeyHex()
        val pk = NostrCrypto.getPublicKeyHex(sk)
        assertEquals(64, sk.length)
        assertEquals(64, pk.length)

        val npub = NostrCrypto.npubEncode(pk)
        assertTrue(npub.startsWith("npub1"))
        val (decodedHex, _) = NostrCrypto.normalizePubkey(npub)
        assertEquals(pk, decodedHex)

        val ev = NostrCrypto.signEvent(
            privKeyHex = sk,
            kind = 30102,
            tags = listOf(listOf("d", "routine-1"), listOf("t", "swonal")),
            content = "{\"type\":\"routine_share\",\"name\":\"Push Day\"}",
            createdAtSec = 1730000000L
        )
        assertTrue(NostrCrypto.verifyEvent(ev))
        assertFalse(NostrCrypto.verifyEvent(ev.copy(kind = 1)))
    }
}
