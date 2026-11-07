package org.kineograph.media.svg

import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Receives a path as absolute drawing commands. Several consumers need the path in different forms, so it is walked. */
internal interface PathSink {
    fun moveTo(x: Double, y: Double)

    fun lineTo(x: Double, y: Double)

    fun quadTo(x1: Double, y1: Double, x: Double, y: Double)

    fun cubicTo(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double)

    fun close()

    /** An arc reaches the sink as several curves. Only its end counts as a vertex, which markers care about. */
    fun arcStart() {}

    fun arcEnd() {}
}

/**
 * Path data with every coordinate made absolute, but with its command letters kept. SMIL may interpolate two paths only when
 * they use the same commands, so turning everything into curves here would lose what that check needs.
 */
internal class PathData(val commands: CharArray, val args: DoubleArray) {
    val isEmpty: Boolean get() = commands.isEmpty()

    private var cachedPath: Path? = null

    /** The drawing path, built once because it is by far the slowest part of a static path to prepare. */
    val path: Path
        get() = cachedPath ?: toAndroidPath().also { cachedPath = it }

    private var cachedBounds: Box? = null
    private var boundsKnown = false

    /**
     * Tight bounds, computed from the curves. Path.computeBounds only reports the control points, and gradients and masks measured
     * against those come out visibly wrong on curved shapes.
     */
    val bounds: Box?
        get() {
            if (!boundsKnown) {
                val sink = BoundsSink()
                walk(sink)
                cachedBounds = sink.result()
                boundsKnown = true
            }
            return cachedBounds
        }

    fun hasSameCommands(other: PathData): Boolean = commands.contentEquals(other.commands)

    fun walk(sink: PathSink) {
        var i = 0
        var cx = 0.0
        var cy = 0.0
        var startX = 0.0
        var startY = 0.0
        var controlX = 0.0
        var controlY = 0.0
        var previous = ' '
        for (command in commands) {
            when (command) {
                'M' -> {
                    cx = args[i]
                    cy = args[i + 1]
                    startX = cx
                    startY = cy
                    sink.moveTo(cx, cy)
                    i += 2
                }

                'L' -> {
                    cx = args[i]
                    cy = args[i + 1]
                    sink.lineTo(cx, cy)
                    i += 2
                }

                'H' -> {
                    cx = args[i++]
                    sink.lineTo(cx, cy)
                }

                'V' -> {
                    cy = args[i++]
                    sink.lineTo(cx, cy)
                }

                'C' -> {
                    controlX = args[i + 2]
                    controlY = args[i + 3]
                    sink.cubicTo(args[i], args[i + 1], controlX, controlY, args[i + 4], args[i + 5])
                    cx = args[i + 4]
                    cy = args[i + 5]
                    i += 6
                }

                'S' -> {
                    val smooth = previous == 'C' || previous == 'S'
                    val x1 = if (smooth) 2 * cx - controlX else cx
                    val y1 = if (smooth) 2 * cy - controlY else cy
                    controlX = args[i]
                    controlY = args[i + 1]
                    sink.cubicTo(x1, y1, controlX, controlY, args[i + 2], args[i + 3])
                    cx = args[i + 2]
                    cy = args[i + 3]
                    i += 4
                }

                'Q' -> {
                    controlX = args[i]
                    controlY = args[i + 1]
                    sink.quadTo(controlX, controlY, args[i + 2], args[i + 3])
                    cx = args[i + 2]
                    cy = args[i + 3]
                    i += 4
                }

                'T' -> {
                    val smooth = previous == 'Q' || previous == 'T'
                    controlX = if (smooth) 2 * cx - controlX else cx
                    controlY = if (smooth) 2 * cy - controlY else cy
                    sink.quadTo(controlX, controlY, args[i], args[i + 1])
                    cx = args[i]
                    cy = args[i + 1]
                    i += 2
                }

                'A' -> {
                    val endX = args[i + 5]
                    val endY = args[i + 6]
                    sink.arcStart()
                    arcToCubics(cx, cy, args[i], args[i + 1], args[i + 2], args[i + 3] != 0.0, args[i + 4] != 0.0, endX, endY, sink)
                    sink.arcEnd()
                    cx = endX
                    cy = endY
                    i += 7
                }

                'Z' -> {
                    sink.close()
                    cx = startX
                    cy = startY
                }
            }
            previous = command
        }
    }

    private fun toAndroidPath(): Path {
        val out = Path()
        walk(
            object : PathSink {
                override fun moveTo(x: Double, y: Double) = out.moveTo(x.toFloat(), y.toFloat())

                override fun lineTo(x: Double, y: Double) = out.lineTo(x.toFloat(), y.toFloat())

                override fun quadTo(x1: Double, y1: Double, x: Double, y: Double) =
                    out.quadTo(x1.toFloat(), y1.toFloat(), x.toFloat(), y.toFloat())

                override fun cubicTo(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) =
                    out.cubicTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), x.toFloat(), y.toFloat())

                override fun close() = out.close()
            },
        )
        return out
    }

    companion object {
        val EMPTY: PathData = PathData(CharArray(0), DoubleArray(0))

        fun argumentCount(command: Char): Int =
            when (command) {
                'M', 'L', 'T' -> 2
                'H', 'V' -> 1
                'C' -> 6
                'S', 'Q' -> 4
                'A' -> 7
                else -> 0
            }

        fun polyline(points: List<Double>, closed: Boolean): PathData {
            val pairs = points.size / 2
            if (pairs == 0) return EMPTY
            val commands = CharArray(pairs + if (closed) 1 else 0) {
                if (it == 0) {
                    'M'
                } else if (it < pairs) {
                    'L'
                } else {
                    'Z'
                }
            }
            return PathData(commands, DoubleArray(pairs * 2) { points[it] })
        }
    }
}

internal class ParsedPath(val data: PathData, val hadError: Boolean)

/**
 * The SVG path grammar. On an error, everything before the bad command is kept, because the specification asks for that and
 * because a slightly damaged file should still show most of its picture.
 */
internal fun parsePathData(text: String): ParsedPath {
    val scanner = NumberScanner(text)
    val commands = ArrayList<Char>()
    var args = DoubleArray(64)
    var argCount = 0
    var cx = 0.0
    var cy = 0.0
    var startX = 0.0
    var startY = 0.0

    fun push(value: Double) {
        if (argCount == args.size) args = args.copyOf(args.size * 2)
        args[argCount++] = value
    }

    fun readNumbers(count: Int, into: DoubleArray): Boolean {
        for (i in 0 until count) {
            if (i > 0) scanner.skipSeparators()
            into[i] = scanner.number() ?: return false
        }
        return true
    }

    val buffer = DoubleArray(7)
    scanner.skipWhitespace()
    var ok = true
    var first = true
    parsing@ while (!scanner.atEnd) {
        val letter = scanner.peek()
        if (!letter.isLetter()) {
            ok = false
            break
        }
        scanner.pos++
        val upper = letter.uppercaseChar()
        val relative = letter != upper
        if (first && upper != 'M') {
            ok = false
            break
        }
        first = false
        if (upper == 'Z') {
            commands += 'Z'
            cx = startX
            cy = startY
            scanner.skipWhitespace()
            continue
        }
        val arity = PathData.argumentCount(upper)
        if (arity == 0) {
            ok = false
            break
        }
        var isFirstSet = true
        while (true) {
            scanner.skipWhitespace()
            val valid =
                if (upper == 'A') {
                    readArc(scanner, buffer)
                } else {
                    readNumbers(arity, buffer)
                }
            if (!valid) {
                ok = false
                break@parsing
            }
            // After the first pair, further pairs of a moveto are implicit linetos, relative if the moveto was.
            val effective = if (upper == 'M' && !isFirstSet) 'L' else upper
            commands += effective
            when (effective) {
                'H' -> {
                    cx = buffer[0] + if (relative) cx else 0.0
                    push(cx)
                }

                'V' -> {
                    cy = buffer[0] + if (relative) cy else 0.0
                    push(cy)
                }

                'A' -> {
                    for (k in 0 until 5) push(buffer[k])
                    cx = buffer[5] + if (relative) cx else 0.0
                    cy = buffer[6] + if (relative) cy else 0.0
                    push(cx)
                    push(cy)
                }

                else -> {
                    val baseX = if (relative) cx else 0.0
                    val baseY = if (relative) cy else 0.0
                    var k = 0
                    while (k < arity) {
                        push(buffer[k] + baseX)
                        push(buffer[k + 1] + baseY)
                        k += 2
                    }
                    cx = buffer[arity - 2] + baseX
                    cy = buffer[arity - 1] + baseY
                }
            }
            if (effective == 'M') {
                startX = cx
                startY = cy
            }
            isFirstSet = false
            scanner.skipSeparators()
            if (scanner.atEnd) break@parsing
            val next = scanner.peek()
            if (next.isLetter()) break
        }
    }
    val hasDrawing = commands.isNotEmpty()
    return ParsedPath(
        if (hasDrawing) PathData(commands.toCharArray(), args.copyOf(argCount)) else PathData.EMPTY,
        !ok,
    )
}

private fun readArc(scanner: NumberScanner, into: DoubleArray): Boolean {
    into[0] = scanner.number() ?: return false
    scanner.skipSeparators()
    into[1] = scanner.number() ?: return false
    scanner.skipSeparators()
    into[2] = scanner.number() ?: return false
    scanner.skipSeparators()
    into[3] = scanner.flag() ?: return false
    scanner.skipSeparators()
    into[4] = scanner.flag() ?: return false
    scanner.skipSeparators()
    into[5] = scanner.number() ?: return false
    scanner.skipSeparators()
    into[6] = scanner.number() ?: return false
    return true
}

/** Endpoint-to-centre arc conversion from the SVG specification's appendix, then one cubic per quarter turn at most. */
private fun arcToCubics(
    x0: Double,
    y0: Double,
    rxRaw: Double,
    ryRaw: Double,
    rotationDegrees: Double,
    largeArc: Boolean,
    sweep: Boolean,
    x1: Double,
    y1: Double,
    sink: PathSink,
) {
    if (x0 == x1 && y0 == y1) return
    var rx = abs(rxRaw)
    var ry = abs(ryRaw)
    if (rx == 0.0 || ry == 0.0) {
        sink.lineTo(x1, y1)
        return
    }
    val phi = Math.toRadians(rotationDegrees % 360.0)
    val cosPhi = cos(phi)
    val sinPhi = sin(phi)
    val dx = (x0 - x1) / 2
    val dy = (y0 - y1) / 2
    val xp = cosPhi * dx + sinPhi * dy
    val yp = -sinPhi * dx + cosPhi * dy
    // Radii too small to span the two end points are scaled up until they just fit.
    val lambda = (xp * xp) / (rx * rx) + (yp * yp) / (ry * ry)
    if (lambda > 1) {
        val scale = sqrt(lambda)
        rx *= scale
        ry *= scale
    }
    val rx2 = rx * rx
    val ry2 = ry * ry
    val denominator = rx2 * yp * yp + ry2 * xp * xp
    val numerator = max(0.0, rx2 * ry2 - denominator)
    val factor = (if (largeArc == sweep) -1.0 else 1.0) * sqrt(numerator / denominator)
    val cxp = factor * rx * yp / ry
    val cyp = -factor * ry * xp / rx
    val cx = cosPhi * cxp - sinPhi * cyp + (x0 + x1) / 2
    val cy = sinPhi * cxp + cosPhi * cyp + (y0 + y1) / 2
    val startAngle = vectorAngle(1.0, 0.0, (xp - cxp) / rx, (yp - cyp) / ry)
    var delta = vectorAngle((xp - cxp) / rx, (yp - cyp) / ry, (-xp - cxp) / rx, (-yp - cyp) / ry)
    if (!sweep && delta > 0) delta -= 2 * PI
    if (sweep && delta < 0) delta += 2 * PI
    val pieces = max(1, ceil(abs(delta) / (PI / 2) - 1e-9).toInt())
    val step = delta / pieces
    val k = 4.0 / 3.0 * tan(step / 4)
    var angle = startAngle
    for (piece in 0 until pieces) {
        val next = angle + step
        val cosA = cos(angle)
        val sinA = sin(angle)
        val cosB = cos(next)
        val sinB = sin(next)
        val c1x = rx * (cosA - k * sinA)
        val c1y = ry * (sinA + k * cosA)
        val c2x = rx * (cosB + k * sinB)
        val c2y = ry * (sinB - k * cosB)
        val ex = cx + cosPhi * rx * cosB - sinPhi * ry * sinB
        val ey = cy + sinPhi * rx * cosB + cosPhi * ry * sinB
        val isLast = piece == pieces - 1
        sink.cubicTo(
            cx + cosPhi * c1x - sinPhi * c1y,
            cy + sinPhi * c1x + cosPhi * c1y,
            cx + cosPhi * c2x - sinPhi * c2y,
            cy + sinPhi * c2x + cosPhi * c2y,
            // Land exactly on the end point, so that the curve after this one joins without a tiny gap.
            if (isLast) x1 else ex,
            if (isLast) y1 else ey,
        )
        angle = next
    }
}

private fun vectorAngle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
    val dot = ux * vx + uy * vy
    val length = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
    val angle = acos((dot / length).coerceIn(-1.0, 1.0))
    return if (ux * vy - uy * vx < 0) -angle else angle
}

private class BoundsSink : PathSink {
    private var left = Double.POSITIVE_INFINITY
    private var top = Double.POSITIVE_INFINITY
    private var right = Double.NEGATIVE_INFINITY
    private var bottom = Double.NEGATIVE_INFINITY
    private var cx = 0.0
    private var cy = 0.0
    private var startX = 0.0
    private var startY = 0.0
    private var pending = false

    fun result(): Box? = if (left > right) null else Box(left, top, right - left, bottom - top)

    private fun include(x: Double, y: Double) {
        left = min(left, x)
        right = max(right, x)
        top = min(top, y)
        bottom = max(bottom, y)
    }

    /** A moveto with nothing drawn after it does not count: it would stretch the box to include an invisible point. */
    private fun commitMove() {
        if (pending) {
            include(cx, cy)
            pending = false
        }
    }

    override fun moveTo(x: Double, y: Double) {
        cx = x
        cy = y
        startX = x
        startY = y
        pending = true
    }

    override fun lineTo(x: Double, y: Double) {
        commitMove()
        include(x, y)
        cx = x
        cy = y
    }

    override fun quadTo(x1: Double, y1: Double, x: Double, y: Double) {
        commitMove()
        includeQuadExtreme(cx, x1, x) { value -> include(value, cy) }
        includeQuadExtreme(cy, y1, y) { value -> include(cx, value) }
        include(x, y)
        cx = x
        cy = y
    }

    override fun cubicTo(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) {
        commitMove()
        includeCubicExtremes(cx, x1, x2, x) { value -> include(value, cy) }
        includeCubicExtremes(cy, y1, y2, y) { value -> include(cx, value) }
        include(x, y)
        cx = x
        cy = y
    }

    override fun close() {
        commitMove()
        cx = startX
        cy = startY
    }

    private inline fun includeQuadExtreme(p0: Double, p1: Double, p2: Double, emit: (Double) -> Unit) {
        val denominator = p0 - 2 * p1 + p2
        if (denominator == 0.0) return
        val t = (p0 - p1) / denominator
        if (t > 0 && t < 1) {
            val u = 1 - t
            emit(u * u * p0 + 2 * u * t * p1 + t * t * p2)
        }
    }

    private inline fun includeCubicExtremes(p0: Double, p1: Double, p2: Double, p3: Double, emit: (Double) -> Unit) {
        // Where the derivative of the cubic is zero.
        val a = -p0 + 3 * p1 - 3 * p2 + p3
        val b = 2 * (p0 - 2 * p1 + p2)
        val c = p1 - p0
        if (abs(a) < 1e-12) {
            if (b != 0.0) {
                val t = -c / b
                if (t > 0 && t < 1) emit(cubicAt(p0, p1, p2, p3, t))
            }
            return
        }
        val discriminant = b * b - 4 * a * c
        if (discriminant < 0) return
        val root = sqrt(discriminant)
        val t1 = (-b + root) / (2 * a)
        val t2 = (-b - root) / (2 * a)
        if (t1 > 0 && t1 < 1) emit(cubicAt(p0, p1, p2, p3, t1))
        if (t2 > 0 && t2 < 1) emit(cubicAt(p0, p1, p2, p3, t2))
    }

    private fun cubicAt(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val u = 1 - t
        return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3
    }
}
