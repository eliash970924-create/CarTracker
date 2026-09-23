package se.eliash.cartracker

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
 * then the car, then its reminders:
 *
 *     [Car]
 *     Name,Primary Fuel,Secondary Fuel,Initial Odometer,Theme Colour,Photo
 *     Volvo V60,Petrol,Electric,12000,4280391411,
 *
 *     [Reminders]
 *     Type,Name,Due Date,Due Odometer (km),Repeat Months,Repeat Km,Warn Days,Warn Km
 *     Service,Service,2027-03-15,15000,12,15000,7,500
 *
 * Each section is appended after the ones before it, never inserted, so every
 * earlier layout is a prefix of this one and still imports.
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
const val REMINDER_SECTION_MARKER = "[Reminders]"

/** Which block of the file the importer is currently reading. */
enum class Section { FUEL, EXPENSES, CAR, REMINDERS }

const val FUEL_HEADER =
    "Date,Odometer (km),Fuel Type,Amount,Price per Unit (SEK),Total Cost (SEK),Missed Previous"

const val EXPENSE_HEADER = "Date,Category,Description,Cost (SEK),Monthly"

const val CAR_HEADER = "Name,Primary Fuel,Secondary Fuel,Initial Odometer,Theme Colour,Photo"

const val REMINDER_HEADER = "Type,Name,Due Date,Due Odometer (km),Repeat Months,Repeat Km,Warn Days,Warn Km"

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
 * A reminder is the same one if it is the same kind, of the same name, due at
 * the same point. The due date is compared by day, as the other keys are.
 */
fun reminderKey(type: String, title: String, dueDay: String?, dueOdometerKm: Int?): String =
    "$type|$title|${dueDay ?: ""}|${dueOdometerKm ?: ""}"

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
 * Writes the backup for one car: fill-ups, then expenses, then the car, then
 * its reminders.
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
    formatDate: (Long) -> String,
    reminders: List<Reminder> = emptyList()
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

    // After the car, for the same reason. What has already been announced is
    // left out: a restored reminder that is due should be announced again.
    if (reminders.isNotEmpty()) {
        writer.write("\n" + REMINDER_SECTION_MARKER + "\n")
        writer.write(REMINDER_HEADER + "\n")
        reminders.forEach { r ->
            writer.write(
                "${csvEscape(r.type)},${csvEscape(r.title)},${r.dueDateMillis?.let(formatDate) ?: ""}," +
                    "${r.dueOdometerKm ?: ""},${r.repeatMonths ?: ""},${r.repeatKm ?: ""}," +
                    "${r.warnDays},${r.warnKm}\n"
            )
        }
    }
}

/** What a backup file's text yielded. */
data class ParsedBackup(
    val car: ImportedCar?,
    val fuelUps: List<FuelUp>,
    val expenses: List<Expense>,
    /** Rows that could not be read and were left out. */
    val unreadableRows: Int,
    /** With no car id, like the other rows. */
    val reminders: List<Reminder> = emptyList()
)

/**
 * Reads a backup's text into rows.
 *
 * Kept out of the UI and given [parseDate] as a parameter so it can be tested
 * without a date formatter or a device locale.
 *
 * A row that cannot be read is skipped and counted rather than aborting the
 * import, which is what a single unreadable date used to do: one bad line in
 * a thousand meant none of the thousand arrived. It is not given a substitute
 * date either - an invented date would sit in the history looking like fact.
 *
 * Rows carry no car id; the caller decides which car they belong to.
 */
fun parseBackupCsv(csv: String, parseDate: (String) -> Long?): ParsedBackup {
    val fuelUps = mutableListOf<FuelUp>()
    val expenses = mutableListOf<Expense>()
    val reminders = mutableListOf<Reminder>()
    var car: ImportedCar? = null
    var unreadable = 0

    var section = Section.FUEL
    var skipHeader = false

    csv.split("\n").forEachIndexed { index, rawLine ->
        val line = rawLine.trim()
        when {
            line.isEmpty() -> return@forEachIndexed
            // A file written before expenses existed never reaches this
            // marker and parses as fuel only.
            line == EXPENSE_SECTION_MARKER -> {
                section = Section.EXPENSES
                skipHeader = true
                return@forEachIndexed
            }
            line == CAR_SECTION_MARKER -> {
                section = Section.CAR
                skipHeader = true
                return@forEachIndexed
            }
            line == REMINDER_SECTION_MARKER -> {
                section = Section.REMINDERS
                skipHeader = true
                return@forEachIndexed
            }
            index == 0 -> return@forEachIndexed   // fuel header
            skipHeader -> {
                skipHeader = false
                return@forEachIndexed             // section header
            }
        }

        val tokens = parseCsvLine(line)

        // Read before any date handling: a car row's first field is a name,
        // and treating it as a date would count the row as unreadable.
        if (section == Section.CAR) {
            if (tokens.size >= 4 && tokens[0].isNotBlank()) {
                car = ImportedCar(
                    name = tokens[0],
                    fuelType = tokens[1].ifBlank { "Petrol" },
                    secondaryFuelType = tokens[2].ifBlank { null },
                    initialOdometer = tokens[3].toIntOrNull() ?: 0,
                    themeColor = tokens.getOrNull(4)?.toLongOrNull()
                )
            } else {
                unreadable++
            }
            return@forEachIndexed
        }

        // Its date is optional and in the third field, so it is read here too.
        if (section == Section.REMINDERS) {
            val reminder = parseReminderRow(tokens, parseDate)
            if (reminder != null) reminders.add(reminder) else unreadable++
            return@forEachIndexed
        }

        val date = tokens.firstOrNull()?.let(parseDate)
        if (date == null) {
            unreadable++
            return@forEachIndexed
        }

        if (section == Section.EXPENSES) {
            val cost = tokens.getOrNull(3)?.toDoubleOrNull() ?: 0.0
            if (tokens.size >= 5 && cost > 0) {
                expenses.add(
                    Expense(
                        carId = 0,
                        dateMillis = date,
                        category = tokens[1],
                        description = tokens[2],
                        costSek = cost,
                        isMonthly = tokens[4].toBooleanStrictOrNull() ?: false
                    )
                )
            } else {
                unreadable++
            }
        } else {
            val amount = tokens.getOrNull(3)?.toDoubleOrNull() ?: 0.0
            if (tokens.size >= 7 && amount > 0) {
                fuelUps.add(
                    FuelUp(
                        carId = 0,
                        fuelTypeUsed = tokens[2],
                        dateMillis = date,
                        odometerKm = tokens[1].toIntOrNull() ?: 0,
                        litersFilled = amount,
                        pricePerLiterSek = tokens[4].toDoubleOrNull() ?: 0.0,
                        totalCostSek = tokens[5].toDoubleOrNull() ?: 0.0,
                        missedPrevious = tokens[6].toBooleanStrictOrNull() ?: false
                    )
                )
            } else {
                unreadable++
            }
        }
    }

    return ParsedBackup(car, fuelUps, expenses, unreadable, reminders)
}

/**
 * One reminder row, or null if it cannot be one: too few fields, a date that
 * does not read, a number that is not one, or neither a date nor a distance.
 * An unknown type is kept as Other rather than lost, and blank warnings take
 * the defaults.
 */
private fun parseReminderRow(tokens: List<String>, parseDate: (String) -> Long?): Reminder? {
    if (tokens.size < 8) return null

    // Blank is none; anything else has to be a number above zero, or the row
    // is refused rather than read with a field quietly missing.
    var malformed = false
    fun optionalInt(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val value = trimmed.toIntOrNull()
        if (value == null || value <= 0) malformed = true
        return value
    }

    val type = ReminderType.fromStored(tokens[0].trim())
    val dateText = tokens[2].trim()
    val dueDate = if (dateText.isEmpty()) null else (parseDate(dateText) ?: return null)
    val dueKm = optionalInt(tokens[3])
    val repeatMonths = optionalInt(tokens[4])
    val repeatKm = optionalInt(tokens[5])
    val warnDays = optionalInt(tokens[6])
    val warnKm = optionalInt(tokens[7])
    if (malformed || !isValidReminder(dueDate, dueKm)) return null

    return Reminder(
        carId = 0,
        type = type.name,
        title = tokens[1].trim().ifEmpty { type.label },
        dueDateMillis = dueDate,
        dueOdometerKm = dueKm,
        repeatMonths = repeatMonths,
        repeatKm = repeatKm,
        warnDays = warnDays ?: DEFAULT_WARN_DAYS,
        warnKm = warnKm ?: DEFAULT_WARN_KM
    )
}

/**
 * Fill-ups newest first, as the history list shows them - the same order as
 * the fill-up query's ORDER BY dateMillis DESC, odometerKm DESC, so a CSV
 * exported from Settings reads exactly like one exported from the car.
 */
fun inHistoryOrder(fuelUps: List<FuelUp>): List<FuelUp> =
    fuelUps.sortedWith(compareByDescending<FuelUp> { it.dateMillis }.thenByDescending { it.odometerKm })

/** Expenses newest first, as the expense list shows them. */
fun expensesInHistoryOrder(expenses: List<Expense>): List<Expense> =
    expenses.sortedByDescending { it.dateMillis }

/**
 * The day format every backup is written in. Import matches rows already
 * present by this string, so the manual and automatic backups share it
 * rather than each keeping a copy that could drift apart.
 */
fun formatBackupDay(millis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(millis))

/**
 * What importing one car achieved, for reporting back to the user. Silence
 * about what a restore actually did is how a backup quietly turns out to be
 * useless.
 */
data class ImportSummary(
    val carName: String?,
    val carCreated: Boolean,
    val fuelAdded: Int,
    val fuelSkipped: Int,
    val expensesAdded: Int,
    val expensesSkipped: Int,
    val remindersAdded: Int = 0
)

/**
 * The one message an import ends with: the familiar detail for one car, a
 * total for a garage. [fallbackCarName] names the car a file without a car
 * section went into; null in [results] means such a file had nowhere to go.
 */
fun importMessage(results: List<ImportSummary?>, fallbackCarName: String?, unreadableRows: Int): String {
    val done = results.filterNotNull()
    if (done.isEmpty()) return "Select a car first, or import a file that includes car details"

    return buildString {
        if (done.size == 1) {
            val result = done.single()
            val name = result.carName ?: fallbackCarName ?: "car"
            append(if (result.carCreated) "Created $name: " else "$name: ")
        } else {
            append("Restored ${done.size} cars")
            val created = done.count { it.carCreated }
            if (created > 0) append(" ($created new)")
            append(": ")
        }
        append("added ${done.sumOf { it.fuelAdded }} fill-ups, ${done.sumOf { it.expensesAdded }} expenses")
        // Only mentioned when there are some, so files without any read as before.
        val reminders = done.sumOf { it.remindersAdded }
        if (reminders > 0) append(if (reminders == 1) ", 1 reminder" else ", $reminders reminders")
        val skipped = done.sumOf { it.fuelSkipped + it.expensesSkipped }
        if (skipped > 0) append(" - skipped $skipped already present")
        if (unreadableRows > 0) append(" - $unreadableRows rows could not be read")
    }
}
