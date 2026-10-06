package com.route0465.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun SetupScreen(v: Int, bump: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val scope = rememberCoroutineScope()

    var path by remember { mutableStateOf(Prefs.xsalesPath(ctx)) }
    var email by remember { mutableStateOf(Prefs.orderEmail(ctx)) }
    var effective by remember { mutableStateOf(LocalDate.now().format(Fmt.mdy)) }
    var rateMsg by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<LocalDate?>(null) }
    var backupMsg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val backups = remember(v) { if (XSales.hasAccess(ctx)) DataBackup.list() else emptyList() }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) restoreUri = uri }

    val found = remember(v, path) { if (XSales.hasAccess(ctx)) XSales.folder(ctx)?.path else null }
    val rates = remember(v) { repo.rateSummary() }
    val order = remember(v) { repo.orderItems() }
    val stores = remember(v) { repo.stores() }
    val products = remember(v) { repo.productCount() }
    val lastDay = remember(v) { repo.days().firstOrNull() }
    val log = remember(v) { repo.logEntries(25) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val eff = parseLooseDate(effective) ?: LocalDate.now()
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    try { RatesImport.import(ctx, uri, eff) } catch (e: Exception) { RatesImport.Result(0, 0, 0, listOf("Couldn't read that file: ${e.message}")) }
                }
                rateMsg = "Loaded ${r.market} market rates and ${r.commission} commission/credit rows, effective ${eff.format(Fmt.mdy)} unless the file gives a date. Skipped ${r.skipped} rows." +
                    if (r.notes.isNotEmpty()) "\n" + r.notes.joinToString("\n") else ""
                bump()
            }
        }
    }

    ScreenColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Panel {
                    H2("Rates")
                    Muted("Upload a CSV or Excel file with product code, market rate, commission % and credit %. Same column names as Taco-Boys. New rates take effect from the date below (or the file's own date column); past days keep the rates they were figured with. Blank credit % uses 10% for GV 2933–2938 and 16% for everything else.")
                    Text(rates, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(effective, { effective = it }, Modifier.fillMaxWidth(), label = { Text("Effective date (MM/DD/YYYY)") }, singleLine = true)
                    PrimaryButton("Upload rates file", Modifier.fillMaxWidth()) {
                        picker.launch(arrayOf("text/*", "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream"))
                    }
                    rateMsg?.let { Banner(it, C.GreenSoft, C.GreenDark) }
                }

                Panel {
                    H2("Order guide")
                    OutlinedTextField(email, { email = it; Prefs.setOrderEmail(ctx, it) }, Modifier.fillMaxWidth(), label = { Text("Send orders to (email)") }, singleLine = true)
                    Muted("Your products, top to bottom in the order you want them.", 14)
                    order.forEachIndexed { i, item ->
                        HorizontalDivider(color = C.Divider)
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}.  ${item.code}  ${item.name}", fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text("▲", fontSize = 20.sp, color = if (i > 0) C.Ink else C.Line,
                                modifier = Modifier.clickable(enabled = i > 0) { repo.moveOrderItem(item.code, true); bump() }.padding(12.dp))
                            Text("▼", fontSize = 20.sp, color = if (i < order.size - 1) C.Ink else C.Line,
                                modifier = Modifier.clickable(enabled = i < order.size - 1) { repo.moveOrderItem(item.code, false); bump() }.padding(12.dp))
                            Text("Remove", fontSize = 14.sp, color = C.Red,
                                modifier = Modifier.clickable { repo.removeOrderItem(item.code); bump() }.padding(12.dp))
                        }
                    }
                    SecondaryButton("Add a product") { picking = true }
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Panel {
                    H2("XSales folder")
                    Text(found?.let { "Using $it" } ?: "Not found yet" + if (!XSales.hasAccess(ctx)) " (allow file access on Home first)" else "", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(path, { path = it }, Modifier.fillMaxWidth(), label = { Text("Folder path (leave blank to find it automatically)") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("Save folder") { Prefs.setXsalesPath(ctx, path); bump() }
                        SecondaryButton("Find automatically") { path = ""; Prefs.setXsalesPath(ctx, ""); bump() }
                    }
                    var folders by remember { mutableStateOf<List<java.io.File>?>(null) }
                    SecondaryButton("Show all XSales folders") {
                        scope.launch { folders = withContext(Dispatchers.IO) { if (XSales.hasAccess(ctx)) XSales.candidates() else emptyList() } }
                    }
                    folders?.let { list ->
                        if (list.isEmpty()) Muted("No folder with ${XSales.AFT} found.", 14)
                        list.forEach { d ->
                            HorizontalDivider(color = C.Divider)
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(d.path + if (XSales.isProd(d)) "  [prod]" else "", fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Text("Use this", color = C.Green, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                    modifier = Modifier.clickable { path = d.path; Prefs.setXsalesPath(ctx, d.path); bump() }.padding(12.dp))
                            }
                        }
                    }
                }

                Panel {
                    H2("Route 0465 stores")
                    if (stores.isEmpty()) Muted("Stores load from XSales with your first import.")
                    stores.forEach { (_, name) -> Text(name, fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp)) }
                    Muted("Products: $products, read from XSales at each import.", 14)
                }

                Panel {
                    H2("Backups")
                    Muted("Saved automatically every day to Documents/${DataBackup.FOLDER} on this tablet. Point a sync app (Autosync for Google Drive) at that folder to copy them off the tablet. Keeps the last 30 days.", 14)
                    Text(backups.firstOrNull()?.let { "Latest: ${it.name}" } ?: "No backups yet", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("Back up now") {
                            scope.launch {
                                backupMsg = withContext(Dispatchers.IO) {
                                    try { "Saved ${DataBackup.now(ctx).name}" to true } catch (e: Exception) { "Backup failed: ${e.message}" to false }
                                }
                                bump()
                            }
                        }
                        SecondaryButton("Restore from backup") {
                            restorePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))
                        }
                    }
                    backupMsg?.let { (msg, ok) -> if (ok) Banner(msg, C.GreenSoft, C.GreenDark) else Banner(msg, C.AmberSoft, C.Amber) }
                }

                Panel {
                    H2("Imports")
                    if (lastDay != null) {
                        Muted("Remove the import for ${lastDay.date.format(Fmt.full)} so it can be imported again (for testing, or after uploading rates).", 14)
                        SecondaryButton("Remove ${lastDay.date.format(Fmt.day)} import") { confirmRemove = lastDay.date }
                    }
                    if (log.isEmpty()) Muted("Nothing yet.")
                    log.forEach { (at, msg) ->
                        HorizontalDivider(color = C.Divider)
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(at, fontSize = 12.sp, color = C.Muted)
                            Text(msg, fontSize = 14.sp)
                        }
                    }
                    Muted("Last background check: ${Prefs.lastCheck(ctx).ifEmpty { "not yet" }}", 13)
                }
            }
        }
    }
    if (picking) ProductPicker(onPick = { repo.addOrderItem(it.code); picking = false; bump() }, onDismiss = { picking = false })
    restoreUri?.let { uri ->
        ConfirmDialog(
            "Restore from backup?", "Replaces everything in this app with what's in that backup. Your current data is saved first as a \"before-restore\" file in the backups folder, just in case.", "Restore",
            onConfirm = {
                scope.launch {
                    backupMsg = withContext(Dispatchers.IO) {
                        try { DataBackup.restore(ctx, uri) to true } catch (e: Exception) { "Restore failed: ${e.message}" to false }
                    }
                    bump()
                }
            },
            onDismiss = { restoreUri = null },
        )
    }
    confirmRemove?.let { d ->
        ConfirmDialog(
            "Remove ${d.format(Fmt.day)}?", "Deletes that day's pay, sales and inventory from this app so it can be imported again. XSales isn't touched.", "Remove",
            onConfirm = { repo.deleteDay(d); bump() }, onDismiss = { confirmRemove = null },
        )
    }
}
