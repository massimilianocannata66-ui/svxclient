package it.svxclient

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import it.svxclient.core.SvxSession

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var editHost: EditText
    private lateinit var editPort: EditText
    private lateinit var editCallsign: EditText
    private lateinit var editPassword: EditText
    private lateinit var editTg: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnPtt: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtLog: TextView

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        editHost = findViewById(R.id.editHost)
        editPort = findViewById(R.id.editPort)
        editCallsign = findViewById(R.id.editCallsign)
        editPassword = findViewById(R.id.editPassword)
        editTg = findViewById(R.id.editTg)
        btnConnect = findViewById(R.id.btnConnect)
        btnPtt = findViewById(R.id.btnPtt)
        txtStatus = findViewById(R.id.txtStatus)
        txtLog = findViewById(R.id.txtLog)

        editHost.setText(prefs.host)
        editPort.setText(prefs.port.toString())
        editCallsign.setText(prefs.callsign)
        editPassword.setText(prefs.password)
        editTg.setText(prefs.tg.toString())

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

    private fun isActive() = SvxBus.active

    private fun onConnectClicked() {
        if (isActive()) {
            startService(Intent(this, SvxService::class.java).setAction(SvxService.ACTION_DISCONNECT))
            return
        }
        val host = editHost.text.toString().trim()
        val port = editPort.text.toString().trim().toIntOrNull()
        val tg = editTg.text.toString().trim().toIntOrNull()
        if (host.isEmpty() || port == null || tg == null ||
            editCallsign.text.isNullOrBlank() || editPassword.text.isNullOrEmpty()
        ) {
            txtStatus.text = "Compila server, porta, nominativo, password e talkgroup"
            return
        }
        prefs.host = host
        prefs.port = port
        prefs.callsign = editCallsign.text.toString().trim()
        prefs.password = editPassword.text.toString()
        prefs.tg = tg

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
        for (e in listOf(editHost, editPort, editCallsign, editPassword, editTg)) {
            e.isEnabled = !active
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
