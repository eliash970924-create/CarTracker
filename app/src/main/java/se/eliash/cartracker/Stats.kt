package se.eliash.cartracker

/**
 * The figures shown on the Log & History dashboard.
 *
 * Consumption is per 100 km; cost is kr/mil, i.e. per 10 km, which is how
 * running costs are quoted in Sweden.
 *
 * For a bifuel or plug-in hybrid the two fuels are measured over overlapping
 * distance, each against its own odometer trail, so [blendedCost] adds the two
 * per-distance costs rather than averaging them.
 */
data class FuelStats(
    val avgPrimary: Double,
    val avgSecondary: Double,
    val costPrimary: Double,
    val costSecondary: Double,
    val blendedCost: Double
)

/** The series plotted on the Charts tab. */
data class ChartSeries(
    val primaryPrices: List<Double>,
    val secondaryPrices: List<Double>,
    val primaryConsumption: List<Double>,
    val secondaryConsumption: List<Double>
)

/**
 * Totals for the dashboard, walked in date order so each fill-up is measured
 * against the previous one for the same fuel.
 *
 * A fill-up marked missedPrevious contributes nothing: the distance since the
 * last recorded one covers unknown fuel, so counting it would understate
 * consumption. Its odometer still advances the trail.
 *
 * The trail starts at the car's initial odometer, so the very first fill-up
 * counts. [calculateChartSeries] deliberately starts from the first fill-up
 * instead; see the note there.
 */
fun calculateFuelStats(car: Car, fuelUps: List<FuelUp>): FuelStats {
    var primaryLiters = 0.0
    var primaryCost = 0.0
    var primaryDistance = 0

    var secondaryLiters = 0.0
    var secondaryCost = 0.0
    var secondaryDistance = 0

    var lastPrimaryOdo = car.initialOdometer
    var lastSecondaryOdo = car.initialOdometer

    fuelUps.sortedBy { it.dateMillis }.forEach { fuelUp ->
        if (fuelUp.odometerKm <= 0) return@forEach
        when (fuelUp.fuelTypeUsed) {
            car.fuelType -> {
                val distance = fuelUp.odometerKm - lastPrimaryOdo
                if (distance > 0 && !fuelUp.missedPrevious) {
                    primaryDistance += distance
                    primaryLiters += fuelUp.litersFilled
                    primaryCost += fuelUp.totalCostSek
                }
                lastPrimaryOdo = fuelUp.odometerKm
            }
            car.secondaryFuelType -> {
                val distance = fuelUp.odometerKm - lastSecondaryOdo
                if (distance > 0 && !fuelUp.missedPrevious) {
                    secondaryDistance += distance
                    secondaryLiters += fuelUp.litersFilled
                    secondaryCost += fuelUp.totalCostSek
                }
                lastSecondaryOdo = fuelUp.odometerKm
            }
        }
    }

    val costPrimary = if (primaryDistance > 0) (primaryCost / primaryDistance) * 10 else 0.0
    val costSecondary = if (secondaryDistance > 0) (secondaryCost / secondaryDistance) * 10 else 0.0

    return FuelStats(
        avgPrimary = if (primaryDistance > 0) (primaryLiters / primaryDistance) * 100 else 0.0,
        avgSecondary = if (secondaryDistance > 0) (secondaryLiters / secondaryDistance) * 100 else 0.0,
        costPrimary = costPrimary,
        costSecondary = costSecondary,
        blendedCost = costPrimary + costSecondary
    )
}

/**
 * Per-fill-up series for the charts.
 *
 * Unlike [calculateFuelStats] the odometer trail starts empty rather than at
 * the car's initial reading, so the first fill-up of each fuel produces no
 * consumption point. That is deliberate: a chart point needs a measured
 * interval between two fill-ups, whereas the dashboard total can reasonably
 * count distance from the odometer the car was registered with.
 */
fun calculateChartSeries(car: Car, fuelUps: List<FuelUp>): ChartSeries {
    val chronological = fuelUps.sortedBy { it.dateMillis }

    val primaryConsumption = mutableListOf<Double>()
    val secondaryConsumption = mutableListOf<Double>()

    var lastPrimaryOdo: Int? = null
    var lastSecondaryOdo: Int? = null

    chronological.forEach { fuelUp ->
        if (fuelUp.odometerKm <= 0) return@forEach
        when (fuelUp.fuelTypeUsed) {
            car.fuelType -> {
                lastPrimaryOdo?.let { previous ->
                    val distance = fuelUp.odometerKm - previous
                    if (distance > 0 && !fuelUp.missedPrevious) {
                        primaryConsumption.add((fuelUp.litersFilled / distance) * 100)
                    }
                }
                lastPrimaryOdo = fuelUp.odometerKm
            }
            car.secondaryFuelType -> {
                lastSecondaryOdo?.let { previous ->
                    val distance = fuelUp.odometerKm - previous
                    if (distance > 0 && !fuelUp.missedPrevious) {
                        secondaryConsumption.add((fuelUp.litersFilled / distance) * 100)
                    }
                }
                lastSecondaryOdo = fuelUp.odometerKm
            }
        }
    }

    return ChartSeries(
        primaryPrices = chronological.filter { it.fuelTypeUsed == car.fuelType }.map { it.pricePerLiterSek },
        secondaryPrices = chronological.filter { it.fuelTypeUsed == car.secondaryFuelType }.map { it.pricePerLiterSek },
        primaryConsumption = primaryConsumption,
        secondaryConsumption = secondaryConsumption
    )
}

/**
 * Consumption for one fill-up, as shown beside it in the history list, or null
 * when it cannot be worked out.
 *
 * Measured against the previous fill-up of the same fuel, falling back to the
 * car's initial odometer when there is no earlier one. Returns null for a
 * fill-up with no odometer reading, one marked as following a missed fill-up,
 * or one that did not advance the odometer - in each case the distance the
 * fuel covered is unknown, and a figure would be a guess presented as fact.
 *
 * [olderEntries] are the entries before this one, newest first, matching the
 * order the history list holds.
 */
fun consumptionForEntry(
    entry: FuelUp,
    olderEntries: List<FuelUp>,
    initialOdometer: Int
): Double? {
    if (entry.odometerKm <= 0 || entry.missedPrevious) return null

    val previousOdometer = olderEntries
        .firstOrNull { it.odometerKm > 0 && it.fuelTypeUsed == entry.fuelTypeUsed }
        ?.odometerKm
        ?: initialOdometer
    if (previousOdometer <= 0) return null

    val distance = entry.odometerKm - previousOdometer
    if (distance <= 0) return null

    return (entry.litersFilled / distance) * 100
}
