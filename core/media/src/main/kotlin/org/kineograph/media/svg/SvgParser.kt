package org.kineograph.media.svg

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Reads SVG files. Android has no SVG renderer of its own, and the available libraries cannot animate, so this package is its own
 * implementation. It is independent of the UI, so that anything that has SVG text can use it.
 *
 * Every entry point throws [SvgParseException], and nothing else, on bad input. Scripts never run and external references are never
 * fetched: this code has no way to do either, and [SvgDocument.warnings] says what was skipped.
 */
public object SvgParser {
    /**
     * Reads plain SVG or gzip-compressed `.svgz`, told apart by the file's first bytes rather than its name.
     *
     * @param shouldStop asked often. Returning true stops it soon after with what it has, for when nobody is waiting for the answer any more.
     */
    public fun parse(input: InputStream, limits: SvgLimits = SvgLimits.forDevice(), shouldStop: (() -> Boolean)? = null): SvgDocument =
        guarded {
            val bytes =
                try {
                    readAll(input, limits.maxInputBytes)
                } catch (e: IOException) {
                    throw SvgParseException(SvgParseException.Reason.READ_FAILED, "The file could not be read.", e)
                }
            parseBytes(bytes, limits, ParseWork(limits, shouldStop))
        }

    public fun parse(bytes: ByteArray, limits: SvgLimits = SvgLimits.forDevice()): SvgDocument =
        guarded { parseBytes(bytes, limits, ParseWork(limits)) }

    /** Reads SVG held as text. A string has no encoding question, so a mismatched XML declaration inside it cannot matter. */
    public fun parse(text: String, limits: SvgLimits = SvgLimits.forDevice()): SvgDocument =
        guarded {
            val trimmed = text.trimStart('\uFEFF', ' ', '\t', '\n', '\r')
            if (trimmed.isEmpty()) throw SvgParseException(SvgParseException.Reason.EMPTY, "The text is empty.")
            buildDocument(limits, ParseWork(limits)) { parser -> parser.setInput(XmlInput.reader(trimmed, limits)) }
        }

    private inline fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (e: SvgParseException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw SvgParseException(SvgParseException.Reason.TOO_LARGE, "The file is too large for this device's memory.", e)
        } catch (e: StackOverflowError) {
            throw SvgParseException(SvgParseException.Reason.TOO_LARGE, "The file is too deeply nested for this device.", e)
        } catch (e: Exception) {
            // android.util.Xml reports trouble through several exception types, and a bug in the code below must not crash an import.
            throw SvgParseException(SvgParseException.Reason.MALFORMED, "The file could not be read as XML: ${e.message}", e)
        }

    private fun parseBytes(bytes: ByteArray, limits: SvgLimits, work: ParseWork): SvgDocument {
        if (bytes.isEmpty()) throw SvgParseException(SvgParseException.Reason.EMPTY, "The file is empty.")
        val plain =
            if (isGzip(bytes)) {
                inflate(bytes, limits.maxInputBytes, work)
            } else {
                bytes.also { take(it.size, work) }
            }
        var start = 0
        // Whitespace before an XML declaration is a parse error, and an easy one to make when generating SVG as a string.
        while (start < plain.size && plain[start].toInt().toChar().let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) start++
        if (start == plain.size) throw SvgParseException(SvgParseException.Reason.EMPTY, "The file holds only whitespace.")
        return buildDocument(limits, work) { parser -> parser.setInput(XmlInput.reader(plain, start, limits)) }
    }

    private fun isGzip(bytes: ByteArray): Boolean = bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()

    /** Stops at the limit instead of trusting the size in the header, since a compression bomb is a small file that lies. */
    private fun inflate(bytes: ByteArray, limit: Long, work: ParseWork): ByteArray =
        try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { readAll(it, limit, work) }
        } catch (e: IOException) {
            // The bytes are already in memory here, so any failure to read them can only mean the data itself is bad.
            throw SvgParseException(SvgParseException.Reason.CORRUPT_COMPRESSION, "The compressed data is damaged.", e)
        }

    /**
     * In total, not for each picture. Otherwise each of many small pictures inside a file could unpack to the full size, again and again.
     * It counts what has been unpacked even if that goes on to fail, since unpacking it took just as long.
     */
    private fun take(count: Int, work: ParseWork) {
        if (!work.takeBytes(count)) throw SvgParseException(SvgParseException.Reason.TOO_LARGE, "The file unpacks to too much.")
    }

    /** Gathers pieces and joins them at the end. A buffer that doubles would hold three times the file at its worst moment. */
    private fun readAll(input: InputStream, limit: Long, work: ParseWork? = null): ByteArray {
        val pieces = ArrayList<ByteArray>()
        var total = 0L
        var piece = ByteArray(READ_PIECE_BYTES)
        var used = 0
        while (true) {
            val count = input.read(piece, used, piece.size - used)
            if (count < 0) break
            used += count
            total += count
            if (total > limit) {
                // A file of many pictures that each unpack to too much must pay for each attempt, or it gets as many as it likes.
                work?.let { take(count, it) }
                throw SvgParseException(SvgParseException.Reason.TOO_LARGE, "The file is too large for this device's memory.")
            }
            work?.let { take(count, it) }
            if (used == piece.size) {
                pieces += piece
                piece = ByteArray(READ_PIECE_BYTES)
                used = 0
            }
        }
        val out = ByteArray(total.toInt())
        var position = 0
        for (full in pieces) {
            full.copyInto(out, position)
            position += full.size
        }
        piece.copyInto(out, position, 0, used)
        return out
    }

    private fun buildDocument(limits: SvgLimits, work: ParseWork, setInput: (XmlPullParser) -> Unit): SvgDocument {
        val parser = Xml.newPullParser()
        // Doctype processing stays off, so an entity can only ever expand one level, which defeats "billion laughs" files. Namespaces
        // are handled in the reader instead, which can be forgiving about undeclared prefixes.
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(parser)
        val warnings = LinkedHashSet<SvgWarning>()
        val root = SvgXmlReader(limits, warnings, work).read(parser)
        val built = SvgModelBuilder(root, limits, warnings, work, ::parseNested).build()
        if (work.isStopped) warnings += SvgWarning.LIMIT_REACHED
        return SvgDocument(built.model, built.animation, warnings.toSet())
    }

    private fun parseNested(bytes: ByteArray, limits: SvgLimits, work: ParseWork, warnings: MutableSet<SvgWarning>): SvgDocument? =
        try {
            guarded { parseBytes(bytes, limits, work) }
        } catch (e: SvgParseException) {
            val isTooMuch = e.reason == SvgParseException.Reason.TOO_LARGE || e.reason == SvgParseException.Reason.TOO_COMPLEX
            warnings += if (isTooMuch) SvgWarning.LIMIT_REACHED else SvgWarning.INVALID_CONTENT_IGNORED
            null
        }

    private const val READ_PIECE_BYTES = 1 shl 20
}
