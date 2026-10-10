@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.route0465.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.Icons
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max

/** The time windows the Sales screen offers. Back = how many periods before the current one. */
enum class SalesRange(val label: String, val unit: String) {
    Today("Day", "day"), Week("Week", "week"), TwoWeeks("2 weeks", "2 weeks"), FourWeeks("4 weeks", "4 weeks"), Month("Month", "month"), Year("Year", "year");

    /** [from, to] for this window, [back] periods ago; the current period ends today. */
    fun window(today: LocalDate, back: Int, freight: Boolean = false): Pair<LocalDate, LocalDate> {
        val ws = if (freight && this == Week) Periods.freightWeekStart(today) else Periods.weekStart(today)
        val (from, to) = when (this) {
            Today -> today.minusDays(back.toLong()).let { it to it }
            Week -> ws.minusDays(7L * back).let { it to it.plusDays(6) }
            TwoWeeks -> ws.minusDays(7 + 14L * back).let { it to it.plusDays(13) }
            FourWeeks -> ws.minusDays(21 + 28L * back).let { it to it.plusDays(27) }
            Month -> today.withDayOfMonth(1).minusMonths(back.toLong()).let { it to it.plusMonths(1).minusDays(1) }
            Year -> today.withDayOfYear(1).minusYears(back.toLong()).let { it to it.plusYears(1).minusDays(1) }
        }
        return from to (if (to.isAfter(today)) today else to)
    }

    /** How many periods back the window holding [date] is (0 = the current one). */
    fun backFor(today: LocalDate, date: LocalDate, freight: Boolean = false): Int {
        val d = if (date.isAfter(today)) today else date
        val ws = Periods.weekStart(today)
        if (freight && this == Week) return (ChronoUnit.DAYS.between(Periods.freightWeekStart(d), Periods.freightWeekStart(today)) / 7).toInt().coerceAtLeast(0)
        val end = ws.plusDays(6)
        val n = when (this) {
            Today -> ChronoUnit.DAYS.between(d, today)
            Week -> ChronoUnit.DAYS.between(Periods.weekStart(d), ws) / 7
            TwoWeeks -> ChronoUnit.DAYS.between(d, end) / 14
            FourWeeks -> ChronoUnit.DAYS.between(d, end) / 28
            Month -> ChronoUnit.MONTHS.between(d.withDayOfMonth(1), today.withDayOfMonth(1))
            Year -> (today.year - d.year).toLong()
        }
        return n.toInt().coerceAtLeast(0)
    }

    fun title(today: LocalDate, back: Int, freight: Boolean = false): String {
        val (from, to) = window(today, back, freight)
        return when (this) {
            Today -> if (back == 0) "Today · ${from.format(Fmt.full)}" else from.format(Fmt.full)
            Month -> from.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.US)) + if (back == 0) " (to date)" else ""
            Year -> "${from.year}" + if (back == 0) " (year to date)" else ""
            else -> "${from.format(Fmt.full)} –\n${to.format(Fmt.full)}"
        }
    }
}

// Credit rate bands, same as Taco-Boys: on target to 1.5%, check to 2.5%, fix it beyond.
private const val TARGET = 0.015
private const val ELEVATED = 0.025

private fun bandColor(rate: Double): Color = when {
    rate <= TARGET -> Color(0xFF2E7D4F)
    rate <= ELEVATED -> Color(0xFFB26A00)
    else -> C.Red
}

private fun bandLabel(rate: Double): String = when {
    rate <= TARGET -> "On target"
    rate <= ELEVATED -> "Check"
    else -> "Fix it"
}

private fun pct(r: Double) = String.format(java.util.Locale.US, "%.2f%%", r * 100)

@Composable
fun SalesScreen(v: Int) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val today = LocalDate.now()
    var range by rememberSaveable { mutableStateOf(SalesRange.Today) }
    var back by rememberSaveable { mutableStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    var view by rememberSaveable { mutableStateOf(0) }
    // Freight week: Wed–Tue, matching new freight deliveries. Only applies to the Week view.
    var freightPref by remember { mutableStateOf(ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getBoolean("sales_freight_week", false)) }
    val freight = freightPref && range == SalesRange.Week
    val (from, to) = range.window(today, back, freight)

    // Dollars or cases. Everything underneath stays in eaches; cases = eaches ÷ case size.
    val prefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    var cases by remember { mutableStateOf(prefs.getBoolean("sales_cases", false)) }
    val packs = remember(v) { repo.casePacks() }
    var storeOpen by rememberSaveable { mutableStateOf("") }
    val rows = remember(v, range, back, freight) { repo.linesBetween(from, to) }
    // Visit averages need a few weeks: the window itself, or the 4 weeks ending with it when it's shorter.
    val firstImport = remember(v) { repo.days().minOfOrNull { it.date } }
    val visitStart = if (java.time.temporal.ChronoUnit.DAYS.between(from, to) >= 27) from else to.minusDays(27)
    // Never count days before the first import: there's no data for them, so they'd water the average down.
    val visitFrom = if (firstImport != null && firstImport.isAfter(visitStart)) firstImport else visitStart
    val visitRows = remember(v, range, back, freight) { if (!visitFrom.isBefore(from)) null else repo.linesBetween(visitFrom, to) }
    val visitBasis = VisitBasis(if (visitFrom.isAfter(to)) to else visitFrom, to, visitRows)
    val promoWeeks = remember(v, range, back, freight) { promosByWeek(repo.promosV2(), bannersFor(repo.stores().map { it.second }).toSet(), from, to) }
    val sales = rows.filter { !it.second.isReturn }
    val credits = rows.filter { it.second.isReturn }
    val gross = sales.sumOf { it.second.net }
    val creditDollars = credits.sumOf { abs(it.second.net) }
    val net = gross - creditDollars
    val routeDays = rows.map { it.first }.distinct().sorted()
    // Like Taco-Boys, buy backs are the company's return: they count against net sales but not the credit rate.
    val rate = if (gross > 0) credits.filter { !it.second.isBuyback }.sumOf { abs(it.second.net) } / gross else 0.0

    ScreenColumn {
        // ---- Period menu, back/forward arrows, view switch ----
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RangeMenu(range) { range = it; back = 0 }
            SalesArrow(left = true) { back += 1 }
            // Tap the date to pick any day, week, month or year from a calendar.
            Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { picking = true }.padding(horizontal = 6.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(range.title(today, back, freight), fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Default.DateRange, contentDescription = "Pick a date", tint = C.Green, modifier = Modifier.size(22.dp))
                }
                Muted("${routeDays.size} route day" + (if (routeDays.size == 1) "" else "s") + " · tap the date to change it", 13)
            }
            SalesArrow(left = false, enabled = back > 0) { back -= 1 }
        }
        if (back > 0) Text(
            "Back to the current ${range.unit}", color = C.Green, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            modifier = Modifier.clickable { back = 0 }.padding(vertical = 6.dp),
        )
        if (picking) {
            val todayMillis = today.toEpochDay() * 86_400_000L
            val state = rememberDatePickerState(
                initialSelectedDateMillis = from.toEpochDay() * 86_400_000L,
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
                    override fun isSelectableYear(year: Int) = year <= today.year
                },
            )
            DatePickerDialog(
                onDismissRequest = { picking = false },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { ms -> back = range.backFor(today, LocalDate.ofEpochDay(ms / 86_400_000L), freight) }
                        picking = false
                    }) { Text("Show this ${range.unit}", fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
            ) {
                DatePicker(state = state, title = { Text("Any day in the ${range.unit} you want", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) })
            }
        }
        // View switch and freight-week toggle, lined up with the tiles below (Net sales, Gross sales).
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Segmented(listOf("Overview", "By store"), view, Modifier.weight(1f), fill = true) { view = it }
            val weekView = range == SalesRange.Week
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (freightPref && weekView) C.GreenSoft else Color.White)
                    .border(1.dp, C.Line, RoundedCornerShape(12.dp))
                    .clickable(enabled = weekView) {
                        freightPref = !freightPref; back = 0
                        ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("sales_freight_week", freightPref).apply()
                    }
                    .heightIn(min = 54.dp).padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Freight week", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (weekView) C.Ink else C.Muted)
                    Text(if (weekView) "Wed – Tue" else "Week view only", fontSize = 12.sp, color = C.Muted)
                }
                androidx.compose.material3.Switch(
                    checked = freightPref && weekView, enabled = weekView,
                    onCheckedChange = { on ->
                        freightPref = on; back = 0
                        ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("sales_freight_week", on).apply()
                    },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = C.Green),
                )
            }
            if (LocalWide.current) {
                Segmented(listOf("Dollars", "Cases"), if (cases) 1 else 0, Modifier.weight(1f), fill = true) { cases = it == 1; prefs.edit().putBoolean("sales_cases", cases).apply() }
                Box(Modifier.weight(1f))
            }
        }
        if (!LocalWide.current) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Segmented(listOf("Dollars", "Cases"), if (cases) 1 else 0, Modifier.weight(1f), fill = true) { cases = it == 1; prefs.edit().putBoolean("sales_cases", cases).apply() }
            Box(Modifier.weight(1f))
        }

        if (rows.isEmpty()) {
            Panel {
                H2(if (range == SalesRange.Today && back == 0) "Today isn't imported yet" else "No sales in this window")
                Muted(if (range == SalesRange.Today && back == 0) "Sales show up after today's End of Day import. Use the ‹ arrow to look at earlier days." else "Use the arrows to look at another ${range.unit}, or pick a different window.")
            }
        } else if (view == 0) {
            SalesOverview(rows, promoWeeks, range, cases, packs, oneStore = false, visits = visitBasis)
        } else {
            val m = Measure(cases, packs)
            val selected = storeOpen.takeIf { sel -> sel.isNotEmpty() && rows.any { it.second.cusCode == sel } }
            if (selected != null) {
                // ---- One store: the same overview, just for this store, then its products ----
                val sRows = rows.filter { it.second.cusCode == selected }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        "‹  All stores", color = C.Green, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        modifier = Modifier.clickable { storeOpen = "" }.padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                    Text(sRows.first().second.store, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                }
                SalesOverview(sRows, emptyList(), range, cases, packs, oneStore = true, visits = visitBasis, store = selected)
                Panel {
                    H2("Products")
                    // Cases view: sold and credited in cases. Dollars view: eaches and dollars for each.
                    val w = if (cases) listOf(0.8f, 3f, 1.1f, 1.1f) else listOf(0.8f, 3f, 0.9f, 1.1f, 0.9f, 1.1f)
                    TableRow(
                        if (cases) listOf("Code", "Product", "Sold", "Credited") else listOf("Code", "Product", "Sold", "Sales", "Cred.", "Credit $"),
                        w, header = true, endAligned = if (cases) setOf(2, 3) else setOf(2, 3, 4, 5),
                    )
                    sRows.map { it.second }.groupBy { it.code }.map { (code, l) ->
                        val sold = l.filter { !it.isReturn }
                        val cr = l.filter { it.isReturn }
                        val crQty = if (cr.isEmpty()) "—" else m.qty(code, cr.sumOf { it.qty })
                        (if (cases) listOf(code, l.first().name, m.qty(code, sold.sumOf { it.qty }), crQty)
                        else listOf(
                            code, l.first().name, Fmt.qty(sold.sumOf { it.qty }), Fmt.money(sold.sumOf { it.net }),
                            crQty, if (cr.isEmpty()) "—" else Fmt.money(-cr.sumOf { abs(it.net) }),
                        )) to sold.sumOf { m.of(it) }
                    }.sortedByDescending { it.second }.forEach { (cells, _) ->
                        HorizontalDivider(color = C.Divider)
                        TableRow(cells, w, bold = setOf(2, 3), endAligned = if (cases) setOf(2, 3) else setOf(2, 3, 4, 5))
                    }
                }
            } else {
                // ---- By store: one row per store; tap for that store's own overview ----
                val stores = rows.groupBy { it.second.cusCode }.map { (cus, l) ->
                    val g = l.filter { !it.second.isReturn }.sumOf { m.of(it.second) }
                    val c = l.filter { it.second.isReturn }.sumOf { m.of(it.second) }
                    val cNoBuy = l.filter { it.second.isReturn && !it.second.isBuyback }.sumOf { m.of(it.second) }
                    StoreTotal(cus, l.first().second.store, g, c, cNoBuy, l.map { it.first }.distinct().size)
                }.sortedByDescending { it.gross - it.credits }
                Panel(pad = 0.dp) {
                    Text("Stores, tap one for its own overview", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))
                    stores.forEach { st ->
                        val r = if (st.gross > 0) st.creditsNoBuyback / st.gross else 0.0
                        HorizontalDivider(color = C.Divider)
                        Row(
                            Modifier.fillMaxWidth().clickable { storeOpen = st.cus }.heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(st.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Muted("${st.visits} visit" + (if (st.visits == 1) "" else "s") + " · credits ${m.fmt(st.credits)} · " + pct(r), 13)
                            }
                            Text(m.fmt(st.gross - st.credits), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                            Text("  ›", fontSize = 24.sp, color = C.Muted)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Visit averages. A visit is a store on a day with at least one ticket that wasn't voided.
 * Averaged over the window, or over the 4 weeks ending with it when the window is shorter ([rows] = null means the window itself).
 */
class VisitBasis(val from: LocalDate, val to: LocalDate, private val rows: List<Pair<LocalDate, LineRow>>?) {
    private val days get() = (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1).coerceAtLeast(1)
    fun count(windowRows: List<Pair<LocalDate, LineRow>>, store: String?): Int =
        (rows ?: windowRows).filter { store == null || it.second.cusCode == store }.map { it.first to it.second.cusCode }.distinct().size
    fun perWeek(windowRows: List<Pair<LocalDate, LineRow>>, store: String?) = count(windowRows, store) * 7.0 / days
    fun perMonth(windowRows: List<Pair<LocalDate, LineRow>>, store: String?) = count(windowRows, store) * 30.44 / days
    fun note(windowRows: List<Pair<LocalDate, LineRow>>, store: String?): String {
        val n = count(windowRows, store)
        return "$n visit" + (if (n == 1) "" else "s") + if (rows != null) " in the last 4 weeks" else " in this window"
    }
}

/** Turns a line into dollars or cases. Cases = eaches ÷ the product's case size (1 when it's unknown). */
private class Measure(val cases: Boolean, val packs: Map<String, Double>) {
    fun pack(code: String) = packs[code.uppercase()]?.takeIf { it > 0 } ?: 1.0
    fun of(l: LineRow): Double = if (cases) l.qty / pack(l.code) else abs(l.net)
    fun fmt(v: Double): String = if (cases) Fmt.one(v) + " cs" else Fmt.money(v)
    /** A quantity in eaches, shown as cases when that's on. */
    fun qty(code: String, eaches: Double): String = if (cases) Fmt.one(eaches / pack(code)) + " cs" else Fmt.qty(eaches)
}

/** The overview: tiles, chart, credit rate, credits by product, credit lines and promos. Used for all stores or one. */
@Composable
private fun SalesOverview(
    rows: List<Pair<LocalDate, LineRow>>, promoWeeks: List<Pair<LocalDate, List<PromoWeekLine>>>,
    range: SalesRange, cases: Boolean, packs: Map<String, Double>, oneStore: Boolean,
    visits: VisitBasis, store: String? = null,
) {
    val m = Measure(cases, packs)
    val sales = rows.filter { !it.second.isReturn }
    val credits = rows.filter { it.second.isReturn }
    val gross = sales.sumOf { m.of(it.second) }
    val creditTotal = credits.sumOf { m.of(it.second) }
    val net = gross - creditTotal
    // Like Taco-Boys, buy backs are the company's return: they count against net sales but not the credit rate.
    val creditOnly = credits.filter { !it.second.isBuyback }.sumOf { m.of(it.second) }
    val rate = if (gross > 0) creditOnly / gross else 0.0

    val tiles: List<@Composable (Modifier) -> Unit> = listOf(
        { md -> Tile("Net sales", m.fmt(net), md) },
        { md -> Tile("Gross sales", m.fmt(gross), md) },
        { md -> Tile("Credits", m.fmt(-creditTotal), md, valueColor = if (creditTotal > 0) C.Red else C.Ink) },
        if (oneStore) ({ md -> Tile("Visits", rows.map { it.first }.distinct().size.toString(), md, sub = "in this window") })
        else ({ md -> Tile("Stores", sales.map { it.second.cusCode }.distinct().size.toString(), md) }),
        { md -> Tile("Visits a week", Fmt.one(visits.perWeek(rows, store)), md, sub = visits.note(rows, store)) },
        { md -> Tile("Visits a month", Fmt.one(visits.perMonth(rows, store)), md, sub = if (oneStore) "average" else "all stores, average") },
    )
    tiles.chunked(if (LocalWide.current) 4 else 2).forEach { r ->
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { r.forEach { t -> t(Modifier.weight(1f)) } }
    }

    Panel {
        if (range == SalesRange.Today && !oneStore) {
            H2("Net sales by store")
            val byStore = rows.groupBy { it.second.store }.map { (st, l) -> st to l.sumOf { (if (it.second.isReturn) -1 else 1) * m.of(it.second) } }.sortedByDescending { it.second }
            BarList(byStore, m::fmt)
        } else {
            val weekly = range == SalesRange.Year
            H2(if (weekly) "Net sales by week" else "Net sales by route day")
            val signed = { l: List<Pair<LocalDate, LineRow>> -> l.sumOf { (if (it.second.isReturn) -1 else 1) * m.of(it.second) } }
            val buckets = if (weekly) rows.groupBy { Periods.weekStart(it.first) }.toSortedMap().map { (d, l) -> d.format(Fmt.md) to signed(l) }
                else rows.groupBy { it.first }.toSortedMap().map { (d, l) -> d.format(Fmt.md) to signed(l) }
            BarChart(buckets, m::fmt)
        }
    }

    // Credit rate, Taco-Boys style (dollars, or units in Cases view like Taco-Boys' unit credit rate)
    Panel {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                H2("Credit rate")
                Muted("Credits as a share of gross sales: ${m.fmt(creditOnly)} of ${m.fmt(gross)}" + if (creditOnly != creditTotal) " (buy backs left out)." else ".", 14)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(pct(rate), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = bandColor(rate))
                Text(bandLabel(rate), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = bandColor(rate))
            }
        }
        RateMeter(rate)

        if (range != SalesRange.Today && range != SalesRange.Week) {
            HorizontalDivider(color = C.Divider)
            Text("Week to week", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            val weeks = rows.groupBy { Periods.weekStart(it.first) }.toSortedMap().map { (wk, l) ->
                val g = l.filter { !it.second.isReturn }.sumOf { m.of(it.second) }
                val c = l.filter { it.second.isReturn && !it.second.isBuyback }.sumOf { m.of(it.second) }
                Triple("${wk.format(Fmt.md)} – ${wk.plusDays(6).format(Fmt.md)}", if (g > 0) c / g else 0.0, c)
            }
            val top = max(weeks.maxOfOrNull { it.second } ?: 0.0, ELEVATED)
            weeks.forEach { (label, r, _) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 14.sp, modifier = Modifier.width(118.dp))
                    Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(4.dp)).background(C.Ground)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((r / top).toFloat().coerceIn(0.01f, 1f)).background(bandColor(r)))
                    }
                    Text(pct(r), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = bandColor(r), textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
                }
            }
        }
    }

    // Credits added up by product over the whole window (buy backs listed apart).
    if (credits.isNotEmpty()) Panel {
        H2("Credits by product")
        Muted("Added up over this window, biggest first.", 13)
        val w = if (cases) listOf(3.2f, 0.8f, 1.1f) else listOf(3.2f, 0.8f, 1.1f, 1.2f)
        TableRow(if (cases) listOf("Product", "Times", "Cases") else listOf("Product", "Times", "Eaches", "Credit $"), w, header = true, endAligned = setOf(1, 2, 3))
        credits.groupBy { it.second.code to it.second.isBuyback }.map { (key, l) ->
            val (code, buy) = key
            val each = l.sumOf { it.second.qty }
            val dollars = l.sumOf { abs(it.second.net) }
            val label = "$code · ${l.first().second.name}" + if (buy) " (buy back)" else ""
            (if (cases) listOf(label, l.size.toString(), m.qty(code, each).removeSuffix(" cs"))
            else listOf(label, l.size.toString(), Fmt.qty(each), Fmt.money(-dollars))) to l.sumOf { m.of(it.second) }
        }.sortedByDescending { it.second }.forEach { (cells, _) ->
            HorizontalDivider(color = C.Divider)
            TableRow(cells, w, bold = setOf(2, 3), endAligned = setOf(1, 2, 3), small = setOf(0), color = C.Ink)
        }

        HorizontalDivider(color = C.Divider)
        var showCredits by remember { mutableStateOf(false) }
        Expander("Every credit line", "${credits.size}", showCredits) { showCredits = !showCredits }
        if (showCredits) {
            credits.sortedByDescending { it.first }.forEach { (d, l) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(
                        "${d.format(Fmt.md)} · " + (if (oneStore) "" else "${l.store} · ") + "${l.code} ${l.name} · ${m.qty(l.code, l.qty)}" + if (l.isBuyback) " · buy back" else "",
                        fontSize = 14.sp, modifier = Modifier.weight(1f),
                    )
                    Text(if (cases) "" else Fmt.money(-abs(l.net)), fontSize = 14.sp, color = C.Red, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (promoWeeks.isNotEmpty()) {
        Panel {
            H2("Promotions in this window")
            Muted("What was on sale each Sat–Fri week, for the banners on your route.", 13)
            promoWeeks.forEach { (wk, lines) ->
                HorizontalDivider(color = C.Divider)
                Text("Week of ${wk.format(Fmt.full)}", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                lines.forEach { l ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text("${l.banner} — ${l.type}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${l.coverage} · ${l.codes.joinToString(", ")}", fontSize = 14.sp, color = C.Muted)
                    }
                }
            }
        }
    }
}

private class StoreTotal(val cus: String, val name: String, val gross: Double, val credits: Double, val creditsNoBuyback: Double, val visits: Int)

@Composable
private fun RangeMenu(range: SalesRange, onPick: (SalesRange) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).background(C.Ink).clickable { open = true }
                .heightIn(min = 52.dp).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(range.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("  ▾", color = Color.White, fontSize = 17.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SalesRange.entries.forEach { r ->
                DropdownMenuItem(
                    text = { Text(r.label, fontSize = 17.sp, fontWeight = if (r == range) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { onPick(r); open = false },
                )
            }
        }
    }
}

@Composable
private fun Expander(title: String, count: String, open: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Muted(count, 13)
        Text(if (open) "  ▴" else "  ▾", fontSize = 18.sp, color = C.Muted)
    }
}

/** Horizontal bars, one per row: label, bar, value. */
@Composable
private fun BarList(items: List<Pair<String, Double>>, fmt: (Double) -> String) {
    val top = items.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
    items.forEach { (label, value) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1.3f))
            Box(Modifier.weight(1.7f).height(20.dp).clip(RoundedCornerShape(4.dp)).background(C.Ground)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((value / top).toFloat().coerceIn(0.01f, 1f)).background(C.Green))
            }
            Text(fmt(value), fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(96.dp))
        }
    }
}

/** Vertical bars over time with the total for the biggest bar and every label that fits. */
@Composable
private fun BarChart(items: List<Pair<String, Double>>, fmt: (Double) -> String = Fmt::money) {
    if (items.isEmpty()) return
    val top = items.maxOf { it.second }.takeIf { it > 0 } ?: 1.0
    val every = max(1, (items.size + 13) / 14) // at most ~14 labels
    Muted("Tallest bar ${fmt(top)} · average ${fmt(items.sumOf { it.second } / items.size)}", 13)
    Row(Modifier.fillMaxWidth().height(170.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (_, value) ->
            Box(
                Modifier.weight(1f).fillMaxHeight((value / top).toFloat().coerceIn(0.02f, 1f))
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(C.Green),
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { i, (label, _) ->
            Text(if (i % every == 0) label else "", fontSize = 11.sp, color = C.Muted, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
        }
    }
}

/** The Taco-Boys credit meter: green / amber / red zones, a target line at 1.5%, a marker at the rate. */
@Composable
private fun RateMeter(rate: Double) {
    val scale = max(0.04, rate * 1.25)
    BoxWithConstraints(Modifier.fillMaxWidth().height(34.dp)) {
        val w = maxWidth
        Row(Modifier.fillMaxWidth().height(22.dp).align(Alignment.Center).clip(RoundedCornerShape(6.dp))) {
            Box(Modifier.weight((TARGET / scale).toFloat()).fillMaxHeight().background(Color(0xFFDCEFE3)))
            Box(Modifier.weight(((ELEVATED - TARGET) / scale).toFloat()).fillMaxHeight().background(Color(0xFFF8E7C6)))
            Box(Modifier.weight(((scale - ELEVATED) / scale).toFloat()).fillMaxHeight().background(Color(0xFFF6D6D1)))
        }
        // target line
        Box(Modifier.offset(x = w * (TARGET / scale).toFloat()).width(2.dp).fillMaxHeight().background(C.Muted))
        // marker
        Box(
            Modifier.offset(x = w * (rate / scale).toFloat().coerceIn(0f, 1f) - 4.dp).width(8.dp).fillMaxHeight()
                .clip(RoundedCornerShape(3.dp)).background(C.Ink).border(1.dp, Color.White, RoundedCornerShape(3.dp)),
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
        val w = maxWidth
        (0..(scale * 100).toInt()).forEach { t ->
            val x = w * (t / 100.0 / scale).toFloat()
            Text("$t%", fontSize = 11.sp, color = C.Muted, modifier = Modifier.offset(x = if (t == 0) x else x - 10.dp))
        }
    }
}

@Composable
private fun SalesArrow(left: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.width(56.dp).height(56.dp).clip(shape).background(if (enabled) Color.White else C.Ground)
            .border(2.dp, if (enabled) C.Green else C.Line, shape).clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (left) "‹" else "›", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = if (enabled) C.Green else C.Line)
    }
}
