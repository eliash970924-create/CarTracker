package se.eliash.cartracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Reminders travel in backups as a last section, after the car. A backup
 * that loses them is a reminder that never comes, so the round trip is
 * tested, and so is every older layout still reading as it did.
 */
class ReminderBackupTest {

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val formatDate: (Long) -> String = { dayFormat.format(it) }
    private val parseDate: (String) -> Long? = { runCatching { dayFormat.parse(it)?.time }.getOrNull() }

    private fun at(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply { clear(); set(year, month, day) }.timeInMillis

    private val car = Car(id = 3, name = "Volvo V60", fuelType = "Petrol", initialOdometer = 10_000)

    private val service = Reminder(
        id = 11, carId = 3, type = ReminderType.Service.name, title = "Big service, with oil",
        dueDateMillis = at(2027, Calendar.MARCH, 15), dueOdometerKm = 25_000,
        repeatMonths = 12, repeatKm = 15_000, warnDays = 14, warnKm = 800, notifiedStage = 2
    )
    private val insurance = Reminder(
        id = 12, carId = 3, type = ReminderType.Insurance.name, title = "Insurance renewal",
        dueDateMillis = at(2026, Calendar.DECEMBER, 1), dueOdometerKm = null,
        repeatMonths = null, repeatKm = null
    )

    private fun write(reminders: List<Reminder>): String = StringWriter().also { w ->
        writeBackupCsv(w, car, emptyList(), emptyList(), null, formatDate, reminders)
    }.toString()

    @Test
    fun `reminders come back as they went out`() {
        val parsed = parseBackupCsv(write(listOf(service, insurance)), parseDate)

        assertEquals(0, parsed.unreadableRows)
        assertEquals(2, parsed.reminders.size)
        val back = parsed.reminders[0]
        assertEquals("Service", back.type)
        assertEquals("a comma in the name survives", "Big service, with oil", back.title)
        assertEquals(service.dueDateMillis, back.dueDateMillis)
        assertEquals(25_000, back.dueOdometerKm)
        assertEquals(12, back.repeatMonths)
        assertEquals(15_000, back.repeatKm)
        assertEquals(14, back.warnDays)
        assertEquals(800, back.warnKm)

        val oneOff = parsed.reminders[1]
        assertNull(oneOff.dueOdometerKm)
        assertNull(oneOff.repeatMonths)
        assertNull(oneOff.repeatKm)
    }

    @Test
    fun `what was announced stays behind, so a restored reminder is announced again`() {
        val back = parseBackupCsv(write(listOf(service)), parseDate).reminders.single()
        assertEquals(0, back.notifiedStage)
        assertEquals("ids and car ids are the importer's to give", 0, back.carId)
    }

    @Test
    fun `the car is still read when reminders follow it`() {
        val parsed = parseBackupCsv(write(listOf(service)), parseDate)
        assertEquals("Volvo V60", parsed.car?.name)
    }

    @Test
    fun `no reminders writes no section, so the file is exactly as before`() {
        val text = write(emptyList())
        assertTrue(REMINDER_SECTION_MARKER !in text)
        assertTrue(parseBackupCsv(text, parseDate).reminders.isEmpty())
    }

    @Test
    fun `a file from before reminders reads with none`() {
        val old = FUEL_HEADER + "\n" +
            "2026-01-15,12000,Petrol,40.0,18.5,740.0,false\n\n" +
            CAR_SECTION_MARKER + "\n" + CAR_HEADER + "\n" +
            "Volvo V60,Petrol,,12000,,\n"
        val parsed = parseBackupCsv(old, parseDate)
        assertTrue(parsed.reminders.isEmpty())
        assertEquals(1, parsed.fuelUps.size)
        assertEquals(0, parsed.unreadableRows)
    }

    private fun withRows(vararg rows: String): String =
        FUEL_HEADER + "\n\n" + REMINDER_SECTION_MARKER + "\n" + REMINDER_HEADER + "\n" + rows.joinToString("\n") + "\n"

    @Test
    fun `an unknown kind is kept as other, and blank warnings take the defaults`() {
        val back = parseBackupCsv(withRows("Carwash,Wash,2027-01-01,,,,,"), parseDate).reminders.single()
        assertEquals(ReminderType.Other.name, back.type)
        assertEquals("Wash", back.title)
        assertEquals(DEFAULT_WARN_DAYS, back.warnDays)
        assertEquals(DEFAULT_WARN_KM, back.warnKm)
    }

    @Test
    fun `a blank name takes the kind's`() {
        val back = parseBackupCsv(withRows("Tyres,,2027-01-01,,,,7,500"), parseDate).reminders.single()
        assertEquals("Tyre change", back.title)
    }

    @Test
    fun `rows that cannot be reminders are counted, not guessed at`() {
        val parsed = parseBackupCsv(
            withRows(
                "Service,Service,not a date,,,,7,500",   // unreadable date
                "Service,Service,,15k,,,7,500",          // not a number
                "Service,Service,,,12,,7,500",           // neither date nor distance
                "Service,Service",                       // too short
                "Service,Service,,15000,,,7,500"         // fine
            ),
            parseDate
        )
        assertEquals(1, parsed.reminders.size)
        assertEquals(4, parsed.unreadableRows)
    }

    @Test
    fun `the same reminder has the same key, a moved one does not`() {
        val day = formatDate(at(2027, Calendar.MARCH, 15))
        assertEquals(
            reminderKey("Service", "Service", day, 25_000),
            reminderKey("Service", "Service", day, 25_000)
        )
        assertTrue(reminderKey("Service", "Service", day, 25_000) != reminderKey("Service", "Service", day, 26_000))
        assertTrue(reminderKey("Service", "Service", null, 25_000) != reminderKey("Service", "Service", day, 25_000))
    }

    @Test
    fun `the import message counts reminders only when there are some`() {
        val with = ImportSummary("Volvo", false, 1, 0, 0, 0, remindersAdded = 2)
        assertEquals("Volvo: added 1 fill-ups, 0 expenses, 2 reminders", importMessage(listOf(with), null, 0))
        val one = ImportSummary("Volvo", false, 1, 0, 0, 0, remindersAdded = 1)
        assertEquals("Volvo: added 1 fill-ups, 0 expenses, 1 reminder", importMessage(listOf(one), null, 0))
        val without = ImportSummary("Volvo", false, 1, 0, 0, 0)
        assertEquals("Volvo: added 1 fill-ups, 0 expenses", importMessage(listOf(without), null, 0))
    }

    @Test
    fun `a garage backup carries each car's reminders`() {
        val out = java.io.ByteArrayOutputStream()
        writeGarageZip(
            out,
            listOf(CarBackup(car, emptyList(), emptyList(), null, listOf(service))),
            openPhoto = { null },
            formatDate = formatDate
        )
        val read = readZipBackup(java.io.ByteArrayInputStream(out.toByteArray())) { null }
        val parsed = parseBackupCsv(read.single().csv, parseDate)
        assertEquals("Big service, with oil", parsed.reminders.single().title)
    }
}
