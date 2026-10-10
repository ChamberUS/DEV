# C4.2-A — portable secure IPC contracts and fail-closed tests

2026-10-10, America/Sao_Paulo. **C4_2_A_PORTABLE_IPC_CONTRACTS_PASS** for the isolated portable foundation and Windows regression described below. This is not a native IPC or cross-platform release qualification.

**WINDOWS_APP_IDENTITY_NOT_PROVEN**. **WINDOWS_AUTHENTICATED_SERVICE_DISABLED**.

## Initial state and prior diagnostic

Git root `C:\src\DEV`; branch `feature/byx-windows-readiness-v1`; expected and actual initial HEAD `66845a3b8df0e0522857a13a532eafc882c874e1`; remote `https://github.com/ChamberUS/DEV.git`. Tracked worktree/index initially clean; **592 untracked files**, including the prior blocked C4.2-A report. All existing evidence was hash-inventoried before editing. No clean/reset/stash/rebase or unrelated edits.

The 2026-10-09 preflight found two real discrepancies in the Panel reader: trailing JSON inside a frame and invalid UTF-8 were accepted. The previous report is preserved verbatim at `C:\src\BYX-artifacts\windows-c4-2-a\implementation-20261010-004243\previous-blocked-report.md`; its diagnostic evidence stays in the earlier preflight directory. The owner subsequently requested continuation. This resumed milestone makes the narrowly scoped compatible parser correction permitted by the assignment, then implements the isolated contracts. It does not weaken the security plan to accommodate permissive parsing.

## Reviewed architecture and reuse

Reviewed Windows Secure IPC RFC, C4.2 implementation plan, App Control diagnostic, C4 checkpoint report, final app-image UAT and current Panel/Local Service/test source. No native API bridge, Service production composition or packaging change is made.

- Existing `LocalServiceClient.read`/`send` framing and constants are reused directly in tests: four-byte big-endian length, ordinary cap **8192 bytes**; market-event cap **16384 bytes** remains unchanged. Size validated before body read/allocation; minimum valid JSON object `{}` is two payload bytes. Multiple separately framed messages remain legal.
- Service `Frames`/`Protocol.mapper()` remain unchanged. The Service already enables trailing-token refusal. Service UTF-8 conversion requires a separate audit; this milestone does not claim it was corrected or qualified.
- Existing Panel/Service `PeerVerifier` is SocketChannel/native-oriented. It is preserved rather than refactored or mapped to fabricated evidence. The new richer pure decision algebra has no adapter or registration into it. MacSecurity audit token, PID+pidversion, code requirement/seal and environment checks remain unchanged.
- Existing Pairing HMAC-SHA256, directional labels and both nonces remain unchanged. Nonce/proof freshness, secret provisioning, native identity and account/session authorization are separate responsibilities. No crypto implementation, weaker algorithm, real token or new pairing provider.
- Existing `MarketFeedClient` lifecycle generation and strictly increasing per-stream sequence semantics are modeled in isolation; production client is not replaced. SessionStore peer/account/credential binding is not changed or reimplemented.
- Windows `native_service_unsupported` still rejects before token read or IPC open. Existing ACL adaptations and the finalized public app-image remain unchanged.

## New contracts and boundaries

`panel.ipc.contracts` contains three small pure Java classes/interfaces, no native dependencies or production registrations:

| File | Contract and limits |
| --- | --- |
| `MessageTransport.java` | Proposed bounded send/receive, monotonic deadline, cancel/drain, idempotent close contract; no implementation. Protocol compatibility predicate reuses existing `SUPPORTED_PROTOCOL`. Connectivity/version compatibility never authenticates a peer |
| `IdentityGate.java` | Immutable evidence observations, explicit verifier result, fixed-code endpoint identity prerequisite decision; statuses VERIFIED, UNSUPPORTED, MISSING, INVALID, STALE, UNTRUSTED_APPLICATION |
| `GenerationFence.java` | Synchronized positive lifecycle generation, restart invalidation, strictly increasing per-stream events, terminal close/overflow refusal; no Service session issuer or native process key |

Only an explicit VERIFIED result from a separately trusted verifier, bound to the exact supplied evidence and current generation with a future expiry, satisfies the **identity prerequisite**. Null verifier -> UNSUPPORTED; missing evidence -> MISSING; malformed/throwing/null result -> INVALID; old generation or expired result -> STALE. Non-VERIFIED results always deny. USER/SID/PID/JAVA_EXECUTABLE/path/files/CONNECTED observations never supply app identity. No Windows verifier exists here. Test-only verifier lambdas are synthetic outcome controls and are never registered in production.

The evidence's instance binding is opaque, not a new attestation or PID substitute. Time uses deterministic nonnegative monotonic elapsed ticks in one verifier/gate domain, not wall clock. Decisions are snapshots, not reusable capabilities; any future caller must recheck lifecycle at use and independently prove pairing, user session and Service authorization. A VERIFIED unit-test result does not grant an operation, prove a real BYX process or create an authenticated session. The production endpoint decision/authority integration remains unavailable.

Generation tests use synthetic lease/response generation values, not real sessions. Current generation can match; older/future/zero/negative values cannot. Restart invalidates captured leases/responses, resets stream sequence, and rejects old callbacks/events. Duplicate/decreasing/nonpositive events cannot advance state. Close is idempotent and rejects current work. Counter exhaustion closes the fence instead of wrapping to a reusable generation. Production restart behavior is not exercised or newly claimed.

## Essential parser correction

Only existing production behavior changed: the Panel rejects malformed UTF-8 rather than replacing it, and rejects extra JSON/garbage **within a declared frame** rather than ignoring it. It uses a UTF-8 decoder with REPORT and the existing Jackson mapper with FAIL_ON_TRAILING_TOKENS; refusal retains `INCOMPATIBLE / contract_violation`.

Valid UTF-8 Unicode, whitespace, ordinary maximum-size frames, wire length/endianness, error size bounds, HMAC and supported protocol remain unchanged. A following valid frame is not considered trailing JSON. Frame parsing does not independently authorize or validate a handshake; version checks remain the caller/handshake responsibility. The portable compatibility predicate rejects unsupported version integers without simulating an authenticated handshake.

No unapproved runtime refactor, second framing implementation, authentication fallback or hidden protocol version change. The stricter shared Panel parser requires fresh macOS regression before cross-platform qualification.

## Tests and exact results

Windows 11 x64, Temurin Java **21.0.12.1**, Maven **3.9.9**, DEFAULT source composition. Maven runs offline (`-o`) using existing dependencies; no network or new dependency installation. JAVA_HOME set only in test processes. No auth/IPC bypass profile.

| Run | Suites / cases | PASS | FAIL | ERROR | SKIP | Exit |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Panel compile | 365 Java source files, release 21 | BUILD SUCCESS | — | — | — | 0 |
| Focused contracts/framing | 3 suites / 25 cases | 25 | 0 | 0 | 0 | 0 |
| Selected existing regression | 6 suites / 47 cases | 47 | 0 | 0 | 0 | 0 |
| Full Panel | 138 suites / 1054 cases | 1028 | 0 | **23** | **3** | **1** |

Focused: IdentityGateTest 7, GenerationFenceTest 6, IpcProtocolContractTest 12. Twenty new cases overall versus baseline (13 new identity/generation + 7 framing cases). New framing tests exercise actual production parsing: min/max, oversize/unread body, malformed signed length, empty/incomplete header, truncated body, trailing JSON/garbage, malformed/overlong/surrogate UTF-8, valid Unicode/whitespace and successive frames. Existing malformed/object/market-value/immutable-book tests are preserved. Version compatibility is independently tested for -1, 0, 1, 2 and MAX_INT; no native handshake qualification implied.

Identity tests cover every non-verified status, unsupported/missing verifier/evidence, irrelevant observations, invalid/stale generation before verifier call, expiry boundary, substituted binding, null/throwing verifier, immutable observations and restart without new identity proof. Generation tests cover captured lease/response invalidation, replay/sequence behavior, invalid generation, close and exhaustion.

Selected regression: WindowsServiceUnavailableTest, AuthenticationEpochTest, SessionReturnTest, StringsParityTest, PackageC1CatalogTest and NavigatorTest. The original Windows guard/private-session denial tests still pass.

Commands from repository root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$mvn = 'C:\src\tools\apache-maven-3.9.9\bin\mvn.cmd'
& $mvn -o -f mvp-binance-panel/pom.xml -DskipTests compile
& $mvn -o -f mvp-binance-panel/pom.xml '-Dtest=IdentityGateTest,GenerationFenceTest,IpcProtocolContractTest' test
& $mvn -o -f mvp-binance-panel/pom.xml '-Dtest=WindowsServiceUnavailableTest,AuthenticationEpochTest,SessionReturnTest,StringsParityTest,PackageC1CatalogTest,NavigatorTest' test
& $mvn -o -f mvp-binance-panel/pom.xml test
```

Full run took **02:41 min** and is intentionally **BUILD FAILURE**, not green. All 23 ERROR class/method identities and exact IOException messages match the baseline: `native_ipc_fixture_unqualified: POSIX pairing and native peer identity required; no Windows substitute`. Zero unexpected error cases, zero assertion failures. Classification remains POSIX fixture dependency / missing qualified Windows native transport and peer identity, not proven assertion success. No weakening, exclusions or new skips. The three existing skips are live observation opt-in plus two existing Windows OS restrictions. The original 1008 PASS rises to 1028 solely from 20 added cases.

One initial Maven invocation at the monorepository root failed with MissingProjectException (wrong command working location), before compilation. It was corrected with `-f mvp-binance-panel/pom.xml`; the failed invocation log is preserved. First focused run had 24 PASS before the version case was added; the final run above has 25 PASS. These operational/intermediate results are not hidden or counted as final product failures.

Evidence outside Git: `C:\src\BYX-artifacts\windows-c4-2-a\implementation-20261010-004243`, including compile/focused/selected/full logs, snapshot XML reports, test-results.json, baseline comparison, preserved prior report, untracked manifest and final preservation/commit receipt. No raw logs, absolute manifests, screenshots or compiled artifacts are staged. The final documentation-only Javadoc clarification after testing does not change executable behavior.

## File inventory, preservation and checkpoint

Exactly eight reviewed files in scope:

1. Modified `src/main/java/panel/localservice/LocalServiceClient.java`.
2. New `src/main/java/panel/ipc/contracts/MessageTransport.java`.
3. New `src/main/java/panel/ipc/contracts/IdentityGate.java`.
4. New `src/main/java/panel/ipc/contracts/GenerationFence.java`.
5. Modified `src/test/java/panel/localservice/IpcProtocolContractTest.java`.
6. New `src/test/java/panel/ipc/contracts/IdentityGateTest.java`.
7. New `src/test/java/panel/ipc/contracts/GenerationFenceTest.java`.
8. Updated this report (`docs/qa/package-c4/C4_2_A_PORTABLE_IPC_CONTRACTS_REPORT.md`).

All paths above are under `mvp-binance-panel`. No POM/dependency, Service, macOS identity, security storage, packaging, wallet, signer, custody, collector or supervisor changes. Prior untracked report is intentionally updated and its original archived; the other **591 pre-existing untracked files** remain byte-identical. Of 857 historical source references, only the two existing files in this scope change; new files are separately inventoried. Exact-path staging and full diff/privacy/whitespace review precede the authorized local commit. Final commit SHA is in the task response/local receipt, not self-embedded. No push/merge/release. Generated target/build evidence stays untracked/ignored outside the commit.

## macOS handoff and next gate

No macOS execution occurred. Earlier owner-reported **998 PASS, 0 FAIL, 0 ERROR, 25 SKIP** applies to the earlier checkpoint, not this change. New contracts are JDK-only, tests use deterministic in-memory data and no OS/native libraries, but portability is not a claimed macOS test result.

On an authorized macOS Java 21 checkout of this exact local checkpoint after separately authorized handoff, run Panel compile, IdentityGateTest/GenerationFenceTest/IpcProtocolContractTest, affected LocalServiceClientTest/ChainStatusClientTest/MarketFeedClientTest and full Panel regression; retain existing real macOS signed-peer/audit-token/seal/env/POSIX tests and validate Service interoperability. Valid product messages and HMAC must remain compatible. Audit the Service UTF-8 decoder independently rather than treating Panel correction as a Service fix.

Next isolated milestone: review the portable interfaces and close the shared-parser macOS/Service audit, then qualify an independently authorized native research environment before any Named Pipe adapter. Native transport, channel-to-live-process binding, same-user-resistant BYX composition identity and app-restricted secret provisioning remain BLOCKED/unproven. No native harness run or privileged Service integration follows automatically from this portable PASS.

REAL USER KEY = NOT AUTHORIZED; REAL TX = DISABLED; BROADCASTS = 0; REAL FUNDS = 0; REAL USER WALLETS = 0; ETHUSDT RESEARCH = UNTOUCHED. Smart App Control, PowerShell policy and Defender unchanged. STOP after this checkpoint.
