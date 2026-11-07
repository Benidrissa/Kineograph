package org.kineograph.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportEditsTest {
    private fun cel(layer: String, frame: String) = Cel(layer, frame, "$layer-$frame.webp", IntRect(0, 0, 4, 4))

    /** Three slots, four ticks: A takes one, B takes two and C takes one. */
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

    private fun image(name: String) = PlacedImage("cels/$name.webp", IntRect(1, 2, 3, 4))

    private fun newIds(): () -> String {
        var next = 0
        return { "N${next++}" }
    }

    private fun animation(
        start: String = "A",
        ticks: Int,
        images: Map<Int, PlacedImage>,
        project: Project = project(),
        above: String? = "L1",
    ): Project = ImportEdits.addAnimationLayer(project, above, start, "Imported", ticks, images, "NEW", newIds())

    @Test
    fun `a duration becomes the nearest number of ticks`() {
        assertEquals(24, ImportEdits.tickCount(2.0, 12))
        assertEquals(1, ImportEdits.tickCount(0.08, 12))
        assertEquals(2, ImportEdits.tickCount(0.125, 12))
    }

    @Test
    fun `there is always at least one tick`() {
        assertEquals(1, ImportEdits.tickCount(0.001, 12))
        assertEquals(1, ImportEdits.tickCount(0.0, 12))
        assertEquals(1, ImportEdits.tickCount(-1.0, 12))
        assertEquals(1, ImportEdits.tickCount(Double.NaN, 12))
    }

    @Test
    fun `a duration too long to count stays a whole number`() {
        assertEquals(Int.MAX_VALUE, ImportEdits.tickCount(1e300, 12))
    }

    @Test
    fun `the timeline grows when the animation runs past its end`() {
        val result = animation(ticks = 6, images = emptyMap())
        assertEquals(listOf("A", "B", "C", "N0", "N1"), result.frames.map { it.id })
        assertEquals(listOf(1, 2, 1, 1, 1), result.frames.map { it.hold })
    }

    @Test
    fun `the timeline stays as it is when the animation fits`() {
        assertEquals(project().frames, animation(ticks = 4, images = emptyMap()).frames)
        assertEquals(project().frames, animation(ticks = 1, images = emptyMap()).frames)
    }

    @Test
    fun `existing slots, layers and cels stay exactly as they were`() {
        val before = project()
        val result = animation(ticks = 8, images = (0 until 8).associateWith { image("t$it") })
        assertEquals(before.frames, result.frames.take(before.frames.size))
        assertEquals(before.layers, result.layers.filterNot { it.id == "NEW" })
        before.cels.forEach { (key, value) -> assertEquals(value, result.cels[key]) }
        assertEquals(before.cels.size, result.cels.count { it.key.layerId != "NEW" })
    }

    @Test
    fun `the new layer goes just above the active one and is named as asked`() {
        val result = animation(ticks = 1, images = emptyMap(), above = "L1")
        assertEquals(listOf("L1", "NEW", "L2"), result.layers.map { it.id })
        assertEquals("Imported", result.layer("NEW")?.name)
    }

    @Test
    fun `the animation starts on the given frame, not the first`() {
        val result = animation(start = "B", ticks = 2, images = mapOf(0 to image("a"), 1 to image("b")))
        assertNull(result.cel("NEW", "A"))
        assertEquals("cels/a.webp", result.cel("NEW", "B")?.file)
        // B holds for two ticks, so the second tick has nowhere of its own to go and the next one lands on C.
        assertNull(result.cel("NEW", "C"))
    }

    @Test
    fun `an animation starting late on the timeline extends it by only what runs over`() {
        val result = animation(start = "C", ticks = 3, images = mapOf(0 to image("a"), 1 to image("b"), 2 to image("c")))
        assertEquals(listOf("A", "B", "C", "N0", "N1"), result.frames.map { it.id })
        assertEquals("cels/a.webp", result.cel("NEW", "C")?.file)
        assertEquals("cels/b.webp", result.cel("NEW", "N0")?.file)
        assertEquals("cels/c.webp", result.cel("NEW", "N1")?.file)
    }

    @Test
    fun `a held slot shows its first tick, and the animation stays in time`() {
        val images = (0 until 4).associateWith { image("t$it") }
        val result = animation(ticks = 4, images = images)
        assertEquals("cels/t0.webp", result.cel("NEW", "A")?.file)
        assertEquals("cels/t1.webp", result.cel("NEW", "B")?.file)
        assertEquals("cels/t3.webp", result.cel("NEW", "C")?.file)
    }

    @Test
    fun `only the ticks that will be seen are sampled`() {
        assertArrayEquals(intArrayOf(0, 1, 3, 4, 5), ImportEdits.ticksToSample(project(), "A", 6))
        assertArrayEquals(intArrayOf(0, 1), ImportEdits.ticksToSample(project(), "A", 3))
        assertArrayEquals(intArrayOf(0, 2, 3), ImportEdits.ticksToSample(project(), "B", 4))
        assertArrayEquals(intArrayOf(0, 1, 2), ImportEdits.ticksToSample(project(), "C", 3))
    }

    @Test
    fun `every sampled tick has a place, and every place has a sampled tick`() {
        for (start in listOf("A", "B", "C")) {
            for (ticks in 1..10) {
                val sampled = ImportEdits.ticksToSample(project(), start, ticks)
                val result = animation(start, ticks, sampled.associateWith { image("t$it") })
                val placed = result.cels.values
                    .filter { it.layerId == "NEW" }
                    .mapNotNull { it.file }
                assertEquals(sampled.map { "cels/t$it.webp" }.toSet(), placed.toSet())
                assertEquals(sampled.size, placed.size)
            }
        }
    }

    @Test
    fun `a tick with no picture stores no cel, but still takes up its time`() {
        val result = animation(start = "C", ticks = 4, images = mapOf(0 to image("a"), 2 to image("b")))
        assertEquals("cels/a.webp", result.cel("NEW", "C")?.file)
        assertNull(result.cel("NEW", "N0"))
        assertEquals("cels/b.webp", result.cel("NEW", "N1")?.file)
        assertNull(result.cel("NEW", "N2"))
        assertEquals(6, result.frames.size)
    }

    @Test
    fun `identical frames keep sharing one file`() {
        val same = image("same")
        val result = animation(start = "C", ticks = 3, images = mapOf(0 to same, 1 to same, 2 to same))
        assertEquals(same.file, result.cel("NEW", "C")?.file)
        assertEquals(same.file, result.cel("NEW", "N0")?.file)
        assertEquals(same.file, result.cel("NEW", "N1")?.file)
        assertEquals(
            1,
            result.cels.values
                .filter { it.layerId == "NEW" }
                .mapNotNull { it.file }
                .toSet()
                .size,
        )
    }

    @Test
    fun `a cel keeps the bounds of its picture`() {
        val result = animation(ticks = 1, images = mapOf(0 to image("a")))
        assertEquals(IntRect(1, 2, 3, 4), result.cel("NEW", "A")?.bounds)
    }

    @Test
    fun `an unknown starting frame falls back to the first`() {
        val result = animation(start = "gone", ticks = 1, images = mapOf(0 to image("a")))
        assertEquals("cels/a.webp", result.cel("NEW", "A")?.file)
    }

    @Test
    fun `one image goes on a new layer on one frame`() {
        val before = project()
        val result = ImportEdits.addImageLayer(before, "L1", "B", "Imported", image("a"), "NEW")
        assertEquals(listOf("L1", "NEW", "L2"), result.layers.map { it.id })
        assertEquals(before.frames, result.frames)
        assertEquals("cels/a.webp", result.cel("NEW", "B")?.file)
        assertEquals(1, result.cels.count { it.key.layerId == "NEW" })
        before.cels.forEach { (key, value) -> assertEquals(value, result.cels[key]) }
    }

    @Test
    fun `a replaced cel changes only that layer on that frame`() {
        val before = project()
        val result = ImportEdits.replaceCel(before, "L1", "B", image("a"))
        assertEquals("cels/a.webp", result.cel("L1", "B")?.file)
        assertEquals(before.cels.size, result.cels.size)
        assertEquals(before.cel("L2", "B"), result.cel("L2", "B"))
        assertEquals(before.cel("L1", "A"), result.cel("L1", "A"))
        assertEquals(before.layers, result.layers)
        assertEquals(before.frames, result.frames)
    }
}
