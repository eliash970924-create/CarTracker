package se.eliash.cartracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.Calendar
import org.junit.Test

class RecurringExpensesTest {

    private fun at(year: Int, month: Int, day: Int = 1): Calendar =
        Calendar.getInstance().apply { clear(); set(year, month, day) }

    private fun expense(
        id: Int,
        year: Int,
        month: Int,
        cost: Double,
        category: String = "Insurance",
        description: String = "",
        monthly: Boolean = true
    ) = Expense(
        id = id,
        carId = 1,
        dateMillis = at(year, month).timeInMillis,
        category = category,
        description = description,
        costSek = cost,
        isMonthly = monthly
    )

    private fun monthOf(e: Expense): Pair<Int, Int> {
        val c = Calendar.getInstance().apply { timeInMillis = e.dateMillis }
        return c.get(Calendar.YEAR) to c.get(Calendar.MONTH)
    }

    @Test
    fun `a price change replaces the old amount instead of running alongside it`() {
        // January through April at 450, then April logged again at the new 500.
        val existing = listOf(
            expense(1, 2026, Calendar.JANUARY, 450.0),
            expense(2, 2026, Calendar.FEBRUARY, 450.0),
            expense(3, 2026, Calendar.MARCH, 450.0),
            expense(4, 2026, Calendar.APRIL, 450.0),
            expense(99, 2026, Calendar.APRIL, 500.0)
        )

        val generated = missingRecurringExpenses(existing, at(2026, Calendar.JULY))

        // May, June and July, all at the new price. Grouping on the cost used
        // to treat 450 and 500 as separate expenses and generate both.
        assertEquals(3, generated.size)
        assertTrue(generated.all { it.costSek == 500.0 })
        assertEquals(
            listOf(2026 to Calendar.MAY, 2026 to Calendar.JUNE, 2026 to Calendar.JULY),
            generated.map { monthOf(it) }
        )
    }

    @Test
    fun `the row entered later wins when two share the latest month`() {
        // Same month, same timestamp: only the id separates them, and the
        // higher id is the one the user added second.
        val existing = listOf(
            expense(4, 2026, Calendar.APRIL, 450.0),
            expense(99, 2026, Calendar.APRIL, 500.0)
        )
        val generated = missingRecurringExpenses(existing, at(2026, Calendar.MAY))
        assertEquals(1, generated.size)
        assertEquals(500.0, generated.first().costSek, 0.001)
    }

    @Test
    fun `a gap of several months is filled once each`() {
        val generated = missingRecurringExpenses(
            listOf(expense(1, 2026, Calendar.JANUARY, 300.0)),
            at(2026, Calendar.APRIL)
        )
        assertEquals(3, generated.size)
        assertEquals(
            listOf(2026 to Calendar.FEBRUARY, 2026 to Calendar.MARCH, 2026 to Calendar.APRIL),
            generated.map { monthOf(it) }
        )
    }

    @Test
    fun `nothing is generated when the current month is already covered`() {
        val generated = missingRecurringExpenses(
            listOf(expense(1, 2026, Calendar.APRIL, 300.0)),
            at(2026, Calendar.APRIL)
        )
        assertEquals(emptyList<Expense>(), generated)
    }

    @Test
    fun `a year boundary is crossed correctly`() {
        val generated = missingRecurringExpenses(
            listOf(expense(1, 2025, Calendar.NOVEMBER, 300.0)),
            at(2026, Calendar.JANUARY)
        )
        assertEquals(
            listOf(2025 to Calendar.DECEMBER, 2026 to Calendar.JANUARY),
            generated.map { monthOf(it) }
        )
    }

    @Test
    fun `different recurring expenses are tracked separately`() {
        val generated = missingRecurringExpenses(
            listOf(
                expense(1, 2026, Calendar.JANUARY, 450.0, category = "Insurance"),
                expense(2, 2026, Calendar.JANUARY, 300.0, category = "Parking", description = "Garage")
            ),
            at(2026, Calendar.FEBRUARY)
        )
        assertEquals(2, generated.size)
        assertEquals(setOf("Insurance", "Parking"), generated.map { it.category }.toSet())
    }

    @Test
    fun `one-off expenses are never repeated`() {
        val generated = missingRecurringExpenses(
            listOf(expense(1, 2026, Calendar.JANUARY, 8000.0, category = "Tires", monthly = false)),
            at(2026, Calendar.JUNE)
        )
        assertEquals(emptyList<Expense>(), generated)
    }

    @Test
    fun `no expenses at all is handled`() {
        assertEquals(emptyList<Expense>(), missingRecurringExpenses(emptyList(), at(2026, Calendar.JUNE)))
    }

    // --- switching a repeat off ---

    /** What the view model does when the repeat checkbox is unticked. */
    private fun stopRepeating(all: List<Expense>, edited: Expense): List<Expense> {
        val series = seriesOf(all, edited).map { it.id }.toSet()
        return all.map { if (it.id in series) it.copy(isMonthly = false) else it }
    }

    @Test
    fun `a series is found by category and description, not by cost`() {
        val rows = listOf(
            expense(1, 2026, Calendar.JANUARY, 450.0),
            expense(2, 2026, Calendar.FEBRUARY, 500.0),
            expense(3, 2026, Calendar.FEBRUARY, 300.0, category = "Parking")
        )
        assertEquals(listOf(1, 2), seriesOf(rows, rows[0]).map { it.id })
    }

    @Test
    fun `two repeats sharing a category stay separate series`() {
        val rows = listOf(
            expense(1, 2026, Calendar.JANUARY, 450.0, description = "Car insurance"),
            expense(2, 2026, Calendar.JANUARY, 120.0, description = "Breakdown cover")
        )
        assertEquals(listOf(1), seriesOf(rows, rows[0]).map { it.id })
        assertEquals(listOf(2), seriesOf(rows, rows[1]).map { it.id })
    }

    @Test
    fun `clearing the whole series actually stops the generator`() {
        val rows = listOf(
            expense(1, 2026, Calendar.JANUARY, 450.0),
            expense(2, 2026, Calendar.FEBRUARY, 450.0),
            expense(3, 2026, Calendar.MARCH, 450.0)
        )
        val stopped = stopRepeating(rows, rows[2])

        assertEquals(
            emptyList<Expense>(),
            missingRecurringExpenses(stopped, at(2026, Calendar.JUNE))
        )
        // The history is kept; only the repeat is switched off.
        assertEquals(3, stopped.size)
        assertTrue(stopped.none { it.isMonthly })
    }

    @Test
    fun `clearing only the edited row lets the next one down take over`() {
        // This is the bug the series-wide clear exists to prevent: unticking
        // the latest row alone leaves February seeding the generator again.
        val rows = listOf(
            expense(1, 2026, Calendar.JANUARY, 450.0),
            expense(2, 2026, Calendar.FEBRUARY, 450.0),
            expense(3, 2026, Calendar.MARCH, 450.0)
        )
        val onlyLatestCleared = rows.map { if (it.id == 3) it.copy(isMonthly = false) else it }

        val generated = missingRecurringExpenses(onlyLatestCleared, at(2026, Calendar.JUNE))
        assertTrue("the repeat should have carried on", generated.isNotEmpty())
        assertEquals(
            listOf(
                2026 to Calendar.MARCH, 2026 to Calendar.APRIL,
                2026 to Calendar.MAY, 2026 to Calendar.JUNE
            ),
            generated.map(::monthOf)
        )
        // Worse than merely carrying on: March already exists as the row that
        // was unticked, so it comes back a second time.
        assertTrue(generated.any { monthOf(it) == 2026 to Calendar.MARCH })
    }

    @Test
    fun `stopping one repeat leaves another running`() {
        val rows = listOf(
            expense(1, 2026, Calendar.JANUARY, 450.0, description = "Car insurance"),
            expense(2, 2026, Calendar.JANUARY, 120.0, description = "Breakdown cover")
        )
        val stopped = stopRepeating(rows, rows[0])

        val generated = missingRecurringExpenses(stopped, at(2026, Calendar.MARCH))
        assertTrue(generated.all { it.description == "Breakdown cover" })
        assertEquals(2, generated.size)
    }

    @Test
    fun `a renamed series is still found through the row it came from`() {
        // The edit renames the description, so the siblings left to clear
        // carry the old one. Looking them up by the original is what finds
        // them.
        val original = expense(3, 2026, Calendar.MARCH, 450.0, description = "Insurance")
        val rows = listOf(
            expense(1, 2026, Calendar.JANUARY, 450.0, description = "Insurance"),
            expense(2, 2026, Calendar.FEBRUARY, 450.0, description = "Insurance"),
            original.copy(description = "Car insurance")
        )
        assertEquals(listOf(1, 2), seriesOf(rows, original).map { it.id })
    }
}
