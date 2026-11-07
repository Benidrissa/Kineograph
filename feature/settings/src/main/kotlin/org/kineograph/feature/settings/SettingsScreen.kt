package org.kineograph.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import org.kineograph.data.AppSettings
import org.kineograph.data.SettingsRepository
import org.kineograph.data.ThemeMode
import org.kineograph.data.ToolbarSide
import org.kineograph.ui.KgIconButton
import org.kineograph.ui.KgIcons
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun SettingsScreen(settings: AppSettings, repository: SettingsRepository, onBack: () -> Unit, onOpenAbout: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val notSaved = stringResource(R.string.settings_not_saved)

    /** A full disk can stop a setting from being saved, and the user should not have to find that out next time. */
    fun save(change: suspend () -> Boolean) {
        scope.launch { if (!change()) snackbar.showSnackbar(notSaved) }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { KgIconButton(KgIcons.Back, stringResource(R.string.action_back), onBack) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel(stringResource(R.string.settings_section_appearance))
            Text(stringResource(R.string.settings_theme))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChip(
                    R.string.settings_theme_system,
                    settings.theme == ThemeMode.SYSTEM,
                ) { save { repository.setTheme(ThemeMode.SYSTEM) } }
                ThemeChip(
                    R.string.settings_theme_light,
                    settings.theme == ThemeMode.LIGHT,
                ) { save { repository.setTheme(ThemeMode.LIGHT) } }
                ThemeChip(
                    R.string.settings_theme_dark,
                    settings.theme == ThemeMode.DARK,
                ) { save { repository.setTheme(ThemeMode.DARK) } }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow(stringResource(R.string.settings_dynamic_color), settings.dynamicColor) { value ->
                    save { repository.setDynamicColor(value) }
                }
            }
            HorizontalDivider()

            SectionLabel(stringResource(R.string.settings_section_drawing))
            Text(stringResource(R.string.settings_toolbar_side))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChip(R.string.settings_side_left, settings.toolbarSide == ToolbarSide.LEFT) {
                    save { repository.setToolbarSide(ToolbarSide.LEFT) }
                }
                ThemeChip(R.string.settings_side_right, settings.toolbarSide == ToolbarSide.RIGHT) {
                    save { repository.setToolbarSide(ToolbarSide.RIGHT) }
                }
            }
            Text(
                stringResource(R.string.settings_toolbar_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow(stringResource(R.string.settings_finger_pressure), settings.simulateFingerPressure) { value ->
                save { repository.setSimulateFingerPressure(value) }
            }
            SmoothingRow(settings.defaultSmoothing) { value -> save { repository.setDefaultSmoothing(value) } }
            SwitchRow(stringResource(R.string.settings_low_latency), settings.lowLatencyDrawing) { value ->
                save { repository.setLowLatencyDrawing(value) }
            }
            Text(
                stringResource(R.string.settings_low_latency_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()

            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onOpenAbout),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ThemeChip(label: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(stringResource(label)) })
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SmoothingRow(value: Int, onChange: (Int) -> Unit) {
    var shown by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val label = stringResource(R.string.settings_default_smoothing)
    Column {
        Text("$label: ${shown.roundToInt()}")
        Slider(
            value = shown,
            onValueChange = { shown = it },
            onValueChangeFinished = { onChange(shown.roundToInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/** What the app is, its licence, and where its code and its components come from. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun AboutScreen(versionName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = { KgIconButton(KgIcons.Back, stringResource(R.string.action_back), onBack) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.about_app_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.about_version, versionName))
            Text(stringResource(R.string.about_summary))
            Text(stringResource(R.string.about_privacy))
            Text(stringResource(R.string.about_license))
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri()))
                        } catch (_: ActivityNotFoundException) {
                            // No browser on this device. The address is shown right here anyway.
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(SOURCE_URL, color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider()
            Text(stringResource(R.string.about_third_party), style = MaterialTheme.typography.titleSmall)
            THIRD_PARTY.forEach { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private const val SOURCE_URL = "https://github.com/Benidrissa/Kineograph"

private val THIRD_PARTY =
    listOf(
        "AndroidX and Jetpack Compose: Apache-2.0",
        "AndroidX Graphics Core: Apache-2.0",
        "AndroidX Input Motion Prediction: Apache-2.0",
        "AndroidX Room, DataStore and WorkManager: Apache-2.0",
        "Kotlin, Kotlin Coroutines and Kotlin Serialization: Apache-2.0",
    )
