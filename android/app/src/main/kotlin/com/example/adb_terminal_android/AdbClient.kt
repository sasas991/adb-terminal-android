package com.example.adb_terminal_android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.DataInputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.zip.CRC32

class AdbClient {

    companion object {
        private const val KEY_ALIAS = "adb_terminal_rsa"

        private const val CMD_CNXN = 0x4E584E43
        private const val CMD_AUTH = 0x48545541
        private const val CMD_OPEN = 0x4E45504F
        private const val CMD_OKAY = 0x59414B4F
        private const val CMD_CLSE = 0x45534C43
        private const val CMD_WRTE = 0x45545257

        private const val AUTH_TOKEN        = 1
        private const val AUTH_SIGNATURE    = 2
        private const val AUTH_RSAPUBLICKEY = 3

        private const val ADB_VERSION = 0x01000000
        private const val MAX_DATA    = 256 * 1024
    }

    private val privateKey: PrivateKey
    private val rsaPublicKey: RSAPublicKey

    private var socket: Socket? = null
    private var inp: DataInputStream? = null
    private var out: OutputStream? = null
    private var localIdCounter = 1

    init {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
                .also { it.initialize(spec) }
                .generateKeyPair()
        }
        privateKey   = ks.getKey(KEY_ALIAS, null) as PrivateKey
        rsaPublicKey = ks.getCertificate(KEY_ALIAS).publicKey as RSAPublicKey
    }

    // ── Public API ────────────────────────────────────────────────────────────

    @Throws(Exception::class)
    fun connect(host: String, port: Int) {
        val s = Socket(host, port)
        s.soTimeout = 60_000
        socket = s
        inp = DataInputStream(s.getInputStream())
        out = s.getOutputStream()
        localIdCounter = 1

        sendMessage(CMD_CNXN, ADB_VERSION, MAX_DATA, "host::".toByteArray())

        var triedSignature = false
        while (true) {
            val msg = readMessage()
            when (msg.command) {
                CMD_CNXN -> return  // handshake complete

                CMD_AUTH -> {
                    if (msg.arg0 != AUTH_TOKEN) continue
                    if (!triedSignature) {
                        sendMessage(CMD_AUTH, AUTH_SIGNATURE, 0, signToken(msg.data))
                        triedSignature = true
                    } else {
                        // Key not recognized — send public key, user must tap "Allow" on device
                        sendMessage(CMD_AUTH, AUTH_RSAPUBLICKEY, 0, encodePublicKey())
                    }
                }

                // Some devices echo our CNXN before sending AUTH — safe to ignore
                else -> {}
            }
        }
    }

    @Throws(Exception::class)
    fun shell(command: String): String {
        socket?.soTimeout = 30_000

        val localId = localIdCounter++
        sendMessage(CMD_OPEN, localId, 0, "shell:$command\u0000".toByteArray(Charsets.UTF_8))

        val sb     = StringBuilder()
        var remoteId  = 0
        var streamOpen = false   // true once we've received OKAY or first WRTE

        while (true) {
            val msg = readMessage()

            // Helper: does this message belong to our stream?
            // Before OKAY we match by arg1 (our localId echoed back by device).
            // After OKAY we also accept by remoteId.
            fun isOurs(): Boolean =
                msg.arg1 == localId || (remoteId != 0 && msg.arg0 == remoteId)

            when (msg.command) {
                CMD_OKAY -> {
                    if (isOurs()) {
                        remoteId   = msg.arg0
                        streamOpen = true
                    }
                    // else: stale OKAY from another stream — ignore
                }

                CMD_WRTE -> {
                    if (isOurs()) {
                        if (!streamOpen) {
                            // Device skipped OKAY (non-standard but seen in the wild)
                            remoteId   = msg.arg0
                            streamOpen = true
                        }
                        sb.append(String(msg.data, Charsets.UTF_8))
                        sendMessage(CMD_OKAY, localId, remoteId, ByteArray(0))
                    }
                }

                CMD_CLSE -> {
                    if (isOurs()) {
                        if (streamOpen) {
                            // Normal close — send our CLSE and finish
                            sendMessage(CMD_CLSE, localId, remoteId, ByteArray(0))
                        } else {
                            // Stream rejected before it opened (service unavailable / SELinux)
                            throw Exception("Service 'shell' rejected by device (CLSE before OKAY)")
                        }
                        break
                    }
                    // else: stale CLSE from another stream — ignore
                }

                // Ignore all other message types
                else -> {}
            }
        }

        return sb.toString().trimEnd().ifEmpty { "(no output)" }
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; inp = null; out = null
    }

    fun isConnected() = socket?.isConnected == true && socket?.isClosed == false

    fun adbPublicKeyBytes(): ByteArray = encodePublicKey()

    // ── ADB wire protocol ────────────────────────────────────────────────────

    private data class Msg(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun readMessage(): Msg {
        val i = inp ?: throw Exception("Not connected")
        fun le() = Integer.reverseBytes(i.readInt())

        val cmd    = le()
        val arg0   = le()
        val arg1   = le()
        val length = le()
        le()    // crc32  (ignored)
        le()    // magic  (ignored)

        val data = ByteArray(length.coerceIn(0, MAX_DATA))
        if (data.isNotEmpty()) i.readFully(data)
        return Msg(cmd, arg0, arg1, data)
    }

    private fun sendMessage(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val o = out ?: throw Exception("Not connected")
        val buf = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command); buf.putInt(arg0);  buf.putInt(arg1)
        buf.putInt(data.size); buf.putInt(crc32(data)); buf.putInt(command.xor(-1))
        buf.put(data)
        o.write(buf.array())
        o.flush()
    }

    // ── Crypto ───────────────────────────────────────────────────────────────

    private fun signToken(token: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA1withRSA")
        sig.initSign(privateKey)
        sig.update(token)
        return sig.sign()
    }

    /**
     * ADB RSA public key binary format (little-endian):
     *   uint32  len        = 64  (2048 / 32)
     *   uint32  n0inv      = -(n^{-1}) mod 2^32
     *   uint32  n[64]      = modulus in LE 32-bit words
     *   uint32  rr[64]     = (2^2048)^2 mod n  in LE 32-bit words
     *   uint32  exponent   = e
     *
     * Then Base64-encoded and " user@android\0" appended.
     */
    private fun encodePublicKey(): ByteArray {
        val keyLen = 64
        val n = rsaPublicKey.modulus
        val e = rsaPublicKey.publicExponent.toInt()

        val pow32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.modInverse(pow32).negate().mod(pow32).toInt()
        val r     = BigInteger.ONE.shiftLeft(32 * keyLen)
        val rr    = r.multiply(r).mod(n)

        fun leWords(v: BigInteger) = IntArray(keyLen).also { words ->
            var rem = v; val mask = BigInteger.valueOf(0xFFFFFFFFL)
            for (i in 0 until keyLen) { words[i] = rem.and(mask).toInt(); rem = rem.shiftRight(32) }
        }

        val buf = ByteBuffer.allocate(4 + 4 + keyLen * 4 + keyLen * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(keyLen); buf.putInt(n0inv)
        leWords(n).forEach  { buf.putInt(it) }
        leWords(rr).forEach { buf.putInt(it) }
        buf.putInt(e)

        val b64 = Base64.encode(buf.array(), Base64.NO_WRAP)
        return b64 + " user@android\u0000".toByteArray(Charsets.US_ASCII)
    }

    private fun crc32(data: ByteArray): Int {
        val c = CRC32(); c.update(data); return c.value.toInt()
    }
}