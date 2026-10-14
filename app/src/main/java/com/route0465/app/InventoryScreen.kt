package com.route0465.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import java.time.LocalDate
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
                if (LocalWide.current) Segmented(listOf("On hand", "Audit"), tab) { tab = it }
            }
            if (!LocalWide.current) Segmented(listOf("On hand", "Audit"), tab) { tab = it }

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
                AuditTab(v, dates)
            }
        }
    }
}

/**
 * Inventory Audit (Taco-Boys inventory_audit.html, driver side only): count the truck against one
 * import's stock, type cases and eaches, see the tablet fix for each row. Saved as you type.
 */
@Composable
private fun AuditTab(v: Int, dates: List<LocalDate>) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    var tick by remember { mutableStateOf(0) }
    var dateIdx by rememberSaveable { mutableStateOf(0) }
    var pickDate by remember { mutableStateOf(false) }
    var onlyFix by rememberSaveable { mutableStateOf(false) }
    var viewing by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val date = dates[dateIdx.coerceIn(0, dates.size - 1)]
    val stock = remember(v, date) { repo.stock(date).filter { it.casePack > 0 }.sortedBy { it.code } }
    val audit = remember(v, date, tick) { InventoryAudit.get(repo, date) }
    val onFile = remember(v, tick) { InventoryAudit.dates(repo) }
    // Typed text per row, seeded from the saved count.
    val typed = remember(date) {
        mutableStateMapOf<String, Pair<String, String>>().apply {
            audit?.lines?.forEach { (code, l) -> put(code, l.cases.toString() to l.eaches.toString()) }
        }
    }
    fun corr(s: StockRow): Correction? {
        val (c, e) = typed[s.code.uppercase()] ?: return null
        if (c.isBlank() && e.isBlank()) return null
        val (sc, se) = InventoryAudit.split(s.onHand, s.casePack.toInt())
        return InventoryAudit.correction(s.casePack.toInt(), sc, se, c.toIntOrNull() ?: 0, e.toIntOrNull() ?: 0)
    }
    val results = stock.associate { it.code to corr(it) }
    val counted = results.values.count { it != null }
    val fixes = results.values.count { it != null && it.diff != 0 }
    val addE = results.values.sumOf { it?.eachesAdd ?: 0 }
    val subC = results.values.sumOf { it?.casesSub ?: 0 }

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                H2("Count the truck")
                Muted("Against the import of ${date.format(Fmt.day)}. Type what you count; a blank row means not counted, never 0. Saved as you type.", 13)
            }
            SecondaryButton("Other import") { pickDate = true }
        }
        if (audit != null && audit.updated.isNotBlank()) Banner(
            "Saved ${audit.updated} · $counted of ${stock.size} counted" + (audit.keyed?.let { " · keyed into XSales ${it.format(Fmt.md)}" } ?: ""), C.AmberSoft, C.Amber,
        )
    }
    if (pickDate) androidx.compose.material3.AlertDialog(
        onDismissRequest = { pickDate = false }, confirmButton = { androidx.compose.material3.TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        title = { Text("Count against which import?") },
        text = {
            androidx.compose.foundation.layout.Column {
                dates.take(14).forEachIndexed { i, d ->
                    Text(d.format(Fmt.full) + if (d in onFile) "  · counted" else "", fontSize = 17.sp, fontWeight = if (i == dateIdx) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable { dateIdx = i; pickDate = false }.padding(vertical = 10.dp))
                }
            }
        },
    )

    Panel(pad = 0.dp) {
        val w = listOf(2.2f, 0.7f, 0.7f, 1f, 1f, 1.3f)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Box(Modifier.weight(2.2f))
            Text("On tablet", fontSize = 11.sp, color = C.Muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1.4f).background(Color(0xFFEEF1F7)))
            Text("You counted", fontSize = 11.sp, color = C.Muted, textAlign = TextAlign.Center, modifier = Modifier.weight(2f).background(Color(0xFFECF7EF)))
            Text("Fix", fontSize = 11.sp, color = C.Muted, textAlign = TextAlign.End, modifier = Modifier.weight(1.3f))
        }
        Box(Modifier.padding(horizontal = 12.dp)) { TableRow(listOf("Code", "Cs", "Ea", "Cs", "Ea", ""), w, header = true, endAligned = setOf(1, 2, 3, 4, 5)) }
        stock.forEach { s ->
            val r = results[s.code]
            if (onlyFix && (r == null || r.diff == 0)) return@forEach
            val (sc, se) = InventoryAudit.split(s.onHand, s.casePack.toInt())
            val (tc, te) = typed[s.code.uppercase()] ?: ("" to "")
            HorizontalDivider(color = C.Divider)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Column(Modifier.weight(2.2f)) {
                    Text(s.code, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                    Text(s.name, fontSize = 12.sp, color = C.Muted, maxLines = 1)
                }
                Text(sc.toString(), fontSize = 15.sp, textAlign = TextAlign.End, modifier = Modifier.weight(0.7f))
                Text(se.toString(), fontSize = 15.sp, textAlign = TextAlign.End, modifier = Modifier.weight(0.7f))
                CountBox(tc, Modifier.weight(1f)) { typed[s.code.uppercase()] = it to te; save(repo, date, s.code, it, te) }
                CountBox(te, Modifier.weight(1f)) { typed[s.code.uppercase()] = tc to it; save(repo, date, s.code, tc, it) }
                Text(
                    when {
                        r == null -> "—"
                        r.diff == 0 -> "✓"
                        else -> listOfNotNull(if (r.casesSub > 0) "−${r.casesSub} cs" else null, if (r.eachesAdd > 0) "+${r.eachesAdd} ea" else null).joinToString("\n")
                    },
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, lineHeight = 16.sp,
                    color = when { r == null -> C.Line; r.diff == 0 -> C.Muted; r.casesSub > 0 -> C.Red; else -> C.Green },
                    modifier = Modifier.weight(1.3f),
                )
            }
        }
    }

    Panel {
        Text(buildString { append("$counted of ${stock.size} counted · $fixes need a fix · +$addE eaches / −$subC cases") }, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
            InventoryAudit.setKeyed(repo, date, if (audit?.keyed == null) LocalDate.now() else null); tick++
        }) {
            androidx.compose.material3.Checkbox(audit?.keyed != null, { on -> InventoryAudit.setKeyed(repo, date, if (on) LocalDate.now() else null); tick++ },
                colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = C.Green))
            Text("I've keyed these fixes into XSales", fontSize = 14.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(if (onlyFix) "Show every row" else "Only rows needing a fix", Modifier.weight(1f)) { onlyFix = !onlyFix }
            SecondaryButton("Clear this count", Modifier.weight(1f), enabled = counted > 0) { confirmClear = true }
        }
    }
    if (confirmClear) ConfirmDialog("Clear the ${date.format(Fmt.md)} count?", "Every number you typed for this import is thrown away.", "Clear",
        onConfirm = { InventoryAudit.clear(repo, date); typed.clear(); tick++ }, onDismiss = { confirmClear = false })

    // ---- Counts on file ----
    if (onFile.isNotEmpty()) Panel {
        H2("Counts on file")
        Muted("Each one is the paper trail for the fixes keyed in that day.", 13)
        onFile.forEach { d ->
            val a = InventoryAudit.get(repo, d) ?: return@forEach
            val st = repo.stock(d).associateBy { it.code.uppercase() }
            val rs = a.lines.values.mapNotNull { l ->
                val s = st[l.code] ?: return@mapNotNull null
                val (sc, se) = InventoryAudit.split(s.onHand, s.casePack.toInt())
                l.code to InventoryAudit.correction(s.casePack.toInt(), sc, se, l.cases, l.eaches)
            }
            val nFix = rs.count { it.second.diff != 0 }
            HorizontalDivider(color = C.Divider)
            Row(Modifier.fillMaxWidth().clickable { viewing = if (viewing == d.toString()) "" else d.toString() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text(d.format(Fmt.full), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Muted("Saved ${a.updated}" + (a.keyed?.let { " · keyed into XSales ${it.format(Fmt.md)}" } ?: ""), 12)
                }
                androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.End) {
                    Text("${rs.size} counted · $nFix fix" + if (nFix == 1) "" else "es", fontSize = 14.sp)
                    Muted(if (nFix == 0) "all matched" else "+${rs.sumOf { it.second.eachesAdd }} ea / −${rs.sumOf { it.second.casesSub }} cs", 12)
                }
                Text(if (viewing == d.toString()) "  ˅" else "  ›", fontSize = 22.sp, color = C.Muted)
            }
            if (viewing == d.toString()) {
                val w = listOf(1.1f, 1.3f, 1.3f, 1.2f)
                TableRow(listOf("Code", "Tablet", "Counted", "Fix"), w, header = true, endAligned = setOf(1, 2, 3))
                rs.filter { it.second.diff != 0 }.sortedBy { it.first }.forEach { (code, c) ->
                    val s = st[code]!!
                    val pack = s.casePack.toInt()
                    val fix = listOfNotNull(if (c.casesSub > 0) "−${c.casesSub} cs" else null, if (c.eachesAdd > 0) "+${c.eachesAdd} ea" else null).joinToString(" ")
                    TableRow(listOf(code, "${c.sys / pack} cs ${c.sys % pack} ea", "${c.cnt / pack} cs ${c.cnt % pack} ea", fix), w, bold = setOf(3), endAligned = setOf(1, 2, 3), small = setOf(1, 2))
                }
                val matched = rs.size - nFix
                if (matched > 0) Muted("$matched matched", 12)
                val net = rs.sumOf { it.second.diff }
                if (net != 0) Muted("Net ${if (net > 0) "+" else ""}$net pieces. Without this count, that movement would look like product going missing.", 12)
            }
        }
    }
}

private fun save(repo: Db, date: LocalDate, code: String, c: String, e: String) {
    if (c.isBlank() && e.isBlank()) InventoryAudit.setLine(repo, date, code, null, null)
    else InventoryAudit.setLine(repo, date, code, c.toIntOrNull() ?: 0, e.toIntOrNull() ?: 0)
}

@Composable
private fun CountBox(value: String, modifier: Modifier, onChange: (String) -> Unit) {
    androidx.compose.foundation.text.BasicTextField(
        value, { onChange(it.filter { ch -> ch.isDigit() }.take(4)) },
        modifier = modifier.padding(horizontal = 3.dp).clip(RoundedCornerShape(6.dp)).background(Color.White).border(1.dp, C.Line, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 8.dp),
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, textAlign = TextAlign.End, color = C.Ink),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
    )
}
