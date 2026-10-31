@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package dev.rescata.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.rescata.core.FileType
import dev.rescata.core.Finding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

// -------------------------------------------------------------------------------------------------
// Pantalla principal. Solo presentación: la lógica (escaneo, exportación, restauración) vive en
// ScanViewModel y en el motor, y no cambia.
// -------------------------------------------------------------------------------------------------

@Composable
fun RescataScreen(vm: ScanViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val previews = remember { Previews.Cache() }
    var exportChoices by remember { mutableStateOf<List<StorageAccess.Volume>?>(null) }
    var details by remember { mutableStateOf<FindingRow?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var limitsOpen by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

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
    // Android 13+: se pide una sola vez el permiso para mostrar el progreso en una notificación.
    // Pase lo que pase, la acción se ejecuta igual.
    var afterNotificationPrompt by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterNotificationPrompt?.invoke()
        afterNotificationPrompt = null
    }

    fun withNotifications(action: () -> Unit) {
        val prefs = ctx.getSharedPreferences("rescata", Context.MODE_PRIVATE)
        val needsPrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("aviso_notificaciones", false)
        if (needsPrompt) {
            prefs.edit().putBoolean("aviso_notificaciones", true).apply()
            afterNotificationPrompt = action
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
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

    fun exportSelected() = withNotifications {
        val vols = StorageAccess.exportVolumes(ctx)
        when {
            vols.isEmpty() -> vm.showMessage("No hay ningún almacenamiento disponible para guardar")
            vols.size == 1 -> vm.export(vols.first())
            else -> exportChoices = vols
        }
        Unit
    }

    // Secciones de resultados (cuadrícula para fotos y vídeos, lista para lo demás)
    val blocks = remember(state.visibleRows) { buildBlocks(state.visibleRows) }
    val tilePx = with(LocalDensity.current) {
        ((LocalConfiguration.current.screenWidthDp.dp - 44.dp) / 3).roundToPx().coerceIn(96, 512)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Rescata", fontWeight = FontWeight.Medium) },
                navigationIcon = {
                    Image(
                        painterResource(R.drawable.ic_logo), contentDescription = null,
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp).size(32.dp),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.rows.isNotEmpty() && !state.scanning) {
                SelectionBar(
                    state = state,
                    onSelectAll = vm::selectVisible,
                    onClear = vm::clearSelection,
                    onRestore = ::restoreSelected,
                    onSave = ::exportSelected,
                )
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            modifier = Modifier.fillMaxSize().testTag("resultados"),
        ) {
            item(key = "hero") {
                Hero(
                    state,
                    onScan = {
                        // Se pliegan los ajustes para que los resultados aparezcan justo debajo
                        settingsOpen = false
                        limitsOpen = false
                        withNotifications { vm.startScan() }
                    },
                    onCancel = vm::cancel,
                )
            }
            if (!state.hasAccess) item(key = "access") { AccessCard(onRequest = ::requestAccess) }
            item(key = "settings") {
                Expandable(
                    title = "Ajustes de búsqueda",
                    icon = painterResource(R.drawable.ic_tune),
                    expanded = settingsOpen,
                    onToggle = { settingsOpen = !settingsOpen },
                ) { OptionsList(state.options, enabled = !state.scanning, onChange = vm::setOptions) }
            }
            item(key = "limits") {
                Expandable(
                    title = "Qué puede recuperar sin root",
                    icon = rememberVectorPainter(Icons.Outlined.Info),
                    expanded = limitsOpen,
                    onToggle = { limitsOpen = !limitsOpen },
                ) { LimitsText() }
            }
            if (state.summary != null || state.exporting) item(key = "summary") { SummarySection(state) }
            if (state.summary != null && state.rows.isEmpty() && !state.scanning) item(key = "empty") { EmptyResults() }
            if (state.rows.isNotEmpty()) {
                item(key = "chips") { CategoryChips(state, onFilter = vm::setFilter) }
                if (state.filter == Finding.Category.WHATSAPP_BACKUP) item(key = "wahelp") { WhatsAppRestoreHelp() }
            }
            items(blocks, key = { it.key }) { block ->
                when (block) {
                    is Block.Header -> SectionHeader(
                        block.category, block.count,
                        onHelp = if (block.category == Finding.Category.WHATSAPP_BACKUP &&
                            state.filter != Finding.Category.WHATSAPP_BACKUP
                        ) ({ vm.setFilter(Finding.Category.WHATSAPP_BACKUP) }) else null,
                    )
                    is Block.Tiles -> TileRow(block.rows, state.selected, previews, tilePx, vm::toggle) { details = it }
                    is Block.Item -> FindingListItem(block.row, block.row.finding.id in state.selected, vm::toggle) {
                        details = it
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }

    exportChoices?.let { vols ->
        AlertDialog(
            onDismissRequest = { exportChoices = null },
            title = { Text("¿Dónde guardar?") },
            text = {
                Column {
                    Text(
                        "Las fotos y vídeos irán al álbum «Rescata» de la galería y el resto a Download/Rescata. " +
                            "Si tienes tarjeta SD o memoria USB, mejor elige esa: así no escribes en la " +
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

    details?.let { row ->
        DetailsSheet(
            row = row,
            selected = row.finding.id in state.selected,
            onToggle = { vm.toggle(row.finding.id) },
            onDismiss = { details = null },
        )
    }
}

// ------------------------------------------------------------------------------------ Inicio

@Composable
private fun Hero(state: UiState, onScan: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            "Recupera tus fotos y archivos borrados",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Busca en la papelera, en las miniaturas y en las copias que quedan en tu móvil.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        if (state.scanning) {
            LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
            Spacer(Modifier.height(10.dp))
            Text(
                state.progressText, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onCancel) { Text("Cancelar") }
        } else {
            Button(
                onClick = onScan,
                enabled = state.hasAccess,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CircleShape,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.summary == null) "Buscar archivos borrados" else "Buscar de nuevo",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val o = state.options
            if (o.systemTrash || o.trashFolders) Pill(rememberVectorPainter(Icons.Filled.Delete), "Papelera")
            if (o.thumbnails) Pill(painterResource(R.drawable.ic_image), "Miniaturas")
            if (o.hiddenMedia) Pill(painterResource(R.drawable.ic_visibility_off), "Ocultos")
            if (o.whatsappBackups || o.whatsappMedia) Pill(painterResource(R.drawable.ic_chat), "WhatsApp")
            if (o.carving) Pill(painterResource(R.drawable.ic_folder), "Cachés")
        }
    }
}

@Composable
private fun Pill(icon: Painter, text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun AccessCard(onRequest: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Hace falta acceso a los archivos", fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.height(4.dp))
            Text(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    "Activa «Permitir acceso para gestionar todos los archivos». La app solo lee; " +
                        "únicamente escribe cuando guardas (álbum «Rescata» de la galería y Download/Rescata)."
                else "Concede el permiso de almacenamiento. La app solo lee; únicamente escribe al guardar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onRequest, shape = CircleShape) { Text("Conceder acceso") }
        }
    }
}

@Composable
private fun Expandable(
    title: String,
    icon: Painter,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 12.dp)) { content() }
            }
        }
    }
}

@Composable
private fun OptionsList(o: Options, enabled: Boolean, onChange: (Options) -> Unit) {
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
    OptionRow("Copias de chats de WhatsApp", "Copias locales para guardarlas o restaurarlas", o.whatsappBackups, enabled) {
        onChange(o.copy(whatsappBackups = it))
    }
    OptionRow("Multimedia de WhatsApp", "Todas las fotos, vídeos y audios que guarda WhatsApp", o.whatsappMedia, enabled) {
        onChange(o.copy(whatsappMedia = it))
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    // Toda la fila es pulsable (no solo el interruptor), más cómodo en el móvil.
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun LimitsText() {
    Text(
        "Solo lo que aún existe como archivo en el almacenamiento compartido: la papelera del " +
            "sistema (30 días), papeleras de apps y fabricantes, miniaturas que sobreviven a la " +
            "foto, copias ocultas y cachés. Lo que ya se borró de verdad, o lo guardado en los " +
            "datos privados de otras apps, no es accesible sin root.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ------------------------------------------------------------------------------------ Resultados

@Composable
private fun SummarySection(state: UiState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        if (state.rows.isNotEmpty()) {
            val soon = state.rows.count {
                it.finding.category == Finding.Category.SYSTEM_TRASH || it.finding.category == Finding.Category.PENDING
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("Encontrados", state.rows.size.toString(), MaterialTheme.colorScheme.surfaceContainerHigh,
                    MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                if (soon > 0) {
                    Metric("Se borran pronto", soon.toString(), MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.onTertiaryContainer, Modifier.weight(1f))
                } else {
                    Metric("Seleccionados", state.selected.size.toString(), MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (state.exporting) {
            LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
            Spacer(Modifier.height(6.dp))
            Text(state.exportProgress, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
        }
        state.summary?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, container: Color, content: Color, modifier: Modifier) {
    Surface(shape = MaterialTheme.shapes.medium, color = container, modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = content)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, color = content)
        }
    }
}

@Composable
private fun EmptyResults() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(R.drawable.ic_image), contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text("No hay nada que recuperar", style = MaterialTheme.typography.titleMedium)
        Text(
            "Prueba a activar más opciones en «Ajustes de búsqueda».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CategoryChips(state: UiState, onFilter: (Finding.Category?) -> Unit) {
    val counts = state.rows.groupingBy { it.finding.category }.eachCount()
    LazyRow(
        modifier = Modifier.testTag("categorias"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        item {
            FilterChip(selected = state.filter == null, onClick = { onFilter(null) },
                label = { Text("Todos (${state.rows.size})") })
        }
        items(Finding.Category.values().filter { counts.containsKey(it) }) { c ->
            FilterChip(
                selected = state.filter == c, onClick = { onFilter(c) },
                label = { Text("${label(c)} (${counts[c]})") },
                leadingIcon = { Icon(categoryIcon(c), contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

/** Pasos oficiales de WhatsApp para restaurar una copia local (faq.whatsapp.com). */
@Composable
private fun WhatsAppRestoreHelp() {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Cómo recuperar chats con una copia", fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.height(4.dp))
            Text(
                "Estas copias están cifradas: solo WhatsApp puede abrirlas. WhatsApp guarda las de los " +
                    "últimos 7 días, así que guarda las que te interesen para no perderlas.\n\n" +
                    "Para restaurar una: con un gestor de archivos, en WhatsApp › Databases, renombra la copia " +
                    "del día anterior al borrado a msgstore.db.cryptNN (sin cambiar el número), desinstala y " +
                    "reinstala WhatsApp y pulsa «Restaurar». Perderás lo recibido después de esa fecha y, si " +
                    "tienes copia en la nube, WhatsApp puede ofrecerte esa en su lugar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun SectionHeader(category: Finding.Category, count: Int, onHelp: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val danger = category == Finding.Category.SYSTEM_TRASH || category == Finding.Category.PENDING
        Icon(
            categoryIcon(category), contentDescription = null, modifier = Modifier.size(20.dp),
            tint = if (danger) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(label(category), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (onHelp != null) TextButton(onClick = onHelp) { Text("Cómo restaurar") }
        Text("$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp))
    }
}

@Composable
private fun TileRow(
    rows: List<FindingRow>,
    selected: Set<Long>,
    cache: Previews.Cache,
    tilePx: Int,
    onToggle: (Long) -> Unit,
    onDetails: (FindingRow) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { r ->
            Tile(r, r.finding.id in selected, cache, tilePx, Modifier.weight(1f), onToggle, onDetails)
        }
        repeat(3 - rows.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** Una foto o vídeo en la cuadrícula: toca para marcar, mantén pulsado para ver los detalles. */
@Composable
private fun Tile(
    row: FindingRow,
    selected: Boolean,
    cache: Previews.Cache,
    tilePx: Int,
    modifier: Modifier,
    onToggle: (Long) -> Unit,
    onDetails: (FindingRow) -> Unit,
) {
    val f = row.finding
    val desc = "${f.suggestedName ?: ""}, ${label(f.category)}"
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(3.dp, primary, RoundedCornerShape(14.dp)) else Modifier)
            .combinedClickable(onClick = { onToggle(f.id) }, onLongClick = { onDetails(row) })
            .semantics {
                contentDescription = desc
                this.selected = selected
            },
    ) {
        Thumb(f, cache, tilePx, Modifier.fillMaxSize())
        if (f.type.kind == FileType.Kind.VIDEO) {
            Icon(
                Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(36.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)).padding(4.dp),
            )
        }
        // Marca de selección arriba a la derecha
        Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape)
                .background(if (selected) primary else Color.Black.copy(alpha = 0.25f))
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp))
        }
        // Información (abre los detalles) arriba a la izquierda
        Box(
            Modifier.align(Alignment.TopStart).padding(4.dp).size(28.dp).clip(CircleShape)
                .clickable { onDetails(row) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Info, contentDescription = "Detalles", tint = Color.White,
                modifier = Modifier.size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.3f)))
        }
        tileBadge(row)?.let { (text, urgent) ->
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = if (urgent) MaterialTheme.colorScheme.onTertiary else Color.White,
                maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (urgent) MaterialTheme.colorScheme.tertiary else Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Thumb(f: Finding, cache: Previews.Cache, targetPx: Int, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(initialValue = cache.get(f.id), f.id) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { Previews.load(f, targetPx) }?.also { cache.put(f.id, it) }
        }
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(typeIcon(f), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp))
        }
    }
}

/** Fila de lista para lo que no es foto ni vídeo (copias de chats, documentos, audios…). */
@Composable
private fun FindingListItem(row: FindingRow, selected: Boolean, onToggle: (Long) -> Unit, onDetails: (FindingRow) -> Unit) {
    val f = row.finding
    val ctx = LocalContext.current
    val size = Formatter.formatShortFileSize(ctx, f.length)
    val isBackup = f.category == Finding.Category.WHATSAPP_BACKUP
    val headline = if (isBackup) whatsappBackupTitle(f) else f.suggestedName ?: ""
    val support = if (isBackup) "Copia cifrada · $size" else listOfNotNull(typeName(f), size).joinToString(" · ")
    ListItem(
        headlineContent = { Text(headline, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(support, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Nombre del archivo de la copia en una línea aparte (se recorta con «…» si no cabe)
                if (isBackup) {
                    Text(f.suggestedName ?: "", style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        leadingContent = {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(typeIcon(f), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onDetails(row) }) { Icon(Icons.Outlined.Info, contentDescription = "Detalles") }
                SelectionMark(selected)
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent,
        ),
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable { onToggle(f.id) }
            .semantics { this.selected = selected },
    )
}

@Composable
private fun SelectionMark(selected: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(24.dp).clip(CircleShape)
            .background(if (selected) primary else Color.Transparent)
            .border(2.dp, if (selected) primary else MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SelectionBar(
    state: UiState,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onRestore: () -> Unit,
    onSave: () -> Unit,
) {
    val anyTrash = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        state.rows.any { it.finding.id in state.selected && it.finding.externalRef != null }
    BottomAppBar {
        // Un solo botón que alterna: ocupa menos y la barra cabe en pantallas estrechas.
        val allSelected = state.visibleRows.isNotEmpty() && state.visibleRows.all { it.finding.id in state.selected }
        TextButton(onClick = if (allSelected) onClear else onSelectAll) {
            Text(if (allSelected) "Ninguno" else "Todos", maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        if (anyTrash) {
            OutlinedButton(onClick = onRestore, contentPadding = compactPadding, shape = CircleShape) {
                Text("Restaurar", maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
        }
        Button(
            onClick = onSave,
            enabled = state.selected.isNotEmpty() && !state.exporting,
            contentPadding = compactPadding,
            shape = CircleShape,
        ) {
            Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Guardar (${state.selected.size})", maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun DetailsSheet(row: FindingRow, selected: Boolean, onToggle: () -> Unit, onDismiss: () -> Unit) {
    val f = row.finding
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(f.suggestedName ?: "", style = MaterialTheme.typography.titleLarge)
            Text(
                listOfNotNull(label(f.category), typeName(f), Formatter.formatShortFileSize(ctx, f.length)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (f.expiresEpochSeconds > 0) {
                Text("Se borrará el ${formatDate(f.expiresEpochSeconds * 1000)}",
                    color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
            }
            if (row.orphanThumbnail) {
                Text("No queda ningún elemento en MediaStore con este ID: el original probablemente se borró",
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
            }
            f.note?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
            }
            if (f.lastModified > 0) {
                Text("Fecha del archivo: ${formatDate(f.lastModified)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Text("Ubicación", style = MaterialTheme.typography.labelMedium)
            Text(
                if (f.isWholeFile) f.sourcePath else "${f.sourcePath} @ 0x${f.offset.toString(16)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = { onToggle(); onDismiss() }, modifier = Modifier.fillMaxWidth(), shape = CircleShape) {
                Text(if (selected) "Quitar de la selección" else "Seleccionar")
            }
        }
    }
}

// ------------------------------------------------------------------------------------ Modelo de la lista

private sealed class Block(val key: String) {
    class Header(val category: Finding.Category, val count: Int) : Block("h_${category.name}")
    class Tiles(val rows: List<FindingRow>) : Block("t_${rows.first().finding.id}")
    class Item(val row: FindingRow) : Block("l_${row.finding.id}")
}

private fun isGalleryMedia(f: Finding) = f.type.kind == FileType.Kind.IMAGE || f.type.kind == FileType.Kind.VIDEO

/** Agrupa por categoría: fotos y vídeos en filas de 3, el resto como elementos de lista. */
private fun buildBlocks(rows: List<FindingRow>): List<Block> {
    val out = ArrayList<Block>()
    val byCategory = rows.groupBy { it.finding.category }
    for (c in Finding.Category.values()) {
        val list = byCategory[c] ?: continue
        out += Block.Header(c, list.size)
        val (media, others) = list.partition { isGalleryMedia(it.finding) }
        media.chunked(3).forEach { out += Block.Tiles(it) }
        others.forEach { out += Block.Item(it) }
    }
    return out
}

private fun tileBadge(row: FindingRow): Pair<String, Boolean>? {
    val f = row.finding
    return when {
        f.category == Finding.Category.SYSTEM_TRASH && f.expiresEpochSeconds > 0 -> {
            val days = ceil((f.expiresEpochSeconds * 1000 - System.currentTimeMillis()) / 86_400_000.0).toInt()
            (if (days <= 1) "Último día" else "Quedan $days días") to true
        }
        f.category == Finding.Category.PENDING -> "Sin terminar" to true
        row.orphanThumbnail -> "Sin original" to false
        else -> null
    }
}

private fun label(c: Finding.Category): String = when (c) {
    Finding.Category.SYSTEM_TRASH -> "En la papelera"
    Finding.Category.PENDING -> "Sin terminar"
    Finding.Category.TRASH_FOLDER -> "Papeleras de apps"
    Finding.Category.THUMBNAIL -> "Miniaturas"
    Finding.Category.HIDDEN_MEDIA -> "Ocultos"
    Finding.Category.CARVED -> "Dentro de cachés"
    Finding.Category.WHATSAPP_BACKUP -> "Copias de WhatsApp"
    Finding.Category.WHATSAPP_MEDIA -> "WhatsApp"
}

@Composable
private fun categoryIcon(c: Finding.Category): Painter = when (c) {
    Finding.Category.SYSTEM_TRASH, Finding.Category.TRASH_FOLDER -> rememberVectorPainter(Icons.Filled.Delete)
    Finding.Category.PENDING -> rememberVectorPainter(Icons.Filled.Warning)
    Finding.Category.THUMBNAIL -> painterResource(R.drawable.ic_image)
    Finding.Category.HIDDEN_MEDIA -> painterResource(R.drawable.ic_visibility_off)
    Finding.Category.CARVED -> painterResource(R.drawable.ic_folder)
    Finding.Category.WHATSAPP_BACKUP, Finding.Category.WHATSAPP_MEDIA -> painterResource(R.drawable.ic_chat)
}

@Composable
private fun typeIcon(f: Finding): Painter = when {
    f.category == Finding.Category.WHATSAPP_BACKUP -> painterResource(R.drawable.ic_chat)
    f.type.kind == FileType.Kind.IMAGE -> painterResource(R.drawable.ic_image)
    f.type.kind == FileType.Kind.VIDEO -> rememberVectorPainter(Icons.Filled.PlayArrow)
    f.type.kind == FileType.Kind.AUDIO -> painterResource(R.drawable.ic_audio)
    else -> painterResource(R.drawable.ic_document)
}

private fun typeName(f: Finding): String? = f.type.name.takeIf { f.type != FileType.UNKNOWN }

private val WA_DATE = Regex("""(?i)^msgstore-(\d{4})-(\d{2})-(\d{2})""")

private fun whatsappBackupTitle(f: Finding): String {
    val m = WA_DATE.find(f.suggestedName ?: "") ?: return "Copia más reciente de los chats"
    val (y, mo, d) = m.destructured
    return "Chats del $d/$mo/$y"
}

private val compactPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

private fun formatDate(millis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(millis))
