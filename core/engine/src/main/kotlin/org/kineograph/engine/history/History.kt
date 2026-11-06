package org.kineograph.engine.history

import org.kineograph.model.IntRect
import org.kineograph.model.Project
import java.io.File

/** Where the user was when an edit happened, so undo can take them back there. */
public data class HistoryCursor(val frameId: String, val layerId: String)

/** One undoable step. */
public sealed interface HistoryEntry {
    /** Roughly how much memory this entry holds. */
    public val memoryBytes: Long

    /** How much disk space this entry holds. */
    public val diskBytes: Long

    /** Frees anything this entry holds, once it can no longer be undone or redone. */
    public fun discard()
}

/** A change to pixels in one rectangle of one cel. The rectangle is in the cel surface's coordinates. */
public class PixelEntry(
    public val layerId: String,
    public val frameId: String,
    public val rect: IntRect,
    public val payload: PatchPayload,
) : HistoryEntry {
    override val memoryBytes: Long get() = payload.memoryBytes + ENTRY_OVERHEAD
    override val diskBytes: Long get() = payload.diskBytes

    override fun discard() {
        payload.discard()
    }

    private companion object {
        const val ENTRY_OVERHEAD = 128L
    }
}

/**
 * A change to the project's structure: frames, layers, holds, cel references. Whole [Project]
 * values are kept, and cel files never change, so restoring one is instant however large the project is.
 */
public class StructureEntry(
    public val before: Project,
    public val after: Project,
    public val cursorBefore: HistoryCursor,
    public val cursorAfter: HistoryCursor,
) : HistoryEntry {
    override val memoryBytes: Long =
        ENTRY_OVERHEAD + (before.cels.size + after.cels.size).toLong() * PER_CEL +
            (before.frames.size + after.frames.size).toLong() * PER_FRAME
    override val diskBytes: Long get() = 0L

    override fun discard() {
        // Holds no resources of its own: cel files outlive history entries.
    }

    private companion object {
        const val ENTRY_OVERHEAD = 512L
        const val PER_CEL = 96L
        const val PER_FRAME = 48L
    }
}

/**
 * The undo and redo stacks, with a memory budget.
 *
 * When memory use passes the budget, the oldest entries' pixels move to disk. Only when the disk budget
 * is spent too are the oldest entries dropped, and then never below [minSteps] unless the disk itself
 * refuses. So at least [minSteps] undos are available within the budgets.
 *
 * Not thread-safe: use it from the engine thread only.
 */
public class History(
    private val memoryBudgetBytes: Long,
    private val diskBudgetBytes: Long,
    private val minSteps: Int,
    private val spillDirectory: File,
) {
    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()
    private var spillCounter = 0L

    public val canUndo: Boolean get() = undoStack.isNotEmpty()
    public val canRedo: Boolean get() = redoStack.isNotEmpty()
    public val undoDepth: Int get() = undoStack.size
    public val redoDepth: Int get() = redoStack.size

    public val memoryBytes: Long get() = undoStack.sumOf { it.memoryBytes } + redoStack.sumOf { it.memoryBytes }
    public val diskBytes: Long get() = undoStack.sumOf { it.diskBytes } + redoStack.sumOf { it.diskBytes }

    /** Records a new edit. Anything that could have been redone is gone from here on. */
    public fun push(entry: HistoryEntry) {
        redoStack.forEach { it.discard() }
        redoStack.clear()
        undoStack.addLast(entry)
        enforceBudgets()
    }

    /** Takes the newest edit for undoing, or null when there is none. The caller must apply it. */
    public fun popUndo(): HistoryEntry? {
        val entry = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(entry)
        return entry
    }

    /** Takes the most recently undone edit for redoing, or null when there is none. */
    public fun popRedo(): HistoryEntry? {
        val entry = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(entry)
        return entry
    }

    /** Applies the budgets again; called after background compression has changed the numbers. */
    public fun enforceBudgets() {
        var memory = memoryBytes
        if (memory > memoryBudgetBytes) {
            for (entry in undoStack) {
                if (memory <= memoryBudgetBytes) break
                if (entry is PixelEntry && !entry.payload.isSpilled) {
                    val before = entry.memoryBytes
                    if (entry.payload.spillTo(nextSpillFile())) memory -= before - entry.memoryBytes
                }
            }
        }
        var disk = diskBytes
        while (undoStack.size > minSteps && (disk > diskBudgetBytes || memory > memoryBudgetBytes)) {
            val oldest = undoStack.removeFirstOrNull() ?: break
            memory -= oldest.memoryBytes
            disk -= oldest.diskBytes
            oldest.discard()
        }
    }

    /** Frees as much memory as possible by moving pixels to disk, for when the system is short of RAM. */
    public fun spillAll() {
        (undoStack + redoStack).forEach { entry ->
            if (entry is PixelEntry && !entry.payload.isSpilled) entry.payload.spillTo(nextSpillFile())
        }
    }

    /** Cel files that undo or redo might bring back, which therefore must not be deleted. */
    public fun referencedFiles(): Set<String> {
        val files = HashSet<String>()
        (undoStack + redoStack).forEach { entry ->
            if (entry is StructureEntry) {
                files += entry.before.referencedFiles()
                files += entry.after.referencedFiles()
            }
        }
        return files
    }

    /** Drops everything, including spill files. */
    public fun clear() {
        undoStack.forEach { it.discard() }
        redoStack.forEach { it.discard() }
        undoStack.clear()
        redoStack.clear()
    }

    private fun nextSpillFile(): File = File(spillDirectory, "patch-${spillCounter++}.bin")
}
