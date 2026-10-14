package com.route0465.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

@Composable
fun PayScreen(v: Int) {
    val ctx = LocalContext.current
    val days = remember(v) { Db.get(ctx).days() }
    val recurring = remember(v) { Expenses.recurring(Db.get(ctx)) }
    val today = LocalDate.now()
    var offset by remember { mutableStateOf(0) }
    val start = Periods.startOf(today).plusDays(offset * 14L)
    val end = start.plusDays(13)
    val inPeriod = days.filter { !it.date.isBefore(start) && !it.date.isAfter(end) }
    val status = when {
        offset == 0 -> "Current pay period"
        offset < 0 -> "Earlier pay period"
        else -> "Upcoming pay period"
    }

    ScreenColumn {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ArrowButton(left = true) { offset -= 1 }
            Panel(Modifier.weight(1f)) {
                Text(status, fontSize = 14.sp, color = C.Muted, fontWeight = FontWeight.SemiBold)
                Text("${start.format(Fmt.day)} – ${end.format(Fmt.day)}", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                val periodExp = (0..1).sumOf { w -> Expenses.forWeek(recurring, start.plusDays(w * 7L)).sumOf { it.weekly } }
                Text("Period total", fontSize = 14.sp, color = C.Muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                Text(Fmt.money(inPeriod.sumOf { it.pay } - periodExp), fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = C.Green)
                if (periodExp > 0) Muted("after ${Fmt.money(periodExp)} in recurring expenses", 13)
            }
            ArrowButton(left = false, enabled = offset < 0) { offset += 1 }
        }

        Split {
            for (w in 0..1) {
                val wStart = start.plusDays(w * 7L)
                val wEnd = wStart.plusDays(6)
                val wDays = inPeriod.filter { !it.date.isBefore(wStart) && !it.date.isAfter(wEnd) }.sortedBy { it.date }
                val wExp = Expenses.forWeek(recurring, wStart)
                val before = wDays.sumOf { it.pay }
                Panel(Modifier.part(1f)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("Week ${w + 1} · ${wStart.format(Fmt.md)} – ${wEnd.format(Fmt.md)}", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text(Fmt.money(before - wExp.sumOf { it.weekly }), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    }
                    HorizontalDivider(color = C.Divider)
                    if (wDays.isEmpty()) Muted("No route days")
                    wDays.forEach { d ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(d.date.format(Fmt.day) + if (d.date == today) " (today)" else "", fontSize = 17.sp, modifier = Modifier.weight(1f))
                            if (d.missingRates.isNotEmpty()) Text("rates missing  ", fontSize = 13.sp, color = C.Amber, fontWeight = FontWeight.Bold)
                            Text(Fmt.money(d.pay), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (wExp.isNotEmpty()) {
                        HorizontalDivider(color = C.Divider)
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text("Pay before expenses", fontSize = 15.sp, color = C.Muted, modifier = Modifier.weight(1f))
                            Text(Fmt.money(before), fontSize = 15.sp, color = C.Muted)
                        }
                        wExp.forEach { e ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text("${e.name} (recurring)", fontSize = 15.sp, color = C.Red, modifier = Modifier.weight(1f))
                                Text("− " + Fmt.money(e.weekly), fontSize = 15.sp, color = C.Red, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    val payday = wEnd
                    Muted((if (payday.isAfter(today)) "Payday " else "Paid ") + payday.format(Fmt.day), 13)
                }
            }
        }
    }
}

/** A large, easy-to-tap arrow for moving between pay periods. */
@Composable
private fun ArrowButton(left: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.size(76.dp).clip(shape).background(if (enabled) Color.White else C.Ground)
            .border(2.dp, if (enabled) C.Green else C.Line, shape).clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (left) Icons.Filled.KeyboardArrowLeft else Icons.Filled.KeyboardArrowRight,
            contentDescription = if (left) "Earlier pay period" else "Later pay period",
            tint = if (enabled) C.Green else C.Line,
            modifier = Modifier.size(56.dp),
        )
    }
}
