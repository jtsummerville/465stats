package com.route0465.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/**
 * Ordering look-back: tap a product on the Order screen. Shows cases a week (all stores) over the
 * prior 2 and prior 4 full Sat–Fri weeks, this year and the same weeks last year (52 weeks back, so
 * weekdays line up), and whether it was on sale last year during the week you're ordering for.
 */
@Composable
fun LastYearDialog(repo: Db, code: String, name: String, delivery: LocalDate, onClose: () -> Unit) {
    val today = LocalDate.now()
    val thisWeek = Periods.weekStart(today)
    val data = remember(code, thisWeek, delivery) {
        val pack = repo.casePacks()[code.uppercase()]?.takeIf { it > 0 } ?: 1.0
        val imported = repo.days().map { it.date }.toSet()
        // Cases a week over the [weeks] full weeks before [start]; only weeks with an import count.
        fun avg(start: LocalDate, weeks: Int): Pair<Double, Int>? {
            val from = start.minusWeeks(weeks.toLong())
            val withData = (0 until weeks).count { w ->
                val ws = from.plusWeeks(w.toLong())
                imported.any { !it.isBefore(ws) && !it.isAfter(ws.plusDays(6)) }
            }
            if (withData == 0) return null
            val eaches = repo.unitsSoldBetween(from, start.minusDays(1)).filter { it.second.equals(code, ignoreCase = true) }.sumOf { it.third }
            return eaches / pack / withData to withData
        }
        val lyStart = thisWeek.minusWeeks(52)
        val grid = listOf(avg(thisWeek, 2), avg(lyStart, 2), avg(thisWeek, 4), avg(lyStart, 4))
        // The delivery week you're ordering for, a year ago.
        val lyFrom = delivery.minusWeeks(52)
        val lyTo = lyFrom.plusDays(6)
        val banners = bannersFor(repo.stores().map { it.second }).toSet()
        val sale = repo.promosV2().filter { p ->
            !(p.end.isBefore(lyFrom) || p.start.isAfter(lyTo)) &&
                (p.banner == ALL_BANNERS || banners.isEmpty() || p.banner in banners) &&
                p.items.any { it.equals(code, ignoreCase = true) }
        }.sortedBy { it.start }
        Triple(grid, lyFrom to lyTo, sale)
    }
    val (grid, lyWeek, sale) = data

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("$code · $name", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Average cases a week, all stores", fontSize = 13.sp, color = C.Muted)
                Row(Modifier.fillMaxWidth()) {
                    Text("", modifier = Modifier.weight(1.3f))
                    Text("This year", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.Muted, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
                    Text("Last year", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.Muted, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
                }
                listOf("Prior 2 weeks" to 2, "Prior 4 weeks" to 4).forEachIndexed { i, (label, w) ->
                    HorizontalDivider(color = C.Divider)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1.3f))
                        Cell(grid[i * 2], w, Modifier.weight(1f))
                        Cell(grid[i * 2 + 1], w, Modifier.weight(1f))
                    }
                }
                HorizontalDivider(color = C.Divider)
                Text("On sale last year, week you're ordering for", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Text("${lyWeek.first.format(Fmt.full)} – ${lyWeek.second.format(Fmt.full)}", fontSize = 13.sp, color = C.Muted)
                if (sale.isEmpty()) Text("No", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                sale.forEach { p -> Text("Yes · ${p.banner} — ${p.type} (${p.start.format(Fmt.md)} – ${p.end.format(Fmt.md)})", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.Amber) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", fontWeight = FontWeight.Bold) } },
    )
}

@Composable
private fun Cell(v: Pair<Double, Int>?, weeks: Int, modifier: Modifier) {
    Column(modifier, horizontalAlignment = androidx.compose.ui.Alignment.End) {
        Text(v?.let { Fmt.one(it.first) + " cs" } ?: "no data", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = if (v == null) C.Muted else C.Ink)
        if (v != null && v.second < weeks) Text("${v.second} of $weeks wks", fontSize = 11.sp, color = C.Amber)
    }
}
