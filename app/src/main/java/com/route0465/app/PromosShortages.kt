package com.route0465.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

@Composable
private fun PickField(label: String, value: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Muted)
        Panel(Modifier.fillMaxWidth(), pad = 14.dp, onClick = onClick) {
            Text(value.ifEmpty { "Tap to choose" }, fontSize = 16.sp, color = if (value.isEmpty()) C.Muted else C.Ink)
        }
    }
}

@Composable
fun PromosScreen(v: Int, bump: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val today = LocalDate.now()
    val promos = remember(v) { repo.promosV2() }
    val names = remember(v) { repo.products("", 5000).associate { it.code.uppercase() to it.name } }
    val banners = remember(v) { bannersFor(repo.stores().map { it.second }) }
    val cycle = orderCycle(today)

    var banner by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(listOf<String>()) }
    var bannerMenu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PromoV2?>(null) }
    var showEnded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val current = promos.filter { !it.end.isBefore(today) }
    val ended = promos.filter { it.end.isBefore(today) }

    @Composable
    fun PromoCard(p: PromoV2) {
        HorizontalDivider(color = C.Divider)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${p.banner} — ${p.type}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                val status = when {
                    p.start.isAfter(today) -> "Starts ${p.start.format(Fmt.day)}"
                    p.end.isBefore(today) -> "Ended"
                    else -> "Running now"
                }
                Muted("${promoDates(p.start, p.end)} · $status", 14)
                val tag = classifyForCycle(p.start, p.end, cycle.first, cycle.second)
                if (tag != null) Text(
                    "On this order: " + if (tag == "short") "light bump" else "stock up",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (tag == "short") C.Blue else C.Amber,
                )
                p.items.forEach { code ->
                    Row {
                        Text(code, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, modifier = Modifier.width(64.dp))
                        Text(names[code.uppercase()] ?: "", fontSize = 14.sp)
                    }
                }
            }
            Text("Remove", color = C.Red, fontSize = 14.sp, modifier = Modifier.clickable { deleting = p }.padding(12.dp))
        }
    }

    ScreenColumn {
        Split {
            Panel(Modifier.part(1.4f)) {
                H2("Promotions")
                Muted("This order's delivery cycle: ${cycle.first.format(Fmt.day)} – ${cycle.second.minusDays(1).format(Fmt.day)}. A promo tags its products on the order guide when it lands on that cycle.", 13)
                if (current.isEmpty()) Muted("No current or upcoming promotions.")
                current.sortedBy { it.start }.forEach { PromoCard(it) }
                if (ended.isNotEmpty()) {
                    HorizontalDivider(color = C.Divider)
                    Row(Modifier.fillMaxWidth().clickable { showEnded = !showEnded }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Ended", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Muted("${ended.size}", 13)
                        Text(if (showEnded) "  ▴" else "  ▾", fontSize = 18.sp, color = C.Muted)
                    }
                    if (showEnded) ended.forEach { PromoCard(it) }
                }
            }
            Panel(Modifier.part(1f)) {
                H2("Add a promotion")
                Box {
                    PickField("Banner (every store of that chain)", banner) { bannerMenu = true }
                    DropdownMenu(expanded = bannerMenu, onDismissRequest = { bannerMenu = false }) {
                        (banners + ALL_BANNERS).forEach { b -> DropdownMenuItem(text = { Text(b) }, onClick = { banner = b; bannerMenu = false }) }
                    }
                }
                OutlinedTextField(type, { type = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Sale type or deal, e.g. BOGO, 2 for \$5, Rollback") })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(start, { start = it }, Modifier.weight(1f), label = { Text("Starts MM/DD/YYYY") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(end, { end = it }, Modifier.weight(1f), label = { Text("Ends MM/DD/YYYY") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Text("Products (${items.size})", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Muted)
                items.forEach { code ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(code, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, modifier = Modifier.width(64.dp))
                        Text(names[code.uppercase()] ?: "", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text("✕", color = C.Red, modifier = Modifier.clickable { items = items - code }.padding(8.dp))
                    }
                }
                SecondaryButton(if (items.isEmpty()) "Pick products" else "Add more products") { picking = true }
                error?.let { Banner(it, C.AmberSoft, C.Amber) }
                PrimaryButton("Add promotion", Modifier.fillMaxWidth(), enabled = banner.isNotEmpty() && type.isNotBlank() && items.isNotEmpty()) {
                    val s0 = parseLooseDate(start, today)
                    val e0 = parseLooseDate(end, today)
                    error = when {
                        s0 == null || e0 == null -> "Enter both dates, like 10/10/2026."
                        s0.isAfter(e0) -> "The end date is before the start date."
                        else -> null
                    }
                    if (error == null && s0 != null && e0 != null) {
                        repo.addPromoV2(banner, type.trim(), items, s0, e0)
                        banner = ""; type = ""; start = ""; end = ""; items = emptyList()
                        bump()
                    }
                }
            }
        }
    }
    if (picking) MultiProductPicker(already = items.toSet(), onAdd = { codes -> items = (items + codes).distinct(); picking = false }, onDismiss = { picking = false })
    deleting?.let { p ->
        ConfirmDialog("Remove this promotion?", "${p.banner} — ${p.type}, ${promoDates(p.start, p.end)} (${p.items.size} products)", "Remove",
            onConfirm = { repo.deletePromoV2(p.id); bump() }, onDismiss = { deleting = null })
    }
}

@Composable
fun ShortagesScreen(v: Int, bump: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val list = remember(v) { repo.shortages() }
    var product by remember { mutableStateOf<Product?>(null) }
    var qty by remember { mutableStateOf("1") }
    var kind by remember { mutableStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Shortage?>(null) }
    val kinds = listOf("Warehouse short", "Missing freight")

    ScreenColumn {
        Split {
            Panel(Modifier.part(1.4f)) {
                H2("Shortages")
                if (list.isEmpty()) Muted("None logged.")
                list.forEach { s ->
                    HorizontalDivider(color = C.Divider)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${s.code} ${s.name}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Muted("${parseLooseDate(s.date)?.format(Fmt.day) ?: s.date} · ${s.kind}", 14)
                        }
                        Text("${Fmt.qty(s.qty)} cs", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                        Text("Remove", color = C.Red, fontSize = 14.sp, modifier = Modifier.clickable { deleting = s }.padding(12.dp))
                    }
                }
            }
            Panel(Modifier.part(1f)) {
                H2("Log a shortage")
                PickField("Product", product?.let { "${it.code} ${it.name}" } ?: "") { picking = true }
                OutlinedTextField(
                    qty, { qty = it }, Modifier.fillMaxWidth(), label = { Text("Cases short") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Segmented(kinds, kind) { kind = it }
                PrimaryButton("Log shortage", Modifier.fillMaxWidth(), enabled = product != null && (qty.toDoubleOrNull() ?: 0.0) > 0) {
                    val p = product ?: return@PrimaryButton
                    repo.addShortage(LocalDate.now(), p.code, p.name, qty.toDoubleOrNull() ?: 1.0, kinds[kind])
                    product = null; qty = "1"
                    bump()
                }
            }
        }
    }
    if (picking) ProductPicker(onPick = { product = it; picking = false }, onDismiss = { picking = false })
    deleting?.let { s ->
        ConfirmDialog("Remove this shortage?", "${s.code} ${s.name} · ${Fmt.qty(s.qty)} cs", "Remove",
            onConfirm = { repo.deleteShortage(s.id); bump() }, onDismiss = { deleting = null })
    }
}

/** The chain a store belongs to: "KROGER #429-SHARONVILLE,OH" → "Kroger". */
fun bannerOf(storeName: String): String {
    val u = storeName.uppercase()
    return when {
        u.startsWith("KROGER") -> "Kroger"
        u.startsWith("WALMART") || u.startsWith("WAL-MART") || u.startsWith("WAL MART") -> "Walmart"
        u.startsWith("SAM'S") || u.startsWith("SAMS") || u.startsWith("SAM ") -> "Sam's Club"
        u.startsWith("TARGET") -> "Target"
        u.startsWith("JUNGLE JIM") -> "Jungle Jim's"
        else -> storeName.substringBefore('#').substringBefore('-').trim()
            .lowercase().split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }
}

/** Banners on this route, plus the usual ones, in a steady order. */
fun bannersFor(storeNames: List<String>): List<String> =
    (listOf("Kroger", "Walmart", "Sam's Club", "Target", "Jungle Jim's") + storeNames.map { bannerOf(it) }).filter { it.isNotBlank() }.distinct()

/** Older promos saved with a single store name show as that store's banner. */
fun bannerLabel(saved: String): String = when (saved) {
    "All stores", "All banners" -> "All banners"
    else -> if (saved in bannersFor(emptyList())) saved else bannerOf(saved)
}
