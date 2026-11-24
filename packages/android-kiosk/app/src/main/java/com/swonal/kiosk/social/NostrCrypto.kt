package com.swonal.kiosk.social

import com.swonal.kiosk.util.CryptoCompat
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.math.BigInteger
import java.security.MessageDigest

@Serializable
data class NostrSignedEvent(
    val id: String,
    val pubkey: String,
    val created_at: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
)

/**
 * Pure-Kotlin NIP-01 / BIP-340 Schnorr secp256k1 + NIP-19 Bech32 cryptography.
 * Compatible with Android 6.0 (API 23) AND host JVM JUnit 5 tests with zero JNI dependency.
 */
object NostrCrypto {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val G_X = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val G_Y = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val THREE = BigInteger.valueOf(3)
    private val SEVEN = BigInteger.valueOf(7)
    private val SQRT_EXP = P.add(BigInteger.ONE).shiftRight(2) // (P + 1) / 4 since P % 4 == 3

    private data class Point(val x: BigInteger, val y: BigInteger)

    private val G = Point(G_X, G_Y)

    fun generateSecretKeyHex(): String {
        while (true) {
            val bytes = CryptoCompat.randomBytes(32)
            val d = BigInteger(1, bytes)
            if (d > BigInteger.ZERO && d < N) {
                return bytes.toHex()
            }
        }
    }

    fun getPublicKeyHex(privKeyHex: String): String {
        val sk = hexToBytes(privKeyHex)
        require(sk.size == 32) { "Private key must be 32 bytes" }
        val d = BigInteger(1, sk)
        require(d > BigInteger.ZERO && d < N) { "Private key out of curve order" }
        val p = pointMul(G, d) ?: error("Invalid curve point")
        return bigIntTo32Bytes(p.x).toHex()
    }

    fun signEvent(
        privKeyHex: String,
        kind: Int,
        tags: List<List<String>>,
        content: String,
        createdAtSec: Long = System.currentTimeMillis() / 1000L
    ): NostrSignedEvent {
        val pubkey = getPublicKeyHex(privKeyHex)
        val id = computeEventId(pubkey, createdAtSec, kind, tags, content)
        val sig = schnorrSign(hexToBytes(id), hexToBytes(privKeyHex), CryptoCompat.randomBytes(32))
        return NostrSignedEvent(
            id = id,
            pubkey = pubkey,
            created_at = createdAtSec,
            kind = kind,
            tags = tags,
            content = content,
            sig = sig.toHex()
        )
    }

    fun verifyEvent(ev: NostrSignedEvent): Boolean {
        if (!HEX_64.matches(ev.id) || !HEX_64.matches(ev.pubkey) || !HEX_128.matches(ev.sig)) {
            return false
        }
        val expectedId = computeEventId(ev.pubkey, ev.created_at, ev.kind, ev.tags, ev.content)
        if (!ev.id.equals(expectedId, ignoreCase = true)) {
            return false
        }
        return runCatching {
            schnorrVerify(hexToBytes(ev.id), hexToBytes(ev.pubkey), hexToBytes(ev.sig))
        }.getOrDefault(false)
    }

    fun computeEventId(
        pubkeyHex: String,
        createdAtSec: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): String {
        val arr = JsonArray(
            listOf(
                JsonPrimitive(0),
                JsonPrimitive(pubkeyHex.lowercase()),
                JsonPrimitive(createdAtSec),
                JsonPrimitive(kind),
                buildJsonArray {
                    for (tag in tags) {
                        add(buildJsonArray {
                            for (cell in tag) add(JsonPrimitive(cell))
                        })
                    }
                },
                JsonPrimitive(content)
            )
        )
        val serialized = Json.encodeToString(JsonArray.serializer(), arr)
        return CryptoCompat.sha256Hex(serialized.toByteArray(Charsets.UTF_8))
    }

    fun schnorrSign(msg32: ByteArray, secKey32: ByteArray, auxRand32: ByteArray): ByteArray {
        require(msg32.size == 32 && secKey32.size == 32 && auxRand32.size == 32)
        val d0 = BigInteger(1, secKey32)
        require(d0 > BigInteger.ZERO && d0 < N) { "Invalid secret key" }
        val p = pointMul(G, d0) ?: error("Point at infinity")
        val d = if (p.y.testBit(0)) N.subtract(d0) else d0
        val pBytes = bigIntTo32Bytes(p.x)

        val auxHash = taggedHash("BIP0340/aux", auxRand32)
        val dBytes = bigIntTo32Bytes(d)
        val t = ByteArray(32) { i -> (dBytes[i].toInt() xor auxHash[i].toInt()).toByte() }
        val rand = taggedHash("BIP0340/nonce", t + pBytes + msg32)
        val k0 = BigInteger(1, rand).mod(N)
        require(k0 != BigInteger.ZERO) { "Failure generating nonce" }

        val rPoint = pointMul(G, k0) ?: error("R at infinity")
        val k = if (rPoint.y.testBit(0)) N.subtract(k0) else k0
        val rBytes = bigIntTo32Bytes(rPoint.x)

        val eHash = taggedHash("BIP0340/challenge", rBytes + pBytes + msg32)
        val e = BigInteger(1, eHash).mod(N)
        val s = k.add(e.multiply(d)).mod(N)
        return rBytes + bigIntTo32Bytes(s)
    }

    fun schnorrVerify(msg32: ByteArray, pubKey32: ByteArray, sig64: ByteArray): Boolean {
        if (msg32.size != 32 || pubKey32.size != 32 || sig64.size != 64) return false
        val pPoint = liftX(BigInteger(1, pubKey32)) ?: return false
        val rBytes = sig64.copyOfRange(0, 32)
        val r = BigInteger(1, rBytes)
        val s = BigInteger(1, sig64.copyOfRange(32, 64))
        if (r >= P || s >= N) return false

        val eHash = taggedHash("BIP0340/challenge", rBytes + pubKey32 + msg32)
        val e = BigInteger(1, eHash).mod(N)

        val negE = if (e == BigInteger.ZERO) BigInteger.ZERO else N.subtract(e)
        val sG = pointMul(G, s)
        val eP = pointMul(pPoint, negE)
        val rPoint = pointAdd(sG, eP) ?: return false
        if (rPoint.y.testBit(0)) return false
        return rPoint.x == r
    }

    // --- NIP-19 Bech32 (npub1 / nsec1) ---

    private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    fun npubEncode(pubkeyHex: String): String = bech32Encode("npub", hexToBytes(pubkeyHex))

    fun nsecEncode(privkeyHex: String): String = bech32Encode("nsec", hexToBytes(privkeyHex))

    fun normalizePubkey(input: String): Pair<String, String> {
        val trimmed = input.trim()
        return if (trimmed.startsWith("npub1", ignoreCase = true)) {
            val (hrp, data) = bech32Decode(trimmed.lowercase())
            require(hrp == "npub" && data.size == 32) { "Invalid npub format" }
            val hex = data.toHex()
            hex to trimmed.lowercase()
        } else if (HEX_64.matches(trimmed)) {
            val hex = trimmed.lowercase()
            hex to npubEncode(hex)
        } else {
            throw IllegalArgumentException("Invalid key. Expected 64-character hex or npub1... format")
        }
    }

    fun bech32Encode(hrp: String, data8: ByteArray): String {
        val fiveBit = convertBits(data8, 8, 5, true)
        val checksum = createChecksum(hrp, fiveBit)
        val combined = fiveBit + checksum
        val sb = StringBuilder(hrp.length + 1 + combined.size)
        sb.append(hrp).append('1')
        for (v in combined) sb.append(BECH32_CHARSET[v])
        return sb.toString()
    }

    fun bech32Decode(bech: String): Pair<String, ByteArray> {
        val pos = bech.lastIndexOf('1')
        require(pos in 1 until bech.length - 6) { "Invalid bech32 string" }
        val hrp = bech.substring(0, pos)
        val dataPart = bech.substring(pos + 1)
        val values = IntArray(dataPart.length) { i ->
            val idx = BECH32_CHARSET.indexOf(dataPart[i])
            require(idx >= 0) { "Invalid bech32 character" }
            idx
        }
        require(verifyChecksum(hrp, values)) { "Invalid bech32 checksum" }
        val payload = values.copyOfRange(0, values.size - 6)
        return hrp to convertBits(payload, 5, 8, false)
    }

    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray =
        convertBits(IntArray(data.size) { data[it].toInt() and 0xFF }, fromBits, toBits, pad).let { outBytes ->
            if (toBits == 5) IntArray(outBytes.size) { outBytes[it].toInt() and 0xFF } else error("unused")
        }

    @JvmName("convertBitsInt")
    private fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val out = mutableListOf<Byte>()
        val maxv = (1 shl toBits) - 1
        for (value in data) {
            require(value >= 0 && (value ushr fromBits) == 0) { "Invalid bit group" }
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out += ((acc ushr bits) and maxv).toByte()
            }
        }
        if (pad) {
            if (bits > 0) out += ((acc shl (toBits - bits)) and maxv).toByte()
        } else {
            require(bits < fromBits && ((acc shl (toBits - bits)) and maxv) == 0) { "Invalid padding" }
        }
        return out.toByteArray()
    }

    private fun polymod(values: IntArray): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0..4) {
                if (((top ushr i) and 1) != 0) chk = chk xor gen[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val ret = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            val c = hrp[i].code
            ret[i] = c ushr 5
            ret[i + hrp.length + 1] = c and 31
        }
        return ret
    }

    private fun createChecksum(hrp: String, data: IntArray): IntArray {
        val values = hrpExpand(hrp) + data + IntArray(6)
        val mod = polymod(values) xor 1
        return IntArray(6) { i -> (mod ushr (5 * (5 - i))) and 31 }
    }

    private fun verifyChecksum(hrp: String, data: IntArray): Boolean =
        polymod(hrpExpand(hrp) + data) == 1

    // --- Curve Math & Tagged Hashes ---

    private fun taggedHash(tag: String, msg: ByteArray): ByteArray {
        val tagHash = MessageDigest.getInstance("SHA-256").digest(tag.toByteArray(Charsets.UTF_8))
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(tagHash)
        digest.update(tagHash)
        digest.update(msg)
        return digest.digest()
    }

    private fun liftX(x: BigInteger): Point? {
        if (x < BigInteger.ZERO || x >= P) return null
        val ySq = x.modPow(THREE, P).add(SEVEN).mod(P)
        val y = ySq.modPow(SQRT_EXP, P)
        if (y.multiply(y).mod(P) != ySq) return null
        val evenY = if (y.testBit(0)) P.subtract(y) else y
        return Point(x, evenY)
    }

    private fun pointAdd(p1: Point?, p2: Point?): Point? {
        if (p1 == null) return p2
        if (p2 == null) return p1
        if (p1.x == p2.x) {
            if (p1.y != p2.y) return null
            if (p1.y == BigInteger.ZERO) return null
            val lam = p1.x.multiply(p1.x).multiply(THREE)
                .multiply(p1.y.shiftLeft(1).modInverse(P)).mod(P)
            val x3 = lam.multiply(lam).subtract(p1.x.shiftLeft(1)).mod(P)
            val y3 = lam.multiply(p1.x.subtract(x3)).subtract(p1.y).mod(P)
            return Point(x3, y3)
        }
        val dx = p2.x.subtract(p1.x).mod(P)
        val dy = p2.y.subtract(p1.y).mod(P)
        val lam = dy.multiply(dx.modInverse(P)).mod(P)
        val x3 = lam.multiply(lam).subtract(p1.x).subtract(p2.x).mod(P)
        val y3 = lam.multiply(p1.x.subtract(x3)).subtract(p1.y).mod(P)
        return Point(x3, y3)
    }

    private fun pointMul(p: Point, k: BigInteger): Point? {
        if (k == BigInteger.ZERO) return null
        var r: Point? = null
        var q: Point? = p
        val bitLen = k.bitLength()
        for (i in 0 until bitLen) {
            if (k.testBit(i)) {
                r = pointAdd(r, q)
            }
            q = pointAdd(q, q)
        }
        return r
    }

    private fun bigIntTo32Bytes(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Odd hex length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private val HEX_64 = Regex("^[0-9a-fA-F]{64}$")
    private val HEX_128 = Regex("^[0-9a-fA-F]{128}$")
}
