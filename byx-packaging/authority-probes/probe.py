#!/usr/bin/env python3
"""Sonda de ATACANTE do mesmo usuário (Python genérico): lê o pairing.token (que o usuário pode ler), faz o handshake perfeito e tenta usar
um token de sessão ROUBADO e a senha. Imprime só REJECTED_BEFORE_ANY_BYTE / REJECTED_AFTER_HANDSHAKE / CODE=<codigo> / ACCEPTED."""
import base64, hashlib, hmac, json, os, socket, struct, sys

home, token = sys.argv[1], sys.argv[2]
secret = base64.urlsafe_b64decode(open(os.path.join(home, "run", "pairing.token")).read().strip() + "==")

def b64(b): return base64.urlsafe_b64encode(b).rstrip(b"=").decode()
def proof(label, cn, sn): return b64(hmac.new(secret, f"byx-ipc-v1|{label}|{cn}|{sn}".encode(), hashlib.sha256).digest())
def send(s, o):
    d = json.dumps(o).encode(); s.sendall(struct.pack(">I", len(d)) + d)
def recv(s):
    h = b""
    while len(h) < 4:
        c = s.recv(4 - len(h))
        if not c: return None
        h += c
    n = struct.unpack(">I", h)[0]; d = b""
    while len(d) < n:
        c = s.recv(n - len(d))
        if not c: return None
        d += c
    return json.loads(d)

s = socket.socket(socket.AF_UNIX); s.settimeout(5)
s.connect(os.path.join(home, "run", "service.sock"))
cn = b64(os.urandom(16))
try:
    send(s, {"v": 1, "type": "hello", "clientNonce": cn})
    ch = recv(s)
except OSError:
    ch = None
if ch is None:
    print("REJECTED_BEFORE_ANY_BYTE"); sys.exit(0)
send(s, {"v": 1, "type": "auth", "clientProof": proof("client", cn, ch["serverNonce"])})
ready = recv(s)
if not ready or ready.get("type") != "ready":
    print("REJECTED_AFTER_HANDSHAKE"); sys.exit(0)
send(s, {"v": 1, "id": "x1", "op": "auth.sessionStatus", "session": token})
r = recv(s)
print("ACCEPTED" if r and r.get("ok") else "CODE=" + str((r or {}).get("error", {}).get("code")))
