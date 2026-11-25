package it.svxclient

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Base64
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Invia la posizione GPS del telefono alla dashboard del server SVX, in automatico,
 * ogni 30 secondi finche' l'app e' collegata. Usa l'indirizzo impostato in
 * Menu > "Posizione per la dashboard" (vuoto = non invia niente).
 *
 * La dashboard riceve una richiesta del tipo:
 *   http://HOST:8088/api/pos?id=RADIO1&lat=45.123456&lon=9.123456
 */
class PosReporter(private val ctx: Context) {

    private val intervalMs = 30_000L

    @Volatile private var last: Location? = null
    @Volatile private var running = false
    private var lm: LocationManager? = null
    private var worker: Thread? = null
    @Volatile private var lastOk: Boolean? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val cur = last
            // tengo la posizione piu' recente
            if (cur == null || loc.time >= cur.time) last = loc
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun start() {
        stop()
        if (Prefs(ctx).posUrl.isBlank()) return
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            SvxBus.log("Posizione: permesso negato, non la invio")
            return
        }
        val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = m
        try {
            for (prov in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (m.isProviderEnabled(prov)) {
                    m.requestLocationUpdates(prov, 10_000L, 5f, listener, Looper.getMainLooper())
                    val l = m.getLastKnownLocation(prov)
                    if (l != null && (last == null || l.time > last!!.time)) last = l
                }
            }
        } catch (e: Exception) {
            SvxBus.log("Posizione non disponibile: ${e.message}")
        }
        running = true
        worker = Thread { loop() }.also {
            it.isDaemon = true
            it.start()
        }
        SvxBus.log("Invio posizione alla dashboard attivo")
    }

    fun stop() {
        running = false
        try { lm?.removeUpdates(listener) } catch (_: Exception) {}
        lm = null
        worker?.interrupt()
        worker = null
        last = null
        lastOk = null
    }

    private fun loop() {
        try {
            while (running) {
                val l = last
                if (l != null) send(l)
                var waited = 0L
                while (running && waited < intervalMs) {
                    Thread.sleep(500)
                    waited += 500
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun report(ok: Boolean, text: String) {
        if (lastOk != ok) {
            lastOk = ok
            SvxBus.log(text)
        }
    }

    private fun send(l: Location) {
        val p = Prefs(ctx)
        var conn: HttpURLConnection? = null
        try {
            val base = URL(p.posUrl.trim())
            val path = if (base.path.isNullOrEmpty() || base.path == "/") "/api/pos" else base.path
            val q = "id=" + URLEncoder.encode(p.callsign.trim(), "UTF-8") +
                String.format(Locale.US, "&lat=%.6f&lon=%.6f", l.latitude, l.longitude)
            val file = path + (if (base.query != null) "?" + base.query + "&" else "?") + q
            val url = URL(base.protocol, base.host, base.port, file)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            val ui = base.userInfo
            if (!ui.isNullOrEmpty()) {
                val tok = Base64.encodeToString(ui.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                conn.setRequestProperty("Authorization", "Basic $tok")
            }
            val code = conn.responseCode
            if (code == 200) {
                report(true, "Posizione inviata alla dashboard")
            } else {
                report(false, "Posizione non inviata: risposta $code")
            }
        } catch (e: Exception) {
            report(false, "Posizione non inviata: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }
}
