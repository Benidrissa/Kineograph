package org.kineograph.app

import androidx.compose.runtime.Composable

/** The standard build has no AI. The AI build's AiEntry, in src/ai, adds the screen for the model on the phone. */
internal object AiEntry {
    const val AVAILABLE: Boolean = false

    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun Screen(app: KineographApp, onBack: () -> Unit) = Unit
}
