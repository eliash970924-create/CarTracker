package se.eliash.cartracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumptionTrendTest {

    private val day = 24L * 60 * 60 * 1000

    private fun fill(
        id: Int, day: Int, odometer: Int, amount: Double,
        fuel: String = "Petrol", missed: Boolean = false
    ) = FuelUp(
        id = id, carId = 1, fuelTypeUsed = fuel, dateMillis = day * this.day, odometerKm = odometer,
        litersFilled = amount, pricePerLiterSek = 18.0, totalCostSek = amount * 18, missedPrevious = missed
    )

    /** The history list's order: newest first. */
    private fun history(vararg fills: FuelUp) = fills.sortedByDescending { it.dateMillis }

    @Test
    fun `each fill-up compares with the one before`() {
        // From 10,000: 6.0, then 5.5 (better), then 7.0 (worse) per 100 km.
        val trend = consumptionTrend(
            history(
                fill(1, 1, 10_500, 30.0),
                fill(2, 2, 11_000, 27.5),
                fill(3, 3, 11_500, 35.0)
            ),
            initialOdometer = 10_000
        )
        assertEquals(6.0, trend[1]!!.value!!, 1e-9)
        assertNull("the first has nothing to compare with", trend[1]!!.change)
        assertEquals(-0.5, trend[2]!!.change!!, 1e-9)
        assertEquals(1.5, trend[3]!!.change!!, 1e-9)
    }

    @Test
    fun `the best ever is marked, and only it`() {
        val trend = consumptionTrend(
            history(
                fill(1, 1, 10_500, 30.0),
                fill(2, 2, 11_000, 27.5),
                fill(3, 3, 11_500, 35.0)
            ),
            initialOdometer = 10_000
        )
        assertTrue(trend[2]!!.isBest)
        assertFalse(trend[1]!!.isBest)
        assertFalse(trend[3]!!.isBest)
    }

    @Test
    fun `a tie stays with the one that got there first`() {
        val trend = consumptionTrend(
            history(fill(1, 1, 10_500, 30.0), fill(2, 2, 11_000, 30.0)),
            initialOdometer = 10_000
        )
        assertTrue(trend[1]!!.isBest)
        assertFalse(trend[2]!!.isBest)
    }

    @Test
    fun `one measured fill-up is not a record`() {
        val trend = consumptionTrend(history(fill(1, 1, 10_500, 30.0)), initialOdometer = 10_000)
        assertFalse(trend[1]!!.isBest)
    }

    @Test
    fun `a gap is passed over, so the next compares with the last measured`() {
        val trend = consumptionTrend(
            history(
                fill(1, 1, 10_500, 30.0),                 // 6.0
                fill(2, 2, 11_200, 20.0, missed = true),  // no figure
                fill(3, 3, 11_700, 25.0)                  // 5.0
            ),
            initialOdometer = 10_000
        )
        assertNull(trend[2]!!.value)
        assertNull(trend[2]!!.change)
        assertEquals(-1.0, trend[3]!!.change!!, 1e-9)
        assertTrue(trend[3]!!.isBest)
    }

    @Test
    fun `a hybrid's fuels are compared each with itself`() {
        val trend = consumptionTrend(
            history(
                fill(1, 1, 10_500, 30.0),                         // petrol 6.0
                fill(2, 2, 10_700, 30.0, fuel = "Electric"),      // 700 km since start: 4.29 kWh
                fill(3, 3, 11_000, 24.0),                         // petrol 4.8
                fill(4, 4, 11_300, 18.0, fuel = "Electric")       // 600 km: 3.0 kWh
            ),
            initialOdometer = 10_000
        )
        assertEquals(-1.2, trend[3]!!.change!!, 1e-9)
        assertEquals(3.0 - 30.0 / 7, trend[4]!!.change!!, 1e-9)
        assertTrue("best petrol", trend[3]!!.isBest)
        assertTrue("best electric", trend[4]!!.isBest)
        assertFalse(trend[1]!!.isBest)
        assertFalse(trend[2]!!.isBest)
    }

    @Test
    fun `it is worked out from existing history, whatever order it arrives in`() {
        val entries = listOf(fill(1, 1, 10_500, 30.0), fill(2, 2, 11_000, 27.5))
        val trend = consumptionTrend(history(*entries.toTypedArray()), initialOdometer = 10_000)
        assertEquals(setOf(1, 2), trend.keys)
    }

    @Test
    fun `a change too small to show at two decimals is not shown`() {
        assertFalse(isVisibleChange(0.004))
        assertFalse(isVisibleChange(-0.004))
        assertTrue(isVisibleChange(0.005))
        assertTrue(isVisibleChange(-0.3))
        assertFalse(isVisibleChange(null))
    }
}
