package id.larus.pdf.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import id.larus.pdf.annot.AnnotationTool
import id.larus.pdf.pdf.FractionRect
import kotlin.math.hypot

/**
 * Inverts luminance while leaving the image otherwise intact — the same trick Preview's dark
 * background uses, so black text on white becomes white on near-black.
 */
private val InvertMatrix = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )
)

private val SearchHighlight = Color(0x66FFC400)
private val SearchHighlightFocused = Color(0x99FF6D00)
private val SelectionHighlight = Color(0x552F6FED)
private val SelectionGrip = Color(0xFF2F6FED)

/** Drawn radius of a grip, and the slack around it that still counts as grabbing one. */
private val HandleRadius = 7.dp
private val HandleTouchRadius = 26.dp

private val NoteRadius = 11.dp
private val NoteTouchRadius = 28.dp

/** Underline and strikethrough weight, relative to the height of the text they cross. */
private const val RuleWeight = 0.07f
private val PagePlaceholderLight = Color(0xFFF2F2F5)
private val PagePlaceholderDark = Color(0xFF1A1A1D)

/**
 * One page: the rendered bitmap, plus any search or selection boxes drawn over it. Overlay
 * rectangles arrive as fractions of the page box, so they stay correct at any zoom or rotation.
 */
@Composable
fun PdfPage(
    index: Int,
    widthPx: Int,
    aspectRatio: Float,
    rotation: Int,
    nightMode: Boolean,
    render: suspend (Int, Int) -> Bitmap?,
    modifier: Modifier = Modifier,
    highlights: List<Pair<FractionRect, Boolean>> = emptyList(),
    selectionBoxes: List<FractionRect> = emptyList(),
    selectionHandles: List<SelectionHandle> = emptyList(),
    onSelectionStart: ((Float, Float) -> Unit)? = null,
    onSelectionDrag: ((Float, Float) -> Unit)? = null,
    onSelectionEnd: (() -> Unit)? = null,
    onHandleDrag: ((Boolean, Float, Float) -> Unit)? = null,
    overlay: PageOverlay = PageOverlay.Empty,
    inkColor: Int = 0,
    brushActive: Boolean = false,
    onStroke: ((List<Pair<Float, Float>>) -> Unit)? = null,
    onPageTap: ((Float, Float) -> Unit)? = null,
    onNoteTap: ((String) -> Unit)? = null,
    onMarkerMove: ((String, Float, Float) -> Unit)? = null,
) {
    // The old bitmap stays on screen until the new one is ready. Resetting to null on every
    // width change is what made the page go blank the moment a pinch started: a zoom produces
    // a new width per frame, and each one threw the visible page away before its replacement
    // had been drawn.
    var bitmap by remember(index, rotation) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index, widthPx, rotation) {
        if (widthPx > 0) render(index, widthPx)?.let { bitmap = it }
    }
    val latestHandles = rememberUpdatedState(selectionHandles)
    val latestNotes = rememberUpdatedState(overlay.notes)
    // The gesture block below is keyed on width and rotation, so it survives a tool change.
    // These callbacks change with the tool — picking "add text" is what turns onPageTap from
    // null into something — so they have to be read live rather than captured once, or the
    // block goes on holding the callbacks from whenever it happened to last restart.
    val latestPageTap = rememberUpdatedState(onPageTap)
    val latestNoteTap = rememberUpdatedState(onNoteTap)
    val latestMarkerMove = rememberUpdatedState(onMarkerMove)
    val latestTexts = rememberUpdatedState(overlay.texts)
    val textMeasurer = rememberTextMeasurer()
    // The line currently under the finger, kept locally so drawing stays at touch speed and
    // only the finished stroke travels up to the view model.
    val wet = remember { mutableStateListOf<Pair<Float, Float>>() }

    Box(
        modifier
            .aspectRatio(aspectRatio.coerceIn(0.1f, 10f))
            .background(if (nightMode) PagePlaceholderDark else PagePlaceholderLight)
            // With the brush out it owns the page outright: no tapping, selecting or scrolling
            // while a line is being drawn, and every event is consumed so nothing below reacts.
            .then(
                if (!brushActive || onStroke == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(widthPx, rotation) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            down.consume()
                            wet.clear()
                            wet += (down.position.x / size.width) to
                                (down.position.y / size.height)
                            var event = awaitPointerEvent()
                            while (event.changes.any { it.pressed }) {
                                val change = event.changes.first()
                                change.consume()
                                wet += (change.position.x / size.width).coerceIn(0f, 1f) to
                                    (change.position.y / size.height).coerceIn(0f, 1f)
                                event = awaitPointerEvent()
                            }
                            onStroke(wet.toList())
                            wet.clear()
                        }
                    }
                }
            )
            .then(
                if (brushActive) {
                    Modifier
                } else {
                    Modifier.pointerInput(widthPx, rotation) {
                        val reach = NoteTouchRadius.toPx()
                        val slop = viewConfiguration.touchSlop

                        /** The note or text box under a point, if any. */
                        fun markerAt(offset: Offset): String? {
                            latestNotes.value.firstOrNull { note ->
                                hypot(
                                    note.x * size.width - offset.x,
                                    note.y * size.height - offset.y,
                                ) <= reach
                            }?.let { return it.id }
                            return latestTexts.value.firstOrNull { box ->
                                val left = box.x * size.width
                                val top = box.y * size.height
                                val height = box.size * size.height * 1.4f
                                offset.x >= left - reach && offset.y >= top - reach * 0.4f &&
                                    offset.y <= top + height + reach * 0.4f
                            }?.id
                        }

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            val marker = markerAt(down.position)

                            if (marker == null) {
                                // With no marker under the finger, this gesture is only ours
                                // if a tool is waiting to be placed. Otherwise let it fall
                                // through to scrolling and the chrome toggle.
                                val placing = latestPageTap.value ?: return@awaitEachGesture
                                down.consume()
                                var moved = false
                                var event = awaitPointerEvent()
                                while (event.changes.any { it.pressed }) {
                                    val change = event.changes.first()
                                    change.consume()
                                    if ((change.position - down.position).getDistance() > slop) {
                                        moved = true
                                    }
                                    event = awaitPointerEvent()
                                }
                                if (!moved) {
                                    placing(
                                        down.position.x / size.width,
                                        down.position.y / size.height,
                                    )
                                }
                                return@awaitEachGesture
                            }

                            // A marker is under the finger: tapping it opens it, dragging it
                            // carries it around the page.
                            //
                            // A text box hangs up and to the left of the finger rather than
                            // down and to the right, so the fingertip lands on its bottom-right
                            // corner and the words stay in sight while they are being moved.
                            val lift = latestTexts.value
                                .firstOrNull { it.id == marker }
                                ?.let { box ->
                                    val laid = textMeasurer.measure(
                                        text = box.text,
                                        style = TextStyle(
                                            fontSize = (box.size * size.height).toSp(),
                                        ),
                                    )
                                    Offset(laid.size.width.toFloat(), laid.size.height.toFloat())
                                }
                                ?: Offset.Zero

                            down.consume()
                            var dragged = false
                            var event = awaitPointerEvent()
                            while (event.changes.any { it.pressed }) {
                                val change = event.changes.first()
                                change.consume()
                                if ((change.position - down.position).getDistance() > slop) {
                                    dragged = true
                                }
                                if (dragged && latestMarkerMove.value != null) {
                                    // Clamped by the box's own extent, not by the page edge:
                                    // pinning a top-left corner to 1.0 would park the words
                                    // just past the bottom of the page, where they vanish.
                                    val maxX = (1f - lift.x / size.width).coerceAtLeast(0f)
                                    val maxY = (1f - lift.y / size.height).coerceAtLeast(0f)
                                    latestMarkerMove.value?.invoke(
                                        marker,
                                        ((change.position.x - lift.x) / size.width)
                                            .coerceIn(0f, maxX),
                                        ((change.position.y - lift.y) / size.height)
                                            .coerceIn(0f, maxY),
                                    )
                                }
                                event = awaitPointerEvent()
                            }
                            if (!dragged) latestNoteTap.value?.invoke(marker)
                        }
                    }
                }
            )
            // Grips are checked before the long-press handler, so grabbing one adjusts the
            // existing selection instead of starting a new one.
            .then(
                if (brushActive || onHandleDrag == null) {
                    Modifier
                } else {
                    // Deliberately not keyed on the handles: they move as the drag resolves,
                    // and re-keying would cancel the very gesture doing the dragging.
                    Modifier.pointerInput(widthPx, rotation) {
                        val radius = HandleTouchRadius.toPx()
                        val drop = HandleRadius.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            val grabbed = latestHandles.value.minByOrNull { handle ->
                                hypot(
                                    handle.x * size.width - down.position.x,
                                    handle.y * size.height + drop - down.position.y,
                                )
                            }?.takeIf { handle ->
                                hypot(
                                    handle.x * size.width - down.position.x,
                                    handle.y * size.height + drop - down.position.y,
                                ) <= radius
                            } ?: return@awaitEachGesture

                            down.consume()
                            // Keep the finger's distance from the line it grabbed, so moving
                            // sideways stays on that line and moving down reaches the next.
                            val anchorOffsetY =
                                grabbed.anchorY * size.height - down.position.y
                            var event = awaitPointerEvent()
                            while (event.changes.any { it.pressed }) {
                                val change = event.changes.first()
                                change.consume()
                                onHandleDrag(
                                    grabbed.isStart,
                                    (change.position.x / size.width).coerceIn(0f, 1f),
                                    ((change.position.y + anchorOffsetY) / size.height)
                                        .coerceIn(0f, 1f),
                                )
                                event = awaitPointerEvent()
                            }
                        }
                    }
                }
            )
            .then(
                if (brushActive || onSelectionStart == null) {
                    Modifier
                } else {
                    // Long-press starts a selection and dragging extends it; a plain drag is
                    // left alone so the page still scrolls.
                    Modifier.pointerInput(index, widthPx, rotation) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                onSelectionStart(
                                    offset.x / size.width.toFloat(),
                                    offset.y / size.height.toFloat(),
                                )
                            },
                            onDragEnd = { onSelectionEnd?.invoke() },
                            onDrag = { change, _ ->
                                change.consume()
                                onSelectionDrag?.invoke(
                                    change.position.x / size.width.toFloat(),
                                    change.position.y / size.height.toFloat(),
                                )
                            },
                        )
                    }
                }
            )
    ) {
        bitmap?.let { rendered ->
            Image(
                bitmap = rendered.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                colorFilter = if (nightMode) ColorFilter.colorMatrix(InvertMatrix) else null,
            )
        }

        if (!overlay.isEmpty || wet.isNotEmpty()) {
            Canvas(Modifier.matchParentSize()) {
                overlay.marks.forEach { mark -> drawMark(mark) }
                overlay.strokes.forEach { stroke ->
                    drawInk(stroke.points, Color(stroke.color), stroke.width * size.width)
                }
                if (wet.isNotEmpty()) {
                    drawInk(wet, Color(inkColor), BrushWidthFraction * size.width)
                }
                overlay.texts.forEach { box ->
                    val laid = textMeasurer.measure(
                        text = box.text,
                        style = TextStyle(
                            color = Color(box.color),
                            fontSize = (box.size * size.height).toSp(),
                        ),
                    )
                    drawText(
                        textLayoutResult = laid,
                        topLeft = Offset(box.x * size.width, box.y * size.height),
                    )
                }
                overlay.notes.forEach { note ->
                    val centre = Offset(note.x * size.width, note.y * size.height)
                    val radius = NoteRadius.toPx()
                    drawCircle(Color.White, radius, centre)
                    drawCircle(Color(note.color), radius, centre, style = Stroke(2.dp.toPx()))
                    drawCircle(Color(note.color).copy(alpha = 0.35f), radius * 0.55f, centre)
                }
            }
        }

        if (highlights.isNotEmpty() || selectionBoxes.isNotEmpty()) {
            Canvas(Modifier.matchParentSize()) {
                selectionBoxes.forEach { rect -> drawFraction(rect, SelectionHighlight) }
                highlights.forEach { (rect, focused) ->
                    drawFraction(rect, if (focused) SearchHighlightFocused else SearchHighlight)
                }
                val radius = HandleRadius.toPx()
                selectionHandles.forEach { handle ->
                    val centre = Offset(
                        handle.x * size.width,
                        handle.y * size.height + radius,
                    )
                    // A stem to the text, so the grip reads as attached to where it ends.
                    drawLine(
                        color = SelectionGrip,
                        start = Offset(centre.x, centre.y - radius),
                        end = centre,
                        strokeWidth = 2.dp.toPx(),
                    )
                    drawCircle(SelectionGrip, radius, centre)
                }
            }
        }
    }
}

/**
 * One text mark. A highlight washes over the words; an underline and a strikethrough are the
 * same rule drawn at different heights, which is all that separates them on the page.
 */
private fun DrawScope.drawMark(mark: MarkOverlay) {
    val left = mark.rect.left * size.width
    val right = mark.rect.right * size.width
    val top = mark.rect.top * size.height
    val bottom = mark.rect.bottom * size.height
    val color = Color(mark.color)
    when (mark.tool) {
        AnnotationTool.Highlight -> drawRect(
            color = color.copy(alpha = 0.35f),
            topLeft = Offset(left, top),
            size = Size((right - left).coerceAtLeast(1f), (bottom - top).coerceAtLeast(1f)),
        )
        AnnotationTool.Underline -> drawLine(
            color = color,
            start = Offset(left, bottom),
            end = Offset(right, bottom),
            strokeWidth = (bottom - top) * RuleWeight,
        )
        AnnotationTool.Strikethrough -> drawLine(
            color = color,
            start = Offset(left, (top + bottom) / 2f),
            end = Offset(right, (top + bottom) / 2f),
            strokeWidth = (bottom - top) * RuleWeight,
        )
        else -> Unit
    }
}

/** A freehand line, as a smooth path rather than a chain of separate segments. */
private fun DrawScope.drawInk(
    points: List<Pair<Float, Float>>,
    color: Color,
    strokeWidth: Float,
) {
    if (points.isEmpty()) return
    val path = Path()
    points.forEachIndexed { index, (fx, fy) ->
        val x = fx * size.width
        val y = fy * size.height
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = strokeWidth.coerceAtLeast(1f),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFraction(
    rect: FractionRect,
    color: Color,
) {
    val left = rect.left * size.width
    val top = rect.top * size.height
    val right = rect.right * size.width
    val bottom = rect.bottom * size.height
    drawRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size((right - left).coerceAtLeast(1f), (bottom - top).coerceAtLeast(1f)),
    )
}
