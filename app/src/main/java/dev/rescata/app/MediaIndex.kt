package dev.rescata.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.RequiresApi

/** Consultas a MediaStore: papelera del sistema e IDs existentes (para detectar miniaturas huérfanas). */
object MediaIndex {

    data class TrashItem(
        val uri: Uri,
        val path: String?,
        val displayName: String,
        val dateExpiresSeconds: Long,
        val size: Long,
    )

    /**
     * Elementos con IS_TRASHED = 1 (Android 11+). Por defecto MediaStore los oculta; con
     * QUERY_ARG_MATCH_TRASHED = MATCH_ONLY se obtienen solo ellos. Con "acceso a todos los
     * archivos" la app ve la tabla Files completa, no solo lo que ha creado ella.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun trashed(ctx: Context): List<TrashItem> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_EXPIRES,
            MediaStore.MediaColumns.SIZE,
        )
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
        val out = ArrayList<TrashItem>()
        try {
            ctx.contentResolver.query(collection, projection, args, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val iVol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)
                val iData = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iExp = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_EXPIRES)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val volume = c.getString(iVol) ?: MediaStore.VOLUME_EXTERNAL
                    out += TrashItem(
                        uri = itemUri(volume, c.getInt(iType), id),
                        path = c.getString(iData),
                        displayName = c.getString(iName) ?: "elemento_$id",
                        dateExpiresSeconds = if (c.isNull(iExp)) 0 else c.getLong(iExp),
                        size = c.getLong(iSize),
                    )
                }
            }
        } catch (e: RuntimeException) {
            // SecurityException / IllegalArgumentException según fabricante: se sigue solo con el sistema de archivos
        }
        return out
    }

    /** Todos los _ID de MediaStore, incluidos los que están en la papelera o pendientes. Vacío si falla. */
    fun allIds(ctx: Context): Set<Long> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val ids = HashSet<Long>()
        try {
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val args = Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }
                ctx.contentResolver.query(collection, projection, args, null)
            } else {
                ctx.contentResolver.query(collection, projection, null, null, null)
            }
            cursor?.use { c ->
                val i = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                while (c.moveToNext()) ids += c.getLong(i)
            }
        } catch (e: RuntimeException) {
            return emptySet()
        }
        return ids
    }

    private fun itemUri(volume: String, mediaType: Int, id: Long): Uri {
        val base = when (mediaType) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.getContentUri(volume)
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.getContentUri(volume)
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.getContentUri(volume)
            else -> MediaStore.Files.getContentUri(volume)
        }
        return ContentUris.withAppendedId(base, id)
    }
}
