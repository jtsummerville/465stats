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
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod

/**
 * Backups of the app's own database (route0465.db) to "Documents/465stats backups" on the tablet.
 * Each zip is locked with the backup password (AES-256; opens in 7-Zip or WinRAR on a computer).
 * One zip per day ("465stats-backup-2026-10-06.zip"), rewritten through the day so it always holds
 * the latest data; the newest [KEEP] are kept. A sync app (e.g. Autosync for Google Drive) can copy
 * that folder off the tablet. The app itself never touches the network.
 */
object DataBackup {
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

    /** Automatic backup: skipped without a password, without file access, or before the first import. */
    @Synchronized
    fun auto(ctx: Context): File? {
        if (Prefs.backupPassword(ctx).isEmpty()) return null
        if (!XSales.hasAccess(ctx)) return null
        if (Db.get(ctx).days().isEmpty()) return null
        return writeToday(ctx)
    }

    /** "Back up now" button. Throws with a readable message if it can't. */
    @Synchronized
    fun now(ctx: Context): File {
        if (Prefs.backupPassword(ctx).isEmpty()) throw IllegalStateException("Set a backup password first.")
        if (!XSales.hasAccess(ctx)) throw IllegalStateException("File access isn't allowed yet. Tap \"Allow file access\" on Home.")
        return writeToday(ctx)
    }

    /**
     * New password saved: remove any backups that aren't password-protected (made before passwords
     * existed), then write today's backup with the new password.
     */
    @Synchronized
    fun passwordChanged(ctx: Context): File? {
        if (!XSales.hasAccess(ctx)) return null
        (dir().listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".zip") }.forEach { f ->
            val locked = runCatching { ZipFile(f).use { it.isEncrypted } }.getOrDefault(true)
            if (!locked) f.delete()
        }
        return if (Db.get(ctx).days().isEmpty()) null else writeToday(ctx)
    }

    private fun writeToday(ctx: Context): File {
        val f = File(dir(), "$PREFIX${LocalDate.now()}.zip")
        write(ctx, f, Prefs.backupPassword(ctx))
        prune()
        return f
    }

    /** Snapshot the live database into [dest]: a zip locked with AES-256 holding one route0465.db. */
    private fun write(ctx: Context, dest: File, password: String) {
        require(password.isNotEmpty())
        dest.parentFile?.mkdirs()
        val tmpDb = File(ctx.cacheDir, ENTRY)
        tmpDb.delete()
        val db = Db.get(ctx).writableDatabase
        try {
            db.execSQL("VACUUM INTO ?", arrayOf(tmpDb.path)) // consistent copy (SQLite 3.27+, Android 11+)
        } catch (_: Exception) {
            db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
            ctx.getDatabasePath("route0465.db").copyTo(tmpDb, overwrite = true)
        }
        val part = File(dest.parentFile, dest.name + ".part")
        part.delete()
        val params = ZipParameters().apply {
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            fileNameInZip = ENTRY
        }
        ZipFile(part, password.toCharArray()).use { it.addFile(tmpDb, params) }
        tmpDb.delete()
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
    }

    private fun prune() {
        list().drop(KEEP).forEach { it.delete() }
    }

    /** True if the picked file is a password-locked zip (so the restore dialog asks for one). */
    fun needsPassword(ctx: Context, uri: Uri): Boolean = runCatching {
        val raw = copyIn(ctx, uri)
        ZipFile(raw).use { it.isValidZipFile && it.isEncrypted }
    }.getOrDefault(false)

    private fun copyIn(ctx: Context, uri: Uri): File {
        val raw = File(ctx.cacheDir, "restore-raw.zip")
        ctx.contentResolver.openInputStream(uri)?.use { input -> raw.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalStateException("Couldn't open that file.")
        return raw
    }

    /**
     * Replace the app's data with a backup the user picked (a .zip from this folder, or a bare .db).
     * Checks the file and password first, and saves the current data as "465stats-before-restore-…zip"
     * so a restore can itself be undone.
     */
    fun restore(ctx: Context, uri: Uri, password: String): String = synchronized(AutoImport) {
        synchronized(this) {
            val incoming = File(ctx.cacheDir, "restore-incoming.db")
            incoming.delete()
            val raw = copyIn(ctx, uri)
            val zip = ZipFile(raw, password.toCharArray())
            if (zip.isValidZipFile) {
                zip.use { z ->
                    val h = z.fileHeaders.firstOrNull { it.fileName.endsWith(".db") }
                        ?: throw IllegalStateException("That zip doesn't have a 465stats backup in it.")
                    if (h.isEncrypted && password.isEmpty()) throw IllegalStateException("That backup needs its password.")
                    try {
                        z.extractFile(h, ctx.cacheDir.path, incoming.name)
                    } catch (e: ZipException) {
                        incoming.delete()
                        if (e.type == ZipException.Type.WRONG_PASSWORD || h.isEncrypted) throw IllegalStateException("Wrong password for that backup.")
                        throw e
                    }
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
                throw IllegalStateException(if (password.isNotEmpty()) "Wrong password, or that file isn't a 465stats backup." else "That file isn't a 465stats backup.")
            }

            // Safety copy of what's in the app right now (locked with the current backup password).
            val current = Prefs.backupPassword(ctx)
            if (XSales.hasAccess(ctx) && current.isNotEmpty()) {
                runCatching { write(ctx, File(dir(), "465stats-before-restore-${LocalDateTime.now().format(stamp)}.zip"), current) }
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
            DataBackup.auto(applicationContext)
        } catch (_: Exception) {
        }
        return Result.success()
    }
}
