package org.kineograph.feature.editor

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kineograph.media.svg.SvgDocument
import org.kineograph.media.svg.SvgParseException
import org.kineograph.media.svg.SvgParser
import org.kineograph.media.svg.SvgWarning
import org.kineograph.media.svgimport.SvgFit
import org.kineograph.media.svgimport.SvgImportOutcome
import org.kineograph.media.svgimport.SvgImportPlace
import org.kineograph.media.svgimport.SvgImportRequest
import org.kineograph.media.svgimport.SvgImporter
import org.kineograph.media.svgimport.SvgSampling
import org.kineograph.media.svgimport.roomForImportedFrames
import org.kineograph.media.svgimport.sampleStartSeconds
import org.kineograph.media.svgimport.suggestedSeconds
import org.kineograph.model.Project
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** A picture that has been read and is waiting for the user's choices. */
internal class SvgFile(val document: SvgDocument, val name: String, val sizeBytes: Long) {
    /** A file with animation that never gets going or changes nothing can only be a still picture. */
    val canAnimate: Boolean get() = document.animation.isAnimated && document.animation.durationSeconds > 0.0

    /** The last moment worth picking for a single image. */
    val lastMomentSeconds: Double get() = document.animation.sampleStartSeconds + document.animation.durationSeconds

    /** What to call a layer holding this picture. */
    val layerName: String
        get() {
            val lowered = name.lowercase()
            val cut = if (lowered.endsWith(".svg") || lowered.endsWith(".svgz")) name.substringBeforeLast('.') else name
            return cut.ifBlank { name }.take(MAX_LAYER_NAME_LENGTH)
        }

    private companion object {
        const val MAX_LAYER_NAME_LENGTH = 80
    }
}

/**
 * What the user has picked so far. [durationText] stays null until they type something, so that the suggested duration is used exactly as the
 * file gives it, rather than as it was rounded for the screen. That matters for a seamless loop.
 */
internal data class SvgImportChoices(
    val asAnimation: Boolean,
    val durationText: String? = null,
    val momentSeconds: Double = 0.0,
    val place: SvgImportPlace = SvgImportPlace.NEW_LAYER,
    val fit: SvgFit = SvgFit.FIT_INSIDE,
    val withIntro: Boolean = true,
) {
    /** How long the animation will be. Null if what was typed is not a length of time. */
    fun durationSeconds(file: SvgFile): Double? =
        if (durationText == null) {
            file.document.animation.suggestedSeconds(withIntro)
        } else {
            parseSeconds(durationText)
        }

    /** The moments the import will sample. Null if what was typed is not a length of time. */
    fun sampling(file: SvgFile, fps: Int): SvgSampling? =
        durationSeconds(file)?.let { SvgSampling.of(file.document.animation, it, fps, withIntro) }

    /** Whether this comes to more frames than the device can keep track of, which holds Import back until the duration is shorter. */
    fun isTooLong(file: SvgFile, project: Project): Boolean =
        asAnimation && (sampling(file, project.fps)?.tickCount ?: 0) > roomForImportedFrames(project)

    fun toRequest(file: SvgFile): SvgImportRequest? =
        if (asAnimation) {
            durationSeconds(file)?.let { SvgImportRequest.Animation(it, fit, file.layerName, withIntro) }
        } else {
            SvgImportRequest.SingleImage(momentSeconds, place, fit, file.layerName)
        }

    companion object {
        fun startingFor(file: SvgFile): SvgImportChoices = SvgImportChoices(asAnimation = file.canAnimate)

        /** French writes a decimal comma, and a keyboard may offer either. */
        fun parseSeconds(text: String): Double? = text
            .trim()
            .replace(',', '.')
            .toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
    }
}

internal sealed interface SvgImportState {
    data object Idle : SvgImportState

    data object Reading : SvgImportState

    data class Ready(val file: SvgFile, val choices: SvgImportChoices, val problem: SvgImportOutcome? = null) : SvgImportState

    data class Running(val file: SvgFile, val choices: SvgImportChoices, val done: Int = 0, val total: Int = 1) : SvgImportState
}

/**
 * Takes one SVG file from being picked to being in the project, or to being given up on, for the screen to show. All the slow work happens away
 * from the main thread.
 *
 * @param makeRoom called before an import starts, while the sweep can still clear out what is left of one that never got to finish.
 * @param holdFiles called before an import writes anything, and [releaseFiles] once the result is settled. In between, what it writes must be
 *   safe from the sweep.
 * @param onMessage for what has to be said when no sheet is open to say it in.
 */
internal class SvgImportController(
    private val scope: CoroutineScope,
    private val importer: SvgImporter,
    private val makeRoom: suspend () -> Unit,
    private val holdFiles: () -> Unit,
    private val releaseFiles: () -> Unit,
    private val onMessage: (EditorMessage) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<SvgImportState>(SvgImportState.Idle)
    val state: StateFlow<SvgImportState> get() = _state

    private var job: Job? = null

    /** Reads a picked file. [fallbackName] is for a file whose provider will not say what it is called. */
    fun open(uri: Uri, resolver: ContentResolver, fallbackName: String) {
        read({ displayName(uri, resolver) ?: fallbackName }, { resolver.openInputStream(uri) })
    }

    fun read(name: () -> String, open: () -> InputStream?) {
        if (_state.value != SvgImportState.Idle) return
        _state.value = SvgImportState.Reading
        job =
            scope.launch {
                when (val result = withContext(io) { readFile(name, open) { !isActive } }) {
                    is ReadResult.Read -> {
                        _state.value = SvgImportState.Ready(result.file, SvgImportChoices.startingFor(result.file))
                    }

                    is ReadResult.Problem -> {
                        onMessage(result.message)
                        _state.value = SvgImportState.Idle
                    }
                }
            }
    }

    private fun readFile(name: () -> String, open: () -> InputStream?, cancelled: () -> Boolean): ReadResult =
        try {
            val stream = open() ?: throw IOException("The provider gave no stream")
            val counted = CountingStream(stream)
            val document = counted.use { SvgParser.parse(it, shouldStop = cancelled) }
            ReadResult.Read(SvgFile(document, name(), counted.count))
        } catch (e: SvgParseException) {
            ReadResult.Problem(e.reason.message())
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            ReadResult.Problem(EditorMessage.SVG_TOO_LARGE)
        } catch (_: Exception) {
            // Each document provider is another app, and fails in its own way: SecurityException, IllegalArgumentException, and so on.
            ReadResult.Problem(EditorMessage.SVG_UNREADABLE)
        }

    private fun SvgParseException.Reason.message(): EditorMessage =
        when (this) {
            SvgParseException.Reason.EMPTY -> EditorMessage.SVG_EMPTY
            SvgParseException.Reason.READ_FAILED -> EditorMessage.SVG_UNREADABLE
            SvgParseException.Reason.TOO_LARGE, SvgParseException.Reason.TOO_COMPLEX -> EditorMessage.SVG_TOO_LARGE
            SvgParseException.Reason.CORRUPT_COMPRESSION, SvgParseException.Reason.MALFORMED -> EditorMessage.SVG_DAMAGED
            SvgParseException.Reason.NOT_SVG -> EditorMessage.SVG_NOT_SVG
        }

    private fun displayName(uri: Uri, resolver: ContentResolver): String? {
        val fromProvider =
            try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            } catch (_: RuntimeException) {
                // The name is a nicety. The picture may read perfectly well.
                null
            }
        return fromProvider?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    fun change(choices: SvgImportChoices) {
        _state.update { if (it is SvgImportState.Ready) it.copy(choices = choices, problem = null) else it }
    }

    fun dismiss() {
        if (_state.value is SvgImportState.Ready) _state.value = SvgImportState.Idle
    }

    /** Stops whatever is under way. Reading and drawing stop within moments, and the project is left as it was. */
    fun cancel() {
        job?.cancel()
        // Nothing is waiting for it now, so nobody needs to watch it finish.
        if (_state.value == SvgImportState.Reading) _state.value = SvgImportState.Idle
    }

    fun start() {
        val ready = _state.value as? SvgImportState.Ready ?: return
        val request = ready.choices.toRequest(ready.file) ?: return
        val warningsBefore = ready.file.document.warnings
        _state.value = SvgImportState.Running(ready.file, ready.choices)
        job =
            scope.launch {
                try {
                    makeRoom()
                    holdFiles()
                    try {
                        val outcome =
                            importer.import(ready.file.document, request) { done, total ->
                                _state.update { if (it is SvgImportState.Running) it.copy(done = done, total = total) else it }
                            }
                        if (outcome == SvgImportOutcome.DONE) {
                            _state.value = SvgImportState.Idle
                            val wasCut = SvgWarning.LIMIT_REACHED in ready.file.document.warnings - warningsBefore
                            if (wasCut) onMessage(EditorMessage.SVG_IMPORTED_INCOMPLETE)
                        } else {
                            _state.value = ready.copy(problem = outcome)
                        }
                    } finally {
                        releaseFiles()
                    }
                } catch (e: CancellationException) {
                    _state.value = ready.copy(problem = null)
                    throw e
                }
            }
    }

    private sealed interface ReadResult {
        data class Read(val file: SvgFile) : ReadResult

        data class Problem(val message: EditorMessage) : ReadResult
    }

    /** Counts what the parser reads, which saves reading the whole file a second time for its size. */
    private class CountingStream(source: InputStream) : FilterInputStream(source) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }
}
