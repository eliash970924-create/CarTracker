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
}
