package id.larus.pdf.annot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import id.larus.pdf.pdf.PdfSession
import java.io.File

/**
 * Writes a copy of the document with the annotations burned in.
 *
 * The pages are re-rendered as images rather than edited in place: androidx.pdf reads PDFs, it
 * does not write them, and the original is a URI the app has no business rewriting. The result
 * is a flat PDF that opens anywhere with the marks visible — text is no longer selectable in
 * it, which is why this is an export and not a save.
 */
object AnnotationExport {

    /** Pixels per PDF point. Enough that text still reads crisply when zoomed in a viewer. */
    private const val DENSITY = 2f

    suspend fun write(
        context: Context,
        session: PdfSession,
        annotations: List<Annotation>,
        fileName: String,
    ): File? {
        val document = PdfDocument()
        val target = File(exportDir(context), fileName)

        try {
            for (index in 0 until session.pageCount) {
                val size = session.pageSize(index) ?: continue
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(size.width, size.height, index + 1).create()
                )
                val bitmap = session.renderPage(
                    index,
                    (size.width * DENSITY).toInt().coerceAtMost(MAX_WIDTH),
                )
                if (bitmap != null) {
                    page.canvas.drawBitmap(
                        bitmap,
                        null,
                        RectF(0f, 0f, size.width.toFloat(), size.height.toFloat()),
                        null,
                    )
                    bitmap.recycle()
                }
                drawAnnotations(
                    canvas = page.canvas,
                    annotations = annotations.filter { it.page == index },
                    width = size.width.toFloat(),
                    height = size.height.toFloat(),
                )
                document.finishPage(page)
            }

            target.parentFile?.mkdirs()
            target.outputStream().use { document.writeTo(it) }
            return target
        } catch (e: Throwable) {
            target.delete()
            return null
        } finally {
            document.close()
        }
    }

    private fun drawAnnotations(
        canvas: Canvas,
        annotations: List<Annotation>,
        width: Float,
        height: Float,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        annotations.filterIsInstance<Markup>().forEach { markup ->
            markup.patches.forEach { patch ->
                val left = patch.left * width
                val right = patch.right * width
                val top = patch.top * height
                val bottom = patch.bottom * height
                val weight = ((bottom - top) * 0.07f).coerceAtLeast(0.6f)
                when (markup.tool) {
                    AnnotationTool.Highlight -> {
                        paint.style = Paint.Style.FILL
                        paint.color = markup.color
                        paint.alpha = 90
                        canvas.drawRect(left, top, right, bottom, paint)
                        paint.alpha = 255
                    }
                    AnnotationTool.Underline -> {
                        paint.style = Paint.Style.STROKE
                        paint.color = markup.color
                        paint.strokeWidth = weight
                        canvas.drawLine(left, bottom, right, bottom, paint)
                    }
                    AnnotationTool.Strikethrough -> {
                        paint.style = Paint.Style.STROKE
                        paint.color = markup.color
                        paint.strokeWidth = weight
                        val middle = (top + bottom) / 2f
                        canvas.drawLine(left, middle, right, middle, paint)
                    }
                    else -> Unit
                }
            }
        }

        annotations.filterIsInstance<Stroke>().forEach { stroke ->
            if (stroke.points.size < 2) return@forEach
            paint.style = Paint.Style.STROKE
            paint.color = stroke.color
            paint.strokeWidth = (stroke.width * width).coerceAtLeast(0.5f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            val path = Path()
            stroke.points.forEachIndexed { position, spot ->
                val x = spot.x * width
                val y = spot.y * height
                if (position == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }

        annotations.filterIsInstance<TextBox>().forEach { box ->
            paint.style = Paint.Style.FILL
            paint.color = box.color
            paint.textSize = box.size * height
            // Canvas.drawText has no notion of a line break — it lays the whole string out on
            // one baseline and draws newlines as nothing. On screen the text is measured by
            // Compose, which does wrap, so a note typed across several lines came out of the
            // export as a single run. Each line is drawn on its own baseline instead.
            val left = box.at.x * width
            // The stored point is the top of the text, but Canvas draws from the baseline,
            // so the first line drops by the ascent to land where the reader put it.
            var baseline = box.at.y * height - paint.fontMetrics.ascent
            box.text.split('\n').forEach { line ->
                canvas.drawText(line, left, baseline, paint)
                baseline += paint.fontSpacing
            }
        }

        // A note is a pin plus, in the app, a dialog that opens on tap. A flat export has no
        // "on tap" — so without a callout, the pin exported as a numbered dot with nothing
        // behind it, and the comment itself never appeared anywhere in the file. The callout
        // is what makes the words actually present in the export.
        annotations.filterIsInstance<Note>().forEachIndexed { position, note ->
            val x = note.at.x * width
            val y = note.at.y * height
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, NOTE_RADIUS, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            paint.color = note.color
            canvas.drawCircle(x, y, NOTE_RADIUS, paint)
            paint.style = Paint.Style.FILL
            paint.textSize = NOTE_RADIUS * 1.2f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("${position + 1}", x, y + NOTE_RADIUS * 0.45f, paint)
            paint.textAlign = Paint.Align.LEFT

            if (note.text.isNotBlank()) {
                drawNoteCallout(canvas, paint, note, x, y, width, height)
            }
        }
    }

    /** A small bordered box carrying the note's actual text, anchored next to its pin. */
    private fun drawNoteCallout(
        canvas: Canvas,
        paint: Paint,
        note: Note,
        pinX: Float,
        pinY: Float,
        width: Float,
        height: Float,
    ) {
        val textSize = (height * 0.014f).coerceIn(6f, 11f)
        paint.textSize = textSize
        val padding = textSize * 0.6f
        val maxTextWidth = (width * 0.32f).coerceAtLeast(60f)
        val lines = wrapText(note.text, paint, maxTextWidth)
        val lineHeight = paint.fontSpacing
        val textWidth = lines.maxOf { paint.measureText(it) }
        val boxWidth = textWidth + padding * 2
        val boxHeight = lineHeight * lines.size + padding * 2

        // Flip to the left of the pin if a right-hand box would run off the page; a note
        // dropped near the right margin is a normal thing to do, not an edge case to ignore.
        var boxLeft = pinX + NOTE_RADIUS * 1.6f
        if (boxLeft + boxWidth > width) {
            boxLeft = (pinX - NOTE_RADIUS * 1.6f - boxWidth).coerceAtLeast(0f)
        }
        val boxTop = (pinY - boxHeight / 2f).coerceIn(0f, (height - boxHeight).coerceAtLeast(0f))
        val rect = RectF(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight)

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRoundRect(rect, padding, padding, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = note.color
        canvas.drawRoundRect(rect, padding, padding, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        var baseline = boxTop + padding - paint.fontMetrics.ascent
        lines.forEach { line ->
            canvas.drawText(line, boxLeft + padding, baseline, paint)
            baseline += lineHeight
        }
    }

    /**
     * Greedy word-wrap against [paint]'s current text size. `Canvas.drawText` never wraps on
     * its own, and a note is exactly the kind of annotation someone writes more than four
     * words into.
     */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        text.split('\n').forEach { paragraph ->
            var remaining = paragraph
            while (remaining.isNotEmpty()) {
                var count = paint.breakText(remaining, true, maxWidth, null)
                if (count <= 0) count = 1
                if (count < remaining.length) {
                    val lastSpace = remaining.lastIndexOf(' ', count - 1)
                    if (lastSpace > 0) count = lastSpace
                }
                lines += remaining.substring(0, count).trimEnd()
                remaining = remaining.substring(count).trimStart()
            }
            if (paragraph.isEmpty()) lines += ""
        }
        return lines.ifEmpty { listOf("") }
    }

    /** Exports live in cache/exports, which is what the file provider hands out. */
    fun exportDir(context: Context): File = File(context.cacheDir, "exports")

    private const val NOTE_RADIUS = 7f
    private const val MAX_WIDTH = 2400
}
