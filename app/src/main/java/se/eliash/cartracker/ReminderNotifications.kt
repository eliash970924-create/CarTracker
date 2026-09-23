package se.eliash.cartracker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Opens a car's reminders: the notification's tap. */
const val ACTION_OPEN_REMINDERS = "se.eliash.cartracker.OPEN_REMINDERS"
const val EXTRA_CAR_ID = "se.eliash.cartracker.CAR_ID"

private const val CHANNEL_ID = "reminders"

/** The hour the daily check aims for: morning, not whenever the phone chose. */
private const val CHECK_HOUR = 9

object ReminderScheduler {
    private const val DAILY = "reminder_check"
    private const val NOW = "reminder_check_now"

    /**
     * Once a day, starting at the next [CHECK_HOUR]. Kept if already there,
     * so opening the app does not keep pushing the next check back.
     */
    fun schedule(context: Context) {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, CHECK_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Straight away: after a reminder is saved or a fill-up logged. */
    fun checkNow(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ReminderWorker>().build())
    }
}

/**
 * Goes through every reminder and notifies each one that has reached a
 * stage - due soon, overdue - it has not been notified of before.
 *
 * Without permission to notify, nothing is recorded as announced, so the
 * notification still comes once permission is given.
 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        try {
            val db = AppDatabase.getDatabase(context)
            val reminders = db.reminderDao().getAllRemindersList()
            if (reminders.isEmpty()) return@withContext Result.success()

            val canNotify = canPostNotifications(context)
            if (canNotify) ensureChannel(context)
            val now = System.currentTimeMillis()
            val locale = Locale.forLanguageTag("sv-SE")

            reminders.groupBy { it.carId }.forEach { (carId, forCar) ->
                val car = db.carDao().getCarById(carId) ?: return@forEach
                val fuelUps = db.fuelUpDao().getFuelUpsListForCar(carId)
                val reading = latestReading(fuelUps)
                val pace = kmPerDay(fuelUps, now)

                forCar.forEach { reminder ->
                    val status = reminderStatus(reminder, reading, pace, now)
                    if (canNotify && shouldNotify(reminder, status)) {
                        notify(context, car, reminder, status, locale)
                        db.reminderDao().updateReminder(reminder.copy(notifiedStage = status.urgency.ordinal))
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}

fun canPostNotifications(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) return false
    return NotificationManagerCompat.from(context).areNotificationsEnabled()
}

private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Service, inspection and other things coming due"
        }
    )
}

private fun notify(context: Context, car: Car, reminder: Reminder, status: ReminderStatus, locale: Locale) {
    val open = Intent(context, MainActivity::class.java).apply {
        action = ACTION_OPEN_REMINDERS
        putExtra(EXTRA_CAR_ID, car.id)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    val tap = PendingIntent.getActivity(
        context, reminder.id, open,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val heading = when (status.urgency) {
        Urgency.Overdue -> "${reminder.title} is overdue"
        else -> "${reminder.title} coming up"
    }
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_reminder)
        .setContentTitle("${car.name}: $heading")
        .setContentText(describeReminder(reminder, status, locale))
        .setContentIntent(tap)
        .setAutoCancel(true)
        .build()
    try {
        NotificationManagerCompat.from(context).notify(reminder.id, notification)
    } catch (e: SecurityException) {
        // Permission withdrawn between the check and now: nothing to show.
    }
}
