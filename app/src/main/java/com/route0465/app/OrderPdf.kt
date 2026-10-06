package com.route0465.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate

object OrderPdf {

    fun fileName(due: LocalDate) = "Route0465_Order_${due.format(Fmt.fileMd)}.pdf"

    fun build(ctx: Context, items: List<OrderItem>, due: LocalDate): File {
        val doc = PdfDocument()
        val body = Paint().apply { textSize = 11f; isAntiAlias = true }
        val bold = Paint(body).apply { typeface = Typeface.DEFAULT_BOLD }
        val title = Paint(bold).apply { textSize = 18f }
        val ordered = items.filter { it.qty > 0 }
        val total = ordered.sumOf { it.qty }

        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(612, 792, pageNo).create())
        var c = page.canvas
        var y = 56f
        c.drawText("Route 0465 order", 48f, y, title); y += 22f
        c.drawText("Due ${due.format(Fmt.full)} by midnight · $total cases", 48f, y, body); y += 28f

        fun header() {
            c.drawText("Code", 48f, y, bold)
            c.drawText("Product", 120f, y, bold)
            c.drawText("Cases", 520f, y, bold)
            y += 8f
            c.drawLine(48f, y, 564f, y, body)
            y += 16f
        }
        header()
        for (it in ordered) {
            if (y > 750f) {
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(612, 792, pageNo).create())
                c = page.canvas
                y = 56f
                header()
            }
            c.drawText(it.code, 48f, y, body)
            c.drawText(it.name.take(60), 120f, y, body)
            c.drawText(it.qty.toString(), 530f, y, bold)
            y += 18f
        }
        if (ordered.isEmpty()) c.drawText("No cases entered.", 48f, y, body)
        doc.finishPage(page)

        val dir = File(ctx.cacheDir, "pdf").apply { mkdirs() }
        val f = File(dir, fileName(due))
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    fun saveToDownloads(ctx: Context, f: File) {
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: throw IllegalStateException("Couldn't create the file in Downloads")
        ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
    }

    fun email(ctx: Context, f: File, to: String, due: LocalDate, cases: Int) {
        val uri = FileProvider.getUriForFile(ctx, "com.route0465.app.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            if (to.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, "Route 0465 order, due ${due.format(Fmt.day)}")
            putExtra(Intent.EXTRA_TEXT, "Route 0465 order attached ($cases cases).")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Email order").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
