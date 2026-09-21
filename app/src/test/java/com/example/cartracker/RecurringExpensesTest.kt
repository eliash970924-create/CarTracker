package com.example.cartracker

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
}
