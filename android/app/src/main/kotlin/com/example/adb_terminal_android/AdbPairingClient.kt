package com.example.adb_terminal_android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.conscrypt.Conscrypt
import java.io.DataInputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.*
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.*

/**
 * ADB Wireless Pairing (Android 11+).
 *
 * Protocol:
 *   1. TCP connect to pairing port (shown on device in Wireless debugging → Pair with code)
 *   2. TLS 1.3 handshake (self-signed cert; server cert is accepted unconditionally)
 *   3. Export 64 bytes of TLS key material (label = "adb-label\0", no context)
 *   4. SPAKE2 password = pairing_code_utf8 + exported_key_material
 *   5. SPAKE2 exchange (P-256), derive AES-256-GCM key via HKDF
 *   6. Exchange PeerInfo packets encrypted with that key
 *
 * Packet wire format (after TLS): version(1) | type(1) | payload_len(2 BE) | payload
 *   type 0 = SPAKE2 message (33-byte compressed P-256 point)
 *   type 1 = PeerInfo        (encrypted: type_byte(1) + adb_key_bytes)
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

    // SPAKE2 blinding points for P-256 (RFC 9382 / AOSP)
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
        val y2 = x.modPow(BigInteger.valueOf(3), P).add(A.multiply(x)).add(B).mod(P)
        var y  = y2.modPow((P + BigInteger.ONE).shiftRight(2), P)
        if (y.testBit(0) == even) y = P - y
        return ECPt(x, y)
    }

    private fun compress(pt: ECPt): ByteArray =
        byteArrayOf(if (pt.y.testBit(0)) 0x03.toByte() else 0x02.toByte()) + to32(pt.x)

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

    private fun hmac256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

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
        out.write(byteArrayOf(1, type.toByte(), (payload.size ushr 8).toByte(), payload.size.toByte()))
        out.write(payload)
        out.flush()
    }

    private fun readPacket(inp: DataInputStream): Pair<Int, ByteArray> {
        inp.readByte() // version
        val type    = inp.readByte().toInt() and 0xFF
        val payload = ByteArray(inp.readUnsignedShort())
        inp.readFully(payload)
        return type to payload
    }

    // ── TLS setup ────────────────────────────────────────────────────────────

    private fun buildSslContext(): SSLContext {
        if (Security.getProvider("Conscrypt") == null) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }

        // Android KeyStore generates the key AND a self-signed certificate automatically
        val alias = "adb_pairing_tls"
        val ks    = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        if (!ks.containsAlias(alias)) {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                .also { it.initialize(spec) }.generateKeyPair()
        }

        // KeyManagerFactory that works with AndroidKeyStore
        val kmf = KeyManagerFactory.getInstance("AndroidKeyStore")
        kmf.init(null) // uses all AndroidKeyStore entries

        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val ctx = SSLContext.getInstance("TLS", "Conscrypt")
        ctx.init(kmf.keyManagers, trustAll, SecureRandom())
        return ctx
    }

    // ── Pairing entry point ───────────────────────────────────────────────────

    fun pair(host: String, port: Int, code: String): String {
        val sslCtx    = buildSslContext()
        val rawSocket = Socket(host, port)
        rawSocket.soTimeout = 30_000

        val sslSock = (sslCtx.socketFactory.createSocket(rawSocket, host, port, true) as SSLSocket).also {
            it.useClientMode = true
            it.startHandshake()
        }

        sslSock.use {
            val out = it.outputStream
            val inp = DataInputStream(it.inputStream)

            // Export 64 bytes of TLS key material (label includes null terminator)
            val exportedKey = Conscrypt.exportKeyingMaterial(it, "adb-label\u0000", null, 64)

            // SPAKE2 password = code_utf8 + exported_key_material
            val password = code.toByteArray(Charsets.UTF_8) + exportedKey

            // w = password interpreted as big-endian integer mod N
            val w = BigInteger(1, MessageDigest.getInstance("SHA-256").digest(password)).mod(N)

            // Ephemeral scalar x
            var x: BigInteger
            do { x = BigInteger(N.bitLength(), SecureRandom()) } while (x.signum() == 0 || x >= N)

            // X = x·G + w·M  (our SPAKE2 public value)
            val X = ecAdd(ecMul(x, G), ecMul(w, M_PT))
            writePacket(out, 0, compress(X))

            // Receive server's Y = y·G + w·N
            val (typeY, payloadY) = readPacket(inp)
            check(typeY == 0) { "Expected SPAKE2 message (type 0), got $typeY" }
            val Y = decompress(payloadY)

            // Shared secret K = x · (Y − w·N_PT)
            val K  = ecMul(x, ecAdd(Y, ecMul(w, N_PT).neg()))
            val Kx = to32(K.x)

            // Derive session key: HKDF(ikm=Kx, salt="", info=label, len=44)
            val info   = "adb pairing_auth aes-256-gcm key".toByteArray(Charsets.UTF_8)
            val keyMat = hkdf(ikm = Kx, salt = byteArrayOf(), info = info, len = 44)
            val aesKey = keyMat.copyOf(32)
            val iv     = keyMat.copyOfRange(32, 44)

            // Send our PeerInfo: type 0 (ADB public key) + key bytes
            writePacket(out, 1, aesGcmEncrypt(aesKey, iv, byteArrayOf(0) + adbKeyBytes))

            // Receive and verify server PeerInfo
            val (typePI, encPI) = readPacket(inp)
            check(typePI == 1) { "Expected PeerInfo (type 1), got $typePI" }
            aesGcmDecrypt(aesKey, iv, encPI) // throws AEADBadTagException if wrong

            return "Paired successfully."
        }
    }
}
