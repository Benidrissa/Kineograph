package org.kineograph.feature.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kineograph.data.ProjectRepository
import org.kineograph.data.ProjectSummary
import org.kineograph.engine.compositor.FlattenOptions
import org.kineograph.engine.compositor.FrameFlattener
import org.kineograph.model.Timeline
import org.kineograph.model.TimelineIndex
import org.kineograph.ui.KgIconButton
import org.kineograph.ui.KgIcons
import java.io.File

/** The home screen: every project, plus the way to start a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun GalleryScreen(
    state: GalleryUiState,
    canEncode: (Int, Int) -> Boolean,
    actions: GalleryActions,
    previewSource: ProjectRepository? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showNewProject by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var confirmDelete by remember { mutableStateOf<ProjectSummary?>(null) }
    val movedToTrash = stringResource(R.string.gallery_moved_to_trash)
    val undo = stringResource(R.string.action_undo)
    val copyOf = stringResource(R.string.copy_of)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_title)) },
                actions = {
                    KgIconButton(KgIcons.Search, stringResource(R.string.gallery_search), { searching = !searching }, selected = searching)
                    SortMenu(state.sort, actions.onSort)
                    KgIconButton(KgIcons.Settings, stringResource(R.string.gallery_settings), actions.onOpenSettings)
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewProject = true },
                icon = { Icon(KgIcons.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.gallery_new)) },
                modifier = Modifier.testTag("new_project_button"),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (searching) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = actions.onQuery,
                    placeholder = { Text(stringResource(R.string.gallery_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("search_field"),
                )
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.filter == GalleryFilter.ALL,
                    onClick = { actions.onFilter(GalleryFilter.ALL) },
                    label = { Text(stringResource(R.string.gallery_projects)) },
                )
                FilterChip(
                    selected = state.filter == GalleryFilter.TRASH,
                    onClick = { actions.onFilter(GalleryFilter.TRASH) },
                    label = { Text(stringResource(R.string.gallery_trash)) },
                )
            }

            if (state.isLoaded && state.all.isEmpty()) {
                EmptyMessage(state)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().testTag("project_grid"),
                ) {
                    val cardActions =
                        CardActions(
                            onOpen = actions.onOpen,
                            onRename = { renaming = it },
                            onDuplicate = { actions.onDuplicate(it.id, "$copyOf ${it.name}") },
                            onTrash = { project ->
                                actions.onTrash(project.id)
                                scope.launch {
                                    val result = snackbar.showSnackbar(
                                        movedToTrash,
                                        undo,
                                        withDismissAction = false,
                                        duration = SnackbarDuration.Long,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) actions.onRestore(project.id)
                                }
                            },
                            onRestore = { actions.onRestore(it.id) },
                            onDeleteForever = { confirmDelete = it },
                        )
                    if (state.recent.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(stringResource(R.string.gallery_recent)) }
                        items(state.recent, key = { "recent-${it.id}" }) { ProjectCard(it, cardActions, previewSource) }
                        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(stringResource(R.string.gallery_all)) }
                    }
                    items(state.all, key = { it.id }) { ProjectCard(it, cardActions, previewSource) }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        val context = LocalContext.current
                        Text(
                            stringResource(R.string.gallery_storage, Formatter.formatShortFileSize(context, state.totalSizeBytes)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }

    if (showNewProject) {
        NewProjectSheet(
            defaultNumber = state.nextDefaultNumber,
            canEncode = canEncode,
            onDismiss = { showNewProject = false },
            onCreate = { request ->
                showNewProject = false
                actions.onCreate(request)
            },
        )
    }
    renaming?.let { project ->
        RenameDialog(project.name, onDismiss = { renaming = null }) { name ->
            actions.onRename(project.id, name)
            renaming = null
        }
    }
    confirmDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.gallery_delete_title)) },
            text = { Text(stringResource(R.string.gallery_delete_message, project.name)) },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDeleteForever(project.id)
                    confirmDelete = null
                }) { Text(stringResource(R.string.gallery_delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** What the gallery can ask the rest of the app to do. */
public class GalleryActions(
    public val onOpen: (String) -> Unit,
    public val onCreate: (NewProjectRequest) -> Unit,
    public val onRename: (String, String) -> Unit,
    public val onDuplicate: (String, String) -> Unit,
    public val onTrash: (String) -> Unit,
    public val onRestore: (String) -> Unit,
    public val onDeleteForever: (String) -> Unit,
    public val onQuery: (String) -> Unit,
    public val onSort: (GallerySort) -> Unit,
    public val onFilter: (GalleryFilter) -> Unit,
    public val onOpenSettings: () -> Unit,
)

private class CardActions(
    val onOpen: (String) -> Unit,
    val onRename: (ProjectSummary) -> Unit,
    val onDuplicate: (ProjectSummary) -> Unit,
    val onTrash: (ProjectSummary) -> Unit,
    val onRestore: (ProjectSummary) -> Unit,
    val onDeleteForever: (ProjectSummary) -> Unit,
)

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun EmptyMessage(state: GalleryUiState) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(
                when {
                    state.filter == GalleryFilter.TRASH -> R.string.gallery_trash_empty
                    state.query.isNotBlank() -> R.string.gallery_no_results
                    else -> R.string.gallery_empty
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SortMenu(sort: GallerySort, onSort: (GallerySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        KgIconButton(KgIcons.Sort, stringResource(R.string.gallery_sort), { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.gallery_sort_date)) },
                leadingIcon = { if (sort == GallerySort.DATE) Icon(KgIcons.Check, null) },
                onClick = {
                    onSort(GallerySort.DATE)
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.gallery_sort_name)) },
                leadingIcon = { if (sort == GallerySort.NAME) Icon(KgIcons.Check, null) },
                onClick = {
                    onSort(GallerySort.NAME)
                    open = false
                },
            )
        }
    }
}

@Composable
private fun ProjectCard(project: ProjectSummary, actions: CardActions, previewSource: ProjectRepository?) {
    var previewing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val duration = Timeline.formatDuration(project.durationMs)
    val framesText = pluralStringResource(R.plurals.frame_count, project.frameCount, project.frameCount)
    val description = stringResource(R.string.gallery_card_description, project.name, duration, framesText, project.fps)
    val open = { if (!project.isTrashed) actions.onOpen(project.id) }

    Card(
        Modifier
            .fillMaxWidth()
            .testTag("project_card_${project.name}")
            .semantics(mergeDescendants = false) {
                role = Role.Button
                this.onClick(label = description) {
                    open()
                    true
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(CARD_ASPECT)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(project.id) {
                    detectTapGestures(
                        onTap = { open() },
                        onLongPress = { previewing = true },
                        onPress = {
                            tryAwaitRelease()
                            previewing = false
                        },
                    )
                },
        ) {
            val thumbnail by rememberThumbnail(project.thumbnail, project.updatedAt)
            val animated by rememberAnimatedPreview(previewing, project.id, previewSource)
            (animated ?: thumbnail)?.let { image ->
                Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            ) {
                Text(
                    stringResource(R.string.fps_value, project.fps),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$duration · $framesText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                KgIconButton(KgIcons.More, stringResource(R.string.gallery_card_menu, project.name), { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (project.isTrashed) {
                        MenuEntry(R.string.action_restore, KgIcons.Restore) {
                            menuOpen = false
                            actions.onRestore(project)
                        }
                        MenuEntry(R.string.gallery_delete_confirm, KgIcons.Trash) {
                            menuOpen = false
                            actions.onDeleteForever(project)
                        }
                    } else {
                        MenuEntry(R.string.action_rename, KgIcons.Rename) {
                            menuOpen = false
                            actions.onRename(project)
                        }
                        MenuEntry(R.string.action_duplicate, KgIcons.Duplicate) {
                            menuOpen = false
                            actions.onDuplicate(project)
                        }
                        MenuEntry(R.string.action_delete, KgIcons.Trash) {
                            menuOpen = false
                            actions.onTrash(project)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuEntry(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

@Composable
private fun rememberThumbnail(file: File?, version: Long) =
    produceState<ImageBitmap?>(null, file, version) {
        value =
            file?.let {
                withContext(Dispatchers.IO) {
                    try {
                        BitmapFactory.decodeFile(it.path)?.asImageBitmap()
                    } catch (_: OutOfMemoryError) {
                        null
                    }
                }
            }
    }

/** While the card is held, plays the project at low resolution, decoding each frame as it is needed. */
@Composable
private fun rememberAnimatedPreview(playing: Boolean, projectId: String, repository: ProjectRepository?) =
    produceState<ImageBitmap?>(null, playing, projectId) {
        if (!playing || repository == null) {
            value = null
            return@produceState
        }
        val opened = repository.open(projectId) ?: return@produceState
        try {
            val project = opened.loaded.project
            val index = TimelineIndex(project.frames)
            val scale = PREVIEW_EDGE.toFloat() / maxOf(project.width, project.height)
            val flattener = FrameFlattener(opened.store)
            val frameMs = 1000L / project.fps
            var tick = 0
            while (true) {
                val slot = project.frames[index.slotAtTick(tick)]
                val image =
                    withContext(Dispatchers.Default) {
                        val bitmap =
                            Bitmap.createBitmap(
                                (project.width * scale).toInt().coerceAtLeast(1),
                                (project.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                        flattener.flatten(project, slot.id, Canvas(bitmap), scale, null, FlattenOptions(forEditor = true))
                        bitmap.asImageBitmap()
                    }
                value = image
                delay(frameMs)
                tick = (tick + 1) % index.totalTicks
            }
        } finally {
            opened.store.close()
        }
    }

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_rename)) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it.take(80) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val CARD_ASPECT = 16f / 10f
private const val PREVIEW_EDGE = 320
