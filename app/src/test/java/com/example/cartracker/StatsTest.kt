package com.example.cartracker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dashboard and chart figures, which were previously computed inside a
 * composable and could not be tested at all.
 */
class StatsTest {

    private val delta = 0.0001

    private fun car(
        fuelType: String = "Petrol",
        secondary: String? = null,
        initialOdometer: Int = 1000
    ) = Car(
        id = 1,
        name = "Test",
        fuelType = fuelType,
        secondaryFuelType = secondary,
        initialOdometer = initialOdometer
    )

    private fun fill(
        date: Long,
        odometer: Int,
        fuel: String = "Petrol",
        liters: Double = 40.0,
        price: Double = 18.0,
        total: Double = 720.0,
        missed: Boolean = false
    ) = FuelUp(
        carId = 1,
        fuelTypeUsed = fuel,
        dateMillis = date,
        odometerKm = odometer,
        litersFilled = liters,
        pricePerLiterSek = price,
        totalCostSek = total,
        missedPrevious = missed
    )

    @Test
    fun `first fill-up is measured from the car's initial odometer`() {
        val stats = calculateFuelStats(car(), listOf(fill(1, 1500)))
        // 40 L over 1500-1000 km
        assertEquals(8.0, stats.avgPrimary, delta)
        // 720 kr over 500 km, quoted per 10 km
        assertEquals(14.4, stats.costPrimary, delta)
        assertEquals(14.4, stats.blendedCost, delta)
    }

    @Test
    fun `a missed fill-up is excluded but still advances the odometer`() {
        val stats = calculateFuelStats(
            car(),
            listOf(
                fill(1, 1500, liters = 40.0, total = 720.0),
                fill(2, 2000, liters = 50.0, total = 900.0, missed = true),
                fill(3, 2500, liters = 30.0, total = 600.0)
            )
        )
        // Only the first and third count: 70 L and 1320 kr over 1000 km.
        // The missed one is skipped, but the third still measures from 2000,
        // not from 1500 - otherwise its distance would be double-counted.
        assertEquals(7.0, stats.avgPrimary, delta)
        assertEquals(13.2, stats.costPrimary, delta)
    }

    @Test
    fun `entries without an odometer reading are ignored`() {
        val stats = calculateFuelStats(car(), listOf(fill(1, 0)))
        assertEquals(0.0, stats.avgPrimary, delta)
        assertEquals(0.0, stats.costPrimary, delta)
    }

    @Test
    fun `each fuel of a hybrid is measured separately and the costs add up`() {
        val stats = calculateFuelStats(
            car(secondary = "Electric", initialOdometer = 0),
            listOf(
                fill(1, 100, fuel = "Petrol", liters = 10.0, price = 20.0, total = 200.0),
                fill(2, 200, fuel = "Electric", liters = 20.0, price = 2.5, total = 50.0)
            )
        )
        assertEquals(10.0, stats.avgPrimary, delta)
        assertEquals(10.0, stats.avgSecondary, delta)
        assertEquals(20.0, stats.costPrimary, delta)
        assertEquals(2.5, stats.costSecondary, delta)
        // The two fuels cover overlapping distance, so the blended running
        // cost is their sum rather than their average.
        assertEquals(22.5, stats.blendedCost, delta)
    }

    @Test
    fun `no history gives zeroes rather than a division by zero`() {
        val stats = calculateFuelStats(car(), emptyList())
        assertEquals(0.0, stats.avgPrimary, delta)
        assertEquals(0.0, stats.avgSecondary, delta)
        assertEquals(0.0, stats.blendedCost, delta)
    }

    @Test
    fun `charts skip the first fill-up because it has no measured interval`() {
        val series = calculateChartSeries(
            car(),
            listOf(
                fill(1, 1500, liters = 40.0, price = 18.0),
                fill(2, 2000, liters = 50.0, price = 18.0, missed = true),
                fill(3, 2500, liters = 30.0, price = 20.0)
            )
        )
        // Unlike the dashboard, the chart has no previous reading to measure
        // the first fill-up against, and the second is marked missed.
        assertEquals(listOf(6.0), series.primaryConsumption)
        // Prices are plotted for every entry regardless.
        assertEquals(listOf(18.0, 18.0, 20.0), series.primaryPrices)
        assertEquals(emptyList<Double>(), series.secondaryConsumption)
    }

    @Test
    fun `entries are ordered by date, not by the order they were added`() {
        val outOfOrder = listOf(fill(3, 2500, liters = 30.0), fill(1, 1500, liters = 40.0))
        val inOrder = listOf(fill(1, 1500, liters = 40.0), fill(3, 2500, liters = 30.0))
        assertEquals(
            calculateFuelStats(car(), inOrder).avgPrimary,
            calculateFuelStats(car(), outOfOrder).avgPrimary,
            delta
        )
    }

    @Test
    fun `history consumption is measured against the previous fill-up of the same fuel`() {
        val entry = fill(3, 2500, liters = 30.0)
        val older = listOf(fill(2, 2000), fill(1, 1500))
        // 30 L over 2500-2000 km
        assertEquals(6.0, consumptionForEntry(entry, older, initialOdometer = 1000)!!, delta)
    }

    @Test
    fun `the oldest fill-up falls back to the car's initial odometer`() {
        val entry = fill(1, 1500, liters = 40.0)
        assertEquals(8.0, consumptionForEntry(entry, emptyList(), initialOdometer = 1000)!!, delta)
    }

    @Test
    fun `a missed fill-up reports no consumption rather than a wrong one`() {
        val entry = fill(2, 2000, liters = 50.0, missed = true)
        assertEquals(null, consumptionForEntry(entry, listOf(fill(1, 1500)), initialOdometer = 1000))
    }

    @Test
    fun `an entry with no odometer reading reports no consumption`() {
        assertEquals(null, consumptionForEntry(fill(2, 0), listOf(fill(1, 1500)), initialOdometer = 1000))
    }

    @Test
    fun `an odometer that did not advance reports no consumption`() {
        val entry = fill(2, 1500)
        assertEquals(null, consumptionForEntry(entry, listOf(fill(1, 1500)), initialOdometer = 1000))
    }

    @Test
    fun `a different fuel in between is skipped over`() {
        val entry = fill(3, 2500, fuel = "Petrol", liters = 30.0)
        // The electric fill-up between them must not become the reference point.
        val older = listOf(fill(2, 2200, fuel = "Electric"), fill(1, 2000, fuel = "Petrol"))
        assertEquals(6.0, consumptionForEntry(entry, older, initialOdometer = 1000)!!, delta)
    }
}
