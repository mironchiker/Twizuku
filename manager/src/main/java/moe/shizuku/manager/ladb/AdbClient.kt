package moe.shizuku.manager.ladb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.security.PrivateKey
import java.security.Signature

/**
 * Реальный ADB-клиент. Говорит на настоящем ADB-протоколе.
 *
 * Требует:
 *  - беспроводная отладка включена на устройстве
 *  - устройство уже сопряжено (см. AdbPairing)
 *  - известен порт adbd (из системного диалога или Settings.Global)
 */
class AdbClient(
    private val host: String = "127.0.0.1",
    private val port: Int,
    private val connectTimeoutMs: Int = 5000
) {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    private var localId = 1
    private var maxPayload = 256 * 1024

    companion object {
        private const val A_VERSION = 0x01000001
        private const val A_MAXDATA = 256 * 1024

        private const val ADB_AUTH_TOKEN = 1
        private const val ADB_AUTH_SIGNATURE = 2
        private const val ADB_AUTH_RSAPUBLICKEY = 3

        // Команды ADB (little-endian ASCII)
        private val A_CNXN = "CNXN".toAdbInt()
        private val A_AUTH = "AUTH".toAdbInt()
        private val A_OPEN = "OPEN".toAdbInt()
        private val A_OKAY = "OKAY".toAdbInt()
        private val A_WRTE = "WRTE".toAdbInt()
        private val A_CLSE = "CLSE".toAdbInt()
    }

    /**
     * Подключение к уже сопряжённому ADB-серверу.
     * @return true если авторизация прошла (CNXN получен)
     */
    @Throws(Exception::class)
    fun connect(keyPair: KeyPair): Boolean {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        s.soTimeout = 5000
        socket = s
        input = DataInputStream(s.getInputStream())
        output = DataOutputStream(s.getOutputStream())

        // 1. Отправляем CNXN
        sendPacket(A_CNXN, A_VERSION, A_MAXDATA, "host::features=shell_v2,cmd\0".toByteArray())

        // 2. Ждём ответ
        val pkt = readPacket()
        return when (pkt.cmd) {
            A_CNXN -> {
                // Уже авторизованы
                if (pkt.arg0 < A_VERSION) {
                    maxPayload = pkt.arg1
                }
                true
            }
            A_AUTH -> {
                handleAuth(pkt, keyPair)
            }
            else -> false
        }
    }

    private fun handleAuth(pkt: Packet, keyPair: KeyPair): Boolean {
        if (pkt.payload.size != 20 || pkt.payload[0].toInt() != ADB_AUTH_TOKEN) {
            return false
        }

        // Подписываем токен
        val signature = signToken(keyPair.private, pkt.payload)
        sendPacket(A_AUTH, ADB_AUTH_SIGNATURE, 0, signature)

        // Читаем ответ: либо CNXN (ок), либо снова AUTH (нужен публичный ключ)
        val resp = readPacket()
        if (resp.cmd == A_CNXN) return true

        if (resp.cmd == A_AUTH) {
            val pubKey = encodePublicKey(keyPair)
            sendPacket(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, pubKey)

            val final = readPacket()
            return final.cmd == A_CNXN
        }
        return false
    }

    /**
     * Выполнить shell-команду и вернуть вывод.
     */
    @Throws(Exception::class)
    fun shell(command: String, timeoutMs: Long = 15_000): String {
        val inp = input ?: throw IllegalStateException("Not connected")
        val streamId = localId++

        sendPacket(A_OPEN, streamId, 0, "shell:$command\0".toByteArray())

        val sb = StringBuilder()
        var remoteStreamId = 0
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val pkt = try {
                readPacket()
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: EOFException) {
                break
            }

            when (pkt.cmd) {
                A_OKAY -> {
                    remoteStreamId = pkt.arg0
                }
                A_WRTE -> {
                    sb.append(String(pkt.payload, Charsets.UTF_8))
                    // Подтверждаем приём
                    sendPacket(A_OKAY, streamId, pkt.arg0, ByteArray(0))
                }
                A_CLSE -> {
                    if (remoteStreamId != 0) {
                        try {
                            sendPacket(A_CLSE, streamId, pkt.arg0, ByteArray(0))
                        } catch (_: Exception) {}
                    }
                    return sb.toString()
                }
                else -> {}
            }
        }
        return sb.toString().ifEmpty { "(таймаут)" }
    }

    fun close() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }

    // ============ ПРОТОКОЛ ============

    private fun sendPacket(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val out = output ?: throw IllegalStateException("Not connected")
        val header = ByteArray(24)
        writeInt32LE(header, 0, cmd)
        writeInt32LE(header, 4, arg0)
        writeInt32LE(header, 8, arg1)
        writeInt32LE(header, 12, payload.size)
        writeInt32LE(header, 16, checksum(payload))
        writeInt32LE(header, 20, cmd xor 0xFFFFFFFF.toInt())
        out.write(header)
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun readPacket(): Packet {
        val inp = input ?: throw IllegalStateException("Not connected")
        val header = ByteArray(24)
        inp.readFully(header)

        val cmd = readInt32LE(header, 0)
        val arg0 = readInt32LE(header, 4)
        val arg1 = readInt32LE(header, 8)
        val len = readInt32LE(header, 12)
        val magic = readInt32LE(header, 20)

        // Проверка magic
        if (magic != (cmd xor 0xFFFFFFFF.toInt())) {
            throw IllegalStateException("Bad packet magic")
        }
        if (len < 0 || len > A_MAXDATA) {
            throw IllegalStateException("Bad payload length: $len")
        }

        val payload = ByteArray(len)
        if (len > 0) inp.readFully(payload)
        return Packet(cmd, arg0, arg1, payload)
    }

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        return sum
    }

    private fun signToken(key: PrivateKey, token: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA1withRSA")
        sig.initSign(key)
        sig.update(token)
        return sig.sign()
    }

    private fun encodePublicKey(pair: KeyPair): ByteArray {
        val base64 = android.util.Base64.encodeToString(
            pair.public.encoded,
            android.util.Base64.NO_WRAP
        )
        // Формат ADB: <base64> <user>@<host>
        return "$base64 twizuku@android\0".toByteArray()
    }

    private fun writeInt32LE(arr: ByteArray, off: Int, value: Int) {
        arr[off] = (value and 0xFF).toByte()
        arr[off + 1] = ((value ushr 8) and 0xFF).toByte()
        arr[off + 2] = ((value ushr 16) and 0xFF).toByte()
        arr[off + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun readInt32LE(arr: ByteArray, off: Int): Int {
        return (arr[off].toInt() and 0xFF) or
                ((arr[off + 1].toInt() and 0xFF) shl 8) or
                ((arr[off + 2].toInt() and 0xFF) shl 16) or
                ((arr[off + 3].toInt() and 0xFF) shl 24)
    }

    data class Packet(val cmd: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)
}

// Top-level, чтобы работало в инициализаторе companion object
private fun String.toAdbInt(): Int {
    require(length == 4) { "ADB command must be 4 chars: $this" }
    return (this[0].code) or
            (this[1].code shl 8) or
            (this[2].code shl 16) or
            (this[3].code shl 24)
}
