package org.kineograph.media.svgimport

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.data.files.CelFileStore
import org.kineograph.engine.compositor.FlattenOptions
import org.kineograph.engine.compositor.FrameFlattener
import org.kineograph.engine.session.CelStore
import org.kineograph.engine.session.DrawingSession
import org.kineograph.engine.session.MemoryBudget
import org.kineograph.media.svg.SvgDocument
import org.kineograph.media.svg.SvgParser
import org.kineograph.model.Background
import org.kineograph.model.ImportEdits
import org.kineograph.model.IntRect
import org.kineograph.model.PaperKind
import org.kineograph.model.PlacedImage
import org.kineograph.model.Project
import org.kineograph.model.newProject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Everything from a parsed picture to pixels in a session, on real Skia. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SvgImporterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val store = TestStore()
    private var session: DrawingSession? = null
    private var savedBackgrounds = 0

    private class TestStore : CelStore {
        val images = ConcurrentHashMap<String, Bitmap>()
        val writes = AtomicInteger()
        val discarded = ArrayList<String>()
        var failWritesAfter = Int.MAX_VALUE
        var failDiskAfter = Int.MAX_VALUE
        var failReadsOf: String? = null

        override fun write(image: Bitmap): String {
            val count = writes.incrementAndGet()
            if (count > failWritesAfter) throw OutOfMemoryError("no room")
            val name = "cels/$count.png"
            images[name] = image
            return name
        }

        override fun read(file: String, sampleSize: Int): Bitmap? {
            if (file == failReadsOf) {
                failReadsOf = null
                throw OutOfMemoryError("no room to decode")
            }
            return images[file]
        }

        override suspend fun awaitWrites() {
            if (writes.get() > failDiskAfter) throw IOException("disk full")
        }

        override fun trimMemory() = Unit

        override fun discard(files: Collection<String>) {
            discarded += files
        }
    }

    @After
    fun tearDown() {
        session?.close()
    }

    private fun fixture(name: String): SvgDocument {
        val stream = checkNotNull(javaClass.getResourceAsStream("/svg/$name")) { "missing fixture $name" }
        return stream.use { SvgParser.parse(it) }
    }

    private fun open(width: Int = 200, height: Int = 100, fps: Int = 12, celStore: CelStore = store): DrawingSession {
        val project = newProject("Test", width, height, fps, Background.Transparent, "Layer 1", now = 0)
        return DrawingSession(project, celStore, MemoryBudget.forMemoryClass(256, isLowRam = false), temp.newFolder()).also { session = it }
    }

    private fun importer(target: DrawingSession, celStore: CelStore = store): SvgImporter =
        SvgImporter(target, celStore, { image ->
            savedBackgrounds++
            val copy = image.copy(Bitmap.Config.ARGB_8888, false)
            Background.Image(celStore.write(copy))
        })

    private fun DrawingSession.settle(): Project = runBlocking {
        awaitIdle()
        snapshot()
    }

    private fun animate(
        target: DrawingSession,
        document: SvgDocument,
        seconds: Double = document.animation.durationSeconds,
        fit: SvgFit = SvgFit.FIT_INSIDE,
    ): SvgImportOutcome = runBlocking { importer(target).import(document, SvgImportRequest.Animation(seconds, fit, "Hops")) }

    private fun still(
        target: DrawingSession,
        document: SvgDocument,
        place: SvgImportPlace = SvgImportPlace.NEW_LAYER,
        fit: SvgFit = SvgFit.FIT_INSIDE,
        seconds: Double = 0.0,
    ): SvgImportOutcome = runBlocking { importer(target).import(document, SvgImportRequest.SingleImage(seconds, place, fit, "Picture")) }

    private fun flattened(project: Project, frameId: String, celStore: CelStore = store): Bitmap {
        val bitmap = Bitmap.createBitmap(project.width, project.height, Bitmap.Config.ARGB_8888)
        FrameFlattener(celStore).flatten(project, frameId, Canvas(bitmap), 1f, null, FlattenOptions(includeBackground = false))
        return bitmap
    }

    private fun Project.newLayerId(): String = layers.last().id

    private val red = Color.RED

    // ----- Animation, all the way into a session -----

    @Test
    fun `an animated file lands in a session frame by frame, with the pixels the file draws at each moment`() {
        val document = fixture("bounce-smil.svg")
        val target = open(width = 100, height = 100)
        assertEquals(SvgImportOutcome.DONE, animate(target, document))
        val project = target.settle()
        assertEquals(24, project.frames.size)
        for (tick in listOf(0, 3, 6, 12, 18, 23)) {
            val expected = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            document.render(Canvas(expected), tick / 12.0, RectF(0f, 0f, 100f, 100f))
            val actual = flattened(project, project.frames[tick].id)
            assertTrue("tick $tick differs from the file", expected.sameAs(actual))
        }
    }

    @Test
    fun `the same import through the real file store reads back the same pixels from disk`() {
        val document = fixture("import-hops.svg")
        val files = CelFileStore(temp.newFolder(), 1024 * 1024)
        val target = open(celStore = files)
        assertEquals(
            SvgImportOutcome.DONE,
            runBlocking {
                importer(target, files).import(document, SvgImportRequest.Animation(2.0, SvgFit.FIT_INSIDE, "Hops"))
            },
        )
        val project = target.settle()
        runBlocking { files.awaitWrites() }
        files.trimMemory()
        val picture = flattened(project, project.frames[7].id, files)
        assertEquals(red, picture.getPixel(50 + 20 + 10, 10))
        assertEquals(0, Color.alpha(picture.getPixel(50 + 10, 10)))
        files.close()
    }

    @Test
    fun `there is one tick per frame at the project's rate, each at i over fps`() {
        val target = open()
        assertEquals(SvgImportOutcome.DONE, animate(target, fixture("import-hops.svg")))
        val project = target.settle()
        assertEquals(24, project.frames.size)
        val layer = project.newLayerId()
        // The square hops every six ticks and is 20 wide, centred at x = 50 by the fit.
        assertEquals(IntRect(50, 0, 70, 20), project.cel(layer, project.frames[0].id)?.bounds)
        assertEquals(IntRect(50, 0, 70, 20), project.cel(layer, project.frames[5].id)?.bounds)
        assertEquals(IntRect(70, 0, 90, 20), project.cel(layer, project.frames[6].id)?.bounds)
        assertEquals(IntRect(90, 0, 110, 20), project.cel(layer, project.frames[17].id)?.bounds)
    }

    @Test
    fun `the frame rate decides how many frames a duration takes`() {
        val target = open(fps = 24)
        assertEquals(SvgImportOutcome.DONE, animate(target, fixture("import-hops.svg"), seconds = 1.5))
        assertEquals(36, target.settle().frames.size)
    }

    @Test
    fun `identical frames share one file and empty frames store nothing`() {
        val target = open()
        animate(target, fixture("import-hops.svg"))
        val project = target.settle()
        val layer = project.newLayerId()
        val files = project.frames.map { project.cel(layer, it.id)?.file }
        assertEquals(1, files.subList(0, 6).toSet().size)
        assertEquals(1, files.subList(6, 12).toSet().size)
        assertNotEquals(files[5], files[6])
        assertTrue(files.subList(18, 24).all { it == null })
        assertEquals(3, store.writes.get())
    }

    @Test
    fun `cels are cropped to their content, just like the others`() {
        val target = open()
        animate(target, fixture("import-hops.svg"))
        val project = target.settle()
        val cel = project.cel(project.newLayerId(), project.frames[0].id) ?: error("no cel")
        val image = store.images[cel.file] ?: error("no image")
        assertEquals(20, image.width)
        assertEquals(20, image.height)
    }

    @Test
    fun `a picture moving back to where it was still gets its own file, because only consecutive frames are compared`() {
        val document =
            SvgParser.parse(
                """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect width="5" height="5">
                  <animate attributeName="x" values="0;5;0" calcMode="discrete" dur="3s"/></rect></svg>""",
            )
        val target = open(width = 10, height = 10, fps = 1)
        animate(target, document, seconds = 3.0)
        assertEquals(3, store.writes.get())
    }

    @Test
    fun `the animation goes on a new layer above the active one, starting at the current frame`() {
        val target = open()
        target.addLayer("Top")
        target.selectLayer(
            target
                .settle()
                .layers
                .first()
                .id,
        )
        target.addFrame()
        target.addFrame()
        val before = target.settle()
        val active = before.layers.first().id
        val current = before.frames[1].id
        target.selectFrame(current)
        target.settle()
        animate(target, fixture("import-hops.svg"), seconds = 0.5)
        val project = target.settle()
        assertEquals(3, project.layers.size)
        assertEquals(active, project.layers[0].id)
        assertEquals("Hops", project.layers[1].name)
        assertEquals(before.layers[1].id, project.layers[2].id)
        // Half a second is six ticks: two on the existing slots, from the second one, and four added after them.
        assertEquals(before.frames.map { it.id }, project.frames.take(3).map { it.id })
        assertEquals(7, project.frames.size)
        assertNull(project.cel(project.layers[1].id, before.frames[0].id))
        assertNotNull(project.cel(project.layers[1].id, current))
        assertEquals(project.layers[1].id, target.state.value.layerId)
        assertEquals(current, target.state.value.frameId)
    }

    @Test
    fun `other layers and frames keep their cels and their timing`() {
        val target = open()
        target.addFrame()
        val frames = target.settle().frames
        target.setHold(frames[0].id, 3)
        target.selectFrame(frames[0].id)
        val before = target.settle()
        animate(target, fixture("import-hops.svg"), seconds = 1.0)
        val project = target.settle()
        assertEquals(before.frames, project.frames.take(before.frames.size))
        assertEquals(before.layers, project.layers.take(before.layers.size))
        before.cels.forEach { (key, value) -> assertEquals(value, project.cels[key]) }
        // Holds of 3 and 1 take up four ticks of the twelve, and each new slot lasts one tick.
        assertEquals(12, project.frames.sumOf { it.hold })
    }

    @Test
    fun `a held slot shows the moment it starts at, so the animation stays in time`() {
        val target = open()
        target.addFrame()
        val frames = target.settle().frames
        target.setHold(frames[0].id, 6)
        target.selectFrame(frames[0].id)
        target.settle()
        animate(target, fixture("import-hops.svg"), seconds = 1.0)
        val project = target.settle()
        val layer = project.newLayerId()
        // The first slot covers ticks 0 to 5, and the second starts on tick 6, when the square has hopped.
        assertEquals(IntRect(50, 0, 70, 20), project.cel(layer, frames[0].id)?.bounds)
        assertEquals(IntRect(70, 0, 90, 20), project.cel(layer, frames[1].id)?.bounds)
        assertEquals(12, project.frames.sumOf { it.hold })
    }

    @Test
    fun `one undo takes the whole import away, and redo brings it back`() {
        val target = open()
        val before = target.settle()
        animate(target, fixture("import-hops.svg"))
        assertEquals(24, target.settle().frames.size)
        target.undo()
        val undone = target.settle()
        assertEquals(before.frames, undone.frames)
        assertEquals(before.layers, undone.layers)
        assertEquals(before.cels, undone.cels)
        assertFalse(target.state.value.canUndo)
        target.redo()
        val redone = target.settle()
        assertEquals(24, redone.frames.size)
        assertEquals(2, redone.layers.size)
    }

    @Test
    fun `an animation does not care that the active layer is locked`() {
        val target = open()
        val layer = target.state.value.layerId
        target.setLayerLocked(layer, true)
        target.settle()
        assertEquals(SvgImportOutcome.DONE, animate(target, fixture("import-hops.svg")))
        assertEquals(2, target.settle().layers.size)
    }

    @Test
    fun `a loop with a delay is sampled from where it starts to repeat, so its last frame runs into its first`() {
        val document =
            SvgParser.parse(
                """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect width="5" height="5">
                  <animate attributeName="x" values="0;5" calcMode="discrete" begin="1s" dur="2s" repeatCount="indefinite"/></rect></svg>""",
            )
        assertEquals(1.0, document.animation.sampleStartSeconds, 1e-9)
        val target = open(width = 10, height = 10, fps = 2)
        animate(target, document)
        val project = target.settle()
        val layer = project.newLayerId()
        val bounds = project.frames.map { project.cel(layer, it.id)?.bounds?.left }
        assertEquals(listOf(0, 0, 5, 5), bounds)
    }

    @Test
    fun `a file that plays once starts at zero, whatever its delay`() {
        val document =
            SvgParser.parse(
                """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect width="5" height="5" x="5">
                  <set attributeName="x" to="0" begin="1s" dur="1s"/></rect></svg>""",
            )
        assertFalse(document.animation.loopsForever)
        val target = open(width = 10, height = 10, fps = 1)
        animate(target, document, seconds = 2.0)
        val project = target.settle()
        val layer = project.newLayerId()
        assertEquals(listOf(5, 0), project.frames.map { project.cel(layer, it.id)?.bounds?.left })
    }

    @Test
    fun `progress reports every picture`() {
        val target = open()
        val heard = ArrayList<Pair<Int, Int>>()
        runBlocking {
            importer(target).import(fixture("import-hops.svg"), SvgImportRequest.Animation(1.0, SvgFit.FIT_INSIDE, "Hops")) { done, total ->
                heard += done to total
            }
        }
        assertEquals((1..12).map { it to 12 }, heard)
    }

    // ----- Fit -----

    @Test
    fun `fit inside shows all of it, centred`() {
        val destination = svgDestination(100f, 100f, 200, 100, SvgFit.FIT_INSIDE)
        assertEquals(RectF(50f, 0f, 150f, 100f), destination)
        assertEquals(RectF(0f, 25f, 200f, 75f), svgDestination(400f, 100f, 200, 100, SvgFit.FIT_INSIDE))
    }

    @Test
    fun `fill covers the canvas and overflows evenly`() {
        assertEquals(RectF(0f, -50f, 200f, 150f), svgDestination(100f, 100f, 200, 100, SvgFit.FILL_CANVAS))
    }

    @Test
    fun `original size is one to one, centred on a whole pixel`() {
        assertEquals(RectF(90f, 40f, 110f, 60f), svgDestination(20f, 20f, 200, 100, SvgFit.ORIGINAL_SIZE))
        assertEquals(RectF(-150f, -50f, 350f, 150f), svgDestination(500f, 200f, 200, 100, SvgFit.ORIGINAL_SIZE))
        val rounded = svgDestination(21f, 21f, 200, 100, SvgFit.ORIGINAL_SIZE)
        assertEquals(rounded.left, rounded.left.toInt().toFloat(), 0f)
        assertEquals(rounded.top, rounded.top.toInt().toFloat(), 0f)
    }

    private fun square(): SvgDocument = SvgParser.parse(
        """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20"><rect width="20" height="20" fill="red"/></svg>""",
    )

    @Test
    fun `each fit really puts the pixels there`() {
        fun boundsFor(fit: SvgFit): IntRect? {
            val target = open()
            still(target, square(), fit = fit)
            val project = target.settle()
            val result = project.cel(project.newLayerId(), project.frames[0].id)?.bounds
            target.close()
            return result
        }
        assertEquals(IntRect(50, 0, 150, 100), boundsFor(SvgFit.FIT_INSIDE))
        assertEquals(IntRect(0, 0, 200, 100), boundsFor(SvgFit.FILL_CANVAS))
        assertEquals(IntRect(90, 40, 110, 60), boundsFor(SvgFit.ORIGINAL_SIZE))
    }

    @Test
    fun `a picture larger than the canvas is cut at the canvas`() {
        val target = open(width = 50, height = 50)
        still(target, square(), fit = SvgFit.FILL_CANVAS)
        val project = target.settle()
        assertEquals(IntRect(0, 0, 50, 50), project.cel(project.newLayerId(), project.frames[0].id)?.bounds)
    }

    // ----- Single image -----

    @Test
    fun `a single image makes a new layer with one cel on the current frame`() {
        val target = open()
        target.addFrame()
        val before = target.settle()
        val current = target.state.value.frameId
        assertEquals(SvgImportOutcome.DONE, still(target, fixture("import-hops.svg")))
        val project = target.settle()
        assertEquals(before.frames, project.frames)
        assertEquals("Picture", project.layers.last().name)
        val cels = project.cels.values.filter { it.layerId == project.newLayerId() }
        assertEquals(listOf(current), cels.map { it.frameId })
        target.undo()
        assertEquals(1, target.settle().layers.size)
    }

    @Test
    fun `a single image shows the moment asked for`() {
        val target = open()
        still(target, fixture("import-hops.svg"), seconds = 0.75)
        val project = target.settle()
        assertEquals(IntRect(70, 0, 90, 20), project.cel(project.newLayerId(), project.frames[0].id)?.bounds)
    }

    /** Blue over the left half of the canvas, already on the active layer. */
    private fun paintActiveLayer(target: DrawingSession, celStore: TestStore = store) {
        val layer = target.state.value.layerId
        val frame = target.state.value.frameId
        val existing = Bitmap.createBitmap(120, 100, Bitmap.Config.ARGB_8888)
        existing.eraseColor(Color.BLUE)
        val file = celStore.write(existing)
        runBlocking {
            target.applyEdit { ImportEdits.replaceCel(it, layer, frame, PlacedImage(file, IntRect(-20, 0, 100, 100))) }
        }
    }

    @Test
    fun `on the current layer it goes over what is there, in a new cel, as one undo step`() {
        val target = open()
        paintActiveLayer(target)
        val before = target.settle()
        val layer = target.state.value.layerId
        val frame = target.state.value.frameId
        val beforeCel = before.cel(layer, frame)
        assertEquals(SvgImportOutcome.DONE, still(target, square(), SvgImportPlace.CURRENT_LAYER, SvgFit.ORIGINAL_SIZE))
        val project = target.settle()
        assertEquals(before.layers, project.layers)
        val cel = project.cel(layer, frame) ?: error("no cel")
        assertNotEquals(beforeCel?.file, cel.file)
        // Whatever the layer has past the edge of the canvas is still there.
        assertEquals(IntRect(-20, 0, 110, 100), cel.bounds)
        val image = store.images[cel.file] ?: error("no image")
        assertEquals(Color.BLUE, image.getPixel(10, 50))
        assertEquals(red, image.getPixel(115, 50))
        // The old file was not touched, because files never change.
        assertEquals(Color.BLUE, store.images[beforeCel?.file]?.getPixel(115, 50))
        target.undo()
        assertEquals(beforeCel, target.settle().cel(layer, frame))
    }

    @Test
    fun `the surface shows the combined cel straight away`() {
        val target = open()
        paintActiveLayer(target)
        still(target, square(), SvgImportPlace.CURRENT_LAYER, SvgFit.ORIGINAL_SIZE)
        target.settle()
        val active = target.scene?.active ?: error("nothing on the surface")
        assertEquals(red, active.bitmap.getPixel(100 - active.left, 50 - active.top))
        assertEquals(Color.BLUE, active.bitmap.getPixel(50 - active.left, 50 - active.top))
    }

    @Test
    fun `an empty current layer simply gets the picture`() {
        val target = open()
        val layer = target.state.value.layerId
        val frame = target.state.value.frameId
        assertEquals(SvgImportOutcome.DONE, still(target, square(), SvgImportPlace.CURRENT_LAYER, SvgFit.ORIGINAL_SIZE))
        assertEquals(IntRect(90, 40, 110, 60), target.settle().cel(layer, frame)?.bounds)
    }

    @Test
    fun `a locked or hidden current layer is refused, and nothing changes`() {
        val target = open()
        val layer = target.state.value.layerId
        target.setLayerLocked(layer, true)
        val locked = target.settle()
        assertEquals(SvgImportOutcome.LAYER_NOT_DRAWABLE, still(target, square(), SvgImportPlace.CURRENT_LAYER))
        assertEquals(locked, target.settle())
        target.setLayerLocked(layer, false)
        target.setLayerVisible(layer, false)
        val hidden = target.settle()
        assertEquals(SvgImportOutcome.LAYER_NOT_DRAWABLE, still(target, square(), SvgImportPlace.CURRENT_LAYER))
        assertEquals(hidden, target.settle())
    }

    @Test
    fun `a layer's drawing that cannot be read is never lost by going ahead without it`() {
        val target = open()
        val layer = target.state.value.layerId
        val frame = target.state.value.frameId
        paintActiveLayer(target)
        val before = target.settle()
        store.images.remove(before.cel(layer, frame)?.file)
        assertEquals(SvgImportOutcome.FAILED, still(target, square(), SvgImportPlace.CURRENT_LAYER))
        assertEquals(before.cel(layer, frame), target.settle().cel(layer, frame))
    }

    @Test
    fun `a background is saved and shown, and no layer changes`() {
        val target = open()
        val before = target.settle()
        assertEquals(SvgImportOutcome.DONE, still(target, square(), SvgImportPlace.BACKGROUND, SvgFit.ORIGINAL_SIZE))
        val project = target.settle()
        assertEquals(1, savedBackgrounds)
        val background = project.background as? Background.Image ?: error("the background did not change")
        assertEquals(before.layers, project.layers)
        assertEquals(before.frames, project.frames)
        assertEquals(before.cels, project.cels)
        val image = store.images[background.file] ?: error("no image")
        assertEquals(200, image.width)
        assertEquals(100, image.height)
        assertEquals(red, image.getPixel(100, 50))
        assertNotNull(target.scene?.backgroundImage)
    }

    @Test
    fun `a background that cannot be saved leaves the old one, and says storage ran out`() {
        val target = open()
        val before = target.settle()
        val failing = SvgImporter(target, store, { throw IOException("disk full") })
        val outcome =
            runBlocking {
                failing.import(
                    square(),
                    SvgImportRequest.SingleImage(0.0, SvgImportPlace.BACKGROUND, SvgFit.FIT_INSIDE, "Picture"),
                )
            }
        assertEquals(SvgImportOutcome.OUT_OF_STORAGE, outcome)
        assertEquals(before, target.settle())
    }

    @Test
    fun `a picture that is completely empty changes nothing and says so`() {
        val empty = SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20"/>""")
        val target = open()
        val before = target.settle()
        for (place in SvgImportPlace.entries) {
            assertEquals(SvgImportOutcome.NOTHING_TO_IMPORT, still(target, empty, place))
        }
        assertEquals(SvgImportOutcome.NOTHING_TO_IMPORT, animate(target, empty, seconds = 1.0))
        assertEquals(before, target.settle())
    }

    @Test
    fun `lifted pixels are put down first, so they cannot be lost`() {
        val target = open()
        paintActiveLayer(target)
        target.settle()
        target.liftSelection(floatArrayOf(10f, 10f, 40f, 10f, 40f, 40f, 10f, 40f))
        target.settle()
        assertNotNull(target.state.value.selection)
        assertEquals(SvgImportOutcome.DONE, still(target, square(), SvgImportPlace.CURRENT_LAYER, SvgFit.ORIGINAL_SIZE))
        val project = target.settle()
        val cel = project.cel(target.state.value.layerId, target.state.value.frameId) ?: error("no cel")
        val image = store.images[cel.file] ?: error("no image")
        val left = checkNotNull(cel.bounds).left
        assertEquals(Color.BLUE, image.getPixel(25 - left, 25))
    }

    // ----- When it does not work out -----

    @Test
    fun `cancelling leaves the project exactly as it was, with no undo step`() {
        val target = open()
        val before = target.settle()
        val canUndoBefore = target.state.value.canUndo
        var finished = false
        var cancelled = false
        val document = fixture("import-hops.svg")
        runBlocking {
            var running: Job? = null
            running =
                CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
                    try {
                        importer(target).import(document, SvgImportRequest.Animation(10.0, SvgFit.FIT_INSIDE, "Hops")) { done, _ ->
                            if (done == 5) running?.cancel()
                        }
                        finished = true
                    } catch (e: CancellationException) {
                        cancelled = true
                        throw e
                    }
                }
            running.start()
            running.join()
        }
        assertFalse(finished)
        assertTrue(cancelled)
        assertEquals(before, target.settle())
        assertEquals(canUndoBefore, target.state.value.canUndo)
        assertTrue(store.discarded.isEmpty())
    }

    @Test
    fun `running out of memory says so and changes nothing`() {
        val target = open()
        val before = target.settle()
        store.failWritesAfter = 1
        assertEquals(SvgImportOutcome.OUT_OF_MEMORY, animate(target, fixture("import-hops.svg")))
        assertEquals(before, target.settle())
        assertFalse(target.state.value.canUndo)
    }

    @Test
    fun `running out of storage says so, changes nothing, and gives back what it wrote`() {
        val target = open()
        val before = target.settle()
        store.failDiskAfter = 1
        assertEquals(SvgImportOutcome.OUT_OF_STORAGE, animate(target, fixture("import-hops.svg")))
        assertEquals(before, target.settle())
        assertFalse(target.state.value.canUndo)
        assertEquals(store.writes.get(), store.discarded.size)
        assertTrue(store.discarded.isNotEmpty())
    }

    @Test
    fun `a full disk stops a long import early instead of queueing every picture`() {
        val document =
            SvgParser.parse(
                """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="10" height="10">
                  <animate attributeName="x" from="0" to="90" dur="100s"/></rect></svg>""",
            )
        val target = open()
        store.failDiskAfter = 0
        assertEquals(SvgImportOutcome.OUT_OF_STORAGE, animate(target, document, seconds = 100.0))
        assertTrue("wrote ${store.writes.get()}", store.writes.get() <= 8)
    }

    @Test
    fun `a canvas too big for the device says memory ran out and does not crash`() {
        // Past what a bitmap can address, which no phone can draw on however the file behaves.
        val huge = newProject("Huge", 2_000_000, 2_000_000, 12, Background.Paper(PaperKind.WHITE), "Layer 1", now = 0)
        val giant = DrawingSession(huge, store, MemoryBudget.forMemoryClass(256, isLowRam = false), temp.newFolder())
        val outcome = runBlocking { importer(giant).import(square(), SvgImportRequest.Animation(1.0, SvgFit.FIT_INSIDE, "Hops")) }
        assertEquals(SvgImportOutcome.OUT_OF_MEMORY, outcome)
        giant.close()
    }

    @Test
    fun `memory running out while the result is shown still leaves the project unchanged`() {
        val target = open()
        paintActiveLayer(target)
        val before = target.settle()
        val layer = target.state.value.layerId
        val frame = target.state.value.frameId
        // The session loads the new cel on to the surface once the project has already changed, and that is where this fails.
        store.failReadsOf = "cels/2.png"
        val first =
            runBlocking {
                importer(
                    target,
                ).import(square(), SvgImportRequest.SingleImage(0.0, SvgImportPlace.CURRENT_LAYER, SvgFit.ORIGINAL_SIZE, "Picture"))
            }
        assertEquals(SvgImportOutcome.OUT_OF_MEMORY, first)
        val project = target.settle()
        assertEquals(before.cel(layer, frame), project.cel(layer, frame))
        assertEquals(before.layers, project.layers)
    }
}
