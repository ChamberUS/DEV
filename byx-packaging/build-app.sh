#!/bin/zsh
# Constrói BYX-MVP.app (painel + serviço local como helper) com runtime Java EMBUTIDO, bibliotecas nativas pré-extraídas e assinadas,
# Hardened Runtime e assinatura de desenvolvimento local (Apple Development). Não requer root, não instala nada, não abre porta.
#   uso: ./build-app.sh [--entitlements-dir DIR] [--out DIR]
# Pré-requisitos: painel e serviço já empacotados (mvn package) — este script NÃO roda a suíte de testes.
set -euo pipefail
HERE="${0:A:h}"; ROOT="${HERE:h}"
source "$HERE/identity.env"
export JAVA_HOME="${JAVA_HOME:-$HOME/dev/tools/jdk-21.0.12.1+1/Contents/Home}"
export PATH="$JAVA_HOME/bin:$HOME/dev/tools/apache-maven-3.9.9/bin:$PATH"
OUT="$HERE/build"; ENT="$HERE/entitlements"
CANARY=0; PROFILE=""
while [[ $# -gt 0 ]]; do case "$1" in
  --out) OUT="$2"; shift 2;;
  --entitlements-dir) ENT="$2"; shift 2;;
  # variante SÓ DE TESTE (nunca o bundle do produto): acrescenta o harness e o leitor do canário do cofre, em outro diretório
  --with-canary-harness) CANARY=1; OUT="$HERE/build-canary"; shift;;
  # perfil de provisionamento (futuro): só com os IDs FINAIS (BYX_IDS_FINAL=true). Caminho NÃO TESTADO: ainda não existe perfil.
  --embedded-profile) PROFILE="$2"; shift 2;;
  *) echo "arg inválido: $1" >&2; exit 2;; esac; done
if [[ -n "$PROFILE" && "$BYX_IDS_FINAL" != "true" ]]; then
  echo "BLOCKED: FINAL BUNDLE ID REQUIRED — nenhum perfil/grupo de acesso é configurado sob o ID provisório (rode set-final-ids.sh)"; exit 6
fi
APP="$OUT/$BYX_APP_NAME.app"
IDENT="${BYX_SIGN_IDENTITY:-$(security find-identity -v -p codesigning | awk '/Apple Development/ {print $2; exit}')}"
[[ -n "$IDENT" ]] || { echo "nenhuma identidade Apple Development encontrada (BYX_SIGN_IDENTITY)"; exit 3; }
PANEL_JAR="$ROOT/mvp-binance-panel/target/mvp-binance-panel-0.1.0.jar"; SERVICE_JAR="$ROOT/byx-local-service/target/byx-local-service-0.1.0.jar"
[[ -f "$PANEL_JAR" && -f "$SERVICE_JAR" ]] || { echo "rode 'mvn package' no painel e no serviço antes"; exit 4; }
rm -rf "$OUT"; mkdir -p "$OUT/input"

echo "== 1. dependências de execução (sem escopo de teste)"
( cd "$ROOT/mvp-binance-panel" && mvn -o -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory="$OUT/input" )
cp "$PANEL_JAR" "$SERVICE_JAR" "$OUT/input/"

echo "== 2. app-image (jpackage, runtime embutido via jlink)"
printf 'main-jar=byx-local-service-0.1.0.jar\nmain-class=byx.service.ServiceMain\n' > "$OUT/service.properties"
CANARY_LAUNCHERS=()
if [[ $CANARY -eq 1 ]]; then
  printf 'main-jar=byx-local-service-0.1.0.jar\nmain-class=byx.service.secrets.SecretCanaryHarness\n' > "$OUT/canary.properties"
  printf 'main-jar=byx-local-service-0.1.0.jar\nmain-class=byx.service.secrets.SecretCanaryReader\n' > "$OUT/reader.properties"
  printf 'main-jar=byx-local-service-0.1.0.jar\nmain-class=byx.service.secrets.SecretCanaryHolder\n' > "$OUT/holder.properties"
  CANARY_LAUNCHERS=(--add-launcher byx-secret-canary="$OUT/canary.properties" --add-launcher byx-secret-reader="$OUT/reader.properties" --add-launcher byx-secret-holder="$OUT/holder.properties")
fi
jpackage --type app-image --name "$BYX_APP_NAME" --dest "$OUT" --input "$OUT/input" \
  --main-jar mvp-binance-panel-0.1.0.jar --main-class panel.app.Main --app-version "$BYX_BUNDLE_VERSION" --vendor "BYX-MVP" \
  --mac-package-identifier "$BYX_APP_ID" --mac-package-name "$BYX_APP_NAME" \
  --add-modules java.base,java.desktop,java.naming,java.net.http,java.sql,java.logging,java.xml,java.management,jdk.jfr,jdk.unsupported,jdk.crypto.ec \
  --add-launcher byx-local-service="$OUT/service.properties" ${CANARY_LAUNCHERS[@]+"${CANARY_LAUNCHERS[@]}"} >/dev/null
CONTENTS="$APP/Contents"; FW="$CONTENTS/Frameworks"; mkdir -p "$FW"

echo "== 3. nativos pré-extraídos (nada é extraído em tempo de execução: biblioteca não assinada seria recusada pelo library validation)"
M2="$HOME/.m2/repository"
# JavaFX procura primeiro ao lado dos jars (Contents/app): achando ali, nunca tenta extrair para ~/.openjfx (extração não assinada seria recusada)
unzip -qjo "$M2/org/openjfx/javafx-graphics/21.0.5/javafx-graphics-21.0.5-mac.jar" '*.dylib' -d "$CONTENTS/app"
unzip -qjo "$M2/net/java/dev/jna/jna/5.17.0/jna-5.17.0.jar" 'com/sun/jna/darwin-x86-64/libjnidispatch.jnilib' -d "$FW"
unzip -qjo "$M2/org/xerial/sqlite-jdbc/3.46.1.0/sqlite-jdbc-3.46.1.0.jar" 'org/sqlite/native/Mac/x86_64/libsqlitejdbc.dylib' -d "$FW"

echo "== 4. configuração dos lançadores (sem extração, sem java global; só o necessário)"
NATIVE_OPTS=(
  'java-options=-Djava.library.path=$APPDIR/../Frameworks'
  'java-options=-Djna.boot.library.path=$APPDIR/../Frameworks'
  'java-options=-Djna.nosys=true'
  'java-options=-Dorg.sqlite.lib.path=$APPDIR/../Frameworks'
  'java-options=-Dorg.sqlite.lib.name=libsqlitejdbc.dylib'
  'java-options=-Dfile.encoding=UTF-8'
  'java-options=-XX:+DisableAttachMechanism'
)
# painel: classpath completo; serviço: só o que ele usa (superfície menor) + exposição controlada do fd do socket para a identidade do peer
CFG_APP="$CONTENTS/app/$BYX_APP_NAME.cfg"; CFG_SVC="$CONTENTS/app/byx-local-service.cfg"
SVC_CFGS=("$CFG_SVC"); [[ $CANARY -eq 1 ]] && SVC_CFGS+=("$CONTENTS/app/byx-secret-canary.cfg" "$CONTENTS/app/byx-secret-reader.cfg" "$CONTENTS/app/byx-secret-holder.cfg")
for CFG in "${SVC_CFGS[@]}"; do
python3 - "$CFG" <<'PY'
import sys,re
p=sys.argv[1]; s=open(p).read()
keep=("byx-local-service-0.1.0.jar","jackson-core-2.21.6.jar","jackson-databind-2.20.0.jar","jackson-annotations-2.21.jar","jna-5.17.0.jar")
out=[]
for l in s.splitlines():
    if l.startswith("app.classpath=") and not any(l.endswith("/"+k) for k in keep): continue
    out.append(l)
open(p,"w").write("\n".join(out)+"\n")
PY
done
for cfg in "$CFG_APP" "${SVC_CFGS[@]}"; do
  python3 - "$cfg" "${NATIVE_OPTS[@]}" <<'PY'
import sys
p=sys.argv[1]; opts=sys.argv[2:]; s=open(p).read().splitlines()
i=[k for k,l in enumerate(s) if l.strip()=="[JavaOptions]"][0]
s[i+1:i+1]=opts
open(p,"w").write("\n".join(s)+"\n")
PY
done
# leitura do fd do socket (identidade do peer): só estes dois pacotes, só nestes dois lançadores; sem eles o peer fica NÃO verificado
for cfg in "$CFG_APP" "${SVC_CFGS[@]}"; do
python3 - "$cfg" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read().splitlines()
i=[k for k,l in enumerate(s) if l.strip()=="[JavaOptions]"][0]
s[i+1:i+1]=['java-options=--add-opens=java.base/sun.nio.ch=ALL-UNNAMED','java-options=--add-opens=java.base/java.io=ALL-UNNAMED']
open(p,"w").write("\n".join(s)+"\n")
PY
done
plutil -remove NSMicrophoneUsageDescription "$CONTENTS/Info.plist" # permissão que o app não usa (padrão do jpackage)
plutil -replace LSMinimumSystemVersion -string 12.0 "$CONTENTS/Info.plist"

# Team ID = OU do certificado de assinatura (NÃO o identificador entre parênteses no nome do certificado)
CERT_NAME=$(security find-identity -v -p codesigning | awk -F'"' -v h="$IDENT" '$0 ~ h || /Apple Development/ {print $2; exit}')
TEAM=$(security find-certificate -c "$CERT_NAME" -p | openssl x509 -noout -subject -nameopt multiline | awk '/organizationalUnitName/ {print $3; exit}')
[[ "$TEAM" =~ '^[A-Z0-9]{10}$' ]] || { echo "Team ID não encontrado no certificado"; exit 5; }
# requisito designado EXPLÍCITO: sobrevive à renovação do certificado (o implícito fixaria o nome do certificado) e é o que um futuro ACL do Keychain deve usar
dr() { echo "=designated => identifier \"$1\" and anchor apple generic and certificate leaf[subject.OU] = \"$TEAM\""; }

echo "== 5. assinatura de dentro para fora (Hardened Runtime; sem timestamp: uso local de desenvolvimento)"
sign() { codesign --force --options runtime --timestamp=none -s "$IDENT" "$@"; }
# todo Mach-O que não seja os dois lançadores (dylibs, jspawnhelper, java, etc.), com identificador estável por arquivo
find "$CONTENTS" -type f \( ! -path "$CONTENTS/MacOS/*" \) -print0 | while IFS= read -r -d '' f; do
  if file -b "$f" | grep -q "Mach-O"; then sign --identifier "$BYX_APP_ID.lib.$(basename "$f")" "$f"; fi
done
SVC_ENT="$ENT/service.entitlements"
if [[ -n "$PROFILE" ]]; then # NÃO TESTADO (sem perfil): modelo de direitos do helper do serviço; o painel NÃO recebe grupo de keychain
  cp "$PROFILE" "$CONTENTS/embedded.provisionprofile"
  SVC_ENT="$OUT/service.keychain.entitlements"
  sed "s/__TEAM__/$TEAM/g; s/__APP_ID__/$BYX_APP_ID/g; s/__SERVICE_ID__/$BYX_SERVICE_ID/g" "$ENT/service.keychain.entitlements.template" > "$SVC_ENT"
fi
sign --identifier "$BYX_SERVICE_ID" -r "$(dr "$BYX_SERVICE_ID")" --entitlements "$SVC_ENT" "$CONTENTS/MacOS/byx-local-service"
if [[ $CANARY -eq 1 ]]; then
  # harness com a identidade e os direitos do SERVIÇO; leitor com a identidade e os direitos do PAINEL (sem grupo de keychain)
  sign --identifier "$BYX_SERVICE_ID" -r "$(dr "$BYX_SERVICE_ID")" --entitlements "$SVC_ENT" "$CONTENTS/MacOS/byx-secret-canary"
  sign --identifier "$BYX_SERVICE_ID" -r "$(dr "$BYX_SERVICE_ID")" --entitlements "$SVC_ENT" "$CONTENTS/MacOS/byx-secret-holder"
  sign --identifier "$BYX_APP_ID" -r "$(dr "$BYX_APP_ID")" --entitlements "$ENT/app.entitlements" "$CONTENTS/MacOS/byx-secret-reader"
fi
sign --identifier "$BYX_APP_ID" -r "$(dr "$BYX_APP_ID")" --entitlements "$ENT/app.entitlements" "$APP"

echo "== 6. verificação"
codesign --verify --deep --strict --verbose=2 "$APP"
echo "OK: $APP"
