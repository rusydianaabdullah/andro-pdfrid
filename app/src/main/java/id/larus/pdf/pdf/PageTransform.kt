package id.larus.pdf.pdf

/**
 * The geometry of rendering a rotated page into a bitmap, kept free of Android types so it can
 * be unit-tested. [PdfDocumentHandle] feeds these numbers into an android.graphics.Matrix.
 *
 * The contract: after rotating about the origin, translating by ([translateX], [translateY])
 * and scaling by [scale], the page's four corners must land exactly on the bitmap rectangle
 * ([bitmapWidth] x [bitmapHeight]).
 */
/** A rectangle expressed as fractions of the page box, so it scales with any zoom level. */
data class FractionRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class PageTransform(
    val quarterTurns: Int,
    val translateX: Float,
    val translateY: Float,
    val scale: Float,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
) {
    val isQuarterTurned: Boolean get() = quarterTurns % 2 == 1

    /**
     * Maps a rectangle in page space to fractions of the displayed page box (0..1 on each
     * axis). Search highlights and selection boxes come back from the PDF in page coordinates,
     * and the overlay has to survive zooming and rotation, so fractions are the useful form.
     */
    fun mapRectToFraction(left: Float, top: Float, right: Float, bottom: Float): FractionRect {
        val corners = listOf(
            apply(left, top), apply(right, top), apply(right, bottom), apply(left, bottom)
        )
        val xs = corners.map { it.first }
        val ys = corners.map { it.second }
        return FractionRect(
            left = xs.min() / bitmapWidth,
            top = ys.min() / bitmapHeight,
            right = xs.max() / bitmapWidth,
            bottom = ys.max() / bitmapHeight,
        )
    }

    /** Inverse of [mapRectToFraction] for a single point: display fractions back to page space. */
    fun fractionToPage(fractionX: Float, fractionY: Float): Pair<Float, Float> {
        val bitmapX = fractionX * bitmapWidth
        val bitmapY = fractionY * bitmapHeight
        val x = bitmapX / scale - translateX
        val y = bitmapY / scale - translateY
        return when (quarterTurns) {
            1 -> y to -x
            2 -> -x to -y
            3 -> -y to x
            else -> x to y
        }
    }

    /** Applies the transform to a point in page space, for tests and for reasoning about it. */
    fun apply(x: Float, y: Float): Pair<Float, Float> {
        val (rx, ry) = when (quarterTurns) {
            1 -> -y to x
            2 -> -x to -y
            3 -> y to -x
            else -> x to y
        }
        return (rx + translateX) * scale to (ry + translateY) * scale
    }

    companion object {
        fun of(
            pageWidth: Int,
            pageHeight: Int,
            targetWidth: Int,
            rotationDegrees: Int,
        ): PageTransform {
            val quarterTurns = ((rotationDegrees / 90) % 4 + 4) % 4
            val swapped = quarterTurns % 2 == 1
            val rotatedWidth = if (swapped) pageHeight else pageWidth
            val rotatedHeight = if (swapped) pageWidth else pageHeight

            val width = targetWidth.coerceAtLeast(1)
            val height = (width.toLong() * rotatedHeight / rotatedWidth).toInt().coerceAtLeast(1)

            // Rotation about the origin pushes the page into negative space; translate it back.
            val (translateX, translateY) = when (quarterTurns) {
                1 -> pageHeight.toFloat() to 0f
                2 -> pageWidth.toFloat() to pageHeight.toFloat()
                3 -> 0f to pageWidth.toFloat()
                else -> 0f to 0f
            }

            return PageTransform(
                quarterTurns = quarterTurns,
                translateX = translateX,
                translateY = translateY,
                scale = width.toFloat() / rotatedWidth,
                bitmapWidth = width,
                bitmapHeight = height,
            )
        }
    }
}
