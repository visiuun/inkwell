package com.visiuun.inkwell.nfc

/**
 * PRINTcipher-48, the lightweight block cipher the case's Fudan NFC front end uses for its
 * challenge/response login. Only the decrypt direction is needed by a reader: the case sends a
 * challenge, the reader answers with decrypt(challenge) and the case checks it by encrypting.
 *
 * Bit 0 is the least significant bit of the 48-bit block.
 */
internal object PrintCipher {
    private val SBOX = arrayOf(
        intArrayOf(0, 1, 3, 6, 7, 4, 5, 2),
        intArrayOf(0, 1, 7, 4, 3, 6, 5, 2),
        intArrayOf(0, 3, 1, 6, 7, 5, 4, 2),
        intArrayOf(0, 7, 3, 5, 1, 4, 6, 2),
    )
    private val INV_SBOX = arrayOf(
        intArrayOf(0, 1, 7, 2, 5, 6, 3, 4),
        intArrayOf(0, 1, 7, 4, 3, 6, 5, 2),
        intArrayOf(0, 2, 7, 1, 6, 5, 3, 4),
        intArrayOf(0, 4, 7, 2, 5, 3, 6, 1),
    )
    private const val ROUNDS = 48

    private fun bits(value: Long, count: Int) = IntArray(count) { ((value ushr it) and 1L).toInt() }

    private fun number(bits: IntArray): Long {
        var v = 0L
        for (i in bits.indices) v = v or (bits[i].toLong() shl i)
        return v
    }

    fun decrypt(block: Long, key: Long, permKey: Long): Long {
        val state = bits(block, 48)
        val k = bits(key, 48)
        val pk = bits(permKey, 32)
        val tmp = IntArray(48)
        val counter = intArrayOf(1, 0, 0, 1, 0, 0)
        repeat(ROUNDS) {
            for (i in 0 until 16) {
                val v = state[i * 3] or (state[i * 3 + 1] shl 1) or (state[i * 3 + 2] shl 2)
                val p = pk[i * 2] or (pk[i * 2 + 1] shl 1)
                val s = INV_SBOX[p][v]
                tmp[i * 3] = s and 1
                tmp[i * 3 + 1] = (s shr 1) and 1
                tmp[i * 3 + 2] = (s shr 2) and 1
            }
            // Round counter runs backwards: shift left, feed (first xor 1 xor last) in at the end.
            val feed = counter[0] xor 1 xor counter[5]
            for (i in 0 until 5) counter[i] = counter[i + 1]
            counter[5] = feed
            for (i in 0 until 6) tmp[i] = tmp[i] xor counter[i]
            for (i in 0 until 48) {
                val src = when {
                    i < 16 -> i * 3
                    i < 32 -> (i - 16) * 3 + 1
                    else -> (i - 32) * 3 + 2
                }
                state[i] = tmp[src] xor k[i]
            }
        }
        return number(state)
    }

    /** The case's side of the handshake. Used by the tests and the built-in simulator. */
    fun encrypt(block: Long, key: Long, permKey: Long): Long {
        val state = bits(block, 48)
        val k = bits(key, 48)
        val pk = bits(permKey, 32)
        val tmp = IntArray(48)
        val counter = IntArray(6)
        repeat(ROUNDS) {
            for (i in 0 until 48) state[i] = state[i] xor k[i]
            for (i in 0 until 47) tmp[(i * 3) % 47] = state[i]
            tmp[47] = state[47]
            val feed = counter[5] xor 1 xor counter[4]
            for (i in 5 downTo 1) counter[i] = counter[i - 1]
            counter[0] = feed
            for (i in 0 until 6) tmp[i] = tmp[i] xor counter[i]
            for (i in 0 until 16) {
                val v = tmp[i * 3] or (tmp[i * 3 + 1] shl 1) or (tmp[i * 3 + 2] shl 2)
                val p = pk[i * 2] or (pk[i * 2 + 1] shl 1)
                val s = SBOX[p][v]
                state[i * 3] = s and 1
                state[i * 3 + 1] = (s shr 1) and 1
                state[i * 3 + 2] = (s shr 2) and 1
            }
        }
        return number(state)
    }
}
