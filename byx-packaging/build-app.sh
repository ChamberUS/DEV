#!/bin/zsh
# Constrói BYX-MVP.app: painel (com.buynnex.byx) + serviço local como HELPER APP ANINHADO (com.buynnex.byx.service, perfil e identidade
# próprios), runtime Java embutido, Hardened Runtime e assinatura de desenvolvimento local (Apple Development). Sem root, sem daemon, sem porta.
#   uso: ./build-app.sh [--out DIR] [--with-canary-harness] [--embedded-profile FILE] [--chain-profile production-disabled|local-qa]
# --chain-profile escolhe o perfil da chain pública do serviço NO BUILD (padrão: production-disabled = NOT_CONFIGURED, sem rede). Perfil desconhecido: FALHA. Não há troca em runtime.
# Topologia (medida): um helper com application-identifier PRÓPRIO dentro do bundle do painel é encerrado pelo macOS (o perfil do bundle
# principal não o autoriza); por isso o serviço é um app-like aninhado em Contents/Helpers/ com o seu perfil. O runtime é UM só em
# disco (clone APFS). Pré-requisito: painel e serviço já empacotados (mvn package); este script NÃO roda a suíte.
set -euo pipefail
HERE="${0:A:h}"; ROOT="${HERE:h}"
source "$HERE/identity.env"
export JAVA_HOME="${JAVA_HOME:-$HOME/dev/tools/jdk-21.0.12.1+1/Contents/Home}"
export PATH="$JAVA_HOME/bin:$HOME/dev/tools/apache-maven-3.9.9/bin:$PATH"
OUT="$HERE/build"; ENT="$HERE/entitlements"; CANARY=0; PROFILE=""; CHAIN="production-disabled"
while [[ $# -gt 0 ]]; do case "$1" in
  --out) OUT="$2"; shift 2;;
  --entitlements-dir) ENT="$2"; shift 2;;
  --with-canary-harness) CANARY=1; OUT="$HERE/build-canary"; shift;; # variante SÓ DE TESTE, em outro diretório
  --embedded-profile) PROFILE="${2:A}"; shift 2;;                     # perfil de DESENVOLVIMENTO do helper (provisioning/provision.sh)
  --chain-profile) CHAIN="${2:-}"; shift 2;;
  *) echo "arg inválido: $1" >&2; exit 2;; esac; done
if [[ -n "$PROFILE" && "$BYX_IDS_FINAL" != "true" ]]; then echo "BLOCKED: FINAL BUNDLE ID REQUIRED"; exit 6; fi
case "$CHAIN" in
  production-disabled|"") CHAIN_ENUM=PRODUCTION_DISABLED;;   # vazio => padrão seguro
  local-qa) CHAIN_ENUM=LOCAL_QA;;
  *) echo "BLOCKED: perfil de chain desconhecido: '$CHAIN' (production-disabled|local-qa)" >&2; exit 2;; esac
APP="$OUT/$BYX_APP_NAME.app"; CONTENTS="$APP/Contents"; HELPER="$CONTENTS/Helpers/byx-local-service.app"; HC="$HELPER/Contents"
IDENT="${BYX_SIGN_IDENTITY:-$(security find-identity -v -p codesigning | awk '/Apple Development/ {print $2; exit}')}"
[[ -n "$IDENT" ]] || { echo "nenhuma identidade Apple Development encontrada (BYX_SIGN_IDENTITY)"; exit 3; }
PANEL_JAR="$ROOT/mvp-binance-panel/target/mvp-binance-panel-0.1.0.jar"; SERVICE_JAR="$ROOT/byx-local-service/target/byx-local-service-0.1.0.jar"
echo "== 0. JAR do serviço com o perfil de chain do build: $CHAIN_ENUM (sem rodar a suíte)"
( cd "$ROOT/byx-local-service" && mvn -o -q package -DskipTests -Dbyx.chain.profile="$CHAIN_ENUM" )
[[ "$(unzip -p "$SERVICE_JAR" byx/chain-profile.txt)" == "$CHAIN_ENUM" ]] || { echo "JAR sem o perfil esperado"; exit 4; }
[[ -f "$PANEL_JAR" && -f "$SERVICE_JAR" ]] || { echo "rode 'mvn package' no painel e no serviço antes"; exit 4; }
CERT_NAME=$(security find-identity -v -p codesigning | awk -F'"' '/Apple Development/ {print $2; exit}')
TEAM=$(security find-certificate -c "$CERT_NAME" -p | openssl x509 -noout -subject -nameopt multiline | awk '/organizationalUnitName/ {print $3; exit}')
[[ "$TEAM" =~ '^[A-Z0-9]{10}$' ]] || { echo "Team ID não encontrado no certificado"; exit 5; }
dr() { echo "=designated => identifier \"$1\" and anchor apple generic and certificate leaf[subject.OU] = \"$TEAM\""; }
rm -rf "$OUT"; mkdir -p "$OUT/input" "$OUT/svc-input"

echo "== 1. dependências de execução (sem escopo de teste)"
( cd "$ROOT/mvp-binance-panel" && mvn -o -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory="$OUT/input" )
cp "$PANEL_JAR" "$OUT/input/"
# dependências do SERVIÇO: as do próprio projeto (fonte única; não depende do que o painel traz)
( cd "$ROOT/byx-local-service" && mvn -o -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory="$OUT/svc-input" )
cp "$SERVICE_JAR" "$OUT/svc-input/"
MODS=java.base,java.desktop,java.naming,java.net.http,java.sql,java.logging,java.xml,java.management,jdk.jfr,jdk.unsupported,jdk.crypto.ec

echo "== 2. app do painel (jpackage, runtime embutido via jlink)"
EXTRA_APP=()
if [[ $CANARY -eq 1 ]]; then # leitor de TESTE com a identidade e os direitos do PAINEL
  printf 'main-jar=byx-local-service-0.1.0.jar\nmain-class=byx.service.secrets.SecretCanaryReader\n' > "$OUT/reader.properties"
  printf 'main-jar=mvp-binance-panel-0.1.0.jar\nmain-class=panel.localservice.AuthorityQaCli\n' > "$OUT/authclient.properties"
  cp "$SERVICE_JAR" "$OUT/input/"; EXTRA_APP=(--add-launcher byx-secret-reader="$OUT/reader.properties" --add-launcher byx-auth-client="$OUT/authclient.properties")
fi
jpackage --type app-image --name "$BYX_APP_NAME" --dest "$OUT" --input "$OUT/input" --main-jar mvp-binance-panel-0.1.0.jar --main-class panel.app.Main \
  --app-version "$BYX_BUNDLE_VERSION" --vendor "BYX-MVP" --mac-package-identifier "$BYX_APP_ID" --mac-package-name "$BYX_APP_NAME" --add-modules "$MODS" \
  ${EXTRA_APP[@]+"${EXTRA_APP[@]}"} >/dev/null

echo "== 3. helpers aninhados (app-likes com identidade e perfil próprios; runtime = clone APFS do mesmo runtime)"
# Medido: direitos restritos (application-identifier) só valem no executável PRINCIPAL de um bundle cujo perfil os autoriza; um lançador extra é
# encerrado pelo taskgated. Por isso cada ferramenta que precisa da identidade do serviço é o executável principal do seu próprio app-like.
mkdir -p "$CONTENTS/Helpers" "$CONTENTS/Frameworks"
M2="$HOME/.m2/repository"
NATIVE_OPTS=('java-options=-Djava.library.path=$APPDIR/../Frameworks' 'java-options=-Djna.boot.library.path=$APPDIR/../Frameworks' 'java-options=-Djna.nosys=true'
  'java-options=-Dorg.sqlite.lib.path=$APPDIR/../Frameworks' 'java-options=-Dorg.sqlite.lib.name=libsqlitejdbc.dylib' 'java-options=-Dfile.encoding=UTF-8'
  'java-options=-XX:+DisableAttachMechanism'
  # leitura do fd do socket (identidade do peer): só estes dois pacotes, só nos lançadores empacotados; sem eles o peer fica NÃO verificado
  'java-options=--add-opens=java.base/sun.nio.ch=ALL-UNNAMED' 'java-options=--add-opens=java.base/java.io=ALL-UNNAMED')
add_opts() { python3 - "$1" "${NATIVE_OPTS[@]}" <<'PY'
import sys
p=sys.argv[1]; opts=sys.argv[2:]; s=open(p).read().splitlines()
i=[k for k,l in enumerate(s) if l.strip()=="[JavaOptions]"][0]
s[i+1:i+1]=opts
open(p,"w").write("\n".join(s)+"\n")
PY
}
# byx-migrate: migrador REAL (identidade do serviço; acessa o cofre do serviço e LÊ o keychain legado): subcomandos como argumento da aplicação, confirmação por stdin
HELPERS=("byx-local-service:byx.service.ServiceMain" "byx-migrate:byx.service.migration.MigrateMain")
[[ $CANARY -eq 1 ]] && HELPERS+=("byx-auth-qa:byx.service.auth.AuthQaMain" "byx-migrate-qa:byx.service.migration.MigrateQaMain")
[[ $CANARY -eq 1 ]] && HELPERS+=("byx-secret-canary:byx.service.secrets.SecretCanaryHarness" "byx-secret-holder:byx.service.secrets.SecretCanaryHolder" "byx-secret-servicereader:byx.service.secrets.SecretCanaryReader")
mk_helper() { # $1=nome (= executável)  $2=classe principal
  local name="$1" mainclass="$2" tmp="$OUT/helper-$1"; local dest="$CONTENTS/Helpers/$1.app"
  mkdir -p "$tmp"
  jpackage --type app-image --name "$name" --dest "$tmp" --input "$OUT/svc-input" --main-jar byx-local-service-0.1.0.jar --main-class "$mainclass" \
    --app-version "$BYX_BUNDLE_VERSION" --vendor "BYX-MVP" --mac-package-identifier "$BYX_SERVICE_ID" --mac-package-name "$name" \
    --runtime-image "$CONTENTS/runtime/Contents/Home" >/dev/null
  mv "$tmp/$name.app" "$dest"; rmdir "$tmp"
  rm -rf "${dest:?}/Contents/runtime"; cp -cR "$CONTENTS/runtime" "$dest/Contents/runtime" # clone APFS: um runtime em disco
  mkdir -p "$dest/Contents/Frameworks"
  unzip -qjo "$M2/net/java/dev/jna/jna/5.17.0/jna-5.17.0.jar" 'com/sun/jna/darwin-x86-64/libjnidispatch.jnilib' -d "$dest/Contents/Frameworks"
  [[ "$name" == byx-migrate* ]] && unzip -qjo "$M2/org/xerial/sqlite-jdbc/3.46.1.0/sqlite-jdbc-3.46.1.0.jar" 'org/sqlite/native/Mac/x86_64/libsqlitejdbc.dylib' -d "$dest/Contents/Frameworks"
  add_opts "$dest/Contents/app/$name.cfg"
  plutil -remove NSMicrophoneUsageDescription "$dest/Contents/Info.plist"; plutil -replace LSMinimumSystemVersion -string 12.0 "$dest/Contents/Info.plist"
  plutil -insert LSUIElement -bool true "$dest/Contents/Info.plist" # sem ícone no Dock
}
for h in "${HELPERS[@]}"; do mk_helper "${h%%:*}" "${h##*:}"; done

echo "== 3b. lançador nativo ENDURECIDO (um só código, parametrizado em build-time) no lugar do lançador genérico: serviço, helper de QA e PAINEL"
# Medido: com o lançador genérico, JAVA_TOOL_OPTIONS=-Xbootclasspath/a:evil.jar executa código alheio DENTRO do processo (identidade e keychain). O lançador novo limpa o
# ambiente, usa argumentos/classpath/classe CONSTANTES (o .cfg mutável é ignorado) e valida o selo do próprio bundle antes de iniciar a JVM.
jars_of() { # $1=arquivo .cfg do jpackage -> lista de literais C com os jars do classpath, NA ORDEM do .cfg (fonte única)
  python3 - "$1" <<'PY'
import sys
print(",".join('"%s"' % l.split("/",1)[1].strip() for l in open(sys.argv[1]) if l.startswith("app.classpath=")))
PY
}
harden() { # $1=executável a substituir  $2=.cfg de origem  $3=classe principal  $4...=opções -D extras de build (PASS_ARGS, SQLITE_NATIVE)
  local exe="$1" cfg="$2" mc="$3"; shift 3
  clang -arch x86_64 -O2 -Wall -Werror -mmacosx-version-min=12.0 -DMAIN_CLASS="\"$mc\"" -DJAR_LIST="$(jars_of "$cfg")" "$@" -framework Security -framework CoreFoundation -o "$exe" "$HERE/launcher/byx-launcher.c"
}
for h in "${HELPERS[@]}"; do
  n="${h%%:*}"
  case "$n" in
    byx-local-service|byx-auth-qa) harden "$CONTENTS/Helpers/$n.app/Contents/MacOS/$n" "$CONTENTS/Helpers/$n.app/Contents/app/$n.cfg" "${h##*:}";;
    byx-migrate|byx-migrate-qa) harden "$CONTENTS/Helpers/$n.app/Contents/MacOS/$n" "$CONTENTS/Helpers/$n.app/Contents/app/$n.cfg" "${h##*:}" -DPASS_ARGS -DSQLITE_NATIVE;;
  esac
done
harden "$CONTENTS/MacOS/$BYX_APP_NAME" "$CONTENTS/app/$BYX_APP_NAME.cfg" panel.app.Main -DPASS_ARGS -DSQLITE_NATIVE   # o painel: argv só depois da classe principal
[[ $CANARY -eq 1 ]] && harden "$CONTENTS/MacOS/byx-auth-client" "$CONTENTS/app/byx-auth-client.cfg" panel.localservice.AuthorityQaCli -DPASS_ARGS
echo "== 4. nativos do painel pré-extraídos (nada é extraído em tempo de execução: biblioteca não assinada seria recusada pelo library validation)"
unzip -qjo "$M2/org/openjfx/javafx-graphics/21.0.5/javafx-graphics-21.0.5-mac.jar" '*.dylib' -d "$CONTENTS/app"
unzip -qjo "$M2/net/java/dev/jna/jna/5.17.0/jna-5.17.0.jar" 'com/sun/jna/darwin-x86-64/libjnidispatch.jnilib' -d "$CONTENTS/Frameworks"
unzip -qjo "$M2/org/xerial/sqlite-jdbc/3.46.1.0/sqlite-jdbc-3.46.1.0.jar" 'org/sqlite/native/Mac/x86_64/libsqlitejdbc.dylib' -d "$CONTENTS/Frameworks"
for cfg in "$CONTENTS/app/"*.cfg; do add_opts "$cfg"; done
plutil -remove NSMicrophoneUsageDescription "$CONTENTS/Info.plist"; plutil -replace LSMinimumSystemVersion -string 12.0 "$CONTENTS/Info.plist"

echo "== 5. perfil e direitos do helper (valores LIDOS do perfil; menor privilégio)"
SVC_ENT="$ENT/service.entitlements"
if [[ -n "$PROFILE" ]]; then
  security cms -D -i "$PROFILE" > "$OUT/profile.plist" 2>/dev/null || { echo "perfil ilegível"; exit 7; }
  read -r P_APPID P_TEAM P_PREFIX < <(python3 -c "
import plistlib,sys
d=plistlib.load(open(sys.argv[1],'rb'))
print(d['Entitlements']['com.apple.application-identifier'], d['TeamIdentifier'][0], d['ApplicationIdentifierPrefix'][0])" "$OUT/profile.plist")
  [[ "$P_APPID" == "$P_PREFIX.$BYX_SERVICE_ID" ]] || { echo "o perfil autoriza '$P_APPID', esperado '$P_PREFIX.$BYX_SERVICE_ID'"; exit 7; }
  [[ "$P_TEAM" == "$TEAM" ]] || { echo "Team do perfil ($P_TEAM) difere do certificado ($TEAM)"; exit 7; }
  SVC_ENT="$OUT/service.keychain.entitlements"
  sed "s/__TEAM__/$P_TEAM/g; s/__APPLICATION_IDENTIFIER__/$P_APPID/g" "$ENT/service.keychain.entitlements.template" > "$SVC_ENT"
  for h in "${HELPERS[@]}"; do cp "$PROFILE" "$CONTENTS/Helpers/${h%%:*}.app/Contents/embedded.provisionprofile"; done
fi

echo "== 6. assinatura de dentro para fora (Hardened Runtime; sem timestamp: uso local de desenvolvimento)"
sign() { codesign --force --options runtime --timestamp=none -s "$IDENT" "$@"; }
sign_libs() { # $1=raiz $2=prefixo do identificador; todo Mach-O exceto os executáveis de Contents/MacOS e os helpers aninhados
  find "$1" -type f -print0 | while IFS= read -r -d '' f; do
    [[ "$f" == "$1/Contents/MacOS/"* || "$f" == "$1/Contents/Helpers/"* ]] && continue
    file -b "$f" | grep -q "Mach-O" && sign --identifier "$2.lib.$(basename "$f")" "$f"
  done; }
for h in "${HELPERS[@]}"; do
  H="$CONTENTS/Helpers/${h%%:*}.app"
  sign_libs "$H" "$BYX_SERVICE_ID"
  sign --identifier "$BYX_SERVICE_ID" -r "$(dr "$BYX_SERVICE_ID")" --entitlements "$SVC_ENT" "$H"
done
sign_libs "$APP" "$BYX_APP_ID"
[[ $CANARY -eq 1 ]] && for x in byx-secret-reader byx-auth-client; do sign --identifier "$BYX_APP_ID" -r "$(dr "$BYX_APP_ID")" --entitlements "$ENT/app.entitlements" "$CONTENTS/MacOS/$x"; done
sign --identifier "$BYX_APP_ID" -r "$(dr "$BYX_APP_ID")" --entitlements "$ENT/app.entitlements" "$APP"

echo "== 7. verificação"
codesign --verify --deep --strict "$APP" && echo "OK: $APP"
