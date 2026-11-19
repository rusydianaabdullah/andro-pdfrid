package id.larus.pdf.annot

/**
 * What the pen is currently doing. [None] is plain reading: gestures scroll and select text.
 */
enum class AnnotationTool {
    None,
    Highlight,
    Underline,
    Strikethrough,
    Brush,
    Note,
    Text,
}

/** The tools that turn a text selection into a mark, as opposed to drawing freehand. */
val AnnotationTool.isTextMarkup: Boolean
    get() = this == AnnotationTool.Highlight ||
        this == AnnotationTool.Underline ||
        this == AnnotationTool.Strikethrough

/**
 * A point on a page, as a fraction of the page's own width and height.
 *
 * Everything an annotation stores is in these fractions rather than pixels or PDF points: the
 * same mark then lands correctly at any zoom, on any screen, and after the page is rotated.
 */
data class Spot(val x: Float, val y: Float)

/** A rectangle in the same page-fraction space as [Spot]. */
data class Patch(val left: Float, val top: Float, val right: Float, val bottom: Float)

sealed interface Annotation {
    val id: String
    val page: Int
    val color: Int
    /** Where the annotation list and "jump to" should scroll — its topmost point. */
    val anchor: Spot
}

/** A highlight, underline or strikethrough, following the boxes of the text it marks. */
data class Markup(
    override val id: String,
    override val page: Int,
    override val color: Int,
    val tool: AnnotationTool,
    val patches: List<Patch>,
    val text: String,
) : Annotation {
    override val anchor: Spot
        get() = patches.firstOrNull()?.let { Spot(it.left, it.top) } ?: Spot(0f, 0f)
}

/** One freehand line, sampled as the finger moves. [width] is a fraction of the page width. */
data class Stroke(
    override val id: String,
    override val page: Int,
    override val color: Int,
    val width: Float,
    val points: List<Spot>,
) : Annotation {
    override val anchor: Spot
        get() = points.minByOrNull { it.y } ?: Spot(0f, 0f)
}

/**
 * Words written straight onto the page, as opposed to a [Note], which hides behind a pin.
 * [size] is the cap height as a fraction of the page height, so the text scales with the page.
 */
data class TextBox(
    override val id: String,
    override val page: Int,
    override val color: Int,
    val at: Spot,
    val text: String,
    val size: Float,
) : Annotation {
    override val anchor: Spot get() = at
}

/** A pinned note: a marker on the page with text behind it. */
data class Note(
    override val id: String,
    override val page: Int,
    override val color: Int,
    val at: Spot,
    val text: String,
) : Annotation {
    override val anchor: Spot get() = at
}
