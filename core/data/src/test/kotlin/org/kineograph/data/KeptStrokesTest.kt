package org.kineograph.data

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.data.files.CelFileStore
import org.kineograph.engine.brush.BrushPresets
import org.kineograph.engine.vector.RecordedStroke
import org.kineograph.model.IntRect
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Strokes kept beside drawings on disk, so in-betweens can use drawings from earlier sessions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class KeptStrokesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val bounds = IntRect(10, 10, 50, 30)

    private fun store(directory: File): CelFileStore = CelFileStore(directory, 1024 * 1024, Dispatchers.IO)

    private fun image(): Bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }

    private fun strokes(): List<RecordedStroke> =
        listOf(
            RecordedStroke(
                BrushPresets.PEN,
                7L,
                Color.BLACK,
                floatArrayOf(12f, 15f, 1f, 30f, 20f, 0.8f, 48f, 25f, 0.6f),
                3,
                3,
                IntRect(-20, -20, 220, 120),
            ),
            RecordedStroke(BrushPresets.PEN, 8L, Color.RED, floatArrayOf(15f, 12f, 1f, 40f, 28f, 1f), 2, 3, IntRect(-20, -20, 220, 120)),
        )

    private fun sidecar(directory: File, file: String) = File(directory, "vectors/${File(file).name.substringBeforeLast('.')}.kgv")

    /** Writes a drawing and its strokes, waits for both, and gives back the drawing's name. */
    private fun keep(store: CelFileStore): String {
        val file = store.write(image())
        store.keepStrokes(file, bounds, strokes())
        runBlocking {
            store.awaitWrites()
            store.awaitKeptStrokes()
        }
        return file
    }

    @Test
    fun `kept strokes come back exactly in a later session`() {
        val directory = temp.newFolder()
        val file = store(directory).let { first -> keep(first).also { first.close() } }

        val read = store(directory).keptStrokes(file, bounds)

        assertNotNull(read)
        assertEquals(2, read?.size)
        val original = strokes()
        read?.zip(original)?.forEach { (back, sent) ->
            assertEquals(sent.seed, back.seed)
            assertEquals(sent.colorArgb, back.colorArgb)
            assertEquals(sent.count, back.count)
            assertTrue(sent.samples.contentEquals(back.samples))
        }
    }

    @Test
    fun `strokes are kept beside the drawing, under its name`() {
        val directory = temp.newFolder()
        val file = keep(store(directory))

        assertTrue(sidecar(directory, file).isFile)
    }

    @Test
    fun `deleting a drawing deletes its strokes`() {
        val directory = temp.newFolder()
        val store = store(directory)
        val file = keep(store)

        store.delete(setOf(file), keep = emptySet())

        assertFalse(sidecar(directory, file).exists())
    }

    @Test
    fun `strokes left without their drawing are swept, and the others stay`() {
        val directory = temp.newFolder()
        val store = store(directory)
        val kept = keep(store)
        val orphan = File(directory, "vectors/orphan.kgv").apply { writeBytes(ByteArray(8)) }

        store.delete(emptySet(), keep = emptySet())

        assertFalse(orphan.exists())
        assertTrue(sidecar(directory, kept).isFile)
    }

    @Test
    fun `giving up on a drawing gives up its strokes`() {
        val directory = temp.newFolder()
        val store = store(directory)
        val file = keep(store)

        runBlocking { store.discard(listOf(file)) }

        assertFalse(sidecar(directory, file).exists())
    }

    @Test
    fun `a damaged file reads as no strokes, without throwing`() {
        val directory = temp.newFolder()
        val store = store(directory)
        val file = keep(store)
        val target = sidecar(directory, file)
        val bytes = target.readBytes()
        for (cut in listOf(0, 1, bytes.size / 2, bytes.size - 1)) {
            target.writeBytes(bytes.copyOf(cut))
            assertNull("cut at $cut", store.keptStrokes(file, bounds))
        }
        target.writeBytes(bytes.also { it[it.size / 3] = (it[it.size / 3] + 1).toByte() })
        assertNull(store.keptStrokes(file, bounds))
    }

    @Test
    fun `strokes for a drawing of another size are not used`() {
        val directory = temp.newFolder()
        val store = store(directory)
        val file = keep(store)

        assertNull(store.keptStrokes(file, IntRect(10, 10, 60, 30)))
    }

    @Test
    fun `a drawing that moved keeps its strokes, which land where it now is`() {
        val directory = temp.newFolder()
        val store = store(directory)
        val file = keep(store)

        val moved = store.keptStrokes(file, bounds.translated(5, -3))

        assertEquals(listOf(5 to -3, 5 to -3), moved?.map { it.offsetX to it.offsetY })
    }

    @Test
    fun `a file far too large is not read at all`() {
        val directory = temp.newFolder()
        val store = store(directory)
        val file = keep(store)
        java.io.RandomAccessFile(sidecar(directory, file), "rw").use { it.setLength(64L * 1024 * 1024) }

        assertNull(store.keptStrokes(file, bounds))
    }

    @Test
    fun `nothing is written once the store has closed`() {
        val directory = temp.newFolder()
        val store = store(directory)
        val file = store.write(image())
        runBlocking { store.awaitWrites() }
        store.close()

        store.keepStrokes(file, bounds, strokes())

        assertFalse(sidecar(directory, file).exists())
    }
}
