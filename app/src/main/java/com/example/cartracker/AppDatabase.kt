package com.example.cartracker

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// BUMPED to Version 8!
@Database(entities = [FuelUp::class, Car::class, Expense::class], version = 8, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun fuelUpDao(): FuelUpDao
    abstract fun carDao(): CarDao
    abstract fun expenseDao(): ExpenseDao

    companion object {
        @Volatile
        private var Instance: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return Instance ?: synchronized(this) {
                Room.databaseBuilder(
                    context,
                    AppDatabase::class.java,
                    "car_tracker_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { Instance = it }
            }
        }
    }
}