package se.eliash.cartracker

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * Car photos, kept in app-private storage.
 *
 * A photo used to be stored as the content:// URI the picker returned. That
 * is a grant, not a file: it does not survive reinstalling the app, and it
 * dies if the original is deleted from the gallery. When it died the photo
 * simply stopped appearing, with nothing to say why.
 *
 * The picked image is now copied into the app's own storage and the car keeps
 * the file name. Values in the old form are still read, so existing photos
 * keep working until they are adopted; see FuelViewModel.adoptLegacyPhotos.
 */
private const val PHOTO_DIR = "car_photos"

private fun photoDir(context: Context): File =
    File(context.filesDir, PHOTO_DIR).apply { mkdirs() }

fun carPhotoFile(context: Context, fileName: String): File = File(photoDir(context), fileName)

/** True for a photo still referenced the old way, by URI rather than by file. */
fun isExternalPhotoReference(stored: String): Boolean =
    stored.startsWith("content://") || stored.startsWith("file://")

/**
 * Copies an image into app storage, returning the file name to store on the
 * car, or null if it could not be read - which for a legacy URI means the
 * grant is already gone.
 *
 * The bytes are copied verbatim rather than decoded and written back out.
 * Re-encoding a Bitmap would drop the EXIF orientation tag, and
 * [loadAndRotateBitmap] needs that tag to show the photo the right way up.
 */
fun copyPhotoIntoAppStorage(context: Context, source: Uri): String? = try {
    val fileName = "car_${UUID.randomUUID()}.img"
    context.contentResolver.openInputStream(source)?.use { input ->
        carPhotoFile(context, fileName).outputStream().use { output -> input.copyTo(output) }
        fileName
    }
} catch (e: Exception) {
    null
}

/** Loads a car photo, accepting either storage form. */
fun loadCarPhoto(context: Context, stored: String?): Bitmap? {
    if (stored.isNullOrBlank()) return null
    val uri = if (isExternalPhotoReference(stored)) {
        Uri.parse(stored)
    } else {
        Uri.fromFile(carPhotoFile(context, stored))
    }
    return loadAndRotateBitmap(context, uri)
}

/**
 * Deletes a car's photo file. Without this, removing a car or changing its
 * photo would leave the image behind for good - the same kind of orphan the
 * database cascade was added to prevent.
 */
fun deleteCarPhoto(context: Context, stored: String?) {
    if (stored.isNullOrBlank() || isExternalPhotoReference(stored)) return
    try {
        carPhotoFile(context, stored).delete()
    } catch (e: Exception) {
        // Nothing useful to do; a stale file is harmless next to losing the row.
    }
}

/**
 * Stores photo bytes read from somewhere else - a backup archive - under a
 * fresh name, returning it, or null if the bytes could not be written.
 *
 * The name is generated here rather than taken from the source. An archive
 * entry's name is attacker-controlled text and could contain "../", so using
 * it would let a crafted backup write outside the photo directory.
 *
 * The stream is deliberately not closed: the caller may still be reading
 * further entries from it.
 */
fun storePhotoBytes(context: Context, input: java.io.InputStream): String? = try {
    val fileName = "car_${UUID.randomUUID()}.img"
    carPhotoFile(context, fileName).outputStream().use { output -> input.copyTo(output) }
    fileName
} catch (e: Exception) {
    null
}
