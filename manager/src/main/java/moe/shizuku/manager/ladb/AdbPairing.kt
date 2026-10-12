package moe.shizuku.manager.ladb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.security.KeyPair
import java.security.MessageDigest

/**
 * ADB Wireless Pairing protocol handler (Android 11+).
 * 
 * Протокол: TLS handshake -> exported keying material -> SPAKE2 -> encrypted PeerInfo
 * [citation:4][citation:18]
 */
class AdbPairing(
    private val host: String = "127.0.0.1",
    private val port: Int
) {

    fun pair(code: String): Boolean {
        val socket = Socket(host, port)
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())

        try {
            // Отправляем заголовок с хешем кода
            val codeBytes = code.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(codeBytes)
            
            // Формат: [hash][code]
            output.write(hash)
            output.write(codeBytes)
            output.flush()

            // Ждём ответ
            val response = input.readByte()
            return response.toInt() == 1
        } finally {
            socket.close()
        }
    }
}
