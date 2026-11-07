package org.kineograph.engine.vector

import org.kineograph.engine.brush.BrushSpec
import org.kineograph.engine.brush.CurvePoint
import org.kineograph.engine.brush.PressureCurve
import org.kineograph.engine.brush.StrokeBlend
import org.kineograph.engine.brush.StrokeEngine
import org.kineograph.engine.brush.StrokeRecording
import org.kineograph.engine.brush.TipShape
import org.kineograph.engine.selection.SelectionTransform
import org.kineograph.model.IntRect
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/*
 * The vector data file. All fixed values are little-endian. A varint is unsigned LEB128, at most five
 * bytes unless stated, and a zigzag is a zigzag-encoded varint.
 *
 *     file    = header record* footer
 *     header  = "KGVS", u8 formatVersion, 3 reserved bytes
 *     record  = u8 kind, u8 flags, i64 id, zigzag anchorX, zigzag anchorY, varint payloadBytes, payload
 *     footer  = "KGVE", u32 bodyBytes, u32 recordCount, u32 gapCount, u32 gapReasons, i32 cel left, top, right, bottom, 4 reserved bytes,
 *               u32 CRC of the header and all records, u32 CRC of the footer so far
 *
 * Every position in a record is stored relative to its anchor, and sits on today's canvas at
 *     stored + anchor + (cel's top left now - cel's top left in the footer)
 * so moving a cel needs no rewrite.
 *
 * A stroke's samples are the exact floats the engine was fed, kept as their bits. Rounding them, even to a thousandth of a pixel, changes
 * which stamp is the last one and which size of tip a stamp picks, and that shows: docs/decisions.md has the numbers. Each value is a
 * zigzag of how far its bits are from what came before, the second difference for x and y and the first for the rest, starting from 0.
 */

/** Why a file cannot be used. */
public enum class VectorDamage {
    /** It is torn, or holds something this format cannot. */
    DAMAGED,

    /** It is larger than any file this app writes. */
    TOO_LARGE,

    /** A later version of the app wrote it. */
    NEWER,

    /** It does not belong to this cel, because the sizes differ. */
    MISMATCH,
}

/** A file or record cannot be used. Nothing about it can be trusted, and nothing is salvaged. */
public class VectorFormatException(public val why: VectorDamage, message: String) : Exception(message)

/** Why some pixels have no description. */
public enum class LostReason(internal val code: Int, internal val gapBit: Int) {
    /** Nothing recorded it: an edit that did not say what it did, or a stroke that landed before its recording arrived. */
    UNRECORDED(1, VectorCodec.GAP_UNRECORDED),

    /** The stroke went past what a recording can hold, or there was no memory for it. */
    TOO_LONG(2, VectorCodec.GAP_TOO_LONG),

    /** The stroke held numbers the format cannot store. */
    BAD_INPUT(3, VectorCodec.GAP_BAD_INPUT),
}

/**
 * One stroke as read back. [samples] holds [count] samples of [stride] floats, laid out as in [StrokeRecording], which are the very floats
 * the stroke's engine was fed. Feeding them to a new [StrokeEngine] with this [seed] places the very same stamps, and those then go
 * [offsetX] and [offsetY] further to be where they are on today's canvas. The two are apart because the canvas can move by whole pixels
 * while adding that to a float would round it. [bounds] and [clip] are already in today's canvas coordinates.
 */
public class VectorStroke(
    public val id: Long,
    public val seed: Long,
    public val spec: BrushSpec,
    public val colorArgb: Int,
    public val engineRevision: Int,
    public val isFinger: Boolean,
    public val durationMs: Long,
    public val lengthPx: Float,
    public val bounds: IntRect,
    public val clip: IntRect?,
    public val samples: FloatArray,
    public val count: Int,
    public val stride: Int,
    public val offsetX: Int,
    public val offsetY: Int,
)

/** A record of pixels that the strokes do not describe. Until it is undone, a cel holding one cannot be worked out from its strokes. */
public sealed interface VectorGap {
    public val id: Long

    /** The bit this adds to the footer's reasons. */
    public val reasonBit: Int
}

/** A flood fill, kept in full because a drawing made now cannot be given it later. [rect] is the area it changed. */
public class VectorFill(
    override val id: Long,
    public val x: Int,
    public val y: Int,
    public val colorArgb: Int,
    public val tolerance: Int,
    public val gapClose: Int,
    public val expand: Int,
    public val sampleAllLayers: Boolean,
    public val behind: Boolean,
    public val rect: IntRect,
) : VectorGap {
    override val reasonBit: Int get() = VectorCodec.GAP_FILL
}

/**
 * Pixels lifted with the lasso and then moved or deleted. [points] holds [pointCount] outline points as x then y, and a count of 0 means
 * the outline was too long to keep. A deleted lift has an identity transform.
 */
public class VectorLift(
    override val id: Long,
    public val deleted: Boolean,
    public val transform: SelectionTransform,
    public val points: FloatArray,
    public val pointCount: Int,
) : VectorGap {
    override val reasonBit: Int get() = VectorCodec.GAP_LIFT
}

/** Pixels in [rect] changed and nobody knows how. */
public class VectorLost(override val id: Long, public val reason: LostReason, public val rect: IntRect) : VectorGap {
    override val reasonBit: Int get() = reason.gapBit
}

/** A kind of record from a later version of the format, which is not understood but keeps its place. */
public class VectorUnknown(override val id: Long, public val kind: Int) : VectorGap {
    override val reasonBit: Int get() = VectorCodec.GAP_UNKNOWN
}

/** Receives a file's records in paint order. */
public interface VectorVisitor {
    public fun onStroke(stroke: VectorStroke)

    public fun onGap(gap: VectorGap)
}

/** What a file's footer says. */
public class VectorFooter(
    public val bodyBytes: Long,
    public val recordCount: Long,
    public val gapCount: Long,
    public val gapReasons: Int,
    public val celBounds: IntRect,
    public val bodyCrc: Long,
)

/**
 * Writes one file to [out], which should buffer: records go out as they are added, so the writer holds one at a time. Nothing here checks
 * how large the file becomes, and [out] holds a useless file if anything throws. Used by one thread.
 */
public class VectorFileEncoder(private val out: OutputStream, private val celBounds: IntRect) {
    private val crc = CRC32()
    private val head = VectorBuffer(VectorCodec.HEADER_BYTES + VectorCodec.FOOTER_BYTES)
    private val payload = VectorBuffer(INITIAL_PAYLOAD_BYTES)
    private val sampleBytes = VectorBuffer(INITIAL_PAYLOAD_BYTES)

    public var bodyBytes: Long = 0L
        private set
    public var recordCount: Long = 0L
        private set
    public var gapCount: Long = 0L
        private set
    public var gapReasons: Int = 0
        private set

    init {
        head.ascii(VectorCodec.HEADER_MAGIC)
        head.u8(VectorCodec.FORMAT_VERSION)
        repeat(VectorCodec.HEADER_RESERVED) { head.u8(0) }
        emit(head, updateCrc = true)
    }

    /**
     * Adds a stroke. [bounds] is everything its stamps reached, and [clip] is the surface, if the stroke ran past it. Returns false if a
     * [VectorLost] went in its place, because the stroke cannot be stored: the recording gave up or is empty, or holds numbers the format
     * has no room for.
     */
    public fun addStroke(id: Long, recording: StrokeRecording, colorArgb: Int, bounds: IntRect, clip: IntRect?): Boolean {
        if (recording.gaveUp != null) return addLost(id, LostReason.TOO_LONG, bounds)
        if (recording.count == 0) return addLost(id, LostReason.UNRECORDED, bounds)
        val isStorable = VectorCodec.isStorable(recording.spec) && isOnCanvas(bounds) && (clip == null || isOnCanvas(clip))
        val length = if (isStorable) encodeSamples(recording, sampleBytes) else null
        if (length == null) return addLost(id, LostReason.BAD_INPUT, bounds)

        payload.clear()
        payload.i64(recording.seed)
        payload.i32(colorArgb)
        payload.varint(StrokeEngine.REVISION.toLong())
        writeRect(payload, bounds, 0, 0)
        payload.f32(length)
        payload.varint(recording.durationMs)
        if (clip != null) writeRect(payload, clip, 0, 0)
        writeSpec(payload, recording.spec)
        payload.varint(recording.count.toLong())
        payload.append(sampleBytes)

        var flags = 0
        if (recording.stride == StrokeRecording.STRIDE_WITH_TILT) flags = flags or VectorCodec.STROKE_HAS_TILT
        if (clip != null) flags = flags or VectorCodec.STROKE_HAS_CLIP
        if (recording.isFinger) flags = flags or VectorCodec.STROKE_FINGER
        // The samples have to stay the floats they were, so a stroke's anchor starts at nothing and only counts how far its cel has moved.
        writeRecord(VectorCodec.KIND_STROKE, flags, id, 0, 0, payload.bytes, 0, payload.size, 0)
        return true
    }

    /** Adds a record of pixels that nobody can describe. */
    public fun addLost(lost: VectorLost) {
        addLost(lost.id, lost.reason, lost.rect)
    }

    /**
     * Copies a record as bytes, with a new anchor if its cel has moved since. This is what keeps stroke ids across a duplicate, and what
     * lets a kind from a later version survive this one. [gapBit] says what it adds to the footer's reasons, and is 0 for a stroke.
     */
    public fun addRaw(
        kind: Int,
        flags: Int,
        id: Long,
        anchorX: Int,
        anchorY: Int,
        bytes: ByteArray,
        offset: Int,
        length: Int,
        gapBit: Int,
    ) {
        writeRecord(kind, flags, id, anchorX, anchorY, bytes, offset, length, gapBit)
    }

    /** Writes the footer. Nothing can be added after this. */
    public fun finish() {
        head.clear()
        head.ascii(VectorCodec.FOOTER_MAGIC)
        head.i32(bodyBytes.toInt())
        head.i32(recordCount.toInt())
        head.i32(gapCount.toInt())
        head.i32(gapReasons)
        head.i32(celBounds.left)
        head.i32(celBounds.top)
        head.i32(celBounds.right)
        head.i32(celBounds.bottom)
        head.i32(0)
        head.i32(crc.value.toInt())
        crc.reset()
        crc.update(head.bytes, 0, head.size)
        head.i32(crc.value.toInt())
        emit(head, updateCrc = false)
    }

    private fun addLost(id: Long, reason: LostReason, rect: IntRect): Boolean {
        val kept = rect.onCanvas()
        payload.clear()
        payload.u8(reason.code)
        writeRect(payload, kept, kept.left, kept.top)
        writeRecord(VectorCodec.KIND_LOST, 0, id, kept.left, kept.top, payload.bytes, 0, payload.size, reason.gapBit)
        return false
    }

    /** Adds a flood fill. One that holds numbers the format cannot store becomes a [VectorLost]. */
    public fun addFill(fill: VectorFill) {
        if (!VectorCodec.isOnCanvas(fill.x.toLong()) || !VectorCodec.isOnCanvas(fill.y.toLong()) || !isOnCanvas(fill.rect) ||
            fill.tolerance < 0 || fill.gapClose < 0 || fill.expand < 0
        ) {
            addLost(fill.id, LostReason.BAD_INPUT, fill.rect)
            return
        }
        payload.clear()
        payload.zigzag(0L)
        payload.zigzag(0L)
        payload.i32(fill.colorArgb)
        payload.varint(fill.tolerance.toLong())
        payload.varint(fill.gapClose.toLong())
        payload.varint(fill.expand.toLong())
        writeRect(payload, fill.rect, fill.x, fill.y)
        var flags = 0
        if (fill.sampleAllLayers) flags = flags or VectorCodec.FILL_ALL_LAYERS
        if (fill.behind) flags = flags or VectorCodec.FILL_BEHIND
        writeRecord(VectorCodec.KIND_FILL, flags, fill.id, fill.x, fill.y, payload.bytes, 0, payload.size, VectorCodec.GAP_FILL)
    }

    /** Adds a lasso's move or delete. One that holds numbers the format cannot store becomes a [VectorLost]. */
    public fun addLift(lift: VectorLift) {
        val transform = lift.transform
        val source = transform.source
        val isFinite =
            transform.translateX.isFinite() && transform.translateY.isFinite() && transform.scale.isFinite() &&
                transform.rotation.isFinite()
        if (!isFinite || !isOnCanvas(source)) {
            addLost(lift.id, LostReason.BAD_INPUT, source)
            return
        }
        val pointCount = if (lift.pointCount in 0..VectorCodec.MAX_OUTLINE_POINTS && pointsAreStorable(lift)) lift.pointCount else 0
        payload.clear()
        writeRect(payload, source, source.left, source.top)
        payload.f32(transform.translateX)
        payload.f32(transform.translateY)
        payload.f32(transform.scale)
        payload.f32(transform.rotation)
        payload.varint(pointCount.toLong())
        var lastX = 0L
        var lastY = 0L
        for (i in 0 until pointCount) {
            val x = VectorCodec.quantize(lift.points[i * 2].toDouble() - source.left, VectorCodec.POSITION_SCALE)
            val y = VectorCodec.quantize(lift.points[i * 2 + 1].toDouble() - source.top, VectorCodec.POSITION_SCALE)
            payload.zigzag(x - lastX)
            payload.zigzag(y - lastY)
            lastX = x
            lastY = y
        }
        val flags = if (lift.deleted) VectorCodec.LIFT_DELETED else 0
        writeRecord(VectorCodec.KIND_LIFT, flags, lift.id, source.left, source.top, payload.bytes, 0, payload.size, VectorCodec.GAP_LIFT)
    }

    private fun pointsAreStorable(lift: VectorLift): Boolean {
        for (i in 0 until lift.pointCount * 2) {
            val value = lift.points.getOrNull(i) ?: return false
            if (!VectorCodec.isOnCanvas(value)) return false
        }
        return true
    }

    private fun writeRecord(
        kind: Int,
        flags: Int,
        id: Long,
        anchorX: Int,
        anchorY: Int,
        bytes: ByteArray,
        offset: Int,
        length: Int,
        gapBit: Int,
    ) {
        head.clear()
        head.u8(kind)
        head.u8(flags)
        head.i64(id)
        head.zigzag(anchorX.toLong())
        head.zigzag(anchorY.toLong())
        head.varint(length.toLong())
        bodyBytes += head.size + length.toLong()
        recordCount++
        if (kind != VectorCodec.KIND_STROKE) {
            gapCount++
            gapReasons = gapReasons or gapBit
        }
        emit(head, updateCrc = true)
        crc.update(bytes, offset, length)
        out.write(bytes, offset, length)
    }

    private fun emit(buffer: VectorBuffer, updateCrc: Boolean) {
        if (updateCrc) crc.update(buffer.bytes, 0, buffer.size)
        out.write(buffer.bytes, 0, buffer.size)
    }

    private fun writeRect(to: VectorBuffer, rect: IntRect, anchorX: Int, anchorY: Int) {
        to.zigzag(rect.left.toLong() - anchorX)
        to.zigzag(rect.top.toLong() - anchorY)
        to.zigzag(rect.right.toLong() - anchorX)
        to.zigzag(rect.bottom.toLong() - anchorY)
    }

    private fun writeSpec(to: VectorBuffer, spec: BrushSpec) {
        val id = spec.id.toByteArray(Charsets.UTF_8)
        to.u8(id.size)
        to.append(id, 0, id.size)
        to.u8(spec.tip.ordinal)
        var flags = 0
        if (spec.antiAlias) flags = flags or VectorCodec.SPEC_ANTI_ALIAS
        if (spec.isEraser) flags = flags or VectorCodec.SPEC_ERASE
        to.u8(flags)
        to.f32(spec.sizePx)
        to.f32(spec.spacing)
        to.f32(spec.opacity)
        to.f32(spec.flow)
        to.f32(spec.hardness)
        to.f32(spec.roundness)
        to.f32(spec.tipAngleDegrees)
        to.f32(spec.tiltShading)
        to.f32(spec.sizeJitter)
        to.f32(spec.angleJitterDegrees)
        to.f32(spec.scatter)
        to.f32(spec.grain)
        writeCurve(to, spec.sizeCurve)
        writeCurve(to, spec.flowCurve)
    }

    private fun writeCurve(to: VectorBuffer, curve: PressureCurve) {
        to.u8(curve.points.size)
        for (point in curve.points) {
            to.f32(point.input)
            to.f32(point.output)
        }
    }

    private fun isOnCanvas(rect: IntRect): Boolean =
        VectorCodec.isOnCanvas(rect.left.toLong()) &&
            VectorCodec.isOnCanvas(rect.top.toLong()) &&
            VectorCodec.isOnCanvas(rect.right.toLong()) &&
            VectorCodec.isOnCanvas(rect.bottom.toLong())

    private fun IntRect.onCanvas(): IntRect {
        val limit = VectorCodec.MAX_COORDINATE.toInt()
        return IntRect(
            left.coerceIn(-limit, limit),
            top.coerceIn(-limit, limit),
            right.coerceIn(-limit, limit),
            bottom.coerceIn(-limit, limit),
        )
    }

    private companion object {
        const val INITIAL_PAYLOAD_BYTES = 1024
    }
}

/** Reading and the rules both ends share. The record classes above are what most callers want. */
public object VectorCodec {
    public const val FORMAT_VERSION: Int = 1
    public const val HEADER_BYTES: Int = 8
    public const val FOOTER_BYTES: Int = 48

    /** No file this app writes is larger, and none is read that is. */
    public const val MAX_FILE_BYTES: Long = 8L * 1024 * 1024

    /** The smallest a record can be: a head with one-byte varints and an empty payload. */
    public const val MIN_RECORD_BYTES: Int = 13

    /** Far more than the largest stroke, whose 65,536 samples of five values take at most 1.6 MB. */
    public const val MAX_PAYLOAD_BYTES: Long = 2L * 1024 * 1024

    /** Outline points of a lift are stored per pixel, so to one screen pixel at the deepest zoom. Nothing replays from those. */
    public const val POSITION_SCALE: Double = 64.0

    /** No position is further than this from the canvas origin. */
    public const val MAX_COORDINATE: Long = 1L shl 23

    public const val KIND_STROKE: Int = 1
    public const val KIND_FILL: Int = 2
    public const val KIND_LIFT: Int = 3
    public const val KIND_LOST: Int = 4

    public const val GAP_FILL: Int = 1
    public const val GAP_LIFT: Int = 2
    public const val GAP_UNRECORDED: Int = 4
    public const val GAP_TOO_LONG: Int = 8
    public const val GAP_BAD_INPUT: Int = 16
    public const val GAP_UNKNOWN: Int = 32

    public const val MAX_OUTLINE_POINTS: Int = 4096

    internal const val HEADER_MAGIC = "KGVS"
    internal const val FOOTER_MAGIC = "KGVE"
    internal const val HEADER_RESERVED = 3
    internal const val STROKE_HAS_TILT = 1
    internal const val STROKE_HAS_CLIP = 2
    internal const val STROKE_FINGER = 4
    internal const val FILL_ALL_LAYERS = 1
    internal const val FILL_BEHIND = 2
    internal const val LIFT_DELETED = 1
    internal const val SPEC_ANTI_ALIAS = 1
    internal const val SPEC_ERASE = 2

    private const val MAX_ID_BYTES = 32
    private const val MAX_CURVE_POINTS = 16
    private const val MAX_SIZE_PX = 1024f
    private const val MAX_SPACING = 16f
    private const val MAX_SIZE_JITTER = 4f
    private const val MAX_SCATTER = 8f
    private const val MAX_ANGLE_DEGREES = 360f
    private const val MAX_DURATION_BYTES = 10

    /** A position relative to an anchor that is itself on the canvas, per pixel. */
    private const val MAX_STORED_POSITION = (MAX_COORDINATE * 2 * 64)

    /**
     * Reads a whole file, checking all of it before [visitor] sees anything. Positions come out as they are on a cel at [celBounds] today.
     * If that is null they stay where they were when the file was written. Throws [VectorFormatException] for anything wrong with the file,
     * and nothing else, whatever it holds.
     */
    public fun decodeFile(bytes: ByteArray, celBounds: IntRect?, visitor: VectorVisitor?): VectorFooter {
        if (bytes.size.toLong() > MAX_FILE_BYTES) refuse(VectorDamage.TOO_LARGE, "Larger than any file this app writes")
        val footer = readFooter(bytes)
        val shiftX = shiftOf(celBounds?.left, footer.celBounds.left)
        val shiftY = shiftOf(celBounds?.top, footer.celBounds.top)
        if (celBounds != null && (celBounds.width != footer.celBounds.width || celBounds.height != footer.celBounds.height)) {
            refuse(VectorDamage.MISMATCH, "The cel has another size")
        }
        val crc = CRC32()
        crc.update(bytes, 0, HEADER_BYTES + footer.bodyBytes.toInt())
        if (crc.value != footer.bodyCrc) damaged("The records do not match their checksum")
        // Fully read once, so a file that goes wrong late on has shown its reader nothing.
        walk(bytes, footer, shiftX, shiftY, null)
        if (visitor != null) walk(bytes, footer, shiftX, shiftY, visitor)
        return footer
    }

    /** Reads the header and footer, which is all it takes to learn a file's status. [bytes] is the whole file. */
    public fun readFooter(bytes: ByteArray): VectorFooter {
        if (bytes.size < HEADER_BYTES) damaged("Too short for a header")
        val header = VectorInput(bytes, 0, HEADER_BYTES)
        if (!header.matches(HEADER_MAGIC)) damaged("Not a vector file")
        val version = header.u8()
        if (version > FORMAT_VERSION) refuse(VectorDamage.NEWER, "Format version $version")
        if (version < FORMAT_VERSION) damaged("Format version $version")
        repeat(HEADER_RESERVED) { if (header.u8() != 0) damaged("Reserved header bytes are set") }
        if (bytes.size < HEADER_BYTES + FOOTER_BYTES) damaged("Too short for a footer")

        val footerStart = bytes.size - FOOTER_BYTES
        val crc = CRC32()
        crc.update(bytes, footerStart, FOOTER_BYTES - Int.SIZE_BYTES)
        val input = VectorInput(bytes, footerStart, bytes.size)
        if (!input.matches(FOOTER_MAGIC)) damaged("The footer is missing")
        val bodyBytes = input.u32()
        val recordCount = input.u32()
        val gapCount = input.u32()
        val gapReasons = input.i32()
        val left = input.i32()
        val top = input.i32()
        val right = input.i32()
        val bottom = input.i32()
        if (input.i32() != 0) damaged("Reserved footer bytes are set")
        val bodyCrc = input.u32()
        if (input.u32() != crc.value) damaged("The footer does not match its checksum")
        if (HEADER_BYTES + bodyBytes + FOOTER_BYTES != bytes.size.toLong()) damaged("The length does not match the footer")
        if (recordCount * MIN_RECORD_BYTES > bodyBytes) damaged("More records than could fit")
        if (gapCount > recordCount) damaged("More gaps than records")
        for (edge in intArrayOf(left, top, right, bottom)) {
            if (!isOnCanvas(edge.toLong())) damaged("The cel is too far away")
        }
        return VectorFooter(bodyBytes, recordCount, gapCount, gapReasons, IntRect(left, top, right, bottom), bodyCrc)
    }

    /** What a record is, read from the front of it. */
    private class RecordHead(val kind: Int, val flags: Int, val id: Long, val anchorX: Int, val anchorY: Int)

    private fun walk(bytes: ByteArray, footer: VectorFooter, shiftX: Int, shiftY: Int, visitor: VectorVisitor?) {
        val input = VectorInput(bytes, HEADER_BYTES, HEADER_BYTES + footer.bodyBytes.toInt())
        var gaps = 0L
        var reasons = 0
        for (i in 0 until footer.recordCount) {
            val kind = input.u8()
            val flags = input.u8()
            val id = input.i64()
            if (id == 0L) damaged("A record with no id")
            val anchorX = readAnchor(input)
            val anchorY = readAnchor(input)
            val payloadBytes = input.varint()
            if (payloadBytes > MAX_PAYLOAD_BYTES || payloadBytes > input.remaining) damaged("A record is longer than the file")
            val start = input.position
            val end = start + payloadBytes.toInt()
            val head = RecordHead(kind, flags, id, anchorX, anchorY)
            val record = VectorInput(bytes, start, end)
            if (kind == KIND_STROKE) {
                val stroke = readStroke(record, head, shiftX, shiftY)
                visitor?.onStroke(stroke)
            } else {
                val gap = readGap(record, head, shiftX, shiftY)
                gaps++
                reasons = reasons or gap.reasonBit
                visitor?.onGap(gap)
            }
            if (kind in KIND_STROKE..KIND_LOST && record.remaining != 0) damaged("A record does not end where its length says")
            input.position = end
        }
        if (input.remaining != 0) damaged("There are bytes after the last record")
        if (gaps != footer.gapCount || reasons != footer.gapReasons) damaged("The footer miscounts the gaps")
    }

    private fun readAnchor(input: VectorInput): Int {
        val anchor = input.zigzag()
        if (!isOnCanvas(anchor)) damaged("A record is too far away")
        return anchor.toInt()
    }

    private fun readStroke(input: VectorInput, head: RecordHead, shiftX: Int, shiftY: Int): VectorStroke {
        if (head.flags and (STROKE_HAS_TILT or STROKE_HAS_CLIP or STROKE_FINGER).inv() != 0) damaged("Unknown stroke flags")
        val seed = input.i64()
        val color = input.i32()
        val revision = input.varint()
        if (revision > Int.MAX_VALUE) damaged("An impossible engine revision")
        val bounds = readRect(input, head, shiftX, shiftY)
        val length = input.f32()
        if (!length.isFinite() || length < 0f) damaged("An impossible length")
        val duration = input.varint(MAX_DURATION_BYTES)
        if (duration < 0L) damaged("An impossible duration")
        val clip = if (head.flags and STROKE_HAS_CLIP != 0) readRect(input, head, shiftX, shiftY) else null
        val spec = readSpec(input)
        val count = input.varint()
        if (count < 1 || count > StrokeRecording.MAX_SAMPLES) damaged("An impossible number of samples")
        val stride = if (head.flags and STROKE_HAS_TILT != 0) StrokeRecording.STRIDE_WITH_TILT else StrokeRecording.STRIDE_PLAIN
        if (count * stride > input.remaining) damaged("More samples than could fit")
        val samples = FloatArray(count.toInt() * stride)
        readSamples(input, samples, stride)
        return VectorStroke(
            head.id,
            seed,
            spec,
            color,
            revision.toInt(),
            head.flags and STROKE_FINGER != 0,
            duration,
            length,
            bounds,
            clip,
            samples,
            count.toInt(),
            stride,
            head.anchorX + shiftX,
            head.anchorY + shiftY,
        )
    }

    private fun readSamples(input: VectorInput, out: FloatArray, stride: Int) {
        val last = LongArray(stride)
        var stepX = 0L
        var stepY = 0L
        var at = 0
        while (at < out.size) {
            stepX += input.zigzag()
            stepY += input.zigzag()
            last[0] += stepX
            last[1] += stepY
            for (field in 2 until stride) last[field] += input.zigzag()
            for (field in 0 until stride) {
                // Bits that do not fit a float would make these sums run away on the next sample.
                val bits = last[field]
                if (bits < Int.MIN_VALUE || bits > Int.MAX_VALUE) damaged("A value is not a float")
                val value = Float.fromBits(bits.toInt())
                if (!value.isFinite()) damaged("A value is not a number")
                out[at + field] = value
            }
            if (!isOnCanvas(out[at]) || !isOnCanvas(out[at + 1])) damaged("A sample is too far away")
            at += stride
        }
    }

    private fun readGap(input: VectorInput, head: RecordHead, shiftX: Int, shiftY: Int): VectorGap =
        when (head.kind) {
            KIND_FILL -> readFill(input, head, shiftX, shiftY)
            KIND_LIFT -> readLift(input, head, shiftX, shiftY)
            KIND_LOST -> readLost(input, head, shiftX, shiftY)
            else -> VectorUnknown(head.id, head.kind)
        }

    private fun readFill(input: VectorInput, head: RecordHead, shiftX: Int, shiftY: Int): VectorFill {
        if (head.flags and (FILL_ALL_LAYERS or FILL_BEHIND).inv() != 0) damaged("Unknown fill flags")
        val x = readPosition(input, head.anchorX, shiftX)
        val y = readPosition(input, head.anchorY, shiftY)
        val color = input.i32()
        val tolerance = readCount(input)
        val gapClose = readCount(input)
        val expand = readCount(input)
        val rect = readRect(input, head, shiftX, shiftY)
        return VectorFill(
            head.id,
            x,
            y,
            color,
            tolerance,
            gapClose,
            expand,
            head.flags and FILL_ALL_LAYERS != 0,
            head.flags and FILL_BEHIND != 0,
            rect,
        )
    }

    private fun readLift(input: VectorInput, head: RecordHead, shiftX: Int, shiftY: Int): VectorLift {
        if (head.flags and LIFT_DELETED.inv() != 0) damaged("Unknown lift flags")
        val source = readRect(input, head, shiftX, shiftY)
        val translateX = input.f32()
        val translateY = input.f32()
        val scale = input.f32()
        val rotation = input.f32()
        if (!translateX.isFinite() || !translateY.isFinite() || !scale.isFinite() ||
            !rotation.isFinite()
        ) {
            damaged("An impossible placement")
        }
        val count = input.varint()
        if (count > MAX_OUTLINE_POINTS || count * 2 > input.remaining) damaged("An impossible number of outline points")
        val points = FloatArray(count.toInt() * 2)
        var x = 0L
        var y = 0L
        val baseX = head.anchorX.toDouble() + shiftX
        val baseY = head.anchorY.toDouble() + shiftY
        for (i in 0 until count.toInt()) {
            x += input.zigzag()
            y += input.zigzag()
            if (abs(x) > MAX_STORED_POSITION || abs(y) > MAX_STORED_POSITION) damaged("An outline point is too far away")
            points[i * 2] = (x / POSITION_SCALE + baseX).toFloat()
            points[i * 2 + 1] = (y / POSITION_SCALE + baseY).toFloat()
        }
        return VectorLift(
            head.id,
            head.flags and LIFT_DELETED != 0,
            SelectionTransform(source, translateX, translateY, scale, rotation),
            points,
            count.toInt(),
        )
    }

    private fun readLost(input: VectorInput, head: RecordHead, shiftX: Int, shiftY: Int): VectorLost {
        if (head.flags != 0) damaged("Unknown flags on a lost record")
        val code = input.u8()
        val reason = LostReason.entries.firstOrNull { it.code == code } ?: damaged("An unknown reason")
        return VectorLost(head.id, reason, readRect(input, head, shiftX, shiftY))
    }

    private fun readRect(input: VectorInput, head: RecordHead, shiftX: Int, shiftY: Int): IntRect {
        val left = readPosition(input, head.anchorX, shiftX)
        val top = readPosition(input, head.anchorY, shiftY)
        val right = readPosition(input, head.anchorX, shiftX)
        val bottom = readPosition(input, head.anchorY, shiftY)
        return IntRect(left, top, right, bottom)
    }

    private fun readPosition(input: VectorInput, anchor: Int, shift: Int): Int {
        val value = input.zigzag() + anchor
        if (!isOnCanvas(value)) damaged("A position is too far away")
        return (value + shift).toInt()
    }

    private fun readCount(input: VectorInput): Int {
        val value = input.varint()
        if (value > Int.MAX_VALUE) damaged("An impossible value")
        return value.toInt()
    }

    private fun readSpec(input: VectorInput): BrushSpec {
        val idBytes = input.u8()
        if (idBytes > MAX_ID_BYTES) damaged("A brush name is too long")
        val id = input.utf8(idBytes)
        val tip = TipShape.entries.getOrNull(input.u8()) ?: damaged("An unknown tip")
        val flags = input.u8()
        if (flags and (SPEC_ANTI_ALIAS or SPEC_ERASE).inv() != 0) damaged("Unknown brush flags")
        val sizePx = input.f32()
        val spacing = input.f32()
        val opacity = input.f32()
        val flow = input.f32()
        val hardness = input.f32()
        val roundness = input.f32()
        val tipAngleDegrees = input.f32()
        val tiltShading = input.f32()
        val sizeJitter = input.f32()
        val angleJitterDegrees = input.f32()
        val scatter = input.f32()
        val grain = input.f32()
        val sizeCurve = readCurve(input)
        val flowCurve = readCurve(input)
        val spec =
            BrushSpec(
                id = id,
                tip = tip,
                sizePx = sizePx,
                spacing = spacing,
                opacity = opacity,
                flow = flow,
                hardness = hardness,
                roundness = roundness,
                tipAngleDegrees = tipAngleDegrees,
                sizeCurve = sizeCurve,
                flowCurve = flowCurve,
                tiltShading = tiltShading,
                sizeJitter = sizeJitter,
                angleJitterDegrees = angleJitterDegrees,
                scatter = scatter,
                grain = grain,
                antiAlias = flags and SPEC_ANTI_ALIAS != 0,
                blend = if (flags and SPEC_ERASE != 0) StrokeBlend.ERASE else StrokeBlend.NORMAL,
            )
        if (!isStorable(spec)) damaged("A brush holds impossible values")
        return spec
    }

    private fun readCurve(input: VectorInput): PressureCurve {
        val count = input.u8()
        if (count !in 1..MAX_CURVE_POINTS) damaged("An impossible curve")
        val points =
            List(count) {
                val point = CurvePoint(input.f32(), input.f32())
                if (!point.input.isUnit() || !point.output.isUnit()) damaged("An impossible curve")
                point
            }
        return PressureCurve(points)
    }

    private fun shiftOf(now: Int?, then: Int): Int {
        if (now == null) return 0
        val shift = now.toLong() - then
        if (abs(shift) > MAX_COORDINATE * 2) refuse(VectorDamage.MISMATCH, "The cel is too far from where it was")
        return shift.toInt()
    }

    /** Whether a brush fits the format, so that a reader could take it back. */
    internal fun isStorable(spec: BrushSpec): Boolean =
        spec.id.toByteArray(Charsets.UTF_8).size <= MAX_ID_BYTES &&
            spec.sizePx > 0f &&
            spec.sizePx <= MAX_SIZE_PX &&
            spec.spacing in 0f..MAX_SPACING &&
            spec.opacity.isUnit() &&
            spec.flow.isUnit() &&
            spec.hardness.isUnit() &&
            spec.roundness.isUnit() &&
            spec.tiltShading.isUnit() &&
            spec.grain.isUnit() &&
            spec.sizeJitter in 0f..MAX_SIZE_JITTER &&
            spec.scatter in 0f..MAX_SCATTER &&
            abs(spec.tipAngleDegrees) <= MAX_ANGLE_DEGREES &&
            abs(spec.angleJitterDegrees) <= MAX_ANGLE_DEGREES &&
            curveIsStorable(spec.sizeCurve) &&
            curveIsStorable(spec.flowCurve)

    private fun curveIsStorable(curve: PressureCurve): Boolean =
        curve.points.size in 1..MAX_CURVE_POINTS && curve.points.all { it.input.isUnit() && it.output.isUnit() }

    private fun Float.isUnit(): Boolean = this >= 0f && this <= 1f

    internal fun isOnCanvas(value: Long): Boolean = abs(value) <= MAX_COORDINATE

    internal fun isOnCanvas(value: Float): Boolean = abs(value) <= MAX_COORDINATE.toFloat()

    internal fun quantize(value: Double, scale: Double): Long = (value * scale).roundToLong()

    private fun damaged(message: String): Nothing = refuse(VectorDamage.DAMAGED, message)

    private fun refuse(why: VectorDamage, message: String): Nothing = throw VectorFormatException(why, message)
}

/**
 * Puts a recording's samples in [out], replacing what it held. Returns the length of the path through them, or null if one of them holds
 * something that cannot be stored: anything that is not a number, or a position nowhere near a canvas.
 */
internal fun encodeSamples(recording: StrokeRecording, out: VectorBuffer): Float? {
    out.clear()
    val stride = recording.stride
    val last = LongArray(stride)
    var stepX = 0L
    var stepY = 0L
    var previousX = 0f
    var previousY = 0f
    var length = 0.0
    var written = 0
    var blockIndex = 0
    while (written < recording.count) {
        val block = recording.blockAt(blockIndex++)
        if (block.isEmpty()) return null
        val samples = minOf(recording.count - written, block.size / stride)
        var at = 0
        repeat(samples) {
            val x = block[at]
            val y = block[at + 1]
            if (!VectorCodec.isOnCanvas(x) || !VectorCodec.isOnCanvas(y)) return null
            for (field in 2 until stride) if (!block[at + field].isFinite()) return null
            val nextStepX = x.toRawBits().toLong() - last[0]
            val nextStepY = y.toRawBits().toLong() - last[1]
            out.zigzag(nextStepX - stepX)
            out.zigzag(nextStepY - stepY)
            stepX = nextStepX
            stepY = nextStepY
            last[0] = x.toRawBits().toLong()
            last[1] = y.toRawBits().toLong()
            for (field in 2 until stride) {
                val bits = block[at + field].toRawBits().toLong()
                out.zigzag(bits - last[field])
                last[field] = bits
            }
            if (written > 0) {
                val alongX = (x - previousX).toDouble()
                val alongY = (y - previousY).toDouble()
                length += sqrt(alongX * alongX + alongY * alongY)
            }
            previousX = x
            previousY = y
            written++
            at += stride
        }
    }
    return length.toFloat()
}

/** A growing array of bytes, written the way the format asks. */
internal class VectorBuffer(initialBytes: Int) {
    var bytes = ByteArray(initialBytes)
        private set
    var size = 0
        private set

    fun clear() {
        size = 0
    }

    fun u8(value: Int) {
        ensure(1)
        bytes[size++] = value.toByte()
    }

    fun i32(value: Int) {
        ensure(Int.SIZE_BYTES)
        bytes[size++] = value.toByte()
        bytes[size++] = (value shr 8).toByte()
        bytes[size++] = (value shr 16).toByte()
        bytes[size++] = (value shr 24).toByte()
    }

    fun i64(value: Long) {
        i32(value.toInt())
        i32((value ushr 32).toInt())
    }

    fun f32(value: Float) {
        i32(value.toRawBits())
    }

    fun varint(value: Long) {
        ensure(MAX_LONG_VARINT_BYTES)
        var rest = value
        while (rest and SEVEN_BITS.inv() != 0L) {
            bytes[size++] = ((rest and SEVEN_BITS) or CONTINUES).toByte()
            rest = rest ushr 7
        }
        bytes[size++] = rest.toByte()
    }

    fun zigzag(value: Long) {
        varint((value shl 1) xor (value shr 63))
    }

    fun ascii(text: String) {
        for (char in text) u8(char.code)
    }

    fun append(source: ByteArray, offset: Int, length: Int) {
        ensure(length)
        source.copyInto(bytes, size, offset, offset + length)
        size += length
    }

    fun append(other: VectorBuffer) {
        append(other.bytes, 0, other.size)
    }

    private fun ensure(extra: Int) {
        val needed = size + extra
        if (needed > bytes.size) bytes = bytes.copyOf(maxOf(needed, bytes.size * 2))
    }

    private companion object {
        const val SEVEN_BITS = 0x7FL
        const val CONTINUES = 0x80L
        const val MAX_LONG_VARINT_BYTES = 10
    }
}

/** Reads what [VectorBuffer] wrote, from a part of an array it never goes outside of. Anything wrong throws [VectorFormatException]. */
internal class VectorInput(private val bytes: ByteArray, start: Int, private val end: Int) {
    var position = start

    val remaining: Int get() = end - position

    fun u8(): Int {
        if (position >= end) cutShort()
        return bytes[position++].toInt() and 0xFF
    }

    fun i32(): Int {
        if (remaining < Int.SIZE_BYTES) cutShort()
        val value =
            (bytes[position].toInt() and 0xFF) or
                ((bytes[position + 1].toInt() and 0xFF) shl 8) or
                ((bytes[position + 2].toInt() and 0xFF) shl 16) or
                ((bytes[position + 3].toInt() and 0xFF) shl 24)
        position += Int.SIZE_BYTES
        return value
    }

    fun u32(): Long = i32().toLong() and 0xFFFFFFFFL

    fun i64(): Long {
        val low = u32()
        val high = u32()
        return low or (high shl 32)
    }

    fun f32(): Float = Float.fromBits(i32())

    /** One unsigned value, which must take the fewest bytes it can. */
    fun varint(maxBytes: Int = MAX_VARINT_BYTES): Long {
        var value = 0L
        var shift = 0
        for (i in 0 until maxBytes) {
            val byte = u8()
            value = value or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) {
                if (i > 0 && byte == 0) overlong()
                if (i == LAST_BYTE_OF_LONG && byte > 1) overlong()
                return value
            }
            shift += 7
        }
        overlong()
    }

    fun zigzag(): Long {
        val raw = varint()
        return (raw ushr 1) xor -(raw and 1L)
    }

    fun matches(text: String): Boolean {
        if (remaining < text.length) return false
        for (char in text) if (u8() != char.code) return false
        return true
    }

    fun utf8(length: Int): String {
        if (remaining < length) cutShort()
        val decoder = Charsets.UTF_8.newDecoder()
        val text =
            try {
                decoder.decode(ByteBuffer.wrap(bytes, position, length)).toString()
            } catch (_: CharacterCodingException) {
                throw VectorFormatException(VectorDamage.DAMAGED, "A name is not text")
            }
        position += length
        return text
    }

    private fun cutShort(): Nothing = throw VectorFormatException(VectorDamage.DAMAGED, "A value is cut short")

    private fun overlong(): Nothing = throw VectorFormatException(VectorDamage.DAMAGED, "A number is too long")

    private companion object {
        const val MAX_VARINT_BYTES = 5
        const val LAST_BYTE_OF_LONG = 9
    }
}
