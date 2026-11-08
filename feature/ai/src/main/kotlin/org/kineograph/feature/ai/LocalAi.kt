package org.kineograph.feature.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the model is doing. */
public enum class AiPhase { IDLE, LOADING, REPLYING }

/** Why the model could not answer. */
public enum class AiProblem {
    /** The engine's native library is not in this APK, as on 32-bit phones. */
    UNSUPPORTED_PHONE,
    OUT_OF_MEMORY,

    /** The engine would not open the file. */
    LOAD_FAILED,

    /** The engine opened the file but stopped while answering. */
    REPLY_FAILED,
}

/** How long things took, in seconds, and how fast the reply came. */
public data class AiTimings(val loadSeconds: Double?, val firstWordsSeconds: Double?, val tokensPerSecond: Double?)

public data class AiState(
    val model: InstalledModel? = null,
    /** Bytes copied so far, and of how many (-1 when unknown), while a model is imported. */
    val importing: Pair<Long, Long>? = null,
    /** Why the last import did not finish, if it did not. */
    val importProblem: ImportOutcome? = null,
    val backend: AiBackend = AiBackend.CPU,
    val phase: AiPhase = AiPhase.IDLE,
    val reply: String = "",
    val timings: AiTimings? = null,
    val problem: AiProblem? = null,
)

/**
 * The model on this phone, for the whole app: importing it, loading it, and asking it things. Work carries on whatever screen is showing.
 * Everything that touches the engine runs on [worker], one thing at a time, as the engine expects.
 */
public class LocalAi(
    private val store: ModelStore,
    private val loader: ModelLoader,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val clock: () -> Long = System::nanoTime,
) {
    private val _state = MutableStateFlow(AiState(model = store.installed()))
    public val state: StateFlow<AiState> get() = _state

    private var importJob: Job? = null
    private var runJob: Job? = null

    /** Only touched on [worker]. */
    private var loaded: LocalModel? = null
    private var loadedBackend: AiBackend? = null

    @Volatile
    private var replying: LocalModel? = null

    public fun import(source: ModelSource) {
        if (importJob?.isActive == true || runJob?.isActive == true) return
        _state.update { it.copy(importing = 0L to source.bytes, importProblem = null, problem = null) }
        importJob =
            scope.launch {
                try {
                    // The engine may be reading the old file, so it lets go before the new one takes its place.
                    withContext(worker) { unloadNow() }
                    val outcome = store.import(source) { copied -> _state.update { it.copy(importing = copied to source.bytes) } }
                    _state.update {
                        when (outcome) {
                            is ImportOutcome.Done -> it.copy(model = outcome.model, importing = null, reply = "", timings = null)
                            else -> it.copy(importing = null, importProblem = outcome)
                        }
                    }
                } finally {
                    _state.update { if (it.importing != null) it.copy(importing = null) else it }
                }
            }
    }

    public fun cancelImport() {
        importJob?.cancel()
    }

    /** Stops whatever is running, lets go of the model and deletes it. */
    public fun delete() {
        importJob?.cancel()
        stop()
        scope.launch {
            // An import still finishing could put a model back after the delete.
            importJob?.cancelAndJoin()
            runJob?.cancelAndJoin()
            withContext(worker) { unloadNow() }
            store.delete()
            _state.update { AiState(backend = it.backend) }
        }
    }

    public fun chooseBackend(backend: AiBackend) {
        _state.update { it.copy(backend = backend, problem = null) }
    }

    public fun ask(prompt: String) {
        if (runJob?.isActive == true || importJob?.isActive == true || _state.value.model == null) return
        _state.update { it.copy(reply = "", timings = null, problem = null) }
        runJob = scope.launch(worker) { answer(prompt) }
    }

    /** Stops the reply being written. What was written so far stays. */
    public fun stop() {
        replying?.stop()
        runJob?.cancel()
    }

    /** Lets go of the model's memory if it is not answering, as when the system runs low. It loads again next time. */
    public fun unload() {
        if (runJob?.isActive == true) return
        scope.launch(worker) { if (runJob?.isActive != true) unloadNow() }
    }

    private suspend fun answer(prompt: String) {
        val backend = _state.value.backend
        var phase = AiPhase.LOADING
        try {
            var loadSeconds: Double? = null
            val model =
                loaded?.takeIf { loadedBackend == backend } ?: run {
                    unloadNow()
                    _state.update { it.copy(phase = AiPhase.LOADING) }
                    val start = clock()
                    loader.load(store.file, backend, store.cacheDirectory).also {
                        loaded = it
                        loadedBackend = backend
                        loadSeconds = seconds(start)
                    }
                }
            phase = AiPhase.REPLYING
            _state.update { it.copy(phase = AiPhase.REPLYING) }
            val start = clock()
            var firstWords: Double? = null
            replying = model
            try {
                model.reply(prompt).collect { text ->
                    if (firstWords == null && text.isNotEmpty()) firstWords = seconds(start)
                    _state.update { it.copy(reply = text) }
                }
            } finally {
                replying = null
            }
            _state.update { it.copy(phase = AiPhase.IDLE, timings = AiTimings(loadSeconds, firstWords, model.lastTokensPerSecond())) }
        } catch (e: CancellationException) {
            _state.update { it.copy(phase = AiPhase.IDLE) }
            throw e
        } catch (_: LinkageError) {
            fail(AiProblem.UNSUPPORTED_PHONE)
        } catch (_: OutOfMemoryError) {
            fail(AiProblem.OUT_OF_MEMORY)
        } catch (_: Exception) {
            fail(if (phase == AiPhase.LOADING) AiProblem.LOAD_FAILED else AiProblem.REPLY_FAILED)
        }
    }

    private fun fail(problem: AiProblem) {
        unloadNow()
        _state.update { it.copy(phase = AiPhase.IDLE, problem = problem) }
    }

    private fun unloadNow() {
        val model = loaded ?: return
        loaded = null
        loadedBackend = null
        try {
            model.close()
        } catch (_: RuntimeException) {
            // It is going anyway.
        }
    }

    private fun seconds(start: Long): Double = (clock() - start) / NANOS_PER_SECOND

    public companion object {
        private const val NANOS_PER_SECOND = 1e9

        /** The app's one [LocalAi], with the model kept where backups do not go. */
        @OptIn(ExperimentalCoroutinesApi::class)
        public fun create(context: Context): LocalAi =
            LocalAi(
                ModelStore(File(context.noBackupFilesDir, "ai")),
                LiteRtLmLoader,
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                Dispatchers.IO.limitedParallelism(1),
            )
    }
}
