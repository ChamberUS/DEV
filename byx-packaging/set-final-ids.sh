#!/bin/zsh
# Troca os IDs provisórios pelos FINAIS em um só passo e de forma consistente (identity.env, as duas AppIdentity.java e os testes de paridade
# que as leem). Por padrão é um ENSAIO (mostra o que mudaria). Exige reverse-DNS de um domínio que VOCÊ controla; recusa "invalid.*",
# "example.*", "test.*" e o ID provisório atual.
#   uso: ./set-final-ids.sh <app-id> <service-id>          (ensaio)
#        ./set-final-ids.sh <app-id> <service-id> --apply   (grava)
set -euo pipefail
HERE="${0:A:h}"; ROOT="${HERE:h}"
APP="${1:-}"; SVC="${2:-}"; MODE="${3:-}"
[[ -n "$APP" && -n "$SVC" ]] || { sed -n 2,7p "$0"; exit 2; }
re='^[a-z0-9-]+(\.[a-z0-9-]+){2,}$'
[[ "$APP" =~ $re && "$SVC" =~ $re ]] || { echo "IDs devem ser reverse-DNS minúsculos (ex.: com.suaempresa.byx.app)"; exit 3; }
[[ "$SVC" == "$APP".* ]] || { echo "o ID do serviço deve começar pelo ID do app (ex.: ${APP}.service)"; exit 3; }
case "$APP" in invalid.*|example.*|com.example.*|org.example.*|test.*|localhost.*) echo "ID de teste/reservado não pode ser final"; exit 3;; esac
source "$HERE/identity.env"
[[ "$APP" != "$BYX_APP_ID" || "$BYX_IDS_FINAL" == "true" ]] || { echo "este é o ID PROVISÓRIO atual; escolha um domínio controlado"; exit 3; }
FILES=("$HERE/identity.env" "$ROOT/byx-local-service/src/main/java/byx/service/identity/AppIdentity.java" "$ROOT/mvp-binance-panel/src/main/java/panel/identity/AppIdentity.java")
echo "IDs atuais: $BYX_APP_ID / $BYX_SERVICE_ID  ->  finais: $APP / $SVC"
for f in "${FILES[@]}"; do echo "  reescreve: ${f#$ROOT/}"; done
echo "ocorrências restantes do ID antigo FORA desses arquivos (revisar à mão: docs e testes com texto literal):"
grep -rIl --exclude-dir=build --exclude-dir=target --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=evidence -F "$BYX_APP_ID" "$ROOT/byx-local-service" "$ROOT/mvp-binance-panel" "$ROOT/byx-packaging" 2>/dev/null | grep -v -F -e "identity.env" -e "AppIdentity.java" | sed "s|$ROOT/|    |" || true
[[ "$MODE" == "--apply" ]] || { echo "(ensaio: nada foi alterado; use --apply)"; exit 0; }
python3 - "$APP" "$SVC" "$BYX_APP_ID" "$BYX_SERVICE_ID" "${FILES[@]}" <<'PY'
import sys,re
app,svc,old_app,old_svc=sys.argv[1:5]
for p in sys.argv[5:]:
    s=open(p).read()
    s=s.replace(old_svc,svc).replace(old_app,app)
    s=re.sub(r'(?m)^BYX_IDS_FINAL=.*$','BYX_IDS_FINAL=true',s)
    open(p,'w').write(s)
PY
echo "aplicado. Rode: mvn package nos dois projetos, ./build-app.sh e ./verify-identity.sh"
