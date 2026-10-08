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
import kotlin.math.abs
import kotlin.math.max

/** The time windows the Sales screen offers, newest data always ending today. */
enum class SalesRange(val label: String) {
    Today("Today"), Week("This week"), TwoWeeks("2 weeks"), FourWeeks("4 weeks"), Month("This month"), Year("Year to date");

    fun start(today: LocalDate): LocalDate = when (this) {
        Today -> today
        Week -> Periods.weekStart(today)
        TwoWeeks -> Periods.weekStart(today).minusDays(7)
        FourWeeks -> Periods.weekStart(today).minusDays(21)
        Month -> today.withDayOfMonth(1)
        Year -> today.withDayOfYear(1)
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
    var view by rememberSaveable { mutableStateOf(0) }
    val from = range.start(today)

    val rows = remember(v, range) { repo.linesBetween(from, today) }
    val voids = remember(v, range) { repo.voidsBetween(from, today) }
    val sales = rows.filter { !it.second.isReturn }
    val credits = rows.filter { it.second.isReturn }
    val gross = sales.sumOf { it.second.net }
    val creditDollars = credits.sumOf { abs(it.second.net) }
    val net = gross - creditDollars
    val routeDays = rows.map { it.first }.distinct().sorted()
    val rate = if (gross > 0) creditDollars / gross else 0.0

    ScreenColumn {
        // ---- Period menu + view switch ----
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RangeMenu(range) { range = it }
            Column(Modifier.weight(1f)) {
                Text(
                    if (range == SalesRange.Today) today.format(Fmt.full) else "${from.format(Fmt.day)} – ${today.format(Fmt.day)}",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
                Muted("${routeDays.size} route day" + if (routeDays.size == 1) "" else "s", 13)
            }
            if (LocalWide.current) Segmented(listOf("Overview", "By store"), view) { view = it }
        }
        if (!LocalWide.current) Segmented(listOf("Overview", "By store"), view) { view = it }

        if (rows.isEmpty()) {
            Panel {
                H2(if (range == SalesRange.Today) "Today isn't imported yet" else "No sales in this window")
                Muted(if (range == SalesRange.Today) "Sales show up after today's End of Day import. Pick a longer window above to see earlier days." else "Pick a longer window, or import a day first.")
            }
        } else if (view == 0) {
            // ---- Overview: main sales, then credits right under ----
            val tiles: List<@Composable (Modifier) -> Unit> = listOf(
                { m -> Tile("Net sales", Fmt.money(net), m) },
                { m -> Tile("Gross sales", Fmt.money(gross), m) },
                { m -> Tile("Credits", Fmt.money(-creditDollars), m, valueColor = if (creditDollars > 0) C.Red else C.Ink) },
                { m -> Tile("Stores", sales.map { it.second.cusCode }.distinct().size.toString(), m) },
            )
            tiles.chunked(if (LocalWide.current) 4 else 2).forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { r.forEach { t -> t(Modifier.weight(1f)) } }
            }

            Panel {
                if (range == SalesRange.Today) {
                    H2("Net sales by store")
                    val byStore = rows.groupBy { it.second.store }.map { (s, l) -> s to l.sumOf { it.second.net } }.sortedByDescending { it.second }
                    BarList(byStore.map { it.first to it.second }, Fmt::money)
                } else {
                    val weekly = range == SalesRange.Year
                    H2(if (weekly) "Net sales by week" else "Net sales by route day")
                    val buckets = if (weekly) {
                        rows.groupBy { Periods.weekStart(it.first) }.toSortedMap().map { (d, l) -> d.format(Fmt.md) to l.sumOf { it.second.net } }
                    } else {
                        rows.groupBy { it.first }.toSortedMap().map { (d, l) -> d.format(Fmt.md) to l.sumOf { it.second.net } }
                    }
                    BarChart(buckets)
                }
            }

            // Credit rate, Taco-Boys style
            Panel {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        H2("Credit rate")
                        Muted("Credits as a share of gross sales: ${Fmt.money(creditDollars)} of ${Fmt.money(gross)}.", 14)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(pct(rate), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = bandColor(rate))
                        Text(bandLabel(rate), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = bandColor(rate))
                    }
                }
                RateMeter(rate)
                Muted("Green to 1.5%, amber to 2.5%, red beyond. The line is the 1.5% target. One division over the whole window, never an average of daily rates.", 13)

                if (range != SalesRange.Today && range != SalesRange.Week) {
                    HorizontalDivider(color = C.Divider)
                    Text("Week to week", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    val weeks = rows.groupBy { Periods.weekStart(it.first) }.toSortedMap().map { (wk, l) ->
                        val g = l.filter { !it.second.isReturn }.sumOf { it.second.net }
                        val c = l.filter { it.second.isReturn }.sumOf { abs(it.second.net) }
                        Triple("${wk.format(Fmt.md)} – ${wk.plusDays(6).format(Fmt.md)}", if (g > 0) c / g else 0.0, c)
                    }
                    val top = max(weeks.maxOfOrNull { it.second } ?: 0.0, ELEVATED)
                    weeks.forEach { (label, r, c) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, fontSize = 14.sp, modifier = Modifier.width(118.dp))
                            Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(4.dp)).background(C.Ground)) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth((r / top).toFloat().coerceIn(0.01f, 1f)).background(bandColor(r)))
                            }
                            Text(pct(r), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = bandColor(r), textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
                        }
                    }
                }

                if (credits.isNotEmpty()) {
                    HorizontalDivider(color = C.Divider)
                    var showCredits by remember { mutableStateOf(false) }
                    Expander("Credit lines", "${credits.size}", showCredits) { showCredits = !showCredits }
                    if (showCredits) {
                        credits.sortedByDescending { it.first }.forEach { (d, l) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Text("${d.format(Fmt.md)} · ${l.store} · ${l.code} ${l.name} · ${Fmt.qty(l.qty)}", fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Text(Fmt.money(-abs(l.net)), fontSize = 14.sp, color = C.Red, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (voids.isNotEmpty()) {
                Panel {
                    var showVoids by remember { mutableStateOf(false) }
                    Expander("Voided tickets (not counted)", "${voids.size}", showVoids) { showVoids = !showVoids }
                    if (showVoids) voids.forEach { (d, doc) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Text("${d.format(Fmt.md)} · ${doc.store}" + if (doc.voidReason.isNotEmpty()) " · ${doc.voidReason}" else "", fontSize = 14.sp, color = C.Muted, modifier = Modifier.weight(1f))
                            Text(Fmt.money(doc.net), fontSize = 14.sp, color = C.Muted)
                        }
                    }
                }
            }
        } else {
            // ---- By store: totals for the window, tap for products ----
            var open by remember(range) { mutableStateOf<String?>(null) }
            val stores = rows.groupBy { it.second.cusCode }.map { (cus, l) ->
                val g = l.filter { !it.second.isReturn }.sumOf { it.second.net }
                val c = l.filter { it.second.isReturn }.sumOf { abs(it.second.net) }
                StoreTotal(cus, l.first().second.store, g, c, l.map { it.first }.distinct().size, l.map { it.second })
            }.sortedByDescending { it.gross - it.credits }
            Panel(pad = 0.dp) {
                Text("Stores, tap for products", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))
                stores.forEach { s ->
                    val isOpen = open == s.cus
                    val r = if (s.gross > 0) s.credits / s.gross else 0.0
                    HorizontalDivider(color = C.Divider)
                    Row(
                        Modifier.fillMaxWidth().background(if (isOpen) C.Row else Color.White).clickable { open = if (isOpen) null else s.cus }
                            .heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (isOpen) "▾  " else "▸  ", color = C.Muted, fontSize = 18.sp)
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Muted("${s.visits} visit" + (if (s.visits == 1) "" else "s") + " · credits ${Fmt.money(s.credits)} (${pct(r)})", 13)
                        }
                        Text(Fmt.money(s.gross - s.credits), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    }
                    if (isOpen) {
                        Column(Modifier.fillMaxWidth().background(C.Row).padding(start = 46.dp, end = 20.dp, bottom = 12.dp)) {
                            val w = listOf(0.8f, 3f, 0.8f, 1f, 0.8f, 1f)
                            TableRow(listOf("Code", "Product", "Sold", "Sales", "Cred.", "Credit $"), w, header = true, endAligned = setOf(2, 3, 4, 5))
                            s.lines.groupBy { it.code }.map { (code, l) ->
                                val sold = l.filter { !it.isReturn }
                                val cr = l.filter { it.isReturn }
                                listOf(
                                    code, l.first().name, Fmt.qty(sold.sumOf { it.qty }), Fmt.money(sold.sumOf { it.net }),
                                    if (cr.isEmpty()) "—" else Fmt.qty(cr.sumOf { it.qty }), if (cr.isEmpty()) "—" else Fmt.money(-cr.sumOf { abs(it.net) }),
                                ) to sold.sumOf { it.net }
                            }.sortedByDescending { it.second }.forEach { (cells, _) ->
                                HorizontalDivider(color = C.Divider)
                                TableRow(cells, w, bold = setOf(3), endAligned = setOf(2, 3, 4, 5))
                            }
                        }
                    }
                }
            }
        }
    }
}

private class StoreTotal(val cus: String, val name: String, val gross: Double, val credits: Double, val visits: Int, val lines: List<LineRow>)

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
private fun BarChart(items: List<Pair<String, Double>>) {
    if (items.isEmpty()) return
    val top = items.maxOf { it.second }.takeIf { it > 0 } ?: 1.0
    val every = max(1, (items.size + 13) / 14) // at most ~14 labels
    Muted("Tallest bar ${Fmt.money(top)} · average ${Fmt.money(items.sumOf { it.second } / items.size)}", 13)
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
