package com.route0465.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class DaySummary(
    val date: LocalDate,
    val pay: Double,
    val netSales: Double,
    val missingRates: String,
    val source: String,
    val importedAt: String,
)

data class DocRow(
    val dmdCode: String,
    val cusCode: String,
    val store: String,
    val isReturn: Boolean,
    val net: Double,
    val voided: Boolean,
    val voidReason: String,
)

data class LineRow(
    val dmdCode: String,
    val cusCode: String,
    val store: String,
    val code: String,
    val name: String,
    val qty: Double,
    val price: Double,
    val net: Double,
    val isReturn: Boolean,
)

data class StockRow(
    val code: String,
    val name: String,
    val initial: Double,
    val onHand: Double,
    val damage: Double,
    val reload: Double,
    val casePack: Double,
) {
    val cases: Double get() = if (casePack > 0) onHand / casePack else onHand
}

data class Product(val code: String, val name: String, val casePack: Double)
data class OrderItem(val code: String, val name: String, val position: Int, val qty: Int, val ranShort: Boolean = false, val shortBy: Int = 0)
data class Promo(val id: Long, val code: String, val name: String, val store: String, val start: String, val end: String, val deal: String)
data class Shortage(val id: Long, val date: String, val code: String, val name: String, val qty: Double, val kind: String)
data class Rate(val marketRate: Double, val commissionPct: Double, val creditPct: Double)
data class StorePhoto(val id: Long, val date: String, val cusCode: String, val store: String, val path: String, val takenAt: Long)
data class EodPdf(val date: String, val path: String, val name: String, val receivedAt: String)

fun <T> SQLiteDatabase.list(sql: String, args: Array<String> = emptyArray(), map: (Cursor) -> T): List<T> {
    val out = ArrayList<T>()
    rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(map(c)) }
    return out
}

fun Cursor.s(col: String): String = getString(getColumnIndexOrThrow(col)) ?: ""
fun Cursor.d(col: String): Double = getDouble(getColumnIndexOrThrow(col))
fun Cursor.i(col: String): Int = getInt(getColumnIndexOrThrow(col))
fun Cursor.l(col: String): Long = getLong(getColumnIndexOrThrow(col))

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "route0465.db", null, 2) {

    companion object {
        @Volatile
        private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }

        private val stamp = DateTimeFormatter.ofPattern("MM/dd/yyyy h:mm a", Locale.US)
        fun now(): String = LocalDateTime.now().format(stamp)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        listOf(
            "CREATE TABLE days(date TEXT PRIMARY KEY, imported_at TEXT, source TEXT, pay REAL, net_sales REAL, docs_kept INTEGER, docs_dropped INTEGER, voids INTEGER, missing_rates TEXT)",
            "CREATE TABLE docs(date TEXT, dmd_code TEXT, cus_code TEXT, store TEXT, is_return INTEGER, net REAL, voided INTEGER, void_reason TEXT)",
            "CREATE TABLE lines(date TEXT, dmd_code TEXT, cus_code TEXT, store TEXT, code TEXT, name TEXT, qty REAL, price REAL, net REAL, is_return INTEGER, pay REAL)",
            "CREATE TABLE stock(date TEXT, code TEXT, name TEXT, initial REAL, onhand REAL, damage REAL, reload REAL, case_pack REAL)",
            "CREATE TABLE products(code TEXT PRIMARY KEY, name TEXT, case_pack REAL)",
            "CREATE TABLE stores(cus_code TEXT PRIMARY KEY, name TEXT)",
            "CREATE TABLE market_rates(code TEXT COLLATE NOCASE, effective_date TEXT, market_rate REAL, PRIMARY KEY(code, effective_date))",
            "CREATE TABLE comm_rates(code TEXT COLLATE NOCASE, effective_date TEXT, commission_pct REAL, credit_pct REAL, PRIMARY KEY(code, effective_date))",
            "CREATE TABLE promos(id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT, name TEXT, store TEXT, start_date TEXT, end_date TEXT, deal TEXT)",
            "CREATE TABLE shortages(id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT, code TEXT, name TEXT, qty REAL, kind TEXT)",
            "CREATE TABLE order_items(code TEXT PRIMARY KEY, position INTEGER, qty INTEGER DEFAULT 0)",
            "CREATE TABLE import_log(id INTEGER PRIMARY KEY AUTOINCREMENT, at TEXT, message TEXT)",
            "CREATE INDEX idx_docs_date ON docs(date)",
            "CREATE INDEX idx_lines_date ON lines(date)",
            "CREATE INDEX idx_stock_date ON stock(date)",
        ).forEach { db.execSQL(it) }
        paperworkTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        paperworkTables(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // A restored older backup may lack these; IF NOT EXISTS keeps this harmless.
        if (!db.isReadOnly) paperworkTables(db)
    }

    private fun paperworkTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS store_photos(id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT, cus_code TEXT, store TEXT, path TEXT, taken_at INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS eod_pdfs(date TEXT PRIMARY KEY, path TEXT, name TEXT, received_at TEXT)")
        createPromoTables(db)
        db.execSQL("CREATE TABLE IF NOT EXISTS order_history(delivery TEXT, code TEXT, ordered INTEGER, suggested INTEGER, ran_short INTEGER, short_by INTEGER)")
        listOf("ran_short INTEGER DEFAULT 0", "short_by INTEGER DEFAULT 0").forEach { col ->
            val name = col.substringBefore(' ')
            val has = db.list("PRAGMA table_info(order_items)") { it.getString(1) }.contains(name)
            if (!has) db.execSQL("ALTER TABLE order_items ADD COLUMN $col")
        }
    }

    // ---------- end of day paperwork ----------

    fun photos(date: LocalDate): List<StorePhoto> = readableDatabase.list(
        "SELECT * FROM store_photos WHERE date=? ORDER BY taken_at", arrayOf(date.toString())
    ) { c -> StorePhoto(c.l("id"), c.s("date"), c.s("cus_code"), c.s("store"), c.s("path"), c.l("taken_at")) }

    fun addPhoto(date: LocalDate, cusCode: String, store: String, path: String) {
        writableDatabase.insert("store_photos", null, ContentValues().apply {
            put("date", date.toString()); put("cus_code", cusCode); put("store", store)
            put("path", path); put("taken_at", System.currentTimeMillis())
        })
    }

    fun deletePhoto(id: Long) {
        writableDatabase.delete("store_photos", "id=?", arrayOf(id.toString()))
    }

    fun eodPdf(date: LocalDate): EodPdf? = readableDatabase.list(
        "SELECT * FROM eod_pdfs WHERE date=?", arrayOf(date.toString())
    ) { c -> EodPdf(c.s("date"), c.s("path"), c.s("name"), c.s("received_at")) }.firstOrNull()

    /** Days that have any saved paperwork, newest first. */
    fun paperworkDays(): List<LocalDate> = readableDatabase.list(
        "SELECT date FROM store_photos UNION SELECT date FROM eod_pdfs ORDER BY date DESC"
    ) { LocalDate.parse(it.getString(0)) }

    fun setEodPdf(date: LocalDate, path: String, name: String) {
        writableDatabase.insertWithOnConflict("eod_pdfs", null, ContentValues().apply {
            put("date", date.toString()); put("path", path); put("name", name); put("received_at", now())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Drops paperwork rows older than [keepFrom]; the files are removed by Paperwork.prune. */
    fun prunePaperwork(keepFrom: LocalDate) {
        val a = arrayOf(keepFrom.toString())
        writableDatabase.delete("store_photos", "date<?", a)
        writableDatabase.delete("eod_pdfs", "date<?", a)
    }

    // ---------- days / sales ----------

    fun dayExists(date: LocalDate): Boolean =
        readableDatabase.list("SELECT 1 FROM days WHERE date=?", arrayOf(date.toString())) { 1 }.isNotEmpty()

    fun days(): List<DaySummary> = readableDatabase.list(
        "SELECT * FROM days ORDER BY date DESC"
    ) { c ->
        DaySummary(
            LocalDate.parse(c.s("date")), c.d("pay"), c.d("net_sales"),
            c.s("missing_rates"), c.s("source"), c.s("imported_at"),
        )
    }

    fun docs(date: LocalDate): List<DocRow> = readableDatabase.list(
        "SELECT * FROM docs WHERE date=? ORDER BY store", arrayOf(date.toString())
    ) { c ->
        DocRow(c.s("dmd_code"), c.s("cus_code"), c.s("store"), c.i("is_return") == 1, c.d("net"), c.i("voided") == 1, c.s("void_reason"))
    }

    fun lines(date: LocalDate): List<LineRow> = readableDatabase.list(
        "SELECT * FROM lines WHERE date=?", arrayOf(date.toString())
    ) { c ->
        LineRow(c.s("dmd_code"), c.s("cus_code"), c.s("store"), c.s("code"), c.s("name"), c.d("qty"), c.d("price"), c.d("net"), c.i("is_return") == 1)
    }

    /** Every sale and credit line from tickets that weren't voided, for days from..to (inclusive). */
    fun linesBetween(from: LocalDate, to: LocalDate): List<Pair<LocalDate, LineRow>> = readableDatabase.list(
        "SELECT l.* FROM lines l JOIN docs d ON d.date=l.date AND d.dmd_code=l.dmd_code " +
            "WHERE l.date>=? AND l.date<=? AND d.voided=0",
        arrayOf(from.toString(), to.toString())
    ) { c ->
        LocalDate.parse(c.s("date")) to
            LineRow(c.s("dmd_code"), c.s("cus_code"), c.s("store"), c.s("code"), c.s("name"), c.d("qty"), c.d("price"), c.d("net"), c.i("is_return") == 1)
    }

    fun voidsBetween(from: LocalDate, to: LocalDate): List<Pair<LocalDate, DocRow>> = readableDatabase.list(
        "SELECT * FROM docs WHERE date>=? AND date<=? AND voided=1 ORDER BY date DESC", arrayOf(from.toString(), to.toString())
    ) { c ->
        LocalDate.parse(c.s("date")) to
            DocRow(c.s("dmd_code"), c.s("cus_code"), c.s("store"), c.i("is_return") == 1, c.d("net"), true, c.s("void_reason"))
    }

    fun deleteDay(date: LocalDate) {
        val w = writableDatabase
        w.beginTransaction()
        try {
            val a = arrayOf(date.toString())
            w.delete("days", "date=?", a)
            w.delete("docs", "date=?", a)
            w.delete("lines", "date=?", a)
            w.delete("stock", "date=?", a)
            log("Removed the import for ${date.format(Fmt.full)}")
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    // ---------- inventory ----------

    fun stockDates(): List<LocalDate> =
        readableDatabase.list("SELECT DISTINCT date FROM stock ORDER BY date DESC") { LocalDate.parse(it.getString(0)) }

    fun stock(date: LocalDate): List<StockRow> = readableDatabase.list(
        "SELECT * FROM stock WHERE date=? ORDER BY code", arrayOf(date.toString())
    ) { c -> StockRow(c.s("code"), c.s("name"), c.d("initial"), c.d("onhand"), c.d("damage"), c.d("reload"), c.d("case_pack")) }

    // ---------- products / stores ----------

    fun productCount(): Int = readableDatabase.list("SELECT COUNT(*) FROM products") { it.getInt(0) }.first()

    fun products(search: String, limit: Int = 80): List<Product> {
        val q = "%${search.trim()}%"
        return readableDatabase.list(
            "SELECT * FROM products WHERE code LIKE ? OR name LIKE ? ORDER BY code LIMIT $limit", arrayOf(q, q)
        ) { c -> Product(c.s("code"), c.s("name"), c.d("case_pack")) }
    }

    fun productName(code: String): String =
        readableDatabase.list("SELECT name FROM products WHERE code=?", arrayOf(code)) { it.getString(0) ?: "" }.firstOrNull() ?: ""

    fun stores(): List<Pair<String, String>> =
        readableDatabase.list("SELECT * FROM stores ORDER BY name") { c -> c.s("cus_code") to c.s("name") }

    // ---------- rates ----------

    fun rateFor(code: String, date: String): Rate? {
        val db = readableDatabase
        val mr = db.list(
            "SELECT market_rate FROM market_rates WHERE code=? AND effective_date<=? ORDER BY effective_date DESC LIMIT 1",
            arrayOf(code, date)
        ) { it.getDouble(0) }.firstOrNull()
        val cm = db.list(
            "SELECT commission_pct, credit_pct FROM comm_rates WHERE code=? AND effective_date<=? ORDER BY effective_date DESC LIMIT 1",
            arrayOf(code, date)
        ) { c -> Pair(c.getDouble(0), if (c.isNull(1)) null else c.getDouble(1)) }.firstOrNull()
        if (mr == null && cm == null) return null
        return Rate(mr ?: 0.0, cm?.first ?: 0.0, cm?.second ?: defaultCreditPct(code))
    }

    fun rateSummary(): String {
        val db = readableDatabase
        val m = db.list("SELECT COUNT(DISTINCT code), MAX(effective_date) FROM market_rates") { Pair(it.getInt(0), it.getString(1)) }.first()
        val c = db.list("SELECT COUNT(DISTINCT code), MAX(effective_date) FROM comm_rates") { Pair(it.getInt(0), it.getString(1)) }.first()
        if (m.first == 0 && c.first == 0) return "No rates uploaded yet"
        fun eff(s: String?) = s?.let { runCatching { LocalDate.parse(it).format(Fmt.mdy) }.getOrNull() } ?: "—"
        return "Market rates for ${m.first} products (latest effective ${eff(m.second)}) · Commission/credit for ${c.first} products (latest effective ${eff(c.second)})"
    }

    // ---------- order guide ----------

    fun orderItems(): List<OrderItem> = readableDatabase.list(
        "SELECT o.code, o.position, o.qty, COALESCE(o.ran_short, 0) AS ran_short, COALESCE(o.short_by, 0) AS short_by, " +
            "COALESCE(p.name, '') AS name FROM order_items o LEFT JOIN products p ON p.code=o.code ORDER BY o.position"
    ) { c -> OrderItem(c.s("code"), c.s("name"), c.i("position"), c.i("qty"), c.i("ran_short") == 1, c.i("short_by")) }

    /** Ran short on the last delivery, and by how many cases (Taco-Boys "Ran short / Short by"). */
    fun setRanShort(code: String, ran: Boolean, shortBy: Int) {
        writableDatabase.update("order_items", ContentValues().apply {
            put("ran_short", if (ran) 1 else 0); put("short_by", if (ran) shortBy.coerceAtLeast(0) else 0)
        }, "code=?", arrayOf(code))
    }

    /** Keeps a copy of each emailed order (by delivery date) so suggestions can learn from it later. */
    fun saveOrderHistory(delivery: LocalDate, items: List<OrderItem>, suggested: Map<String, Int?>) {
        val w = writableDatabase
        w.beginTransaction()
        try {
            w.delete("order_history", "delivery=?", arrayOf(delivery.toString()))
            items.forEach { item ->
                w.insert("order_history", null, ContentValues().apply {
                    put("delivery", delivery.toString()); put("code", item.code); put("ordered", item.qty)
                    put("ran_short", if (item.ranShort) 1 else 0); put("short_by", item.shortBy)
                    suggested[item.code]?.let { s -> put("suggested", s) }
                })
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /** Units sold (not credits) per product per day, from tickets that weren't voided. */
    fun unitsSoldBetween(from: LocalDate, to: LocalDate): List<Triple<LocalDate, String, Double>> = readableDatabase.list(
        "SELECT l.date, l.code, SUM(l.qty) FROM lines l JOIN docs d ON d.date=l.date AND d.dmd_code=l.dmd_code " +
            "WHERE l.date>=? AND l.date<=? AND d.voided=0 AND l.is_return=0 GROUP BY l.date, l.code",
        arrayOf(from.toString(), to.toString())
    ) { c -> Triple(LocalDate.parse(c.getString(0)), c.getString(1), c.getDouble(2)) }

    fun casePacks(): Map<String, Double> =
        readableDatabase.list("SELECT code, case_pack FROM products") { c -> c.getString(0).uppercase() to c.getDouble(1) }.toMap()

    fun addOrderItem(code: String) {
        val next = readableDatabase.list("SELECT COALESCE(MAX(position), 0) + 1 FROM order_items") { it.getInt(0) }.first()
        writableDatabase.insertWithOnConflict("order_items", null, ContentValues().apply {
            put("code", code); put("position", next); put("qty", 0)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun removeOrderItem(code: String) {
        writableDatabase.delete("order_items", "code=?", arrayOf(code))
    }

    fun moveOrderItem(code: String, up: Boolean) {
        val items = orderItems().toMutableList()
        val i = items.indexOfFirst { it.code == code }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j < 0 || j >= items.size) return
        val a = items[i]; items[i] = items[j]; items[j] = a
        renumberOrder(items.map { it.code })
    }

    /** Moves a product to position [pos] (1 = top). */
    fun moveOrderItemTo(code: String, pos: Int) {
        val codes = orderItems().map { it.code }.toMutableList()
        if (!codes.remove(code)) return
        codes.add((pos - 1).coerceIn(0, codes.size), code)
        renumberOrder(codes)
    }

    private fun renumberOrder(codes: List<String>) {
        val w = writableDatabase
        w.beginTransaction()
        try {
            codes.forEachIndexed { idx, c ->
                w.update("order_items", ContentValues().apply { put("position", idx + 1) }, "code=?", arrayOf(c))
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    fun addOrderItems(codes: List<String>) {
        val w = writableDatabase
        w.beginTransaction()
        try {
            codes.forEach { addOrderItem(it) }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /** Replaces the whole order guide with [codes] in that order (quantities start at 0). */
    fun replaceOrderItems(codes: List<String>) {
        val w = writableDatabase
        w.beginTransaction()
        try {
            w.delete("order_items", null, null)
            codes.forEachIndexed { i, c ->
                w.insertWithOnConflict("order_items", null, ContentValues().apply {
                    put("code", c); put("position", i + 1); put("qty", 0)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    fun clearOrderItems() {
        writableDatabase.delete("order_items", null, null)
    }

    fun setOrderQty(code: String, qty: Int) {
        writableDatabase.update("order_items", ContentValues().apply { put("qty", qty.coerceAtLeast(0)) }, "code=?", arrayOf(code))
    }

    fun clearOrderQty() {
        writableDatabase.execSQL("UPDATE order_items SET qty=0, ran_short=0, short_by=0")
    }

    // ---------- promos / shortages ----------

    fun promos(): List<Promo> = readableDatabase.list("SELECT * FROM promos ORDER BY id DESC") { c ->
        Promo(c.l("id"), c.s("code"), c.s("name"), c.s("store"), c.s("start_date"), c.s("end_date"), c.s("deal"))
    }

    fun addPromo(code: String, name: String, store: String, start: String, end: String, deal: String) {
        writableDatabase.insert("promos", null, ContentValues().apply {
            put("code", code); put("name", name); put("store", store)
            put("start_date", start); put("end_date", end); put("deal", deal)
        })
    }

    fun deletePromo(id: Long) {
        writableDatabase.delete("promos", "id=?", arrayOf(id.toString()))
    }

    fun shortages(): List<Shortage> = readableDatabase.list("SELECT * FROM shortages ORDER BY date DESC, id DESC") { c ->
        Shortage(c.l("id"), c.s("date"), c.s("code"), c.s("name"), c.d("qty"), c.s("kind"))
    }

    fun addShortage(date: LocalDate, code: String, name: String, qty: Double, kind: String) {
        writableDatabase.insert("shortages", null, ContentValues().apply {
            put("date", date.toString()); put("code", code); put("name", name); put("qty", qty); put("kind", kind)
        })
    }

    fun deleteShortage(id: Long) {
        writableDatabase.delete("shortages", "id=?", arrayOf(id.toString()))
    }

    // ---------- log ----------

    fun log(message: String) {
        writableDatabase.insert("import_log", null, ContentValues().apply {
            put("at", now()); put("message", message)
        })
    }

    fun logEntries(limit: Int = 30): List<Pair<String, String>> =
        readableDatabase.list("SELECT at, message FROM import_log ORDER BY id DESC LIMIT $limit") { c -> c.getString(0) to c.getString(1) }
}
