package com.route0465.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/**
 * End of Day paperwork: store photos taken in the app, the XSales End of Day PDF shared into the app,
 * and one email to the bosses with both PDFs attached. Everything lives in the app's own storage.
 */
object Paperwork {
    const val AUTHORITY = "com.route0465.app.files"
    private const val KEEP_DAYS = 60L

    fun dayDir(ctx: Context, date: LocalDate): File = File(ctx.filesDir, "paperwork/$date").apply { mkdirs() }

    fun newPhotoFile(ctx: Context, date: LocalDate): File = File(dayDir(ctx, date), "photo_${System.currentTimeMillis()}.jpg")

    /** Copies the scanner's finished pages into today's paperwork, in order. Returns how many were saved. */
    fun saveScannedPages(ctx: Context, date: LocalDate, cusCode: String, store: String, pages: List<Uri>): Int {
        var n = 0
        val start = System.currentTimeMillis()
        pages.forEachIndexed { i, uri ->
            val f = File(dayDir(ctx, date), "scan_${start}_${i + 1}.jpg")
            val ok = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } } != null
            }.getOrDefault(false)
            if (ok && f.length() > 0) {
                Db.get(ctx).addPhoto(date, cusCode, store, f.path, start + i); n++
            } else f.delete()
        }
        return n
    }

    fun uriFor(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, AUTHORITY, f)

    /** URIs carried by a share (single or multiple), however the sending app packed them. */
    @Suppress("DEPRECATION")
    fun sharedUris(i: Intent): List<Uri> {
        val out = ArrayList<Uri>()
        runCatching { i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()?.let { out.add(it) }
        runCatching { i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()?.let { out.addAll(it) }
        i.clipData?.let { cd -> for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { out.add(it) } }
        return out.distinct()
    }

    private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /**
     * Saves the first real PDF among [uris] as today's End of Day PDF (replacing an earlier one for today).
     * Returns a message for the screen.
     */
    fun receiveEod(ctx: Context, uris: List<Uri>): String {
        if (uris.isEmpty()) return "Nothing was attached to that share."
        val today = LocalDate.now()
        for (uri in uris) {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
            if (bytes.size < 5 || String(bytes, 0, 5, Charsets.ISO_8859_1) != "%PDF-") continue
            val f = File(dayDir(ctx, today), "EOD_0465_$today.pdf")
            f.writeBytes(bytes)
            val name = displayName(ctx, uri) ?: f.name
            val replacing = Db.get(ctx).eodPdf(today) != null
            Db.get(ctx).setEodPdf(today, f.path, name)
            Db.get(ctx).log("End of Day PDF received for ${today.format(Fmt.mdy)}: $name (${bytes.size / 1024} KB)")
            return (if (replacing) "Replaced today's End of Day PDF with " else "Got today's End of Day PDF: ") + name
        }
        return "That share didn't include a PDF, so nothing was saved."
    }

    /** Decodes a photo no bigger than [maxSide] pixels on its long side, turned upright. */
    fun loadScaled(path: String, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val longest = max(bmp.width, bmp.height)
        if (longest > maxSide) {
            val s = maxSide.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        val degrees = runCatching {
            when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (degrees != 0f) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        return bmp
    }

    /** One PDF of all of today's store photos: stores in the order first photographed, one photo per page. */
    fun buildStorePdf(ctx: Context, date: LocalDate, photos: List<StorePhoto>): File? {
        if (photos.isEmpty()) return null
        val groups = LinkedHashMap<String, MutableList<StorePhoto>>()
        photos.sortedBy { it.takenAt }.forEach { groups.getOrPut(it.cusCode) { ArrayList() }.add(it) }

        val doc = PdfDocument()
        val head = Paint().apply { textSize = 12f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
        val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        var pageNo = 0
        for ((_, list) in groups) {
            list.forEachIndexed { i, p ->
                val bmp = loadScaled(p.path, 2000) ?: return@forEachIndexed
                pageNo++
                val page = doc.startPage(PdfDocument.PageInfo.Builder(612, 792, pageNo).create())
                val c = page.canvas
                c.drawText("Route 0465 · ${p.store} · ${date.format(Fmt.full)} · page ${i + 1} of ${list.size}", 36f, 28f, head)
                val maxW = 540f
                val maxH = 734f
                val s = min(maxW / bmp.width, maxH / bmp.height)
                val w = bmp.width * s
                val h = bmp.height * s
                val left = 36f + (maxW - w) / 2f
                c.drawBitmap(bmp, null, RectF(left, 40f, left + w, 40f + h), imgPaint)
                doc.finishPage(page)
                bmp.recycle()
            }
        }
        if (pageNo == 0) {
            doc.close(); return null
        }
        val f = File(dayDir(ctx, date), "Store_paperwork_0465_$date.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    /** Opens the mail app with the bosses' addresses, a subject, a short summary and the PDFs attached. */
    fun email(ctx: Context, date: LocalDate, to: String, files: List<File>, body: String) {
        val uris = ArrayList(files.map { uriFor(ctx, it) })
        val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/pdf"
            val addrs = to.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.contains('@') }
            if (addrs.isNotEmpty()) putExtra(Intent.EXTRA_EMAIL, addrs.toTypedArray())
            putExtra(Intent.EXTRA_SUBJECT, "Route 0465 End of Day ${date.format(Fmt.mdy)}")
            putExtra(Intent.EXTRA_TEXT, body)
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            if (uris.isNotEmpty()) {
                val clip = ClipData.newRawUri("paperwork", uris[0])
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                clipData = clip
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Send End of Day").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    fun viewPhoto(ctx: Context, f: File) {
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(ctx, f), "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(i, "Open photo").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun view(ctx: Context, f: File) {
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(ctx, f), "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(i, "Open PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Keeps the last 60 days of paperwork on the tablet. */
    fun prune(ctx: Context) {
        val keepFrom = LocalDate.now().minusDays(KEEP_DAYS)
        File(ctx.filesDir, "paperwork").listFiles()?.forEach { d ->
            val date = runCatching { LocalDate.parse(d.name) }.getOrNull() ?: return@forEach
            if (date.isBefore(keepFrom)) d.deleteRecursively()
        }
        Db.get(ctx).prunePaperwork(keepFrom)
    }

    @Suppress("unused")
    private val sdk = Build.VERSION.SDK_INT
}
