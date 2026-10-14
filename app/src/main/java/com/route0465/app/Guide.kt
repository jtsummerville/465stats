package com.route0465.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One how-to: a title, what it's for, numbered steps, and (optionally) the Setup section it lives in. */
class GuideTopic(val id: String, val title: String, val summary: String, val steps: List<String>, val section: String? = null)

/**
 * Setup › Getting started: step-by-step instructions for a new install and for the daily routine.
 * How-to only; the rules behind the numbers stay in Reference.
 */
val GUIDE_TOPICS: List<GuideTopic> = listOf(
    GuideTopic(
        "install", "1 · Install and allow file access", "Do this once on a new tablet.",
        listOf(
            "Install 465stats.apk. Android asks you to allow installing from this source the first time; say yes. Installing a newer build over an old one keeps all your data.",
            "Open the app. On Home, tap Allow file access and switch it on in the Android screen that opens. The app only reads XSales' backup files; it never changes them.",
            "Go to Setup. The Getting set up checklist at the top shows what's left. Each line opens the right section; this guide walks through them in order.",
        ),
    ),
    GuideTopic(
        "folder", "2 · XSales folder", "Where the app reads your End of Day backups from.",
        listOf(
            "Setup › XSales folder. It usually finds the folder by itself (the one with \"prd\" in its name, e.g. XSales 4.4.1/OLE PRD) and shows it under Reading from.",
            "If it says Not found yet, tap Edit, then Show XSales folders on this tablet and pick the one that holds BCKAftMain.sqlite. Tap Save.",
            "Check XSales backups › Check now lists the backup files it can see and their dates.",
        ), "Folder",
    ),
    GuideTopic(
        "sync", "3 · Stores, products and barcodes from XSales", "Pull your route's store list, product list, case packs and UPCs in one tap.",
        listOf(
            "Run End of Day in XSales at least once on this tablet so a backup exists.",
            "Setup › Stores and products › Sync from XSales, then confirm. It reads the newest backup and adds every store and product, with case packs and barcodes. Nothing you've renamed or removed by hand is touched.",
            "Your route number is read at the same time (Setup › Route and pay periods shows it). It goes on every email, PDF and printout.",
            "Run Sync again any time XSales gets new products or stores.",
        ), "Stores",
    ),
    GuideTopic(
        "route", "4 · Route number and pay periods", "Check the route and line up the pay periods.",
        listOf(
            "Setup › Route and pay periods. The route number should read \"from XSales\" after the first sync or import. Only type one in if XSales has it wrong.",
            "Pay periods start shows the Saturday the 14-day periods are counted from. If your pay periods run on the other week, tap Edit, pick a Saturday that starts one of yours, and Save. Change this once, at the start; it re-dates every period.",
        ), "Route",
    ),
    GuideTopic(
        "rates", "5 · Market rates and commission", "Without these the app can't figure pay.",
        listOf(
            "Make a spreadsheet (CSV or Excel) with one row per product: Code, Market rate, Commission, Credit, and optionally Effective date. Reference › Rates upload › Example shows exactly what it looks like. Keep leading zeros on codes (0092, not 92).",
            "Get the file onto the tablet: email it to yourself and save the attachment, or copy it over USB into Downloads.",
            "Setup › Rates › Upload new rates file, pick the file, confirm the effective date. The summary line shows how many products got rates.",
            "A product with no rate pays $0 and is listed under \"rates missing\" on Pay, so you can see what's still needed. There are no built-in defaults.",
            "When rates change, upload a new file with the new effective date. Old days keep their old rates; pay already locked never changes.",
        ), "Rates",
    ),
    GuideTopic(
        "order", "6 · Order schedule and order guide", "When your order is due and what's on it.",
        listOf(
            "Setup › Order guide › Order schedule › Edit: pick the day and time the order is due, your delivery day, and the day your freight week starts (it fills in as the day after delivery). Save.",
            "Under it, build the order guide: Edit, then Add products (search by code or name), or Load list from file with a file of product codes, one per line. Done when finished.",
            "The Order tab now shows the deadline, Suggested cases per product, and promo tags. Tap a product's code or name for last year's sales.",
        ), "OrderGuide",
    ),
    GuideTopic(
        "emails", "7 · Email addresses", "Where orders, End of Day paperwork and expense reports go.",
        listOf(
            "Setup › Email addresses › Edit. Orders go to: your ordering address. End of Day paperwork goes to: your bosses, separated by commas. Expense reports go to: your own email. Save.",
            "The app doesn't send mail by itself: every email button opens your mail app with the addresses, subject and attachments filled in, and you tap Send there. Make sure Gmail (or your mail app) is signed in on the tablet.",
        ), "Emails",
    ),
    GuideTopic(
        "backups", "8 · Backups and password", "Protect your data. Do this before you rely on the app.",
        listOf(
            "Setup › Backups and password › Edit, type a backup password twice, Save. No password, no backups.",
            "From then on the app backs itself up about every 6 hours to Documents/465stats backups on the tablet (a locked zip; it keeps the newest 30). Back up now makes one straight away.",
            "To keep a copy off the tablet, email the newest zip to yourself from the Files app now and then, or copy the folder over USB.",
            "Moving to a new tablet or undoing a mistake: install the app, set the same password, Setup › Backups and password › Restore from a backup…, pick the zip. The app saves a \"before-restore\" copy first so a restore can itself be undone.",
            "Backups hold the app's own data: days, sales, pay, rates, order guide, promos, shortages, expenses, audits. Not the XSales files, not the paperwork photos.",
        ), "Backups",
    ),
    GuideTopic(
        "printer", "9 · Zebra printer (only if you print scan sheets)", "Pair the printer and pick it.",
        listOf(
            "Pair the Zebra with the tablet in Android Bluetooth settings first (Setup › Printer › Bluetooth settings opens them).",
            "Setup › Printer › Edit › Choose printer, pick it from the paired list. Language Auto works for the RW420; paper width 4 inch; feed Medium. Save, then Test print.",
        ), "Printer",
    ),
    GuideTopic(
        "eod", "Every route day · End of Day import", "Gets the day's sales, credits, truck stock and pay into the app.",
        listOf(
            "Finish the day in XSales and run its End of Day. That writes the backup the app reads.",
            "Open 465stats. With Auto-import on (Home), it imports by itself within a few minutes. Or tap End of Day on Home to import now.",
            "Home shows the import summary: stores, tickets, credits, store visits with no sale. Pay, Sales, Inventory and Order update from it.",
            "Only today's backup is ever imported, once. To redo a day: Setup › Imports and activity log › Remove <day> import…, then import again.",
            "If you haven't run End of Day yet but need a scan sheet for a finalized invoice, Scan sheet › From XSales reads the live file.",
        ),
    ),
    GuideTopic(
        "paperwork", "Every route day · Store paperwork and the End of Day email", "Scan each store's paperwork and send it with the XSales PDF.",
        listOf(
            "Paperwork tab: tap Scan next to a store. The scanner finds the page edges; take each page, then Save. Scan more adds pages.",
            "In XSales, share the End of Day PDF to 465stats (Android share menu › 465stats). It appears in the Email panel.",
            "Tap Send Email. Your mail app opens to the bosses with the store PDFs and the XSales PDF attached and the no-sale visits listed; tap Send.",
            "Paperwork is kept 60 days on the tablet.",
        ),
    ),
    GuideTopic(
        "ordering", "Every order day · Place the order", "Build and email the order before the deadline.",
        listOf(
            "Order tab › Edit order. Type cases or use − +. Suggested is the app's estimate from your last 4 weeks; a red ! flags an order more than 5 cases off it. Tick Ran short last delivery where it applies.",
            "Tap a product's code or name to see last year's sales for these weeks and whether it was on sale.",
            "Done, then Email order. The PDF and the subject line (with your route and due date) are filled in; tap Send in your mail app.",
            "Promos tab: key in this week's promotions so the Order and Sales pages can tag them.",
        ),
    ),
    GuideTopic(
        "scansheet", "When you can't DEX · Scan sheet", "Print a strip of barcodes with the eaches you're delivering.",
        listOf(
            "Scan sheet tab: pick the store and date (the route fills in). Tap + Add product and type the eaches, or From XSales to fill it from a finalized invoice.",
            "Tap Print. The sheet prints on the Zebra: header, one barcode per product with its eaches, then the totals.",
            "UPC tab shows any product's barcode full screen if a scanner wants to read it off the tablet.",
        ),
    ),
    GuideTopic(
        "audit", "When you count the truck · Inventory Audit", "Count against the last import and get the XSales fixes.",
        listOf(
            "Inventory tab › Audit. It counts against the newest import; Other import picks another day.",
            "Type cases and eaches for each product as you count. Leave a row blank if you didn't get to it. The Fix column shows what to key into XSales: add eaches or subtract whole cases.",
            "Everything saves as you type. When you've entered the fixes in XSales, tick I've keyed these fixes into XSales. Counts on file keeps every count as the paper trail.",
        ),
    ),
    GuideTopic(
        "expenses", "Weekly · Expenses and the expense report", "Track what comes out of pay and what you spend.",
        listOf(
            "Expenses tab. Recurring: name, dollars a week, start week, Add recurring. It comes out of every pay week on the Pay page until you tap Stop.",
            "One-time: date, category, amount, optional note, Add expense. These are a log and never touch pay; the chart under them shows a category by week or month.",
            "Email expense report sends this pay period's PDF (recurring, one-time, totals) to your own email.",
        ),
    ),
    GuideTopic(
        "shortages", "When freight is short · Shortages", "Log it so the week shows what it cost you.",
        listOf(
            "Shortages tab › pick the product, cases short, Log shortage.",
            "Each freight week totals the cases, the value at market rate and the pay you lost on it.",
        ),
    ),
    GuideTopic(
        "reading", "Reading the numbers", "Where things are and what they mean.",
        listOf(
            "Pay: by day and by 14-day pay period, after recurring expenses. \"Rates missing\" means a product needs a rate uploaded.",
            "Sales: Dashboard (any range), Pay period, Freight week (type in your manager's target), Stores, Credits (losses by reason, top 5 out of date), Buy backs (not a loss; shows where each item sells best).",
            "Inventory: cases on hand from the last import, and the Audit.",
            "Setup › Reference explains the rule behind every number. Search it for a word like credit, suggested or lock.",
        ),
    ),
)

@Composable
fun GuideSection(back: () -> Unit, openSection: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    val s = q.trim().lowercase()
    ScreenColumn {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "‹  Setup", color = C.Green, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                modifier = Modifier.clickable { back() }.padding(vertical = 10.dp, horizontal = 4.dp),
            )
            Text("Getting started", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        }
        Muted("Step by step: setting up a new tablet (1–9, in order), then the daily routine. Tap a part to open it, or search for a word.", 14)
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search, e.g. backup, email, printer") })
        val topics = GUIDE_TOPICS.filter { t -> s.isEmpty() || t.title.lowercase().contains(s) || t.summary.lowercase().contains(s) || t.steps.any { it.lowercase().contains(s) } }
        if (topics.isEmpty()) Muted("Nothing matches \"$q\".")
        Panel(pad = 0.dp) {
            topics.forEachIndexed { i, t ->
                if (i > 0) HorizontalDivider(color = C.Divider)
                val expanded = s.isNotEmpty() || open == t.id
                Row(
                    Modifier.fillMaxWidth().clickable { open = if (open == t.id) "" else t.id }.padding(horizontal = 22.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(t.summary, fontSize = 14.sp, color = C.Muted)
                    }
                    Text(if (expanded) "▴" else "▾", fontSize = 18.sp, color = C.Green)
                }
                if (expanded) Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    t.steps.forEachIndexed { n, step ->
                        Row {
                            Text("${n + 1}.", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = C.GreenDark, modifier = Modifier.width(28.dp))
                            Text(step, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
                        }
                    }
                    if (t.section != null) Text(
                        "Open this section ›", color = C.Green, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.clickable { openSection(t.section) }.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
