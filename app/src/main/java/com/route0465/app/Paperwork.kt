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

    /**
     * One PDF of the day's store pages, like a scanner app makes: each page is just the scanned paper, sized to
     * that paper (long receipts stay long, sideways invoices stay sideways), no border and no label.
     * Stores come in the order first scanned. The JPEGs go in as-is, so nothing is blurred by re-compressing.
     */
    fun buildStorePdf(ctx: Context, date: LocalDate, photos: List<StorePhoto>): File? {
        if (photos.isEmpty()) return null
        val groups = LinkedHashMap<String, MutableList<StorePhoto>>()
        photos.sortedWith(compareBy({ it.takenAt }, { it.id })).forEach { groups.getOrPut(it.cusCode) { ArrayList() }.add(it) }
        val pages = groups.values.flatten().mapNotNull { uprightJpeg(it.path) }
        if (pages.isEmpty()) return null
        val f = File(dayDir(ctx, date), "Store_paperwork_0465_$date.pdf")
        writeJpegPdf(f, pages, "Route 0465 store paperwork ${date.format(Fmt.mdy)}")
        return f
    }

    private class JpegPage(val bytes: ByteArray, val w: Int, val h: Int) {
        /** Colour channels from the JPEG's frame header: 1 = grey (scanner's black-and-white filter), 3 = colour. */
        val components: Int = run {
            var i = 2
            var comps = 3
            while (i + 9 < bytes.size) {
                if (bytes[i] != 0xFF.toByte()) { i++; continue }
                val m = bytes[i + 1].toInt() and 0xFF
                if (m == 0xD8 || m == 0x01 || m in 0xD0..0xD7 || m == 0xFF) { i++; continue }
                val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
                if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) { comps = bytes[i + 9].toInt() and 0xFF; break }
                i += 2 + len
            }
            comps
        }
    }

    /** The page as upright JPEG bytes: the file itself when it needs no turning, otherwise turned and re-saved once. */
    private fun uprightJpeg(path: String): JpegPage? {
        val file = File(path)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val turned = runCatching {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL).let { it != ExifInterface.ORIENTATION_NORMAL && it != ExifInterface.ORIENTATION_UNDEFINED }
        val tooBig = max(bounds.outWidth, bounds.outHeight) > 4000
        if (bounds.outMimeType == "image/jpeg" && !turned && !tooBig) return JpegPage(file.readBytes(), bounds.outWidth, bounds.outHeight)
        val bmp = loadScaled(path, 3200) ?: return null
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        val page = JpegPage(out.toByteArray(), bmp.width, bmp.height)
        bmp.recycle()
        return page
    }

    /** Minimal PDF: one page per JPEG, page size = image size at 200 dpi, image embedded untouched (DCTDecode). */
    private fun writeJpegPdf(f: File, pages: List<JpegPage>, title: String) {
        val out = java.io.BufferedOutputStream(f.outputStream())
        var pos = 0L
        val offsets = ArrayList<Long>()
        fun w(s: String) { val b = s.toByteArray(Charsets.ISO_8859_1); out.write(b); pos += b.size }
        fun wb(b: ByteArray) { out.write(b); pos += b.size }
        fun obj(n: Int) { while (offsets.size < n) offsets.add(0L); offsets[n - 1] = pos; w("$n 0 obj\n") }
        val safeTitle = title.replace("(", "").replace(")", "").replace("\\", "")
        w("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n")
        // 1 catalog, 2 pages, 3 info, then per page: page, contents, image.
        val kids = pages.indices.joinToString(" ") { "${4 + it * 3} 0 R" }
        obj(1); w("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        obj(2); w("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>\nendobj\n")
        obj(3); w("<< /Title ($safeTitle) /Producer (465stats) >>\nendobj\n")
        pages.forEachIndexed { i, p ->
            val n = 4 + i * 3
            val pw = p.w * 72.0 / 200.0
            val ph = p.h * 72.0 / 200.0
            val pwS = String.format(java.util.Locale.US, "%.2f", pw)
            val phS = String.format(java.util.Locale.US, "%.2f", ph)
            obj(n); w("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pwS $phS] /Resources << /XObject << /Im0 ${n + 2} 0 R >> >> /Contents ${n + 1} 0 R >>\nendobj\n")
            val content = "q $pwS 0 0 $phS 0 0 cm /Im0 Do Q\n"
            obj(n + 1); w("<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")
            obj(n + 2); w("<< /Type /XObject /Subtype /Image /Width ${p.w} /Height ${p.h} /ColorSpace ${when (p.components) { 1 -> "/DeviceGray"; 4 -> "/DeviceCMYK"; else -> "/DeviceRGB" }} /BitsPerComponent 8 /Filter /DCTDecode /Length ${p.bytes.size} >>\nstream\n")
            wb(p.bytes); w("\nendstream\nendobj\n")
        }
        val xref = pos
        w("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { w(String.format(java.util.Locale.US, "%010d 00000 n \n", it)) }
        w("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info 3 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.close()
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
