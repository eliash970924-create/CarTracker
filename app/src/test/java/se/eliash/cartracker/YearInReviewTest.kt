package se.eliash.cartracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class YearInReviewTest {

    private fun at(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply { clear(); set(year, month, day, 12, 0) }.timeInMillis

    private fun fill(
        id: Int, date: Long, odometer: Int, liters: Double,
        fuel: String = "Petrol", price: Double = 18.0
    ) = FuelUp(
        id = id, carId = 1, fuelTypeUsed = fuel, dateMillis = date, odometerKm = odometer,
        litersFilled = liters, pricePerLiterSek = price, totalCostSek = liters * price, missedPrevious = false
    )

    private fun expense(date: Long, cost: Double) =
        Expense(carId = 1, dateMillis = date, category = "Insurance", description = "", costSek = cost)

    private val car = Car(id = 1, name = "Volvo", fuelType = "Petrol", initialOdometer = 10_000)
    private val now = Calendar.getInstance().apply { clear(); set(2026, Calendar.MAY, 1) }

    // Newest first, as the history holds it. From 10,000 km: 6.0, 5.0, then 6.0.
    private val history = listOf(
        fill(3, at(2026, Calendar.FEBRUARY, 10), 11_600, 36.0),
        fill(2, at(2025, Calendar.JUNE, 10), 11_000, 25.0),
        fill(1, at(2025, Calendar.JANUARY, 10), 10_500, 30.0)
    )
    private val expenses = listOf(expense(at(2025, Calendar.MARCH, 5), 1000.0))

    private val years = yearSummaries(car, history, expenses, now)

    @Test
    fun `every year with anything in it, newest first, this one marked so far`() {
        assertEquals(listOf(2026, 2025), years.map { it.year })
        assertTrue(years[0].partial)
        assertFalse(years[1].partial)
    }

    @Test
    fun `costs are the year's own`() {
        val y2025 = years[1]
        assertEquals((30.0 + 25.0) * 18, y2025.fuelCost, 1e-9)
        assertEquals(1000.0, y2025.otherCost, 1e-9)
        assertEquals(2, y2025.fillUps)
        assertEquals(36.0 * 18, years[0].fuelCost, 1e-9)
    }

    @Test
    fun `distance is shared between the years a tank spans, none lost`() {
        // 500 km in the first half of 2025, then 600 km from June 2025 to
        // February 2026: most of it in 2025.
        assertEquals(1_100.0, years.sumOf { it.distanceKm }, 0.01)
        assertTrue(years[1].distanceKm > 500 && years[1].distanceKm < 1_100)
    }

    @Test
    fun `consumption is total fuel over total distance, not an average of tanks`() {
        // 55 L over 1,000 km; the tanks were 6.0 and 5.0.
        assertEquals(5.5, years[1].consumption.single().per100Km, 1e-9)
        assertEquals("Petrol", years[1].consumption.single().fuel)
    }

    @Test
    fun `the year's best tank, with its date`() {
        val best = years[1].bestTanks.single()
        assertEquals(5.0, best.per100Km, 1e-9)
        assertEquals(at(2025, Calendar.JUNE, 10), best.dateMillis)
    }

    @Test
    fun `the priciest month counts expenses too`() {
        assertEquals(Calendar.MARCH, years[1].priciestMonth!!.month)
        assertEquals(1000.0, years[1].priciestMonth!!.value, 1e-9)
    }

    @Test
    fun `cost per mil, and how it changed`() {
        fun summary(cost: Double, km: Double) = YearSummary(
            2025, false, cost, 0.0, km, 1, emptyList(), emptyList(), null, null
        )
        val before = summary(10_000.0, 10_000.0)   // 10 kr/mil
        val after = summary(11_000.0, 10_000.0)    // 11 kr/mil
        assertEquals(10.0, before.costPerMil!!, 1e-9)
        assertEquals(10.0, costPerMilChange(after, before)!!, 1e-9)
        assertNull("no year before", costPerMilChange(after, null))
        assertNull("no distance to compare by", costPerMilChange(after, summary(500.0, 0.0)))
    }

    @Test
    fun `a year with only expenses is still there`() {
        val onlyExpense = yearSummaries(car, emptyList(), listOf(expense(at(2024, Calendar.MAY, 1), 300.0)), now)
        assertEquals(listOf(2024), onlyExpense.map { it.year })
        assertNull(onlyExpense.single().costPerMil)
        assertTrue(onlyExpense.single().consumption.isEmpty())
    }

    @Test
    fun `nothing logged, no years`() {
        assertTrue(yearSummaries(car, emptyList(), emptyList(), now).isEmpty())
    }

    @Test
    fun `a hybrid's fuels are each measured, in the car's order`() {
        val hybrid = car.copy(secondaryFuelType = "Electric")
        val fills = listOf(
            fill(4, at(2025, Calendar.APRIL, 1), 10_800, 24.0, fuel = "Electric", price = 1.5),  // 800 km: 3.0
            fill(1, at(2025, Calendar.JANUARY, 10), 10_500, 30.0)                                // 500 km: 6.0
        )
        val y = yearSummaries(hybrid, fills, emptyList(), now).single()
        assertEquals(listOf("Petrol", "Electric"), y.consumption.map { it.fuel })
        assertEquals(3.0, y.consumption[1].per100Km, 1e-9)
    }

    // --- the remembered price ---

    @Test
    fun `the last price paid is for the chosen fuel, newest first`() {
        val fills = listOf(
            fill(1, at(2025, Calendar.JANUARY, 1), 10_500, 30.0, price = 17.5),
            fill(2, at(2025, Calendar.FEBRUARY, 1), 11_000, 30.0, price = 18.25),
            fill(3, at(2025, Calendar.MARCH, 1), 11_100, 20.0, fuel = "Electric", price = 1.25)
        )
        assertEquals(18.25, lastPriceFor(fills, "Petrol")!!, 1e-9)
        assertEquals("a home charging rate is remembered too", 1.25, lastPriceFor(fills, "Electric")!!, 1e-9)
        assertNull(lastPriceFor(fills, "Diesel"))
    }

    @Test
    fun `a fill-up without a price is passed over`() {
        val fills = listOf(
            fill(1, at(2025, Calendar.JANUARY, 1), 10_500, 30.0, price = 17.5),
            fill(2, at(2025, Calendar.FEBRUARY, 1), 11_000, 30.0, price = 0.0)
        )
        assertEquals(17.5, lastPriceFor(fills, "Petrol")!!, 1e-9)
    }

    @Test
    fun `prices are shown as typed on a Swedish keyboard`() {
        assertEquals("18,49", priceText(18.49))
        assertEquals("2,5", priceText(2.5))
        assertEquals("3", priceText(3.0))
        assertEquals("30", priceText(30.0))
        assertEquals("2", priceText(1.999))
    }
}
