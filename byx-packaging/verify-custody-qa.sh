#!/bin/zsh
# SYNTHETIC custody QA matrix (V2.1T-1) against build-custody-qa/. No network, no BYX node, no Binance, no broadcast, no real key. Cleans up the exact synthetic
# namespace in every outcome. Prints PASS/FAIL per case.   uso: ./verify-custody-qa.sh
set -u
HERE="${0:A:h}"; O="$HERE/build-custody-qa"; APP="$O/BYX-MVP.app"; C="$APP/Contents"
H="$C/Helpers/byx-signer-helper-qa.app/Contents/MacOS/byx-signer-helper-qa"; SV="$C/Helpers/byx-custody-qa-service.app/Contents/MacOS/byx-custody-qa-service"
PN="$C/MacOS/byx-custody-qa-panel"; T="$O/Tools"; PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "PASS  $1"; }
bad() { FAIL=$((FAIL+1)); echo "FAIL  $1  [$2]"; }
has() { [[ "$2" == *"$3"* ]] && ok "$1" || bad "$1" "esperado '$3' em: ${2:0:400}"; }
not() { [[ "$2" != *"$3"* ]] && ok "$1" || bad "$1" "NÃO deveria conter '$3'"; }
run_as() { env -i HOME="$HOME" PATH=/usr/bin:/bin "$@" 2>&1; }
PROBE_DIR=$(mktemp -d /private/tmp/byx-custody-security-qa.XXXXXX) || exit 2
chmod 700 "$PROBE_DIR"
cleanup() { run_as "$SV" cleanup >/dev/null 2>&1; rm -f "$PROBE_DIR/ready.bin"; rmdir "$PROBE_DIR"; }
trap cleanup EXIT
[[ -x "$H" && -x "$SV" && -x "$PN" ]] || { echo "rode ./build-custody-qa.sh antes"; exit 2; }

echo "== 1. aceitação do perfil / assinatura / matriz de entitlements"
P=$(security cms -D -i "$C/Helpers/byx-signer-helper-qa.app/Contents/embedded.provisionprofile" 2>/dev/null | python3 -c "import sys,plistlib,datetime;d=plistlib.loads(sys.stdin.buffer.read());e=d['Entitlements'];print(e['com.apple.application-identifier'],d['TeamIdentifier'][0],d['UUID'],d['ExpirationDate'].date(),d['ExpirationDate']>datetime.datetime.utcnow())")
echo "  profile: $P"
has "perfil do signer QA: application-identifier = W5Z65G9UP2.com.buynnex.byx.signer.qa, Team W5Z65G9UP2, não vencido" "$P" "W5Z65G9UP2.com.buynnex.byx.signer.qa W5Z65G9UP2"
has "perfil não vencido" "$P" "True"
ent() { codesign -d --entitlements - "$1" 2>&1; }
SE=$(ent "$C/Helpers/byx-signer-helper-qa.app"); has "SIGNER QA: grupo exclusivo PRESENTE" "$SE" "W5Z65G9UP2.com.buynnex.byx.signer.qa.keys"
has "SIGNER QA: application-identifier próprio" "$SE" "W5Z65G9UP2.com.buynnex.byx.signer.qa"
not "SIGNER QA: sem grupo curinga" "$SE" "W5Z65G9UP2.*"
for who in "$APP" "$C/Helpers/byx-local-service.app" "$C/Helpers/byx-custody-qa-service.app" "$C/MacOS/byx-custody-qa-panel"; do
  E=$(ent "$who"); not "$(basename $who): grupo do signer QA ausente" "$E" "signer.qa.keys"; not "$(basename $who): sem keychain-access-groups explícito" "$E" "keychain-access-groups"
done
for who in "$C/Helpers/byx-signer-helper-qa.app" "$C/Helpers/byx-custody-qa-service.app" "$APP"; do codesign --verify --strict "$who" 2>/dev/null && ok "assinatura válida: $(basename $who)" || bad "assinatura: $who" ""; done
S1=$(codesign -dvv "$C/Helpers/byx-signer-helper-qa.app" 2>&1); has "signer QA: identificador próprio e Team" "$S1" "Identifier=com.buynnex.byx.signer.qa"; has "signer QA: hardened runtime" "$S1" "runtime"
SP=$(security cms -D -i "$C/Helpers/byx-signer-helper-qa.app/Contents/embedded.provisionprofile" 2>/dev/null | python3 -c "import sys,plistlib;print(plistlib.loads(sys.stdin.buffer.read())['UUID'])"); SVP=$(security cms -D -i "$C/Helpers/byx-local-service.app/Contents/embedded.provisionprofile" 2>/dev/null | python3 -c "import sys,plistlib;print(plistlib.loads(sys.stdin.buffer.read())['UUID'])")
[[ "$SP" != "$SVP" ]] && ok "perfil do signer QA é distinto do perfil do serviço ($SP vs $SVP)" || bad "perfis iguais" ""

echo "== 2. caminho feliz: SERVICE -> SIGNER (identidade real do serviço), custódia sintética completa"
OUT=$(run_as "$SV" scenario)
for k in "firstCall=COUNT" "refuseOtherNamespace=NAMESPACE_REFUSED" "cleanSlate=DELETED(0) remaining=0" "orphanA.metadataWithoutKeychain=KEY_NOT_FOUND" "keychainCreate=PROVISIONED" "addressMatchesIndependentDerivation=true" \
  "noOverwrite=ALREADY_EXISTS" "orphanB.keychainWithoutMetadata=ORPHAN_DETECTED_NOT_ADOPTED" "orphanB.itemsAfter=1" "broadcasts=0" "syntheticSign=SIGNED" "independentVerify=PASS" "wrongPublicKey=REJECTED" \
  "unknownRef=KEY_NOT_FOUND" "broadcast=NONE" "delete=DELETED" "lookupAfterDelete=KEY_NOT_FOUND" "namespaceItemsRemaining=0"; do has "scenario: $k" "$OUT" "$k"; done
has "o service role roda com o Team esperado" "$OUT" "qa.role.selfTeam=W5Z65G9UP2"

echo "== 3. chamadores NÃO confiáveis (devem ser recusados ANTES de qualquer acesso ao Keychain)"
R=$("$T/client-unsigned" spawn "$H" count ""); has "TERMINAL/processo não assinado -> signer: CALLER_UNTRUSTED" "$R" "CALLER_UNTRUSTED"; has "  e keychainCalls = 0" "$R" "\"keychainCalls\":0"
R=$("$T/client-wrongrole" spawn "$H" count ""); has "MESMO Team, outro role (com.buynnex.byx.wrongrole.qa) -> signer: CALLER_UNTRUSTED" "$R" "CALLER_UNTRUSTED"; has "  e keychainCalls = 0" "$R" "\"keychainCalls\":0"
# item real presente enquanto o chamador errado tenta (prova que a recusa não é "item inexistente")
REFA="plant$(python3 -c 'import secrets;print(secrets.token_hex(8))')"; run_as "$SV" plant "$REFA" | grep -q "plant=PROVISIONED" && ok "item sintético plantado pelo chamador legítimo ($REFA)" || bad "plant" ""
R=$("$T/client-unsigned" spawn "$H" lookup "$REFA"); has "KeyRef válida + processo errado: CALLER_UNTRUSTED (não FOUND)" "$R" "CALLER_UNTRUSTED"; not "  nenhuma chave derivada para o chamador errado" "$R" "publicKey"
# helper executado DIRETAMENTE pelo Terminal, outro processo se conecta
INV=$(python3 -c "import secrets;print(secrets.token_hex(16))"); touch "$PROBE_DIR/ready.bin"; chmod 600 "$PROBE_DIR/ready.bin"
( printf "%s\n" "$INV" | env -i "$H" > "$PROBE_DIR/ready.bin" 2>/dev/null ) & sleep 1.5
SOCK=$(python3 -c "import sys,struct,json;b=open(sys.argv[1],'rb').read();n=struct.unpack('>I',b[:4])[0];print(json.loads(b[4:4+n])['path'])" "$PROBE_DIR/ready.bin" 2>/dev/null)
HPID=$(python3 -c "import sys,struct,json;b=open(sys.argv[1],'rb').read();n=struct.unpack('>I',b[:4])[0];print(json.loads(b[4:4+n])['pid'])" "$PROBE_DIR/ready.bin" 2>/dev/null)
NET=$(lsof -a -nP -p "$HPID" -i 2>/dev/null | grep -vc COMMAND); [[ "$NET" == "0" ]] && ok "signer em execução: ZERO sockets inet (lsof -i)" || bad "sockets inet no signer" "$NET"
FDS=$(lsof -a -nP -p "$HPID" 2>/dev/null | awk 'NR>1 && $4 ~ /^[0-9]+[urw]?$/ {print $4}' | tr -d 'urw' | sort -un | tr '\n' ' '); echo "  fds do signer: $FDS"
R=$("$T/client-unsigned" attach "$SOCK" "$INV" lookup "$REFA"); has "helper iniciado DIRETAMENTE pelo Terminal + chamador errado: CALLER_UNTRUSTED" "$R" "CALLER_UNTRUSTED"; has "  keychainCalls = 0" "$R" "\"keychainCalls\":0"
sleep 1
R=$(run_as JAVA_TOOL_OPTIONS=-Dinjected=1 "$SV" scenario); RC=$?
[[ "$RC" == 74 && "$R" == *"launcher: environment injection rejected"* && "$R" != *"qa.role"* ]] && ok "service role com JAVA_TOOL_OPTIONS: launcher recusa antes da JVM/Keychain" || bad "environment injection must fail before JVM" "$RC:${R:0:200}"
R=$(run_as "$PN" scenario "$C/Helpers/byx-signer-helper-qa.app"); has "PANEL role -> signer: CALLER_UNTRUSTED" "$R" "CALLER_UNTRUSTED"; has "  keychainCalls = 0" "$R" "keychainCalls=0"

echo "== 4. signer errado -> SERVICE (o service recusa ANTES de executar)"
V=$(run_as "$SV" variants "$O/Variants")
has "helper NÃO ASSINADO recusado" "$V" "variant.unsigned=SIGNER_UNTRUSTED:HELPER_BUNDLE_NO_CODE"
has "helper MODIFICADO (selo quebrado) recusado" "$V" "variant.modified=SIGNER_UNTRUSTED:HELPER_BUNDLE_SEAL_BROKEN"
has "helper com IDENTIFICADOR errado (assinado, mesmo Team) recusado" "$V" "variant.wrongid=SIGNER_UNTRUSTED:HELPER_BUNDLE_REQUIREMENT_FAILED"
has "helper COPIADO (genuíno, fora da origem) recusado" "$V" "variant.copied=SIGNER_UNTRUSTED:HELPER_ORIGIN"
not "nenhuma variante aceita" "$V" "ACCEPTED"

echo "== 5. acesso EMPÍRICO ao segredo sintético (item plantado: $REFA)"
R=$(run_as "$PN" access "$REFA" | grep secretAccess); has "PANEL -> segredo: NEGADO" "$R" "gotData=false"; echo "  panel: $R"
R=$(run_as "$SV" access "$REFA" | grep secretAccess); has "SERVICE -> segredo (leitura direta, fora do signer): NEGADO" "$R" "gotData=false"; echo "  service: $R"
R=$("$T/client-unsigned" foreign-read "$REFA"); has "PROCESSO ESTRANHO (não assinado) -> segredo: NEGADO" "$R" "\"gotData\":false"; echo "  foreign: $R"
R=$("$T/client-wrongrole" foreign-read "$REFA"); has "MESMO Team, outro role -> segredo: NEGADO" "$R" "\"gotData\":false"; echo "  wrongrole: $R"
R=$(run_as "$SV" lookup "$REFA" | grep "qa.lookup"); has "SIGNER -> segredo (controle positivo): PERMITIDO" "$R" "FOUND"

echo "== 6. limpeza determinística (interrompida e normal)"
R=$(run_as "$SV" cleanup); has "cleanup remove exatamente o namespace sintético" "$R" "qa.cleanup=DELETED(1)"; has "itens restantes = 0" "$R" "qa.namespaceItemsRemaining=0"
R=$(run_as "$SV" lookup "$REFA" | grep "qa.lookup"); has "lookup do item removido -> KEY_NOT_FOUND" "$R" "KEY_NOT_FOUND"
R=$(run_as "$SV" scenario | grep -E "namespaceItemsRemaining"); has "itens do namespace após uma rodada completa = 0" "$R" "=0"

echo; echo "RESUMO: $PASS PASS, $FAIL FAIL"; [[ $FAIL -eq 0 ]]
