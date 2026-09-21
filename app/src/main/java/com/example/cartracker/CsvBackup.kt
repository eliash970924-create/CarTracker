package com.example.cartracker

import java.io.Writer

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

const val CAR_HEADER = "Name,Primary Fuel,Secondary Fuel,Initial Odometer,Theme Colour,Photo"

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
    val themeColor: Long?,
    /** File name of a photo carried alongside, once copied into app storage. */
    val photo: String? = null
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


/**
 * Writes the backup for one car: fill-ups, then expenses, then the car.
 *
 * Shared by the plain CSV export and the zip backup so the two cannot drift.
 * [photoName] is written into the car row only when a photo travels with the
 * file, which a bare CSV cannot carry.
 */
fun writeBackupCsv(
    writer: Writer,
    car: Car?,
    fuelUps: List<FuelUp>,
    expenses: List<Expense>,
    photoName: String? = null,
    formatDate: (Long) -> String
) {
    writer.write(FUEL_HEADER + "\n")
    fuelUps.forEach { fuelUp ->
        writer.write(
            "${formatDate(fuelUp.dateMillis)},${fuelUp.odometerKm},${csvEscape(fuelUp.fuelTypeUsed)}," +
                "${fuelUp.litersFilled},${fuelUp.pricePerLiterSek},${fuelUp.totalCostSek},${fuelUp.missedPrevious}\n"
        )
    }

    // Appended as a second section so the block above stays byte-identical to
    // what earlier versions wrote.
    if (expenses.isNotEmpty()) {
        writer.write("\n" + EXPENSE_SECTION_MARKER + "\n")
        writer.write(EXPENSE_HEADER + "\n")
        expenses.forEach { expense ->
            writer.write(
                "${formatDate(expense.dateMillis)},${csvEscape(expense.category)}," +
                    "${csvEscape(expense.description)},${expense.costSek},${expense.isMonthly}\n"
            )
        }
    }

    // Last, so the two blocks above keep the byte layout older versions wrote.
    car?.let {
        writer.write("\n" + CAR_SECTION_MARKER + "\n")
        writer.write(CAR_HEADER + "\n")
        writer.write(
            "${csvEscape(it.name)},${csvEscape(it.fuelType)},${csvEscape(it.secondaryFuelType ?: "")}," +
                "${it.initialOdometer},${it.themeColor ?: ""},${csvEscape(photoName ?: "")}\n"
        )
    }
}
