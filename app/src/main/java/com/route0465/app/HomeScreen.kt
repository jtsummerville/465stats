package com.route0465.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
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
    var backups by remember { mutableStateOf<List<XSales.Backup>?>(null) }
    val access = remember(v) { XSales.hasAccess() }
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
        if (!access) {
            Banner(
                "To read the XSales backups, Android needs you to allow \"All files access\" for this app once. The app only copies and reads them; it never changes XSales's files.",
                C.AmberSoft, C.Amber, title = "Allow file access",
            ) {
                SecondaryButton("Allow file access") {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Panel(Modifier.weight(1.25f), pad = 26.dp) {
                H2("Import today's route")
                Muted("Run End of Day in XSales first, then tap below. The app copies the XSales backup files, checks they're dated today, and reads only today's tickets.")
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
                    Column(Modifier.weight(1f)) {
                        Text("Auto-import", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Muted(
                            if (auto) "On. Imports by itself when XSales finishes End of Day (checks every 15 minutes and when you open the app)."
                            else "Off. Use the End of Day button.", 14,
                        )
                    }
                }
                SecondaryButton("Check XSales backups") {
                    scope.launch {
                        backups = withContext(Dispatchers.IO) { listOf(XSales.AFT, XSales.BEF).map { XSales.inspect(ctx, it) } }
                    }
                }
                backups?.let { list ->
                    val fmt = SimpleDateFormat("MM/dd h:mm a", Locale.US)
                    list.forEach { b ->
                        val dated = b.date?.let { if (it == today) "dated today" else "dated ${it.format(Fmt.mdy)}" } ?: "date unknown"
                        Muted(
                            if (!b.exists) "${b.name}: ${b.note}"
                            else "${b.name}: $dated · saved ${fmt.format(Date(b.modified))}" + if (b.note.isNotEmpty()) " · ${b.note}" else "",
                            14,
                        )
                    }
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                        (if (due == today) "Today" else due.format(Fmt.day)) + " by midnight",
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
                "${r.stores} stores · ${r.saleLines} sale lines · ${r.creditLines} credit lines · ${r.voids} voided tickets · ${r.stockItems} stock items\n" +
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
