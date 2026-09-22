package se.eliash.cartracker

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.eliash.cartracker.ui.theme.CarTallyAmber
import se.eliash.cartracker.ui.theme.CarTallyPetrol
import se.eliash.cartracker.ui.theme.ThemeMode
import se.eliash.cartracker.ui.theme.carColorScheme
import se.eliash.cartracker.ui.theme.contrastRatio
import se.eliash.cartracker.ui.theme.liftUntilReadable
import se.eliash.cartracker.ui.theme.resolveDarkTheme

class ThemeTest {

    // --- which mode applies ---

    @Test
    fun `following the phone tracks the phone`() {
        assertTrue(resolveDarkTheme(ThemeMode.System, systemDark = true))
        assertFalse(resolveDarkTheme(ThemeMode.System, systemDark = false))
    }

    @Test
    fun `an explicit choice overrides the phone`() {
        assertFalse(resolveDarkTheme(ThemeMode.Light, systemDark = true))
        assertTrue(resolveDarkTheme(ThemeMode.Dark, systemDark = false))
    }

    @Test
    fun `nothing saved, or something unrecognised, follows the phone`() {
        assertEquals(ThemeMode.System, ThemeMode.fromStored(null))
        assertEquals(ThemeMode.System, ThemeMode.fromStored("Sepia"))
        assertEquals(ThemeMode.Dark, ThemeMode.fromStored("Dark"))
    }

    // --- contrast ---

    @Test
    fun `contrast runs from 1 for a colour on itself to 21 for black on white`() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(CarTallyPetrol, CarTallyPetrol), 0.0001)
    }

    @Test
    fun `petrol is lifted until it reads on a dark page`() {
        val page = darkColorScheme().surface
        assertTrue("petrol starts out invisible", contrastRatio(CarTallyPetrol, page) < 3.0)
        val lifted = liftUntilReadable(CarTallyPetrol, page)
        assertTrue(contrastRatio(lifted, page) >= 3.0)
        assertNotEquals(CarTallyPetrol, lifted)
    }

    @Test
    fun `a colour already bright enough is left as it is`() {
        val page = darkColorScheme().surface
        assertEquals(CarTallyAmber, liftUntilReadable(CarTallyAmber, page))
    }

    // --- a car's colours ---

    private val accents = listOf(
        "petrol" to CarTallyPetrol,
        "black" to Color.Black,
        "white" to Color.White,
        "red" to Color(0xFFFF0000),
        "blue" to Color(0xFF1976D2),
        "yellow" to Color(0xFFFBC02D),
        "grey" to Color(0xFF808080),
        "navy" to Color(0xFF0D1B2A)
    )

    @Test
    fun `light mode keeps a car's colour exactly as chosen`() {
        accents.forEach { (name, accent) ->
            assertEquals(name, accent, carColorScheme(lightColorScheme(), accent, dark = false).primary)
        }
    }

    @Test
    fun `in dark mode every car colour stays visible against the page`() {
        val base = darkColorScheme()
        accents.forEach { (name, accent) ->
            val scheme = carColorScheme(base, accent, dark = true)
            val ratio = contrastRatio(scheme.primary, scheme.surface)
            assertTrue("$name primary is %.2f:1 on the page".format(ratio), ratio >= 3.0)
        }
    }

    @Test
    fun `in dark mode the top bar title reads on every car's tint`() {
        val base = darkColorScheme()
        accents.forEach { (name, accent) ->
            val scheme = carColorScheme(base, accent, dark = true)
            val ratio = contrastRatio(scheme.onPrimaryContainer, scheme.primaryContainer)
            assertTrue("$name title is %.2f:1 on its bar".format(ratio), ratio >= 4.5)
        }
    }

    @Test
    fun `containers are solid, never see-through`() {
        // Translucency is what drew a square inside the add button.
        listOf(lightColorScheme() to false, darkColorScheme() to true).forEach { (base, dark) ->
            accents.forEach { (name, accent) ->
                val scheme = carColorScheme(base, accent, dark)
                assertEquals("$name primaryContainer alpha", 1f, scheme.primaryContainer.alpha, 0.001f)
                assertEquals("$name secondaryContainer alpha", 1f, scheme.secondaryContainer.alpha, 0.001f)
            }
        }
    }
}
