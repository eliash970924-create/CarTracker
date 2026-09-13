package com.example.cartracker

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val carId: Int,
    val dateMillis: Long,
    val category: String,
    val description: String,
    val costSek: Double,
    val isMonthly: Boolean = false // NEW: The recurring flag!
)