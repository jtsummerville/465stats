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
import androidx.activity.compose.BackHandler
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

/** Dashboard (every range, Overview or By store) and Stores (always By store). Freight weeks have their own page. */
@Composable
private fun SalesBrowser(v: Int, storesOnly: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val today = LocalDate.now()
    var range by rememberSaveable { mutableStateOf(if (storesOnly) SalesRange.Week else SalesRange.Today) }
    var back by rememberSaveable { mutableStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    var viewPick by rememberSaveable { mutableStateOf(0) }
    val view = if (storesOnly) 1 else viewPick
    val freight = false
    val (from, to) = range.window(today, back, freight)

    // Dollars or cases. Everything underneath stays in eaches; cases = eaches ÷ case size.
    val prefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    var cases by remember { mutableStateOf(prefs.getBoolean("sales_cases", false)) }
    val packs = remember(v) { repo.casePacks() }
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

    var storeOpen by rememberSaveable { mutableStateOf("") }
    val sellers = remember(v, storeOpen, range, back) { if (storeOpen.isEmpty()) emptyMap() else sellsMostAt(repo, to) }
    BackHandler(enabled = storeOpen.isNotEmpty() && view == 1) { storeOpen = "" }

    ScreenColumn {
        // One Back: from a store to the store list, from the list to the Sales menu.
        val openName = if (view == 1) rows.firstOrNull { it.second.cusCode == storeOpen }?.second?.store else null
        if (openName != null) BackBar(openName) { storeOpen = "" }
        else BackBar(if (storesOnly) "Stores" else "Dashboard", onBack = onBack)
        // ---- Period menu, back/forward arrows, view switch ----
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RangeMenu(range) { range = it; back = 0 }
            // Tap the date to pick any day, week, month or year from a calendar.
            Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { picking = true }.padding(horizontal = 6.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(range.title(today, back, freight), fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Default.DateRange, contentDescription = "Pick a date", tint = C.Green, modifier = Modifier.size(22.dp))
                }
                Muted("${routeDays.size} route day" + (if (routeDays.size == 1) "" else "s") + " · tap the date to change it", 13)
            }
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
        // View switch (History only) and Dollars / Cases.
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!storesOnly) Segmented(listOf("Overview", "By store"), viewPick, Modifier.weight(1f), fill = true) { viewPick = it }
            Segmented(listOf("Dollars", "Cases"), if (cases) 1 else 0, Modifier.weight(1f), fill = true) { cases = it == 1; prefs.edit().putBoolean("sales_cases", cases).apply() }
            if (storesOnly) Box(Modifier.weight(1f))
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
                SalesOverview(sRows, emptyList(), range, cases, packs, oneStore = true, visits = visitBasis, store = selected)
                StoreReturns(sRows, selected, sellers)
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
    visits: VisitBasis, store: String? = null, compact: Boolean = false,
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

    var pieByStore by rememberSaveable { mutableStateOf(false) }
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
            if (weekly || oneStore) H2(if (weekly) "Net sales by week" else "Net sales by route day")
            else Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { H2(if (pieByStore) "Net sales by store" else "Net sales by route day") }
                Segmented(listOf("Day", "Store"), if (pieByStore) 1 else 0, Modifier.width(180.dp), fill = true) { pieByStore = it == 1 }
            }
            val signed = { l: List<Pair<LocalDate, LineRow>> -> l.sumOf { (if (it.second.isReturn) -1 else 1) * m.of(it.second) } }
            if (weekly) {
                val buckets = rows.groupBy { Periods.weekStart(it.first) }.toSortedMap().map { (d, l) -> d.format(Fmt.md) to signed(l) }
                BarChart(buckets, m::fmt)
            } else if (pieByStore && !oneStore) {
                PieChart(storeSlices(rows.groupBy { it.second.store }.map { (st, l) -> st to signed(l) }), m::fmt)
            } else {
                // One slice per route day (weekday), Monday first; colour belongs to the weekday, never its rank.
                val byDow = rows.groupBy { it.first.dayOfWeek }.toSortedMap()
                val slices = byDow.map { (dow, l) ->
                    val dates = l.map { it.first }.distinct().sorted()
                    val name = dow.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)
                    val label = if (dates.size == 1) "$name ${dates[0].format(Fmt.md)}" else "$name (${dates.size} days)"
                    PieSlice(label, signed(l), DAY_COLORS[dow.value - 1])
                }
                PieChart(slices, m::fmt)
            }
        }
    }

    if (compact) return
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

private class PieSlice(val label: String, val value: Double, val color: Color)

// Fixed colour per weekday, Mon..Sun (validated categorical order, colourblind-checked).
private val DAY_COLORS = listOf(
    Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100),
    Color(0xFFE87BA4), Color(0xFF008300), Color(0xFF6250D6),
)

/** Donut chart with a legend giving each slice's value and share. Negative nets get no slice but stay in the legend. */
@Composable
private fun PieChart(items: List<PieSlice>, fmt: (Double) -> String = Fmt::money) {
    if (items.isEmpty()) return
    val total = items.sumOf { max(0.0, it.value) }
    val donut = @Composable {
        Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.size(190.dp)) {
                val stroke = size.minDimension * 0.22f
                val inset = stroke / 2
                val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                if (total <= 0) {
                    drawArc(C.Line, 0f, 360f, false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    return@Canvas
                }
                val shown = items.filter { it.value > 0 }
                val gap = if (shown.size > 1) 1.6f else 0f
                var start = -90f
                shown.forEach { sl ->
                    val sweep = (sl.value / total * 360).toFloat()
                    drawArc(sl.color, start + gap / 2, max(0.5f, sweep - gap), false, topLeft, arcSize,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(fmt(items.sumOf { it.value }), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
                Text("net", fontSize = 12.sp, color = C.Muted)
            }
        }
    }
    val legend = @Composable { mod: Modifier ->
        Column(mod, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { sl ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(sl.color))
                    Text(sl.label, fontSize = 14.sp, color = C.Ink, modifier = Modifier.padding(start = 8.dp).weight(1f))
                    Text(fmt(sl.value), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
                    val pct = if (total > 0 && sl.value > 0) Math.round(sl.value / total * 100).toString() + "%" else "—"
                    Text(pct, fontSize = 13.sp, color = C.Muted, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
                }
            }
        }
    }
    if (LocalWide.current) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            donut()
            legend(Modifier.weight(1f))
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            donut()
            legend(Modifier.fillMaxWidth())
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

// ======================= Sales menu and its pages =======================

/** Sales opens on a menu; each button opens one page. */
@Composable
fun SalesScreen(v: Int) {
    var page by rememberSaveable { mutableStateOf("menu") }
    BackHandler(enabled = page != "menu") { page = "menu" }
    val toMenu = { page = "menu" }
    when (page) {
        "pay" -> PayPeriodPage(v, toMenu)
        "freight" -> FreightWeekPage(v, toMenu)
        "stores" -> SalesBrowser(v, storesOnly = true, onBack = toMenu)
        "credits" -> ReturnsPage(v, buybacks = false, onBack = toMenu)
        "buybacks" -> ReturnsPage(v, buybacks = true, onBack = toMenu)
        "history" -> SalesBrowser(v, storesOnly = false, onBack = toMenu)
        else -> SalesMenu(v) { page = it }
    }
}

private fun signedNet(l: List<Pair<LocalDate, LineRow>>) = l.sumOf { if (it.second.isReturn) -abs(it.second.net) else it.second.net }

/** "Out of date" style reasons, as XSales names them. Everything else that's a credit is still a loss. */
internal fun isOod(reason: String): Boolean {
    val r = reason.lowercase()
    return listOf("date", "ood", "expir", "stale", "code").any { it in r }
}

private fun reasonOf(l: LineRow) = l.reason.ifBlank { "Reason not recorded" }

/** Freight-week sales targets your manager gives you, saved per week (keyed by the Wednesday). */
internal object FreightTargets {
    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    fun get(ctx: android.content.Context, wed: LocalDate): Double? =
        prefs(ctx).getString("freight_target_$wed", null)?.toDoubleOrNull()
    fun set(ctx: android.content.Context, wed: LocalDate, v: Double?) {
        prefs(ctx).edit().apply { if (v == null) remove("freight_target_$wed") else putString("freight_target_$wed", v.toString()) }.apply()
    }
}

/** For each product: the stores that sold the most of it, eaches a week over the 4 weeks ending [to]. Key = code (upper case). */
private fun sellsMostAt(repo: Db, to: LocalDate): Map<String, List<Triple<String, String, Double>>> =
    repo.linesBetween(to.minusDays(27), to).filter { !it.second.isReturn }
        .groupBy { it.second.code.uppercase() }
        .mapValues { (_, l) ->
            l.groupBy { it.second.cusCode }.map { (cus, sl) -> Triple(cus, sl.first().second.store, sl.sumOf { it.second.qty } / 4.0) }
                .sortedByDescending { it.third }
        }

private fun visitBasisFor(repo: Db, from: LocalDate, to: LocalDate): VisitBasis {
    val firstImport = repo.days().minOfOrNull { it.date }
    val start = if (ChronoUnit.DAYS.between(from, to) >= 27) from else to.minusDays(27)
    val vf = if (firstImport != null && firstImport.isAfter(start)) firstImport else start
    val rows = if (!vf.isBefore(from)) null else repo.linesBetween(vf, to)
    return VisitBasis(if (vf.isAfter(to)) to else vf, to, rows)
}

@Composable
private fun BackBar(title: String, back: String = "Back", onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "‹  $back", color = C.Green, fontWeight = FontWeight.Bold, fontSize = 17.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onBack() }.padding(vertical = 10.dp, horizontal = 4.dp),
        )
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
    }
}

/** The period's dates; tap them to pick any day from a calendar and jump to the period holding it. */
@Composable
private fun DateHeader(title: String, current: Boolean, initial: LocalDate, what: String, onPick: (LocalDate) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, C.Line, RoundedCornerShape(12.dp))
            .clickable { picking = true }.padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(Icons.Default.DateRange, contentDescription = "Pick a date", tint = C.Green, modifier = Modifier.size(24.dp))
        }
        Muted("Tap to pick another $what from the calendar", 13)
    }
    if (!current) Text(
        "Back to the current $what", color = C.Green, fontWeight = FontWeight.Bold, fontSize = 15.sp,
        modifier = Modifier.clickable { onPick(today) }.padding(vertical = 4.dp),
    )
    if (picking) {
        val todayMillis = today.toEpochDay() * 86_400_000L
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial.toEpochDay() * 86_400_000L,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
                override fun isSelectableYear(year: Int) = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms -> onPick(LocalDate.ofEpochDay(ms / 86_400_000L)) }
                    picking = false
                }) { Text("Show this $what", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state, title = { Text("Any day in the $what you want", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
    }
}

private fun weekTitle(from: LocalDate) = "${from.format(Fmt.full)} – ${from.plusDays(6).format(Fmt.full)}"
private fun periodTitle(from: LocalDate) = "${from.format(Fmt.full)} – ${from.plusDays(13).format(Fmt.full)}"

@Composable
private fun MenuButton(title: String, sub: String, value: String, valueSub: String?, modifier: Modifier, valueColor: Color = C.Ink, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, C.Line, RoundedCornerShape(16.dp))
            .clickable { onClick() }.heightIn(min = 84.dp).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Text(sub, fontSize = 13.sp, color = C.Muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(value, fontWeight = FontWeight.ExtraBold, fontSize = if (value == "›") 26.sp else 19.sp, color = valueColor)
            if (valueSub != null) Text(valueSub, fontSize = 12.sp, color = C.Muted)
        }
    }
}

@Composable
private fun SalesMenu(v: Int, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val today = LocalDate.now()
    val pFrom = Periods.startOf(today)
    val fFrom = SalesRange.Week.window(today, 0, freight = true).first
    val rows = remember(v) { repo.linesBetween(minOf(pFrom, fFrom), today) }
    val pay = rows.filter { !it.first.isBefore(pFrom) }
    val fr = rows.filter { !it.first.isBefore(fFrom) }
    val gross = pay.filter { !it.second.isReturn }.sumOf { it.second.net }
    val lost = pay.filter { it.second.isReturn && !it.second.isBuyback }.sumOf { abs(it.second.net) }
    val buy = pay.filter { it.second.isBuyback }
    val target = FreightTargets.get(ctx, fFrom)
    val frNet = signedNet(fr)
    val storeCount = remember(v) { repo.stores().size }

    val buttons: List<@Composable (Modifier) -> Unit> = listOf(
        { md -> MenuButton("Dashboard", "Day · Week · Month · Year to date", "›", null, md) { onOpen("history") } },
        { md -> MenuButton("Pay period", periodTitle(pFrom), Fmt.money(signedNet(pay)), "net so far", md) { onOpen("pay") } },
        { md -> MenuButton("Freight week", weekTitle(fFrom), Fmt.money(frNet), target?.let { "of ${Fmt.money(it)} target" } ?: "no target set", md,
            valueColor = if (target != null && frNet >= target) C.Green else C.Ink) { onOpen("freight") } },
        { md -> MenuButton("Stores", "$storeCount stores", "›", null, md) { onOpen("stores") } },
        { md -> MenuButton("Credits", "this pay period", Fmt.money(lost), if (gross > 0) pct(lost / gross) + " credit rate" else null, md,
            valueColor = if (lost > 0) C.Red else C.Ink) { onOpen("credits") } },
        { md -> MenuButton("Buy backs", "this pay period", Fmt.money(buy.sumOf { abs(it.second.net) }),
            buy.map { it.second.code }.distinct().size.let { "$it item" + (if (it == 1) "" else "s") + " to move" }, md) { onOpen("buybacks") } },
    )
    ScreenColumn {
        Text("Sales", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        buttons.chunked(if (LocalWide.current) 2 else 1).forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                r.forEach { b -> b(Modifier.weight(1f)) }
                if (r.size == 1 && LocalWide.current) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PayPeriodPage(v: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val today = LocalDate.now()
    val prefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    // The 14-day pay period holding this day (two Sat–Fri weeks, same periods as Pay).
    var day by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    var cases by remember { mutableStateOf(prefs.getBoolean("sales_cases", false)) }
    val packs = remember(v) { repo.casePacks() }
    val from = Periods.startOf(LocalDate.ofEpochDay(day))
    val end = from.plusDays(13)
    val to = if (end.isAfter(today)) today else end
    val rows = remember(v, from) { repo.linesBetween(from, to) }
    val visits = remember(v, from) { visitBasisFor(repo, from, to) }
    // Same pay period a year ago (52 weeks back keeps the weekdays lined up).
    val lyFrom = from.minusWeeks(52)
    val lastYear = remember(v, from) { repo.linesBetween(lyFrom, lyFrom.plusDays(13)) }

    ScreenColumn {
        BackBar("Pay period", onBack = onBack)
        DateHeader(periodTitle(from), from == Periods.startOf(today), from, "pay period") { day = it.toEpochDay() }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Segmented(listOf("Dollars", "Cases"), if (cases) 1 else 0, Modifier.weight(1f), fill = true) { cases = it == 1; prefs.edit().putBoolean("sales_cases", cases).apply() }
            Box(Modifier.weight(1f))
        }
        if (rows.isEmpty()) Panel {
            H2("No sales in this pay period yet")
            Muted("Sales show up after each End of Day import. Tap the dates to look at an earlier pay period.")
        } else SalesOverview(rows, emptyList(), SalesRange.TwoWeeks, cases, packs, oneStore = false, visits = visits, compact = true)
        Panel {
            H2("Vs last year")
            if (lastYear.isEmpty()) Muted("Fills in once there's a year of imports (the pay period starting ${lyFrom.format(Fmt.mdy)}).", 14)
            else {
                val now = signedNet(rows)
                val then = signedNet(lastYear)
                val diff = now - then
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Muted("Same pay period last year (${periodTitle(lyFrom)})", 13)
                        Text(Fmt.money(then), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Text((if (diff >= 0) "+" else "−") + Fmt.money(abs(diff)) + if (then > 0) "  (" + (if (diff >= 0) "+" else "−") + Fmt.one(abs(diff) / then * 100) + "%)" else "",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (diff >= 0) C.Green else C.Red)
                }
            }
        }
    }
}

@Composable
private fun FreightWeekPage(v: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val today = LocalDate.now()
    var back by rememberSaveable { mutableStateOf(0) }
    val from = SalesRange.Week.window(today, back, freight = true).first
    val end = from.plusDays(6)
    val rows = remember(v, back) { repo.linesBetween(from, end) }
    val runDays = remember(v) { repo.days().map { it.date.dayOfWeek }.toSet() }
    var text by remember(back) { mutableStateOf(FreightTargets.get(ctx, from)?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "") }
    val target = text.toDoubleOrNull()
    val net = signedNet(rows)
    val past = remember(v, back) { repo.linesBetween(from.minusWeeks(8), from.minusDays(1)) }

    ScreenColumn {
        BackBar("Freight week", onBack = onBack)
        DateHeader(weekTitle(from), back == 0, from, "freight week") { back = SalesRange.Week.backFor(today, it, freight = true) }
        Panel {
            H2("Target from your manager")
            androidx.compose.material3.OutlinedTextField(
                text, { t ->
                    val clean = t.filter { it.isDigit() || it == '.' }
                    text = clean
                    FreightTargets.set(ctx, from, clean.toDoubleOrNull())
                },
                Modifier.fillMaxWidth(), singleLine = true, prefix = { Text("$") },
                label = { Text("Dollar target for this freight week") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
            )
            Muted("Saved for this week only. Each freight week keeps its own.", 13)
        }
        Panel {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Muted(if (back == 0) "Net so far" else "Net", 14)
                    Text(Fmt.money(net), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                }
                if (target != null && target > 0) Column(horizontalAlignment = Alignment.End) {
                    val left = target - net
                    Muted(if (left > 0) "Still to go" else "Over target", 14)
                    Text(Fmt.money(abs(left)), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = if (left > 0) C.Red else C.Green)
                }
            }
            if (target != null && target > 0) {
                Box(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(9.dp)).background(C.Ground)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((net / target).toFloat().coerceIn(0.01f, 1f)).background(C.Green))
                }
                val daysLeft = if (back == 0) generateSequence(today.plusDays(1)) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.count { it.dayOfWeek in runDays } else 0
                Muted("${Fmt.one(net / target * 100)}% of target" + if (back == 0) " · $daysLeft route day" + (if (daysLeft == 1) "" else "s") + " left" else "", 14)
            } else Muted("Type in the target above to see how close you are.", 14)
        }
        Panel {
            H2("By route day")
            val byDay = rows.groupBy { it.first }
            generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }
                .filter { it in byDay || it.dayOfWeek in runDays }.forEach { d ->
                    HorizontalDivider(color = C.Divider)
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(d.format(Fmt.full), fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(byDay[d]?.let { Fmt.money(signedNet(it)) } ?: "—", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (byDay[d] == null) C.Muted else C.Ink)
                    }
                }
        }
        val weeks = past.groupBy { Periods.freightWeekStart(it.first) }.toSortedMap(compareByDescending { it })
        if (weeks.isNotEmpty()) Panel {
            H2("Past freight weeks")
            weeks.forEach { (wed, l) ->
                val n = signedNet(l)
                val t = FreightTargets.get(ctx, wed)
                HorizontalDivider(color = C.Divider)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${wed.format(Fmt.md)} – ${wed.plusDays(6).format(Fmt.md)}", fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Fmt.money(n) + (t?.let { " of " + Fmt.money(it) } ?: ""), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        if (t != null) Text(if (n >= t) "✓ hit" else "✗ ${Fmt.money(t - n)} short", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (n >= t) C.Green else C.Red)
                    }
                }
            }
        }
    }
}

private val RETURN_RANGES = listOf(SalesRange.Today, SalesRange.Week, SalesRange.Month, SalesRange.Year)

/** Credits (losses) or buy backs, all stores; tap a store for item by item. */
@Composable
private fun ReturnsPage(v: Int, buybacks: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val today = LocalDate.now()
    var ri by rememberSaveable { mutableStateOf(1) }
    var back by rememberSaveable { mutableStateOf(0) }
    var storeOpen by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = storeOpen.isNotEmpty()) { storeOpen = "" }
    val range = RETURN_RANGES[ri]
    val (from, to) = range.window(today, back)
    val rows = remember(v, ri, back) { repo.linesBetween(from, to) }
    val sellers = remember(v, ri, back, buybacks, storeOpen) { if (buybacks || storeOpen.isNotEmpty()) sellsMostAt(repo, to) else emptyMap() }
    val title = if (buybacks) "Buy backs" else "Credits"
    val open = storeOpen.takeIf { s -> s.isNotEmpty() && rows.any { it.second.cusCode == s } }

    ScreenColumn {
        if (open != null) BackBar(rows.first { it.second.cusCode == open }.second.store) { storeOpen = "" }
        else BackBar(title, onBack = onBack)
        Segmented(RETURN_RANGES.map { it.label }, ri, Modifier.fillMaxWidth(), fill = true) { ri = it; back = 0 }
        DateHeader(range.title(today, back).replace("\n", " "), back == 0, from, range.unit) { back = range.backFor(today, it) }

        if (open != null) {
            StoreReturns(rows.filter { it.second.cusCode == open }, open, sellers, creditsFirst = !buybacks)
            return@ScreenColumn
        }
        val sales = rows.filter { !it.second.isReturn }
        val gross = sales.sumOf { it.second.net }
        val credits = rows.filter { it.second.isReturn && !it.second.isBuyback }
        val buys = rows.filter { it.second.isBuyback }
        val lost = credits.sumOf { abs(it.second.net) }

        if (!buybacks) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Tile("Lost to credits", Fmt.money(lost), Modifier.weight(1f), valueColor = if (lost > 0) C.Red else C.Ink)
                Tile("Credit rate", if (gross > 0) pct(lost / gross) else "—", Modifier.weight(1f), valueColor = if (gross > 0) bandColor(lost / gross) else C.Ink,
                    sub = if (gross > 0) bandLabel(lost / gross) else null)
            }
            Panel {
                H2("Top 5 out of date")
                val ood = credits.filter { isOod(it.second.reason) }
                val oodTotal = ood.sumOf { abs(it.second.net) }
                if (ood.isEmpty()) Muted(
                    if (credits.isNotEmpty() && credits.all { it.second.reason.isBlank() }) "These credits were imported before reasons were saved (build 65). Reasons show from the next import on."
                    else "No out-of-date credits in this window.", 14,
                ) else {
                    Muted("Share of all out-of-date credit dollars, all stores (${Fmt.money(oodTotal)}).", 13)
                    ood.groupBy { it.second.code }.map { (code, l) -> Triple(code, l.first().second.name, l.sumOf { abs(it.second.net) }) to l.sumOf { it.second.qty } }
                        .sortedByDescending { it.first.third }.take(5).forEach { (t, each) ->
                            HorizontalDivider(color = C.Divider)
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${t.first} · ${t.second}", fontSize = 15.sp)
                                    Muted("${Fmt.qty(each)} eaches · ${Fmt.money(t.third)}", 13)
                                }
                                Text(Fmt.one(t.third / oodTotal * 100) + "%", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Tile("Bought back", Fmt.money(buys.sumOf { abs(it.second.net) }), Modifier.weight(1f), sub = "not a loss")
                Tile("Items to move", buys.map { it.second.code }.distinct().size.toString(), Modifier.weight(1f))
            }
        }

        val list = if (buybacks) buys else credits
        Panel(pad = 0.dp) {
            Text("By store, tap one for item by item", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))
            if (list.isEmpty()) Box(Modifier.padding(start = 20.dp, bottom = 16.dp)) { Muted(if (buybacks) "No buy backs in this window." else "No credits in this window.", 14) }
            list.groupBy { it.second.cusCode }.map { (cus, l) -> Triple(cus, l, l.sumOf { abs(it.second.net) }) }.sortedByDescending { it.third }.forEach { (cus, l, total) ->
                HorizontalDivider(color = C.Divider)
                Row(
                    Modifier.fillMaxWidth().clickable { storeOpen = cus }.heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(l.first().second.store, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Muted(
                            if (buybacks) l.groupBy { it.second.code }.keys.joinToString(", ")
                            else l.groupBy { reasonOf(it.second) }.map { (r, rl) -> "$r ${Fmt.money(rl.sumOf { abs(it.second.net) })}" }.joinToString(" · "),
                            13,
                        )
                    }
                    Text(Fmt.money(total), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = if (buybacks) C.Ink else C.Red)
                    Text("  ›", fontSize = 24.sp, color = C.Muted)
                }
            }
        }
    }
}

/** One store's credits by reason (losses) and its buy backs with where each item sells best. */
@Composable
private fun StoreReturns(sRows: List<Pair<LocalDate, LineRow>>, cus: String, sellers: Map<String, List<Triple<String, String, Double>>>, creditsFirst: Boolean = true) {
    val credits = sRows.filter { it.second.isReturn && !it.second.isBuyback }
    val buys = sRows.filter { it.second.isBuyback }
    val creditPanel = @Composable {
        Panel {
            H2("Credits (losses) · " + Fmt.money(credits.sumOf { abs(it.second.net) }))
            if (credits.isEmpty()) Muted("No credits here in this window.", 14)
            credits.groupBy { reasonOf(it.second) }.map { (r, l) -> r to l }.sortedByDescending { (_, l) -> l.sumOf { abs(it.second.net) } }.forEach { (r, l) ->
                HorizontalDivider(color = C.Divider)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(r, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = if (isOod(r)) C.Red else C.Amber, modifier = Modifier.weight(1f))
                    Text(Fmt.money(l.sumOf { abs(it.second.net) }), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                }
                l.groupBy { it.second.code }.map { (code, cl) -> Triple(code, cl.first().second.name, cl.sumOf { it.second.qty }) to cl.sumOf { abs(it.second.net) } }
                    .sortedByDescending { it.second }.forEach { (t, d) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text("${t.first} · ${t.second}", fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text("${Fmt.qty(t.third)} ea · ${Fmt.money(d)}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
            }
        }
    }
    val buyPanel = @Composable {
        Panel {
            H2("Buy backs · " + Fmt.money(buys.sumOf { abs(it.second.net) }))
            Muted("Not a loss. Close to date here, so move these to a store that sells more.", 13)
            if (buys.isEmpty()) Muted("No buy backs here in this window.", 14)
            buys.groupBy { it.second.code }.map { (code, bl) -> Triple(code, bl.first().second.name, bl.sumOf { it.second.qty }) }
                .sortedByDescending { it.third }.forEach { (code, name, each) ->
                    HorizontalDivider(color = C.Divider)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text("$code · $name", fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text("${Fmt.qty(each)} ea", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    val best = sellers[code.uppercase()].orEmpty().filter { it.first != cus && it.third > 0 }.take(3)
                    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(C.GreenSoft).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(
                            if (best.isEmpty()) "No other store sold this in the last 4 weeks."
                            else "Sells most at: " + best.joinToString(" · ") { "${it.second} ${Fmt.one(it.third)}/wk" },
                            fontSize = 13.sp, color = C.Ink,
                        )
                    }
                }
        }
    }
    if (creditsFirst) { creditPanel(); buyPanel() } else { buyPanel(); creditPanel() }
}

/** Store slices for the pie: the 7 biggest stores, then everyone else as Other. */
private fun storeSlices(byStore: List<Pair<String, Double>>): List<PieSlice> {
    val sorted = byStore.sortedByDescending { it.second }
    val colors = DAY_COLORS + Color(0xFFE34948)
    if (sorted.size <= colors.size) return sorted.mapIndexed { i, (n, v) -> PieSlice(n, v, colors[i]) }
    val top = sorted.take(7).mapIndexed { i, (n, v) -> PieSlice(n, v, colors[i]) }
    val rest = sorted.drop(7)
    return top + PieSlice("Other (${rest.size} stores)", rest.sumOf { it.second }, Color(0xFF9AA39D))
}
