package org.kineograph.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
import org.kineograph.media.video.PlatformEncoder
import org.kineograph.ui.KineographTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as KineographApp
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(AppSettings())
            val dark =
                when (settings.theme) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            val windowSize = calculateWindowSizeClass(this)
            KineographTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                KineographNavHost(app, settings, wideLayout = windowSize.widthSizeClass != WindowWidthSizeClass.Compact)
            }
        }
    }
}

@Composable
private fun KineographNavHost(app: KineographApp, settings: AppSettings, wideLayout: Boolean) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = ROUTE_GALLERY) {
        composable(ROUTE_GALLERY) {
            val model: GalleryViewModel =
                viewModel(factory = factoryOf { GalleryViewModel(app.projectRepository) })
            val state by model.state.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val layerName = stringResource(R.string.default_layer_name)
            GalleryScreen(
                state = state,
                canEncode = PlatformEncoder::canEncodeAtDefaultRate,
                previewSource = app.projectRepository,
                actions =
                    GalleryActions(
                        onOpen = { id -> nav.navigate("editor/$id") },
                        onCreate = { request ->
                            model.create(request, layerName, context.contentResolver) { id -> nav.navigate("editor/$id") }
                        },
                        onRename = model::rename,
                        onDuplicate = model::duplicate,
                        onTrash = model::moveToTrash,
                        onRestore = model::restore,
                        onDeleteForever = model::deletePermanently,
                        onQuery = model::setQuery,
                        onSort = model::setSort,
                        onFilter = model::setFilter,
                        onOpenSettings = { nav.navigate(ROUTE_SETTINGS) },
                    ),
            )
        }
        composable("editor/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val model: EditorViewModel =
                viewModel(
                    factory =
                        factoryOf {
                            EditorViewModel(
                                EditorDependencies(
                                    app.projectRepository,
                                    app.settings,
                                    app.budget,
                                    app.exportManager,
                                    app.files::spillDirectory,
                                    app.appScope,
                                ),
                                id,
                            )
                        },
                )
            DisposableEffect(model) {
                val listener: () -> Unit = model::onLowMemory
                app.lowMemoryListeners += listener
                onDispose { app.lowMemoryListeners -= listener }
            }
            EditorRoute(model, wideLayout, onBack = { nav.popBackStack() })
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(settings, app.settings, onBack = { nav.popBackStack() }, onOpenAbout = { nav.navigate(ROUTE_ABOUT) })
        }
        composable(ROUTE_ABOUT) {
            AboutScreen(BuildConfig.VERSION_NAME, onBack = { nav.popBackStack() })
        }
    }
}

private inline fun <reified T : ViewModel> factoryOf(crossinline create: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
    }

private const val ROUTE_GALLERY = "gallery"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_ABOUT = "about"
