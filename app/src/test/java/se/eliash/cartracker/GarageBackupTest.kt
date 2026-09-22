package se.eliash.cartracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The whole-garage backup is new, and a backup that will not restore is the
 * worst kind, so the format is tested end to end: written, read back, and
 * parsed, with the old single-car layout still reading as it always has.
 */
class GarageBackupTest {

    private fun day(n: Long) = n * 86_400_000L
    private val formatDate: (Long) -> String = { "2026-01-%02d".format((it / 86_400_000L).toInt()) }
    private val parseDate: (String) -> Long? = { it.substringAfterLast('-').toLongOrNull()?.let(::day) }

    private fun car(id: Int, name: String, photo: String? = null) =
        Car(id = id, name = name, fuelType = "Petrol", initialOdometer = 1000, imageUri = photo)

    private fun fill(carId: Int, d: Long, odo: Int) = FuelUp(
        carId = carId, fuelTypeUsed = "Petrol", dateMillis = day(d), odometerKm = odo,
        litersFilled = 40.0, pricePerLiterSek = 18.0, totalCostSek = 720.0, missedPrevious = false
    )

    /** A photo store that remembers what it was given, as the app's would on disk. */
    private class FakeStore {
        val stored = mutableListOf<ByteArray>()
        fun store(input: java.io.InputStream): String {
            stored += input.readBytes(); return "stored_${stored.size}.img"
        }
    }

    // --- which car an entry belongs to ---

    @Test
    fun `entries are assigned to their car's folder`() {
        assertEquals("" to "backup.csv", backupEntryOwner("backup.csv"))
        assertEquals("" to "photos/a.img", backupEntryOwner("photos/a.img"))
        assertEquals("2" to "backup.csv", backupEntryOwner("cars/2/backup.csv"))
        assertEquals("12" to "photos/a.img", backupEntryOwner("cars/12/photos/a.img"))
    }

    @Test
    fun `a malformed car folder is skipped, not guessed at`() {
        assertNull(backupEntryOwner("cars/"))
        assertNull(backupEntryOwner("cars/backup.csv"))
    }

    // --- the round trip ---

    @Test
    fun `a garage backup reads back every car, in order, with the right photo each`() {
        val out = ByteArrayOutputStream()
        writeGarageZip(
            out,
            listOf(
                CarBackup(car(1, "Volvo V60", "volvo.img"), listOf(fill(1, 3, 1500), fill(1, 1, 1100)), emptyList(), "volvo.img"),
                CarBackup(car(2, "Polo"), listOf(fill(2, 2, 900)), emptyList(), null)
            ),
            openPhoto = { name -> ByteArrayInputStream("photo of $name".toByteArray()) },
            formatDate = formatDate
        )

        val store = FakeStore()
        val read = readZipBackup(ByteArrayInputStream(out.toByteArray()), store::store)

        assertEquals(2, read.size)
        val first = parseBackupCsv(read[0].csv, parseDate)
        val second = parseBackupCsv(read[1].csv, parseDate)
        assertEquals("Volvo V60", first.car?.name)
        assertEquals("Polo", second.car?.name)
        assertEquals(2, first.fuelUps.size)
        assertEquals(1, second.fuelUps.size)

        // The Volvo's photo went to the Volvo, and only it.
        assertEquals("stored_1.img", read[0].photoName)
        assertNull(read[1].photoName)
        assertEquals("photo of volvo.img", String(store.stored.single()))
    }

    @Test
    fun `a photo that cannot be read leaves the car in, without claiming a photo`() {
        val out = ByteArrayOutputStream()
        writeGarageZip(
            out,
            listOf(CarBackup(car(1, "Volvo V60", "gone.img"), emptyList(), emptyList(), "gone.img")),
            openPhoto = { null },
            formatDate = formatDate
        )
        val read = readZipBackup(ByteArrayInputStream(out.toByteArray()), FakeStore()::store)
        assertEquals(1, read.size)
        assertNull(read.single().photoName)
        assertNull("the CSV must not name a photo that is not there", parseBackupCsv(read.single().csv, parseDate).car?.photo)
    }

    @Test
    fun `ten or more cars keep their order`() {
        val out = ByteArrayOutputStream()
        val cars = (1..11).map { CarBackup(car(it, "Car $it"), emptyList(), emptyList(), null) }
        writeGarageZip(out, cars, openPhoto = { null }, formatDate = formatDate)
        val names = readZipBackup(ByteArrayInputStream(out.toByteArray()), FakeStore()::store)
            .map { parseBackupCsv(it.csv, parseDate).car?.name }
        assertEquals((1..11).map { "Car $it" }, names)
    }

    // --- the layout every earlier backup used ---

    @Test
    fun `a single-car backup still reads as one car with its photo`() {
        val csv = ByteArrayOutputStream().also { bytes ->
            bytes.writer().use { writeBackupCsv(it, car(1, "Volvo V60", "v.img"), listOf(fill(1, 1, 1100)), emptyList(), "v.img", formatDate) }
        }.toByteArray()
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z ->
                z.putNextEntry(ZipEntry("backup.csv")); z.write(csv); z.closeEntry()
                z.putNextEntry(ZipEntry("photos/v.img")); z.write("old photo".toByteArray()); z.closeEntry()
            }
        }.toByteArray()

        val store = FakeStore()
        val read = readZipBackup(ByteArrayInputStream(zip), store::store)
        assertEquals(1, read.size)
        assertEquals("Volvo V60", parseBackupCsv(read.single().csv, parseDate).car?.name)
        assertEquals("stored_1.img", read.single().photoName)
    }

    @Test
    fun `a stray photo with no car is never stored`() {
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z ->
                z.putNextEntry(ZipEntry("cars/3/photos/x.img")); z.write("orphan".toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        val store = FakeStore()
        assertTrue(readZipBackup(ByteArrayInputStream(zip), store::store).isEmpty())
        assertTrue("no orphan file should be written", store.stored.isEmpty())
    }
}
