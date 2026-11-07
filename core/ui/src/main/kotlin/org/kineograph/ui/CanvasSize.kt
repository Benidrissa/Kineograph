package org.kineograph.ui

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.kineograph.model.CanvasFit
import org.kineograph.model.canvasFit

/**
 * A box for typing one edge of a canvas. The limit on digits is only so that what is typed can always be read as a number:
 * it is more than any bitmap can be wide, so it never decides anything. [CanvasSizeNote] does that.
 */
@Composable
public fun CanvasSizeField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(MAX_EDGE_DIGITS)) },
        label = { Text(label) },
        singleLine = true,
        isError = (value.toIntOrNull() ?: 0) < 1,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/**
 * How a typed size suits this device, or null if it is not a size. It looks again whenever the size changes, and when the user
 * comes back to the app, having perhaps closed the apps they were told to.
 */
@Composable
public fun rememberCanvasFit(width: Int?, height: Int?): CanvasFit? {
    val context = LocalContext.current
    var returns by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        returns++
        onPauseOrDispose { }
    }
    return remember(width, height, returns) {
        if (width == null || height == null || width < 1 || height < 1) {
            null
        } else {
            canvasFit(width, height, availableMemoryBytes(context))
        }
    }
}

/** What is free now. Bitmaps live outside the Java heap on every Android this app runs on, so the heap's own limit does not come into it. */
private fun availableMemoryBytes(context: Context): Long {
    val manager = context.applicationContext.getSystemService(ActivityManager::class.java) ?: return 0L
    return ActivityManager.MemoryInfo().also(manager::getMemoryInfo).availMem
}

/** Says why a size cannot be used, or that it may be slow, and offers one that fits. Says nothing about a size with no problem. */
@Composable
public fun CanvasSizeNote(fit: CanvasFit?, onUseSize: (width: Int, height: Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    when (fit) {
        CanvasFit.Fits -> {
            Unit
        }

        CanvasFit.Large -> {
            NoteText(stringResource(R.string.canvas_size_large), colors.tertiary)
        }

        null -> {
            NoteText(stringResource(R.string.canvas_size_invalid), colors.error)
        }

        is CanvasFit.TooBig -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val reason = if (fit.memoryWouldHelp) R.string.canvas_size_too_big else R.string.canvas_size_too_big_for_android
                NoteText(stringResource(reason), colors.error)
                OutlinedButton(
                    onClick = { onUseSize(fit.suggestedWidth, fit.suggestedHeight) },
                    modifier = Modifier.testTag("canvas_size_use"),
                ) {
                    Text(stringResource(R.string.canvas_size_use, fit.suggestedWidth, fit.suggestedHeight))
                }
            }
        }
    }
}

@Composable
private fun NoteText(text: String, color: Color) {
    // It appears because of what was typed, somewhere else on the screen, so TalkBack has to be told.
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("canvas_size_note"),
    )
}

private const val MAX_EDGE_DIGITS = 9
