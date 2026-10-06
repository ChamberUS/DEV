#!/usr/bin/env python3
"""Edições HOSTIS no snapshot CIFRADO da autoridade (o atacante do mesmo usuário escreve o arquivo, mas não tem a chave AEAD nem a MAC do serviço).
Layout: magic(4) formatVersion(2) authorityVersion(8) nonce(12) ciphertext+tag. Cada modo muda UMA coisa."""
import sys
p, mode = sys.argv[1], sys.argv[2]
b = bytearray(open(p, "rb").read())
if mode == "ciphertext":
    b[26 + 3] ^= 1
elif mode == "tag":
    b[-1] ^= 1
elif mode == "nonce":
    b[14] ^= 1
elif mode == "version":      # versão da autoridade no cabeçalho (AAD)
    b[13] ^= 1
elif mode == "truncate":
    b = b[:-10]
elif mode == "oversize":
    b = b + b"\0" * (8 * 1024 * 1024)
elif mode == "garbage":
    b = bytearray(b'{"format":1,"state":{"version":9,"accounts":[]},"mac":"x"}')
else:
    sys.exit("modo?")
open(p, "wb").write(bytes(b))
