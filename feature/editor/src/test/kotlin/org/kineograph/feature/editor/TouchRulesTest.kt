package org.kineograph.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kineograph.engine.brush.BrushPresets
import org.kineograph.feature.editor.canvas.CanvasTool
import org.kineograph.feature.editor.canvas.DoubleTapResolver
import org.kineograph.feature.editor.canvas.TapClassifier
import org.kineograph.feature.editor.canvas.TapGesture
import org.kineograph.feature.editor.canvas.secondFingerStartsGesture

class TouchRulesTest {
    @Test
    fun `a quick still two-finger touch is a tap`() {
        val tap = TapClassifier(slopPx = 10f)
        tap.begin(0, 2)
        tap.onMoved(3f)
        assertEquals(TapGesture.TWO_FINGER_TAP, tap.end(120))
    }

    @Test
    fun `three fingers make a redo tap`() {
        val tap = TapClassifier(slopPx = 10f)
        tap.begin(0, 2)
        tap.onPointerCount(3)
        assertEquals(TapGesture.THREE_FINGER_TAP, tap.end(120))
    }

    @Test
    fun `moving turns a tap into a pan`() {
        val tap = TapClassifier(slopPx = 10f)
        tap.begin(0, 2)
        tap.onMoved(11f)
        assertEquals(TapGesture.NONE, tap.end(120))
    }

    @Test
    fun `holding too long is not a tap`() {
        val tap = TapClassifier(slopPx = 10f)
        tap.begin(0, 2)
        assertEquals(TapGesture.NONE, tap.end(900))
    }

    @Test
    fun `a classifier can be reused`() {
        val tap = TapClassifier(slopPx = 10f)
        tap.begin(0, 3)
        tap.onMoved(50f)
        tap.end(100)
        tap.begin(1000, 2)
        assertEquals(TapGesture.TWO_FINGER_TAP, tap.end(1100))
    }

    @Test
    fun `two taps close together are a double tap`() {
        val resolver = DoubleTapResolver(windowMs = 260)
        assertFalse(resolver.onTwoFingerTap(1000))
        assertTrue(resolver.onTwoFingerTap(1200))
    }

    @Test
    fun `two taps far apart are two undos`() {
        val resolver = DoubleTapResolver(windowMs = 260)
        assertFalse(resolver.onTwoFingerTap(1000))
        assertFalse(resolver.onTwoFingerTap(2000))
    }

    @Test
    fun `a third tap after a double starts afresh`() {
        val resolver = DoubleTapResolver(windowMs = 260)
        resolver.onTwoFingerTap(1000)
        assertTrue(resolver.onTwoFingerTap(1100))
        assertFalse(resolver.onTwoFingerTap(1200))
    }

    @Test
    fun `a second finger soon after the first starts a gesture`() {
        assertTrue(secondFingerStartsGesture(elapsedMs = 80, travelledPx = 5f, windowMs = 160, maxTravelPx = 60f))
    }

    @Test
    fun `a second finger after drawing has begun is treated as a palm`() {
        assertFalse(secondFingerStartsGesture(elapsedMs = 500, travelledPx = 5f, windowMs = 160, maxTravelPx = 60f))
        assertFalse(secondFingerStartsGesture(elapsedMs = 80, travelledPx = 200f, windowMs = 160, maxTravelPx = 60f))
    }

    // ----- Tool state -----

    @Test
    fun `the size slider is logarithmic and reversible`() {
        assertEquals(1f, ToolState.sliderToSize(0f), 0.01f)
        assertEquals(ToolState.MAX_SIZE, ToolState.sliderToSize(1f), 0.1f)
        assertEquals(20f, ToolState.sliderToSize(0.5f), 1f)
        for (size in listOf(1f, 3f, 17f, 90f, 400f)) {
            assertEquals(size, ToolState.sliderToSize(ToolState.sizeToSlider(size)), 0.1f)
        }
    }

    @Test
    fun `each brush remembers its own size and opacity`() {
        var tools = ToolState().withActiveSize(50f).withActiveOpacity(0.5f)
        tools = tools.copy(brushId = BrushPresets.PENCIL.id).withActiveSize(9f)
        assertEquals(9f, tools.activeSize, 0f)
        assertEquals(BrushPresets.PENCIL.opacity, tools.activeOpacity, 0f)
        tools = tools.copy(brushId = BrushPresets.PEN.id)
        assertEquals(50f, tools.activeSize, 0f)
        assertEquals(0.5f, tools.activeOpacity, 0f)
    }

    @Test
    fun `the eraser has its own settings`() {
        val tools = ToolState().withActiveSize(50f).copy(tool = CanvasTool.ERASER).withActiveSize(80f)
        assertEquals(80f, tools.activeSize, 0f)
        assertEquals(80f, tools.toCanvasConfig().eraser.sizePx, 0f)
        assertEquals(50f, tools.toCanvasConfig().brush.sizePx, 0f)
    }

    @Test
    fun `default sizes follow the canvas`() {
        val pixelArt = ToolState().scaledForCanvas(64, 64)
        val full = ToolState().scaledForCanvas(3840, 2160)
        assertEquals(1f, pixelArt.activeSize, 0f)
        assertTrue(full.activeSize > BrushPresets.PEN.sizePx * 2)
    }

    @Test
    fun `sizes stay within limits`() {
        assertEquals(ToolState.MAX_SIZE, ToolState().withActiveSize(99999f).activeSize, 0f)
        assertEquals(ToolState.MIN_SIZE, ToolState().withActiveSize(-1f).activeSize, 0f)
    }

    @Test
    fun `recent colours put the newest first without repeats`() {
        val tools = ToolState().withRecent(1).withRecent(2).withRecent(1)
        assertEquals(listOf(1, 2), tools.recentColors)
    }

    @Test
    fun `recent colours are limited`() {
        var tools = ToolState()
        repeat(30) { tools = tools.withRecent(it) }
        assertEquals(12, tools.recentColors.size)
        assertEquals(29, tools.recentColors.first())
    }

    @Test
    fun `the canvas gets the chosen brush`() {
        val config = ToolState(brushId = BrushPresets.MARKER.id).toCanvasConfig()
        assertEquals(BrushPresets.MARKER.id, config.brush.id)
        assertEquals(BrushPresets.ERASER.id, config.eraser.id)
    }
}
