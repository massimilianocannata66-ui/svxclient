package it.svxclient

import it.svxclient.core.SvxSession

/** Stato condiviso tra servizio e schermata. Tutto accessibile da qualsiasi thread. */
object SvxBus {
    @Volatile var active: Boolean = false         // l'utente ha chiesto di restare connesso
    @Volatile var state: SvxSession.State = SvxSession.State.DISCONNECTED
    @Volatile var detail: String = "Non connesso"
    @Volatile var talker: String? = null          // chi sta parlando (altri)
    @Volatile var transmitting: Boolean = false   // sto trasmettendo io
    @Volatile var nodes: List<String> = emptyList()

    private val logLines = ArrayDeque<String>()
    @Volatile var listener: (() -> Unit)? = null

    @Synchronized
    fun log(line: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ITALY)
            .format(java.util.Date())
        logLines.addFirst("$t  $line")
        while (logLines.size > 40) logLines.removeLast()
        notifyChanged()
    }

    @Synchronized
    fun logText(): String = logLines.joinToString("\n")

    fun notifyChanged() {
        listener?.invoke()
    }
}
