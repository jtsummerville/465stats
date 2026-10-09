package com.route0465.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
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

@Composable
fun InventoryScreen(v: Int) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val dates = remember(v) { repo.stockDates() }
    var tab by remember { mutableStateOf(0) }

    ScreenColumn {
        val latest = dates.firstOrNull()
        if (latest == null) {
            Panel { H2("No inventory yet"); Muted("Truck stock shows up here after your first End of Day import.") }
        } else {
            val stock = remember(v, latest) { repo.stock(latest) }
            val total = stock.sumOf { it.cases }
            val out = stock.count { it.onHand <= 0.0 }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Panel(Modifier.widthIn(min = 240.dp), bg = C.Ink, line = C.Ink) {
                    Text("Total cases on hand", color = C.RailMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(Fmt.one(total), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold)
                    Text("As of ${latest.format(Fmt.day)}", color = C.RailMuted, fontSize = 13.sp)
                }
                Tile("Out of stock", "$out items", valueColor = if (out > 0) C.Red else C.Ink)
                Box(Modifier.weight(1f))
                if (LocalWide.current) Segmented(listOf("On hand", "Inventory Check"), tab) { tab = it }
            }
            if (!LocalWide.current) Segmented(listOf("On hand", "Inventory Check"), tab) { tab = it }

            if (tab == 0) {
                Panel {
                    // Code never wraps; the description is a little smaller to make room.
                    val w = listOf(1.15f, 2.65f, 1f, 1f, 1f)
                    TableRow(listOf("Code", "Product", "Each on hand", "Case pack", "Cases"), w, header = true, endAligned = setOf(2, 3, 4))
                    stock.forEach { s ->
                        HorizontalDivider(color = C.Divider)
                        TableRow(
                            listOf(s.code, s.name, Fmt.qty(s.onHand), Fmt.qty(s.casePack), Fmt.one(s.cases)), w,
                            bold = setOf(4), endAligned = setOf(2, 3, 4),
                            bg = if (s.onHand <= 0.0) C.RedSoft else Color.Transparent,
                            oneLine = setOf(0), small = setOf(1),
                        )
                    }
                }
            } else {
                CheckTab(v, dates)
            }
        }
    }
}

/** Inventory Check, Taco-Boys style: one period between two imports at a time, newest first. */
@Composable
private fun CheckTab(v: Int, dates: List<java.time.LocalDate>) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    var idx by remember { mutableStateOf(0) }
    if (dates.size < 2) {
        Panel { Muted("Inventory Check compares two imports. It fills in after your second End of Day.") }
        return
    }
    val maxIdx = dates.size - 2
    val i = idx.coerceIn(0, maxIdx)
    val curr = dates[i]
    val prev = dates[i + 1]
    val r = remember(v, curr, prev) { InventoryCheck.check(repo, prev, curr, dates) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton("‹", enabled = i < maxIdx) { idx = i + 1 }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text("${prev.format(Fmt.day)}  →  ${curr.format(Fmt.day)}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Muted(
                "${r.checked} products checked · " + (if (r.coveredDays.size == 1) "1 route day" else "${r.coveredDays.size} route days") +
                    if (r.deliveredTotal > 0) " · ${Fmt.qty(r.deliveredTotal)} eaches delivered" else "", 13,
            )
        }
        SecondaryButton("›", enabled = i > 0) { idx = i - 1 }
    }
    if (r.missingDays.isNotEmpty()) Banner(
        "No import for " + r.missingDays.joinToString(", ") { it.format(Fmt.day) } +
            ". Anything below happened somewhere in this stretch, not on one particular day.",
        C.AmberSoft, C.Amber,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Tile("Went missing", if (r.drops.isEmpty()) "None" else "${Fmt.qty(-r.dropPieces)} ea", Modifier.weight(1f), valueColor = if (r.drops.isEmpty()) C.Ink else C.Red)
        Tile(
            "Appeared", if (r.rises.isEmpty()) "None" else "${Fmt.qty(r.risePieces)} ea", Modifier.weight(1f),
            valueColor = if (r.rises.isEmpty() || r.delivery) C.Ink else C.Amber,
        )
    }
    if (r.balanced) Banner("Every product balanced: last count − sold + buy backs + delivered matches the truck.", C.GreenSoft, C.GreenDark)
    if (r.delivery) Banner(
        "Lots of products went up at once, like freight that wasn't recorded as delivered. Increases here can't be told apart from new stock, and a drop only shows if it's bigger than what came in.",
        C.AmberSoft, C.Amber,
    )

    if (r.drops.isNotEmpty()) Panel {
        H2("Went missing")
        if (r.dropValue > 0) Muted("${r.drops.size} product" + (if (r.drops.size == 1) "" else "s") + " · ${Fmt.money(r.dropValue)} at market rate", 14)
        FindingRows(r.drops)
    }
    if (r.rises.isNotEmpty()) Panel {
        H2("Appeared")
        Muted(
            "${r.rises.size} product" + (if (r.rises.size == 1) "" else "s") +
                (if (r.riseValue > 0) " · ${Fmt.money(r.riseValue)} at market rate" else "") +
                if (r.delivery) " · can't be checked on a delivery period" else "", 14,
        )
        FindingRows(r.rises)
    }
}

@Composable
private fun FindingRows(rows: List<InventoryCheck.Row>) {
    val w = listOf(2.6f, 1f, 1f, 1f)
    TableRow(listOf("Product", "Expected", "On truck", "Off by"), w, header = true, endAligned = setOf(1, 2, 3))
    rows.forEach { f ->
        HorizontalDivider(color = C.Divider)
        TableRow(
            listOf(
                "${f.code} · ${f.name}", Fmt.qty(f.expected), Fmt.qty(f.actual),
                (if (f.diff > 0) "+" else "") + Fmt.qty(f.diff),
            ),
            w, bold = setOf(3), endAligned = setOf(1, 2, 3), small = setOf(0),
            color = C.Ink,
        )
        val parts = ArrayList<String>()
        parts += "Last count ${Fmt.qty(f.prev)}"
        parts += "sold ${Fmt.qty(f.sold)}"
        if (f.buyback > 0) parts += "buy backs +${Fmt.qty(f.buyback)}"
        if (f.delivered > 0) parts += "delivered +${Fmt.qty(f.delivered)}"
        f.value?.let { parts += Fmt.money(it) }
        if (f.credited > 0) parts += "also credited back ${Fmt.qty(f.credited)}"
        Muted(parts.joinToString(" · "), 13)
    }
}
