package org.kineograph.media.svg

internal val ANIMATION_TAGS: Set<String> = setOf("animate", "animateTransform", "animateMotion", "animateColor", "set", "discard")
private val SYNC_BEGIN_END = Regex("""^(.+?)\.(beginEvent|endEvent|begin|end)\s*(?:([+-])\s*(.+))?$""")
private val SYNC_REPEAT = Regex("""^(.+?)\.repeat\(\s*(\d+)\s*\)\s*(?:([+-])\s*(.+))?$""")

/** Turns SMIL animation elements into timings and tracks on their target elements. */
internal class SmilBuilder(
    private val root: SvgElement,
    private val byId: Map<String, SvgElement>,
    private val warnings: MutableSet<SvgWarning>,
    private val rootFontSize: Double,
) {
    val timings: ArrayList<SmilTiming> = ArrayList()
    var foundAnimation: Boolean = false
        private set
    private var order = 0

    fun build() {
        walk(root)
        val timeline = SmilTimeline(timings)
        timeline.solve()
        if (timeline.exhausted) warnings += SvgWarning.LIMIT_REACHED
    }

    private fun walk(element: SvgElement) {
        if (element.tag in ANIMATION_TAGS) {
            foundAnimation = true
            order++
            build(element)
            return
        }
        for (child in element.children) walk(child)
    }

    private fun build(element: SvgElement) {
        val target = targetOf(element) ?: return
        when (element.tag) {
            "animateMotion" -> buildMotion(element, target)
            "discard" -> buildDiscard(element, target)
            else -> buildValues(element, target)
        }
    }

    private fun targetOf(element: SvgElement): SvgElement? {
        val href = element.attrs["href"]?.trim() ?: return element.parent
        if (!href.startsWith("#")) {
            warnings += SvgWarning.EXTERNAL_REFERENCE_IGNORED
            return null
        }
        return byId[href.substring(1)]
    }

    private fun readTiming(element: SvgElement, fixedFreeze: Boolean? = null): SmilTiming {
        val attrs = element.attrs
        val begins = readTimeList(attrs["begin"], isBegin = true)
        val ends = readTimeList(attrs["end"], isBegin = false)
        val duration = attrs["dur"]?.let { parseClock(it) }?.takeIf { it > 0.0 }
        val repeatCount =
            attrs["repeatCount"]?.trim()?.let {
                if (it == "indefinite") Double.POSITIVE_INFINITY else parseNumberOrNull(it)?.takeIf { value -> value > 0.0 }
            }
        val repeatDuration =
            attrs["repeatDur"]?.trim()?.let {
                if (it == "indefinite") Double.POSITIVE_INFINITY else parseClock(it)?.takeIf { value -> value > 0.0 }
            }
        var min = attrs["min"]?.let { parseClock(it) }?.takeIf { it >= 0.0 } ?: 0.0
        var max = attrs["max"]?.let { parseClock(it) }?.takeIf { it > 0.0 } ?: Double.POSITIVE_INFINITY
        // The specification says a min above max means both are ignored.
        if (min > max) {
            min = 0.0
            max = Double.POSITIVE_INFINITY
        }
        val restart =
            when (attrs["restart"]?.trim()) {
                "whenNotActive" -> Restart.WHEN_NOT_ACTIVE
                "never" -> Restart.NEVER
                else -> Restart.ALWAYS
            }
        val timing =
            SmilTiming(
                id = element.id,
                begins = begins.specs,
                ends = ends.specs,
                endOpen = ends.specs.isEmpty() || ends.hasOpen,
                duration = duration,
                repeatCount = repeatCount,
                repeatDuration = repeatDuration,
                minDuration = min,
                maxDuration = max,
                restart = restart,
                freeze = fixedFreeze ?: (attrs["fill"]?.trim() == "freeze"),
            )
        timings += timing
        return timing
    }

    private class TimeList(val specs: List<TimeSpec>, val hasOpen: Boolean)

    private fun readTimeList(raw: String?, isBegin: Boolean): TimeList {
        if (raw == null) return TimeList(if (isBegin) listOf(TimeSpec.Offset(0.0)) else emptyList(), false)
        val specs = ArrayList<TimeSpec>()
        var open = false
        for (part in raw.split(';')) {
            val text = part.trim()
            if (text.isEmpty()) continue
            val spec = parseTimeSpec(text, isBegin)
            if (spec != null) {
                specs += spec
            } else {
                open = true
            }
        }
        return TimeList(specs, open)
    }

    /** Returns null for anything that cannot happen during an import: a click, a key press, a wall clock, `indefinite`. */
    private fun parseTimeSpec(text: String, isBegin: Boolean): TimeSpec? {
        if (text == "indefinite") {
            // In a begin this waits for a script or a link. In an end it just means "no fixed end", which is not worth a warning.
            if (isBegin) warnings += SvgWarning.EVENT_TRIGGER_IGNORED
            return null
        }
        val signed = text[0] == '+' || text[0] == '-'
        val offset = parseClock(if (signed) text.substring(1) else text)
        if (offset != null) return TimeSpec.Offset(if (text[0] == '-') -offset else offset)
        SYNC_REPEAT.find(text)?.let { match ->
            val (id, repeat, sign, value) = match.destructured
            val seconds = syncOffset(sign, value) ?: return invalidTime()
            val count = repeat.toIntOrNull() ?: return invalidTime()
            return TimeSpec.Sync(unescapeId(id), SyncKind.REPEAT, count, seconds)
        }
        SYNC_BEGIN_END.find(text)?.let { match ->
            val (id, kind, sign, value) = match.destructured
            val seconds = syncOffset(sign, value) ?: return invalidTime()
            // beginEvent and endEvent are events by name, but they fire by themselves, at exactly these same moments.
            return TimeSpec.Sync(unescapeId(id), if (kind.startsWith("begin")) SyncKind.BEGIN else SyncKind.END, 0, seconds)
        }
        // Everything else is an event name, `click` or `a.mouseover+1s`, and no event fires during an import.
        warnings += SvgWarning.EVENT_TRIGGER_IGNORED
        return null
    }

    private fun invalidTime(): TimeSpec? {
        warnings += SvgWarning.INVALID_CONTENT_IGNORED
        return null
    }

    private fun syncOffset(sign: String, value: String): Double? {
        if (sign.isEmpty()) return 0.0
        val seconds = parseClock(value) ?: return null
        return if (sign == "-") -seconds else seconds
    }

    private fun unescapeId(id: String): String = id.replace("\\", "").trim()

    private fun buildValues(element: SvgElement, target: SvgElement) {
        val attrs = element.attrs
        val tag = element.tag
        val rawName = attrs["attributeName"]?.trim() ?: if (tag == "animateTransform") "transform" else return
        val property = rawName.removePrefix("xlink:")
        val def = PROPERTIES[property]
        if (def == null) {
            warnings += SvgWarning.UNSUPPORTED_FEATURE_IGNORED
            return
        }
        // Transforms have their own element for this. Browsers ignore animate and set on them, so a file that tries shows no motion.
        if (def.kind == PropKind.TRANSFORM && tag != "animateTransform") {
            warnings += SvgWarning.INVALID_CONTENT_IGNORED
            return
        }
        val timing = readTiming(element)
        val parse: (String) -> SvgValue? =
            if (tag == "animateTransform") {
                val type = attrs["type"]?.trim() ?: "translate"
                ({ text -> parseAnimateTransformValue(type, text) })
            } else {
                ({ text -> parseProperty(property, resolveRem(text, rootFontSize)) })
            }
        val interpolable = tag == "animateTransform" || (def.kind != PropKind.KEYWORD && def.kind != PropKind.RAW)
        val requestedMode =
            when (attrs["calcMode"]?.trim()) {
                "discrete" -> CalcMode.DISCRETE
                "paced" -> CalcMode.PACED
                "spline" -> CalcMode.SPLINE
                else -> if (tag == "set") CalcMode.DISCRETE else CalcMode.LINEAR
            }
        val mode = if (interpolable) requestedMode else CalcMode.DISCRETE
        val spec = readValueSpec(element, parse)?.takeUnless { property == "stroke-dasharray" && it.mentionsNoDashes }
        if (spec == null) {
            warnings += SvgWarning.INVALID_CONTENT_IGNORED
            return
        }
        val keyTimes = attrs["keyTimes"]?.takeIf { mode != CalcMode.PACED }?.let { parseKeyTimes(it) }
        val keyTimesInvalid = attrs["keyTimes"] != null && mode != CalcMode.PACED && !validKeyTimes(keyTimes, spec.values.size, mode)
        val splines = if (mode == CalcMode.SPLINE) parseKeySplines(attrs["keySplines"]) else null
        val splinesInvalid = mode == CalcMode.SPLINE && (splines == null || splines.size != spec.values.size - 1)
        if (keyTimesInvalid || splinesInvalid) {
            // Browsers switch such an animation off, so the picture stays as the file's author saw it.
            warnings += SvgWarning.INVALID_CONTENT_IGNORED
            return
        }
        val additiveRequested = attrs["additive"]?.trim() == "sum" || spec.additiveByDefinition
        target.addTrack(
            SmilValueTrack(
                property,
                order,
                timing,
                spec.values,
                mode,
                keyTimes,
                splines,
                additive = additiveRequested && !spec.isToAnimation && interpolable,
                accumulate = attrs["accumulate"]?.trim() == "sum" && interpolable,
                isToAnimation = spec.isToAnimation,
            ),
        )
    }

    private class ValueSpec(val values: List<SvgValue?>, val additiveByDefinition: Boolean, val isToAnimation: Boolean) {
        /** `none` is fine as a dash pattern, but SMIL's lists have no way to reach it, so browsers drop the whole animation. */
        val mentionsNoDashes: Boolean get() = values.any { it is DashValue && it.items.isEmpty() }
    }

    private fun readValueSpec(element: SvgElement, parse: (String) -> SvgValue?): ValueSpec? {
        val attrs = element.attrs
        if (element.tag == "set") {
            val to = readOne(attrs["to"], parse) ?: return null
            return ValueSpec(listOf(to.value), additiveByDefinition = false, isToAnimation = false)
        }
        attrs["values"]?.let { raw ->
            val parts = raw.split(';').map { it.trim() }.toMutableList()
            // A trailing semicolon is common in generated files, and an empty last value is plainly not what was meant.
            if (parts.isNotEmpty() && parts.last().isEmpty()) parts.removeAt(parts.lastIndex)
            if (parts.isEmpty()) return null
            val values = parts.map { readOne(it, parse)?.value ?: return null }
            return ValueSpec(values, additiveByDefinition = false, isToAnimation = false)
        }
        val from = attrs["from"]?.let { readOne(it, parse) ?: return null }
        val to = attrs["to"]?.let { readOne(it, parse) ?: return null }
        val by = attrs["by"]?.let { readOne(it, parse) ?: return null }
        return when {
            from != null && to != null -> {
                ValueSpec(listOf(from.value, to.value), additiveByDefinition = false, isToAnimation = false)
            }

            from != null && by != null -> {
                val end = ValueMath.addNumeric(from.value, by.value, Rgba.BLACK) ?: return null
                ValueSpec(listOf(from.value, end), additiveByDefinition = false, isToAnimation = false)
            }

            by != null -> {
                val zero = ValueMath.zeroOf(by.value) ?: return null
                ValueSpec(listOf(zero, by.value), additiveByDefinition = true, isToAnimation = false)
            }

            // Even when discrete. SMIL's text says this should hold the target all the way, but both Chromium and Firefox flip half way.
            to != null -> {
                ValueSpec(listOf(null, to.value), additiveByDefinition = false, isToAnimation = true)
            }

            else -> {
                null
            }
        }
    }

    private class OneValue(val value: SvgValue?)

    /** `inherit` in an animation value means "whatever is underneath", which a null entry stands for. */
    private fun readOne(raw: String?, parse: (String) -> SvgValue?): OneValue? {
        val text = raw?.trim() ?: return null
        val value = parse(text) ?: return null
        return OneValue(if (value === InheritValue || value === InitialValue) null else value)
    }

    private fun parseKeyTimes(raw: String): DoubleArray? {
        val parts = raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        return DoubleArray(parts.size) { parseNumberOrNull(parts[it]) ?: return null }
    }

    private fun validKeyTimes(times: DoubleArray?, count: Int, mode: CalcMode): Boolean {
        if (times == null || times.size != count || times.isEmpty()) return false
        if (times[0] != 0.0) return false
        for (i in 1 until times.size) if (times[i] < times[i - 1] || times[i] > 1.0) return false
        // A list of values must reach its last value by the end, except in discrete mode, where each value just holds.
        return mode == CalcMode.DISCRETE || times.last() == 1.0 || count == 1
    }

    private fun parseKeySplines(raw: String?): List<Easing>? {
        if (raw == null) return null
        val parts = raw.split(';').map { it.trim() }.toMutableList()
        if (parts.isNotEmpty() && parts.last().isEmpty()) parts.removeAt(parts.lastIndex)
        return parts.map { part ->
            val numbers = parseNumberList(part) ?: return null
            if (numbers.size != 4 || numbers.any { it !in 0.0..1.0 }) return null
            CubicBezier(numbers[0], numbers[1], numbers[2], numbers[3])
        }
    }

    private fun buildDiscard(element: SvgElement, target: SvgElement) {
        // Once discarded, an element stays gone for the rest of the picture, which is the same as `display: none`, frozen.
        val timing = readTiming(element, fixedFreeze = true)
        target.addTrack(
            SmilValueTrack(
                "display",
                order,
                timing,
                listOf(KeywordValue("none")),
                CalcMode.DISCRETE,
                keyTimes = null,
                splines = null,
                additive = false,
                accumulate = false,
                isToAnimation = false,
            ),
        )
    }

    private fun buildMotion(element: SvgElement, target: SvgElement) {
        val attrs = element.attrs
        val path = motionPath(element)
        if (path == null) {
            warnings += SvgWarning.INVALID_CONTENT_IGNORED
            return
        }
        val timing = readTiming(element)
        val mode =
            when (attrs["calcMode"]?.trim()) {
                "discrete" -> CalcMode.DISCRETE
                "linear" -> CalcMode.LINEAR
                "spline" -> CalcMode.SPLINE
                else -> CalcMode.PACED
            }
        val keyPoints =
            attrs["keyPoints"]?.let { raw ->
                raw
                    .split(';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { parseNumberOrNull(it)?.coerceIn(0.0, 1.0) ?: return@let null }
                    .toDoubleArray()
            }
        val keyTimes = attrs["keyTimes"]?.takeIf { mode != CalcMode.PACED }?.let { parseKeyTimes(it) }
        val splines = if (mode == CalcMode.SPLINE) parseKeySplines(attrs["keySplines"]) else null
        val rotate =
            when (val value = attrs["rotate"]?.trim()) {
                null, "" -> MotionRotate.Fixed(0.0)
                "auto" -> MotionRotate.Auto
                "auto-reverse" -> MotionRotate.AutoReverse
                else -> MotionRotate.Fixed(parseAngle(value) ?: 0.0)
            }
        target.addTrack(
            SmilMotionTrack(
                order,
                timing,
                path,
                // keyPoints need times to pair with, so paced mode cannot apply to them. It would ignore keyTimes.
                if (keyPoints != null && mode == CalcMode.PACED) CalcMode.LINEAR else mode,
                keyPoints,
                keyTimes ?: if (keyPoints != null) attrs["keyTimes"]?.let { parseKeyTimes(it) } else null,
                splines,
                rotate,
                additive = attrs["additive"]?.trim() == "sum",
                accumulate = attrs["accumulate"]?.trim() == "sum",
            ),
        )
    }

    private fun motionPath(element: SvgElement): PathData? {
        val attrs = element.attrs
        for (child in element.children) {
            if (child.tag == "mpath") {
                val href = child.attrs["href"]?.trim() ?: continue
                if (!href.startsWith("#")) {
                    warnings += SvgWarning.EXTERNAL_REFERENCE_IGNORED
                    continue
                }
                val data = (byId[href.substring(1)]?.props?.get("d") as? PathValue)?.data
                if (data != null && !data.isEmpty) return data
            }
        }
        attrs["path"]?.let { raw ->
            val parsed = parsePathData(raw)
            if (parsed.hadError) warnings += SvgWarning.INVALID_CONTENT_IGNORED
            if (!parsed.data.isEmpty) return parsed.data
        }
        attrs["values"]?.let { raw ->
            val parts = raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            val points = ArrayList<Double>(parts.size * 2)
            for (part in parts) {
                val numbers = parseCoordinatePair(part) ?: return null
                points += numbers.first
                points += numbers.second
            }
            return PathData.polyline(points, closed = false).takeIf { !it.isEmpty }
        }
        val from = attrs["from"]?.let { parseCoordinatePair(it) ?: return null }
        val to = attrs["to"]?.let { parseCoordinatePair(it) ?: return null }
        val by = attrs["by"]?.let { parseCoordinatePair(it) ?: return null }
        val start = from ?: (0.0 to 0.0)
        val end =
            to ?: by?.let { (start.first + it.first) to (start.second + it.second) } ?: return null
        return PathData.polyline(listOf(start.first, start.second, end.first, end.second), closed = false)
    }

    private fun parseCoordinatePair(raw: String): Pair<Double, Double>? {
        val numbers = parseNumberList(raw) ?: parseLengthList(raw)?.map { it.px } ?: return null
        if (numbers.isEmpty()) return null
        return numbers[0] to (numbers.getOrNull(1) ?: 0.0)
    }
}

/** The values of `animateTransform` are bare numbers, whose meaning depends on its `type` attribute. */
private fun parseAnimateTransformValue(type: String, text: String): SvgValue? {
    val numbers = parseNumberList(text) ?: return null
    if (numbers.isEmpty()) return null
    val op: TransformOp =
        when (type) {
            "translate" -> TransformOp.Translate(Length(px = numbers[0]), Length(px = numbers.getOrElse(1) { 0.0 }))
            "scale" -> TransformOp.Scale(numbers[0], numbers.getOrElse(1) { numbers[0] })
            "rotate" -> TransformOp.Rotate(numbers[0], numbers.getOrElse(1) { 0.0 }, numbers.getOrElse(2) { 0.0 })
            "skewX" -> TransformOp.Skew(numbers[0], 0.0)
            "skewY" -> TransformOp.Skew(0.0, numbers[0])
            else -> return null
        }
    return TransformValue(listOf(op))
}
