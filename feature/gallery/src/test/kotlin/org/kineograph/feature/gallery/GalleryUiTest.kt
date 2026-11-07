package org.kineograph.feature.gallery

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.data.ProjectRepository
import org.kineograph.data.ProjectSummary
import org.kineograph.data.db.openProjectDao
import org.kineograph.data.files.ProjectFiles
import org.kineograph.model.Background
import org.kineograph.model.PaperKind
import org.kineograph.ui.DefaultSwatches
import org.kineograph.ui.KineographTheme
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
class GalleryUiTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private fun summary(name: String, id: String = name, trashed: Boolean = false) =
        ProjectSummary(
            id = id,
            name = name,
            createdAt = 0,
            updatedAt = 0,
            width = 1280,
            height = 720,
            fps = 12,
            frameCount = 12,
            durationMs = 2000,
            folder = null,
            isTrashed = trashed,
            sizeBytes = 2048,
            thumbnail = null,
        )

    private val noActions =
        GalleryActions(
            onOpen = {},
            onCreate = {},
            onRename = {
                _,
                _,
                ->
            },
            onDuplicate = { _, _ -> },
            onTrash = {},
            onRestore = {},
            onDeleteForever = {},
            onQuery = {},
            onSort = {},
            onFilter = {},
            onOpenSettings = {},
        )

    private fun show(state: GalleryUiState, actions: GalleryActions = noActions) {
        compose.setContent {
            KineographTheme(darkTheme = false) {
                GalleryScreen(state, canEncode = { _, _ -> false }, actions = actions)
            }
        }
    }

    private fun loaded(vararg projects: ProjectSummary) = GalleryUiState(
        all = projects.toList(),
        isLoaded = true,
        allNames = projects.mapTo(HashSet()) { it.name },
    )

    @Test
    fun `a card shows the name, duration, frame count and frame rate`() {
        show(loaded(summary("Bouncing Ball")))
        compose.onNodeWithText("Bouncing Ball").assertIsDisplayed()
        compose.onNodeWithText("00:02 · 12 frames").assertIsDisplayed()
        compose.onNodeWithText("12 fps").assertIsDisplayed()
        compose.onRoot().saveScreenshot("gallery")
    }

    @Test
    fun `the empty gallery explains what to do`() {
        show(GalleryUiState(isLoaded = true))
        compose.onNodeWithText("No projects yet. Tap New to draw your first animation.").assertIsDisplayed()
    }

    @Test
    fun `the new project sheet opens from the button`() {
        show(GalleryUiState(isLoaded = true))
        compose.onNodeWithTag("new_project_button").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("new_project_name").assertTextContains("My animation 1")
    }

    @Test
    fun `opening a card from the menu offers rename, duplicate and delete`() {
        show(loaded(summary("Mine")))
        compose.onNodeWithText("Mine").assertIsDisplayed()
        compose.onNode(hasContentDescription("More actions for Mine")).performClick()
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Duplicate").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun `deleting moves to trash and offers undo`() {
        var trashed: String? = null
        var restored: String? = null
        val actions =
            GalleryActions(
                onOpen = {},
                onCreate = {},
                onRename = { _, _ -> },
                onDuplicate = { _, _ -> },
                onTrash = { trashed = it },
                onRestore = { restored = it },
                onDeleteForever = {},
                onQuery = {},
                onSort = {},
                onFilter = {},
                onOpenSettings = {},
            )
        show(loaded(summary("Mine")), actions)
        compose.onNode(hasContentDescription("More actions for Mine")).performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        assertEquals("Mine", trashed)
        compose.onNodeWithText("Moved to trash").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals("Mine", restored)
    }

    // ----- New project form -----

    private fun showForm(canEncode: (Int, Int) -> Boolean = { _, _ -> false }, onCreate: (NewProjectRequest) -> Unit = {}) {
        compose.setContent {
            KineographTheme(darkTheme = false) {
                NewProjectForm(defaultNumber = 3, canEncode = canEncode, onCreate = onCreate)
            }
        }
    }

    private fun typeCustomSize(width: String, height: String) {
        compose.onNodeWithTag("canvas_size_dropdown").performScrollTo().performClick()
        compose.onNodeWithTag("preset_custom").performScrollTo().performClick()
        compose.onNodeWithText("Width").performScrollTo().performTextClearance()
        if (width.isNotEmpty()) compose.onNodeWithText("Width").performTextInput(width)
        compose.onNodeWithText("Height").performTextClearance()
        if (height.isNotEmpty()) compose.onNodeWithText("Height").performTextInput(height)
        compose.waitForIdle()
    }

    private fun chooseBackground(tag: String) {
        compose.onNodeWithTag("background_dropdown").performScrollTo().performClick()
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun availableMemoryIs(bytes: Long) {
        val manager = ApplicationProvider.getApplicationContext<Context>().getSystemService(ActivityManager::class.java)
        shadowOf(manager).setMemoryInfo(ActivityManager.MemoryInfo().apply { availMem = bytes })
    }

    @Test
    fun `the form starts with the specified defaults`() {
        var created: NewProjectRequest? = null
        showForm(onCreate = { created = it })
        compose.onRoot().saveScreenshot("new_project")
        compose.onNodeWithTag("new_project_name").assertTextContains("My animation 3")
        compose.onNodeWithTag("create_project").performScrollTo().performClick()
        val request = created ?: error("nothing created")
        assertEquals("My animation 3", request.name)
        assertEquals(1280, request.width)
        assertEquals(720, request.height)
        assertEquals(12, request.fps)
        assertEquals(Background.Paper(PaperKind.WHITE), request.background)
    }

    @Test
    fun `the helper text explains the frame rate`() {
        showForm()
        compose.onNodeWithTag("fps_helper").performScrollTo().assertTextContains("You need 12 drawings for 1 second of animation")
    }

    @Test
    fun `presets set the canvas size`() {
        var created: NewProjectRequest? = null
        showForm(onCreate = { created = it })
        compose.onNodeWithTag("canvas_size_dropdown").performClick()
        compose.onNodeWithTag("preset_vertical").performClick()
        compose.onNodeWithTag("create_project").performScrollTo().performClick()
        assertEquals(1080, created?.width)
        assertEquals(1920, created?.height)
    }

    @Test
    fun `4K is only offered if the encoder supports it`() {
        showForm(canEncode = { w, _ -> w < 3000 })
        compose.onNodeWithTag("canvas_size_dropdown").performClick()
        compose.onNodeWithTag("preset_hd").assertIsDisplayed()
        compose.onNodeWithTag("preset_uhd_4k").assertDoesNotExist()
    }

    @Test
    fun `4K appears when the encoder supports it`() {
        showForm(canEncode = { _, _ -> true })
        compose.onNodeWithTag("canvas_size_dropdown").performClick()
        compose.onNodeWithTag("preset_uhd_4k").assertExists()
    }

    @Test
    fun `a custom size is used and a bad one blocks creation with an explanation`() {
        var created: NewProjectRequest? = null
        showForm(onCreate = { created = it })
        typeCustomSize("640", "0")
        compose.onNodeWithTag("create_project").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Enter a width and height of at least 1.").assertExists()
        typeCustomSize("640", "480")
        compose.onNodeWithText("Enter a width and height of at least 1.").assertDoesNotExist()
        compose
            .onNodeWithTag("create_project")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(640, created?.width)
        assertEquals(480, created?.height)
    }

    @Test
    fun `a blank size says what is wrong`() {
        showForm()
        typeCustomSize("", "480")
        compose.onNodeWithTag("create_project").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Enter a width and height of at least 1.").assertExists()
    }

    @Test
    fun `a canvas past the old limit of 8192 is fine when the device can hold it`() {
        var created: NewProjectRequest? = null
        showForm(onCreate = { created = it })
        typeCustomSize("99999", "10")
        compose.onNodeWithTag("canvas_size_note").assertDoesNotExist()
        compose
            .onNodeWithTag("create_project")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(99999, created?.width)
    }

    @Test
    fun `a canvas no device could hold says so, and offers one that fits`() {
        var created: NewProjectRequest? = null
        showForm(onCreate = { created = it })
        typeCustomSize("40000", "20000")
        compose.onNodeWithTag("create_project").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("more than Android can hold", substring = true).assertExists()
        compose.onNodeWithTag("canvas_size_use").performScrollTo().performClick()
        compose.waitForIdle()
        compose
            .onNodeWithTag("create_project")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        val request = created ?: error("nothing created")
        assertEquals("the same shape", 2.0, request.width.toDouble() / request.height, 0.01)
    }

    @Test
    fun `a canvas too big for what is free says to close other apps, or offers one that fits`() {
        availableMemoryIs(64L * 1024 * 1024)
        var created: NewProjectRequest? = null
        showForm(onCreate = { created = it })
        typeCustomSize("9000", "9000")
        compose.onNodeWithTag("create_project").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Close other apps", substring = true).assertExists()
        compose.onNodeWithTag("canvas_size_use").performScrollTo().performClick()
        compose.waitForIdle()
        compose
            .onNodeWithTag("create_project")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        val request = created ?: error("nothing created")
        assertTrue("$request", request.width.toLong() * request.height * 8 <= 64L * 1024 * 1024)
    }

    @Test
    fun `a preset too big for what is free says so too`() {
        availableMemoryIs(1024L * 1024)
        showForm()
        compose.onNodeWithTag("create_project").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Close other apps", substring = true).assertExists()
    }

    @Test
    fun `a canvas that will be slow warns, and can still be made`() {
        showForm()
        typeCustomSize("4000", "4000")
        compose.onNodeWithText("may run slowly", substring = true).assertExists()
        compose.onNodeWithTag("create_project").performScrollTo().assertIsEnabled()
    }

    // ----- The camera -----

    /** Launches nothing, as on a device with no camera app. */
    private fun showFormOnPhoneWithoutCamera(onCreate: (NewProjectRequest) -> Unit = {}) {
        val registry =
            object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ): Unit = throw ActivityNotFoundException("No camera app")
            }
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                KineographTheme(darkTheme = false) {
                    NewProjectForm(
                        defaultNumber = 3,
                        canEncode = { _, _ -> false },
                        onCreate = onCreate,
                        cameraUriFor = { Uri.parse("content://test/photo.jpg") },
                    )
                }
            }
        }
    }

    @Test
    fun `a phone with no camera app gets an explanation, not a crash`() {
        var created: NewProjectRequest? = null
        showFormOnPhoneWithoutCamera(onCreate = { created = it })
        chooseBackground("background_camera")
        compose.onNodeWithTag("camera_unavailable").performScrollTo().assertIsDisplayed()
        chooseBackground("background_transparent")
        compose.onNodeWithTag("camera_unavailable").assertDoesNotExist()
        compose.onNodeWithTag("create_project").performScrollTo().performClick()
        assertEquals(Background.Transparent, created?.background)
    }

    /** A camera app that puts a photo of one colour in the file it was given, and says it worked. */
    private fun showFormWithCamera(file: java.io.File, colors: () -> Int) {
        val registry =
            object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    val photo = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(colors()) }
                    file.outputStream().use { photo.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    dispatchResult(requestCode, true)
                }
            }
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                KineographTheme(darkTheme = false) {
                    NewProjectForm(
                        defaultNumber = 3,
                        canEncode = { _, _ -> false },
                        onCreate = {},
                        cameraUriFor = { Uri.fromFile(file) },
                    )
                }
            }
        }
    }

    /** Off to one side, where the ball never goes. */
    private fun previewBackground(): Int {
        val picture = compose.onNodeWithTag("project_preview").captureToImage().asAndroidBitmap()
        return picture.getPixel(picture.width * 3 / 4, picture.height / 2)
    }

    private fun waitForPreview(argb: Int) {
        compose.waitUntil(10_000) {
            Thread.sleep(5)
            previewBackground() == argb
        }
    }

    @Test
    fun `taking a second photo as the background shows the second photo`() {
        var color = Color.RED
        showFormWithCamera(temp.newFile("background.jpg")) { color }
        chooseBackground("background_camera")
        waitForPreview(Color.RED)
        color = Color.BLUE
        chooseBackground("background_camera")
        waitForPreview(Color.BLUE)
    }

    @Test
    fun `the ball never disappears against the background, starting with the colour first offered`() {
        for (argb in listOf(DefaultSwatches[3], 0xFFE4572E.toInt(), 0xFFE55A30.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
            val ball = ballColorOn(BackgroundChoice.SolidChoice(argb))
            assertNotEquals(
                "#%08X".format(argb),
                androidx.compose.ui.graphics
                    .Color(argb),
                ball,
            )
        }
        assertNotEquals(ballColorOn(BackgroundChoice.TransparentChoice), ballColorOn(BackgroundChoice.SolidChoice(DefaultSwatches[3])))
        // Any other colour leaves the ball as it is, so that the preview looks the same as it always has.
        assertEquals(ballColorOn(BackgroundChoice.TransparentChoice), ballColorOn(BackgroundChoice.SolidChoice(0xFF000000.toInt())))
    }

    @Test
    fun `the colour first offered still leaves a ball to look at in the preview`() {
        val background = BackgroundChoice.SolidChoice(DefaultSwatches[3])
        compose.setContent {
            KineographTheme(darkTheme = true) { ProjectPreview(12, 1280, 720, background, null, Modifier.size(320.dp, 180.dp)) }
        }
        compose.waitForIdle()
        val picture = compose.onNodeWithTag("project_preview").captureToImage().asAndroidBitmap()
        val colours = HashSet<Int>()
        for (x in 0 until picture.width) {
            for (y in 0 until picture.height) colours += picture.getPixel(x, y)
        }
        assertTrue("no ball among ${colours.size} colours", ballColorOn(background).toArgb() in colours)
    }

    @Test
    fun `the preview shows a photo the way up it will be stored`() {
        val file = temp.newFile("sideways.jpg")
        val photo = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        file.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        ExifInterface(file.path).run {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val thumbnail = decodeThumbnail(Uri.fromFile(file), ApplicationProvider.getApplicationContext<Context>().contentResolver)
        assertEquals(40, thumbnail?.width)
        assertEquals(80, thumbnail?.height)
    }

    // ----- Surviving the camera -----

    private fun StateRestorationTester.showFormWith(onCreate: (NewProjectRequest) -> Unit) {
        setContent {
            KineographTheme(darkTheme = false) {
                NewProjectForm(defaultNumber = 3, canEncode = { _, _ -> false }, onCreate = onCreate)
            }
        }
    }

    @Test
    fun `the background choice outlives the activity`() {
        var created: NewProjectRequest? = null
        val restoration = StateRestorationTester(compose)
        restoration.showFormWith { created = it }
        chooseBackground("background_paper_dots")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("create_project").performScrollTo().performClick()
        assertEquals(Background.Paper(PaperKind.DOTS), created?.background)
    }

    @Test
    fun `a transparent choice outlives the activity`() {
        var created: NewProjectRequest? = null
        val restoration = StateRestorationTester(compose)
        restoration.showFormWith { created = it }
        chooseBackground("background_transparent")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("create_project").performScrollTo().performClick()
        assertEquals(Background.Transparent, created?.background)
    }

    @Test
    fun `an open sheet outlives the activity, for the photo to come back to`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            KineographTheme(darkTheme = false) {
                GalleryScreen(GalleryUiState(isLoaded = true), canEncode = { _, _ -> false }, actions = noActions)
            }
        }
        compose.onNodeWithTag("new_project_button").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("new_project_name").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("new_project_name").assertExists()
    }

    // ----- The default name -----

    @Test
    fun `the sheet offers the first number no project has`() {
        show(loaded(summary("Bouncing Ball"), summary("My animation 1"), summary("My animation 3")))
        compose.onNodeWithTag("new_project_button").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("new_project_name").assertTextContains("My animation 2")
    }

    // ----- Tapping a card -----

    private fun actionsOpening(onOpen: (String) -> Unit, onQuery: (String) -> Unit = {}) =
        GalleryActions(
            onOpen = onOpen,
            onCreate = {},
            onRename = { _, _ -> },
            onDuplicate = { _, _ -> },
            onTrash = {},
            onRestore = {},
            onDeleteForever = {},
            onQuery = onQuery,
            onSort = {},
            onFilter = {},
            onOpenSettings = {},
        )

    @Test
    fun `tapping anywhere on a card opens it`() {
        val opened = ArrayList<String>()
        show(loaded(summary("Mine")), actionsOpening({ opened += it }))
        compose.onNodeWithText("Mine").performClick()
        assertEquals(listOf("Mine"), opened)
        compose.onNodeWithText("00:02 · 12 frames").performClick()
        assertEquals(listOf("Mine", "Mine"), opened)
        compose.onNodeWithText("12 fps").performClick()
        assertEquals(listOf("Mine", "Mine", "Mine"), opened)
        compose.onNodeWithTag("project_card_Mine").performClick()
        assertEquals(4, opened.size)
    }

    @Test
    fun `the menu on a card opens the menu, not the project`() {
        val opened = ArrayList<String>()
        show(loaded(summary("Mine")), actionsOpening({ opened += it }))
        compose.onNode(hasContentDescription("More actions for Mine")).performClick()
        assertTrue(opened.isEmpty())
        compose.onNodeWithText("Rename").assertIsDisplayed()
    }

    @Test
    fun `a card in the trash does not open`() {
        val opened = ArrayList<String>()
        show(loaded(summary("Mine", trashed = true)), actionsOpening({ opened += it }))
        compose.onNodeWithText("Mine").performClick()
        assertTrue(opened.isEmpty())
    }

    // ----- Holding a card -----

    @Test
    fun `holding a card plays the project on its picture, not on white`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = ProjectRepository(openProjectDao(context), ProjectFiles(temp.newFolder(), temp.newFolder()), 1024L * 1024)
        val picture = temp.newFile("picture.png")
        val red = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        picture.outputStream().use { red.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val project =
            runBlocking {
                val id = "with-picture"
                val background =
                    checkNotNull(repository.importBackgroundImage(id, Uri.fromFile(picture), context.contentResolver, 100, 100))
                repository.create("Mine", 100, 100, 12, background, "Layer 1", id)
            }
        compose.setContent {
            KineographTheme(darkTheme = false) {
                GalleryScreen(
                    loaded(summary("Mine", id = project.id)),
                    canEncode = { _, _ -> false },
                    actions = noActions,
                    previewSource = repository,
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("project_card_Mine").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1000)

        fun centreOfCard(): Int =
            compose
                .onNodeWithTag("project_card_Mine")
                .captureToImage()
                .asAndroidBitmap()
                .let { it.getPixel(it.width / 2, it.height / 3) }
        val deadline = System.currentTimeMillis() + 10_000
        while (Color.red(centreOfCard()) < 200 || Color.green(centreOfCard()) > 100) {
            check(System.currentTimeMillis() < deadline) { "The card shows ${Integer.toHexString(centreOfCard())}" }
            Thread.sleep(20)
            compose.mainClock.advanceTimeBy(100)
        }
        compose.onNodeWithTag("project_card_Mine").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
    }

    // ----- Search -----

    @Test
    fun `closing search ends the search`() {
        var state by mutableStateOf(loaded(summary("Mine")))
        compose.setContent {
            KineographTheme(darkTheme = false) {
                GalleryScreen(
                    state,
                    canEncode = { _, _ -> false },
                    actions = actionsOpening({}, onQuery = { state = state.copy(query = it) }),
                )
            }
        }
        compose.onNodeWithTag("search_button").performClick()
        compose.onNodeWithTag("search_field").performTextInput("xyz")
        compose.waitForIdle()
        assertEquals("xyz", state.query)
        compose.onNodeWithTag("search_button").performClick()
        compose.waitForIdle()
        assertEquals("", state.query)
        compose.onNodeWithTag("search_field").assertDoesNotExist()
        compose.onNodeWithTag("search_button").assertIsNotSelected()
    }

    @Test
    fun `a search already in force has its box on screen`() {
        show(loaded(summary("Mine")).copy(query = "Mi"))
        compose.onNodeWithTag("search_field").assertIsDisplayed().assertTextContains("Mi")
        compose.onNodeWithTag("search_button").assertIsSelected()
    }

    @Test
    fun `emptying the box does not take it away from under the user`() {
        var state by mutableStateOf(loaded(summary("Mine")))
        compose.setContent {
            KineographTheme(darkTheme = false) {
                GalleryScreen(
                    state,
                    canEncode = { _, _ -> false },
                    actions = actionsOpening({}, onQuery = { state = state.copy(query = it) }),
                )
            }
        }
        compose.runOnIdle { state = state.copy(query = "Mi") }
        compose.waitForIdle()
        compose.onNodeWithTag("search_field").performTextClearance()
        compose.waitForIdle()
        compose.onNodeWithTag("search_field").assertIsDisplayed()
    }

    @Test
    fun `search says whether it is on, to TalkBack too`() {
        show(loaded(summary("Mine")))
        compose.onNodeWithTag("search_button").assertIsNotSelected().performClick()
        compose.onNodeWithTag("search_button").assertIsSelected()
        compose.onNodeWithTag("search_field").assertIsDisplayed()
    }

    @Test
    fun `a blank name blocks creation`() {
        showForm()
        compose.onNodeWithTag("new_project_name").performTextClearance()
        compose.waitForIdle()
        compose.onNodeWithTag("create_project").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `the background choice carries through`() {
        var created: NewProjectRequest? = null
        showForm(onCreate = { created = it })
        compose.onNodeWithTag("background_dropdown").performClick()
        compose.onNodeWithTag("background_transparent").performScrollTo().performClick()
        compose.onNodeWithTag("create_project").performScrollTo().performClick()
        assertEquals(Background.Transparent, created?.background)
    }

    @Test
    fun `papers can be chosen`() {
        var created: NewProjectRequest? = null
        showForm(onCreate = { created = it })
        compose.onNodeWithTag("background_dropdown").performClick()
        compose.onNodeWithTag("background_paper_dots").performClick()
        compose.onNodeWithTag("create_project").performScrollTo().performClick()
        assertTrue(created?.background == Background.Paper(PaperKind.DOTS))
    }

    @Test
    fun `choosing from a drop-down closes it and shows the choice`() {
        showForm()
        compose.onNodeWithTag("background_dropdown").assertTextContains("White")
        compose.onNodeWithTag("background_dropdown").performClick()
        compose.onNodeWithText("Dark paper for bright drawings").assertIsDisplayed()
        compose.onNodeWithTag("background_paper_dark").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Dark paper for bright drawings").assertDoesNotExist()
        compose.onNodeWithTag("background_dropdown").assertTextContains("Dark")
    }

    @Test
    fun `canvas options are grouped and explain themselves`() {
        showForm()
        compose.onNodeWithTag("canvas_size_dropdown").assertTextContains("HD 1280×720")
        compose.onNodeWithTag("canvas_size_dropdown").performClick()
        compose.onNodeWithText("Social").assertExists()
        compose.onNodeWithText("TikTok, Reels, Shorts, WhatsApp Status").assertExists()
        compose.onNodeWithTag("preset_square").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("canvas_size_dropdown").assertTextContains("Square 1080×1080")
    }

    @Test
    fun `the whole form fits on a phone without scrolling`() {
        showForm()
        compose.onNodeWithTag("create_project").assertIsDisplayed()
        compose.onNodeWithTag("project_preview").assertIsDisplayed()
    }

    @Test
    fun `at the largest font size the options grow with their text, and the form can still be finished`() {
        var created: NewProjectRequest? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                KineographTheme(darkTheme = false) {
                    NewProjectForm(defaultNumber = 3, canEncode = { _, _ -> false }, onCreate = { created = it })
                }
            }
        }
        compose.onNodeWithTag("background_dropdown").performScrollTo().performClick()
        val option = compose.onNodeWithTag("background_paper_dots").performScrollTo().fetchSemanticsNode()
        val title = compose.onNodeWithText("Dots", useUnmergedTree = true).fetchSemanticsNode()
        val hint = compose.onNodeWithText("Light dots to help with spacing", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("the name and the hint both fit", option.size.height >= title.size.height + hint.size.height)
        compose.onNodeWithTag("background_paper_dots").performClick()
        compose.onNodeWithTag("canvas_size_dropdown").performScrollTo().performClick()
        compose.onNodeWithTag("preset_square").performScrollTo().performClick()
        compose
            .onNodeWithTag("create_project")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals(1080, created?.width)
        assertEquals(Background.Paper(PaperKind.DOTS), created?.background)
    }

    /** Lays the sheet out in both themes, with its menus open, to check by eye. */
    @Test
    fun `the form in both themes with its menus open`() {
        var dark by mutableStateOf(false)
        compose.setContent {
            KineographTheme(darkTheme = dark) {
                Surface {
                    NewProjectForm(defaultNumber = 3, canEncode = { _, _ -> true }, onCreate = {})
                }
            }
        }
        for (isDark in listOf(false, true)) {
            dark = isDark
            compose.waitForIdle()
            val theme = if (isDark) "dark" else "light"
            compose.onRoot().saveScreenshot("new_project_$theme")
            compose.onNodeWithTag("background_dropdown").performClick()
            compose.waitForIdle()
            compose.onNode(isPopup()).saveScreenshot("new_project_background_menu_$theme")
            compose.onNodeWithTag("background_paper_white").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("canvas_size_dropdown").performClick()
            compose.waitForIdle()
            compose.onNode(isPopup()).saveScreenshot("new_project_canvas_menu_$theme")
            compose.onNodeWithTag("preset_hd").performClick()
            compose.waitForIdle()
        }
    }
}
