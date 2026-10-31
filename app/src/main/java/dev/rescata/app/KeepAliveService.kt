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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            BackgroundWork.cancel()
            return START_NOT_STICKY
        }
        // Obligatorio tras startForegroundService(), aunque el trabajo ya haya terminado
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(BackgroundWork.status.value), type)
        acquireWakeLock()
        if (watcher == null) {
            watcher = scope.launch {
                while (true) {
                    val s = BackgroundWork.status.value
                    if (s == null) {
                        finish()
                        break
                    }
                    notifyProgress(s)
                    delay(500) // como mucho dos actualizaciones por segundo
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+: límite de tiempo de los servicios dataSync (6 h al día). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun finish() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Rescata:trabajo").apply {
            setReferenceCounted(false)
            acquire(3 * 60 * 60 * 1000L) // tope de seguridad: se libera antes al terminar
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    @SuppressLint("MissingPermission") // se comprueba al capturar SecurityException
    private fun notifyProgress(s: BackgroundWork.Status) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, build(s))
        } catch (e: SecurityException) {
            // Sin permiso de notificaciones: el trabajo sigue igual, solo no se ve el progreso
        }
    }

    private fun build(s: BackgroundWork.Status?): android.app.Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Búsqueda y guardado", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progreso mientras Rescata busca o guarda archivos"
                }
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "trabajos"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_CANCEL = "dev.rescata.app.CANCELAR"
    }
}
