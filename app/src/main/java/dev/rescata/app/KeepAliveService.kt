package dev.rescata.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano de tipo «dataSync» (importar/exportar y procesar archivos locales, según la
 * documentación de Android). Muestra el progreso en una notificación con botón «Cancelar» y mantiene
 * el procesador despierto mientras haya un trabajo de [BackgroundWork] en marcha.
 */
class KeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockSince = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            BackgroundWork.cancel()
            return START_NOT_STICKY
        }
        // Obligatorio tras startForegroundService(), aunque el trabajo ya haya terminado
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(BackgroundWork.status.value), type)
        } catch (e: RuntimeException) {
            // El sistema no lo permite ahora (p. ej. límite de tiempo agotado): sin cerrar la app,
            // se avisa y se reanuda al volver a abrirla
            if (BackgroundWork.status.value != null) notifyPaused()
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        NotificationManagerCompat.from(this).cancel(PAUSED_NOTIFICATION_ID)
        if (wakeLock?.isHeld != true) renewWakeLock()
        if (watcher == null) {
            watcher = scope.launch {
                while (true) {
                    val s = BackgroundWork.status.value
                    if (s == null) {
                        finish()
                        break
                    }
                    notifyProgress(s)
                    // Mientras dure el trabajo, sin tope fijo: se renueva antes de que caduque
                    if (SystemClock.elapsedRealtime() - wakeLockSince > WAKE_LOCK_RENEW_MS) renewWakeLock()
                    delay(500) // como mucho dos actualizaciones por segundo
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15+: los servicios dataSync pueden estar 6 h al día en segundo plano. Al llegar al
     * límite hay que detener el servicio en unos segundos; el trabajo no se pierde: se avisa con una
     * notificación y continúa con normalidad en cuanto se abre la app (que reinicia el contador).
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        if (BackgroundWork.status.value != null) notifyPaused()
        finish()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun finish() {
        isRunning = false
        watcher?.cancel()
        watcher = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Toma un wake lock nuevo con caducidad y suelta el anterior (sin hueco entre ambos). */
    private fun renewWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val fresh = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Rescata:trabajo").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MS) // si algo fallara, caduca solo
        }
        val old = wakeLock
        wakeLock = fresh
        wakeLockSince = SystemClock.elapsedRealtime()
        old?.let { if (it.isHeld) it.release() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    @SuppressLint("MissingPermission") // se comprueba al capturar SecurityException
    private fun notifyPaused() {
        ensureChannels()
        val title = BackgroundWork.status.value?.title ?: "Rescata"
        val text = "Android limita a 6 horas el trabajo en segundo plano. Toca para abrir Rescata y continuar."
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$title: en pausa")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(PAUSED_NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            // Sin permiso de notificaciones: continuará igualmente al abrir la app
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission") // se comprueba al capturar SecurityException
    private fun notifyProgress(s: BackgroundWork.Status) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, build(s))
        } catch (e: SecurityException) {
            // Sin permiso de notificaciones: el trabajo sigue igual, solo no se ve el progreso
        }
    }

    private fun build(s: BackgroundWork.Status?): android.app.Notification {
        ensureChannels()
        val open = openApp()
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, KeepAliveService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s?.title ?: "Rescata")
            .setContentText(s?.text?.takeIf { it.isNotEmpty() } ?: "Trabajando…")
            .setProgress(s?.total ?: 0, s?.done ?: 0, (s?.total ?: 0) == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Cancelar", cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Búsqueda y guardado", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progreso mientras Rescata busca o guarda archivos"
                }
            )
        }
        if (nm.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL_ID, "Avisos", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Cuando Android pausa una búsqueda o un guardado largo"
                }
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "trabajos"
        private const val ALERT_CHANNEL_ID = "avisos"
        private const val NOTIFICATION_ID = 42
        const val PAUSED_NOTIFICATION_ID = 43
        private const val ACTION_CANCEL = "dev.rescata.app.CANCELAR"
        private const val WAKE_LOCK_MS = 30 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 10 * 60 * 1000L

        /** El servicio está en primer plano ahora mismo. */
        @Volatile var isRunning = false
            private set
    }
}
