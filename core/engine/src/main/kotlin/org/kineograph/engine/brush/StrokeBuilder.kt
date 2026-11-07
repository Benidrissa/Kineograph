package org.kineograph.engine.brush

/** How one stroke should treat its input. */
public data class StrokeOptions(
    /** Stabilizer strength, 0..100. */
    val smoothing: Int = 0,
    /** Longest stabilizer string in canvas pixels. Pass a value scaled by the zoom so the feel stays constant. */
    val stringLengthPx: Float = 24f,
    /** Pressure smoothing strength, 0..100. */
    val pressureSmoothing: Int = 0,
    /** Fake pressure from speed. Only meaningful for fingers. */
    val simulatePressure: Boolean = false,
    /** The speed at which simulated pressure reaches its lightest, in screen pixels per millisecond. */
    val fastSpeedPxPerMs: Float = 4f,
)

/**
 * The whole stroke pipeline for one stroke: pressure processing, then stabilizing, then stamp
 * placement. Results accumulate in [stamps] and [predictedStamps]; the caller drains them.
 *
 * Runs on the input thread. It does no drawing and creates no per-point objects.
 */
public class StrokeBuilder(
    spec: BrushSpec,
    private val options: StrokeOptions,
    seed: Long,
    /** Keeps what the engine is fed, for the vector data saved beside the pixels. Null records nothing. */
    public val recording: StrokeRecording? = null,
) {
    private val engine = StrokeEngine(spec, seed)
    private val stabilizer = StrokeStabilizer(options.smoothing, options.stringLengthPx)
    private val smoother = PressureSmoother(options.pressureSmoothing)
    private val finger = FingerPressure(options.fastSpeedPxPerMs)
    private var lastPressure = 0f
    private var lastTilt = 0f
    private var lastOrientation = 0f
    private var started = false
    private var predictedScratch = FloatArray(StrokeEngine.PREDICTED_STRIDE * 8)
    private var predictedCount = 0

    /** Final stamps not yet drained. */
    public val stamps: StampBuffer = StampBuffer(128)

    /** Throw-away stamps for the part of the stroke that is not final. Replaced on every prediction. */
    public val predictedStamps: StampBuffer = StampBuffer(64)

    /** Starts the stroke. [screenX] and [screenY] are only used to measure speed for simulated pressure. */
    public fun begin(x: Float, y: Float, screenX: Float, screenY: Float, pressure: Float, tilt: Float, orientation: Float, timeMs: Long) {
        check(!started) { "Stroke already started" }
        started = true
        finger.reset(screenX, screenY, timeMs)
        smoother.reset()
        val p = smoother.next(if (options.simulatePressure) finger.start() else pressure)
        lastPressure = p
        lastTilt = tilt
        lastOrientation = orientation
        stabilizer.reset(x, y)
        engine.begin(x, y, p, tilt, orientation, stamps)
        recording?.add(x, y, p, tilt, orientation, timeMs)
    }

    /** Adds one real input sample. */
    public fun add(x: Float, y: Float, screenX: Float, screenY: Float, pressure: Float, tilt: Float, orientation: Float, timeMs: Long) {
        val simulated = finger.next(screenX, screenY, timeMs)
        val p = smoother.next(if (options.simulatePressure) simulated else pressure)
        lastPressure = p
        lastTilt = tilt
        lastOrientation = orientation
        stabilizer.push(x, y)
        engine.add(stabilizer.x, stabilizer.y, p, tilt, orientation, stamps)
        recording?.add(stabilizer.x, stabilizer.y, p, tilt, orientation, timeMs)
    }

    /** Clears the list of predicted points, ready for [addPredicted]. */
    public fun clearPrediction() {
        predictedCount = 0
    }

    /** Adds one predicted point. It only affects [predictedStamps], never the final stroke. */
    public fun addPredicted(x: Float, y: Float) {
        val needed = (predictedCount + 1) * StrokeEngine.PREDICTED_STRIDE
        if (needed > predictedScratch.size) predictedScratch = predictedScratch.copyOf(needed * 2)
        val offset = predictedCount * StrokeEngine.PREDICTED_STRIDE
        // A stabilized stroke does not follow the input exactly, so predictions are only used without one.
        predictedScratch[offset] = x
        predictedScratch[offset + 1] = y
        predictedScratch[offset + 2] = lastPressure
        predictedScratch[offset + 3] = lastTilt
        predictedScratch[offset + 4] = lastOrientation
        predictedCount++
    }

    /** Rebuilds [predictedStamps] from the unfinished tail of the stroke and the predicted points. */
    public fun buildPrediction() {
        predictedStamps.clear()
        engine.predict(predictedScratch, if (options.smoothing == 0) predictedCount else 0, predictedStamps)
    }

    /** Ends the stroke, catching up to where the pen actually lifted. */
    public fun finish() {
        if (options.smoothing > 0) {
            // Bring the stabilized end point to the real one, so strokes reach where they were aimed.
            engine.add(stabilizer.lastInputX, stabilizer.lastInputY, lastPressure, lastTilt, lastOrientation, stamps)
            recording?.add(stabilizer.lastInputX, stabilizer.lastInputY, lastPressure, lastTilt, lastOrientation, recording.lastTimeMs)
        }
        engine.finish(stamps)
        predictedStamps.clear()
    }
}
