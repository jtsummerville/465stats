package com.route0465.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

@Composable
fun OrderScreen(v: Int, bump: () -> Unit, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val items = remember(v) { repo.orderItems() }
    val stockDate = remember(v) { repo.stockDates().firstOrNull() }
    val onHand = remember(v, stockDate) { stockDate?.let { d -> repo.stock(d).associate { it.code to it.cases } } ?: emptyMap() }
    val promos = remember(v) { activePromos(repo.promos(), LocalDate.now()) }
    val due = nextOrderDue()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val total = items.sumOf { it.qty }

    ScreenColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(Modifier.weight(1f), bg = C.AmberSoft, line = C.AmberLine) {
                Text("Order deadline", fontSize = 14.sp, color = C.Amber, fontWeight = FontWeight.SemiBold)
                Text((if (due == LocalDate.now()) "Today" else due.format(Fmt.day)) + " by midnight", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
            Tile("Cases ordered", total.toString())
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Email order", enabled = items.isNotEmpty()) {
                    try {
                        val f = OrderPdf.build(ctx, items, due)
                        OrderPdf.email(ctx, f, Prefs.orderEmail(ctx), due, total)
                        message = if (Prefs.orderEmail(ctx).isBlank()) "Tip: set the order email address in Setup so it fills in by itself." else null
                    } catch (e: Exception) {
                        message = "Couldn't make the email: ${e.message}"
                    }
                }
                SecondaryButton("Save PDF", enabled = items.isNotEmpty()) {
                    message = try {
                        val f = OrderPdf.build(ctx, items, due)
                        OrderPdf.saveToDownloads(ctx, f)
                        "Saved ${f.name} to Downloads."
                    } catch (e: Exception) {
                        "Couldn't save the PDF: ${e.message}"
                    }
                }
            }
        }
        message?.let { Banner(it, C.GreenSoft, C.GreenDark) }

        Panel {
            if (items.isEmpty()) {
                H2("No products on your order guide yet")
                Muted("Pick your products and put them in your order on the Setup screen.")
                SecondaryButton("Go to Setup") { go(Screen.Setup) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Muted("Cases to order. On hand is from the last import${stockDate?.let { " (" + it.format(Fmt.day) + ")" } ?: ""}.", 14)
                    Box(Modifier.weight(1f))
                    Text(
                        "Clear all", color = C.Red, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.clickable { confirmClear = true }.padding(10.dp),
                    )
                }
                items.forEach { item ->
                    HorizontalDivider(color = C.Divider)
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(item.code, color = C.Muted, fontSize = 15.sp, modifier = Modifier.width(64.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name.ifEmpty { item.code }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Muted("On hand ${onHand[item.code]?.let { c -> Fmt.one(c) } ?: "0.0"} cs", 13)
                        }
                        promos[item.code]?.let { p ->
                            Text(
                                p, color = C.Amber, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(C.AmberSoft).padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                        StepButton("−", dark = false) { repo.setOrderQty(item.code, item.qty - 1); bump() }
                        Text(item.qty.toString(), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, modifier = Modifier.width(48.dp))
                        StepButton("+", dark = true) { repo.setOrderQty(item.code, item.qty + 1); bump() }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog("Clear all quantities?", "Sets every product on the order guide back to 0 cases.", "Clear",
            onConfirm = { repo.clearOrderQty(); bump() }, onDismiss = { confirmClear = false })
    }
}

@Composable
private fun StepButton(label: String, dark: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(if (dark) C.Ink else C.Ground).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 26.sp, color = if (dark) Color.White else C.Ink, fontWeight = FontWeight.Bold) }
}

/** Product code -> badge text for promos that haven't ended yet. */
fun activePromos(promos: List<Promo>, today: LocalDate): Map<String, String> {
    val out = HashMap<String, String>()
    promos.forEach { p ->
        val end = parseLooseDate(p.end, today)
        if (end == null || !end.isBefore(today)) {
            out.putIfAbsent(p.code, "Promo " + listOf(p.start, p.end).filter { it.isNotBlank() }.joinToString(" – ").ifEmpty { p.deal })
        }
    }
    return out
}
