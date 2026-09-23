package se.eliash.cartracker

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/*
 * Automatic backup: one file, chosen once, kept up to date with the whole
 * garage.
 *
 * The file is picked through the system's own "save as" screen, which lists
 * Google Drive, the phone's storage and any other provider installed - so
 * backing up to Drive needs no sign-in, no API keys and no Cloud project. The
 * permission to that one file is kept, and a scheduled job rewrites it.
 *
 * It is a mirror, not a history: each run replaces the file with the garage
 * as it is. The one exception is a garage with no cars, which is never
 * written - if everything were deleted by mistake, the last backup is exactly
 * what is needed, so it is left alone.
 */

/** The preferences file the app has always used. The name predates CarTally. */
const val APP_PREFS = "CarTrackerPrefs"

enum class BackupFrequency(val days: Int) {
    Off(0), Daily(1), Weekly(7);

    companion object {
        fun fromStored(value: String?): BackupFrequency = entries.firstOrNull { it.name == value } ?: Off
    }
}

/** Everything Settings shows about automatic backup, as last recorded. */
data class AutoBackupState(
    val uri: String?,
    val fileName: String?,
    val frequency: BackupFrequency,
    val lastSuccessMillis: Long?,
    val lastFailureMillis: Long?,
    val lastFailureMessage: String?
) {
    val isSetUp: Boolean get() = uri != null
}

object AutoBackupPrefs {
    private const val URI = "auto_backup_uri"
    private const val NAME = "auto_backup_name"
    private const val FREQUENCY = "auto_backup_frequency"
    private const val LAST_SUCCESS = "auto_backup_last_success"
    private const val LAST_FAILURE = "auto_backup_last_failure"
    private const val LAST_FAILURE_MESSAGE = "auto_backup_last_failure_message"

    fun read(prefs: SharedPreferences) = AutoBackupState(
        uri = prefs.getString(URI, null),
        fileName = prefs.getString(NAME, null),
        frequency = BackupFrequency.fromStored(prefs.getString(FREQUENCY, null)),
        lastSuccessMillis = prefs.getLong(LAST_SUCCESS, -1).takeIf { it >= 0 },
        lastFailureMillis = prefs.getLong(LAST_FAILURE, -1).takeIf { it >= 0 },
        lastFailureMessage = prefs.getString(LAST_FAILURE_MESSAGE, null)
    )

    /** A new file starts with a clean record: the old one's history is not its. */
    fun setTarget(prefs: SharedPreferences, uri: String, name: String) {
        prefs.edit()
            .putString(URI, uri).putString(NAME, name)
            .remove(LAST_SUCCESS).remove(LAST_FAILURE).remove(LAST_FAILURE_MESSAGE)
            .apply()
    }

    fun clear(prefs: SharedPreferences) {
        prefs.edit()
            .remove(URI).remove(NAME).remove(FREQUENCY)
            .remove(LAST_SUCCESS).remove(LAST_FAILURE).remove(LAST_FAILURE_MESSAGE)
            .apply()
    }

    fun setFrequency(prefs: SharedPreferences, frequency: BackupFrequency) {
        prefs.edit().putString(FREQUENCY, frequency.name).apply()
    }

    fun recordSuccess(prefs: SharedPreferences, at: Long) {
        prefs.edit().putLong(LAST_SUCCESS, at).apply()
    }

    fun recordFailure(prefs: SharedPreferences, at: Long, message: String) {
        prefs.edit().putLong(LAST_FAILURE, at).putString(LAST_FAILURE_MESSAGE, message).apply()
    }
}

/**
 * The state, kept current: the worker records its result in preferences,
 * and this listens, so Settings updates the moment a backup finishes.
 */
@Composable
fun rememberAutoBackupState(prefs: SharedPreferences): AutoBackupState {
    var state by remember { mutableStateOf(AutoBackupPrefs.read(prefs)) }
    DisposableEffect(prefs) {
        // Held here as well as registered: preferences keep only a weak
        // reference, and a listener nothing else holds is collected.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
            state = AutoBackupPrefs.read(p)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

/** A line for Settings, and whether it is something to act on. */
data class BackupStatus(val text: String, val problem: Boolean)

/**
 * What Settings says about the last backup.
 *
 * A failure more recent than the last success is shown as it is. So is a
 * backup that is overdue - more than twice its interval old - because the
 * failure that matters most is the silent one: some phones' battery savers
 * stop scheduled work without a word, and a backup nobody knows has stopped
 * is worse than none.
 */
fun autoBackupStatus(state: AutoBackupState, now: Long, locale: Locale = Locale.getDefault()): BackupStatus {
    val success = state.lastSuccessMillis
    val failure = state.lastFailureMillis

    if (failure != null && (success == null || failure > success)) {
        return BackupStatus("Last attempt failed: ${state.lastFailureMessage ?: "unknown error"}", problem = true)
    }
    if (success == null) return BackupStatus("No backup yet", problem = false)

    val text = "Last backup ${describeWhen(success, now, locale)}"
    val overdue = state.frequency != BackupFrequency.Off &&
        now - success > 2L * state.frequency.days * TimeUnit.DAYS.toMillis(1)
    return if (overdue) {
        BackupStatus("$text - later than expected. Android may be holding it back; try Back up now.", problem = true)
    } else {
        BackupStatus(text, problem = false)
    }
}

/** "today at 14:02", "yesterday at 09:10", "3 days ago", "on 2 Mar 2026". */
fun describeWhen(then: Long, now: Long, locale: Locale): String {
    fun midnight(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    // Midnights between the two, in local time. Rounded, because a day with
    // a clock change in it is 23 or 25 hours long.
    val days = Math.round((midnight(now) - midnight(then)) / TimeUnit.DAYS.toMillis(1).toDouble())
    val time = SimpleDateFormat("HH:mm", locale).format(Date(then))
    return when {
        days <= 0 -> "today at $time"
        days == 1L -> "yesterday at $time"
        days < 14 -> "$days days ago"
        else -> "on " + SimpleDateFormat("d MMM yyyy", locale).format(Date(then))
    }
}

/** The file's name as its provider shows it, for Settings. */
fun displayNameOf(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
} catch (e: Exception) {
    null
}

/** The read and write access kept on the chosen file. */
const val BACKUP_URI_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

object AutoBackupScheduler {
    private const val PERIODIC = "auto_backup"
    private const val NOW = "auto_backup_now"

    /**
     * Runs roughly every day or week, when the battery is not low. Roughly:
     * Android runs scheduled work when it suits the phone, not at a set time.
     * Replacing the schedule keeps one, never two.
     */
    fun apply(context: Context, frequency: BackupFrequency) {
        val work = WorkManager.getInstance(context)
        if (frequency == BackupFrequency.Off) {
            work.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(frequency.days.toLong(), TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun runNow(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<AutoBackupWorker>().build())
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC)
            cancelUniqueWork(NOW)
        }
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val prefs = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        val target = AutoBackupPrefs.read(prefs).uri ?: return@withContext Result.success()

        try {
            if (!writeGarageBackup(context, Uri.parse(target))) {
                // No cars: nothing written, and the last backup kept on purpose.
                return@withContext Result.success()
            }
            AutoBackupPrefs.recordSuccess(prefs, System.currentTimeMillis())
            Result.success()
        } catch (e: SecurityException) {
            fail(prefs, "CarTally is no longer allowed to write to that file. Choose it again.")
        } catch (e: FileNotFoundException) {
            fail(prefs, "the file could not be found - it may have been moved or deleted. Choose it again.")
        } catch (e: Exception) {
            // Worth another go - a provider that was briefly unreachable -
            // but not for ever: after that, say so.
            if (runAttemptCount < 2) Result.retry()
            else fail(prefs, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(prefs: SharedPreferences, message: String): Result {
        AutoBackupPrefs.recordFailure(prefs, System.currentTimeMillis(), message)
        return Result.failure()
    }
}

/**
 * Writes the whole garage to [uri]. False, having written nothing, when
 * there are no cars - see the note at the top of this file.
 */
fun writeGarageBackup(context: Context, uri: Uri): Boolean {
    val db = AppDatabase.getDatabase(context)
    val cars = db.carDao().getCarsList().sortedBy { it.id }
    if (cars.isEmpty()) return false

    val backups = cars.map { car ->
        CarBackup(
            car = car,
            fuelUps = inHistoryOrder(db.fuelUpDao().getFuelUpsListForCar(car.id)),
            expenses = expensesInHistoryOrder(db.expenseDao().getExpensesListForCar(car.id)),
            photoName = car.imageUri?.takeUnless { isExternalPhotoReference(it) },
            reminders = db.reminderDao().getRemindersListForCar(car.id)
        )
    }

    // "wt" truncates. Plain "w" does not on every provider, and a shorter
    // backup written over a longer one would keep the old one's tail.
    val output = context.contentResolver.openOutputStream(uri, "wt")
        ?: throw FileNotFoundException("no stream for $uri")
    output.use { stream ->
        writeGarageZip(
            stream, backups,
            openPhoto = { name -> carPhotoFile(context, name).takeIf { it.exists() }?.inputStream() },
            formatDate = ::formatBackupDay
        )
    }
    return true
}
