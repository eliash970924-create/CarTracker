package se.eliash.cartracker

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cars")
data class Car(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val fuelType: String,
    val secondaryFuelType: String? = null,
    val initialOdometer: Int,
    val imageUri: String? = null, // NEW: Saves the photo path
    val themeColor: Long? = null  // NEW: Saves the hex color
)