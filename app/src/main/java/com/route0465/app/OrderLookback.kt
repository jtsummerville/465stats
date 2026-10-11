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
 * Ordering look-back: tap a product on the Order screen to see what it sold a year ago around the
 * delivery you're ordering for. The year-ago window starts on the Sat–Fri week holding that same delivery,
 * 52 weeks back (so weekdays line up), and covers 1, 2 and 4 weeks. All stores, in cases (eaches ÷ case size).
 */
@Composable
fun LastYearDialog(repo: Db, code: String, name: String, delivery: LocalDate, nowWeekly: Double?, onClose: () -> Unit) {
    val from = Periods.weekStart(delivery).minusWeeks(52)
    val to4 = from.plusDays(27)
    val data = remember(code, from) {
        val pack = repo.casePacks()[code.uppercase()]?.takeIf { it > 0 } ?: 1.0
        val sold = repo.unitsSoldBetween(from, to4).filter { it.second.equals(code, ignoreCase = true) }
        val imported = repo.days().map { it.date }.filter { !it.isBefore(from) && !it.isAfter(to4) }.toSet()
        val promos = promosByWeek(repo.promosV2(), bannersFor(repo.stores().map { it.second }).toSet(), from, to4)
            .sortedBy { it.first }
            .mapNotNull { (wk, lines) ->
                lines.filter { l -> l.codes.any { it.equals(code, ignoreCase = true) } }.takeIf { it.isNotEmpty() }?.let { wk to it }
            }
        Triple(pack, sold, imported) to promos
    }
    val (pack, sold, imported) = data.first
    val promos = data.second

    // Cases a week over the first n weeks, counting only weeks that have at least one import.
    fun avg(weeks: Int): Pair<Double, Int>? {
        val end = from.plusDays(7L * weeks - 1)
        val withData = (0 until weeks).count { w ->
            val ws = from.plusWeeks(w.toLong())
            imported.any { !it.isBefore(ws) && !it.isAfter(ws.plusDays(6)) }
        }
        if (withData == 0) return null
        val eaches = sold.filter { !it.first.isAfter(end) }.sumOf { it.third }
        return eaches / pack / withData to withData
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Column {
                Text("$code · $name", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                Text("Last year, around this delivery", fontSize = 14.sp, color = C.Muted)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Starting ${from.format(Fmt.full)} · all stores", fontSize = 13.sp, color = C.Muted)
                listOf(1 to "Same week", 2 to "2 weeks (avg a week)", 4 to "Month, 4 weeks (avg a week)").forEach { (w, label) ->
                    HorizontalDivider(color = C.Divider)
                    val a = avg(w)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(label, fontSize = 15.sp)
                            if (a != null && a.second < w) Text("only ${a.second} of $w weeks imported", fontSize = 12.sp, color = C.Amber)
                        }
                        Text(a?.let { Fmt.one(it.first) + " cs" } ?: "no data", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
                            color = if (a == null) C.Muted else C.Ink)
                    }
                }
                if (nowWeekly != null) {
                    HorizontalDivider(color = C.Divider)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("This year, last 4 weeks (avg a week)", fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(Fmt.one(nowWeekly) + " cs", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                HorizontalDivider(color = C.Divider)
                Text("On promotion then", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                if (promos.isEmpty()) Text(
                    if (imported.isEmpty()) "Nothing to compare yet. This fills in once the app has a year of imports." else "No promos on this item in those 4 weeks.",
                    fontSize = 14.sp, color = C.Muted,
                )
                promos.forEach { (wk, lines) ->
                    lines.forEach { l -> Text("Week of ${wk.format(Fmt.md)}: ${l.banner} — ${l.type} (${l.coverage})", fontSize = 14.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", fontWeight = FontWeight.Bold) } },
    )
}
