package org.kineograph.feature.gallery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kineograph.model.MAX_FPS
import org.kineograph.model.MIN_FPS
import org.kineograph.ui.rememberReduceMotion
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** A scrolling wheel of frame rates that snaps to a value. TalkBack users can set it directly. */
@Composable
public fun FpsWheel(fps: Int, onFpsChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    // A number has to fit in its slot at the largest font size too.
    val itemHeight = with(LocalDensity.current) {
        maxOf(
            40.dp,
            MaterialTheme.typography.titleLarge.lineHeight
                .toDp(),
        )
    }
    val values = remember { (MIN_FPS..MAX_FPS).toList() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (fps - MIN_FPS).coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    val fling = rememberSnapFlingBehavior(listState)

    val centered by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val middle = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - middle) }?.let { values[it.index] }
        }
    }
    val latestFps by rememberUpdatedState(fps)
    val latestChange by rememberUpdatedState(onFpsChange)
    LaunchedEffect(listState) {
        snapshotFlow { centered }.collect { value -> if (value != null && value != latestFps) latestChange(value) }
    }
    LaunchedEffect(fps) {
        if (centered != fps && !listState.isScrollInProgress) listState.scrollToItem(fps - MIN_FPS)
    }

    val label = stringResource(R.string.new_project_fps)
    val unit = stringResource(R.string.fps_value, fps)
    Box(
        modifier
            .width(96.dp)
            .height(itemHeight * 3)
            .testTag("fps_wheel")
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = unit
                progressBarRangeInfo = ProgressBarRangeInfo(fps.toFloat(), MIN_FPS.toFloat()..MAX_FPS.toFloat(), MAX_FPS - MIN_FPS - 1)
                setProgress { target ->
                    val value = target.roundToInt().coerceIn(MIN_FPS, MAX_FPS)
                    onFpsChange(value)
                    scope.launch { listState.scrollToItem(value - MIN_FPS) }
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        LazyColumn(
            state = listState,
            flingBehavior = fling,
            contentPadding = PaddingValues(vertical = itemHeight),
            modifier = Modifier.fillMaxWidth().height(itemHeight * 3),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(values, key = { it }) { value ->
                val isCurrent = value == fps
                Box(Modifier.fillMaxWidth().height(itemHeight), contentAlignment = Alignment.Center) {
                    Text(
                        text = value.toString(),
                        style = if (isCurrent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        HorizontalDivider(Modifier.align(Alignment.Center).offset(y = -itemHeight / 2), color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.align(Alignment.Center).offset(y = itemHeight / 2), color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * A preview of the project about to be made: a ball bouncing, at the chosen frame rate, on the chosen canvas shape and
 * background, so each choice can be judged by eye. The ball is 12 drawings: squash when it lands, stretch when it
 * leaves. It stands still if the user has turned off animations.
 */
@Composable
internal fun ProjectPreview(
    fps: Int,
    canvasWidth: Int,
    canvasHeight: Int,
    background: BackgroundChoice,
    image: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    var step by remember { mutableIntStateOf(if (reduceMotion) IMPACT_STEP else 0) }
    if (!reduceMotion) {
        LaunchedEffect(fps) {
            val frameTime = 1000L / fps.coerceIn(MIN_FPS, MAX_FPS)
            while (true) {
                delay(frameTime)
                step = (step + 1) % BOUNCE.size
            }
        }
    }
    val outline = MaterialTheme.colorScheme.outline
    val description = stringResource(R.string.fps_preview_description, fps, canvasWidth, canvasHeight)
    Canvas(modifier.semantics { contentDescription = description }.testTag("project_preview")) {
        val scale = min(size.width / canvasWidth.coerceAtLeast(1), size.height / canvasHeight.coerceAtLeast(1))
        val shape = Size(canvasWidth * scale, canvasHeight * scale)
        val topLeft = Offset((size.width - shape.width) / 2f, (size.height - shape.height) / 2f)
        clipRect(topLeft.x, topLeft.y, topLeft.x + shape.width, topLeft.y + shape.height) {
            drawBackground(background, image, topLeft, shape)
            val pose = BOUNCE[step]
            val radius = min(shape.width, shape.height) * BALL_FRACTION
            val floorY = topLeft.y + shape.height * FLOOR_FRACTION
            val travel = (floorY - topLeft.y - radius * BALL_HEADROOM).coerceAtLeast(0f)
            val halfWidth = radius * pose[1]
            val halfHeight = radius * pose[2]
            val bottom = floorY - pose[0] * travel
            drawOval(
                BALL_COLOR,
                Offset(topLeft.x + shape.width / 2f - halfWidth, bottom - halfHeight * 2f),
                Size(
                    halfWidth * 2f,
                    halfHeight * 2f,
                ),
            )
        }
        drawRect(outline, topLeft, shape, style = Stroke(1.dp.toPx()))
    }
}

/** Twelve drawings of one bounce: height above the floor, horizontal stretch, vertical stretch. */
private val BOUNCE =
    arrayOf(
        floatArrayOf(1.00f, 1.00f, 1.00f),
        floatArrayOf(0.94f, 1.00f, 1.00f),
        floatArrayOf(0.76f, 0.96f, 1.06f),
        floatArrayOf(0.46f, 0.90f, 1.16f),
        floatArrayOf(0.08f, 0.84f, 1.26f),
        floatArrayOf(0.00f, 1.34f, 0.62f),
        floatArrayOf(0.08f, 0.86f, 1.22f),
        floatArrayOf(0.46f, 0.90f, 1.16f),
        floatArrayOf(0.76f, 0.96f, 1.06f),
        floatArrayOf(0.94f, 1.00f, 1.00f),
        floatArrayOf(1.00f, 1.00f, 1.00f),
        floatArrayOf(0.98f, 1.00f, 1.00f),
    )
private const val IMPACT_STEP = 5
private val BALL_COLOR = Color(0xFFE4572E)
private const val BALL_FRACTION = 0.1f
private const val FLOOR_FRACTION = 0.92f
private const val BALL_HEADROOM = 2.4f
