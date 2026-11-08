package org.kineograph.app

import androidx.compose.runtime.Composable
import org.kineograph.feature.ai.AiScreen
import org.kineograph.feature.ai.LocalAi

/** The AI build's way in to the model on the phone. The standard build's AiEntry, in src/standard, has none. */
internal object AiEntry {
    const val AVAILABLE: Boolean = true

    @Volatile
    private var ai: LocalAi? = null

    /** The app's one [LocalAi], made the first time it is wanted. It lets go of the model when the system runs low on memory. */
    fun ai(app: KineographApp): LocalAi =
        ai ?: synchronized(this) {
            ai ?: LocalAi.create(app).also { made ->
                app.lowMemoryListeners += made::unload
                ai = made
            }
        }

    @Composable
    fun Screen(app: KineographApp, onBack: () -> Unit) {
        AiScreen(ai(app), onBack)
    }
}
