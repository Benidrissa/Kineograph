package org.kineograph.model

import kotlin.math.pow

/** One ghost to draw: which frame, how strongly, and on which side of the current frame it falls. */
public data class OnionGhost(val frameIndex: Int, val opacity: Float, val isPrevious: Boolean, val distance: Int)

/**
 * Works out which neighbouring frames to show as ghosts.
 *
 * Ghosts are returned farthest first so nearer ones paint on top. A frame is never shown twice, even
 * when wrapping around a short animation would reach it from both directions; the nearer side wins.
 */
public fun planOnionSkin(settings: OnionSkinSettings, frameCount: Int, currentIndex: Int): List<OnionGhost> {
    if (!settings.enabled || frameCount <= 1 || currentIndex !in 0 until frameCount) return emptyList()
    val previousCount = settings.previousFrames.coerceIn(0, OnionSkinSettings.MAX_GHOSTS_PER_SIDE)
    val nextCount = settings.nextFrames.coerceIn(0, OnionSkinSettings.MAX_GHOSTS_PER_SIDE)
    val seen = HashSet<Int>().apply { add(currentIndex) }
    val ghosts = ArrayList<OnionGhost>(previousCount + nextCount)

    fun consider(distance: Int, isPrevious: Boolean) {
        val raw = if (isPrevious) currentIndex - distance else currentIndex + distance
        val index =
            if (settings.wrapAround) {
                raw.mod(frameCount)
            } else {
                if (raw !in 0 until frameCount) return
                raw
            }
        if (seen.add(index)) {
            val opacity = settings.opacity * settings.falloff.coerceIn(0f, 1f).pow(distance - 1)
            ghosts += OnionGhost(index, opacity.coerceIn(0f, 1f), isPrevious, distance)
        }
    }

    // Walking outward means a frame reachable from both sides is claimed by its nearer side.
    for (distance in 1..maxOf(previousCount, nextCount)) {
        if (distance <= previousCount) consider(distance, isPrevious = true)
        if (distance <= nextCount) consider(distance, isPrevious = false)
    }
    return ghosts.sortedByDescending { it.distance }
}
