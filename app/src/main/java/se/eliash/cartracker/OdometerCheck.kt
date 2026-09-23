package se.eliash.cartracker

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * An odometer only counts up, so a car's readings have to rise with their
 * dates. A reading that does not - most often a date typed a year out - is
 * refused when it is entered, with the entry it clashes with named, and one
 * already in the history is pointed out.
 *
 * Readings are compared by calendar day. Two fill-ups on the same day can be
 * in either order - the time of day is not reliably known - so they are
 * never held against each other.
 */

/** A local calendar day as one sortable number: 2024-02-02 is 20240202. */
fun localDay(millis: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return c.get(Calendar.YEAR) * 10_000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
}

/**
 * A logged reading that the one being entered cannot sit beside.
 * [readingTooHigh]: [other] is later and reads lower; otherwise it is earlier
 * and reads higher.
 */
data class OdometerConflict(val other: FuelUp, val readingTooHigh: Boolean)

/**
 * Whether a reading of [odometerKm] on [dateMillis] fits the car's [history],
 * leaving out the entry [excludeId] - the one being edited. Null when it fits,
 * or when there is no reading to check.
 *
 * Every fuel counts: it is one odometer, whatever went in the tank. When
 * several readings clash, the nearest in time is named, as the likeliest to
 * be the one the reading was meant to follow or precede.
 */
fun odometerConflict(
    dateMillis: Long,
    odometerKm: Int,
    history: List<FuelUp>,
    excludeId: Int? = null
): OdometerConflict? {
    if (odometerKm <= 0) return null
    val day = localDay(dateMillis)
    val others = history.filter { it.id != excludeId && it.odometerKm > 0 }

    others.filter { localDay(it.dateMillis) > day && it.odometerKm < odometerKm }
        .minByOrNull { it.dateMillis }
        ?.let { return OdometerConflict(it, readingTooHigh = true) }

    others.filter { localDay(it.dateMillis) < day && it.odometerKm > odometerKm }
        .maxByOrNull { it.dateMillis }
        ?.let { return OdometerConflict(it, readingTooHigh = false) }

    return null
}

/**
 * The refusal in words: what clashes with what, and what to do about it -
 * including that the entry already there may be the wrong one.
 */
fun describeOdometerConflict(
    dateMillis: Long,
    odometerKm: Int,
    conflict: OdometerConflict,
    locale: Locale
): String {
    val dates = SimpleDateFormat("d MMM yyyy", locale)
    val thisDate = dates.format(Date(dateMillis))
    val otherDate = dates.format(Date(conflict.other.dateMillis))
    val thisKm = "%,d km".format(locale, odometerKm)
    val otherKm = "%,d km".format(locale, conflict.other.odometerKm)

    val clash = if (conflict.readingTooHigh) {
        "on $otherDate it read $otherKm, less than the $thisKm given for $thisDate"
    } else {
        "on $otherDate it already read $otherKm, more than the $thisKm given for $thisDate"
    }
    return "The odometer can't go backwards: $clash. Check the date and the reading, " +
        "or if the $otherDate entry is the wrong one, correct that one in the history."
}

/**
 * The entries in [history] whose readings do not fit the rest: the fewest
 * that, taken out, leave every other reading rising with its date.
 *
 * Not simply every entry in a clash: one reading dated a year early clashes
 * with the whole year after it, and flagging that year would bury the one
 * mistake among the entries that are right. Keeping the longest run of
 * readings that do rise - and flagging what is left - points at the mistake.
 * Same-day readings are put in reading order, so they never clash.
 */
fun outOfOrderEntries(history: List<FuelUp>): Set<Int> {
    val readings = history.filter { it.odometerKm > 0 }
        .sortedWith(compareBy<FuelUp>({ localDay(it.dateMillis) }, { it.odometerKm }))
    if (readings.size < 2) return emptySet()

    // Longest run of readings that never go down, oldest to newest.
    val length = IntArray(readings.size) { 1 }
    val previous = IntArray(readings.size) { -1 }
    for (i in readings.indices) {
        for (j in 0 until i) {
            if (readings[j].odometerKm <= readings[i].odometerKm && length[j] + 1 > length[i]) {
                length[i] = length[j] + 1
                previous[i] = j
            }
        }
    }
    // On a tie, the run ending latest: the newest readings are the likeliest right.
    var end = 0
    for (i in readings.indices) if (length[i] >= length[end]) end = i

    val kept = mutableSetOf<Int>()
    var i = end
    while (i >= 0) {
        kept += readings[i].id
        i = previous[i]
    }
    return readings.map { it.id }.filterNot { it in kept }.toSet()
}

/**
 * [fuelUps] as the calculations should see them: each reading flagged by
 * [outOfOrderEntries] taken out, as if never entered, until it is fixed.
 *
 * One such reading otherwise does real damage. Dated a year early, 67,000 km
 * after 42,000 reads as 25,000 km on a tank - a consumption near zero, the
 * best ever, and a month that never happened in the distance chart.
 *
 * The next fill-up of the same fuel is then treated as following a missed
 * one: its distance would run back past the reading taken out, over fuel it
 * does not count, and show a figure better than the truth. No figure is
 * better than a wrong one. Only the calculations see this copy; the history
 * list still shows what was entered.
 */
fun withTrustedReadings(fuelUps: List<FuelUp>): List<FuelUp> {
    val flagged = outOfOrderEntries(fuelUps)
    if (flagged.isEmpty()) return fuelUps

    val gapAfter = mutableSetOf<String>()
    val adjusted = mutableMapOf<Int, FuelUp>()
    fuelUps.sortedWith(compareBy<FuelUp>({ it.dateMillis }, { it.odometerKm })).forEach { entry ->
        when {
            entry.id in flagged -> {
                adjusted[entry.id] = entry.copy(odometerKm = 0)
                gapAfter += entry.fuelTypeUsed
            }
            entry.odometerKm > 0 && entry.fuelTypeUsed in gapAfter -> {
                adjusted[entry.id] = entry.copy(missedPrevious = true)
                gapAfter -= entry.fuelTypeUsed
            }
        }
    }
    // The order given is kept: callers rely on it (the history is newest first).
    return fuelUps.map { adjusted[it.id] ?: it }
}
