package org.kineograph.app

import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.MutableStateFlow
import org.kineograph.data.AppSettings
import org.kineograph.data.ThemeMode
import org.kineograph.feature.editor.EditorDependencies
import org.kineograph.feature.editor.EditorRoute
import org.kineograph.feature.editor.EditorViewModel
import org.kineograph.feature.gallery.GalleryActions
import org.kineograph.feature.gallery.GalleryScreen
import org.kineograph.feature.gallery.GalleryViewModel
import org.kineograph.feature.settings.AboutScreen
import org.kineograph.feature.settings.SettingsScreen
import org.kineograph.media.export.ExportWorker
import org.kineograph.media.export.GalleryWriter
import org.kineograph.media.video.PlatformEncoder
import org.kineograph.ui.KineographTheme

class MainActivity : ComponentActivity() {
    /** A project to go to, when a notification about its export was tapped. */
    private val requestedProject = MutableStateFlow<String?>(null)

    /**
     * What the theme came out as, going by the user's choice. The system's own light or dark says nothing about that. The bars' styles
     * read this when applied, and the library goes on applying the first styles it was given at each configuration change, such as a
     * rotation, so they cannot simply hold the answer.
     */
    private var appIsDark: Boolean? = null

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isDark: (Resources) -> Boolean = { resources ->
            appIsDark ?: (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        }
        val statusBars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT, isDark)
        val navigationBars = SystemBarStyle.auto(NAVIGATION_SCRIM_LIGHT, NAVIGATION_SCRIM_DARK, isDark)
        enableEdgeToEdge(statusBars, navigationBars)
        val app = application as KineographApp
        // Only a fresh start counts. The system hands the same intent back when it recreates this screen, and when the app
        // is reopened from Recents, by which time the user may have moved on.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) takeRequestedProject(intent)
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(AppSettings())
            val dark =
                when (settings.theme) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            DisposableEffect(dark) {
                appIsDark = dark
                enableEdgeToEdge(statusBars, navigationBars)
                onDispose { }
            }
            val windowSize = calculateWindowSizeClass(this)
            KineographTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                // The window's own background goes by the system's theme, and would otherwise show through as screens cross-fade.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    KineographNavHost(app, settings, windowSize.widthSizeClass != WindowWidthSizeClass.Compact, requestedProject)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeRequestedProject(intent)
    }

    private fun takeRequestedProject(intent: Intent) {
        ExportWorker.requestedProject(intent)?.let { requestedProject.value = it }
    }
}

@Composable
private fun KineographNavHost(app: KineographApp, settings: AppSettings, wideLayout: Boolean, requestedProject: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    // Two editors on one project would overwrite each other's work, so there is only ever one, right above the gallery.
    val openProject: (String) -> Unit = { id -> nav.navigate("editor/$id") { popUpTo(ROUTE_GALLERY) } }
    NavHost(navController = nav, startDestination = ROUTE_GALLERY) {
        composable(ROUTE_GALLERY) {
            val model: GalleryViewModel =
                viewModel(factory = factoryOf { GalleryViewModel(app.projectRepository) })
            val state by model.state.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val layerName = stringResource(R.string.default_layer_name)
            LaunchedEffect(model) { app.startupNotices.collect(model::notify) }
            GalleryScreen(
                state = state,
                canEncode = PlatformEncoder::canEncodeAtDefaultRate,
                previewSource = app.projectRepository,
                messages = model.messages,
                actions =
                    GalleryActions(
                        // A double tap must not open two editors on one project: the older would overwrite the newer's work.
                        onOpen = { id ->
                            if (nav.currentBackStackEntry?.destination?.route == ROUTE_GALLERY) nav.navigate("editor/$id")
                        },
                        onCreate = { request ->
                            model.create(request, layerName, context.contentResolver) { id, backgroundFailed ->
                                nav.navigate("editor/$id?$ARGUMENT_BACKGROUND_FAILED=$backgroundFailed")
                            }
                        },
                        onRename = model::rename,
                        onDuplicate = model::duplicate,
                        onTrash = model::moveToTrash,
                        onRestore = model::restore,
                        onDeleteForever = model::deletePermanently,
                        onQuery = model::setQuery,
                        onSort = model::setSort,
                        onFilter = model::setFilter,
                        onOpenSettings = dropUnlessResumed { nav.navigate(ROUTE_SETTINGS) },
                    ),
            )
        }
        composable(
            ROUTE_EDITOR,
            arguments =
                listOf(
                    navArgument(ARGUMENT_ID) { type = NavType.StringType },
                    navArgument(ARGUMENT_BACKGROUND_FAILED) {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
        ) { entry ->
            val model: EditorViewModel = viewModel(factory = editorFactory(app, entry))
            DisposableEffect(model) {
                val listener: () -> Unit = model::onLowMemory
                app.lowMemoryListeners += listener
                onDispose { app.lowMemoryListeners -= listener }
            }
            val back = dropUnlessResumed { nav.popBackStack() }
            EditorRoute(model, wideLayout, onBack = { model.leavingFor?.let(openProject) ?: back() })
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                settings,
                app.settings,
                onBack = dropUnlessResumed { nav.popBackStack() },
                onOpenAbout = dropUnlessResumed { nav.navigate(ROUTE_ABOUT) },
            )
        }
        composable(ROUTE_ABOUT) {
            AboutScreen(BuildConfig.VERSION_NAME, onBack = dropUnlessResumed { nav.popBackStack() })
        }
    }
    val project by requestedProject.collectAsStateWithLifecycle()
    LaunchedEffect(project) {
        val id = project ?: return@LaunchedEffect
        requestedProject.value = null
        val entry = nav.currentBackStackEntry
        if (entry?.destination?.route == ROUTE_EDITOR) {
            // Its unsaved work is the user's to decide about, as it is for Back, so the editor has the last word on closing.
            val editor = ViewModelProvider(entry, editorFactory(app, entry))[EditorViewModel::class.java]
            editor.requestLeaveFor(id) { editor.leavingFor?.let(openProject) }
        } else {
            openProject(id)
        }
    }
}

/** The one place an editor is made, so that the screen and a request from outside it find the same one on [entry]. */
private fun editorFactory(app: KineographApp, entry: NavBackStackEntry): ViewModelProvider.Factory {
    val id = entry.arguments?.getString(ARGUMENT_ID).orEmpty()
    return factoryOf {
        EditorViewModel(
            EditorDependencies(
                app.projectRepository,
                app.settings,
                app.budget,
                app.exportManager,
                app.files::spillDirectory,
                app.appScope,
                { file, format -> GalleryWriter.save(app, file, format) },
            ),
            id,
            backgroundFailed = entry.arguments?.getBoolean(ARGUMENT_BACKGROUND_FAILED) == true,
        )
    }
}

private inline fun <reified T : ViewModel> factoryOf(crossinline create: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
    }

private const val ROUTE_GALLERY = "gallery"
private const val ARGUMENT_ID = "id"
private const val ARGUMENT_BACKGROUND_FAILED = "backgroundFailed"
private const val ROUTE_EDITOR = "editor/{$ARGUMENT_ID}?$ARGUMENT_BACKGROUND_FAILED={$ARGUMENT_BACKGROUND_FAILED}"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_ABOUT = "about"

/** What the library uses by default, so that the navigation bar looks as it does in other apps. */
private val NAVIGATION_SCRIM_LIGHT = Color.argb(230, 255, 255, 255)
private val NAVIGATION_SCRIM_DARK = Color.argb(128, 27, 27, 27)
