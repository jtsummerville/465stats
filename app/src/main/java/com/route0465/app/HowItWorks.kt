package com.route0465.app

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One part of the app and the rules behind it. Each entry is a term or a rule, then how it's worked out. */
data class HowTopic(val id: String, val title: String, val summary: String, val entries: List<Pair<String, String>>)

/** Lets another screen open Setup straight to a topic here (e.g. Order › "How Suggested is figured"). */
object HowLink {
    var pendingTopic: String? = null
}

val HOW_TOPICS: List<HowTopic> = listOf(
    HowTopic(
        "import", "Importing from XSales", "Where the numbers come from and the today-only rule",
        listOf(
            "Which file" to "The app reads BCKAftMain.sqlite, the backup XSales writes after End of Day. If that one isn't today's, Home offers BCKBefMain.sqlite (the before-End-of-Day backup) instead. The matching Basics backup is copied alongside it.",
            "Which folder" to "Unless you set a folder in Setup › XSales folder, the app looks through the tablet's storage (up to 5 folders deep) for folders holding BCKAftMain.sqlite and prefers one whose name has \"prd\" or \"prod\" in it, so \"Ole prd\" wins over a test copy like \"Ole\".",
            "Read-only" to "The app copies the backup into its own storage and opens the copy read-only. It never writes to, moves or deletes anything in the XSales folder.",
            "Today only — check 1, the whole file" to "The backup's business date (general.gnlDate) must be today. If it isn't, nothing is imported and the log says what date the file had.",
            "Today only — check 2, every ticket" to "Each ticket's own date (invoice date, or start time if that's blank) must also be today. Any ticket from another day is dropped and counted as dropped, so nothing old can sneak in.",
            "Truck stock" to "Truck inventory has no date per row, so it's only taken because the whole file already passed check 1.",
            "Stores and products" to "Each import adds any new stores and products from XSales and updates their names and case packs. A store or product you added, renamed or changed in Setup › Stores and products keeps your version, and one you removed stays removed.",
            "One import per day" to "Once today is imported it isn't imported again. Voided tickets are kept for the record but never counted in sales or pay.",
            "Automatic import" to "When the switch on Home is on, the app checks about every 15 minutes and whenever you open it. It only imports when BCKAftMain.sqlite has changed since last time, and the same two date checks apply. You get a notification when it imports.",
        ),
    ),
    HowTopic(
        "pay", "Pay", "Commission math, credits and pay periods",
        listOf(
            "Rates used" to "For each product the app uses the market rate and the commission % / credit % in effect on that day: the newest rate whose effective date is on or before the day.",
            "A sale line" to "cases or units × market rate × commission %.",
            "A credit (return) line" to "−(qty × market rate × commission %) − qty × market rate × (1 − credit %). You lose the commission you earned, plus the part of the product's value Ole doesn't credit back.",
            "Which lines are credits" to "Same as Taco-Boys. Sales are the units sold on invoices. Credits and buy backs are the returned-product list from End of Day (what Taco-Boys reads as TOTAL RETURNS), one line per product with its reason. A return on a buy-back ticket or with the Buy Back reason is a buy back; every other return is a credit pickup. The reason (damaged, out of date and so on) never changes pay.",
            "A buy back line" to "−(qty × market rate × commission %). A buy back (XSales doc code grt) is the company's return, like product that expired from a planning issue, so you lose only the commission, not the cost.",
            "Default credit %" to "If a rate sheet has no credit %: products 2933–2938 use 10%, everything else 16%.",
            "Damaged goods" to "Damage-return quantities on a sales ticket are counted as credit lines.",
            "Missing rates" to "A product with no market rate or commission % on file counts $0 and is listed as missing on that day so you can fix the rate sheet.",
            "Voids" to "Voided tickets pay nothing.",
            "Pay periods" to "14 days: two Saturday–Friday weeks, counted from Sat 07/11/2026. The pay is figured when the day is imported, using the rates in effect on that day's own date.",
            "Locked pay" to "A pay period locks 5 days after it ends (the Thursday after its last Friday). Until then, uploading rates refigures its days, so a missing or wrong rate can still be fixed. Once locked, its pay is final and nothing changes it, not even a back-dated rate.",
        ),
    ),
    HowTopic(
        "rates", "Rates upload", "How a rate sheet is read",
        listOf(
            "Two separate histories" to "Market rates and commission/credit % are stored separately, each with an effective date, like Taco-Boys. Uploading a new sheet adds new dated rows; older ones stay for older days.",
            "Effective date" to "You're asked for the date the sheet takes effect. Days before it keep using the older rates.",
            "Columns" to "The app finds the product code, market rate, commission % and credit % columns by their headings. A row with credit % but no commission % is skipped and noted.",
        ),
    ),
    HowTopic(
        "sales", "Sales", "Ranges, credit rate and promo weeks",
        listOf(
            "Ranges" to "Day, Week (Sat–Fri), 2 weeks, 4 weeks, Month and Year to date. The arrows step one range back or forward; Pick a date jumps to the range holding that day.",
            "2 and 4 weeks" to "Counted back from the end of the current Sat–Fri week, in whole weeks.",
            "Sales and credits" to "Sales are the dollars on sales tickets; credits are the dollars on return tickets and damage returns. Net = sales − credits. Voided tickets are left out and listed separately.",
            "Dollars or cases" to "The Dollars / Cases switch flips every Sales number. Dollars are what the stores were invoiced. Cases are eaches ÷ that product's case size (from XSales, or your own in Setup › Stores and products). In Cases the credit rate is credited cases ÷ cases sold, like Taco-Boys' unit credit rate. The math underneath is always in eaches.",
            "One store" to "In By store, tapping a store opens the same overview for that store alone (tiles, chart, credit rate, credits by product, every credit line, voids), then its products. Its credit rate uses only that store's sales and credits.",
            "Credits by product" to "Every credit in the window added up per product: how many times it was credited, the eaches or cases, and the dollars. Buy backs are listed apart. On the main overview it covers all stores; on a store's page, just that store.",
            "Freight week" to "In the Week view, the Freight week switch shows Wednesday through Tuesday, matching when new freight comes in, instead of Saturday through Friday. The arrows and calendar step by freight weeks while it's on. It stays as you left it.",
            "Credit rate" to "Credit dollars ÷ sales dollars for the whole window, one division, never an average of daily rates. Buy backs count against net sales but are left out of the credit rate.",
            "Credit colors" to "Green up to 1.5% (on target), amber up to 2.5% (check it), red above 2.5% (fix it). The line on the meter is the 1.5% target.",
            "Promotions in this window" to "For each Sat–Fri week, the promos that overlapped it for banners you serve, and how much of the week they ran (all week, Mon–Wed, Sat only…).",
        ),
    ),
    HowTopic(
        "inventory", "Inventory", "Cases on hand and Inventory Check",
        listOf(
            "Cases on hand" to "From truck stock in the last import: units on hand ÷ case pack. The case pack is the largest unit multiplier XSales has for that product.",
            "Total cases" to "The sum of cases on hand across every product.",
            "Inventory Check" to "Taco-Boys' check, in eaches, for each product between two imports: last count − sold + buy backs + delivered should equal what's on the truck now. Use the arrows to step back through earlier imports.",
            "Credits in the check" to "Credit pickups are not subtracted: they never go back into sellable stock. Buy backs are added, because they go back on the truck and get resold. A product that was also credited back says so as context.",
            "Delivered" to "XSales' reload for each product on the days in between.",
            "Went missing and appeared" to "Product that left the truck with no sale, buy back or delivery behind it, and product that showed up with nothing to explain it. Both are findings, listed separately and never netted against each other. Value is the eaches off × market rate.",
            "Delivery periods" to "If 6 or more products go up by 150+ eaches in total, it looks like freight that wasn't recorded as delivered. Then increases can't be told from restock, and only a drop bigger than what came in can show. That's said on the screen, never called clean.",
            "Missing imports" to "If the route ran on a day with no import in between (weekdays learned from your own imports), the screen says so: the finding happened somewhere in that stretch, not on one particular day.",
        ),
    ),
    HowTopic(
        "order", "Order guide and Suggested", "Deadline, delivery, suggestion, flags and ran short",
        listOf(
            "Order deadline" to "Wednesday by midnight (today, if today is Wednesday).",
            "Which delivery" to "Ole places the order Thursday; it's delivered the Tuesday after. That delivery runs until the next Tuesday's delivery, and that week is what promo tags and Suggested aim at.",
            "Weekly rate" to "Units sold per product over the last 4 full Sat–Fri weeks that have imported sales, ÷ case pack, ÷ the number of those weeks.",
            "Suggested" to "weekly rate × (days from the last inventory import to the delivery + 7) ÷ 7, plus the cases you ran short by, minus cases on hand at the last import. Rounded up, never below 0.",
            "Why + 7 days" to "The order has to cover you until the delivery and then through the week until the next delivery.",
            "— instead of a number" to "No full week of sales imported yet, so there's nothing to average.",
            "Promotions" to "Not added into Suggested. The tag tells you to stock up or bump, and you decide how much.",
            "Red !" to "Your order is more than 5 cases away from Suggested, same as Taco-Boys. Only shown when you've ordered something.",
            "Ran short / by" to "Tick it when you ran out before the last delivery and enter by how many cases. Those cases are added to Suggested. Clear all resets them for the next order.",
            "Email order" to "Builds the order PDF, opens your mail app addressed to the order email, and saves a copy of the order and the suggestions in the app's order history.",
        ),
    ),
    HowTopic(
        "promos", "Promotions", "Banners and the stock up / light bump tags",
        listOf(
            "What a promo is" to "A banner (or All banners), a type you type in, one or more products, and a start and end date. Promos only count for banners your route serves, unless they're set to All banners.",
            "Stock up (filled tag)" to "The sale is still running when the delivery lands: it starts before the next delivery and ends on or after it. Same rule as Taco-Boys.",
            "Light bump (outlined tag)" to "The sale starts and ends inside the one delivery week, so it's short. Bump a little.",
            "No tag" to "A sale that's ending partway through the delivery week (a dying sale), or one that hasn't started by then, isn't tagged.",
            "Several promos" to "If more than one promo covers a product, the tag says PROMO ×2 (or more) and stock up wins over light bump. Tap the tag to see each one.",
            "Ended promos" to "Folded away under Ended on the Promotions screen. They still show in Sales' promo weeks.",
        ),
    ),
    HowTopic(
        "shortages", "Shortages", "Warehouse short and missing freight",
        listOf(
            "What's kept" to "Each entry is today's date, the product, the quantity and whether it was warehouse short or missing freight. They're just a record; they don't change pay or Suggested.",
        ),
    ),
    HowTopic(
        "paperwork", "End of Day paperwork", "Photos, the XSales PDF and the email",
        listOf(
            "Store pages" to "Scanned in the app per store with Google's document scanner: it finds the edges of the paper, crops and straightens it, and lets you rotate, re-crop or apply a clean-up filter before saving. Several pages per scan. Saved in the app's own storage, not your gallery. If the scanner isn't available, the regular camera is used instead.",
            "Store paperwork PDF" to "Like a scanner app makes: one page per scanned page, each sized to its own paper (long receipts stay long, sideways invoices stay sideways), with no border or label. Stores come in the order you first scanned them. The scanned images go in untouched, so nothing gets blurrier.",
            "XSales End of Day PDF" to "Share it from XSales into 465stats. The first real PDF in the share is saved as today's; sharing again replaces it.",
            "Email" to "Opens your mail app addressed to the boss emails from Setup, with a subject, a short summary and both PDFs attached. You still tap Send in the mail app.",
            "How long they're kept" to "60 days, then the photos and PDFs for older days are deleted from the app.",
        ),
    ),
    HowTopic(
        "upc", "UPC lookup", "Where the barcodes come from",
        listOf(
            "Product list" to "A built-in list of Ole products with code, description, case pack and UPC.",
            "Barcode" to "Drawn as a UPC-A barcode from the 12-digit UPC. Scan view turns the screen brightness all the way up so scanners can read it.",
        ),
    ),
    HowTopic(
        "scansheet", "Scan sheet and printer", "What prints on the barcode sheet and how the printer is reached",
        listOf(
            "What it's for" to "For a store you can't DEX and where the product isn't on hand to scan. It's not an invoice: it's a strip of barcodes with the eaches you're delivering, for the receiver to scan and key in.",
            "What prints" to "A header with OLE MEXICAN FOODS, the route, the date and the 465stats version centered at the top, then CUSTOMER with the store name and number. Then one block per product: code and description, its UPC-A barcode with the digits under it, and the eaches. Then the total eaches and number of products.",
            "Barcodes" to "Taken from the same built-in list as the UPC screen. A product with no UPC on file can't be added.",
            "Quantity" to "Always in eaches. The \"= 2 cases + 3\" note under a quantity uses the product's case pack and is only shown on the tablet, never printed.",
            "From XSales" to "Lists today's finalized invoices from XSales' live working file (Main.sqlite), read only when you tap the button. The file and its companions are copied into 465stats and only the copy is opened, so XSales is never touched and a half-written change is never read. Picking an invoice fills the sheet with its store, today's date and every product with its eaches. A product with no barcode on file is left off and named. Voided invoices and tickets from other days never show.",
            "The sheet is kept" to "Store, date, route and products stay on the tablet until you change them or tap Clear all, so you can reprint any time.",
            "Printer connection" to "Bluetooth only, straight to a printer already paired in the tablet's Bluetooth settings. Nothing goes over the internet.",
            "Printer language" to "Zebra printers speak CPCL (older mobile printers like the RW420), ZPL (desktop and most newer ones) or both. On Auto the app asks the printer each time which one it's set to and uses that; if the printer doesn't answer it uses the last one it found, or CPCL.",
            "Paper width" to "2, 3 or 4 inch at 203 dpi (384, 576 or 832 dots). On 2 inch paper the quantity prints under the barcode instead of beside it.",
            "Paper at the end" to "After the total and product count the printer feeds blank paper so the whole sheet clears the tear bar: Short about 1/4 inch, Medium about 1/2 inch, Long about 1 inch. Set in Setup › Printer.",
            "Page breaks" to "Each product prints as its own short page, back to back, so a long sheet never overruns the printer's memory.",
        ),
    ),
    HowTopic(
        "backups", "Backups and password", "What's backed up and how it's locked",
        listOf(
            "What's backed up" to "The app's own database: days, sales, pay, rates, order guide, promos, shortages. Not the XSales files and not the photos.",
            "When" to "About every 6 hours, once there's at least one imported day. One zip per day, rewritten through the day so it holds the latest. The newest 30 are kept.",
            "Where" to "Documents/465stats backups on the tablet.",
            "Lock" to "Each zip is locked with AES-256 using your backup password. It opens in 7-Zip or WinRAR on a computer. No password, no backups.",
            "Changing the password" to "Needs the current password plus the new one typed twice.",
        ),
    ),
)

@Composable
fun HowItWorksSection(back: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(HowLink.pendingTopic ?: "") }
    remember { HowLink.pendingTopic = null; 0 }
    var q by remember { mutableStateOf("") }
    val s = q.trim().lowercase()
    ScreenColumn {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "‹  Setup", color = C.Green, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                modifier = Modifier.clickable { back() }.padding(vertical = 10.dp, horizontal = 4.dp),
            )
            Text("Reference", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        }
        Muted("The rules behind every number in the app. Tap a part to open it, or search for a word.", 14)
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search, e.g. credit, suggested, void") })
        val topics = HOW_TOPICS.mapNotNull { t ->
            if (s.isEmpty()) t to t.entries
            else {
                val hit = t.entries.filter { (a, b) -> a.lowercase().contains(s) || b.lowercase().contains(s) }
                when {
                    t.title.lowercase().contains(s) -> t to t.entries
                    hit.isNotEmpty() -> t to hit
                    else -> null
                }
            }
        }
        if (topics.isEmpty()) Muted("Nothing matches \"$q\".")
        Panel(pad = 0.dp) {
            topics.forEachIndexed { i, (t, entries) ->
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
                if (expanded) Column(
                    Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    entries.forEach { (term, rule) ->
                        Column {
                            Text(term, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = C.GreenDark)
                            Text(rule, fontSize = 15.sp, lineHeight = 21.sp)
                        }
                    }
                }
            }
        }
    }
}
