package org.kineograph.feature.editor.canvas

/** The three things the low-latency library is asked to do. It sits behind this so the rules below can be tested without it. */
internal interface FrontBufferLayers {
    /** Asks for the front layer to be painted soon. The library may silently drop this request. */
    fun requestFrontPaint()

    /** Draws the scene to the buffered layer and hides the front layer, however the front layer got to be as it is. */
    fun commit()

    /** Hides and clears the front layer, without drawing the scene. */
    fun cancel()
}

/**
 * Decides what reaches the low-latency library, and when. This is where all of its traps are handled:
 *
 *  - When a commit starts, it throws away everything the library has queued for the front layer. One that had to wait behind another
 *    commit starts later, and takes whatever has been queued in the meantime. So stamps never go into the library's queue. They wait
 *    here, and the library only gets requests to come and fetch them, which it can lose without harm. Each scene draw ends by asking
 *    again, and nothing is painted until the last commit has finished.
 *  - That holding back is why every redraw is a commit. A redraw the library's other way does not hold stamps back, so one under way
 *    when the pen lands would hide the start of the stroke.
 *  - A stroke's commit is issued once the engine has merged it. Until then its stamps are on the front layer and must stay, so
 *    the next stroke is held back here. Anything painted before the commit would vanish with the old stroke.
 *  - A commit hides the front layer, so one asked for during a stroke waits for the stroke's end. A commit redraws the scene
 *    anyway, and a cancel issues one if a redraw has been waiting.
 *  - The library can replay what it had queued long after a stroke is over, and the engine reports eraser stamps late. Every
 *    stroke therefore has an id, and whatever is left of a finished stroke is dropped.
 *
 * Calls may come from any thread. The library takes only short locks of its own and calls back on its own thread, so calling it
 * with the lock held cannot deadlock, and doing so stops it being called after [close].
 */
internal class WetStrokeSequencer<T>(private val layers: FrontBufferLayers) {
    private class OpenStroke<T>(val id: Long) {
        val waiting = ArrayList<T>()
    }

    private val lock = Any()

    /** Strokes begun and not yet finished, oldest first. Only the oldest may paint. */
    private val openStrokes = ArrayDeque<OpenStroke<T>>()
    private var lastStrokeId = 0L
    private var redrawWanted = false

    /** A commit has been issued and its scene draw has not started yet, so that draw will show whatever changes now. */
    private var sceneDrawQueued = false
    private var closed = false

    val isClosed: Boolean get() = synchronized(lock) { closed }

    /** Starts a stroke and returns its id. */
    fun beginStroke(): Long =
        synchronized(lock) {
            val id = ++lastStrokeId
            if (!closed) openStrokes.addLast(OpenStroke(id))
            id
        }

    /** Adds something for a stroke to paint. It is dropped if that stroke has already finished. */
    fun add(strokeId: Long, command: T) {
        synchronized(lock) {
            val stroke = openStroke(strokeId) ?: return
            stroke.waiting += command
            if (stroke === openStrokes.first()) layers.requestFrontPaint()
        }
    }

    /**
     * Finishes a stroke the engine has merged. Must be told after everything for this stroke has gone through [add]. A stroke held back
     * by this one can paint from now on, and asks to when this commit's scene draw has finished.
     */
    fun commitStroke(strokeId: Long) {
        synchronized(lock) {
            val stroke = openStroke(strokeId) ?: return
            // The commit goes first. If the next stroke could paint before it, the commit would hide that paint.
            layers.commit()
            sceneDrawQueued = true
            redrawWanted = false
            openStrokes.remove(stroke)
        }
    }

    /** Abandons a stroke. It keeps the front layer from showing it, and honours any redraw it held up. */
    fun cancelStroke(strokeId: Long) {
        synchronized(lock) {
            val stroke = openStroke(strokeId) ?: return
            // If an older stroke is still waiting for its commit, this one never got as far as the front layer, which is
            // still showing that older stroke and has to go on doing so. That stroke's commit then also covers the redraw.
            val mayHavePainted = stroke === openStrokes.first()
            openStrokes.remove(stroke)
            if (mayHavePainted) layers.cancel()
            if (openStrokes.isEmpty() && redrawWanted) redraw()
        }
    }

    /** Asks for the scene to be drawn again, now if nothing is being drawn on the front layer, otherwise when that stops. */
    fun requestRedraw() {
        synchronized(lock) {
            if (closed) return
            if (openStrokes.isEmpty()) redraw() else redrawWanted = true
        }
    }

    /** What is ready to paint now, in order. Call on the render thread each time the library asks for the front layer. */
    fun takeCommands(): List<T> =
        synchronized(lock) {
            val waiting = openStrokes.firstOrNull()?.waiting ?: return emptyList()
            ArrayList(waiting).also { waiting.clear() }
        }

    /** Call on the render thread when a scene draw starts. From here on it may be too late for that draw to show a change. */
    fun onSceneDrawStarted() {
        synchronized(lock) { sceneDrawQueued = false }
    }

    /** Call on the render thread when a scene draw has finished. Its commit may have swallowed requests to paint. */
    fun onSceneDrawFinished() {
        synchronized(lock) {
            if (openStrokes.firstOrNull()?.waiting?.isNotEmpty() == true) layers.requestFrontPaint()
        }
    }

    /** Stops everything. Nothing reaches the library after this returns, so it can then be released. */
    fun close() {
        synchronized(lock) {
            closed = true
            openStrokes.clear()
        }
    }

    private fun openStroke(id: Long): OpenStroke<T>? = openStrokes.firstOrNull { it.id == id }

    private fun redraw() {
        redrawWanted = false
        if (sceneDrawQueued) return
        sceneDrawQueued = true
        layers.commit()
    }
}
