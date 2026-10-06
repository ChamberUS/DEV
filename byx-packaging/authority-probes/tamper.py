#!/usr/bin/env python3
"""Edições HOSTIS no arquivo da autoridade (o atacante do mesmo usuário pode escrever o arquivo, mas não tem a chave MAC do serviço)."""
import json, re, sys
p, mode = sys.argv[1], sys.argv[2]
s = open(p).read()
d = json.loads(s)
accts = d["state"]["accounts"]
if mode == "role":
    n = s.replace('"role":"USER"', '"role":"ADMIN"', 1)
elif mode == "reenable":
    n = s.replace('"enabled":false', '"enabled":true', 1)
elif mode == "credversion":
    n = re.sub(r'"credentialVersion":(\d+)', lambda m: '"credentialVersion":%d' % (int(m.group(1)) + 8), s, count=1)
elif mode == "insert":
    fake = dict(accts[0]); fake["id"] = "a" * 32; fake["username"] = "fake_admin"; fake["role"] = "ADMIN"
    accts.append(fake); n = json.dumps(d, separators=(",", ":"))
elif mode == "hash":
    h = [a["passwordHash"] for a in accts]
    n = s.replace(h[0], h[1], 1)
else:
    sys.exit("modo?")
assert n != s, "edição sem efeito"
open(p, "w").write(n)
