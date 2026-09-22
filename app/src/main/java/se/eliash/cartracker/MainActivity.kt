package se.eliash.cartracker

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import se.eliash.cartracker.ui.theme.CarTallyAmber
import se.eliash.cartracker.ui.theme.ThemeMode
import se.eliash.cartracker.ui.theme.resolveDarkTheme
import se.eliash.cartracker.ui.theme.CarTallyPetrol
import se.eliash.cartracker.ui.theme.carColorScheme
import se.eliash.cartracker.ui.theme.carTallyColorScheme

/** Preference holding the id of the car to open at start. Absent means the garage. */
private const val DEFAULT_CAR_KEY = "default_car_id"

/** Preference holding the light / dark / follow-the-phone choice. */
private const val THEME_KEY = "theme_mode"

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

    // Still "CarTrackerPrefs" after the rename to CarTally, deliberately: this is
    // the file the settings already live in, and a new name opens an empty one.
    val prefs = remember { context.getSharedPreferences("CarTrackerPrefs", Context.MODE_PRIVATE) }

    // Saveable, so turning the phone does not throw away where you were.
    var currentTab by rememberSaveable { mutableStateOf("Entries") }
    var chartSubTab by rememberSaveable { mutableStateOf("Price") }

    // Settings is a third place to be, beside the garage and a car. Saveable,
    // like the rest, so turning the phone does not close it.
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // Which car an export is for. Saveable: the system file picker can outlive
    // this screen, and its answer then arrives at a fresh one.
    var exportCarId by rememberSaveable { mutableStateOf<Int?>(null) }

    // Null until the database has answered. Telling "not loaded yet" apart
    // from "no cars" is what keeps the garage from flashing up for a frame
    // before a default car opens.
    val loadedCars by viewModel.allCars.collectAsState(initial = null)
    val cars = loadedCars ?: emptyList()
    val carSummaries by viewModel.carSummaries.collectAsState(initial = emptyList())
    val summariesByCar = remember(carSummaries) { carSummaries.associateBy { it.carId } }

    // The selection is an id, and the car is looked up from the live list.
    // An edited car is then picked up without being copied back by hand, and
    // the id survives rotation, where a Car held in remember did not - turning
    // the phone used to jump back to the first car.
    var selectedCarId by rememberSaveable { mutableStateOf<Int?>(null) }
    val selectedCar = cars.firstOrNull { it.id == selectedCarId }
    var showAddCarDialog by remember { mutableStateOf(false) }
    var editingCar by remember { mutableStateOf<Car?>(null) }

    // The car to open at start, if any. Kept in preferences, not the
    // database: it is a choice about this phone, not a fact about the car.
    var defaultCarId by remember {
        mutableStateOf(prefs.getInt(DEFAULT_CAR_KEY, -1).takeIf { it >= 0 })
    }
    fun setDefaultCar(id: Int?) {
        defaultCarId = id
        val editor = prefs.edit()
        if (id == null) editor.remove(DEFAULT_CAR_KEY) else editor.putInt(DEFAULT_CAR_KEY, id)
        editor.apply()
    }

    // Decided once, on the database's first answer. Saveable, so a rotation
    // does not re-open the default car over wherever you had got to.
    var launchHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(loadedCars) {
        val loaded = loadedCars ?: return@LaunchedEffect
        if (!launchHandled) {
            launchHandled = true
            selectedCarId = launchCar(loaded, defaultCarId)?.id
        }
    }

    // Owned here, not by the dialog: the drawer's "Add New Car" clears it and
    // the top bar's edit button fills it from the selected car.
    val carForm = rememberCarFormState()

    // Rescues photos picked before they were copied into app storage.
    LaunchedEffect(Unit) { viewModel.adoptLegacyPhotos() }
    LaunchedEffect(selectedCar?.id) { selectedCar?.let { viewModel.checkRecurringExpenses(it.id) } }

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

    // Keyed on the id, so editing the car's name does not start the history over.
    val fuelHistory by remember(selectedCarId) { selectedCarId?.let { viewModel.getFuelUpsForCar(it) } ?: kotlinx.coroutines.flow.emptyFlow() }.collectAsState(initial = emptyList())
    val expenseHistory by remember(selectedCarId) { selectedCarId?.let { viewModel.getExpensesForCar(it) } ?: kotlinx.coroutines.flow.emptyFlow() }.collectAsState(initial = emptyList())

    val svLocale = Locale.forLanguageTag("sv-SE")

    // For Settings > About. Read from the package rather than BuildConfig,
    // which this build does not generate.
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }

    val formatBackupDate: (Long) -> String = { millis ->
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
    }

    // Both exports read the chosen car's history from the database rather than
    // what is on screen, so Settings can export any car, not only the open one.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            val car = cars.firstOrNull { c -> c.id == exportCarId } ?: return@let
            scope.launch(Dispatchers.IO) {
                try {
                    val (fuelUps, expenses) = viewModel.historyForExport(car.id)
                    context.contentResolver.openOutputStream(it)
                        ?.bufferedWriter(Charsets.UTF_8)
                        ?.use { writer ->
                            writeBackupCsv(
                                writer, car, fuelUps, expenses,
                                photoName = null, formatDate = formatBackupDate
                            )
                        }
                    val summary = "Exported ${car.name}: ${fuelUps.size} fill-ups and ${expenses.size} expenses"
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
            val car = cars.firstOrNull { c -> c.id == exportCarId } ?: return@let
            scope.launch(Dispatchers.IO) {
                try {
                    val (fuelUps, expenses) = viewModel.historyForExport(car.id)
                    context.contentResolver.openOutputStream(it)?.use { output ->
                        writeBackupZip(output, context, car, fuelUps, expenses, formatDate = formatBackupDate)
                    }
                    val withPhoto = car.imageUri?.let { photo -> !isExternalPhotoReference(photo) } ?: false
                    val summary = "Backed up ${car.name}: ${fuelUps.size} fill-ups, " +
                        "${expenses.size} expenses" +
                        if (withPhoto) " and the photo" else ""
                    launch(Dispatchers.Main) { Toast.makeText(context, summary, Toast.LENGTH_LONG).show() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { Toast.makeText(context, "Backup failed", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }
    fun safeFileName(car: Car) = car.name.replace(" ", "_")

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

    // Light, dark, or following the phone. Saved, so it holds across launches.
    var themeMode by remember { mutableStateOf(ThemeMode.fromStored(prefs.getString(THEME_KEY, null))) }
    fun changeThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString(THEME_KEY, mode.name).apply()
    }
    val darkTheme = resolveDarkTheme(themeMode, isSystemInDarkTheme())
    val baseColors = if (darkTheme) darkColorScheme() else lightColorScheme()

    // A car wears its own colour; one without a colour chosen, CarTally petrol.
    val activePrimaryColor = selectedCar?.themeColor?.let { Color(it) } ?: CarTallyPetrol

    // The garage and Settings wear CarTally's colours outright - both are about
    // the app rather than one car. A car keeps its own accent.
    val dynamicThemeColors = if (selectedCar == null || showSettings) {
        carTallyColorScheme(baseColors, darkTheme)
    } else {
        carColorScheme(baseColors, activePrimaryColor, darkTheme)
    }

    // The top bar reaches up under the status bar - edge-to-edge is enforced
    // from Android 15 for this target SDK - so the clock and battery have to
    // suit it: light over a dark bar, dark over a pale one. The gesture bar
    // sits over the page, so it follows the page. Earlier versions keep their
    // own system bar colours, so are left alone.
    val view = LocalView.current
    val lightStatusIcons = dynamicThemeColors.primaryContainer.luminance() < 0.5f
    SideEffect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !lightStatusIcons
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    // Everything below wears these colours, the dialogs included. They used
    // to sit outside, in Material's default light theme - which is why their
    // buttons were purple - and at night they would have been glaring white.
    MaterialTheme(colorScheme = dynamicThemeColors) {
        if (showAddCarDialog || editingCar != null) {
            AddEditCarDialog(
                form = carForm,
                isEditMode = editingCar != null,
                // Only ever the selected car is edited, so its loaded history is
                // the history the confirmation is counting.
                fuelUpCount = if (editingCar != null) fuelHistory.size else 0,
                expenseCount = if (editingCar != null) expenseHistory.size else 0,
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
                    } else {
                        viewModel.saveCar(name, primaryFuel, secondaryFuel, odometer, photo, themeColor)
                    }
                    showAddCarDialog = false
                    editingCar = null
                },
                onDelete = {
                    editingCar?.let { car ->
                        viewModel.deleteCar(car)
                        if (selectedCarId == car.id) selectedCarId = null
                        if (defaultCarId == car.id) setDefaultCar(null)
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

        // Back from a car goes to the garage rather than out of the app. An open
        // drawer closes first, as it would anyway.
        BackHandler(enabled = drawerState.isOpen || showSettings || selectedCar != null) {
            when {
                drawerState.isOpen -> { scope.launch { drawerState.close() } }
                showSettings -> { showSettings = false }
                else -> { selectedCarId = null }
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            // No swipe-open from Settings, which has a back arrow instead of the
            // menu button: a drawer appearing from nowhere there would confuse.
            gesturesEnabled = !showSettings,
            drawerContent = {
                GarageDrawer(
                    cars = cars,
                    selectedCar = selectedCar,
                    currentTab = currentTab,
                    onSelectCar = { car ->
                        selectedCarId = car.id
                        showSettings = false
                        scope.launch { drawerState.close() }
                    },
                    onOpenGarage = {
                        selectedCarId = null
                        showSettings = false
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
                    onOpenSettings = {
                        showSettings = true
                        scope.launch { drawerState.close() }
                    }
                )
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (showSettings) "Settings" else selectedCar?.name ?: "CarTally") },
                        navigationIcon = {
                            if (showSettings) {
                                IconButton(onClick = { showSettings = false }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            } else {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, "Menu")
                                }
                            }
                        },
                        actions = {
                            if (selectedCar != null && !showSettings) {
                                IconButton(onClick = {
                                    carForm.loadFrom(selectedCar!!)
                                    editingCar = selectedCar
                                }) { Icon(Icons.Default.Edit, "Edit Car") }
                            }
                        },
                        // The icons follow the title. Left to default they stay dark, and
                        // the menu icon disappears into the garage's petrol.
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                },
                floatingActionButton = {
                    if (loadedCars != null && selectedCar == null && !showSettings) {
                        // The logo's drop and its gauge: amber, with the + in petrol.
                        FloatingActionButton(
                            onClick = { carForm.reset(); showAddCarDialog = true },
                            containerColor = CarTallyAmber,
                            contentColor = CarTallyPetrol
                        ) {
                            Icon(Icons.Default.Add, "Add a car")
                        }
                    }
                }
            ) { paddingValues ->
                if (loadedCars == null) {
                    // A frame or two while the database answers. Blank rather
                    // than the garage, which would flash before a default car.
                    Box(modifier = Modifier.fillMaxSize().padding(paddingValues))
                } else if (showSettings) {
                    SettingsScreen(
                        cars = cars,
                        themeMode = themeMode,
                        onThemeModeChange = { changeThemeMode(it) },
                        defaultCarId = defaultCarId,
                        onDefaultCarChange = { setDefaultCar(it) },
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        onBackup = { car ->
                            exportCarId = car.id
                            exportZipLauncher.launch("${safeFileName(car)}_Backup.zip")
                        },
                        onExportCsv = { car ->
                            exportCarId = car.id
                            exportLauncher.launch("${safeFileName(car)}_History.csv")
                        },
                        versionName = versionName,
                        modifier = Modifier.fillMaxSize().padding(paddingValues)
                    )
                } else if (selectedCar == null) {
                    GarageScreen(
                        cars = cars,
                        summaries = summariesByCar,
                        defaultCarId = defaultCarId,
                        locale = svLocale,
                        onOpenCar = { selectedCarId = it.id },
                        onToggleDefault = { car ->
                            setDefaultCar(if (defaultCarId == car.id) null else car.id)
                        },
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxSize().padding(paddingValues)
                    )
                } else {
                    when (currentTab) {
                        "Charts" -> ChartsTab(
                            car = selectedCar!!,
                            fuelHistory = fuelHistory,
                            expenses = expenseHistory,
                            subTab = chartSubTab,
                            onSubTabChange = { chartSubTab = it },
                            // Lifted for dark mode, where the raw accent can vanish.
                            primaryColor = dynamicThemeColors.primary,
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