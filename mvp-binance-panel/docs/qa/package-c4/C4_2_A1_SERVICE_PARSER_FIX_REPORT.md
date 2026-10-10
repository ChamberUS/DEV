# C4.2-A1 — Strict UTF-8 Service decoder implementation

Date: 2026-10-10, America/Sao_Paulo.

**Milestone status: C4_2_A1_SERVICE_UTF8_FIX_BLOCKED** for unrestricted complete-Service regression. The narrow decoder correction and all executed focused, security, selected Service, full Panel and interoperability checks pass. No unexpected security regression was detected. The full Service gate is explicitly BLOCKED because its existing suites include real Keychain access and helper signing execution outside this task's restrictions; these tests were not weakened or counted as passing.

## Source identity and preflight

Initial source: **d3203f54126f73ea14e8b0d44a858c75ea4702ea**.
New isolated worktree: dedicated macOS QA worktree outside the development checkout.
Dedicated branch: `feature/byx-c4-2-a1-service-utf8`.
Remote: `https://github.com/ChamberUS/DEV.git`.

Destination and branch were unused before creation; approximately 81 GiB was free. The worktree was created from the exact C4.2-A commit with an initially clean index/tree. No original workspace branch was switched.

Read the prior C4.2-A1 audit, C4.2-A macOS report, ServiceInstance/Frames/Protocol, relevant socket/security/auth fixtures and Panel parser. Searched the complete Service production tree for byte-to-string/JSON/frame/channel input paths. The two affected Panel→Service endpoint conversions are the typed Hello/Auth `parse` and the post-pairing request tree in `handle`. Other String conversions found belong to HTTP, filesystem migration, secret-provider data and native identity metadata; custody has a distinct byte-based protocol. Those separate boundaries were not silently modified or newly qualified.

macOS 26.5.1 build 25F80, x86_64; Temurin Java 21.0.12.1+1-LTS; Maven 3.9.9. Builds/tests used offline Maven with the existing dependency cache, isolated test homes and documented native reflection opens. No dependency, profile or provisioning changes.

## Classification and exact correction

Original audit confirmed malformed UTF-8 replaced with U+FFFD before Service JSON parsing, while the Panel rejected it. Distinct invalid encodings could map to the same parsed field. Passing raw bytes to this Jackson version also did not guarantee strict UTF-8 validation. **No authentication bypass was demonstrated**; the correction enforces the protocol's stated input encoding rather than asserting an exploit.

Only production file changed: `byx-local-service/src/main/java/byx/service/ServiceInstance.java`.

A small package-private static `decodeUtf8(byte[])` uses StandardCharsets.UTF_8.newDecoder with REPORT for both malformed and unmappable input. CharacterCodingException is explicitly converted to JsonMappingException with fixed `malformed_utf8` text and no payload-bearing cause. Both previous String(frame, UTF_8) calls now use this helper before Jackson.

The existing JsonProcessingException branches send bad_request and return. Existing worker finally cancels subscriptions, removes the connection, releases its slot and closes the channel. No cleanup, watchdog, timeout or restart code changed.

Frames still validates size before body allocation/read. Four-byte big-endian framing, protocol 1, ordinary 8192 cap and separately defined 16384 market-event cap are unchanged. Service inbound requests remain capped at 8192; the larger market bound applies to outbound events. JSON duplicate/trailing/unknown-field/depth/string/number/coercion policies, HMAC-SHA256/directional labels/nonces, native macOS identity checks, session authority and operation allowlists are unchanged. No Panel production code changed.

## Before and after

| Input or boundary | Before | After / observed validation |
| --- | --- | --- |
| Invalid bytes in quoted values | String replacement could produce parseable JSON | Strict decoder rejects before JSON |
| Bad Hello nonce bytes | Could deserialize altered nonce and reach format check | bad_request, no challenge, close |
| Bad Auth proof bytes | Could reach proof format check | bad_request, no ready, close |
| Bad request op/password bytes | Could reach tree interpretation/downstream validation | bad_request before dispatch, close |
| ASCII and permitted Unicode text | Existing policies | Preserved |
| Valid UTF-8 U+FFFD | Valid encoding | Preserved; not banned as a character |
| Non-ASCII nonce/proof/op | Existing field/allowlist denial | Same semantic policy after successful decoding |
| Extra JSON/garbage in a frame | Rejected by existing mapper | Still rejected |
| Empty/oversized/truncated frames | Existing framing close behavior | Preserved |
| Independent sequential frames | Legal | Preserved |

## New test matrix and direct socket evidence

`ServiceUtf8ParserTest`: **13 cases**, pure Java. Six malformed sequences: isolated continuation 80, overlong C0 AF, truncated E2 82, surrogate ED A0 80, above-maximum F4 90 80 80 and invalid lead FF. It asserts fixed exceptions without payload-bearing causes. Positive cases preserve ASCII, accented/Japanese/emoji, legitimate U+FFFD and whitespace. Existing trailing-token constraints remain active. Exact ordinary/market framing maxima and oversize writes remain enforced.

`auth.ServiceUtf8SecurityTest`: **41 cases**, using the existing AuthFixture composition (fictional users/random passwords, temporary encrypted authority file, memory vault/anchor, fake second factor) and temporary AF_UNIX ServiceInstance fixtures. The injected development peer key is test-only and does not qualify native identity or a production user session.

- Six malformed sequences × three phases: **18**. Bytes are embedded in clientNonce, clientProof or operation values. The response must be fixed error/bad_request with no result/session; no additional byte may follow it before EOF/reset within two seconds. A pipelined later health request therefore cannot deliver a result. Malformed Hello cannot yield challenge, malformed Auth cannot yield ready, malformed requests cannot produce a dispatched response.
- Trailing JSON or non-JSON garbage × three phases: **6**, rejecting and closing.
- Empty/oversized/truncated bodies × three phases: **9**, closing through the original framing path.
- Valid Unicode or U+FFFD × three phases: **6**. Non-ASCII nonce remains bad_request; non-ASCII proof remains auth_failed (demonstrating decoding succeeds before original format rejection); unlisted Unicode op returns unsupported_operation and leaves the paired connection usable.
- Malformed password: **1**. A configured synthetic authority exists, but password-derivation count does not change and no fake second-factor delivery occurs. This guards against reaching auth.password application processing. No real user credential or authority is involved.
- Valid ASCII handshake and multiple sequential public requests/Unicode unknown operations: **1**, preserving health/version/capabilities and existing denial policy.

Each rejection waits for zero active connections and then fills **both configured connection slots** with legitimate paired clients and checks health for both, detecting semaphore leakage. Both clients then close and activeConnections returns to zero. Captured logs are checked for no raw authentication-field/payload markers or fixture user password. This is actual isolated socket evidence, not only a pure parsing hypothesis. No invalid frame advances pairing or creates an authority session.

## Actual regression results

Service compilation: exit 0, 7.94 s. All counts below come from archived per-run XML. Overlapping runs must not be summed as unique coverage.

| Run | Suites | Total | PASS | FAIL | ERROR | SKIP |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| focused-release | 2 | 54 | 54 | 0 | 0 | 0 |
| security | 4 | 30 | 30 | 0 | 0 | 0 |
| service-safe | 47 | 514 | 514 | 0 | 0 | 0 |
| panel-focused | 5 | 47 | 47 | 0 | 0 | 0 |
| panel-full | 138 | 1043 | 1018 | 0 | 0 | 25 |

Final focused run contains the complete matrix and stronger no-extra-byte/slot-release assertions. Intermediate executions are preserved: 48/48 before the six Unicode phase cases were added; 54/54 after adding those cases; 54/54 after strengthening slot recovery; final 54/54 after strengthening EOF checks. No product failure was hidden. The last changes were test assertions only; production code stayed identical across all regression runs. The final committed source matches the final focused candidate hashes (864 Panel/Service source/resource/test files).

Existing security selection: ServiceSecurityTest, ServiceSecretIsolationTest, AuthIpcTest, PeerIdentityTest — **30 PASS**. Includes original handshake proof/replay, framing/timeout, secret separation and isolated native macOS peer identity refusal tests.

Broad safe Service regression: **47 suites / 514 PASS**. The exact positive selection is recorded in service-safe-execution.json. It includes all ordinary test classes except SecretStoreTest and SignerClientTest; synthetic tx/wallet/custody-control tests use fake ports/processes or public test vectors and do not enable real custody, signing keys, accounts or broadcasts. This is a selected regression, **not a full Service suite claim**.

Full Service gate: **BLOCKED, not executed**. `SecretStoreTest.theRealBackendWithoutTheEntitlementFailsClosedAndCreatesNothing` invokes SecretStores.system and `/usr/bin/security find-generic-password` against the login Keychain (attributes, no value). This would violate this milestone's no-real-Keychain boundary. `SignerClientTest` builds/executes a synthetic signing harness and unavailable helper; that helper workflow was not initiated. Both classes are visibly excluded from the positive selection, not disabled/edited to force a PASS. Their unexecuted cases are BLOCKED gates, not artificial JUnit SKIPs. This prevents a complete-Service qualification claim.

Panel selection: IpcProtocolContractTest, LocalServiceClientTest, ChainStatusClientTest, MarketFeedClientTest, AuthorityClientTest — **47 PASS**. Full Panel: **1018 PASS / 0 FAIL / 0 ERROR / 25 SKIP**, unchanged from the approved C4.2-A macOS result. Expected skips: WindowsStorage 16, WindowsServiceUnavailable 7, WindowsPublicLayout disabled parameterized template 1, live-capture opt-in 1. All **23/23** previously Windows-failing IPC class/method pairs passed, with exact mapping in test-results.json. No live capture property was enabled.

External pure-byte interoperability controller exercised real Frames/Protocol.mapper, the corrected Service helper and real Panel read/send: **7 PASS / 0 FAIL**, protocol 1, limits 8192/16384; no Service process or session was started by that controller. Normal tested Unicode serialization/messages remain compatible. No UI source changed; the full Panel tests exercised JavaFX. No manual native application or duplicate production Service was launched in this task.

## Evidence and source control

Durable local evidence: owner-maintained isolated evidence archive, retained outside Git (created only after verifying its destination was absent). Contains execution commands/durations/logs, copied XML by run, test-results.json including 23-case mapping, candidate source hashes, scope/staged audit, interoperability helper/result, local commit receipt and workspace-protection.json. Raw logs, XML, compiled objects, sockets, tokens and runtime homes are not staged or committed.

Local implementation commit: **2aea5740664280d4e0d7ed035a3e57bf6f54b04f**.
Message: `fix(service): reject malformed UTF-8 in IPC messages`.
Parent/tested baseline: `d3203f54126f73ea14e8b0d44a858c75ea4702ea`.
Branch: `feature/byx-c4-2-a1-service-utf8`.

The commit has exactly three reviewed files:

1. Modified ServiceInstance.java (15 additions / 2 deletions; sole production change).
2. New ServiceUtf8ParserTest.java (54 lines).
3. New auth/ServiceUtf8SecurityTest.java (203 lines).

Fourth task deliverable: this report at `mvp-binance-panel/docs/qa/package-c4/C4_2_A1_SERVICE_PARSER_FIX_REPORT.md` was left uncommitted by the implementation milestone. The authorized handoff adds its sanitized version in a separate documentation-only commit, preserving the implementation commit unchanged. The original local report is archived outside Git. Generated public-layout QA images from existing Panel tests remain untracked in this new worktree and were excluded. No unrelated project file was staged. At implementation closeout the source/test commit was local only; no push, merge, release or packaging had occurred. This handoff authorizes an ordinary push of the dedicated QA branch only, after audit and with remote verification.

Reviewed every staged file and complete diff; whitespace checks passed. No credentials, Keychain state, developer identity/certificate, private key, real token, raw ETHUSDT data, production runtime state or packaged app output was introduced.

## Preservation and remaining gates

Original macOS development checkout HEAD/branch/status and 31 changed-file hashes remained intact. Prior C4 worktree status/HEAD and 289 hashes remained intact. Prior C4.2-A worktree HEAD/status and 272 existing report/evidence hashes remained intact, including the audit and macOS regression report. Collector and supervisor remained alive during final read-only checks. No process was signaled or production Service/signer/credential modified.

```text
REAL USER KEY = NOT AUTHORIZED
REAL TX = DISABLED
BROADCASTS = 0
REAL FUNDS = 0
REAL USER WALLETS = 0
ETHUSDT RESEARCH = UNTOUCHED
WINDOWS_APP_IDENTITY_NOT_PROVEN
WINDOWS_AUTHENTICATED_SERVICE_DISABLED
```

## Windows portability and next verification

The decoder and pure tests use standard Java 21 and existing Jackson only; no new native transport dependency. Windows portability is a source-level implication, not an executed Windows result. AF_UNIX/POSIX socket test evidence here qualifies macOS fixtures only. Native Windows IPC and application identity remain unimplemented/unproven.

After a separately authorized publication/handoff, verify the exact implementation SHA on Windows, compile both projects and run ServiceUtf8ParserTest plus Panel IpcProtocolContractTest/IdentityGateTest/GenerationFenceTest. Preserve valid Unicode/U+FFFD, frame bounds and trailing JSON denial. A full Windows Panel comparison requires separately authorized subsequent QA; this initial handoff is limited to the reviewed classes below and must preserve the known native-fixture blockers. New ServiceUtf8SecurityTest requires the authorized macOS POSIX fixture; Windows execution must be BLOCKED until a qualified isolated native transport/peer-identity fixture exists. Do not fake identity or enable authenticated Windows Service for this decoder milestone.

Recommended closeout: review this local patch and its passing affected tests; resolve full-Service QA authorization/provisioning boundaries in a separate approved safe setup before claiming complete-Service regression. No macOS decoder compatibility correction is currently indicated by executed results. No real-Keychain exception was inferred from earlier tasks. Do not start C4.2-B, native Windows IPC, custody, release or production deployment automatically. STOP after delivery.


## Published-branch handoff to Windows QA

This is source preservation and portable QA handoff only. Historical implementation status above remains BLOCKED for full-Service qualification. Focused parser/security/selected Service/Panel outcomes remain PASS; **full Service = NOT EXECUTED / OUT OF SCOPE**. No fresh regression was needed for documentation-only sanitation; executable source hashes match the tested candidate. Publication does not upgrade any test gate or declare Windows compatibility.

Remote: `https://github.com/ChamberUS/DEV.git`.
Branch: `feature/byx-c4-2-a1-service-utf8`.
Implementation: `2aea5740664280d4e0d7ed035a3e57bf6f54b04f`.
The documentation commit and exact verified final branch SHA are supplied in the publication receipt/final handoff. The report cannot self-embed its own commit SHA. Stop if the remote tip differs from that receipt.

### Reviewed initial Windows-safe matrix

| Class | Expected cases | Dependencies and permitted evidence |
| --- | ---: | --- |
| ServiceUtf8ParserTest | 13 | Actual Service strict decoder, existing Protocol mapper and Frames over memory streams; no ServiceInstance construction, socket, account or native verifier |
| IpcProtocolContractTest | 12 | Actual Panel reader, framing/UTF-8/trailing JSON and typed market data in memory; no running market feed, UI, pairing or authority |
| IdentityGateTest | 7 | Pure decision algebra with synthetic outcomes; no production attestation/session or registered verifier |
| GenerationFenceTest | 6 | Deterministic generation/replay/lifecycle algebra; no Service or credentials |
| WindowsServiceUnavailableTest | 7 | Windows-only DEFAULT/strict denial before IPC, token creation or helper startup; temporary empty homes and no endpoint connection |

Expected selected count is **45** if all these classes execute unchanged on Windows. This is a prediction, not Windows evidence; record actual PASS/FAIL/ERROR/SKIP and Surefire exit codes. The Windows refusal test's DEVELOPMENT_UNVERIFIED label is inspected only to assert DEFAULT refuses; it is never accepted as production identity. Its strict-policy test must refuse before calling its verifier. Synthetic positive identity-gate outcomes are isolated algebra controls and do not grant a session.

The portable decoder tests prove malformed bytes raise a fixed exception before JSON; production source places that check before handshake/request interpretation. Windows refusal tests prove the unsupported native boundary cannot acquire a session or authorize operations. These are distinct assertions: **end-to-end malformed-wire dispatch/cleanup proof on Windows remains BLOCKED**, because ServiceUtf8SecurityTest uses AF_UNIX/POSIX and an injected test peer. Its 41 macOS passes are preserved as macOS evidence only. Do not port that fixture with development-unverified identity to claim native Windows authorization.

Do not execute ServiceUtf8SecurityTest, ServiceSecurityTest, ServiceSecretIsolationTest, AuthIpcTest, PeerIdentityTest or the 23 native-dependent Panel IPC cases in this initial Windows selection. Do not edit/exclude them from their original suites or assign artificial SKIPs. Their known Windows/native blockers remain documented. Do not run unrestricted Service/Panel suites for this handoff, real Keychain tests, signer helper, custody, real credentials, production endpoints or authenticated Service. No environment/profile override may simulate a supported native transport.

### Exact isolated checkout procedure (PowerShell; for the Windows QA owner)

Configure Temurin 21 JAVA_HOME/PATH and Maven 3.9.9 first. The existing Windows repository root is `C:\src\DEV`; its current branch and tracked/untracked work must stay intact. Use a new detached worktree `C:\src\BYX-C4-2-A1-QA`. Confirm sufficient free space with Get-PSDrive before creating it. The following is a procedure to execute later, not an execution performed on macOS.

```powershell
$repo = 'C:\src\DEV'
$qa = 'C:\src\BYX-C4-2-A1-QA'
$branch = 'feature/byx-c4-2-a1-service-utf8'
$expected = '<FINAL_REMOTE_SHA_FROM_PUBLICATION_RECEIPT>'
if ($expected -notmatch '^[0-9a-f]{40}$') { throw 'Supply the verified final published SHA.' }
if (Test-Path $qa) { throw 'QA destination already exists; do not overwrite it.' }
Get-PSDrive C
$root = (git -C $repo rev-parse --show-toplevel)
if ($LASTEXITCODE -ne 0 -or ($root -replace '/', '\') -ne $repo) { throw 'Unexpected Git root.' }
$remote = (git -C $repo remote get-url origin)
if ($LASTEXITCODE -ne 0 -or $remote -ne 'https://github.com/ChamberUS/DEV.git') { throw 'Unexpected remote.' }
git -C $repo status --short
git -C $repo worktree list
git -C $repo fetch origin "refs/heads/${branch}:refs/remotes/origin/${branch}"
if ($LASTEXITCODE -ne 0) { throw 'Fetch failed; do not reset/rebase.' }
$tip = (git -C $repo rev-parse "refs/remotes/origin/$branch").Trim()
if ($LASTEXITCODE -ne 0 -or $tip -ne $expected) { throw 'Published SHA mismatch; stop.' }
git -C $repo worktree add --detach $qa $expected
if ($LASTEXITCODE -ne 0) { throw 'Worktree creation failed.' }
git -C $qa merge-base --is-ancestor '2aea5740664280d4e0d7ed035a3e57bf6f54b04f' HEAD
if ($LASTEXITCODE -ne 0) { throw 'Implementation is not an ancestor; stop.' }
git -C $qa status --short
java -version
mvn.cmd -version
```

Verify JAVA_HOME actually resolves Temurin 21 and Maven reports 3.9.9/Java 21 before continuing. Expected Panel path in the isolated worktree: `C:\src\BYX-C4-2-A1-QA\mvp-binance-panel` (canonical application path in the original clone is `C:\src\DEV\mvp-binance-panel`). Never switch/reset the original checkout.

### Commands for the later authorized portable QA run

Use a fresh QA home and the existing Maven dependency cache only. Compile invokes no runtime service; exact test selection prevents native or privileged test execution. Check every exit code; an unexpected failure/error or skip is a gate, not a reason to edit tests. Dependencies may resolve through ordinary Maven; do not change dependencies/profiles to compensate for failures.

```powershell
$qaHome = Join-Path $env:TEMP ('byx-c42a1-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $qaHome -ErrorAction Stop | Out-Null
$cache = Join-Path $env:USERPROFILE '.m2\repository'
$common = @("-Dmaven.repo.local=$cache", "-Duser.home=$qaHome", "-DargLine=-Duser.home=$qaHome")
foreach ($project in @('byx-local-service', 'mvp-binance-panel')) {
    & mvn.cmd -f "$qa\$project\pom.xml" @common -DskipTests compile
    if ($LASTEXITCODE -ne 0) { throw "Compilation failed: $project" }
}
& mvn.cmd -f "$qa\byx-local-service\pom.xml" @common '-Dtest=ServiceUtf8ParserTest' test
if ($LASTEXITCODE -ne 0) { throw 'Service portable decoder tests failed.' }
& mvn.cmd -f "$qa\mvp-binance-panel\pom.xml" @common '-Dtest=IpcProtocolContractTest,IdentityGateTest,GenerationFenceTest,WindowsServiceUnavailableTest' test
if ($LASTEXITCODE -ne 0) { throw 'Panel portable/refusal tests failed.' }
```

Archive actual commands, toolchain, exact source SHA and selected Surefire XML outside source staging. Record gate results separately from the 23 existing native Windows errors and the unexecuted full Service suite. Preserve all permanent gates. Do not begin Windows QA on the Mac or proceed to C4.2-B automatically.
