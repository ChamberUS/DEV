#!/bin/zsh
# Separate SYNTHETIC_QA artifact. Chain remains PRODUCTION_DISABLED. Never modifies DEFAULT runtime.
set -euo pipefail
HERE="${0:A:h}"; ROOT="${HERE:h}"
export JAVA_HOME="${JAVA_HOME:-$HOME/dev/tools/jdk-21.0.12.1+1/Contents/Home}"
export PATH="$JAVA_HOME/bin:$HOME/dev/tools/apache-maven-3.9.9/bin:$PATH"
"$HERE/build-wallet-lifecycle-qa.sh"
source "$HERE/identity.env"
OUT="$HERE/build-custody-qa"; APP="$OUT/$BYX_APP_NAME.app"; C="$APP/Contents"
IDENT="$(security find-identity -v -p codesigning | awk '/Apple Development/ {print $2; exit}')"
[[ -n "$IDENT" ]] || exit 3
(cd "$ROOT/mvp-binance-panel" && mvn -o -q -Pwallet-qa -DskipTests package)
cp "$ROOT/mvp-binance-panel/target/mvp-binance-panel-0.1.0.jar" "$C/app/"
jar cf "$OUT/byx-panel-wallet-qa-tests.jar" -C "$ROOT/mvp-binance-panel/target/test-classes" .
cp "$OUT/byx-panel-wallet-qa-tests.jar" "$C/app/"
cp "$C/MacOS/byx-custody-qa-panel" "$C/MacOS/byx-wallet-panel-qa"
cp "$C/app/byx-custody-qa-panel.cfg" "$C/app/byx-wallet-panel-qa.cfg"
python3 - "$C/app/byx-wallet-panel-qa.cfg" <<'PY'
import sys
p=sys.argv[1];s=open(p).read().replace('byx.service.signer.CustodyQaMain','panel.wallet.WalletPanelQaMain')
s=s.replace('app.classpath=$APPDIR/byx-local-service-custody-qa-tests.jar','app.classpath=$APPDIR/byx-panel-wallet-qa-tests.jar')
open(p,'w').write(s)
PY
codesign --force --options runtime --timestamp=none -s "$IDENT" --identifier "$BYX_APP_ID" --entitlements "$HERE/entitlements/app.entitlements" "$C/MacOS/byx-wallet-panel-qa"
codesign --force --options runtime --timestamp=none -s "$IDENT" --identifier "$BYX_APP_ID" --entitlements "$HERE/entitlements/app.entitlements" "$APP"
codesign --verify --deep --strict "$APP"
[[ "$(unzip -p "$C/app/mvp-binance-panel-0.1.0.jar" panel/wallet-capability.txt)" == SYNTHETIC_QA ]] || exit 4
[[ "$(unzip -p "$C/Helpers/byx-custody-qa-service.app/Contents/app/byx-local-service-0.1.0.jar" byx/chain-profile.txt)" == PRODUCTION_DISABLED ]] || exit 5
