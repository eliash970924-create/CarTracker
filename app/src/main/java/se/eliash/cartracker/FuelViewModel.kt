package se.eliash.cartracker

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class FuelViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val fuelDao = db.fuelUpDao()
    private val carDao = db.carDao()
    private val expenseDao = db.expenseDao()
    private val reminderDao = db.reminderDao()

    val allCars: Flow<List<Car>> = carDao.getAllCars()
    val carSummaries: Flow<List<CarSummary>> = fuelDao.getCarSummaries()

    fun getFuelUpsForCar(carId: Int): Flow<List<FuelUp>> = fuelDao.getAllFuelUpsForCar(carId)

    /**
     * Everything logged against a car, newest first, for export. Read from the
     * database rather than from what is on screen, so Settings can export any
     * car, not only the open one. Blocking: call it off the main thread.
     */
    fun historyForExport(carId: Int): Pair<List<FuelUp>, List<Expense>> =
        inHistoryOrder(fuelDao.getFuelUpsListForCar(carId)) to
            expensesInHistoryOrder(expenseDao.getExpensesListForCar(carId))
    fun getExpensesForCar(carId: Int): Flow<List<Expense>> = expenseDao.getExpensesForCar(carId)

    fun getRemindersForCar(carId: Int): Flow<List<Reminder>> = reminderDao.getRemindersForCar(carId)

    /**
     * Adds [reminder], or saves changes to it, then checks at once whether it
     * is already due. The dialog clears [Reminder.notifiedStage] when the due
     * point changes, so a moved reminder is announced afresh.
     */
    fun saveReminder(reminder: Reminder) {
        viewModelScope.launch(Dispatchers.IO) {
            if (reminder.id == 0) reminderDao.insertReminder(reminder) else reminderDao.updateReminder(reminder)
            ReminderScheduler.checkNow(getApplication<Application>())
        }
    }

    fun deleteReminder(reminder: Reminder) {
        viewModelScope.launch(Dispatchers.IO) { reminderDao.deleteReminder(reminder) }
    }

    /**
     * Done at [doneMillis], at [doneOdometerKm] if known: moved on to its next
     * due point, or removed if it does not repeat. See [markDone].
     */
    fun completeReminder(reminder: Reminder, doneMillis: Long, doneOdometerKm: Int?) {
        viewModelScope.launch(Dispatchers.IO) {
            val next = markDone(reminder, doneMillis, doneOdometerKm)
            if (next == null) reminderDao.deleteReminder(reminder) else reminderDao.updateReminder(next)
        }
    }

    fun saveCar(name: String, fuelType: String, secondaryFuelType: String?, initialOdometer: Int, imageUri: String?, themeColor: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            carDao.insertCar(Car(name = name, fuelType = fuelType, secondaryFuelType = secondaryFuelType, initialOdometer = initialOdometer, imageUri = imageUri, themeColor = themeColor))
        }
    }

    fun updateCar(car: Car) {
        viewModelScope.launch(Dispatchers.IO) {
            val previous = carDao.getCarById(car.id)
            carDao.updateCar(car)
            // A replaced photo would otherwise sit in app storage for good.
            if (previous != null && previous.imageUri != car.imageUri) {
                deleteCarPhoto(getApplication<Application>(), previous.imageUri)
            }
        }
    }

    // NEW: Delete an existing car
    fun deleteCar(car: Car) {
        viewModelScope.launch(Dispatchers.IO) {
            carDao.deleteCar(car)
            deleteCarPhoto(getApplication<Application>(), car.imageUri)
        }
    }

    /**
     * Copies photos still held as picker URIs into app storage, once.
     *
     * Without this only newly picked photos would be safe, and the ones
     * already on the car would still vanish on the next reinstall. A copy
     * that fails means the grant has already gone; the row is left as it is
     * rather than cleared, since clearing would destroy the only record that
     * a photo was ever chosen.
     */
    fun adoptLegacyPhotos() {
        viewModelScope.launch(Dispatchers.IO) {
            carDao.getCarsList().forEach { car ->
                val stored = car.imageUri
                if (stored != null && isExternalPhotoReference(stored)) {
                    val adopted = copyPhotoIntoAppStorage(getApplication<Application>(), Uri.parse(stored))
                    if (adopted != null) carDao.updateCar(car.copy(imageUri = adopted))
                }
            }
        }
    }

    fun saveFuelEntry(carId: Int, fuelTypeUsed: String, dateMillis: Long, odometer: Int, liters: Double, price: Double, total: Double, missedPrevious: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            fuelDao.insertFuelUp(FuelUp(carId = carId, fuelTypeUsed = fuelTypeUsed, dateMillis = dateMillis, odometerKm = odometer, litersFilled = liters, pricePerLiterSek = price, totalCostSek = total, missedPrevious = missedPrevious))
            // A new reading can bring a distance reminder due.
            ReminderScheduler.checkNow(getApplication<Application>())
        }
    }

    fun saveExpense(carId: Int, dateMillis: Long, category: String, description: String, cost: Double, isMonthly: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            expenseDao.insertExpense(Expense(carId = carId, dateMillis = dateMillis, category = category, description = description, costSek = cost, isMonthly = isMonthly))
            if (isMonthly) fillRecurringExpenses(carId)
        }
    }

    fun checkRecurringExpenses(carId: Int) {
        viewModelScope.launch(Dispatchers.IO) { fillRecurringExpenses(carId) }
    }

    /** One car's share of an import: a parsed CSV, with its stored photo already set. */
    class CarImport(val car: ImportedCar?, val fuelUps: List<FuelUp>, val expenses: List<Expense>)

    /**
     * Restores a backup - one car or a whole garage - and reports once, with
     * a summary per car in the order given.
     *
     * The cars are imported one after another, never at once: two could
     * otherwise each find no car of a name and both create it. It runs in the
     * view model's scope, so turning the phone mid-import does not cut it off
     * halfway through a car.
     */
    fun importBackups(
        fallbackCarId: Int?,
        cars: List<CarImport>,
        dayOf: (Long) -> String,
        onFinished: (List<ImportSummary?>) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val results = cars.map { importOne(fallbackCarId, it.car, it.fuelUps, it.expenses, dayOf) }
            withContext(Dispatchers.Main) { onFinished(results) }
        }
    }

    /**
     * Restores one car's CSV in one pass. Null when the file names no car and
     * there is no [fallbackCarId] to put its rows under. Blocking.
     *
     * The car comes from the file when it carries a [ImportedCar] section:
     * an existing car of that name is reused, otherwise one is created, so a
     * backup can be restored onto a fresh install. Files written before the
     * car section existed fall back to [fallbackCarId], the car on screen.
     *
     * Rows already present are skipped rather than inserted again, matched on
     * the calendar day and values via [fuelKey] and [expenseKey]. Nothing is
     * ever deleted: an import merges, it does not replace.
     *
     * Recurring expenses are filled in once at the end. Calling saveExpense in
     * a loop would launch one checkRecurringExpenses per monthly row, and
     * those run concurrently: two can each find the same month missing and
     * both insert it.
     */
    private fun importOne(
        fallbackCarId: Int?,
        car: ImportedCar?,
        fuelUps: List<FuelUp>,
        expenses: List<Expense>,
        dayOf: (Long) -> String
    ): ImportSummary? {
        val existingCar = car?.let { carDao.getCarByName(it.name) }
        var created = false

        // A photo that came with the file, for a car already here: adopted if
        // the car has none, deleted if it has its own - so restoring the same
        // backup twice leaves no stray copies in storage.
        val importedPhoto = car?.photo
        if (existingCar != null && importedPhoto != null) {
            if (existingCar.imageUri.isNullOrBlank()) {
                carDao.updateCar(existingCar.copy(imageUri = importedPhoto))
            } else {
                deleteCarPhoto(getApplication<Application>(), importedPhoto)
            }
        }

        val carId: Int = when {
            existingCar != null -> existingCar.id
            car != null -> {
                created = true
                carDao.insertCar(
                    Car(
                        name = car.name,
                        fuelType = car.fuelType,
                        secondaryFuelType = car.secondaryFuelType,
                        initialOdometer = car.initialOdometer,
                        imageUri = car.photo,
                        themeColor = car.themeColor
                    )
                ).toInt()
            }
            // A file with no car section needs the car on screen.
            fallbackCarId != null -> fallbackCarId
            else -> return null
        }

        val knownFuel = fuelDao.getFuelUpsListForCar(carId)
            .map { fuelKey(dayOf(it.dateMillis), it.odometerKm, it.litersFilled, it.fuelTypeUsed) }
            .toMutableSet()
        val knownExpenses = expenseDao.getExpensesListForCar(carId)
            .map { expenseKey(dayOf(it.dateMillis), it.category, it.description, it.costSek) }
            .toMutableSet()

        var fuelAdded = 0
        var fuelSkipped = 0
        fuelUps.forEach { row ->
            val key = fuelKey(dayOf(row.dateMillis), row.odometerKm, row.litersFilled, row.fuelTypeUsed)
            // add() reports whether the key was new, which also stops a
            // file that repeats a row from inserting it twice.
            if (knownFuel.add(key)) {
                fuelDao.insertFuelUp(row.copy(id = 0, carId = carId))
                fuelAdded++
            } else {
                fuelSkipped++
            }
        }

        var expensesAdded = 0
        var expensesSkipped = 0
        expenses.forEach { row ->
            val key = expenseKey(dayOf(row.dateMillis), row.category, row.description, row.costSek)
            if (knownExpenses.add(key)) {
                expenseDao.insertExpense(row.copy(id = 0, carId = carId))
                expensesAdded++
            } else {
                expensesSkipped++
            }
        }

        if (expensesAdded > 0 && expenses.any { it.isMonthly }) fillRecurringExpenses(carId)

        return ImportSummary(
            carName = car?.name,
            carCreated = created,
            fuelAdded = fuelAdded,
            fuelSkipped = fuelSkipped,
            expensesAdded = expensesAdded,
            expensesSkipped = expensesSkipped
        )
    }

    private fun fillRecurringExpenses(carId: Int) {
        val expenses = expenseDao.getExpensesListForCar(carId)
        missingRecurringExpenses(expenses, Calendar.getInstance())
            .forEach { expenseDao.insertExpense(it) }
    }

    fun deleteExpense(expense: Expense) { viewModelScope.launch(Dispatchers.IO) { expenseDao.deleteExpense(expense) } }

    /**
     * Saves an edited expense, and keeps the repeat consistent with it.
     *
     * Switching a repeat off has to clear the flag across the whole series,
     * not just the row being edited. The generator seeds from the latest row
     * still marked monthly, so clearing one leaves the next one down to take
     * over and fill the months straight back in.
     *
     * The series is found from [original], because the edit may have renamed
     * the description - which is half of what identifies a series - and the
     * rows still to be cleared carry the old name.
     */
    fun updateExpense(original: Expense, updated: Expense) {
        viewModelScope.launch(Dispatchers.IO) {
            expenseDao.updateExpense(updated)

            val stoppedRepeating = original.isMonthly && !updated.isMonthly
            if (stoppedRepeating) {
                seriesOf(expenseDao.getExpensesListForCar(updated.carId), original)
                    .filter { it.id != updated.id && it.isMonthly }
                    .forEach { expenseDao.updateExpense(it.copy(isMonthly = false)) }
            } else if (updated.isMonthly) {
                // A new or renamed repeat may have months owing behind it.
                fillRecurringExpenses(updated.carId)
            }
        }
    }
    fun updateFuelEntry(fuelUp: FuelUp) { viewModelScope.launch(Dispatchers.IO) { fuelDao.updateFuelUp(fuelUp) } }
    fun deleteFuelEntry(fuelUp: FuelUp) { viewModelScope.launch(Dispatchers.IO) { fuelDao.deleteFuelUp(fuelUp) } }
}