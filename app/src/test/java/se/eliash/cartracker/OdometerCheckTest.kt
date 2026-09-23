package se.eliash.cartracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class OdometerCheckTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply { clear(); set(year, month, day, hour, 0) }.timeInMillis

    private fun fill(id: Int, date: Long, odometer: Int, fuel: String = "Petrol") = FuelUp(
        id = id, carId = 1, fuelTypeUsed = fuel, dateMillis = date, odometerKm = odometer,
        litersFilled = 40.0, pricePerLiterSek = 18.0, totalCostSek = 720.0, missedPrevious = false
    )

    // A year of an old car's history, in 2024.
    private val history = listOf(
        fill(1, at(2024, Calendar.JANUARY, 10), 42_000),
        fill(2, at(2024, Calendar.MARCH, 3), 43_000),
        fill(3, at(2024, Calendar.JUNE, 20), 51_000),
        fill(4, at(2024, Calendar.DECEMBER, 1), 64_000)
    )

    // --- entering a reading ---

    @Test
    fun `a reading dated a year early is refused, naming the entry after it`() {
        // The case this was written for: 2 Feb 2024 typed for 2 Feb 2025.
        val conflict = odometerConflict(at(2024, Calendar.FEBRUARY, 2), 67_000, history)!!
        assertTrue(conflict.readingTooHigh)
        assertEquals("the nearest later reading is named", 2, conflict.other.id)
    }

    @Test
    fun `the right date is accepted`() {
        assertNull(odometerConflict(at(2025, Calendar.FEBRUARY, 2), 67_000, history))
    }

    @Test
    fun `a reading lower than one before it is refused, naming the nearest earlier`() {
        val conflict = odometerConflict(at(2024, Calendar.JULY, 1), 45_000, history)!!
        assertFalse(conflict.readingTooHigh)
        assertEquals(3, conflict.other.id)
    }

    @Test
    fun `a reading that fits between its neighbours is accepted`() {
        assertNull(odometerConflict(at(2024, Calendar.APRIL, 15), 47_000, history))
        assertNull("equal to a neighbour is fine", odometerConflict(at(2024, Calendar.APRIL, 15), 43_000, history))
    }

    @Test
    fun `readings on the same day are never held against each other`() {
        // Two fill-ups the same day can be in either order.
        assertNull(odometerConflict(at(2024, Calendar.MARCH, 3, hour = 8), 42_900, history))
        assertNull(odometerConflict(at(2024, Calendar.MARCH, 3, hour = 20), 43_100, history))
    }

    @Test
    fun `no reading, nothing to check`() {
        assertNull(odometerConflict(at(2024, Calendar.FEBRUARY, 2), 0, history))
    }

    @Test
    fun `an edited entry is not checked against itself`() {
        // Fixing entry 3's date leaves its own old reading out of it.
        assertNull(odometerConflict(at(2024, Calendar.JUNE, 21), 51_000, history, excludeId = 3))
    }

    @Test
    fun `every fuel counts, being one odometer`() {
        val hybrid = history + fill(5, at(2024, Calendar.DECEMBER, 20), 65_000, fuel = "Electric")
        val conflict = odometerConflict(at(2025, Calendar.JANUARY, 5), 64_500, hybrid)!!
        assertEquals(5, conflict.other.id)
    }

    // --- in words ---

    @Test
    fun `the refusal says what clashes with what, and what to do`() {
        val date = at(2024, Calendar.FEBRUARY, 2)
        val conflict = odometerConflict(date, 67_000, history)!!
        assertEquals(
            "The odometer can't go backwards: on 3 Mar 2024 it read 43,000 km, less than the " +
                "67,000 km given for 2 Feb 2024. Check the date and the reading, or if the " +
                "3 Mar 2024 entry is the wrong one, correct that one in the history.",
            describeOdometerConflict(date, 67_000, conflict, Locale.ENGLISH)
        )
    }

    @Test
    fun `the other way round reads the other way round`() {
        val date = at(2024, Calendar.JULY, 1)
        val conflict = odometerConflict(date, 45_000, history)!!
        assertEquals(
            "The odometer can't go backwards: on 20 Jun 2024 it already read 51,000 km, more than the " +
                "45,000 km given for 1 Jul 2024. Check the date and the reading, or if the " +
                "20 Jun 2024 entry is the wrong one, correct that one in the history.",
            describeOdometerConflict(date, 45_000, conflict, Locale.ENGLISH)
        )
    }

    // --- what is already in the history ---

    @Test
    fun `one wrong date flags that entry, not the year it clashes with`() {
        val withMistake = history + fill(9, at(2024, Calendar.FEBRUARY, 2), 67_000)
        assertEquals(setOf(9), outOfOrderEntries(withMistake))
    }

    @Test
    fun `a history in order flags nothing`() {
        assertTrue(outOfOrderEntries(history).isEmpty())
        assertTrue(outOfOrderEntries(emptyList()).isEmpty())
    }

    @Test
    fun `entries without a reading are neither flagged nor in the way`() {
        val withBlank = history + fill(9, at(2024, Calendar.FEBRUARY, 2), 0)
        assertTrue(outOfOrderEntries(withBlank).isEmpty())
    }

    @Test
    fun `a reading far too low is flagged on its own`() {
        // 5,100 typed for 51,000.
        val typo = history.map { if (it.id == 3) it.copy(odometerKm = 5_100) else it }
        assertEquals(setOf(3), outOfOrderEntries(typo))
    }

    @Test
    fun `same-day readings in either order are not flagged`() {
        val sameDay = history + fill(9, at(2024, Calendar.MARCH, 3, hour = 8), 43_050)
        assertTrue(outOfOrderEntries(sameDay).isEmpty())
    }

    // --- what the calculations see until it is fixed ---

    private val car = Car(id = 1, name = "Old car", fuelType = "Petrol", initialOdometer = 41_000)

    /** The year above with the mistake in it: 67,000 km dated 2 Feb 2024. */
    private val withMistake = history + fill(9, at(2024, Calendar.FEBRUARY, 2), 67_000)

    private fun newestFirst(list: List<FuelUp>) = list.sortedByDescending { it.dateMillis }

    @Test
    fun `the misdated reading gets no consumption, rather than a near-zero one`() {
        // Measured from 42,000 in January, it would be 40 L over 25,000 km.
        val trend = consumptionTrend(newestFirst(withMistake), car.initialOdometer)
        assertNull(trend[9]!!.value)
        assertFalse(trend[9]!!.isBest)
    }

    @Test
    fun `the fill-up after it is measured from the last reading that fits`() {
        // 3 Mar 2024 now measures from 42,000 in January, not from the 67,000
        // that was never there - but, following a reading taken out, it gets
        // no figure rather than one over fuel it does not count.
        val trend = consumptionTrend(newestFirst(withMistake), car.initialOdometer)
        assertNull(trend[2]!!.value)
        // The one after that is measured normally: 40 L over 8,000 km.
        assertEquals(0.5, trend[3]!!.value!!, 1e-9)
    }

    @Test
    fun `the dashboard average leaves the mistake out`() {
        // Counted, the 25,000 km "tank" gives 160 L over 47,000 km: 0.34.
        // Left out, with the fill-up measured from it: 120 L over 22,000 km.
        val stats = calculateFuelStats(car, withMistake)
        assertEquals(120.0 / 22_000 * 100, stats.avgPrimary, 1e-9)
    }

    @Test
    fun `the misdated reading adds no distance to a month`() {
        val now = Calendar.getInstance().apply { clear(); set(2025, Calendar.JANUARY, 15) }
        val clean = monthlyOverview(history, emptyList(), now)
        val mistaken = monthlyOverview(withMistake, emptyList(), now)
        assertEquals(
            clean.months.sumOf { it.distanceKm },
            mistaken.months.sumOf { it.distanceKm },
            0.5
        )
    }

    @Test
    fun `the misdated fill-up's cost still counts`() {
        val now = Calendar.getInstance().apply { clear(); set(2025, Calendar.JANUARY, 15) }
        val mistaken = monthlyOverview(withMistake, emptyList(), now)
        assertEquals(5 * 720.0, mistaken.months.sumOf { it.fuelCost }, 0.01)
    }

    @Test
    fun `a history in order is left exactly as it is`() {
        assertEquals(history, withTrustedReadings(history))
    }

    @Test
    fun `the list keeps its order and every entry`() {
        val trusted = withTrustedReadings(newestFirst(withMistake))
        assertEquals(newestFirst(withMistake).map { it.id }, trusted.map { it.id })
        assertEquals(0, trusted.single { it.id == 9 }.odometerKm)
        assertTrue(trusted.single { it.id == 2 }.missedPrevious)
    }
}
