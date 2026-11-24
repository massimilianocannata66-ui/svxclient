package it.svxclient

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Audio: riproduce i pacchetti Opus ricevuti e cattura/codifica il microfono.
 * Opus mono a 16 kHz, frame da 20 ms (come SvxLink).
 */
class AudioEngine(
    private val onEncoded: (ByteArray) -> Unit,
    private val onTxEnd: () -> Unit,
) {
    companion object {
        const val RATE = 16000
        const val FRAME = 320            // 20 ms a 16 kHz
        private const val BITRATE = 20000
    }

    private val rxQueue = LinkedBlockingQueue<ByteArray>()
    private val flushMarker = ByteArray(0)

    /** Guadagni (1.0 = invariato), modificabili anche durante l'uso. */
    @Volatile var rxGain: Float = 1.0f
    @Volatile var txGain: Float = 1.0f

    private fun applyGain(buf: ShortArray, n: Int, g: Float) {
        if (g == 1.0f) return
        for (i in 0 until n) {
            val v = (buf[i] * g).toInt()
            buf[i] = v.coerceIn(-32768, 32767).toShort()
        }
    }

    @Volatile private var running = false
    @Volatile private var capturing = false
    private var playThread: Thread? = null
    private var capThread: Thread? = null

    val isTransmitting: Boolean get() = capturing

    fun start() {
        if (running) return
        running = true
        playThread = thread(name = "svx-play", isDaemon = true) { playLoop() }
    }

    fun stop() {
        running = false
        stopCapture()
        rxQueue.clear()
        SvxLevel.set(-90f, true)
        playThread?.interrupt()
        playThread = null
    }

    /** Pacchetto Opus ricevuto dalla rete. */
    fun enqueueRx(opus: ByteArray) {
        if (!capturing && opus.isNotEmpty()) rxQueue.offer(opus)
    }

    /** Fine della trasmissione remota. */
    fun flushRx() {
        rxQueue.offer(flushMarker)
    }

    // ------------------------------------------------------------ riproduzione

    private fun playLoop() {
        val decoder = OpusDecoder(RATE, 1)
        val minBuf = AudioTrack.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, RATE * 2 / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val pcm = ShortArray(1920)               // fino a 120 ms
        val cushion = ShortArray(RATE * 40 / 1000) // 40 ms di silenzio come cuscinetto anti-scatti
        var playing = false

        try {
            while (running) {
                val frame = try {
                    rxQueue.poll(300, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    break
                }

                if (frame == null || frame === flushMarker) {
                    if (playing) {
                        track.stop()              // finisce di riprodurre quanto in coda
                        playing = false
                        decoder.resetState()
                        SvxLevel.set(-90f, true)
                    }
                    continue
                }

                if (!playing) {
                    track.play()
                    track.write(cushion, 0, cushion.size)
                    playing = true
                }

                try {
                    val n = decoder.decode(frame, 0, frame.size, pcm, 0, pcm.size, false)
                    if (n > 0) {
                        applyGain(pcm, n, rxGain)
                        SvxLevel.set(SvxLevel.dbOf(pcm, n))
                        track.write(pcm, 0, n)
                    }
                } catch (e: Exception) {
                    // pacchetto danneggiato: lo salto
                }
            }
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }

    // ------------------------------------------------------------- trasmissione

    /** Avvia la cattura dal microfono. Ritorna false se il microfono non e' disponibile. */
    @Suppress("MissingPermission")
    fun startCapture(): Boolean {
        if (capturing) return true
        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, FRAME * 2 * 4)
            )
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        capturing = true
        rxQueue.clear()
        capThread = thread(name = "svx-capture", isDaemon = true) {
            val enc = OpusEncoder(RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP)
            enc.setBitrate(BITRATE)
            enc.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
            enc.setComplexity(5)

            val pcm = ShortArray(FRAME)
            val out = ByteArray(400)
            try {
                rec.startRecording()
                while (capturing) {
                    var got = 0
                    while (got < FRAME && capturing) {
                        val r = rec.read(pcm, got, FRAME - got)
                        if (r <= 0) break
                        got += r
                    }
                    if (got < FRAME) break
                    applyGain(pcm, FRAME, txGain)
                    SvxLevel.set(SvxLevel.dbOf(pcm, FRAME))
                    val n = enc.encode(pcm, 0, FRAME, out, 0, out.size)
                    if (n > 0) onEncoded(out.copyOf(n))
                }
            } catch (e: Exception) {
                // microfono perso: chiudo la trasmissione
            } finally {
                capturing = false
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
                SvxLevel.set(-90f, true)
                onTxEnd()
            }
        }
        return true
    }

    fun stopCapture() {
        capturing = false
    }
}

/** Livello del segnale audio (ricevuto o trasmesso) in dB, da -90 (silenzio) a 0. */
object SvxLevel {
    @Volatile var db: Float = -90f
    private var lastNotify = 0L

    fun dbOf(buf: ShortArray, n: Int): Float {
        if (n <= 0) return -90f
        var sum = 0.0
        for (i in 0 until n) {
            val s = buf[i].toDouble()
            sum += s * s
        }
        val rms = Math.sqrt(sum / n)
        if (rms < 1.0) return -90f
        return (20.0 * Math.log10(rms / 32768.0)).toFloat().coerceIn(-90f, 0f)
    }

    fun set(value: Float, force: Boolean = false) {
        db = value
        val now = System.currentTimeMillis()
        if (force || now - lastNotify >= 80) {
            lastNotify = now
            SvxBus.notifyChanged()
        }
    }
}
