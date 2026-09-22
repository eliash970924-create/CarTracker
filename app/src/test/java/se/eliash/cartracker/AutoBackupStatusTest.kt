package se.eliash.cartracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class AutoBackupStatusTest {

    private val locale = Locale.UK

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
        Calendar.getInstance().apply { clear(); set(year, month, day, hour, minute) }.timeInMillis

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    private fun state(
        frequency: BackupFrequency = BackupFrequency.Daily,
        success: Long? = null,
        failure: Long? = null,
        message: String? = null
    ) = AutoBackupState(
        uri = "content://example/backup.zip",
        fileName = "CarTally_Backup.zip",
        frequency = frequency,
        lastSuccessMillis = success,
        lastFailureMillis = failure,
        lastFailureMessage = message
    )

    private val now = at(2026, Calendar.MARCH, 10, 18, 0)

    // --- what the status says ---

    @Test
    fun `nothing yet is not a problem`() {
        val status = autoBackupStatus(state(), now, locale)
        assertEquals("No backup yet", status.text)
        assertFalse(status.problem)
    }

    @Test
    fun `a recent success is reported plainly`() {
        val status = autoBackupStatus(state(success = at(2026, Calendar.MARCH, 10, 9, 5)), now, locale)
        assertEquals("Last backup today at 09:05", status.text)
        assertFalse(status.problem)
    }

    @Test
    fun `a failure after the last success is shown`() {
        val status = autoBackupStatus(
            state(success = now - 2 * day, failure = now - hour, message = "no space"), now, locale
        )
        assertEquals("Last attempt failed: no space", status.text)
        assertTrue(status.problem)
    }

    @Test
    fun `a failure with no success at all is shown`() {
        val status = autoBackupStatus(state(failure = now - hour, message = null), now, locale)
        assertEquals("Last attempt failed: unknown error", status.text)
        assertTrue(status.problem)
    }

    @Test
    fun `a success after a failure clears it`() {
        val status = autoBackupStatus(
            state(success = now - hour, failure = now - day, message = "no space"), now, locale
        )
        assertFalse(status.problem)
        assertTrue(status.text.startsWith("Last backup"))
    }

    // --- overdue: the silent failure ---

    @Test
    fun `daily is overdue after more than two days`() {
        assertFalse(autoBackupStatus(state(success = now - 47 * hour), now, locale).problem)
        val late = autoBackupStatus(state(success = now - 3 * day), now, locale)
        assertTrue(late.problem)
        assertTrue(late.text.startsWith("Last backup 3 days ago - later than expected"))
    }

    @Test
    fun `weekly is overdue after more than two weeks`() {
        val weekly = BackupFrequency.Weekly
        assertFalse(autoBackupStatus(state(weekly, success = now - 10 * day), now, locale).problem)
        assertTrue(autoBackupStatus(state(weekly, success = now - 15 * day), now, locale).problem)
    }

    @Test
    fun `off is never overdue`() {
        val status = autoBackupStatus(state(BackupFrequency.Off, success = now - 100 * day), now, locale)
        assertFalse(status.problem)
    }

    // --- describeWhen ---

    @Test
    fun `today and yesterday carry the time`() {
        assertEquals("today at 08:00", describeWhen(at(2026, Calendar.MARCH, 10, 8, 0), now, locale))
        assertEquals("yesterday at 23:59", describeWhen(at(2026, Calendar.MARCH, 9, 23, 59), now, locale))
    }

    @Test
    fun `yesterday counts calendar days, not hours`() {
        // Twenty minutes earlier, but on the other side of midnight.
        val justAfterMidnight = at(2026, Calendar.MARCH, 10, 0, 10)
        assertEquals("yesterday at 23:50", describeWhen(at(2026, Calendar.MARCH, 9, 23, 50), justAfterMidnight, locale))
    }

    @Test
    fun `yesterday across the new year`() {
        assertEquals(
            "yesterday at 20:00",
            describeWhen(at(2025, Calendar.DECEMBER, 31, 20, 0), at(2026, Calendar.JANUARY, 1, 8, 0), locale)
        )
    }

    @Test
    fun `days ago up to two weeks, then the date`() {
        assertEquals("2 days ago", describeWhen(at(2026, Calendar.MARCH, 8), now, locale))
        assertEquals("13 days ago", describeWhen(at(2026, Calendar.FEBRUARY, 25), now, locale))
        assertEquals("on 24 Feb 2026", describeWhen(at(2026, Calendar.FEBRUARY, 24), now, locale))
    }

    @Test
    fun `a clock change does not shift the count`() {
        // Europe moves its clocks on 29 March 2026; wherever the test runs,
        // two midnights apart is two days.
        assertEquals(
            "2 days ago",
            describeWhen(at(2026, Calendar.MARCH, 28), at(2026, Calendar.MARCH, 30), locale)
        )
    }

    // --- stored frequency ---

    @Test
    fun `frequency reads back, and anything unknown is off`() {
        assertEquals(BackupFrequency.Daily, BackupFrequency.fromStored("Daily"))
        assertEquals(BackupFrequency.Weekly, BackupFrequency.fromStored("Weekly"))
        assertEquals(BackupFrequency.Off, BackupFrequency.fromStored(null))
        assertEquals(BackupFrequency.Off, BackupFrequency.fromStored("Hourly"))
    }
}
