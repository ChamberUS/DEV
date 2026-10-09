#!/bin/zsh
# QA EMPACOTADO da autoridade de autenticação (V2.1F). Roda contra o bundle de TESTE (build-app.sh --with-canary-harness --embedded-profile ...):
# o helper byx-auth-qa (identidade e keychain do serviço, lançador nativo endurecido) com usuários FICTÍCIOS e autoridade TEMPORÁRIA; o cliente
# byx-auth-client tem a identidade do PAINEL. Não toca panel.db, ~/.mvp-binance-panel nem nenhum item real do keychain (só o item de teste
# AUTHORITY_TEST_ANCHOR, removido ao fim). Sem argumentos de senha/token em argv: comandos por stdin.
set -uo pipefail
HERE="${0:A:h}"; APP="${1:-$HERE/build-canary/BYX-MVP.app}"
QA_JAVA_HOME="${JAVA_HOME:-$HOME/dev/tools/jdk-21.0.12.1+1/Contents/Home}"; export PATH="$QA_JAVA_HOME/bin:$PATH"
SVC="$APP/Contents/Helpers/byx-auth-qa.app/Contents/MacOS/byx-auth-qa"; CLI="$APP/Contents/MacOS/byx-auth-client"
QA="$(mktemp -d /private/tmp/byx-authqa.XXXXXX)"; chmod 700 "$QA"; AUTH="$QA/qa-authority"; CRED="$AUTH/qa-credentials.json"
PASS=0; FAIL=0; SVC_PID=""
ok()   { PASS=$((PASS+1)); echo "PASS  $1"; }
bad()  { FAIL=$((FAIL+1)); echo "FAIL  $1  [$2]"; }
expect() { if [[ "$2" == *"$3"* ]]; then ok "$1"; else bad "$1" "esperado '$3' em: ${2:0:200}"; fi; }
refute() { if [[ "$2" != *"$3"* ]]; then ok "$1"; else bad "$1" "NÃO deveria conter '$3'"; fi; }
start_svc() { env -i HOME="$HOME" PATH=/usr/bin:/bin BYX_LOCAL_SERVICE_HOME="$QA" "$SVC" >> "$QA/svc.log" 2>&1 & SVC_PID=$!
  for i in {1..120}; do [[ -S "$QA/run/service.sock" ]] && { sleep 0.3; return 0; }; sleep 0.25; done; echo "serviço não subiu"; return 1; }
stop_svc() { [[ -n "$SVC_PID" ]] && kill "$SVC_PID" 2>/dev/null; wait "$SVC_PID" 2>/dev/null; SVC_PID=""; sleep 0.5; }
cli() { BYX_LOCAL_SERVICE_HOME="$QA" "$CLI" 2>&1; }   # comandos em stdin
cleanup() { stop_svc; touch "$AUTH/qa-wipe"; env -i HOME="$HOME" PATH=/usr/bin:/bin BYX_LOCAL_SERVICE_HOME="$QA" "$SVC" >/dev/null 2>&1; rm -rf "${QA:?}"; }
trap cleanup EXIT
mkdir -p "$AUTH"; chmod 700 "$AUTH"; : > "$QA/svc.log"
touch "$AUTH/qa-reset" # recomeça do zero os itens de TESTE (um QA anterior interrompido pode ter deixado âncora/chave de teste no keychain)

echo "== 0. lançador endurecido e subida"
start_svc || exit 1
expect "serviço de QA sobe com identidade verificada e autoridade confiável" "$(cat "$QA/svc.log")" "auth_qa authority=trusted"
expect "modo de identidade empacotado e verificado" "$(cat "$QA/svc.log")" "identity=packaged_verified"
[[ "$(stat -f %Lp "$CRED")" == 600 ]] && ok "arquivo de credenciais de teste é 0600" || bad "credenciais 0600" "$(stat -f %Lp "$CRED")"
[[ "$(stat -f %Lp "$AUTH/authority.bin")" == 600 ]] && ok "arquivo da autoridade é 0600" || bad "autoridade 0600" ""
python3 "$HERE/authority-probes/confidentiality.py" "$AUTH/authority.bin" "$CRED" && ok "snapshot CIFRADO: cópia, hex-dump e strings do arquivo (mesmo usuário) não acham usuário, papel, verificador, senha nem estrutura" || bad "confidencialidade do snapshot" "ver saída"
strings -a -n 4 "$AUTH/authority.bin" | grep -qiE "admin|normal_user|argon2|role|username|passwordHash" && bad "strings acha texto estrutural" "$(strings -a -n 4 "$AUTH/authority.bin" | head -3)" || ok "strings(1) não acha texto estrutural"
[[ "$(head -c 4 "$AUTH/authority.bin")" == "BYXA" ]] && ok "só o magic fixo é legível" || bad "magic" ""

echo "== 1. login legítimo, estado e logout (cliente com a identidade do painel)"
OUT=$(printf 'login normal_user %s\nstatus\nprinttoken\nlogout\nstatus\nquit\n' "$CRED" | cli)
expect "login legítimo (USER)" "$OUT" "RESULT login OK role=USER"
expect "estado da sessão: USER, sem elevação" "$OUT" "RESULT status OK role=USER elevated=false"
expect "após logout o cliente não tem token" "$OUT" "RESULT status AUTH_REQUIRED"
TOK=$(echo "$OUT" | sed -n 's/^TOKEN=//p')
[[ ${#TOK} -eq 43 ]] && ok "token opaco de 43 caracteres (256 bits)" || bad "token" "${#TOK}"
OUT=$(printf 'adopt %s\nstatus\nquit\n' "$TOK" | cli)
expect "replay do token DEPOIS do logout é recusado pelo serviço" "$OUT" "RESULT status AUTH_REQUIRED"

echo "== 2. credenciais: senha errada, usuário desconhecido, conta desabilitada"
python3 - "$CRED" "$QA/bad.json" <<'PY'
import json,sys
c=json.load(open(sys.argv[1])); b={"normal_user":{"password":"definitely-wrong-password"},"ghost_user":{"password":"definitely-wrong-password"},"disabled_user":c["disabled_user"],"admin_user":c["admin_user"]}
json.dump(b,open(sys.argv[2],"w"))
PY
W=$(printf 'login normal_user %s\nquit\n' "$QA/bad.json" | cli); G=$(printf 'login ghost_user %s\nquit\n' "$QA/bad.json" | cli); D=$(printf 'login disabled_user %s\nquit\n' "$QA/bad.json" | cli)
expect "senha errada" "$W" "RESULT login INVALID_CREDENTIALS"
expect "usuário desconhecido: mesma resposta" "$G" "RESULT login INVALID_CREDENTIALS"
expect "conta desabilitada com a senha CERTA: mesma resposta" "$D" "RESULT login INVALID_CREDENTIALS"
[[ "$W" == "$G" && "$G" == "$D" ]] && ok "as três respostas externas são idênticas" || bad "equivalência" "$W | $G | $D"

echo "== 3. ligação da sessão ao peer (outro processo com a MESMA identidade de painel)"
( printf 'login normal_user %s\nprinttoken\nsleep 6000\nstatus\nquit\n' "$CRED" | cli > "$QA/a.out" ) & APID=$!
for i in {1..40}; do grep -q '^TOKEN=' "$QA/a.out" 2>/dev/null && break; sleep 0.25; done
TA=$(sed -n 's/^TOKEN=//p' "$QA/a.out")
B=$(printf 'adopt %s\nstatus\nquit\n' "$TA" | cli)
expect "painel B (outro processo, mesma identidade) com o token roubado: recusado" "$B" "RESULT status AUTH_REQUIRED"
PY=$(python3 "$HERE/authority-probes/probe.py" "$QA" "$TA" 2>&1)
expect "Python genérico (mesmo usuário, pairing.token lido) é recusado antes de qualquer byte" "$PY" "REJECTED_BEFORE_ANY_BYTE"
JV=$(java "$HERE/authority-probes/Probe.java" "$QA" "$TA" 2>&1 | tail -1)
expect "Java genérico (mesmo usuário, pairing.token lido) é recusado antes de qualquer byte" "$JV" "REJECTED_BEFORE_ANY_BYTE"
wait $APID
expect "o dono legítimo continua com a sessão válida" "$(cat "$QA/a.out")" "RESULT status OK role=USER"

echo "== 4. segundo fator de teste e elevação de administrador"
ADMIN_ID=$(python3 -c "import json;print(json.load(open('$CRED'))['admin_user']['id'])")
USER_ID=$(python3 -c "import json;print(json.load(open('$CRED'))['normal_user']['id'])")
OUT=$(printf 'login admin_user %s\nelevate\nquit\n' "$CRED" | cli)
expect "elevação sem segundo fator recente é recusada" "$OUT" "RESULT elevate ELEVATION_REQUIRES_MFA"
OTPFILE="$AUTH/qa-otp/$ADMIN_ID.otp"; SMSFILE="$AUTH/qa-otp/$ADMIN_ID.sms"
OUT=$(printf 'login admin_user %s\nbegin2fa\nverify2fa - %s\nelevate\nstatus\nquit\n' "$CRED" "$OTPFILE" | cli)
expect "e-mail verificado, mas o SMS ainda é exigido: sem elevação" "$OUT" "RESULT verify2fa OK"
expect "o 1º estágio pede o 2º (next=SMS)" "$OUT" "next=SMS"
expect "e-mail sozinho NÃO eleva" "$OUT" "RESULT elevate ELEVATION_REQUIRES_MFA"
OUT=$(printf 'login admin_user %s\nbegin2fa\nverify2fa - %s\nsms\nverifysms %s\nelevate\nstatus\nverify2fa - %s\nquit\n' "$CRED" "$OTPFILE" "$SMSFILE" "$OTPFILE" | cli)
expect "segundo fator de dois estágios concluído" "$OUT" "RESULT verifysms OK"
expect "ADMIN com e-mail+SMS recentes eleva (propriedade temporária da sessão)" "$OUT" "RESULT elevate OK"
expect "estado: ADMIN elevado" "$OUT" "RESULT status OK role=ADMIN elevated=true"
expect "reuso do mesmo código (replay) é recusado" "$(echo "$OUT" | tail -2)" "CHALLENGE_INVALID"
refute "os códigos nunca saem do serviço para a saída do cliente" "$OUT" "$(cat "$OTPFILE")"

echo "== 4b. dispositivo confiável do serviço (inscrever, reduzir o 2º fator, revogar) e trava de migração"
OUT=$(printf 'login admin_user %s\nbegin2fa\nverify2fa - %s\nsms\nverifysms %s\nelevate\nenroll\ndevices\nquit\n' "$CRED" "$OTPFILE" "$SMSFILE" | cli)
expect "inscrição do dispositivo (ADMIN elevado + 2º fator fresco)" "$OUT" "RESULT enroll OK"
DEV=$(echo "$OUT" | sed -n 's/^DEVICES=\([0-9a-f]\{32\}\),ACTIVE.*/\1/p' | head -1)
[[ ${#DEV} -eq 32 ]] && ok "o dispositivo aparece ATIVO na lista do serviço" || bad "lista de dispositivos" "$OUT"
# d1eca78 (revisão de segurança): o dispositivo confiável NÃO reduz mais o 2º fator para a elevação; a elevação exige MFA recente e é ABSOLUTA (5 min, sem deslizar)
OUT=$(printf 'login admin_user %s\nelevate\nstatus\nquit\n' "$CRED" | cli)
expect "dispositivo confiável inscrito NÃO substitui o 2º fator: a elevação continua exigindo MFA" "$OUT" "RESULT elevate ELEVATION_REQUIRES_MFA"
expect "o serviço informa o dispositivo, mas não o usa para elevar" "$OUT" "trustedDevice=true"
OUT=$(printf 'login admin_user %s\nrevokedev %s\nbegin2fa\nverify2fa - %s\nsms\nverifysms %s\nelevate\nrevokedev %s\nstatus\nquit\n' "$CRED" "$DEV" "$OTPFILE" "$SMSFILE" "$DEV" | cli)
expect "revogar exige elevação de administrador (sem ela: DENIED)" "$OUT" "RESULT revokedev DENIED"
expect "elevado (2º fator real), a revogação do dispositivo funciona" "$OUT" "RESULT revokedev OK"
expect "depois de revogado, o dispositivo deixa de constar" "$(echo "$OUT" | tail -2)" "trustedDevice=false"
OUT=$(printf 'login normal_user %s\nbegin2fa\nverify2fa - %s\nsms\nverifysms %s\nenroll\nquit\n' "$CRED" "$AUTH/qa-otp/$USER_ID.otp" "$AUTH/qa-otp/$USER_ID.sms" | cli)
expect "um USER não inscreve dispositivo" "$OUT" "RESULT enroll DENIED"
stop_svc; touch "$AUTH/qa-freeze"; : > "$QA/svc.log"; start_svc; rm -f "$AUTH/qa-freeze"
OUT=$(printf 'login admin_user %s\nbegin2fa\nverify2fa - %s\nsms\nverifysms %s\nelevate\nenroll\nchangepw admin_user %s qa-new-password-frozen-1\nlogout\nquit\n' "$CRED" "$OTPFILE" "$SMSFILE" "$CRED" | cli)
expect "trava de migração: login, 2º fator e elevação seguem funcionando" "$OUT" "RESULT elevate OK"
expect "trava de migração: inscrição de dispositivo recusada (FROZEN)" "$OUT" "RESULT enroll FROZEN"
expect "trava de migração: troca de senha recusada (FROZEN)" "$OUT" "RESULT changepw FROZEN"
stop_svc; touch "$AUTH/qa-unfreeze"; : > "$QA/svc.log"; start_svc; rm -f "$AUTH/qa-unfreeze"
OUT=$(printf 'login admin_user %s\nchangepw admin_user %s qa-new-password-after-unfreeze-1\nquit\n' "$CRED" "$CRED" | cli)
expect "depois da validação explícita (sem trava) a troca de senha de teste funciona" "$OUT" "RESULT changepw OK"
python3 - "$CRED" <<'PY'
import json,sys
p=sys.argv[1]; c=json.load(open(p)); c["admin_user"]["password"]="qa-new-password-after-unfreeze-1"; json.dump(c,open(p,"w"))
PY

echo "== 5. reinício do serviço invalida toda sessão (nenhuma reconstrução automática)"
( printf 'login normal_user %s\nprinttoken\nsleep 4000\nquit\n' "$CRED" | cli > "$QA/r.out" ) & RPID=$!
for i in {1..40}; do grep -q '^TOKEN=' "$QA/r.out" 2>/dev/null && break; sleep 0.25; done
TR=$(sed -n 's/^TOKEN=//p' "$QA/r.out"); stop_svc; start_svc
OUT=$(printf 'adopt %s\nstatus\nquit\n' "$TR" | cli)
expect "token anterior ao reinício → AUTH_REQUIRED" "$OUT" "RESULT status AUTH_REQUIRED"
wait $RPID
OUT=$(printf 'login normal_user %s\nquit\n' "$CRED" | cli); expect "novo login após o reinício funciona (login novo, não persistente)" "$OUT" "RESULT login OK"

echo "== 6. adulteração da autoridade com a âncora REAL no keychain do serviço"
AF="$AUTH/authority.bin"; cp "$AF" "$QA/authority.v0"
tamper() { # $1=descrição $2=modo (authority-probes/tamper.py)
  stop_svc; cp "$QA/authority.good" "$AF"; python3 "$HERE/authority-probes/tamper.py" "$AF" "$2" || { bad "$1" "edição falhou"; return; }
  : > "$QA/svc.log"; start_svc; local o=$(printf 'login normal_user %s\nquit\n' "$CRED" | cli)
  expect "$1: serviço detecta e falha fechado" "$(cat "$QA/svc.log")" "authority=untrusted"; expect "$1: login → AUTHORITY_UNAVAILABLE" "$o" "RESULT login AUTHORITY_UNAVAILABLE"; }
cp "$AF" "$QA/authority.good"
tamper "bit do texto cifrado" ciphertext
tamper "etiqueta GCM" tag
tamper "nonce" nonce
tamper "versão da autoridade no cabeçalho (AAD)" version
tamper "texto cifrado truncado" truncate
tamper "texto cifrado acima do limite" oversize
tamper "formato antigo em claro / lixo" garbage
stop_svc; cp "$QA/authority.good" "$AF"; : > "$QA/svc.log"; start_svc
expect "arquivo original restaurado após reinício: confiável de novo" "$(cat "$QA/svc.log")" "authority=trusted"
OUT=$(printf 'login normal_user %s\nchangepw normal_user %s %s\nquit\n' "$CRED" "$CRED" "qa-new-password-$RANDOM-xyz" | cli)
expect "troca de senha de teste (versão da autoridade sobe)" "$OUT" "RESULT changepw OK"
stop_svc; cp "$QA/authority.good" "$AF"; : > "$QA/svc.log"; start_svc
expect "ROLLBACK para snapshot antigo detectado (âncora do serviço está na versão nova)" "$(cat "$QA/svc.log")" "reason=rollback"
stop_svc; rm -f "$AF"; : > "$QA/svc.log"; start_svc
expect "arquivo apagado com âncora presente: falha fechada" "$(cat "$QA/svc.log")" "authority=untrusted"

echo "== 7. injeção na JVM do serviço (JAVA_TOOL_OPTIONS / -Xbootclasspath/a)"
stop_svc; touch "$AUTH/qa-reset"
EV="$QA/evil"; mkdir -p "$EV/src/byx/service/auth" "$EV/classes"
cat > "$EV/src/byx/service/auth/AuthQaMain.java" <<'JV'
package byx.service.auth;
public final class AuthQaMain { public static void main(String[] a) throws Exception { java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("EVIL_MARKER")), "EXECUTED"); } }
JV
javac -d "$EV/classes" "$EV/src/byx/service/auth/AuthQaMain.java" && (cd "$EV/classes" && jar cf "$EV/evil.jar" .)
rm -f "$QA/marker.control" "$QA/marker.helper"
CP=$(ls "$APP"/Contents/Helpers/byx-auth-qa.app/Contents/app/*.jar | tr '\n' ':')
EVIL_MARKER="$QA/marker.control" java -Xbootclasspath/a:"$EV/evil.jar" -cp "$CP" byx.service.auth.AuthQaMain >/dev/null 2>&1
[[ -f "$QA/marker.control" ]] && ok "controle positivo: o jar hostil É eficaz num java genérico" || bad "controle positivo" "marcador ausente"
EVIL_MARKER="$QA/marker.helper" JAVA_TOOL_OPTIONS="-Xbootclasspath/a:$EV/evil.jar" _JAVA_OPTIONS="-Xbootclasspath/a:$EV/evil.jar" JDK_JAVA_OPTIONS="-Xbootclasspath/a:$EV/evil.jar" \
  BYX_LOCAL_SERVICE_HOME="$QA" "$SVC" >> "$QA/svc.log" 2>&1 & SVC_PID=$!
wait "$SVC_PID"; INJECTION_RC=$?; SVC_PID=""
[[ ! -f "$QA/marker.helper" ]] && ok "helper endurecido: código injetado por JAVA_TOOL_OPTIONS/_JAVA_OPTIONS/JDK_JAVA_OPTIONS NÃO executou" || bad "injeção" "marcador criado"
[[ $INJECTION_RC -eq 74 && ! -S "$QA/run/service.sock" ]] && ok "serviço de QA recusa ambiente hostil antes da JVM (exit 74), sem socket" || bad "serviço com ambiente hostil" "exit=$INJECTION_RC"
stop_svc

echo "== 8. bundle adulterado não inicia"
T="$(mktemp -d /private/tmp/byx-authqa-tamper.XXXXXX)"; cp -cR "$APP" "$T/BYX-MVP.app"
J=$(ls "$T"/BYX-MVP.app/Contents/Helpers/byx-auth-qa.app/Contents/app/byx-local-service-*.jar | head -1); printf 'x' >> "$J"
BYX_LOCAL_SERVICE_HOME="$QA" "$T/BYX-MVP.app/Contents/Helpers/byx-auth-qa.app/Contents/MacOS/byx-auth-qa" > "$T/out" 2>&1; RC=$?
[[ $RC -eq 71 ]] && ok "jar do serviço alterado → lançador recusa (exit 71)" || bad "bundle adulterado" "exit=$RC $(cat "$T/out")"
expect "mensagem fixa, sem caminhos" "$(cat "$T/out")" "bundle seal invalid"
rm -rf "${T:?}"

echo "== 9. PAINEL endurecido: injeção, DYLD, adulteração do bundle, e aceito pelo serviço endurecido"
PANEL="$APP/Contents/MacOS/BYX-MVP"
start_svc
OUT=$(BYX_LOCAL_SERVICE_HOME="$QA" "$PANEL" --probe-service 2>&1)
expect "painel endurecido (lançador nativo) é ACEITO pelo serviço endurecido" "$OUT" "probe.state=CONNECTED"
expect "o painel se vê como packaged_verified" "$OUT" "probe.selfIdentity=packaged_verified"
EVP="$QA/evilpanel"; mkdir -p "$EVP/src/panel/app" "$EVP/classes"
cat > "$EVP/src/panel/app/Main.java" <<'JV'
package panel.app;
public final class Main { public static void main(String[] a) throws Exception { java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("EVIL_MARKER")), "EXECUTED"); } }
JV
javac -d "$EVP/classes" "$EVP/src/panel/app/Main.java" && (cd "$EVP/classes" && jar cf "$EVP/evil.jar" .)
PCP=$(python3 - "$APP/Contents/app/BYX-MVP.cfg" "$APP/Contents/app" <<'PY'
import sys
print(":".join(sys.argv[2]+"/"+l.split("/",1)[1].strip() for l in open(sys.argv[1]) if l.startswith("app.classpath=")))
PY
)
rm -f -- "$QA"/marker.p.*(N)
EVIL_MARKER="$QA/marker.p.control" java -Xbootclasspath/a:"$EVP/evil.jar" -cp "$PCP" panel.app.Main >/dev/null 2>&1
[[ -f "$QA/marker.p.control" ]] && ok "controle positivo (painel): JAVA_TOOL_OPTIONS/-Xbootclasspath hostil roda num java genérico" || bad "controle positivo painel" ""
for V in JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS; do
  rm -f "$QA/marker.p.$V"
  env EVIL_MARKER="$QA/marker.p.$V" "$V=-Xbootclasspath/a:$EVP/evil.jar" BYX_LOCAL_SERVICE_HOME="$QA" "$PANEL" --probe-service >/dev/null 2>&1
  [[ ! -f "$QA/marker.p.$V" ]] && ok "painel endurecido: código injetado por $V NÃO executou" || bad "injeção $V no painel" "marcador criado"
done
# DYLD_INSERT_LIBRARIES: controle positivo num executável sem Hardened Runtime; o painel endurecido não carrega
cat > "$QA/dy.c" <<'CC'
#include <stdlib.h>
#include <stdio.h>
__attribute__((constructor)) static void hit(void) { const char *m = getenv("EVIL_MARKER"); if (m) { FILE *f = fopen(m, "w"); if (f) { fputs("DYLD", f); fclose(f); } } }
CC
cat > "$QA/dyhost.c" <<'CC'
int main(void) { return 0; }
CC
clang -arch x86_64 -dynamiclib -o "$QA/evil.dylib" "$QA/dy.c" && clang -arch x86_64 -o "$QA/dyhost" "$QA/dyhost.c"
rm -f "$QA/marker.dyld.control" "$QA/marker.dyld.panel"
EVIL_MARKER="$QA/marker.dyld.control" DYLD_INSERT_LIBRARIES="$QA/evil.dylib" "$QA/dyhost" >/dev/null 2>&1
[[ -f "$QA/marker.dyld.control" ]] && ok "controle positivo: DYLD_INSERT_LIBRARIES carrega a biblioteca hostil num executável comum" || bad "controle DYLD" "marcador ausente"
EVIL_MARKER="$QA/marker.dyld.panel" DYLD_INSERT_LIBRARIES="$QA/evil.dylib" BYX_LOCAL_SERVICE_HOME="$QA" "$PANEL" --probe-service >/dev/null 2>&1
[[ ! -f "$QA/marker.dyld.panel" ]] && ok "painel endurecido: DYLD_INSERT_LIBRARIES não carregou a biblioteca hostil" || bad "DYLD no painel" "marcador criado"
stop_svc
T="$(mktemp -d /private/tmp/byx-authqa-tamper.XXXXXX)"; cp -cR "$APP" "$T/BYX-MVP.app"
J=$(ls "$T"/BYX-MVP.app/Contents/app/mvp-binance-panel-*.jar | head -1); printf 'x' >> "$J"
"$T/BYX-MVP.app/Contents/MacOS/BYX-MVP" --probe-service > "$T/out" 2>&1; RC=$?
[[ $RC -eq 71 ]] && ok "painel com jar alterado → lançador recusa (exit 71)" || bad "painel adulterado (jar)" "exit=$RC"
cp -c "$APP/Contents/app/mvp-binance-panel-0.1.0.jar" "$J"
echo "java-options=-Dtampered=1" >> "$T/BYX-MVP.app/Contents/app/BYX-MVP.cfg"
"$T/BYX-MVP.app/Contents/MacOS/BYX-MVP" --probe-service > "$T/out2" 2>&1; RC=$?
[[ $RC -eq 71 ]] && ok "painel com .cfg alterado → lançador recusa (exit 71)" || bad "painel adulterado (cfg)" "exit=$RC"
expect "mensagem fixa, sem caminhos" "$(cat "$T/out2")" "bundle seal invalid"
rm -rf "${T:?}"
T2="$(mktemp -d /private/tmp/byx-authqa-clean.XXXXXX)"; cp -cR "$APP" "$T2/BYX-MVP.app"; start_svc
[[ -S "$QA/run/service.sock" ]] && OUT=$(BYX_LOCAL_SERVICE_HOME="$QA" "$T2/BYX-MVP.app/Contents/MacOS/BYX-MVP" --probe-service 2>&1)
expect "cópia LIMPA do bundle (restaurado) inicia e conecta normalmente" "$OUT" "probe.state=CONNECTED"
stop_svc; rm -rf "${T2:?}"

echo; echo "RESUMO: $PASS PASS, $FAIL FAIL"; [[ $FAIL -eq 0 ]]
