#!/bin/zsh
# Obtém (ou renova) o perfil de provisionamento de DESENVOLVIMENTO do helper do serviço pelo mecanismo normal do Xcode (conta já logada no
# Xcode; nenhuma credencial é lida, exportada ou guardada aqui). AÇÃO PERSISTENTE na conta Apple: cria/atualiza o App ID explícito
# <service-id> e um perfil de desenvolvimento, e registra ESTE Mac na lista de dispositivos do time. Personal Team = recursos TEMPORÁRIOS
# que exigem reprovisionamento periódico; isto é SOMENTE para desenvolvimento, nunca estratégia de distribuição.
#   uso: ./provision.sh            (gera out/service.provisionprofile; ignorado pelo git)
set -euo pipefail
HERE="${0:A:h}"; source "$HERE/../identity.env"
TEAM=$(security find-certificate -c "Apple Development" -p | openssl x509 -noout -subject -nameopt multiline | awk '/organizationalUnitName/ {print $3; exit}')
[[ "$TEAM" =~ '^[A-Z0-9]{10}$' ]] || { echo "Team ID não encontrado"; exit 3; }
W=$(mktemp -d /tmp/byxprov.XXXX); P="$W/BYXServiceProvisioning.xcodeproj"; mkdir -p "$P" "$HERE/out"
echo 'print("byx provisioning probe")' > "$W/main.swift"
# Menor privilégio: SÓ o application-identifier próprio (grupo padrão do keychain de proteção de dados) e o team-identifier.
# Nada de keychain-access-groups explícito, App Groups ou outras capacidades.
cat > "$W/probe.entitlements" <<ENT
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
	<key>com.apple.application-identifier</key><string>\$(AppIdentifierPrefix)\$(CFBundleIdentifier)</string>
	<key>com.apple.developer.team-identifier</key><string>\$(DEVELOPMENT_TEAM)</string>
</dict></plist>
ENT
cat > "$P/project.pbxproj" <<PBX
// !\$*UTF8*\$!
{
	archiveVersion = 1; classes = {}; objectVersion = 56;
	objects = {
		A1000000000000000000001 = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = main.swift; sourceTree = "<group>"; };
		A100000000000000000000F = {isa = PBXFileReference; lastKnownFileType = text.plist.entitlements; path = probe.entitlements; sourceTree = "<group>"; };
		A1000000000000000000002 = {isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = BYXServiceProvisioning.app; sourceTree = BUILT_PRODUCTS_DIR; };
		A1000000000000000000003 = {isa = PBXBuildFile; fileRef = A1000000000000000000001; };
		A1000000000000000000004 = {isa = PBXGroup; children = (A1000000000000000000001, A100000000000000000000F, A1000000000000000000005); sourceTree = "<group>"; };
		A1000000000000000000005 = {isa = PBXGroup; children = (A1000000000000000000002); name = Products; sourceTree = "<group>"; };
		A1000000000000000000006 = {isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = (A1000000000000000000003); runOnlyForDeploymentPostprocessing = 0; };
		A1000000000000000000007 = {isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0; };
		A1000000000000000000008 = {isa = PBXNativeTarget; buildConfigurationList = A100000000000000000000C; buildPhases = (A1000000000000000000006, A1000000000000000000007); buildRules = (); dependencies = (); name = BYXServiceProvisioning; productName = BYXServiceProvisioning; productReference = A1000000000000000000002; productType = "com.apple.product-type.application"; };
		A1000000000000000000009 = {isa = PBXProject; attributes = {BuildIndependentTargetsInParallel = 1; LastUpgradeCheck = 2600; TargetAttributes = {A1000000000000000000008 = {CreatedOnToolsVersion = 26.0;};};}; buildConfigurationList = A100000000000000000000B; compatibilityVersion = "Xcode 14.0"; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en, Base); mainGroup = A1000000000000000000004; productRefGroup = A1000000000000000000005; projectDirPath = ""; projectRoot = ""; targets = (A1000000000000000000008); };
		A100000000000000000000B = {isa = XCConfigurationList; buildConfigurations = (A100000000000000000000D); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; };
		A100000000000000000000C = {isa = XCConfigurationList; buildConfigurations = (A100000000000000000000E); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; };
		A100000000000000000000D = {isa = XCBuildConfiguration; name = Release; buildSettings = {SDKROOT = macosx; MACOSX_DEPLOYMENT_TARGET = 12.0; SWIFT_VERSION = 5.0; ONLY_ACTIVE_ARCH = YES; ENABLE_APP_SANDBOX = NO; }; };
		A100000000000000000000E = {isa = XCBuildConfiguration; name = Release; buildSettings = {PRODUCT_BUNDLE_IDENTIFIER = "$BYX_SERVICE_ID"; PRODUCT_NAME = "\$(TARGET_NAME)"; CODE_SIGN_STYLE = Automatic; DEVELOPMENT_TEAM = $TEAM; ENABLE_HARDENED_RUNTIME = YES; GENERATE_INFOPLIST_FILE = YES; ENABLE_APP_SANDBOX = NO; CODE_SIGN_IDENTITY = "Apple Development"; CODE_SIGN_ENTITLEMENTS = probe.entitlements; }; };
	};
	rootObject = A1000000000000000000009;
}
PBX
cd "$W"
xcodebuild -project BYXServiceProvisioning.xcodeproj -scheme BYXServiceProvisioning -configuration Release -derivedDataPath "$W/dd" -allowProvisioningUpdates -allowProvisioningDeviceRegistration build 2>&1 | tail -25
APPB=$(find "$W/dd" -name "BYXServiceProvisioning.app" -maxdepth 6 | head -1)
[[ -f "$APPB/Contents/embedded.provisionprofile" ]] || { echo "BLOCKED: o Xcode não gerou o perfil (ver saída acima)"; exit 4; }
cp "$APPB/Contents/embedded.provisionprofile" "$HERE/out/service.provisionprofile"
echo "perfil: $HERE/out/service.provisionprofile"
