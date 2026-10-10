# C4.1-P — Windows application image / local QA

Date: 2026-10-09 (America/Sao_Paulo). This is a local app-image, not an installer or production release.

## Independent results

- `C4_WINDOWS_APP_IMAGE_BUILT`
- `C4_WINDOWS_APP_IMAGE_RUNTIME_VERIFIED` — bounded public DEFAULT smoke through ordinary ShellExecute; physical Explorer double-click subsequently confirmed by the owner during C4.1-P finalization.
- `C4_WINDOWS_FULL_COMPATIBILITY_NOT_YET_QUALIFIED`

No production source, POM, Service, signer, custody, authorization or research changes. No commit, push, merge, policy changes, certificate imports or blocked IPC harness execution.

## Provenance and environment

Repository `C:\src\DEV`; branch `feature/byx-windows-readiness-v1`; HEAD `a8e1eaab044777215e24425c5688ec9ac1765a1d`; origin `https://github.com/ChamberUS/DEV.git`. Tracked working tree and index were clean and remain unchanged. Pre-existing untracked QA material was preserved. The historical preflight manifest's 857 files were rehashed: **857 unchanged, zero changed**.

Windows 11 Pro x64, 25H2, build 26200.9457. Temurin JDK `21.0.12.1` from `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`; Maven `3.9.9` from `C:\src\tools\apache-maven-3.9.9`. JavaFX `21.0.5` Windows artifacts. Runtime execution used a normal account with effective administrator membership false; tool execution permission did not elevate the Windows token.

Existing Windows regression remains **1008 PASS, 0 FAIL, 23 ERROR, 3 SKIP**; focused **249 PASS, 0 FAIL, 0 ERROR, 1 SKIP**. Those results are checkpoint evidence, not suites rerun for this packaging task. The 23 POSIX/native IPC fixture errors remain unresolved. Owner-reported macOS results: **998 PASS, 0 FAIL, 0 ERROR, 25 SKIP, 181 native UI checks**; report not committed/published and not independently verified here.

## Packaging and exact commands

Unique artifact root (no existing output overwritten):

`C:\src\BYX-artifacts\windows-c4-1-p\run-20261009-224800`

Image directory: `image\BYX-MVP` beneath that root. Launcher: `image\BYX-MVP\BYX-MVP.exe`. Keep the entire image together.

Only the Panel POM, `src/main`, `src/build-default`, and `src/wallet-build` were copied into `build-project`. No monorepository, tests, local databases, private packaging assets, provisioning or research captures were copied. The isolated Maven build selected DEFAULT sources and wallet capability DISABLED. Maven skipped tests for packaging only; no test failures were converted to success.

The executed Maven command, with process-local JAVA_HOME/PATH, was:

```powershell
$out = 'C:\src\BYX-artifacts\windows-c4-1-p\run-20261009-224800'
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;C:\src\tools\apache-maven-3.9.9\bin;$env:PATH"
mvn -f "$out\build-project\pom.xml" -Dmaven.test.skip=true -Dbyx.panel.sources=build-default -Dbyx.wallet.capability=DISABLED package org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies -DincludeScope=runtime "-DoutputDirectory=$out\input"
Copy-Item "$out\build-project\target\mvp-binance-panel-0.1.0.jar" "$out\input"
```

Native preparation opened the original dependency ZIP/JAR files using .NET `System.IO.Compression.ZipFile` and extracted, without modifying the JARs:

- Every `.dll` entry of `javafx-graphics-21.0.5-win.jar` into `input/native`, preserving file names.
- `com/sun/jna/win32-x86-64/jnidispatch.dll` from `jna-5.17.0.jar`.
- `org/sqlite/native/Windows/x86_64/sqlitejdbc.dll` from `sqlite-jdbc-3.46.1.0.jar`.

`windows-native-inventory.json` records exact source JAR, ZIP entry, extracted file and SHA-256. These are Windows DLLs; no macOS helper or Service was run. Intact cross-platform dependency JARs still contain unused vendor native payloads for other operating systems; their presence does not represent macOS binary execution.

The executed jpackage command was equivalent to the following argument-array invocation. `jpackage-command.json` contains the complete verbatim arguments used:

```powershell
$opts = @(
  '-Dfile.encoding=UTF-8', '-XX:+DisableAttachMechanism',
  '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED',
  '--add-opens=java.base/java.io=ALL-UNNAMED',
  '-Djava.library.path=$APPDIR/native', '-Djna.boot.library.path=$APPDIR/native',
  '-Djna.nosys=true', '-Dorg.sqlite.lib.path=$APPDIR/native',
  '-Dorg.sqlite.lib.name=sqlitejdbc.dll'
)
$argsForPackage = @(
  '--type','app-image','--name','BYX-MVP','--dest',"$out\image",
  '--input',"$out\input",'--main-jar','mvp-binance-panel-0.1.0.jar',
  '--main-class','panel.app.Main','--app-version','0.1.0',
  '--vendor','BYX-MVP','--description','BYX-MVP Windows DEFAULT public UI - local QA',
  '--add-modules','java.base,java.desktop,java.naming,java.net.http,java.sql,java.logging,java.xml,java.management,java.scripting,java.security.jgss,jdk.jfr,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.zipfs',
  '--verbose'
)
foreach ($opt in $opts) { $argsForPackage += @('--java-options', $opt) }
& "$env:JAVA_HOME\bin\jpackage.exe" @argsForPackage
```

Maven and jpackage both exited **0**. Maven BUILD SUCCESS, 14.468 seconds. Evidence: `maven-package.log`, `maven-exit.txt`, `jpackage.log`, `jpackage-exit.txt`. No WiX, MSI or installer EXE. jpackage's generic Defender exclusion suggestion was not followed.

## Runtime, dependencies and resources

Embedded `runtime/release` reports Java `21.0.12.1`; jlink resolved modules:

`java.base java.datatransfer java.xml java.prefs java.desktop java.logging java.management java.security.sasl java.naming java.net.http java.scripting java.security.jgss java.transaction.xa java.sql jdk.charsets jdk.crypto.ec jdk.jfr jdk.unsupported jdk.zipfs`

JavaFX is carried as classpath JARs and Windows native libraries, not JavaFX jlink modules. `app/BYX-MVP.cfg` names the packaged JARs and bundled native directories. Native launcher embeds the image JVM; launching needs neither Maven nor system Java. Loaded module inspection observed image-local `jvm.dll`, `java.dll`, JNA `jnidispatch.dll`, and SQLite `sqlitejdbc.dll`.

19 packaged JARs, each hashed in `dependency-sha256.json`:

```text
bcprov-jdk18on-1.78.1.jar
core-1.2.6.jar
fxplayer-1.2.6.jar
jackson-annotations-2.21.jar
jackson-core-2.20.0.jar
jackson-core-3.1.1.jar
jackson-databind-2.20.0.jar
jackson-databind-3.1.1.jar
javafx-base-21.0.5.jar
javafx-base-21.0.5-win.jar
javafx-controls-21.0.5.jar
javafx-controls-21.0.5-win.jar
javafx-graphics-21.0.5.jar
javafx-graphics-21.0.5-win.jar
jna-5.17.0.jar
mvp-binance-panel-0.1.0.jar
protobuf-java-4.33.5.jar
slf4j-api-2.0.17.jar
sqlite-jdbc-3.46.1.0.jar
```

Both Jackson major versions are the actual resolved graph, including transitive dependencies; no dependency versions were edited. Future reproducibility should lock the complete graph rather than relying on changing transitive resolution.

All **67 main resources** are present and byte-identical to staged source resources: zero missing or mismatched. Application JAR has 833 entries, `panel.app.Main`, locale bundles EN/PT-BR, theme CSS, fonts, notification resources, and 19 mascot assets. Filtered wallet capability is **DISABLED**. No QaApp/txview/test payload or prohibited runtime/credential payload was found in the application inventory. The input was exclusively reviewed source plus Maven runtime dependencies, not user-home files. Packaging does not constitute a general third-party dependency security audit.

JavaFX actually loaded `glass.dll`, `prism_d3d.dll`, and `javafx_font.dll` through its normal per-user `.openjfx/cache/21.0.5+1/amd64` cache. All three hashes match their bundled Windows DLLs exactly (`resource-integrity.json`). The image's classpath contains only its own JARs; no Maven development classpath or source-resource directory is configured. Clean-machine/empty-cache extraction remains untested; no cache deletion or alternate launch was used.

No approved Windows `.ico` was found. The native launcher uses jpackage's default icon; the rendered application retains existing BYX branding, fonts and assets. Custom Windows icon approval remains future work.

## Hashes, signature and App Control

Final image: **261 files, 140,643,235 bytes**. Full relative-path/size/SHA-256 inventory: `app-image-sha256.json` outside the image. Manifest SHA-256:

`2322F757EA7E445BDB8FAC06A944C8145C3A972B82A70C0E091688FCE66FAC61`

Launcher SHA-256:

`02C0CE6B6D81F2A79C70280396DCEC5497922D446AFB9FDB1B59DFF329533406`

Launcher Authenticode status **NotSigned**, no signer or timestamp signer (`launcher-signature.json`). Existing machine evidence identifies Smart App Control enforcement `VerifiedAndReputableDesktop`; the earlier IPC harness denial remains distinct. This launch succeeded without a policy change. No artifact-matching Code Integrity denial event was found in the inspected launch interval (`code-integrity-runtime.json`). This does not establish permanent trust, reputation, or authorization to run the previously blocked harness.

Future distribution needs a reviewed Windows signing identity, certificate/trust acceptance under the target policy, timestamping and publisher verification. Self-signing, moving files or executable-name changes are not assumed to establish trust. Signing the launcher/JVM does not prove live BYX Java application identity.

## Bounded packaged-runtime smoke

Exactly one ordinary launch attempt at `2026-10-09T22:50:22.3665491-03:00`:

```powershell
Start-Process -FilePath 'C:\src\BYX-artifacts\windows-c4-1-p\run-20261009-224800\image\BYX-MVP\BYX-MVP.exe' -WorkingDirectory 'C:\src\BYX-artifacts\windows-c4-1-p\run-20261009-224800\image\BYX-MVP' -PassThru
```

No RunAs, alternate Java launch, auth-bypass profile or simulated session. Parent PID 17192 spawned same-image UI PID 10728. Public checks used Windows UI Automation and screenshots; no credentials were entered. Physical mouse double-click was not performed and remains a human acceptance item.

| Check | Actual result |
| --- | --- |
| Public Login / DARK | Rendered with bundled typography and existing artwork; screenshot inspected |
| Service state | Honest “Local service unavailable” / “Serviço local indisponível”; action becomes Retry; no authenticated session |
| EN → PT-BR | Locale selector switched login text, banner and links |
| Help / FAQ | Public Help opened; “Abrir FAQ” displayed FAQ and 20 questions; screenshots inspected |
| Keyboard | Tab moved from username field to forgot-password button within UI PID; focus ring visible on public navigation |
| Minimum dimensions | Request 600×400 physical clamped to 1650×1050 physical, equivalent to 1100×700 logical at 150% |
| Native controls | Move/resize, minimize and maximize responded; maximized outer bounds −11,−11,1942,1030 include window frame |
| Shutdown | WindowPattern.Close at 22:55:11; UI and launcher parent exited within bounded waits; numeric exit codes unavailable from retrieved process objects |
| Double-click | Owner-confirmed physical Explorer double-click during finalization; ordinary ShellExecute independently verified earlier |
| Private screens / LIGHT | Not exercised through authentication bypass; no qualification claim |

Display was physically 1920×1080 at 150% scaling. Restored minimum-height window overlaps the taskbar; lower content is partly obscured in the captures. This is an observed visual limitation requiring human review, not a passing layout claim at every resolution. Other display resolutions were not physically tested in this milestone. FAQ content updates while the native title remains “Ajuda”, also visible in the capture; no source fix was made.

Evidence at artifact root: `login-en.png`, `login-pt.png`, `help-pt.png`, `faq-pt.png`; `ui-login-elements.json`, `ui-login-pt-elements.json`, `ui-help-elements.json`, `ui-faq-elements.json`, `window-focus-smoke.json`, `runtime-attempt.json`, `runtime-close.json`, `packaged-loaded-modules.json`. Screenshots include only the application region plus the overlapping Windows taskbar; no entered credentials or research data.

## Security, limitations and macOS impact

Authenticated Service access remains unavailable: Windows secure IPC transport has not been qualified, live BYX application identity is not proven, and no privileged pairing/session/Service authorization is enabled. Authenticated Trading, Research, Benefits, wallet, custody and notification-center behavior are not demonstrated by this packaging smoke. Mascot and notification assets are bundled; private functionality is not implied by resource presence. Windows ACL storage implementation and all gates are preserved unchanged.

No macOS Service/helper, Apple signing/provisioning or Keychain migration was used. Shared source and existing macOS packaging are unchanged. A later shared-source/dependency/signing change requires macOS regression; this local Windows artifact creates no macOS compatibility claim. Keep the existing 23 Windows IPC errors visible until independently fixed and qualified.

REAL USER KEY = NOT AUTHORIZED. REAL TX = DISABLED. BROADCASTS = 0. REAL FUNDS = 0. REAL USER WALLETS = 0. ETHUSDT RESEARCH = UNTOUCHED.

## Git inventory and human launch

New workspace files in this task: this report and `app-image-current-artifact-path.txt` (artifact pointer). Generated project, binaries, logs, hashes and screenshots are outside the repository. Before the report, Git listed 591 untracked files, including the pointer; after it, 592 are expected. No tracked files or staged files changed. Existing unrelated untracked files remain untouched. Nothing was committed or pushed.

For human UAT, open the image folder in Explorer and double-click **BYX-MVP.exe** as the normal user. Keep `app` and `runtime` beside the launcher; do not copy the EXE alone. Alternatively use this single PowerShell command (no Maven/Java/admin requirement):

```powershell
Start-Process -FilePath 'C:\src\BYX-artifacts\windows-c4-1-p\run-20261009-224800\image\BYX-MVP\BYX-MVP.exe' -WorkingDirectory 'C:\src\BYX-artifacts\windows-c4-1-p\run-20261009-224800\image\BYX-MVP'
```

Confirm Login and the unavailable banner; switch EN/PT-BR; open Help/FAQ; Tab through public controls; maximize/restore and inspect lower content; close with X. Do not enter real credentials. If Windows refuses execution, preserve the denial and stop—do not bypass protection. The owner subsequently confirmed Explorer double-click startup and the public UI/unavailable state; broader visual acceptance and clean-machine qualification remain pending.

## Finalization addendum

The statements above about no commit and pending physical double-click describe the initial packaging pass. The owner now authorizes a local source checkpoint, without push, and confirms physical double-click UAT. All 261 original image files were rehashed unchanged. The image remains unsigned and public DEFAULT only.

Packaging implementation is now preserved in `byx-packaging/build-windows-app-image.ps1` with a reviewed 18-dependency SHA-256 inventory in `byx-packaging/windows-runtime-dependencies.json`. It does not change shared Java/POM/macOS build behavior. The script's normal invocation was denied by PowerShell execution policy before its body ran; no fresh image was produced, no policy was changed and no bypass was attempted. See `windows-app-image-checkpoint.md` for the exact command and incomplete rebuild qualification. Original build/runtime success remains valid for the UAT image; the new script has static review only until an authorized execution environment is available.

Subsequent closeout: the owner successfully rebuilt from source checkpoint `54e1406e04cc977026fe1f7a401eb1d81df50bd1` and confirms runtime/public UAT of that new image. [Final UAT report](C4_WINDOWS_APP_IMAGE_FINAL_UAT.md) records independently inspected build logs, all 261 image hashes, and the whole-JAR difference with identical entry contents. This supersedes the rebuild blocker above; security/production readiness remains unqualified.

JDK tooling references: [Oracle JDK 21 jpackage](https://docs.oracle.com/en/java/javase/21/jpackage/packaging-overview.html) supports self-contained app images; [OpenJFX documentation](https://openjfx.io/openjfx-docs/index.html) describes platform-specific Maven JavaFX artifacts. Those references describe tooling, not BYX security qualification.
