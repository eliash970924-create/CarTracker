package com.example.cartracker

import androidx.room.migration.Migration

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
 *
 * Example:
 *
 *   val MIGRATION_8_9 = object : Migration(8, 9) {
 *       override fun migrate(db: SupportSQLiteDatabase) {
 *           db.execSQL("ALTER TABLE cars ADD COLUMN notes TEXT")
 *       }
 *   }
 */
val ALL_MIGRATIONS: Array<Migration> = arrayOf<Migration>(
    // No migrations yet - version 8 is the baseline.
)
