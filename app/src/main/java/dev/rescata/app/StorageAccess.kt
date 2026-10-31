package dev.rescata.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/** Permisos y volúmenes de almacenamiento accesibles sin root. */
object StorageAccess {

    /** [mediaStoreName]: nombre del volumen para MediaStore ("external_primary" en la memoria interna). */
    data class Volume(
        val label: String,
        val dir: File,
        val removable: Boolean,
        val primary: Boolean,
        val mediaStoreName: String = PRIMARY_MEDIA_VOLUME,
    )

    private const val PRIMARY_MEDIA_VOLUME = "external_primary" // MediaStore.VOLUME_EXTERNAL_PRIMARY

    /** Álbumes de la galería donde se guardan fotos y vídeos recuperados (se acumulan). */
    const val ALBUM = "Rescata"
    const val ALBUM_THUMBNAILS = "Rescata – Miniaturas"

    /** Subcarpeta (dentro de cada volumen) donde se exporta lo recuperado; se excluye del escaneo. */
    val EXPORT_SUBDIR = Environment.DIRECTORY_DOWNLOADS + "/Rescata"

    /**
     * Android 11+ (API 30): "Acceso a todos los archivos" (MANAGE_EXTERNAL_STORAGE).
     * Android 8-10: READ/WRITE_EXTERNAL_STORAGE (en Android 10 con requestLegacyExternalStorage).
     */
    fun hasFullAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            granted(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) &&
                granted(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

    private fun granted(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    /** Pantalla de Ajustes donde el usuario activa "Permitir acceso para gestionar todos los archivos". */
    fun allFilesAccessIntent(ctx: Context): Intent {
        val direct = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${ctx.packageName}")
        )
        return if (direct.resolveActivity(ctx.packageManager) != null) direct
        else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }

    fun volumes(ctx: Context): List<Volume> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = ctx.getSystemService(StorageManager::class.java)
            return sm.storageVolumes
                .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
                .mapNotNull { v ->
                    v.directory?.let {
                        Volume(v.getDescription(ctx), it, v.isRemovable, v.isPrimary,
                            v.mediaStoreVolumeName ?: PRIMARY_MEDIA_VOLUME)
                    }
                }
                .sortedByDescending { it.primary }
        }
        // Android 8-10: almacenamiento principal + raíces deducidas de los directorios de la app en otros volúmenes.
        @Suppress("DEPRECATION")
        val primary = Volume("Almacenamiento interno", Environment.getExternalStorageDirectory(), false, true)
        val others = ctx.getExternalFilesDirs(null).drop(1).mapNotNull { f ->
            val path = f?.absolutePath ?: return@mapNotNull null
            val root = path.substringBefore("/Android/data/", missingDelimiterValue = "")
            if (root.isEmpty()) null else Volume("Tarjeta SD (${File(root).name})", File(root), true, false)
        }
        return listOf(primary) + others
    }

    /** Carpetas que crea la propia app (se excluyen del escaneo). */
    fun ownFolders(v: Volume): List<File> = listOf(
        File(v.dir, EXPORT_SUBDIR),
        File(v.dir, Environment.DIRECTORY_PICTURES + "/" + ALBUM),
        File(v.dir, Environment.DIRECTORY_PICTURES + "/" + ALBUM_THUMBNAILS),
    )

    /**
     * Carpetas de exportación de versiones anteriores de la app: Download/Rescata/<aaaaMMdd_HHmmss>.
     * Lo que haya ahí idéntico a lo que se guarda ahora se mueve al álbum en vez de copiarse otra vez.
     */
    fun previousExportDirs(v: Volume): List<File> =
        File(v.dir, EXPORT_SUBDIR).listFiles { f -> f.isDirectory && PREVIOUS_EXPORT.matches(f.name) }
            ?.sortedBy { it.name }.orEmpty()

    private val PREVIOUS_EXPORT = Regex("""\d{8}_\d{6}""")

    /**
     * Espacio que se deja libre al guardar: 1 GB (o el 10 % del almacenamiento si es muy pequeño),
     * para no llenar el móvil.
     */
    fun freeSpaceMargin(v: Volume): Long = minOf(1L shl 30, v.dir.totalSpace / 10)

    /**
     * Volúmenes donde se puede escribir la exportación. Antes de Android 11 una app sin root
     * no puede escribir en la raíz de la tarjeta SD, así que solo se ofrece el almacenamiento principal.
     */
    fun exportVolumes(ctx: Context): List<Volume> {
        val all = volumes(ctx)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) all else all.filter { it.primary }
    }
}
