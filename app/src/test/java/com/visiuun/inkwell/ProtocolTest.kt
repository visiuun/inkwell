package com.visiuun.inkwell

import com.visiuun.inkwell.image.Dither
import com.visiuun.inkwell.image.Ink
import com.visiuun.inkwell.image.InkImage
import com.visiuun.inkwell.image.Packer
import com.visiuun.inkwell.image.Quantizer
import com.visiuun.inkwell.nfc.CaseException
import com.visiuun.inkwell.nfc.CaseInfo
import com.visiuun.inkwell.nfc.CaseLink
import com.visiuun.inkwell.nfc.CaseSession
import com.visiuun.inkwell.nfc.Framing
import com.visiuun.inkwell.nfc.PrintCipher
import com.visiuun.inkwell.nfc.RefreshResult
import com.visiuun.inkwell.nfc.SimulatedCase
import com.visiuun.inkwell.nfc.Stage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

class ProtocolTest {
    // Produced by running the vendor app's own cipher class on these inputs (key and permutation key zero).
    private val vendorVectors = listOf(
        0x000000000000L to 0x86B3BFEC624CL,
        0xFFFFFFFFFFFFL to 0xFAE433FA39A6L,
        0x123456789ABCL to 0xCF0DBDE5EF5AL,
        0x4C847555C35BL to 0x8BDC3DA4E30BL,
        0x9D350DFE8AF7L to 0x8721B1C48548L,
        0xBBE10C45C028L to 0x497EB23F8D88L,
        0x3CE3F12BBB4BL to 0xFF1825E7EEB8L,
        0x9D82B52C856DL to 0x9F6E992A5AD5L,
        0x6ABE17610C9AL to 0x3D4743A9C7C8L,
        0x67BD738597DCL to 0x9C89F9210E6FL,
        0x8E9961B35C88L to 0x9DE577985667L,
        0x6CF7B0BFBE20L to 0x0C3D8AC00991L,
    )

    @Test
    fun cipherMatchesVendorImplementation() {
        for ((input, expected) in vendorVectors) {
            assertEquals(expected, PrintCipher.decrypt(input, 0L, 0L))
            assertEquals(input, PrintCipher.encrypt(expected, 0L, 0L))
        }
        assertEquals(0x9725CBDEE15EL, PrintCipher.decrypt(0x0A0B0C0D0E0FL, 0x112233445566L, 0x89ABCDEFL))
    }

    private fun payload(size: Int) = ByteArray(size) { (it * 31 + 7).toByte() }

    private fun join(parts: List<ByteArray>) = parts.fold(ByteArray(0)) { acc, b -> acc + b }

    private fun run(case: SimulatedCase, data: ByteArray): Pair<CaseSession, RefreshResult> {
        val session = CaseSession(case, sleep = {}, random = java.util.Random(1))
        session.login()
        session.handshake()
        return session to session.sendImage(data)
    }

    @Test
    fun deliversAFullPicture() {
        val case = SimulatedCase()
        val data = payload(240 * 416 / 4)
        val (session, result) = run(case, data)
        assertEquals(RefreshResult.DONE, result)
        assertEquals(240, session.info.width)
        assertEquals(416, session.info.height)
        assertEquals("SN37-B4-V102", session.info.version)
        assertEquals(102, session.info.versionNumber)
        assertTrue(case.prepared)
        // 24960 bytes in 180-byte packets: the 139 packets the vendor app sends.
        assertEquals(139, case.received.size)
        assertEquals(180, case.chunkSize)
        val joined = join(case.received)
        assertArrayEquals(data, joined.copyOf(data.size))
        assertTrue(joined.drop(data.size).all { it == 0xFF.toByte() })
        assertFalse(case.trailingPacket)
    }

    @Test
    fun resendsThePacketTheCaseAsksFor() {
        val case = SimulatedCase(dropPacket = 57)
        val data = payload(24960)
        val (_, result) = run(case, data)
        assertEquals(RefreshResult.DONE, result)
        assertArrayEquals(data, join(case.received).copyOf(data.size))
    }

    @Test
    fun newerFirmwareGetsTheTrailingPacket() {
        val newer = SimulatedCase(version = "SN37-B4-V501")
        run(newer, payload(900))
        assertTrue(newer.trailingPacket)
        val flagged = SimulatedCase(flag = 2)
        run(flagged, payload(900))
        assertTrue(flagged.trailingPacket)
    }

    @Test
    fun smallPanelsUsePlain200BytePackets() {
        val case = SimulatedCase(width = 200, height = 300, version = "SN29-A1-V012")
        val data = payload(200 * 300 / 4)
        val (session, result) = run(case, data)
        assertEquals(Framing.LEGACY, session.info.framing)
        assertEquals(RefreshResult.DONE, result)
        assertEquals(200, case.chunkSize)
        assertEquals(75, case.received.size)
        assertArrayEquals(data, join(case.received))
    }

    @Test
    fun readsTheSerial() {
        val session = CaseSession(SimulatedCase(), sleep = {})
        session.login()
        session.handshake()
        assertEquals("123456789ABC", session.readSerial())
    }

    @Test
    fun wrongKeyIsReportedNotSent() {
        val case = SimulatedCase(key = ByteArray(16) { 9 })
        val session = CaseSession(case, sleep = {})
        session.login()
        try {
            session.handshake()
            fail("handshake should not succeed with the wrong key")
        } catch (e: CaseException) {
            assertEquals(Stage.HANDSHAKE, e.stage)
        }
    }

    @Test
    fun aDeadLinkAfterTheTransferIsUnconfirmedNotAFailure() {
        val case = SimulatedCase()
        var dead = false
        val link = CaseLink { frame ->
            // The link dies on the first redraw poll and stays dead.
            if (frame.size == 32 && frame[1] == 0x4A.toByte() && frame[2] == 5.toByte()) dead = true
            if (dead) throw IOException("Tag was lost.")
            case.transceive(frame)
        }
        val session = CaseSession(link, sleep = {})
        session.login()
        session.handshake()
        assertEquals(RefreshResult.UNCONFIRMED, session.sendImage(payload(24960)))
        assertEquals(139, case.received.size)
    }

    @Test
    fun aTagThatIsNotACaseFailsAtLogin() {
        val session = CaseSession({ throw IOException("Transceive failed") }, sleep = {})
        try {
            session.login()
            fail("login should fail")
        } catch (e: CaseException) {
            assertEquals(Stage.LOGIN, e.stage)
        }
    }

    @Test
    fun versionNumbersFollowTheVendorRules() {
        fun info(version: String): CaseInfo? {
            val d = ByteArray(32)
            version.toByteArray(Charsets.ISO_8859_1).copyInto(d, 6)
            d[22] = 240.toByte()
            d[23] = 1
            d[24] = 160.toByte()
            return CaseSession.parseInfo(d)
        }
        assertEquals(105, info("SN37-B4-V105  ")!!.versionNumber)
        // Trailing NULs spoil the 14-byte read, so the 12-byte read decides.
        assertEquals(600, info("SN60-W-V600 ")!!.versionNumber)
        assertNull(info("SN37-B4-Vbeta ")!!.versionNumber)
        // An all-zero reply (wrong key, wrong device) is not a description.
        assertNull(CaseSession.parseInfo(ByteArray(32)))
    }

    // ------------------------------------------------------------ pixels

    /** The vendor's route, step by step: mirror, rotate a quarter turn clockwise, read column by column. */
    private fun vendorPortraitPack(image: InkImage): ByteArray {
        val w = image.width
        val h = image.height
        val mirrored = Array(h) { y -> ByteArray(w) { x -> image.pixels[y * w + (w - 1 - x)] } }
        // Clockwise quarter turn: source (x, y) lands on (h - 1 - y, x); the result is h wide and w tall.
        val rotated = Array(w) { ByteArray(h) }
        for (y in 0 until h) for (x in 0 until w) rotated[x][h - 1 - y] = mirrored[y][x]
        val out = ByteArray(h * (w / 4))
        var at = 0
        for (column in 0 until h) {
            var bits = 0
            for (row in 0 until w) {
                bits = (bits shl 2) or (rotated[row][column].toInt() and 3)
                if (row % 4 == 3) {
                    out[at++] = bits.toByte()
                    bits = 0
                }
            }
        }
        return out
    }

    @Test
    fun packingMatchesTheVendorRoute() {
        val random = Random(3)
        val image = InkImage(240, 416, ByteArray(240 * 416) { random.nextInt(4).toByte() })
        val packed = Packer.pack(image)
        assertEquals(24960, packed.size)
        assertArrayEquals(vendorPortraitPack(image), packed)
        // Worked out by hand: the portrait layout is the picture read backwards.
        val reversed = image.pixels.reversedArray()
        for (i in packed.indices) {
            val expected = (reversed[i * 4].toInt() shl 6) or (reversed[i * 4 + 1].toInt() shl 4) or
                (reversed[i * 4 + 2].toInt() shl 2) or reversed[i * 4 + 3].toInt()
            assertEquals(expected.toByte(), packed[i])
        }
    }

    @Test
    fun inkCodesAreTheOnesTheCaseExpects() {
        assertEquals(0, Ink.BLACK.code)
        assertEquals(1, Ink.WHITE.code)
        assertEquals(2, Ink.YELLOW.code)
        assertEquals(3, Ink.RED.code)
        assertTrue(Packer.pack(InkImage.solid(240, 416, Ink.WHITE)).all { it == 0x55.toByte() })
        assertTrue(Packer.pack(InkImage.solid(240, 416, Ink.YELLOW)).all { it == 0xAA.toByte() })
        assertTrue(Packer.pack(InkImage.solid(240, 416, Ink.RED)).all { it == 0xFF.toByte() })
    }

    @Test
    fun quantizerKeepsPureColoursAndRespectsTheInkList() {
        val w = 32
        val h = 32
        fun fill(r: Int, g: Int, b: Int) = FloatArray(w * h * 3) { i ->
            when (i % 3) {
                0 -> r.toFloat()
                1 -> g.toFloat()
                else -> b.toFloat()
            }
        }
        val all = Ink.entries.toList()
        for (dither in Dither.entries) {
            assertTrue(Quantizer.quantize(fill(255, 0, 0), w, h, all, dither).pixels.all { it == Ink.RED.code.toByte() })
            assertTrue(Quantizer.quantize(fill(255, 255, 0), w, h, all, dither).pixels.all { it == Ink.YELLOW.code.toByte() })
            assertTrue(Quantizer.quantize(fill(0, 0, 0), w, h, all, dither).pixels.all { it == Ink.BLACK.code.toByte() })
            val noRed = Quantizer.quantize(fill(200, 30, 30), w, h, listOf(Ink.BLACK, Ink.WHITE, Ink.YELLOW), dither)
            assertTrue(noRed.pixels.none { it == Ink.RED.code.toByte() })
        }
        // Mid grey diffuses to an even mix of black and white with no stray colour.
        val grey = Quantizer.quantize(fill(128, 128, 128), w, h, all, Dither.SMOOTH).pixels
        assertTrue(grey.all { it == Ink.BLACK.code.toByte() || it == Ink.WHITE.code.toByte() })
        val whites = grey.count { it == Ink.WHITE.code.toByte() }
        assertTrue("white share was $whites of ${grey.size}", whites in grey.size * 4 / 10..grey.size * 6 / 10)
    }
}
