# Package C4.1 — Windows secure storage and native DEFAULT startup

Date: 2026-10-09. Historical C4.1 status: **BLOCKED_C4_1**. Latest corrective status: **BLOCKED_C4_1_REGRESSION**; see the [C4.1-R report](windows-c4-1-regression-hardening.md) and addendum below.

The real Windows DEFAULT application opens its authentication window and public Help/FAQ pages, and closes normally. Runtime storage uses verified native NTFS ownership and ACLs. The historical C4.1 run below had three mascot failures and 31 IPC errors. The later C4.1-R complete run has 992 PASS / 0 FAIL / 23 ERROR / 3 SKIP: mascot fixture corrections pass, while native IPC remains unqualified. Authenticated screens and the two larger physical logical-window targets remain unavailable; two minimum-size public-layout findings are recorded. No complete Windows compatibility or macOS regression success is claimed. Earlier run details below are preserved as historical evidence.

## Starting repository and environment

- Repository: `C:\src\DEV`, origin `https://github.com/ChamberUS/DEV.git`.
- Starting branch: `feature/byx-ui-redesign-v1`; HEAD/C3: `625689b2021c4c6a5f4a409d5d4680bccc865cbc`.
- Starting tracked tree and index were clean. Existing untracked `windows-baseline.md`, `windows-suite-summary.json` and `windows-test-failures.json` were preserved.
- Created local branch `feature/byx-windows-readiness-v1` at the same SHA. No commit, push, reset, rebase or release.
- Windows 11 Pro, version `10.0.26200`, build `26200`, x64; local C: NTFS.
- Temurin Java `21.0.12.1`, Maven `3.9.9`, JavaFX `21.0.5`, existing JNA `5.17.0`, SQLite JDBC `3.46.1.0`.
- `JAVA_HOME`: `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`; Maven home `C:\src\tools\apache-maven-3.9.9`. The runner's stale PATH was refreshed from persisted user/machine values for each Maven command; no persistent environment setting was changed.
- Actual account: `[QA-account-redacted]`, SID `[QA-user-SID-redacted]`. Administrator group is **not enabled** in its process token. See [environment evidence](c4-1-environment.json). Runner escalation addresses a sandbox setup failure; it did not elevate Windows privileges.
- Reviewed the complete previous C4 report and relevant A/B/C1/C2/C3 QA records. No `AGENTS.md` was found in the repository.

## Original 107 failures/errors

The unchanged [original case-level evidence](windows-test-failures.json) contains exact exception messages and complete traces; [original suite totals](windows-suite-summary.json) remain historical evidence. Counts below refer to the original 27 failures and 80 errors, excluding the existing live-observation skip.

| Underlying cause | Original count | Exact affected classes and diagnosis | C4.1 disposition |
|---|---:|---|---|
| Hardcoded `/tmp` setup | 42 errors | `ServiceChainGatewayTest` 1, `GasSignerIsolationTest` 1, `ChainStatusClientTest` 7, `LocalServiceClientTest` 12, `MarketFeedClientTest` 12, `PrivateFilesTest` 9; `NoSuchFileException: \tmp\...` before the assertions | Native temporary fixtures for the first two and PrivateFiles. The three IPC suites remain active and blocked, 31 errors. Their POSIX pairing/native identity assumptions are not a Windows transport implementation |
| Unconditional POSIX runtime storage | 29 errors | `ByxWalletOwnershipTest` 1, `PackageANavigationBoundaryTest` 3, `PackageC1AuthorityTest` 4, `PackageC3AppIsolationTest` 6, `ResearchGateReproductionTest` 10, `LegacyIsolationProductTest` 4, `LegacyPanelDbTest` 1; `Database.openRuntime → PrivateFiles.prepareDirectory → WindowsFileSystemProvider.readAttributes → UnsupportedOperationException` | Windows storage adapter plus secure fixture creation; assertions retained |
| Migration preparation masks storage cause | 5 errors | `RuntimeMigrationTest`: `IllegalStateException: prepare_failed`; migrator catches the underlying filesystem/storage exception | Native secure fixtures and same storage adapter; all eight migration tests passed, including source preservation |
| Unix subprocess fixture | 1 error | `BackendGatewayTest`: `Cannot run program "/bin/sh"`, `CreateProcess error=2` | Test child uses this JVM, retaining timeout, nonzero exit and JSON output assertions |
| File symlink creation privilege | 2 errors | `CaptureMonitorTest`, `LegacyPanelDbTest`: `FileSystemException`, client lacks required privilege | Two specific Unix file-symlink tests excluded on Windows only; real Windows runtime junction/ancestor-reparse and hardlink rejection tests added. Legacy absent/corrupt-file assertions remain active |
| Capture fixture identity/argv | 17 failures + 1 wrapped error | `CaptureMonitorTest` 4 failures plus a wrapped motion assertion error; `CaptureRuntimeResolverTest` 4 failures; `ScientificCaptureResolverTest` 9 failures. Relative synthetic state identity and Windows backslashes conflict with Unix process-argv fixtures | Normalize synthetic fixture identity/argv. Production capture resolvers, observers and ETHUSDT pipeline unchanged; tests passed |
| Inventory path separator | 9 failures | `FinalSafetyTest` 3, `LegacyAuthDisabledTest` 2, `ServerAuthorizationBoundaryTest` 2, `ChainOwnershipGuardTest` 1, `LegacyIsolationProductTest` 1 | Canonical `/` representation for source-inventory comparisons; mandatory forbidden-import/ownership assertions retained |
| Meta-only simulated shortcut | 1 failure | `ShellComponentsTest`: dialog/palette expectation used macOS Meta on Windows | Send Control on Windows, Meta on macOS; modal exclusion assertions retained |

These groups sum to 107. The fixtures often fail before reaching a deeper native restriction; fixing `/tmp` alone would not qualify private Service IPC. No missing JavaFX or SQLite DLL failure was observed. No platform-independent regression was established from the original evidence. Initially masked migration/capture causes were clarified by native reruns.

Original JSON SHA-256: failures `5E64C48D1441962B6E1254C947F7D9B58DF7A9D771A10882A730C4FDFC0FCA9B`; suite summary `7F88ED8C8F8038755A8505C3D0A1454046A2C5D9F3D5D5B34C4A617EECD148BC`.

## Storage contract and implementation

Original POSIX contract: new directory `0700`, new file `0600` before SQLite opens it; reject wrong directory owner/type, symlink and group/other directory write access; reject wrong file owner/type/link; never silently change existing files on open. Existing readable directory/file permissions are audited under the original macOS policy; tightening is an explicit operation. Legacy `panel.db` stays read-only/immutable and cannot become runtime authority. Runtime schema versions newer than supported remain refused.

`PrivateFiles` delegates its Windows operations to a small package-private `WindowsStorage`. The existing POSIX method bodies and constants remain unchanged. `RuntimeStorage` resolves the real current account's LocalAppData through `SHGetKnownFolderPath`, then appends `BYX-MVP`; it does not trust a caller-provided `user.home`, environment storage override, shared temp folder, repository or install directory for DEFAULT runtime state. Existing injected test compositions retain their isolated temporary homes; no new runtime bypass switch or fake authority was added.

The Windows policy requires a local fixed NTFS volume with persistent ACL support and an available NIO ACL view. Remote/device namespaces, alternate data streams and other providers are refused. `AclFileAttributeView` supplies a readable cross-check, but raw handle-based Windows descriptors are authoritative: the Java view does not expose all ACE types or inherited provenance. See [Java 21 ACL view](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/attribute/AclFileAttributeView.html) and [GetSecurityInfo](https://learn.microsoft.com/en-us/windows/win32/api/aclapi/nf-aclapi-getsecurityinfo).

The process token supplies the owner SID. New objects receive a protected DACL **at creation**, with full control only for that SID, SYSTEM and BUILTIN Administrators. New directories include object/container inheritance for those same principals; the runtime file is created before SQLite opens it. Native APIs reuse the existing JNA dependency; the POM and dependency versions are unchanged. See [security descriptor format](https://learn.microsoft.com/en-us/windows/win32/secauthz/security-descriptor-string-format), [CreateDirectoryW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createdirectoryw), [CreateFileW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew) and [known folder resolution](https://learn.microsoft.com/en-us/windows/win32/api/shlobj_core/nf-shlobj_core-shgetknownfolderpath).

Validation requires the current token user to own the object and have full access; directories must propagate that access to children. Every raw ACE is examined. Unintended principals, unsafe inherited grants, unresolved/malformed security, null/empty DACLs, unsupported ACE types/flags/rights and unverifiable metadata fail closed. Deny/object/callback/conditional ACEs are conservatively refused rather than approximated. Existing secure inherited ACLs are accepted after validation. Existing suspicious ACLs/data are never repaired or overwritten. Windows `tighten` explicitly refuses with `windows_explicit_acl_migration_required`.

All ancestors and the runtime file are opened with reparse-aware handles and without `FILE_SHARE_DELETE`, pinning their names throughout the database lifetime. Any ancestor or target reparse point, wrong type or multiply linked file is refused. The directory/file/SQLite `-journal`, `-wal`, `-shm` objects are checked before and after database callbacks. Sidecar handles are temporary so SQLite can delete them; safe directory inheritance restricts their initial creation. Workers stop before application shutdown releases the storage lease. Metadata audit never creates state and returns stable codes without paths.

Threat model: another ordinary Windows account must not read/write the application state or redirect its storage path. SYSTEM, administrators, OS/kernel compromise and other processes running as the same user are outside this isolation boundary, analogous to root/the same UID in the POSIX model. This is **non-auth runtime data protection**, not a Windows vault for keys, passwords, OTPs, trusted-device material or session tokens. No second Windows account was provisioned: native SID/ACE validation and real wrong-owner rejection were tested; an interactive cross-account penetration test remains a UAT extension. No general support for network shares, FAT/exFAT, redirected reparse-based profiles, Linux or unusual ACL forms is claimed.

## Native security tests and regression

`WindowsStorageTest`: 16 real Windows filesystem tests, no mocked ACL success. Coverage includes secure creation/current owner/protected DACL, immutable existing state, permissive directory/file rejection, unsafe inherited ACEs, real orphan-SID rejection without mutation, SQLite WAL/shm, unsafe sidecar rejection, concurrent creation (20 operations, six workers), pinned path replacement prevention/release, actual non-elevated junction traversal refusal including ancestors, hardlinks, provider without ACL support, independent state files, known-folder resolution unaffected by fake home, and redacted/noncreating diagnostics. `PrivateFilesTest`: all nine tests stay active, with Windows-equivalent owner/ACL/junction assertions. `WindowsServiceUnavailableTest`: three tests verify native unsupported state, absent session/auth refusal and no macOS helper resolution.

The reinforced final targeted run contains **66 PASS / 0 FAIL / 0 ERROR / 0 SKIP**: the above tests plus `AuthorityClientTest`, `PackageANavigationBoundaryTest` and `PackageC3NotificationTest`. Earlier storage run passed all 16 native cases; an intermediate 135-test run correctly rejected inherited broad fixture ACLs, and a subsequent 92-test run passed with one documented Unix symlink exclusion. Logs are retained separately; failed iterations were not rewritten.

| Run | Total | PASS | FAIL | ERROR | SKIP | Outcome |
|---|---:|---:|---:|---:|---:|---|
| Original Windows baseline | 977 | 869 | 27 | 80 | 1 | Failed |
| First C4.1 full suite | 997 | 958 | 1 | 35 | 3 | Failed; revealed three remaining broad-ACL navigation fixtures and two new unsupported-code presentation/test mismatches |
| Second C4.1 full suite | 997 | 961 | 0 | 33 | 3 | Failed; two additional modal-count errors in navigation when run with earlier suites |
| Final C4.1 full suite | 997 | 960 | 3 | 31 | 3 | Failed; navigation fixtures now pass, three mascot assertions failed |

Final complete run: 133 suites, Maven exit 1, 2m07s, completed 2026-10-09 18:45:40 -03:00. Its results were captured before any subsequent isolated diagnostic overwrote Surefire files: [new suite summary](c4-1-suite-summary.json), [new case-level failures/traces](c4-1-test-failures.json), local full log `c4-1-regression-review.log`. Compared with the original baseline: +91 PASS, -24 FAIL, -49 ERROR, +2 SKIP; total +20 (16 Windows storage tests, three Windows unavailable-Service tests and the separated legacy symlink case). The suite still fails. Latest compile and focused logs are `c4-1-compile-review.log` and `c4-1-focused-review.log`.

Both required compile/focused sequences ran successfully. Latest `mvn -DskipTests compile`: SUCCESS, exit 0. Latest `mvn "-Dtest=StringsParityTest,PackageC1CatalogTest,NavigatorTest" test`: 18 PASS (3/11/4), exit 0. No profile changed and no platform failures were suppressed. The two unsupported-code mismatches were corrected: authority still denies login with an explicit Windows code, and notification presentation maps confirmed unsupported Service to OFFLINE rather than UNCERTAIN. No authorization was granted by these changes.

The second full run exposed an additional fixture dependence: `Settings.FILE` is a static path resolved on its first class load. Changing `user.home` in a later suite does not relocate it. The navigation fixture's intended `onboardingCompleted=true` was therefore not reliably loaded, opening an extra welcome modal before its own test dialog (`expected 1, was 2`). The test now explicitly supplies its already intended non-authority post-onboarding UX state before startup. Production settings, onboarding, modal/palette gates and mandatory navigation assertions remain unchanged. The failed full-run evidence is retained in `c4-1-full-tests-final.log`.

Only three skips are intended: original `CaptureLiveObservationTest` requires the absent `capture.live` opt-in; `CaptureMonitorTest.nioStorageUsesMetadataAndDoesNotFollowSymlinks` and `LegacyPanelDbTest.aSymlinkedLegacyFileIsUnavailable` require Unix file-symlink capability unavailable to this ordinary account. The latter was separated from absent/corrupt legacy-file assertions. They remain active on macOS. Windows storage junction/hardlink security is covered separately; production Windows research observation and arbitrary legacy file-symlink qualification remain unavailable.

Remaining blocker: `ChainStatusClientTest` 7, `LocalServiceClientTest` 12 and `MarketFeedClientTest` 12 fail in unchanged POSIX `/tmp` fixture setup, before authenticated IPC/security assertions. They do not prevent the real public DEFAULT window opening, but their mandatory private IPC invariants have not been established on Windows. They must not be reported as passing or broadly skipped. Automatic approval review rejected a proposed command excluding whole platform-dependent suites because that would weaken required security coverage. That command did not run; those suites were retained active. The safer fixture adaptations and native storage tests were completed without those exclusions.

Additional final failures, all retained and not excluded:

| Case | Observed failure / classification | Impact |
|---|---|---|
| `MascotPresenceViewTest.idleFullRunsTheRigWithAliveBlinkingBreathingAndAnEyeThatFollowsTheCursorWithinLimits` | Line 110: gaze expected >0.8 and ≤1, observed 0.0. **Unknown** exact root cause; native focus/timing or suite state is suspected, not proven | Does not prevent observed public DEFAULT startup; blocks complete mascot qualification |
| `MascotViewTest.closingTheScreenMidAnimationReleasesEverythingAndLaterCallbacksDoNothing` | Line 170: cached images expected 0, observed 5. **Unknown** root cause; shared cache/lifecycle contamination may follow an earlier failed test | Resource disposal regression cannot be dismissed; authenticated mascot UAT unqualified |
| `MascotViewTest.fullMotionLoopsAndAdvancesFramesThenStopsToThePoster` | Line 70: same cache count mismatch, 0 versus 5. **Unknown** root cause | Same limitation; no claim of a verified regression fix |

These classes and their production mascot code were not modified. Both earlier complete C4.1 runs passed these classes. One bounded isolated reproduction passed all **19 tests / 0 FAIL / 0 ERROR / 0 SKIP**, Maven exit 0, 16.335s, recorded in `c4-1-mascot-diagnostic.log`. The observed result varies across runs; the exact focus/timing/shared-state cause remains unknown. This isolated pass does not replace the final full-suite failure counts. No unrelated mascot correction was attempted merely to raise the pass count.

## Real JavaFX DEFAULT smoke

Command: `mvn javafx:run`, unchanged entry `panel.app.Main`, DEFAULT source set, wallet capability DISABLED, no LOCAL_QA/wallet-qa profile, no injected providers. Actual Java application PID 12152 displayed `BYX-MVP — Login`; the Maven parent was PID 21940. The launch lasted about 3m25s including plugin dependency resolution and bounded observation. Normal WM_CLOSE of that window terminated its Java process and Maven returned **BUILD SUCCESS / exit 0**. No global Java process kill or Service launch was used.

New real state: `C:\Users\[QA-user]\AppData\Local\BYX-MVP\runtime.db`, 45,056 bytes at observation. [Native storage evidence](c4-1-default-storage.json) shows current-user owner, protected DACL, and exactly current account/SYSTEM/Administrators full control. This is non-auth schema/state; no secret or pairing state was created. State was retained, not destructively cleaned up.

| Area | Actual DEFAULT result |
|---|---|
| Startup/shutdown | Native JavaFX login displayed; normal close exited successfully |
| Service/Retry | Honest local-Service-unavailable banner; empty-credential Retry returned to the same unavailable login |
| EN/PT-BR | Selected Portuguese with the real ComboBox/keyboard; labels/banner/title changed to `Entrar`; selected English again |
| Keyboard/focus | Visible focus ring, Tab focus movement, Control+K did not expose an authenticated command palette from login |
| Public navigation | Login → FAQ → Help → Sign in worked; FAQ placeholder content was visibly identified by the existing product |
| Home, Trading Desk, account/settings, mascot, notifications, command palette | Blocked by Service-backed authentication; not accessed through synthetic login |
| DARK/LIGHT | Actual default dark appearance observed; authenticated theme switch unavailable. LIGHT not qualified in DEFAULT |
| Research, custody, signing, broadcasting | Unavailable/gated; no privileged capability exercised |

Actual PNG screenshots (unscaled pixel capture, not recreated screens):

- [1100×700 logical, English](c4-1-default-1100x700-en.png) and [Portuguese](c4-1-default-1100x700-pt-br.png): full outer window 1650×1050 physical pixels at 144 DPI/150%; [bounds metadata](c4-1-default-1100x700-en.json).
- [Retry and keyboard focus](c4-1-default-retry-and-keyboard.png), [public FAQ](c4-1-default-public-faq.png), [public Help](c4-1-default-public-help.png).
- [1920×1080 logical resize attempt, visible region only](c4-1-default-1920x1080-visible.png), [metadata](c4-1-default-1920x1080-visible.json).
- [1440×900 logical resize attempt, visible region only](c4-1-default-1440x900-visible.png), [metadata](c4-1-default-1440x900-visible.json).

The monitor is 1920×1080 **physical** pixels, 1280×720 logical pixels at 150% scaling; see [DPI audit](c4-1-dpi-audit.json). Windows clamped both larger resize attempts to an actual outer window of 1946×1106 physical pixels, and both captures contain only the visible 1920×1080 region. Their requested logical sizes were **not achieved** and their full layouts are **not qualified**. Filenames identify attempts, not successful resolution qualification. A larger effective desktop or a separately prepared 100%-scale UAT environment is required. OS display settings were not changed. The initial DPI-unaware, obstructed capture was corrected before delivery and is not used as evidence. PowerShell refused the temporary `.ps1` helper under its execution policy; native interactive commands were used without changing that policy, and the unused newly created helper was removed.

## Changed files and scope

Production, all within Panel:

| Class | Change |
|---|---|
| `security/WindowsStorage.java` (new) | Native SID/NTFS/DACL creation and validation, reparse-aware storage leases |
| `security/RuntimeStorage.java` (new) | Known-folder runtime location; legacy history retains original home location |
| `security/PrivateFiles.java` | Windows dispatch and explicit refusal of unqualified ACL tightening |
| `security/Database.java` | Windows lease ownership, callback validation, release at close |
| `app/AppContext.java`, `app/PanelApp.java` | DEFAULT runtime location and shutdown release; existing injected QA isolation preserved |
| `localservice/LocalServiceClient.java`, `localservice/ServiceLauncher.java` | Windows unavailable before secret read/IPC; no macOS helper execution |
| `notifications/ServiceNotificationObserver.java` | Native unsupported code presented as definite offline |

New test support: `WindowsStorageTest`, `WindowsServiceUnavailableTest`, `SecureTempDirFactory`, `ProcessTestChild`. Modified test classes: `PrivateFilesTest`, `LegacyPanelDbTest`, `RuntimeMigrationTest`, `LegacyIsolationProductTest`, `ByxWalletOwnershipTest`, `ResearchGateReproductionTest`, `PackageANavigationBoundaryTest`, `PackageC1AuthorityTest`, `PackageC3AppIsolationTest`, `AuthorityClientTest`, `BackendGatewayTest`, `GasSignerIsolationTest`, `ServiceChainGatewayTest`, `CaptureMonitorTest`, `CaptureRuntimeResolverTest`, `ScientificCaptureResolverTest`, `ShellComponentsTest`, `FinalSafetyTest`, `LegacyAuthDisabledTest`, `ChainOwnershipGuardTest`, `ServerAuthorizationBoundaryTest`. Assertions and test-only synthetic identities remain test fixtures; DEFAULT does not adopt them.

Git index remains empty; HEAD unchanged. No changes to `byx-local-service`, `byx-packaging`, signer/custody/research repositories, POM, profiles or approved UI layouts. QA history and original JSON evidence preserved. Logs are ignored by the existing repository rule but retained locally in this QA directory. New screenshots/JSON/reports and Java files remain untracked; existing modified Java files remain unstaged. Detailed final inventory is recorded in `c4-1-git-status.txt`.

## macOS and next milestone

Windows native libraries load lazily behind platform checks; macOS retains POSIX storage, legacy location and existing helper/IPC behavior. Test-only pathname and subprocess adaptations preserve their invariants on either OS. The new unsupported code mapping does not alter existing macOS codes. Actual macOS regression was **not executed**; rerun the approved macOS suite before accepting cross-platform preservation.

Next work must be separately scoped: qualify Windows-native authenticated IPC, pairing ownership/ACLs, peer/code identity and protected secret storage without altering Service authority or using macOS binaries. Do not introduce these capabilities merely to clear fixture failures. First review the native storage patch and complete the remaining mandatory security coverage; retain the 31 failing cases as evidence until a legitimate platform-equivalent design/qualification exists. Investigate native focus/pulse timing and shared mascot asset lifecycle using the three recorded failures, without weakening disposal assertions. Prepare a display capable of the two larger logical targets and repeat visual UAT. Installer packaging, Windows custody, Linux support and real trading are not started by this package.

Human UAT: use a normal Windows account with Java 21/Maven 3.9.9; review the unstaged diff, run the required compile/focused/full commands, and inspect native ACL/security results. Launch only `mvn javafx:run` in DEFAULT. Expect unavailable Service and no successful authentication. Exercise language, Retry, public FAQ/Help, keyboard focus and safe close; do not enter credentials or provide a fake session. Record larger-size layouts with DPI-aware measurements in a suitable desktop. Stop on any ownership/ACL/reparse error rather than repairing or relaxing security.

REAL USER KEY = NOT AUTHORIZED. REAL TX = DISABLED. BROADCASTS = 0. REAL FUNDS = 0. REAL USER WALLETS = 0. ETHUSDT RESEARCH = UNTOUCHED.

**BLOCKED_C4_1** — secure runtime storage and real public DEFAULT startup verified; mandatory private IPC/security qualification, the three final mascot failures and complete target-size UAT remain unresolved. Stop after C4.1.

## C4.1-R addendum — 2026-10-09

Latest status: **BLOCKED_C4_1_REGRESSION**. The preceding sections record the original C4.1 milestone; their historical logs, counts, failures and screenshots are retained. See [complete corrective report](windows-c4-1-regression-hardening.md) for the current inventory, all case outcomes, visual findings and macOS handoff.

C4.1-R changes only test source and QA evidence. WindowsStorage, RuntimeStorage, PrivateFiles, Database, LocalServiceClient and ServiceLauncher match their preflight SHA-256 hashes; both original C4.1 JSON result files also match. [Preservation check](c4-1-r-preservation-check.json). NTFS ownership/protected DACL, creation-time grants, no insecure fallback/repair, ancestor/file pinning and reparse/hardlink rejection remain unchanged. No production source, Service transport, signer, credential store, research gate or ETHUSDT pipeline changed in this correction.

| Current run | PASS | FAIL | ERROR | SKIP | Exit |
|---|---:|---:|---:|---:|---:|
| Required compile | n/a | n/a | n/a | n/a | 0 |
| Required StringsParity/C1/Navigator | 18 | 0 | 0 | 0 | 0 |
| Targeted ACL/IPC/mascot | 59 | 0 | 23 | 0 | 1 |
| Supported targeted layout/security/navigation/locale/theme/notifications/session | 178 | 0 | 0 | 0 | 0 |
| Mascot, ten independent 19-case runs | 190 | 0 | 0 | 0 | 0 each |
| **Required complete Maven suite (1018 cases, 135 suites)** | **992** | **0** | **23** | **3** | **1** |

Final complete run finished at 2026-10-09 19:14:45 -03:00, 2m26s. [Current suite summary](c4-1-r-suite-summary.json), [all outcomes](c4-1-r-test-results.json), [errors/skips with traces](c4-1-r-test-failures.json), [commands and exits](c4-1-r-run-results.json). Comparison with the original 960/3/31/3 result: +32 PASS, -3 FAIL, -8 ERROR, unchanged SKIP. The 21 added cases are four Windows denial tests, five portable parser tests and twelve public layout cases; no original case was removed and no new Windows skip was added.

The 31 IPC setup errors shared the nonexistent Windows `/tmp` fixture. Portable temp setup and null-safe cleanup allow eight original neutral/unavailable cases to execute. The remaining 23 cases (ChainStatus 3, LocalServiceClient 10, MarketFeedClient 10) now explicitly refuse the unqualified POSIX pairing fixture before secret/socket creation. They remain ERROR, not skipped or faked successful. The original macOS fixture body and short Unix path are retained. New Windows tests verify the unchanged real native-Service refusal, lack of authority/private data, strict-verifier non-invocation and absent chain/market data; portable parser tests do not establish authenticated IPC. Native kernel-bound peer/code identity and protected pairing-secret lifecycle require a separately approved security milestone. No Service port was attempted.

The three mascot failures were test-clock/event ordering and failed-assertion cleanup problems. Manual clock now precedes scene attachment; pointer input and ticks share one FX transaction; AfterEach disposes mounted views/stages even after assertions fail. Production mascot and all strict animation/cache assertions remain unchanged. Ten fresh JVM runs (190 executions) and the final complete suite observed no intermittent failures. No arbitrary sleeps, timeout increases or animation suppression were added. [Repetition evidence](c4-1-r-mascot-repeats.json).

Actual DEFAULT `mvn javafx:run` opened native public login/FAQ/Help again as Java PID 9856, changed EN/PT-BR through the actual control, retained unavailable Service after Retry, showed keyboard focus, and denied authenticated palette access. Normal WM_CLOSE stopped its own process; Maven exit 0, 1m46s, finished 19:16:45 -03:00. No credentials or fake session were supplied. [Native startup log](c4-1-r-default-startup.log). Actual DARK was observed; actual LIGHT/authenticated screens remain unqualified.

The display is 1920x1080 physical at 144 DPI/150%. The 1100x700 logical outer window fits at 1650x1050 physical. Requests for 1440x900 and 1920x1080 logical both constrain to 1946x1106 physical; captures show only 1920x1080 and are explicitly clipped, not proof of requested-size UAT. Twelve native offscreen public Scene cases at exact dimensions, EN/PT-BR and DARK/LIGHT produced 36 screenshots with denied auth/persistence; [layout matrix](c4-1-r-layout/README.md). These component layouts are separate from physical DEFAULT and exclude window decorations.

Two physical minimum-size public visual findings remain: Help action text is ellipsized in both languages; expanded access guidance partly overlaps the Sign in heading with the language selector. Screenshots and precise follow-up scope are in the corrective report. Home, Trading Desk, authenticated mascot/account/notifications/dialogs remain inaccessible without legitimate Service authorization. No gate was bypassed to claim that coverage.

Current Git branch and HEAD remain feature/byx-windows-readiness-v1 / 625689b2021c4c6a5f4a409d5d4680bccc865cbc, index empty, no commit/push. [Current Git status](c4-1-r-git-status.txt). macOS execution is pending and not claimed; the corrective report lists portable/platform-specific tests, preserved invariants and exact native macOS commands/repetitions. Do not push before required cross-platform review and owner approval.

REAL USER KEY = NOT AUTHORIZED. REAL TX = DISABLED. BROADCASTS = 0. REAL FUNDS = 0. REAL USER WALLETS = 0. ETHUSDT RESEARCH = UNTOUCHED.

**BLOCKED_C4_1_REGRESSION** — required suite still errors; native IPC security, physical larger-size UAT, public layout findings and macOS review remain open. Stop after corrective QA; no packaging, custody, Service port, Linux or live trading.
