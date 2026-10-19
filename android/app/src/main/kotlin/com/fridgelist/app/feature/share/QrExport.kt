package com.fridgelist.app.feature.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import com.fridgelist.app.R
import com.fridgelist.app.ui.common.QrCodes
import java.io.File
import java.io.FileOutputStream

/** Share or print a list's QR code (ARCHITECTURE §8.7) with framework APIs only. */
object QrExport {
    /** A crisp, print-sized PNG: the QR scaled without smoothing, with a white quiet zone. */
    fun poster(url: String, sizePx: Int = 1024): Bitmap {
        val qr = QrCodes.bitmap(url)
        val out = createBitmap(sizePx, sizePx)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val margin = sizePx / 10
        canvas.drawBitmap(qr, null, Rect(margin, margin, sizePx - margin, sizePx - margin), Paint().apply { isFilterBitmap = false })
        return out
    }

    fun share(context: Context, url: String, listName: String) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "fridgelist-qr.png")
        FileOutputStream(file).use { poster(url).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, url)
            .putExtra(Intent.EXTRA_TITLE, listName)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun print(context: Context, url: String, listName: String) {
        val manager = context.getSystemService(PrintManager::class.java) ?: return
        val job = context.getString(R.string.share_print_job, listName)
        manager.print(job, PosterAdapter(context, poster(url), listName), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
    }

    /** One page: the list name and the QR, centred. */
    private class PosterAdapter(private val context: Context, private val qr: Bitmap, private val title: String) : PrintDocumentAdapter() {
        private var attributes: PrintAttributes? = null

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) return callback.onLayoutCancelled()
            attributes = newAttributes
            val info = PrintDocumentInfo.Builder("fridgelist-qr.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build()
            callback.onLayoutFinished(info, newAttributes != oldAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            val document = PrintedPdfDocument(context, attributes ?: return callback.onWriteFailed(null))
            try {
                val page = document.startPage(0)
                val canvas = page.canvas
                val width = canvas.width
                val paint = Paint().apply {
                    textSize = width / 18f
                    isAntiAlias = true
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText(title, width / 2f, width / 8f, paint)
                val side = (width * 0.7f).toInt()
                val left = (width - side) / 2
                val top = width / 6
                canvas.drawBitmap(qr, null, Rect(left, top, left + side, top + side), Paint().apply { isFilterBitmap = false })
                document.finishPage(page)
                FileOutputStream(destination.fileDescriptor).use { document.writeTo(it) }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e::class.simpleName)
            } finally {
                document.close()
            }
        }
    }
}
