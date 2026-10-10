# C4.1 implementation update — 2026-10-09

Current status: **BLOCKED_C4_1**. Historical C4 diagnostic material below is preserved; its statements about no source changes and no native launch describe those earlier diagnostic passes.

The authorized C4.1 implementation is on local branch `feature/byx-windows-readiness-v1`, unchanged C3 HEAD `625689b2021c4c6a5f4a409d5d4680bccc865cbc`. Temurin Java `21.0.12.1`, Maven `3.9.9`, native Windows 11 Pro build `26200`, x64/NTFS were verified. Compilation and the required focused 18-test set passed. The original 977-test baseline and its 107 failures/errors are preserved and classified in the [C4.1 implementation report](windows-storage-implementation.md).

Windows runtime storage now uses real current-token SID ownership, protected restrictive ACLs at native creation, raw ACE validation, NTFS/reparse/hardlink checks and pinned database paths. Existing POSIX behavior remains in place. The reinforced targeted run passed 66 tests without failures/errors/skips, including 16 native Windows storage cases, nine PrivateFiles cases and three unavailable-Service cases. No ACL relaxation, secret-storage fallback or authorization bypass was introduced.

The real `mvn javafx:run` DEFAULT application displayed its login window, switched EN/PT-BR, exercised unavailable-Service Retry and public FAQ/Help, and exited normally with Maven success. Authentication-dependent Home/Trading Desk/settings/themes/notifications/mascot remain gated; Service/custody/signing/broadcast are unavailable. [Actual login screenshot](c4-1-default-1100x700-en.png) and [Portuguese screenshot](c4-1-default-1100x700-pt-br.png) show the full 1100×700 logical window at 150% DPI scaling. Larger logical resize attempts were clamped/clipped by the native desktop and do not qualify those target layouts.

Final full suite: **997 tests / 960 PASS / 3 FAIL / 31 ERROR / 3 SKIP**, 133 suites, Maven exit 1. See [new suite summary](c4-1-suite-summary.json) and [case-level failures](c4-1-test-failures.json); the original JSON evidence remains unchanged. Three final failures concern mascot gaze/resource disposal and remain unqualified. The 31 unchanged POSIX Service IPC fixture errors remain active; their security assertions have not been qualified on Windows. Whole-suite Windows exclusions were rejected by automatic approval review and were not applied. macOS regression was not executed here. No commit/push or changes outside Panel were made; ETHUSDT research remains untouched.

---

# Preserved C4 diagnostic history

# Package C4 — Windows native baseline

Diagnostic date: 2026-10-09 (America/Sao_Paulo).

Final status: **C4_WINDOWS_BASELINE_BLOCKED**.

## Current result — resumed diagnostic

Resumed on 2026-10-09 using the prepared Windows toolchain. This section supersedes the historical environment/build results below. Native compilation succeeds and the focused tests pass. The full suite fails on Windows. The real DEFAULT application cannot reach stage creation through its current storage path; no standalone startup or UI smoke was attempted after that blocker was confirmed. Windows compatibility remains unqualified.

### Environment and source verification

- Host remains Windows 11 Pro x64, CIM build `26200`, NTFS on C:.
- `mvn -version`: **Apache Maven 3.9.9** (`8e8579a9e76f7d015ee5ec7bfcdc97d260186937`), home `C:\src\tools\apache-maven-3.9.9`.
- Maven JVM: **Java 21.0.12.1**, Eclipse Adoptium/Temurin, runtime `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`; architecture `amd64`.
- Persistent user JAVA_HOME points to that JDK; machine JAVA_HOME points to the same directory with a trailing backslash. User PATH contains Maven 3.9.9 and JDK 21; machine PATH contains JDK 21 and native Git.
- The already-running Codex session retained its old empty JAVA_HOME/PATH. Each Maven command process reloaded user JAVA_HOME and concatenated the persisted user and machine PATH. No persistent environment changes or alternate tool versions were used.
- Branch: `feature/byx-ui-redesign-v1`; HEAD reconfirmed as **`625689b2021c4c6a5f4a409d5d4680bccc865cbc`**. Initial status contained only the existing untracked baseline report. Tracked source and index were unchanged.
- JavaFX **21.0.5 Windows** classifiers for controls, graphics and base were downloaded successfully from Maven Central. Native component tests initialized the Windows Glass toolkit (`com.sun.glass.ui.win.WinApplication._runLoop` appears in executed traces). This establishes toolkit feasibility, not successful DEFAULT application startup.
- No LOCAL_QA or wallet-qa profile was selected. `TxLabBuild.available()` remains false and the POM wallet capability remains `DISABLED`.

### Executed build and tests

All commands ran from `C:\src\DEV\mvp-binance-panel`. Full output was retained through `Tee-Object`; console truncation does not truncate the log files. No added test exclusions, skip flags or security modifications were used.

| Command | Actual native Windows result | Evidence |
|---|---|---|
| `mvn -DskipTests compile` | Exit **0**, BUILD SUCCESS; **360 source files**, javac `release 21`; 15.284 s; finished 18:05:00 -03:00 | [compile log](windows-compile.log) |
| `mvn "-Dtest=StringsParityTest,PackageC1CatalogTest,NavigatorTest" test` | Exit **0**, BUILD SUCCESS; **18 passed, 0 failures, 0 errors, 0 skipped**; 12.465 s; finished 18:05:21 -03:00 | [focused log](windows-focused-tests.log) |
| `mvn test` | Exit **1**, BUILD FAILURE; **977 total, 869 passed, 27 failures, 80 errors, 1 skipped**, **131 suites**; 01:53 min; finished 18:07:33 -03:00 | [full log](windows-full-tests.log), [suite inventory](windows-suite-summary.json), [case failures and complete traces](windows-test-failures.json) |

Focused counts: StringsParityTest **3**, PackageC1CatalogTest **11**, NavigatorTest **4**, all passed. The full-run Surefire XML totals independently match Maven's terminal summary. XML remains in `target/surefire-reports`; JSON evidence preserves suite totals and every failing/error/skipped case from that run. Logs are local diagnostic artifacts and may be ignored by repository rules.

The sole skip is `CaptureLiveObservationTest`, with the existing condition `System property [capture.live] does not exist`. Live scientific observation was not enabled. Existing DEFAULT POM exclusions were retained, not broadened.

Compilation reported existing deprecated API and unchecked-operation notes. The suite reported the unnamed-module JavaFX warning, missing SLF4J provider and intentional invalid/missing Lottie fixture warnings. No missing Windows JavaFX native-library failure was observed. SQLite in-memory/component tests ran; runtime file storage remains blocked before SQLite opens it.

### Observed failures and classification

| Group | Actual findings | Classification / interpretation |
|---|---|---|
| Hard-coded `/tmp` | **42 errors**: ServiceChainGatewayTest 1, GasSignerIsolationTest 1, ChainStatusClientTest 7, LocalServiceClientTest 12, MarketFeedClientTest 12, PrivateFilesTest 9. `NoSuchFileException: \\tmp\\...` occurs during fixture setup | **Cross-platform source incompatibility** in test fixtures. Missing POSIX temp location is not repaired by creating a fake Windows `/tmp`. These errors mask later socket/POSIX checks |
| Unix shell fixture | BackendGatewayTest 1 error: `Cannot run program "/bin/sh" ... CreateProcess error=2` | **Cross-platform source incompatibility**, Unix-only dependency in the test; not a Java compiler failure |
| Mandatory POSIX storage | **29 errors** across ByxWalletOwnershipTest 1, PackageANavigationBoundaryTest 3, PackageC1AuthorityTest 4, PackageC3AppIsolationTest 6, ResearchGateReproductionTest 10, LegacyIsolationProductTest 4 and LegacyPanelDbTest 1 | **Cross-platform source incompatibility** and **security-related limitation**. Root cause: `WindowsFileSystemProvider.readAttributes` → `PrivateFiles.prepareDirectory:59` → `Database.openRuntime:35`, wrapped as `Could not open local database`. These are real startup/storage failures, not evidence that the tested auth flow ran |
| Migration preparation | RuntimeMigrationTest **5 errors**, each `RuntimeMigrator$MigrationException: prepare_failed` | **Unknown exact cause**, with strong source evidence of the same mandatory storage dependency. `prepare` catches SQLException/IllegalStateException and replaces their cause with a fixed code; the emitted trace cannot prove which inner exception occurred. No migration or security bypass was added to expose it |
| Symbolic-link creation | **2 errors**: CaptureMonitorTest and LegacyPanelDbTest; Windows reports `O cliente não tem o privilégio necessário` from `createSymbolicLink` | **Security-related limitation** / **environment/configuration issue** of the test account. No administrator relaunch, privilege grant or weakened link assertion was attempted |
| Source/class inventory separators | **9 failures**: ChainOwnershipGuardTest 1, FinalSafetyTest 3, LegacyAuthDisabledTest 2, LegacyIsolationProductTest 1, ServerAuthorizationBoundaryTest 2 | **Cross-platform source incompatibility** in inventory comparisons/allowlist filters. Actual strings contain `\\` while expectations contain `/`; the scanner consequently reports the existing DenyAll class as an offender. These failures remain failures; they do not establish that a permissive authorizer appeared |
| Capture observer identity/state | CaptureMonitorTest **4 failures + 1 wrapped assertion error**; CaptureRuntimeResolverTest **4 failures**; ScientificCaptureResolverTest **9 failures** | **Cross-platform source incompatibility**, inferred from inspected observer code and fixtures. Unix string suffixes `/bin/adaptive-trader` and `/continuous_capture.sh` do not match Windows Path strings. CaptureMonitor's root-relative synthetic path is compared with an absolute drive-qualified path. States degrade to STALE/UNKNOWN/STOPPED, and the expected animation loop is absent. No live ETHUSDT process/data was used |
| Shell shortcut fixture | ShellComponentsTest **1 failure**: expected `t-bot`, actual `t-desk` | **Cross-platform source incompatibility** in test input. Fixture sends Meta=true and Control=false (Cmd); product checks `isShortcutDown`, whose Windows shortcut is Ctrl. No product shortcut regression is established |

The 80 errors are fully accounted for as 42 temp-path, 1 shell, 29 storage, 5 migration, 2 symlink and 1 wrapped capture assertion errors. The 27 failures comprise 9 inventory, 17 capture-state and 1 shortcut failures. These classifications do not substitute for a passing rerun after implementation. No genuine regression has been established independently of these platform assumptions; migration's exact inner cause remains unknown.

### Safe DEFAULT startup: confirmed blocker and macOS dependencies

Actual application-based suite traces now confirm the previously inspected sequence: `PanelApp.init:91` → `AppContext` → `Database.openRuntime` → `PrivateFiles.prepareDirectory`. On existing temporary Windows homes the POSIX read throws UnsupportedOperationException; on a new home the implementation explicitly throws `InsecureStorageException("not_posix")`. Both paths precede `PanelApp.start` and stage creation. Some teardown traces also contain a suppressed null-context exception after init fails; that is secondary to the storage error.

`prepareFile` separately requires POSIX attributes at creation/read time; `tighten` and `audit` use POSIX permissions too. Replacing one directory check alone would not fix the policy. RuntimeMigrator uses Database.openRuntime and therefore needs the same secure storage adaptation. `runtime.db` stores non-auth runtime data; credentials remain in the Service. The legacy `panel.db` must remain immutable/read-only and must never be used as fallback authority.

Other inspected dependencies remain unresolved:

- Service pairing in LocalServiceClient validates owner and POSIX modes before Unix socket IPC. A working Windows socket transport alone cannot replace these guarantees.
- Panel/Service MacSecurity uses Security.framework and rejects non-macOS. Existing IdentityPolicy detects DEVELOPMENT_UNVERIFIED on failure; this cannot grant packaged identity or private privileges.
- ServiceLauncher only derives the helper within the macOS `.app` layout and uses POSIX creation and `/dev/null`. Maven/Windows execution has no valid bundled helper and must report Service unavailable.
- Service SecItemSecretStore depends on Data Protection Keychain. A Windows secret provider needs separate design/qualification; plaintext or generic credential fallback is unacceptable.
- Capture observation uses macOS/Unix process assumptions and `lsof`; Windows must honestly report unsupported observation without changing or starting the ETHUSDT pipeline.

No standalone `javafx:run` command was attempted: the real app's init failure was already observed in authorized tests, and the user explicitly requested diagnosis without circumvention. Home, full navigation, Trading Desk, EN/PT-BR, DARK/LIGHT, notifications and mascot are **not certified in a running DEFAULT app**. Component suites for settings/localization/themes, notifications, mascot and Trading Desk do run natively; their success does not substitute for the requested end-to-end smoke. PackageC3AppIsolationTest's six cases fail at context creation.

### Specific correction plan for C4.1 — not implemented

1. **Secure Windows runtime storage first.** Introduce an OS-specific policy behind PrivateFiles while preserving its public contract and existing POSIX implementation. The Windows implementation must validate the current user's SID/owner, reject unauthorized effective access and unsafe inherited ACLs, handle links/junctions/reparse points, and create the directory and file with restrictive access from their first observable creation. Refuse unsupported filesystems or unverifiable policy; do not create first and secure later. Review any required Windows native APIs before implementation.
2. **Cover the full SQLite lifecycle.** Apply that policy to runtime.db and `-journal`, `-wal`, `-shm`, including inheritance and replacements. Keep existing files unchanged unless an explicit migration/tightening action is approved. Preserve schema refusal, legacy filename refusal and immutable legacy history. Route RuntimeMigrator through the same policy, and qualify Windows drive/URI handling with read-only source hashes preserved. Add meaningful Windows ACL/security tests for owner mismatch, unexpected grants, inheritance, reparse points and auxiliary files; preserve macOS tests.
3. **Keep DEFAULT capabilities honestly unavailable.** Expose Windows support state without substituting authority/session success. Unavailable Service/peer verification/secure secrets must block login-dependent operations, custódia, trading and Research as required by existing gates. Do not use injected QA providers to qualify DEFAULT. Retain TxLab exclusion, wallet DISABLED and ServerAuthorization.DENY_ALL.
4. **Adapt diagnostics/tests without relaxing assertions.** Use native temporary directories, native bounded subprocess fixtures, canonical source-inventory path formatting and platform shortcut events. A controlled Windows test environment must support real link/reparse-point security tests; absence of privilege remains a recorded limitation, not permission to omit them. Capture observer portability requires a separately scoped review or explicit unsupported capability; do not change the ETHUSDT pipeline to satisfy UI tests.
5. **Separate private Service work.** Windows native helper packaging/lifecycle, authenticated local IPC, peer/code identity and protected secret storage need independent review and tests. No macOS binary/provisioning/signing asset may serve as a substitute. These capabilities may stay disabled for a truthful UI-only DEFAULT qualification.
6. **Requalify in order.** Repeat compile, focused tests and full suite after authorized changes. Then perform a bounded real DEFAULT startup using isolated local state, without synthetic authority or backend responses. Record actual access to Home/navigation and the requested UI options; protected routes must remain gated. Qualify successful shutdown and cleanup of only that launch's processes. Compilation/component tests alone cannot justify a Windows-compatible claim.

### Preservation and stop condition

This resumed pass only updates QA documentation/evidence and creates normal Maven output/cache artifacts. No application source, tests, POM or security policy was edited. No commits, pushes, Service binary launches, real keys/transactions/broadcasts, custody/trading enablement or live research operations were performed. Existing repository tests use their existing synthetic fixtures; no new fake backend or signer was supplied for startup. Final tracked/index diffs remain empty; QA files are the only untracked additions.

**C4_WINDOWS_BASELINE_BLOCKED** — environment/build blocker resolved; secure runtime storage, full-suite portability and real DEFAULT execution remain unresolved.

## Historical first diagnostic pass (superseded where noted above)

The initial pass found the toolchain unavailable in its session and could not compile or run tests. Its original evidence is retained below for provenance; those environment/build results are historical, not the current result.

## Host and toolchain

| Check | Observed result |
|---|---|
| Workspace / Panel | `C:\src\DEV` / `C:\src\DEV\mvp-binance-panel` |
| Operating system | Microsoft Windows 11 Pro; CIM version `10.0.26200`, build `26200` |
| Architecture | OS 64 bits; .NET runtime architecture `X64`; discovered JVM `amd64` |
| Filesystem | C: is NTFS |
| Shell | Windows PowerShell 5.1.26100.9444 |
| Git | `2.56.0.windows.2`, verified through `C:\Program Files\Git\cmd\git.exe`; unavailable as `git` on session PATH |
| Required Java 21 | Not found in inspected installation locations; not verified |
| Discovered Java | `C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\java.exe`: OpenJDK/Temurin `25.0.4.1+1-LTS`, 64-bit; not the requested Java 21 baseline |
| Required Maven 3.9.9 | Not found in inspected installation/download locations; not verified |
| Discovered Maven | `C:\Users\[QA-user]\Downloads\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd`: Apache Maven `3.9.16` (`2bdd9fddda4b155ebf8000e807eb73fd829a51d5`); not the requested Maven 3.9.9 baseline |
| JAVA_HOME | Empty in the original process, user environment and machine environment |
| Command resolution | `git`, `java` and `mvn` each initially produced `CommandNotFoundException` |
| JavaFX declaration | `pom.xml` declares `org.openjfx:javafx-controls:21.0.5`; compiler release is `21` |
| JavaFX resolution / natives | Unverified. Default `.m2\repository\org\openjfx` did not exist; no Panel `target` directory or Maven wrapper was found. No Windows JavaFX DLL was resolved or loaded |
| DEFAULT composition | `byx.panel.sources=build-default`, wallet capability `DISABLED`; LOCAL_QA was not activated |

Original session PATH:

```text
C:\Users\[QA-user]\.codex\tmp\arg0\codex-arg0bldXLO;C:\Program Files\Google\Chrome\Application;C:\WINDOWS\system32;C:\WINDOWS;C:\WINDOWS\System32\Wbem;C:\WINDOWS\System32\WindowsPowerShell\v1.0\;C:\WINDOWS\System32\OpenSSH\;C:\Users\[QA-user]\AppData\Local\Microsoft\WindowsApps;;C:\Users\[QA-user]\.vscode\extensions\openai.chatgpt-26.1007.21434-win32-x64\bin\windows-x86_64
```

Inspection covered standard Git, Java, Eclipse Adoptium, Microsoft, Apache, tools and Scoop locations, plus top-level Program Files and Downloads directories. Tool absence is scoped to these checks, not an exhaustive inventory of every disk. Maven 3.9.16's version was verified using its absolute path and a temporary, process-only JAVA_HOME pointing to JDK 25. That process ran `-version` only. No persistent environment configuration, installation, alternate-version build or dependency download was performed.

The sandbox command runner failed before starting with `helper_unknown_error: setup refresh had errors`. Local diagnostics subsequently ran with approved escalation. This runner issue is separate from application compatibility.

## Repository and C3 publication verification

| Check | Result |
|---|---|
| Origin | `https://github.com/ChamberUS/DEV.git` |
| Branch | `feature/byx-ui-redesign-v1` |
| Local HEAD | `625689b2021c4c6a5f4a409d5d4680bccc865cbc` |
| HEAD subject | `feat(panel): implement event-driven notification center` |
| Commit date | 2026-10-09 17:26:52 -0300 |
| Live published branch tip | `git ls-remote origin refs/heads/feature/byx-ui-redesign-v1` returned the identical SHA |
| C3 content | HEAD contains notification-center source/tests, event-source audit, test matrix and C3 QA evidence |
| Prior package history | C2 `1a35f0d` and C1 `08f6e02` appear immediately before C3 in local history; A/B publication history is also present |
| Initial working tree / index | Clean: `git status --short` returned no entries |
| Pre-report verification | `git diff --exit-code`, `git diff --cached --exit-code` and full porcelain status were clean |

The C3 implementation report describes its pre-publication macOS HEAD `1a35f0d`. It is historical evidence; this Windows audit independently confirms the newly published C3 commit `625689b`. macOS test counts and screenshots are not Windows qualification evidence.

## Compilation and test results

Both requested commands were executed in `C:\src\DEV\mvp-binance-panel`, sequentially and without output suppression.

| Command | Exact outcome | Classification |
|---|---|---|
| `mvn -DskipTests compile` | PowerShell runner exit `1`; `CommandNotFoundException`, `CategoryInfo: ObjectNotFound: (mvn:String) [], CommandNotFoundException`, `FullyQualifiedErrorId: CommandNotFoundException`. Maven never started; no compilation occurred | Environment/configuration issue |
| `mvn "-Dtest=StringsParityTest,PackageC1CatalogTest,NavigatorTest" test` | PowerShell runner exit `1`; same command-resolution exception. Maven/Surefire never started; none of the three test classes executed | Environment/configuration issue |
| `mvn test` | Not executed: the required successful compilation and focused tests prerequisite was not met | Blocked by environment/configuration issue |

Error text from each failed invocation:

```text
mvn : O termo 'mvn' não é reconhecido como nome de cmdlet, função, arquivo de script ou programa operável. Verifique a
grafia do nome ou, se um caminho tiver sido incluído, veja se o caminho está correto e tente novamente.
```

There are no JUnit totals or Surefire reports from this pass. Pass/fail/error/skip counts are unavailable, not zero-success totals. No failing platform test was skipped, excluded or reclassified as passing. No Java compiler failure, missing JavaFX native-library failure or genuine regression was observed, because execution never reached those stages.

## DEFAULT JavaFX feasibility

Result: **not launched; native execution and UI smoke unverified**. No `javafx:run` attempt was made because the required build toolchain is unavailable and inspection identifies an unconditional startup storage blocker.

The real path is `Main.main` → `Application.launch(PanelApp.class)` → `PanelApp.init` → `createContext` → `new AppContext`. AppContext immediately opens `runtime.db` through `Database.openRuntime`, before `PanelApp.start` can display the stage. `Database.openRuntime` always invokes `PrivateFiles.prepareDirectory` and `prepareFile`. These require POSIX attributes and permissions. A new directory on a filesystem without that attribute view explicitly throws `InsecureStorageException("not_posix")`; an existing directory still requires `PosixFileAttributes`. There is no Windows ACL implementation on this path. This is a source-derived startup prediction, not an observed JavaFX exception or a native launch result.

The existing Service launcher derives only a signed macOS `.app/Contents/Helpers/.../Contents/MacOS` helper; outside that bundle it cannot start a Service. Auth remains Service-backed and unavailable rather than locally emulated. Even after storage adaptation, Home/navigation and protected Trading Desk access must be assessed through the actual router and authorization gates; this pass grants no authenticated or Research access.

| Requested smoke coverage | Windows result |
|---|---|
| Home | Not exercised |
| Navigation | Not exercised |
| Trading Desk | Not exercised; authenticated access was not fabricated |
| EN / PT-BR | Not exercised |
| DARK / LIGHT | Not exercised |
| Notifications | Not exercised; no fabricated events or backend responses |
| Mascot | Not exercised |

## Platform blockers and failure classification

The following entries are inspection findings, not failed test results.

| Finding / evidence | Classification | Required adaptation |
|---|---|---|
| Missing requested tool versions, JAVA_HOME and PATH entries | Environment/configuration issue | Provision Java 21 and Maven 3.9.9 and configure command resolution; retain native Git |
| `panel/security/PrivateFiles.java` and `Database.openRuntime` require POSIX attributes before stage creation | Cross-platform source incompatibility; security-related limitation | Introduce equivalent fail-closed Windows ownership/ACL validation and secure file creation; do not remove storage checks |
| `panel/localservice/LocalServiceClient.java` uses UNIX sockets and POSIX owner/mode checks for pairing files and socket | Cross-platform source incompatibility; security-related limitation | Design authenticated Windows local IPC and pairing validation; evaluate transport availability separately from peer identity and ACL guarantees |
| Panel and Service `identity/MacSecurity.java` use `/System/Library/Frameworks/Security.framework/Security` and reject non-macOS hosts | macOS-only dependency; security-related limitation | A separately reviewed Windows code/peer identity model is required for private capabilities; development identity must never imply verified privilege |
| `panel/localservice/ServiceLauncher.java` assumes `.app` helper layout, POSIX file attributes and `/dev/null` | macOS-only dependency | Native Windows packaging/helper discovery and lifecycle management with preserved identity boundaries; unsupported Service stays unavailable |
| Service `secrets/SecItemSecretStore.java` requires macOS Data Protection Keychain; `SecretStores` has no insecure fallback | macOS-only dependency; security-related limitation | Review a native Windows protected-secret provider separately; keep private operations disabled until equivalent guarantees exist |
| Capture runtime resolver invokes `/usr/sbin/lsof`; system motion probe reads macOS preferences; some account text says “Mac” | macOS-only dependency | Make unsupported probes explicit and localize platform copy; do not alter the ETHUSDT research pipeline |
| Tests/fixtures including `GasSignerIsolationTest`, `FakeService`, `FakeMarketService` and `LocalServiceClientTest` assume POSIX permissions and/or UNIX sockets | Cross-platform source incompatibility (potential test blocker) | Run unchanged tests once toolchain is ready, record actual failures, then adapt fixtures/security assertions without weakening guarantees |
| JavaFX 21.0.5 native Windows artifacts and SQLite native loading | Unknown until dependency resolution/native execution | Verify resolved Windows classifiers/DLLs and real toolkit/native loading; no missing-native failure has yet been observed |

No genuine regression can be established from this pass. macOS signing/provisioning assets were neither read for use nor executed. Service source was inspected only to identify native dependencies.

## Recommended first implementation changes

1. Restore the requested environment baseline first: Java 21, Maven 3.9.9, JAVA_HOME and PATH. Repeat the exact compile and focused commands, then the full suite if both pass. Preserve all actual Windows failures and their logs.
2. Make Panel runtime storage platform-aware with secure Windows ACL creation/validation, ownership checks and link/reparse-point handling. Keep macOS behavior and fail-closed enforcement. This is the first source change needed before a real DEFAULT stage can appear.
3. Add explicit Windows capability reporting for unsupported Service/identity/credential functions. Preserve DEFAULT transaction/wallet restrictions and normal authorization. A UI-only mode must expose unavailable capabilities honestly and must not inject synthetic authority, signers or successful backend responses.
4. Resolve and inspect native Windows JavaFX 21.0.5 dependencies; perform a bounded launch with isolated local state after startup blockers are addressed. Check the requested UI surfaces and record any authorization-blocked routes honestly. Stop only processes created by that smoke test.
5. Treat Windows Service transport, peer verification, protected secrets and packaging as separate security adaptations. Do not make them prerequisites for claiming a successful UI-only smoke unless that smoke actually requires them. Do not declare complete Windows compatibility from compilation alone.

These are recommendations for C4.1 implementation, not changes made in this diagnostic pass.

## Scope preservation

Only this QA document is added. No source edits, dependency/profile changes, test changes, commits, pushes, branch switches or source updates were performed. No macOS Service binary was started. No real keys, credentials, transactions, broadcasts, signer emulation, backend-response fabrication, credential-storage fallback or Research privilege bypass was used. The ETHUSDT research pipeline was not modified or started. No full-suite success or native Windows execution is claimed.

**C4_WINDOWS_BASELINE_BLOCKED**
