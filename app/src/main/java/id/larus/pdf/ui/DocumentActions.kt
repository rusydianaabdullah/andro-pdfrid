package id.larus.pdf.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.ContextCompat
import id.larus.pdf.R
import java.io.FileOutputStream

/** Share and print, both handed straight to the system so nothing is re-encoded. */
object DocumentActions {

    fun share(context: Context, uri: Uri, name: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.action_share))
            )
        }
    }

    fun print(context: Context, uri: Uri, name: String) {
        val printManager = ContextCompat.getSystemService(context, PrintManager::class.java) ?: return
        runCatching { printManager.print(name, PassThroughAdapter(context, uri, name), null) }
    }

    /**
     * The document is already a PDF, so printing just copies the bytes to the spooler rather
     * than rasterising anything.
     */
    private class PassThroughAdapter(
        private val context: Context,
        private val uri: Uri,
        private val name: String,
    ) : PrintDocumentAdapter() {

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            callback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output ->
                        input.copyTo(output)
                    }
                } ?: error("cannot read document")
            }
            if (copied.isSuccess) {
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } else {
                callback.onWriteFailed(copied.exceptionOrNull()?.message)
            }
        }
    }
}
