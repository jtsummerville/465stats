package com.route0465.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

enum class Screen(val label: String, val title: String, val icon: ImageVector) {
    Home("Home", "Home", Icons.Filled.Home),
    Pay("Pay", "Pay", Icons.Filled.DateRange),
    Sales("Sales", "Sales", Icons.Filled.List),
    Inventory("Inventory", "Inventory", Icons.Filled.Info),
    Order("Order", "Order guide", Icons.Filled.ShoppingCart),
    Promos("Promos", "Promotions", Icons.Filled.Star),
    Shortages("Shortages", "Shortages", Icons.Filled.Warning),
    Setup("Setup", "Setup", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {
    private val version = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { AppTheme { AppRoot(version) } }
    }

    override fun onResume() {
        super.onResume()
        version.value = version.value + 1
        val ctx = applicationContext
        Thread {
            val r = try { AutoImport.check(ctx) } catch (_: Exception) { null }
            try { DataBackup.auto(ctx) } catch (_: Exception) { }
            if (r != null) runOnUiThread { version.value = version.value + 1 }
        }.start()
    }
}

@Composable
fun AppRoot(version: MutableState<Int>) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    val v = version.value
    val bump: () -> Unit = { version.value = version.value + 1 }
    val go: (Screen) -> Unit = { screen = it }

    Row(Modifier.fillMaxSize().background(C.Ground)) {
        NavigationRail(containerColor = C.Ink, modifier = Modifier.fillMaxHeight()) {
            Column(
                Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Spacer(Modifier.height(10.dp))
                Text("0465", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                Spacer(Modifier.height(10.dp))
                Screen.entries.forEach { s ->
                    NavigationRailItem(
                        selected = screen == s,
                        onClick = { screen = s },
                        icon = { Icon(s.icon, contentDescription = null) },
                        label = { Text(s.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
                        alwaysShowLabel = true,
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color.White, selectedTextColor = Color.White, indicatorColor = C.Green,
                            unselectedIconColor = C.RailMuted, unselectedTextColor = C.RailMuted,
                        ),
                    )
                }
            }
        }
        Column(Modifier.fillMaxSize()) {
            Header(screen.title, v)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (screen) {
                    Screen.Home -> HomeScreen(v, bump, go)
                    Screen.Pay -> PayScreen(v)
                    Screen.Sales -> SalesScreen(v)
                    Screen.Inventory -> InventoryScreen(v)
                    Screen.Order -> OrderScreen(v, bump, go)
                    Screen.Promos -> PromosScreen(v, bump)
                    Screen.Shortages -> ShortagesScreen(v, bump)
                    Screen.Setup -> SetupScreen(v, bump)
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, v: Int) {
    val ctx = LocalContext.current
    val today = LocalDate.now()
    val last = remember(v) { Db.get(ctx).days().firstOrNull() }
    val importedToday = last?.date == today
    Row(
        Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 28.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = C.Ink)
        Text("Route 0465 · ${today.format(Fmt.full)}", fontSize = 15.sp, color = C.Muted, modifier = Modifier.weight(1f))
        val label = when {
            importedToday -> "Imported today"
            last != null -> "Last import ${last.date.format(Fmt.day)}"
            else -> "No imports yet"
        }
        Text(
            label,
            Modifier.clip(RoundedCornerShape(8.dp)).background(if (importedToday) C.GreenSoft else C.Ground).padding(horizontal = 12.dp, vertical = 8.dp),
            color = if (importedToday) C.GreenDark else C.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}
