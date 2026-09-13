package com.example.cartracker

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow

@Dao
interface CarDao {
    @Insert
    fun insertCar(car: Car)

    @Update
    fun updateCar(car: Car)

    @Delete
    fun deleteCar(car: Car) // NEW: The delete command

    @Query("SELECT * FROM cars ORDER BY id ASC")
    fun getAllCars(): Flow<List<Car>>
}