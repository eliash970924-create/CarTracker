package com.example.cartracker

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "fuel_ups")
data class FuelUp(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val carId: Int,
    val fuelTypeUsed: String,
    val dateMillis: Long,
    val odometerKm: Int,
    val litersFilled: Double,
    val pricePerLiterSek: Double,
    val totalCostSek: Double,
    val missedPrevious: Boolean
)