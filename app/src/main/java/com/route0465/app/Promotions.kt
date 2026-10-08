package com.route0465.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Promotions, the Taco-Boys way (promos.py), minus regions (one route):
 * a promo is a banner + a sale type + several product codes + a start and end date.
 * The order guide tags a product only when the sale lands on the delivery that order covers.
 */
data class PromoV2(val id: Long, val banner: String, val type: String, val items: List<String>, val start: LocalDate, val end: LocalDate)

/** One promo line behind an order-guide tag. */
data class PromoLine(val banner: String, val type: String, val dates: String, val state: String) {
    val cue: String get() = if (state == "short") "light bump" else "stock up"
}

data class PromoTag(val label: String, val state: String, val lines: List<PromoLine>)

const val ALL_BANNERS = "All banners"
val DEFAULT_SALE_TYPES = listOf("BOGO", "Roller", "Mega Sale", "Rollback")

private val SHORT = DateTimeFormatter.ofPattern("MMM d", Locale.US)

fun promoDates(start: LocalDate, end: LocalDate): String = "${start.format(SHORT)} – ${end.format(SHORT)}, ${end.year}"

/** Creates the promo table, and carries over promos saved the old way (one product per row). */
fun createPromoTables(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS promos2(id INTEGER PRIMARY KEY AUTOINCREMENT, banner TEXT, type TEXT, items TEXT, start_date TEXT, end_date TEXT)")
    val hasNew = db.list("SELECT COUNT(*) FROM promos2") { it.getInt(0) }.first() > 0
    val hasOld = db.list("SELECT name FROM sqlite_master WHERE type='table' AND name='promos'") { 1 }.isNotEmpty()
    if (hasNew || !hasOld) return
    db.list("SELECT * FROM promos") { c -> listOf(c.s("code"), c.s("store"), c.s("start_date"), c.s("end_date"), c.s("deal")) }.forEach { (code, store, s, e, deal) ->
        val today = LocalDate.now()
        val start = parseLooseDate(s, today) ?: return@forEach
        val end = parseLooseDate(e, today) ?: start
        db.insert("promos2", null, ContentValues().apply {
            put("banner", bannerLabel(store)); put("type", deal.ifBlank { "PROMO" }); put("items", code)
            put("start_date", start.toString()); put("end_date", end.toString())
        })
    }
}

fun Db.promosV2(): List<PromoV2> = readableDatabase.list("SELECT * FROM promos2 ORDER BY start_date DESC, id DESC") { c ->
    PromoV2(
        c.l("id"), c.s("banner"), c.s("type"),
        c.s("items").split(',').map { it.trim() }.filter { it.isNotEmpty() },
        LocalDate.parse(c.s("start_date")), LocalDate.parse(c.s("end_date")),
    )
}

fun Db.addPromoV2(banner: String, type: String, items: List<String>, start: LocalDate, end: LocalDate) {
    writableDatabase.insert("promos2", null, ContentValues().apply {
        put("banner", banner); put("type", type); put("items", items.joinToString(","))
        put("start_date", start.toString()); put("end_date", end.toString())
    })
}

fun Db.deletePromoV2(id: Long) {
    writableDatabase.delete("promos2", "id=?", arrayOf(id.toString()))
}

/** Sale types: Taco-Boys' starter list plus any you add. */
object SaleTypes {
    fun all(ctx: Context): List<String> {
        val extra = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("sale_types", "") ?: ""
        return (DEFAULT_SALE_TYPES + extra.split('|').map { it.trim() }.filter { it.isNotEmpty() }).distinct()
    }

    fun add(ctx: Context, name: String) {
        val n = name.trim()
        if (n.isEmpty() || n in all(ctx)) return
        val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val extra = (sp.getString("sale_types", "") ?: "").split('|').filter { it.isNotBlank() } + n
        sp.edit().putString("sale_types", extra.joinToString("|")).apply()
    }
}

/**
 * The order-to-order cycle for the order due next (route 0465: order due Wednesday by midnight,
 * Ole orders Thursday, delivery the Tuesday after; the cycle runs to the next Tuesday delivery).
 * Returns [delivery, nextDelivery).
 */
fun orderCycle(today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> {
    val oleOrder = nextOrderDue(today).plusDays(1)
    var delivery = oleOrder.plusDays(1)
    while (delivery.dayOfWeek != DayOfWeek.TUESDAY) delivery = delivery.plusDays(1)
    return delivery to delivery.plusDays(7)
}

/**
 * Same rule as Taco-Boys classify_for_cycle, with the window [winStart, winEnd):
 *  "normal" (stock up)  — the sale is still running at the next delivery: start < winEnd <= end;
 *  "short" (light bump) — the sale starts and ends inside this one cycle;
 *  null                 — not tagged; a sale ending partway through the cycle is a dying sale.
 * Dates here are whole days, end inclusive, compared the same way as the ISO strings in Taco-Boys.
 */
fun classifyForCycle(start: LocalDate, end: LocalDate, winStart: LocalDate, winEnd: LocalDate): String? {
    if (start.isAfter(end)) return null
    if (start.isBefore(winEnd) && !end.isBefore(winEnd)) return "normal"
    if (!start.isBefore(winStart) && start.isBefore(winEnd) && end.isBefore(winEnd)) return "short"
    return null
}

/** Product code → tag for the coming order, counting only banners this route serves. */
fun promoTags(promos: List<PromoV2>, routeBanners: Set<String>, cycle: Pair<LocalDate, LocalDate>): Map<String, PromoTag> {
    val byCode = HashMap<String, MutableList<PromoLine>>()
    for (p in promos) {
        if (p.banner != ALL_BANNERS && routeBanners.isNotEmpty() && p.banner !in routeBanners) continue
        val state = classifyForCycle(p.start, p.end, cycle.first, cycle.second) ?: continue
        for (code in p.items) {
            byCode.getOrPut(code.uppercase()) { ArrayList() }.add(PromoLine(p.banner, p.type.ifBlank { "PROMO" }, promoDates(p.start, p.end), state))
        }
    }
    return byCode.mapValues { (_, lines) ->
        val state = if (lines.any { it.state == "normal" }) "normal" else "short"
        PromoTag(if (lines.size == 1) lines[0].type else "PROMO ×${lines.size}", state, lines)
    }
}
