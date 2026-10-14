package com.route0465.app

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(v: Int, bump: () -> Unit, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()

    var result by remember { mutableStateOf<ImportResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(Prefs.auto(ctx)) }
    val access = remember(v) { XSales.hasAccess(ctx) }
    val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { bump() }
    val days = remember(v) { repo.days() }
    val stockDates = remember(v) { repo.stockDates() }
    val cases = remember(v) { stockDates.firstOrNull()?.let { d -> repo.stock(d).sumOf { it.cases } } }

    fun runImport(useBefore: Boolean) {
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                try { XSales.runImport(ctx, useBefore) } catch (e: Exception) { ImportResult.Failed(e.message ?: "Unexpected error") }
            }
            result = r
            busy = false
            bump()
        }
    }

    ScreenColumn {
        SetupChecklist(v, access, go)
        if (!access) {
            Banner(
                "To read the XSales backups, Android needs you to allow file access for this app once. The app only copies and reads them; it never changes XSales's files.",
                C.AmberSoft, C.Amber, title = "Allow file access",
            ) {
                SecondaryButton("Allow file access") {
                    if (Build.VERSION.SDK_INT >= 30) {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } else {
                        askStorage.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                    }
                }
            }
        }

        Split {
            Panel(Modifier.part(1.25f), pad = 26.dp) {
                H2("Import today's route")
                Muted("Run End of Day in XSales first, then tap below.")
                PrimaryButton(if (busy) "Importing…" else "End of Day", Modifier.fillMaxWidth(), enabled = !busy && access, big = true) {
                    runImport(false)
                }
                if (busy) CircularProgressIndicator(color = C.Green)
                ResultCard(result, onUseBefore = { runImport(true) }, go = go)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Switch(
                        checked = auto,
                        onCheckedChange = {
                            auto = it
                            Prefs.setAuto(ctx, it)
                            if (it) AutoImport.schedule(ctx) else AutoImport.cancel(ctx)
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = C.Green),
                    )
                    Text("Auto-import", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                }
            }

            Column(Modifier.part(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val todayDay = days.firstOrNull { it.date == today }
                val pStart = Periods.startOf(today)
                val periodPay = days.filter { !it.date.isBefore(pStart) && it.date.isBefore(pStart.plusDays(14)) }.sumOf { it.pay }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Tile("Today's pay", todayDay?.let { Fmt.money(it.pay) } ?: "—", Modifier.weight(1f)) { go(Screen.Pay) }
                    Tile("This pay period", Fmt.money(periodPay), Modifier.weight(1f)) { go(Screen.Pay) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Tile("Today's net sales", todayDay?.let { Fmt.money(it.netSales) } ?: "—", Modifier.weight(1f)) { go(Screen.Sales) }
                    Tile("Cases on hand", cases?.let { Fmt.one(it) } ?: "—", Modifier.weight(1f)) { go(Screen.Inventory) }
                }
                val due = nextOrderDue(today)
                Panel(Modifier.fillMaxWidth(), bg = C.AmberSoft, line = C.AmberLine, onClick = { go(Screen.Order) }) {
                    Text("Next order due", fontSize = 14.sp, color = C.Amber, fontWeight = FontWeight.SemiBold)
                    Text(
                        (if (due == today) "Today" else due.format(Fmt.day)) + " " + Schedule.dueTimeText,
                        fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = C.Ink,
                    )
                }
                if (todayDay != null && todayDay.missingRates.isNotEmpty()) {
                    Banner("No rates for: ${todayDay.missingRates}. Those lines count \$0 in pay until you upload rates (Setup), then remove and re-import today.", C.AmberSoft, C.Amber, title = "Pay is incomplete")
                }
            }
        }
    }
}

@Composable
private fun ResultCard(r: ImportResult?, onUseBefore: () -> Unit, go: (Screen) -> Unit) {
    when (r) {
        null -> {}
        is ImportResult.Imported -> Banner(
            "Read ${r.source}, dated today.\n" +
                "${r.stores} stores · ${r.saleLines} sale lines · ${r.creditLines} credit lines · ${r.stockItems} stock items\n" +
                (if (r.noSaleVisits > 0) "${r.noSaleVisits} no-sale store visit" + (if (r.noSaleVisits == 1) "" else "s") + " (No Store Visit Survey)\n" else "") +
                "Tickets read ${r.ticketsRead}, kept ${r.ticketsRead - r.ticketsDropped}, dropped ${r.ticketsDropped} from other dates.\n" +
                "Pay ${Fmt.money(r.pay)} · Net sales ${Fmt.money(r.netSales)}" +
                if (r.missingRates.isNotEmpty()) "\nNo rates for ${r.missingRates.size} products: ${r.missingRates.joinToString(", ")}" else "",
            C.GreenSoft, C.GreenDark, title = "Imported ${r.date.format(Fmt.full)}",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("See pay") { go(Screen.Pay) }
                SecondaryButton("See sales") { go(Screen.Sales) }
            }
        }
        is ImportResult.AlreadyImported -> Banner(
            "Today is already imported, so nothing was added or changed. To redo it, remove today's import in Setup first.",
            C.Ground, C.Ink, title = "Already imported",
        )
        is ImportResult.WrongDate -> Banner(
            "${r.file} is dated ${r.fileDate?.format(Fmt.mdy) ?: "unknown"}, not today (${r.today.format(Fmt.mdy)}). Nothing was imported. " +
                if (r.beforeIsToday) "The before-End-of-Day backup is dated today, which usually means XSales's End of Day hasn't finished. Finish it in XSales and tap again, or import from the before-End-of-Day backup."
                else "Run End of Day in XSales, then tap again.",
            C.RedSoft, C.Red, title = "Not imported",
        ) {
            if (r.beforeIsToday) SecondaryButton("Import from before-End-of-Day backup") { onUseBefore() }
        }
        is ImportResult.Failed -> Banner(r.message, C.RedSoft, C.Red, title = "Couldn't import")
    }
}

@Suppress("unused")
private val unusedColor = Color.Unspecified

/** One line per backup folder found, with each backup's date, marking the folder the app reads. */
fun describeBackups(ctx: android.content.Context, today: LocalDate): List<String> {
    if (!XSales.hasAccess(ctx)) return listOf("File access isn't allowed yet.")
    val using = XSales.folder(ctx)
    val dirs = (XSales.candidates() + listOfNotNull(using)).distinctBy { it.path }
    if (dirs.isEmpty()) return listOf("No folder with ${XSales.AFT} was found. Set the folder in Setup.")
    val fmt = SimpleDateFormat("MM/dd h:mm a", Locale.US)
    return dirs.map { d ->
        val parts = listOf(XSales.AFT, XSales.BEF).map { name ->
            val b = XSales.inspect(ctx, name, d)
            val dated = b.date?.let { if (it == today) "TODAY" else it.format(Fmt.mdy) } ?: "date unknown"
            if (!b.exists) "$name missing" else "$name $dated (saved ${fmt.format(Date(b.modified))})"
        }
        val tag = if (d.path == using?.path) "USING → " else ""
        tag + d.path + (if (XSales.isProd(d)) "  [prod]" else "") + "\n   " + parts.joinToString("\n   ")
    }
}

/**
 * First-run checklist: what a fresh install still needs before the numbers mean anything. Each line opens
 * the Setup section that fixes it. Gone once everything's done.
 */
@Composable
private fun SetupChecklist(v: Int, access: Boolean, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val items: List<Triple<String, Boolean, String?>> = remember(v, access) {
        listOf(
            Triple("Allow file access so XSales backups can be read", access, null),
            Triple("XSales folder found", access && XSales.folder(ctx) != null, "Folder"),
            Triple("Stores and products synced from XSales (Setup › Stores and products › Sync from XSales)", repo.productCount() > 0, "Stores"),
            Triple("Route number read from XSales or typed in", Prefs.routeSource(ctx).isNotBlank(), "Route"),
            Triple("Pay period start checked (Setup › Route and pay periods)", Prefs.routeSource(ctx).isNotBlank() || repo.days().isNotEmpty(), "Route"),
            Triple("Market rates and commission uploaded", repo.rateSummary() != "No rates uploaded yet", "Rates"),
            Triple("Order schedule and order guide set", repo.orderItems().isNotEmpty(), "OrderGuide"),
            Triple("Email addresses set", Prefs.orderEmail(ctx).isNotBlank() || Prefs.bossEmails(ctx).isNotBlank(), "Emails"),
            Triple("Backup password set", Prefs.backupPassword(ctx).isNotEmpty(), "Backups"),
            Triple("Printer chosen (only if you print scan sheets)", PrinterPrefs.name(ctx).isNotBlank(), "Printer"),
        )
    }
    val left = items.count { !it.second }
    if (left == 0) return
    // Only the printer is optional: once everything else is done, stop nagging.
    if (left == 1 && !items.last().second && repo.days().isNotEmpty()) return
    Panel(bg = C.AmberSoft, line = C.AmberLine) {
        Text("Getting set up · $left to go", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = C.Amber)
        items.forEach { (label, done, section) ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !done && section != null) { SetupLink.pendingSection = section; go(Screen.Setup) }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (done) "✓" else "○", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (done) C.Green else C.Amber, modifier = Modifier.width(26.dp))
                Text(label, fontSize = 14.sp, color = if (done) C.Muted else C.Ink, modifier = Modifier.weight(1f))
                if (!done && section != null) Text("›", fontSize = 20.sp, color = C.Amber)
            }
        }
    }
}
