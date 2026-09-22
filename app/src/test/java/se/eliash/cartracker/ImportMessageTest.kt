package se.eliash.cartracker

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportMessageTest {

    private fun summary(
        name: String? = "Volvo",
        created: Boolean = false,
        fuel: Int = 0,
        fuelSkipped: Int = 0,
        expenses: Int = 0,
        expensesSkipped: Int = 0
    ) = ImportSummary(name, created, fuel, fuelSkipped, expenses, expensesSkipped)

    @Test
    fun `one existing car keeps the familiar message`() {
        assertEquals(
            "Volvo: added 3 fill-ups, 1 expenses",
            importMessage(listOf(summary(fuel = 3, expenses = 1)), null, 0)
        )
    }

    @Test
    fun `one new car says it was created`() {
        assertEquals(
            "Created Volvo: added 2 fill-ups, 0 expenses",
            importMessage(listOf(summary(created = true, fuel = 2)), null, 0)
        )
    }

    @Test
    fun `a file without car details is named after the car it went into`() {
        assertEquals(
            "Saab: added 1 fill-ups, 0 expenses",
            importMessage(listOf(summary(name = null, fuel = 1)), "Saab", 0)
        )
    }

    @Test
    fun `a garage is totalled`() {
        val results = listOf(
            summary(name = "Volvo", created = true, fuel = 3, expenses = 1),
            summary(name = "Saab", fuel = 2, fuelSkipped = 4, expensesSkipped = 1)
        )
        assertEquals(
            "Restored 2 cars (1 new): added 5 fill-ups, 1 expenses - skipped 5 already present",
            importMessage(results, null, 0)
        )
    }

    @Test
    fun `a garage with nothing new leaves out the count of new cars`() {
        val results = listOf(summary(name = "Volvo"), summary(name = "Saab"))
        assertEquals("Restored 2 cars: added 0 fill-ups, 0 expenses", importMessage(results, null, 0))
    }

    @Test
    fun `unreadable rows are mentioned`() {
        assertEquals(
            "Volvo: added 1 fill-ups, 0 expenses - 2 rows could not be read",
            importMessage(listOf(summary(fuel = 1)), null, 2)
        )
    }

    @Test
    fun `nowhere to go asks for a car`() {
        assertEquals(
            "Select a car first, or import a file that includes car details",
            importMessage(listOf(null), null, 0)
        )
    }

    @Test
    fun `a car that had nowhere to go does not count toward the garage`() {
        assertEquals(
            "Volvo: added 1 fill-ups, 0 expenses",
            importMessage(listOf(null, summary(fuel = 1)), null, 0)
        )
    }
}
