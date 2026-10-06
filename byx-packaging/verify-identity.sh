#!/bin/zsh
# Demonstração e prova da identidade do app/peer com o BYX-MVP.app ASSINADO (build/). Usa homes temporários; não toca o home real,
# o Keychain, o banco do painel nem a captura. Saída: PASS/FAIL por caso. Rede: só o caso --market (dado público, ~25 s).
#   uso: ./verify-identity.sh [--market]
set -u
HERE="${0:A:h}"; ROOT="${HERE:h}"
source "$HERE/identity.env" # BYX_APP_ID / BYX_SERVICE_ID: fonte única
APP="$HERE/build/BYX-MVP.app"; APPEXE="$APP/Contents/MacOS/BYX-MVP"; SVCEXE="$APP/Contents/Helpers/byx-local-service.app/Contents/MacOS/byx-local-service"; HELPERAPP="$APP/Contents/Helpers/byx-local-service.app"
JAVA_HOME="${JAVA_HOME:-$HOME/dev/tools/jdk-21.0.12.1+1/Contents/Home}"; JAVA="$JAVA_HOME/bin/java"
export BYX_APP_ID BYX_TEAM="$(codesign -dvv "$APP" 2>&1 | awk -F= '/^TeamIdentifier/ {print $2}')"
PASS=0; FAIL=0
ok()   { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ if eval "$2"; then ok "$1"; else bad "$1"; fi }

start_service() { # $1=home  $2..=comando
  local home="$1"; shift
  BYX_LOCAL_SERVICE_HOME="$home" "$@" > "$home.log" 2>&1 &
  for i in {1..60}; do grep -q " started " "$home.log" 2>/dev/null && [[ -S "$home/run/service.sock" ]] && return 0; sleep 0.25; done; return 1
}
stop_services() { pkill -f "Contents/MacOS/byx-local-service" 2>/dev/null; pkill -f "byx.service.ServiceMain" 2>/dev/null; sleep 1; }

# cliente "atacante" em Python: lê o pairing.token (o mesmo usuário pode) e fala o protocolo PERFEITAMENTE
PYCLIENT='
import socket,sys,hmac,hashlib,base64,json,struct,os
home=sys.argv[1]; claims=len(sys.argv)>2
secret=base64.urlsafe_b64decode(open(home+"/run/pairing.token").read().strip()+"==")
def send(s,o):
    b=json.dumps(o).encode(); s.sendall(struct.pack(">I",len(b))+b)
def recv(s):
    h=s.recv(4)
    if len(h)<4: raise EOFError("closed")
    n=struct.unpack(">I",h)[0]; d=b""
    while len(d)<n:
        c=s.recv(n-len(d))
        if not c: raise EOFError("closed")
        d+=c
    return json.loads(d)
def proof(label,cn,sn): return base64.urlsafe_b64encode(hmac.new(secret,("byx-ipc-v1|%s|%s|%s"%(label,cn,sn)).encode(),hashlib.sha256).digest()).decode().rstrip("=")
try:
    s=socket.socket(socket.AF_UNIX); s.settimeout(5); s.connect(home+"/run/service.sock")
    cn=base64.urlsafe_b64encode(os.urandom(16)).decode().rstrip("=")
    hello={"v":1,"type":"hello","clientNonce":cn}
    if claims: hello.update({"bundleId":os.environ["BYX_APP_ID"],"pid":int(sys.argv[2]),"teamId":os.environ.get("BYX_TEAM","")})  # alegações falsas
    send(s,hello); ch=recv(s)
    if ch.get("type")=="error": print("REFUSED(%s)"%ch.get("code")); sys.exit(0)
    send(s,{"v":1,"type":"auth","clientProof":proof("client",cn,ch["serverNonce"])}); r=recv(s)
    print("ACCEPTED" if r.get("type")=="ready" else "REFUSED(%s)"%r.get("code"))
except (EOFError,ConnectionError,socket.timeout) as e:
    print("REFUSED(closed_by_service)")
'

echo "== assinatura do bundle"
codesign --verify --deep --strict "$APP" 2>/dev/null && ok "bundle assinado e íntegro" || bad "bundle assinado e íntegro"
TEAM=$(codesign -dvv "$APP" 2>&1 | awk -F= '/^TeamIdentifier/ {print $2}')
check "Team ID presente ($TEAM)" '[[ -n "$TEAM" && "$TEAM" != "not set" ]]'
check "Hardened Runtime ligado (flag runtime)" 'codesign -dv "$APP" 2>&1 | grep -q "flags=0x10000(runtime)"'
check "runtime Java EMBUTIDO no bundle (não usa o java global)" '[[ -n "$(find "$APP/Contents/runtime" -name libjvm.dylib | head -1)" ]] && ! otool -L "$APPEXE" | grep -q "/Users/.*/jdk"'
check "o executável do serviço tem identificador próprio" 'codesign -dv "$SVCEXE" 2>&1 | grep -q "^Identifier=$BYX_SERVICE_ID"'
check "painel: entitlements = somente allow-jit (sem application-identifier, sem keychain)" '[[ "$(codesign -d --entitlements - "$APP" 2>&1 | grep -c "\[Key\]")" == "1" ]] && codesign -d --entitlements - "$APP" 2>&1 | grep -q allow-jit'
check "sem disable-library-validation / debugger / dyld-env / get-task-allow (painel e serviço)" '! codesign -d --entitlements - "$APP" "$HELPERAPP" 2>&1 | grep -qE "disable-library-validation|cs.debugger|allow-dyld-environment|get-task-allow"'
SVC_KEYS=$(codesign -d --entitlements - "$HELPERAPP" 2>&1 | grep -c "\[Key\]")
if [[ -f "$HELPERAPP/Contents/embedded.provisionprofile" ]]; then
  check "serviço: SÓ application-identifier + team-identifier + allow-jit (sem keychain-access-groups/App Groups)" '[[ "$SVC_KEYS" == "3" ]] && codesign -d --entitlements - "$HELPERAPP" 2>&1 | grep -q "com.apple.application-identifier" && codesign -d --entitlements - "$HELPERAPP" 2>&1 | grep -q "com.apple.developer.team-identifier" && ! codesign -d --entitlements - "$HELPERAPP" 2>&1 | grep -qE "keychain-access-groups|application-groups"'
  check "perfil embutido só no helper do serviço (o painel não tem perfil)" '[[ ! -e "$APP/Contents/embedded.provisionprofile" ]]'
  ent_val() { codesign -d --entitlements - "$1" 2>&1 | awk '/application-identifier/ {f=1; next} f && /\[String\]/ {sub(/.*\[String\] */,""); print; exit}'; }
  echo "      painel  application-identifier: $(ent_val "$APP")(nenhum)"
  echo "      serviço application-identifier: $(ent_val "$HELPERAPP")"
  echo "      Team ID: $TEAM"
else
  check "serviço: entitlements = somente allow-jit (sem perfil)" '[[ "$SVC_KEYS" == "1" ]]'
fi

echo "== serviço EMPACOTADO (identidade própria verificada)"
T=$(mktemp -d /tmp/idv.XXXX); stop_services
start_service "$T" "$SVCEXE" || bad "serviço empacotado subiu"
check "serviço empacotado: modo packaged_verified (derivado da própria assinatura)" 'grep -q "identity=packaged_verified" "$T.log"'

echo "== peer LEGÍTIMO (BYX-MVP assinado)"
OUT=$(BYX_LOCAL_SERVICE_HOME="$T" "$APPEXE" --probe-service 2>&1)
check "app assinado conecta (CONNECTED)" 'echo "$OUT" | grep -q "^probe.state=CONNECTED"'
check "o app se vê como packaged_verified" 'echo "$OUT" | grep -q "^probe.selfIdentity=packaged_verified"'
check "e verificou o serviço (appIdentity=packaged_verified declarado)" 'echo "$OUT" | grep -q "^probe.service.appIdentity=packaged_verified"'
check "conexão continua: market capability true" 'echo "$OUT" | grep -q "^probe.marketData=true"'
check "private gate continua false" 'echo "$OUT" | grep -q "^probe.service.privateGateAllowed=false"'
check "accountData/notifications/adminOperations/secretIntegrations = false" '[[ $(echo "$OUT" | grep -cE "^probe.service.(accountData|notifications|adminOperations|secretIntegrations)=false") == 4 ]]'

echo "== ATAQUE do mesmo usuário (todos leem o pairing.token real)"
# 1. o MESMO código do painel, mas rodando no java genérico do JDK (sem a identidade assinada do app)
CP="$HERE/build/input/*"
OUT=$(BYX_LOCAL_SERVICE_HOME="$T" "$JAVA" --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED -cp "$CP" panel.app.Main --probe-service 2>&1)
check "mesmo código do painel no java genérico: REJEITADO (selfIdentity=development_unverified)" 'echo "$OUT" | grep -q "^probe.selfIdentity=development_unverified" && ! echo "$OUT" | grep -q "^probe.state=CONNECTED"'
# 2. Python com o protocolo e o token corretos
R=$(/usr/bin/python3 -c "$PYCLIENT" "$T" 2>&1)
check "python3 com token e protocolo corretos: REJEITADO ($R)" '[[ "$R" == REFUSED* ]]'
# 3. payload com bundleId/PID/Team falsos (o PID real do app assinado em execução não ajuda)
BYX_LOCAL_SERVICE_HOME="$T" "$APPEXE" --probe-service --market > /tmp/idv.market.$$ 2>&1 &
LEGIT_PID=$!; sleep 1
R=$(/usr/bin/python3 -c "$PYCLIENT" "$T" "$LEGIT_PID" 2>&1)
check "alegações falsas (bundleId, pid=$LEGIT_PID do app legítimo, teamId): REJEITADO ($R)" '[[ "$R" == REFUSED* ]]'
kill $LEGIT_PID 2>/dev/null; wait $LEGIT_PID 2>/dev/null
# 4. cópia do executável com o MESMO identificador mas assinatura ad-hoc (sem cadeia Apple/Team)
C=$(mktemp -d /tmp/idc.XXXX); cp -R "$APP" "$C/"; codesign --force --deep -s - --identifier "$BYX_APP_ID" "$C/BYX-MVP.app" 2>/dev/null
OUT=$(BYX_LOCAL_SERVICE_HOME="$T" "$C/BYX-MVP.app/Contents/MacOS/BYX-MVP" --probe-service 2>&1)
check "app com mesmo identificador, assinatura ad-hoc: REJEITADO" '! echo "$OUT" | grep -q "^probe.state=CONNECTED"'
rm -rf "$C"
# 5. o app ASSINADO, mas lançado com um vetor de injeção na JVM no ambiente
OUT=$(JAVA_TOOL_OPTIONS="-Dbyx.attack=1" BYX_LOCAL_SERVICE_HOME="$T" "$APPEXE" --probe-service 2>&1)
check "app assinado com JAVA_TOOL_OPTIONS (injeção): REJEITADO" '! echo "$OUT" | grep -q "^probe.state=CONNECTED"'
# 6. o app ASSINADO com a configuração do bundle adulterada em disco (selo quebrado)
C=$(mktemp -d /tmp/idt.XXXX); cp -R "$APP" "$C/"; echo "java-options=-Dbyx.tampered=1" >> "$C/BYX-MVP.app/Contents/app/BYX-MVP.cfg"
OUT=$(BYX_LOCAL_SERVICE_HOME="$T" "$C/BYX-MVP.app/Contents/MacOS/BYX-MVP" --probe-service 2>&1); TAMPER_RC=$?
check "app assinado com bundle adulterado (selo quebrado): REJEITADO" '! echo "$OUT" | grep -q "^probe.state=CONNECTED"'
# V2.1F-1: o lançador endurecido do painel recusa iniciar (exit 71) ANTES da JVM; a recusa do serviço (peer_bundle_modified) fica como 2ª camada (testes de unidade)
check "o próprio lançador do painel recusou iniciar com o selo quebrado (exit 71)" '[[ $TAMPER_RC -eq 71 ]]'
rm -rf "$C"
check "o serviço registrou as recusas por código fixo, sem segredo" 'grep -q "peer_rejected peer_requirement_failed" "$T.log" && grep -q "peer_rejected peer_env_unsafe" "$T.log" && ! grep -q "$(cat $T/run/pairing.token)" "$T.log"'

echo "== CONTROLE: o mesmo cliente Python é perfeito (aceito) num serviço em modo desenvolvimento"
D=$(mktemp -d /tmp/idd.XXXX)
CPS="$APP/Contents/Helpers/byx-local-service.app/Contents/app/*" # o mesmo código e as mesmas dependências do helper empacotado, mas num java GENÉRICO (sem identidade)
BYX_LOCAL_SERVICE_HOME="$D" "$JAVA" -cp "$CPS" byx.service.ServiceMain > "$D.log" 2>&1 &
for i in {1..60}; do grep -q " started " "$D.log" 2>/dev/null && break; sleep 0.25; done
check "serviço no java genérico: modo development_unverified" 'grep -q "identity=development_unverified" "$D.log"'
R=$(/usr/bin/python3 -c "$PYCLIENT" "$D" 2>&1)
check "o cliente Python é aceito aqui ($R): só a identidade o separa do caso acima" '[[ "$R" == ACCEPTED ]]'
OUT=$(BYX_LOCAL_SERVICE_HOME="$D" "$APPEXE" --probe-service 2>&1)
check "o app EMPACOTADO recusa falar com um serviço NÃO verificado (impostor com o token)" '! echo "$OUT" | grep -q "^probe.state=CONNECTED" && echo "$OUT" | grep -q "^probe.code=service_identity_not_verified"'
stop_services

echo "== REINÍCIO do serviço empacotado"
T2=$(mktemp -d /tmp/idr.XXXX)
for round in 1 2; do
  start_service "$T2" "$SVCEXE" || bad "serviço empacotado subiu (rodada $round)"
  OUT=$(BYX_LOCAL_SERVICE_HOME="$T2" "$APPEXE" --probe-service 2>&1)
  check "rodada $round: app assinado conecta" 'echo "$OUT" | grep -q "^probe.state=CONNECTED"'
  stop_services
done

if [[ "${1:-}" == "--market" ]]; then
  echo "== MERCADO PÚBLICO a partir do bundle endurecido (rede: Binance pública, ~25 s)"
  T3=$(mktemp -d /tmp/idm.XXXX); start_service "$T3" "$SVCEXE"
  OUT=$(BYX_LOCAL_SERVICE_HOME="$T3" "$APPEXE" --probe-service --market 2>&1)
  check "feed LIVE com book e preço" 'echo "$OUT" | grep -q "^probe.market.feed=LIVE" && echo "$OUT" | grep -q "^probe.market.hasPrice=true"'
  stop_services
fi
echo "---- $PASS PASS, $FAIL FAIL"
[[ $FAIL -eq 0 ]]
