package it.svxclient.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializzazione dei campi come in AsyncMsg.h di SvxLink:
 * numeri big-endian; stringa = u16 lunghezza + byte; lista = u16 quantita' + elementi.
 */
public final class Wire {
    private Wire() {}

    public static final class ProtocolException extends IOException {
        public ProtocolException(String m) { super(m); }
    }

    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Writer u8(int v) {
            out.write(v & 0xFF);
            return this;
        }

        public Writer u16(int v) {
            out.write((v >> 8) & 0xFF);
            out.write(v & 0xFF);
            return this;
        }

        public Writer u32(long v) {
            out.write((int) ((v >> 24) & 0xFF));
            out.write((int) ((v >> 16) & 0xFF));
            out.write((int) ((v >> 8) & 0xFF));
            out.write((int) (v & 0xFF));
            return this;
        }

        public Writer str(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            if (b.length > 0xFFFF) throw new IllegalArgumentException("stringa troppo lunga");
            u16(b.length);
            out.write(b, 0, b.length);
            return this;
        }

        public Writer vec(byte[] b, int off, int len) {
            if (len > 0xFFFF) throw new IllegalArgumentException("lista troppo lunga");
            u16(len);
            out.write(b, off, len);
            return this;
        }

        public Writer vec(byte[] b) {
            return vec(b, 0, b.length);
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    public static final class Reader {
        private final byte[] buf;
        private int pos;
        private final int end;

        public Reader(byte[] buf, int off, int len) {
            this.buf = buf;
            this.pos = off;
            this.end = off + len;
        }

        public int remaining() {
            return end - pos;
        }

        private void need(int n) throws ProtocolException {
            if (n < 0 || end - pos < n) throw new ProtocolException("Messaggio troncato");
        }

        public int u8() throws ProtocolException {
            need(1);
            return buf[pos++] & 0xFF;
        }

        public int u16() throws ProtocolException {
            need(2);
            int v = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
            pos += 2;
            return v;
        }

        public long u32() throws ProtocolException {
            need(4);
            long v = ((long) (buf[pos] & 0xFF) << 24) | ((buf[pos + 1] & 0xFF) << 16)
                    | ((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }

        public String str() throws ProtocolException {
            int n = u16();
            need(n);
            String s = new String(buf, pos, n, StandardCharsets.UTF_8);
            pos += n;
            return s;
        }

        public byte[] vec() throws ProtocolException {
            int n = u16();
            need(n);
            byte[] b = new byte[n];
            System.arraycopy(buf, pos, b, 0, n);
            pos += n;
            return b;
        }

        public List<String> strVec() throws ProtocolException {
            int n = u16();
            List<String> l = new ArrayList<>();
            for (int i = 0; i < n; i++) l.add(str());
            return l;
        }
    }
}
