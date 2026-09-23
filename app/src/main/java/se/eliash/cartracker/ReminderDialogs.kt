package se.eliash.cartracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * The date picker speaks in UTC midnights, and a due date is a day in the
 * phone's own time. These convert between the two by calendar date, so a
 * picked day stays that day wherever the phone is.
 */

private fun pickerMillisToLocalNoon(utcMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 12, 0)
    }.timeInMillis
}

private fun localToPickerMillis(localMillis: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = localMillis }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPickerDialog(initialMillis: Long, onPicked: (Long) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = localToPickerMillis(initialMillis))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPicked(pickerMillisToLocalNoon(it)) }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) { DatePicker(state = state) }
}

private fun numberText(value: Int?): String = value?.toString() ?: ""

/**
 * Adds a reminder, or edits [existing]. Due by date, distance or both, with
 * an optional repeat and how early to warn.
 *
 * Changes are held here until saved, so dismissing discards them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderDialog(
    existing: Reminder?,
    carId: Int,
    locale: Locale,
    onSave: (Reminder) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var type by remember { mutableStateOf(ReminderType.fromStored(existing?.type ?: ReminderType.Service.name)) }
    var title by remember { mutableStateOf(existing?.title ?: type.label) }
    // Until the title is typed in, it follows the type chosen.
    var titleTouched by remember { mutableStateOf(existing != null && existing.title != type.label) }
    var dueDate by remember { mutableStateOf(existing?.dueDateMillis) }
    var dueKm by remember { mutableStateOf(numberText(existing?.dueOdometerKm)) }
    var repeatMonths by remember { mutableStateOf(numberText(existing?.repeatMonths)) }
    var repeatKm by remember { mutableStateOf(numberText(existing?.repeatKm)) }
    var warnDays by remember { mutableStateOf((existing?.warnDays ?: DEFAULT_WARN_DAYS).toString()) }
    var warnKm by remember { mutableStateOf((existing?.warnKm ?: DEFAULT_WARN_KM).toString()) }
    var typeExpanded by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (pickingDate) {
        DayPickerDialog(
            initialMillis = dueDate ?: System.currentTimeMillis(),
            onPicked = { dueDate = it },
            onDismiss = { pickingDate = false }
        )
    }

    val numberKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New reminder" else "Edit reminder") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = !typeExpanded }
                ) {
                    OutlinedTextField(
                        value = type.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        ReminderType.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    type = option
                                    if (!titleTouched) title = option.label
                                    typeExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; titleTouched = true },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Due", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.weight(1f)) {
                        Text(dueDate?.let { SimpleDateFormat("d MMM yyyy", locale).format(Date(it)) } ?: "Pick a date")
                    }
                    if (dueDate != null) {
                        TextButton(onClick = { dueDate = null }) { Text("Clear") }
                    }
                }
                OutlinedTextField(
                    value = dueKm,
                    onValueChange = { dueKm = it },
                    label = { Text("At odometer (km)") },
                    placeholder = { Text("Optional") },
                    singleLine = true,
                    keyboardOptions = numberKeyboard,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Whichever comes first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("Repeat every", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = repeatMonths,
                        onValueChange = { repeatMonths = it },
                        label = { Text("Months") },
                        singleLine = true,
                        keyboardOptions = numberKeyboard,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = repeatKm,
                        onValueChange = { repeatKm = it },
                        label = { Text("Km") },
                        singleLine = true,
                        keyboardOptions = numberKeyboard,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "Leave both empty for a one-off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("Warn me", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = warnDays,
                        onValueChange = { warnDays = it },
                        label = { Text("Days before") },
                        singleLine = true,
                        keyboardOptions = numberKeyboard,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = warnKm,
                        onValueChange = { warnKm = it },
                        label = { Text("Km before") },
                        singleLine = true,
                        keyboardOptions = numberKeyboard,
                        modifier = Modifier.weight(1f)
                    )
                }

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                if (onDelete != null) {
                    OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete reminder", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val result = reminderFromInput(
                    ReminderInput(type, title, dueDate, dueKm, repeatMonths, repeatKm, warnDays, warnKm),
                    existing, carId
                )
                val reminder = result.reminder
                if (reminder != null) onSave(reminder) else error = result.error
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Marks [reminder] done: when, and at what reading - today and the latest
 * logged one to begin with - with where the next one will fall shown before
 * it is confirmed.
 */
@Composable
fun CompleteReminderDialog(
    reminder: Reminder,
    suggestedOdometerKm: Int?,
    locale: Locale,
    onConfirm: (doneMillis: Long, doneOdometerKm: Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var doneMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var odometer by remember { mutableStateOf(numberText(suggestedOdometerKm)) }
    var pickingDate by remember { mutableStateOf(false) }

    if (pickingDate) {
        DayPickerDialog(initialMillis = doneMillis, onPicked = { doneMillis = it }, onDismiss = { pickingDate = false })
    }

    val odometerKm = odometer.filterNot { it.isWhitespace() }.toIntOrNull()?.takeIf { it > 0 }
    val next = markDone(reminder, doneMillis, odometerKm)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${reminder.title} done") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Done on " + SimpleDateFormat("d MMM yyyy", locale).format(Date(doneMillis)))
                }
                OutlinedTextField(
                    value = odometer,
                    onValueChange = { odometer = it },
                    label = { Text("Odometer then (km)") },
                    placeholder = { Text("Optional") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (next != null) "Next due ${describeDuePoint(next, locale)}."
                    else "It doesn't repeat, so it will be removed.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(doneMillis, odometerKm) }) { Text("Mark done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
