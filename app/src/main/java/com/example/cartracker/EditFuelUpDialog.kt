package com.example.cartracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Edits or deletes a single fill-up.
 *
 * The edits are held here and only reported on save, so dismissing the dialog
 * discards them. [canMarkMissed] is false for the oldest fill-up, which has no
 * previous one to have missed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditFuelUpDialog(
    fuelUp: FuelUp,
    availableFuels: List<String>,
    canMarkMissed: Boolean,
    onSave: (FuelUp) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var odometer by remember { mutableStateOf(if (fuelUp.odometerKm == 0) "" else fuelUp.odometerKm.toString()) }
    var liters by remember { mutableStateOf(fuelUp.litersFilled.toString().replace('.', ',')) }
    var price by remember { mutableStateOf(fuelUp.pricePerLiterSek.toString().replace('.', ',')) }
    var missedPrevious by remember { mutableStateOf(fuelUp.missedPrevious) }
    var fuelExpanded by remember { mutableStateOf(false) }
    var fuelType by remember { mutableStateOf(fuelUp.fuelTypeUsed) }
    var dateMillis by remember { mutableLongStateOf(fuelUp.dateMillis) }
    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = dateMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { dateMillis = it }
                    showDatePicker = false
                }) { Text("OK") }
            }
        ) { DatePicker(state = dpState) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Fill-up") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExposedDropdownMenuBox(
                        expanded = fuelExpanded,
                        onExpandedChange = { fuelExpanded = !fuelExpanded },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = fuelType,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Fuel") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(fuelExpanded) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = fuelExpanded,
                            onDismissRequest = { fuelExpanded = false }
                        ) {
                            availableFuels.forEach { fuel ->
                                DropdownMenuItem(
                                    text = { Text(fuel) },
                                    onClick = { fuelType = fuel; fuelExpanded = false }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = liters,
                        onValueChange = { liters = it },
                        label = { Text(if (fuelType == "Electric") "kWh" else "Liters") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = odometer,
                        onValueChange = { odometer = it },
                        label = { Text("Odo (km)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = it },
                        label = { Text("Price (SEK)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(dateMillis)),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Date") },
                        modifier = Modifier.weight(1f),
                        trailingIcon = {
                            IconButton(onClick = { showDatePicker = true }) {
                                Icon(Icons.Default.DateRange, null)
                            }
                        }
                    )
                    if (canMarkMissed) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Checkbox(checked = missedPrevious, onCheckedChange = { missedPrevious = it })
                            Text(
                                "Missed previous fill-up",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val amount = liters.replace(',', '.').toDoubleOrNull() ?: 0.0
                val unitPrice = price.replace(',', '.').toDoubleOrNull() ?: 0.0
                if (amount > 0) {
                    onSave(
                        fuelUp.copy(
                            dateMillis = dateMillis,
                            odometerKm = odometer.toIntOrNull() ?: 0,
                            litersFilled = amount,
                            pricePerLiterSek = unitPrice,
                            totalCostSek = amount * unitPrice,
                            missedPrevious = missedPrevious,
                            fuelTypeUsed = fuelType
                        )
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDelete) { Text("Delete", color = Color.Red) }
        }
    )
}
