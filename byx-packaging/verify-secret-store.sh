#!/bin/zsh
# Matriz de acesso ao canário do cofre (keychain de proteção de dados) com o bundle de TESTE build-canary/ (precisa do perfil de DESENVOLVIMENTO).
# Namespace de teste "invalid.byx-canary-test/…": nenhum segredo real, nenhum item do produto. Tentativas REAIS enquanto o item existe.
# Primeiro o SERVIÇO precisa conseguir escrever/ler/atualizar/apagar; sem isso reporta BLOCKED e não finge o resto ("não achou" nunca é prova).
#   uso: ./build-app.sh --with-canary-harness --embedded-profile provisioning/out/service.provisionprofile && ./verify-secret-store.sh
set -u
HERE="${0:A:h}"; ROOT="${HERE:h}"; source "$HERE/identity.env"
APP="$HERE/build-canary/BYX-MVP.app"; H="$APP/Contents/Helpers"
CANARY="$H/byx-secret-canary.app/Contents/MacOS/byx-secret-canary"; HOLDER="$H/byx-secret-holder.app/Contents/MacOS/byx-secret-holder"
SVCREAD="$H/byx-secret-servicereader.app/Contents/MacOS/byx-secret-servicereader"; PANELREAD="$APP/Contents/MacOS/byx-secret-reader"
SVCAPP="$H/byx-local-service.app/Contents/MacOS/byx-local-service"
JAVA_HOME="${JAVA_HOME:-$HOME/dev/tools/jdk-21.0.12.1+1/Contents/Home}"; JAVA="$JAVA_HOME/bin/java"
PASS=0; FAIL=0; SKIP=0
ok(){ echo "PASS  $1"; PASS=$((PASS+1)); }; bad(){ echo "FAIL  $1"; FAIL=$((FAIL+1)); }; skip(){ echo "SKIP  $1"; SKIP=$((SKIP+1)); }
[[ -x "$CANARY" ]] || { echo "rode ./build-app.sh --with-canary-harness --embedded-profile … antes"; exit 4; }
res() { "$@" 2>&1 | grep -E "^reader.result=" | head -1; }
notfound() { [[ "$1" != *"FOUND("* ]]; }
fs_none() { security find-generic-password -s "invalid.byx-canary-test/test-canary" >/dev/null 2>&1; [[ $? -eq 44 ]]; }

PYREAD='
import ctypes,ctypes.util,sys,os
cf=ctypes.CDLL(ctypes.util.find_library("CoreFoundation")); sec=ctypes.CDLL(ctypes.util.find_library("Security"))
cf.CFStringCreateWithCString.restype=ctypes.c_void_p; cf.CFStringCreateWithCString.argtypes=[ctypes.c_void_p,ctypes.c_char_p,ctypes.c_uint32]
cf.CFDictionaryCreate.restype=ctypes.c_void_p; cf.CFDictionaryCreate.argtypes=[ctypes.c_void_p,ctypes.POINTER(ctypes.c_void_p),ctypes.POINTER(ctypes.c_void_p),ctypes.c_long,ctypes.c_void_p,ctypes.c_void_p]
sec.SecItemCopyMatching.argtypes=[ctypes.c_void_p,ctypes.POINTER(ctypes.c_void_p)]; sec.SecItemCopyMatching.restype=ctypes.c_int32
def g(lib,n): return ctypes.c_void_p.in_dll(lib,n).value
tok=""
if len(sys.argv)>1 and os.path.exists(sys.argv[1]): tok="token-read(%d bytes) "%len(open(sys.argv[1]).read().strip())
s=cf.CFStringCreateWithCString(None,b"invalid.byx-canary-test/test-canary",0x08000100); acc=cf.CFStringCreateWithCString(None,b"byx",0x08000100)
keys=[g(sec,"kSecClass"),g(sec,"kSecAttrService"),g(sec,"kSecAttrAccount"),g(sec,"kSecUseDataProtectionKeychain"),g(sec,"kSecAttrSynchronizable"),g(sec,"kSecReturnData"),g(sec,"kSecMatchLimit")]
vals=[g(sec,"kSecClassGenericPassword"),s,acc,g(cf,"kCFBooleanTrue"),g(cf,"kCFBooleanFalse"),g(cf,"kCFBooleanTrue"),g(sec,"kSecMatchLimitOne")]
K=(ctypes.c_void_p*7)(*keys); V=(ctypes.c_void_p*7)(*vals)
q=cf.CFDictionaryCreate(None,K,V,7,ctypes.addressof(ctypes.c_char.in_dll(cf,"kCFTypeDictionaryKeyCallBacks")),ctypes.addressof(ctypes.c_char.in_dll(cf,"kCFTypeDictionaryValueCallBacks")))
out=ctypes.c_void_p(); st=sec.SecItemCopyMatching(q,ctypes.byref(out))
print("reader.result=%s%s(os=%d)"%(tok,"FOUND(length)" if st==0 and out.value else "NOT_FOUND_OR_DENIED",st))
'

echo "== antes: nenhum item de teste (arquivo de chaveiro, só atributos) e nenhum canário no cofre moderno (leitor com identidade do serviço)"
fs_none && ok "nenhum item de teste no keychain de login" || bad "item de teste pré-existente no keychain de login"
R=$(res "$SVCREAD"); echo "      $R"; [[ "$R" == *NOT_FOUND* ]] && ok "cofre moderno limpo antes do teste" || bad "cofre moderno não está limpo ($R)"

echo "== A) SERVIÇO (identidade e perfil próprios): escreve / lê / atualiza / apaga o canário; grupo de acesso e atributos"
OUT=$("$CANARY" 2>&1); echo "$OUT" | grep -E "^canary\." | sed 's/^/      /'
if echo "$OUT" | grep -q "^canary.result=OK"; then
  ok "A: CRUD completo do canário (SecItem, proteção de dados)"; A_OK=1
  echo "$OUT" | grep -q "^canary.accessGroup.match=true" && ok "A: grupo de acesso EFETIVO == application-identifier assinado (padrão, sem keychain-access-groups explícito)" || bad "A: grupo de acesso efetivo difere do esperado"
  echo "$OUT" | grep -q "^canary.synchronizable=false" && ok "A: item NÃO é sincronizável (sem iCloud)" || bad "A: atributo de sincronização inesperado"
  echo "$OUT" | grep -q "^canary.accessible.whenUnlockedThisDeviceOnly=true" && ok "A: acessibilidade WhenUnlockedThisDeviceOnly" || bad "A: acessibilidade inesperada"
else
  A_OK=0; echo "BLOCKED  A: $(echo "$OUT" | grep -E '^canary.write=' | head -1)"
fi
echo "$OUT" | grep -qE "[0-9a-f]{32}" && bad "valor do canário na saída" || ok "canário ausente da saída do harness"

if [[ $A_OK -ne 1 ]]; then
  skip "B–F não avaliáveis: sem canário escrito pelo serviço, 'não achou' não prova isolamento"
  fs_none && ok "limpeza: nenhum item no keychain de login" || bad "item de teste ficou"
  echo "---- $PASS PASS, $FAIL FAIL, $SKIP SKIP  =>  SECURE SECRET STORE: BLOCKED"; exit 3
fi

echo "== canário VIVO (mantido pelo serviço) enquanto os outros tentam ler"
HD=$(mktemp -d /tmp/idh.XXXX); mkfifo "$HD/in"
"$HOLDER" < "$HD/in" > "$HD/out" 2>/dev/null &
HOLD_PID=$!; exec 7> "$HD/in" # mantém a ponta de escrita aberta: fechar = o holder apaga o canário e sai
READY=""; for i in {1..120}; do READY=$(grep -m1 "^holder.ready=" "$HD/out" 2>/dev/null); [[ -n "$READY" ]] && break; sleep 0.25; done
[[ "$READY" == "holder.ready=OK" ]] && ok "o serviço mantém o canário" || { bad "holder não ficou pronto ($READY)"; }
R=$(res "$SVCREAD"); echo "      controle: $R"; [[ "$R" == *"FOUND("* ]] && ok "CONTROLE POSITIVO: o mesmo leitor com a identidade do SERVIÇO enxerga o item (logo 'negado' abaixo é real)" || bad "controle positivo falhou ($R)"
neg() { echo "      $2: $1"; notfound "$1" && ok "$2 NÃO lê o canário" || bad "$2 LEU o canário"; }
neg "$(res "$PANELREAD")" "B) painel assinado (identidade e direitos do painel)"
neg "$(res "$JAVA" -cp "$ROOT/byx-local-service/target/classes:$HOME/.m2/repository/net/java/dev/jna/jna/5.17.0/jna-5.17.0.jar" byx.service.secrets.SecretCanaryReader)" "C) java genérico do mesmo usuário"
neg "$(/usr/bin/python3 -c "$PYREAD" 2>&1 | tail -1)" "D) Python do mesmo usuário"
# E) impostores ad-hoc: (1) mesmo identificador, sem direitos; (2) mesmo identificador COM os direitos do serviço (o macOS deve encerrá-lo)
C=$(mktemp -d /tmp/ids.XXXX); cp -R "$H/byx-secret-servicereader.app" "$C/imp1.app"; cp -R "$H/byx-secret-servicereader.app" "$C/imp2.app"
codesign --force --deep -s - --identifier "$BYX_SERVICE_ID" "$C/imp1.app" 2>/dev/null
SVC_ENT=$(ls "$HERE/build-canary/service.keychain.entitlements" 2>/dev/null)
codesign --force --deep -s - --identifier "$BYX_SERVICE_ID" --entitlements "$SVC_ENT" "$C/imp2.app" 2>/dev/null
neg "$(res "$C/imp1.app/Contents/MacOS/byx-secret-servicereader")" "E1) impostor ad-hoc, mesmo identificador, sem direitos"
OUT2=$("$C/imp2.app/Contents/MacOS/byx-secret-servicereader" 2>&1); RC=$?; echo "      E2: exit=$RC"
[[ "$OUT2" != *"FOUND("* ]] && ok "E2) impostor ad-hoc COM os direitos do serviço NÃO lê (exit=$RC; 137 = encerrado pelo macOS)" || bad "E2 LEU o canário"
rm -rf "${C:?}"
# F) processo que TEM o pairing.token: o token não concede acesso ao Keychain
T=$(mktemp -d /tmp/idf.XXXX); BYX_LOCAL_SERVICE_HOME="$T" "$SVCAPP" > "$T.log" 2>&1 &
SVC_PID=$!; for i in {1..60}; do grep -q " started " "$T.log" 2>/dev/null && break; sleep 0.25; done
neg "$(/usr/bin/python3 -c "$PYREAD" "$T/run/pairing.token" 2>&1 | tail -1)" "F) processo com o pairing.token do serviço em execução"
kill $SVC_PID 2>/dev/null
skip "G) outro Team ID: só há uma identidade de assinatura (mesmo mecanismo de requisito; não testável aqui)"
exec 7>&-; wait $HOLD_PID 2>/dev/null
grep -E "^holder\." "$HD/out" | sed 's/^/      /'
grep -q "^holder.cleanup=DELETED" "$HD/out" && ok "holder: apagou o canário ao encerrar" || bad "holder não confirmou a limpeza"

echo "== limpeza: o canário foi apagado (consulta de atributos pelo leitor do serviço; o CLI security só vê o keychain de arquivo)"
R=$(res "$SVCREAD"); echo "      $R"; [[ "$R" == *NOT_FOUND* ]] && ok "limpeza: nenhum canário restou no cofre moderno" || bad "CANÁRIO RESTANTE: invalid.byx-canary-test/test-canary (valor nunca impresso)"
fs_none && ok "limpeza: nenhum item no keychain de login" || bad "item de teste no keychain de login"
echo "---- $PASS PASS, $FAIL FAIL, $SKIP SKIP"
[[ $FAIL -eq 0 ]]
