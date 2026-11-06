package org.kineograph.engine.session

import android.app.ActivityManager
import android.content.Context

/** Memory limits, derived from what the device says it can spare. */
public data class MemoryBudget(
    /** RAM for encoded cel images, so that switching back to a recent frame does not touch the disk. */
    val compressedCelCacheBytes: Long,
    /** RAM for undo patches. Beyond this they spill to disk. */
    val historyMemoryBytes: Long,
    /** Disk space for undo patches. */
    val historyDiskBytes: Long,
    /** Memory for frames decoded ahead for playback. */
    val playbackCacheBytes: Long,
    /** Longest edge of a cached onion-skin ghost. */
    val ghostMaxEdge: Int,
    /** The margin around the canvas you can draw into is the shorter canvas edge divided by this. */
    val overscanDivisor: Int,
    val isLowRam: Boolean,
) {
    /** The drawable margin outside the canvas, in pixels. */
    public fun overscanFor(width: Int, height: Int): Int = minOf(width, height) / overscanDivisor

    public companion object {
        private const val MEGABYTE = 1024L * 1024L
        private const val HISTORY_MIN_STEPS_BUDGET_MB = 24L

        /** Pure, so that the sizing rules can be unit-tested. [memoryClassMb] comes from ActivityManager. */
        public fun forMemoryClass(memoryClassMb: Int, isLowRam: Boolean): MemoryBudget {
            val heap = memoryClassMb.toLong().coerceAtLeast(32L)
            val share = if (isLowRam) 2L else 1L
            return MemoryBudget(
                compressedCelCacheBytes = heap * MEGABYTE / 4 / share,
                historyMemoryBytes = maxOf(HISTORY_MIN_STEPS_BUDGET_MB, heap / 3 / share) * MEGABYTE,
                historyDiskBytes = 512 * MEGABYTE,
                playbackCacheBytes = heap * MEGABYTE / share,
                ghostMaxEdge = if (isLowRam) 640 else 1280,
                overscanDivisor = if (isLowRam) 16 else 8,
                isLowRam = isLowRam,
            )
        }

        public fun forDevice(context: Context): MemoryBudget {
            val manager = context.getSystemService(ActivityManager::class.java)
            return if (manager == null) {
                forMemoryClass(128, isLowRam = true)
            } else {
                forMemoryClass(manager.memoryClass, manager.isLowRamDevice)
            }
        }
    }
}
