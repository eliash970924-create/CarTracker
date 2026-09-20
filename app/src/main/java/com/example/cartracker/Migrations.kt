package com.example.cartracker

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Real schema migrations, applied in order by Room.
 *
 * Version 8 is the baseline: it is the first version whose schema is recorded
 * under app/schemas, so it is the earliest version we can migrate *from*
 * with confidence. Versions 1-7 predate schema export and cannot be
 * reconstructed; see [AppDatabase] for how those are handled.
 *
 * To change the schema from here on:
 *   1. Edit the @Entity classes.
 *   2. Bump the version in @Database.
 *   3. Add a Migration below and list it in [ALL_MIGRATIONS].
 *   4. Build once so Room writes the new app/schemas/<version>.json, and
 *      commit that file alongside the migration.
 *
 * Skipping step 3 now fails loudly at runtime instead of silently erasing
 * the user's fuel and expense history.
 */

/**
 * Adds ON DELETE CASCADE foreign keys from fuel_ups and expenses to cars.
 *
 * Before this, deleting a car removed only its row: its fill-ups and expenses
 * stayed behind forever, invisible to every query (they all filter by carId)
 * but still occupying the database. Worse, SQLite reuses AUTOINCREMENT ids
 * only above the high-water mark, but a restored or re-imported car could
 * still collide with stale carId values and inherit a dead car's history.
 *
 * SQLite cannot add a constraint to an existing table, so each child table is
 * rebuilt and its rows copied across.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Check constraints at COMMIT rather than per statement, so the
        // create/copy/drop/rename sequence below cannot trip over itself.
        db.execSQL("PRAGMA defer_foreign_keys = TRUE")

        // --- fuel_ups ---------------------------------------------------
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `fuel_ups_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `carId` INTEGER NOT NULL,
                `fuelTypeUsed` TEXT NOT NULL,
                `dateMillis` INTEGER NOT NULL,
                `odometerKm` INTEGER NOT NULL,
                `litersFilled` REAL NOT NULL,
                `pricePerLiterSek` REAL NOT NULL,
                `totalCostSek` REAL NOT NULL,
                `missedPrevious` INTEGER NOT NULL,
                FOREIGN KEY(`carId`) REFERENCES `cars`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )

        // Rows whose car is already gone cannot satisfy the new constraint.
        // They are unreachable by every existing query, so they are dropped
        // here rather than carried forward.
        db.execSQL(
            """
            INSERT INTO `fuel_ups_new` (
                `id`, `carId`, `fuelTypeUsed`, `dateMillis`, `odometerKm`,
                `litersFilled`, `pricePerLiterSek`, `totalCostSek`, `missedPrevious`
            )
            SELECT
                `id`, `carId`, `fuelTypeUsed`, `dateMillis`, `odometerKm`,
                `litersFilled`, `pricePerLiterSek`, `totalCostSek`, `missedPrevious`
            FROM `fuel_ups`
            WHERE `carId` IN (SELECT `id` FROM `cars`)
            """.trimIndent()
        )

        db.execSQL("DROP TABLE `fuel_ups`")
        db.execSQL("ALTER TABLE `fuel_ups_new` RENAME TO `fuel_ups`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_fuel_ups_carId` ON `fuel_ups` (`carId`)")

        // --- expenses ---------------------------------------------------
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `expenses_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `carId` INTEGER NOT NULL,
                `dateMillis` INTEGER NOT NULL,
                `category` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `costSek` REAL NOT NULL,
                `isMonthly` INTEGER NOT NULL,
                FOREIGN KEY(`carId`) REFERENCES `cars`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            INSERT INTO `expenses_new` (
                `id`, `carId`, `dateMillis`, `category`,
                `description`, `costSek`, `isMonthly`
            )
            SELECT
                `id`, `carId`, `dateMillis`, `category`,
                `description`, `costSek`, `isMonthly`
            FROM `expenses`
            WHERE `carId` IN (SELECT `id` FROM `cars`)
            """.trimIndent()
        )

        db.execSQL("DROP TABLE `expenses`")
        db.execSQL("ALTER TABLE `expenses_new` RENAME TO `expenses`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_carId` ON `expenses` (`carId`)")
    }
}

val ALL_MIGRATIONS: Array<Migration> = arrayOf<Migration>(
    MIGRATION_8_9
)
