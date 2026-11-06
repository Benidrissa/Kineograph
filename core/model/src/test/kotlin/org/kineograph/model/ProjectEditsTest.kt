package org.kineograph.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectEditsTest {
    private fun cel(layer: String, frame: String, file: String = "$layer-$frame.webp") = Cel(layer, frame, file, IntRect(0, 0, 4, 4))

    private fun project(): Project =
        Project(
            id = "p",
            name = "Test",
            createdAt = 0,
            updatedAt = 0,
            width = 100,
            height = 100,
            fps = 12,
            background = Background.Transparent,
            layers = listOf(Layer("L1", "One"), Layer("L2", "Two")),
            frames = listOf(FrameSlot("A"), FrameSlot("B", 2), FrameSlot("C")),
            cels = listOf(cel("L1", "A"), cel("L1", "B"), cel("L2", "B")).associateBy { CelKey(it.layerId, it.frameId) },
        )

    @Test
    fun `add frame goes after the given frame`() {
        val result = ProjectEdits.addFrameAfter(project(), "A", "X")
        assertEquals(listOf("A", "X", "B", "C"), result.frames.map { it.id })
    }

    @Test
    fun `add frame with no anchor goes at the end`() {
        assertEquals(
            "X",
            ProjectEdits
                .addFrameAfter(project(), null, "X")
                .frames
                .last()
                .id,
        )
        assertEquals(
            "X",
            ProjectEdits
                .addFrameAfter(project(), "missing", "X")
                .frames
                .last()
                .id,
        )
    }

    @Test
    fun `insert frame goes before the given frame`() {
        val result = ProjectEdits.insertFrameBefore(project(), "B", "X")
        assertEquals(listOf("A", "X", "B", "C"), result.frames.map { it.id })
    }

    @Test
    fun `duplicate frame keeps the hold and shares cel files`() {
        val result = ProjectEdits.duplicateFrame(project(), "B", "X")
        assertEquals(listOf("A", "B", "X", "C"), result.frames.map { it.id })
        assertEquals(2, result.frames[2].hold)
        assertEquals("L1-B.webp", result.cel("L1", "X")?.file)
        assertEquals("L2-B.webp", result.cel("L2", "X")?.file)
        assertEquals("X", result.cel("L1", "X")?.frameId)
    }

    @Test
    fun `delete frame removes its cels`() {
        val result = ProjectEdits.deleteFrame(project(), "B")
        assertEquals(listOf("A", "C"), result.frames.map { it.id })
        assertNull(result.cel("L1", "B"))
        assertNotNull(result.cel("L1", "A"))
    }

    @Test
    fun `deleting the last frame clears it instead`() {
        val single = project().copy(frames = listOf(FrameSlot("A")))
        val result = ProjectEdits.deleteFrame(single, "A")
        assertEquals(listOf("A"), result.frames.map { it.id })
        assertNull(result.cel("L1", "A"))
    }

    @Test
    fun `move frame reorders`() {
        val result = ProjectEdits.moveFrame(project(), 0, 2)
        assertEquals(listOf("B", "C", "A"), result.frames.map { it.id })
    }

    @Test
    fun `invalid move changes nothing`() {
        val original = project()
        assertSame(original, ProjectEdits.moveFrame(original, 0, 9))
        assertSame(original, ProjectEdits.moveFrame(original, 1, 1))
    }

    @Test
    fun `clear frame on every layer`() {
        val result = ProjectEdits.clearFrame(project(), "B")
        assertNull(result.cel("L1", "B"))
        assertNull(result.cel("L2", "B"))
        assertNotNull(result.cel("L1", "A"))
    }

    @Test
    fun `clear frame on one layer leaves the others`() {
        val result = ProjectEdits.clearFrame(project(), "B", "L1")
        assertNull(result.cel("L1", "B"))
        assertNotNull(result.cel("L2", "B"))
    }

    @Test
    fun `set hold never goes below one`() {
        assertEquals(1, ProjectEdits.setHold(project(), "A", 0).frames[0].hold)
        assertEquals(5, ProjectEdits.setHold(project(), "A", 5).frames[0].hold)
    }

    @Test
    fun `copy and paste frames across all layers`() {
        val original = project()
        val clipboard = ProjectEdits.copyFrames(original, listOf("B"))
        var next = 0
        val result = ProjectEdits.pasteFrames(original, clipboard, "C", "L1", newIds = { "N${next++}" })
        assertEquals(listOf("A", "B", "C", "N0"), result.frames.map { it.id })
        assertEquals(2, result.frames.last().hold)
        assertEquals("L1-B.webp", result.cel("L1", "N0")?.file)
        assertEquals("L2-B.webp", result.cel("L2", "N0")?.file)
    }

    @Test
    fun `copied frames keep timeline order`() {
        val clipboard = ProjectEdits.copyFrames(project(), listOf("C", "A"))
        assertEquals(2, clipboard.frames.size)
        assertTrue(clipboard.frames[0].cels.containsKey("L1"))
        assertTrue(clipboard.frames[1].cels.isEmpty())
    }

    @Test
    fun `frames copied from one layer paste onto the target layer`() {
        val original = project()
        val clipboard = ProjectEdits.copyFrames(original, listOf("B"), layerId = "L1")
        val result = ProjectEdits.pasteFrames(original, clipboard, "A", "L2", newIds = { "N" })
        assertEquals("L1-B.webp", result.cel("L2", "N")?.file)
        assertNull(result.cel("L1", "N"))
    }

    @Test
    fun `paste drops cels of deleted layers`() {
        val original = project()
        val clipboard = ProjectEdits.copyFrames(original, listOf("B"))
        val withoutLayer = ProjectEdits.deleteLayer(original, "L2")
        val result = ProjectEdits.pasteFrames(withoutLayer, clipboard, "A", "L1", newIds = { "N" })
        assertNull(result.cel("L2", "N"))
        assertNotNull(result.cel("L1", "N"))
    }

    @Test
    fun `layers can be added without limit`() {
        var result = project()
        repeat(1000) { result = ProjectEdits.addLayer(result, null, "Layer $it") }
        assertEquals(1002, result.layers.size)
    }

    @Test
    fun `add layer goes above the anchor`() {
        val result = ProjectEdits.addLayer(project(), "L1", "New", "X")
        assertEquals(listOf("L1", "X", "L2"), result.layers.map { it.id })
    }

    @Test
    fun `delete layer removes its cels`() {
        val result = ProjectEdits.deleteLayer(project(), "L1")
        assertEquals(listOf("L2"), result.layers.map { it.id })
        assertNull(result.cel("L1", "A"))
    }

    @Test
    fun `the last layer cannot be deleted`() {
        val single = ProjectEdits.deleteLayer(project(), "L1")
        assertSame(single, ProjectEdits.deleteLayer(single, "L2"))
    }

    @Test
    fun `duplicate layer shares cel files`() {
        val result = ProjectEdits.duplicateLayer(project(), "L1", "Copy", "X")
        assertEquals(listOf("L1", "X", "L2"), result.layers.map { it.id })
        assertEquals("L1-A.webp", result.cel("X", "A")?.file)
        assertEquals("X", result.cel("X", "A")?.layerId)
    }

    @Test
    fun `move layer reorders`() {
        val result = ProjectEdits.moveLayer(project(), 0, 1)
        assertEquals(listOf("L2", "L1"), result.layers.map { it.id })
    }

    @Test
    fun `update layer changes only the target`() {
        val result = ProjectEdits.updateLayer(project(), "L2") { it.copy(visible = false, opacity = 0.5f) }
        assertTrue(result.layer("L1")?.visible == true)
        assertFalse(result.layer("L2")?.visible == true)
        assertEquals(0.5f, result.layer("L2")?.opacity ?: 0f, 0f)
    }

    @Test
    fun `fps is clamped to the supported range`() {
        assertEquals(60, ProjectEdits.setFps(project(), 500).fps)
        assertEquals(1, ProjectEdits.setFps(project(), 0).fps)
    }

    @Test
    fun `with cel stores and removes`() {
        val key = CelKey("L1", "C")
        val added = ProjectEdits.withCel(project(), key, cel("L1", "C"))
        assertNotNull(added.cel("L1", "C"))
        assertNull(ProjectEdits.withCel(added, key, null).cel("L1", "C"))
    }

    @Test
    fun `referenced files are deduplicated`() {
        val duplicated = ProjectEdits.duplicateFrame(project(), "B", "X")
        assertEquals(setOf("L1-A.webp", "L1-B.webp", "L2-B.webp"), duplicated.referencedFiles())
    }

    @Test
    fun `a new project has one frame and one layer and a clamped frame rate`() {
        val created = newProject("Mine", 64, 64, 120, Background.Transparent, "Layer 1", now = 5)
        assertEquals(1, created.frames.size)
        assertEquals(1, created.layers.size)
        assertEquals(MAX_FPS, created.fps)
    }
}
