package com.example.adb_terminal_android

import java.io.DataInputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ADB Wireless Pairing (Android 11+).
 *
 * Implements SPAKE2 over TCP to register our RSA public key with adbd.
 * After pairing, [AdbClient] connects without showing the "Allow debugging?" dialog.
 *
 * Packet wire format: version(1) | type(1) | payload_len(2 BE) | payload
 *   type 0 = SPAKE2 message (compressed P-256 point, 33 bytes)
 *   type 1 = PeerInfo        (AES-256-GCM encrypted: type_byte + adb_key_bytes)
 */
class AdbPairingClient(private val adbKeyBytes: ByteArray) {

    // ── P-256 parameters ──────────────────────────────────────────────────────

    private val P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val A = P - BigInteger.valueOf(3)
    private val B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
    private val N = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

    private val G = ECPt(
        BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16),
        BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16)
    )

    // SPAKE2 blinding points for P-256 (RFC 9382)
    //   M = compressed 02 886e2f97...  (even y)
    //   N = compressed 03 d8bbd6c6...  (odd  y)
    private val M_PT = decompressXY(BigInteger("886E2F97ACE46E55BA9DD7242579F2993B64E16EF3DCAB95AFD497333D8FA12", 16), even = true)
    private val N_PT = decompressXY(BigInteger("D8BBD6C639C62937B04D997F38C3770719C629D7014D49A24B4F98BAA1292B49", 16), even = false)

    // ── EC arithmetic ─────────────────────────────────────────────────────────

    private data class ECPt(val x: BigInteger, val y: BigInteger) {
        companion object { val INF = ECPt(BigInteger.ZERO, BigInteger.ZERO) }
        val isInf get() = x.signum() == 0 && y.signum() == 0
    }

    private fun ECPt.neg() = ECPt(x, P - y)
    private fun f(v: BigInteger) = v.mod(P)

    private fun ecAdd(p1: ECPt, p2: ECPt): ECPt {
        if (p1.isInf) return p2
        if (p2.isInf) return p1
        if (p1.x == p2.x) return if (p1.y != p2.y) ECPt.INF else ecDbl(p1)
        val lam = f((p2.y - p1.y) * (p2.x - p1.x).modInverse(P))
        val x3  = f(lam * lam - p1.x - p2.x)
        return ECPt(x3, f(lam * (p1.x - x3) - p1.y))
    }

    private fun ecDbl(pt: ECPt): ECPt {
        if (pt.isInf) return pt
        val lam = f((pt.x * pt.x * BigInteger.valueOf(3) + A) * (pt.y * BigInteger.valueOf(2)).modInverse(P))
        val x3  = f(lam * lam - pt.x * BigInteger.valueOf(2))
        return ECPt(x3, f(lam * (pt.x - x3) - pt.y))
    }

    private fun ecMul(k: BigInteger, pt: ECPt): ECPt {
        var R = ECPt.INF; var Q = pt; var s = k.mod(N)
        while (s.signum() > 0) {
            if (s.testBit(0)) R = ecAdd(R, Q)
            Q = ecDbl(Q); s = s.shiftRight(1)
        }
        return R
    }

    // ── Point encoding ────────────────────────────────────────────────────────

    private fun decompressXY(x: BigInteger, even: Boolean): ECPt {
        val x3 = x.modPow(BigInteger.valueOf(3), P)
        val y2 = x3.add(A.multiply(x)).add(B).mod(P)
        var y  = y2.modPow((P + BigInteger.ONE).shiftRight(2), P)   // P ≡ 3 mod 4
        if (y.testBit(0) == even) y = P - y   // even → LSB 0; odd → LSB 1
        return ECPt(x, y)
    }

    private fun compress(pt: ECPt): ByteArray {
        val prefix = if (pt.y.testBit(0)) 0x03.toByte() else 0x02.toByte()
        return byteArrayOf(prefix) + to32(pt.x)
    }

    private fun decompress(b: ByteArray): ECPt {
        require(b.size == 33) { "Expected 33-byte compressed point" }
        return decompressXY(BigInteger(1, b.copyOfRange(1, 33)), even = b[0] == 0x02.toByte())
    }

    private fun to32(n: BigInteger): ByteArray {
        val raw = n.toByteArray()
        return when {
            raw.size == 33 && raw[0] == 0.toByte() -> raw.copyOfRange(1, 33)
            raw.size == 32 -> raw
            raw.size < 32  -> ByteArray(32 - raw.size) + raw
            else           -> raw.copyOfRange(raw.size - 32, raw.size)
        }
    }

    // ── Crypto helpers ────────────────────────────────────────────────────────

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    private fun hmac256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-SHA256 (RFC 5869). */
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk    = hmac256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val result = mutableListOf<Byte>()
        var prev   = byteArrayOf()
        var i      = 1
        while (result.size < len) {
            prev = hmac256(prk, prev + info + byteArrayOf(i.toByte()))
            result += prev.toList()
            i++
        }
        return result.take(len).toByteArray()
    }

    private fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return c.doFinal(plain)
    }

    private fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, cipher: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return c.doFinal(cipher)
    }

    // ── Packet I/O ───────────────────────────────────────────────────────────

    private fun writePacket(out: OutputStream, type: Int, payload: ByteArray) {
        out.write(byteArrayOf(
            1,                              // version
            type.toByte(),
            (payload.size ushr 8).toByte(),
            payload.size.toByte()
        ))
        out.write(payload)
        out.flush()
    }

    private fun readPacket(inp: DataInputStream): Pair<Int, ByteArray> {
        inp.readByte()                         // version (ignored)
        val type    = inp.readByte().toInt() and 0xFF
        val len     = inp.readUnsignedShort()
        val payload = ByteArray(len)
        inp.readFully(payload)
        return type to payload
    }

    // ── Pairing entry point ───────────────────────────────────────────────────

    /**
     * Runs the pairing handshake.
     * @param host  IP shown in "Wireless debugging" settings
     * @param port  Port shown in "Pair device with pairing code" dialog
     * @param code  6-digit pairing code shown on device
     * @throws Exception on any protocol or authentication error
     */
    fun pair(host: String, port: Int, code: String): String {
        Socket(host, port).use { sock ->
            sock.soTimeout = 30_000
            val out = sock.getOutputStream()
            val inp = DataInputStream(sock.getInputStream())

            // 1. Password → scalar w = SHA256(code) mod N
            val w = BigInteger(1, sha256(code.toByteArray(Charsets.UTF_8))).mod(N)

            // 2. Random ephemeral scalar x ∈ [1, N-1]
            var x: BigInteger
            do { x = BigInteger(N.bitLength(), SecureRandom()) } while (x.signum() == 0 || x >= N)

            // 3. Our SPAKE2 public value: X = x·G + w·M
            val X = ecAdd(ecMul(x, G), ecMul(w, M_PT))
            writePacket(out, 0, compress(X))

            // 4. Server's SPAKE2 public value: Y = y·G + w·N
            val (typeY, payloadY) = readPacket(inp)
            check(typeY == 0) { "Expected SPAKE2 message (type 0), got type $typeY" }
            val Y = decompress(payloadY)

            // 5. Shared EC secret: K = x · (Y − w·N)
            val K = ecMul(x, ecAdd(Y, ecMul(w, N_PT).neg()))

            // 6. Derive session keys via HKDF
            //    IKM  = K.x (the x-coordinate of the shared point)
            //    salt = SHA256(code)
            //    info = compress(X) || compress(Y) || to32(K.x)
            val Kx = to32(K.x)
            val info = compress(X) + payloadY + Kx
            val keyMat = hkdf(
                ikm  = Kx,
                salt = sha256(code.toByteArray(Charsets.UTF_8)),
                info = info,
                len  = 44
            )
            val aesKey = keyMat.copyOf(32)
            val iv     = keyMat.copyOfRange(32, 44)

            // 7. Send our PeerInfo (type 1): type_byte(0) + adb_public_key_bytes
            val peerInfo = byteArrayOf(0) + adbKeyBytes
            writePacket(out, 1, aesGcmEncrypt(aesKey, iv, peerInfo))

            // 8. Receive and verify server PeerInfo
            val (typePI, encPI) = readPacket(inp)
            check(typePI == 1) { "Expected PeerInfo (type 1), got type $typePI" }
            aesGcmDecrypt(aesKey, iv, encPI)   // throws AEADBadTagException if keys don't match

            return "Paired successfully."
        }
    }
}
