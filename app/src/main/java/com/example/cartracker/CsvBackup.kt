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

const val FUEL_HEADER =
    "Date,Odometer (km),Fuel Type,Amount,Price per Unit (SEK),Total Cost (SEK),Missed Previous"

const val EXPENSE_HEADER = "Date,Category,Description,Cost (SEK),Monthly"

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
