package it.svxclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import it.svxclient.core.SvxSession

/**
 * Tiene viva la connessione al reflector anche con lo schermo spento.
 * Gestisce riconnessione automatica e PTT.
 */
class SvxService : Service() {

    companion object {
        const val ACTION_CONNECT = "it.svxclient.CONNECT"
        const val ACTION_DISCONNECT = "it.svxclient.DISCONNECT"
        private const val CHANNEL = "svx"
        private const val NOTIF_ID = 1

        @Volatile private var instance: SvxService? = null

        /** Premuto (true) o rilasciato (false) il tasto PTT. */
        fun ptt(down: Boolean) {
            instance?.handlePtt(down)
        }

        /** Rilegge volume/microfono e segnali PTT dalle impostazioni. */
        fun reloadSettings() {
            instance?.applySettings()
        }

        /** Cambia talkgroup al volo, anche con la connessione attiva. */
        fun changeTg(tg: Int) {
            val sess = instance?.session ?: return
            Thread { sess.selectTg(tg) }.start()       // la rete non va usata dal thread principale
        }

        /** Segnali usati da molte radio POC per il tasto PTT fisico. */
        val DEFAULT_DOWN = listOf(
            "android.intent.action.PTT.down",
            "com.sonim.intent.action.PTT_KEY_DOWN",
        )
        val DEFAULT_UP = listOf(
            "android.intent.action.PTT.up",
            "com.sonim.intent.action.PTT_KEY_UP",
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private var session: SvxSession? = null
    private var audio: AudioEngine? = null
    private var wantConnected = false
    private var retryDelayMs = 3000L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val retryRunnable = Runnable { openSession() }
    private var pttReceiver: BroadcastReceiver? = null
    private var pos: PosReporter? = null
    private var downActions: Set<String> = emptySet()
    private var upActions: Set<String> = emptySet()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        unregisterPttReceiver()
        instance = null
        closeAll()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                goForeground("Connessione in corso...")
                wantConnected = true
                SvxBus.active = true
                retryDelayMs = 3000L
                releaseLocks()
                acquireLocks()
                registerPttReceiver()
                openSession()
                pos = PosReporter(this).also { it.start() }
            }
            ACTION_DISCONNECT -> {
                wantConnected = false
                closeAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------ sessione

    private fun openSession() {
        if (!wantConnected) return
        main.removeCallbacks(retryRunnable)
        session?.stop()

        val p = Prefs(this)
        if (audio == null) {
            audio = AudioEngine(
                onEncoded = { b -> session?.sendAudio(b, 0, b.size) },
                onTxEnd = {
                    session?.sendFlush()
                    SvxBus.transmitting = false
                    SvxBus.notifyChanged()
                },
            ).also { it.start() }
            applySettings()
        }

        SvxBus.log("Connessione a ${p.host}:${p.port} come ${p.callsign}, TG ${p.tg}")
        val bound = Bound()
        val s = SvxSession(p.host.trim(), p.port, p.callsign.trim(), p.password, p.tg, bound)
        bound.mine = s
        session = s
        s.start()
    }

    private fun closeAll(detail: String = "Non connesso") {
        main.removeCallbacks(retryRunnable)
        unregisterPttReceiver()
        pos?.stop()
        pos = null
        session?.stop()
        session = null
        audio?.stop()
        audio = null
        releaseLocks()
        SvxBus.active = false
        SvxBus.state = SvxSession.State.DISCONNECTED
        SvxBus.detail = detail
        SvxBus.talker = null
        SvxBus.transmitting = false
        SvxBus.notifyChanged()
    }

    // ------------------------------------------- impostazioni e PTT fisico

    private fun splitActions(text: String): List<String> =
        text.split(",", ";", " ", "\n").map { it.trim() }.filter { it.isNotEmpty() }

    private fun applySettings() {
        val p = Prefs(this)
        audio?.rxGain = p.rxGain / 100f
        audio?.txGain = p.txGain / 100f
        if (pttReceiver != null) registerPttReceiver()     // rilegge i nomi dei segnali
    }

    private fun registerPttReceiver() {
        unregisterPttReceiver()
        val p = Prefs(this)
        downActions = (DEFAULT_DOWN + splitActions(p.pttDownActions)).toSet()
        upActions = (DEFAULT_UP + splitActions(p.pttUpActions)).toSet()
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val act = intent?.action ?: return
                when {
                    act in downActions -> handlePtt(true)
                    act in upActions -> handlePtt(false)
                }
            }
        }
        val f = IntentFilter()
        for (a in downActions + upActions) f.addAction(a)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, f, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(r, f)
        }
        pttReceiver = r
    }

    private fun unregisterPttReceiver() {
        pttReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        pttReceiver = null
    }

    // ----------------------------------------------------------------- PTT

    private fun handlePtt(down: Boolean) {
        val a = audio ?: return
        if (down) {
            if (SvxBus.state != SvxSession.State.CONNECTED) return
            if (SvxBus.talker != null) return        // canale occupato
            if (a.startCapture()) {
                SvxBus.transmitting = true
                SvxBus.notifyChanged()
            } else {
                SvxBus.log("Microfono non disponibile (permesso negato?)")
            }
        } else {
            a.stopCapture()                           // onTxEnd manda il flush
        }
    }

    // ------------------------------------------------ SvxSession.Listener

    /** Inoltra gli eventi al servizio solo se la sessione e' ancora quella corrente. */
    private inner class Bound : SvxSession.Listener {
        @Volatile var mine: SvxSession? = null
        private fun ok() = session != null && session === mine

        override fun onState(state: SvxSession.State, detail: String, fatal: Boolean) {
            // Gli eventi di sessioni vecchie vanno ignorati, tranne la chiusura finale
            if (ok() || (state == SvxSession.State.DISCONNECTED && fatal && mine === session)) {
                this@SvxService.onState(state, detail, fatal)
            }
        }
        override fun onTalker(start: Boolean, tg: Int, callsign: String) {
            if (ok()) this@SvxService.onTalker(start, tg, callsign)
        }
        override fun onNode(joined: Boolean, callsign: String) {
            if (ok()) this@SvxService.onNode(joined, callsign)
        }
        override fun onNodeList(nodes: List<String>) {
            if (ok()) this@SvxService.onNodeList(nodes)
        }
        override fun onAudio(opus: ByteArray) {
            if (ok()) this@SvxService.onAudio(opus)
        }
        override fun onFlush() {
            if (ok()) this@SvxService.onFlush()
        }
        override fun onInfo(text: String) {
            if (ok()) this@SvxService.onInfo(text)
        }
    }

    private fun onState(state: SvxSession.State, detail: String, fatal: Boolean) {
        SvxBus.state = state
        SvxBus.detail = detail
        if (state != SvxSession.State.CONNECTED) SvxBus.talker = null
        SvxBus.log(detail)
        updateNotification(detail)

        when (state) {
            SvxSession.State.CONNECTED -> retryDelayMs = 3000L
            SvxSession.State.DISCONNECTED -> {
                if (wantConnected && !fatal) {
                    SvxBus.log("Nuovo tentativo tra ${retryDelayMs / 1000} s")
                    main.postDelayed(retryRunnable, retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
                } else if (fatal) {
                    wantConnected = false
                    main.post {
                        closeAll(detail)          // il messaggio d'errore resta visibile
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                } else {
                    // disconnessione richiesta dall'utente o servizio gia' chiuso
                }
            }
            else -> {}
        }
        SvxBus.notifyChanged()
    }

    private fun onTalker(start: Boolean, tg: Int, callsign: String) {
        SvxBus.talker = if (start) callsign else null
        if (start) SvxBus.log("$callsign sta parlando") else SvxBus.log("$callsign ha finito")
        SvxBus.notifyChanged()
    }

    private fun onNode(joined: Boolean, callsign: String) {
        SvxBus.nodes = if (joined) SvxBus.nodes + callsign else SvxBus.nodes - callsign
        SvxBus.log(if (joined) "$callsign si e' collegato" else "$callsign si e' scollegato")
    }

    private fun onNodeList(nodes: List<String>) {
        SvxBus.nodes = nodes
        SvxBus.notifyChanged()
    }

    private fun onAudio(opus: ByteArray) {
        audio?.enqueueRx(opus)
    }

    private fun onFlush() {
        audio?.flushRx()
    }

    private fun onInfo(text: String) {
        SvxBus.log(text)
    }

    // ------------------------------------------------ notifica e risorse

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Connessione SVX", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            val locOk = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (Prefs(this).posUrl.isNotBlank() && locOk) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("SVX Client")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "svx:conn").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "svx:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }
}

