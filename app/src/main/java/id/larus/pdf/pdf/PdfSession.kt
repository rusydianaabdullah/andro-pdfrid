package id.larus.pdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.util.Size
import androidx.core.graphics.createBitmap
import androidx.core.util.size
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPasswordException
import androidx.pdf.SandboxedPdfLoader
import androidx.pdf.content.PdfPageTextContent
import androidx.pdf.content.SelectionBoundary
import kotlinx.coroutines.Dispatchers
import java.io.Closeable

/** A run of matching text on one page, in PDF page coordinates. */
data class SearchMatch(val page: Int, val bounds: List<RectF>)

/** A live text selection: what is highlighted, and the text it corresponds to. */
data class TextSelection(
    val page: Int,
    val text: String,
    val bounds: List<RectF>,
)

/**
 * An open document.
 *
 * Wraps androidx.pdf, which renders and extracts text in an isolated process. That is what
 * makes search and selection possible at all — the platform's own PdfRenderer exposes text
 * only from Android 15, and hands back nothing but pixels before that.
 */
class PdfSession private constructor(
    private val document: PdfDocument,
) : Closeable {

    val pageCount: Int = document.pageCount

    private val sizes = arrayOfNulls<Size>(pageCount)

    /**
     * True once [close] has run. The renderer lives in another process, so every call below is
     * a binder transaction: once the document is closed — or the sandbox is killed under memory
     * pressure — those transactions throw DeadObjectException. Nothing here may crash the app
     * for that; a closed or dead document simply has no answers left to give.
     */
    @Volatile
    private var closed = false

    suspend fun pageSize(index: Int): Size? {
        if (closed) return null
        sizes.getOrNull(index)?.let { return it }
        val info = runCatching { document.getPageInfo(index) }.getOrNull() ?: return null
        return Size(info.width, info.height).also {
            if (index in sizes.indices) sizes[index] = it
        }
    }

    /**
     * Renders one page [targetWidth] pixels wide. androidx.pdf has no rotation parameter, so a
     * turned page is rendered upright at swapped dimensions and then spun into place.
     */
    suspend fun renderPage(index: Int, targetWidth: Int, rotationDegrees: Int = 0): Bitmap? {
        if (closed || index !in 0 until pageCount) return null
        val page = pageSize(index) ?: return null
        val transform = PageTransform.of(page.width, page.height, targetWidth, rotationDegrees)

        val upright = if (transform.isQuarterTurned) {
            Size(transform.bitmapHeight, transform.bitmapWidth)
        } else {
            Size(transform.bitmapWidth, transform.bitmapHeight)
        }

        val rendered = runCatching {
            document.getPageBitmapSource(index).use { source -> source.getBitmap(upright) }
        }.getOrNull() ?: return null

        return if (transform.quarterTurns == 0) rendered else rotate(rendered, transform)
    }

    private fun rotate(source: Bitmap, transform: PageTransform): Bitmap {
        val target = createBitmap(transform.bitmapWidth, transform.bitmapHeight)
        val matrix = Matrix().apply {
            postRotate(transform.quarterTurns * 90f)
            when (transform.quarterTurns) {
                1 -> postTranslate(source.height.toFloat(), 0f)
                2 -> postTranslate(source.width.toFloat(), source.height.toFloat())
                3 -> postTranslate(0f, source.width.toFloat())
            }
        }
        Canvas(target).drawBitmap(source, matrix, null)
        source.recycle()
        return target
    }

    /** Every match for [query] across the whole document, in reading order. */
    suspend fun search(query: String): List<SearchMatch> {
        if (closed || query.isBlank()) return emptyList()
        val results = runCatching {
            document.searchDocument(query, 0 until pageCount)
        }.getOrNull() ?: return emptyList()

        val matches = mutableListOf<SearchMatch>()
        for (i in 0 until results.size) {
            val page = results.keyAt(i)
            results.valueAt(i).forEach { bounds ->
                matches += SearchMatch(page, bounds.bounds)
            }
        }
        return matches.sortedWith(
            compareBy({ it.page }, { it.bounds.firstOrNull()?.top ?: 0f })
        )
    }

    /**
     * Text between two points on a page, both in PDF page coordinates. Passing the same point
     * twice selects the word under it, which is what a long-press should do.
     */
    suspend fun selectBetween(page: Int, start: PointF, stop: PointF): TextSelection? {
        if (closed) return null
        val selection = runCatching { document.getSelectionBounds(page, start, stop) }.getOrNull()
        return selection?.toTextSelection(page)
    }

    private fun androidx.pdf.content.PageSelection.toTextSelection(page: Int): TextSelection? {
        val texts = selectedContents.filterIsInstance<PdfPageTextContent>()
        if (texts.isEmpty()) return null
        val text = texts.joinToString("") { it.text }
        if (text.isBlank()) return null
        return TextSelection(page, text, texts.flatMap { it.bounds })
    }

    /** All text on a page, used by "copy page text". */
    suspend fun pageText(page: Int): String? {
        if (closed) return null
        val content = runCatching { document.getPageContent(page) }.getOrNull() ?: return null
        val text = content.textContents.joinToString("\n") { it.text }
        return text.ifBlank { null }
    }

    override fun close() {
        closed = true
        runCatching { document.close() }
    }

    companion object {
        sealed interface OpenResult {
            data class Success(val session: PdfSession) : OpenResult
            /** The file needs a password we do not have yet. */
            data object NeedsPassword : OpenResult
            data class Failed(val cause: Throwable) : OpenResult
        }

        suspend fun open(context: Context, uri: Uri, password: String? = null): OpenResult = try {
            val loader = SandboxedPdfLoader(context.applicationContext, Dispatchers.IO)
            OpenResult.Success(PdfSession(loader.openDocument(uri, password)))
        } catch (e: PdfPasswordException) {
            OpenResult.NeedsPassword
        } catch (e: Throwable) {
            OpenResult.Failed(e)
        }
    }
}
