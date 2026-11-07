package org.kineograph.model

import kotlin.math.ceil
import kotlin.math.roundToLong

/** A picture that is already in the project's files, and where it sits on the canvas. */
public data class PlacedImage(val file: String, val bounds: IntRect)

/**
 * How a project changes when finished pictures are brought into it. Nothing here knows where they came from or touches a pixel,
 * so that all of an import's timeline rules can be tested on their own. The caller is responsible for undo.
 *
 * Time on the new layer counts in ticks from the slot it starts on. A slot can hold only one drawing however many ticks it lasts, and
 * existing slots are never changed, so a tick in the middle of a held slot has nowhere to go. The drawing of that slot's first tick stays up
 * for all of it, which keeps the animation in time with everything else in the project.
 */
public object ImportEdits {
    /** How many ticks a duration takes up at [fps]. Always at least one, because something was asked for. */
    public fun tickCount(durationSeconds: Double, fps: Int): Int {
        if (durationSeconds.isNaN() || durationSeconds <= 0.0) return 1
        val ticks = (durationSeconds * fps.coerceIn(MIN_FPS, MAX_FPS)).roundToLong()
        return ticks.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * How many ticks it takes to show a duration from its first moment up to and including its last, for something that ends instead of running
     * back into its start. That is one more than the ticks between them, and what comes last stays on screen, so it is the one that must not go.
     */
    public fun tickCountThroughEnd(durationSeconds: Double, fps: Int): Int {
        if (durationSeconds.isNaN() || durationSeconds <= 0.0) return 1
        val between = ceil(durationSeconds * fps.coerceIn(MIN_FPS, MAX_FPS) - ROUNDING_MARGIN)
        return if (between >= Int.MAX_VALUE - 1.0) Int.MAX_VALUE else between.toInt() + 1
    }

    /**
     * The ticks of an animation starting on [startFrameId] that will really be seen: one for each existing slot it covers, then one for each
     * slot that has to be added. Drawing only these saves drawing pictures that would be thrown away.
     */
    public fun ticksToSample(project: Project, startFrameId: String, tickCount: Int): IntArray {
        val timeline = TimelineIndex(project.frames)
        val startSlot = project.frameIndex(startFrameId).coerceAtLeast(0)
        val startTick = timeline.startTick(startSlot)
        var onExistingSlots = 0
        var slot = startSlot
        while (slot < timeline.slotCount && timeline.startTick(slot) - startTick < tickCount) {
            onExistingSlots++
            slot++
        }
        val firstNewTick = timeline.totalTicks - startTick
        val ticks = IntArray(onExistingSlots + (tickCount - firstNewTick).coerceAtLeast(0))
        for (index in 0 until onExistingSlots) ticks[index] = timeline.startTick(startSlot + index) - startTick
        for (index in onExistingSlots until ticks.size) ticks[index] = firstNewTick + index - onExistingSlots
        return ticks
    }

    /**
     * Puts an animation on one new layer just above [aboveLayerId], starting on [startFrameId]. [images] maps a tick to its picture, and a tick
     * with no entry is empty there, so it stores no cel. Slots are added after the end of the timeline when the animation runs past it. Every
     * existing layer, slot and cel stays exactly as it was, and a picture that fills several ticks stays one file.
     */
    public fun addAnimationLayer(
        project: Project,
        aboveLayerId: String?,
        startFrameId: String,
        layerName: String,
        tickCount: Int,
        images: Map<Int, PlacedImage>,
        newLayerId: String = newId(),
        newFrameIds: () -> String = ::newId,
    ): Project {
        val timeline = TimelineIndex(project.frames)
        val startSlot = project.frameIndex(startFrameId).coerceAtLeast(0)
        val startTick = timeline.startTick(startSlot)
        val cels = LinkedHashMap(project.cels)

        fun place(tick: Int, frameId: String) {
            val image = images[tick] ?: return
            cels[CelKey(newLayerId, frameId)] = Cel(newLayerId, frameId, image.file, image.bounds)
        }

        for (slot in startSlot until timeline.slotCount) {
            val tick = timeline.startTick(slot) - startTick
            if (tick >= tickCount) break
            place(tick, project.frames[slot].id)
        }
        val firstNewTick = timeline.totalTicks - startTick
        val added = ArrayList<FrameSlot>((tickCount - firstNewTick).coerceAtLeast(0))
        for (tick in firstNewTick until tickCount) {
            val frameId = newFrameIds()
            added += FrameSlot(frameId)
            place(tick, frameId)
        }
        return ProjectEdits.addLayer(project, aboveLayerId, layerName, newLayerId).copy(frames = project.frames + added, cels = cels)
    }

    /** Puts one picture on one new layer just above [aboveLayerId], on [frameId] alone. */
    public fun addImageLayer(
        project: Project,
        aboveLayerId: String?,
        frameId: String,
        layerName: String,
        image: PlacedImage,
        newLayerId: String = newId(),
    ): Project =
        ProjectEdits.withCel(
            ProjectEdits.addLayer(project, aboveLayerId, layerName, newLayerId),
            CelKey(newLayerId, frameId),
            Cel(newLayerId, frameId, image.file, image.bounds),
        )

    /** Makes [image] what [layerId] shows on [frameId]. Combining it with what was there is up to whoever drew the picture. */
    public fun replaceCel(project: Project, layerId: String, frameId: String, image: PlacedImage): Project =
        ProjectEdits.withCel(project, CelKey(layerId, frameId), Cel(layerId, frameId, image.file, image.bounds))

    /** Far less than any tick lasts. A duration worked out from a whole number of ticks must not round up to one more of them. */
    private const val ROUNDING_MARGIN = 1e-6
}
