package org.kineograph.media.svg

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.util.zip.GZIPInputStream

/**
 * Reads SVG files. Android has no SVG renderer of its own, and the available libraries cannot animate, so this package is its own
 * implementation. It is independent of the UI, so that anything that has SVG text can use it.
 *
 * Every entry point throws [SvgParseException], and nothing else, on bad input. Scripts never run and external references are never
 * fetched: this code has no way to do either, and [SvgDocument.warnings] says what was skipped.
 */
public object SvgParser {
    /** Reads plain SVG or gzip-compressed `.svgz`, told apart by the file's first bytes rather than its name. */
    public fun parse(input: InputStream, limits: SvgLimits = SvgLimits.forDevice()): SvgDocument =
        guarded {
            val bytes =
                try {
                    readAll(input, limits.maxInputBytes)
                } catch (e: IOException) {
                    throw SvgParseException(SvgParseException.Reason.READ_FAILED, "The file could not be read.", e)
                }
            parseBytes(bytes, limits)
        }

    public fun parse(bytes: ByteArray, limits: SvgLimits = SvgLimits.forDevice()): SvgDocument = guarded { parseBytes(bytes, limits) }

    /** Reads SVG held as text. A string has no encoding question, so a mismatched XML declaration inside it cannot matter. */
    public fun parse(text: String, limits: SvgLimits = SvgLimits.forDevice()): SvgDocument =
        guarded {
            val trimmed = text.trimStart('\uFEFF', ' ', '\t', '\n', '\r')
            if (trimmed.isEmpty()) throw SvgParseException(SvgParseException.Reason.EMPTY, "The text is empty.")
            buildDocument(limits) { parser -> parser.setInput(StringReader(trimmed)) }
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

    private fun parseBytes(bytes: ByteArray, limits: SvgLimits): SvgDocument {
        if (bytes.isEmpty()) throw SvgParseException(SvgParseException.Reason.EMPTY, "The file is empty.")
        val plain = if (isGzip(bytes)) inflate(bytes, limits.maxInputBytes) else bytes
        var start = 0
        // Whitespace before an XML declaration is a parse error, and an easy one to make when generating SVG as a string.
        while (start < plain.size && plain[start].toInt().toChar().let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) start++
        if (start == plain.size) throw SvgParseException(SvgParseException.Reason.EMPTY, "The file holds only whitespace.")
        return buildDocument(limits) { parser -> parser.setInput(ByteArrayInputStream(plain, start, plain.size - start), null) }
    }

    private fun isGzip(bytes: ByteArray): Boolean = bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()

    /** Stops at the limit instead of trusting the size in the header, since a compression bomb is a small file that lies. */
    private fun inflate(bytes: ByteArray, limit: Long): ByteArray =
        try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { readAll(it, limit) }
        } catch (e: IOException) {
            // The bytes are already in memory here, so any failure to read them can only mean the data itself is bad.
            throw SvgParseException(SvgParseException.Reason.CORRUPT_COMPRESSION, "The compressed data is damaged.", e)
        }

    private fun readAll(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total >
                limit
            ) {
                throw SvgParseException(SvgParseException.Reason.TOO_LARGE, "The file is too large for this device's memory.")
            }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun buildDocument(limits: SvgLimits, setInput: (XmlPullParser) -> Unit): SvgDocument {
        val parser = Xml.newPullParser()
        // Doctype processing stays off, so an entity can only ever expand one level, which defeats "billion laughs" files. Namespaces
        // are handled in the reader instead, which can be forgiving about undeclared prefixes.
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(parser)
        val warnings = LinkedHashSet<SvgWarning>()
        val root = SvgXmlReader(limits, warnings).read(parser)
        val built = SvgModelBuilder(root, limits, warnings, ::parseNested).build()
        return SvgDocument(built.model, built.animation, warnings.toSet())
    }

    private fun parseNested(bytes: ByteArray, limits: SvgLimits): SvgDocument? =
        try {
            guarded { parseBytes(bytes, limits) }
        } catch (_: SvgParseException) {
            null
        }
}
