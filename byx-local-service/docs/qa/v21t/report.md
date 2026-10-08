# V2.1T — custody architecture closure

V2.1T STATUS: **READY_FOR_SYNTHETIC_KEYCHAIN_IMPLEMENTATION**.
REAL USER KEY: **NOT AUTHORIZED**. REAL TX: **DISABLED**.

| Delivery item | Decision / evidence |
|---|---|
| V2.1S CLEAN FULL SUITE | One invocation: 405 tests, 0 failures, 0 errors, 0 skipped. JAVA_TOOL_OPTIONS/_JAVA_OPTIONS/JDK_JAVA_OPTIONS explicitly removed with env -u. No rerun masking. Log /private/tmp/byx-v21t-clean-full.log. |
| CAPTURE HEALTH | Beginning supervisor 10657 / collector 96558 alive, correct continuous ETHUSDT campaign/streams/chunk/limit flags; active .part +60,317 bytes / 3 s, age 0.82 s. Final: same supervisor/collector and CLI configuration/campaign; microstructure-20261008T001300Z-usd_m_futures .part +41,148 bytes / 3 s, age 0.25 s. Recorder config hash was not re-audited; no claim of session integrity/admission validation. No raw event reads or capture intervention. |
| THREAT MODEL | Same-user apps/UI/metadata/replay/tampering adversarial; trusted OS/signing/Keychain and approved service/helper code required. Root/certificate/helper RCE cannot be claimed defeated. ADR §§1,13. |
| CUSTODY UNIT RECOMMENDATION | One derived account scalar, 32 bytes. No persisted mnemonic/seed. Device-bound consequence explicit; alternatives seed/mnemonic/wrapped blob/external wallet compared. ADR §2. |
| WALLET ID / KEY REF MODEL | Separate random 128-bit WalletId and SigningKeyRef; panel sees only service-issued WalletId; account ownership checked by service. Existing sender aliases not silently redefined. ADR §3. |
| PUBLIC METADATA | Version/ref/pubkey/address/algorithm/network/time/state/origin/access policy only; no secret or generic signing handle. Service owns authoritative catalog. |
| KEYCHAIN STORAGE MODEL | Future GenericPassword bounded scalar envelope, exact compiled namespace/ref/group, no arbitrary item lookup or persistent refs. Nothing created. ADR §4. |
| DATA PROTECTION MODEL | SecItem + kSecUseDataProtectionKeychain=true, synchronizable=false, helper-only user-context group; no file-based fallback. |
| ACCESSIBILITY CLASS | WhenUnlockedThisDeviceOnly candidate; actual screen-lock/session-switch behavior requires native qualification, ambiguity denies. No secret cache. ADR §5. |
| ACCESS GROUP / ENTITLEMENTS | Current Team W5Z65G9UP2; service default group W5Z65G9UP2.com.buynnex.byx.service. Future signer com.buynnex.byx.signer uses its own default group/profile, no panel/service membership. QA separate. No provisioning. |
| HELPER CODE IDENTITY | Future signed app-like sibling helper, fixed approved bundle origin, role/Team/DR/profile/seal and immutable install; native Go no copied JVM JIT/debug entitlements. Current signer ABSENT. |
| CALLER AUTHENTICATION | Future kernel audit token on connected private AF_UNIX channel, live Security.framework role/Team/origin/seal validation, actual direct parent constraint before any item lookup/prompt. Terminal/unsigned/wrong-role deny. ADR §7. |
| PID/TOCTOU HANDLING | PID+instance token, real child handle, live code/parent and channel checks; no PID/name-only trust. Installation immutability remains deployment gate. |
| IPC TRUST ANALYSIS | Current stdio unchanged, synthetic only. Recommend authenticated one-shot UDS for future custody: socketpair probe proved creator-token limitation; connected UDS probe proved bidirectional actual PID/instance matching. No signed custody qualification yet. |
| KEY LOOKUP MODEL | WalletId -> service-owned SigningKeyRef -> exact helper namespace. No caller service/account/file/HD/raw SecItem path. |
| MEMORY LIFETIME | Retrieve only after auth/lease/presence, derive/compare, sign once, best-effort wipe/release, exit. No private bytes in Java/panel or retained across calls. |
| ZEROIZATION LIMITATIONS | GC is not erasure; stack/native/crypto copies and optimizations remain. Fixed mutable buffers/minimal copies/KeepAlive are best effort, not guaranteed secure erase. |
| CORE/SWAP/DEBUGGER ANALYSIS | Future per-process core limit, redacted panic, hardened runtime/no debug rights and optional compatible native protection; no global settings changed, no swap/root immunity claim. |
| ENV / FD HYGIENE | Empty/minimal approved env; no DYLD/JAVA/HOME/PATH/TMPDIR wallet lookup. Only controlled bootstrap/protocol/runtime FDs; close inherited DB/network/secret FDs. Native qualification pending. |
| NETWORK BOUNDARY | No IP/DNS/HTTP/RPC; narrow AF_UNIX only in future. Current production dependency/filesystem guard PASS. Sandbox candidate without network entitlements; no sandbox/network proof fabricated. |
| CREATE FLOW DESIGN | Future helper-owned entropy/fixed BIP32 derivation, only scalar persisted; no creation/entropy/derivation implementation or mnemonic/seed generation now. ADR §10. |
| IMPORT FLOW DESIGN | Block first version; later separate trusted signer-owned input boundary/external wallet, never panel/service clipboard/text/files. |
| BACKUP / RECOVERY | No initial secret export; device/item loss may permanently lose signing ability. Real funds require explicit device-loss acceptance or approved recovery/external wallet. No silent replacement/adoption. |
| USER PRESENCE OPTIONS | Every transaction conservative candidate; high-value seam preserved without threshold; unlock-session/never alternatives and costs documented. No final UX chosen, no prompts. |
| FAILURE TAXONOMY | Closed future codes for missing/locked/unavailable/denied/timeout/corrupt/algorithm/address/version/network/revoked/caller/helper errors; unknown -> SIGNING_FAILED. Current enums unchanged. ADR §12. |
| TAMPER MATRIX | Replacement/copy/PID reuse/metadata/lookup/address/algorithm/replay/debugger/lock/restore cases mapped to controls, deny states and residual risks. ADR §13. |
| METADATA INTEGRITY | Service authority integrity/ownership; protected item header; helper recomputes scalar-derived pubkey/address; independent service signature/TxRaw/hash/intent checks retained. |
| PERSISTENCE OWNER | Separate versioned public wallet catalog domain in encrypted service authority, never panel.db. Wallet secret item/group/lifecycle separate from auth. |
| REINSTALL / RESTORE MODEL | Missing item -> unavailable; orphan item -> explicit reconciliation, no automatic adoption; revoked/deleted tombstones prevent resurrection. Cross-store qualification pending. |
| PACKAGING PLAN | Contents/Helpers/byx-signer-helper.app sibling to service; own profile/default group; root/nested seal and approved origin. Current bundle signatures verified read-only; no rebuild/install. |
| TEST KEYCHAIN STRATEGY | Mock first; later separately authorized QA profile/group and synthetic canary only, positive control and deterministic cleanup. File-based temporary keychain does not prove DP behavior. No SecItem custody call now. |
| PARENT IDENTITY TEST PLAN | Trusted service, Terminal, unsigned/same-user, wrong Team/role, copied/modified bundle, token/PID churn/parent exit/replay listed with zero-provider-call assertions. Native signed matrix NOT RUN. |
| ACCESS GROUP TEST PLAN | Signer positive control; panel/service/foreign denial; QA/auth/production separation; lock/presence/profile/restore/cleanup tests. NOT RUN. |
| ADR | docs/adr/ADR-TX-002-key-custody.md. No production interfaces needed; contracts formalized in ADR. |
| FILES | Created ADR-TX-002, this report, evidence.json and test-only signer-helper/internal/signer/boundary_test.go. No src/main/packaging/collector changes. |
| COMMITS | Baseline 553737e; separate V2.1T local design/test commit identified in delivery. No push. |

Secure Enclave does not supply native secp256k1: [Apple documentation](https://developer.apple.com/documentation/security/protecting-keys-with-the-secure-enclave). Native caller/Keychain/lock/presence/access-group/security qualification is **future work**, not implied by the successful existing synthetic vector or code-signature audit.

Tests actually executed: one clean complete Java service suite PASS; one directed Go TestProductionHelperHasNoNetworkOrWalletFilesystemSurface PASS; go vet PASS; two ephemeral non-secret AF_UNIX kernel probes (socketpair limitation and connected-endpoint feasibility). Temporary probe directories/sockets removed. No node, live Binance/BYX traffic, scientific processing, rejected-session modification, wallet/mnemonic/seed/key creation/import, custody Keychain read/write, presence prompt, broadcast or private/TX gate change.

Session 20261007T220638Z remains REJECT / REAL_SEQUENCE_GAP / CORRELATED_NOT_CAUSAL per approved diagnosis; not inspected or changed in this task.
