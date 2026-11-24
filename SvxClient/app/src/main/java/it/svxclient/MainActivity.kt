package it.svxclient

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.view.WindowManager
import android.text.InputType
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import it.svxclient.core.SvxSession
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var btnConnect: Button
    private lateinit var btnPtt: Button
    private lateinit var btnMenu: Button
    private lateinit var btnExit: Button
    private lateinit var txtLevel: TextView
    private lateinit var levelBar: LevelBar
    private lateinit var txtInfo: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtLog: TextView

    private var hwPttDown = false

    // ---- GPS
    private var locMgr: LocationManager? = null
    private var lastLoc: Location? = null
    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            lastLoc = loc
            runOnUiThread { render() }
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        supportActionBar?.hide()          // niente tre puntini: c'e' il bottone Menu
        // lo schermo resta acceso finche' l'app e' aperta (cosi' il tasto PTT arriva sempre)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btnMenu = findViewById(R.id.btnMenu)
        btnExit = findViewById(R.id.btnExit)
        txtLevel = findViewById(R.id.txtLevel)
        levelBar = findViewById(R.id.levelBar)
        btnConnect = findViewById(R.id.btnConnect)
        btnPtt = findViewById(R.id.btnPtt)
        txtInfo = findViewById(R.id.txtInfo)
        txtStatus = findViewById(R.id.txtStatus)
        txtLog = findViewById(R.id.txtLog)

        btnConnect.setOnClickListener { onConnectClicked() }
        btnMenu.setOnClickListener { showMenu() }
        btnExit.setOnClickListener { exitApp() }

        btnPtt.setOnTouchListener { v, ev ->
            if (!prefs.pttScreenEnabled) return@setOnTouchListener true
            if (prefs.pttToggle) {
                // a scatto: un tocco = trasmetti, un altro tocco = torna in ascolto
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    SvxService.ptt(!SvxBus.transmitting)
                }
                return@setOnTouchListener true
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    SvxService.ptt(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    SvxService.ptt(false)
                }
            }
            true
        }
    }

    override fun onStart() {
        super.onStart()
        SvxBus.listener = { runOnUiThread { render() } }
        startGps()
        render()
    }

    override fun onStop() {
        stopGps()
        SvxBus.listener = null
        super.onStop()
    }

    // ------------------------------------------------------------- menu

    private fun showMenu() {
        val m = PopupMenu(this, btnMenu)
        m.menu.add(Menu.NONE, 1, 1, "Impostazioni server")
        m.menu.add(Menu.NONE, 2, 2, "TG memorizzati")
        m.menu.add(Menu.NONE, 3, 3, "Storico TG")
        m.menu.add(Menu.NONE, 4, 4, "Impostazioni audio")
        m.menu.add(Menu.NONE, 5, 5, "Tasto PTT della radio")
        m.menu.add(
            Menu.NONE, 6, 6,
            if (prefs.pttScreenEnabled) "Disabilita PTT a schermo" else "Abilita PTT a schermo"
        )
        m.menu.add(
            Menu.NONE, 7, 7,
            "PTT a scatto (Hold/Tap): " + if (prefs.pttToggle) "ATTIVO" else "SPENTO"
        )
        m.menu.add(
            Menu.NONE, 8, 8,
            if (prefs.gpsShow) "Nascondi posizione GPS" else "Mostra posizione GPS"
        )
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showServerDialog()
                2 -> showTgListDialog()
                3 -> showTgHistoryDialog()
                4 -> showAudioDialog()
                5 -> showPttDialog()
                6 -> {
                    prefs.pttScreenEnabled = !prefs.pttScreenEnabled
                    render()
                }
                7 -> {
                    prefs.pttToggle = !prefs.pttToggle
                    if (!prefs.pttToggle && SvxBus.transmitting) SvxService.ptt(false)
                    render()
                }
                8 -> {
                    prefs.gpsShow = !prefs.gpsShow
                    if (prefs.gpsShow) startGps() else stopGps()
                    render()
                }
            }
            true
        }
        m.show()
    }

    private fun exitApp() {
        if (isActive()) {
            startService(Intent(this, SvxService::class.java).setAction(SvxService.ACTION_DISCONNECT))
        }
        finishAndRemoveTask()
    }

    private fun box(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (16 * resources.displayMetrics.density).toInt()
        setPadding(p, p / 2, p, 0)
    }

    private fun field(hint: String, value: String, inputType: Int): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            this.inputType = inputType
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }

    private fun showServerDialog() {
        if (isActive()) {
            txtStatus.text = "Disconnetti prima di cambiare le impostazioni del server"
            return
        }
        val b = box()
        val host = field("Server (indirizzo o nome host)", prefs.host, InputType.TYPE_TEXT_VARIATION_URI)
        val port = field("Porta", prefs.port.toString(), InputType.TYPE_CLASS_NUMBER)
        val call = field(
            "Nominativo (es. RADIO1)", prefs.callsign,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )
        val pass = field(
            "Password del gruppo", prefs.password,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        val tg = field("Talkgroup", prefs.tg.toString(), InputType.TYPE_CLASS_NUMBER)
        for (v in listOf(host, port, call, pass, tg)) b.addView(v)
        AlertDialog.Builder(this)
            .setTitle("Impostazioni server")
            .setView(b)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva") { _, _ ->
                prefs.host = host.text.toString().trim()
                prefs.port = port.text.toString().trim().toIntOrNull() ?: 5300
                prefs.callsign = call.text.toString().trim()
                prefs.password = pass.text.toString()
                prefs.tg = tg.text.toString().trim().toIntOrNull() ?: 1
                render()
            }
            .show()
    }

    /** Passa a un altro talkgroup (anche con la connessione attiva) e lo ricorda nello storico. */
    private fun applyTg(n: Int) {
        prefs.tg = n
        prefs.noteTgUsed(n)
        SvxService.changeTg(n)
        SvxBus.log("Talkgroup $n")
        render()
    }

    private fun tgItems(list: List<Int>): Array<CharSequence> =
        Array(list.size) { i ->
            "TG ${list[i]}" + if (list[i] == prefs.tg) "   (attuale)" else ""
        }

    private fun showTgListDialog() {
        val list = prefs.tgList
        val b = AlertDialog.Builder(this).setTitle("TG memorizzati")
        if (list.isEmpty()) {
            b.setMessage("Nessun TG memorizzato. Tocca Aggiungi.")
        } else {
            b.setItems(tgItems(list)) { _, i -> applyTg(list[i]) }
            b.setNeutralButton("Elimina") { _, _ -> showTgDeleteDialog() }
        }
        b.setPositiveButton("Aggiungi") { _, _ -> showTgAddDialog() }
        b.setNegativeButton("Chiudi", null)
        b.show()
    }

    private fun showTgAddDialog() {
        val b = box()
        val tg = field("Numero del talkgroup", "", InputType.TYPE_CLASS_NUMBER)
        b.addView(tg)
        AlertDialog.Builder(this)
            .setTitle("Aggiungi TG")
            .setView(b)
            .setNegativeButton("Annulla", null)
            .setNeutralButton("Solo salva") { _, _ ->
                val n = tg.text.toString().trim().toIntOrNull()
                if (n != null && n !in prefs.tgList) prefs.tgList = prefs.tgList + n
            }
            .setPositiveButton("Salva e usa") { _, _ ->
                val n = tg.text.toString().trim().toIntOrNull()
                if (n != null) {
                    if (n !in prefs.tgList) prefs.tgList = prefs.tgList + n
                    applyTg(n)
                }
            }
            .show()
    }

    private fun showTgDeleteDialog() {
        val list = prefs.tgList
        AlertDialog.Builder(this)
            .setTitle("Quale TG elimini?")
            .setItems(tgItems(list)) { _, i ->
                prefs.tgList = list.filterIndexed { idx, _ -> idx != i }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showTgHistoryDialog() {
        val list = prefs.tgHistory
        val b = AlertDialog.Builder(this).setTitle("Storico TG")
        if (list.isEmpty()) {
            b.setMessage("Ancora nessun TG usato.")
        } else {
            b.setItems(tgItems(list)) { _, i -> applyTg(list[i]) }
            b.setNeutralButton("Cancella storico") { _, _ -> prefs.tgHistory = emptyList() }
        }
        b.setNegativeButton("Chiudi", null)
        b.show()
    }

    private fun slider(label: String, start: Int, onChange: (Int) -> Unit, b: LinearLayout) {
        val t = TextView(this)
        t.text = "$label: $start%"
        val s = SeekBar(this).apply {
            max = 300
            progress = start
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    t.text = "$label: $p%"
                    onChange(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        b.addView(t)
        b.addView(s)
    }

    private fun showAudioDialog() {
        val oldRx = prefs.rxGain
        val oldTx = prefs.txGain
        var saved = false
        val b = box()
        slider("Volume ascolto", oldRx, { prefs.rxGain = it; SvxService.reloadSettings() }, b)
        slider("Volume microfono", oldTx, { prefs.txGain = it; SvxService.reloadSettings() }, b)
        AlertDialog.Builder(this)
            .setTitle("Impostazioni audio")
            .setMessage("100% = volume normale. Muovi i cursori e tocca Salva.")
            .setView(b)
            .setPositiveButton("Salva") { _, _ ->
                saved = true
                SvxBus.log("Audio salvato: ascolto ${prefs.rxGain}%, microfono ${prefs.txGain}%")
            }
            .setNegativeButton("Annulla", null)
            .setOnDismissListener {
                if (!saved) {                      // annullato: torno ai valori di prima
                    prefs.rxGain = oldRx
                    prefs.txGain = oldTx
                    SvxService.reloadSettings()
                }
            }
            .show()
    }

    private fun showPttDialog() {
        val b = box()
        val down = field(
            "Segnale PTT premuto (facoltativo)", prefs.pttDownActions,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )
        val up = field(
            "Segnale PTT rilasciato (facoltativo)", prefs.pttUpActions,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )
        val info = TextView(this)
        info.text = "Tasto attuale: " +
            (if (prefs.pttKey == 0) "nessuno" else "${prefs.pttKey} (${KeyEvent.keyCodeToString(prefs.pttKey)})") +
            "\n\nPer usare il tasto PTT fisico con l'app aperta: tocca \"Impara tasto\" e premi il tasto sulla radio.\n" +
            "Per averlo anche con lo schermo spento servono i nomi dei segnali della radio (campi sotto)."
        b.addView(info)
        b.addView(down)
        b.addView(up)
        AlertDialog.Builder(this)
            .setTitle("Tasto PTT della radio")
            .setView(b)
            .setPositiveButton("Salva") { _, _ ->
                prefs.pttDownActions = down.text.toString().trim()
                prefs.pttUpActions = up.text.toString().trim()
                SvxService.reloadSettings()
            }
            .setNeutralButton("Impara tasto") { _, _ -> startLearn() }
            .setNegativeButton("Togli tasto") { _, _ -> prefs.pttKey = 0 }
            .show()
    }

    private fun startLearn() {
        AlertDialog.Builder(this)
            .setTitle("Premi il tasto PTT sulla radio")
            .setMessage("Aspetto il tasto...")
            .setNegativeButton("Annulla", null)
            .setOnKeyListener { dlg, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode != KeyEvent.KEYCODE_BACK) {
                    prefs.pttKey = keyCode
                    SvxBus.log("Tasto PTT salvato: $keyCode (${KeyEvent.keyCodeToString(keyCode)})")
                    dlg.dismiss()
                    render()
                    true
                } else {
                    false
                }
            }
            .show()
    }

    // ------------------------------------------------- tasti fisici

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_HOME
        ) {
            return super.onKeyDown(keyCode, event)
        }
        if (keyCode == prefs.pttKey && prefs.pttKey != 0) {
            if (event.repeatCount == 0 && !hwPttDown) {
                hwPttDown = true
                SvxService.ptt(true)
            }
            return true
        }
        if (event.repeatCount == 0) {
            SvxBus.log("Tasto $keyCode (${KeyEvent.keyCodeToString(keyCode)})")
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == prefs.pttKey && prefs.pttKey != 0) {
            if (hwPttDown) {
                hwPttDown = false
                SvxService.ptt(false)
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ------------------------------------------------------------- GPS

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (!prefs.gpsShow) return
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                43
            )
            return
        }
        stopGps()
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locMgr = lm
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 5000L, 5f, locListener)
                    val last = lm.getLastKnownLocation(p)
                    if (last != null && lastLoc == null) lastLoc = last
                }
            }
        } catch (e: Exception) {
            SvxBus.log("GPS non disponibile: ${e.message}")
        }
    }

    private fun stopGps() {
        try { locMgr?.removeUpdates(locListener) } catch (_: Exception) {}
        locMgr = null
    }

    // ---------------------------------------------------- connessione

    private fun isActive() = SvxBus.active

    private fun onConnectClicked() {
        if (isActive()) {
            startService(Intent(this, SvxService::class.java).setAction(SvxService.ACTION_DISCONNECT))
            return
        }
        if (prefs.host.isEmpty() || prefs.callsign.isBlank() || prefs.password.isEmpty()) {
            txtStatus.text = "Apri il Menu e compila \"Impostazioni server\""
            return
        }
        if (needsPermissions()) {
            requestPermissions()
        } else {
            startConnection()
        }
    }

    private fun neededPermissions(): List<String> {
        val l = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l
    }

    private fun needsPermissions() = neededPermissions().any {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, neededPermissions().toTypedArray(), 42)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 43) {
            val ok = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (ok) {
                startGps()
            } else {
                prefs.gpsShow = false
                SvxBus.log("Permesso della posizione negato")
            }
            render()
            return
        }
        if (requestCode != 42) return
        // Il microfono e' indispensabile; le notifiche sono facoltative
        val micOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (micOk) {
            startConnection()
        } else {
            txtStatus.text = "Serve il permesso del microfono per trasmettere"
        }
    }

    private fun startConnection() {
        prefs.noteTgUsed(prefs.tg)
        val i = Intent(this, SvxService::class.java).setAction(SvxService.ACTION_CONNECT)
        ContextCompat.startForegroundService(this, i)
    }

    private fun render() {
        val active = isActive()
        btnConnect.text = if (active) "Disconnetti" else "Connetti"

        var info = if (prefs.host.isEmpty()) {
            "Server non impostato: apri il Menu"
        } else {
            "${prefs.callsign} \u2192 ${prefs.host}:${prefs.port}   TG ${prefs.tg}"
        }
        if (prefs.gpsShow) {
            val l = lastLoc
            info += if (l == null) {
                "\nGPS: in ricerca..."
            } else {
                String.format(
                    Locale.US, "\nGPS: %.5f, %.5f  (\u00B1%d m)",
                    l.latitude, l.longitude, l.accuracy.toInt()
                )
            }
        }
        txtInfo.text = info

        val connected = SvxBus.state == SvxSession.State.CONNECTED
        btnPtt.visibility = if (prefs.pttScreenEnabled) View.VISIBLE else View.GONE
        btnPtt.isEnabled = connected && prefs.pttScreenEnabled
        val talker = SvxBus.talker
        btnPtt.text = when {
            !prefs.pttScreenEnabled -> "PTT A SCHERMO DISABILITATO"
            SvxBus.transmitting ->
                if (prefs.pttToggle) "TRASMETTI (TX)\nTocca per tornare in ascolto" else "STAI TRASMETTENDO"
            !connected -> "PTT"
            talker != null -> "RICEZIONE (RX)\n$talker"
            else -> if (prefs.pttToggle) "TOCCA PER PARLARE" else "TIENI PREMUTO PER PARLARE"
        }
        // rosso = TX, verde = RX (qualcuno parla), grigio = nessuno parla
        val pttColor = when {
            SvxBus.transmitting -> Color.rgb(229, 57, 53)
            talker != null -> Color.rgb(67, 160, 71)
            else -> Color.rgb(117, 117, 117)
        }
        btnPtt.backgroundTintList = ColorStateList.valueOf(pttColor)
        btnPtt.setTextColor(Color.WHITE)

        val nodes = if (SvxBus.nodes.isEmpty()) "" else "\nStazioni collegate: ${SvxBus.nodes.joinToString(", ")}"
        txtStatus.text = SvxBus.detail + nodes
        txtLog.text = SvxBus.logText()

        val db = SvxLevel.db
        levelBar.level = db
        txtLevel.text = if (db <= -89f) "Sig (dB): --" else "Sig (dB): ${db.toInt()}"
    }
}

/** Barra del segnale: da -60 a 0 dB. Verde fino a -30, giallo fino a -10, rosso oltre. */
class LevelBar @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var level: Float = -90f
        set(v) {
            field = v
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * ctx.resources.displayMetrics.scaledDensity
        color = Color.GRAY
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val labelH = text.textSize + 6f
        val barH = height - labelH
        val n = 30                       // 30 blocchi da 2 dB
        val gap = 3f
        val bw = (w - gap * (n - 1)) / n
        for (i in 0 until n) {
            val lo = -60f + i * 2f
            paint.color = if (level < lo) {
                Color.argb(60, 136, 136, 136)
            } else when {
                lo < -30f -> Color.rgb(67, 160, 71)     // verde
                lo < -10f -> Color.rgb(253, 216, 53)    // giallo
                else -> Color.rgb(229, 57, 53)          // rosso
            }
            val x = i * (bw + gap)
            c.drawRect(x, 0f, x + bw, barH, paint)
        }
        for (db in listOf(-60, -50, -40, -30, -20, -10, 0)) {
            val x = (db + 60) / 60f * w
            c.drawText(db.toString(), x.coerceIn(18f, w - 18f), height - 4f, text)
        }
    }
}
