package com.route0465.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Asks for the Nearby devices (Bluetooth) permission when needed, then runs the action. */
@Composable
fun rememberBluetoothGate(onDenied: () -> Unit): ((() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pending; pending = null
        if (ok) p?.invoke() else onDenied()
    }
    return { action ->
        if (!Zebra.needsPermission(ctx)) action()
        else if (Build.VERSION.SDK_INT >= 31) { pending = action; launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }
    }
}

fun openBluetoothSettings(ctx: Context) {
    runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private class SheetItem(val p: UpcItem, qty: String) {
    var qty by mutableStateOf(qty)
}

private fun casesNote(qty: String, pack: String): String {
    val q = qty.toIntOrNull() ?: return ""
    val c = pack.toIntOrNull() ?: return ""
    if (q <= 0 || c <= 0) return ""
    val full = q / c
    val rest = q % c
    return when {
        full == 0 -> ""
        rest == 0 -> "= $full case" + if (full == 1) "" else "s"
        else -> "= $full case" + (if (full == 1) "" else "s") + " + $rest"
    }
}

@Composable
fun ScanSheetScreen(v: Int, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { Db.get(ctx) }
    val upcs = remember { runCatching { Upc.all(ctx) }.getOrDefault(emptyList()) }
    val byCode = remember(upcs) { upcs.associateBy { it.code } }
    val stores = remember(v) { repo.stores() }

    val draft = remember { SheetDraft.load(ctx) }
    var storeCode by remember { mutableStateOf(draft.storeCode) }
    var storeName by remember { mutableStateOf(draft.storeName) }
    var date by remember { mutableStateOf(draft.date.ifBlank { LocalDate.now().format(Fmt.mdy) }) }
    var route by remember { mutableStateOf(draft.route) }
    val items = remember { mutableStateListOf<SheetItem>().apply { draft.items.forEach { (c, q) -> byCode[c]?.let { add(SheetItem(it, q)) } } } }

    fun persist() = SheetDraft.save(ctx, SheetDraft.Draft(storeCode, storeName, date, route, items.map { it.p.code to it.qty }))

    var pickStore by remember { mutableStateOf(false) }
    var pickProduct by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<SheetItem?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var choosePrinter by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, ok
    var printerName by remember(v) { mutableStateOf(PrinterPrefs.name(ctx)) }

    val gate = rememberBluetoothGate { msg = "465stats needs the Nearby devices permission to talk to the printer. Allow it and try again." to false }

    fun doPrint() {
        val day = parseLooseDate(date)
        val problem = when {
            storeName.isBlank() -> "Pick the store first."
            day == null -> "The date doesn't look right. Tap it to pick from the calendar."
            items.isEmpty() -> "Add at least one product."
            items.any { (it.qty.toIntOrNull() ?: 0) <= 0 } -> "Every product needs a quantity in eaches. Remove any you're not delivering."
            PrinterPrefs.address(ctx).isBlank() -> "Choose your printer first."
            else -> null
        }
        if (problem != null) {
            msg = problem to false
            if (PrinterPrefs.address(ctx).isBlank() && storeName.isNotBlank() && items.isNotEmpty()) gate { choosePrinter = true }
            return
        }
        val sheet = SheetPrint(storeCode, storeName, day!!, route.trim().ifEmpty { "0465" },
            items.map { SheetLine(it.p.code, it.p.desc, it.p.upc, it.p.casePack, it.qty.toInt()) })
        gate {
            busy = true; msg = null
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching { Zebra.print(ctx) { lang, dots, feed -> SheetLayout.build(lang, dots, feed, sheet, appVersion(ctx)) } }
                }
                busy = false
                msg = r.fold(
                    { "Sent to ${PrinterPrefs.name(ctx)}: ${sheet.lines.size} products, ${sheet.lines.sumOf { it.qty }} eaches." to true },
                    { (it.message ?: "Printing failed.") to false },
                )
                if (r.isSuccess) repo.log("Scan sheet printed for ${sheet.storeName}: ${sheet.lines.size} products")
            }
        }
    }

    ScreenColumn {
        Panel {
            H2("Delivery")
            PickField("Store", if (storeName.isBlank()) "" else storeName + if (storeCode.isNotBlank()) "  ·  #$storeCode" else "") { pickStore = true }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CalendarField("Date", date, LocalDate.now(), Modifier.weight(1f)) { date = it; persist() }
                OutlinedTextField(
                    route, { route = it.filter { c -> c.isLetterOrDigit() }.take(8); persist() }, Modifier.width(140.dp),
                    singleLine = true, label = { Text("Route") },
                )
            }
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    H2("Products")
                    val total = items.sumOf { it.qty.toIntOrNull() ?: 0 }
                    Muted(if (items.isEmpty()) "Nothing added yet" else "${items.size} products · $total eaches", 14)
                }
                if (items.isNotEmpty()) TextButton(onClick = { clearing = true }) { Text("Clear all", color = C.Red, fontWeight = FontWeight.Bold) }
            }
            items.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(color = C.Divider)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(row.p.code, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        Text(row.p.desc, fontSize = 14.sp, maxLines = 2)
                        Text("Case of ${row.p.casePack.ifEmpty { "?" }}  ·  ${row.p.upc}", fontSize = 13.sp, color = C.Muted)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        OutlinedTextField(
                            row.qty, { s -> row.qty = s.filter { c -> c.isDigit() }.take(5); persist() }, Modifier.width(120.dp),
                            singleLine = true, label = { Text("Eaches") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = (row.qty.toIntOrNull() ?: 0) <= 0,
                        )
                        val note = casesNote(row.qty, row.p.casePack)
                        if (note.isNotEmpty()) Text(note, fontSize = 13.sp, color = C.Muted)
                    }
                    Text("✕", fontSize = 22.sp, color = C.Red, modifier = Modifier.clickable { removing = row }.padding(10.dp))
                }
            }
            SecondaryButton("+ Add product", Modifier.fillMaxWidth()) { pickProduct = true }
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Printer", fontSize = 13.sp, color = C.Muted, fontWeight = FontWeight.SemiBold)
                    Text(printerName.ifBlank { "None chosen yet" }, fontSize = 17.sp, color = if (printerName.isBlank()) C.Muted else C.Ink)
                }
                if (printerName.isBlank()) SecondaryButton("Choose printer") { gate { choosePrinter = true } }
                else TextButton(onClick = { SetupLink.pendingSection = "Printer"; go(Screen.Setup) }) { Text("Setup › Printer", color = C.Green, fontWeight = FontWeight.Bold) }
            }
            PrimaryButton(if (busy) "Printing…" else "Print scan sheet", Modifier.fillMaxWidth(), enabled = !busy, big = true) { doPrint() }
            msg?.let { (t, ok) -> Banner(t, if (ok) C.GreenSoft else C.AmberSoft, if (ok) C.GreenDark else C.Amber) }
        }
    }

    if (pickStore) StorePickerDialog(stores, { (c, n) -> storeCode = c; storeName = n; persist(); pickStore = false }) { pickStore = false }
    if (pickProduct) UpcPickerDialog(upcs, items.map { it.p.code }.toSet(), { p ->
        items.add(SheetItem(p, "")); persist(); pickProduct = false
    }) { pickProduct = false }
    removing?.let { r ->
        ConfirmDialog("Remove ${r.p.code}?", "Take ${r.p.desc} off this sheet?", "Remove", { items.remove(r); persist() }) { removing = null }
    }
    if (clearing) ConfirmDialog("Clear the sheet?", "Removes every product from this sheet. The store, date and route stay.", "Clear all",
        { items.clear(); persist(); msg = null }) { clearing = false }
    if (choosePrinter) PrinterChooserDialog({ d -> PrinterPrefs.setPrinter(ctx, d.address, d.name); printerName = d.name; choosePrinter = false; msg = null }) { choosePrinter = false }
}

@Composable
private fun StorePickerDialog(stores: List<Pair<String, String>>, onPick: (Pair<String, String>) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = remember(q, stores) {
        val t = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        stores.filter { (c, n) -> val hay = "$c $n".lowercase(); t.all { hay.contains(it) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Pick the store") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search name or number") })
                if (stores.isEmpty()) Muted("No stores yet. They load from XSales with your first import, or add them in Setup › Stores and products.")
                else if (list.isEmpty()) Muted("Nothing matches.")
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list) { s ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(vertical = 13.dp)) {
                            Text(s.second, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Text("#${s.first}", fontSize = 14.sp, color = C.Muted)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun UpcPickerDialog(all: List<UpcItem>, already: Set<String>, onPick: (UpcItem) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = remember(q, all) { Upc.search(all, q).take(120) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Add a product") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search code, description or UPC") })
                if (list.isEmpty()) Muted("Nothing matches.")
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(list, key = { it.code }) { p ->
                        val ok = p.upc.isNotEmpty() && p.code !in already
                        Column(Modifier.fillMaxWidth().clickable(enabled = ok) { onPick(p) }.padding(vertical = 11.dp)) {
                            Text("${p.code}   ${p.desc}", fontSize = 16.sp, color = if (ok) C.Ink else C.Muted)
                            when {
                                p.code in already -> Text("Already on the sheet", fontSize = 13.sp, color = C.Muted)
                                p.upc.isEmpty() -> Text("No barcode on file", fontSize = 13.sp, color = C.Amber)
                            }
                        }
                    }
                }
            }
        },
    )
}

/** Lists the tablet's paired Bluetooth devices, printers first. Pairing itself happens in Android's Bluetooth settings. */
@Composable
fun PrinterChooserDialog(onPick: (Zebra.Paired) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val list = remember(tick) { Zebra.paired(ctx) }
    val on = remember(tick) { Zebra.bluetoothOn(ctx) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { tick++ }) { Text("Refresh") } },
        title = { Text("Choose the printer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!on) Muted("Bluetooth is off. Turn it on, then tap Refresh.")
                else if (list.isEmpty()) Muted("No paired devices. Pair the printer in Bluetooth settings, then tap Refresh.")
                else Muted("A Zebra usually shows its serial number as its name.", 13)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(list, key = { it.address }) { d ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(d) }.padding(vertical = 12.dp)) {
                            Text(d.name, fontSize = 17.sp, fontWeight = if (d.isPrinter) FontWeight.Bold else FontWeight.Normal)
                            Text(d.address + if (d.isPrinter) "  ·  printer" else "", fontSize = 13.sp, color = C.Muted)
                        }
                    }
                }
                Text(
                    "Open Bluetooth settings to pair or re-pair ›", color = C.Green, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.clickable { openBluetoothSettings(ctx) }.background(C.GreenSoft).padding(12.dp).fillMaxWidth(),
                )
            }
        },
    )
}
