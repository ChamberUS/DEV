#!/bin/zsh
# Separate instrumented QA build; the regular QA and DEFAULT builders never select fencingprobe.
set -euo pipefail
HERE="${0:A:h}"
ROOT="${HERE:h}"
"$HERE/build-custody-qa.sh"
source "$HERE/identity.env"
OUT="$HERE/build-custody-qa"
APP="$OUT/$BYX_APP_NAME.app"
SIGNER="$APP/Contents/Helpers/byx-signer-helper-qa.app"
IDENT="$(security find-identity -v -p codesigning | awk '/Apple Development/ {print $2; exit}')"
[[ -n "$IDENT" ]] || exit 3
(cd "$ROOT/byx-local-service/signer-helper" && CGO_ENABLED=1 /usr/local/opt/go@1.25/bin/go build -p 1 -tags qa,fencingprobe -trimpath -o "$OUT/byx-signer-helper-qa-probe.bin" ./cmd/byx-signer-helper-qa)
cp "$OUT/byx-signer-helper-qa-probe.bin" "$SIGNER/Contents/MacOS/byx-signer-helper-qa"
TEAM="$(/usr/libexec/PlistBuddy -c 'Print :com.apple.developer.team-identifier' "$OUT/signer-qa.entitlements")"
codesign --force --options runtime --timestamp=none -s "$IDENT" --identifier com.buynnex.byx.signer.qa -r "=designated => identifier \"com.buynnex.byx.signer.qa\" and anchor apple generic and certificate leaf[subject.OU] = \"$TEAM\"" --entitlements "$OUT/signer-qa.entitlements" "$SIGNER"
codesign --force --options runtime --timestamp=none -s "$IDENT" --identifier "$BYX_APP_ID" -r "=designated => identifier \"$BYX_APP_ID\" and anchor apple generic and certificate leaf[subject.OU] = \"$TEAM\"" --entitlements "$HERE/entitlements/app.entitlements" "$APP"
codesign --verify --deep --strict "$APP"
