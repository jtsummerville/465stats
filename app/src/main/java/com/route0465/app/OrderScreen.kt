package com.route0465.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import java.time.LocalDate
import android.view.ViewTreeObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager

@Composable
fun OrderScreen(v: Int, bump: () -> Unit, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val items = remember(v) { repo.orderItems() }
    val stockDate = remember(v) { repo.stockDates().firstOrNull() }
    val onHand = remember(v, stockDate) { stockDate?.let { d -> repo.stock(d).associate { it.code to it.cases } } ?: emptyMap() }
    val cycle = orderCycle()
    val promos = remember(v) { promoTags(repo.promosV2(), bannersFor(repo.stores().map { it.second }).toSet() - ALL_BANNERS, cycle) }
    var promoInfo by remember { mutableStateOf<Pair<String, PromoTag>?>(null) }
    val onHandU = remember(onHand) { onHand.mapKeys { it.key.uppercase() } }
    val sugg = remember(v) { Suggest.compute(repo, items, onHandU, stockDate, cycle.first) }
    // Ran short last delivery, and by how many cases (Taco-Boys "Ran short / Short by").
    val ran = remember(v) { mutableStateMapOf<String, Pair<Boolean, Int>>().apply { items.forEach { put(it.code, it.ranShort to it.shortBy) } } }
    var showHow by remember { mutableStateOf(false) }
    val due = nextOrderDue()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    // Which number box has the keyboard. While one does, the top of the screen folds away so the list gets the room.
    var typingIn by remember { mutableStateOf<String?>(null) }
    val typing = editing && typingIn != null
    val focus = LocalFocusManager.current
    val wide = LocalWide.current
    val onFocus = { key: String, has: Boolean -> if (has) typingIn = key else if (typingIn == key) typingIn = null }
    // Keyboard closed with the Back key: let go of the box so the top of the screen comes back.
    val view = LocalView.current
    DisposableEffect(view) {
        var wasOpen = false
        val l = ViewTreeObserver.OnGlobalLayoutListener {
            val open = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (wasOpen && !open) { focus.clearFocus(); typingIn = null }
            wasOpen = open
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(l)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(l) }
    }
    // Quantities live here while you type; each change is saved straight away.
    val qty = remember(v) { mutableStateMapOf<String, Int>().apply { items.forEach { put(it.code, it.qty) } } }
    val total = qty.values.sum()
    val current = {
        items.map { it.copy(qty = qty[it.code] ?: 0, ranShort = ran[it.code]?.first ?: false, shortBy = ran[it.code]?.second ?: 0) }
    }
    val setRan = { code: String, r: Boolean, by: Int -> ran[code] = r to (if (r) by else 0); repo.setRanShort(code, r, by) }
    val flagged = { code: String -> val n = qty[code] ?: 0; val s = sugg[code]?.cases; n > 0 && s != null && kotlin.math.abs(n - s) > Suggest.FLAG_CASES }
    val setQty = { code: String, n: Int -> val c = n.coerceIn(0, 9999); qty[code] = c; repo.setOrderQty(code, c) }
    val shown = remember(items, q) {
        val s = q.trim().lowercase()
        if (s.isEmpty()) items else items.filter { it.code.lowercase().contains(s) || it.name.lowercase().contains(s) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = if (typing) 8.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(if (typing) 8.dp else 14.dp)) {
        if (typing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$total cases · ${qty.values.count { it > 0 }} items", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                SecondaryButton("Hide keyboard") { focus.clearFocus(); typingIn = null }
            }
        }
        if (!typing) Split(16.dp) {
            Panel(Modifier.part(1f), bg = C.AmberSoft, line = C.AmberLine) {
                Text("Order deadline", fontSize = 14.sp, color = C.Amber, fontWeight = FontWeight.SemiBold)
                Text((if (due == LocalDate.now()) "Today" else due.format(Fmt.day)) + " by midnight", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Tile("Cases ordered", total.toString())
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Email order", enabled = items.isNotEmpty()) {
                        try {
                            val f = OrderPdf.build(ctx, current(), due)
                            OrderPdf.email(ctx, f, Prefs.orderEmail(ctx), due, total)
                            repo.saveOrderHistory(cycle.first, current(), sugg.mapValues { it.value.cases })
                            message = if (Prefs.orderEmail(ctx).isBlank()) "Tip: set the order email address in Setup so it fills in by itself." else null
                        } catch (e: Exception) {
                            message = "Couldn't make the email: ${e.message}"
                        }
                    }
                }
            }
        }
        if (!typing) message?.let { Banner(it, C.GreenSoft, C.GreenDark) }

        if (items.isEmpty()) {
            Panel {
                H2("No products on your order guide yet")
                Muted("Pick your products and put them in your order in Setup › Order guide.")
                SecondaryButton("Go to Setup") { go(Screen.Setup) }
            }
        } else {
            if (!typing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, label = { Text("Find a product") })
                if (editing) {
                    Text(
                        "Clear all", color = C.Red, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.clickable { confirmClear = true }.padding(10.dp),
                    )
                    PrimaryButton("Done") { focus.clearFocus(); typingIn = null; editing = false }
                } else {
                    SecondaryButton("Edit order") { editing = true }
                }
            }
            Text(
                "${qty.values.count { it > 0 }} items · $total cases · ${items.count { flagged(it.code) }} flags · ${ran.values.count { it.first }} ran short",
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            Row(Modifier.fillMaxWidth().clickable { showHow = !showHow }, verticalAlignment = Alignment.CenterVertically) {
                Text("How Suggested is figured", fontSize = 14.sp, color = C.Green, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (showHow) "▴" else "▾", color = C.Green)
            }
            if (showHow) Muted(
                "Average cases sold per week over your last 4 full Sat–Fri weeks, enough to last from the last inventory to this delivery (${cycle.first.format(Fmt.day)}) and through the next 7 days, plus what you ran short by, minus what's on hand, rounded up. " +
                    "Promotions aren't added in; the tag tells you to stock up or bump. A ! means your order is more than ${Suggest.FLAG_CASES} cases off the suggestion. " +
                    "It gets better as more days are imported; with no sales yet it shows —.", 13,
            )
            Muted(
                (if (editing) "Type the cases or use − +. Tap Done to lock it." else "Locked. Tap Edit order to change quantities.") +
                    " On hand is from the last import${stockDate?.let { " (" + it.format(Fmt.day) + ")" } ?: ""}." +
                    " Promo tags are for delivery ${cycle.first.format(Fmt.day)} – ${cycle.second.minusDays(1).format(Fmt.day)}: filled = stock up, outlined = light bump. Tap a tag for details.", 13,
            )
            }
            Panel(Modifier.weight(1f).fillMaxWidth(), pad = 0.dp) {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(shown, key = { _, x -> x.code }) { i, item ->
                        if (i > 0) HorizontalDivider(color = C.Divider)
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(item.code, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = C.Ink, modifier = Modifier.width(70.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name.ifEmpty { item.code }, fontSize = 15.sp, fontWeight = FontWeight.Normal, maxLines = 2)
                                Muted("On hand ${onHand[item.code]?.let { c -> Fmt.one(c) } ?: "0.0"} cs", 13)
                            }
                            promos[item.code.uppercase()]?.let { tag ->
                                val short = tag.state == "short"
                                Text(
                                    tag.label, color = if (short) C.Blue else C.Amber, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(999.dp))
                                        .background(if (short) Color.White else C.AmberSoft)
                                        .border(1.5.dp, if (short) C.Blue else C.AmberSoft, RoundedCornerShape(999.dp))
                                        .clickable { promoInfo = item.code to tag }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                            val n = qty[item.code] ?: 0
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(62.dp)) {
                                Text("Sugg.", fontSize = 11.sp, color = C.Muted)
                                Text(sugg[item.code]?.cases?.toString() ?: "—", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.Muted)
                            }
                            Text(
                                if (flagged(item.code)) "!" else "", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(22.dp).clip(RoundedCornerShape(6.dp)).background(if (flagged(item.code)) C.Red else Color.Transparent),
                            )
                            if (editing && wide) {
                                StepButton("−", dark = false) { setQty(item.code, n - 1) }
                                QtyField(n, item.code, onFocus) { setQty(item.code, it) }
                                StepButton("+", dark = true) { setQty(item.code, n + 1) }
                            } else if (!editing) {
                                Text(
                                    if (n == 0) "—" else "$n cs", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                                    color = if (n == 0) C.Line else C.Ink, textAlign = TextAlign.End, modifier = Modifier.width(96.dp),
                                )
                            }
                        }
                        val (isShort, by) = ran[item.code] ?: (false to 0)
                        if (editing) {
                            val n = qty[item.code] ?: 0
                            Row(Modifier.fillMaxWidth().padding(start = if (wide) 70.dp else 0.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.clickable { setRan(item.code, !isShort, by) }, verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(isShort, { setRan(item.code, it, by) }, colors = CheckboxDefaults.colors(checkedColor = C.Amber))
                                    Text(if (wide) "Ran short last delivery" else "Ran short", fontSize = 14.sp)
                                }
                                if (isShort) {
                                    Text("by", fontSize = 14.sp, color = C.Muted)
                                    QtyField(by, item.code + ":short", onFocus) { setRan(item.code, true, it) }
                                    Text("cs", fontSize = 14.sp, color = C.Muted)
                                }
                                if (!wide) {
                                    Box(Modifier.weight(1f))
                                    StepButton("−", dark = false) { setQty(item.code, n - 1) }
                                    QtyField(n, item.code, onFocus) { setQty(item.code, it) }
                                    StepButton("+", dark = true) { setQty(item.code, n + 1) }
                                }
                            }
                        } else if (isShort) {
                            Text("Ran short last delivery" + if (by > 0) " by $by cs" else "", fontSize = 13.sp, color = C.Amber, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 82.dp))
                        }
                        }
                    }
                }
            }
        }
    }
    promoInfo?.let { (code, tag) ->
        AlertDialog(
            onDismissRequest = { promoInfo = null },
            title = { Text("$code on promotion") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("For delivery ${cycle.first.format(Fmt.day)} – ${cycle.second.minusDays(1).format(Fmt.day)}:", color = C.Muted)
                    tag.lines.forEach { l ->
                        Text("${l.banner} — ${l.type}", fontWeight = FontWeight.Bold)
                        Text("${l.dates} · ${l.cue}", color = if (l.state == "short") C.Blue else C.Amber)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { promoInfo = null }) { Text("OK") } },
        )
    }
    if (confirmClear) {
        ConfirmDialog("Clear all quantities?", "Sets every product back to 0 cases and clears the Ran short marks, ready for next week's order.", "Clear",
            onConfirm = { repo.clearOrderQty(); bump() }, onDismiss = { confirmClear = false })
    }
}

/** A small number box you can type cases into. Blank counts as 0. */
@Composable
private fun QtyField(value: Int, key: String, onFocus: (String, Boolean) -> Unit, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(if (value == 0) "" else value.toString()) }
    // Follow − / + presses and Clear all without fighting the cursor while typing.
    LaunchedEffect(value) { if ((text.toIntOrNull() ?: 0) != value) text = if (value == 0) "" else value.toString() }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter(Char::isDigit).take(4)
            text = digits
            onChange(digits.toIntOrNull() ?: 0)
        },
        modifier = Modifier.width(84.dp).onFocusChanged { onFocus(key, it.isFocused) },
        singleLine = true,
        placeholder = { Text("0", fontSize = 20.sp, color = C.Line, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        textStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
    )
}

@Composable
private fun StepButton(label: String, dark: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(if (dark) C.Ink else C.Ground).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 26.sp, color = if (dark) Color.White else C.Ink, fontWeight = FontWeight.Bold) }
}
