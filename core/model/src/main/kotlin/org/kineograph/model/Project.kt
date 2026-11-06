package org.kineograph.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Lowest and highest frame rates a project may use. */
public const val MIN_FPS: Int = 1
public const val MAX_FPS: Int = 60
public const val DEFAULT_FPS: Int = 12

/**
 * An animation project. Immutable: every edit produces a new value, which is what makes structural
 * undo a matter of restoring an earlier snapshot.
 *
 * [cels] is not in the specification's sketch of this class. It holds the pixels' addresses, and it
 * lives here so that a single [Project] value fully describes a project (see docs/decisions.md).
 */
@Serializable
public data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    val background: Background,
    val layers: List<Layer>,
    val frames: List<FrameSlot>,
    val groups: List<FrameGroup> = emptyList(),
    val audioTracks: List<AudioTrack> = emptyList(),
    val settings: ProjectSettings = ProjectSettings(),
    @Serializable(with = CelMapSerializer::class)
    val cels: Map<CelKey, Cel> = emptyMap(),
) {
    /** Looks up the cel for one layer on one frame, or null when that cel is empty. */
    public fun cel(layerId: String, frameId: String): Cel? = cels[CelKey(layerId, frameId)]

    /** Every cel file this project refers to. */
    public fun referencedFiles(): Set<String> = cels.values.mapNotNullTo(HashSet()) { it.file }

    public fun layer(id: String): Layer? = layers.firstOrNull { it.id == id }

    public fun frameIndex(id: String): Int = frames.indexOfFirst { it.id == id }
}

/** A drawing layer. [opacity] is 0..1. Layers are stored bottom to top in [Project.layers]. */
@Serializable
public data class Layer(
    val id: String,
    val name: String,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val opacity: Float = 1f,
    val blendMode: BlendMode = BlendMode.NORMAL,
    val alphaLock: Boolean = false,
    val clipToBelow: Boolean = false,
    val isReference: Boolean = false,
)

/** One position on the timeline. [hold] is how many ticks the drawing stays on screen, at least 1. */
@Serializable
public data class FrameSlot(val id: String, val hold: Int = 1)

/** Identifies a cel: one layer on one frame. */
@Serializable
public data class CelKey(val layerId: String, val frameId: String)

/**
 * The pixels of one layer on one frame. [file] is relative to the project directory and the file it
 * names is never modified, so several cels can safely share one file. [bounds] is where the cropped
 * image sits in canvas coordinates; it may extend past the canvas because drawing can run off the
 * edge. An empty cel has no entry at all in [Project.cels].
 */
@Serializable
public data class Cel(val layerId: String, val frameId: String, val file: String?, val bounds: IntRect?)

@Serializable
public data class AudioTrack(
    val id: String,
    val name: String,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val clips: List<AudioClip> = emptyList(),
)

@Serializable
public data class AudioClip(
    val id: String,
    val file: String,
    val startFrame: Int,
    val inOffsetMs: Long,
    val durationMs: Long,
    val gain: Float = 1f,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
)

@Serializable
public data class FrameGroup(
    val id: String,
    val name: String,
    val startFrameId: String,
    val endFrameId: String,
    val loopCount: Int = 1,
)

/** What sits behind every layer. It is drawn by the compositor and is not itself a layer. */
@Serializable
public sealed interface Background {
    @Serializable
    @SerialName("color")
    public data class Color(val argb: Int) : Background

    /** [file] is relative to the project directory, normally under `media/`. */
    @Serializable
    @SerialName("image")
    public data class Image(val file: String) : Background

    @Serializable
    @SerialName("paper")
    public data class Paper(val kind: PaperKind) : Background

    @Serializable
    @SerialName("transparent")
    public data object Transparent : Background
}

@Serializable
public enum class PaperKind { WHITE, OFF_WHITE, GRID, DOTS, DARK }

@Serializable
public enum class BlendMode {
    NORMAL,
    MULTIPLY,
    SCREEN,
    OVERLAY,
    ADD,
    DARKEN,
    LIGHTEN,
    COLOR_DODGE,
    COLOR_BURN,
    DIFFERENCE,
}

@Serializable
public data class ProjectSettings(
    val onionSkin: OnionSkinSettings = OnionSkinSettings(),
    val grid: GridSettings = GridSettings(),
    val playbackMode: PlaybackMode = PlaybackMode.LOOP,
)

@Serializable
public data class OnionSkinSettings(
    val enabled: Boolean = false,
    val previousFrames: Int = 1,
    val nextFrames: Int = 0,
    val opacity: Float = 0.35f,
    val falloff: Float = 0.6f,
    val tint: Boolean = true,
    val currentLayerOnly: Boolean = false,
    val wrapAround: Boolean = false,
    val showDuringPlayback: Boolean = false,
) {
    public companion object {
        public const val MAX_GHOSTS_PER_SIDE: Int = 5
    }
}

@Serializable
public data class GridSettings(val enabled: Boolean = false, val spacingPx: Int = 64)

@Serializable
public enum class PlaybackMode { LOOP, PING_PONG, ONCE }

/** An axis-aligned rectangle with integer edges. [right] and [bottom] are exclusive. */
@Serializable
public data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    public val width: Int get() = right - left
    public val height: Int get() = bottom - top
    public val isEmpty: Boolean get() = right <= left || bottom <= top

    public fun area(): Long = if (isEmpty) 0 else width.toLong() * height.toLong()

    public fun union(other: IntRect): IntRect =
        when {
            isEmpty -> other
            other.isEmpty -> this
            else -> IntRect(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))
        }

    /** The overlap with [other], or [EMPTY] when they do not touch. */
    public fun intersect(other: IntRect): IntRect {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return if (r <= l || b <= t) EMPTY else IntRect(l, t, r, b)
    }

    public fun translated(dx: Int, dy: Int): IntRect = IntRect(left + dx, top + dy, right + dx, bottom + dy)

    public fun inflated(by: Int): IntRect = IntRect(left - by, top - by, right + by, bottom + by)

    public fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

    public companion object {
        public val EMPTY: IntRect = IntRect(0, 0, 0, 0)
    }
}

/** Writes the cel map as a plain list, which keeps `manifest.json` easy for other tools to read. */
internal object CelMapSerializer : KSerializer<Map<CelKey, Cel>> {
    private val delegate = ListSerializer(Cel.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Map<CelKey, Cel>) {
        delegate.serialize(encoder, value.values.toList())
    }

    override fun deserialize(decoder: Decoder): Map<CelKey, Cel> = delegate
        .deserialize(
            decoder,
        ).associateBy { CelKey(it.layerId, it.frameId) }
}
