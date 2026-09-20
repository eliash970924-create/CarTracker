package com.example.cartracker

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version 8 is the schema baseline. Its schema is exported to app/schemas and
 * committed, so every later version can be migrated from it properly.
 */
@Database(entities = [FuelUp::class, Car::class, Expense::class], version = 8, exportSchema = true)
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
                    .addMigrations(*ALL_MIGRATIONS)
                    // Versions 1-7 predate schema export, so their layouts are
                    // unknown and cannot be migrated. Only those pre-baseline
                    // development databases are recreated from scratch.
                    //
                    // Everything from version 8 on is deliberately NOT covered:
                    // bumping the version without adding a Migration throws
                    // IllegalStateException at startup rather than quietly
                    // deleting the user's logged fuel-ups and expenses.
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6, 7)
                    .build()
                    .also { Instance = it }
            }
        }
    }
}
