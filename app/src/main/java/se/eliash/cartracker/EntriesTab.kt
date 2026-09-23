package se.eliash.cartracker

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import se.eliash.cartracker.ui.theme.TrendBetter
import se.eliash.cartracker.ui.theme.TrendBetterOnDark
import se.eliash.cartracker.ui.theme.TrendWorse
import se.eliash.cartracker.ui.theme.TrendWorseOnDark

/**
 * The fill-up entry form's contents.
 *
 * Owned by the screen so a half-filled entry survives leaving the tab, the
 * same as the expense form. The chosen fuel is deliberately not here: it is
 * keyed on the selected car and re-read from shared preferences whenever the
 * car changes, so it lives in the screen where that keying can happen.
 */
@Stable
class FuelEntryFormState {
    var distance by mutableStateOf("")
    var amount by mutableStateOf("")
    var pricePerUnit by mutableStateOf("")
    var missedPrevious by mutableStateOf(false)
    /** 0 reads [distance] as an odometer reading, 1 as a trip distance. */
    var distanceIsTrip by mutableIntStateOf(0)
    var dateMillis by mutableLongStateOf(System.currentTimeMillis())
    var showDatePicker by mutableStateOf(false)
    var fuelExpanded by mutableStateOf(false)

    /** Resets after a save, leaving the odometer/trip toggle where it was. */
    fun clear() {
        distance = ""
        amount = ""
        pricePerUnit = ""
        missedPrevious = false
        dateMillis = System.currentTimeMillis()
    }
}

@Composable
fun rememberFuelEntryFormState(): FuelEntryFormState = remember { FuelEntryFormState() }

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EntriesTab(
    car: Car,
    fuelHistory: List<FuelUp>,
    form: FuelEntryFormState,
    fuelType: String,
    onFuelTypeChange: (String) -> Unit,
    availableFuels: List<String>,
    currencyLocale: Locale,
    onSave: (fuelType: String, dateMillis: Long, odometerKm: Int, amount: Double, pricePerUnit: Double, missedPrevious: Boolean) -> Unit,
    onEditEntry: (FuelUp) -> Unit,
    modifier: Modifier = Modifier,
    /** Put the cursor in the amount field, with the keyboard up: the shortcut. */
    requestFocus: Boolean = false,
    onFocusRequested: () -> Unit = {}
) {
    val stats = remember(fuelHistory, car) { calculateFuelStats(car, fuelHistory) }
    val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    fun unitFor(fuel: String?) = if (fuel == "Electric") "kWh" else "L"

    // The litres (or kWh) field: the first one to type in, read straight off
    // the pump.
    val amountFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            // Straight after launch the window may not have focus yet, and a
            // keyboard asked for before it does is quietly not shown.
            snapshotFlow { windowInfo.isWindowFocused }.first { it }
            // Throws if the field is not laid out yet; the form is then still
            // there to tap, which is all that is lost.
            runCatching {
                amountFocus.requestFocus()
                keyboard?.show()
            }
            onFocusRequested()
        }
    }

    if (form.showDatePicker) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = form.dateMillis)
        DatePickerDialog(
            onDismissRequest = { form.showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { form.dateMillis = it }
                    form.showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { form.showDatePicker = false }) { Text("Cancel") }
            }
        ) { DatePicker(state = dpState) }
    }

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExposedDropdownMenuBox(
                expanded = form.fuelExpanded,
                onExpandedChange = { form.fuelExpanded = !form.fuelExpanded },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = fuelType,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Fuel") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(form.fuelExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = form.fuelExpanded,
                    onDismissRequest = { form.fuelExpanded = false }
                ) {
                    availableFuels.forEach { fuel ->
                        DropdownMenuItem(
                            text = { Text(fuel) },
                            onClick = { onFuelTypeChange(fuel); form.fuelExpanded = false }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = form.amount,
                onValueChange = { form.amount = it },
                label = { Text(if (fuelType == "Electric") "kWh" else "Liters") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).focusRequester(amountFocus)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.distance,
                onValueChange = { form.distance = it },
                label = { Text(if (form.distanceIsTrip == 0) "Odo (km)" else "Trip (km)") },
                placeholder = { Text("Optional") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                trailingIcon = {
                    IconButton(onClick = {
                        form.distanceIsTrip = if (form.distanceIsTrip == 0) 1 else 0
                    }) { Icon(Icons.Default.SwapVert, null) }
                }
            )
            OutlinedTextField(
                value = form.pricePerUnit,
                onValueChange = { form.pricePerUnit = it },
                label = { Text("Price per unit (SEK)") },
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
                value = dateFormat.format(Date(form.dateMillis)),
                onValueChange = {},
                readOnly = true,
                label = { Text("Date") },
                modifier = Modifier.weight(1f),
                trailingIcon = {
                    IconButton(onClick = { form.showDatePicker = true }) {
                        Icon(Icons.Default.DateRange, null)
                    }
                }
            )
            if (fuelHistory.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Checkbox(
                        checked = form.missedPrevious,
                        onCheckedChange = { form.missedPrevious = it }
                    )
                    Text(
                        "Missed previous",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        Button(
            onClick = {
                val amount = form.amount.replace(',', '.').toDoubleOrNull() ?: 0.0
                val price = form.pricePerUnit.replace(',', '.').toDoubleOrNull() ?: 0.0
                val typed = form.distance.replace(',', '.').toDoubleOrNull()

                // A trip distance is added to the last known reading for this
                // fuel, so the stored odometer stays absolute either way.
                val odometer = when {
                    typed == null -> 0
                    form.distanceIsTrip == 1 -> {
                        val lastKnown = fuelHistory
                            .firstOrNull { it.odometerKm > 0 && it.fuelTypeUsed == fuelType }
                            ?.odometerKm
                            ?: car.initialOdometer
                        lastKnown + typed.toInt()
                    }
                    else -> typed.toInt()
                }

                if (amount > 0) {
                    onSave(fuelType, form.dateMillis, odometer, amount, price, form.missedPrevious)
                    form.clear()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save Entry") }

        HorizontalDivider()

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${car.fuelType} Avg", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "%.2f ${unitFor(car.fuelType)}/100km".format(currencyLocale, stats.avgPrimary),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            "%.2f kr/mil".format(currencyLocale, stats.costPrimary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }
                    if (car.secondaryFuelType != null) {
                        VerticalDivider(modifier = Modifier.height(50.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${car.secondaryFuelType} Avg", style = MaterialTheme.typography.labelMedium)
                            Text(
                                "%.2f ${unitFor(car.secondaryFuelType)}/100km".format(currencyLocale, stats.avgSecondary),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                "%.2f kr/mil".format(currencyLocale, stats.costSecondary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (car.secondaryFuelType != null) "True Blended Cost: " else "Total Cost: ",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "%.2f kr/mil".format(currencyLocale, stats.blendedCost),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 16.sp
                    )
                }
            }
        }

        // Worked out from the whole history, so every fill-up already logged
        // gets its arrow and the best one its mark, not only new ones.
        val trend = remember(fuelHistory, car.initialOdometer) {
            consumptionTrend(fuelHistory, car.initialOdometer)
        }
        val trendColors = trendColors()

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(items = fuelHistory, key = { _, item -> item.id }) { _, fuelUp ->
                val info = trend[fuelUp.id]
                val consumption = info?.value

                Card(
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = {},
                        onLongClick = { onEditEntry(fuelUp) }
                    ),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    dateFormat.format(Date(fuelUp.dateMillis)),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                if (info?.isBest == true) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.EmojiEvents,
                                            contentDescription = null,
                                            tint = trendColors.better,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "Best ever",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = trendColors.better
                                        )
                                    }
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                val unit = unitFor(fuelUp.fuelTypeUsed)
                                val consumptionText = when {
                                    fuelUp.missedPrevious -> "Missed Previous"
                                    fuelUp.odometerKm == 0 -> "No Odo Data"
                                    consumption != null -> "%.2f $unit/100km".format(currencyLocale, consumption)
                                    else -> "First Entry"
                                }
                                Text(
                                    consumptionText,
                                    color = if (fuelUp.missedPrevious || fuelUp.odometerKm == 0) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    style = MaterialTheme.typography.labelLarge
                                )
                                info?.change?.takeIf { isVisibleChange(it) }?.let { change ->
                                    TrendLine(change, currencyLocale, trendColors)
                                }
                                Text(
                                    if (fuelUp.odometerKm == 0) "Odometer: Data missing" else "${fuelUp.odometerKm} km",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        val unitShort = unitFor(fuelUp.fuelTypeUsed)
                        Text(
                            "${"%.2f".format(currencyLocale, fuelUp.totalCostSek)} SEK",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "${"%.2f".format(currencyLocale, fuelUp.litersFilled)} $unitShort at " +
                                "${"%.2f".format(currencyLocale, fuelUp.pricePerLiterSek)} kr/$unitShort",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

/** Green for better, red for worse, each in the step that reads on this page. */
private data class TrendColors(val better: Color, val worse: Color)

@Composable
private fun trendColors(): TrendColors =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        TrendColors(TrendBetterOnDark, TrendWorseOnDark)
    } else {
        TrendColors(TrendBetter, TrendWorse)
    }

/**
 * How a fill-up's consumption compares with the one before it: the arrow
 * points the way the number moved, and lower is better, so down is green.
 * The difference is written out too, so the colour is never the only sign.
 */
@Composable
private fun TrendLine(change: Double, locale: Locale, colors: TrendColors) {
    val better = change < 0
    val color = if (better) colors.better else colors.worse
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (better) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
            contentDescription = if (better) "Better than the previous fill-up" else "Worse than the previous fill-up",
            tint = color,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            "%.2f".format(locale, kotlin.math.abs(change)),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

