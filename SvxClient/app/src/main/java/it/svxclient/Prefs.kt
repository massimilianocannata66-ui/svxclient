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
}
