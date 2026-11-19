package com.visiuun.inkwell.nfc

import java.io.IOException
import java.security.SecureRandom
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** One raw ISO 14443-3A exchange with the case (CRC handled by the transport). */
fun interface CaseLink {
    @Throws(IOException::class)
    fun transceive(frame: ByteArray): ByteArray
}

enum class Stage { LOGIN, HANDSHAKE, PREPARE, TRANSFER, REFRESH }

class CaseException(val stage: Stage, message: String, cause: Throwable? = null) : IOException(message, cause)

class CancelledException : IOException("Cancelled")

/** What the case reports about itself in the handshake. */
data class CaseInfo(
    val version: String,
    val versionNumber: Int?,
    val screenType: Int,
    val width: Int,
    val height: Int,
    /**
     * High nibble: sampling mode, low nibble: screen mode. The vendor app chooses its pixel layout
     * from this, but that choice doesn't hold on real hardware, so it is only reported here.
     */
    val mode: Int,
    val manufacturer: Int,
    val batch: Int,
    val radio: Int,
    val flag: Int,
    val serial: String? = null,
    /** The decrypted handshake reply as hex, for troubleshooting. */
    val raw: String = "",
) {
    /** Older 200x300 panels use 200-byte packets without a checksum. */
    val framing: Framing get() = if (width == 200 && height == 300) Framing.LEGACY else Framing.STANDARD

    /** The firmware asks for one empty trailing packet before it starts drawing. */
    val wantsTrailingPacket: Boolean get() = (versionNumber != null && versionNumber > 500) || flag == 1 || flag == 2

    /** Large panels take both halves of the picture at fixed offsets in a 108 kB buffer. */
    val usesSplitBuffer: Boolean
        get() = (version.startsWith("SN60") || version.startsWith("SN3H-W")) && (versionNumber ?: 0) >= 104
}

enum class Framing(val chunk: Int) { STANDARD(180), LEGACY(200) }

enum class RefreshResult {
    /** The case reported that the panel finished drawing. */
    DONE,
    /** The case reported a drawing error. */
    FAILED,
    /** The picture was delivered but the link dropped before the case confirmed the redraw. */
    UNCONFIRMED,
}

/**
 * The wire protocol of the white-label four-colour NFC e-ink cases (SpaceInk, DeerPhoneCase and
 * friends): a Fudan NFC front end that looks like a Type 2 tag, with a microcontroller behind it.
 *
 *  1. Login: `B0` returns a challenge, `B1` + PRINTcipher-48 answer unlocks the tag, then
 *     `AF E0 01 91` switches the front end into pass-through mode.
 *  2. Handshake: 32-byte commands starting `00 4A`, AES-128-ECB encrypted. `01 00` returns the case
 *     description; the last four bytes of that (still encrypted) reply are the session token every
 *     later command carries.
 *  3. Picture: `09 00` prepares, `09 01` announces the packet count, `09 02` carries each packet in
 *     the clear. The case answers every packet with the number it wants next.
 *  4. Redraw: the reader keeps the field up and polls `05 00` until the case answers `09 03`.
 */
class CaseSession(
    private val link: CaseLink,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val random: Random = SecureRandom(),
    private val cancelled: () -> Boolean = { false },
    private val key: ByteArray = DEFAULT_KEY,
) {
    private var token = ByteArray(4)
    lateinit var info: CaseInfo
        private set

    /** A one-line account of the last transfer, for troubleshooting. */
    var summary: String = ""
        private set

    private fun check() {
        if (cancelled()) throw CancelledException()
    }

    private fun aes(mode: Int, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").apply { init(mode, SecretKeySpec(key, "AES")) }.doFinal(data)

    private fun exchange(stage: Stage, frame: ByteArray): ByteArray = try {
        link.transceive(frame)
    } catch (e: CancelledException) {
        throw e
    } catch (e: IOException) {
        if (cancelled()) throw CancelledException()
        throw CaseException(stage, e.message ?: "Lost contact with the case", e)
    }

    /** A 32-byte encrypted command; returns the decrypted reply. */
    private fun secure(stage: Stage, command: ByteArray): ByteArray {
        val reply = exchange(stage, aes(Cipher.ENCRYPT_MODE, command))
        if (reply.isEmpty() || reply.size % 16 != 0) throw CaseException(stage, "Unexpected reply (${reply.size} bytes)")
        return aes(Cipher.DECRYPT_MODE, reply)
    }

    private fun command(a: Int, b: Int, vararg payload: Int): ByteArray {
        val c = ByteArray(32)
        c[1] = 0x4A
        c[2] = a.toByte()
        c[3] = b.toByte()
        payload.forEachIndexed { i, v -> c[4 + i] = v.toByte() }
        token.copyInto(c, 28)
        return c
    }

    /** Step 1: unlock the NFC front end and switch it to pass-through. */
    fun login() {
        check()
        val challenge = exchange(Stage.LOGIN, byteArrayOf(0xB0.toByte())).reversedArray()
        if (challenge.size < 4) throw CaseException(Stage.LOGIN, "This tag doesn't speak the case protocol")
        val block = ByteArray(6).also { random.nextBytes(it) }
        challenge.copyInto(block, 0, 0, 4)
        var number = 0L
        for (b in block) number = (number shl 8) or (b.toLong() and 0xFF)
        val answer = PrintCipher.decrypt(number, 0L, 0L)
        val frame = ByteArray(7)
        frame[0] = 0xB1.toByte()
        for (i in 0 until 6) frame[1 + i] = (answer ushr (8 * i)).toByte()
        val ok = exchange(Stage.LOGIN, frame)
        if (!(ok.size == 2 && ok[0] == 0xAA.toByte() && ok[1] == 0xAA.toByte())) {
            throw CaseException(Stage.LOGIN, "The case rejected the login")
        }
        exchange(Stage.LOGIN, byteArrayOf(0xAF.toByte(), 0xE0.toByte(), 0x01, 0x91.toByte()))
    }

    /** Step 2: fetch the session token and the case description. */
    fun handshake(): CaseInfo {
        check()
        sleep(100)
        val probe = ByteArray(32)
        probe[4] = 1
        exchange(Stage.HANDSHAKE, probe)
        token = ByteArray(4)
        val encrypted = exchange(Stage.HANDSHAKE, aes(Cipher.ENCRYPT_MODE, command(0x01, 0x00)))
        if (encrypted.size < 32 || encrypted.size % 16 != 0) {
            throw CaseException(Stage.HANDSHAKE, "Unexpected handshake reply (${encrypted.size} bytes)")
        }
        token = encrypted.copyOfRange(encrypted.size - 4, encrypted.size)
        info = parseInfo(aes(Cipher.DECRYPT_MODE, encrypted))
            ?: throw CaseException(Stage.HANDSHAKE, "The case answered, but not with a description this app understands")
        return info
    }

    /** Optional: the serial number programmed into the case. */
    fun readSerial(): String? {
        check()
        val reply = secure(Stage.HANDSHAKE, command(0x01, 0x09, 0))
        if (reply.size < 7 || reply[5] != 0.toByte()) return null
        val length = (reply[4].toInt() and 0xFF) - 1
        if (length <= 0 || 6 + length > reply.size) return null
        return reply.copyOfRange(6, 6 + length).joinToString("") { "%02X".format(it) }
    }

    /**
     * Steps 3 and 4. [payload] is the packed picture for [info]; [onProgress] gets (packets delivered, total).
     * Blocks until the case has redrawn, which takes around 20 seconds on the four-colour panel.
     */
    fun sendImage(
        payload: ByteArray,
        onProgress: (sent: Int, total: Int) -> Unit = { _, _ -> },
        onRefreshing: () -> Unit = {},
        refreshTimeoutMs: Long = 90_000,
        now: () -> Long = System::currentTimeMillis,
    ): RefreshResult {
        val framing = info.framing
        val packets = packets(if (info.usesSplitBuffer) splitBuffer(payload) else payload, framing)
        val count = packets.size
        summary = "${payload.size} bytes, $count packets of ${framing.chunk}" +
            (if (info.usesSplitBuffer) ", split buffer" else "") +
            (if (framing == Framing.STANDARD && info.wantsTrailingPacket) ", trailing packet" else "")
        if (count > 0xFFFF) throw CaseException(Stage.PREPARE, "Picture is too large for this case")

        check()
        if (framing == Framing.STANDARD) secure(Stage.PREPARE, command(0x09, 0x00, 0, 0xFF))
        else secure(Stage.PREPARE, command(0x09, 0x00, 0))
        val announce = secure(Stage.PREPARE, command(0x09, 0x01, 2, count shr 8, count and 0xFF))
        if (announce.size < 8) throw CaseException(Stage.PREPARE, "The case didn't accept the transfer")
        var next = be16(announce, 6)
        onProgress(0, count)

        val tail = if (framing == Framing.STANDARD) 1 else 2
        var budget = count * 4 + 16
        while (next <= count) {
            check()
            if (next < 1 || budget-- <= 0) throw CaseException(Stage.TRANSFER, "The case asked for an impossible packet ($next)")
            val body = packets[next - 1]
            val frame = ByteArray(6 + body.size + tail)
            frame[1] = 0x4A
            frame[2] = 0x09
            frame[3] = 0x02
            frame[4] = (next shr 8).toByte()
            frame[5] = next.toByte()
            body.copyInto(frame, 6)
            val reply = exchange(Stage.TRANSFER, frame)
            if (reply.size < 8) throw CaseException(Stage.TRANSFER, "The case stopped acknowledging packets")
            next = be16(reply, 6)
            onProgress((next - 1).coerceIn(0, count), count)
        }

        if (framing == Framing.STANDARD && info.wantsTrailingPacket) {
            val frame = ByteArray(6 + 180 + 2)
            frame[1] = 0x4A
            frame[2] = 0x09
            frame[3] = 0x02
            frame[4] = ((count + 1) shr 8).toByte()
            frame[5] = (count + 1).toByte()
            try {
                link.transceive(frame)
            } catch (_: IOException) {
                // The picture is already in; the redraw poll below decides how this ends.
            }
        }

        onRefreshing()
        sleep(100)
        val poll = command(0x05, 0x00)
        val pause = if (framing == Framing.STANDARD) 150L else 100L
        val deadline = now() + refreshTimeoutMs
        var failures = 0
        while (now() < deadline) {
            if (cancelled()) return RefreshResult.UNCONFIRMED
            try {
                val reply = link.transceive(poll)
                failures = 0
                if (reply.size > 5 && reply[2] == 9.toByte() && reply[3] == 3.toByte()) {
                    return if (reply[5] == 0.toByte()) RefreshResult.DONE else RefreshResult.FAILED
                }
            } catch (_: IOException) {
                // A dropped frame is normal while the panel draws current; a dead link is not.
                if (++failures >= 8) return RefreshResult.UNCONFIRMED
            }
            sleep(pause)
        }
        return RefreshResult.UNCONFIRMED
    }

    companion object {
        /** The AES key every case of this family ships with. */
        val DEFAULT_KEY = byteArrayOf(
            0x3A, 0x60, 0x43, 0x2A, 0x5C, 0x01, 0x21, 0x1F, 0x29, 0x1E, 0x0F, 0x4E, 0x0C, 0x13, 0x28, 0x25,
        )

        internal fun be16(data: ByteArray, at: Int) = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

        internal fun parseInfo(d: ByteArray): CaseInfo? {
            if (d.size < 29) return null
            val width = be16(d, 21)
            val height = be16(d, 23)
            if (width !in 16..2000 || height !in 16..2000) return null
            val text = String(d, 6, 14, Charsets.ISO_8859_1).filter { it in ' '..'~' }.trim()
            return CaseInfo(
                version = text,
                versionNumber = versionNumber(d),
                screenType = d[20].toInt() and 0xFF,
                width = width,
                height = height,
                mode = d[25].toInt() and 0xFF,
                manufacturer = d[26].toInt() and 0xFF,
                batch = d[27].toInt() and 0xFF,
                radio = d[28].toInt() and 0xFF,
                flag = d[4].toInt() and 0xFF,
                raw = d.joinToString("") { "%02X".format(it) },
            )
        }

        /** The firmware build number: the digits after the last dash of the version string. */
        private fun versionNumber(d: ByteArray): Int? {
            for (length in intArrayOf(14, 12)) {
                val last = String(d, 6, length, Charsets.ISO_8859_1).replace("V", "").replace(" ", "").split("-").last()
                if (last.all { it.isDigit() }) return last.toIntOrNull()
            }
            return null
        }

        /** Cuts the picture into packet bodies. Standard packets end with a one-byte sum of their 180 bytes. */
        internal fun packets(payload: ByteArray, framing: Framing): List<ByteArray> {
            val size = framing.chunk
            val count = (payload.size + size - 1) / size
            return List(count) { i ->
                val from = i * size
                val to = minOf(from + size, payload.size)
                when (framing) {
                    Framing.STANDARD -> {
                        val body = ByteArray(size + 1) { 0xFF.toByte() }
                        payload.copyInto(body, 0, from, to)
                        var sum = 0
                        for (j in 0 until size) sum += body[j]
                        body[size] = sum.toByte()
                        body
                    }
                    Framing.LEGACY -> ByteArray(size).also { payload.copyInto(it, 0, from, to) }
                }
            }
        }

        /** Large panels: first half at 0, second half at 60000, in a fixed 108000-byte buffer. */
        internal fun splitBuffer(payload: ByteArray): ByteArray {
            val half = payload.size / 2
            if (half > 48_000) return payload
            val out = ByteArray(108_000)
            payload.copyInto(out, 0, 0, half)
            payload.copyInto(out, 60_000, half, half * 2)
            return out
        }
    }
}
