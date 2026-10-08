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
                    val w = listOf(0.8f, 3f, 1f, 1f, 1f)
                    TableRow(listOf("Code", "Product", "Each on hand", "Case pack", "Cases"), w, header = true, endAligned = setOf(2, 3, 4))
                    stock.forEach { s ->
                        HorizontalDivider(color = C.Divider)
                        TableRow(
                            listOf(s.code, s.name, Fmt.qty(s.onHand), Fmt.qty(s.casePack), Fmt.one(s.cases)), w,
                            bold = setOf(4), endAligned = setOf(2, 3, 4),
                            bg = if (s.onHand <= 0.0) C.RedSoft else Color.Transparent,
                        )
                    }
                }
            } else {
                val prevDate = dates.getOrNull(1)
                Panel {
                    if (prevDate == null) {
                        Muted("Inventory Check compares two imports. It fills in after your second End of Day.")
                    } else {
                        val prev = remember(v, prevDate) { repo.stock(prevDate).associateBy { it.code } }
                        Muted("Cases at the last two imports: ${prevDate.format(Fmt.day)} and ${latest.format(Fmt.day)}.", 14)
                        val w = listOf(3.6f, 1f, 1f, 1f)
                        TableRow(listOf("Product", prevDate.format(Fmt.day), latest.format(Fmt.day), "Change"), w, header = true, endAligned = setOf(1, 2, 3))
                        val codes = (stock.map { it.code } + prev.keys).distinct().sorted()
                        val now = stock.associateBy { it.code }
                        codes.forEach { code ->
                            val a = prev[code]?.cases ?: 0.0
                            val b = now[code]?.cases ?: 0.0
                            val ch = b - a
                            val name = now[code]?.name ?: prev[code]?.name ?: ""
                            HorizontalDivider(color = C.Divider)
                            TableRow(
                                listOf("$code · $name", Fmt.one(a), Fmt.one(b), (if (ch > 0.05) "+" else "") + Fmt.one(ch)), w,
                                bold = setOf(3), endAligned = setOf(1, 2, 3),
                            )
                        }
                    }
                }
            }
        }
    }
}
