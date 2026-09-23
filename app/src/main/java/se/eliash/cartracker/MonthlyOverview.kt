package se.eliash.cartracker

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
    val distanceKm: Double,
    /** [fuelCost] by fuel, keyed as the fill-ups name it: "Petrol", "Electric". */
    val fuelCostByType: Map<String, Double> = emptyMap()
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

/** Month ordinal - year * 12 + 0-based month - of a timestamp, in local time. */
fun monthOrdinal(millis: Long): Int = monthOrdinalOf(millis)

/** Cost and distance per month, keyed by [monthOrdinal], every month with any. */
class MonthTotals(
    val fuel: Map<Int, Double>,
    val fuelByType: Map<Int, Map<String, Double>>,
    val expenses: Map<Int, Double>,
    val distance: Map<Int, Double>
)

/**
 * The sums the monthly and yearly views are both built from, so the two can
 * never disagree: costs by the month they were paid, distance spread over the
 * days between readings. Readings that do not fit their dates are left out of
 * the distance; see withTrustedReadings.
 */
fun monthTotals(fuelUps: List<FuelUp>, expenses: List<Expense>): MonthTotals {
    // Costs count every fill-up; distance only readings that fit their dates.
    val trusted = withTrustedReadings(fuelUps)
    val fuelByMonth = mutableMapOf<Int, Double>()
    val fuelByMonthAndType = mutableMapOf<Int, MutableMap<String, Double>>()
    val expensesByMonth = mutableMapOf<Int, Double>()
    val distanceByMonth = mutableMapOf<Int, Double>()

    trusted.forEach {
        val month = monthOrdinalOf(it.dateMillis)
        fuelByMonth.merge(month, it.totalCostSek, Double::plus)
        fuelByMonthAndType.getOrPut(month) { mutableMapOf() }.merge(it.fuelTypeUsed, it.totalCostSek, Double::plus)
    }
    expenses.forEach { expensesByMonth.merge(monthOrdinalOf(it.dateMillis), it.costSek, Double::plus) }

    trusted
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
    return MonthTotals(fuelByMonth, fuelByMonthAndType, expensesByMonth, distanceByMonth)
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
    val totals = monthTotals(fuelUps, expenses)
    val fuelByMonth = totals.fuel
    val fuelByMonthAndType = totals.fuelByType
    val expensesByMonth = totals.expenses
    val distanceByMonth = totals.distance

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
            distanceKm = distanceByMonth[ordinal] ?: 0.0,
            fuelCostByType = fuelByMonthAndType[ordinal] ?: emptyMap()
        )
    }

    return MonthlyOverview(
        months = months,
        averageMonthlyCost = months.sumOf { it.totalCost } / months.size,
        averageMonthlyDistance = months.sumOf { it.distanceKm } / months.size
    )
}

/** One part of a month's cost: a fuel, or everything else. */
data class CostPart(val label: String, val amount: Double)

/** What the part that is not a fuel is called. */
const val OTHER_COSTS = "Other costs"

/**
 * A month's cost in parts: each of the car's fuels, in the car's order, then
 * everything else.
 *
 * Every month gets the same parts, zeroes included, so a colour means the
 * same thing in every bar. Fuel of a type the car no longer lists - its fuels
 * were changed after it was logged - goes under [OTHER_COSTS] with the
 * expenses: a part of its own would appear in some months and not others.
 *
 * Distance is not split. A plug-in hybrid's odometer counts every kilometre
 * whichever fuel drove it, so there is no honest per-fuel distance to show.
 */
fun costBreakdown(month: MonthSummary, carFuels: List<String>): List<CostPart> {
    val fuels = carFuels.distinct()
    val fuelParts = fuels.map { CostPart(it, month.fuelCostByType[it] ?: 0.0) }
    val unlisted = month.fuelCostByType.filterKeys { it !in fuels }.values.sum()
    return fuelParts + CostPart(OTHER_COSTS, month.expenseCost + unlisted)
}

/**
 * The average month in the same parts as [costBreakdown]: each part's total
 * over the same months the overall average uses, so the parts add up to it.
 */
fun averageCostBreakdown(months: List<MonthSummary>, carFuels: List<String>): List<CostPart> {
    if (months.isEmpty()) return emptyList()
    val perMonth = months.map { costBreakdown(it, carFuels) }
    return perMonth.first().indices.map { i ->
        CostPart(perMonth.first()[i].label, perMonth.sumOf { it[i].amount } / months.size)
    }
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

/**
 * Heights of a stacked bar's parts, in the order given, for a bar [total]
 * tall with [gap] between neighbouring parts.
 *
 * A part with nothing in it gets no height and no gap. The gaps come out of
 * the bar rather than being added to it, so a stacked bar is exactly as tall
 * as the same total drawn plain. A part too small to see is still drawn
 * [minimum] tall: a sliver says "a little", nothing says "none".
 */
fun stackHeights(amounts: List<Double>, total: Float, gap: Float, minimum: Float = 1f): List<Float> {
    val shown = amounts.count { it > 0 }
    val sum = amounts.filter { it > 0 }.sum()
    if (shown == 0 || sum <= 0.0) return amounts.map { 0f }
    val available = (total - gap * (shown - 1)).coerceAtLeast(0f)
    return amounts.map { amount ->
        if (amount > 0) (available * (amount / sum)).toFloat().coerceAtLeast(minimum) else 0f
    }
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
