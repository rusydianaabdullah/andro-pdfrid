package id.larus.pdf.ui

import id.larus.pdf.annot.AnnotationTool
import id.larus.pdf.pdf.FractionRect

/**
 * Annotations for one page, already mapped into display fractions of the page box on screen.
 *
 * The stored form is relative to the PDF page and knows nothing about rotation or zoom; this
 * is the drawable form, recomputed whenever either changes.
 */
data class PageOverlay(
    val marks: List<MarkOverlay> = emptyList(),
    val strokes: List<StrokeOverlay> = emptyList(),
    val notes: List<NoteOverlay> = emptyList(),
    val texts: List<TextOverlay> = emptyList(),
) {
    val isEmpty: Boolean
        get() = marks.isEmpty() && strokes.isEmpty() && notes.isEmpty() && texts.isEmpty()

    companion object {
        val Empty = PageOverlay()
    }
}

data class MarkOverlay(val rect: FractionRect, val tool: AnnotationTool, val color: Int)

data class StrokeOverlay(
    val points: List<Pair<Float, Float>>,
    val color: Int,
    /** Stroke width as a fraction of the page box width. */
    val width: Float,
)

data class NoteOverlay(val id: String, val x: Float, val y: Float, val color: Int)

data class TextOverlay(
    val id: String,
    val x: Float,
    val y: Float,
    val color: Int,
    val text: String,
    /** Font size as a fraction of the page box height. */
    val size: Float,
)

/** Brush thickness as a fraction of the page width, so a line scales with the page. */
const val BrushWidthFraction = 0.005f
