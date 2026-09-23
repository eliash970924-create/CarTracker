package se.eliash.cartracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class ReminderTest {

    private val day = 24L * 60 * 60 * 1000
    private val locale = Locale.ENGLISH

    private fun at(year: Int, month: Int, dayOfMonth: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply { clear(); set(year, month, dayOfMonth, hour, 0) }.timeInMillis

    private val now = at(2026, Calendar.SEPTEMBER, 23)

    private fun reminder(
        dueDate: Long? = null,
        dueKm: Int? = null,
        repeatMonths: Int? = null,
        repeatKm: Int? = null,
        notified: Int = 0
    ) = Reminder(
        id = 1, carId = 1, type = ReminderType.Service.name, title = "Service",
        dueDateMillis = dueDate, dueOdometerKm = dueKm,
        repeatMonths = repeatMonths, repeatKm = repeatKm,
        notifiedStage = notified
    )

    private fun fill(dateMillis: Long, odometer: Int) = FuelUp(
        carId = 1, fuelTypeUsed = "Petrol", dateMillis = dateMillis, odometerKm = odometer,
        litersFilled = 40.0, pricePerLiterSek = 18.0, totalCostSek = 720.0, missedPrevious = false
    )

    // --- how far the car goes ---

    @Test
    fun `km per day comes from the logged readings`() {
        val fills = listOf(fill(now - 30 * day, 10_000), fill(now - 10 * day, 10_800), fill(now, 11_200))
        assertEquals(40.0, kmPerDay(fills, now)!!, 0.01)
    }

    @Test
    fun `too short a span gives no estimate rather than a wild one`() {
        val fills = listOf(fill(now - 3 * day, 10_000), fill(now, 10_500))
        assertNull(kmPerDay(fills, now))
        assertNull(kmPerDay(listOf(fill(now, 10_000)), now))
        assertNull(kmPerDay(emptyList(), now))
    }

    @Test
    fun `recent driving counts over old habits`() {
        val fills = listOf(
            fill(now - 400 * day, 1_000),       // a year ago, driving 100 km a day
            fill(now - 300 * day, 11_000),
            fill(now - 60 * day, 12_000),       // lately, 20 km a day
            fill(now, 13_200)
        )
        assertEquals(20.0, kmPerDay(fills, now)!!, 0.01)
    }

    @Test
    fun `with too little recent history the whole of it is used`() {
        val fills = listOf(fill(now - 300 * day, 10_000), fill(now - 5 * day, 13_000), fill(now, 13_100))
        assertEquals(3_100 / 300.0, kmPerDay(fills, now)!!, 0.01)
    }

    @Test
    fun `entries without a reading are ignored`() {
        val fills = listOf(fill(now - 20 * day, 10_000), fill(now - 10 * day, 0), fill(now, 10_400))
        assertEquals(20.0, kmPerDay(fills, now)!!, 0.01)
        assertEquals(OdometerReading(10_400, now), latestReading(fills))
    }

    // --- urgency ---

    @Test
    fun `far off by date and distance is fine`() {
        val status = reminderStatus(
            reminder(dueDate = now + 90 * day, dueKm = 20_000),
            OdometerReading(10_000, now), kmPerDay = 30.0, now = now
        )
        assertEquals(Urgency.Ok, status.urgency)
    }

    @Test
    fun `within a week of the date is due soon`() {
        val status = reminderStatus(reminder(dueDate = now + 7 * day), null, null, now)
        assertEquals(Urgency.Soon, status.urgency)
        assertEquals(7L, status.daysLeft)
        assertEquals(Urgency.Ok, reminderStatus(reminder(dueDate = now + 8 * day), null, null, now).urgency)
    }

    @Test
    fun `the day it is due is not yet overdue, the day after is`() {
        assertEquals(Urgency.Soon, reminderStatus(reminder(dueDate = now), null, null, now).urgency)
        assertEquals(Urgency.Overdue, reminderStatus(reminder(dueDate = now - day), null, null, now).urgency)
    }

    @Test
    fun `a logged reading past the due distance is overdue`() {
        val status = reminderStatus(reminder(dueKm = 15_000), OdometerReading(15_100, now), null, now)
        assertEquals(Urgency.Overdue, status.urgency)
        assertEquals(-100, status.kmLeft)
    }

    @Test
    fun `the estimate warns before the next fill-up would`() {
        // Last read 20 days ago, 1,000 km short. At 40 km a day that is
        // about 800 km since, so roughly 200 km left now.
        val status = reminderStatus(
            reminder(dueKm = 11_000), OdometerReading(10_000, now - 20 * day), kmPerDay = 40.0, now = now
        )
        assertEquals(1_000, status.kmLeft)
        assertEquals(200, status.estimatedKmLeft)
        assertEquals(5L, status.estimatedDaysForKm)
        assertEquals(Urgency.Soon, status.urgency)
    }

    @Test
    fun `an estimate alone never makes it overdue`() {
        // Probably past it by now, but nothing logged says so.
        val status = reminderStatus(
            reminder(dueKm = 10_300), OdometerReading(10_000, now - 30 * day), kmPerDay = 40.0, now = now
        )
        assertEquals(Urgency.Soon, status.urgency)
        assertTrue(status.estimatedKmLeft!! < 0)
    }

    @Test
    fun `whichever comes first decides`() {
        // Far off by date, close by distance.
        val status = reminderStatus(
            reminder(dueDate = now + 200 * day, dueKm = 10_300), OdometerReading(10_000, now), 30.0, now
        )
        assertEquals(Urgency.Soon, status.urgency)
    }

    @Test
    fun `no reading yet means the distance cannot count`() {
        val status = reminderStatus(reminder(dueKm = 15_000), null, null, now)
        assertEquals(Urgency.Ok, status.urgency)
        assertNull(status.kmLeft)
    }

    // --- in words ---

    @Test
    fun `the date is described plainly`() {
        fun say(days: Int) = describeReminder(
            reminder(dueDate = now + days * day), reminderStatus(reminder(dueDate = now + days * day), null, null, now), locale
        )
        assertEquals("Due today", say(0))
        assertEquals("Due tomorrow", say(1))
        assertEquals("Due in 12 days", say(12))
        assertEquals("1 day overdue", say(-1))
        assertEquals("3 days overdue", say(-3))
    }

    @Test
    fun `a date far off is given as the date`() {
        val due = at(2027, Calendar.MARCH, 15)
        val r = reminder(dueDate = due)
        assertEquals("Due 15 Mar 2027", describeReminder(r, reminderStatus(r, null, null, now), locale))
    }

    @Test
    fun `the distance carries a rough time when the pace is known`() {
        val r = reminder(dueKm = 11_200)
        val status = reminderStatus(r, OdometerReading(10_000, now), kmPerDay = 40.0, now = now)
        assertEquals("In about 1,200 km (about 4 weeks)", describeReminder(r, status, locale))
    }

    @Test
    fun `date and distance are both given`() {
        val r = reminder(dueDate = now + 12 * day, dueKm = 11_200)
        val status = reminderStatus(r, OdometerReading(10_000, now), kmPerDay = null, now = now)
        assertEquals("Due in 12 days · in about 1,200 km", describeReminder(r, status, locale))
    }

    @Test
    fun `distance overdue, and not yet known`() {
        val r = reminder(dueKm = 15_000)
        assertEquals("1,250 km overdue", describeReminder(r, reminderStatus(r, OdometerReading(16_250, now), null, now), locale))
        assertEquals("At 15,000 km", describeReminder(r, reminderStatus(r, null, null, now), locale))
    }

    @Test
    fun `rough durations`() {
        assertEquals("a day", roughDuration(1))
        assertEquals("5 days", roughDuration(5))
        assertEquals("3 weeks", roughDuration(21))
        assertEquals("4 months", roughDuration(120))
        assertEquals("3 years", roughDuration(3 * 365))
    }

    // --- marking done ---

    @Test
    fun `done moves it on from the day it was done, by date and distance`() {
        val done = at(2026, Calendar.SEPTEMBER, 10)
        val next = markDone(
            reminder(dueDate = now, dueKm = 15_000, repeatMonths = 12, repeatKm = 15_000, notified = 2),
            doneMillis = done, doneOdometerKm = 14_600
        )!!
        assertEquals(at(2027, Calendar.SEPTEMBER, 10), next.dueDateMillis)
        assertEquals(29_600, next.dueOdometerKm)
        assertEquals("a moved reminder is announced again", 0, next.notifiedStage)
    }

    @Test
    fun `a one-off is finished once done`() {
        assertNull(markDone(reminder(dueDate = now), now, 10_000))
    }

    @Test
    fun `without a reading, distance counts on from the old due point`() {
        val next = markDone(reminder(dueKm = 15_000, repeatKm = 15_000), now, doneOdometerKm = null)!!
        assertEquals(30_000, next.dueOdometerKm)
        assertNull(next.dueDateMillis)
    }

    // --- notifying once per stage ---

    @Test
    fun `each stage is notified once`() {
        val soon = ReminderStatus(Urgency.Soon, 3, null, null, null)
        val overdue = ReminderStatus(Urgency.Overdue, -1, null, null, null)
        val ok = ReminderStatus(Urgency.Ok, 30, null, null, null)
        assertTrue(shouldNotify(reminder(notified = 0), soon))
        assertFalse(shouldNotify(reminder(notified = Urgency.Soon.ordinal), soon))
        assertTrue(shouldNotify(reminder(notified = Urgency.Soon.ordinal), overdue))
        assertFalse(shouldNotify(reminder(notified = Urgency.Overdue.ordinal), overdue))
        assertFalse(shouldNotify(reminder(notified = 0), ok))
    }

    // --- order ---

    @Test
    fun `the most urgent come first, then the soonest`() {
        val a = reminder().copy(id = 1) to ReminderStatus(Urgency.Ok, 90, null, null, null)
        val b = reminder().copy(id = 2) to ReminderStatus(Urgency.Soon, 6, null, null, null)
        val c = reminder().copy(id = 3) to ReminderStatus(Urgency.Soon, null, 400, 300, 3)
        val d = reminder().copy(id = 4) to ReminderStatus(Urgency.Overdue, -2, null, null, null)
        assertEquals(listOf(4, 3, 2, 1), remindersInOrder(listOf(a, b, c, d)).map { it.first.id })
    }

    // --- what is typed ---

    private fun input(
        type: ReminderType = ReminderType.Service,
        title: String = "",
        dueDate: Long? = null,
        dueKm: String = "",
        repeatMonths: String = "",
        repeatKm: String = "",
        warnDays: String = "",
        warnKm: String = ""
    ) = ReminderInput(type, title, dueDate, dueKm, repeatMonths, repeatKm, warnDays, warnKm)

    @Test
    fun `a reminder needs a date or a distance`() {
        val result = reminderFromInput(input(), existing = null, carId = 1)
        assertNull(result.reminder)
        assertEquals("Give a date, a distance, or both", result.error)
    }

    @Test
    fun `numbers may be typed with spaces, and blank warnings take the defaults`() {
        val r = reminderFromInput(
            input(dueKm = "15 000", repeatMonths = "12", repeatKm = "15 000"), existing = null, carId = 7
        ).reminder!!
        assertEquals(15_000, r.dueOdometerKm)
        assertEquals(12, r.repeatMonths)
        assertEquals(DEFAULT_WARN_DAYS, r.warnDays)
        assertEquals(DEFAULT_WARN_KM, r.warnKm)
        assertEquals(7, r.carId)
        assertEquals(0, r.id)
    }

    @Test
    fun `a blank name takes the type's`() {
        val r = reminderFromInput(input(type = ReminderType.Tyres, dueDate = now), null, 1).reminder!!
        assertEquals("Tyre change", r.title)
        assertEquals("Tyres", r.type)
    }

    @Test
    fun `something that is not a number is refused, with the reason`() {
        val result = reminderFromInput(input(dueKm = "15k"), null, 1)
        assertNull(result.reminder)
        assertEquals("The distance must be a whole number above zero", result.error)
        assertNull(reminderFromInput(input(dueDate = now, repeatMonths = "0"), null, 1).reminder)
    }

    @Test
    fun `moving the due point announces it again, renaming does not`() {
        val existing = reminder(dueDate = now, notified = Urgency.Soon.ordinal)
        val renamed = reminderFromInput(input(title = "Big service", dueDate = now), existing, 1).reminder!!
        assertEquals(Urgency.Soon.ordinal, renamed.notifiedStage)
        assertEquals(existing.id, renamed.id)
        val moved = reminderFromInput(input(dueDate = now + 30 * day), existing, 1).reminder!!
        assertEquals(0, moved.notifiedStage)
    }

    @Test
    fun `where it is next due, in words`() {
        val both = reminder(dueDate = at(2027, Calendar.SEPTEMBER, 10), dueKm = 29_600)
        assertEquals("10 Sep 2027 or at 29,600 km, whichever comes first", describeDuePoint(both, locale))
        assertEquals("at 29,600 km", describeDuePoint(reminder(dueKm = 29_600), locale))
    }

    @Test
    fun `how often it repeats, in words`() {
        assertEquals("Every 12 months or 15,000 km", repeatText(reminder(repeatMonths = 12, repeatKm = 15_000), locale))
        assertEquals("Every 1 month", repeatText(reminder(repeatMonths = 1), locale))
        assertNull(repeatText(reminder(), locale))
    }
}
