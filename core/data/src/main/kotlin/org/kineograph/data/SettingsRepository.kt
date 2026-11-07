package org.kineograph.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

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

/** Set when a damaged settings file had to be replaced with the defaults, so the user can be told. */
@Volatile
private var settingsWereReset = false

/**
 * A damaged file is replaced rather than reported as an error. Nothing else can repair it, and the only other way out
 * would be clearing the app's data, which would take the user's drawings with it.
 */
internal val settingsCorruptionHandler: ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler {
        settingsWereReset = true
        emptyPreferences()
    }

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = settingsCorruptionHandler,
)

public class SettingsRepository internal constructor(private val store: DataStore<Preferences>) {
    public constructor(context: Context) : this(context.applicationContext.settingsStore)

    /**
     * The current settings, or the defaults when they cannot be read. A storage problem costs the user their
     * preferences for a while, never the app. After that the flow ends, so it shows no later changes until collected again.
     */
    public val settings: Flow<AppSettings> =
        store.data
            .catch { problem -> if (problem is IOException) emit(emptyPreferences()) else throw problem }
            .map { prefs ->
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

    /** Whether a damaged settings file had to be replaced with the defaults since the app started. */
    public fun wereSettingsReset(): Boolean = settingsWereReset

    /** Each setter returns whether the change reached the disk, which a full disk can prevent. */
    public suspend fun setTheme(theme: ThemeMode): Boolean = save { it[THEME] = theme.name }

    public suspend fun setDynamicColor(enabled: Boolean): Boolean = save { it[DYNAMIC_COLOR] = enabled }

    public suspend fun setToolbarSide(side: ToolbarSide): Boolean = save { it[TOOLBAR_SIDE] = side.name }

    public suspend fun setSimulateFingerPressure(enabled: Boolean): Boolean = save { it[FINGER_PRESSURE] = enabled }

    public suspend fun setDefaultSmoothing(amount: Int): Boolean = save { it[SMOOTHING] = amount.coerceIn(0, 100) }

    public suspend fun setLowLatencyDrawing(enabled: Boolean): Boolean = save { it[LOW_LATENCY] = enabled }

    public suspend fun setSampleCreated(): Boolean = save { it[SAMPLE_CREATED] = true }

    private suspend fun save(change: (MutablePreferences) -> Unit): Boolean =
        try {
            store.edit { change(it) }
            true
        } catch (_: IOException) {
            false
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
