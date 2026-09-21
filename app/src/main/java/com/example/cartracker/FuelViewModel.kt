package com.example.cartracker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.Calendar

class FuelViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val fuelDao = db.fuelUpDao()
    private val carDao = db.carDao()
    private val expenseDao = db.expenseDao()

    val allCars: Flow<List<Car>> = carDao.getAllCars()

    fun getFuelUpsForCar(carId: Int): Flow<List<FuelUp>> = fuelDao.getAllFuelUpsForCar(carId)
    fun getExpensesForCar(carId: Int): Flow<List<Expense>> = expenseDao.getExpensesForCar(carId)

    fun saveCar(name: String, fuelType: String, secondaryFuelType: String?, initialOdometer: Int, imageUri: String?, themeColor: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            carDao.insertCar(Car(name = name, fuelType = fuelType, secondaryFuelType = secondaryFuelType, initialOdometer = initialOdometer, imageUri = imageUri, themeColor = themeColor))
        }
    }

    fun updateCar(car: Car) {
        viewModelScope.launch(Dispatchers.IO) {
            carDao.updateCar(car)
        }
    }

    // NEW: Delete an existing car
    fun deleteCar(car: Car) {
        viewModelScope.launch(Dispatchers.IO) {
            carDao.deleteCar(car)
        }
    }

    fun saveFuelEntry(carId: Int, fuelTypeUsed: String, dateMillis: Long, odometer: Int, liters: Double, price: Double, total: Double, missedPrevious: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { fuelDao.insertFuelUp(FuelUp(carId = carId, fuelTypeUsed = fuelTypeUsed, dateMillis = dateMillis, odometerKm = odometer, litersFilled = liters, pricePerLiterSek = price, totalCostSek = total, missedPrevious = missedPrevious)) }
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

    /**
     * Restores a CSV backup in one pass.
     *
     * Inserts every row first and fills in recurring expenses once at the end.
     * Calling saveExpense in a loop would launch one checkRecurringExpenses per
     * monthly row, and those run concurrently: two can each find the same month
     * missing and both insert it, duplicating the expense.
     */
    fun importEntries(carId: Int, fuelUps: List<FuelUp>, expenses: List<Expense>) {
        viewModelScope.launch(Dispatchers.IO) {
            fuelUps.forEach { fuelDao.insertFuelUp(it.copy(id = 0, carId = carId)) }
            expenses.forEach { expenseDao.insertExpense(it.copy(id = 0, carId = carId)) }
            if (expenses.any { it.isMonthly }) fillRecurringExpenses(carId)
        }
    }

    private fun fillRecurringExpenses(carId: Int) {
        val expenses = expenseDao.getExpensesListForCar(carId)
        val monthlyExpenses = expenses.filter { it.isMonthly }
        if (monthlyExpenses.isEmpty()) return

        val grouped = monthlyExpenses.groupBy { "${it.category}_${it.description}_${it.costSek}" }
        val now = Calendar.getInstance()

        grouped.values.forEach { group ->
            val latest = group.maxByOrNull { it.dateMillis } ?: return@forEach
            val cal = Calendar.getInstance().apply { timeInMillis = latest.dateMillis }

            while (true) {
                val currentYear = cal.get(Calendar.YEAR)
                val currentMonth = cal.get(Calendar.MONTH)
                val targetYear = now.get(Calendar.YEAR)
                val targetMonth = now.get(Calendar.MONTH)

                if (currentYear < targetYear || (currentYear == targetYear && currentMonth < targetMonth)) {
                    cal.add(Calendar.MONTH, 1)
                    val newExpense = latest.copy(id = 0, dateMillis = cal.timeInMillis)
                    expenseDao.insertExpense(newExpense)
                } else break
            }
        }
    }

    fun deleteExpense(expense: Expense) { viewModelScope.launch(Dispatchers.IO) { expenseDao.deleteExpense(expense) } }
    fun updateFuelEntry(fuelUp: FuelUp) { viewModelScope.launch(Dispatchers.IO) { fuelDao.updateFuelUp(fuelUp) } }
    fun deleteFuelEntry(fuelUp: FuelUp) { viewModelScope.launch(Dispatchers.IO) { fuelDao.deleteFuelUp(fuelUp) } }
}