package se.eliash.cartracker

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FuelUpDao {
    @Insert
    fun insertFuelUp(fuelUp: FuelUp)

    @Update
    fun updateFuelUp(fuelUp: FuelUp)

    @Delete
    fun deleteFuelUp(fuelUp: FuelUp)

    // UPDATED: Now sorts primarily by the Calendar Date!
    @Query("SELECT * FROM fuel_ups WHERE carId = :carId ORDER BY dateMillis DESC, odometerKm DESC")
    fun getAllFuelUpsForCar(carId: Int): Flow<List<FuelUp>>

    // Used by import to skip rows already present.
    @Query("SELECT * FROM fuel_ups WHERE carId = :carId")
    fun getFuelUpsListForCar(carId: Int): List<FuelUp>
}