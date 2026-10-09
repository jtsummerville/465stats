package com.route0465.app

import android.content.ContentValues
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import java.io.File
import java.time.LocalDate
import kotlin.math.abs

sealed class ImportResult {
    data class Imported(
        val date: LocalDate,
        val source: String,
        val pay: Double,
        val netSales: Double,
        val stores: Int,
        val saleLines: Int,
        val creditLines: Int,
        val voids: Int,
        val ticketsRead: Int,
        val ticketsDropped: Int,
        val linesDropped: Int,
        val stockItems: Int,
        val missingRates: List<String>,
    ) : ImportResult()

    data class AlreadyImported(val date: LocalDate) : ImportResult()
    data class WrongDate(val file: String, val fileDate: LocalDate?, val today: LocalDate, val beforeIsToday: Boolean) : ImportResult()
    data class Failed(val message: String) : ImportResult()
}

/**
 * Reads XSales's End of Day backups. Rules:
 *  - never touches the originals: each file is copied into this app's cache and the COPY is opened read-only;
 *  - the whole backup must carry today's business date (general.gnlDate / jrnCode) or nothing is imported;
 *  - inside it, every ticket must itself be dated today, anything else is dropped and counted;
 *  - a day that's already imported is never added to or overwritten.
 */
object XSales {
    const val AFT = "BCKAftMain.sqlite"
    const val BEF = "BCKBefMain.sqlite"
    const val AFT_BASICS = "BCKAftBasics.sqlite"
    const val BEF_BASICS = "BCKBefBasics.sqlite"

    data class Backup(val name: String, val exists: Boolean, val date: LocalDate?, val modified: Long, val note: String)

    /** Android 11+: "All files access". Android 10 and older: the storage permission. */
    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** True for the live XSales data folder ("Ole prd"), false for the stale test one ("Ole"). Matches "prd" or "prod". */
    fun isProd(dir: File): Boolean = Regex("(^|[^a-z])pro?d([^a-z]|$)").containsMatchIn(dir.path.lowercase())

    /**
     * Every folder in shared storage (up to 5 levels deep) that holds BCKAftMain.sqlite,
     * production folders first. There can be more than one: "Ole" is a stale test copy, "Ole prod" is live.
     */
    fun candidates(): List<File> {
        val out = ArrayList<File>()
        fun walk(d: File, depth: Int) {
            val kids = d.listFiles() ?: return
            if (kids.any { it.isFile && it.name == AFT }) out += d
            if (depth >= 5) return
            kids.filter { it.isDirectory && !it.name.startsWith(".") && !it.name.equals("Android", ignoreCase = true) }
                .forEach { walk(it, depth + 1) }
        }
        walk(Environment.getExternalStorageDirectory(), 0)
        return out.sortedWith(compareByDescending<File> { isProd(it) }.thenBy { it.path.lowercase() })
    }

    /** The folder chosen in Setup; otherwise the production ("Ole prd") folder; never a non-prod folder when a prod one exists. */
    fun folder(ctx: Context): File? {
        val p = Prefs.xsalesPath(ctx)
        if (p.isNotBlank()) {
            val f = File(p)
            return if (f.isDirectory) f else null
        }
        return candidates().firstOrNull()
    }

    private fun snapshot(ctx: Context, src: File): File {
        val dir = File(ctx.cacheDir, "xsales").apply { mkdirs() }
        val dst = File(dir, src.name)
        src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        return dst
    }

    private fun openReadOnly(f: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    /** Every row as a lower-cased column-name map, so schema details never crash the import. */
    private fun rows(db: SQLiteDatabase, sql: String): List<Map<String, String?>> {
        val out = ArrayList<Map<String, String?>>()
        db.rawQuery(sql, null).use { c ->
            val names = c.columnNames.map { it.lowercase() }
            while (c.moveToNext()) {
                val m = HashMap<String, String?>(names.size)
                for (i in names.indices) {
                    m[names[i]] = try { c.getString(i) } catch (_: Exception) { null }
                }
                out.add(m)
            }
        }
        return out
    }

    private fun Map<String, String?>.g(col: String): String? = this[col.lowercase()]?.trim()
    private fun Map<String, String?>.num(col: String): Double = g(col)?.toDoubleOrNull() ?: 0.0

    private fun hasTable(db: SQLiteDatabase, name: String): Boolean =
        db.list("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? COLLATE NOCASE", arrayOf(name)) { 1 }.isNotEmpty()

    private fun businessDate(db: SQLiteDatabase): LocalDate? {
        if (!hasTable(db, "general")) return null
        val r = rows(db, "SELECT * FROM general LIMIT 1").firstOrNull() ?: return null
        parseLooseDate(r.g("gnlDate"))?.let { return it }
        val j = r.g("jrnCode") ?: return null
        if (j.length >= 12) {
            try {
                return LocalDate.parse(j.substring(4, 8) + "-" + j.substring(8, 10) + "-" + j.substring(10, 12))
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun inspect(ctx: Context, name: String, dir: File? = null): Backup {
        if (!hasAccess(ctx)) return Backup(name, false, null, 0, "File access not allowed yet")
        val folder = dir ?: folder(ctx) ?: return Backup(name, false, null, 0, "XSales folder not found")
        val f = File(folder, name)
        if (!f.isFile) return Backup(name, false, null, 0, "Not in ${folder.path}")
        return try {
            val copy = snapshot(ctx, f)
            openReadOnly(copy).use { db -> Backup(name, true, businessDate(db), f.lastModified(), "") }
        } catch (e: Exception) {
            Backup(name, true, null, f.lastModified(), "Couldn't read it: ${e.message}")
        }
    }

    private class Doc(
        val code: String, val cus: String, val store: String, val isReturn: Boolean,
        val net: Double, val voided: Boolean, val reason: String, val isBuyback: Boolean = false,
    )

    private class Ln(val doc: Doc, val code: String, val name: String, val qty: Double, val price: Double, val net: Double, val isReturn: Boolean, val isBuyback: Boolean = false)

    @Synchronized
    fun runImport(ctx: Context, useBefore: Boolean = false): ImportResult {
        val repo = Db.get(ctx)
        val today = LocalDate.now()
        if (!hasAccess(ctx)) return ImportResult.Failed("File access isn't allowed yet. Tap \"Allow file access\" on Home.")
        val folder = folder(ctx) ?: return ImportResult.Failed("Couldn't find the XSales folder. Set it in Setup.")
        if (repo.dayExists(today)) return ImportResult.AlreadyImported(today)

        val mainName = if (useBefore) BEF else AFT
        val src = File(folder, mainName)
        if (!src.isFile) return ImportResult.Failed("$mainName isn't in ${folder.path}.")
        val copy = try {
            snapshot(ctx, src)
        } catch (e: Exception) {
            return ImportResult.Failed("Couldn't copy $mainName: ${e.message}")
        }
        // Keep the matching Basics backup with it (copied, never opened for writing).
        File(folder, if (useBefore) BEF_BASICS else AFT_BASICS).takeIf { it.isFile }?.let { runCatching { snapshot(ctx, it) } }

        val x = try {
            openReadOnly(copy)
        } catch (e: Exception) {
            return ImportResult.Failed("Couldn't open $mainName: ${e.message}")
        }

        x.use { db ->
            // 1) The whole backup must be today's.
            val fileDate = businessDate(db)
            if (fileDate != today) {
                val befToday = !useBefore && inspect(ctx, BEF, folder).date == today
                repo.log("Not imported: ${src.path} is dated ${fileDate?.format(Fmt.mdy) ?: "unknown"}, today is ${today.format(Fmt.mdy)}")
                return ImportResult.WrongDate(src.path, fileDate, today, befToday)
            }
            if (!hasTable(db, "demandUp")) return ImportResult.Failed("$mainName has no tickets table (demandUp).")

            val storeNames = HashMap<String, String>()
            if (hasTable(db, "customer")) rows(db, "SELECT * FROM customer").forEach { r ->
                val c = r.g("cusCode") ?: return@forEach
                storeNames[c] = r.g("cusName")?.takeIf { it.isNotEmpty() } ?: c
            }
            val reasons = HashMap<String, String>()
            if (hasTable(db, "reason")) rows(db, "SELECT * FROM reason").forEach { r ->
                val c = r.g("reaCode") ?: return@forEach
                reasons[c] = r.g("reaName") ?: c
            }
            val prodNames = HashMap<String, String>()
            if (hasTable(db, "product")) rows(db, "SELECT * FROM product").forEach { r ->
                val c = r.g("proCode") ?: return@forEach
                prodNames[c] = r.g("proName") ?: ""
            }
            val casePack = HashMap<String, Double>()
            if (hasTable(db, "productUnit")) rows(db, "SELECT * FROM productUnit").forEach { r ->
                val c = r.g("proCode") ?: return@forEach
                val m = r.g("pruMultiplyBy")?.toDoubleOrNull() ?: return@forEach
                if (m > (casePack[c] ?: 0.0)) casePack[c] = m
            }

            // 2) Every ticket must itself be dated today.
            val kept = ArrayList<Doc>()
            var read = 0
            var dropped = 0
            rows(db, "SELECT * FROM demandUp").forEach { r ->
                read++
                val d = parseLooseDate(r.g("dmdInvoiceDate")) ?: parseLooseDate(r.g("dmdStartTime"))
                if (d != today) {
                    dropped++; return@forEach
                }
                val code = r.g("dmdCode") ?: run { dropped++; return@forEach }
                val cus = r.g("cusCode") ?: ""
                // Doc codes (Taco-Boys, confirmed on real route reports): invdst = invoice, rtndst = credit, grt = buy back.
                val docCode = (r.g("docCode") ?: "").lowercase()
                val isBuy = docCode.startsWith("grt")
                val isRet = docCode.startsWith("rtn") || isBuy
                val cancel = r.g("dmdCancelInvoice") ?: "0"
                val voided = cancel == "1" || cancel.equals("true", ignoreCase = true)
                val reason = r.g("reaCancelInvoice")?.let { reasons[it] ?: it } ?: ""
                val raw = r.num("dmdNetAmount")
                kept += Doc(code, cus, storeNames[cus] ?: cus, isRet, if (isRet) -abs(raw) else raw, voided, reason, isBuy)
            }

            val byCode = kept.associateBy { it.code }
            val lines = ArrayList<Ln>()
            var linesDropped = 0
            if (hasTable(db, "invoiceProductUp")) rows(db, "SELECT * FROM invoiceProductUp").forEach { r ->
                val doc = byCode[r.g("dmdCode")] ?: run { linesDropped++; return@forEach }
                val pc = r.g("proCode") ?: ""
                val name = prodNames[pc] ?: pc
                val q = r.num("iprQuantity")
                val price = r.num("iprPrice")
                val net = r.g("iprNetAmount")?.toDoubleOrNull() ?: (q * price)
                if (q != 0.0) lines += Ln(doc, pc, name, abs(q), price, if (doc.isReturn) -abs(net) else net, doc.isReturn, doc.isBuyback)
                val dq = r.num("iprDamageReturnQuantity")
                if (!doc.isReturn && dq > 0) {
                    val da = r.g("iprDamageReturnAmount")?.toDoubleOrNull() ?: (dq * price)
                    lines += Ln(doc, pc, name, dq, price, -abs(da), true)
                }
            }

            // Pay: the Taco-Boys commission math, per line, voided tickets excluded.
            val dayStr = today.toString()
            val missing = sortedSetOf<String>()
            val linePay = lines.map { l ->
                if (l.doc.voided) 0.0 else {
                    val rate = repo.rateFor(l.code, dayStr)
                    if (rate == null || rate.marketRate == 0.0 || rate.commissionPct == 0.0) {
                        missing += l.code; 0.0
                    } else {
                        repo.linePay(l.qty, rate, l.isReturn, l.isBuyback)
                    }
                }
            }
            val pay = linePay.sum()
            val netSales = kept.filter { !it.voided }.sumOf { it.net }

            // Truck stock has no per-row date: only taken because the whole file passed the date check.
            val stock = if (hasTable(db, "routeStock")) rows(db, "SELECT * FROM routeStock").mapNotNull { r ->
                val pc = r.g("proCode") ?: return@mapNotNull null
                StockRow(pc, prodNames[pc] ?: pc, r.num("rstInitial"), r.num("rstOnHands"), r.num("rstDamage"), r.num("rstReload"), casePack[pc] ?: 1.0)
            } else emptyList()

            val w = repo.writableDatabase
            w.beginTransaction()
            try {
                if (repo.dayExists(today)) return ImportResult.AlreadyImported(today)
                val source = if (useBefore) "${src.path} (before End of Day)" else src.path
                w.insert("days", null, ContentValues().apply {
                    put("date", dayStr); put("imported_at", Db.now()); put("source", source)
                    put("pay", pay); put("net_sales", netSales); put("docs_kept", kept.size)
                    put("docs_dropped", dropped); put("voids", kept.count { it.voided })
                    put("missing_rates", missing.joinToString(", "))
                })
                kept.forEach { d ->
                    w.insert("docs", null, ContentValues().apply {
                        put("date", dayStr); put("dmd_code", d.code); put("cus_code", d.cus); put("store", d.store)
                        put("is_return", if (d.isReturn) 1 else 0); put("net", d.net); put("buyback", if (d.isBuyback) 1 else 0)
                        put("voided", if (d.voided) 1 else 0); put("void_reason", d.reason)
                    })
                }
                lines.forEachIndexed { i, l ->
                    w.insert("lines", null, ContentValues().apply {
                        put("date", dayStr); put("dmd_code", l.doc.code); put("cus_code", l.doc.cus); put("store", l.doc.store)
                        put("code", l.code); put("name", l.name); put("qty", l.qty); put("price", l.price); put("net", l.net)
                        put("is_return", if (l.isReturn) 1 else 0); put("buyback", if (l.isBuyback) 1 else 0); put("pay", linePay[i])
                    })
                }
                stock.forEach { s ->
                    w.insert("stock", null, ContentValues().apply {
                        put("date", dayStr); put("code", s.code); put("name", s.name); put("initial", s.initial)
                        put("onhand", s.onHand); put("damage", s.damage); put("reload", s.reload); put("case_pack", s.casePack)
                    })
                }
                // New stores and products are added; ones you changed or removed by hand are left alone.
                prodNames.forEach { (code, name) ->
                    val cp = casePack[code] ?: 1.0
                    w.insertWithOnConflict("products", null, ContentValues().apply {
                        put("code", code); put("name", name); put("case_pack", cp)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                    w.execSQL("UPDATE products SET name=?, case_pack=? WHERE code=? AND COALESCE(manual,0)=0", arrayOf<Any>(name, cp, code))
                }
                storeNames.forEach { (code, name) ->
                    w.insertWithOnConflict("stores", null, ContentValues().apply {
                        put("cus_code", code); put("name", name)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                    w.execSQL("UPDATE stores SET name=? WHERE cus_code=? AND COALESCE(manual,0)=0", arrayOf<Any>(name, code))
                }
                repo.log(
                    "Imported ${today.format(Fmt.mdy)} from $source: read $read tickets, kept ${kept.size}, " +
                        "dropped $dropped from other dates; ${lines.size} lines (ignored $linesDropped from other tickets); " +
                        "${stock.size} stock items" + if (missing.isNotEmpty()) "; no rates for ${missing.joinToString(", ")}" else ""
                )
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }

            runCatching { DataBackup.auto(ctx) }
            return ImportResult.Imported(
                date = today, source = src.path, pay = pay, netSales = netSales,
                stores = kept.filter { !it.voided }.map { it.cus }.distinct().size,
                saleLines = lines.count { !it.isReturn && !it.doc.voided },
                creditLines = lines.count { it.isReturn && !it.doc.voided },
                voids = kept.count { it.voided }, ticketsRead = read, ticketsDropped = dropped,
                linesDropped = linesDropped, stockItems = stock.size, missingRates = missing.toList(),
            )
        }
    }
}
