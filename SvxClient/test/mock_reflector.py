#!/usr/bin/env python3
"""Finto SvxReflector scritto dal sorgente C++ (ReflectorClient.cpp / ReflectorMsg.h / AsyncMsg.h).
Serve solo per provare il client Java. Protocollo 2.0, autenticazione con password."""
import hashlib, hmac, os, socket, struct, sys, threading

PORT = int(sys.argv[1])
LOG = open(sys.argv[2], "w", buffering=1)
USERS = {"RADIO1": "GRUPPO", "RADIO2": "GRUPPO"}
PASSWORDS = {"GRUPPO": "Password2"}
CLIENT_ID = 0x1234
connected = set()
lock = threading.Lock()
udp_peers = {}   # client_id -> (addr, tcp_conn, callsign, tg)


def log(*a):
    LOG.write(" ".join(str(x) for x in a) + "\n")


def rd(sock, n):
    b = b""
    while len(b) < n:
        c = sock.recv(n - len(b))
        if not c:
            raise EOFError
        b += c
    return b


def frame(t, body=b""):
    payload = struct.pack(">H", t) + body
    return struct.pack(">I", len(payload)) + payload


def s16(x):
    b = x.encode()
    return struct.pack(">H", len(b)) + b


def vec(b):
    return struct.pack(">H", len(b)) + b


def get_str(b, o):
    (n,) = struct.unpack_from(">H", b, o)
    return b[o + 2:o + 2 + n].decode(), o + 2 + n


def get_vec(b, o):
    (n,) = struct.unpack_from(">H", b, o)
    return b[o + 2:o + 2 + n], o + 2 + n


def tcp_client(conn):
    callsign = None
    try:
        challenge = None
        authed = False
        while True:
            (ln,) = struct.unpack(">I", rd(conn, 4))
            if ln > (32768 if authed else 64):
                log("ERR frame troppo grande", ln, "authed", authed)
                conn.sendall(frame(13, s16("Protocol error")))
                return
            p = rd(conn, ln)
            (t,) = struct.unpack_from(">H", p, 0)
            body = p[2:]
            if t == 1:
                continue
            if t == 5:
                maj, mn = struct.unpack(">HH", body)
                log("PROTOVER", maj, mn)
                challenge = os.urandom(20)
                conn.sendall(frame(10, vec(challenge)))
            elif t == 11:
                cs, o = get_str(body, 0)
                dig, o = get_vec(body, o)
                grp = USERS.get(cs)
                key = PASSWORDS.get(grp, "")
                exp = hmac.new(key.encode(), challenge, hashlib.sha1).digest()
                if grp and hmac.compare_digest(exp, dig) and cs not in connected:
                    callsign = cs
                    connected.add(cs)
                    authed = True
                    log("LOGIN_OK", cs)
                    conn.sendall(frame(12))
                    info = struct.pack(">HH", 0, CLIENT_ID)
                    info += struct.pack(">H", 1) + s16("RADIO9")
                    info += struct.pack(">H", 1) + s16("OPUS")
                    conn.sendall(frame(100, info))
                else:
                    log("LOGIN_FAIL", cs)
                    conn.sendall(frame(13, s16("Access denied")))
                    return
            elif t == 106 and authed:
                (tg,) = struct.unpack(">I", body)
                log("SELECT_TG", tg)
                with lock:
                    udp_peers.setdefault(CLIENT_ID, {})["tg"] = tg
                    udp_peers[CLIENT_ID]["conn"] = conn
                    udp_peers[CLIENT_ID]["cs"] = callsign
            else:
                log("TCP_OTHER", t)
    except (EOFError, ConnectionError, OSError):
        pass
    finally:
        if callsign:
            connected.discard(callsign)
        log("TCP_CLOSED", callsign)
        conn.close()


def udp_loop(u):
    talking = False
    while True:
        data, addr = u.recvfrom(4096)
        t, cid, seq = struct.unpack_from(">HHH", data, 0)
        body = data[6:]
        with lock:
            peer = udp_peers.setdefault(cid, {})
            peer["addr"] = addr
        if t == 1:
            log("UDP_HB", cid)
            u.sendto(struct.pack(">HHH", 1, cid, 0), addr)
        elif t == 101:
            audio, _ = get_vec(body, 0)
            log("UDP_AUDIO", len(audio), audio.hex())
            conn = peer.get("conn")
            if conn and not talking:
                talking = True
                conn.sendall(frame(104, struct.pack(">I", peer.get("tg", 0)) + s16(peer.get("cs", "?"))))
            u.sendto(struct.pack(">HHH", 101, cid, seq) + vec(audio), addr)
        elif t == 102:
            log("UDP_FLUSH")
            conn = peer.get("conn")
            u.sendto(struct.pack(">HHH", 102, cid, seq), addr)
            if conn and talking:
                talking = False
                conn.sendall(frame(105, struct.pack(">I", peer.get("tg", 0)) + s16(peer.get("cs", "?"))))


def main():
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", PORT))
    srv.listen(5)
    u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    u.bind(("127.0.0.1", PORT))
    threading.Thread(target=udp_loop, args=(u,), daemon=True).start()
    log("READY")
    while True:
        c, _ = srv.accept()
        threading.Thread(target=tcp_client, args=(c,), daemon=True).start()


main()
