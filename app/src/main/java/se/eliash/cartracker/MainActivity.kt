package se.eliash.cartracker

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.util.Locale
import se.eliash.cartracker.ui.theme.CarTallyAmber
import se.eliash.cartracker.ui.theme.resolveDarkTheme
import se.eliash.cartracker.ui.theme.CarTallyPetrol
import se.eliash.cartracker.ui.theme.carColorScheme
import se.eliash.cartracker.ui.theme.carTallyColorScheme

/** The "Log fill-up" shortcut's action, as named in res/xml/shortcuts.xml. */
const val ACTION_LOG_FILL_UP = "se.eliash.cartracker.LOG_FILL_UP"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The launcher starts a shortcut in a fresh task, so this is always
        // read at creation. A rotation re-reads it too; the screen acts on it
        // only once.
        val fromFillUpShortcut = intent?.action == ACTION_LOG_FILL_UP
        // A reminder notification's tap: that car's reminders. Started in a
        // fresh task too, for the same reason.
        val remindersForCarId = intent?.takeIf { it.action == ACTION_OPEN_REMINDERS }
            ?.getIntExtra(EXTRA_CAR_ID, -1)?.takeIf { it >= 0 }

        // Kept if already scheduled, so this only ever sets it up once.
        ReminderScheduler.schedule(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FuelEntryScreen(startWithFillUp = fromFillUpShortcut, openRemindersForCarId = remindersForCarId)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FuelEntryScreen(
    viewModel: FuelViewModel = viewModel(),
    startWithFillUp: Boolean = false,
    openRemindersForCarId: Int? = null
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Still "CarTrackerPrefs" after the rename to CarTally, deliberately: this is
    // the file the settings already live in, and a new name opens an empty one.
    val prefs = remember { context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE) }

    // Saveable, so turning the phone does not throw away where you were.
    var currentTab by rememberSaveable { mutableStateOf("Entries") }
    var chartSubTab by rememberSaveable { mutableStateOf("Price") }

    // Settings is a third place to be, beside the garage and a car. Saveable,
    // like the rest, so turning the phone does not close it.
    var showSettings by rememberSaveable { mutableStateOf(false) }

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

    // The default car, the theme and each car's last fuel: choices about this
    // phone, kept in preferences rather than the database.
    val appPrefs = rememberAppPreferences(prefs)
    val defaultCarId = appPrefs.defaultCarId

    // Set by the "Log fill-up" shortcut: the form takes focus the next time
    // it appears. Not saveable, so turning the phone does not do it again.
    var focusFillUpForm by remember { mutableStateOf(false) }

    // Decided once, on the database's first answer. Saveable, so a rotation
    // does not re-open the default car over wherever you had got to.
    var launchHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(loadedCars) {
        val loaded = loadedCars ?: return@LaunchedEffect
        if (!launchHandled) {
            launchHandled = true
            val remindersCar = openRemindersForCarId?.let { id -> loaded.firstOrNull { it.id == id } }
            if (remindersCar != null) {
                selectedCarId = remindersCar.id
                currentTab = "Reminders"
            } else if (startWithFillUp) {
                // With a choice of cars the garage opens instead, and the
                // form still gets the focus once one is picked.
                selectedCarId = fillUpShortcutCar(loaded, defaultCarId)?.id
                currentTab = "Entries"
                focusFillUpForm = true
            } else {
                selectedCarId = launchCar(loaded, defaultCarId)?.id
            }
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
        val savedFuel = selectedCar?.let { appPrefs.lastFuel(it.id) }
        mutableStateOf(if (savedFuel != null && availableFuels.contains(savedFuel)) savedFuel else (availableFuels.firstOrNull() ?: "Petrol"))
    }

    // Keyed on the id, so editing the car's name does not start the history over.
    val fuelHistory by remember(selectedCarId) { selectedCarId?.let { viewModel.getFuelUpsForCar(it) } ?: kotlinx.coroutines.flow.emptyFlow() }.collectAsState(initial = emptyList())
    val expenseHistory by remember(selectedCarId) { selectedCarId?.let { viewModel.getExpensesForCar(it) } ?: kotlinx.coroutines.flow.emptyFlow() }.collectAsState(initial = emptyList())
    val reminders by remember(selectedCarId) { selectedCarId?.let { viewModel.getRemindersForCar(it) } ?: kotlinx.coroutines.flow.emptyFlow() }.collectAsState(initial = emptyList())
    // What wants attention now, for the strip above the fill-up form.
    val dueNow = remember(reminders, fuelHistory) { dueReminders(reminders, fuelHistory, System.currentTimeMillis()) }

    val svLocale = Locale.forLanguageTag("sv-SE")

    // For Settings > About. Read from the package rather than BuildConfig,
    // which this build does not generate.
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }

    // Export, import and automatic backup, each through the system's file picker.
    val backup = rememberBackupActions(viewModel, prefs, cars, selectedCar)

    // Light, dark, or following the phone. Saved, so it holds across launches.
    val darkTheme = resolveDarkTheme(appPrefs.themeMode, isSystemInDarkTheme())
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

    SystemBarAppearance(dynamicThemeColors, darkTheme)

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
                        if (defaultCarId == car.id) appPrefs.changeDefaultCar(null)
                    }
                    showAddCarDialog = false
                    editingCar = null
                },
                onDismiss = { showAddCarDialog = false; editingCar = null }
            )
        }

        HistoryEditDialogs(
            editingFuelUp = editingFuelUp,
            editingExpense = editingExpense,
            fuelHistory = fuelHistory,
            availableFuels = availableFuels,
            currencyLocale = svLocale,
            viewModel = viewModel,
            onFuelUpDone = { editingFuelUp = null },
            onExpenseDone = { editingExpense = null }
        )

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
                        themeMode = appPrefs.themeMode,
                        onThemeModeChange = { appPrefs.changeThemeMode(it) },
                        defaultCarId = defaultCarId,
                        onDefaultCarChange = { appPrefs.changeDefaultCar(it) },
                        onImport = backup.importFile,
                        onBackup = backup.exportZip,
                        onExportCsv = backup.exportCsv,
                        autoBackup = backup.autoBackup,
                        onChooseBackupFile = backup.chooseAutoBackupFile,
                        onAutoBackupFrequencyChange = backup.setAutoBackupFrequency,
                        onBackUpNow = backup.backUpNow,
                        onStopAutoBackup = backup.stopAutoBackup,
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
                            appPrefs.changeDefaultCar(if (defaultCarId == car.id) null else car.id)
                        },
                        onImport = backup.importFile,
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

                        "Entries" -> Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                            DueRemindersBanner(dueNow, svLocale, onOpen = { currentTab = "Reminders" })
                            EntriesTab(
                                car = selectedCar!!,
                                fuelHistory = fuelHistory,
                                form = entryForm,
                                fuelType = entryFuelType,
                                onFuelTypeChange = { fuel ->
                                    entryFuelType = fuel
                                    selectedCar?.let { appPrefs.rememberFuel(it.id, fuel) }
                                },
                                availableFuels = availableFuels,
                                currencyLocale = svLocale,
                                onSave = { fuel, dateMillis, odometer, amount, price, missed ->
                                    viewModel.saveFuelEntry(
                                        selectedCar!!.id, fuel, dateMillis, odometer,
                                        amount, price, amount * price, missed
                                    )
                                    appPrefs.rememberFuel(selectedCar!!.id, fuel)
                                },
                                onEditEntry = { editingFuelUp = it },
                                requestFocus = focusFillUpForm,
                                onFocusRequested = { focusFillUpForm = false },
                                modifier = Modifier.fillMaxWidth().weight(1f)
                            )
                        }

                        "Reminders" -> RemindersTab(
                            car = selectedCar!!,
                            reminders = reminders,
                            fuelHistory = fuelHistory,
                            locale = svLocale,
                            onSave = { viewModel.saveReminder(it) },
                            onDelete = { viewModel.deleteReminder(it) },
                            onDone = { reminder, doneMillis, odometer ->
                                viewModel.completeReminder(reminder, doneMillis, odometer)
                            },
                            modifier = Modifier.fillMaxSize().padding(paddingValues)
                        )
                    }
                }
            }
        }
    }
}