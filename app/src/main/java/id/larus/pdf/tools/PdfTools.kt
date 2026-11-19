package id.larus.pdf.tools

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/**
 * Splitting and merging documents.
 *
 * Both go through PDFBox, which copies page objects wholesale — text, fonts and vector art come
 * out the other side intact, rather than flattened into pictures of themselves.
 *
 * Each operation writes into the app's own cache and hands back a [File]; nothing touches the
 * document the user picked.
 */
object PdfTools {

    /** Where finished files land. The file provider only shares this directory. */
    fun outputDir(context: Context): File =
        File(context.cacheDir, "exports").apply { mkdirs() }

    // ---- split ----------------------------------------------------------------------------

    /**
     * Writes one PDF per requested range. A range of 3..5 becomes a file with those three
     * pages, in order, keeping everything the originals had.
     */
    fun split(
        context: Context,
        source: Uri,
        ranges: List<IntRange>,
        baseName: String,
    ): List<File> {
        if (ranges.isEmpty()) return emptyList()
        val written = mutableListOf<File>()
        openSource(context, source).use { document ->
            val pageCount = document.numberOfPages
            ranges.forEachIndexed { index, range ->
                val pages = range.filter { it in 0 until pageCount }
                if (pages.isEmpty()) return@forEachIndexed
                PDDocument().use { part ->
                    pages.forEach { page -> part.addPage(document.getPage(page)) }
                    val label = if (pages.size == 1) {
                        "${pages.first() + 1}"
                    } else {
                        "${pages.first() + 1}-${pages.last() + 1}"
                    }
                    val file = File(outputDir(context), "$baseName ($label).pdf")
                    part.save(file)
                    written += file
                }
            }
        }
        return written
    }

    /** Splits into one file per page, which is what "split every page" means. */
    fun splitEveryPage(context: Context, source: Uri, baseName: String): List<File> {
        val count = pageCount(context, source)
        return split(context, source, (0 until count).map { it..it }, baseName)
    }

    fun pageCount(context: Context, source: Uri): Int =
        openSource(context, source).use { it.numberOfPages }

    // ---- merge ----------------------------------------------------------------------------

    /** Concatenates documents in the order given, keeping each one's pages as they were. */
    fun merge(context: Context, sources: List<Uri>, outputName: String): File? {
        if (sources.size < 2) return null
        val target = File(outputDir(context), outputName)
        val merger = PDFMergerUtility()
        merger.destinationFileName = target.absolutePath
        val streams = sources.mapNotNull { context.contentResolver.openInputStream(it) }
        if (streams.size != sources.size) {
            streams.forEach { it.close() }
            return null
        }
        try {
            streams.forEach { merger.addSource(it) }
            merger.mergeDocuments(null)
        } finally {
            streams.forEach { runCatching { it.close() } }
        }
        return target.takeIf { it.exists() }
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun openSource(context: Context, source: Uri): PDDocument =
        context.contentResolver.openInputStream(source).use { PDDocument.load(it) }
}
