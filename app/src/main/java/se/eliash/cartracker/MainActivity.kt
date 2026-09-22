package se.eliash.cartracker

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { FuelEntryScreen() } }
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

    // Owned here, not by the dialog: the drawer's "Add New Car" clears it and
    // the top bar's edit button fills it from the selected car.
    val carForm = rememberCarFormState()

    // Rescues photos picked before they were copied into app storage.
    LaunchedEffect(Unit) { viewModel.adoptLegacyPhotos() }
    LaunchedEffect(selectedCar) { selectedCar?.let { viewModel.checkRecurringExpenses(it.id) } }
    LaunchedEffect(cars) { if (selectedCar == null && cars.isNotEmpty()) selectedCar = cars.first() }

    var editingFuelUp by remember { mutableStateOf<FuelUp?>(null) }
    var editingExpense by remember { mutableStateOf<Expense?>(null) }

    // Owned here, like the expense form, so a half-filled entry survives
    // leaving the tab.
    val entryForm = rememberFuelEntryFormState()

    // Held by the screen rather than by ExpensesTab, so a half-filled form
    // survives leaving the tab and coming back, as it did before.
    val expenseForm = rememberExpenseFormState()


    val availableFuels = remember(selectedCar) { listOfNotNull(selectedCar?.fuelType, selectedCar?.secondaryFuelType).ifEmpty { listOf("Petrol") } }
    var entryFuelType by remember(selectedCar) {
        val savedFuel = selectedCar?.let { prefs.getString("last_fuel_${it.id}", null) }
        mutableStateOf(if (savedFuel != null && availableFuels.contains(savedFuel)) savedFuel else (availableFuels.firstOrNull() ?: "Petrol"))
    }

    val fuelHistory by remember(selectedCar) { if (selectedCar != null) viewModel.getFuelUpsForCar(selectedCar!!.id) else kotlinx.coroutines.flow.emptyFlow() }.collectAsState(initial = emptyList())
    val expenseHistory by remember(selectedCar) { if (selectedCar != null) viewModel.getExpensesForCar(selectedCar!!.id) else kotlinx.coroutines.flow.emptyFlow() }.collectAsState(initial = emptyList())

    val svLocale = Locale.forLanguageTag("sv-SE")

    val formatBackupDate: (Long) -> String = { millis ->
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(it)
                        ?.bufferedWriter(Charsets.UTF_8)
                        ?.use { writer ->
                            writeBackupCsv(
                                writer, selectedCar, fuelHistory, expenseHistory,
                                photoName = null, formatDate = formatBackupDate
                            )
                        }
                    val summary = "Exported ${fuelHistory.size} fill-ups and ${expenseHistory.size} expenses"
                    launch(Dispatchers.Main) { Toast.makeText(context, summary, Toast.LENGTH_LONG).show() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    // A second export, because the two serve different ends: the CSV is what
    // opens in a spreadsheet, the zip is what survives losing the phone.
    val exportZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(it)?.use { output ->
                        writeBackupZip(
                            output, context, selectedCar, fuelHistory, expenseHistory,
                            formatDate = formatBackupDate
                        )
                    }
                    val withPhoto = selectedCar?.imageUri?.let { photo ->
                        !isExternalPhotoReference(photo)
                    } ?: false
                    val summary = "Backed up ${fuelHistory.size} fill-ups, " +
                        "${expenseHistory.size} expenses" +
                        if (withPhoto) " and the photo" else ""
                    launch(Dispatchers.Main) { Toast.makeText(context, summary, Toast.LENGTH_LONG).show() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { Toast.makeText(context, "Backup failed", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val fallbackCarId = selectedCar?.id
            val fallbackCarName = selectedCar?.name
            scope.launch(Dispatchers.IO) {
                try {
                    // Accepts a zip backup or a bare CSV: every file the app
                    // has written still imports.
                    val backup = readBackup(context) { context.contentResolver.openInputStream(it) }
                    if (backup == null) {
                        launch(Dispatchers.Main) {
                            Toast.makeText(context, "Could not read that file", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }

                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val parsed = parseBackupCsv(backup.csv) { text ->
                        // parse throws on anything it does not recognise, and
                        // an unreadable row is skipped rather than aborting.
                        runCatching { dateFormat.parse(text)?.time }.getOrNull()
                    }

                    viewModel.importBackup(
                        fallbackCarId = fallbackCarId,
                        // The stored name comes from the archive read, not the
                        // car row: the photo is saved under a fresh name.
                        car = parsed.car?.copy(photo = backup.photoName),
                        fuelUps = parsed.fuelUps,
                        expenses = parsed.expenses,
                        dayOf = formatBackupDate
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
                                if (parsed.unreadableRows > 0) {
                                    append(" - ${parsed.unreadableRows} rows could not be read")
                                }
                            }
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) { launch(Dispatchers.Main) { Toast.makeText(context, "Import failed", Toast.LENGTH_LONG).show() } }
            }
        }
    }

    if (showAddCarDialog || editingCar != null) {
        AddEditCarDialog(
            form = carForm,
            isEditMode = editingCar != null,
            onSave = { name, primaryFuel, secondaryFuel, odometer, photo, themeColor ->
                val editing = editingCar
                if (editing != null) {
                    val updated = editing.copy(
                        name = name,
                        fuelType = primaryFuel,
                        secondaryFuelType = secondaryFuel,
                        initialOdometer = odometer,
                        imageUri = photo,
                        themeColor = themeColor
                    )
                    viewModel.updateCar(updated)
                    // The selected car holds a copy, so it needs the new values too.
                    if (selectedCar?.id == updated.id) selectedCar = updated
                } else {
                    viewModel.saveCar(name, primaryFuel, secondaryFuel, odometer, photo, themeColor)
                }
                showAddCarDialog = false
                editingCar = null
            },
            onDelete = {
                editingCar?.let { car ->
                    viewModel.deleteCar(car)
                    if (selectedCar?.id == car.id) selectedCar = null
                }
                showAddCarDialog = false
                editingCar = null
            },
            onDismiss = { showAddCarDialog = false; editingCar = null }
        )
    }

    editingFuelUp?.let { editing ->
        EditFuelUpDialog(
            fuelUp = editing,
            availableFuels = availableFuels,
            // The oldest fill-up has no previous one to have missed.
            canMarkMissed = fuelHistory.lastOrNull()?.id != editing.id,
            onSave = { updated ->
                viewModel.updateFuelEntry(updated)
                editingFuelUp = null
            },
            onDelete = {
                // The entry before this one now covers its distance too, so it
                // has to be marked as following a gap - otherwise that distance
                // is credited to a tankful that never covered it.
                val index = fuelHistory.indexOfFirst { it.id == editing.id }
                if (index > 0) {
                    viewModel.updateFuelEntry(fuelHistory[index - 1].copy(missedPrevious = true))
                }
                viewModel.deleteFuelEntry(editing)
                editingFuelUp = null
            },
            onDismiss = { editingFuelUp = null }
        )
    }

    editingExpense?.let { editing ->
        EditExpenseDialog(
            expense = editing,
            currencyLocale = svLocale,
            onSave = { updated ->
                viewModel.updateExpense(original = editing, updated = updated)
                editingExpense = null
            },
            onDelete = {
                viewModel.deleteExpense(editing)
                editingExpense = null
            },
            onDismiss = { editingExpense = null }
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
                GarageDrawer(
                    cars = cars,
                    selectedCar = selectedCar,
                    currentTab = currentTab,
                    hasDataToExport = fuelHistory.isNotEmpty() || expenseHistory.isNotEmpty(),
                    onSelectCar = { car ->
                        selectedCar = car
                        scope.launch { drawerState.close() }
                    },
                    // Deliberately leaves the drawer open, as before: the
                    // dialog opens over it.
                    onAddCar = {
                        carForm.reset()
                        showAddCarDialog = true
                    },
                    onSelectTab = { tab ->
                        currentTab = tab
                        scope.launch { drawerState.close() }
                    },
                    onImport = {
                        importLauncher.launch(arrayOf("*/*"))
                        scope.launch { drawerState.close() }
                    },
                    onExport = {
                        val safeName = selectedCar!!.name.replace(" ", "_")
                        exportLauncher.launch("${safeName}_History.csv")
                        scope.launch { drawerState.close() }
                    },
                    onExportBackup = {
                        val safeName = selectedCar!!.name.replace(" ", "_")
                        exportZipLauncher.launch("${safeName}_Backup.zip")
                        scope.launch { drawerState.close() }
                    }
                )
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
                                    carForm.loadFrom(selectedCar!!)
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
                        "Charts" -> ChartsTab(
                            car = selectedCar!!,
                            fuelHistory = fuelHistory,
                            expenses = expenseHistory,
                            subTab = chartSubTab,
                            onSubTabChange = { chartSubTab = it },
                            primaryColor = activePrimaryColor,
                            currencyLocale = svLocale,
                            modifier = Modifier.fillMaxSize().padding(paddingValues)
                        )

                        "Expenses" -> ExpensesTab(
                            expenses = expenseHistory,
                            form = expenseForm,
                            currencyLocale = svLocale,
                            onSave = { date, category, description, cost, isMonthly ->
                                viewModel.saveExpense(selectedCar!!.id, date, category, description, cost, isMonthly)
                            },
                            onEdit = { editingExpense = it },
                            modifier = Modifier.fillMaxSize().padding(paddingValues)
                        )

                        "Entries" -> EntriesTab(
                            car = selectedCar!!,
                            fuelHistory = fuelHistory,
                            form = entryForm,
                            fuelType = entryFuelType,
                            onFuelTypeChange = { fuel ->
                                entryFuelType = fuel
                                selectedCar?.let { prefs.edit().putString("last_fuel_${it.id}", fuel).apply() }
                            },
                            availableFuels = availableFuels,
                            currencyLocale = svLocale,
                            onSave = { fuel, dateMillis, odometer, amount, price, missed ->
                                viewModel.saveFuelEntry(
                                    selectedCar!!.id, fuel, dateMillis, odometer,
                                    amount, price, amount * price, missed
                                )
                                prefs.edit().putString("last_fuel_${selectedCar!!.id}", fuel).apply()
                            },
                            onEditEntry = { editingFuelUp = it },
                            modifier = Modifier.fillMaxSize().padding(paddingValues)
                        )
                    }
                }
            }
        }
    }
}