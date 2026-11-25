package it.svxclient

import android.content.Context

/** Impostazioni salvate sul telefono. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("svx", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v).apply()

    var port: Int
        get() = sp.getInt("port", 5300)
        set(v) = sp.edit().putInt("port", v).apply()

    var callsign: String
        get() = sp.getString("callsign", "RADIO1") ?: "RADIO1"
        set(v) = sp.edit().putString("callsign", v).apply()

    var password: String
        get() = sp.getString("password", "") ?: ""
        set(v) = sp.edit().putString("password", v).apply()

    var tg: Int
        get() = sp.getInt("tg", 1)
        set(v) = sp.edit().putInt("tg", v).apply()

    /** Volume ascolto e microfono, in percento (100 = invariato). */
    var rxGain: Int
        get() = sp.getInt("rxGain", 100)
        set(v) = sp.edit().putInt("rxGain", v).apply()

    var txGain: Int
        get() = sp.getInt("txGain", 100)
        set(v) = sp.edit().putInt("txGain", v).apply()

    /** Codice del tasto PTT fisico della radio (132 = F2 della Uniwa F400, 0 = nessuno). */
    var pttKey: Int
        get() = sp.getInt("pttKey", 132)
        set(v) = sp.edit().putInt("pttKey", v).apply()

    /** Nomi dei segnali (broadcast) del tasto PTT, separati da virgola. */
    var pttDownActions: String
        get() = sp.getString("pttDown", "") ?: ""
        set(v) = sp.edit().putString("pttDown", v).apply()

    var pttUpActions: String
        get() = sp.getString("pttUp", "") ?: ""
        set(v) = sp.edit().putString("pttUp", v).apply()

    private fun getInts(key: String): List<Int> =
        (sp.getString(key, "") ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }

    private fun putInts(key: String, l: List<Int>) {
        sp.edit().putString(key, l.joinToString(",")).apply()
    }

    /** Talkgroup salvati dall'utente. */
    var tgList: List<Int>
        get() = getInts("tgList")
        set(v) = putInts("tgList", v)

    /** Ultimi talkgroup usati (il piu' recente per primo). */
    var tgHistory: List<Int>
        get() = getInts("tgHistory")
        set(v) = putInts("tgHistory", v)

    fun noteTgUsed(tg: Int) {
        tgHistory = (listOf(tg) + tgHistory.filter { it != tg }).take(10)
    }

    /** Il tasto PTT sullo schermo e' attivo? */
    var pttScreenEnabled: Boolean
        get() = sp.getBoolean("pttScreen", true)
        set(v) = sp.edit().putBoolean("pttScreen", v).apply()

    /** PTT a schermo "a scatto": un tocco trasmette, un altro tocco torna in ascolto. */
    var pttToggle: Boolean
        get() = sp.getBoolean("pttToggle", true)
        set(v) = sp.edit().putBoolean("pttToggle", v).apply()

    /** Mostra la posizione GPS nella schermata principale. */
    var gpsShow: Boolean
        get() = sp.getBoolean("gpsShow", false)
        set(v) = sp.edit().putBoolean("gpsShow", v).apply()
}
