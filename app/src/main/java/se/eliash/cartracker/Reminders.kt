package se.eliash.cartracker

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * What a reminder's due point means today: how long is left by date, how far
 * by distance, and so how urgent it is. Pure, so the arithmetic that decides
 * whether a notification goes out can be tested.
 */

private val DAY_MILLIS = TimeUnit.DAYS.toMillis(1)

/** Readings older than this are left out of the km-per-day estimate when newer ones suffice. */
private const val RECENT_DAYS = 180

/** Less time than this between readings says too little about how far the car goes. */
private const val MIN_SPAN_DAYS = 14

enum class Urgency { Ok, Soon, Overdue }

/** Where the odometer was last read, and when. */
data class OdometerReading(val km: Int, val atMillis: Long)

data class ReminderStatus(
    val urgency: Urgency,
    /** Calendar days to the due date; negative once past it. Null with no date. */
    val daysLeft: Long?,
    /** Distance to the due reading from the last logged one; negative once past. */
    val kmLeft: Int?,
    /** [kmLeft], less what the car has probably covered since that reading. */
    val estimatedKmLeft: Int?,
    /** Roughly how many days [estimatedKmLeft] takes at the usual pace. */
    val estimatedDaysForKm: Long?
)

/** The newest logged odometer reading, or null before the first. */
fun latestReading(fuelUps: List<FuelUp>): OdometerReading? =
    fuelUps.filter { it.odometerKm > 0 }
        .maxWithOrNull(compareBy<FuelUp> { it.dateMillis }.thenBy { it.odometerKm })
        ?.let { OdometerReading(it.odometerKm, it.dateMillis) }

/**
 * How far the car usually goes in a day, from its logged readings.
 *
 * The last half-year when that covers at least two weeks, so a change of
 * habit shows; otherwise the whole history. Null when the readings span
 * under two weeks or show no distance: too little to estimate from, and a
 * reminder is better quiet than wrong.
 */
fun kmPerDay(fuelUps: List<FuelUp>, now: Long): Double? {
    val readings = fuelUps.filter { it.odometerKm > 0 }.sortedBy { it.dateMillis }
    val recent = readings.filter { it.dateMillis >= now - RECENT_DAYS * DAY_MILLIS }

    fun rate(span: List<FuelUp>): Double? {
        if (span.size < 2) return null
        val days = (span.last().dateMillis - span.first().dateMillis).toDouble() / DAY_MILLIS
        val km = span.last().odometerKm - span.first().odometerKm
        if (days < MIN_SPAN_DAYS || km <= 0) return null
        return km / days
    }
    return rate(recent) ?: rate(readings)
}

/**
 * How [reminder] stands at [now].
 *
 * Overdue is only ever decided by fact: the date has passed, or a logged
 * reading has reached the due distance. "Due soon" also listens to the
 * estimate, which is the point of having one - a warning that waits for the
 * next fill-up can arrive after the service it was warning about.
 */
fun reminderStatus(
    reminder: Reminder,
    reading: OdometerReading?,
    kmPerDay: Double?,
    now: Long
): ReminderStatus {
    val daysLeft = reminder.dueDateMillis?.let { calendarDaysBetween(now, it) }
    val kmLeft = reminder.dueOdometerKm?.let { due -> reading?.let { due - it.km } }

    val pace = kmPerDay?.takeIf { it > 0 }
    val estimatedKmLeft = if (kmLeft != null && pace != null && reading != null) {
        val daysSince = ((now - reading.atMillis).coerceAtLeast(0L)).toDouble() / DAY_MILLIS
        (kmLeft - pace * daysSince).roundToInt()
    } else {
        kmLeft
    }
    val estimatedDaysForKm = if (estimatedKmLeft != null && pace != null) {
        ceil(estimatedKmLeft.coerceAtLeast(0) / pace).toLong()
    } else {
        null
    }

    val overdue = (daysLeft != null && daysLeft < 0) || (kmLeft != null && kmLeft <= 0)
    val soon = (daysLeft != null && daysLeft <= reminder.warnDays) ||
        (estimatedKmLeft != null && estimatedKmLeft <= reminder.warnKm)

    return ReminderStatus(
        urgency = when {
            overdue -> Urgency.Overdue
            soon -> Urgency.Soon
            else -> Urgency.Ok
        },
        daysLeft = daysLeft,
        kmLeft = kmLeft,
        estimatedKmLeft = estimatedKmLeft,
        estimatedDaysForKm = estimatedDaysForKm
    )
}

/**
 * Midnights between two instants in local time. Rounded, because a day with
 * a clock change in it is 23 or 25 hours long.
 */
fun calendarDaysBetween(from: Long, to: Long): Long {
    fun midnight(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return ((midnight(to) - midnight(from)).toDouble() / DAY_MILLIS).roundToLong()
}

/**
 * The status in words: the date and the distance, each as it stands.
 * "Due in 12 days · in about 1,200 km (about 3 weeks)".
 */
fun describeReminder(reminder: Reminder, status: ReminderStatus, locale: Locale): String {
    val parts = mutableListOf<String>()

    status.daysLeft?.let { days ->
        parts += when {
            days < -1 -> "${-days} days overdue"
            days == -1L -> "1 day overdue"
            days == 0L -> "due today"
            days == 1L -> "due tomorrow"
            days <= 60 -> "due in $days days"
            else -> "due " + SimpleDateFormat("d MMM yyyy", locale).format(Date(reminder.dueDateMillis!!))
        }
    }

    reminder.dueOdometerKm?.let { due ->
        val kmLeft = status.kmLeft
        val estimate = status.estimatedKmLeft
        parts += when {
            // No reading yet: all there is to say is where it is due.
            kmLeft == null -> "at %,d km".format(locale, due)
            kmLeft < 0 -> "%,d km overdue".format(locale, -kmLeft)
            kmLeft == 0 -> "due now by distance"
            estimate != null && estimate <= 0 -> "probably due by distance now"
            else -> buildString {
                append("in about %,d km".format(locale, estimate ?: kmLeft))
                status.estimatedDaysForKm?.let { append(" (about ${roughDuration(it)})") }
            }
        }
    }

    return parts.joinToString(" · ").replaceFirstChar { it.uppercase(locale) }
}

/** "5 days", "3 weeks", "4 months": a time to the nearest sensible unit. */
fun roughDuration(days: Long): String = when {
    days <= 1 -> "a day"
    days < 14 -> "$days days"
    days < 60 -> "${(days / 7.0).roundToLong()} weeks"
    else -> {
        val months = (days / 30.4).roundToLong()
        if (months >= 24) "${(days / 365.0).roundToLong()} years" else "$months months"
    }
}

/**
 * The reminder after it has been done at [doneMillis], with the odometer at
 * [doneOdometerKm] if known: its next due point, counted from then, as asked
 * for - done early, the next one is early too.
 *
 * Null when it does not repeat, or when it repeats by distance only and no
 * reading is known to count from: it is then finished.
 */
fun markDone(reminder: Reminder, doneMillis: Long, doneOdometerKm: Int?): Reminder? {
    val nextDate = reminder.repeatMonths?.let { months ->
        Calendar.getInstance().apply {
            timeInMillis = doneMillis
            add(Calendar.MONTH, months)
        }.timeInMillis
    }
    val nextKm = reminder.repeatKm?.let { km ->
        (doneOdometerKm ?: reminder.dueOdometerKm)?.let { it + km }
    }
    if (nextDate == null && nextKm == null) return null
    return reminder.copy(dueDateMillis = nextDate, dueOdometerKm = nextKm, notifiedStage = 0)
}

/** Whether [status] is a stage [reminder] has not been notified of yet. */
fun shouldNotify(reminder: Reminder, status: ReminderStatus): Boolean =
    status.urgency != Urgency.Ok && status.urgency.ordinal > reminder.notifiedStage

/**
 * Most urgent first, then soonest: whichever of the date and the estimated
 * distance comes first decides how soon.
 */
fun remindersInOrder(items: List<Pair<Reminder, ReminderStatus>>): List<Pair<Reminder, ReminderStatus>> =
    items.sortedWith(
        compareByDescending<Pair<Reminder, ReminderStatus>> { it.second.urgency.ordinal }
            .thenBy { (_, status) ->
                listOfNotNull(status.daysLeft, status.estimatedDaysForKm).minOrNull() ?: Long.MAX_VALUE
            }
    )

/** A reminder needs a date, a distance, or both, or it could never come due. */
fun isValidReminder(dueDateMillis: Long?, dueOdometerKm: Int?): Boolean =
    dueDateMillis != null || dueOdometerKm != null

/** What the reminder dialog holds, as typed. */
data class ReminderInput(
    val type: ReminderType,
    val title: String,
    val dueDateMillis: Long?,
    val dueKm: String,
    val repeatMonths: String,
    val repeatKm: String,
    val warnDays: String,
    val warnKm: String
)

/** A reminder ready to save, or why the input cannot be one. */
data class ReminderResult(val reminder: Reminder?, val error: String?)

/**
 * [input] as a reminder for [carId], replacing [existing] if given.
 *
 * Numbers may be typed with spaces, as "15 000"; blank means none, and the
 * warnings fall back to their defaults. A change to when it is due or how
 * early it warns clears what has been announced, so the new point is
 * announced in its turn; a new title alone does not.
 */
fun reminderFromInput(input: ReminderInput, existing: Reminder?, carId: Int): ReminderResult {
    fun number(text: String, what: String): Pair<Int?, String?> {
        val cleaned = text.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return null to null
        val value = cleaned.toIntOrNull()
        return if (value == null || value <= 0) null to "$what must be a whole number above zero" else value to null
    }

    val (dueKm, dueKmError) = number(input.dueKm, "The distance")
    val (repeatMonths, monthsError) = number(input.repeatMonths, "The months")
    val (repeatKm, repeatKmError) = number(input.repeatKm, "The repeat distance")
    val (warnDays, warnDaysError) = number(input.warnDays, "The days of warning")
    val (warnKm, warnKmError) = number(input.warnKm, "The km of warning")
    listOfNotNull(dueKmError, monthsError, repeatKmError, warnDaysError, warnKmError).firstOrNull()?.let {
        return ReminderResult(null, it)
    }
    if (!isValidReminder(input.dueDateMillis, dueKm)) {
        return ReminderResult(null, "Give a date, a distance, or both")
    }

    val candidate = Reminder(
        id = existing?.id ?: 0,
        carId = carId,
        type = input.type.name,
        title = input.title.trim().ifEmpty { input.type.label },
        dueDateMillis = input.dueDateMillis,
        dueOdometerKm = dueKm,
        repeatMonths = repeatMonths,
        repeatKm = repeatKm,
        warnDays = warnDays ?: DEFAULT_WARN_DAYS,
        warnKm = warnKm ?: DEFAULT_WARN_KM
    )
    val samePoint = existing != null &&
        existing.dueDateMillis == candidate.dueDateMillis &&
        existing.dueOdometerKm == candidate.dueOdometerKm &&
        existing.warnDays == candidate.warnDays &&
        existing.warnKm == candidate.warnKm
    return ReminderResult(
        candidate.copy(notifiedStage = if (samePoint) existing!!.notifiedStage else 0),
        null
    )
}

/** Where a reminder is next due: "23 Sep 2027 or at 29,600 km, whichever comes first". */
fun describeDuePoint(reminder: Reminder, locale: Locale): String {
    val parts = listOfNotNull(
        reminder.dueDateMillis?.let { SimpleDateFormat("d MMM yyyy", locale).format(Date(it)) },
        reminder.dueOdometerKm?.let { "at %,d km".format(locale, it) }
    )
    return when (parts.size) {
        0 -> ""
        1 -> parts.single()
        else -> parts.joinToString(" or ") + ", whichever comes first"
    }
}
