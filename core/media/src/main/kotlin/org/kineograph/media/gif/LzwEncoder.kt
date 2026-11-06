package org.kineograph.media.gif

import java.io.ByteArrayOutputStream

/**
 * GIF's variant of LZW compression. Codes start one bit wider than the pixel size, grow as the dictionary
 * fills, and the dictionary resets at 4096 entries. Output is split into the sub-blocks of up to 255 bytes that
 * the GIF format requires, ending with an empty block.
 */
internal class LzwEncoder {
    private val hashKeys = IntArray(TABLE_SIZE)
    private val hashValues = ShortArray(TABLE_SIZE)

    fun encode(indexes: ByteArray, minCodeSize: Int): ByteArray {
        val output = BlockWriter()
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1
        var nextCode = endCode + 1
        var codeBits = minCodeSize + 1

        resetTable()
        output.writeCode(clearCode, codeBits)
        if (indexes.isEmpty()) {
            output.writeCode(endCode, codeBits)
            return output.finish()
        }

        var prefix = indexes[0].toInt() and 0xFF
        for (i in 1 until indexes.size) {
            val symbol = indexes[i].toInt() and 0xFF
            val key = (prefix shl 8) or symbol
            val found = lookup(key)
            if (found >= 0) {
                prefix = found
                continue
            }
            output.writeCode(prefix, codeBits)
            if (nextCode < MAX_CODES) {
                insert(key, nextCode)
                // The decoder's table is one entry behind, so widths change once a code exceeds the current range.
                if (nextCode == (1 shl codeBits) && codeBits < MAX_CODE_BITS) codeBits++
                nextCode++
            } else {
                output.writeCode(clearCode, codeBits)
                resetTable()
                nextCode = endCode + 1
                codeBits = minCodeSize + 1
            }
            prefix = symbol
        }
        output.writeCode(prefix, codeBits)
        output.writeCode(endCode, codeBits)
        return output.finish()
    }

    private fun resetTable() {
        hashKeys.fill(-1)
    }

    private fun lookup(key: Int): Int {
        var slot = hash(key)
        while (true) {
            val existing = hashKeys[slot]
            if (existing == -1) return -1
            if (existing == key) return hashValues[slot].toInt() and 0xFFFF
            slot = (slot + 1) and TABLE_MASK
        }
    }

    private fun insert(key: Int, code: Int) {
        var slot = hash(key)
        while (hashKeys[slot] != -1) slot = (slot + 1) and TABLE_MASK
        hashKeys[slot] = key
        hashValues[slot] = code.toShort()
    }

    private fun hash(key: Int): Int = ((key * HASH_MULTIPLIER) ushr HASH_SHIFT) and TABLE_MASK

    /** Packs variable-width codes least-significant bit first and cuts the result into sub-blocks. */
    private class BlockWriter {
        private val output = ByteArrayOutputStream(4096)
        private val block = ByteArray(255)
        private var blockSize = 0
        private var bitBuffer = 0L
        private var bitCount = 0

        fun writeCode(code: Int, width: Int) {
            bitBuffer = bitBuffer or (code.toLong() shl bitCount)
            bitCount += width
            while (bitCount >= 8) {
                writeByte((bitBuffer and 0xFF).toInt())
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
            }
        }

        private fun writeByte(value: Int) {
            block[blockSize++] = value.toByte()
            if (blockSize == block.size) flushBlock()
        }

        private fun flushBlock() {
            if (blockSize == 0) return
            output.write(blockSize)
            output.write(block, 0, blockSize)
            blockSize = 0
        }

        fun finish(): ByteArray {
            if (bitCount > 0) {
                writeByte((bitBuffer and 0xFF).toInt())
                bitBuffer = 0
                bitCount = 0
            }
            flushBlock()
            output.write(0)
            return output.toByteArray()
        }
    }

    private companion object {
        const val MAX_CODES = 4096
        const val MAX_CODE_BITS = 12
        const val TABLE_SIZE = 1 shl 14
        const val TABLE_MASK = TABLE_SIZE - 1
        const val HASH_MULTIPLIER = -1640531535
        const val HASH_SHIFT = 18
    }
}
