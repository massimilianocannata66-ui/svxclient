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
}
