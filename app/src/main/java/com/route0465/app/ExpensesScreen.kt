package com.route0465.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/** The Expenses tab: recurring (out of pay), one-time (a log), a spending chart, and the emailed report. */
@Composable
fun ExpensesScreen() {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val today = LocalDate.now()
    var v by remember { mutableStateOf(0) }
    val recurring = remember(v) { Expenses.recurring(repo) }
    val thisWeek = Periods.weekStart(today)
    val periodStart = Periods.startOf(today)
    // One-time log: the last year, enough for the chart and the lists.
    val once = remember(v) { Expenses.oneTime(repo, today.minusDays(365), today) }
    val categories = remember(v) { Expenses.categories(repo) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    ScreenColumn {
        Text("Expenses", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)

        // ---------------- Recurring ----------------
        Panel {
            H2("Recurring, comes out of pay")
            Muted("Taken out of each Sat–Fri pay week from its start week until you stop it, route days or not. Pay already locked never changes.", 13)
            recurring.forEach { r ->
                HorizontalDivider(color = C.Divider)
                val running = r.stop == null
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (running) C.Ink else C.Muted)
                        Muted(
                            if (running) "Started ${r.start.format(Fmt.day)} · running"
                            else "${r.start.format(Fmt.md)} – ${r.stop!!.plusDays(6).format(Fmt.md)} · stopped", 13,
                        )
                    }
                    Text(Fmt.money(r.weekly) + " / wk", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (running) C.Ink else C.Muted)
                    if (running) Text(
                        "Stop", color = C.Red, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.padding(start = 14.dp).clickable {
                            confirm = "Stop ${r.name}? This week still pays it; from next week on it stops." to { Expenses.stopRecurring(repo, r.id, today); v++ }
                        },
                    ) else Text(
                        "Remove", color = C.Muted, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.padding(start = 14.dp).clickable {
                            confirm = "Remove ${r.name} for good? Weeks it was taken out of will go back to full pay." to { Expenses.removeRecurring(repo, r.id); v++ }
                        },
                    )
                }
            }
            HorizontalDivider(color = C.Divider)
            var name by remember { mutableStateOf("") }
            var amt by remember { mutableStateOf("") }
            var start by remember { mutableStateOf(thisWeek.format(Fmt.mdy)) }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.weight(1.3f), label = { Text("Name, e.g. Truck rental") }, singleLine = true)
                OutlinedTextField(amt, { amt = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.weight(0.8f), label = { Text("$ a week") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            CalendarField("Starts the week of", start, thisWeek, Modifier.fillMaxWidth().padding(top = 6.dp)) { start = it }
            SecondaryButton("Add recurring", Modifier.fillMaxWidth().padding(top = 6.dp), enabled = name.isNotBlank() && (amt.toDoubleOrNull() ?: 0.0) > 0) {
                Expenses.addRecurring(repo, name, amt.toDouble(), parseLooseDate(start) ?: thisWeek)
                name = ""; amt = ""; start = thisWeek.format(Fmt.mdy); v++
            }
        }

        // ---------------- One-time ----------------
        Panel {
            H2("One-time, just a log")
            Muted("Not taken out of pay. Pick a category so the chart can add them up.", 13)
            var date by remember { mutableStateOf(today.format(Fmt.mdy)) }
            var cat by remember { mutableStateOf(categories.first()) }
            var catOpen by remember { mutableStateOf(false) }
            var newCat by remember { mutableStateOf(false) }
            var amt by remember { mutableStateOf("") }
            var note by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CalendarField("Date", date, today, Modifier.weight(1f)) { date = it }
                if (newCat) OutlinedTextField(cat, { cat = it }, Modifier.weight(1.1f), label = { Text("New category") }, singleLine = true)
                else Box(Modifier.weight(1.1f)) {
                    PickField("Category", cat) { catOpen = true }
                    DropdownMenu(catOpen, { catOpen = false }) {
                        categories.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { cat = c; catOpen = false }) }
                        DropdownMenuItem(text = { Text("New category…", color = C.Green, fontWeight = FontWeight.Bold) }, onClick = { cat = ""; newCat = true; catOpen = false })
                    }
                }
                OutlinedTextField(amt, { amt = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.weight(0.8f), label = { Text("$") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("What for (optional)") }, singleLine = true)
            SecondaryButton("Add expense", Modifier.fillMaxWidth().padding(top = 6.dp), enabled = cat.isNotBlank() && (amt.toDoubleOrNull() ?: 0.0) > 0) {
                Expenses.addOneTime(repo, parseLooseDate(date) ?: today, cat, note, amt.toDouble())
                amt = ""; note = ""; newCat = false; v++
            }

            val inPeriod = once.filter { !it.date.isBefore(periodStart) }
            var showAll by remember { mutableStateOf(false) }
            val shown = if (showAll) once else inPeriod
            if (once.isNotEmpty()) {
                HorizontalDivider(color = C.Divider, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(if (showAll) "Last 12 months" else "This pay period", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(Fmt.money(shown.sumOf { it.amount }), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                }
            }
            shown.forEach { o ->
                HorizontalDivider(color = C.Divider)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${o.date.format(Fmt.md)} · ${o.category}", fontSize = 15.sp)
                        if (o.note.isNotBlank()) Muted(o.note, 13)
                    }
                    Text(Fmt.money(o.amount), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Remove", color = C.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 12.dp).clickable { confirm = "Remove this ${o.category} expense (${Fmt.money(o.amount)})?" to { Expenses.removeOneTime(repo, o.id); v++ } })
                }
            }
            if (once.size > inPeriod.size) Text(
                if (showAll) "Show this pay period only" else "Show the last 12 months (${once.size})", color = C.Green, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                modifier = Modifier.clickable { showAll = !showAll }.padding(vertical = 8.dp),
            )
        }

        // ---------------- Chart ----------------
        if (once.isNotEmpty()) Panel {
            H2("What you're spending")
            var monthly by remember { mutableStateOf(false) }
            var picked by remember { mutableStateOf(once.groupBy { it.category }.maxByOrNull { e -> e.value.sumOf { it.amount } }?.key ?: categories.first()) }
            Segmented(listOf("By week", "By month"), if (monthly) 1 else 0, Modifier.fillMaxWidth().padding(top = 6.dp), fill = true) { monthly = it == 1 }
            val mine = once.filter { it.category == picked }
            val buckets: List<Pair<String, Double>> = if (monthly) {
                (11 downTo 0).map { back ->
                    val m = today.withDayOfMonth(1).minusMonths(back.toLong())
                    m.format(java.time.format.DateTimeFormatter.ofPattern("MMM", java.util.Locale.US)) to
                        mine.filter { it.date.withDayOfMonth(1) == m }.sumOf { it.amount }
                }
            } else {
                (11 downTo 0).map { back ->
                    val ws = thisWeek.minusWeeks(back.toLong())
                    ws.format(java.time.format.DateTimeFormatter.ofPattern("M/d", java.util.Locale.US)) to
                        mine.filter { !it.date.isBefore(ws) && !it.date.isAfter(ws.plusDays(6)) }.sumOf { it.amount }
                }
            }
            val nonZero = buckets.count { it.second > 0 }
            Muted("$picked · last 12 ${if (monthly) "months" else "weeks"}" + if (nonZero > 0) " · avg ${Fmt.money(buckets.sumOf { it.second } / nonZero)} a ${if (monthly) "month" else "week"} when you bought it" else "", 13)
            val top = buckets.maxOf { it.second }.takeIf { it > 0 } ?: 1.0
            Row(Modifier.fillMaxWidth().height(110.dp).padding(top = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                buckets.forEach { (_, value) ->
                    Box(Modifier.weight(1f).fillMaxHeight((value / top).toFloat().coerceIn(0.02f, 1f)).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(if (value > 0) Color(0xFF2A78D6) else C.Line))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                buckets.forEach { (label, _) -> Text(label, fontSize = 10.sp, color = C.Muted, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f)) }
            }
            Muted("Tap a category to chart it · this week / this month", 13)
            val monthStart = today.withDayOfMonth(1)
            once.groupBy { it.category }.map { (c, l) -> Triple(c, l.filter { !it.date.isBefore(thisWeek) }.sumOf { it.amount }, l.filter { !it.date.isBefore(monthStart) }.sumOf { it.amount }) }
                .sortedByDescending { it.third }.forEach { (c, wk, mo) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (c == picked) C.GreenSoft else Color.Transparent).clickable { picked = c }.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(if (c == picked) Color(0xFF2A78D6) else C.Line))
                        Text(c, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp).weight(1f), fontWeight = if (c == picked) FontWeight.Bold else FontWeight.Normal)
                        Text((if (wk > 0) Fmt.money(wk) else "—") + " · " + (if (mo > 0) Fmt.money(mo) else "—"), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
        }

        // ---------------- Report ----------------
        PrimaryButton("Email expense report (this pay period)", Modifier.fillMaxWidth()) {
            try {
                val f = Expenses.buildPdf(ctx, periodStart)
                Expenses.email(ctx, f, Prefs.myEmail(ctx), periodStart)
                message = if (Prefs.myEmail(ctx).isBlank()) "Tip: set your email in Setup › Email addresses so it fills in by itself." else null
            } catch (e: Exception) { message = "Couldn't make the report: ${e.message}" }
        }
        message?.let { Banner(it, C.GreenSoft, C.GreenDark) }
    }
    confirm?.let { (text, act) ->
        ConfirmDialog("Are you sure?", text, "Yes", onConfirm = { act(); confirm = null }, onDismiss = { confirm = null })
    }
}
