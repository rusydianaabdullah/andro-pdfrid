package id.larus.pdf

import id.larus.pdf.ui.ToolsViewModel.Companion.parseRanges
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The split field is free text, so it has to cope with whatever someone types into it. The
 * ranges it returns are zero-based, because that is what the page list uses.
 */
class PageRangeParsingTest {

    @Test
    fun `single pages and ranges both parse`() {
        assertEquals(
            listOf(0..0, 1..2, 7..9),
            parseRanges("1, 2-3, 8-10", 10),
        )
    }

    @Test
    fun `whitespace and empty pieces are ignored`() {
        assertEquals(listOf(0..0, 4..4), parseRanges("  1 , , 5 ,", 10))
    }

    @Test
    fun `a range beyond the document is clamped to it`() {
        assertEquals(listOf(6..9), parseRanges("7-99", 10))
    }

    @Test
    fun `a single page beyond the document is dropped rather than clamped`() {
        assertEquals(listOf(0..0), parseRanges("1, 40", 10))
    }

    @Test
    fun `a backwards range is read as the range it describes`() {
        assertEquals(listOf(2..5), parseRanges("6-3", 10))
    }

    @Test
    fun `nonsense yields nothing, which the caller reads as one file per page`() {
        assertEquals(emptyList<IntRange>(), parseRanges("abc, -, 1-x", 10))
    }

    @Test
    fun `an empty document has no ranges whatever is typed`() {
        assertEquals(emptyList<IntRange>(), parseRanges("1-3", 0))
    }
}
