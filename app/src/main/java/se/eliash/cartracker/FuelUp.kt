package se.eliash.cartracker

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "fuel_ups",
    foreignKeys = [
        ForeignKey(
            entity = Car::class,
            parentColumns = ["id"],
            childColumns = ["carId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["carId"])]
)
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
