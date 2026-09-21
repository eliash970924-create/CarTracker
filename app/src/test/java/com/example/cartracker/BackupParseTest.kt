package com.example.cartracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading a backup file's text. The parser takes its date reader as a
 * parameter, so these run without a formatter or a device locale.
 */
class BackupParseTest {

    /** Stands in for SimpleDateFormat: yyyy-MM-dd as a number, or null. */
    private val parseDate: (String) -> Long? = { text ->
        Regex("""^(\d{4})-(\d{2})-(\d{2})$""").matchEntire(text)?.let { m ->
            val (y, mo, d) = m.destructured
            y.toLong() * 10000 + mo.toLong() * 100 + d.toLong()
        }
    }

    private val fuelRow = "2026-01-15,12000,Petrol,40.0,18.5,740.0,false"

    private fun file(vararg lines: String) = (listOf(FUEL_HEADER) + lines).joinToString("\n")

    @Test
    fun `a fuel-only file, as every early export was, still reads`() {
        val parsed = parseBackupCsv(file(fuelRow), parseDate)
        assertEquals(1, parsed.fuelUps.size)
        assertEquals(0, parsed.expenses.size)
        assertNull(parsed.car)
        assertEquals(0, parsed.unreadableRows)
        assertEquals(40.0, parsed.fuelUps.first().litersFilled, 0.001)
    }

    @Test
    fun `all three sections read`() {
        val parsed = parseBackupCsv(
            file(
                fuelRow,
                "",
                EXPENSE_SECTION_MARKER,
                EXPENSE_HEADER,
                "2026-01-20,Tires,\"Winter set, mounted\",8000.0,false",
                "",
                CAR_SECTION_MARKER,
                CAR_HEADER,
                "Volvo V60,Petrol,Electric,12000,4280391411,car_abc.img"
            ),
            parseDate
        )
        assertEquals(1, parsed.fuelUps.size)
        assertEquals(1, parsed.expenses.size)
        assertEquals("Winter set, mounted", parsed.expenses.first().description)
        assertEquals("Volvo V60", parsed.car?.name)
        assertEquals("Electric", parsed.car?.secondaryFuelType)
        assertEquals(0, parsed.unreadableRows)
    }

    @Test
    fun `one unreadable date no longer costs the whole file`() {
        val parsed = parseBackupCsv(
            file(
                fuelRow,
                "not-a-date,12500,Petrol,41.0,18.5,758.5,false",
                "2026-02-01,13000,Petrol,42.0,18.5,777.0,false"
            ),
            parseDate
        )
        // The two good rows arrive; previously the bad one aborted all three.
        assertEquals(2, parsed.fuelUps.size)
        assertEquals(1, parsed.unreadableRows)
    }

    @Test
    fun `an unreadable row is left out rather than given today's date`() {
        val parsed = parseBackupCsv(file("garbage,,,,,,"), parseDate)
        // A substituted date would sit in the history looking like fact.
        assertEquals(0, parsed.fuelUps.size)
        assertEquals(1, parsed.unreadableRows)
    }

    @Test
    fun `a truncated row is counted, not silently dropped`() {
        val parsed = parseBackupCsv(file("2026-01-15,12000,Petrol"), parseDate)
        assertEquals(0, parsed.fuelUps.size)
        assertEquals(1, parsed.unreadableRows)
    }

    @Test
    fun `a car row is read as a car, not as a bad date`() {
        val parsed = parseBackupCsv(
            file(fuelRow, "", CAR_SECTION_MARKER, CAR_HEADER, "Saab 900,Petrol,,90000,"),
            parseDate
        )
        assertEquals("Saab 900", parsed.car?.name)
        assertNull(parsed.car?.secondaryFuelType)
        assertEquals(90000, parsed.car?.initialOdometer)
        assertNull(parsed.car?.themeColor)
        // Its name must not be mistaken for an unreadable date.
        assertEquals(0, parsed.unreadableRows)
    }

    @Test
    fun `a car row from before the photo column still reads`() {
        val parsed = parseBackupCsv(
            file(fuelRow, "", CAR_SECTION_MARKER, CAR_HEADER, "Volvo V60,Petrol,Electric,12000,4280391411"),
            parseDate
        )
        assertEquals("Volvo V60", parsed.car?.name)
        assertEquals(4280391411L, parsed.car?.themeColor)
        assertNull(parsed.car?.photo)
    }

    @Test
    fun `blank lines between sections are not counted against the file`() {
        val parsed = parseBackupCsv(file("", fuelRow, "", ""), parseDate)
        assertEquals(1, parsed.fuelUps.size)
        assertEquals(0, parsed.unreadableRows)
    }

    @Test
    fun `an empty file yields nothing rather than failing`() {
        val parsed = parseBackupCsv("", parseDate)
        assertEquals(0, parsed.fuelUps.size)
        assertEquals(0, parsed.expenses.size)
        assertNull(parsed.car)
        assertEquals(0, parsed.unreadableRows)
    }
}
