package dev.rescata.app

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.rescata.core.Finding
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Random

/**
 * Guardar más de 10.000 fotos en la galería con el móvil bloqueado: el ritmo no debe caer a medida
 * que se llena el álbum, todo debe copiarse íntegro y volver a guardar no debe duplicar nada.
 * En Android 15+ se fuerza además el límite de tiempo de los servicios en segundo plano (acortado a
 * unos segundos) para comprobar que el guardado no se pierde y continúa al volver a la app.
 */
@RunWith(AndroidJUnit4::class)
class EscalaE2ETest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val device = UiDevice.getInstance(instr)

    @Suppress("DEPRECATION")
    private val root: File = Environment.getExternalStorageDirectory()
    private val source = File(root, "Download/RescataEscala/.escala")
    private val album = File(root, "Pictures/Rescata")
    private val othersDir = File(root, "Download/Rescata")
    private val shots = File(root, "Download/rescata-capturas")
    private val log = StringBuilder()
    private val timeLimitTest = Build.VERSION.SDK_INT >= 35

    @Before
    fun prepare() {
        val pkg = ctx.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            shell("appops set --uid $pkg MANAGE_EXTERNAL_STORAGE allow")
        } else {
            shell("pm grant $pkg android.permission.READ_EXTERNAL_STORAGE")
            shell("pm grant $pkg android.permission.WRITE_EXTERNAL_STORAGE")
        }
        assertTrue("La app no tiene acceso al almacenamiento", StorageAccess.hasFullAccess(ctx))
        shell("settings put global hide_error_dialogs 1")
        shell("settings put secure anr_show_background 0")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        // Restos de la otra prueba: que solo se guarde lo de esta
        listOf("Download/RescataTest", "Pictures/RescataTest", "Pictures/.thumbnails/999999.jpg", "Pictures/Rescata – Miniaturas")
            .forEach { File(root, it).deleteRecursively() }
        shell("rm -r ${File(root, "Android/media/com.whatsapp/WhatsApp").absolutePath}")
        listOf(source.parentFile!!, album, othersDir).forEach { it.deleteRecursively() }
        shots.mkdirs()

        // N fotos distintas y del mismo tamaño (el peor caso para detectar duplicados)
        val base = baseJpeg()
        val t0 = SystemClock.elapsedRealtime()
        assertTrue(source.mkdirs())
        for (i in 0 until N) File(source, "escala_%05d.jpg".format(i)).writeBytes(variant(base, i))
        note("Preparadas $N fotos de ${variant(base, 0).size} bytes en ${(SystemClock.elapsedRealtime() - t0) / 1000} s")

        if (timeLimitTest) {
            // Límite de 6 h de los servicios dataSync acortado a 20 s (comando de la documentación de Android)
            shell("device_config set_sync_disabled_for_tests persistent")
            shell("device_config put activity_manager data_sync_fgs_timeout_duration 20000")
        }
    }

    @After
    fun cleanUp() {
        try {
            File(shots, "escala_${Build.VERSION.SDK_INT}.txt").writeText(log.toString())
        } finally {
            if (timeLimitTest) {
                shell("device_config delete activity_manager data_sync_fgs_timeout_duration")
                shell("device_config set_sync_disabled_for_tests none")
            }
            listOf(source.parentFile!!, album, othersDir).forEach { it.deleteRecursively() }
        }
    }

    @Test
    fun guardaMasDeDiezMilSinRalentizarse() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Buscar (lo mismo que hace el botón)
            var t = SystemClock.elapsedRealtime()
            onVm(scenario) { it.startScan() }
            waitFor(15 * 60_000L, "fin de la búsqueda") { vm(scenario) { s -> !s.scanning && s.summary != null } }
            note("Búsqueda: ${(SystemClock.elapsedRealtime() - t) / 1000} s")

            // Seleccionar exactamente las N fotos de la prueba
            onVm(scenario) { it.setFilter(Finding.Category.HIDDEN_MEDIA) }
            onVm(scenario) { it.selectVisible() }
            val extra = vm(scenario) { s ->
                s.rows.filter { it.finding.id in s.selected && !it.finding.sourcePath.startsWith(source.absolutePath) }
                    .map { it.finding.id }
            }
            extra.forEach { id -> onVm(scenario) { it.toggle(id) } }
            assertEquals("Fotos de la prueba seleccionadas", N, vm(scenario) { s -> s.selected.size })

            // Guardar con el móvil bloqueado
            val volume = StorageAccess.exportVolumes(ctx).first { it.primary }
            t = SystemClock.elapsedRealtime()
            onVm(scenario) { it.export(volume) }
            waitFor(60_000, "servicio en primer plano") { keepAliveInForeground() }
            device.sleep()
            val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            waitFor(10_000, "pantalla apagada") { !power.isInteractive }

            val progress = Progress()
            if (timeLimitTest) checkTimeLimit(scenario, progress)

            waitFor(40 * 60_000L, "fin del guardado") {
                progress.sample()
                reports().size == 1 && BackgroundWork.status.value == null
            }
            val total = (SystemClock.elapsedRealtime() - t) / 1000
            if (!power.isInteractive) {
                device.wakeUp()
                shell("wm dismiss-keyguard")
            }
            waitFor(30_000, "volver a la app") { scenario.state == Lifecycle.State.RESUMED }
            waitFor(30_000, "estado final") { vm(scenario) { s -> !s.exporting } }

            // Ritmo: los últimos 2000 no deben tardar mucho más que 2000 del principio
            val early = progress.at(4000) - progress.at(2000)
            val late = progress.at(N) - progress.at(N - 2000)
            note("Guardado: $total s · 2000-4000: ${early / 1000.0} s · últimos 2000: ${late / 1000.0} s")
            assertTrue("El guardado se ralentiza: ${early / 1000.0} s → ${late / 1000.0} s", late < 2.5 * early + 20_000)

            // Todo copiado e íntegro
            val rows = readRows(reports().single())
            assertEquals(N, rows.size)
            rows.forEach { assertEquals("Estado de ${it[3]}: ${it.getOrNull(14)}", "exportado", it[10]) }
            assertEquals("Fotos en el álbum", N, album.listFiles { f -> !f.name.startsWith(".") }?.size ?: 0)
            val rnd = Random(5)
            repeat(200) {
                val r = rows[rnd.nextInt(rows.size)]
                assertEquals("SHA-256 de ${r[12]}", r[6], sha256(File(r[12]).readBytes()))
                assertEquals("Copia de ${r[3]}", r[6], sha256(File(r[3]).readBytes()))
            }
            note("Comprobadas 200 copias al azar byte a byte (SHA-256)")

            // Guardar otra vez lo mismo: nada nuevo y sin ralentizarse
            t = SystemClock.elapsedRealtime()
            onVm(scenario) { it.export(volume) }
            val again = Progress()
            waitFor(30 * 60_000L, "fin del segundo guardado") {
                again.sample()
                reports().size == 2 && BackgroundWork.status.value == null && vm(scenario) { s -> !s.exporting }
            }
            val early2 = again.at(4000) - again.at(2000)
            val late2 = again.at(N) - again.at(N - 2000)
            note("Segundo guardado: ${(SystemClock.elapsedRealtime() - t) / 1000} s · 2000-4000: ${early2 / 1000.0} s · últimos 2000: ${late2 / 1000.0} s")
            val second = readRows(reports().last())
            assertEquals(N, second.size)
            second.forEach { assertEquals("Segundo guardado de ${it[3]}", "ya existía", it[10]) }
            assertEquals("Se duplicaron fotos", N, album.listFiles { f -> !f.name.startsWith(".") }?.size ?: 0)
            assertTrue("El segundo guardado se ralentiza", late2 < 2.5 * early2 + 20_000)
        }
    }

    /**
     * Android 15+: con el límite acortado, el sistema detiene el servicio a mitad del guardado. La app
     * no debe cerrarse, debe avisar y, al volver a ella, el servicio debe reanudarse sin perder nada.
     */
    private fun checkTimeLimit(scenario: ActivityScenario<MainActivity>, progress: Progress) {
        var stopped = false
        val end = SystemClock.elapsedRealtime() + 120_000
        while (SystemClock.elapsedRealtime() < end && BackgroundWork.status.value != null) {
            progress.sample()
            if (!keepAliveInForeground()) {
                stopped = true
                break
            }
            Thread.sleep(500)
        }
        note("Límite de tiempo de Android alcanzado a mitad del guardado: ${if (stopped) "sí" else "no"} " +
            "(${BackgroundWork.status.value?.done ?: -1} guardadas)")
        if (!stopped || BackgroundWork.status.value == null) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        waitFor(10_000, "aviso de pausa") {
            progress.sample()
            nm.activeNotifications.any { it.id == KeepAliveService.PAUSED_NOTIFICATION_ID }
        }
        note("Aviso «en pausa» mostrado")
        Thread.sleep(3000)
        progress.sample()
        device.wakeUp()
        shell("wm dismiss-keyguard")
        waitFor(30_000, "volver a la app") { scenario.state == Lifecycle.State.RESUMED }
        waitFor(30_000, "servicio reanudado") {
            progress.sample()
            keepAliveInForeground() || BackgroundWork.status.value == null
        }
        if (BackgroundWork.status.value != null) {
            assertFalse("El aviso de pausa sigue tras reanudar",
                nm.activeNotifications.any { it.id == KeepAliveService.PAUSED_NOTIFICATION_ID })
            note("Servicio reanudado al abrir la app (${BackgroundWork.status.value?.done ?: -1} guardadas)")
        }
    }

    /** Momento (ms) en que el guardado pasó por cada número de archivos. */
    private inner class Progress {
        private val times = LongArray(N + 1) { -1 }
        private var last = -1
        fun sample() {
            val now = SystemClock.elapsedRealtime()
            // Al terminar, el estado vuelve a null: se da por completado
            val done = BackgroundWork.status.value?.done ?: if (reports().isNotEmpty()) N else last
            for (k in (last + 1)..minOf(done, N)) if (k >= 0) times[k] = now
            if (done > last) last = minOf(done, N)
        }
        fun at(k: Int): Long {
            for (i in k..N) if (times[i] >= 0) return times[i]
            throw AssertionError("Sin muestra de progreso para $k")
        }
    }

    private fun note(s: String) {
        log.append(s).append('\n')
        android.util.Log.i("EscalaE2E", s)
    }

    private fun <T> vm(scenario: ActivityScenario<MainActivity>, read: (UiState) -> T): T {
        var result: Result<T>? = null
        scenario.onActivity { act ->
            result = runCatching { read(ViewModelProvider(act)[ScanViewModel::class.java].state.value) }
        }
        return result!!.getOrThrow()
    }

    private fun onVm(scenario: ActivityScenario<MainActivity>, action: (ScanViewModel) -> Unit) {
        scenario.onActivity { act -> action(ViewModelProvider(act)[ScanViewModel::class.java]) }
    }

    private fun waitFor(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Tiempo agotado esperando: $what")
            Thread.sleep(250)
        }
    }

    private fun reports(): List<File> =
        othersDir.listFiles()?.filter { it.name.startsWith("informe_") && it.name.endsWith(".csv") }
            ?.sortedBy { it.name }.orEmpty()

    private fun readRows(report: File): List<List<String>> =
        report.readText(Charsets.UTF_8).removePrefix("﻿").split("\r\n")
            .filter { it.isNotBlank() }.drop(1).map { it.split(";") }

    private fun keepAliveInForeground(): Boolean {
        val pfd = instr.uiAutomation.executeShellCommand("dumpsys activity services ${ctx.packageName}/.KeepAliveService")
        val out = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
        return out.contains("isForeground=true")
    }

    private fun shell(cmd: String) {
        val pfd = instr.uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private companion object {
        const val N = 12_000

        fun baseJpeg(): ByteArray {
            val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(Color.rgb(30, 120, 200))
            return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        }

        /** La misma foto con un comentario JPEG distinto (segmento COM tras el SOI): contenido único. */
        fun variant(base: ByteArray, i: Int): ByteArray {
            val text = "rescata-escala-%05d".format(i).toByteArray(Charsets.US_ASCII)
            val len = text.size + 2
            val out = ByteArrayOutputStream(base.size + len + 2)
            out.write(base, 0, 2) // FF D8
            out.write(0xFF); out.write(0xFE); out.write(len shr 8); out.write(len and 0xFF)
            out.write(text)
            out.write(base, 2, base.size - 2)
            return out.toByteArray()
        }
    }
}
