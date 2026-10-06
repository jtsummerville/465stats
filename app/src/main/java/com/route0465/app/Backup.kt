package com.route0465.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Environment
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backups of the app's own database (route0465.db) to "Documents/465stats backups" on the tablet.
 * One zip per day ("465stats-backup-2026-10-06.zip"), rewritten through the day so it always holds
 * the latest data; the newest [KEEP] are kept. A sync app (e.g. Autosync for Google Drive) can copy
 * that folder off the tablet. The app itself never touches the network.
 */
object Backup {
    const val FOLDER = "465stats backups"
    private const val PREFIX = "465stats-backup-"
    private const val ENTRY = "route0465.db"
    private const val KEEP = 30
    private const val WORK = "daily-backup"
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm", Locale.US)

    fun dir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), FOLDER)

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<BackupWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Backups in the folder, newest first. */
    fun list(): List<File> =
        (dir().listFiles() ?: emptyArray()).filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(".zip") }
            .sortedByDescending { it.name }

    /** Automatic backup: skipped without file access or before the first import. */
    @Synchronized
    fun auto(ctx: Context): File? {
        if (!XSales.hasAccess(ctx)) return null
        if (Db.get(ctx).days().isEmpty()) return null
        return writeToday(ctx)
    }

    /** "Back up now" button. Throws with a readable message if it can't. */
    @Synchronized
    fun now(ctx: Context): File {
        if (!XSales.hasAccess(ctx)) throw IllegalStateException("File access isn't allowed yet. Tap \"Allow file access\" on Home.")
        return writeToday(ctx)
    }

    private fun writeToday(ctx: Context): File {
        val f = File(dir(), "$PREFIX${LocalDate.now()}.zip")
        write(ctx, f)
        prune()
        return f
    }

    /** Snapshot the live database into [dest] as a zip with a single route0465.db entry. */
    private fun write(ctx: Context, dest: File) {
        dest.parentFile?.mkdirs()
        val tmpDb = File(ctx.cacheDir, "backup-snapshot.db")
        tmpDb.delete()
        val db = Db.get(ctx).writableDatabase
        try {
            db.execSQL("VACUUM INTO ?", arrayOf(tmpDb.path)) // consistent copy (SQLite 3.27+, Android 11+)
        } catch (_: Exception) {
            db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
            ctx.getDatabasePath("route0465.db").copyTo(tmpDb, overwrite = true)
        }
        val part = File(dest.parentFile, dest.name + ".part")
        ZipOutputStream(part.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry(ENTRY))
            tmpDb.inputStream().use { it.copyTo(z) }
            z.closeEntry()
        }
        tmpDb.delete()
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
    }

    private fun prune() {
        list().drop(KEEP).forEach { it.delete() }
    }

    /**
     * Replace the app's data with a backup the user picked (a .zip from this folder, or a bare .db).
     * Checks the file first, and saves the current data as "465stats-before-restore-…zip" so a
     * restore can itself be undone.
     */
    fun restore(ctx: Context, uri: Uri): String = synchronized(AutoImport) {
        synchronized(this) {
            val incoming = File(ctx.cacheDir, "restore-incoming.db")
            incoming.delete()
            val raw = File(ctx.cacheDir, "restore-raw")
            ctx.contentResolver.openInputStream(uri)?.use { input -> raw.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException("Couldn't open that file.")
            val isZip = raw.inputStream().use { s -> val b = ByteArray(2); s.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() }
            if (isZip) {
                ZipInputStream(raw.inputStream().buffered()).use { z ->
                    var e = z.nextEntry
                    while (e != null && !e.name.endsWith(".db")) e = z.nextEntry
                    if (e == null) throw IllegalStateException("That zip doesn't have a 465stats backup in it.")
                    incoming.outputStream().use { z.copyTo(it) }
                }
                raw.delete()
            } else {
                raw.renameTo(incoming)
            }

            val days = try {
                SQLiteDatabase.openDatabase(incoming.path, null, SQLiteDatabase.OPEN_READWRITE).use { chk ->
                    val n = chk.rawQuery("SELECT COUNT(*) FROM days", null).use { c -> c.moveToFirst(); c.getInt(0) }
                    if (chk.version == 0) chk.version = 1 // so the app opens it as-is instead of re-creating tables
                    n
                }
            } catch (e: Exception) {
                incoming.delete()
                throw IllegalStateException("That file isn't a 465stats backup.")
            }

            // Safety copy of what's in the app right now.
            if (XSales.hasAccess(ctx)) {
                runCatching { write(ctx, File(dir(), "465stats-before-restore-${LocalDateTime.now().format(stamp)}.zip")) }
            }

            val helper = Db.get(ctx)
            helper.close()
            val live = ctx.getDatabasePath("route0465.db")
            File(live.path + "-wal").delete()
            File(live.path + "-shm").delete()
            File(live.path + "-journal").delete()
            incoming.copyTo(live, overwrite = true)
            incoming.delete()
            helper.log("Restored from backup ($days days)")
            "Restored. The app now has $days saved days from that backup."
        }
    }
}

class BackupWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        try {
            Backup.auto(applicationContext)
        } catch (_: Exception) {
        }
        return Result.success()
    }
}
