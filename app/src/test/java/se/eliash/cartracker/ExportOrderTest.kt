package se.eliash.cartracker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Settings exports read a car's history from the database, not from the
 * screen, so it has to come out in the same order the screen shows it -
 * otherwise a CSV exported from Settings would read differently from one
 * exported from the car.
 */
class ExportOrderTest {

    private fun fill(id: Int, day: Long, odometer: Int) = FuelUp(
        id = id, carId = 1, fuelTypeUsed = "Petrol", dateMillis = day * 86_400_000L,
        odometerKm = odometer, litersFilled = 40.0, pricePerLiterSek = 18.0,
        totalCostSek = 720.0, missedPrevious = false
    )

    private fun expense(id: Int, day: Long) = Expense(
        id = id, carId = 1, dateMillis = day * 86_400_000L, category = "Other",
        description = "", costSek = 100.0
    )

    @Test
    fun `fill-ups come out newest first`() {
        val rows = listOf(fill(1, day = 10, odometer = 1000), fill(2, day = 30, odometer = 3000), fill(3, day = 20, odometer = 2000))
        assertEquals(listOf(2, 3, 1), inHistoryOrder(rows).map { it.id })
    }

    @Test
    fun `two fill-ups on one day are ordered by odometer, highest first`() {
        // The history query's second sort key: the later fill-up of the day
        // has the higher reading.
        val rows = listOf(fill(1, day = 5, odometer = 1200), fill(2, day = 5, odometer = 1500))
        assertEquals(listOf(2, 1), inHistoryOrder(rows).map { it.id })
    }

    @Test
    fun `expenses come out newest first`() {
        val rows = listOf(expense(1, day = 3), expense(2, day = 9), expense(3, day = 6))
        assertEquals(listOf(2, 3, 1), expensesInHistoryOrder(rows).map { it.id })
    }

    @Test
    fun `nothing logged exports nothing, without failing`() {
        assertEquals(emptyList<FuelUp>(), inHistoryOrder(emptyList()))
        assertEquals(emptyList<Expense>(), expensesInHistoryOrder(emptyList()))
    }
}
