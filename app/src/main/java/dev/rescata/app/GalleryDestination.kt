package dev.rescata.app

import android.content.ContentValues
import android.content.Context
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import dev.rescata.core.ContentIndex
import dev.rescata.core.Destination
import dev.rescata.core.DirectoryDestination
import dev.rescata.core.FileType
import dev.rescata.core.Finding
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Álbum de la galería: Pictures/<álbum> en el volumen elegido. Las fotos y vídeos se acumulan ahí
 * y aparecen en Google Fotos, la galería del fabricante, etc.
 *
 * Android 10+: se insertan con MediaStore (RELATIVE_PATH + IS_PENDING), que es la vía oficial.
 * Android 8-9 (o si MediaStore rechaza un tipo): se escribe el archivo y se pide que se indexe.
 */
class GalleryDestination(
    private val ctx: Context,
    private val volume: StorageAccess.Volume,
    private val album: String,
) : Destination {

    private val relativePath = Environment.DIRECTORY_PICTURES + "/" + album
    val dir = File(volume.dir, relativePath)

    override fun label(): String = "Galería › $album"

    /** El álbum se lista una vez por exportación, no una vez por archivo (los ocultos no cuentan). */
    private val index = ContentIndex(dir) { !it.name.startsWith(".") }

    override fun findExisting(finding: Finding, sha256: String, size: Long): String? = index.find(sha256, size)

    override fun written(location: String, sha256: String, size: Long) = index.add(location, sha256, size)

    override fun write(finding: Finding, fileName: String, writer: Destination.ContentWriter): String {
        val taken = bestDateMillis(finding)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return writeWithMediaStore(finding, fileName, writer, taken)
            } catch (e: IllegalArgumentException) {
                // p. ej. un tipo MIME que esta versión de MediaStore no admite: se usa la vía de archivo
            }
        }
        return writeFileAndScan(finding, fileName, writer, taken)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeWithMediaStore(f: Finding, fileName: String, writer: Destination.ContentWriter, taken: Long): String {
        val resolver = ctx.contentResolver
        val collection = if (f.type.kind == FileType.Kind.VIDEO) {
            MediaStore.Video.Media.getContentUri(volume.mediaStoreName)
        } else {
            MediaStore.Images.Media.getContentUri(volume.mediaStoreName)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, f.type.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (taken > 0) put(MediaStore.MediaColumns.DATE_TAKEN, taken)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("La galería no aceptó $fileName")
        try {
            val out = resolver.openOutputStream(uri, "w") ?: throw IOException("No se pudo abrir $fileName")
            out.use { writer.writeTo(it) }
            // Fecha del archivo = fecha original, para que la galería lo ordene en su día
            if (taken > 0) dataPath(uri)?.let { File(it).setLastModified(taken) }
            val publish = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                if (taken > 0) put(MediaStore.MediaColumns.DATE_TAKEN, taken)
            }
            resolver.update(uri, publish, null, null)
            return dataPath(uri) ?: "$relativePath/$fileName"
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (ignored: Exception) { }
            throw if (e is IOException) e else IOException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun writeFileAndScan(f: Finding, fileName: String, writer: Destination.ContentWriter, taken: Long): String {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("No se pudo crear $dir")
        val out = DirectoryDestination.uniqueFile(dir, fileName)
        try {
            FileOutputStream(out).use { writer.writeTo(it) }
        } catch (e: IOException) {
            out.delete()
            throw e
        }
        if (taken > 0) out.setLastModified(taken)
        MediaScannerConnection.scanFile(ctx, arrayOf(out.absolutePath), arrayOf(f.type.mimeType), null)
        return out.absolutePath
    }

    @Suppress("DEPRECATION")
    private fun dataPath(uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: RuntimeException) {
        null
    }

    companion object {
        private const val MAX_EXIF_BYTES = 64L * 1024 * 1024

        /** Fecha de la foto según EXIF (DateTimeOriginal) o, si no hay, la fecha del archivo original. */
        fun bestDateMillis(f: Finding): Long = exifDateMillis(f) ?: f.lastModified

        private fun exifDateMillis(f: Finding): Long? {
            if (f.type != FileType.JPEG && f.type != FileType.HEIF && f.type != FileType.WEBP && f.type != FileType.PNG) {
                return null
            }
            return try {
                open(f)?.use { input ->
                    val exif = ExifInterface(input)
                    val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                        ?: return null
                    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).parse(raw.trim())?.time
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun open(f: Finding): InputStream? {
            if (f.isWholeFile) return FileInputStream(f.sourcePath)
            if (f.length > MAX_EXIF_BYTES) return null
            val bytes = ByteArray(f.length.toInt())
            RandomAccessFile(f.sourcePath, "r").use {
                it.seek(f.offset)
                it.readFully(bytes)
            }
            return ByteArrayInputStream(bytes)
        }
    }
}
