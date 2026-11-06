package org.kineograph.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.kineograph.model.Background
import org.kineograph.model.Cel
import org.kineograph.model.CelKey
import org.kineograph.model.FrameSlot
import org.kineograph.model.IntRect
import org.kineograph.model.Layer
import org.kineograph.model.PaperKind
import org.kineograph.model.Project
import org.kineograph.model.newId

/**
 * Builds the built-in sample: a ball bouncing for 12 frames at 12 FPS. Every drawing is generated here, so
 * no outside artwork is involved. It teaches the classic lesson: slow at the top and fast near
 * the floor, squashed on impact and stretched on the way back up.
 */
public object SampleProject {
    private const val WIDTH = 1280
    private const val HEIGHT = 720
    private const val FPS = 12
    private const val BALL_RADIUS = 56f
    private const val FLOOR_Y = 640f
    private const val BALL_X_START = 240f
    private const val BALL_X_END = 1040f
    private const val BALL_COLOR = 0xFFE4572E.toInt()
    private const val SHADOW_COLOR = 0x33000000

    /** For each frame: height above the floor as a fraction, then horizontal and vertical stretch. */
    private val POSES =
        arrayOf(
            floatArrayOf(1.00f, 1.00f, 1.00f),
            floatArrayOf(0.94f, 1.00f, 1.00f),
            floatArrayOf(0.76f, 0.96f, 1.06f),
            floatArrayOf(0.46f, 0.90f, 1.16f),
            floatArrayOf(0.06f, 0.84f, 1.26f),
            floatArrayOf(0.00f, 1.34f, 0.62f),
            floatArrayOf(0.08f, 0.86f, 1.22f),
            floatArrayOf(0.46f, 0.90f, 1.16f),
            floatArrayOf(0.76f, 0.96f, 1.06f),
            floatArrayOf(0.94f, 1.00f, 1.00f),
            floatArrayOf(1.00f, 1.00f, 1.00f),
            floatArrayOf(0.98f, 1.00f, 1.00f),
        )

    /** Writes the sample's images through [repository] and saves it. [name] and [layerName] come from the app's translations. */
    public suspend fun create(repository: ProjectRepository, name: String, layerName: String, now: Long): String {
        val projectId = newId()
        val store = repository.storeFor(projectId)
        val layerId = newId()
        val frames = ArrayList<FrameSlot>(POSES.size)
        val cels = HashMap<CelKey, Cel>()
        POSES.forEachIndexed { index, pose ->
            val frameId = newId()
            frames += FrameSlot(frameId)
            val travel = index.toFloat() / (POSES.size - 1)
            val (image, bounds) = drawBall(BALL_X_START + (BALL_X_END - BALL_X_START) * travel, pose[0], pose[1], pose[2])
            cels[CelKey(layerId, frameId)] = Cel(layerId, frameId, store.write(image), bounds)
        }
        val project =
            Project(
                id = projectId,
                name = name,
                createdAt = now,
                updatedAt = now,
                width = WIDTH,
                height = HEIGHT,
                fps = FPS,
                background = Background.Paper(PaperKind.OFF_WHITE),
                layers = listOf(Layer(layerId, layerName)),
                frames = frames,
                cels = cels,
            )
        repository.saveNew(project, store)
        return projectId
    }

    private fun drawBall(centerX: Float, heightFraction: Float, stretchX: Float, stretchY: Float): Pair<Bitmap, IntRect> {
        val halfWidth = BALL_RADIUS * stretchX
        val halfHeight = BALL_RADIUS * stretchY
        val bottom = FLOOR_Y - heightFraction * (FLOOR_Y - BALL_RADIUS * 3f)
        val top = bottom - halfHeight * 2f
        val shadowWidth = BALL_RADIUS * (1.1f - heightFraction * 0.5f)

        val left = (centerX - maxOf(halfWidth, shadowWidth)).toInt() - 2
        val right = (centerX + maxOf(halfWidth, shadowWidth)).toInt() + 3
        val boundsTop = top.toInt() - 2
        val boundsBottom = (FLOOR_Y + SHADOW_HEIGHT).toInt() + 3
        val bounds = IntRect(left, boundsTop, right, boundsBottom)

        val image = Bitmap.createBitmap(bounds.width, bounds.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.translate(-left.toFloat(), -boundsTop.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = SHADOW_COLOR
        canvas.drawOval(RectF(centerX - shadowWidth, FLOOR_Y - SHADOW_HEIGHT, centerX + shadowWidth, FLOOR_Y + SHADOW_HEIGHT), paint)
        paint.color = BALL_COLOR
        canvas.drawOval(RectF(centerX - halfWidth, top, centerX + halfWidth, bottom), paint)
        return image to bounds
    }

    private const val SHADOW_HEIGHT = 10f
}
