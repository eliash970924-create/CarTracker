package se.eliash.cartracker

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.eliash.cartracker.ui.theme.CarTallyAmber
import se.eliash.cartracker.ui.theme.CarTallyAmberDeep
import se.eliash.cartracker.ui.theme.CarTallyInk
import se.eliash.cartracker.ui.theme.CarTallyIvory
import se.eliash.cartracker.ui.theme.CarTallyMuted
import se.eliash.cartracker.ui.theme.CarTallyMutedOnDark
import se.eliash.cartracker.ui.theme.CarTallyNight
import se.eliash.cartracker.ui.theme.CarTallyNightCard
import se.eliash.cartracker.ui.theme.CarTallyPetrol
import se.eliash.cartracker.ui.theme.CarTallyPetrolLifted
import se.eliash.cartracker.ui.theme.contrastRatio
import kotlin.math.pow

/**
 * The garage's colours have to stay readable, whatever they are later tuned
 * to. WCAG: 4.5:1 for text, 3:1 for icons.
 *
 * Checked because a palette can look right in a mockup and still fail: the
 * logo's own amber is 2.1:1 on white, too faint even for the star.
 */
class PaletteTest {

    private fun channel(c: Float): Double =
        if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertReadable(what: String, fg: Color, bg: Color, needed: Double) {
        val ratio = contrast(fg, bg)
        assertTrue("$what is %.2f:1, needs %.1f:1".format(ratio, needed), ratio >= needed)
    }

    @Test
    fun `the top bar title reads on petrol`() =
        assertReadable("ivory on petrol", CarTallyIvory, CarTallyPetrol, 4.5)

    @Test
    fun `body text reads on the page and on a card`() {
        assertReadable("ink on ivory", CarTallyInk, CarTallyIvory, 4.5)
        assertReadable("ink on white", CarTallyInk, Color.White, 4.5)
    }

    @Test
    fun `secondary text reads on the page and on a card`() {
        assertReadable("muted on ivory", CarTallyMuted, CarTallyIvory, 4.5)
        assertReadable("muted on white", CarTallyMuted, Color.White, 4.5)
    }

    @Test
    fun `the default label reads on a card`() =
        assertReadable("petrol on white", CarTallyPetrol, Color.White, 4.5)

    @Test
    fun `the plus reads on the add button`() =
        assertReadable("petrol on amber", CarTallyPetrol, CarTallyAmber, 3.0)

    @Test
    fun `the starred star is visible on a card`() =
        assertReadable("deep amber on white", CarTallyAmberDeep, Color.White, 3.0)

    // --- dark mode ---

    @Test
    fun `the dark top bar title reads on lifted petrol`() =
        assertReadable("ivory on lifted petrol", CarTallyIvory, CarTallyPetrolLifted, 4.5)

    @Test
    fun `dark body and secondary text read on a card`() {
        assertReadable("ivory on night card", CarTallyIvory, CarTallyNightCard, 4.5)
        assertReadable("muted on night card", CarTallyMutedOnDark, CarTallyNightCard, 4.5)
        assertReadable("muted on night", CarTallyMutedOnDark, CarTallyNight, 4.5)
    }

    @Test
    fun `the amber star and default label read on a dark card`() {
        assertReadable("amber icon on night card", CarTallyAmber, CarTallyNightCard, 3.0)
        assertReadable("amber text on night card", CarTallyAmber, CarTallyNightCard, 4.5)
    }

    @Test
    fun `a dark card still parts from the page`() {
        val ratio = contrast(CarTallyNightCard, CarTallyNight)
        assertTrue("card on page is %.2f:1".format(ratio), ratio >= 1.2)
    }

    @Test
    fun `the app's contrast agrees with this independent calculation`() {
        // The app's contrastRatio uses Compose's luminance; this file works
        // it out from the WCAG formula directly. They should not drift apart.
        listOf(
            CarTallyIvory to CarTallyPetrol,
            CarTallyAmber to CarTallyNightCard,
            CarTallyMuted to Color.White
        ).forEach { (a, b) -> assertEquals(contrast(a, b), contrastRatio(a, b), 0.02) }
    }
}
