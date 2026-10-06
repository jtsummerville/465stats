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
    val promos = remember(v) { repo.promos() }
    val stores = remember(v) { repo.stores() }
    var product by remember { mutableStateOf<Product?>(null) }
    var store by remember { mutableStateOf("All stores") }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    var deal by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var storeMenu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Promo?>(null) }

    ScreenColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Panel(Modifier.weight(1.4f)) {
                H2("Promotions")
                if (promos.isEmpty()) Muted("None yet. Add one on the right.")
                promos.forEach { p ->
                    HorizontalDivider(color = C.Divider)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${p.code} ${p.name}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Muted("${p.store} · ${listOf(p.start, p.end).filter { it.isNotBlank() }.joinToString(" – ")}", 14)
                        }
                        Text(p.deal, color = C.Amber, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Remove", color = C.Red, fontSize = 14.sp, modifier = Modifier.clickable { deleting = p }.padding(12.dp))
                    }
                }
            }
            Panel(Modifier.weight(1f)) {
                H2("Add a promotion")
                PickField("Product", product?.let { "${it.code} ${it.name}" } ?: "") { picking = true }
                Box {
                    PickField("Store", store) { storeMenu = true }
                    DropdownMenu(expanded = storeMenu, onDismissRequest = { storeMenu = false }) {
                        (listOf("All stores") + stores.map { it.second }).forEach { s ->
                            DropdownMenuItem(text = { Text(s) }, onClick = { store = s; storeMenu = false })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(start, { start = it }, Modifier.weight(1f), label = { Text("Starts (MM/DD)") }, singleLine = true)
                    OutlinedTextField(end, { end = it }, Modifier.weight(1f), label = { Text("Ends (MM/DD)") }, singleLine = true)
                }
                OutlinedTextField(deal, { deal = it }, Modifier.fillMaxWidth(), label = { Text("Deal, e.g. 2 for \$5") }, singleLine = true)
                PrimaryButton("Add promotion", Modifier.fillMaxWidth(), enabled = product != null) {
                    val p = product ?: return@PrimaryButton
                    repo.addPromo(p.code, p.name, store, start.trim(), end.trim(), deal.trim())
                    product = null; start = ""; end = ""; deal = ""
                    bump()
                }
            }
        }
    }
    if (picking) ProductPicker(onPick = { product = it; picking = false }, onDismiss = { picking = false })
    deleting?.let { p ->
        ConfirmDialog("Remove this promotion?", "${p.code} ${p.name} · ${p.deal}", "Remove",
            onConfirm = { repo.deletePromo(p.id); bump() }, onDismiss = { deleting = null })
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
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Panel(Modifier.weight(1.4f)) {
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
            Panel(Modifier.weight(1f)) {
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
