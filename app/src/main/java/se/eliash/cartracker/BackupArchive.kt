package se.eliash.cartracker

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The full backup: the same CSV as the plain export, plus the car's photo.
 *
 * A CSV alone cannot carry an image, so a restore onto a different phone used
 * to arrive without it. The plain CSV export stays, because it is what opens
 * in a spreadsheet; this is the one to keep if the phone is lost.
 *
 * Two layouts, both read by [readBackup]:
 *
 *     backup.csv                one car - what "Back up <car>" writes
 *     photos/<name>
 *
 *     cars/1/backup.csv         the whole garage - what automatic backup
 *     cars/1/photos/<name>      writes, one folder per car, each exactly
 *     cars/2/backup.csv         the single-car layout
 *
 * A garage backup is only a set of single-car ones side by side, so the CSV
 * inside - and everything that reads it - is unchanged.
 */
const val BACKUP_CSV_ENTRY = "backup.csv"
const val BACKUP_PHOTO_PREFIX = "photos/"
const val GARAGE_CAR_PREFIX = "cars/"

/** One car's share of a backup: its CSV, and the photo stored for it, if any. */
data class BackupContents(val csv: String, val photoName: String?)

/** One car going into a garage backup. [photoName] is a photo this app owns. */
class CarBackup(
    val car: Car,
    val fuelUps: List<FuelUp>,
    val expenses: List<Expense>,
    val photoName: String?,
    val reminders: List<Reminder> = emptyList()
)

fun writeBackupZip(
    output: OutputStream,
    context: Context,
    car: Car?,
    fuelUps: List<FuelUp>,
    expenses: List<Expense>,
    formatDate: (Long) -> String,
    reminders: List<Reminder> = emptyList()
) {
    // Only a photo this app owns can be read back out. One still held as a
    // picker URI may already have lost its grant, and would fail here.
    val photoName = car?.imageUri
        ?.takeUnless { isExternalPhotoReference(it) }
        ?.takeIf { carPhotoFile(context, it).exists() }

    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry(BACKUP_CSV_ENTRY))
        val writer = zip.writer(Charsets.UTF_8)
        writeBackupCsv(writer, car, fuelUps, expenses, photoName, formatDate, reminders)
        // Flushed rather than closed: closing the writer would close the zip.
        writer.flush()
        zip.closeEntry()

        if (photoName != null) {
            zip.putNextEntry(ZipEntry(BACKUP_PHOTO_PREFIX + photoName))
            carPhotoFile(context, photoName).inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

/**
 * Writes every car into one archive, a folder each. [openPhoto] returns a
 * photo's bytes, or null if it cannot be read - the car then goes in without
 * it, and its CSV does not name a photo that is not there.
 */
fun writeGarageZip(
    output: OutputStream,
    cars: List<CarBackup>,
    openPhoto: (String) -> InputStream?,
    formatDate: (Long) -> String
) {
    ZipOutputStream(output).use { zip ->
        cars.forEachIndexed { index, backup ->
            val folder = "$GARAGE_CAR_PREFIX${index + 1}/"
            val photo = backup.photoName?.let { name -> openPhoto(name)?.let { name to it } }

            zip.putNextEntry(ZipEntry(folder + BACKUP_CSV_ENTRY))
            val writer = zip.writer(Charsets.UTF_8)
            writeBackupCsv(
                writer, backup.car, backup.fuelUps, backup.expenses, photo?.first, formatDate, backup.reminders
            )
            writer.flush()
            zip.closeEntry()

            if (photo != null) {
                val (name, stream) = photo
                zip.putNextEntry(ZipEntry(folder + BACKUP_PHOTO_PREFIX + name))
                stream.use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}

/**
 * Which car an archive entry belongs to, and its path within that car:
 * "" for the single-car layout, the folder number for a garage one. Null for
 * anything that is neither, which is skipped.
 */
fun backupEntryOwner(name: String): Pair<String, String>? {
    if (!name.startsWith(GARAGE_CAR_PREFIX)) return "" to name
    val rest = name.removePrefix(GARAGE_CAR_PREFIX)
    val slash = rest.indexOf('/')
    if (slash <= 0) return null
    return rest.substring(0, slash) to rest.substring(slash + 1)
}

/**
 * Every car in a zip backup, in the order written: one for a single-car
 * backup, one per folder for a garage backup. An archive with no CSV in it is
 * a zip, but not one of ours, and yields nothing.
 *
 * Photos are held until the whole archive has been read and stored only for
 * a car that is really there, so a stray one leaves no orphan file behind.
 */
fun readZipBackup(input: InputStream, storePhoto: (InputStream) -> String?): List<BackupContents> {
    val csvs = LinkedHashMap<String, String>()
    val photos = HashMap<String, ByteArray>()

    ZipInputStream(input).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val owner = backupEntryOwner(entry.name)
            if (owner != null && !entry.isDirectory) {
                val (car, path) = owner
                when {
                    path == BACKUP_CSV_ENTRY -> csvs[car] = zip.readBytes().toString(Charsets.UTF_8)
                    path.startsWith(BACKUP_PHOTO_PREFIX) -> photos[car] = zip.readBytes()
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }

    // Numeric, so folder 10 follows folder 9; the single-car "" sorts first.
    return csvs.entries
        .sortedBy { it.key.toIntOrNull() ?: 0 }
        .map { (car, csv) ->
            BackupContents(csv, photos[car]?.let { storePhoto(ByteArrayInputStream(it)) })
        }
}

/**
 * Reads a backup - a zip in either layout, or a bare CSV - so every file the
 * app has ever written still imports. Null if it is none of those.
 *
 * [openStream] is called more than once: the first four bytes decide the
 * format, and the stream cannot be rewound.
 */
fun readBackup(context: Context, openStream: () -> InputStream?): List<BackupContents>? {
    val zipped = openStream()?.use { looksLikeZip(it) } ?: return null

    return if (zipped) {
        openStream()
            ?.use { raw -> readZipBackup(raw) { storePhotoBytes(context, it) } }
            ?.takeIf { it.isNotEmpty() }
    } else {
        openStream()?.use { listOf(BackupContents(it.readBytes().toString(Charsets.UTF_8), null)) }
    }
}

/** The local file header a zip starts with: PK\u0003\u0004. */
private fun looksLikeZip(stream: InputStream): Boolean {
    val signature = ByteArray(4)
    return stream.read(signature) == 4 &&
        signature[0] == 0x50.toByte() &&
        signature[1] == 0x4B.toByte() &&
        signature[2] == 0x03.toByte() &&
        signature[3] == 0x04.toByte()
}
