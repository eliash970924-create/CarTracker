package com.example.cartracker

import android.content.Context
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
 */
const val BACKUP_CSV_ENTRY = "backup.csv"
const val BACKUP_PHOTO_PREFIX = "photos/"

/** What a backup yielded, whichever of the two forms it was in. */
data class BackupContents(val csv: String, val photoName: String?)

fun writeBackupZip(
    output: OutputStream,
    context: Context,
    car: Car?,
    fuelUps: List<FuelUp>,
    expenses: List<Expense>,
    formatDate: (Long) -> String
) {
    // Only a photo this app owns can be read back out. One still held as a
    // picker URI may already have lost its grant, and would fail here.
    val photoName = car?.imageUri
        ?.takeUnless { isExternalPhotoReference(it) }
        ?.takeIf { carPhotoFile(context, it).exists() }

    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry(BACKUP_CSV_ENTRY))
        val writer = zip.writer(Charsets.UTF_8)
        writeBackupCsv(writer, car, fuelUps, expenses, photoName, formatDate)
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
 * Reads a backup, accepting either a zip or a bare CSV, so every file the app
 * has ever written still imports.
 *
 * [openStream] is called more than once: the first four bytes decide the
 * format, and the stream cannot be rewound.
 */
fun readBackup(context: Context, openStream: () -> InputStream?): BackupContents? {
    val zipped = openStream()?.use { looksLikeZip(it) } ?: return null

    return if (zipped) {
        readZipBackup(context, openStream)
    } else {
        openStream()?.use { BackupContents(it.readBytes().toString(Charsets.UTF_8), null) }
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

private fun readZipBackup(context: Context, openStream: () -> InputStream?): BackupContents? {
    var csv: String? = null
    var photoName: String? = null

    openStream()?.use { raw ->
        ZipInputStream(raw).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == BACKUP_CSV_ENTRY ->
                        csv = zip.readBytes().toString(Charsets.UTF_8)
                    entry.name.startsWith(BACKUP_PHOTO_PREFIX) && !entry.isDirectory ->
                        photoName = storePhotoBytes(context, zip)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    // No CSV means this is a zip, but not one of ours.
    return csv?.let { BackupContents(it, photoName) }
}
