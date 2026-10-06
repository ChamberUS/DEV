#!/bin/zsh
# Matriz de acesso ao canário do cofre com o bundle de TESTE (build-canary/, identidade e namespace de teste "invalid.*": nada permanente,
# nenhum segredo real, nenhum item do produto). Tentativas REAIS: o serviço escreve/lê/atualiza/apaga e outros processos do mesmo usuário
# tentam ler enquanto o item existe. Se o próprio SERVIÇO não consegue escrever (sem provisioning), reporta BLOCKED e não finge o resto.
#   uso: ./build-app.sh --with-canary-harness && ./verify-secret-store.sh
set -u
HERE="${0:A:h}"; ROOT="${HERE:h}"; source "$HERE/identity.env"
B="$HERE/build-canary/BYX-MVP.app/Contents/MacOS"; APP="$HERE/build-canary/BYX-MVP.app"
JAVA_HOME="${JAVA_HOME:-$HOME/dev/tools/jdk-21.0.12.1+1/Contents/Home}"; JAVA="$JAVA_HOME/bin/java"
PASS=0; FAIL=0; SKIP=0
ok(){ echo "PASS  $1"; PASS=$((PASS+1)); }; bad(){ echo "FAIL  $1"; FAIL=$((FAIL+1)); }; skip(){ echo "SKIP  $1"; SKIP=$((SKIP+1)); }
[[ -x "$B/byx-secret-canary" ]] || { echo "rode ./build-app.sh --with-canary-harness antes"; exit 4; }

echo "== antes: nenhum item de teste no keychain de login (só atributos, sem -g/-w)"
security find-generic-password -s "invalid.byx-canary-test/test-canary" >/dev/null 2>&1; [[ $? -eq 44 ]] && ok "nenhum canário pré-existente" || bad "item de teste pré-existente"

echo "== A) SERVIÇO (identidade assinada do helper) escreve / lê / atualiza / apaga o canário"
OUT=$("$B/byx-secret-canary" 2>&1); echo "$OUT" | grep -E "^canary\." | sed 's/^/      /'
if echo "$OUT" | grep -q "^canary.result=OK"; then ok "A: ciclo completo com o canário (SecItem, proteção de dados)"; A_OK=1
else
  A_OK=0; WHY=$(echo "$OUT" | grep -E "^canary.write=" | head -1)
  echo "BLOCKED  A: o próprio serviço não consegue escrever no keychain de proteção de dados ($WHY)"
fi
echo "$OUT" | grep -qE "[0-9a-f]{32}" && bad "valor do canário apareceu na saída" || ok "canário ausente da saída do harness"

# tentativa em Python (ctypes) com SecItemCopyMatching + proteção de dados — mecanismo exercitado mesmo que o item não exista
PYREAD='
import ctypes,ctypes.util,sys
cf=ctypes.CDLL(ctypes.util.find_library("CoreFoundation")); sec=ctypes.CDLL(ctypes.util.find_library("Security"))
for f,r,a in [("CFStringCreateWithCString",ctypes.c_void_p,[ctypes.c_void_p,ctypes.c_char_p,ctypes.c_uint32]),("CFDictionaryCreate",ctypes.c_void_p,[ctypes.c_void_p,ctypes.POINTER(ctypes.c_void_p),ctypes.POINTER(ctypes.c_void_p),ctypes.c_long,ctypes.c_void_p,ctypes.c_void_p])]:
    getattr(cf,f).restype=r; getattr(cf,f).argtypes=a
sec.SecItemCopyMatching.argtypes=[ctypes.c_void_p,ctypes.POINTER(ctypes.c_void_p)]; sec.SecItemCopyMatching.restype=ctypes.c_int32
def g(lib,n): return ctypes.c_void_p.in_dll(lib,n).value
s=cf.CFStringCreateWithCString(None,b"invalid.byx-canary-test/test-canary",0x08000100); acc=cf.CFStringCreateWithCString(None,b"byx",0x08000100)
keys=[g(sec,"kSecClass"),g(sec,"kSecAttrService"),g(sec,"kSecAttrAccount"),g(sec,"kSecUseDataProtectionKeychain"),g(sec,"kSecAttrSynchronizable"),g(sec,"kSecReturnData"),g(sec,"kSecMatchLimit")]
vals=[g(sec,"kSecClassGenericPassword"),s,acc,g(cf,"kCFBooleanTrue"),g(cf,"kCFBooleanFalse"),g(cf,"kCFBooleanTrue"),g(sec,"kSecMatchLimitOne")]
K=(ctypes.c_void_p*7)(*keys); V=(ctypes.c_void_p*7)(*vals)
q=cf.CFDictionaryCreate(None,K,V,7,ctypes.addressof(ctypes.c_char.in_dll(cf,"kCFTypeDictionaryKeyCallBacks")),ctypes.addressof(ctypes.c_char.in_dll(cf,"kCFTypeDictionaryValueCallBacks")))
out=ctypes.c_void_p(); st=sec.SecItemCopyMatching(q,ctypes.byref(out))
print("python.result=%s(os=%d)"%("FOUND" if st==0 and out.value else "NOT_FOUND_OR_DENIED",st))
'
R=$(/usr/bin/python3 -c "$PYREAD" 2>&1 | tail -1); echo "      $R"
if echo "$R" | grep -q "^python.result=FOUND"; then bad "D: Python leu algo ($R)"
elif [[ $A_OK -eq 1 ]]; then ok "D (mecanismo): Python do mesmo usuário NÃO lê (sem o grupo de acesso do serviço)"
else echo "INFO  D (mecanismo exercitado): sem canário escrito pelo serviço, 'não achou' NÃO prova isolamento"; fi

if [[ $A_OK -ne 1 ]]; then
  echo "== B, C, E, F não avaliáveis de forma significativa: sem um canário escrito pelo serviço, 'não conseguiu ler' não prova isolamento"
  skip "B painel assinado tenta ler o canário do serviço (precisa de A)"
  skip "C java genérico (precisa de A)"; skip "E impostor ad-hoc (precisa de A)"; skip "F outro Team ID (também sem segunda identidade de assinatura)"
  security find-generic-password -s "invalid.byx-canary-test/test-canary" >/dev/null 2>&1; [[ $? -eq 44 ]] && ok "limpeza: nenhum item de teste ficou" || bad "item de teste ficou (invalid.byx-canary-test/test-canary)"
  echo "---- $PASS PASS, $FAIL FAIL, $SKIP SKIP  =>  SECURE SECRET STORE: BLOCKED ON PROVISIONING"
  exit 3
fi

echo "== canário VIVO (mantido pelo serviço) enquanto os outros tentam ler"
coproc "$B/byx-secret-holder" 2>/dev/null
HOLD_PID=$!; READY=""; for i in {1..80}; do read -t 0.25 -r line <&p && [[ "$line" == holder.ready=* ]] && { READY="$line"; break; }; done
[[ "$READY" == "holder.ready=OK" ]] && ok "serviço mantém o canário" || { bad "holder não ficou pronto ($READY)"; }
res() { "$@" 2>&1 | grep -E "^reader.result=" | head -1; }
R=$(res "$B/byx-secret-reader"); check_neg() { echo "      $2: $1"; [[ "$1" != *FOUND\(* ]] && ok "$2 NÃO lê o canário" || bad "$2 LEU o canário"; }
check_neg "$R" "B) painel assinado (identidade e direitos do painel)"
R=$(res "$JAVA" -cp "$ROOT/byx-local-service/target/classes:$HOME/.m2/repository/net/java/dev/jna/jna/5.17.0/jna-5.17.0.jar" byx.service.secrets.SecretCanaryReader); check_neg "$R" "C) java genérico do mesmo usuário"
R=$(/usr/bin/python3 -c "$PYREAD" 2>&1 | tail -1); [[ "$R" == *NOT_FOUND_OR_DENIED* ]] && ok "D) Python do mesmo usuário NÃO lê" || bad "D) Python leu"
C=$(mktemp -d /tmp/ids.XXXX); cp -R "$APP" "$C/"; codesign --force --deep -s - --identifier "$BYX_SERVICE_ID" "$C/BYX-MVP.app" 2>/dev/null
R=$(res "$C/BYX-MVP.app/Contents/MacOS/byx-secret-reader"); check_neg "$R" "E) impostor ad-hoc com o MESMO identificador do serviço"; rm -rf "$C"
skip "F) outro Team ID: só há uma identidade de assinatura (o requisito de código é o mesmo mecanismo; não testável aqui)"
print -u ${p[2]} "" 2>/dev/null; exec {p[1]}>&- {p[2]}>&- 2>/dev/null; wait $HOLD_PID 2>/dev/null
security find-generic-password -s "invalid.byx-canary-test/test-canary" >/dev/null 2>&1; [[ $? -eq 44 ]] && ok "limpeza: o canário foi apagado" || bad "item de teste ficou (invalid.byx-canary-test/test-canary)"
echo "---- $PASS PASS, $FAIL FAIL, $SKIP SKIP"
[[ $FAIL -eq 0 ]]
