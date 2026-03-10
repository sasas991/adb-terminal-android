package com.example.adb_terminal_android

import android.util.Base64
import java.io.DataInputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.zip.CRC32

class AdbClient {

    companion object {
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
    private val publicKey: RSAPublicKey

    private var socket: Socket? = null
    private var inp: DataInputStream? = null
    private var out: OutputStream? = null
    private var localIdCounter = 1

    init {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()
        privateKey = kp.private
        publicKey  = kp.public as RSAPublicKey
    }

    // ── Public API ────────────────────────────────────────────────────────────

    @Throws(Exception::class)
    fun connect(host: String, port: Int) {
        val s = Socket(host, port)
        s.soTimeout = 60_000
        socket = s
        inp = DataInputStream(s.getInputStream())
        out = s.getOutputStream()

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
                        // key not recognized — send public key, user must tap "Allow" on device
                        sendMessage(CMD_AUTH, AUTH_RSAPUBLICKEY, 0, encodePublicKey())
                    }
                }

                else -> throw Exception("Unexpected message during handshake: 0x${msg.command.toString(16)}")
            }
        }
    }

    @Throws(Exception::class)
    fun shell(command: String): String {
        val s = socket ?: throw Exception("Not connected")
        s.soTimeout = 30_000

        val localId = localIdCounter++
        sendMessage(CMD_OPEN, localId, 0, "shell:$command\u0000".toByteArray())

        // Wait for device to acknowledge the stream
        var remoteId = 0
        loop@ while (true) {
            val msg = readMessage()
            when (msg.command) {
                CMD_OKAY -> { remoteId = msg.arg0; break@loop }
                CMD_CLSE -> throw Exception("Device closed stream before sending data")
                else -> {}
            }
        }

        // Collect output until CLSE
        val sb = StringBuilder()
        while (true) {
            val msg = readMessage()
            when (msg.command) {
                CMD_WRTE -> {
                    sb.append(String(msg.data, Charsets.UTF_8))
                    sendMessage(CMD_OKAY, localId, remoteId, ByteArray(0))
                }
                CMD_CLSE -> {
                    sendMessage(CMD_CLSE, localId, remoteId, ByteArray(0))
                    break
                }
                CMD_OKAY -> {}
                else -> break
            }
        }

        return sb.toString().trimEnd().ifEmpty { "(нет вывода)" }
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; inp = null; out = null
    }

    fun isConnected() = socket?.isConnected == true && socket?.isClosed == false

    // ── ADB wire protocol ────────────────────────────────────────────────────

    private data class Msg(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun readMessage(): Msg {
        val i = inp ?: throw Exception("Not connected")
        fun le() = Integer.reverseBytes(i.readInt())   // big-endian → little-endian

        val cmd    = le()
        val arg0   = le()
        val arg1   = le()
        val length = le()
        le()    // crc32  (ignored)
        le()    // magic  (ignored)

        val data = ByteArray(length)
        if (length > 0) i.readFully(data)
        return Msg(cmd, arg0, arg1, data)
    }

    private fun sendMessage(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val o = out ?: throw Exception("Not connected")
        val crc   = crc32(data)
        val magic = command.xor(-1)

        val buf = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command); buf.putInt(arg0); buf.putInt(arg1)
        buf.putInt(data.size); buf.putInt(crc); buf.putInt(magic)
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
     * Encode the RSA public key in ADB's binary format, then Base64 + " user@host\0".
     *
     * ADB key struct (little-endian):
     *   uint32  len        – key length in 32-bit words (64 for 2048-bit)
     *   uint32  n0inv      – -n^{-1} mod 2^32
     *   uint32  n[64]      – modulus, LE 32-bit words
     *   uint32  rr[64]     – (2^2048)^2 mod n, LE 32-bit words
     *   uint32  exponent   – e (65537)
     */
    private fun encodePublicKey(): ByteArray {
        val keyLen = 64  // 2048-bit / 32 = 64 words
        val n = publicKey.modulus
        val e = publicKey.publicExponent.toInt()

        val pow32   = BigInteger.ONE.shiftLeft(32)
        val n0inv   = n.modInverse(pow32).negate().mod(pow32).toInt()

        val r  = BigInteger.ONE.shiftLeft(32 * keyLen)
        val rr = r.multiply(r).mod(n)

        fun leWords(v: BigInteger): IntArray {
            val words = IntArray(keyLen)
            var rem   = v
            val mask  = BigInteger.valueOf(0xFFFFFFFFL)
            for (i in 0 until keyLen) { words[i] = rem.and(mask).toInt(); rem = rem.shiftRight(32) }
            return words
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