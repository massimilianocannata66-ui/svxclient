package it.svxclient.core;

import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Costanti del protocollo SvxReflector (da ReflectorMsg.h di SvxLink). */
public final class Proto {
    private Proto() {}

    // Versione di protocollo annunciata: 2.0 = autenticazione con password, niente TLS.
    public static final int VER_MAJOR = 2;
    public static final int VER_MINOR = 0;

    // Dimensione massima di un frame TCP dopo l'autenticazione
    public static final int MAX_FRAME = 32768;

    // Messaggi TCP
    public static final int MSG_HEARTBEAT = 1;
    public static final int MSG_PROTO_VER = 5;
    public static final int MSG_PROTO_VER_DOWNGRADE = 6;
    public static final int MSG_AUTH_CHALLENGE = 10;
    public static final int MSG_AUTH_RESPONSE = 11;
    public static final int MSG_AUTH_OK = 12;
    public static final int MSG_ERROR = 13;
    public static final int MSG_SERVER_INFO = 100;
    public static final int MSG_NODE_LIST = 101;
    public static final int MSG_NODE_JOINED = 102;
    public static final int MSG_NODE_LEFT = 103;
    public static final int MSG_TALKER_START = 104;
    public static final int MSG_TALKER_STOP = 105;
    public static final int MSG_SELECT_TG = 106;
    public static final int MSG_TG_MONITOR = 107;
    public static final int MSG_REQUEST_QSY = 109;

    // Messaggi UDP
    public static final int UDP_HEARTBEAT = 1;
    public static final int UDP_AUDIO = 101;
    public static final int UDP_FLUSH_SAMPLES = 102;
    public static final int UDP_ALL_SAMPLES_FLUSHED = 103;

    public static final int CHALLENGE_LEN = 20;

    /** Risposta alla sfida: HMAC-SHA1(chiave = password, dati = sfida di 20 byte). */
    public static byte[] hmacSha1(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
