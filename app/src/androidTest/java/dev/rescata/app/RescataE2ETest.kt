package dev.rescata.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.rescata.core.Finding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Prueba de extremo a extremo en un Android real (emulador): crea archivos "borrados" de verdad,
 * abre la app, escanea, exporta, comprueba byte a byte lo recuperado y restaura desde la papelera.
 * Deja capturas de pantalla en /sdcard/Download/rescata-capturas.
 */
@RunWith(AndroidJUnit4::class)
class RescataE2ETest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val device = UiDevice.getInstance(instr)

    @Suppress("DEPRECATION")
    private val root: File = Environment.getExternalStorageDirectory()
    private val pics = File(root, "Pictures/RescataTest")
    private val dl = File(root, "Download/RescataTest")
    private val thumb = File(root, "Pictures/.thumbnails/999999.jpg")
    private val shots = File(root, "Download/rescata-capturas")
    private val album = File(root, "Pictures/Rescata")
    private val albumThumbs = File(root, "Pictures/Rescata – Miniaturas")
    private val othersDir = File(root, "Download/Rescata")
    private val waRoot = File(root, "Android/media/com.whatsapp/WhatsApp")
    private val waBackupName = "msgstore-2026-10-29.1.db.crypt15"
    private val waImageName = "IMG-20261029-WA0001.jpg"
    private val trashSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    private val orphanSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    private var pngHiddenMtime = 0L

    private val jpegTrash = image(Color.rgb(200, 40, 40), Bitmap.CompressFormat.JPEG, "papelera")
    private val jpegThumb = image(Color.rgb(40, 160, 60), Bitmap.CompressFormat.JPEG, "miniatura")
    private val pngHidden = image(Color.rgb(40, 80, 200), Bitmap.CompressFormat.PNG, "oculta")
    // Con fecha EXIF: debe aparecer en la galería el 1 de mayo de 2021, no el día de la exportación
    private val jpegVendorTrash = withExifDate(image(Color.rgb(220, 180, 20), Bitmap.CompressFormat.JPEG, "app"), "2021:05:01 10:00:00")
    private val pngEmbedded = image(Color.rgb(180, 40, 180), Bitmap.CompressFormat.PNG, "carving")
    private val jpegWa = image(Color.rgb(37, 211, 102), Bitmap.CompressFormat.JPEG, "whatsapp")
    private val waBackup = junk(3000)

    @Before
    fun prepare() {
        val pkg = ctx.packageName
        if (trashSupported) {
            shell("appops set --uid $pkg MANAGE_EXTERNAL_STORAGE allow")
        } else {
            shell("pm grant $pkg android.permission.READ_EXTERNAL_STORAGE")
            shell("pm grant $pkg android.permission.WRITE_EXTERNAL_STORAGE")
        }
        assertTrue("La app no tiene acceso al almacenamiento", StorageAccess.hasFullAccess(ctx))

        listOf(pics, dl, thumb, shots, album, albumThumbs, othersDir, waRoot).forEach { it.deleteRecursively() }
        shell("rm -r ${waRoot.absolutePath}")
        assertTrue("No se pudo crear la carpeta de capturas", shots.mkdirs())

        // Una foto normal en MediaStore (viva: no debe aparecer). Garantiza que MediaStore tiene IDs.
        insertPhoto("foto_viva.jpg", image(Color.GRAY, Bitmap.CompressFormat.JPEG, "viva"))

        // Foto enviada a la papelera del sistema por el mecanismo real de Android (11+).
        if (trashSupported) {
            val uri = insertPhoto("foto_papelera.jpg", jpegTrash)
            val n = ctx.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_TRASHED, 1)
            }, null, null)
            assertEquals(1, n)
            val names = pics.list()?.toList().orEmpty()
            assertTrue(
                "Android no renombró el archivo a .trashed-: $names",
                names.any { Regex("""\.trashed-\d+-foto_papelera\.jpg""").matches(it) },
            )
        }

        write(thumb, jpegThumb)                                   // miniatura cuyo original no existe
        // PNG oculto y sin extensión; sin EXIF, así que su fecha en la galería debe ser la del archivo (15/01/2020)
        val hidden = File(dl, ".oculta/sin_extension")
        write(hidden, pngHidden)
        hidden.setLastModified(1579089600000L)
        pngHiddenMtime = hidden.lastModified()
        write(File(dl, ".Trash/borrada.jpg"), jpegVendorTrash)    // papelera de una app
        write(File(dl, "rescata_cache.bin"), junk(6000) + pngEmbedded + junk(4000)) // imagen dentro de una caché
        write(File(dl, "visible.jpg"), image(Color.DKGRAY, Bitmap.CompressFormat.JPEG, "visible")) // viva

        // WhatsApp: una copia local de chats y una foto guardada por WhatsApp
        writeShared(File(waRoot, "Databases/$waBackupName"), waBackup)
        writeShared(File(waRoot, "Media/WhatsApp Images/$waImageName"), jpegWa)
    }

    @Test
    fun escaneaExportaYRestaura() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            shot("01_inicio")

            // Activar carving y multimedia de WhatsApp pulsando la fila de cada opción
            compose.onNodeWithText("Carving (lento)").performClick()
            compose.onNodeWithTag("resultados").performScrollToNode(hasText("Multimedia de WhatsApp"))
            compose.onNodeWithText("Multimedia de WhatsApp").performClick()
            compose.onNodeWithTag("resultados").performScrollToNode(hasText("Escanear"))
            compose.onNodeWithText("Escanear").performClick()
            // Se espera al estado de la app (no se sondea el árbol de la UI en bucle: provoca carreras con Espresso)
            waitFor(180_000, "fin del escaneo") { vm(scenario) { s -> !s.scanning && s.summary != null } }
            compose.waitForIdle()
            compose.onNodeWithText("hallazgo", substring = true).assertIsDisplayed()
            shot("02_resultados")

            // Cada caso aparece en la lista
            val expected = mutableListOf("borrada.jpg", "miniatura_999999.jpg", "sin_extension", "rescata_cache_0x",
                waImageName, waBackupName)
            if (trashSupported) expected += "foto_papelera.jpg"
            for (t in expected) {
                compose.onNodeWithTag("resultados").performScrollToNode(hasText(t, substring = true))
                compose.onNodeWithText(t, substring = true).assertIsDisplayed()
            }
            if (orphanSupported) {
                compose.onNodeWithTag("resultados").performScrollToNode(hasText("miniatura_999999.jpg", substring = true))
                compose.onNode(
                    hasText("miniatura_999999.jpg", substring = true) and
                        hasText("No queda ningún elemento en MediaStore", substring = true)
                ).assertIsDisplayed()
                shot("03_miniatura_huerfana")
            }

            // Filtro «Copia de WhatsApp» con las instrucciones para restaurar, y vuelta a «Todos»
            // Filtro «Copia de WhatsApp»: se aplica a través de la app (desplazar con el test la fila de chips,
            // que está dentro de la lista, se cuelga en Android 11+ por una limitación de la herramienta de pruebas)
            setFilter(scenario, Finding.Category.WHATSAPP_BACKUP)
            waitFor(10_000, "filtro «Copia de WhatsApp»") {
                vm(scenario) { s -> s.filter == Finding.Category.WHATSAPP_BACKUP && s.visibleRows.size == 1 }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("resultados").performScrollToNode(hasText("Cómo recuperar chats con una copia"))
            compose.onNodeWithText("Cómo recuperar chats con una copia").assertIsDisplayed()
            compose.onNodeWithTag("resultados")
                .performScrollToNode(hasText("Copia de los chats de WhatsApp del 29/10/2026", substring = true))
            compose.onNodeWithText("Copia de los chats de WhatsApp del 29/10/2026", substring = true).assertIsDisplayed()
            shot("03c_copias_whatsapp")
            setFilter(scenario, null)
            waitFor(10_000, "volver a «Todos»") { vm(scenario) { s -> s.filter == null } }
            compose.waitForIdle()

            // Exportar todo
            compose.onNodeWithText("Todos").performClick()
            compose.onNodeWithText("Exportar", substring = true).performClick()
            if (compose.onAllNodesWithText("¿Dónde exportar?").fetchSemanticsNodes().isNotEmpty()) {
                val label = StorageAccess.exportVolumes(ctx).first { it.primary }.label
                compose.onNodeWithText(label, substring = true).performClick()
            }
            waitFor(120_000, "fin de la exportación") { vm(scenario) { s -> !s.exporting } && reports().size == 1 }
            compose.waitForIdle()
            shot("04_exportado")
            verifyExport(reports().single())

            // Exportar otra vez lo mismo: no debe duplicar nada en la galería
            val inAlbum = album.list()?.size ?: 0
            compose.onNodeWithText("Exportar", substring = true).performClick()
            if (compose.onAllNodesWithText("¿Dónde exportar?").fetchSemanticsNodes().isNotEmpty()) {
                val label = StorageAccess.exportVolumes(ctx).first { it.primary }.label
                compose.onNodeWithText(label, substring = true).performClick()
            }
            waitFor(120_000, "fin de la segunda exportación") { vm(scenario) { s -> !s.exporting } && reports().size == 2 }
            compose.waitForIdle()
            shot("04b_sin_duplicados")
            val second = readRows(reports().last())
            assertTrue(second.isNotEmpty())
            second.forEach { assertEquals("Segunda exportación de ${it[3]}", "ya existía", it[10]) }
            assertEquals("La segunda exportación creó copias nuevas", inAlbum, album.list()?.size ?: 0)

            // Restaurar en su sitio la foto de la papelera (diálogo del sistema)
            if (trashSupported) {
                compose.onNodeWithText("Ninguno").performClick()
                compose.onNodeWithTag("resultados").performScrollToNode(hasText("foto_papelera.jpg", substring = true))
                // Acción semántica de clic: no depende de que el centro de la fila quede bajo la barra superior
                compose.onNodeWithText("foto_papelera.jpg", substring = true)
                    .performSemanticsAction(SemanticsActions.OnClick)
                compose.onNodeWithText("Restaurar").performClick()
                val allow = device.wait(
                    Until.findObject(By.text(Pattern.compile("(?i)^(allow|permitir|restore|restaurar)$"))), 20_000
                )
                shot("05_dialogo_restaurar")
                assertNotNull("No apareció el diálogo del sistema para restaurar", allow)
                allow.click()
                val restored = File(pics, "foto_papelera.jpg")
                val deadline = System.currentTimeMillis() + 20_000
                while (!restored.exists() && System.currentTimeMillis() < deadline) Thread.sleep(250)
                compose.waitForIdle()
                shot("06_restaurado")
                assertTrue("La foto no volvió a su ubicación original", restored.exists())
                assertArrayEquals(jpegTrash, restored.readBytes())
                waitFor(10_000, "que la foto restaurada salga de la lista") {
                    vm(scenario) { s -> s.rows.none { it.finding.category == Finding.Category.SYSTEM_TRASH } }
                }
                compose.waitForIdle()
                shot("07_lista_actualizada")
            }
        }
    }

    private fun <T> vm(scenario: ActivityScenario<MainActivity>, read: (UiState) -> T): T {
        var result: Result<T>? = null
        scenario.onActivity { act ->
            result = runCatching { read(ViewModelProvider(act)[ScanViewModel::class.java].state.value) }
        }
        return result!!.getOrThrow()
    }

    private fun setFilter(scenario: ActivityScenario<MainActivity>, c: Finding.Category?) {
        scenario.onActivity { act -> ViewModelProvider(act)[ScanViewModel::class.java].setFilter(c) }
    }

    private fun waitFor(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Tiempo agotado esperando: $what")
            Thread.sleep(200)
        }
    }

    private fun reports(): List<File> =
        othersDir.listFiles()?.filter { it.name.startsWith("informe_") && it.name.endsWith(".csv") }
            ?.sortedBy { it.name }.orEmpty()

    private fun readRows(report: File): List<List<String>> =
        report.readText(Charsets.UTF_8).removePrefix("﻿").split("\r\n")
            .filter { it.isNotBlank() }.drop(1).map { it.split(";") }

    private fun verifyExport(report: File) {
        val rows = readRows(report)
        assertTrue("El informe está vacío", rows.isNotEmpty())
        for (cols in rows) {
            assertEquals("Estado de ${cols[3]}: ${cols.getOrNull(14)}", "exportado", cols[10])
            val copy = File(cols[12])
            assertTrue("Falta la copia ${cols[12]}", copy.isFile)
            assertEquals("SHA-256 de ${copy.name}", cols[6], sha256(copy.readBytes()))
            assertFalse("Se exportó un archivo vivo: ${cols[3]}", cols[3].endsWith("visible.jpg"))
            assertFalse("Se exportó un archivo vivo: ${cols[3]}", cols[3].endsWith("/foto_viva.jpg"))
        }
        // Fotos en el álbum «Rescata», miniatura en «Rescata – Miniaturas», byte a byte
        assertArrayEquals(jpegVendorTrash, File(album, "borrada.jpg").readBytes())
        assertArrayEquals(pngHidden, File(album, "sin_extension.png").readBytes())
        assertArrayEquals(pngEmbedded, File(album, "rescata_cache_0x1770.png").readBytes())
        assertArrayEquals(jpegThumb, File(albumThumbs, "miniatura_999999.jpg").readBytes())
        if (trashSupported) assertArrayEquals(jpegTrash, File(album, "foto_papelera.jpg").readBytes())
        assertFalse("La miniatura no debe ir al álbum principal", File(album, "miniatura_999999.jpg").exists())
        assertArrayEquals(jpegWa, File(album, waImageName).readBytes())
        assertArrayEquals(waBackup, File(othersDir, waBackupName).readBytes())

        // Indexadas en la galería y con su fecha original (EXIF o fecha del archivo)
        assertEquals("Fecha en la galería de borrada.jpg", "2021-05-01", galleryDay(File(album, "borrada.jpg")))
        assertEquals("Fecha en la galería de sin_extension.png", day(pngHiddenMtime), galleryDay(File(album, "sin_extension.png")))
        assertNotNull("La miniatura no está en la galería", galleryDay(File(albumThumbs, "miniatura_999999.jpg")))
    }

    /** Día (yyyy-MM-dd, hora local) con el que la galería ordenará el archivo según MediaStore. */
    @Suppress("DEPRECATION")
    private fun galleryDay(file: File): String? {
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Images.ImageColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED)
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            ctx.contentResolver.query(uri, projection, "${MediaStore.MediaColumns.DATA}=?", arrayOf(file.absolutePath), null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val taken = if (c.isNull(0)) 0L else c.getLong(0)
                        val millis = if (taken > 0) taken else c.getLong(1) * 1000
                        return day(millis)
                    }
                }
            Thread.sleep(500) // en Android 8-9 el indexado es asíncrono
        }
        return null
    }

    private fun day(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(millis))

    private fun withExifDate(jpeg: ByteArray, date: String): ByteArray {
        val tmp = File.createTempFile("exif", ".jpg", ctx.cacheDir)
        try {
            tmp.writeBytes(jpeg)
            ExifInterface(tmp.absolutePath).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date)
                saveAttributes()
            }
            return tmp.readBytes()
        } finally {
            tmp.delete()
        }
    }

    private fun insertPhoto(name: String, bytes: ByteArray): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 8-9: sin RELATIVE_PATH; se escribe el archivo y se indexa
            val f = File(pics, name)
            write(f, bytes)
            val latch = CountDownLatch(1)
            var scanned: Uri? = null
            MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf("image/jpeg")) { _, u ->
                scanned = u
                latch.countDown()
            }
            assertTrue("No se indexó $name", latch.await(30, TimeUnit.SECONDS))
            return scanned ?: throw AssertionError("Sin URI para $name")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/RescataTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("No se pudo insertar $name en MediaStore", uri)
        resolver.openOutputStream(uri!!)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun shot(name: String) {
        device.waitForIdle()
        device.takeScreenshot(File(shots, "$name.png"))
    }

    private fun shell(cmd: String) {
        val pfd = instr.uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    /**
     * Escribe en una carpeta de otra app dentro de Android/media. Si el sistema no deja a la app,
     * se usa el shell (como haría adb push) en una ruta sin espacios.
     */
    private fun writeShared(f: File, bytes: ByteArray) {
        var target = f
        try {
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
        } catch (e: Exception) {
            // se intenta con el shell
        }
        if (!(f.isFile && f.length() == bytes.size.toLong())) {
            target = File(f.parentFile!!.parentFile, f.parentFile!!.name.replace(" ", "") + "/" + f.name)
            val tmp = File(dl, "tmp_" + f.name)
            write(tmp, bytes)
            shell("mkdir -p ${target.parentFile!!.absolutePath}")
            shell("cp ${tmp.absolutePath} ${target.absolutePath}")
            tmp.delete()
        }
        assertTrue("No se pudo preparar $target", target.isFile && target.length() == bytes.size.toLong())
    }

    private fun write(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        assertTrue("No se pudo escribir $f", f.isFile)
    }

    private fun junk(n: Int): ByteArray = ByteArray(n).also { b ->
        Random(n.toLong()).nextBytes(b)
        for (i in b.indices) {
            val v = b[i].toInt() and 0xFF
            if (v == 0xFF || v == 0x89 || v == 'G'.code || v == 'R'.code) b[i] = 0x11
        }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private companion object {
        fun image(color: Int, format: Bitmap.CompressFormat, label: String): ByteArray {
            val bmp = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(color)
            c.drawText(label, 24f, 135f, Paint().apply { this.color = Color.WHITE; textSize = 44f; isAntiAlias = true })
            return ByteArrayOutputStream().also { bmp.compress(format, 90, it) }.toByteArray()
        }
    }
}
