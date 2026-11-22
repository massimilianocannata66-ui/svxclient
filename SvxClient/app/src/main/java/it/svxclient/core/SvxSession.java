package it.svxclient.core;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Una sessione verso un SvxReflector (protocollo 2.0, autenticazione con password).
 * Controllo su TCP, audio Opus su UDP. Nessuna dipendenza da Android.
 */
public final class SvxSession {

    public enum State { DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED }

    public interface Listener {
        /** fatal=true: inutile riprovare (es. password errata). */
        void onState(State state, String detail, boolean fatal);
        void onTalker(boolean start, int tg, String callsign);
        void onNode(boolean joined, String callsign);
        void onNodeList(List<String> nodes);
        void onAudio(byte[] opus);
        void onFlush();
        void onInfo(String text);
    }

    private static final class SessionEnd extends IOException {
        final boolean fatal;
        SessionEnd(String m, boolean fatal) { super(m); this.fatal = fatal; }
    }

    private static final long TCP_TIMEOUT_MS = 30_000;
    private static final long UDP_WARN_MS = 10_000;
    private static final long UDP_TIMEOUT_MS = 45_000;

    private final String host;
    private final int port;
    private final String callsign;
    private final byte[] password;
    private volatile int tg;
    private final Listener listener;

    private final AtomicBoolean closed = new AtomicBoolean(true);
    private final AtomicBoolean endNotified = new AtomicBoolean(false);
    private final Object txLock = new Object();
    private final AtomicInteger udpSeq = new AtomicInteger();

    private volatile Socket tcp;
    private volatile OutputStream tcpOut;
    private volatile DatagramSocket udp;
    private volatile InetAddress serverAddr;
    private volatile int clientId = -1;
    private volatile State state = State.DISCONNECTED;
    private volatile long lastTcpRx;
    private volatile long lastUdpRx;
    private volatile long udpStart;
    private volatile String abortReason;
    private volatile boolean abortFatal;
    private boolean udpWarned;

    public SvxSession(String host, int port, String callsign, String password, int tg,
                      Listener listener) {
        this.host = host;
        this.port = port;
        this.callsign = callsign;
        this.password = password.getBytes(StandardCharsets.UTF_8);
        this.tg = tg;
        this.listener = listener;
    }

    public State getState() { return state; }

    public void start() {
        if (!closed.compareAndSet(true, false)) return;
        if (password.length == 0) {
            finish("Password mancante", true);
            return;
        }
        Thread t = new Thread(this::runTcp, "svx-tcp");
        t.setDaemon(true);
        t.start();
    }

    /** Chiusura richiesta dall'utente. */
    public void stop() {
        abort("Disconnesso", false);
    }

    public void selectTg(int newTg) {
        this.tg = newTg;
        if (state == State.CONNECTED) {
            sendTcp(Proto.MSG_SELECT_TG, new Wire.Writer().u32(newTg));
        }
    }

    /** Invia un pacchetto Opus (un frame, tipicamente 20 ms). */
    public void sendAudio(byte[] opus, int off, int len) {
        sendUdp(Proto.UDP_AUDIO, new Wire.Writer().vec(opus, off, len));
    }

    /** Segnala la fine della trasmissione. */
    public void sendFlush() {
        sendUdp(Proto.UDP_FLUSH_SAMPLES, null);
    }

    // ------------------------------------------------------------------ TCP

    private void runTcp() {
        String detail = "Disconnesso";
        boolean fatal = false;
        try {
            setState(State.CONNECTING, host + ":" + port);
            Socket s = new Socket();
            tcp = s;
            s.connect(new InetSocketAddress(host, port), 8000);
            s.setTcpNoDelay(true);
            tcpOut = s.getOutputStream();
            serverAddr = s.getInetAddress();
            lastTcpRx = System.currentTimeMillis();
            startTimer();
            setState(State.AUTHENTICATING, "Connesso, autenticazione in corso");
            sendTcp(Proto.MSG_PROTO_VER,
                    new Wire.Writer().u16(Proto.VER_MAJOR).u16(Proto.VER_MINOR));

            InputStream in = s.getInputStream();
            byte[] hdr = new byte[4];
            while (!closed.get()) {
                readFully(in, hdr, 4);
                long len = ((long) (hdr[0] & 0xFF) << 24) | ((hdr[1] & 0xFF) << 16)
                        | ((hdr[2] & 0xFF) << 8) | (hdr[3] & 0xFF);
                if (len < 2 || len > Proto.MAX_FRAME) {
                    throw new IOException("Frame non valido (" + len + " byte)");
                }
                byte[] payload = new byte[(int) len];
                readFully(in, payload, payload.length);
                lastTcpRx = System.currentTimeMillis();
                handleFrame(payload);
            }
        } catch (SessionEnd e) {
            detail = e.getMessage();
            fatal = e.fatal;
        } catch (IOException e) {
            detail = describe(e);
        } catch (RuntimeException e) {
            detail = "Errore interno: " + e;
        }
        finish(detail, fatal);
    }

    private static void readFully(InputStream in, byte[] b, int n) throws IOException {
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r < 0) throw new EOFException("Connessione chiusa dal server");
            got += r;
        }
    }

    private void handleFrame(byte[] payload) throws IOException {
        Wire.Reader r = new Wire.Reader(payload, 0, payload.length);
        int type = r.u16();
        switch (type) {
            case Proto.MSG_HEARTBEAT:
                break;

            case Proto.MSG_PROTO_VER_DOWNGRADE: {
                int maj = r.u16();
                int min = r.u16();
                throw new SessionEnd("Il server richiede il protocollo " + maj + "." + min
                        + ", non supportato da questa app", true);
            }

            case Proto.MSG_AUTH_CHALLENGE: {
                byte[] challenge = r.vec();
                if (challenge.length != Proto.CHALLENGE_LEN) {
                    throw new IOException("Sfida di autenticazione non valida");
                }
                byte[] digest = Proto.hmacSha1(password, challenge);
                sendTcp(Proto.MSG_AUTH_RESPONSE, new Wire.Writer().str(callsign).vec(digest));
                break;
            }

            case Proto.MSG_AUTH_OK:
                listener.onInfo("Autenticazione riuscita");
                break;

            case Proto.MSG_ERROR: {
                String msg = r.remaining() >= 2 ? r.str() : "";
                if (msg.equalsIgnoreCase("Access denied")) {
                    boolean authenticating = state == State.AUTHENTICATING;
                    throw new SessionEnd(authenticating
                            ? "Accesso negato: controlla nominativo e password (o il nominativo e' "
                              + "gia' connesso da un altro dispositivo)"
                            : "Accesso negato dal server", true);
                }
                if (msg.equalsIgnoreCase("Invalid callsign")) {
                    throw new SessionEnd("Nominativo non accettato dal server", true);
                }
                throw new SessionEnd("Errore dal server: " + msg, false);
            }

            case Proto.MSG_SERVER_INFO:
                handleServerInfo(r);
                break;

            case Proto.MSG_NODE_LIST:
                listener.onNodeList(r.strVec());
                break;

            case Proto.MSG_NODE_JOINED:
                listener.onNode(true, r.str());
                break;

            case Proto.MSG_NODE_LEFT:
                listener.onNode(false, r.str());
                break;

            case Proto.MSG_TALKER_START:
            case Proto.MSG_TALKER_STOP: {
                boolean start = type == Proto.MSG_TALKER_START;
                // Formato v2: u32 tg + stringa. Formato v1: solo stringa.
                int rem = r.remaining();
                int firstLen = rem >= 2 ? (((payload[payload.length - rem] & 0xFF) << 8)
                        | (payload[payload.length - rem + 1] & 0xFF)) : -1;
                if (firstLen >= 0 && 2 + firstLen == rem) {
                    listener.onTalker(start, 0, r.str());
                } else {
                    int t = (int) r.u32();
                    listener.onTalker(start, t, r.str());
                }
                break;
            }

            default:
                // Messaggi sconosciuti vanno ignorati (compatibilita' futura)
                break;
        }
    }

    private void handleServerInfo(Wire.Reader r) throws IOException {
        r.u16(); // riservato
        clientId = r.u16();
        List<String> nodes = r.strVec();
        List<String> codecs = r.strVec();
        if (!codecs.contains("OPUS")) {
            listener.onInfo("Attenzione: il server non dichiara il codec OPUS " + codecs);
        }
        startUdp();
        sendTcp(Proto.MSG_SELECT_TG, new Wire.Writer().u32(tg));
        setState(State.CONNECTED, "Connesso, talkgroup " + tg);
        listener.onNodeList(nodes);
    }

    private void sendTcp(int type, Wire.Writer body) {
        Wire.Writer w = new Wire.Writer().u16(type);
        byte[] b = w.toByteArray();
        byte[] extra = body == null ? new byte[0] : body.toByteArray();
        int len = b.length + extra.length;
        byte[] frame = new byte[4 + len];
        frame[0] = (byte) (len >> 24);
        frame[1] = (byte) (len >> 16);
        frame[2] = (byte) (len >> 8);
        frame[3] = (byte) len;
        System.arraycopy(b, 0, frame, 4, b.length);
        System.arraycopy(extra, 0, frame, 4 + b.length, extra.length);
        try {
            synchronized (txLock) {
                OutputStream o = tcpOut;
                if (o == null) return;
                o.write(frame);
                o.flush();
            }
        } catch (IOException e) {
            abort(describe(e), false);
        }
    }

    // ------------------------------------------------------------------ UDP

    private void startUdp() throws IOException {
        DatagramSocket u = new DatagramSocket();
        udp = u;
        udpStart = System.currentTimeMillis();
        lastUdpRx = 0;
        Thread t = new Thread(this::runUdp, "svx-udp");
        t.setDaemon(true);
        t.start();
        sendUdp(Proto.UDP_HEARTBEAT, null);
    }

    private void runUdp() {
        DatagramSocket u = udp;
        byte[] buf = new byte[4096];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (!closed.get()) {
            try {
                p.setLength(buf.length);
                u.receive(p);
            } catch (IOException e) {
                if (!closed.get()) abort(describe(e), false);
                return;
            }
            if (serverAddr != null && !serverAddr.equals(p.getAddress())) continue;
            lastUdpRx = System.currentTimeMillis();
            try {
                handleUdp(buf, p.getLength());
            } catch (IOException ignored) {
                // pacchetto malformato: lo scarto
            }
        }
    }

    private void handleUdp(byte[] buf, int len) throws IOException {
        Wire.Reader r = new Wire.Reader(buf, 0, len);
        int type = r.u16();
        r.u16(); // client id
        r.u16(); // numero di sequenza
        switch (type) {
            case Proto.UDP_AUDIO:
                listener.onAudio(r.vec());
                break;
            case Proto.UDP_FLUSH_SAMPLES:
                listener.onFlush();
                break;
            default:
                break; // heartbeat e altro
        }
    }

    private void sendUdp(int type, Wire.Writer body) {
        DatagramSocket u = udp;
        InetAddress a = serverAddr;
        if (u == null || a == null || clientId < 0 || closed.get()) return;
        Wire.Writer w = new Wire.Writer().u16(type).u16(clientId)
                .u16(udpSeq.getAndIncrement() & 0xFFFF);
        byte[] head = w.toByteArray();
        byte[] extra = body == null ? new byte[0] : body.toByteArray();
        byte[] data = new byte[head.length + extra.length];
        System.arraycopy(head, 0, data, 0, head.length);
        System.arraycopy(extra, 0, data, head.length, extra.length);
        try {
            u.send(new DatagramPacket(data, data.length, a, port));
        } catch (IOException e) {
            abort(describe(e), false);
        }
    }

    // ------------------------------------------------------------ heartbeat

    private void startTimer() {
        Thread t = new Thread(() -> {
            int sec = 0;
            while (!closed.get()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                sec++;
                long now = System.currentTimeMillis();
                if (sec % 3 == 0) sendTcp(Proto.MSG_HEARTBEAT, null);
                if (udp != null && (lastUdpRx == 0 || sec % 3 == 0)) {
                    sendUdp(Proto.UDP_HEARTBEAT, null);
                }
                if (now - lastTcpRx > TCP_TIMEOUT_MS) {
                    abort("Il server non risponde (timeout)", false);
                    return;
                }
                if (udp != null) {
                    if (lastUdpRx == 0) {
                        if (!udpWarned && now - udpStart > UDP_WARN_MS) {
                            udpWarned = true;
                            listener.onInfo("Nessuna risposta UDP dal server: controlla che la "
                                    + "porta UDP " + port + " sia aperta/inoltrata");
                        }
                        if (now - udpStart > UDP_TIMEOUT_MS) {
                            abort("Il server non risponde via UDP: apri/inoltra anche la porta UDP "
                                    + port + " sul router", false);
                            return;
                        }
                    } else if (now - lastUdpRx > UDP_TIMEOUT_MS) {
                        abort("Timeout UDP: il server non invia piu' pacchetti", false);
                        return;
                    }
                }
            }
        }, "svx-timer");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ chiusura

    private void setState(State s, String detail) {
        state = s;
        listener.onState(s, detail, false);
    }

    private void abort(String reason, boolean fatal) {
        if (abortReason == null) {
            abortReason = reason;
            abortFatal = fatal;
        }
        closed.set(true);
        closeSockets();
        // Se il thread TCP non e' mai partito o e' gia' finito, notifico qui
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            finish(abortReason, abortFatal);
        }, "svx-abort");
        t.setDaemon(true);
        t.start();
    }

    private void finish(String detail, boolean fatal) {
        closed.set(true);
        closeSockets();
        if (abortReason != null) {
            detail = abortReason;
            fatal = abortFatal;
        }
        if (endNotified.compareAndSet(false, true)) {
            state = State.DISCONNECTED;
            listener.onState(State.DISCONNECTED, detail, fatal);
        }
    }

    private void closeSockets() {
        try {
            Socket s = tcp;
            if (s != null) s.close();
        } catch (IOException ignored) {
        }
        DatagramSocket u = udp;
        if (u != null) u.close();
    }

    private String describe(IOException e) {
        if (e instanceof ConnectException) {
            return "Impossibile connettersi a " + host + ":" + port;
        }
        if (e instanceof UnknownHostException) return "Host sconosciuto: " + host;
        if (e instanceof SocketTimeoutException) return "Timeout di connessione";
        if (e instanceof EOFException) return "Connessione chiusa dal server";
        String m = e.getMessage();
        return m == null ? e.toString() : m;
    }
}
