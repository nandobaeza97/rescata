package dev.rescata.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ejecuta los trabajos largos (buscar, guardar) fuera de la pantalla y mantiene la app viva con un
 * servicio en primer plano mientras duran, para que no se detengan al bloquear el móvil.
 * El trabajo en sí es el mismo de siempre: aquí solo cambia quién lo ejecuta.
 */
object BackgroundWork {

    data class Status(val title: String, val text: String = "", val done: Int = 0, val total: Int = 0)

    private val _status = MutableStateFlow<Status?>(null)
    /** Lo que muestra la notificación; null cuando no hay ningún trabajo en marcha. */
    val status: StateFlow<Status?> = _status.asStateFlow()

    /** Ámbito de la app (no de la pantalla): sigue vivo aunque se cierre o se pause la actividad. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicInteger(0)
    @Volatile private var cancelFlag: AtomicBoolean? = null

    fun launch(ctx: Context, title: String, cancel: AtomicBoolean, block: suspend () -> Unit): Job {
        val app = ctx.applicationContext
        running.incrementAndGet()
        cancelFlag = cancel
        _status.value = Status(title)
        try {
            ContextCompat.startForegroundService(app, Intent(app, KeepAliveService::class.java))
        } catch (e: RuntimeException) {
            // Si el sistema no deja iniciar el servicio, el trabajo se hace igual (como antes)
        }
        return scope.launch {
            try {
                block()
            } finally {
                if (running.decrementAndGet() == 0) {
                    cancelFlag = null
                    _status.value = null // el servicio se detiene solo al ver null
                }
            }
        }
    }

    fun update(text: String, done: Int = 0, total: Int = 0) {
        _status.update { it?.copy(text = text, done = done, total = total) }
    }

    /** Botón «Cancelar» de la notificación. */
    fun cancel() {
        cancelFlag?.set(true)
    }
}
