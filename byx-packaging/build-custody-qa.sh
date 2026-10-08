#!/bin/zsh
# CUSTODY QA ARTIFACT (V2.1T-1) — separate from DEFAULT: contains the SYNTHETIC signer QA helper (com.buynnex.byx.signer.qa), the canary roles (service/panel
# identities) and the negative-test tools. NEVER the default bundle; the default build (./build-app.sh) does not contain any of this.
#   uso: ./build-custody-qa.sh        (gera build-custody-qa/; exige provisioning/out/signer-qa.provisionprofile e service.provisionprofile)
set -euo pipefail
HERE="${0:A:h}"; ROOT="${HERE:h}"
source "$HERE/identity.env"
export JAVA_HOME="${JAVA_HOME:-$HOME/dev/tools/jdk-21.0.12.1+1/Contents/Home}"
export PATH="/usr/local/opt/go@1.25/bin:$JAVA_HOME/bin:$HOME/dev/tools/apache-maven-3.9.9/bin:$PATH"
OUT="$HERE/build-custody-qa"
SIGNER_ID="${BYX_APP_ID}.signer.qa"; WRONG_ID="${BYX_APP_ID}.wrongrole.qa"
SIGNER_PROFILE="$HERE/provisioning/out/signer-qa.provisionprofile"; SERVICE_PROFILE="$HERE/provisioning/out/service.provisionprofile"
IDENT="${BYX_SIGN_IDENTITY:-$(security find-identity -v -p codesigning | awk '/Apple Development/ {print $2; exit}')}"
[[ -n "$IDENT" ]] || { echo "nenhuma identidade Apple Development"; exit 3; }
TEAM=$(security find-certificate -c "Apple Development" -p | openssl x509 -noout -subject -nameopt multiline | awk '/organizationalUnitName/ {print $3; exit}')
dr() { echo "=designated => identifier \"$1\" and anchor apple generic and certificate leaf[subject.OU] = \"$TEAM\""; }
sign() { codesign --force --options runtime --timestamp=none -s "$IDENT" "$@"; }

echo "== 0. perfil do signer QA (aceitação)"
security cms -D -i "$SIGNER_PROFILE" > /tmp/byx-signer-qa.profile.plist 2>/dev/null || { echo "BLOCKED: perfil ilegível"; exit 7; }
read -r P_APPID P_TEAM P_PREFIX P_EXP < <(python3 -c "import plistlib,datetime;d=plistlib.load(open('/tmp/byx-signer-qa.profile.plist','rb'));assert d['ExpirationDate']>datetime.datetime.utcnow();print(d['Entitlements']['com.apple.application-identifier'],d['TeamIdentifier'][0],d['ApplicationIdentifierPrefix'][0],d['ExpirationDate'].strftime('%Y-%m-%dT%H:%M:%S'))") || { echo "BLOCKED: perfil vencido/ilegível"; exit 7; }
[[ "$P_APPID" == "$P_PREFIX.$SIGNER_ID" && "$P_TEAM" == "$TEAM" ]] || { echo "BLOCKED: perfil não autoriza $P_PREFIX.$SIGNER_ID para o Team $TEAM (autoriza $P_APPID / $P_TEAM)"; exit 7; }
GROUP="$P_PREFIX.$SIGNER_ID.keys"
echo "perfil OK: $P_APPID · team $P_TEAM · grupo $GROUP · expira $P_EXP UTC"

echo "== 1. base canário (painel + serviço + papéis de teste), perfil de chain PRODUCTION_DISABLED"
rm -rf "$OUT"
"$HERE/build-app.sh" --with-canary-harness --out "$OUT" --embedded-profile "$SERVICE_PROFILE" >/dev/null
APP="$OUT/$BYX_APP_NAME.app"; C="$APP/Contents"
[[ "$(unzip -p "$ROOT/byx-local-service/target/byx-local-service-0.1.0.jar" byx/chain-profile.txt)" == "PRODUCTION_DISABLED" ]] || { echo "BLOCKED: chain profile"; exit 4; }

echo "== 2. jar de teste (runner sintético) e binários Go (-tags qa)"
( cd "$ROOT/byx-local-service" && mvn -o -q test-compile )
TJAR="$OUT/byx-local-service-custody-qa-tests.jar"
jar cf "$TJAR" -C "$ROOT/byx-local-service/target/test-classes" .
( cd "$ROOT/byx-local-service/signer-helper" && CGO_ENABLED=1 go build -tags qa -trimpath -o "$OUT/byx-signer-helper-qa.bin" ./cmd/byx-signer-helper-qa && CGO_ENABLED=1 go build -tags qa -trimpath -o "$OUT/byx-custody-qa-client.bin" ./cmd/byx-custody-qa-client )

echo "== 3. signer QA (app-like, identidade e perfil PRÓPRIOS; grupo exclusivo)"
S="$C/Helpers/byx-signer-helper-qa.app"; mkdir -p "$S/Contents/MacOS"
cp "$OUT/byx-signer-helper-qa.bin" "$S/Contents/MacOS/byx-signer-helper-qa"; cp "$SIGNER_PROFILE" "$S/Contents/embedded.provisionprofile"
cat > "$S/Contents/Info.plist" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>$SIGNER_ID</string><key>CFBundleExecutable</key><string>byx-signer-helper-qa</string><key>CFBundleName</key><string>byx-signer-helper-qa</string>
<key>CFBundlePackageType</key><string>APPL</string><key>CFBundleVersion</key><string>$BYX_BUNDLE_VERSION</string><key>CFBundleShortVersionString</key><string>$BYX_BUNDLE_VERSION</string>
<key>LSMinimumSystemVersion</key><string>12.0</string><key>LSUIElement</key><true/>
</dict></plist>
PL
cat > "$OUT/signer-qa.entitlements" <<ENT
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
	<key>com.apple.application-identifier</key><string>$P_APPID</string>
	<key>com.apple.developer.team-identifier</key><string>$P_TEAM</string>
	<key>keychain-access-groups</key><array><string>$GROUP</string></array>
</dict></plist>
ENT
sign --identifier "$SIGNER_ID" -r "$(dr "$SIGNER_ID")" --entitlements "$OUT/signer-qa.entitlements" "$S"

echo "== 4. papel SERVICE (clone do leitor de serviço da canary: identidade e perfil do serviço) e papel PANEL (clone do leitor do painel)"
SV="$C/Helpers/byx-custody-qa-service.app"; cp -cR "$C/Helpers/byx-secret-servicereader.app" "$SV"
mv "$SV/Contents/MacOS/byx-secret-servicereader" "$SV/Contents/MacOS/byx-custody-qa-service"; mv "$SV/Contents/app/byx-secret-servicereader.cfg" "$SV/Contents/app/byx-custody-qa-service.cfg"
plutil -replace CFBundleExecutable -string byx-custody-qa-service "$SV/Contents/Info.plist"; plutil -replace CFBundleName -string byx-custody-qa-service "$SV/Contents/Info.plist"
cp "$TJAR" "$SV/Contents/app/"
python3 - "$SV/Contents/app/byx-custody-qa-service.cfg" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read().replace("app.mainclass=byx.service.secrets.SecretCanaryReader","app.mainclass=byx.service.signer.CustodyQaMain")
s=s.replace("app.classpath=$APPDIR/byx-local-service-0.1.0.jar","app.classpath=$APPDIR/byx-local-service-0.1.0.jar\napp.classpath=$APPDIR/byx-local-service-custody-qa-tests.jar")
open(p,"w").write(s)
PY
sign --identifier "$BYX_SERVICE_ID" -r "$(dr "$BYX_SERVICE_ID")" --entitlements "$OUT/service.keychain.entitlements" "$SV"
cp "$C/MacOS/byx-secret-reader" "$C/MacOS/byx-custody-qa-panel"; cp "$C/app/byx-secret-reader.cfg" "$C/app/byx-custody-qa-panel.cfg"; cp "$TJAR" "$C/app/"
python3 - "$C/app/byx-custody-qa-panel.cfg" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read().replace("main-class=byx.service.secrets.SecretCanaryReader","main-class=byx.service.signer.CustodyQaMain").replace("app.mainclass=byx.service.secrets.SecretCanaryReader","app.mainclass=byx.service.signer.CustodyQaMain")
s=s.replace("app.classpath=$APPDIR/mvp-binance-panel-0.1.0.jar","app.classpath=$APPDIR/mvp-binance-panel-0.1.0.jar\napp.classpath=$APPDIR/byx-local-service-custody-qa-tests.jar",1)
open(p,"w").write(s)
PY
grep -q "CustodyQaMain" "$C/app/byx-custody-qa-panel.cfg" || { echo "BLOCKED: cfg do papel painel"; exit 5; }
sign --identifier "$BYX_APP_ID" -r "$(dr "$BYX_APP_ID")" --entitlements "$HERE/entitlements/app.entitlements" "$C/MacOS/byx-custody-qa-panel"
sign --identifier "$BYX_APP_ID" -r "$(dr "$BYX_APP_ID")" --entitlements "$HERE/entitlements/app.entitlements" "$APP"

echo "== 5. ferramentas negativas (fora do bundle) e variantes do signer"
T="$OUT/Tools"; mkdir -p "$T"
cp "$OUT/byx-custody-qa-client.bin" "$T/client-unsigned"; codesign --remove-signature "$T/client-unsigned" 2>/dev/null || true
cp "$OUT/byx-custody-qa-client.bin" "$T/client-wrongrole"; sign --identifier "$WRONG_ID" "$T/client-wrongrole"
V="$OUT/Variants"; mkdir -p "$V"/{unsigned,modified,wrongid,copied}
cp -cR "$S" "$V/copied/byx-signer-helper-qa.app"                                  # genuíno, mas FORA da origem aprovada
cp -cR "$S" "$V/unsigned/byx-signer-helper-qa.app"; codesign --remove-signature "$V/unsigned/byx-signer-helper-qa.app/Contents/MacOS/byx-signer-helper-qa"; codesign --remove-signature "$V/unsigned/byx-signer-helper-qa.app" 2>/dev/null || true
cp -cR "$S" "$V/modified/byx-signer-helper-qa.app"; printf '\n<!-- tampered -->\n' >> "$V/modified/byx-signer-helper-qa.app/Contents/Info.plist"
cp -cR "$S" "$V/wrongid/byx-signer-helper-qa.app"; sign --identifier "$WRONG_ID" "$V/wrongid/byx-signer-helper-qa.app"

echo "== 6. verificação"
codesign --verify --deep --strict "$APP" && echo "OK: $APP"
codesign --verify --strict "$S" && echo "OK: signer QA"
echo "$OUT"
