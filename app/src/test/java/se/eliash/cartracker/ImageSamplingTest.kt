package se.eliash.cartracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSamplingTest {

    @Test
    fun `a 12 megapixel photo shrinks to a few hundred pixels for a thumbnail`() {
        // 4000x3000 into a 192px square: 3000/8 = 375 still covers it, and
        // 3000/16 = 187 would not.
        assertEquals(8, sampleSizeFor(4000, 3000, 192))
    }

    @Test
    fun `the shorter side decides, so a cropped square is still covered`() {
        // Portrait and landscape of the same photo shrink the same.
        assertEquals(sampleSizeFor(4000, 3000, 192), sampleSizeFor(3000, 4000, 192))
        val sample = sampleSizeFor(4000, 3000, 192)
        assertTrue("shorter side must still cover the target", 3000 / sample >= 192)
    }

    @Test
    fun `an exact multiple lands on the target, not below it`() {
        assertEquals(4, sampleSizeFor(768, 1024, 192))
    }

    @Test
    fun `a photo already small is not shrunk`() {
        assertEquals(1, sampleSizeFor(150, 100, 192))
        assertEquals(1, sampleSizeFor(192, 192, 192))
    }

    @Test
    fun `a 50 megapixel photo for the colour sample shrinks a long way`() {
        // 8160x6120 to 64px: 6120/64 = 95, so 64 is the largest power of two.
        assertEquals(64, sampleSizeFor(8160, 6120, 64))
    }

    @Test
    fun `unreadable dimensions decode at full size rather than failing`() {
        // A decode that could not read the header reports -1.
        assertEquals(1, sampleSizeFor(-1, -1, 192))
        assertEquals(1, sampleSizeFor(4000, 3000, 0))
    }

    @Test
    fun `memory for the garage thumbnail drops from megabytes to kilobytes`() {
        val full = 4000L * 3000 * 4
        val s = sampleSizeFor(4000, 3000, 192)
        val sampled = (4000L / s) * (3000L / s) * 4
        assertEquals(48_000_000L, full)
        assertEquals(750_000L, sampled)
    }
}
