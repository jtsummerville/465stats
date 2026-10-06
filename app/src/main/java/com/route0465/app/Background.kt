package com.route0465.app

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notify.createChannel(this)
        if (Prefs.auto(this)) AutoImport.schedule(this)
    }
}

class AutoImportWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        try {
            AutoImport.check(applicationContext)
        } catch (_: Exception) {
        }
        return Result.success()
    }
}

/**
 * Watches BCKAftMain.sqlite. When XSales's End of Day rewrites it, its modified time changes;
 * the app then runs the same import as the button (same date checks). Checks every 15 minutes
 * in the background and each time the app is opened.
 */
object AutoImport {
    private const val WORK = "auto-import"

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<AutoImportWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
    }

    @Synchronized
    fun check(ctx: Context): ImportResult? {
        if (!Prefs.auto(ctx) || !XSales.hasAccess(ctx)) return null
        Prefs.setLastCheck(ctx, Db.now())
        val folder = XSales.folder(ctx) ?: return null
        val f = File(folder, XSales.AFT)
        if (!f.isFile) return null
        val modified = f.lastModified()
        if (modified == Prefs.lastAftModified(ctx)) return null
        val result = XSales.runImport(ctx, useBefore = false)
        if (result !is ImportResult.Failed) Prefs.setLastAftModified(ctx, modified)
        if (result is ImportResult.Imported) Notify.imported(ctx, result)
        return result
    }
}

object Notify {
    private const val CHANNEL = "imports"

    fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Route imports", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun imported(ctx: Context, r: ImportResult.Imported) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = "Pay ${Fmt.money(r.pay)} · Net sales ${Fmt.money(r.netSales)}" +
            if (r.missingRates.isNotEmpty()) " · rates missing for ${r.missingRates.size} products" else ""
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else Notification.Builder(ctx)
        val n = builder
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Route imported · ${r.date.format(Fmt.day)}")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java)?.notify(1, n)
    }
}
