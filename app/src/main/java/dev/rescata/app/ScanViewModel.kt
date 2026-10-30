package dev.rescata.app

import android.app.Application
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.rescata.core.DirectoryDestination
import dev.rescata.core.Exporter
import dev.rescata.core.FileType
import dev.rescata.core.Finding
import dev.rescata.core.ScanEngine
import dev.rescata.core.ScanListener
import dev.rescata.core.ScanOptions
import dev.rescata.core.Signatures
import dev.rescata.core.TrashedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class Options(
    val systemTrash: Boolean = true,
    val trashFolders: Boolean = true,
    val thumbnails: Boolean = true,
    val hiddenMedia: Boolean = true,
    val carving: Boolean = false,
    val whatsappBackups: Boolean = true,
    val whatsappMedia: Boolean = false,
)

data class FindingRow(val finding: Finding, val orphanThumbnail: Boolean = false)

data class UiState(
    val hasAccess: Boolean = false,
    val options: Options = Options(),
    val scanning: Boolean = false,
    val progressText: String = "",
    val rows: List<FindingRow> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val filter: Finding.Category? = null,
    val summary: String? = null,
    val exporting: Boolean = false,
    val exportProgress: String = "",
    val message: String? = null,
) {
    val visibleRows: List<FindingRow>
        get() = if (filter == null) rows else rows.filter { it.finding.category == filter }
}

private fun n(count: Number, one: String, many: String) =
    "$count ${if (count.toLong() == 1L) one else many}"

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState(hasAccess = StorageAccess.hasFullAccess(app)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var cancelFlag = AtomicBoolean(false)
    private var job: Job? = null

    fun refreshAccess() = _state.update { it.copy(hasAccess = StorageAccess.hasFullAccess(getApplication())) }
    fun setOptions(o: Options) = _state.update { it.copy(options = o) }
    fun setFilter(c: Finding.Category?) = _state.update { it.copy(filter = c) }
    fun consumeMessage() = _state.update { it.copy(message = null) }
    fun showMessage(m: String) = _state.update { it.copy(message = m) }

    fun toggle(id: Long) = _state.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun selectVisible() = _state.update { s -> s.copy(selected = s.selected + s.visibleRows.map { it.finding.id }) }
    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun cancel() = cancelFlag.set(true)

    fun startScan() {
        if (_state.value.scanning) return
        val ctx = getApplication<Application>()
        val o = _state.value.options
        val cancel = AtomicBoolean(false).also { cancelFlag = it }
        _state.update {
            it.copy(scanning = true, rows = emptyList(), selected = emptySet(), filter = null,
                summary = null, progressText = "Preparando…")
        }

        job = viewModelScope.launch(Dispatchers.IO) {
            val volumes = StorageAccess.volumes(ctx)
            val opts = ScanOptions().apply {
                systemTrash = o.systemTrash
                trashFolders = o.trashFolders
                thumbnails = o.thumbnails
                hiddenMedia = o.hiddenMedia
                carving = o.carving
                whatsappBackups = o.whatsappBackups
                whatsappMedia = o.whatsappMedia
                volumes.forEach { v -> StorageAccess.ownFolders(v).forEach { excludedPaths.add(it.absolutePath) } }
            }

            // 1) Papelera del sistema según MediaStore (Android 11+), para poder restaurar en su sitio.
            val trash = if (o.systemTrash && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaIndex.trashed(ctx)
            } else emptyList()
            val trashByPath = trash.filter { it.path != null }.associateBy { it.path!! }

            // 2) Recorrido del almacenamiento accesible.
            val found = ArrayList<Finding>()
            var lastPush = 0L
            val summary = ScanEngine(opts).scan(volumes.map { it.dir }, object : ScanListener {
                override fun onProgress(filesVisited: Long, dirsVisited: Long, currentPath: String) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastPush < 250) return
                    lastPush = now
                    val snapshot = found.map { FindingRow(it) }
                    _state.update {
                        it.copy(progressText = "$filesVisited archivos · $dirsVisited carpetas\n$currentPath",
                            rows = snapshot)
                    }
                }

                override fun onFinding(finding: Finding) {
                    trashByPath[finding.sourcePath]?.let { finding.externalRef = it.uri.toString() }
                    found += finding
                }
            }, cancel)

            // 3) Elementos de la papelera que MediaStore conoce pero el recorrido no vio.
            val seen = found.mapTo(HashSet()) { it.sourcePath }
            var extraId = 1_000_000_000L
            for (t in trash) {
                val path = t.path ?: continue
                if (path in seen) continue
                val file = File(path)
                if (!file.canRead()) continue
                val tn = TrashedName.parse(file.name)
                found += Finding(
                    extraId++, Finding.Category.SYSTEM_TRASH, Signatures.identify(file), path, -1,
                    file.length(), file.lastModified(), tn?.originalName ?: t.displayName,
                    t.dateExpiresSeconds, -1, "En la papelera del sistema (MediaStore)"
                ).also { it.externalRef = t.uri.toString() }
            }

            // 4) Miniaturas cuyo ID ya no existe en MediaStore: la foto original probablemente se borró.
            val thumbIds = found.filter { it.category == Finding.Category.THUMBNAIL && it.thumbnailMediaId >= 0 }
            val knownIds = if (thumbIds.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaIndex.allIds(ctx)
            } else emptySet()
            val rows = found
                .sortedWith(compareBy<Finding> { it.category.ordinal }.thenByDescending { it.lastModified })
                .map { f ->
                    val orphan = knownIds.isNotEmpty() && f.category == Finding.Category.THUMBNAIL &&
                        f.thumbnailMediaId >= 0 && f.thumbnailMediaId !in knownIds
                    FindingRow(f, orphan)
                }

            val text = buildString {
                append("${n(summary.filesVisited, "archivo", "archivos")} en ${n(summary.dirsVisited, "carpeta", "carpetas")} · ")
                append(n(rows.size, "hallazgo", "hallazgos"))
                val orphans = rows.count { it.orphanThumbnail }
                if (orphans > 0) append(" · ${n(orphans, "miniatura sin original", "miniaturas sin original")}")
                if (summary.unreadableDirs > 0) {
                    append("\n${n(summary.unreadableDirs, "carpeta sin acceso", "carpetas sin acceso")} (p. ej. Android/data, protegida por el sistema)")
                }
                val noPath = trash.count { it.path == null }
                if (noPath > 0) append("\n${n(noPath, "elemento", "elementos")} de la papelera sin ruta accesible")
                if (summary.cancelled) append("\nEscaneo cancelado: resultados parciales")
            }
            _state.update { it.copy(scanning = false, rows = rows, summary = text, progressText = "") }
        }
    }

    private var pendingRestore: Set<Long> = emptySet()

    /** URIs de MediaStore de los elementos seleccionados que están en la papelera del sistema. */
    fun selectedTrashUris(): List<Uri> {
        val s = _state.value
        val rows = s.rows.filter { it.finding.id in s.selected && it.finding.externalRef != null }
        pendingRestore = rows.map { it.finding.id }.toSet()
        return rows.map { Uri.parse(it.finding.externalRef) }
    }

    fun onRestoreResult(ok: Boolean) {
        val ids = pendingRestore
        pendingRestore = emptySet()
        if (!ok) {
            showMessage("Restauración cancelada")
            return
        }
        // Ya no están en la papelera: se quitan de la lista.
        _state.update { s ->
            s.copy(
                rows = s.rows.filterNot { it.finding.id in ids },
                selected = s.selected - ids,
                message = "${n(ids.size, "elemento restaurado", "elementos restaurados")} en su ubicación original",
            )
        }
    }

    fun export(volume: StorageAccess.Volume) {
        val s = _state.value
        val selected = s.rows.filter { it.finding.id in s.selected }.map { it.finding }
        if (selected.isEmpty() || s.exporting) return
        val ctx = getApplication<Application>()
        _state.update { it.copy(exporting = true, exportProgress = "Exportando…") }
        viewModelScope.launch(Dispatchers.IO) {
            // Fotos y vídeos → álbum «Rescata» de la galería; miniaturas → «Rescata – Miniaturas»;
            // el resto (documentos, audio, desconocidos) y el informe → Download/Rescata.
            val album = GalleryDestination(ctx, volume, StorageAccess.ALBUM)
            val thumbs = GalleryDestination(ctx, volume, StorageAccess.ALBUM_THUMBNAILS)
            val othersDir = File(volume.dir, StorageAccess.EXPORT_SUBDIR)
            val others = object : DirectoryDestination(othersDir, "Download › Rescata") {
                override fun afterWrite(finding: Finding, out: File) {
                    super.afterWrite(finding, out)
                    MediaScannerConnection.scanFile(ctx, arrayOf(out.absolutePath), null, null)
                }
            }
            val router = Exporter.Router { f ->
                when {
                    f.category == Finding.Category.THUMBNAIL && f.type.isImage -> thumbs
                    f.type.kind == FileType.Kind.IMAGE || f.type.kind == FileType.Kind.VIDEO -> album
                    else -> others
                }
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
            val report = DirectoryDestination.uniqueFile(othersDir, "informe_$stamp.csv")
            val msg = try {
                val res = Exporter().export(selected, router, report, { done, total, _ ->
                    _state.update { it.copy(exportProgress = "Exportando $done de $total…") }
                }, null)
                MediaScannerConnection.scanFile(ctx, arrayOf(report.absolutePath), null, null)
                buildString {
                    if (res.exported == 0 && res.alreadyPresent > 0) {
                        append("Nada nuevo: ya estaba todo exportado")
                    } else {
                        append("Guardado: ")
                        append(res.exportedByDestination.entries.joinToString(" · ") { (dest, count) -> "$count en $dest" })
                        if (res.alreadyPresent > 0) append(" · ${n(res.alreadyPresent, "ya estaba", "ya estaban")}")
                    }
                    if (res.failed > 0) append(" · ${n(res.failed, "falló", "fallaron")}")
                    append(". Informe: Download/Rescata/${report.name}")
                }
            } catch (e: Exception) {
                "No se pudo exportar: ${e.message}"
            }
            _state.update { it.copy(exporting = false, exportProgress = "", message = msg) }
        }
    }
}
