package com.route0465.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/** Printer settings. Locked in Setup › Printer; the scan sheet only reads them. */
object PrinterPrefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("printer", Context.MODE_PRIVATE)

    fun address(ctx: Context): String = sp(ctx).getString("address", "") ?: ""
    fun name(ctx: Context): String = sp(ctx).getString("name", "") ?: ""
    fun setPrinter(ctx: Context, address: String, name: String) =
        sp(ctx).edit().putString("address", address).putString("name", name).remove("detected").apply()

    /** "auto", "cpcl" or "zpl". */
    fun language(ctx: Context): String = sp(ctx).getString("language", "auto") ?: "auto"
    fun setLanguage(ctx: Context, v: String) = sp(ctx).edit().putString("language", v).apply()

    /** What Auto found the last time it asked the printer. */
    fun detected(ctx: Context): String = sp(ctx).getString("detected", "") ?: ""
    fun setDetected(ctx: Context, v: String) = sp(ctx).edit().putString("detected", v).apply()

    /** Print width in dots at 203 dpi: 384 = 2", 576 = 3", 832 = 4". */
    fun widthDots(ctx: Context): Int = sp(ctx).getInt("width", 832)
    fun setWidthDots(ctx: Context, v: Int) = sp(ctx).edit().putInt("width", v).apply()

    /** Blank paper fed after the last line so the end clears the tear bar, in dots (203 per inch). */
    fun feedDots(ctx: Context): Int = sp(ctx).getInt("feed", 100).let { v -> if (FEEDS.any { it.first == v }) v else 100 }
    fun setFeedDots(ctx: Context, v: Int) = sp(ctx).edit().putInt("feed", v).apply()
    val FEEDS = listOf(40 to "Short", 100 to "Medium", 200 to "Long")
    fun feedLabel(d: Int) = FEEDS.firstOrNull { it.first == d }?.second ?: "$d dots"

    val WIDTHS = listOf(384 to "2 inch", 576 to "3 inch", 832 to "4 inch")
    val LANGS = listOf("auto" to "Auto", "cpcl" to "CPCL", "zpl" to "ZPL")
    fun widthLabel(d: Int) = WIDTHS.firstOrNull { it.first == d }?.second ?: "$d dots"
    fun langLabel(l: String) = LANGS.firstOrNull { it.first == l }?.second ?: l
}

data class SheetLine(val code: String, val desc: String, val upc: String, val casePack: String, val qty: Int)
data class SheetPrint(val storeCode: String, val storeName: String, val date: LocalDate, val route: String, val lines: List<SheetLine>)

/** The sheet being built. Kept on the tablet until you clear it, so leaving the screen loses nothing. */
object SheetDraft {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("scan_sheet", Context.MODE_PRIVATE)

    data class Draft(val storeCode: String, val storeName: String, val date: String, val route: String, val items: List<Pair<String, String>>)

    fun load(ctx: Context): Draft {
        val p = sp(ctx)
        val arr = runCatching { JSONArray(p.getString("items", "[]")) }.getOrDefault(JSONArray())
        val items = List(arr.length()) { i -> arr.getJSONObject(i).let { it.getString("code") to it.optString("qty", "") } }
        return Draft(p.getString("store_code", "") ?: "", p.getString("store_name", "") ?: "", p.getString("date", "") ?: "",
            p.getString("route", "0465") ?: "0465", items)
    }

    fun save(ctx: Context, d: Draft) {
        val arr = JSONArray()
        d.items.forEach { (c, q) -> arr.put(JSONObject().put("code", c).put("qty", q)) }
        sp(ctx).edit().putString("store_code", d.storeCode).putString("store_name", d.storeName).putString("date", d.date)
            .putString("route", d.route).putString("items", arr.toString()).apply()
    }
}

object Zebra {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    fun needsPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED

    private fun adapter(ctx: Context) = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    fun bluetoothOn(ctx: Context): Boolean = runCatching { adapter(ctx)?.isEnabled == true }.getOrDefault(false)

    data class Paired(val address: String, val name: String, val isPrinter: Boolean)

    /** Devices already paired in the tablet's Bluetooth settings, printers first. */
    @SuppressLint("MissingPermission")
    fun paired(ctx: Context): List<Paired> {
        if (needsPermission(ctx)) return emptyList()
        val devs = runCatching { adapter(ctx)?.bondedDevices?.toList() }.getOrNull() ?: return emptyList()
        return devs.map { d ->
            val major = runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull()
            Paired(d.address, runCatching { d.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: d.address,
                major == BluetoothClass.Device.Major.IMAGING)
        }.sortedWith(compareBy({ !it.isPrinter }, { it.name.lowercase() }))
    }

    @SuppressLint("MissingPermission")
    private fun open(ctx: Context, address: String): BluetoothSocket {
        val ad = adapter(ctx) ?: throw Exception("This tablet has no Bluetooth.")
        if (!ad.isEnabled) throw Exception("Bluetooth is off. Turn it on and try again.")
        runCatching { ad.cancelDiscovery() }
        val dev: BluetoothDevice = ad.getRemoteDevice(address)
        val tries = listOf<() -> BluetoothSocket>(
            { dev.createRfcommSocketToServiceRecord(SPP) },
            { dev.createInsecureRfcommSocketToServiceRecord(SPP) },
            { dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(dev, 1) as BluetoothSocket },
        )
        var last: Exception? = null
        for (make in tries) {
            val s = try { make() } catch (e: Exception) { last = e; continue }
            try { s.connect(); return s } catch (e: Exception) { last = e; runCatching { s.close() } }
        }
        throw Exception("Couldn't reach the printer. Make sure it's on, awake and in range. (${last?.message ?: "no answer"})")
    }

    /** Asks the printer which language it's set to. Returns "zpl", "cpcl" or "" when it doesn't say. */
    private fun ask(s: BluetoothSocket, waitMs: Long = 2500L): String {
        val out = s.outputStream
        val inp = s.inputStream
        out.write("! U1 getvar \"device.languages\"\r\n".toByteArray(Charsets.US_ASCII)); out.flush()
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + waitMs
        var quietSince = 0L
        while (System.currentTimeMillis() < end) {
            val n = runCatching { inp.available() }.getOrDefault(0)
            if (n > 0) {
                val buf = ByteArray(n); val r = inp.read(buf)
                if (r > 0) sb.append(String(buf, 0, r, Charsets.US_ASCII))
                quietSince = System.currentTimeMillis()
            } else if (sb.isNotEmpty() && System.currentTimeMillis() - quietSince > 300) break
            Thread.sleep(50)
        }
        val a = sb.toString().lowercase()
        return when {
            a.contains("zpl") -> "zpl"
            a.contains("line_print") || a.contains("cpcl") -> "cpcl"
            else -> ""
        }
    }

    /** Sends a job built for whichever language the printer speaks. Returns the language used. Runs on a background thread. */
    fun print(ctx: Context, build: (lang: String, dots: Int, feed: Int) -> String): String {
        val address = PrinterPrefs.address(ctx)
        if (address.isBlank()) throw Exception("No printer chosen yet. Pick it in Setup › Printer.")
        if (needsPermission(ctx)) throw Exception("465stats needs the Nearby devices permission to use the printer.")
        val dots = PrinterPrefs.widthDots(ctx)
        val s = open(ctx, address)
        try {
            var lang = PrinterPrefs.language(ctx)
            if (lang == "auto") {
                lang = ask(s).ifEmpty { PrinterPrefs.detected(ctx) }.ifEmpty { "cpcl" }
                PrinterPrefs.setDetected(ctx, lang)
            }
            val bytes = build(lang, dots, PrinterPrefs.feedDots(ctx)).toByteArray(Charsets.US_ASCII)
            val out = s.outputStream
            // Small paced chunks: the RW420's Bluetooth buffer is small and drops data if flooded.
            var i = 0
            while (i < bytes.size) {
                val n = minOf(512, bytes.size - i)
                out.write(bytes, i, n); out.flush(); i += n
                Thread.sleep(15)
            }
            // Android hands the data off before the printer has it. Closing now cuts off the end of the sheet,
            // so ask the printer a question: it answers only after it has read everything before it.
            val answered = ask(s, 45_000L).isNotEmpty()
            Thread.sleep(if (answered) 800L else maxOf(3000L, bytes.size / 4L))
            return lang
        } finally {
            runCatching { s.close() }
        }
    }
}

/** Turns a sheet into printer commands. Each product is its own short page so long sheets never overflow the printer. */
object SheetLayout {
    private fun clean(s: String, max: Int): String {
        val t = s.map { if (it.code in 32..126 && it != '^' && it != '~') it else ' ' }.joinToString("").replace(Regex("\\s+"), " ").trim()
        return if (t.length > max) t.take(max) else t
    }

    private const val M = 16

    fun build(lang: String, dots: Int, feed: Int, p: SheetPrint, version: String): String {
        val total = p.lines.sumOf { it.qty }
        val sb = StringBuilder()
        val pages = mutableListOf<Page>()
        pages += header(dots, p, total, version)
        p.lines.forEach { pages += product(dots, it) }
        pages += footer(dots, p.lines.size, total, feed)
        pages.forEach { sb.append(if (lang == "zpl") it.zpl(dots) else it.cpcl(dots)) }
        return sb.toString()
    }

    fun test(lang: String, dots: Int, feed: Int, sample: UpcItem?): String {
        val pages = mutableListOf<Page>()
        val pg = Page(110)
        pg.text(M, 10, Size.Big, "465stats test print", center = true)
        pg.text(M, 66, Size.Small, clean("Language ${lang.uppercase()} - paper ${PrinterPrefs.widthLabel(dots)}", 60), center = true)
        pages += pg
        if (sample != null) pages += product(dots, SheetLine(sample.code, sample.desc, sample.upc, sample.casePack, 12))
        pages += Page(50 + feed).also { it.text(M, 10, Size.Small, "If the barcode above scans, you're set.", center = true) }
        return pages.joinToString("") { if (lang == "zpl") it.zpl(dots) else it.cpcl(dots) }
    }

    private fun header(dots: Int, p: SheetPrint, total: Int, version: String): Page {
        val pg = Page(300)
        val bigChars = (dots - 2 * M) / 24
        val smallChars = (dots - 2 * M) / 12
        pg.text(M, 8, Size.Big, "OLE MEXICAN FOODS", center = true)
        pg.text(M, 62, Size.Small, clean("Route ${p.route}", smallChars), center = true)
        pg.text(M, 92, Size.Small, clean("Date ${p.date.format(Fmt.mdy)}", smallChars), center = true)
        pg.text(M, 122, Size.Small, clean("465stats v$version", smallChars), center = true)
        pg.text(M, 170, Size.Small, "CUSTOMER")
        pg.text(M, 196, Size.Big, clean(p.storeName.ifBlank { "Store" }, bigChars))
        if (p.storeCode.isNotBlank()) pg.text(M, 248, Size.Small, clean("Store # ${p.storeCode}", smallChars))
        pg.line(M, 290, dots - M, 3)
        return pg
    }

    private fun product(dots: Int, l: SheetLine): Page {
        val module = if (dots >= 560) 3 else 2
        val barH = if (module == 3) 100 else 80
        val quiet = 9 * module
        val bx = M + quiet
        val bw = 95 * module
        val smallChars = (dots - 2 * M) / 12
        val qx = bx + bw + quiet + 40
        val side = qx + 190 <= dots
        val topBar = 40
        val barBottom = topBar + barH + 44
        val h = if (side) barBottom + 18 else barBottom + 70
        val pg = Page(h)
        pg.text(M, 8, Size.Small, clean("${l.code}  ${l.desc}", smallChars))
        pg.upc(bx, topBar, module, barH, l.upc)
        if (side) {
            pg.text(qx, topBar, Size.Small, "EACHES")
            pg.text(qx, topBar + 30, Size.Huge, l.qty.toString())
        } else {
            pg.text(M, barBottom + 10, Size.Big, "${l.qty} EACHES")
        }
        pg.line(M, h - 4, dots - M, 1)
        return pg
    }

    private fun footer(dots: Int, n: Int, total: Int, feed: Int): Page {
        // The blank space under the product count pushes the end of the sheet past the tear bar.
        val pg = Page(96 + feed)
        pg.text(M, 14, Size.Big, "TOTAL  $total EACHES")
        pg.text(M, 66, Size.Small, "$n product" + if (n == 1) "" else "s")
        return pg
    }

    enum class Size { Small, Big, Huge }

    private class Page(val h: Int) {
        private sealed class Op
        private class T(val x: Int, val y: Int, val size: Size, val s: String, val center: Boolean) : Op()
        /** A solid black bar, drawn exactly so barcodes come out the same width on every printer. */
        private class B(val x: Int, val y: Int, val w: Int, val h: Int) : Op()
        private class L(val x0: Int, val y: Int, val x1: Int, val w: Int) : Op()
        private val ops = mutableListOf<Op>()

        fun text(x: Int, y: Int, size: Size, s: String, center: Boolean = false) { ops += T(x, y, size, s, center) }
        /** UPC-A drawn bar by bar from the 12-digit UPC, guard bars a little longer, digits underneath. */
        fun upc(x: Int, y: Int, module: Int, h: Int, upc12: String) {
            val bits = Upc.bits(upc12)
            var i = 0
            while (i < 95) {
                if (bits[i] == '1') {
                    var j = i
                    while (j < 95 && bits[j] == '1') j++
                    val guard = Upc.isGuard(i)
                    ops += B(x + i * module, y, (j - i) * module, if (guard) h + 12 else h)
                    i = j
                } else i++
            }
            val digits = "${upc12[0]} ${upc12.substring(1, 6)} ${upc12.substring(6, 11)} ${upc12[11]}"
            ops += T(x + (95 * module - digits.length * 12) / 2, y + h + 14, Size.Small, digits, false)
        }
        fun line(x0: Int, y: Int, x1: Int, w: Int) { ops += L(x0, y, x1, w) }

        fun cpcl(dots: Int): String {
            // JOURNAL = continuous receipt roll: no hunting for label gaps, no backing up over what already printed.
            val sb = StringBuilder("! 0 200 200 $h 1\r\nPAGE-WIDTH $dots\r\nJOURNAL\r\n")
            for (o in ops) when (o) {
                is T -> {
                    val (font, size) = when (o.size) { Size.Small -> 7 to 0; Size.Big -> 4 to 0; Size.Huge -> 4 to 1 }
                    if (o.center) sb.append("CENTER $dots\r\n")
                    sb.append("TEXT $font $size ${if (o.center) 0 else o.x} ${o.y} ${o.s}\r\n")
                    if (o.center) sb.append("LEFT\r\n")
                }
                // One-dot-wide vertical lines side by side: no guessing which way a thick line grows.
                is B -> for (dx in 0 until o.w) sb.append("LINE ${o.x + dx} ${o.y} ${o.x + dx} ${o.y + o.h} 1\r\n")
                is L -> sb.append("LINE ${o.x0} ${o.y} ${o.x1} ${o.y} ${o.w}\r\n")
            }
            sb.append("PRINT\r\n")
            return sb.toString()
        }

        fun zpl(dots: Int): String {
            // ^MNN = continuous media for this print only (not saved to the printer).
            val sb = StringBuilder("^XA^MNN^PW$dots^LL$h^LH0,0^CI0\n")
            for (o in ops) when (o) {
                is T -> {
                    val px = when (o.size) { Size.Small -> 24; Size.Big -> 46; Size.Huge -> 92 }
                    if (o.center) sb.append("^FO0,${o.y}^FB$dots,1,0,C,0^A0N,$px,$px^FD${o.s}^FS\n")
                    else sb.append("^FO${o.x},${o.y}^A0N,$px,$px^FD${o.s}^FS\n")
                }
                is B -> sb.append("^FO${o.x},${o.y}^GB${o.w},${o.h},${o.w}^FS\n")
                is L -> sb.append("^FO${o.x0},${o.y}^GB${o.x1 - o.x0},${o.w},${o.w}^FS\n")
            }
            sb.append("^XZ\n")
            return sb.toString()
        }
    }
}

/** The installed version, e.g. 0.1.36 (the last number is the build). */
fun appVersion(ctx: Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
