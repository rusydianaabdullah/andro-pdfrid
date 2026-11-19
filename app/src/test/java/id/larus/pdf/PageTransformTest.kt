package id.larus.pdf

import id.larus.pdf.pdf.PageTransform
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * The transform must map the page's four corners exactly onto the bitmap rectangle. Getting
 * this wrong renders a page half off the canvas, which is invisible in a compile check.
 */
class PageTransformTest {

    private val pageWidth = 612   // US Letter, portrait
    private val pageHeight = 792

    private fun assertCornersFillBitmap(rotationDegrees: Int, targetWidth: Int = 1000) {
        val transform = PageTransform.of(pageWidth, pageHeight, targetWidth, rotationDegrees)
        val corners = listOf(
            0f to 0f,
            pageWidth.toFloat() to 0f,
            pageWidth.toFloat() to pageHeight.toFloat(),
            0f to pageHeight.toFloat(),
        ).map { (x, y) -> transform.apply(x, y) }

        val xs = corners.map { it.first }
        val ys = corners.map { it.second }

        assertClose("min x at $rotationDegrees°", 0f, xs.min())
        assertClose("min y at $rotationDegrees°", 0f, ys.min())
        assertClose("max x at $rotationDegrees°", transform.bitmapWidth.toFloat(), xs.max())
        assertClose("max y at $rotationDegrees°", transform.bitmapHeight.toFloat(), ys.max())
    }

    private fun assertClose(message: String, expected: Float, actual: Float) {
        if (abs(expected - actual) > 1.0f) {
            throw AssertionError("$message: expected $expected but was $actual")
        }
    }

    @Test
    fun `every rotation maps the page onto the whole bitmap`() {
        listOf(0, 90, 180, 270).forEach(::assertCornersFillBitmap)
    }

    @Test
    fun `negative and wrapped rotations normalise`() {
        assertCornersFillBitmap(-90)
        assertCornersFillBitmap(450)
        assertEquals(
            PageTransform.of(pageWidth, pageHeight, 1000, 90),
            PageTransform.of(pageWidth, pageHeight, 1000, 450),
        )
        assertEquals(
            PageTransform.of(pageWidth, pageHeight, 1000, 270),
            PageTransform.of(pageWidth, pageHeight, 1000, -90),
        )
    }

    @Test
    fun `quarter turns swap the bitmap aspect ratio`() {
        val upright = PageTransform.of(pageWidth, pageHeight, 1000, 0)
        val turned = PageTransform.of(pageWidth, pageHeight, 1000, 90)
        assertEquals(1000, upright.bitmapWidth)
        assertEquals(1294, upright.bitmapHeight)   // 1000 * 792 / 612
        assertEquals(1000, turned.bitmapWidth)
        assertEquals(772, turned.bitmapHeight)     // 1000 * 612 / 792
    }

    @Test
    fun `landscape pages rotate just as cleanly`() {
        val landscape = PageTransform.of(792, 612, 800, 90)
        assertEquals(800, landscape.bitmapWidth)
        assertEquals(1035, landscape.bitmapHeight)
    }

    @Test
    fun `a degenerate target width still produces a drawable bitmap`() {
        val transform = PageTransform.of(pageWidth, pageHeight, 0, 0)
        assertEquals(1, transform.bitmapWidth)
        assert(transform.bitmapHeight >= 1)
    }
}
