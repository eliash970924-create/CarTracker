package se.eliash.cartracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class GarageTest {

    private fun car(id: Int, initialOdometer: Int = 10000, secondary: String? = null) =
        Car(id = id, name = "Car $id", fuelType = "Petrol",
            secondaryFuelType = secondary, initialOdometer = initialOdometer)

    // Noon, so no time zone can push the date across midnight.
    private fun noon(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply { clear(); set(year, month, day, 12, 0) }.timeInMillis

    // --- what opens at start ---

    @Test
    fun `a default car that exists is opened`() {
        val cars = listOf(car(1), car(2), car(3))
        assertEquals(2, launchCar(cars, defaultCarId = 2)?.id)
    }

    @Test
    fun `no default opens the garage, even with only one car`() {
        // One car is exactly the case the default is for, but choosing it is
        // the user's call: it is not set for them.
        assertNull(launchCar(listOf(car(1)), defaultCarId = null))
    }

    @Test
    fun `a default that was deleted falls back to the garage`() {
        // The id lives in preferences and can outlive the car it names.
        assertNull(launchCar(listOf(car(1), car(3)), defaultCarId = 2))
    }

    @Test
    fun `an empty garage opens the garage whatever the default says`() {
        assertNull(launchCar(emptyList(), defaultCarId = 1))
    }

    // --- the "Log fill-up" shortcut ---

    @Test
    fun `the shortcut opens the default car`() {
        assertEquals(2, fillUpShortcutCar(listOf(car(1), car(2)), defaultCarId = 2)?.id)
    }

    @Test
    fun `the shortcut opens the only car, even with no default`() {
        // Unlike a plain start: asking to log a fill-up with one car leaves
        // nothing to choose.
        assertEquals(1, fillUpShortcutCar(listOf(car(1)), defaultCarId = null)?.id)
    }

    @Test
    fun `with several cars and no default the shortcut asks, via the garage`() {
        assertNull(fillUpShortcutCar(listOf(car(1), car(2)), defaultCarId = null))
        assertNull(fillUpShortcutCar(listOf(car(1), car(3)), defaultCarId = 2))
    }

    @Test
    fun `the shortcut with no cars opens the garage`() {
        assertNull(fillUpShortcutCar(emptyList(), defaultCarId = null))
    }

    // --- what a card says ---

    @Test
    fun `a car with no fill-ups shows its starting odometer`() {
        assertEquals(
            "12,000 km · no fill-ups yet",
            garageStats(car(1, initialOdometer = 12000), summary = null, Locale.ENGLISH)
        )
    }

    @Test
    fun `a car with fill-ups shows its latest reading and when`() {
        val summary = CarSummary(carId = 1, fillUps = 7, latestOdometerKm = 12480,
            lastFillUpMillis = noon(2026, Calendar.SEPTEMBER, 18))
        assertEquals(
            "12,480 km · last fill-up 18 Sep 2026",
            garageStats(car(1, initialOdometer = 10000), summary, Locale.ENGLISH)
        )
    }

    @Test
    fun `fill-ups logged by trip distance do not show the car going backwards`() {
        // A trip-distance entry stores no reading, so the latest can be 0.
        val summary = CarSummary(carId = 1, fillUps = 3, latestOdometerKm = 0,
            lastFillUpMillis = noon(2026, Calendar.MARCH, 2))
        assertEquals(
            "15,000 km · last fill-up 2 Mar 2026",
            garageStats(car(1, initialOdometer = 15000), summary, Locale.ENGLISH)
        )
    }

    @Test
    fun `swedish formatting groups thousands with a space, not a comma`() {
        val text = garageStats(car(1, initialOdometer = 12000), null, Locale.forLanguageTag("sv-SE"))
        // The JDK uses a no-break space here; either is correct, a comma is not.
        assertEquals("12 000 km · no fill-ups yet", text.replace(' ', ' ').replace(' ', ' '))
    }

    @Test
    fun `fuel label names both fuels for a bifuel car`() {
        assertEquals("Petrol", fuelLabel(car(1)))
        assertEquals("Petrol / Electric", fuelLabel(car(1, secondary = "Electric")))
    }
}
