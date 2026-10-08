package com.route0465.app

import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import org.json.JSONArray
import kotlin.math.floor

/** One row of the case-UPC list (Ole price book, 2026-09-07). */
data class UpcItem(val code: String, val desc: String, val casePack: String, val upc: String, val note: String)

object Upc {
    @Volatile
    private var cache: List<UpcItem>? = null

    fun all(ctx: Context): List<UpcItem> = cache ?: synchronized(this) {
        cache ?: run {
            val text = ctx.assets.open("upc.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            List(arr.length()) { i ->
                val r = arr.getJSONArray(i)
                UpcItem(r.getString(0), r.getString(1), r.getString(2), r.getString(3), r.getString(4))
            }.also { cache = it }
        }
    }

    /** Same matching as the UPC Lookup page: every word must appear; exact code/UPC matches first. */
    fun search(items: List<UpcItem>, q: String): List<UpcItem> {
        val s = q.trim().lowercase()
        if (s.isEmpty()) return items
        val terms = s.split(Regex("\\s+"))
        val hits = items.filter { p -> val hay = "${p.code} ${p.desc} ${p.upc}".lowercase(); terms.all { hay.contains(it) } }
        val exact = hits.filter { it.code.lowercase() == s || it.upc == s }
        return exact + hits.filter { it !in exact }
    }

    private val L = listOf("0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111", "0111011", "0110111", "0001011")
    private val R = L.map { p -> p.map { if (it == '0') '1' else '0' }.joinToString("") }

    /** UPC-A as 95 modules ('1' = bar). */
    fun bits(u: String): String =
        "101" + u.substring(0, 6).map { L[it - '0'] }.joinToString("") + "01010" + u.substring(6).map { R[it - '0'] }.joinToString("") + "101"

    fun isGuard(i: Int) = i < 10 || i in 45..49 || i >= 85
}

/** Draws a UPC-A barcode with quiet zones and the printed digits, bars snapped to whole pixels so scanners read it cleanly. */
@Composable
fun UpcBarcode(upc: String, modifier: Modifier = Modifier, digitSize: Int = 14) {
    val bits = remember(upc) { Upc.bits(upc) }
    Column(modifier.background(Color.White).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(117f / 58f)) {
            val module = floor(size.width / 117f).coerceAtLeast(1f)
            val left = (size.width - module * 95f) / 2f
            val tall = size.height
            val short = size.height * 0.88f
            var i = 0
            while (i < 95) {
                if (bits[i] == '1') {
                    var j = i
                    while (j < 95 && bits[j] == '1' && Upc.isGuard(j) == Upc.isGuard(i)) j++
                    drawRect(Color.Black, Offset(left + i * module, 0f), Size((j - i) * module, if (Upc.isGuard(i)) tall else short))
                    i = j
                } else i++
            }
        }
        Text(
            "${upc[0]}  ${upc.substring(1, 6)}  ${upc.substring(6, 11)}  ${upc[11]}",
            color = Color.Black, fontFamily = FontFamily.Monospace, fontSize = digitSize.sp, fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun UpcScreen() {
    val ctx = LocalContext.current
    val all = remember { runCatching { Upc.all(ctx) }.getOrDefault(emptyList()) }
    var q by remember { mutableStateOf("") }
    val list = remember(q, all) { Upc.search(all, q) }
    var open by remember { mutableStateOf<UpcItem?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                q, { q = it }, Modifier.weight(1f), singleLine = true,
                label = { Text("Search item code, description, or UPC") },
            )
            Muted(
                if (q.isBlank()) "${all.size} products · ${all.count { it.upc.isNotEmpty() }} with barcodes"
                else "${list.size} match" + if (list.size == 1) "" else "es", 14,
            )
        }
        Muted("Case UPCs from the Ole price book (09/07/2026). Tap a product to show its barcode full screen for scanning.", 13)
        if (list.isEmpty()) Muted("No products match that search. Try part of the item code or a word from the description.")
        LazyVerticalGrid(
            columns = GridCells.Adaptive(250.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(list, key = { it.code }) { p ->
                Column(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).border(1.dp, C.Line, RoundedCornerShape(10.dp))
                        .clickable(enabled = p.upc.isNotEmpty()) { open = p }.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(p.code, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    Text(p.desc, fontSize = 14.sp, minLines = 2, maxLines = 2)
                    Row(Modifier.fillMaxWidth()) {
                        Text("Case qty ${p.casePack.ifEmpty { "?" }}", fontSize = 13.sp, color = C.Muted, modifier = Modifier.weight(1f))
                        Text(p.upc, fontSize = 13.sp, color = C.Muted)
                    }
                    if (p.upc.isNotEmpty()) {
                        UpcBarcode(p.upc, Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)), digitSize = 11)
                    } else {
                        Text(
                            "No barcode. ${p.note}", fontSize = 13.sp, color = C.Amber, fontWeight = FontWeight.Medium,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(C.AmberSoft).padding(12.dp),
                        )
                    }
                }
            }
        }
    }

    open?.let { p -> ScanView(p) { open = null } }
}

/** Full-screen white barcode at full brightness, for a store scanner to read off the tablet. */
@Composable
private fun ScanView(p: UpcItem, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The dialog has its own window; brighten that one so the barcode scans easily. It goes back when the dialog closes.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            dialogWindow?.let { it.attributes = it.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL } }
            onDispose { }
        }
        Box(Modifier.fillMaxSize().background(Color.White).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(p.code, fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
                Text(p.desc, fontSize = 18.sp, color = Color.Black, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
                Text("Case qty: ${p.casePack.ifEmpty { "?" }}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.Black)
                UpcBarcode(p.upc, Modifier.widthIn(max = 620.dp).fillMaxWidth(), digitSize = 22)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton(if (copied) "Copied ${p.upc}" else "Copy UPC") {
                        clipboard.setText(AnnotatedString(p.upc)); copied = true
                    }
                    SecondaryButton("Close", Modifier.heightIn(min = 52.dp)) { onClose() }
                }
            }
        }
    }
}
