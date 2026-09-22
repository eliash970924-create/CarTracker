package se.eliash.cartracker

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Insert
    fun insertExpense(expense: Expense)

    @Update
    fun updateExpense(expense: Expense)

    @Delete
    fun deleteExpense(expense: Expense)

    // Used for the UI
    @Query("SELECT * FROM expenses WHERE carId = :carId ORDER BY dateMillis DESC")
    fun getExpensesForCar(carId: Int): Flow<List<Expense>>

    // NEW: Used by the background Auto-Generator
    @Query("SELECT * FROM expenses WHERE carId = :carId")
    fun getExpensesListForCar(carId: Int): List<Expense>
}