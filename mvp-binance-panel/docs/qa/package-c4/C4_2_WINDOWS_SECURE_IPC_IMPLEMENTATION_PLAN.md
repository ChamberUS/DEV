# C4.2 — secure Windows IPC implementation plan

2026-10-09. Documentation and static architecture review on `feature/byx-windows-readiness-v1`, source checkpoint `54e1406e04cc977026fe1f7a401eb1d81df50bd1`.

**C4_2_WINDOWS_SECURE_IPC_DESIGN_BLOCKED** for implementation approval: no qualified native transport execution and no security-equivalent live application identity strategy. The plan below is complete for review; it does not approve privileged connectivity. Public image closeout is independently **C4_1_P_FINALIZED**.

## Reviewed baseline and implemented macOS contract

Reviewed [approved Windows RFC](../../package-c4/WINDOWS_SECURE_IPC_RFC.md), [isolated probe](windows-ipc-identity-probe.md), [App Control diagnosis](windows-app-control-diagnostic.md), [authorized-environment plan](windows-ipc-test-environment-plan.md), and current source. No RFC contract or production code was changed.

| Boundary | Source and implemented behavior |
| --- | --- |
| Panel → Service | `panel/localservice/LocalServiceClient.java`, `AuthorityClient`, `MarketFeedClient`: UNIX channel, strict server verification before handshake, fresh pairing-file read, deadlines and bounded typed responses |
| Service endpoint | `byx/service/RuntimeDir.java`, `ServiceInstance.java`: user-owned non-link runtime, 0700 directory, 0600 token/socket; fresh 32-byte secret on start; UNIX listener; cleanup on stop |
| Kernel identity | Panel/Service `identity/MacSecurity.java`, `PeerIdentity.java`: LOCAL_PEERTOKEN audit token, Security.framework guest from that token; expected component/Apple chain/Team requirement; strict bundle seal; launch environment rejects JVM/DYLD injection |
| Process instance | `byx/service/identity/PeerKeys.java`: PID **plus pidversion**, never a client-supplied PID; absent evidence returns NONE. Auth does not accept missing peer identity |
| Pairing | `byx/service/Pairing.java`, `ServiceInstance.handle`, Panel `pair`: HMAC-SHA256, direction-specific labels and both fresh nonces; client checks server proof before client proof/requests; constant-time comparison |
| Session / authority | `auth/SessionStore.java`, `AuthComposition`, `AuthLimits`: hashed random session tokens bound to account, credential version and peer key; revocation and expiry; 8 h absolute, 15 min idle, 5 min admin elevation; Service decides operation/role/MFA |
| Privileged boundary | Panel → Service → CustodyClient → Signer → platform secure store. `ServiceMain` composes real authority/providers; transactions use `TxProduction.disabled`. Never start it for this investigation |

Existing v1 HMAC transcript is exactly `byx-ipc-v1|direction|clientNonce|serverNonce`; **instanceId/generation and code-manifest digest are not explicit fields in that HMAC**. Fresh per-start secret provides an implicit epoch, while ready/health expose instance ID and feed/client lifecycle fencing rejects late generations. Do not describe proposed explicit transcript/channel/generation binding as already implemented. Any wire evolution needs version negotiation, compatibility analysis and separate approval; leave current macOS protocol unchanged here.

Protocol framing is **four-byte big-endian length + UTF-8 JSON**, maximum ordinary frame 8192 bytes, strict DTO/version/operation checks. Service defaults: 8 connections, handshake 3 s, read/write 5 s, idle 30 s, max 1000 requests/connection and 4 market subscribers. Preserve operation-specific/event caps and client watchdog/backoff. The isolated probe's little-endian framing is research-only and must not be mistaken for the product wire contract.

Mac verifier documentation explicitly acknowledges disk replacement between launch and connection; protected non-user-writable installation is a prerequisite, not a claim that macOS resists all injection or process compromise. Windows must preserve the approved threat boundary, without inventing stronger macOS assurances or weakening required Windows app identity.

## Present Windows state and independent gates

`LocalServiceClient.loadSecret` refuses Windows with `native_service_unsupported` **before token read or IPC open**. Preserve this guard. `IdentityPolicy.detect` has historical development fallback when MacSecurity is unavailable; **never use that fallback to enable authenticated Windows access**. `PeerKeys.NONE` must not become an invented Windows peer identifier.

Panel `WindowsStorage.java` implements real NTFS descriptor/owner/ACE/reparse checks for **noncredential Panel data**. Its documented isolation excludes hostile processes of the same user and privileged OS principals. This ACL implementation is valuable but does not implement pipe policy, protected pairing, app identity or a Windows Service coffer. Do not reuse it as credential storage without a separately approved design.

| Gate | Required evidence | Current state |
| --- | --- | --- |
| A: transport | Real local NPFS endpoint, verified handle ACL, framing, timeouts, cancellation and cleanup | BLOCKED / native cases not executed |
| B: OS peer identity | Bidirectional channel-originated process/token evidence, alive instance binding, races/reuse resolved | NOT PROVEN |
| C: live BYX code identity | Authorized launcher/JVM/JAR/config/native composition bound to that connected instance; hostile same-user impostor excluded | NOT PROVEN / design blocker |
| D: mutual pairing | Application-restricted provisioning, fresh per-generation proofs and replay resistance over qualified channel | NOT PROVEN |
| E: user session | Actual account/MFA/session lifecycle bound to verified peer and generation | NOT IMPLEMENTED / not authorized to enable |
| F: Service authorization | Backend operation/role/capability checks independently deny unauthorized requests | Windows integration NOT QUALIFIED |

OS user identity, process identity, executable identity, application-code identity and authorized user session are distinct. No earlier gate implies a later one. None of A–F is established by an open JavaFX window.

Historical **23 Windows ERROR** remain `IOException: native_ipc_fixture_unqualified: POSIX pairing and native peer identity required; no Windows substitute`, originating at `IpcTestFiles.requirePosixPairing`. Classes: ChainStatusClientTest (3), LocalServiceClientTest (10), MarketFeedClientTest (10). The RFC/error matrix records every method and aggregated negative mode. Preserve all originals; native equivalents must retain their full assertions. No new executions, PASS conversions or skips here.

## Proposed transport and native API scope

Named Pipes remain the preferred **research candidate**, not an approved Service transport. Start with one disposable, duplex byte-stream instance in an isolated non-admin harness, no production endpoint or authority. Future multi-client design must keep a verified ownership instance/lifecycle and validate every additional pipe instance; do not apply FIRST_PIPE_INSTANCE blindly to all legitimate secondary instances or release the ownership anchor prematurely. [Microsoft CreateNamedPipe](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-createnamedpipea) documents first-instance failure and remote-rejection flags.

Required native surface, subject to review and measured proof:

- `kernel32`: CreateNamedPipeW, CreateFileW, ConnectNamedPipe, GetNamedPipeClientProcessId/GetNamedPipeServerProcessId, ReadFile/WriteFile, CreateEventW, GetOverlappedResult, CancelIoEx, DisconnectNamedPipe, CloseHandle; OpenProcess, GetProcessTimes, QueryFullProcessImageNameW, process waits. Exact return/GetLastError retained immediately.
- `advapi32`: explicit SDDL/security descriptors, GetSecurityInfo **on the pipe handle**, OpenProcessToken/GetTokenInformation (user, logon groups, session, integrity/elevation); where necessary identification-only ImpersonateNamedPipeClient/OpenThreadToken, always RevertToSelf and failure handling. No operation or secret-store access under client impersonation.
- `wintrust`/`crypt32`: approved Authenticode and chain/revocation/timestamp policy plus handle-bound file integrity design; WinVerifyTrust succeeds only on return **zero**. No install/trust-root modifications. JNA 5.17.0 already exists; ABI x64, handle ownership, buffers and errors still need audit. JNI is an alternative requiring the same qualification, not an execution workaround.

Explicit protected DACL; no default/NULL descriptor, Everyone/Anonymous access, inherited broad rights, or generic write to clients. Restrict selected logon SID and independently verify expected user/logon tokens. Separate allow ACEs for user/logon express a union, **not conjunction**. Client access excludes creation rights; server instance creation is limited to authorized owner. Owner's WRITE_DAC powers and handle duplication remain explicit same-user attacks. [Microsoft pipe rights](https://learn.microsoft.com/en-us/windows/win32/ipc/named-pipe-security-and-access-rights) explains default ACL exposure and generic-write instance-creation rights.

Use PIPE_REJECT_REMOTE_CLIENTS, non-inheritable handles, bounded overlapped I/O and real deadlines. nDefaultTimeOut is not a complete read/write deadline. Handle ERROR_PIPE_CONNECTED races correctly. Cancellation must await completion/drain before releasing buffers/OVERLAPPED/events; CancelIoEx alone is not completion. [Microsoft CancelIoEx](https://learn.microsoft.com/en-us/windows/win32/api/ioapiset/nf-ioapiset-cancelioex).

Peer PID APIs provide process IDs, not a live application attestation. Resolve channel → PID → retained process handle/token/creation-instance race, check termination and recheck connection association where feasible; if correlation cannot be proved, **deny**. Do not accept PID/path supplied over the wire or guess a process from its name. Stale handle and real PID recycling are distinct tests. [Microsoft client PID API](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-getnamedpipeclientprocessid).

## Identity design decisions that block production

Threats: unauthorized local/different-user process; hostile same-user Java/native process; endpoint squatting/replacement; permissive ACL or owner mutation; forged/replayed proof; stale process/session/restart; mutable JAR/config/DLL; JVM agents, classpath/environment/search-path injection; parser/resource exhaustion; confused deputy. OS kernel compromise/admin compromise and compromise of an already authorized BYX process require an explicit threat-boundary decision, not silent exclusion. Same-user capabilities such as process memory access/injection must be measured and contained by the chosen isolation design.

Required design must specify **both endpoints** and prove these claims:

1. Dedicated authorized launcher and component policy, not generic signed java.exe. File trust is executable identity only; WinVerifyTrust does not attest the currently loaded Java composition. [Microsoft WinVerifyTrust](https://learn.microsoft.com/en-us/windows/win32/api/wintrust/nf-wintrust-winverifytrust). This application-identity limitation is a design inference from that API's object verification scope.
2. Protected installation/update boundary for launcher, JVM, all JAR/classpath/config and native DLLs; signed manifest/catalog or validated equivalent including rollback/revocation. Review JAR signatures/complete content, unsigned entries and dynamic loading. Hashes from a user-writable directory or one-time module enumeration are insufficient.
3. Bind verified files/composition and trusted launch/loading constraints to the **live connected instance**; close substitution/TOCTOU races. Prevent injected options/agents, writable extra classpaths/DLL search, attach/debug/handle theft from creating an eligible impostor. Existing DisableAttachMechanism is useful but not a complete Windows security boundary.
4. App-restricted secret acquisition/use. Shared per-user token or ordinary DPAPI cannot distinguish arbitrary same-user apps. [Microsoft DPAPI](https://learn.microsoft.com/en-us/windows/win32/api/dpapi/nf-dpapi-cryptprotectdata) describes user/machine scope; code-restricted BYX identity must be independently designed.
5. Decide an enforceable isolation/composition model: protected launcher plus audited native broker under separate restricted identity, or package/AppContainer/other independently verified boundary. Neither option is proven by packaging/name/package SID alone. No LocalSystem-by-convenience design. If no model demonstrates required same-user resistance and mutual identity, stop with this security blocker rather than substituting bearer-token trust.

HMAC provisioning cannot be solved by placing a static key in JAR/source, argv/env/log or ordinary readable per-user file. Specify creation, eligible-client delivery, proof-use restrictions, rotation/revocation, per-Service-generation secret and non-secret generation identifier. Authenticate direction, protocol/domain, nonces, endpoint roles and approved channel/instance/generation binding in a reviewed transcript; handle replay/reflection/cross-channel relay. Revoke previous generation sessions atomically on stop/restart; discard delayed requests/events/callbacks. No protocol upgrade implemented here.

## Signing and authorized native research prerequisites

Historical harness was blocked by SAC **VerifiedAndReputableDesktop**, NTSTATUS **0xc0e90002**, CodeIntegrity 3077/3089 correlation. CiTool inventory was denied; administrative ownership remains incompletely established. The final compiled harness hash is recorded in the prior report, distinct from earlier blocked versions. No native pipe scenario was qualified. App-image execution and Process RemoteSigned permission do not authorize this harness or change SAC trust.

Two legitimate routes, both requiring a new explicit research authorization:

- Reviewed test artifact signed with a legitimate Windows trust chain accepted by the existing policy, hash/dependency manifest and non-admin execution procedure approved by policy owner. No self-signed acceptance assumption, no certificate import here, no production signing keys. [Microsoft SAC signing guidance](https://learn.microsoft.com/en-us/windows/apps/develop/smart-app-control/code-signing-for-smart-app-control) supplies the trust prerequisites; signing alone does not prove BYX code identity.
- Separately provisioned owner-authorized Windows 11 x64 VM/test host with an approved native research policy, two standard QA users and two sessions, no production secrets/Service, no shared writable host workspace, controlled/no network. Preserve Secure Boot/TPM and existing host protections. Existing environment plan covers licensing/resources/provisioning; this task neither provisions nor changes policy. VM NPFS/token results are guest-kernel evidence, not qualification of notebook SAC, hardware or every Windows configuration.

Current host has **no verified acceptable native identity-probe execution procedure**. Gate A/native execution remains BLOCKED. No blocked harness retry, rename, repackage, wrapper, certificate installation, Defender/SAC modification or privileged Service launch. Static/portable analysis remains available.

## Interfaces and small milestones — proposals, not implementations

| Milestone | Scope / interfaces | Evidence and exit criterion |
| --- | --- | --- |
| M0: safe now, separately requested | Isolated pure contract model for LocalTransport deadlines/frame limits; immutable NativePeerEvidence/PeerInstanceId; deny-by-default IdentityVerdict; EndpointPolicy; generation invalidation rules | Portable policy/parser tests distinguish missing evidence, user/process/code identity, deny stale generations; no native calls/Service credentials. Doubles explicitly unit-only, never identity proof |
| M1: authorized lab only | Narrow NativePipe adapter and disposable harness, audited native handles/token/DACL/SQOS/cancel cleanup | Gates A/B measured; all native positive/negative cases recorded; no production authority |
| M2: architecture approval + lab | LiveCodeIdentityVerifier and protected composition/isolation/launch model for both peers | Gate C proven against arbitrary same-user Java/native controls, tamper/injection and races; incomplete evidence denies |
| M3: after C | PairingProvider/HandshakeTranscript and ServiceGeneration in isolated channel using ephemeral QA secrets | Gate D; real mutual proofs, protected provisioning, replay/reflection/relay/restart tests; no user account integration |
| M4: separate integration approval | AuthorizedSessionContext and ServiceAuthorizationPort; preserve actual account/MFA/operation enforcement | Gates E/F and full Windows/macOS regressions; only then review Windows authenticated login enablement |

PeerInstanceId should be an opaque platform-qualified identity with lifecycle, not truncation of creation time into the current macOS long key. Preserve MacSecurity/pidversion and existing contracts; any cross-platform session-key/interface migration needs compatibility review and macOS regression before integration. Unknown platform or verifier failure returns unsupported/deny; no development fallback or alternative transport.

## Native test matrix and acceptance

All rows below are **PLANNED / NOT_EXECUTED** in this task. Pure tests in M0 are not substitutes for native results.

| Case | Actual evidence required / fail-closed expectation | Gate |
| --- | --- | --- |
| Local creation, connect and frames | Handle descriptor readback, intended Win32 flags, positive byte transport with exact big-endian product framing | A |
| User/logon restrictions | Real A/B users plus A second logon; permitted combination connects, other user/logon denied as specified | A/B |
| DACL permissive/default/owner mutation | Broad/inherited/NULL/missing descriptor refused; attempts to grant rights/change owner cannot create authorized peer | A/C |
| Remote rejection | Second authorized isolated host/switch attempts remote connection and is rejected; otherwise NOT_EXECUTED | A |
| Squatting and multiple instances | Impostor creates name first; legitimate endpoint refuses availability; unauthorized secondary instance refused | A/B |
| Server replacement / client impostor | Bidirectional channel identity rejects replacement or arbitrary process even with same SID/name/path and valid ordinary token | B/C |
| Live process evidence | Channel-linked token/handle/creation checks, process exit during checks, denied access/zero PID/incomplete evidence all deny | B |
| PID reuse and stale handle | Actual recycled PID tested separately from stale-handle negative; no old identity/cache acceptance | B/C |
| Code tamper and launch injection | Changed JAR/class/config/DLL, signed generic JVM, agents/env/classpath/native search/inherited handles do not qualify | C |
| Same-user token/memory/handle attacks | Authorized design boundary prevents secret/identity misuse; demonstrate or declare design FAIL | C/D |
| Wrong secret, replay, reflection, relay | Old server proof never triggers client proof/request; new nonces, roles, generation and channel binding enforced in real pipe | D |
| Restart / snapshot / credential change | New secret/generation; prior session revoked; late requests/events ignored; snapshot is not live-session continuation | D/E |
| Bounds / malformed / partial I/O | 0, 8193, extreme length, partial header/body, invalid UTF-8/JSON/version/op refused before unsafe allocation; preserve larger event-specific cap | A/D |
| Timeout / cancel / disconnect / server exit | Slow read/write/silence, cancellation race and process exit settle within budgets; drain native memory, close handles and own children only | A |
| Login / authorization negatives | Real QA accounts/MFA/roles/expiry; valid code or paired pipe alone never grants role/private capability | E/F |
| Original 23 methods and every mode | Native Windows equivalents plus unchanged POSIX originals; no fake native identity, no skips to obtain green | A–F |

Collect RunId/CaseId, exact source/executable hashes, Windows build/policy evidence, normal-token context, monotonic elapsed deadlines, API return/error, descriptor/token relationships, expected/observed and PASS/FAIL/ERROR/NOT_EXECUTED. Raw SID/SDDL is private lab evidence; shared reports anonymize relationships and exclude secrets, account identifiers and personal paths. Preserve real process exit separately from shell/build LASTEXITCODE. Proposed isolated run budgets: 2 s frame, 300 ms negative connect, 5 s teardown; three functional runs and 100 lifecycle/cancel cycles; final no leaked owned handles/threads/children. These are planned criteria, not measured product timings.

Before enabling Windows authenticated login: owner-approved threat/identity/storage/provisioning design; authorized native environment; A–D passed bidirectionally; E/F proven with synthetic QA accounts and real Service-side policy; 23 scenarios faithfully qualified, broader Windows/Service security regression green; required macOS peer/seal/env/POSIX/session/protocol and full regression passed; independent review and explicit capability release authorization. No custody/trading enablement follows from this gate.

## Rollback and source protection

Keep research isolated on the dedicated branch. Production Windows guard remains enabled through M0–M3. Any experimental transport/provisioning/identity failure closes channel, revokes generation/sessions and returns honest unavailable; never downgrades to loopback/dev/unauthenticated IPC. Future rollback uses reviewed normal revert to the public-only checkpoint, no history rewrite or security-policy rollback. Stop only owned research processes and dispose their handles; retain evidence; do not clean existing artifacts or disturb ETHUSDT collectors/supervisors. No implementation or rollback action was performed now.

This milestone changes sanitized documentation only. No source/test/POM/native harness/macOS verifier/Keychain/signer/custody edits; no full regression or app/harness rerun; documentation-only local commit after staged review, no push/merge/release. Shared changes at a later milestone require macOS compile, focused/full Panel and Service regression, real signed-peer identity/seal/env tests, POSIX storage, sessions/restart and packaging checks in an authorized macOS environment.

REAL USER KEY = NOT AUTHORIZED; REAL TX = DISABLED; BROADCASTS = 0; REAL FUNDS = 0; REAL USER WALLETS = 0; ETHUSDT RESEARCH = UNTOUCHED. STOP after this plan; privileged Windows IPC is not authorized.
