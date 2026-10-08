# V2.1T-1 — automatic signer QA provisioning + SYNTHETIC custody QA

**STATUS: READY_FOR_WALLET_LIFECYCLE_DESIGN.** REAL USER KEY: NOT AUTHORIZED. REAL TX: DISABLED. Nothing here touches a real wallet, mnemonic, seed, key, node, Binance or the chain.

## Automatic provisioning (SUCCESS, no manual action needed)
Xcode 26.0 on this Mac, Personal Team `W5Z65G9UP2` (free provisioning team), one valid Apple Development certificate (reused; nothing created, revoked or deleted). `provisioning/provision-signer-qa.sh` (Xcode managed signing, `-allowProvisioningUpdates`, no portal automation, no credentials read) registered the explicit App ID **com.buynnex.byx.signer.qa** and produced:

| field | value |
|---|---|
| profile name | Mac Team Provisioning Profile: com.buynnex.byx.signer.qa |
| UUID | 695b211c-9dad-4cce-b30f-68b27f99deeb |
| created / expires | 2026-10-08 01:03:43 UTC / **2026-10-15 01:03:43 UTC** (7 days, Personal Team) |
| TeamIdentifier / ApplicationIdentifierPrefix | W5Z65G9UP2 / W5Z65G9UP2 |
| application-identifier | W5Z65G9UP2.com.buynnex.byx.signer.qa |
| profile keychain-access-groups | `["W5Z65G9UP2.*"]` (Xcode-managed wildcard; same shape as the existing service profile) |
| signed binary keychain-access-groups | `["W5Z65G9UP2.com.buynnex.byx.signer.qa.keys"]` only |
| certificate | the existing Apple Development certificate (sha1 prefix 582d9306), 1 device (this Mac) |

Existing profiles untouched: service `b7f753ed-…` (com.buynnex.byx.service) expires **2026-10-13 16:49:55 UTC** (5 days left; not renewed here). Panel has no profile (JIT-only entitlement). Panel/Service App IDs, certificates and profiles unchanged.

Important nuance: because the managed profile carries a team-wide wildcard, the *profile* alone does not isolate the group. Isolation comes from the entitlements embedded in each signature (only the signer lists the group) and is **proved empirically** (below), not inferred.

## Acceptance gate
Team W5Z65G9UP2 ✔ · signer QA application id ✔ · profile app id `W5Z65G9UP2.com.buynnex.byx.signer.qa` ✔ · signer group present ✔ · panel group absent ✔ · service group absent ✔ · profile valid ✔ · certificate valid ✔ · code signature valid (strict, nested) ✔.

## Artifact and transport
`./build-custody-qa.sh` builds `build-custody-qa/` (separate from DEFAULT, git-ignored): canary base (panel + service + test roles), `Contents/Helpers/byx-signer-helper-qa.app` (native Go + cgo/Security.framework, hardened runtime, own identity and profile), the service-role runner (clone of the canary service reader: service identity/profile) and the panel-role runner (panel identity), plus negative tools (unsigned client, same-Team wrong-role client) and signer variants. DEFAULT has none of it (`find build -iname '*signer*'` = 0).

**Transport decision: private connected AF_UNIX socket, one-shot helper per call** (the ADR-TX-002 recommendation). A launched-by-parent `socketpair` was rejected because both endpoint tokens name the creator. The helper creates an exclusive 0700 directory under the OS per-user temp root (`confstr`), a 0600 socket, prints a READY frame, accepts ONE connection and reads the **kernel LOCAL_PEERTOKEN before reading any byte**. No TCP, HTTP, localhost, shell, PATH lookup or config.

**Caller authentication (helper side, before any Keychain call):** (1) the helper validates itself (genuine sealed `com.buynnex.byx.signer.qa`, Team, approved origin `…/Contents/Helpers/byx-signer-helper-qa.app`); (2) peer pid from the kernel token must equal `getppid()` (additional constraint only); (3) launch environment of the caller (KERN_PROCARGS2) must not carry JAVA_TOOL_OPTIONS/_JAVA_OPTIONS/JDK_JAVA_OPTIONS/CLASSPATH/DYLD_*/LD_*; (4) live code behind the token must satisfy `identifier "com.buynnex.byx.service" and anchor apple generic and certificate leaf[subject.OU]="W5Z65G9UP2"` with strict nested seal; (5) the caller's bundle must sit in the same `Contents/Helpers` directory. Any failure → fixed `CALLER_UNTRUSTED` frame with `keychainCalls=0` and exit. Then a fresh 256-bit challenge, exactly one request (trailing bytes rejected), one operation, exit.

**Signer verification (service side, before sending anything):** static check of the fixed-origin bundle (Apple chain, Team, exact identifier, strict nested seal; unsigned/modified/wrong id each have their own verdict), real-path = fixed origin (a genuine copy elsewhere is refused), empty launch environment (helper reports `envCount=0`, kernel record scanned for loader/injection variables), inherited descriptors (`fds=0`, `socketFds=0`), private endpoint directory (owner, 0700, no symlink), and on the connected socket the kernel token must name the very child spawned, running the genuine sealed signer code from the approved origin.

**Keychain:** native `SecItemAdd/CopyMatching/Delete` (Data Protection Keychain, `kSecUseDataProtectionKeychain=true`), GenericPassword, `kSecAttrAccessible = WhenUnlockedThisDeviceOnly`, `kSecAttrSynchronizable=false`, exact access group `W5Z65G9UP2.com.buynnex.byx.signer.qa.keys`, compiled namespace `byx.signer.qa.synthetic.v1`; no `/usr/bin/security`; no prompts appeared. The 32-byte scalar is generated **inside the signer** (crypto/rand, validity checked against the curve order), never in Java, source, binary, argv, env or logs.

## Matrix (`./verify-custody-qa.sh`: 65 PASS, 0 FAIL, run on the final rebuilt artifact)
| case | result |
|---|---|
| SERVICE → SIGNER (real service identity) | full synthetic flow PASS: create key, address matches independent derivation, no overwrite, orphan A (metadata without Keychain) → KEY_NOT_FOUND, orphan B (Keychain without metadata) → detected, NOT adopted, no new key, fake BankSend quote through the real TxService engine to the signing barrier, signer retrieves secret, SIGN_MODE_DIRECT secp256k1 signature, TxRaw, **independent service verification (low-S, TxRaw, tx hash, bindings, intent digest) PASS**, wrong public key rejected, unknown ref → KEY_NOT_FOUND, **0 broadcasts**, delete, lookup → KEY_NOT_FOUND, namespace items 0 |
| Terminal / unsigned process → SIGNER | CALLER_UNTRUSTED, keychainCalls=0 (valid KeyRef, real item present) |
| same Team, wrong role → SIGNER | CALLER_UNTRUSTED, keychainCalls=0 |
| helper started directly by the Terminal + unsigned attacker | CALLER_UNTRUSTED, keychainCalls=0 |
| genuine service identity with injected JAVA_TOOL_OPTIONS | CALLER_UNTRUSTED, keychainCalls=0 |
| PANEL identity → SIGNER | CALLER_UNTRUSTED, keychainCalls=0 |
| unsigned / modified-seal / wrong-identifier signer → SERVICE | refused before execution (NO_CODE / SEAL_BROKEN / REQUIREMENT_FAILED) |
| genuine copy of the signer elsewhere → SERVICE | refused (HELPER_ORIGIN) |
| secret access PANEL / SERVICE (direct) / unsigned foreign / same-Team wrong role | all **DENIED** (errSecMissingEntitlement -34018, no data) |
| secret access SIGNER (positive control) | ALLOWED (FOUND) |
| cleanup | exact synthetic namespace only; other namespace → NAMESPACE_REFUSED; remaining items 0; failed-run cleanup verified |
| network | signer fds 0,1,2 (+1 local socket), **0 inet sockets** (lsof), no `net`/`os/exec` dependency, no IP socket code (Go guard tests) |

## Limits stated plainly
* Go cannot guarantee secure erase: the scalar is copied C→Go, wiped best-effort (`memset_s` on the C buffer, `Zero`/wipe on Go copies), but the Security.framework `CFData`, Go stack/heap copies, crypto temporaries and swap are not provably cleared. There is no cache; the process exits after one operation.
* TOCTOU: a bundle swapped after validation and before use is not detected; install in a location the ordinary user cannot write. No claim against root, a compromised trusted binary, or a compromised signing certificate.
* The Xcode-managed profile carries a team wildcard group; isolation relies on signed entitlements (proved by access tests). Profile lifetime is 7 days (Personal Team); expired → helper is killed by macOS and signing is unavailable.
* This is software custody of a secp256k1 scalar, not hardware non-exportable (Secure Enclave has no secp256k1).
* No sandbox profile was applied (no network entitlements are requested, but App Sandbox is not enabled).
* Finding (pre-existing V2.1S code, not changed): `SignerClient.verifyIdentity` uses `com.sun.security.auth.module.UnixSystem` (module `jdk.security.auth`), which is absent from the packaged jlink runtime; it would fail there. The new custody client does not use it.
