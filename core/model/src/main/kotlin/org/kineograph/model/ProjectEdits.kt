package org.kineograph.model

import java.util.UUID

/** Creates identifiers for frames, layers and projects. */
public fun newId(): String = UUID.randomUUID().toString()

/**
 * Pure edits to a [Project]. Pixels are not touched here. Cel files never change once written, so
 * "copying" a cel just points at the same file. The caller is responsible for undo.
 */
public object ProjectEdits {
    /** Adds an empty frame just after [afterFrameId], or at the end when it is null or unknown. */
    public fun addFrameAfter(project: Project, afterFrameId: String?, newFrameId: String = newId()): Project {
        val index = project.frameIndex(afterFrameId.orEmpty())
        val insertAt = if (index < 0) project.frames.size else index + 1
        return project.copy(frames = project.frames.inserted(insertAt, FrameSlot(newFrameId)))
    }

    /** Adds an empty frame just before [beforeFrameId], or at the start when it is unknown. */
    public fun insertFrameBefore(project: Project, beforeFrameId: String, newFrameId: String = newId()): Project {
        val insertAt = project.frameIndex(beforeFrameId).coerceAtLeast(0)
        return project.copy(frames = project.frames.inserted(insertAt, FrameSlot(newFrameId)))
    }

    /** Copies a frame, hold and all, directly after itself. The copy shares the original's cel files. */
    public fun duplicateFrame(project: Project, frameId: String, newFrameId: String = newId()): Project {
        val index = project.frameIndex(frameId)
        if (index < 0) return project
        val slot = project.frames[index]
        val copiedCels =
            project.cels
                .filterKeys { it.frameId == frameId }
                .values
                .associate { cel -> CelKey(cel.layerId, newFrameId) to cel.copy(frameId = newFrameId) }
        return project.copy(
            frames = project.frames.inserted(index + 1, slot.copy(id = newFrameId)),
            cels = project.cels + copiedCels,
        )
    }

    /**
     * Removes a frame and its cels. A project always keeps at least one frame, so deleting the only
     * frame clears it instead.
     */
    public fun deleteFrame(project: Project, frameId: String): Project {
        if (project.frameIndex(frameId) < 0) return project
        if (project.frames.size == 1) return clearFrame(project, frameId)
        return project.copy(
            frames = project.frames.filterNot { it.id == frameId },
            cels = project.cels.filterKeys { it.frameId != frameId },
        )
    }

    /** Moves the slot at [fromIndex] so it ends up at [toIndex]. Out-of-range indexes do nothing. */
    public fun moveFrame(project: Project, fromIndex: Int, toIndex: Int): Project {
        val frames = project.frames
        if (fromIndex !in frames.indices || toIndex !in frames.indices || fromIndex == toIndex) return project
        val reordered = frames.toMutableList()
        reordered.add(toIndex, reordered.removeAt(fromIndex))
        return project.copy(frames = reordered)
    }

    /** Empties a frame on every layer, or only on [layerId] when it is given. */
    public fun clearFrame(project: Project, frameId: String, layerId: String? = null): Project =
        project.copy(cels = project.cels.filterKeys { it.frameId != frameId || (layerId != null && it.layerId != layerId) })

    /** Sets how many ticks a frame stays on screen. Values below 1 become 1. */
    public fun setHold(project: Project, frameId: String, hold: Int): Project =
        project.copy(frames = project.frames.map { if (it.id == frameId) it.copy(hold = maxOf(1, hold)) else it })

    /** Copies the given frames to a clipboard, in timeline order. */
    public fun copyFrames(project: Project, frameIds: Collection<String>, layerId: String? = null): FrameClipboard {
        val wanted = frameIds.toSet()
        val frames =
            project.frames.filter { it.id in wanted }.map { slot ->
                val cels =
                    project.cels.values
                        .filter { it.frameId == slot.id && (layerId == null || it.layerId == layerId) }
                        .associateBy { it.layerId }
                ClipboardFrame(slot.hold, cels)
            }
        return FrameClipboard(frames, layerId)
    }

    /**
     * Inserts the clipboard after [afterFrameId]. Frames copied from one layer are pasted onto
     * [targetLayerId]. Cels whose layer has since been deleted are dropped.
     */
    public fun pasteFrames(
        project: Project,
        clipboard: FrameClipboard,
        afterFrameId: String?,
        targetLayerId: String,
        newIds: () -> String = ::newId,
    ): Project {
        if (clipboard.frames.isEmpty()) return project
        val knownLayers = project.layers.mapTo(HashSet()) { it.id }
        val index = project.frameIndex(afterFrameId.orEmpty())
        val insertAt = if (index < 0) project.frames.size else index + 1
        val slots = ArrayList<FrameSlot>(clipboard.frames.size)
        val cels = project.cels.toMutableMap()
        clipboard.frames.forEach { frame ->
            val frameId = newIds()
            slots += FrameSlot(frameId, frame.hold)
            frame.cels.values.forEach { cel ->
                val layerId = if (clipboard.singleLayerSource != null) targetLayerId else cel.layerId
                if (layerId in knownLayers) {
                    cels[CelKey(layerId, frameId)] = cel.copy(layerId = layerId, frameId = frameId)
                }
            }
        }
        val frames = project.frames.toMutableList()
        frames.addAll(insertAt, slots)
        return project.copy(frames = frames, cels = cels)
    }

    /** Adds a new empty layer just above [aboveLayerId], or on top when it is null or unknown. */
    public fun addLayer(project: Project, aboveLayerId: String?, name: String, newLayerId: String = newId()): Project {
        val index = project.layers.indexOfFirst { it.id == aboveLayerId }
        val insertAt = if (index < 0) project.layers.size else index + 1
        return project.copy(layers = project.layers.inserted(insertAt, Layer(newLayerId, name)))
    }

    /** Removes a layer and its cels. A project always keeps at least one layer. */
    public fun deleteLayer(project: Project, layerId: String): Project {
        if (project.layers.size <= 1 || project.layer(layerId) == null) return project
        return project.copy(
            layers = project.layers.filterNot { it.id == layerId },
            cels = project.cels.filterKeys { it.layerId != layerId },
        )
    }

    /** Copies a layer directly above itself. The copy shares the original's cel files. */
    public fun duplicateLayer(project: Project, layerId: String, name: String, newLayerId: String = newId()): Project {
        val index = project.layers.indexOfFirst { it.id == layerId }
        if (index < 0) return project
        val copiedCels =
            project.cels
                .filterKeys { it.layerId == layerId }
                .values
                .associate { cel -> CelKey(newLayerId, cel.frameId) to cel.copy(layerId = newLayerId) }
        return project.copy(
            layers = project.layers.inserted(index + 1, project.layers[index].copy(id = newLayerId, name = name)),
            cels = project.cels + copiedCels,
        )
    }

    /** Moves the layer at [fromIndex] (bottom = 0) so it ends up at [toIndex]. */
    public fun moveLayer(project: Project, fromIndex: Int, toIndex: Int): Project {
        val layers = project.layers
        if (fromIndex !in layers.indices || toIndex !in layers.indices || fromIndex == toIndex) return project
        val reordered = layers.toMutableList()
        reordered.add(toIndex, reordered.removeAt(fromIndex))
        return project.copy(layers = reordered)
    }

    /** Applies [change] to the layer called [layerId]. */
    public fun updateLayer(project: Project, layerId: String, change: (Layer) -> Layer): Project =
        project.copy(layers = project.layers.map { if (it.id == layerId) change(it) else it })

    /** Sets the frame rate, clamped to the supported range. */
    public fun setFps(project: Project, fps: Int): Project = project.copy(fps = fps.coerceIn(MIN_FPS, MAX_FPS))

    /** Stores or, when [cel] is null, removes the cel for one layer on one frame. */
    public fun withCel(project: Project, key: CelKey, cel: Cel?): Project =
        project.copy(cels = if (cel == null) project.cels - key else project.cels + (key to cel))

    private fun <T> List<T>.inserted(index: Int, item: T): List<T> {
        val result = ArrayList<T>(size + 1)
        result.addAll(subList(0, index))
        result.add(item)
        result.addAll(subList(index, size))
        return result
    }
}

/** Frames held for pasting. [singleLayerSource] is set when only one layer was copied. */
public data class FrameClipboard(val frames: List<ClipboardFrame>, val singleLayerSource: String?)

/** One copied frame: its hold and its cels, keyed by layer id. */
public data class ClipboardFrame(val hold: Int, val cels: Map<String, Cel>)

/** Creates a new project with one empty frame and one empty layer. */
public fun newProject(
    name: String,
    width: Int,
    height: Int,
    fps: Int,
    background: Background,
    layerName: String,
    now: Long,
    id: String = newId(),
): Project =
    Project(
        id = id,
        name = name,
        createdAt = now,
        updatedAt = now,
        width = width.coerceAtLeast(1),
        height = height.coerceAtLeast(1),
        fps = fps.coerceIn(MIN_FPS, MAX_FPS),
        background = background,
        layers = listOf(Layer(newId(), layerName)),
        frames = listOf(FrameSlot(newId())),
    )
