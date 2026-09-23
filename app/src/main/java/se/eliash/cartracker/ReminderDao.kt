package se.eliash.cartracker

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Insert
    fun insertReminder(reminder: Reminder): Long

    @Update
    fun updateReminder(reminder: Reminder)

    @Delete
    fun deleteReminder(reminder: Reminder)

    @Query("SELECT * FROM reminders WHERE carId = :carId ORDER BY id ASC")
    fun getRemindersForCar(carId: Int): Flow<List<Reminder>>

    /** For the daily check, which runs with the app closed. */
    @Query("SELECT * FROM reminders")
    fun getAllRemindersList(): List<Reminder>
}
