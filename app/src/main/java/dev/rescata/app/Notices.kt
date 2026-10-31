package dev.rescata.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Canales y avisos de Rescata (progreso, pausa por el límite de Android y fin de un trabajo). */
object Notices {

    const val PROGRESS_CHANNEL = "trabajos"
    private const val ALERT_CHANNEL = "avisos"
    const val PROGRESS_ID = 42
    const val PAUSED_ID = 43
    const val DONE_ID = 44

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(PROGRESS_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(PROGRESS_CHANNEL, "Búsqueda y guardado", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progreso mientras Rescata busca o guarda archivos"
                }
            )
        }
        if (nm.getNotificationChannel(ALERT_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL, "Avisos", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Cuando termina una búsqueda o un guardado, o Android lo pausa"
                }
            )
        }
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Android 15+ detuvo el trabajo por su límite de 6 h en segundo plano. */
    fun paused(ctx: Context, title: String) = alert(
        ctx, PAUSED_ID, "$title: en pausa",
        "Android limita a 6 horas el trabajo en segundo plano. Toca para abrir Rescata y continuar.",
    )

    /** Fin de una búsqueda o un guardado mientras la app no estaba a la vista. */
    fun done(ctx: Context, title: String, text: String) {
        cancel(ctx, PAUSED_ID)
        alert(ctx, DONE_ID, title, text)
    }

    fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)

    @SuppressLint("MissingPermission") // se comprueba al capturar SecurityException
    private fun alert(ctx: Context, id: Int, title: String, text: String) {
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (e: SecurityException) {
            // Sin permiso de notificaciones: el resultado se ve al abrir la app
        }
    }
}
