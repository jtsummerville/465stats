package com.route0465.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Setup is a menu of sections. Each section shows its settings read-only; changing anything takes Edit, then Save. */
private enum class SetupSection(val title: String) {
    Rates("Rates"),
    OrderGuide("Order guide"),
    Emails("Email addresses"),
    Folder("XSales folder"),
    Backups("Backups and password"),
    Stores("Stores and products"),
    Imports("Imports and activity log"),
    HowItWorks("How it works"),
}

@Composable
fun SetupScreen(v: Int, bump: () -> Unit) {
    var sectionName by rememberSaveable { mutableStateOf("") }
    if (HowLink.pendingTopic != null && sectionName != SetupSection.HowItWorks.name) sectionName = SetupSection.HowItWorks.name
    val back = { sectionName = "" }
    when (if (sectionName.isEmpty()) null else SetupSection.valueOf(sectionName)) {
        null -> SetupMenu(v) { sectionName = it.name }
        SetupSection.Rates -> RatesSection(v, bump, back)
        SetupSection.OrderGuide -> OrderGuideSection(v, bump, back)
        SetupSection.Emails -> EmailsSection(back)
        SetupSection.Folder -> FolderSection(v, bump, back)
        SetupSection.Backups -> BackupsSection(v, bump, back)
        SetupSection.Stores -> StoresSection(v, back)
        SetupSection.Imports -> ImportsSection(v, bump, back)
        SetupSection.HowItWorks -> HowItWorksSection(back)
    }
}

// ---------------------------------------------------------------- menu

@Composable
private fun SetupMenu(v: Int, open: (SetupSection) -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val summaries = remember(v) {
        val order = repo.orderItems()
        val last = repo.days().firstOrNull()
        val backups = if (XSales.hasAccess(ctx)) DataBackup.list() else emptyList()
        mapOf(
            SetupSection.Rates to repo.rateSummary(),
            SetupSection.OrderGuide to "${order.size} products" + Prefs.orderEmail(ctx).let { if (it.isBlank()) "" else " · orders go to $it" },
            SetupSection.Emails to listOf(Prefs.orderEmail(ctx), Prefs.bossEmails(ctx)).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "Not set yet" },
            SetupSection.Folder to ((if (XSales.hasAccess(ctx)) XSales.folder(ctx)?.path else null) ?: "Not found yet"),
            SetupSection.Backups to (if (Prefs.backupPassword(ctx).isEmpty()) "Off: no password set" else "Password set · " + (backups.firstOrNull()?.name ?: "no backups yet")),
            SetupSection.Stores to "${repo.stores().size} stores · ${repo.productCount()} products",
            SetupSection.Imports to (last?.let { "Last import ${it.date.format(Fmt.day)}" } ?: "No imports yet"),
            SetupSection.HowItWorks to "The logic behind pay, sales, Suggested, promos and the rest",
        )
    }
    ScreenColumn {
        Muted("Tap a section to look at it. Nothing changes until you tap Edit and then Save.", 14)
        Panel(pad = 0.dp) {
            SetupSection.entries.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = C.Divider)
                Row(
                    Modifier.fillMaxWidth().clickable { open(s) }.heightIn(min = 76.dp).padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(summaries[s] ?: "", fontSize = 14.sp, color = C.Muted, maxLines = 2)
                    }
                    Text("›", fontSize = 30.sp, color = C.Muted)
                }
            }
        }
    }
}

@Composable
private fun SectionTop(title: String, back: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "‹  Setup", color = C.Green, fontWeight = FontWeight.Bold, fontSize = 17.sp,
            modifier = Modifier.clickable { back() }.padding(vertical = 10.dp, horizontal = 4.dp),
        )
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun ReadRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, fontSize = 13.sp, color = C.Muted, fontWeight = FontWeight.SemiBold)
        Text(value.ifBlank { "Not set" }, fontSize = 17.sp, color = if (value.isBlank()) C.Muted else C.Ink)
    }
}

@Composable
private fun EditSaveRow(editing: Boolean, onEdit: () -> Unit, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!editing) SecondaryButton("Edit") { onEdit() }
        else {
            PrimaryButton("Save") { onSave() }
            SecondaryButton("Cancel") { onCancel() }
        }
    }
}

private fun validEmails(s: String): Boolean {
    val list = s.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    return list.all { Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(it) }
}

// ---------------------------------------------------------------- rates

@Composable
private fun RatesSection(v: Int, bump: () -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val scope = rememberCoroutineScope()
    val summary = remember(v) { repo.rateSummary() }
    var asking by remember { mutableStateOf(false) }
    var effective by remember { mutableStateOf(LocalDate.now().format(Fmt.mdy)) }
    var msg by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val eff = parseLooseDate(effective) ?: LocalDate.now()
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    try { RatesImport.import(ctx, uri, eff) } catch (e: Exception) { RatesImport.Result(0, 0, 0, listOf("Couldn't read that file: ${e.message}")) }
                }
                msg = "Loaded ${r.market} market rates and ${r.commission} commission/credit rows, effective ${eff.format(Fmt.mdy)} unless the file gives a date. Skipped ${r.skipped} rows." +
                    if (r.notes.isNotEmpty()) "\n" + r.notes.joinToString("\n") else ""
                bump()
            }
        }
    }

    ScreenColumn {
        SectionTop("Rates", back)
        Panel {
            ReadRow("On file", summary)
            Muted("Rates are kept by date. A new upload adds rates from its effective date on; past days keep the rates they were figured with. Blank credit % uses 10% for GV 2933–2938 and 16% for everything else.", 14)
            PrimaryButton("Upload new rates file") { asking = true }
            msg?.let { Banner(it, C.GreenSoft, C.GreenDark) }
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Upload new rates") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Pick a CSV or Excel file with product code, market rate, commission % and credit %. The rates start on this date:")
                    OutlinedTextField(effective, { effective = it }, Modifier.fillMaxWidth(), label = { Text("Effective date (MM/DD/YYYY)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    picker.launch(arrayOf("text/*", "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream"))
                }) { Text("Choose file", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- emails

@Composable
private fun EmailsSection(back: () -> Unit) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var order by remember { mutableStateOf(Prefs.orderEmail(ctx)) }
    var bosses by remember { mutableStateOf(Prefs.bossEmails(ctx)) }
    var error by remember { mutableStateOf<String?>(null) }

    ScreenColumn {
        SectionTop("Email addresses", back)
        Panel {
            if (!editing) {
                ReadRow("Orders go to", Prefs.orderEmail(ctx))
                ReadRow("End of Day paperwork goes to", Prefs.bossEmails(ctx))
            } else {
                OutlinedTextField(order, { order = it }, Modifier.fillMaxWidth(), label = { Text("Orders go to") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(bosses, { bosses = it }, Modifier.fillMaxWidth(), label = { Text("End of Day paperwork goes to (separate with commas)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                Muted("Use full addresses, like name@gmail.com.", 13)
            }
            EditSaveRow(
                editing,
                onEdit = { order = Prefs.orderEmail(ctx); bosses = Prefs.bossEmails(ctx); error = null; editing = true },
                onCancel = { editing = false; error = null },
                onSave = {
                    if (!validEmails(order) || !validEmails(bosses)) {
                        error = "One of those isn't a full email address. Check for the @ and the .com part."
                    } else {
                        Prefs.setOrderEmail(ctx, order.trim()); Prefs.setBossEmails(ctx, bosses.trim())
                        editing = false; error = null
                    }
                },
            )
            error?.let { Banner(it, C.AmberSoft, C.Amber) }
        }
    }
}

// ---------------------------------------------------------------- XSales folder

@Composable
private fun FolderSection(v: Int, bump: () -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val found = remember(v) { if (XSales.hasAccess(ctx)) XSales.folder(ctx)?.path else null }
    var editing by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf(Prefs.xsalesPath(ctx)) }
    var folders by remember { mutableStateOf<List<java.io.File>?>(null) }

    ScreenColumn {
        SectionTop("XSales folder", back)
        Panel {
            ReadRow("Reading from", found ?: ("Not found yet" + if (!XSales.hasAccess(ctx)) " (allow file access on Home first)" else ""))
            ReadRow("How it's chosen", if (Prefs.xsalesPath(ctx).isBlank()) "Automatically (the \"Ole prd\" folder)" else "Set by you")
            if (editing) {
                OutlinedTextField(path, { path = it }, Modifier.fillMaxWidth(), label = { Text("Folder path (blank = find automatically)") }, singleLine = true)
                SecondaryButton("Show XSales folders on this tablet") {
                    scope.launch { folders = withContext(Dispatchers.IO) { if (XSales.hasAccess(ctx)) XSales.candidates() else emptyList() } }
                }
                folders?.let { list ->
                    if (list.isEmpty()) Muted("No folder with ${XSales.AFT} found.", 14)
                    list.forEach { d ->
                        HorizontalDivider(color = C.Divider)
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(d.path + if (XSales.isProd(d)) "  [prod]" else "", fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text("Use this", color = C.Green, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                modifier = Modifier.clickable { path = d.path }.padding(12.dp))
                        }
                    }
                }
            }
            EditSaveRow(
                editing,
                onEdit = { path = Prefs.xsalesPath(ctx); folders = null; editing = true },
                onCancel = { editing = false },
                onSave = { Prefs.setXsalesPath(ctx, path); editing = false; bump() },
            )
        }
    }
}

// ---------------------------------------------------------------- backups

@Composable
private fun BackupsSection(v: Int, bump: () -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasPw = remember(v) { Prefs.backupPassword(ctx).isNotEmpty() }
    val backups = remember(v) { if (XSales.hasAccess(ctx)) DataBackup.list() else emptyList() }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var changingPw by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) restoreUri = uri }

    ScreenColumn {
        SectionTop("Backups and password", back)
        Panel {
            ReadRow("Password", if (hasPw) "Set  ••••••••" else "")
            if (!hasPw) Banner("Backups are off until you set a password.", C.AmberSoft, C.Amber)
            ReadRow("Latest backup", backups.firstOrNull()?.name ?: "None yet")
            Muted("Saved automatically every day to Documents/${DataBackup.FOLDER}, locked with your password. Keeps the last 30 days. Write the password down somewhere safe; without it nobody, including you, can open a backup.", 14)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(if (hasPw) "Change password" else "Set password") { changingPw = true }
                SecondaryButton("Back up now", enabled = hasPw) {
                    scope.launch {
                        msg = withContext(Dispatchers.IO) {
                            try { "Saved ${DataBackup.now(ctx).name}" to true } catch (e: Exception) { "Backup failed: ${e.message}" to false }
                        }
                        bump()
                    }
                }
            }
            HorizontalDivider(color = C.Divider)
            Muted("Restoring replaces everything in this app with a backup. Your current data is saved first, just in case.", 13)
            SecondaryButton("Restore from a backup…") {
                restorePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))
            }
            msg?.let { (m, ok) -> if (ok) Banner(m, C.GreenSoft, C.GreenDark) else Banner(m, C.AmberSoft, C.Amber) }
        }
    }

    if (changingPw) PasswordDialog(ctx, hasPw, onDone = { result ->
        changingPw = false
        if (result != null) {
            scope.launch {
                msg = withContext(Dispatchers.IO) {
                    try {
                        val f = DataBackup.passwordChanged(ctx)
                        "Password saved." + (f?.let { " Saved ${it.name} with it." } ?: "") to true
                    } catch (e: Exception) { "Password saved, but the backup failed: ${e.message}" to false }
                }
                bump()
            }
        }
    })

    restoreUri?.let { uri ->
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("Restore from backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Replaces everything in this app with what's in that backup. Your current data is saved first as a \"before-restore\" file in the backups folder.")
                    OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), label = { Text("That backup's password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = pw.trim()
                    restoreUri = null
                    scope.launch {
                        msg = withContext(Dispatchers.IO) {
                            try { DataBackup.restore(ctx, uri, p) to true } catch (e: Exception) { "Restore failed: ${e.message}" to false }
                        }
                        bump()
                    }
                }) { Text("Restore", color = C.Red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Cancel") } },
        )
    }
}

/** Changing the password needs the current one first; the new one is typed twice. Calls onDone(newPassword) or onDone(null). */
@Composable
private fun PasswordDialog(ctx: Context, hasPw: Boolean, onDone: (String?) -> Unit) {
    var current by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val vt = if (show) VisualTransformation.None else PasswordVisualTransformation()
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(if (hasPw) "Change backup password" else "Set backup password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasPw) OutlinedTextField(current, { current = it }, Modifier.fillMaxWidth(), label = { Text("Current password") }, singleLine = true, visualTransformation = vt)
                OutlinedTextField(new1, { new1 = it }, Modifier.fillMaxWidth(), label = { Text("New password") }, singleLine = true, visualTransformation = vt)
                OutlinedTextField(new2, { new2 = it }, Modifier.fillMaxWidth(), label = { Text("New password again") }, singleLine = true, visualTransformation = vt)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { show = !show }) {
                    Checkbox(show, { show = it }, colors = CheckboxDefaults.colors(checkedColor = C.Green))
                    Text("Show passwords")
                }
                error?.let { Text(it, color = C.Red) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = new1.trim()
                error = when {
                    hasPw && current.trim() != Prefs.backupPassword(ctx) -> "The current password isn't right."
                    n.length < 4 -> "Use at least 4 characters."
                    n != new2.trim() -> "The two new passwords don't match."
                    else -> null
                }
                if (error == null) {
                    Prefs.setBackupPassword(ctx, n)
                    onDone(n)
                }
            }) { Text("Save", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- stores

@Composable
private fun StoresSection(v: Int, back: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val stores = remember(v) { repo.stores() }
    val products = remember(v) { repo.productCount() }
    ScreenColumn {
        SectionTop("Stores and products", back)
        Panel {
            Muted("These come from XSales at each import; nothing to edit here.", 14)
            ReadRow("Products", "$products")
            HorizontalDivider(color = C.Divider)
            Text("Route 0465 stores (${stores.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (stores.isEmpty()) Muted("Stores load from XSales with your first import.")
            stores.forEach { (_, name) -> Text(name, fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp)) }
        }
    }
}

// ---------------------------------------------------------------- imports + log

@Composable
private fun ImportsSection(v: Int, bump: () -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val lastDay = remember(v) { repo.days().firstOrNull() }
    val log = remember(v) { repo.logEntries(40) }
    var confirmRemove by remember { mutableStateOf<LocalDate?>(null) }
    var showLog by remember { mutableStateOf(false) }

    ScreenColumn {
        SectionTop("Imports and activity log", back)
        Panel {
            ReadRow("Last import", lastDay?.let { "${it.date.format(Fmt.full)} · ${it.importedAt}" } ?: "")
            if (lastDay != null) {
                Muted("Removing an import deletes that day's pay, sales and inventory from this app. XSales isn't touched.", 14)
                SecondaryButton("Remove ${lastDay.date.format(Fmt.day)} import…") { confirmRemove = lastDay.date }
            }
            HorizontalDivider(color = C.Divider)
            Row(Modifier.fillMaxWidth().clickable { showLog = !showLog }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Activity log", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Muted(if (log.isEmpty()) "empty" else "${log.size} entries", 13)
                Text(if (showLog) "  ▴" else "  ▾", fontSize = 18.sp, color = C.Muted)
            }
            if (showLog) {
                if (log.isEmpty()) Muted("Nothing yet.")
                log.forEach { (at, m) ->
                    HorizontalDivider(color = C.Divider)
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(at, fontSize = 12.sp, color = C.Muted)
                        Text(m, fontSize = 14.sp)
                    }
                }
                Muted("Last background check: ${Prefs.lastCheck(ctx).ifEmpty { "not yet" }}", 13)
            }
        }
    }
    confirmRemove?.let { d ->
        ConfirmDialog(
            "Remove ${d.format(Fmt.day)}?", "Deletes that day's pay, sales and inventory from this app so it can be imported again. XSales isn't touched.", "Remove",
            onConfirm = { repo.deleteDay(d); bump() }, onDismiss = { confirmRemove = null },
        )
    }
}

// ---------------------------------------------------------------- order guide (its own page, built for hundreds of products)

@Composable
private fun OrderGuideSection(v: Int, bump: () -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val scope = rememberCoroutineScope()
    val items = remember(v) { repo.orderItems() }
    var editing by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<OrderItem?>(null) }
    var removing by remember { mutableStateOf<OrderItem?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var pendingFile by remember { mutableStateOf<List<String>?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val codes = withContext(Dispatchers.IO) { runCatching { readCodeList(ctx, uri) }.getOrNull() }
            if (codes.isNullOrEmpty()) msg = "Couldn't find any product codes in that file." else pendingFile = codes
        }
    }

    val shown = remember(items, q) {
        val s = q.trim().lowercase()
        val all = items.mapIndexed { i, x -> i to x }
        if (s.isEmpty()) all else all.filter { (_, x) -> x.code.lowercase().contains(s) || x.name.lowercase().contains(s) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTop("Order guide", back) {
            if (!editing) SecondaryButton("Edit") { editing = true } else PrimaryButton("Done") { editing = false }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, label = { Text("Find in your order guide") })
            Muted("${items.size} products", 14)
        }
        if (editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Add products") { adding = true }
                SecondaryButton("Load list from file") { filePicker.launch(arrayOf("text/*", "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream")) }
                if (items.isNotEmpty()) SecondaryButton("Clear all") { confirmClear = true }
            }
            Muted("Tap a product to move it to any position. Use ▲ ▼ for small moves.", 13)
        } else {
            Muted("Locked. Tap Edit to add, remove or reorder products.", 13)
        }
        msg?.let { Banner(it, C.AmberSoft, C.Amber) }

        Panel(Modifier.weight(1f).fillMaxWidth(), pad = 0.dp) {
            if (items.isEmpty()) {
                Column(Modifier.padding(20.dp)) { Muted("No products yet. Tap Edit, then Add products or Load list from file.") }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(shown, key = { _, p -> p.second.code }) { idx, (pos, item) ->
                    if (idx > 0) HorizontalDivider(color = C.Divider)
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = editing) { moving = item }.heightIn(min = 52.dp).padding(horizontal = 18.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${pos + 1}.", fontSize = 15.sp, color = C.Muted, modifier = Modifier.width(52.dp))
                        Text(item.code, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(70.dp))
                        Text(item.name.ifEmpty { "(not in XSales products yet)" }, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        if (editing) {
                            Text("▲", fontSize = 20.sp, color = if (pos > 0) C.Ink else C.Line,
                                modifier = Modifier.clickable(enabled = pos > 0) { repo.moveOrderItem(item.code, true); bump() }.padding(10.dp))
                            Text("▼", fontSize = 20.sp, color = if (pos < items.size - 1) C.Ink else C.Line,
                                modifier = Modifier.clickable(enabled = pos < items.size - 1) { repo.moveOrderItem(item.code, false); bump() }.padding(10.dp))
                            Text("Remove", fontSize = 14.sp, color = C.Red, modifier = Modifier.clickable { removing = item }.padding(10.dp))
                        }
                    }
                }
            }
        }
    }

    if (adding) MultiProductPicker(already = items.map { it.code }.toSet(), onAdd = { codes -> repo.addOrderItems(codes); adding = false; bump() }, onDismiss = { adding = false })
    moving?.let { item ->
        var pos by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("Move ${item.code}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(item.name)
                    Text("Now #${item.position} of ${items.size}. Move it to:")
                    OutlinedTextField(pos, { pos = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Position (1 = top)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pos.toIntOrNull()?.let { repo.moveOrderItemTo(item.code, it); bump() }
                    moving = null
                }) { Text("Move", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { moving = null }) { Text("Cancel") } },
        )
    }
    removing?.let { item ->
        ConfirmDialog("Remove ${item.code}?", "${item.name} comes off your order guide.", "Remove",
            onConfirm = { repo.removeOrderItem(item.code); bump() }, onDismiss = { removing = null })
    }
    if (confirmClear) {
        ConfirmDialog("Clear the whole order guide?", "Removes all ${items.size} products. You can add them back or load a list from a file.", "Clear all",
            onConfirm = { repo.clearOrderItems(); bump() }, onDismiss = { confirmClear = false })
    }
    pendingFile?.let { codes ->
        ConfirmDialog(
            "Replace your order guide?",
            "The file has ${codes.size} products. They'll replace the ${items.size} on your order guide, in the file's order.",
            "Replace",
            onConfirm = { repo.replaceOrderItems(codes); msg = null; bump() },
            onDismiss = { pendingFile = null },
        )
    }
}

/** Product codes from a CSV or Excel file, top to bottom: a column headed "code" / "item", else the first column. */
private fun readCodeList(ctx: Context, uri: Uri): List<String> {
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return emptyList()
    val table = if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) RatesImport.readXlsx(bytes)
    else RatesImport.readCsv(String(bytes, Charsets.UTF_8))
    val rows = table.filter { r -> r.any { it.isNotBlank() } }
    if (rows.isEmpty()) return emptyList()
    val head = rows[0].map { it.trim().lowercase() }
    val col = head.indexOfFirst { it == "code" || it.contains("item") || it.contains("product code") || it == "sku" }
    val body = if (col >= 0) rows.drop(1) else rows
    val c = if (col >= 0) col else 0
    return body.mapNotNull { it.getOrNull(c)?.trim()?.takeIf { s -> s.isNotEmpty() } }.distinct()
}

/** Search, tick as many products as you like, add them all at once. */
@Composable
fun MultiProductPicker(already: Set<String>, onAdd: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    var q by remember { mutableStateOf("") }
    val list = remember(q) { repo.products(q, 2000) }
    var picked by remember { mutableStateOf(listOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add products") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search code or name") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Muted("${picked.size} ticked", 13)
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    val addable = list.map { it.code }.filter { it !in already }
                    if (addable.isNotEmpty()) Text(
                        "Tick all ${addable.size} shown", color = C.Green, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.clickable { picked = (picked + addable).distinct() }.padding(6.dp),
                    )
                }
                if (list.isEmpty()) Muted(if (repo.productCount() == 0) "No products yet. They load from XSales with your first import." else "Nothing matches.")
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(list, key = { _, p -> p.code }) { _, p ->
                        val on = p.code in already
                        val ticked = p.code in picked
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !on) { picked = if (ticked) picked - p.code else picked + p.code }
                                .background(if (ticked) C.GreenSoft else Color.Transparent).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(on || ticked, null, enabled = !on, colors = CheckboxDefaults.colors(checkedColor = C.Green))
                            Text("${p.code}   ${p.name}" + if (on) "  (already on)" else "", fontSize = 15.sp, color = if (on) C.Muted else C.Ink)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(picked) }, enabled = picked.isNotEmpty()) { Text("Add ${picked.size}", fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
