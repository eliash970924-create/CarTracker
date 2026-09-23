package se.eliash.cartracker

import java.util.Calendar

/*
 * A car's year at a glance: what it cost, how far it went, and the moments
 * worth remembering. Built from the same monthly totals and per-fill-up
 * consumption as the rest of the app, so every figure here agrees with the
 * Monthly view and the history list.
 */

/** A fuel's consumption over a year: all its measured fuel over all its measured distance. */
data class FuelConsumption(val fuel: String, val per100Km: Double)

/** The fill-up with the lowest consumption of a fuel that year. */
data class BestTank(val fuel: String, val per100Km: Double, val dateMillis: Long)

/** A month and its figure: the priciest, or the one driven furthest. */
data class MonthFigure(val month: Int, val value: Double)

data class YearSummary(
    val year: Int,
    /** The year in progress: its figures are so far, not final. */
    val partial: Boolean,
    val fuelCost: Double,
    val otherCost: Double,
    val distanceKm: Double,
    val fillUps: Int,
    /** In the car's fuel order. */
    val consumption: List<FuelConsumption>,
    val bestTanks: List<BestTank>,
    val priciestMonth: MonthFigure?,
    val mostDrivenMonth: MonthFigure?
) {
    val totalCost: Double get() = fuelCost + otherCost

    /** Running cost in kr/mil, or null for a year with no distance to divide by. */
    val costPerMil: Double? get() = if (distanceKm > 0) totalCost / distanceKm * 10 else null
}

/**
 * Every year with anything in it, newest first; the current one marked as
 * partial.
 *
 * Consumption is total fuel over total distance, the same way as the
 * dashboard, not an average of each tank's figure - a short tank would
 * otherwise count as much as a long one. A tank belongs to the year it was
 * filled in. Readings that do not fit their dates are left out, as
 * everywhere else.
 */
fun yearSummaries(
    car: Car,
    fuelHistoryNewestFirst: List<FuelUp>,
    expenses: List<Expense>,
    now: Calendar
): List<YearSummary> {
    val totals = monthTotals(fuelHistoryNewestFirst, expenses)
    val trend = consumptionTrend(fuelHistoryNewestFirst, car.initialOdometer)
    val thisYear = now.get(Calendar.YEAR)
    val carFuels = listOfNotNull(car.fuelType, car.secondaryFuelType)

    fun yearOf(millis: Long) = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR)

    val years = (totals.fuel.keys + totals.expenses.keys + totals.distance.keys)
        .map { it / 12 }
        .distinct()
        // sortedDescending, not a sorted set's reversed(): from Android 15
        // that resolves to a platform method older phones do not have.
        .sortedDescending()

    return years.mapNotNull { year ->
        val months = (year * 12) until (year * 12 + 12)
        fun sum(map: Map<Int, Double>) = months.sumOf { map[it] ?: 0.0 }

        val fuelCost = sum(totals.fuel)
        val otherCost = sum(totals.expenses)
        val distance = sum(totals.distance)
        if (fuelCost == 0.0 && otherCost == 0.0 && distance == 0.0) return@mapNotNull null

        val fillUps = fuelHistoryNewestFirst.filter { yearOf(it.dateMillis) == year }

        // Each measured tank's fuel and the distance it covered, by fuel.
        val measured = fillUps.mapNotNull { entry ->
            trend[entry.id]?.value?.takeIf { it > 0 }?.let { value -> Triple(entry, value, entry.litersFilled * 100 / value) }
        }
        val fuelsInOrder = (carFuels + measured.map { it.first.fuelTypeUsed }).distinct()

        val consumption = fuelsInOrder.mapNotNull { fuel ->
            val forFuel = measured.filter { it.first.fuelTypeUsed == fuel }
            val km = forFuel.sumOf { it.third }
            if (km <= 0) null else FuelConsumption(fuel, forFuel.sumOf { it.first.litersFilled } / km * 100)
        }
        val bestTanks = fuelsInOrder.mapNotNull { fuel ->
            measured.filter { it.first.fuelTypeUsed == fuel }
                .minWithOrNull(compareBy<Triple<FuelUp, Double, Double>>({ it.second }, { it.first.dateMillis }))
                ?.let { (entry, value, _) -> BestTank(fuel, value, entry.dateMillis) }
        }

        fun peak(value: (Int) -> Double): MonthFigure? =
            months.map { MonthFigure(it % 12, value(it)) }
                .filter { it.value > 0 }
                .maxWithOrNull(compareBy<MonthFigure> { it.value }.thenByDescending { it.month })

        YearSummary(
            year = year,
            partial = year == thisYear,
            fuelCost = fuelCost,
            otherCost = otherCost,
            distanceKm = distance,
            fillUps = fillUps.size,
            consumption = consumption,
            bestTanks = bestTanks,
            priciestMonth = peak { (totals.fuel[it] ?: 0.0) + (totals.expenses[it] ?: 0.0) },
            mostDrivenMonth = peak { totals.distance[it] ?: 0.0 }
        )
    }
}

/**
 * How [current]'s running cost compares with [previous]'s, in percent:
 * positive is dearer. Null when either has no distance to compare by.
 */
fun costPerMilChange(current: YearSummary, previous: YearSummary?): Double? {
    val now = current.costPerMil ?: return null
    val before = previous?.costPerMil?.takeIf { it > 0 } ?: return null
    return (now - before) / before * 100
}
