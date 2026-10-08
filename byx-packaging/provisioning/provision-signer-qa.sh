#!/bin/zsh
# Obtém (ou renova) o perfil de provisionamento de DESENVOLVIMENTO do signer QA (custódia SINTÉTICA) pelo mecanismo normal do Xcode (conta já logada no
# Xcode; nenhuma credencial é lida, exportada ou guardada aqui). AÇÃO PERSISTENTE na conta Apple: cria/atualiza o App ID explícito
# com.buynnex.byx.signer.qa e um perfil de desenvolvimento, e registra ESTE Mac na lista de dispositivos do time. Personal Team = recursos TEMPORÁRIOS
# que exigem reprovisionamento periódico; isto é SOMENTE para desenvolvimento, nunca estratégia de distribuição.
#   uso: ./provision-signer-qa.sh            (gera out/signer-qa.provisionprofile; ignorado pelo git)
set -euo pipefail
HERE="${0:A:h}"; source "$HERE/../identity.env"
SIGNER_QA_ID="${BYX_APP_ID}.signer.qa"   # SOMENTE a identidade QA do signer (a de produção NÃO é criada aqui)
SIGNER_QA_GROUP_SUFFIX="${SIGNER_QA_ID}.keys"
TEAM=$(security find-certificate -c "Apple Development" -p | openssl x509 -noout -subject -nameopt multiline | awk '/organizationalUnitName/ {print $3; exit}')
[[ "$TEAM" =~ '^[A-Z0-9]{10}$' ]] || { echo "Team ID não encontrado"; exit 3; }
W=$(mktemp -d /tmp/byxprov.XXXX); P="$W/BYXSignerQaProvisioning.xcodeproj"; mkdir -p "$P" "$HERE/out"
echo 'print("byx provisioning probe")' > "$W/main.swift"
# Menor privilégio: application-identifier próprio, team-identifier e UM grupo de keychain exclusivo do signer QA (nada de wildcard no binário,
# App Groups ou outras capacidades). O Panel e o Service NÃO recebem este grupo.
cat > "$W/probe.entitlements" <<ENT
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
	<key>com.apple.application-identifier</key><string>\$(AppIdentifierPrefix)\$(CFBundleIdentifier)</string>
	<key>com.apple.developer.team-identifier</key><string>\$(DEVELOPMENT_TEAM)</string>
	<key>keychain-access-groups</key><array><string>\$(AppIdentifierPrefix)$SIGNER_QA_GROUP_SUFFIX</string></array>
</dict></plist>
ENT
cat > "$P/project.pbxproj" <<PBX
// !\$*UTF8*\$!
{
	archiveVersion = 1; classes = {}; objectVersion = 56;
	objects = {
		A1000000000000000000001 = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = main.swift; sourceTree = "<group>"; };
		A100000000000000000000F = {isa = PBXFileReference; lastKnownFileType = text.plist.entitlements; path = probe.entitlements; sourceTree = "<group>"; };
		A1000000000000000000002 = {isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = BYXSignerQaProvisioning.app; sourceTree = BUILT_PRODUCTS_DIR; };
		A1000000000000000000003 = {isa = PBXBuildFile; fileRef = A1000000000000000000001; };
		A1000000000000000000004 = {isa = PBXGroup; children = (A1000000000000000000001, A100000000000000000000F, A1000000000000000000005); sourceTree = "<group>"; };
		A1000000000000000000005 = {isa = PBXGroup; children = (A1000000000000000000002); name = Products; sourceTree = "<group>"; };
		A1000000000000000000006 = {isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = (A1000000000000000000003); runOnlyForDeploymentPostprocessing = 0; };
		A1000000000000000000007 = {isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0; };
		A1000000000000000000008 = {isa = PBXNativeTarget; buildConfigurationList = A100000000000000000000C; buildPhases = (A1000000000000000000006, A1000000000000000000007); buildRules = (); dependencies = (); name = BYXSignerQaProvisioning; productName = BYXSignerQaProvisioning; productReference = A1000000000000000000002; productType = "com.apple.product-type.application"; };
		A1000000000000000000009 = {isa = PBXProject; attributes = {BuildIndependentTargetsInParallel = 1; LastUpgradeCheck = 2600; TargetAttributes = {A1000000000000000000008 = {CreatedOnToolsVersion = 26.0;};};}; buildConfigurationList = A100000000000000000000B; compatibilityVersion = "Xcode 14.0"; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en, Base); mainGroup = A1000000000000000000004; productRefGroup = A1000000000000000000005; projectDirPath = ""; projectRoot = ""; targets = (A1000000000000000000008); };
		A100000000000000000000B = {isa = XCConfigurationList; buildConfigurations = (A100000000000000000000D); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; };
		A100000000000000000000C = {isa = XCConfigurationList; buildConfigurations = (A100000000000000000000E); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; };
		A100000000000000000000D = {isa = XCBuildConfiguration; name = Release; buildSettings = {SDKROOT = macosx; MACOSX_DEPLOYMENT_TARGET = 12.0; SWIFT_VERSION = 5.0; ONLY_ACTIVE_ARCH = YES; ENABLE_APP_SANDBOX = NO; }; };
		A100000000000000000000E = {isa = XCBuildConfiguration; name = Release; buildSettings = {PRODUCT_BUNDLE_IDENTIFIER = "$SIGNER_QA_ID"; PRODUCT_NAME = "\$(TARGET_NAME)"; CODE_SIGN_STYLE = Automatic; DEVELOPMENT_TEAM = $TEAM; ENABLE_HARDENED_RUNTIME = YES; GENERATE_INFOPLIST_FILE = YES; ENABLE_APP_SANDBOX = NO; CODE_SIGN_IDENTITY = "Apple Development"; CODE_SIGN_ENTITLEMENTS = probe.entitlements; }; };
	};
	rootObject = A1000000000000000000009;
}
PBX
cd "$W"
xcodebuild -project BYXSignerQaProvisioning.xcodeproj -scheme BYXSignerQaProvisioning -configuration Release -derivedDataPath "$W/dd" -allowProvisioningUpdates -allowProvisioningDeviceRegistration build 2>&1 | tail -25
APPB=$(find "$W/dd" -name "BYXSignerQaProvisioning.app" -maxdepth 6 | head -1)
[[ -f "$APPB/Contents/embedded.provisionprofile" ]] || { echo "BLOCKED: o Xcode não gerou o perfil (ver saída acima)"; exit 4; }
cp "$APPB/Contents/embedded.provisionprofile" "$HERE/out/signer-qa.provisionprofile"
echo "perfil: $HERE/out/signer-qa.provisionprofile"
