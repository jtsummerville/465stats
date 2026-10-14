package com.route0465.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.time.LocalDate
import kotlin.math.ceil

/**
 * Inventory Audit, from Taco-Boys audit_store.py: what the driver physically counted against one
 * import's truck stock, plus the exact tablet correction that count produces.
 *
 * Correction rule (Taco-Boys correction_for): the Ole tablet can only ADD EACHES and SUBTRACT WHOLE
 * CASES, never add cases. So an overage is all eaches to add; a shortage is "subtract K cases, add back
 * the remainder as eaches". Figured on total pieces, from the pack size, every time it's saved: what
 * the screen showed is never trusted into the record.
 *
 * A blank row is NOT COUNTED, never zero: "counted and found none" and "never got to it" are different.
 * 465stats keeps one audit per import date, saved as it's typed (no submit: it's the driver's own tool,
 * not a manager's queue). The "keyed into XSales" date is stamped only when the box is ticked.
 */
data class Correction(val sys: Int, val cnt: Int, val diff: Int, val eachesAdd: Int, val casesSub: Int)

data class AuditLine(val code: String, val cases: Int, val eaches: Int)

data class Audit(val date: LocalDate, val lines: Map<String, AuditLine>, val keyed: LocalDate?, val updated: String)

object InventoryAudit {
    fun correction(pack: Int, sysCases: Int, sysEaches: Int, cntCases: Int, cntEaches: Int): Correction {
        if (pack <= 0) return Correction(0, 0, 0, 0, 0)
        val sys = sysCases * pack + sysEaches
        val cnt = cntCases * pack + cntEaches
        val diff = cnt - sys
        return when {
            diff == 0 -> Correction(sys, cnt, 0, 0, 0)
            diff < 0 -> {
                val short = -diff
                val k = ceil(short / pack.toDouble()).toInt()
                Correction(sys, cnt, diff, k * pack - short, k)
            }
            else -> Correction(sys, cnt, diff, diff, 0)
        }
    }

    /** XSales keeps on-hand in eaches; the tablet shows whole cases plus the leftover eaches. */
    fun split(eaches: Double, pack: Int): Pair<Int, Int> {
        val e = eaches.toInt()
        return if (pack <= 0) 0 to e else e / pack to e % pack
    }

    fun tables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS audit_lines(date TEXT, code TEXT, cases INTEGER, eaches INTEGER, PRIMARY KEY(date, code))")
        db.execSQL("CREATE TABLE IF NOT EXISTS audits(date TEXT PRIMARY KEY, keyed TEXT, updated TEXT)")
    }

    fun get(repo: Db, date: LocalDate): Audit? {
        val head = repo.readableDatabase.list("SELECT * FROM audits WHERE date=?", arrayOf(date.toString())) { c ->
            (c.s("keyed").takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }) to c.s("updated")
        }.firstOrNull()
        val lines = repo.readableDatabase.list("SELECT * FROM audit_lines WHERE date=?", arrayOf(date.toString())) { c ->
            AuditLine(c.s("code").uppercase(), c.getInt(c.getColumnIndexOrThrow("cases")), c.getInt(c.getColumnIndexOrThrow("eaches")))
        }.associateBy { it.code }
        if (head == null && lines.isEmpty()) return null
        return Audit(date, lines, head?.first, head?.second ?: "")
    }

    /** Dates that have a count, newest first. */
    fun dates(repo: Db): List<LocalDate> =
        repo.readableDatabase.list("SELECT DISTINCT date FROM audit_lines ORDER BY date DESC") { LocalDate.parse(it.getString(0)) }

    private fun touch(db: SQLiteDatabase, date: LocalDate) {
        val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy h:mm a", java.util.Locale.US))
        db.execSQL("INSERT INTO audits(date, keyed, updated) VALUES(?, '', ?) ON CONFLICT(date) DO UPDATE SET updated=excluded.updated", arrayOf(date.toString(), now))
    }

    /** Saves one row's count; null for both clears it (not counted). */
    fun setLine(repo: Db, date: LocalDate, code: String, cases: Int?, eaches: Int?) {
        val db = repo.writableDatabase
        if (cases == null && eaches == null) db.execSQL("DELETE FROM audit_lines WHERE date=? AND code=?", arrayOf(date.toString(), code.uppercase()))
        else db.insertWithOnConflict("audit_lines", null, ContentValues().apply {
            put("date", date.toString()); put("code", code.uppercase()); put("cases", cases ?: 0); put("eaches", eaches ?: 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        touch(db, date)
    }

    fun setKeyed(repo: Db, date: LocalDate, keyed: LocalDate?) {
        val db = repo.writableDatabase
        touch(db, date)
        db.execSQL("UPDATE audits SET keyed=? WHERE date=?", arrayOf(keyed?.toString() ?: "", date.toString()))
    }

    fun clear(repo: Db, date: LocalDate) {
        val db = repo.writableDatabase
        db.execSQL("DELETE FROM audit_lines WHERE date=?", arrayOf(date.toString()))
        db.execSQL("DELETE FROM audits WHERE date=?", arrayOf(date.toString()))
    }
}
