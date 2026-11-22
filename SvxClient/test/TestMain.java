import it.svxclient.core.SvxSession;
import it.svxclient.core.SvxSession.State;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class TestMain {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "OK    " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    static class L implements SvxSession.Listener {
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<byte[]> audio = new CopyOnWriteArrayList<>();
        volatile State state = State.DISCONNECTED;
        volatile String detail = "";
        volatile boolean fatal;

        public void onState(State s, String d, boolean f) { state = s; detail = d; fatal = f; events.add("state:" + s); }
        public void onTalker(boolean st, int tg, String cs) { events.add("talker:" + st + ":" + tg + ":" + cs); }
        public void onNode(boolean j, String cs) { events.add("node:" + j + ":" + cs); }
        public void onNodeList(List<String> n) { events.add("nodes:" + n); }
        public void onAudio(byte[] a) { audio.add(a); }
        public void onFlush() { events.add("flush"); }
        public void onInfo(String t) { events.add("info:" + t); }
    }

    static boolean waitFor(java.util.function.BooleanSupplier c, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return c.getAsBoolean();
    }

    public static void main(String[] a) throws Exception {
        int port = Integer.parseInt(a[0]);

        // 1) login corretto, audio in andata e ritorno
        L l = new L();
        SvxSession s = new SvxSession("127.0.0.1", port, "RADIO1", "Password2", 7, l);
        s.start();
        check("connessione con password corretta", waitFor(() -> l.state == State.CONNECTED, 5000));
        check("lista nodi ricevuta", l.events.contains("nodes:[RADIO9]"));
        byte[] f1 = {1, 2, 3, 4, 5};
        byte[] f2 = new byte[200];
        Arrays.fill(f2, (byte) 0xAB);
        s.sendAudio(f1, 0, f1.length);
        s.sendAudio(f2, 0, f2.length);
        check("audio rimandato indietro (2 frame)", waitFor(() -> l.audio.size() == 2, 3000));
        check("contenuto frame 1 identico", l.audio.size() > 0 && Arrays.equals(l.audio.get(0), f1));
        check("contenuto frame 2 identico", l.audio.size() > 1 && Arrays.equals(l.audio.get(1), f2));
        check("talker start con tg=7 e nominativo", waitFor(() -> l.events.contains("talker:true:7:RADIO1"), 2000));
        s.sendFlush();
        check("flush ricevuto", waitFor(() -> l.events.contains("flush"), 2000));
        check("talker stop", waitFor(() -> l.events.contains("talker:false:7:RADIO1"), 2000));
        s.selectTg(9);
        Thread.sleep(300);
        // heartbeat: attendo oltre 3 s e verifico che resti connesso
        Thread.sleep(3500);
        check("ancora connesso dopo gli heartbeat", l.state == State.CONNECTED);
        s.stop();
        check("disconnessione richiesta", waitFor(() -> l.state == State.DISCONNECTED, 3000));
        check("non fatale dopo stop", !l.fatal);

        // 2) password errata
        L l2 = new L();
        SvxSession s2 = new SvxSession("127.0.0.1", port, "RADIO2", "sbagliata", 1, l2);
        s2.start();
        check("password errata -> disconnesso", waitFor(() -> l2.state == State.DISCONNECTED && l2.events.size() > 1, 5000));
        check("password errata -> messaggio e fatale", l2.detail.startsWith("Accesso negato") && l2.fatal);

        // 3) nominativo sconosciuto
        L l3 = new L();
        SvxSession s3 = new SvxSession("127.0.0.1", port, "PIPPO", "Password2", 1, l3);
        s3.start();
        check("nominativo sconosciuto rifiutato", waitFor(() -> l3.state == State.DISCONNECTED && l3.events.size() > 1, 5000));

        // 4) server non raggiungibile
        L l4 = new L();
        SvxSession s4 = new SvxSession("127.0.0.1", port + 1, "RADIO1", "Password2", 1, l4);
        s4.start();
        check("server non raggiungibile", waitFor(() -> l4.state == State.DISCONNECTED && l4.events.size() > 1, 5000));
        check("messaggio di errore chiaro", l4.detail.startsWith("Impossibile connettersi"));

        System.out.println(failures == 0 ? "TUTTI I TEST OK" : failures + " TEST FALLITI");
        System.exit(failures == 0 ? 0 : 1);
    }
}
