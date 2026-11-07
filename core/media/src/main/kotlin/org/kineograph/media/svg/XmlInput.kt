package org.kineograph.media.svg

import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.nio.charset.Charset

/**
 * What the XML parser reads from. The parser cannot be interrupted, so anything it would choke on has to be stopped on the way in.
 *
 * Decoding happens here rather than in the parser because the check has to see characters: a file can declare almost any encoding, and
 * looking at its bytes would only work for some of them.
 */
internal object XmlInput {
    fun reader(text: String, limits: SvgLimits): Reader = AttributeGuard(StringReader(text), limits.maxAttributesPerElement)

    fun reader(bytes: ByteArray, start: Int, limits: SvgLimits): Reader =
        AttributeGuard(decoded(bytes, start), limits.maxAttributesPerElement)

    /** Tells the encoding apart the way the XML specification suggests, and the way the parser itself would. */
    private fun decoded(bytes: ByteArray, start: Int): Reader {
        fun at(index: Int): Int = if (start + index < bytes.size) bytes[start + index].toInt() and 0xFF else -1

        fun from(skip: Int, charset: Charset): Reader {
            val offset = (start + skip).coerceAtMost(bytes.size)
            return InputStreamReader(ByteArrayInputStream(bytes, offset, bytes.size - offset), charset)
        }
        val b0 = at(0)
        val b1 = at(1)
        val b2 = at(2)
        val b3 = at(3)
        return when {
            b0 == 0x00 && b1 == 0x00 && b2 == 0xFE && b3 == 0xFF -> from(4, Charset.forName("UTF-32BE"))
            b0 == 0xFF && b1 == 0xFE && b2 == 0x00 && b3 == 0x00 -> from(4, Charset.forName("UTF-32LE"))
            b0 == 0x00 && b1 == 0x00 && b2 == 0x00 && b3 == '<'.code -> from(0, Charset.forName("UTF-32BE"))
            b0 == '<'.code && b1 == 0x00 && b2 == 0x00 && b3 == 0x00 -> from(0, Charset.forName("UTF-32LE"))
            b0 == 0x00 && b1 == '<'.code && b2 == 0x00 && b3 == '?'.code -> from(0, Charsets.UTF_16BE)
            b0 == '<'.code && b1 == 0x00 && b2 == '?'.code && b3 == 0x00 -> from(0, Charsets.UTF_16LE)
            b0 == 0xFE && b1 == 0xFF -> from(2, Charsets.UTF_16BE)
            b0 == 0xFF && b1 == 0xFE -> from(2, Charsets.UTF_16LE)
            b0 == 0xEF && b1 == 0xBB && b2 == 0xBF -> from(3, Charsets.UTF_8)
            b0 == '<'.code && b1 == '?'.code && b2 == 'x'.code && b3 == 'm'.code -> declared(bytes, start)
            else -> from(0, Charsets.UTF_8)
        }
    }

    /** The declaration itself is in plain letters, whatever it then says the rest of the file is written in. */
    private fun declared(bytes: ByteArray, start: Int): Reader {
        var end = start
        while (end < bytes.size && bytes[end] != '>'.code.toByte()) end++
        // A file cut off inside its declaration is the parser's to complain about.
        if (end >= bytes.size) return InputStreamReader(ByteArrayInputStream(bytes, start, bytes.size - start), Charsets.UTF_8)
        end++
        val declaration = String(bytes, start, end - start, Charsets.ISO_8859_1)
        val named = declaredEncoding(declaration) ?: Charsets.UTF_8
        return JoinedReader(StringReader(declaration), InputStreamReader(ByteArrayInputStream(bytes, end, bytes.size - end), named))
    }

    private fun declaredEncoding(declaration: String): Charset? {
        val word = declaration.indexOf("encoding")
        if (word < 0) return null
        var open = word
        while (open < declaration.length && declaration[open] != '"' && declaration[open] != '\'') open++
        if (open >= declaration.length) return null
        val close = declaration.indexOf(declaration[open], open + 1)
        if (close < 0) return null
        // An encoding no one has heard of ends here as a broken file, which is what it is.
        return Charset.forName(declaration.substring(open + 1, close).trim())
    }
}

private class JoinedReader(private var first: Reader?, private val second: Reader) : Reader() {
    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        first?.let { reader ->
            val count = reader.read(buffer, offset, length)
            if (count >= 0) return count
            first = null
        }
        return second.read(buffer, offset, length)
    }

    override fun close() {
        first?.close()
        second.close()
    }
}

/**
 * Counts the attributes of each start tag as the text goes by. The parser grows its list of attributes by a fixed step, which makes one
 * element with a hundred thousand of them take many seconds, in a call nothing can stop. Refusing such a file loses nothing real.
 */
private class AttributeGuard(private val inner: Reader, private val limit: Int) : Reader() {
    private var state = TEXT
    private var attributes = 0
    private var quote = '\u0000'
    private var run = 0

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        val count = inner.read(buffer, offset, length)
        for (i in offset until offset + count) step(buffer[i])
        return count
    }

    override fun close() {
        inner.close()
    }

    private fun step(c: Char) {
        when (state) {
            TEXT -> {
                if (c == '<') state = OPENING
            }

            OPENING -> {
                when (c) {
                    '!' -> {
                        state = DECLARATION
                    }

                    '?' -> {
                        state = INSTRUCTION
                        run = 0
                    }

                    '/' -> {
                        state = TEXT
                    }

                    else -> {
                        state = TAG
                        attributes = 0
                        quote = '\u0000'
                    }
                }
            }

            DECLARATION -> {
                state =
                    when (c) {
                        '-' -> COMMENT
                        '[' -> CDATA
                        else -> TEXT
                    }
                run = 0
            }

            COMMENT -> {
                run = if (c == '-') {
                    run + 1
                } else if (c == '>' && run >= 2) {
                    endOfMarkup()
                } else {
                    0
                }
            }

            CDATA -> {
                run = if (c == ']') {
                    run + 1
                } else if (c == '>' && run >= 2) {
                    endOfMarkup()
                } else {
                    0
                }
            }

            INSTRUCTION -> {
                run = if (c == '?') {
                    1
                } else if (c == '>' && run == 1) {
                    endOfMarkup()
                } else {
                    0
                }
            }

            TAG -> {
                inTag(c)
            }
        }
    }

    private fun endOfMarkup(): Int {
        state = TEXT
        return 0
    }

    private fun inTag(c: Char) {
        if (quote != '\u0000') {
            if (c == quote) quote = '\u0000'
            return
        }
        when (c) {
            '"', '\'' -> {
                quote = c
            }

            '>' -> {
                state = TEXT
            }

            '=' -> {
                if (++attributes > limit) {
                    throw SvgParseException(SvgParseException.Reason.TOO_COMPLEX, "One element has more attributes than can be read.")
                }
            }
        }
    }

    private companion object {
        const val TEXT = 0
        const val OPENING = 1
        const val DECLARATION = 2
        const val COMMENT = 3
        const val CDATA = 4
        const val INSTRUCTION = 5
        const val TAG = 6
    }
}
