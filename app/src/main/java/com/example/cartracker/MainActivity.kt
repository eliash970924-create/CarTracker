package com.example.cartracker

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun loadAndRotateBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        var stream = context.contentResolver.openInputStream(uri) ?: return null
        val bitmap = BitmapFactory.decodeStream(stream)
        stream.close()

        stream = context.contentResolver.openInputStream(uri) ?: return bitmap
        val exif = ExifInterface(stream)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        stream.close()

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }

        if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } catch (e: Exception) { null }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { FuelEntryScreen() } }
        }
    }
}

@Composable
fun NativeLineChart(data: List<Double>, title: String, lineColor: Color) {
    if (data.isEmpty()) return
    val maxVal = (data.maxOrNull() ?: 10.0) + (data.maxOrNull() ?: 10.0) * 0.1
    val minVal = ((data.minOrNull() ?: 0.0) - (data.minOrNull() ?: 0.0) * 0.1).coerceAtLeast(0.0)
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                val width = size.width
                val height = size.height
                val xStep = if (data.size > 1) width / (data.size - 1) else width
                val yRange = if (maxVal == minVal) 1.0 else maxVal - minVal
                val strokePath = Path()
                val fillPath = Path()
                fillPath.moveTo(0f, height)
                data.forEachIndexed { index, value ->
                    val x = index * xStep
                    val y = height - ((value - minVal) / yRange * height).toFloat()
                    if (index == 0) { strokePath.moveTo(x, y); fillPath.lineTo(x, y) } else { strokePath.lineTo(x, y); fillPath.lineTo(x, y) }
                    drawCircle(color = lineColor, radius = 6f, center = Offset(x, y))
                }
                fillPath.lineTo(width, height)
                fillPath.close()
                drawPath(path = fillPath, brush = Brush.verticalGradient(colors = listOf(lineColor.copy(alpha = 0.4f), Color.Transparent), startY = 0f, endY = height))
                drawPath(path = strokePath, color = lineColor, style = Stroke(width = 6f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Lowest: %.2f".format(Locale("sv", "SE"), data.minOrNull() ?: 0.0), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Text("Highest: %.2f".format(Locale("sv", "SE"), data.maxOrNull() ?: 0.0), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FuelEntryScreen(viewModel: FuelViewModel = viewModel()) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val prefs = remember { context.getSharedPreferences("CarTrackerPrefs", Context.MODE_PRIVATE) }

    var currentTab by remember { mutableStateOf("Entries") }
    var chartSubTab by remember { mutableStateOf("Price") }

    val cars by viewModel.allCars.collectAsState(initial = emptyList())
    var selectedCar by remember { mutableStateOf<Car?>(null) }
    var showAddCarDialog by remember { mutableStateOf(false) }
    var editingCar by remember { mutableStateOf<Car?>(null) }

    var carName by remember { mutableStateOf("") }
    var initialOdo by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var odoError by remember { mutableStateOf<String?>(null) }
    var selectedFuel1 by remember { mutableStateOf("Petrol") }
    var isBifuel by remember { mutableStateOf(false) }
    var selectedFuel2 by remember { mutableStateOf("Electric") }
    var newCarImageUri by remember { mutableStateOf<String?>(null) }
    var newCarDetectedColor by remember { mutableStateOf<Long?>(null) }
    var newCarThemeColor by remember { mutableStateOf<Long>(0xFF1976D2) }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            newCarImageUri = uri.toString()
            try {
                val bitmap = loadAndRotateBitmap(context, uri)
                if (bitmap != null) {
                    val scaled = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
                    val cInt = scaled.getPixel(0, 0)
                    scaled.recycle()
                    val hexColor = Color(cInt).copy(alpha = 1f).toArgb().toLong() and 0xFFFFFFFFL
                    newCarDetectedColor = hexColor
                    newCarThemeColor = hexColor
                }
            } catch (e: Exception) { }
        }
    }

    LaunchedEffect(selectedCar) { selectedCar?.let { viewModel.checkRecurringExpenses(it.id) } }
    LaunchedEffect(cars) { if (selectedCar == null && cars.isNotEmpty()) selectedCar = cars.first() }

    var distanceInput by remember { mutableStateOf("") }
    var liters by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var missedPrevious by remember { mutableStateOf(false) }
    var inputMode by remember { mutableIntStateOf(0) }
    var editingFuelUp by remember { mutableStateOf<FuelUp?>(null) }

    var expDateMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var expCategoryExpanded by remember { mutableStateOf(false) }
    val expCategories = listOf("Maintenance", "Tires", "Insurance", "Parking", "Wash", "Tolls", "Other")
    var expCategory by remember { mutableStateOf(expCategories[0]) }
    var expDesc by remember { mutableStateOf("") }
    var expCost by remember { mutableStateOf("") }
    var showExpDatePicker by remember { mutableStateOf(false) }
    var expIsMonthly by remember { mutableStateOf(false) }

    var entryDateMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showMainDatePicker by remember { mutableStateOf(false) }
    var expandedEntryFuel by remember { mutableStateOf(false) }

    val availableFuels = remember(selectedCar) { listOfNotNull(selectedCar?.fuelType, selectedCar?.secondaryFuelType).ifEmpty { listOf("Petrol") } }
    var entryFuelType by remember(selectedCar) {
        val savedFuel = selectedCar?.let { prefs.getString("last_fuel_${it.id}", null) }
        mutableStateOf(if (savedFuel != null && availableFuels.contains(savedFuel)) savedFuel else (availableFuels.firstOrNull() ?: "Petrol"))
    }

    val fuelHistory by remember(selectedCar) { if (selectedCar != null) viewModel.getFuelUpsForCar(selectedCar!!.id) else kotlinx.coroutines.flow.emptyFlow() }.collectAsState(initial = emptyList())
    val expenseHistory by remember(selectedCar) { if (selectedCar != null) viewModel.getExpensesForCar(selectedCar!!.id) else kotlinx.coroutines.flow.emptyFlow() }.collectAsState(initial = emptyList())

    val svLocale = Locale.forLanguageTag("sv-SE")

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    context.contentResolver.openOutputStream(it)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                        writer.write(FUEL_HEADER + "\n")
                        fuelHistory.forEach { fuelUp ->
                            val dateStr = dateFormat.format(Date(fuelUp.dateMillis))
                            writer.write("$dateStr,${fuelUp.odometerKm},${csvEscape(fuelUp.fuelTypeUsed)},${fuelUp.litersFilled},${fuelUp.pricePerLiterSek},${fuelUp.totalCostSek},${fuelUp.missedPrevious}\n")
                        }

                        // Appended as a second section so the block above stays
                        // byte-identical to what earlier versions wrote.
                        if (expenseHistory.isNotEmpty()) {
                            writer.write("\n" + EXPENSE_SECTION_MARKER + "\n")
                            writer.write(EXPENSE_HEADER + "\n")
                            expenseHistory.forEach { expense ->
                                val dateStr = dateFormat.format(Date(expense.dateMillis))
                                writer.write("$dateStr,${csvEscape(expense.category)},${csvEscape(expense.description)},${expense.costSek},${expense.isMonthly}\n")
                            }
                        }

                        // Last, so the two blocks above keep the byte layout
                        // older versions wrote. Lets a file be restored onto a
                        // fresh install without creating the car by hand.
                        selectedCar?.let { car ->
                            writer.write("\n" + CAR_SECTION_MARKER + "\n")
                            writer.write(CAR_HEADER + "\n")
                            writer.write("${csvEscape(car.name)},${csvEscape(car.fuelType)},${csvEscape(car.secondaryFuelType ?: "")},${car.initialOdometer},${car.themeColor ?: ""}\n")
                        }
                    }
                    val summary = "Exported ${fuelHistory.size} fill-ups and ${expenseHistory.size} expenses"
                    launch(Dispatchers.Main) { Toast.makeText(context, summary, Toast.LENGTH_LONG).show() }
                } catch (e: Exception) { launch(Dispatchers.Main) { Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show() } }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val fallbackCarId = selectedCar?.id
            val fallbackCarName = selectedCar?.name
            scope.launch(Dispatchers.IO) {
                try {
                    val fuelRows = mutableListOf<FuelUp>()
                    val expenseRows = mutableListOf<Expense>()
                    var importedCar: ImportedCar? = null

                    context.contentResolver.openInputStream(it)?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
                        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        var section = Section.FUEL
                        var skipHeader = false

                        lines.forEachIndexed { index, rawLine ->
                            val line = rawLine.trim()
                            when {
                                line.isEmpty() -> return@forEachIndexed
                                // A file written before expenses existed never
                                // reaches this marker and parses as fuel only.
                                line == EXPENSE_SECTION_MARKER -> {
                                    section = Section.EXPENSES
                                    skipHeader = true
                                    return@forEachIndexed
                                }
                                line == CAR_SECTION_MARKER -> {
                                    section = Section.CAR
                                    skipHeader = true
                                    return@forEachIndexed
                                }
                                index == 0 -> return@forEachIndexed   // fuel header
                                skipHeader -> {
                                    skipHeader = false
                                    return@forEachIndexed             // expense header
                                }
                            }

                            val tokens = parseCsvLine(line)

                            // Parsed before any date handling: a car row's
                            // first field is a name, and feeding that to
                            // SimpleDateFormat throws and aborts the import.
                            if (section == Section.CAR) {
                                if (tokens.size >= 4 && tokens[0].isNotBlank()) {
                                    importedCar = ImportedCar(
                                        name = tokens[0],
                                        fuelType = tokens[1].ifBlank { "Petrol" },
                                        secondaryFuelType = tokens[2].ifBlank { null },
                                        initialOdometer = tokens[3].toIntOrNull() ?: 0,
                                        themeColor = tokens.getOrNull(4)?.toLongOrNull()
                                    )
                                }
                                return@forEachIndexed
                            }

                            val date = tokens.getOrNull(0)?.let { t -> dateFormat.parse(t)?.time }
                                ?: System.currentTimeMillis()

                            if (section == Section.EXPENSES) {
                                val cost = tokens.getOrNull(3)?.toDoubleOrNull() ?: 0.0
                                if (tokens.size >= 5 && cost > 0) {
                                    expenseRows.add(
                                        Expense(
                                            carId = 0,
                                            dateMillis = date,
                                            category = tokens[1],
                                            description = tokens[2],
                                            costSek = cost,
                                            isMonthly = tokens[4].toBooleanStrictOrNull() ?: false
                                        )
                                    )
                                }
                            } else {
                                val amount = tokens.getOrNull(3)?.toDoubleOrNull() ?: 0.0
                                if (tokens.size >= 7 && amount > 0) {
                                    fuelRows.add(
                                        FuelUp(
                                            carId = 0,
                                            fuelTypeUsed = tokens[2],
                                            dateMillis = date,
                                            odometerKm = tokens[1].toIntOrNull() ?: 0,
                                            litersFilled = amount,
                                            pricePerLiterSek = tokens[4].toDoubleOrNull() ?: 0.0,
                                            totalCostSek = tokens[5].toDoubleOrNull() ?: 0.0,
                                            missedPrevious = tokens[6].toBooleanStrictOrNull() ?: false
                                        )
                                    )
                                }
                            }
                        }
                    }

                    val dayOf: (Long) -> String = { millis ->
                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
                    }
                    viewModel.importBackup(
                        fallbackCarId = fallbackCarId,
                        car = importedCar,
                        fuelUps = fuelRows,
                        expenses = expenseRows,
                        dayOf = dayOf
                    ) { result ->
                        val message = when {
                            result == null ->
                                "Select a car first, or import a file that includes car details"
                            else -> buildString {
                                val name = result.carName ?: fallbackCarName ?: "car"
                                append(if (result.carCreated) "Created $name. " else "$name: ")
                                append("added ${result.fuelAdded} fill-ups, ${result.expensesAdded} expenses")
                                val skipped = result.fuelSkipped + result.expensesSkipped
                                if (skipped > 0) append(" - skipped $skipped already present")
                            }
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) { launch(Dispatchers.Main) { Toast.makeText(context, "Import failed", Toast.LENGTH_LONG).show() } }
            }
        }
    }

    if (showMainDatePicker) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = entryDateMillis)
        DatePickerDialog(onDismissRequest = { showMainDatePicker = false }, confirmButton = { TextButton(onClick = { dpState.selectedDateMillis?.let { entryDateMillis = it }; showMainDatePicker = false }) { Text("OK") } }, dismissButton = { TextButton(onClick = { showMainDatePicker = false }) { Text("Cancel") } }) { DatePicker(state = dpState) }
    }

    if (showExpDatePicker) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = expDateMillis)
        DatePickerDialog(onDismissRequest = { showExpDatePicker = false }, confirmButton = { TextButton(onClick = { dpState.selectedDateMillis?.let { expDateMillis = it }; showExpDatePicker = false }) { Text("OK") } }, dismissButton = { TextButton(onClick = { showExpDatePicker = false }) { Text("Cancel") } }) { DatePicker(state = dpState) }
    }

    if (showAddCarDialog || editingCar != null) {
        val isEditMode = editingCar != null
        var expanded1 by remember { mutableStateOf(false) }
        val fuelTypes = listOf("Petrol", "Diesel", "Electric", "Gas", "E85")
        var expanded2 by remember { mutableStateOf(false) }
        var showCustomColorSlider by remember { mutableStateOf(false) }
        var customHue by remember { mutableFloatStateOf(0f) }

        val themePalette = listOf(0xFF1976D2, 0xFFD32F2F, 0xFF388E3C, 0xFFFBC02D, 0xFF8E24AA, 0xFF424242)
        val rainbowBrush = Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red))

        AlertDialog(
            onDismissRequest = { showAddCarDialog = false; editingCar = null },
            title = { Text(if (isEditMode) "Edit Car Details" else "Add New Car") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (newCarImageUri == null) "Upload Car Photo" else "Change Photo")
                    }

                    Text("Select App Theme Color", style = MaterialTheme.typography.labelMedium)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        if (newCarDetectedColor != null) {
                            Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(Color(newCarDetectedColor!!)).border(width = if (newCarThemeColor == newCarDetectedColor) 3.dp else 0.dp, color = if (newCarThemeColor == newCarDetectedColor) Color.Black else Color.Transparent, shape = CircleShape).clickable { newCarThemeColor = newCarDetectedColor!!; showCustomColorSlider = false })
                        }
                        themePalette.forEach { colorHex ->
                            Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(Color(colorHex)).border(width = if (newCarThemeColor == colorHex) 3.dp else 0.dp, color = if (newCarThemeColor == colorHex) Color.Black else Color.Transparent, shape = CircleShape).clickable { newCarThemeColor = colorHex; showCustomColorSlider = false })
                        }
                        Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(rainbowBrush).clickable { showCustomColorSlider = !showCustomColorSlider })
                    }

                    if (showCustomColorSlider) {
                        Column {
                            Slider(value = customHue, onValueChange = { customHue = it; newCarThemeColor = Color.hsv(it, 1f, 1f).toArgb().toLong() and 0xFFFFFFFFL }, valueRange = 0f..360f)
                            Box(modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red))))
                        }
                    }

                    OutlinedTextField(value = carName, onValueChange = { carName = it; nameError = null }, label = { Text("Car Name") }, modifier = Modifier.fillMaxWidth(), isError = nameError != null, supportingText = { if (nameError != null) Text(nameError!!) }, singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
                    ExposedDropdownMenuBox(expanded = expanded1, onExpandedChange = { expanded1 = !expanded1 }) {
                        OutlinedTextField(value = selectedFuel1, onValueChange = {}, readOnly = true, label = { Text("Primary Fuel") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded1) }, colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(), modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = expanded1, onDismissRequest = { expanded1 = false }) { fuelTypes.forEach { ft -> DropdownMenuItem(text = { Text(ft) }, onClick = { selectedFuel1 = ft; expanded1 = false }) } }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = isBifuel, onCheckedChange = { isBifuel = it }); Text("Bifuel / Hybrid vehicle") }
                    if (isBifuel) {
                        ExposedDropdownMenuBox(expanded = expanded2, onExpandedChange = { expanded2 = !expanded2 }) {
                            OutlinedTextField(value = selectedFuel2, onValueChange = {}, readOnly = true, label = { Text("Secondary Fuel") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded2) }, colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(), modifier = Modifier.menuAnchor().fillMaxWidth())
                            ExposedDropdownMenu(expanded = expanded2, onDismissRequest = { expanded2 = false }) { fuelTypes.forEach { ft -> DropdownMenuItem(text = { Text(ft) }, onClick = { selectedFuel2 = ft; expanded2 = false }) } }
                        }
                    }
                    OutlinedTextField(value = initialOdo, onValueChange = { initialOdo = it; odoError = null }, label = { Text("Current Odometer (km)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), isError = odoError != null, supportingText = { if (odoError != null) Text(odoError!!) })

                    if (isEditMode) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                viewModel.deleteCar(editingCar!!)
                                if (selectedCar?.id == editingCar!!.id) selectedCar = null
                                showAddCarDialog = false
                                editingCar = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                        ) {
                            Text("Delete Car")
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val odo = initialOdo.toIntOrNull()
                    var valid = true
                    if (carName.isBlank()) { nameError = "Car name is required"; valid = false }
                    if (odo == null) { odoError = "Valid odometer required"; valid = false }

                    if (valid && odo != null) {
                        if (isEditMode) {
                            val updatedCar = editingCar!!.copy(name = carName, fuelType = selectedFuel1, secondaryFuelType = if (isBifuel) selectedFuel2 else null, initialOdometer = odo, imageUri = newCarImageUri, themeColor = newCarThemeColor)
                            viewModel.updateCar(updatedCar)
                            if (selectedCar?.id == updatedCar.id) { selectedCar = updatedCar }
                        } else {
                            viewModel.saveCar(carName, selectedFuel1, if (isBifuel) selectedFuel2 else null, odo, newCarImageUri, newCarThemeColor)
                        }
                        showAddCarDialog = false; editingCar = null
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddCarDialog = false; editingCar = null }) { Text("Cancel") } }
        )
    }

    if (editingFuelUp != null) {
        var editOdometer by remember { mutableStateOf(if (editingFuelUp!!.odometerKm == 0) "" else editingFuelUp!!.odometerKm.toString()) }
        var editLiters by remember { mutableStateOf(editingFuelUp!!.litersFilled.toString().replace('.', ',')) }
        var editPrice by remember { mutableStateOf(editingFuelUp!!.pricePerLiterSek.toString().replace('.', ',')) }
        var editMissed by remember { mutableStateOf(editingFuelUp!!.missedPrevious) }
        var editExpandedFuel by remember { mutableStateOf(false) }
        var editFuelType by remember { mutableStateOf(editingFuelUp!!.fuelTypeUsed) }
        var editDateMillis by remember { mutableLongStateOf(editingFuelUp!!.dateMillis) }
        var showEditDatePicker by remember { mutableStateOf(false) }

        if (showEditDatePicker) {
            val dpState = rememberDatePickerState(initialSelectedDateMillis = editDateMillis)
            DatePickerDialog(onDismissRequest = { showEditDatePicker = false }, confirmButton = { TextButton(onClick = { dpState.selectedDateMillis?.let { editDateMillis = it }; showEditDatePicker = false }) { Text("OK") } }) { DatePicker(state = dpState) }
        }

        AlertDialog(
            onDismissRequest = { editingFuelUp = null },
            title = { Text("Edit Fill-up") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExposedDropdownMenuBox(expanded = editExpandedFuel, onExpandedChange = { editExpandedFuel = !editExpandedFuel }, modifier = Modifier.weight(1f)) {
                            OutlinedTextField(value = editFuelType, onValueChange = {}, readOnly = true, label = { Text("Fuel") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(editExpandedFuel) }, colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(), modifier = Modifier.menuAnchor().fillMaxWidth())
                            ExposedDropdownMenu(expanded = editExpandedFuel, onDismissRequest = { editExpandedFuel = false }) { availableFuels.forEach { ft -> DropdownMenuItem(text = { Text(ft) }, onClick = { editFuelType = ft; editExpandedFuel = false }) } }
                        }
                        OutlinedTextField(value = editLiters, onValueChange = { editLiters = it }, label = { Text(if (editFuelType == "Electric") "kWh" else "Liters") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = editOdometer, onValueChange = { editOdometer = it }, label = { Text("Odo (km)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        OutlinedTextField(value = editPrice, onValueChange = { editPrice = it }, label = { Text("Price (SEK)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(value = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(editDateMillis)), onValueChange = {}, readOnly = true, label = { Text("Date") }, modifier = Modifier.weight(1f), trailingIcon = { IconButton(onClick = { showEditDatePicker = true }) { Icon(Icons.Default.DateRange, null) } })
                        if (fuelHistory.lastOrNull()?.id != editingFuelUp?.id) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Checkbox(checked = editMissed, onCheckedChange = { editMissed = it })
                                Text("Missed previous fill-up", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        } else Spacer(modifier = Modifier.weight(1f))
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val l = editLiters.replace(',', '.').toDoubleOrNull() ?: 0.0
                    val p = editPrice.replace(',', '.').toDoubleOrNull() ?: 0.0
                    val o = editOdometer.toIntOrNull() ?: 0
                    if (l > 0) { viewModel.updateFuelEntry(editingFuelUp!!.copy(dateMillis = editDateMillis, odometerKm = o, litersFilled = l, pricePerLiterSek = p, totalCostSek = l * p, missedPrevious = editMissed, fuelTypeUsed = editFuelType)); editingFuelUp = null }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val idx = fuelHistory.indexOfFirst { it.id == editingFuelUp!!.id }
                    if (idx > 0) viewModel.updateFuelEntry(fuelHistory[idx - 1].copy(missedPrevious = true))
                    viewModel.deleteFuelEntry(editingFuelUp!!)
                    editingFuelUp = null
                }) { Text("Delete", color = Color.Red) }
            }
        )
    }

    val activePrimaryColor = selectedCar?.themeColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val isLightColor = activePrimaryColor.luminance() > 0.5f

    val dynamicThemeColors = MaterialTheme.colorScheme.copy(
        primary = activePrimaryColor,
        onPrimary = if (isLightColor) Color.Black else Color.White,
        primaryContainer = activePrimaryColor.copy(alpha = 0.2f),
        onPrimaryContainer = if (isLightColor) Color(0xFF1A1A1A) else activePrimaryColor,
        secondaryContainer = activePrimaryColor.copy(alpha = 0.1f)
    )

    MaterialTheme(colorScheme = dynamicThemeColors) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                    Text("Your Garage", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.headlineMedium)
                    HorizontalDivider()

                    cars.forEach { car ->
                        val fuelDisplay = if (car.secondaryFuelType != null) "${car.fuelType} / ${car.secondaryFuelType}" else car.fuelType
                        val isSelected = car.id == selectedCar?.id
                        val containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                        val contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

                        Surface(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .fillMaxWidth()
                                .clip(CircleShape)
                                .clickable { selectedCar = car; scope.launch { drawerState.close() } },
                            color = containerColor,
                            contentColor = contentColor
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 0.dp, end = 16.dp, top = 0.dp, bottom = 0.dp)
                            ) {
                                val bmp = remember(car.imageUri) {
                                    try { car.imageUri?.let { uriStr -> loadAndRotateBitmap(context, Uri.parse(uriStr))?.asImageBitmap() } } catch (e: Exception) { null }
                                }
                                if (bmp != null) {
                                    Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(CircleShape))
                                    Spacer(modifier = Modifier.width(16.dp))
                                }
                                else {
                                    Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(car.themeColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary))
                                    Spacer(modifier = Modifier.width(16.dp))
                                }

                                Column(verticalArrangement = Arrangement.Center) {
                                    Text(car.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(fuelDisplay, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            carName = ""; initialOdo = ""; nameError = null; odoError = null; selectedFuel1 = "Petrol"; isBifuel = false; selectedFuel2 = "Electric"
                            newCarImageUri = null; newCarDetectedColor = null; newCarThemeColor = 0xFF1976D2
                            showAddCarDialog = true
                        },
                        modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth()
                    ) { Text("Add New Car") }

                    if (selectedCar != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider()
                        Text("Views", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        NavigationDrawerItem(label = { Text("Log & History") }, selected = currentTab == "Entries", onClick = { currentTab = "Entries"; scope.launch { drawerState.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
                        NavigationDrawerItem(label = { Text("Service & Expenses") }, selected = currentTab == "Expenses", onClick = { currentTab = "Expenses"; scope.launch { drawerState.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
                        NavigationDrawerItem(label = { Text("Charts & Graphs") }, selected = currentTab == "Charts", onClick = { currentTab = "Charts"; scope.launch { drawerState.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
                    }

                    Spacer(modifier = Modifier.weight(1f))
                    HorizontalDivider()
                    Text("Data Management", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    // Offered with no car selected too: a backup carries its
                    // own car details, so restoring onto a fresh install no
                    // longer means recreating the car by hand first.
                    NavigationDrawerItem(
                        label = { Text(if (selectedCar != null) "Import from file" else "Import a car from file") },
                        selected = false,
                        onClick = { importLauncher.launch(arrayOf("*/*")); scope.launch { drawerState.close() } },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    if (selectedCar != null && (fuelHistory.isNotEmpty() || expenseHistory.isNotEmpty())) {
                        NavigationDrawerItem(label = { Text("Export ${selectedCar!!.name} to CSV") }, selected = false, onClick = { val safeName = selectedCar!!.name.replace(" ", "_"); exportLauncher.launch("${safeName}_History.csv"); scope.launch { drawerState.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(selectedCar?.name ?: "Car Tracker") },
                        navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, null) } },
                        actions = {
                            if (selectedCar != null) {
                                IconButton(onClick = {
                                    carName = selectedCar!!.name
                                    initialOdo = selectedCar!!.initialOdometer.toString()
                                    selectedFuel1 = selectedCar!!.fuelType
                                    isBifuel = selectedCar!!.secondaryFuelType != null
                                    selectedFuel2 = selectedCar!!.secondaryFuelType ?: "Electric"
                                    newCarImageUri = selectedCar!!.imageUri
                                    newCarThemeColor = selectedCar!!.themeColor ?: 0xFF1976D2
                                    newCarDetectedColor = null
                                    nameError = null
                                    odoError = null
                                    editingCar = selectedCar
                                }) { Icon(Icons.Default.Edit, "Edit Car") }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer, titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                    )
                },
                floatingActionButton = { if (selectedCar == null) FloatingActionButton(onClick = { showAddCarDialog = true }) { Icon(Icons.Default.Add, null) } }
            ) { paddingValues ->
                if (selectedCar == null) {
                    Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.TopCenter) { Text("Press the + to add your car.", modifier = Modifier.padding(top = 150.dp)) }
                } else {
                    when (currentTab) {
                        "Charts" -> {
                            val chartDataPoints = remember(fuelHistory, selectedCar) {
                                val car = selectedCar ?: return@remember emptyMap()

                                val chronological = fuelHistory.sortedBy { it.dateMillis }
                                val pPrices = chronological.filter { it.fuelTypeUsed == car.fuelType }.map { it.pricePerLiterSek }
                                val sPrices = chronological.filter { it.fuelTypeUsed == car.secondaryFuelType }.map { it.pricePerLiterSek }

                                val pCons = mutableListOf<Double>()
                                val sCons = mutableListOf<Double>()

                                var lastPrimaryOdo: Int? = null
                                var lastSecondaryOdo: Int? = null

                                chronological.forEach { fuelUp ->
                                    if (fuelUp.odometerKm > 0) {
                                        if (fuelUp.fuelTypeUsed == car.fuelType) {
                                            if (lastPrimaryOdo != null) {
                                                val dist = fuelUp.odometerKm - lastPrimaryOdo!!
                                                if (dist > 0 && !fuelUp.missedPrevious) pCons.add((fuelUp.litersFilled / dist) * 100)
                                            }
                                            lastPrimaryOdo = fuelUp.odometerKm
                                        } else if (fuelUp.fuelTypeUsed == car.secondaryFuelType) {
                                            if (lastSecondaryOdo != null) {
                                                val dist = fuelUp.odometerKm - lastSecondaryOdo!!
                                                if (dist > 0 && !fuelUp.missedPrevious) sCons.add((fuelUp.litersFilled / dist) * 100)
                                            }
                                            lastSecondaryOdo = fuelUp.odometerKm
                                        }
                                    }
                                }
                                mapOf("pPrices" to pPrices, "sPrices" to sPrices, "pCons" to pCons, "sCons" to sCons)
                            }

                            Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                                TabRow(selectedTabIndex = if (chartSubTab == "Price") 0 else 1) {
                                    Tab(selected = chartSubTab == "Price", onClick = { chartSubTab = "Price" }, text = { Text("Fuel Price") })
                                    Tab(selected = chartSubTab == "Consumption", onClick = { chartSubTab = "Consumption" }, text = { Text("Consumption") })
                                }
                                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                    if (chartSubTab == "Price") {
                                        val pPrices = chartDataPoints["pPrices"] ?: emptyList()
                                        val sPrices = chartDataPoints["sPrices"] ?: emptyList()
                                        if (pPrices.size >= 2) { item { val unit = if (selectedCar!!.fuelType == "Electric") "kWh" else "L"; NativeLineChart(data = pPrices, title = "${selectedCar!!.fuelType} Price (SEK/$unit)", lineColor = activePrimaryColor) } }
                                        else { item { Text("Add at least 2 ${selectedCar!!.fuelType} entries to generate a chart.", color = Color.Gray) } }
                                        if (selectedCar!!.secondaryFuelType != null) { if (sPrices.size >= 2) { item { val unit = if (selectedCar!!.secondaryFuelType == "Electric") "kWh" else "L"; NativeLineChart(data = sPrices, title = "${selectedCar!!.secondaryFuelType} Price (SEK/$unit)", lineColor = Color(0xFF1976D2)) } } }
                                    } else {
                                        val pCons = chartDataPoints["pCons"] ?: emptyList()
                                        val sCons = chartDataPoints["sCons"] ?: emptyList()
                                        if (pCons.size >= 2) { item { val unit = if (selectedCar!!.fuelType == "Electric") "kWh" else "L"; NativeLineChart(data = pCons, title = "${selectedCar!!.fuelType} Consumption ($unit/100km)", lineColor = activePrimaryColor) } }
                                        else { item { Text("Add at least 2 consecutive ${selectedCar!!.fuelType} entries to generate a chart.", color = Color.Gray) } }
                                        if (selectedCar!!.secondaryFuelType != null) { if (sCons.size >= 2) { item { val unit = if (selectedCar!!.secondaryFuelType == "Electric") "kWh" else "L"; NativeLineChart(data = sCons, title = "${selectedCar!!.secondaryFuelType} Consumption ($unit/100km)", lineColor = Color(0xFFFBC02D)) } } }
                                    }
                                }
                            }
                        }

                        "Expenses" -> {
                            val totalExp = remember(expenseHistory) { expenseHistory.sumOf { it.costSek } }
                            Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(value = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(expDateMillis)), onValueChange = {}, readOnly = true, label = { Text("Date") }, modifier = Modifier.weight(1f), trailingIcon = { IconButton(onClick = { showExpDatePicker = true }) { Icon(Icons.Default.DateRange, null) } })
                                    ExposedDropdownMenuBox(expanded = expCategoryExpanded, onExpandedChange = { expCategoryExpanded = !expCategoryExpanded }, modifier = Modifier.weight(1f)) {
                                        OutlinedTextField(value = expCategory, onValueChange = {}, readOnly = true, label = { Text("Category") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expCategoryExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                                        ExposedDropdownMenu(expanded = expCategoryExpanded, onDismissRequest = { expCategoryExpanded = false }) { expCategories.forEach { cat -> DropdownMenuItem(text = { Text(cat) }, onClick = { expCategory = cat; expCategoryExpanded = false }) } }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(value = expDesc, onValueChange = { expDesc = it }, label = { Text("Description (Optional)") }, modifier = Modifier.weight(1.5f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, capitalization = KeyboardCapitalization.Sentences))
                                    OutlinedTextField(value = expCost, onValueChange = { expCost = it }, label = { Text("Cost (SEK)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Checkbox(checked = expIsMonthly, onCheckedChange = { expIsMonthly = it })
                                    Text("Repeats automatically every month", style = MaterialTheme.typography.bodyMedium)
                                }
                                Button(onClick = {
                                    val cost = expCost.replace(',', '.').toDoubleOrNull() ?: 0.0
                                    if (cost > 0) { viewModel.saveExpense(selectedCar!!.id, expDateMillis, expCategory, expDesc, cost, expIsMonthly); expDesc = ""; expCost = ""; expIsMonthly = false; expDateMillis = System.currentTimeMillis() }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Save Expense") }

                                HorizontalDivider()

                                if (expenseHistory.isNotEmpty()) {
                                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
                                        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("Total Non-Fuel Expenses", style = MaterialTheme.typography.labelMedium)
                                            Text("%.2f SEK".format(svLocale, totalExp), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                        }
                                    }
                                }

                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                                    itemsIndexed(items = expenseHistory, key = { _, item -> item.id }) { _, expense ->
                                        Card(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { viewModel.deleteExpense(expense) }), elevation = CardDefaults.cardElevation(2.dp)) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text(SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(expense.dateMillis)), style = MaterialTheme.typography.labelMedium)
                                                    Text(if (expense.isMonthly) "🔄 ${expense.category}" else expense.category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                    Text(expense.description.ifEmpty { "No description" }, style = MaterialTheme.typography.bodyMedium, color = if(expense.description.isEmpty()) Color.Gray else Color.Unspecified)
                                                    Text("${"%.2f".format(svLocale, expense.costSek)} SEK", style = MaterialTheme.typography.titleMedium)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        "Entries" -> {
                            // HÄR BÖRJAR DEN NYA PERFEKTA BERÄKNINGEN FÖR LADDHYBRIDER
                            val dashboardStats = remember(fuelHistory, selectedCar) {
                                val car = selectedCar ?: return@remember null
                                val pType = car.fuelType
                                val sType = car.secondaryFuelType

                                var totalP_Liters = 0.0
                                var totalP_Cost = 0.0
                                var totalP_Dist = 0

                                var totalS_Liters = 0.0
                                var totalS_Cost = 0.0
                                var totalS_Dist = 0

                                val chronological = fuelHistory.sortedBy { it.dateMillis }

                                var lastPOdo = car.initialOdometer
                                var lastSOdo = car.initialOdometer

                                chronological.forEach { fuelUp ->
                                    if (fuelUp.odometerKm > 0) {
                                        if (fuelUp.fuelTypeUsed == pType) {
                                            val dist = fuelUp.odometerKm - lastPOdo
                                            if (dist > 0 && !fuelUp.missedPrevious) {
                                                totalP_Dist += dist
                                                totalP_Liters += fuelUp.litersFilled
                                                totalP_Cost += fuelUp.totalCostSek
                                            }
                                            lastPOdo = fuelUp.odometerKm
                                        } else if (fuelUp.fuelTypeUsed == sType) {
                                            val dist = fuelUp.odometerKm - lastSOdo
                                            if (dist > 0 && !fuelUp.missedPrevious) {
                                                totalS_Dist += dist
                                                totalS_Liters += fuelUp.litersFilled
                                                totalS_Cost += fuelUp.totalCostSek
                                            }
                                            lastSOdo = fuelUp.odometerKm
                                        }
                                    }
                                }

                                val avgPrimary = if (totalP_Dist > 0) (totalP_Liters / totalP_Dist) * 100 else 0.0
                                val costPrimary = if (totalP_Dist > 0) (totalP_Cost / totalP_Dist) * 10 else 0.0

                                val avgSecondary = if (totalS_Dist > 0) (totalS_Liters / totalS_Dist) * 100 else 0.0
                                val costSecondary = if (totalS_Dist > 0) (totalS_Cost / totalS_Dist) * 10 else 0.0

                                val blendedCost = costPrimary + costSecondary

                                mapOf(
                                    "avgPrimary" to avgPrimary,
                                    "avgSecondary" to avgSecondary,
                                    "costPrimary" to costPrimary,
                                    "costSecondary" to costSecondary,
                                    "blended" to blendedCost
                                )
                            }
                            // HÄR SLUTAR DEN NYA BERÄKNINGEN

                            Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ExposedDropdownMenuBox(expanded = expandedEntryFuel, onExpandedChange = { expandedEntryFuel = !expandedEntryFuel }, modifier = Modifier.weight(1f)) {
                                        OutlinedTextField(value = entryFuelType, onValueChange = {}, readOnly = true, label = { Text("Fuel") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedEntryFuel) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                                        ExposedDropdownMenu(expanded = expandedEntryFuel, onDismissRequest = { expandedEntryFuel = false }) {
                                            availableFuels.forEach { ft ->
                                                DropdownMenuItem(
                                                    text = { Text(ft) },
                                                    onClick = {
                                                        entryFuelType = ft
                                                        selectedCar?.let { prefs.edit().putString("last_fuel_${it.id}", ft).apply() }
                                                        expandedEntryFuel = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    OutlinedTextField(value = liters, onValueChange = { liters = it }, label = { Text(if (entryFuelType == "Electric") "kWh" else "Liters") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(value = distanceInput, onValueChange = { distanceInput = it }, label = { Text(if (inputMode == 0) "Odo (km)" else "Trip (km)") }, placeholder = { Text("Optional") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), trailingIcon = { IconButton(onClick = { inputMode = if (inputMode == 0) 1 else 0 }) { Icon(Icons.Default.SwapVert, null) } })
                                    OutlinedTextField(value = price, onValueChange = { price = it }, label = { Text("Price per unit (SEK)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(value = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(entryDateMillis)), onValueChange = {}, readOnly = true, label = { Text("Date") }, modifier = Modifier.weight(1f), trailingIcon = { IconButton(onClick = { showMainDatePicker = true }) { Icon(Icons.Default.DateRange, null) } })
                                    if (fuelHistory.isNotEmpty()) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            Checkbox(checked = missedPrevious, onCheckedChange = { missedPrevious = it })
                                            Text("Missed previous", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    } else Spacer(modifier = Modifier.weight(1f))
                                }

                                Button(onClick = {
                                    val l = liters.replace(',', '.').toDoubleOrNull() ?: 0.0
                                    val p = price.replace(',', '.').toDoubleOrNull() ?: 0.0
                                    val rawDist = distanceInput.replace(',', '.').toDoubleOrNull()
                                    val finalOdo = if (rawDist == null) 0 else if (inputMode == 1) {
                                        val lastKnownOdo = fuelHistory.firstOrNull { it.odometerKm > 0 && it.fuelTypeUsed == entryFuelType }?.odometerKm ?: selectedCar!!.initialOdometer
                                        lastKnownOdo + rawDist.toInt()
                                    } else rawDist.toInt()

                                    if (l > 0) {
                                        viewModel.saveFuelEntry(selectedCar!!.id, entryFuelType, entryDateMillis, finalOdo, l, p, l * p, missedPrevious)
                                        prefs.edit().putString("last_fuel_${selectedCar!!.id}", entryFuelType).apply()
                                        distanceInput = ""; liters = ""; price = ""; missedPrevious = false; entryDateMillis = System.currentTimeMillis()
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Save Entry") }

                                HorizontalDivider()

                                if (dashboardStats != null) {
                                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text("${selectedCar!!.fuelType} Avg", style = MaterialTheme.typography.labelMedium)
                                                    val pUnit = if (selectedCar!!.fuelType == "Electric") "kWh" else "L"
                                                    Text("%.2f $pUnit/100km".format(svLocale, dashboardStats["avgPrimary"]), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                                    Text("%.2f kr/mil".format(svLocale, dashboardStats["costPrimary"]), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                                                }
                                                if (selectedCar!!.secondaryFuelType != null) {
                                                    VerticalDivider(modifier = Modifier.height(50.dp))
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        Text("${selectedCar!!.secondaryFuelType} Avg", style = MaterialTheme.typography.labelMedium)
                                                        val sUnit = if (selectedCar!!.secondaryFuelType == "Electric") "kWh" else "L"
                                                        Text("%.2f $sUnit/100km".format(svLocale, dashboardStats["avgSecondary"]), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                                        Text("%.2f kr/mil".format(svLocale, dashboardStats["costSecondary"]), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                                                    }
                                                }
                                            }
                                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                                val costLabel = if (selectedCar!!.secondaryFuelType != null) "True Blended Cost: " else "Total Cost: "
                                                Text(costLabel, style = MaterialTheme.typography.bodyMedium)
                                                Text("%.2f kr/mil".format(svLocale, dashboardStats["blended"]), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
                                            }
                                        }
                                    }
                                }

                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                                    itemsIndexed(items = fuelHistory, key = { _, item -> item.id }) { index, fuelUp ->
                                        var currentCons: Double? = null
                                        val olderEntries = fuelHistory.subList(index + 1, fuelHistory.size)
                                        val prevOdo = olderEntries.firstOrNull { it.odometerKm > 0 && it.fuelTypeUsed == fuelUp.fuelTypeUsed }?.odometerKm ?: selectedCar!!.initialOdometer

                                        if (fuelUp.odometerKm > 0 && prevOdo > 0) {
                                            val dist = fuelUp.odometerKm - prevOdo
                                            if (dist > 0 && !fuelUp.missedPrevious) currentCons = (fuelUp.litersFilled / dist) * 100
                                        }

                                        Card(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { editingFuelUp = fuelUp }), elevation = CardDefaults.cardElevation(2.dp)) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text(SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(fuelUp.dateMillis)), style = MaterialTheme.typography.labelMedium)
                                                    Column(horizontalAlignment = Alignment.End) {
                                                        val unit = if (fuelUp.fuelTypeUsed == "Electric") "kWh" else "L"
                                                        val consText = when {
                                                            fuelUp.missedPrevious -> "Missed Previous"
                                                            fuelUp.odometerKm == 0 -> "No Odo Data"
                                                            currentCons != null -> "%.2f $unit/100km".format(svLocale, currentCons)
                                                            else -> "First Entry"
                                                        }
                                                        val consColor = if (fuelUp.missedPrevious || fuelUp.odometerKm == 0) Color.Gray else MaterialTheme.colorScheme.primary
                                                        Text(consText, color = consColor, style = MaterialTheme.typography.labelLarge)
                                                        Text(if (fuelUp.odometerKm == 0) "Odometer: Data missing" else "${fuelUp.odometerKm} km", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                                val formattedCost = "%.2f".format(svLocale, fuelUp.totalCostSek)
                                                val formattedLiters = "%.2f".format(svLocale, fuelUp.litersFilled)
                                                val formattedPrice = "%.2f".format(svLocale, fuelUp.pricePerLiterSek)
                                                val unitShort = if (fuelUp.fuelTypeUsed == "Electric") "kWh" else "L"

                                                Text("$formattedCost SEK", style = MaterialTheme.typography.titleMedium)
                                                Text("$formattedLiters $unitShort at $formattedPrice kr/$unitShort", style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}