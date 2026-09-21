package com.example.cartracker

/**
 * CSV backup format, shared by export and import.
 *
 * A file holds the fuel-ups first, then an optional expense section:
 *
 *     Date,Odometer (km),Fuel Type,...
 *     2026-01-15,12000,Petrol,40.0,18.5,740.0,false
 *
 *     [Expenses]
 *     Date,Category,Description,Cost (SEK),Monthly
 *     2026-01-20,Tires,"Winter set, mounted",8000.0,false
 *
 * The fuel section is byte-identical to what earlier versions wrote, so files
 * exported before expenses existed still import, and a file exported now is
 * still readable by anything that only understands the fuel block.
 *
 * Numbers are written with Kotlin's Double.toString, which always uses a dot
 * regardless of locale, and are read back with toDoubleOrNull, which expects
 * one. That keeps a file exported on a Swedish device readable everywhere.
 */
const val EXPENSE_SECTION_MARKER = "[Expenses]"
const val CAR_SECTION_MARKER = "[Car]"

/** Which block of the file the importer is currently reading. */
enum class Section { FUEL, EXPENSES, CAR }

const val FUEL_HEADER =
    "Date,Odometer (km),Fuel Type,Amount,Price per Unit (SEK),Total Cost (SEK),Missed Previous"

const val EXPENSE_HEADER = "Date,Category,Description,Cost (SEK),Monthly"

const val CAR_HEADER = "Name,Primary Fuel,Secondary Fuel,Initial Odometer,Theme Colour"

/**
 * The car a backup describes, so a file can be restored onto a fresh install
 * without creating the car by hand first. The photo is deliberately left out:
 * it is stored as a content:// URI granted to this install, which means
 * nothing on another device or after a reinstall.
 */
data class ImportedCar(
    val name: String,
    val fuelType: String,
    val secondaryFuelType: String?,
    val initialOdometer: Int,
    val themeColor: Long?
)

/**
 * Identity used to recognise a row already present, so re-importing a file
 * merges instead of duplicating.
 *
 * Keyed on the calendar day rather than the raw timestamp: export writes
 * yyyy-MM-dd, so a row that goes out and comes back lands at midnight while
 * the row it came from still carries the time of day it was entered. Matching
 * on dateMillis would therefore never find the original and would duplicate
 * every row in the file.
 */
fun fuelKey(dateStr: String, odometerKm: Int, liters: Double, fuelType: String): String =
    "$dateStr|$odometerKm|$liters|$fuelType"

fun expenseKey(dateStr: String, category: String, description: String, cost: Double): String =
    "$dateStr|$category|$description|$cost"

/**
 * Quotes a field if it contains a comma, quote or newline, doubling any quote
 * inside it, per RFC 4180. Expense descriptions are free text, so without this
 * a description like `Oil change, filter` would silently split into two
 * columns and corrupt every row after it.
 */
fun csvEscape(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }

/**
 * Splits one CSV line, honouring quoted fields and doubled quotes. Replaces a
 * plain split(","), which cannot read back anything [csvEscape] has quoted.
 */
fun parseCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0

    while (i < line.length) {
        val c = line[i]
        when {
            // A doubled quote inside a quoted field is one literal quote.
            inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                field.append('"')
                i++
            }
            c == '"' -> inQuotes = !inQuotes
            c == ',' && !inQuotes -> {
                fields.add(field.toString())
                field.setLength(0)
            }
            else -> field.append(c)
        }
        i++
    }
    fields.add(field.toString())
    return fields
}
