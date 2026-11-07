package org.kineograph.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kineograph.engine.history.History
import org.kineograph.engine.history.HistoryCursor
import org.kineograph.engine.history.PatchCodec
import org.kineograph.engine.history.PatchPayload
import org.kineograph.engine.history.PixelEntry
import org.kineograph.engine.history.StructureEntry
import org.kineograph.model.Background
import org.kineograph.model.Cel
import org.kineograph.model.CelKey
import org.kineograph.model.FrameSlot
import org.kineograph.model.IntRect
import org.kineograph.model.Layer
import org.kineograph.model.Project
import java.util.Random

class HistoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun history(memory: Long = 1_000_000, disk: Long = 10_000_000, minSteps: Int = 100) = History(
        memory,
        disk,
        minSteps,
        temp.newFolder(),
    )

    private fun noise(count: Int, seed: Long = 1): IntArray {
        val random = Random(seed)
        return IntArray(count) { random.nextInt() }
    }

    private fun pixelEntry(pixels: IntArray) = PixelEntry("l", "f", IntRect(0, 0, pixels.size, 1), PatchPayload(pixels))

    private fun project(file: String): Project =
        Project(
            id = "p",
            name = "n",
            createdAt = 0,
            updatedAt = 0,
            width = 1,
            height = 1,
            fps = 12,
            background = Background.Transparent,
            layers = listOf(Layer("l", "L")),
            frames = listOf(FrameSlot("f")),
            cels = mapOf(CelKey("l", "f") to Cel("l", "f", file, IntRect(0, 0, 1, 1))),
        )

    @Test
    fun `codec round trips pixels exactly`() {
        val pixels = noise(10_000)
        assertArrayEquals(pixels, PatchCodec.decompress(PatchCodec.compress(pixels), pixels.size))
    }

    @Test
    fun `codec handles an empty patch`() {
        assertEquals(0, PatchCodec.decompress(PatchCodec.compress(IntArray(0)), 0).size)
    }

    @Test
    fun `mostly transparent patches shrink a lot`() {
        val pixels = IntArray(100_000)
        pixels[500] = -1
        assertTrue(PatchCodec.compress(pixels).size < pixels.size * 4 / 100)
    }

    @Test
    fun `a payload reads back the same in every state`() {
        val pixels = noise(1000)
        val payload = PatchPayload(pixels.copyOf())
        assertArrayEquals(pixels, payload.readOrNull())
        payload.compress()
        assertTrue(payload.isCompressed)
        assertArrayEquals(pixels, payload.readOrNull())
        assertTrue(payload.spillTo(temp.newFile()))
        assertTrue(payload.isSpilled)
        assertEquals(0L, payload.memoryBytes)
        assertArrayEquals(pixels, payload.readOrNull())
    }

    @Test
    fun `a payload can spill straight from raw pixels`() {
        val pixels = noise(100)
        val payload = PatchPayload(pixels.copyOf())
        assertTrue(payload.spillTo(temp.newFile()))
        assertArrayEquals(pixels, payload.readOrNull())
    }

    @Test
    fun `replacing a payload brings it back to memory and removes its file`() {
        val file = temp.newFile()
        val payload = PatchPayload(noise(100, seed = 1))
        payload.spillTo(file)
        val replacement = noise(100, seed = 2)
        payload.replace(replacement.copyOf())
        assertFalse(file.exists())
        assertFalse(payload.isSpilled)
        assertArrayEquals(replacement, payload.readOrNull())
    }

    @Test
    fun `a failed spill keeps the pixels in memory`() {
        val folder = temp.newFolder()
        val pixels = noise(100)
        val payload = PatchPayload(pixels.copyOf())
        assertFalse(payload.spillTo(folder))
        assertFalse(payload.isSpilled)
        assertArrayEquals(pixels, payload.readOrNull())
    }

    @Test
    fun `undo and redo move entries between stacks`() {
        val history = history()
        val a = pixelEntry(noise(4))
        val b = pixelEntry(noise(4))
        history.push(a)
        history.push(b)
        assertSame(b, history.popUndo())
        assertTrue(history.canRedo)
        assertSame(b, history.popRedo())
        assertFalse(history.canRedo)
        assertSame(b, history.popUndo())
        assertSame(a, history.popUndo())
        assertFalse(history.canUndo)
        assertNull(history.popUndo())
    }

    @Test
    fun `a new edit clears redo`() {
        val history = history()
        val undone = pixelEntry(noise(4))
        history.push(undone)
        history.popUndo()
        history.push(pixelEntry(noise(4)))
        assertFalse(history.canRedo)
        assertNull(history.popRedo())
    }

    @Test
    fun `at least one hundred steps survive however tight memory is`() {
        val history = history(memory = 1024, disk = 1024, minSteps = 100)
        repeat(300) { history.push(pixelEntry(noise(2_000, seed = it.toLong()))) }
        assertEquals(100, history.undoDepth)
    }

    @Test
    fun `when memory is tight old patches go to disk but stay undoable`() {
        val history = history(memory = 100_000, disk = 100_000_000, minSteps = 100)
        val first = noise(5_000, seed = 99)
        val firstEntry = pixelEntry(first.copyOf())
        history.push(firstEntry)
        repeat(50) { history.push(pixelEntry(noise(5_000, seed = it.toLong()))) }
        assertEquals(51, history.undoDepth)
        assertTrue(history.memoryBytes <= 100_000)
        assertTrue(firstEntry.payload.isSpilled)
        assertArrayEquals(first, firstEntry.payload.readOrNull())
    }

    @Test
    fun `with room to spare nothing is dropped or spilled`() {
        val history = history(memory = 100_000_000, disk = 100_000_000, minSteps = 10)
        val entries = List(500) { pixelEntry(noise(16, seed = it.toLong())) }
        entries.forEach(history::push)
        assertEquals(500, history.undoDepth)
        assertEquals(0L, history.diskBytes)
    }

    @Test
    fun `dropped entries free their files`() {
        val dir = temp.newFolder()
        val history = History(memoryBudgetBytes = 1, diskBudgetBytes = 1, minSteps = 2, spillDirectory = dir)
        repeat(10) { history.push(pixelEntry(noise(1000, seed = it.toLong()))) }
        assertEquals(2, history.undoDepth)
        assertTrue((dir.listFiles()?.size ?: 0) <= 2)
    }

    @Test
    fun `spill all empties memory`() {
        val history = history()
        history.push(pixelEntry(noise(2000)))
        history.spillAll()
        assertTrue(history.memoryBytes < 1000)
    }

    @Test
    fun `structure entries report the cel files they keep alive`() {
        val history = history()
        val cursor = HistoryCursor("f", "l")
        history.push(StructureEntry(project("old.webp"), project("new.webp"), cursor, cursor))
        assertEquals(setOf("old.webp", "new.webp"), history.referencedFiles())
    }

    @Test
    fun `a large patch survives compression through the small scratch buffer`() {
        val pixels = noise(100_000)
        assertArrayEquals(pixels, PatchCodec.decompress(PatchCodec.compress(pixels), pixels.size))
    }

    @Test
    fun `a spilled patch whose file was deleted reads as gone rather than throwing`() {
        val file = temp.newFile()
        val payload = PatchPayload(noise(100))
        assertTrue(payload.spillTo(file))
        assertTrue(file.delete())
        assertNull(payload.readOrNull())
    }

    @Test
    fun `a spilled patch whose file is damaged reads as gone rather than as garbage`() {
        val file = temp.newFile()
        val payload = PatchPayload(noise(100))
        assertTrue(payload.spillTo(file))
        file.writeBytes(byteArrayOf(-1, -1, -1, -1, -1, -1))
        assertNull(payload.readOrNull())
    }

    @Test
    fun `a spilled patch whose file was cut short reads as gone rather than as transparent pixels`() {
        val file = temp.newFile()
        val payload = PatchPayload(noise(1000))
        assertTrue(payload.spillTo(file))
        file.writeBytes(file.readBytes().copyOf(50))
        assertNull(payload.readOrNull())
    }

    @Test
    fun `peeking leaves the stacks alone`() {
        val history = history()
        val entry = pixelEntry(noise(4))
        history.push(entry)
        assertSame(entry, history.peekUndo())
        assertEquals(1, history.undoDepth)
        assertNull(history.peekRedo())
        history.popUndo()
        assertSame(entry, history.peekRedo())
        assertEquals(1, history.redoDepth)
    }

    @Test
    fun `losing an undo step forgets what is older and keeps what can be redone`() {
        val history = history()
        val redoable = pixelEntry(noise(4, seed = 3))
        history.push(pixelEntry(noise(4, seed = 1)))
        history.push(pixelEntry(noise(4, seed = 2)))
        history.push(redoable)
        history.popUndo()
        history.discardUndoSteps()
        assertFalse(history.canUndo)
        assertSame(redoable, history.peekRedo())
    }

    @Test
    fun `losing a redo step keeps what can be undone`() {
        val history = history()
        val undoable = pixelEntry(noise(4, seed = 1))
        history.push(undoable)
        history.push(pixelEntry(noise(4, seed = 2)))
        history.popUndo()
        history.discardRedoSteps()
        assertFalse(history.canRedo)
        assertSame(undoable, history.peekUndo())
    }

    @Test
    fun `clear removes everything including spill files`() {
        val dir = temp.newFolder()
        val history = History(1, 100_000_000, 100, dir)
        repeat(5) { history.push(pixelEntry(noise(100, seed = it.toLong()))) }
        history.clear()
        assertFalse(history.canUndo)
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }
}
