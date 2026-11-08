package org.kineograph.feature.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LocalAiTest {
    @get:Rule
    val temp = TemporaryFolder()

    private class FakeModel(private val reply: (String) -> Flow<String>) : LocalModel {
        var closed = false
        var stopped = false

        override fun reply(prompt: String): Flow<String> = reply.invoke(prompt)

        override fun stop() {
            stopped = true
        }

        override fun lastTokensPerSecond(): Double = 21.5

        override fun close() {
            closed = true
        }
    }

    private class FakeLoader : ModelLoader {
        val loads = ArrayList<AiBackend>()
        val models = ArrayList<FakeModel>()
        var failure: Throwable? = null
        var reply: (String) -> Flow<String> = { prompt ->
            flow {
                emit("You said")
                emit("You said: $prompt")
            }
        }

        override fun load(file: File, backend: AiBackend, cacheDirectory: File): LocalModel {
            loads += backend
            failure?.let { throw it }
            assertTrue(file.isFile)
            return FakeModel(reply).also { models += it }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loader = FakeLoader()
    private lateinit var store: ModelStore
    private lateinit var ai: LocalAi

    private fun modelSource() = ModelSource("gemma.litertlm", 1024L + 8) { ByteArrayInputStream(ModelStore.MAGIC + ByteArray(1024)) }

    @Before
    fun setUp() {
        store = ModelStore(temp.newFolder(), Dispatchers.IO) { Long.MAX_VALUE }
        runBlocking { store.import(modelSource()) }
        ai = LocalAi(store, loader, scope, Dispatchers.IO.limitedParallelism(1))
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun awaitState(test: (AiState) -> Boolean): AiState = runBlocking { withTimeout(10_000) { ai.state.first(test) } }

    @Test
    fun `asking loads the model once and gives the whole reply`() {
        ai.ask("hello")
        val first = awaitState { it.phase == AiPhase.IDLE && it.timings != null }
        assertEquals("You said: hello", first.reply)
        assertNotNull(first.timings?.loadSeconds)
        assertEquals(21.5, first.timings?.tokensPerSecond)

        ai.ask("again")
        val second = awaitState { it.reply == "You said: again" && it.timings != null }

        assertEquals(listOf(AiBackend.CPU), loader.loads)
        assertNull(second.timings?.loadSeconds)
    }

    @Test
    fun `the other backend loads the model again on it, and lets go of the first`() {
        ai.ask("one")
        awaitState { it.timings != null }

        ai.chooseBackend(AiBackend.GPU)
        ai.ask("two")
        awaitState { it.reply == "You said: two" && it.timings != null }

        assertEquals(listOf(AiBackend.CPU, AiBackend.GPU), loader.loads)
        assertTrue(loader.models[0].closed)
    }

    @Test
    fun `a model that will not load says so`() {
        loader.failure = IllegalStateException("bad file")

        ai.ask("hello")

        assertEquals(AiProblem.LOAD_FAILED, awaitState { it.problem != null }.problem)
        assertEquals(AiPhase.IDLE, ai.state.value.phase)
    }

    @Test
    fun `a missing native library means this phone cannot run it`() {
        loader.failure = UnsatisfiedLinkError("no liblitertlm_jni.so")

        ai.ask("hello")

        assertEquals(AiProblem.UNSUPPORTED_PHONE, awaitState { it.problem != null }.problem)
    }

    @Test
    fun `a reply that breaks part way is told apart from a model that will not load`() {
        loader.reply = {
            flow {
                emit("Half")
                throw IllegalStateException("decode failed")
            }
        }

        ai.ask("hello")

        val state = awaitState { it.problem != null }
        assertEquals(AiProblem.REPLY_FAILED, state.problem)
        assertTrue(loader.models.single().closed)
    }

    @Test
    fun `stop ends the reply where it was`() {
        val started = CompletableDeferred<Unit>()
        loader.reply = {
            flow {
                emit("The ball")
                started.complete(Unit)
                awaitCancellation()
            }
        }
        ai.ask("hello")
        runBlocking { withTimeout(10_000) { started.await() } }

        ai.stop()

        val state = awaitState { it.phase == AiPhase.IDLE }
        assertEquals("The ball", state.reply)
        assertTrue(loader.models.single().stopped)
    }

    @Test
    fun `deleting lets go of the model and removes it`() {
        ai.ask("hello")
        awaitState { it.timings != null }

        ai.delete()

        awaitState { it.model == null }
        assertTrue(loader.models.single().closed)
        assertNull(store.installed())
    }

    @Test
    fun `nothing is asked of a model that is not there`() {
        ai.delete()
        awaitState { it.model == null }

        ai.ask("hello")

        Thread.sleep(200)
        assertEquals(emptyList<AiBackend>(), loader.loads)
    }

    @Test
    fun `an imported model shows, ready to try`() {
        ai.delete()
        awaitState { it.model == null }

        ai.import(modelSource())

        assertEquals(InstalledModel("gemma.litertlm", 1032), awaitState { it.model != null && it.importing == null }.model)
    }

    @Test
    fun `a refused import says why`() {
        ai.import(ModelSource("photo.jpg", 4) { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) })

        assertEquals(ImportOutcome.NotAModel, awaitState { it.importProblem != null }.importProblem)
    }
}
