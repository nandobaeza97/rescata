package dev.rescata.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.test.and
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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
import java.util.Random
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
    private val trashSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private val jpegTrash = image(Color.rgb(200, 40, 40), Bitmap.CompressFormat.JPEG, "papelera")
    private val jpegThumb = image(Color.rgb(40, 160, 60), Bitmap.CompressFormat.JPEG, "miniatura")
    private val pngHidden = image(Color.rgb(40, 80, 200), Bitmap.CompressFormat.PNG, "oculta")
    private val jpegVendorTrash = image(Color.rgb(220, 180, 20), Bitmap.CompressFormat.JPEG, "app")
    private val pngEmbedded = image(Color.rgb(180, 40, 180), Bitmap.CompressFormat.PNG, "carving")

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

        listOf(pics, dl, thumb, shots, File(root, "Download/Rescata")).forEach { it.deleteRecursively() }
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
        write(File(dl, ".oculta/sin_extension"), pngHidden)       // PNG oculto y sin extensión
        write(File(dl, ".Trash/borrada.jpg"), jpegVendorTrash)    // papelera de una app
        write(File(dl, "rescata_cache.bin"), junk(6000) + pngEmbedded + junk(4000)) // imagen dentro de una caché
        write(File(dl, "visible.jpg"), image(Color.DKGRAY, Bitmap.CompressFormat.JPEG, "visible")) // viva
    }

    @Test
    fun escaneaExportaYRestaura() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            shot("01_inicio")

            // Activar carving pulsando la fila de la opción
            compose.onNodeWithText("Carving (lento)").performClick()
            compose.onNodeWithTag("resultados").performScrollToNode(hasText("Escanear"))
            compose.onNodeWithText("Escanear").performClick()
            compose.waitUntil(180_000) {
                compose.onAllNodesWithText("hallazgos", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            shot("02_resultados")

            // Cada caso aparece en la lista
            val expected = mutableListOf("borrada.jpg", "miniatura_999999.jpg", "sin_extension", "rescata_cache_0x")
            if (trashSupported) expected += "foto_papelera.jpg"
            for (t in expected) {
                compose.onNodeWithTag("resultados").performScrollToNode(hasText(t, substring = true))
                compose.onNodeWithText(t, substring = true).assertIsDisplayed()
            }
            compose.onNodeWithTag("resultados").performScrollToNode(hasText("miniatura_999999.jpg", substring = true))
            compose.onNode(
                hasText("miniatura_999999.jpg", substring = true) and
                    hasText("No queda ningún elemento en MediaStore", substring = true)
            ).assertIsDisplayed()
            shot("03_miniatura_huerfana")

            // Exportar todo
            compose.onNodeWithText("Todos").performClick()
            compose.onNodeWithText("Exportar", substring = true).performClick()
            if (compose.onAllNodesWithText("¿Dónde exportar?").fetchSemanticsNodes().isNotEmpty()) {
                val label = StorageAccess.exportVolumes(ctx).first { it.primary }.label
                compose.onNodeWithText(label, substring = true).performClick()
            }
            compose.waitUntil(120_000) {
                compose.onAllNodesWithText("Exportados", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            shot("04_exportado")
            verifyExport()

            // Restaurar en su sitio la foto de la papelera (diálogo del sistema)
            if (trashSupported) {
                compose.onNodeWithText("Ninguno").performClick()
                compose.onNodeWithTag("resultados").performScrollToNode(hasText("foto_papelera.jpg", substring = true))
                compose.onNodeWithText("foto_papelera.jpg", substring = true).performClick()
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
            }
        }
    }

    private fun verifyExport() {
        val outDirs = File(root, "Download/Rescata").listFiles()?.filter { it.isDirectory }.orEmpty()
        assertEquals("Debe haber una carpeta de exportación", 1, outDirs.size)
        val outDir = outDirs.single()
        val report = File(outDir, "informe.csv").readText(Charsets.UTF_8).removePrefix("﻿")
        val rows = report.split("\r\n").filter { it.isNotBlank() }.drop(1)
        assertTrue("El informe está vacío", rows.isNotEmpty())
        for (row in rows) {
            val cols = row.split(";")
            val copy = File(outDir, cols[10])
            assertTrue("Falta el archivo exportado ${cols[10]}", copy.isFile)
            assertEquals("SHA-256 de ${copy.name}", cols[6], sha256(copy.readBytes()))
            assertFalse("Se exportó un archivo vivo: ${cols[3]}", cols[3].endsWith("visible.jpg"))
            assertFalse("Se exportó un archivo vivo: ${cols[3]}", cols[3].endsWith("/foto_viva.jpg"))
        }
        fun exported(match: (String) -> Boolean): ByteArray {
            val f = outDir.listFiles().orEmpty().filter { match(it.name) }
            assertEquals("Esperaba un único archivo exportado: ${f.map { it.name }}", 1, f.size)
            return f.single().readBytes()
        }
        assertArrayEquals(jpegVendorTrash, exported { it.endsWith("_borrada.jpg") })
        assertArrayEquals(jpegThumb, exported { it.endsWith("_miniatura_999999.jpg") })
        assertArrayEquals(pngHidden, exported { it.endsWith("_sin_extension.png") })
        assertArrayEquals(pngEmbedded, exported { it.contains("rescata_cache_0x") && it.endsWith(".png") })
        if (trashSupported) assertArrayEquals(jpegTrash, exported { it.endsWith("_foto_papelera.jpg") })
    }

    private fun insertPhoto(name: String, bytes: ByteArray): Uri {
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
