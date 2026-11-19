package com.visiuun.inkwell.nfc

import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * The case's side of the conversation, as far as it can be inferred from the reader's side.
 * It backs the unit tests and the demo mode on devices without NFC; it is not a substitute for
 * testing against real hardware.
 */
internal class SimulatedCase(
    private val width: Int = 240,
    private val height: Int = 416,
    private val version: String = "SN37-B4-V102",
    private val mode: Int = 0,
    private val flag: Int = 0,
    private val refreshPolls: Int = 20,
    private val dropPacket: Int = -1,
    private val key: ByteArray = CaseSession.DEFAULT_KEY,
    private val random: Random = Random(7),
) : CaseLink {
    private var challenge: ByteArray? = null
    private var unlocked = false
    private var passThrough = false
    private var token: ByteArray? = null
    private var expected = 0
    private var next = 1
    private var polls = 0
    private var dropped = false

    /** Packet bodies as received, in order. */
    val received = ArrayList<ByteArray>()
    var prepared = false
        private set
    var trailingPacket = false
        private set
    var chunkSize = 0
        private set

    private fun aes(mode: Int, data: ByteArray) =
        Cipher.getInstance("AES/ECB/NoPadding").apply { init(mode, SecretKeySpec(key, "AES")) }.doFinal(data)

    private fun tokenMatches(frame: ByteArray) = token != null && frame.copyOfRange(28, 32).contentEquals(token!!)

    private fun reply(a: Int, b: Int, build: (ByteArray) -> Unit = {}): ByteArray =
        ByteArray(32).also { it[1] = 0x4A; it[2] = a.toByte(); it[3] = b.toByte(); build(it) }

    override fun transceive(frame: ByteArray): ByteArray {
        if (frame.isEmpty()) throw IOException("empty frame")
        if (!passThrough) return frontEnd(frame)
        if (frame.size == 32 && frame.take(4).all { it == 0.toByte() }) return ByteArray(32)
        if (frame.size >= 6 && frame[0] == 0.toByte() && frame[1] == 0x4A.toByte()) return plain(frame)
        if (frame.size == 32) return aes(Cipher.ENCRYPT_MODE, secure(aes(Cipher.DECRYPT_MODE, frame)))
        throw IOException("Transceive failed")
    }

    private fun frontEnd(frame: ByteArray): ByteArray = when (frame[0].toInt() and 0xFF) {
        0xB0 -> ByteArray(4).also { random.nextBytes(it); challenge = it }
        0xB1 -> {
            val c = challenge ?: throw IOException("Transceive failed")
            if (frame.size != 7) throw IOException("Transceive failed")
            var answer = 0L
            for (i in 0 until 6) answer = answer or ((frame[1 + i].toLong() and 0xFF) shl (8 * i))
            val block = PrintCipher.encrypt(answer, 0L, 0L)
            val want = c.reversedArray()
            var top = 0L
            for (b in want) top = (top shl 8) or (b.toLong() and 0xFF)
            unlocked = (block ushr 16) == top
            if (unlocked) byteArrayOf(0xAA.toByte(), 0xAA.toByte()) else byteArrayOf(0x55, 0x55)
        }
        0xAF -> {
            if (!unlocked) throw IOException("Transceive failed")
            passThrough = true
            byteArrayOf(0xAA.toByte(), 0xAA.toByte())
        }
        else -> throw IOException("Transceive failed")
    }

    private fun secure(cmd: ByteArray): ByteArray {
        if (cmd[0] != 0.toByte() || cmd[1] != 0x4A.toByte()) throw IOException("Transceive failed")
        val a = cmd[2].toInt()
        val b = cmd[3].toInt()
        return when {
            a == 1 && b == 0 -> {
                val info = reply(1, 0) { r ->
                    r[4] = flag.toByte()
                    val text = version.padEnd(14, ' ').toByteArray(Charsets.ISO_8859_1)
                    text.copyInto(r, 6, 0, 14)
                    r[20] = 0x04
                    r[21] = (width shr 8).toByte(); r[22] = width.toByte()
                    r[23] = (height shr 8).toByte(); r[24] = height.toByte()
                    r[25] = mode.toByte()
                    r[26] = 0x01; r[27] = 0x02; r[28] = 0x00
                }
                // The session token is whatever the encrypted description happens to end with.
                token = aes(Cipher.ENCRYPT_MODE, info).copyOfRange(28, 32)
                info
            }
            !tokenMatches(cmd) -> reply(a, b) { it[5] = 1 }
            a == 1 && b == 9 -> reply(1, 9) { r ->
                val id = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte())
                r[4] = (id.size + 1).toByte()
                id.copyInto(r, 6)
            }
            a == 9 && b == 0 -> { prepared = true; reply(9, 0) }
            a == 9 && b == 1 -> {
                expected = CaseSession.be16(cmd, 5)
                next = 1
                received.clear()
                reply(9, 1) { it[6] = 0; it[7] = 1 }
            }
            else -> reply(a, b) { it[5] = 1 }
        }
    }

    private fun plain(frame: ByteArray): ByteArray {
        val a = frame[2].toInt()
        val b = frame[3].toInt()
        if (a == 9 && b == 2) {
            val index = CaseSession.be16(frame, 4)
            val body = frame.size - 6
            if (index == expected + 1) {
                trailingPacket = true
            } else if (index == next) {
                if (index == dropPacket && !dropped) {
                    // Pretend the packet was corrupted once: ask for it again.
                    dropped = true
                } else {
                    val data: ByteArray
                    if (body == 182) {
                        data = frame.copyOfRange(6, 186)
                        var sum = 0
                        for (v in data) sum += v
                        if (sum.toByte() != frame[186]) throw IOException("Transceive failed")
                    } else {
                        data = frame.copyOfRange(6, frame.size - 2)
                    }
                    chunkSize = data.size
                    received.add(data)
                    next++
                }
            }
            return reply(9, 2) { it[6] = (next shr 8).toByte(); it[7] = next.toByte() }
        }
        if (a == 5 && b == 0) {
            if (!tokenMatches(frame)) return ByteArray(2)
            return if (++polls >= refreshPolls) reply(9, 3) { it[4] = 1 } else reply(5, 0) { it[4] = 1; it[5] = 0x32 }
        }
        throw IOException("Transceive failed")
    }
}
