#!/bin/zsh
# QA EMPACOTADO da MIGRAÇÃO de autoridade (V2.1G) com dados SINTÉTICOS: banco legado sintético, itens legados sintéticos (namespace invalid.byx-legacy-test),
# alvo de TESTE (ids de escopo TEST). Roda contra o bundle de TESTE (build-app.sh --with-canary-harness --embedded-profile ...): helper byx-migrate-qa (identidade
# do serviço, lançador endurecido) e byx-auth-qa servindo a autoridade MIGRADA. Não toca ~/.mvp-binance-panel, ~/.byx-local-service nem nenhum item real do keychain.
set -uo pipefail
HERE="${0:A:h}"; APP="${1:-$HERE/build-canary/BYX-MVP.app}"
MIG="$APP/Contents/Helpers/byx-migrate-qa.app/Contents/MacOS/byx-migrate-qa"; SVC="$APP/Contents/Helpers/byx-auth-qa.app/Contents/MacOS/byx-auth-qa"; CLI="$APP/Contents/MacOS/byx-auth-client"
QA="$(mktemp -d /tmp/byx-migqa.XXXXXX)"; chmod 700 "$QA"; LEG="$QA/legacy"; AUTH="$QA/qa-authority"; PW="$LEG/qa-passwords.json"
PASS=0; FAIL=0; SVC_PID=""
ok()   { PASS=$((PASS+1)); echo "PASS  $1"; }
bad()  { FAIL=$((FAIL+1)); echo "FAIL  $1  [$2]"; }
expect() { if [[ "$2" == *"$3"* ]]; then ok "$1"; else bad "$1" "esperado '$3' em: ${2:0:300}"; fi; }
refute() { if [[ "$2" != *"$3"* ]]; then ok "$1"; else bad "$1" "NÃO deveria conter '$3'"; fi; }
M() { BYX_LOCAL_SERVICE_HOME="$QA" "$MIG" "$@" 2>&1 | grep -v "^SLF4J"; }
cli() { BYX_LOCAL_SERVICE_HOME="$QA" "$CLI" 2>&1; }
start_svc() { BYX_LOCAL_SERVICE_HOME="$QA" "$SVC" >> "$QA/svc.log" 2>&1 & SVC_PID=$!
  for i in {1..120}; do [[ -S "$QA/run/service.sock" ]] && { sleep 0.3; return 0; }; sleep 0.25; done; echo "serviço não subiu"; return 1; }
stop_svc() { [[ -n "$SVC_PID" ]] && kill "$SVC_PID" 2>/dev/null; wait "$SVC_PID" 2>/dev/null; SVC_PID=""; sleep 0.5; }
cleanup() { stop_svc; M cleanup >/dev/null; rm -rf "${QA:?}"; }
trap cleanup EXIT
: > "$QA/svc.log"
dbsum() { shasum -a 256 "$LEG/panel.db" | cut -d' ' -f1; }
listing() { ls -la "$LEG" | awk '{print $1,$9}' | sort; }
sec_values() { python3 - "$LEG" <<'PY'
import json,sqlite3,sys,os
d=sys.argv[1]; vals=[]
vals+= [v["password"] for v in json.load(open(os.path.join(d,"qa-passwords.json"))).values()]
c=sqlite3.connect("file:"+os.path.join(d,"panel.db")+"?mode=ro&immutable=1",uri=True)
for h,e,p in c.execute("select password_hash,email,phone from users"):
    vals+=[h,e]+([p] if p else [])
vals+=["re_SYNTHETIC_LEGACY_RESEND_KEY","SYNTHETIC-LEGACY-TWILIO-SECRET","SYNTHETIC-LEGACY-DEVICE-TOKEN","f"*64]
print("\n".join(vals))
PY
}
leaks() { # $1=texto  → lista de valores sensíveis encontrados
  local t="$1" v; sec_values | while IFS= read -r v; do [[ -n "$v" && "$t" == *"$v"* ]] && echo "LEAK:${v:0:6}"; done; }

echo "== 0. dados sintéticos"
M cleanup >/dev/null # remove itens de TESTE (legados sintéticos e alvo) que um QA anterior interrompido possa ter deixado
OUT=$(M seed); expect "banco, provedores e itens legados SINTÉTICOS criados" "$OUT" "RESULT OK seed"
OUT=$(M legacy-status); expect "itens legados presentes (3)" "$(echo "$OUT" | grep -c PRESENT)" "3"
H0=$(dbsum); L0=$(listing)

echo "== 1. auditoria SOMENTE LEITURA"
OUT=$(M audit)
expect "contagens: 3 usuários, 1 ADMIN, 1 desabilitado" "$OUT" "users.total=3"
expect "papéis" "$OUT" "users.byRole.ADMIN=1"
expect "estados" "$OUT" "users.disabled=1"
expect "algoritmo do verificador (só metadados)" "$OUT" "argon2id v=19 m=19456,t=2,p=1=3"
expect "normalização visível (1 usuário vira minúscula)" "$OUT" "users.usernamesLowercased=1"
expect "dispositivo confiável apenas contado" "$OUT" "trustedDevices.active=1"
L=$(leaks "$OUT"); [[ -z "$L" ]] && ok "a auditoria não imprime hash, senha, e-mail, telefone, token nem segredo" || bad "vazamento na auditoria" "$L"
[[ "$(dbsum)" == "$H0" ]] && ok "banco legado byte a byte idêntico após a auditoria" || bad "banco mudou" ""
[[ "$(listing)" == "$L0" ]] && ok "nenhum arquivo auxiliar (journal/wal) foi criado ao lado do banco" || bad "diretório legado mudou" "$(listing)"
[[ ! -e "$AUTH/authority.bin" ]] && ok "a auditoria não escreveu autoridade nenhuma" || bad "autoridade criada" ""

echo "== 2. manifesto (sem segredos) e fonte alterada"
OUT=$(M plan); expect "plano gravado" "$OUT" "status=PLANNED"
MF="$AUTH/migration-manifest.json"; MFT=$(cat "$MF")
for f in manifestVersion dbSha256 usersDigest authorityFormatVersion serviceCodeRequirement com.buynnex.byx.service NOT_MIGRATED_INVALIDATED_AT_CUTOVER_LEGACY_PRESERVED; do expect "manifesto tem $f" "$MFT" "$f"; done
L=$(leaks "$MFT"); [[ -z "$L" ]] && ok "o manifesto não contém verificador, senha, e-mail, telefone nem segredo" || bad "vazamento no manifesto" "$L"
[[ "$(stat -f %Lp "$MF")" == 600 ]] && ok "manifesto 0600" || bad "manifesto 0600" ""
M tamper-source >/dev/null
OUT=$(echo PREPARE-REAL-MIGRATION | M prepare)
expect "legado alterado depois do plano: prepare PARA" "$OUT" "RESULT STOP source_users_changed"
[[ ! -e "$AUTH/authority.bin" ]] && ok "e nada foi escrito" || bad "autoridade criada" ""
M seed >/dev/null; M plan >/dev/null; H1=$(dbsum); L1=$(listing)

echo "== 3. preparar: confirmação, importação, verificação, segredos"
OUT=$(echo "yes" | M prepare); expect "sem a frase exata nada acontece" "$OUT" "RESULT STOP confirmation_required"
[[ ! -e "$AUTH/authority.bin" ]] && ok "nenhuma autoridade sem a confirmação" || bad "autoridade criada" ""
OUT=$(echo PREPARE-REAL-MIGRATION | M prepare)
expect "preparado" "$OUT" "status=PREPARED"
expect "segredo Resend copiado (lido uma vez do legado → cofre do serviço → relido e comparado)" "$OUT" "secrets.RESEND=PREPARED"
expect "segredo Twilio copiado" "$OUT" "secrets.TWILIO=PREPARED"
expect "dispositivo confiável legado NÃO migrado" "$OUT" "trustedDevice=NOT_MIGRATED_LEGACY_PRESERVED"
expect "janela de segurança ligada desde o início" "$OUT" "freeze=true"
L=$(leaks "$OUT"); [[ -z "$L" ]] && ok "a saída do prepare não contém valor sensível" || bad "vazamento no prepare" "$L"
[[ "$(dbsum)" == "$H1" && "$(listing)" == "$L1" ]] && ok "banco legado intacto e sem arquivos novos depois do prepare" || bad "legado mudou" ""
OUT=$(M legacy-status); expect "itens legados continuam presentes (nada apagado)" "$(echo "$OUT" | grep -c PRESENT)" "3"
SNAP=$(strings -a -n 4 "$AUTH/authority.bin")
for needle in qa_admin qa_user argon2 example.test "+5511"; do refute "snapshot da autoridade migrada é cifrado (sem '$needle')" "$SNAP" "$needle"; done
ST=$(cat "$AUTH/migration-state.json"); L=$(leaks "$ST"); [[ -z "$L" ]] && ok "o arquivo de estado não contém segredo" || bad "vazamento no estado" "$L"
OUT=$(M verify); expect "verificação fonte × alvo" "$OUT" "verification=PASS"
expect "segredos de produção presentes no cofre" "$OUT" "vaultSecrets.MIGRATION_TEST_RESEND=PRESENT"
OUT=$(M plan); expect "novo plano sobre autoridade já importada é recusado" "$OUT" "RESULT STOP already_imported"

echo "== 4. a autoridade MIGRADA serve o login com a senha ANTIGA (sem nunca pedir a senha para migrar)"
start_svc
OUT=$(printf 'login qa_admin %s\nstatus\nlogout\nquit\n' "$PW" | cli)
expect "senha antiga do administrador funciona" "$OUT" "RESULT login OK role=ADMIN"
OUT=$(printf 'login qa_user %s\nstatus\nquit\n' "$PW" | cli); expect "senha antiga do usuário funciona (USER, ninguém foi promovido)" "$OUT" "RESULT login OK role=USER"
OUT=$(printf 'login qa_off %s\nquit\n' "$PW" | cli); expect "conta desabilitada continua desabilitada (mesmo com a senha certa)" "$OUT" "RESULT login INVALID_CREDENTIALS"
OUT=$(printf 'login qa_user %s\nchangepw qa_user %s qa-new-password-in-window-1\nquit\n' "$PW" "$PW" | cli)
expect "janela de segurança: troca de senha recusada (FROZEN)" "$OUT" "RESULT changepw FROZEN"
AL="$AUTH/audit.log"
[[ -f "$AL" && "$(stat -f %Lp "$AL")" == 600 ]] && ok "auditoria do serviço persistida (0600, encadeada)" || bad "auditoria" ""
ALT=$(cat "$AL"); expect "eventos de login no serviço" "$ALT" "|LOGIN_OK|"; expect "evento de troca recusada na janela" "$ALT" "|PASSWORD_CHANGE_FROZEN|"
L=$(leaks "$ALT"); [[ -z "$L" ]] && ok "a auditoria do serviço não contém senha, e-mail, hash nem token" || bad "vazamento na auditoria do serviço" "$L"
refute "nem nomes digitados" "$ALT" "qa_admin"
stop_svc

echo "== 5. finalizar o cutover só depois da validação explícita"
OUT=$(echo nope | M finalize); expect "sem a frase não finaliza" "$OUT" "RESULT STOP confirmation_required"
OUT=$(echo FINALIZE-CUTOVER | M finalize)
expect "cutover verificado" "$OUT" "status=CUTOVER_VERIFIED"; expect "legado vira ROLLBACK_ONLY" "$OUT" "legacy=ROLLBACK_ONLY"; expect "trava removida" "$OUT" "freeze=false"
OUT=$(M legacy-status); expect "o legado AINDA existe (a exclusão é uma etapa separada)" "$(echo "$OUT" | grep -c PRESENT)" "3"
[[ "$(dbsum)" == "$H1" ]] && ok "banco legado continua intacto" || bad "banco legado mudou" ""
OUT=$(echo PREPARE-REAL-MIGRATION | M prepare); expect "prepare depois do cutover é recusado" "$OUT" "RESULT STOP already_cutover"
start_svc
OUT=$(printf 'login qa_user %s\nchangepw qa_user %s qa-new-password-after-window-1\nquit\n' "$PW" "$PW" | cli)
expect "fora da janela a troca de senha funciona" "$OUT" "RESULT changepw OK"
stop_svc

echo "== 6. limpeza"
OUT=$(M cleanup); expect "itens de teste removidos" "$OUT" "RESULT OK cleanup"
OUT=$(M legacy-status); expect "nenhum item legado sintético restou" "$(echo "$OUT" | grep -c ABSENT)" "3"

echo; echo "RESUMO: $PASS PASS, $FAIL FAIL"; [[ $FAIL -eq 0 ]]
