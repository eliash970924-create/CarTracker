package com.example.cartracker

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** What one calendar month cost and how far it was driven. */
data class MonthSummary(
    val year: Int,
    /** 0-based, matching Calendar.MONTH. */
    val month: Int,
    val fuelCost: Double,
    val expenseCost: Double,
    val distanceKm: Double
) {
    val totalCost: Double get() = fuelCost + expenseCost

    /** Running cost in kr/mil, or null when the month recorded no distance. */
    val costPerMil: Double? get() =
        if (distanceKm > 0) (totalCost / distanceKm) * 10 else null
}

data class MonthlyOverview(
    /** Oldest first, with no gaps: a quiet month is present with zeroes. */
    val months: List<MonthSummary>,
    val averageMonthlyCost: Double,
    val averageMonthlyDistance: Double
)

/** Months are compared as a single number so a range can be walked. */
private fun monthOrdinalOf(calendar: Calendar): Int =
    calendar.get(Calendar.YEAR) * 12 + calendar.get(Calendar.MONTH)

private fun monthOrdinalOf(millis: Long): Int =
    monthOrdinalOf(Calendar.getInstance().apply { timeInMillis = millis })

/**
 * A gap longer than this is treated as degenerate rather than walked a day at
 * a time. Ten years between two fill-ups means a mistyped date, not driving.
 */
private const val MAX_SPREAD_DAYS = 3650

/**
 * Spreads a distance evenly across the days it spans, so a tank that straddles
 * a month boundary is shared between the two months rather than landing
 * entirely in the later one.
 *
 * Even driving is an assumption, but a mild one, and it stops the timing of a
 * fill-up deciding how a month looks.
 */
private fun spreadAcrossMonths(
    fromMillis: Long,
    toMillis: Long,
    distanceKm: Double,
    into: MutableMap<Int, Double>
) {
    val dayMonths = mutableListOf<Int>()
    val cursor = Calendar.getInstance().apply { timeInMillis = fromMillis }

    while (cursor.timeInMillis < toMillis && dayMonths.size < MAX_SPREAD_DAYS) {
        dayMonths.add(monthOrdinalOf(cursor))
        // Calendar's own arithmetic, so a daylight-saving change does not
        // shift the count.
        cursor.add(Calendar.DAY_OF_MONTH, 1)
    }

    // Two fill-ups on the same day, or a span too long to be real: the whole
    // distance goes to the month it was recorded in.
    if (dayMonths.isEmpty() || dayMonths.size >= MAX_SPREAD_DAYS) {
        into.merge(monthOrdinalOf(toMillis), distanceKm, Double::plus)
        return
    }

    val perDay = distanceKm / dayMonths.size
    dayMonths.forEach { into.merge(it, perDay, Double::plus) }
}

/**
 * What the car has cost and covered, month by month.
 *
 * Every month between the first record and the last complete one is present,
 * quiet ones included: insurance is still owed in a month nothing was driven,
 * so leaving those out would overstate the average.
 *
 * The current month is excluded. It is only part-way through, and counting it
 * would drag the average down by however many days are left in it.
 *
 * Distance uses the car's odometer however it was fuelled, unlike the
 * consumption figures, which follow each fuel's own trail. A missed fill-up
 * is not skipped either: the fuel is unknown, but the distance was still
 * driven, and this is a question about distance.
 *
 * The very first fill-up contributes no distance. The car's initial odometer
 * has no date, so there is no span to spread its distance over.
 */
fun monthlyOverview(
    fuelUps: List<FuelUp>,
    expenses: List<Expense>,
    now: Calendar
): MonthlyOverview {
    val fuelByMonth = mutableMapOf<Int, Double>()
    val expensesByMonth = mutableMapOf<Int, Double>()
    val distanceByMonth = mutableMapOf<Int, Double>()

    fuelUps.forEach { fuelByMonth.merge(monthOrdinalOf(it.dateMillis), it.totalCostSek, Double::plus) }
    expenses.forEach { expensesByMonth.merge(monthOrdinalOf(it.dateMillis), it.costSek, Double::plus) }

    fuelUps
        .filter { it.odometerKm > 0 }
        .sortedBy { it.dateMillis }
        .zipWithNext()
        .forEach { (previous, current) ->
            val distance = current.odometerKm - previous.odometerKm
            if (distance > 0) {
                spreadAcrossMonths(
                    previous.dateMillis,
                    current.dateMillis,
                    distance.toDouble(),
                    distanceByMonth
                )
            }
        }

    val firstRecord = (fuelUps.map { it.dateMillis } + expenses.map { it.dateMillis }).minOrNull()
        ?: return MonthlyOverview(emptyList(), 0.0, 0.0)

    val firstMonth = monthOrdinalOf(firstRecord)
    val lastCompleteMonth = monthOrdinalOf(now) - 1
    if (lastCompleteMonth < firstMonth) return MonthlyOverview(emptyList(), 0.0, 0.0)

    val months = (firstMonth..lastCompleteMonth).map { ordinal ->
        MonthSummary(
            year = ordinal / 12,
            month = ordinal % 12,
            fuelCost = fuelByMonth[ordinal] ?: 0.0,
            expenseCost = expensesByMonth[ordinal] ?: 0.0,
            distanceKm = distanceByMonth[ordinal] ?: 0.0
        )
    }

    return MonthlyOverview(
        months = months,
        averageMonthlyCost = months.sumOf { it.totalCost } / months.size,
        averageMonthlyDistance = months.sumOf { it.distanceKm } / months.size
    )
}

/**
 * Each value as a fraction of the largest, for drawing bars.
 *
 * Scaled from zero rather than from the smallest value, so a month that cost
 * half as much is drawn half as tall. A run of zeroes gives zero-height bars
 * rather than dividing by zero.
 */
fun barFractions(values: List<Double>): List<Float> {
    val max = values.maxOrNull() ?: 0.0
    if (max <= 0.0) return values.map { 0f }
    return values.map { (it / max).coerceIn(0.0, 1.0).toFloat() }
}

/** "mar 2026", in the given locale. */
fun monthLabel(summary: MonthSummary, locale: Locale): String =
    SimpleDateFormat("MMM yyyy", locale).format(monthStart(summary).time)

/** Just "mar", for a bar too narrow to carry the year. */
fun shortMonthLabel(summary: MonthSummary, locale: Locale): String =
    SimpleDateFormat("MMM", locale).format(monthStart(summary).time)

private fun monthStart(summary: MonthSummary): Calendar =
    Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, summary.year)
        set(Calendar.MONTH, summary.month)
        set(Calendar.DAY_OF_MONTH, 1)
    }
