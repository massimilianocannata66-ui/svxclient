package it.svxclient

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import it.svxclient.core.SvxSession

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var btnConnect: Button
    private lateinit var btnPtt: Button
    private lateinit var txtInfo: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtLog: TextView

    /** Se non nullo, il prossimo tasto premuto viene salvato come tasto PTT. */
    private var learnDialog: AlertDialog? = null
    private var hwPttDown = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        btnConnect = findViewById(R.id.btnConnect)
        btnPtt = findViewById(R.id.btnPtt)
        txtInfo = findViewById(R.id.txtInfo)
        txtStatus = findViewById(R.id.txtStatus)
        txtLog = findViewById(R.id.txtLog)

        btnConnect.setOnClickListener { onConnectClicked() }

        btnPtt.setOnTouchListener { v, ev ->
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
        render()
    }

    override fun onStop() {
        SvxBus.listener = null
        super.onStop()
    }

    // ------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, 1, 1, "Impostazioni server")
        menu.add(Menu.NONE, 2, 2, "Cambio TG / gruppo")
        menu.add(Menu.NONE, 3, 3, "Impostazioni audio")
        menu.add(Menu.NONE, 4, 4, "Tasto PTT della radio")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> showServerDialog()
            2 -> showTgDialog()
            3 -> showAudioDialog()
            4 -> showPttDialog()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
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

    private fun showTgDialog() {
        val b = box()
        val tg = field("Numero del talkgroup", prefs.tg.toString(), InputType.TYPE_CLASS_NUMBER)
        b.addView(tg)
        AlertDialog.Builder(this)
            .setTitle("Cambio TG / gruppo")
            .setView(b)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Applica") { _, _ ->
                val n = tg.text.toString().trim().toIntOrNull()
                if (n != null) {
                    prefs.tg = n
                    SvxService.changeTg(n)          // se connesso, cambia subito
                    SvxBus.log("Talkgroup $n")
                    render()
                }
            }
            .show()
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
        val b = box()
        slider("Volume ascolto", prefs.rxGain, { prefs.rxGain = it; SvxService.reloadSettings() }, b)
        slider("Volume microfono", prefs.txGain, { prefs.txGain = it; SvxService.reloadSettings() }, b)
        AlertDialog.Builder(this)
            .setTitle("Impostazioni audio")
            .setMessage("100% = volume normale. Salva da solo mentre muovi i cursori.")
            .setView(b)
            .setPositiveButton("Chiudi", null)
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
        learnDialog = AlertDialog.Builder(this)
            .setTitle("Premi il tasto PTT sulla radio")
            .setMessage("Aspetto il tasto...")
            .setNegativeButton("Annulla") { _, _ -> learnDialog = null }
            .setOnDismissListener { learnDialog = null }
            .show()
    }

    // ------------------------------------------------- tasti fisici

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_HOME
        ) {
            return super.onKeyDown(keyCode, event)
        }
        val ld = learnDialog
        if (ld != null) {
            prefs.pttKey = keyCode
            learnDialog = null
            ld.dismiss()
            SvxBus.log("Tasto PTT salvato: $keyCode (${KeyEvent.keyCodeToString(keyCode)})")
            render()
            return true
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

    // ---------------------------------------------------- connessione

    private fun isActive() = SvxBus.active

    private fun onConnectClicked() {
        if (isActive()) {
            startService(Intent(this, SvxService::class.java).setAction(SvxService.ACTION_DISCONNECT))
            return
        }
        if (prefs.host.isEmpty() || prefs.callsign.isBlank() || prefs.password.isEmpty()) {
            txtStatus.text = "Apri il menu ⋮ in alto a destra e compila \"Impostazioni server\""
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
        val i = Intent(this, SvxService::class.java).setAction(SvxService.ACTION_CONNECT)
        ContextCompat.startForegroundService(this, i)
    }

    private fun render() {
        val active = isActive()
        btnConnect.text = if (active) "Disconnetti" else "Connetti"

        txtInfo.text = if (prefs.host.isEmpty()) {
            "Server non impostato: apri il menu ⋮ in alto a destra"
        } else {
            "${prefs.callsign} → ${prefs.host}:${prefs.port}   TG ${prefs.tg}"
        }

        val connected = SvxBus.state == SvxSession.State.CONNECTED
        btnPtt.isEnabled = connected
        val talker = SvxBus.talker
        btnPtt.text = when {
            SvxBus.transmitting -> "STAI TRASMETTENDO"
            !connected -> "PTT"
            talker != null -> "Occupato: $talker"
            else -> "TIENI PREMUTO PER PARLARE"
        }

        val nodes = if (SvxBus.nodes.isEmpty()) "" else "\nStazioni collegate: ${SvxBus.nodes.joinToString(", ")}"
        txtStatus.text = SvxBus.detail + nodes
        txtLog.text = SvxBus.logText()
    }
}
