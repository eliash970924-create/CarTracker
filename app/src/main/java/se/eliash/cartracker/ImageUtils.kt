package se.eliash.cartracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri

/**
 * How much to shrink a photo while decoding it, as a power of two, so its
 * shorter side still covers [targetPx].
 *
 * The shorter side, because a photo shown cropped to fill a square has to
 * cover the square both ways. Decoding at full size and scaling afterwards
 * is the expensive way round: a 12-megapixel photo is 48 MB in memory, to
 * fill a thumbnail about 180 pixels across.
 */
fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
    if (width <= 0 || height <= 0 || targetPx <= 0) return 1
    val shorter = minOf(width, height)
    var sample = 1
    while (shorter / (sample * 2) >= targetPx) sample *= 2
    return sample
}

/**
 * Decodes an image no larger than it needs to be to fill [targetPx], and
 * applies the rotation its EXIF orientation asks for.
 *
 * Phone cameras commonly store the sensor image unrotated and record the
 * orientation as a tag, so a car photo shown without this appears sideways.
 * The rotation is applied after shrinking, so it copies a small bitmap
 * rather than a second full-size one.
 */
fun loadAndRotateBitmap(context: Context, uri: Uri, targetPx: Int): Bitmap? {
    return try {
        val resolver = context.contentResolver

        // Dimensions only - no pixels are decoded here. decodeStream returns
        // null in this mode by design, so its result is not the test.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (resolver.openInputStream(uri) ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
        }
        val bitmap = (resolver.openInputStream(uri) ?: return null)
            .use { BitmapFactory.decodeStream(it, null, options) } ?: return null

        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }
        if (matrix.isIdentity) return bitmap

        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        rotated
    } catch (e: Exception) { null }
}
