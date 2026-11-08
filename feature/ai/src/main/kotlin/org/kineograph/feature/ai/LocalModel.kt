package org.kineograph.feature.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/** Where the model runs. */
public enum class AiBackend { CPU, GPU }

/** A language model loaded and ready, answering one prompt at a time. */
public interface LocalModel : AutoCloseable {
    /** The reply to [prompt], all of it so far each time more arrives. */
    public fun reply(prompt: String): Flow<String>

    /** Stops the reply being written, from any thread. */
    public fun stop()

    /** How many tokens a second the last reply came at, if the model says. */
    public fun lastTokensPerSecond(): Double?
}

/** Loads the model in [file] to run on [backend]. Slow, and may throw: run it off the main thread. */
public fun interface ModelLoader {
    public fun load(file: File, backend: AiBackend, cacheDirectory: File): LocalModel
}

/** Gemma, or any model in LiteRT-LM's format, through LiteRT-LM. */
public object LiteRtLmLoader : ModelLoader {
    override fun load(file: File, backend: AiBackend, cacheDirectory: File): LocalModel {
        cacheDirectory.mkdirs()
        val engine =
            Engine(
                EngineConfig(
                    modelPath = file.path,
                    backend =
                        when (backend) {
                            AiBackend.CPU -> Backend.CPU()
                            AiBackend.GPU -> Backend.GPU()
                        },
                    cacheDir = cacheDirectory.path,
                ),
            )
        try {
            engine.initialize()
        } catch (t: Throwable) {
            engine.close()
            throw t
        }
        return LiteRtLmModel(engine)
    }
}

// The speed figures come from an API LiteRT-LM marks as experimental. A failure there only means no figure.
@OptIn(ExperimentalApi::class)
private class LiteRtLmModel(private val engine: Engine) : LocalModel {
    @Volatile
    private var current: Conversation? = null

    @Volatile
    private var tokensPerSecond: Double? = null

    override fun reply(prompt: String): Flow<String> =
        flow {
            // A new conversation each time, so one prompt's reply never colours the next.
            val conversation = engine.createConversation(ConversationConfig(samplerConfig = SamplerConfig(TOP_K, TOP_P, TEMPERATURE)))
            current = conversation
            try {
                val text = StringBuilder()
                conversation.sendMessageAsync(prompt).collect { message ->
                    text.append(textOf(message))
                    emit(text.toString())
                }
                tokensPerSecond =
                    try {
                        conversation.getBenchmarkInfo().lastDecodeTokensPerSecond.takeIf { it > 0 }
                    } catch (_: RuntimeException) {
                        null
                    }
            } finally {
                current = null
                conversation.close()
            }
        }

    override fun stop() {
        try {
            current?.cancelProcess()
        } catch (_: RuntimeException) {
            // Already finished.
        }
    }

    override fun lastTokensPerSecond(): Double? = tokensPerSecond

    override fun close() {
        engine.close()
    }

    private fun textOf(message: Message): String =
        message.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString("") { it.text }

    private companion object {
        const val TOP_K = 40
        const val TOP_P = 0.95
        const val TEMPERATURE = 0.7
    }
}
