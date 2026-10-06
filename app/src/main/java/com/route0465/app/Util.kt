package com.route0465.app

import android.content.Context
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

object Fmt {
    private val nf = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    fun money(v: Double): String = (if (v < -0.004) "−$" else "$") + nf.format(abs(v))
    fun one(v: Double): String = String.format(Locale.US, "%.1f", v)
    fun qty(v: Double): String = if (v == floor(v)) v.toLong().toString() else one(v)

    val day: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MM/dd", Locale.US)
    val full: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MM/dd/yyyy", Locale.US)
    val md: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd", Locale.US)
    val mdy: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US)
    val fileMd: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd", Locale.US)
}

/** 14-day pay periods (two Saturday–Friday weeks) counted from 07/11/2026. */
object Periods {
    val ANCHOR: LocalDate = LocalDate.of(2026, 7, 11)

    fun startOf(d: LocalDate): LocalDate {
        val days = ChronoUnit.DAYS.between(ANCHOR, d)
        val idx = Math.floorDiv(days, 14L)
        return ANCHOR.plusDays(idx * 14)
    }

    /** Saturday that starts the Sat–Fri week holding [d]. */
    fun weekStart(d: LocalDate): LocalDate {
        val back = (d.dayOfWeek.value - DayOfWeek.SATURDAY.value + 7) % 7
        return d.minusDays(back.toLong())
    }
}

/** Order deadline: Wednesday by midnight (today if today is Wednesday). */
fun nextOrderDue(today: LocalDate = LocalDate.now()): LocalDate {
    val ahead = (DayOfWeek.WEDNESDAY.value - today.dayOfWeek.value + 7) % 7
    return today.plusDays(ahead.toLong())
}

/** Reads yyyy-MM-dd[ ...], M/d, M/d/yy, M/d/yyyy. Returns null if it can't. */
fun parseLooseDate(s: String?, today: LocalDate = LocalDate.now()): LocalDate? {
    if (s == null) return null
    val t = s.trim()
    if (t.isEmpty()) return null
    if (t.length >= 10 && t[4] == '-' && t[7] == '-') {
        try {
            return LocalDate.parse(t.substring(0, 10))
        } catch (_: Exception) {
        }
    }
    val parts = t.substringBefore(' ').split('/', '-', '.').map { it.trim() }.filter { it.isNotEmpty() }
    try {
        if (parts.size >= 2) {
            val m = parts[0].toInt()
            val d = parts[1].toInt()
            var y = if (parts.size >= 3) parts[2].take(4).toInt() else today.year
            if (y < 100) y += 2000
            return LocalDate.of(y, m, d)
        }
    } catch (_: Exception) {
    }
    return null
}

/** Credit % default when a rate upload leaves it blank: GV 2933–2938 = 10%, others 16%. */
fun defaultCreditPct(code: String): Double =
    if (code.trim() in setOf("2933", "2934", "2935", "2936", "2937", "2938")) 0.10 else 0.16

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun xsalesPath(ctx: Context): String = sp(ctx).getString("xsales_path", "") ?: ""
    fun setXsalesPath(ctx: Context, v: String) = sp(ctx).edit().putString("xsales_path", v.trim()).apply()

    fun orderEmail(ctx: Context): String = sp(ctx).getString("order_email", "") ?: ""
    fun setOrderEmail(ctx: Context, v: String) = sp(ctx).edit().putString("order_email", v.trim()).apply()

    fun auto(ctx: Context): Boolean = sp(ctx).getBoolean("auto_import", true)
    fun setAuto(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("auto_import", v).apply()

    fun lastAftModified(ctx: Context): Long = sp(ctx).getLong("last_aft_modified", 0L)
    fun setLastAftModified(ctx: Context, v: Long) = sp(ctx).edit().putLong("last_aft_modified", v).apply()

    fun lastCheck(ctx: Context): String = sp(ctx).getString("last_check", "") ?: ""
    fun setLastCheck(ctx: Context, v: String) = sp(ctx).edit().putString("last_check", v).apply()
}
