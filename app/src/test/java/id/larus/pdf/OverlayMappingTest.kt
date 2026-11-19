package id.larus.pdf

import id.larus.pdf.pdf.PageTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Search highlights and selection boxes come back in PDF page coordinates and have to land on
 * the right pixels at any zoom and rotation. A mistake here draws a box over the wrong words,
 * which no compiler or emulator-free smoke test would catch.
 */
class OverlayMappingTest {

    private val pageWidth = 612
    private val pageHeight = 792

    private fun transform(rotation: Int, targetWidth: Int = 1000) =
        PageTransform.of(pageWidth, pageHeight, targetWidth, rotation)

    private fun assertClose(message: String, expected: Float, actual: Float, tolerance: Float = 0.002f) {
        if (abs(expected - actual) > tolerance) {
            throw AssertionError("$message: expected $expected but was $actual")
        }
    }

    @Test
    fun `the whole page maps to the whole box at every rotation`() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val rect = transform(rotation).mapRectToFraction(
                0f, 0f, pageWidth.toFloat(), pageHeight.toFloat()
            )
            assertClose("left at $rotation", 0f, rect.left)
            assertClose("top at $rotation", 0f, rect.top)
            assertClose("right at $rotation", 1f, rect.right)
            assertClose("bottom at $rotation", 1f, rect.bottom)
        }
    }

    @Test
    fun `a box in the top left of the page moves as the page turns`() {
        // A quarter of the way across, a tenth of the way down: a heading, say.
        val left = 0f
        val top = 0f
        val right = pageWidth / 4f
        val bottom = pageHeight / 10f

        val upright = transform(0).mapRectToFraction(left, top, right, bottom)
        assertClose("upright left", 0f, upright.left)
        assertClose("upright top", 0f, upright.top)
        assertClose("upright right", 0.25f, upright.right)
        assertClose("upright bottom", 0.1f, upright.bottom)

        // Rotating 90° clockwise sends the top-left corner to the top-right.
        val turned = transform(90).mapRectToFraction(left, top, right, bottom)
        assertClose("turned right edge", 1f, turned.right)
        assertClose("turned top", 0f, turned.top)
        assertClose("turned left", 0.9f, turned.left)
        assertClose("turned bottom", 0.25f, turned.bottom)
    }

    @Test
    fun `fractions are independent of the rendered width`() {
        val rect = 100f to 200f
        val narrow = transform(0, targetWidth = 400)
            .mapRectToFraction(rect.first, rect.second, rect.first + 50f, rect.second + 20f)
        val wide = transform(0, targetWidth = 2000)
            .mapRectToFraction(rect.first, rect.second, rect.first + 50f, rect.second + 20f)
        assertClose("left", narrow.left, wide.left)
        assertClose("top", narrow.top, wide.top)
        assertClose("right", narrow.right, wide.right)
        assertClose("bottom", narrow.bottom, wide.bottom)
    }

    @Test
    fun `fractionToPage inverts the display mapping`() {
        // A long-press gives fractions of the page box; they must come back as the page point
        // the PDF engine expects, or the selection lands on the wrong word.
        listOf(0, 90, 180, 270).forEach { rotation ->
            val t = transform(rotation)
            listOf(0.1f to 0.2f, 0.5f to 0.5f, 0.9f to 0.75f).forEach { (fx, fy) ->
                val (x, y) = t.fractionToPage(fx, fy)
                val (bx, by) = t.apply(x, y)
                assertClose("x at $rotation", fx * t.bitmapWidth, bx, tolerance = 0.5f)
                assertClose("y at $rotation", fy * t.bitmapHeight, by, tolerance = 0.5f)
            }
        }
    }

    @Test
    fun `a tap in page space stays inside the page bounds`() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val (x, y) = transform(rotation).fractionToPage(0.5f, 0.5f)
            assertTrue("x within page at $rotation: $x", x in 0f..pageWidth.toFloat())
            assertTrue("y within page at $rotation: $y", y in 0f..pageHeight.toFloat())
        }
    }

    @Test
    fun `centre of the box is the centre of the page at every rotation`() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val (x, y) = transform(rotation).fractionToPage(0.5f, 0.5f)
            assertEquals("x at $rotation", pageWidth / 2f, x, 0.5f)
            assertEquals("y at $rotation", pageHeight / 2f, y, 0.5f)
        }
    }
}
