package se.eliash.cartracker

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * A car's reminders, most urgent first, with the way to add one, change one
 * or mark one done.
 *
 * The dialogs are this tab's own: nothing outside needs to know one is open.
 */
@Composable
fun RemindersTab(
    car: Car,
    reminders: List<Reminder>,
    fuelHistory: List<FuelUp>,
    locale: Locale,
    onSave: (Reminder) -> Unit,
    onDelete: (Reminder) -> Unit,
    onDone: (Reminder, doneMillis: Long, doneOdometerKm: Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val now = remember(reminders, fuelHistory) { System.currentTimeMillis() }
    val reading = remember(fuelHistory) { latestReading(fuelHistory) }
    val pace = remember(fuelHistory, now) { kmPerDay(fuelHistory, now) }
    val ordered = remember(reminders, reading, pace, now) {
        remindersInOrder(reminders.map { it to reminderStatus(it, reading, pace, now) })
    }

    var editing by remember { mutableStateOf<Reminder?>(null) }
    var adding by remember { mutableStateOf(false) }
    var completing by remember { mutableStateOf<Reminder?>(null) }

    // Whether notifications can be shown, looked at again whenever the
    // answer may have changed: after asking, and after the settings screen.
    var permissionChecks by remember { mutableIntStateOf(0) }
    val notificationsAllowed = remember(permissionChecks) { canPostNotifications(context) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionChecks++
    }
    val openSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionChecks++
    }

    fun save(reminder: Reminder) {
        onSave(reminder)
        // Asked for with the first reminder rather than at start, so the
        // question comes with its reason. Android stops showing it by itself
        // once it has been refused twice.
        if (!notificationsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (adding || editing != null) {
        ReminderDialog(
            existing = editing,
            carId = car.id,
            locale = locale,
            onSave = { reminder ->
                save(reminder)
                adding = false
                editing = null
            },
            onDelete = editing?.let { reminder ->
                {
                    onDelete(reminder)
                    editing = null
                }
            },
            onDismiss = { adding = false; editing = null }
        )
    }

    completing?.let { reminder ->
        CompleteReminderDialog(
            reminder = reminder,
            suggestedOdometerKm = reading?.km,
            locale = locale,
            onConfirm = { doneMillis, odometer ->
                onDone(reminder, doneMillis, odometer)
                completing = null
            },
            onDismiss = { completing = null }
        )
    }

    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add a reminder")
            }
        }

        if (reminders.isNotEmpty() && !notificationsAllowed) {
            item {
                NotificationsOffNote(onTurnOn = {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    } else {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", context.packageName, null))
                    }
                    openSettings.launch(intent)
                })
            }
        }

        if (ordered.isEmpty()) {
            item {
                Text(
                    "Nothing yet. Add the next inspection, service, tyre change or " +
                        "insurance renewal, by date, by distance or both - CarTally " +
                        "warns you before whichever comes first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }

        items(ordered, key = { it.first.id }) { (reminder, status) ->
            ReminderCard(
                reminder = reminder,
                status = status,
                locale = locale,
                onClick = { editing = reminder },
                onDone = { completing = reminder }
            )
        }

        if (ordered.any { it.first.dueOdometerKm != null }) {
            item {
                Text(
                    pace?.let {
                        "Distances are estimated from your usual %.0f km a day, so a warning can come before the next fill-up.".format(locale, it)
                    } ?: "Distances count from your latest fill-up. After a few weeks of fill-ups, CarTally can estimate ahead as well.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReminderCard(
    reminder: Reminder,
    status: ReminderStatus,
    locale: Locale,
    onClick: () -> Unit,
    onDone: () -> Unit
) {
    val statusColor = when (status.urgency) {
        Urgency.Overdue -> MaterialTheme.colorScheme.error
        Urgency.Soon -> MaterialTheme.colorScheme.primary
        Urgency.Ok -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        elevation = CardDefaults.cardElevation(2.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(reminder.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        describeReminder(reminder, status, locale),
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor,
                        fontWeight = if (status.urgency == Urgency.Ok) FontWeight.Normal else FontWeight.Bold
                    )
                    repeatText(reminder, locale)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = onDone) { Text("Done") }
            }
        }
    }
}

@Composable
private fun NotificationsOffNote(onTurnOn: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Notifications are off for CarTally, so reminders only show here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onTurnOn) { Text("Turn on") }
        }
    }
}

/** "Every 12 months or 15,000 km", or null for a reminder that does not repeat. */
fun repeatText(reminder: Reminder, locale: Locale): String? {
    val parts = listOfNotNull(
        reminder.repeatMonths?.let { if (it == 1) "1 month" else "$it months" },
        reminder.repeatKm?.let { "%,d km".format(locale, it) }
    )
    return if (parts.isEmpty()) null else "Every " + parts.joinToString(" or ")
}

/** Items from [reminders] that want attention now, most urgent first, for the banner. */
fun dueReminders(
    reminders: List<Reminder>,
    fuelHistory: List<FuelUp>,
    now: Long
): List<Pair<Reminder, ReminderStatus>> {
    val reading = latestReading(fuelHistory)
    val pace = kmPerDay(fuelHistory, now)
    return remindersInOrder(
        reminders.map { it to reminderStatus(it, reading, pace, now) }
            .filter { it.second.urgency != Urgency.Ok }
    )
}

/**
 * A strip above the fill-up form when something is due soon or overdue: the
 * screen opened most often is where it will be seen.
 */
@Composable
fun DueRemindersBanner(due: List<Pair<Reminder, ReminderStatus>>, locale: Locale, onOpen: () -> Unit) {
    if (due.isEmpty()) return
    val overdue = due.any { it.second.urgency == Urgency.Overdue }
    val container = if (overdue) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (overdue) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    val (first, firstStatus) = due.first()
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onOpen)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                "${first.title}: ${describeReminder(first, firstStatus, locale).replaceFirstChar { it.lowercase(locale) }}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            if (due.size > 1) {
                Text(
                    "and ${due.size - 1} more - tap to see",
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.8f)
                )
            }
        }
    }
}
