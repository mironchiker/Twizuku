package moe.shizuku.manager.ladb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AdbPairing(
    private val host: String = "127.0.0.1",
    private val port: Int,
    private val timeoutMs: Int = 10_000
) {

    @Throws(Exception::class)
    fun pair(code: String): Boolean {
        require(code.length == 6) { "Код должен быть из 6 цифр" }

        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = timeoutMs

        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())

        try {
            val keyGen = KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(2048)
            val keyPair = keyGen.generateKeyPair()

            val codeHash = MessageDigest.getInstance("SHA-256").digest(code.toByteArray())
            val encrypted = encryptPeerInfo(keyPair.public.encoded, "twizuku@android", codeHash)

            output.writeInt(encrypted.size)
            output.write(encrypted)
            output.flush()

            return input.readInt() == 0
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun encryptPeerInfo(publicKey: ByteArray, name: String, secret: ByteArray): ByteArray {
        val key = SecretKeySpec(secret.copyOf(32), "AES")
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(ByteArray(16)))
        return cipher.doFinal(buildPeerInfoPayload(publicKey, name))
    }

    private fun buildPeerInfoPayload(publicKey: ByteArray, name: String): ByteArray {
        val nameBytes = name.toByteArray()
        val buffer = ByteArray(4 + publicKey.size + 4 + nameBytes.size)
        var offset = 0

        buffer[offset++] = (publicKey.size ushr 24).toByte()
        buffer[offset++] = (publicKey.size ushr 16).toByte()
        buffer[offset++] = (publicKey.size ushr 8).toByte()
        buffer[offset++] = publicKey.size.toByte()
        System.arraycopy(publicKey, 0, buffer, offset, publicKey.size)
        offset += publicKey.size

        buffer[offset++] = (nameBytes.size ushr 24).toByte()
        buffer[offset++] = (nameBytes.size ushr 16).toByte()
        buffer[offset++] = (nameBytes.size ushr 8).toByte()
        buffer[offset++] = nameBytes.size.toByte()
        System.arraycopy(nameBytes, 0, buffer, offset, nameBytes.size)

        return buffer
    }
}
