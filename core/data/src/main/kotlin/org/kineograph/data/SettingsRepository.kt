package org.kineograph.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

public enum class ThemeMode { SYSTEM, LIGHT, DARK }

public enum class ToolbarSide { LEFT, RIGHT }

/** The user's app-wide preferences. Nothing here ever leaves the device. */
public data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val toolbarSide: ToolbarSide = ToolbarSide.LEFT,
    val simulateFingerPressure: Boolean = true,
    val defaultSmoothing: Int = 25,
    val lowLatencyDrawing: Boolean = true,
    val sampleCreated: Boolean = false,
)

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

public class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore

    public val settings: Flow<AppSettings> =
        store.data.map { prefs ->
            AppSettings(
                theme = enumValue(prefs[THEME], ThemeMode.SYSTEM),
                dynamicColor = prefs[DYNAMIC_COLOR] ?: false,
                toolbarSide = enumValue(prefs[TOOLBAR_SIDE], ToolbarSide.LEFT),
                simulateFingerPressure = prefs[FINGER_PRESSURE] ?: true,
                defaultSmoothing = prefs[SMOOTHING] ?: 25,
                lowLatencyDrawing = prefs[LOW_LATENCY] ?: true,
                sampleCreated = prefs[SAMPLE_CREATED] ?: false,
            )
        }

    public suspend fun setTheme(theme: ThemeMode) {
        store.edit { it[THEME] = theme.name }
    }

    public suspend fun setDynamicColor(enabled: Boolean) {
        store.edit { it[DYNAMIC_COLOR] = enabled }
    }

    public suspend fun setToolbarSide(side: ToolbarSide) {
        store.edit { it[TOOLBAR_SIDE] = side.name }
    }

    public suspend fun setSimulateFingerPressure(enabled: Boolean) {
        store.edit { it[FINGER_PRESSURE] = enabled }
    }

    public suspend fun setDefaultSmoothing(amount: Int) {
        store.edit { it[SMOOTHING] = amount.coerceIn(0, 100) }
    }

    public suspend fun setLowLatencyDrawing(enabled: Boolean) {
        store.edit { it[LOW_LATENCY] = enabled }
    }

    public suspend fun setSampleCreated() {
        store.edit { it[SAMPLE_CREATED] = true }
    }

    private inline fun <reified T : Enum<T>> enumValue(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val TOOLBAR_SIDE = stringPreferencesKey("toolbar_side")
        val FINGER_PRESSURE = booleanPreferencesKey("finger_pressure")
        val SMOOTHING = intPreferencesKey("smoothing")
        val LOW_LATENCY = booleanPreferencesKey("low_latency")
        val SAMPLE_CREATED = booleanPreferencesKey("sample_created")
    }
}
