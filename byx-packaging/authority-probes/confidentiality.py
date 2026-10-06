#!/usr/bin/env python3
"""Processo do MESMO usuário abre/copia o snapshot e procura estrutura em claro (usuário, papel, verificador, senha, JSON)."""
import binascii, json, sys
raw = open(sys.argv[1], "rb").read()
creds = json.load(open(sys.argv[2]))
needles = ["normal_user", "admin_user", "disabled_user", "argon2id", "ADMIN", "USER", "role", "username", "passwordHash", "credentialVersion", "enabled", "accounts", '{"']
needles += [c["password"] for c in creds.values()] + [c["id"] for c in creds.values()]
hexd = binascii.hexlify(raw).decode()
bad = [n for n in needles if n.encode() in raw or binascii.hexlify(n.encode()).decode() in hexd]
if bad:
    print("PLAINTEXT FOUND:", bad[:5]); sys.exit(1)
sys.exit(0)
