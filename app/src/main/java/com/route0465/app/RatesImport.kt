package com.route0465.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

/**
 * Rates upload: CSV or Excel (.xlsx), same column names Taco-Boys accepts.
 * Market rate and commission/credit % are separate dated histories, like Taco-Boys:
 * a new upload never edits an old entry, it adds one effective from its date.
 */
object RatesImport {

    data class Result(val market: Int, val commission: Int, val skipped: Int, val notes: List<String>)

    private val synonyms = mapOf(
        "code" to "code", "item code" to "code", "product code" to "code", "item number" to "code",
        "code item number" to "code", "sku" to "code", "item" to "code",
        "name" to "name", "product name" to "name", "item name" to "name", "description" to "name",
        "market rate" to "market_rate", "market_rate" to "market_rate", "rate" to "market_rate", "price" to "market_rate",
        "commission" to "commission_pct", "commission %" to "commission_pct", "commission_pct" to "commission_pct",
        "commission percent" to "commission_pct", "comm %" to "commission_pct",
        "credit" to "credit_pct", "credit %" to "credit_pct", "credit_pct" to "credit_pct", "credit percent" to "credit_pct",
        "effective date" to "effective_date", "effective_date" to "effective_date", "date" to "effective_date",
        "start date" to "effective_date",
        "route" to "route", "route number" to "route", "route #" to "route",
    )

    private fun normalizeHeader(raw: String): String {
        val t = raw.trim().lowercase().replace(Regex("[^a-z0-9%_# ]"), " ").replace(Regex("\\s+"), " ").trim()
        return synonyms[t] ?: t
    }

    private fun number(raw: String?): Double? =
        raw?.replace("$", "")?.replace(",", "")?.replace("%", "")?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    private fun pct(raw: String?): Double? = number(raw)?.let { if (it > 1) it / 100.0 else it }

    private fun dateCell(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        raw.trim().toDoubleOrNull()?.let { serial ->
            if (serial > 20000 && serial < 80000) return LocalDate.of(1899, 12, 30).plusDays(serial.toLong())
        }
        return parseLooseDate(raw)
    }

    fun import(ctx: Context, uri: Uri, defaultEffective: LocalDate): Result {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return Result(0, 0, 0, listOf("Couldn't open that file."))
        val table = if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) readXlsx(bytes) else readCsv(String(bytes, Charsets.UTF_8))
        val headerIdx = table.indexOfFirst { row -> row.any { it.isNotBlank() } }
        if (headerIdx < 0) return Result(0, 0, 0, listOf("The file is empty."))
        val header = table[headerIdx].map { normalizeHeader(it) }
        if ("code" !in header) return Result(0, 0, 0, listOf("No product code column found. Columns seen: ${table[headerIdx].joinToString(", ")}"))
        if ("market_rate" !in header && "commission_pct" !in header) {
            return Result(0, 0, 0, listOf("No market rate or commission column found. Columns seen: ${table[headerIdx].joinToString(", ")}"))
        }

        val w = Db.get(ctx).writableDatabase
        var market = 0
        var comm = 0
        var skipped = 0
        val notes = ArrayList<String>()
        w.beginTransaction()
        try {
            for (ri in headerIdx + 1 until table.size) {
                val row = table[ri]
                if (row.all { it.isBlank() }) continue
                val cell = { name: String -> header.indexOf(name).let { if (it >= 0 && it < row.size) row[it] else null } }
                val code = cell("code")?.trim().orEmpty()
                if (code.isEmpty()) { skipped++; continue }
                val route = cell("route")?.trim().orEmpty()
                if (route.isNotEmpty() && route.trimStart('0') != "465") { skipped++; continue }
                val eff = (dateCell(cell("effective_date")) ?: defaultEffective).toString()

                number(cell("market_rate"))?.let { mr ->
                    w.insertWithOnConflict("market_rates", null, ContentValues().apply {
                        put("code", code); put("effective_date", eff); put("market_rate", mr)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                    market++
                }
                val cp = pct(cell("commission_pct"))
                if (cp != null) {
                    val cr = pct(cell("credit_pct"))
                    w.insertWithOnConflict("comm_rates", null, ContentValues().apply {
                        put("code", code); put("effective_date", eff); put("commission_pct", cp)
                        if (cr != null) put("credit_pct", cr) else putNull("credit_pct")
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                    comm++
                } else if (cell("credit_pct")?.isNotBlank() == true) {
                    if (notes.size < 5) notes += "Row ${ri + 1} ($code): credit % without commission % was skipped."
                }
            }
            Db.get(ctx).log("Rates upload: $market market rates, $comm commission/credit rows, $skipped rows skipped")
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        // New rates apply to days already imported too.
        if (market + comm > 0) {
            val changed = Db.get(ctx).recomputePay()
            notes += "Pay refigured for imported days: $changed day" + (if (changed == 1) "" else "s") + " changed."
        }
        return Result(market, comm, skipped, notes)
    }

    fun readCsv(text: String): List<List<String>> {
        val delim = if (text.lineSequence().firstOrNull { it.isNotBlank() }?.contains('\t') == true) '\t' else ','
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ } else quoted = false
                } else sb.append(ch)
            } else when (ch) {
                '"' -> quoted = true
                delim -> { row.add(sb.toString()); sb.setLength(0) }
                '\r' -> {}
                '\n' -> { row.add(sb.toString()); sb.setLength(0); rows.add(row); row = ArrayList() }
                else -> sb.append(ch)
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); rows.add(row) }
        return rows
    }

    /** Minimal .xlsx reader: first worksheet, shared + inline strings. */
    fun readXlsx(bytes: ByteArray): List<List<String>> {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/sheet")) entries[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        val shared = ArrayList<String>()
        entries["xl/sharedStrings.xml"]?.let { xml ->
            val p = Xml.newPullParser().apply { setInput(ByteArrayInputStream(xml), "UTF-8") }
            var sb: StringBuilder? = null
            var inT = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "si" -> sb = StringBuilder()
                        "t" -> inT = true
                    }
                    XmlPullParser.TEXT -> if (inT) sb?.append(p.text)
                    XmlPullParser.END_TAG -> when (p.name) {
                        "t" -> inT = false
                        "si" -> { shared.add(sb?.toString() ?: ""); sb = null }
                    }
                }
            }
        }
        val sheetName = entries.keys.filter { it.startsWith("xl/worksheets/sheet") }.minByOrNull {
            it.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: 999
        } ?: return emptyList()
        val sheet = entries[sheetName] ?: return emptyList()
        val rows = ArrayList<List<String>>()
        val p = Xml.newPullParser().apply { setInput(ByteArrayInputStream(sheet), "UTF-8") }
        var row: ArrayList<String>? = null
        var col = 0
        var type = ""
        var value = StringBuilder()
        var inValue = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = ArrayList()
                    "c" -> {
                        type = p.getAttributeValue(null, "t") ?: ""
                        val ref = p.getAttributeValue(null, "r") ?: ""
                        col = colIndex(ref) ?: (row?.size ?: 0)
                        value = StringBuilder()
                    }
                    "v", "t" -> inValue = true
                }
                XmlPullParser.TEXT -> if (inValue) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        val r = row ?: ArrayList<String>().also { row = it }
                        val text = if (type == "s") shared.getOrNull(value.toString().trim().toIntOrNull() ?: -1) ?: "" else value.toString()
                        while (r.size < col) r.add("")
                        if (r.size == col) r.add(text) else r[col] = text
                    }
                    "row" -> { row?.let { rows.add(it) }; row = null }
                }
            }
        }
        return rows
    }

    private fun colIndex(ref: String): Int? {
        val letters = ref.takeWhile { it.isLetter() }.uppercase()
        if (letters.isEmpty()) return null
        var n = 0
        for (ch in letters) n = n * 26 + (ch - 'A' + 1)
        return n - 1
    }
}
