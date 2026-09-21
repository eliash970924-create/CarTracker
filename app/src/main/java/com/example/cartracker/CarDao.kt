package com.example.cartracker

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow

@Dao
interface CarDao {
    // Returns the generated row id, so an import can fill the car it creates.
    @Insert
    fun insertCar(car: Car): Long

    @Update
    fun updateCar(car: Car)

    @Delete
    fun deleteCar(car: Car) // NEW: The delete command

    @Query("SELECT * FROM cars ORDER BY id ASC")
    fun getAllCars(): Flow<List<Car>>

    // Import matches an existing car by name before creating one.
    @Query("SELECT * FROM cars WHERE name = :name LIMIT 1")
    fun getCarByName(name: String): Car?
}