package com.route0465.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

@Composable
fun SalesScreen(v: Int) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val days = remember(v) { repo.days() }
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    val date = picked ?: days.firstOrNull()?.date

    if (date == null) {
        ScreenColumn { Panel { H2("No sales yet"); Muted("Sales show up here after your first End of Day import.") } }
    } else {
        SalesBody(v, repo, days, date) { picked = it }
    }
}

@Composable
private fun SalesBody(v: Int, repo: Db, days: List<DaySummary>, date: LocalDate, pick: (LocalDate) -> Unit) {
    ScreenColumn {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            days.take(14).forEach { d ->
                val on = d.date == date
                Text(
                    d.date.format(Fmt.day),
                    Modifier.background(if (on) C.Ink else Color.White).clickable { pick(d.date) }
                        .heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    color = if (on) Color.White else C.Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                )
            }
        }

        val docs = remember(v, date) { repo.docs(date) }
        val lines = remember(v, date) { repo.lines(date) }
        val live = docs.filter { !it.voided }
        val liveCodes = live.map { it.dmdCode }.toSet()
        val credits = lines.filter { it.isReturn && it.dmdCode in liveCodes }
        val voids = docs.filter { it.voided }
        val day = days.firstOrNull { it.date == date }
        val wStart = Periods.weekStart(date)
        val weekTotal = days.filter { !it.date.isBefore(wStart) && !it.date.isAfter(date) }.sumOf { it.netSales }

        // Four across when the tablet is sideways, two by two when it's upright.
        val tiles: List<@Composable (Modifier) -> Unit> = listOf(
            { m -> Tile("Net sales", Fmt.money(day?.netSales ?: 0.0), m) },
            { m -> Tile("Stores serviced", live.map { it.cusCode }.distinct().size.toString(), m) },
            { m -> Tile("Credits", Fmt.money(credits.sumOf { it.net }), m, valueColor = if (credits.isEmpty()) C.Ink else C.Red) },
            { m -> Tile("Week so far (Sat–${date.format(Fmt.md)})", Fmt.money(weekTotal), m) },
        )
        tiles.chunked(if (LocalWide.current) 4 else 2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { t -> t(Modifier.weight(1f)) }
            }
        }

        var open by remember(date) { mutableStateOf<String?>(null) }
        Panel(pad = 0.dp) {
            Text("By store, tap for line items", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(start = 22.dp, top = 16.dp, bottom = 8.dp))
            val byStore = live.groupBy { it.cusCode }.toList().sortedByDescending { (_, ds) -> ds.sumOf { it.net } }
            byStore.forEach { (cus, ds) ->
                val isOpen = open == cus
                val codes = ds.map { it.dmdCode }.toSet()
                val storeLines = lines.filter { it.dmdCode in codes && !it.isReturn }
                HorizontalDivider(color = C.Divider)
                Row(
                    Modifier.fillMaxWidth().background(if (isOpen) C.Row else Color.White).clickable { open = if (isOpen) null else cus }
                        .heightIn(min = 64.dp).padding(horizontal = 22.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (isOpen) "▾  " else "▸  ", color = C.Muted, fontSize = 18.sp)
                    Column(Modifier.weight(1f)) {
                        Text(ds.first().store, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Muted("${storeLines.size} lines · ${ds.size} ticket" + if (ds.size == 1) "" else "s", 13)
                    }
                    Text(Fmt.money(ds.sumOf { it.net }), fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
                }
                if (isOpen) {
                    Column(Modifier.fillMaxWidth().background(C.Row).padding(start = 54.dp, end = 22.dp, bottom = 14.dp)) {
                        val w = listOf(0.8f, 3f, 0.7f, 0.9f, 1f)
                        TableRow(listOf("Code", "Product", "Qty", "Price", "Net"), w, header = true, endAligned = setOf(2, 3, 4))
                        storeLines.forEach { l ->
                            HorizontalDivider(color = C.Divider)
                            TableRow(listOf(l.code, l.name, Fmt.qty(l.qty), Fmt.money(l.price), Fmt.money(l.net)), w, bold = setOf(4), endAligned = setOf(2, 3, 4))
                        }
                    }
                }
            }
        }

        Split {
            Panel(Modifier.part(1f)) {
                H2("Credits")
                if (credits.isEmpty()) Muted("None")
                credits.forEach { l ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("${l.store} · ${l.code} ${l.name} · ${Fmt.qty(l.qty)}", fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(Fmt.money(l.net), color = C.Red, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
            Panel(Modifier.part(1f)) {
                H2("Voided tickets (not counted)")
                if (voids.isEmpty()) Muted("None")
                voids.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("${d.store}" + if (d.voidReason.isNotEmpty()) " · ${d.voidReason}" else "", fontSize = 15.sp, color = C.Muted, modifier = Modifier.weight(1f))
                        Text(Fmt.money(d.net), fontSize = 15.sp, color = C.Muted)
                    }
                }
            }
        }
    }
}
