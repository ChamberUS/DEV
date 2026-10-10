# C4.1-P finalization and local source checkpoint

2026-10-09. **C4_WINDOWS_APP_IMAGE_CHECKPOINT_BLOCKED** for complete reproducible-script qualification: ordinary PowerShell script execution was denied before the rebuild began. A reviewed local source checkpoint is still authorized and useful; it does not qualify the unexecuted script as tested.

## Preserved baseline and UAT

Git root `C:\src\DEV`, branch `feature/byx-windows-readiness-v1`, parent `a8e1eaab044777215e24425c5688ec9ac1765a1d`. No tracked/index changes at audit start; 592 untracked files: 495 other QA evidence, 23 isolated IPC evidence, 72 private checkpoint evidence, and 2 app-image documentation/pointer files. All were inventoried locally, not indiscriminately staged. Private evidence, local pointers and generated artifacts remain outside the commit.

The owner explicitly confirms physical Windows Explorer double-click launch, public Login and expected Service-unavailable state on the existing image. This is owner-confirmed UAT, distinct from the previously independently executed ShellExecute/UI Automation smoke. No extra application or blocked IPC harness was launched for this finalization.

Approved image remains:

`C:\src\BYX-artifacts\windows-c4-1-p\run-20261009-224800\image\BYX-MVP`

All **261 files** rehashed unchanged against the original manifest; 140,643,235 bytes. Launcher SHA-256 `02C0CE6B6D81F2A79C70280396DCEC5497922D446AFB9FDB1B59DFF329533406`; image-manifest SHA-256 `2322F757EA7E445BDB8FAC06A944C8145C3A972B82A70C0E091688FCE66FAC61`. Authenticode **NotSigned**; original ordinary launch was permitted, with no permanent trust or application-code identity claim. No image/runtimes/binaries are included in Git.

## Packaging implementation

Commit scope is exactly these four text files:

1. `byx-packaging/build-windows-app-image.ps1`
2. `byx-packaging/windows-runtime-dependencies.json`
3. `mvp-binance-panel/docs/qa/package-c4/windows-app-image-qa.md`
4. `mvp-binance-panel/docs/qa/package-c4/windows-app-image-checkpoint.md`

Script builds in a new external directory, refuses existing output and in-repository output, stages only Git-tracked Panel build inputs, chooses DEFAULT sources and DISABLED wallet capability, copies runtime dependencies, rejects drift against 18 reviewed dependency hashes, extracts Windows JavaFX/JNA/SQLite DLLs, verifies main resources and the wallet capability, packages with jpackage, and generates local provenance/log/hash/signature evidence. It does not launch the image. It rejects injected JVM/Maven environment options and restores process JAVA_HOME/PATH. Original JARs remain intact, including unused vendor native payloads for other platforms. Maven tests are skipped for artifact construction only; the known **1008 PASS / 0 FAIL / 23 ERROR / 3 SKIP** baseline remains explicitly unresolved.

Tool versions reconfirmed: Temurin Java **21.0.12.1**, Maven **3.9.9** using that Java, jpackage **21.0.12.1**, JavaFX **21.0.5**. The script intentionally requires those qualified JDK/Maven versions. This is reproducibility of the selected build inputs/dependency graph and procedure, not a promise of bit-for-bit JAR timestamps or launcher output across future toolchains. The SHA lock fails closed on changed Maven transitive resolution instead of silently accepting it; all Maven plugin/tool inputs are not fully vendored.

Exact normal rebuild command attempted:

```powershell
& .\byx-packaging\build-windows-app-image.ps1 -OutputDirectory 'C:\src\BYX-artifacts\windows-c4-1-p\finalization-20261009-230319\rebuild' -JdkHome 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot' -MavenHome 'C:\src\tools\apache-maven-3.9.9' -GitExecutable 'C:\Program Files\Git\cmd\git.exe'
```

Result: **PSSecurityException**, FullyQualifiedErrorId **UnauthorizedAccess**, message states script execution is disabled. All five configured execution-policy scopes read `Undefined`; effective policy is **Restricted**. This is a PowerShell execution-policy refusal, not evidence of a new Smart App Control denial. Script body did not execute and `rebuild` directory does not exist. No `ExecutionPolicy Bypass`, policy modification, inline evaluation, alternate wrapper, certificate import or relocation was attempted.

Static PowerShell parser review passed with zero syntax errors. Full script build execution remains **NOT VERIFIED**. The preceding manual build and packaged runtime are verified as described in `windows-app-image-qa.md`. Required next step: policy owner approves an appropriate development execution procedure for this reviewed script (including trusted signing if required), then run it normally into a fresh directory and qualify the new image separately. Do not treat this report as authorization to change policy or trust certificates.

Local finalization evidence, kept untracked/outside Git:

`C:\src\BYX-artifacts\windows-c4-1-p\finalization-20261009-230319`

Contains pre-checkpoint untracked-file hash inventory, policy inspection, original-image verification and review/checkpoint receipts. The original detailed QA report records the exact successful Maven/jpackage commands and resource inventories. The packaged launcher config uses image-local JARs and the bundled runtime; launch requires neither Maven nor an external Java/development working directory. Prior JavaFX native-cache hash verification still applies; a separate clean-machine test remains pending.

## Branding

Panel and packaging asset searches found no approved `.ico`, `.icns` or standalone Windows logo asset. Existing approved UI branding is rendered typography/artwork with bundled fonts and mascot PNG/JSON resources; those do not establish approval of a launcher icon. An unrelated web-project favicon is not a BYX-MVP approved asset and was not reused. Generic Explorer icon occurs because the original jpackage invocation omitted `--icon`, leaving the JDK template icon.

Once an approved Windows `.ico` exists, jpackage supports `--icon <approved-file.ico>` during image creation. No new logo, conversion/redesign or cosmetic Java-source edit was made. A future icon change requires a new image/hash/signing/UAT record, preserving the approved image.

## Security and cross-platform impact

Packaging inputs are tracked Panel source/resources plus reviewed Maven runtime JARs. No user-home/private packaging content, runtime/account databases, pairing tokens, keys, credentials, personal data, test secrets, provisioning, Keychain or research captures were copied. Original image file/ZIP inventories and unchanged checksums support this scope; filename/content-inventory checks do not constitute a general dependency security audit. No real input credentials were entered. Existing localization, theme, mascot and notification resources remain packaged; private screens were not authenticated or qualified.

Windows IPC transport remains unqualified; live BYX application identity unproven; mutual pairing/session/Service authorization unresolved. Signing a launcher or java.exe alone does not resolve those identity requirements. Privileged Service connectivity, signer, custody, wallets and trading remain unavailable. Smart App Control and all Windows security settings remain unchanged.

No shared Java, POM, macOS packaging or approved macOS branch changes. No new macOS regression is required by this Windows-only script/documentation checkpoint; any later shared-source/dependency change requires focused security/Panel and full macOS regression, including peer identity, POSIX storage, public UI and packaging. Owner-reported macOS results remain 998 PASS / 0 FAIL / 0 ERROR / 25 SKIP and 181 native UI checks, not independently rerun here.

REAL USER KEY = NOT AUTHORIZED. REAL TX = DISABLED. BROADCASTS = 0. REAL FUNDS = 0. REAL USER WALLETS = 0. ETHUSDT RESEARCH = UNTOUCHED.

After exact-path staging and review, only the four files above are locally committed. Final SHA and actual Git status are returned in the task response and local checkpoint receipt (a commit cannot contain its own hash). Expected remaining untracked files: **591**, including the original artifact pointer and all unrelated/private evidence; tracked worktree/index clean. No push, merge, installer, public release or production IPC.
