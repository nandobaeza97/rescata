package dev.rescata.app

import android.content.Context
import android.media.MediaScannerConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Avisa a la galería (MediaScanner) de archivos nuevos o movidos por tandas, con una sola conexión
 * por tanda. Pedirlo archivo a archivo abre una conexión con el sistema por cada uno y, con miles,
 * Android cierra la app («Too many Binders sent to SYSTEM»).
 */
class MediaScanBatch(private val ctx: Context, private val batchSize: Int = 300) {

    private val paths = ArrayList<String>()
    private val mimes = ArrayList<String?>()
    private var inFlight: CountDownLatch? = null

    /** [mime] null: se deduce de la extensión. */
    @Synchronized
    fun add(path: String, mime: String? = null) {
        paths += path
        mimes += mime
        if (paths.size >= batchSize) flush()
    }

    @Synchronized
    fun flush() {
        if (paths.isEmpty()) return
        // Como mucho una tanda a la vez: si la galería va más lenta que el guardado, se espera un poco
        inFlight?.await(WAIT_SECONDS, TimeUnit.SECONDS)
        val p = paths.toTypedArray()
        val m = mimes.toTypedArray()
        paths.clear()
        mimes.clear()
        val latch = CountDownLatch(p.size)
        inFlight = latch
        try {
            MediaScannerConnection.scanFile(ctx, p, m) { _, _ -> latch.countDown() }
        } catch (e: RuntimeException) {
            inFlight = null // la galería lo indexará por su cuenta más tarde
        }
    }

    /** Envía lo que quede y espera (con límite) a que la galería termine. */
    fun finish() {
        flush()
        val last = synchronized(this) { inFlight }
        last?.await(WAIT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val WAIT_SECONDS = 120L
    }
}
