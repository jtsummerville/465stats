package com.route0465.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object C {
    val Ink = Color(0xFF17201B)
    val Ground = Color(0xFFEEF0EC)
    val Line = Color(0xFFD5DAD4)
    val Divider = Color(0xFFE4E8E3)
    val Muted = Color(0xFF4F5B54)
    val RailMuted = Color(0xFFC9D2CB)
    val Green = Color(0xFF0E6B4A)
    val GreenDark = Color(0xFF0A5038)
    val GreenSoft = Color(0xFFE3F1EA)
    val Amber = Color(0xFF6B4A0E)
    val AmberSoft = Color(0xFFFBEFD9)
    val AmberLine = Color(0xFFE8C99A)
    val Red = Color(0xFF8E2117)
    val RedSoft = Color(0xFFFBE9E7)
    val Row = Color(0xFFF7F8F6)
    val Blue = Color(0xFF2D6CB5)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = C.Green, onPrimary = Color.White, background = C.Ground, surface = Color.White,
            onSurface = C.Ink, onBackground = C.Ink, secondary = C.Ink,
        ),
        content = content,
    )
}

@Composable
fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    bg: Color = Color.White,
    line: Color = C.Line,
    pad: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    var m = modifier.border(1.dp, line, shape).clip(shape).background(bg)
    if (onClick != null) m = m.clickable { onClick() }
    Column(m.padding(pad), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun H2(text: String) = Text(text, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = C.Ink)

@Composable
fun Muted(text: String, size: Int = 15) = Text(text, fontSize = size.sp, color = C.Muted, lineHeight = (size + 7).sp)

@Composable
fun Tile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = C.Ink, sub: String? = null, onClick: (() -> Unit)? = null) {
    Panel(modifier, onClick = onClick) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Muted)
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = valueColor)
        if (sub != null) Text(sub, fontSize = 12.sp, color = C.Muted)
    }
}

@Composable
fun Banner(text: String, bg: Color, fg: Color, title: String? = null, content: @Composable ColumnScope.() -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = fg)
        Text(text, fontSize = 15.sp, color = C.Ink, lineHeight = 22.sp)
        content()
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, big: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = if (big) 96.dp else 52.dp),
        shape = RoundedCornerShape(if (big) 14.dp else 12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = C.Green, contentColor = Color.White),
    ) {
        Text(text, fontSize = if (big) 28.sp else 16.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = C.Green),
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, fill: Boolean = false, onSelect: (Int) -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, C.Line, RoundedCornerShape(12.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                (if (fill) Modifier.weight(1f) else Modifier).clip(RoundedCornerShape(9.dp)).background(if (on) C.Ink else Color.Transparent)
                    .clickable { onSelect(i) }.heightIn(min = 46.dp).padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) Color.White else C.Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
        }
    }
}

/** A row in a simple table. Weights line the columns up. */
@Composable
fun TableRow(cells: List<String>, weights: List<Float>, bold: Set<Int> = emptySet(), header: Boolean = false, bg: Color = Color.Transparent, endAligned: Set<Int> = emptySet(), color: Color = C.Ink, oneLine: Set<Int> = emptySet(), small: Set<Int> = emptySet()) {
    Row(Modifier.fillMaxWidth().background(bg).padding(vertical = if (header) 8.dp else 11.dp), verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { i, t ->
            Text(
                t, modifier = Modifier.weight(weights[i]).padding(end = 8.dp),
                fontSize = if (header) 13.sp else if (i in small) 14.sp else 16.sp,
                maxLines = if (i in oneLine) 1 else Int.MAX_VALUE,
                softWrap = i !in oneLine,
                fontWeight = if (header) FontWeight.SemiBold else if (i in bold) FontWeight.ExtraBold else FontWeight.Normal,
                color = if (header) C.Muted else color,
                textAlign = if (i in endAligned) TextAlign.End else TextAlign.Start,
            )
        }
    }
}

@Composable
fun RowScope.Spacer1() = Box(Modifier.weight(1f))

@Composable
fun ProductPicker(onPick: (Product) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    var q by remember { mutableStateOf("") }
    val list = remember(q) { repo.products(q, 100) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Pick a product") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    label = { Text("Search code or name") }, modifier = Modifier.fillMaxWidth(),
                )
                if (list.isEmpty()) {
                    Muted(if (repo.productCount() == 0) "No products yet. They load from XSales with your first import." else "Nothing matches.")
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list) { p ->
                        Text(
                            "${p.code}   ${p.name}",
                            Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 13.dp),
                            fontSize = 16.sp,
                        )
                    }
                }
            }
        },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = C.Red, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = { Text(text) },
    )
}


/** True when the screen is wide enough for side-by-side panels (tablet held sideways); false when held upright. */
val LocalWide = compositionLocalOf { true }

interface SplitScope {
    /** Share of the width when side by side; full width when stacked. */
    fun Modifier.part(weight: Float): Modifier
}

/** Side by side when the tablet is sideways, stacked top to bottom when it's upright. */
@Composable
fun Split(gap: Dp = 20.dp, content: @Composable SplitScope.() -> Unit) {
    if (LocalWide.current) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            val row = this
            val scope = object : SplitScope {
                override fun Modifier.part(weight: Float): Modifier = with(row) { this@part.weight(weight) }
            }
            scope.content()
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            val scope = object : SplitScope {
                override fun Modifier.part(weight: Float): Modifier = this.fillMaxWidth()
            }
            scope.content()
        }
    }
}
