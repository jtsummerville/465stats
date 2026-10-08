package com.route0465.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

@Composable
fun PaperworkScreen(v: Int, bump: () -> Unit, shareMsg: MutableState<String?>) {
    val ctx = LocalContext.current
    val repo = remember { Db.get(ctx) }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()

    val photos = remember(v) { repo.photos(today) }
    val eod = remember(v) { repo.eodPdf(today) }
    val stores = remember(v, photos) {
        // Stores serviced today (if imported) first, then the rest of route 465, then any typed-in store with photos.
        val served = repo.docs(today).filter { !it.voided }.map { it.cusCode to it.store }.distinctBy { it.first }
        val all = repo.stores()
        val typed = photos.map { it.cusCode to it.store }.distinctBy { it.first }
        (served + all + typed).distinctBy { it.first }
    }

    var pendingPath by rememberSaveable { mutableStateOf("") }
    var pendingCus by rememberSaveable { mutableStateOf("") }
    var pendingStore by rememberSaveable { mutableStateOf("") }
    var otherName by remember { mutableStateOf("") }
    var emails by remember { mutableStateOf(Prefs.bossEmails(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<StorePhoto?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = File(pendingPath)
        if (ok && f.isFile && f.length() > 0) repo.addPhoto(today, pendingCus, pendingStore, f.path) else f.delete()
        pendingPath = ""
        bump()
    }
    fun shoot(cus: String, store: String) {
        val f = Paperwork.newPhotoFile(ctx, today)
        pendingPath = f.path; pendingCus = cus; pendingStore = store
        try {
            camera.launch(Paperwork.uriFor(ctx, f))
        } catch (e: Exception) {
            message = "Couldn't open the camera: ${e.message}"
        }
    }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = withContext(Dispatchers.IO) {
                try { Paperwork.receiveEod(ctx, listOf(uri)) } catch (e: Exception) { "Couldn't take that file: ${e.message}" }
            }
            bump()
        }
    }

    ScreenColumn {
        shareMsg.value?.let { m ->
            Banner(m, if (m.startsWith("Got") || m.startsWith("Replaced")) C.GreenSoft else C.AmberSoft, C.GreenDark) {
                Text("Dismiss", color = C.Green, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { shareMsg.value = null }.padding(vertical = 6.dp))
            }
        }
        Split {
            Panel(Modifier.part(1.4f)) {
                H2("Store paperwork · ${today.format(Fmt.day)}")
                Muted("At each store, tap Take photo and shoot every page the store prints. Photos go into one PDF, in the order you take them.", 14)
                if (stores.isEmpty()) Muted("Your stores load with your first End of Day import. Until then, type the store name below.", 14)
                stores.forEach { (cus, name) ->
                    val mine = photos.filter { it.cusCode == cus }
                    HorizontalDivider(color = C.Divider)
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Muted(if (mine.isEmpty()) "No photos" else "${mine.size} photo" + if (mine.size == 1) "" else "s", 13)
                        }
                        PrimaryButton("Take photo") { shoot(cus, name) }
                    }
                    if (mine.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            mine.forEach { p -> Thumb(p) { deleting = p } }
                        }
                    }
                }
                HorizontalDivider(color = C.Divider)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(otherName, { otherName = it }, Modifier.weight(1f), label = { Text("Store not listed") }, singleLine = true)
                    SecondaryButton("Take photo", enabled = otherName.isNotBlank()) {
                        val n = otherName.trim()
                        shoot("other:$n", n)
                        otherName = ""
                    }
                }
            }

            Column(Modifier.part(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Panel {
                    H2("XSales End of Day PDF")
                    if (eod != null) {
                        Text("Received ${eod.receivedAt}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = C.GreenDark)
                        Muted(eod.name, 14)
                        SecondaryButton("Open it") {
                            runCatching { Paperwork.view(ctx, File(eod.path)) }.onFailure { message = "No app here can open PDFs." }
                        }
                    } else {
                        Muted("After End of Day in XSales, send the PDF the way you normally do, and pick 465stats instead of email.", 14)
                    }
                    SecondaryButton(if (eod == null) "Pick the PDF from files instead" else "Replace with a different PDF") {
                        pickPdf.launch(arrayOf("application/pdf"))
                    }
                }

                Panel {
                    H2("Send to bosses")
                    OutlinedTextField(
                        emails, { emails = it; Prefs.setBossEmails(ctx, it) }, Modifier.fillMaxWidth(),
                        label = { Text("Boss emails (separate with commas)") },
                    )
                    val storeCount = photos.map { it.cusCode }.distinct().size
                    Muted(
                        "Attaching:\n" +
                            (if (eod != null) "• XSales End of Day PDF\n" else "• No End of Day PDF yet\n") +
                            (if (photos.isNotEmpty()) "• Store paperwork: ${photos.size} photos from $storeCount stores, as one PDF" else "• No store photos yet"),
                        14,
                    )
                    PrimaryButton(if (busy) "Building…" else "Send End of Day email", Modifier.fillMaxWidth(), enabled = !busy && (eod != null || photos.isNotEmpty())) {
                        busy = true
                        message = null
                        scope.launch {
                            val built = withContext(Dispatchers.IO) {
                                runCatching { Paperwork.buildStorePdf(ctx, today, photos) }
                            }
                            busy = false
                            val storePdf = built.getOrElse { e -> message = "Couldn't build the store PDF: ${e.message}"; return@launch }
                            val files = listOfNotNull(eod?.let { File(it.path) }?.takeIf { it.isFile }, storePdf)
                            val perStore = photos.groupBy { it.store }.entries.joinToString("\n") { (s, l) -> "$s: ${l.size} page" + if (l.size == 1) "" else "s" }
                            val body = "Route 0465 End of Day for ${today.format(Fmt.full)}.\n\n" +
                                (if (eod != null) "Attached: XSales End of Day PDF" else "XSales End of Day PDF: not attached") +
                                (if (storePdf != null) " and store paperwork (${storePdf.length() / 1024} KB).\n\n$perStore" else ".")
                            try {
                                Paperwork.email(ctx, today, emails, files, body)
                                repo.log("End of Day email prepared: ${files.size} attachments, ${photos.size} store photos")
                                if (storePdf != null && storePdf.length() > 20L * 1024 * 1024) {
                                    message = "Heads up: the store PDF is ${storePdf.length() / (1024 * 1024)} MB, close to Gmail's 25 MB limit."
                                }
                            } catch (e: Exception) {
                                message = "Couldn't open the email: ${e.message}"
                            }
                        }
                    }
                    if (busy) CircularProgressIndicator(color = C.Green)
                    if (eod == null && photos.isNotEmpty()) Muted("The End of Day PDF isn't here yet, so the email would go without it.", 13)
                    message?.let { Banner(it, C.AmberSoft, C.Amber) }
                }
            }
        }
    }

    deleting?.let { p ->
        ConfirmDialog(
            "Delete this photo?", "${p.store}. It's removed from today's paperwork.", "Delete",
            onConfirm = { repo.deletePhoto(p.id); File(p.path).delete(); bump() },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun Thumb(p: StorePhoto, onTap: () -> Unit) {
    val bmp = remember(p.path) { Paperwork.loadScaled(p.path, 260)?.asImageBitmap() }
    Box(Modifier.size(width = 96.dp, height = 124.dp).clip(RoundedCornerShape(8.dp)).background(C.Ground).clickable { onTap() }) {
        if (bmp != null) Image(bmp, contentDescription = "Photo for ${p.store}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Text(
            "✕", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(999.dp)).background(Color(0x99000000)).padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}
