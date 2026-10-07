#!/bin/zsh
# QA EMPACOTADO do COMPORTAMENTO do produto depois do cutover (V2.1G), em home TEMPORÁRIO: o app inicia o helper do serviço do PRÓPRIO bundle; sem autoridade
# preparada nenhum login é possível (não existe fallback para o banco legado); o gate privado segue fechado; o artefato não contém o caminho de autenticação antigo.
set -uo pipefail
# uso: ./verify-cutover.sh --artifact final|canary [--app <caminho/BYX-MVP.app>]
#   final  = o produto: NÃO pode conter nenhum helper de QA/segurança (byx-auth-client etc.); a ausência é verificada (EXPECTED_ABSENT), a presença é FAIL.
#   canary = variante de QA/segurança (build-app.sh --with-canary-harness): byx-auth-client é requisito; a ausência é FAIL.
# O tipo é SEMPRE explícito (nada é inferido do nome da pasta); tipo ausente/desconhecido = erro (exit 2).
HERE="${0:A:h}"; ARTIFACT=""; APP=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --artifact) ARTIFACT="${2:-}"; shift 2;;
    --app) APP="${2:-}"; shift 2;;
    *) echo "FAIL  argumento desconhecido: $1 (uso: --artifact final|canary [--app PATH])" >&2; exit 2;;
  esac
done
case "$ARTIFACT" in
  final) : "${APP:=$HERE/build/BYX-MVP.app}";;
  canary) : "${APP:=$HERE/build-canary/BYX-MVP.app}";;
  *) echo "FAIL  --artifact final|canary é obrigatório (recebido: '${ARTIFACT}'); o tipo não é inferido do caminho" >&2; exit 2;;
esac
[[ -d "$APP/Contents" ]] || { echo "FAIL  bundle inexistente: $APP" >&2; exit 2; }
PANEL="$APP/Contents/MacOS/BYX-MVP"; CLI="$APP/Contents/MacOS/byx-auth-client"
T="$(mktemp -d /tmp/byx-cutqa.XXXXXX)"; chmod 700 "$T"
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "PASS  $1"; }
bad()  { FAIL=$((FAIL+1)); echo "FAIL  $1  [$2]"; }
expect() { if [[ "$2" == *"$3"* ]]; then ok "$1"; else bad "$1" "esperado '$3' em: ${2:0:300}"; fi; }
refute() { if [[ "$2" != *"$3"* ]]; then ok "$1"; else bad "$1" "NÃO deveria conter '$3'"; fi; }
trap 'pkill -f "[b]yx-local-service.app/Contents/MacOS" 2>/dev/null; rm -rf "${T:?}"' EXIT

echo "== 1. o app inicia o serviço do próprio bundle (sem daemon, sem argumento, sem configuração)"
OUT=$(BYX_LOCAL_SERVICE_HOME="$T" "$PANEL" --probe-service --ensure-service 2>&1)
expect "o lançador encontra o helper do PRÓPRIO bundle" "$OUT" "probe.launcher.available=true"
expect "e o serviço sobe e atende o handshake de pareamento" "$OUT" "probe.launcher.ensured=true"
expect "painel empacotado ↔ serviço empacotado: conectados" "$OUT" "probe.state=CONNECTED"
expect "identidade verificada nos dois lados" "$OUT" "probe.service.appIdentity=packaged_verified"
expect "o serviço do produto monta a autenticação" "$OUT" "probe.service.authentication=true"
expect "gate privado fechado" "$OUT" "probe.service.privateGateAllowed=false"
expect "e exige revisão explícita" "$OUT" "probe.service.privateGateReviewRequired=true"
expect "nenhuma capacidade privada habilitada" "$(echo "$OUT" | grep -cE '^probe.service.(accountData|notifications|adminOperations|secretIntegrations)=false')" "4"
sleep 1
pgrep -f "[b]yx-local-service.app/Contents/MacOS" >/dev/null && bad "o serviço iniciado pelo app deveria encerrar com o app" "ainda rodando" || ok "o serviço iniciado pelo app encerra junto com ele (shutdown hook; só o que o app iniciou)"
[[ -f "$T/service.log" && "$(stat -f %Lp "$T/service.log")" == 600 ]] && ok "service.log 0600 (só eventos fixos)" || bad "service.log" ""
refute "o log não contém segredo" "$(cat "$T/service.log" 2>/dev/null)" "password"

if [[ "$ARTIFACT" == final ]]; then
echo "== 2. artefato FINAL: nenhum helper de QA/segurança (EXPECTED_ABSENT) e o serviço não cria autoridade sozinho"
for h in MacOS/byx-auth-client MacOS/byx-secret-reader Helpers/byx-auth-qa.app Helpers/byx-migrate-qa.app Helpers/byx-secret-canary.app Helpers/byx-secret-holder.app Helpers/byx-secret-servicereader.app app/byx-auth-client.cfg app/byx-secret-reader.cfg; do
  [[ ! -e "$APP/Contents/$h" ]] && ok "EXPECTED_ABSENT: $h" || bad "helper de QA presente no artefato FINAL: $h" "o final não pode conter o harness do canary"
done
BYX_LOCAL_SERVICE_HOME="$T" "$APP/Contents/Helpers/byx-local-service.app/Contents/MacOS/byx-local-service" >/dev/null 2>&1 &
SP=$!; for i in {1..100}; do [[ -S "$T/run/service.sock" ]] && break; sleep 0.25; done; sleep 0.5
[[ ! -e "$T/authority/authority.bin" ]] && ok "o serviço NÃO cria autoridade sozinho (nem admin padrão)" || bad "autoridade criada" ""
kill $SP 2>/dev/null; wait $SP 2>/dev/null

else
echo "== 2. artefato CANARY: byx-auth-client é obrigatório; sem autoridade preparada NENHUM login é possível (e não há fallback para o banco legado)"
[[ -x "$CLI" ]] && ok "canary: byx-auth-client presente (requisito de QA)" || { bad "canary sem byx-auth-client" "ausência no canary é FAIL"; }
mkdir -p "$T/fake"; printf '{"anyone":{"password":"any-password-value-1"}}' > "$T/fake/c.json"; chmod 600 "$T/fake/c.json"
# o CLI não inicia serviço: sobe um para o teste
BYX_LOCAL_SERVICE_HOME="$T" "$APP/Contents/Helpers/byx-local-service.app/Contents/MacOS/byx-local-service" >/dev/null 2>&1 &
SP=$!; for i in {1..100}; do [[ -S "$T/run/service.sock" ]] && break; sleep 0.25; done; sleep 0.5
OUT=$(printf 'login anyone %s\nstatus\nquit\n' "$T/fake/c.json" | BYX_LOCAL_SERVICE_HOME="$T" "$CLI" 2>&1)
expect "autoridade não preparada: AUTHORITY_UNAVAILABLE" "$OUT" "RESULT login AUTHORITY_UNAVAILABLE"
refute "e nenhuma sessão nasce" "$OUT" "RESULT login OK"
[[ ! -e "$T/authority/authority.bin" ]] && ok "o serviço NÃO cria autoridade sozinho (nem admin padrão)" || bad "autoridade criada" ""
kill $SP 2>/dev/null; wait $SP 2>/dev/null

fi

echo "== 3. o artefato não contém o caminho de autenticação antigo"
J="$APP/Contents/app/mvp-binance-panel-0.1.0.jar"
for c in panel/auth/PasswordHasher panel/auth/OtpService panel/auth/PersistentRateLimiter panel/security/MacOsKeychainSecretStore panel/user/SqliteUserRepository panel/auth/ResendEmailOtpProvider panel/auth/TwilioVerifySmsProvider; do
  unzip -l "$J" | grep -q "$c.class" && bad "classe legada no jar: $c" "" || ok "classe legada ausente: $c"
done
ls "$APP/Contents/app" | grep -qi "resend\|okhttp" && bad "SDK do provedor no painel" "$(ls "$APP/Contents/app" | grep -i 'resend\|okhttp')" || ok "o painel não traz SDK de provedor (Resend/OkHttp)"
[[ ! -e "$APP/../../setup-local-2fa.sh" ]] && ok "sem setup de 2FA legado" || bad "setup legado" ""

echo; echo "ARTEFATO: $ARTIFACT ($APP)"; echo "RESUMO: $PASS PASS, $FAIL FAIL"; [[ $FAIL -eq 0 ]]
