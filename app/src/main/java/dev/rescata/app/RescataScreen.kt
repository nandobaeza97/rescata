@file:OptIn(ExperimentalMaterial3Api::class)

package dev.rescata.app

import android.Manifest
import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.rescata.core.Finding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RescataScreen(vm: ScanViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val previews = remember { Previews.Cache() }
    var exportChoices by remember { mutableStateOf<List<StorageAccess.Volume>?>(null) }

    // Al volver de Ajustes se comprueba de nuevo si el usuario concedió el acceso.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshAccess() }

    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refreshAccess()
    }
    val legacyPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshAccess()
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onRestoreResult(it.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m, withDismissAction = true, duration = SnackbarDuration.Long)
        vm.consumeMessage()
    }

    fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            settingsLauncher.launch(StorageAccess.allFilesAccessIntent(ctx))
        } else {
            legacyPermLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
        }
    }

    fun restoreSelected() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uris = vm.selectedTrashUris()
            if (uris.isEmpty()) {
                vm.showMessage("Ninguno de los seleccionados está en la papelera del sistema")
                return
            }
            try {
                // Diálogo del sistema: el usuario confirma sacar los elementos de la papelera.
                val pi = MediaStore.createTrashRequest(ctx.contentResolver, uris, false)
                restoreLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (e: Exception) {
                vm.showMessage("No se pudo pedir la restauración: ${e.message}")
            }
        }
    }

    fun exportSelected() {
        val vols = StorageAccess.exportVolumes(ctx)
        when {
            vols.isEmpty() -> vm.showMessage("No hay ningún almacenamiento disponible para exportar")
            vols.size == 1 -> vm.export(vols.first())
            else -> exportChoices = vols
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Rescata") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.rows.isNotEmpty() && !state.scanning) {
                val anyTrash = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    state.rows.any { it.finding.id in state.selected && it.finding.externalRef != null }
                BottomAppBar {
                    TextButton(onClick = vm::selectVisible) { Text("Todos") }
                    TextButton(onClick = vm::clearSelection) { Text("Ninguno") }
                    Spacer(Modifier.weight(1f))
                    if (anyTrash) {
                        OutlinedButton(onClick = ::restoreSelected) { Text("Restaurar") }
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(
                        onClick = ::exportSelected,
                        enabled = state.selected.isNotEmpty() && !state.exporting,
                    ) { Text("Exportar (${state.selected.size})") }
                    Spacer(Modifier.width(8.dp))
                }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { LimitsCard() }
            if (!state.hasAccess) item { AccessCard(onRequest = ::requestAccess) }
            item {
                OptionsCard(state.options, enabled = !state.scanning, onChange = vm::setOptions)
            }
            item {
                ScanControls(state, onScan = vm::startScan, onCancel = vm::cancel)
            }
            if (state.rows.isNotEmpty()) {
                item { CategoryChips(state, onFilter = vm::setFilter) }
            }
            items(state.visibleRows, key = { it.finding.id }) { row ->
                FindingItem(row, row.finding.id in state.selected, previews) { vm.toggle(row.finding.id) }
                HorizontalDivider()
            }
        }
    }

    exportChoices?.let { vols ->
        AlertDialog(
            onDismissRequest = { exportChoices = null },
            title = { Text("¿Dónde exportar?") },
            text = {
                Column {
                    Text(
                        "Si tienes tarjeta SD o memoria USB, mejor exporta ahí: así no escribes en la " +
                            "memoria interna de la que intentas recuperar.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    vols.forEach { v ->
                        TextButton(onClick = { exportChoices = null; vm.export(v) }) {
                            Text(v.label + if (v.removable) " (extraíble)" else "")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { exportChoices = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun LimitsCard() {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Qué puede recuperar sin root", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Solo lo que aún existe como archivo en el almacenamiento compartido: la papelera del " +
                    "sistema (30 días), papeleras de apps y fabricantes, miniaturas que sobreviven a la " +
                    "foto, copias ocultas y cachés. Lo que ya se borró de verdad, o lo guardado en los " +
                    "datos privados de otras apps, no es accesible sin root.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AccessCard(onRequest: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Hace falta acceso a los archivos", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    "Activa «Permitir acceso para gestionar todos los archivos». La app solo lee; " +
                        "únicamente escribe en Download/Rescata cuando exportas."
                else "Concede el permiso de almacenamiento. La app solo lee; únicamente escribe al exportar.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRequest) { Text("Conceder acceso") }
        }
    }
}

@Composable
private fun OptionsCard(o: Options, enabled: Boolean, onChange: (Options) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        OptionRow("Papelera del sistema", "Archivos .trashed- y .pending- (Android 11+)", o.systemTrash, enabled) {
            onChange(o.copy(systemTrash = it))
        }
        OptionRow("Papeleras de apps", "Carpetas .Trash, RecycleBin, .recycle…", o.trashFolders, enabled) {
            onChange(o.copy(trashFolders = it))
        }
        OptionRow("Miniaturas", ".thumbnails y contenedores .thumbdata antiguos", o.thumbnails, enabled) {
            onChange(o.copy(thumbnails = it))
        }
        OptionRow("Multimedia oculto", "Carpetas ocultas, .nomedia, extensiones falsas", o.hiddenMedia, enabled) {
            onChange(o.copy(hiddenMedia = it))
        }
        OptionRow("Carving (lento)", "Busca imágenes dentro de cachés y bases de datos", o.carving, enabled) {
            onChange(o.copy(carving = it))
        }
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ScanControls(state: UiState, onScan: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (state.scanning) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(state.progressText, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onCancel) { Text("Cancelar") }
        } else {
            Button(onClick = onScan, enabled = state.hasAccess, modifier = Modifier.fillMaxWidth()) { Text("Escanear") }
        }
        if (state.exporting) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.exportProgress, style = MaterialTheme.typography.bodySmall)
        }
        state.summary?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CategoryChips(state: UiState, onFilter: (Finding.Category?) -> Unit) {
    val counts = state.rows.groupingBy { it.finding.category }.eachCount()
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        item {
            FilterChip(selected = state.filter == null, onClick = { onFilter(null) },
                label = { Text("Todos (${state.rows.size})") })
        }
        items(Finding.Category.values().filter { counts.containsKey(it) }) { c ->
            FilterChip(selected = state.filter == c, onClick = { onFilter(c) },
                label = { Text("${label(c)} (${counts[c]})") })
        }
    }
}

@Composable
private fun FindingItem(row: FindingRow, selected: Boolean, cache: Previews.Cache, onToggle: () -> Unit) {
    val f = row.finding
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Thumb(f, cache)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.suggestedName ?: "", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${label(f.category)} · ${f.type.name} · ${Formatter.formatShortFileSize(ctx, f.length)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (f.expiresEpochSeconds > 0) {
                Text("Caduca el ${formatDate(f.expiresEpochSeconds * 1000)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (row.orphanThumbnail) {
                Text("No queda ningún elemento en MediaStore con este ID: el original probablemente se borró",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            f.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text(
                if (f.isWholeFile) f.sourcePath else "${f.sourcePath} @ 0x${f.offset.toString(16)}",
                style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Thumb(f: Finding, cache: Previews.Cache) {
    val px = with(LocalDensity.current) { 56.dp.roundToPx() }
    val bmp by produceState<Bitmap?>(initialValue = cache.get(f.id), f.id) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { Previews.load(f, px) }?.also { cache.put(f.id, it) }
        }
    }
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(f.type.defaultExtension.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun label(c: Finding.Category): String = when (c) {
    Finding.Category.SYSTEM_TRASH -> "Papelera del sistema"
    Finding.Category.PENDING -> "Pendiente"
    Finding.Category.TRASH_FOLDER -> "Papelera de app"
    Finding.Category.THUMBNAIL -> "Miniatura"
    Finding.Category.HIDDEN_MEDIA -> "Oculto"
    Finding.Category.CARVED -> "Carving"
}

private fun formatDate(millis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(millis))
