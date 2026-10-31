package dev.rescata.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.util.LruCache
import android.util.Size
import dev.rescata.core.FileType
import dev.rescata.core.Finding
import java.io.File
import java.io.RandomAccessFile

/** Vistas previas pequeñas, decodificadas con submuestreo para no gastar memoria. */
object Previews {

    private const val MAX_CARVED_BYTES = 40L * 1024 * 1024

    // Hasta 1/8 de la memoria de la app (máx. 64 MB) para las miniaturas de la cuadrícula
    class Cache : LruCache<Long, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(64L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    fun load(f: Finding, targetPx: Int): Bitmap? = try {
        when {
            f.type.isImage -> decodeImage(f, targetPx)
            f.type.kind == FileType.Kind.VIDEO && f.isWholeFile &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ThumbnailUtils.createVideoThumbnail(File(f.sourcePath), Size(targetPx, targetPx), null)
            else -> null
        }
    } catch (t: Throwable) { // incluye OutOfMemoryError y formatos no soportados
        null
    }

    private fun decodeImage(f: Finding, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (f.isWholeFile) {
            BitmapFactory.decodeFile(f.sourcePath, bounds)
            return BitmapFactory.decodeFile(f.sourcePath, sampled(bounds, target))
        }
        if (f.length > MAX_CARVED_BYTES) return null
        val bytes = ByteArray(f.length.toInt())
        RandomAccessFile(f.sourcePath, "r").use {
            it.seek(f.offset)
            it.readFully(bytes)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampled(bounds, target))
    }

    private fun sampled(bounds: BitmapFactory.Options, target: Int): BitmapFactory.Options {
        var sample = 1
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w > 0 && h > 0) {
            while (w / (sample * 2) >= target && h / (sample * 2) >= target) sample *= 2
        }
        return BitmapFactory.Options().apply { inSampleSize = sample }
    }
}
