package com.route0465.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate

/**
 * Expenses. Separate from Taco-Boys; nothing here touches pay math or imports.
 *
 * Recurring: a weekly amount taken out of each Sat–Fri pay week from its start week until it's stopped
 * (stop week = the last week it still comes out). It comes out whether or not the week had route days.
 * Stored day pay never changes: the Pay page subtracts at display time. Stopping or editing applies
 * from the current week forward, so a locked period never changes.
 *
 * One-time: a dated log with a category, never taken out of pay. The chart adds them up by category.
 */
data class RecurringExpense(val id: Long, val name: String, val weekly: Double, val start: LocalDate, val stop: LocalDate?) {
    /** Does it come out of the Sat–Fri week starting [weekStart]? */
    fun appliesTo(weekStart: LocalDate) = !weekStart.isBefore(start) && (stop == null || !weekStart.isAfter(stop))
}

data class OneTimeExpense(val id: Long, val date: LocalDate, val category: String, val note: String, val amount: Double)

object Expenses {
    val DEFAULT_CATEGORIES = listOf("Gas", "Supplies", "Tolls", "Repairs", "Other")

    fun tables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS expense_recurring(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, weekly REAL, start TEXT, stop TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS expense_once(id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT, category TEXT, note TEXT, amount REAL)")
    }

    fun recurring(repo: Db): List<RecurringExpense> = repo.readableDatabase.list("SELECT * FROM expense_recurring ORDER BY stop IS NOT NULL, start DESC, id DESC") { c ->
        RecurringExpense(c.l("id"), c.s("name"), c.d("weekly"), LocalDate.parse(c.s("start")), c.s("stop").takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) })
    }

    fun addRecurring(repo: Db, name: String, weekly: Double, start: LocalDate) {
        repo.writableDatabase.insert("expense_recurring", null, ContentValues().apply {
            put("name", name.trim()); put("weekly", weekly); put("start", Periods.weekStart(start).toString()); putNull("stop")
        })
    }

    /** Stops it after the week holding [lastWeekDay]: that week still pays it, later weeks don't. */
    fun stopRecurring(repo: Db, id: Long, lastWeekDay: LocalDate) {
        repo.writableDatabase.execSQL("UPDATE expense_recurring SET stop=? WHERE id=?", arrayOf(Periods.weekStart(lastWeekDay).toString(), id))
    }

    fun removeRecurring(repo: Db, id: Long) = repo.writableDatabase.execSQL("DELETE FROM expense_recurring WHERE id=?", arrayOf(id))

    /** Recurring lines that come out of the Sat–Fri week starting [weekStart]. */
    fun forWeek(all: List<RecurringExpense>, weekStart: LocalDate) = all.filter { it.appliesTo(weekStart) }

    fun oneTime(repo: Db, from: LocalDate, to: LocalDate): List<OneTimeExpense> = repo.readableDatabase.list(
        "SELECT * FROM expense_once WHERE date>=? AND date<=? ORDER BY date DESC, id DESC", arrayOf(from.toString(), to.toString())
    ) { c -> OneTimeExpense(c.l("id"), LocalDate.parse(c.s("date")), c.s("category"), c.s("note"), c.d("amount")) }

    fun addOneTime(repo: Db, date: LocalDate, category: String, note: String, amount: Double) {
        repo.writableDatabase.insert("expense_once", null, ContentValues().apply {
            put("date", date.toString()); put("category", category.trim().ifBlank { "Other" }); put("note", note.trim()); put("amount", amount)
        })
    }

    fun removeOneTime(repo: Db, id: Long) = repo.writableDatabase.execSQL("DELETE FROM expense_once WHERE id=?", arrayOf(id))

    /** Every category used so far plus the defaults, in a steady order. */
    fun categories(repo: Db): List<String> {
        val used = repo.readableDatabase.list("SELECT DISTINCT category FROM expense_once") { it.getString(0) }
        return (DEFAULT_CATEGORIES + used).distinct()
    }

    // ---------------------------------------------------------------- PDF and email

    fun fileName(start: LocalDate) = "Expenses_${start.format(Fmt.fileMd)}.pdf"

    /** The report for one 14-day pay period: recurring with its total, one-time with its total, then both together. */
    fun buildPdf(ctx: Context, start: LocalDate): File {
        val repo = Db.get(ctx)
        val end = start.plusDays(13)
        val rec = recurring(repo)
        val once = oneTime(repo, start, end).sortedBy { it.date }
        val doc = PdfDocument()
        val body = Paint().apply { textSize = 11f; isAntiAlias = true }
        val bold = Paint(body).apply { typeface = Typeface.DEFAULT_BOLD }
        val right = Paint(body).apply { textAlign = Paint.Align.RIGHT }
        val boldRight = Paint(bold).apply { textAlign = Paint.Align.RIGHT }
        val title = Paint(bold).apply { textSize = 18f }
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(612, 792, pageNo).create())
        var c = page.canvas
        var y = 56f
        val amtX = 560f
        fun line(h: Float = 16f) { y += h; if (y > 750f) { doc.finishPage(page); pageNo++; page = doc.startPage(PdfDocument.PageInfo.Builder(612, 792, pageNo).create()); c = page.canvas; y = 56f } }

        c.drawText("Expense report", 48f, y, title); line(22f)
        c.drawText("Route ${Prefs.routeNumber(ctx)}", 48f, y, body); line()
        c.drawText("Pay period ${start.format(Fmt.full)} – ${end.format(Fmt.full)}", 48f, y, body); line(26f)

        c.drawText("RECURRING (taken out of pay)", 48f, y, bold); line()
        var recTotal = 0.0
        val weeks = listOf(start, start.plusDays(7))
        rec.forEach { r ->
            val n = weeks.count { r.appliesTo(it) }
            if (n == 0) return@forEach
            val amt = r.weekly * n
            recTotal += amt
            c.drawText(r.name, 60f, y, body)
            c.drawText("${Fmt.money(r.weekly)} / wk  x $n week" + (if (n == 1) "" else "s"), 300f, y, body)
            c.drawText(Fmt.money(amt), amtX, y, right); line()
        }
        if (recTotal == 0.0) { c.drawText("None", 60f, y, body); line() }
        c.drawText("Recurring total", 60f, y, bold); c.drawText(Fmt.money(recTotal), amtX, y, boldRight); line(26f)

        c.drawText("ONE-TIME (log only, not taken out of pay)", 48f, y, bold); line()
        once.forEach { o ->
            c.drawText(o.date.format(Fmt.mdy), 60f, y, body)
            c.drawText(o.category, 140f, y, body)
            c.drawText(o.note.take(40), 230f, y, body)
            c.drawText(Fmt.money(o.amount), amtX, y, right); line()
        }
        if (once.isEmpty()) { c.drawText("None", 60f, y, body); line() }
        val onceTotal = once.sumOf { it.amount }
        c.drawText("One-time total", 60f, y, bold); c.drawText(Fmt.money(onceTotal), amtX, y, boldRight); line()
        if (once.isNotEmpty()) {
            val byCat = once.groupBy { it.category }.map { (k, l) -> k to l.sumOf { it.amount } }.sortedByDescending { it.second }
            c.drawText(byCat.joinToString(" · ") { "${it.first} ${Fmt.money(it.second)}" }, 72f, y, body); line()
        }
        line(10f)
        c.drawText("TOTAL EXPENSES (recurring + one-time)", 48f, y, bold)
        c.drawText(Fmt.money(recTotal + onceTotal), amtX, y, boldRight)
        doc.finishPage(page)

        val dir = File(ctx.cacheDir, "pdf").apply { mkdirs() }
        val f = File(dir, fileName(start))
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    fun email(ctx: Context, f: File, to: String, start: LocalDate) {
        val uri = FileProvider.getUriForFile(ctx, "com.route0465.app.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            if (to.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, "Expense report")
            putExtra(Intent.EXTRA_TEXT, "Expense report for the pay period ${start.format(Fmt.mdy)} – ${start.plusDays(13).format(Fmt.mdy)}, attached.")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Email expense report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
