# ADR-TX-001 — Future real signer strategy

Status: **V2.1S SYNTHETIC FOUNDATION IMPLEMENTED; production signing/custody unavailable**. V2.1R-1 contract is checkpointed separately. No real key provider or transaction transport is authorized by this ADR. Context: the service owns authorization, quotes and the
signer boundary (`TxSigner`, `TxSignRequest`); the panel never sees keys. BYX is a Cosmos SDK chain (coin type 118, SDK 0.53.x, bech32 `byx`); MsgSend gas ≈ 94 k.

## Options

| | A. In-process Java signer | B. Minimal Go signer helper | C. External / hardware-style wallet | D. `byxd` CLI subprocess |
|---|---|---|---|---|
| Security boundary | Same JVM as the service: a service RCE = key access; key lives in heap | Separate OS process with its own entitlements; can be sandboxed, no network, tiny IPC | Hardware key stays on device; a software wallet extension still holds a key outside the service | Separate process but a **general-purpose** binary with a huge command surface |
| Key exposure | Heap, GC copies, swap; mitigable (char[]/zeroing, Keychain ref) but weak in Java | Key in a small process's memory only for the signing call; can own a Data Protection Keychain-backed software secp256k1 provider; no native secp256k1 Secure Enclave signing | Minimal: only signatures cross the boundary | Key via keyring/CLI flags/stdin/files; `--from`, keyring backends; argv/env/log leakage risks; password prompts |
| Protobuf compatibility | Must re-implement or vendor SignDoc/TxBody encoding; drift risk vs SDK | Closed protobuf subset, byte-exact SDK golden SignDoc; SDK types remain a future option | Wallet builds the doc itself (Keplr/Ledger amino/direct quirks) | Exact (same code as the node) |
| Dependency burden | protobuf + bcprov (already present) | A Go toolchain, a vendored module set, reproducible build, ~10 MB binary | Wallet SDKs / WalletConnect-like transport / USB HID | Ship `byxd` (≈100 MB, whole node) |
| Packaging / signing | Nothing new | One more helper `.app` in the bundle, code-signed, identity-verified like the service | Per-device integration, entitlements (USB/BLE) | Ship and sign the node binary; version coupling with the chain |
| Signing correctness | Highest drift risk (we own an encoder) but testable against golden vectors from the chain | Lowest drift risk; golden vectors trivial | Depends on wallet; some modes (EIP-712/amino) differ | Lowest drift, but side effects possible (it can also broadcast, query, modify config) |
| Maintainability | One language, but we maintain crypto/proto code | Two languages; small stable surface | Many device/wallet variants | Easy to start, hard to keep safe |
| Auditability | Large JVM attack surface to audit | Small, single-purpose binary; narrow input (a bound sign request) | Audit shifts to the device vendor | Audit the whole CLI + its keyring + its config handling |
| macOS Keychain | Via existing `SecItem` store (service entitlements) | Helper needs its own Keychain access group / entitlement (provisioning cost) | n/a | Keyring backend `os` = Keychain with the CLI's ACL, hard to scope |
| Attack surface | Service process ≈ signing process | Extra IPC hop, extra binary | Phishing/UX of the device flow | **Largest**: subprocess injection (argv/env/PATH), arbitrary subcommands, config files, update drift |

## Scrutiny of D (CLI subprocess)
Rejected for production. It is the easiest to prototype and the easiest to get wrong: argv and environment are observable to same-user processes; passphrases or key names travel on stdin/argv;
the CLI honors config files, home directories, `--node`, `--keyring-backend`, `--broadcast-mode`; a single wrong flag can broadcast; it requires shipping and trusting the whole node binary;
errors are text to be parsed; there is no narrow contract and no way to enforce "sign only this structure". It also couples our release to the chain binary. It may be used **only** in an isolated,
offline, throwaway developer harness to produce golden vectors, never in the app.

## Chain evidence and deterministic compatibility proof

The active chain is BYX `byxd`, Cosmos SDK **0.53.3**, not the partial EVM branch. Account key generation defaults to **Cosmos secp256k1**, not `eth_secp256k1`. CoinType118 is HD namespace metadata, not proof of the curve: the proof is the SDK keyring default `hd.Secp256k1`, CLI key-type default and registered standard crypto types, with no custom Ethermint keyring/sign-mode override in BYX app wiring. Other SDK-registered key types are not claimed impossible by consensus; the supported future single-signer contract here is explicitly secp256k1/DIRECT.

| Item | Evidence |
| --- | --- |
| Chain constants | BYX `app/app.go:62–65`: `AccountAddressPrefix="byx"`, `ChainCoinType=118` |
| Applied coin/prefix config | BYX `app/config.go:15–18`: SetCoinType, account/validator/consensus Bech32 codecs, sealed config |
| Account key default | SDK `crypto/keyring/keyring.go:213–214`; `client/keys/add.go:94`: standard `hd.Secp256k1`; no Ethermint override in BYX `cmd/byxd/cmd/root.go`/`app/app_config.go` |
| HD defaults | SDK `client/keys/add.go:91–94,273–280`: coin config118, account0,index0; `crypto/hd/hdpath.go:119–122,278–280`: BIP44 change0 → `m/44'/118'/0'/0/0` |
| Keplr alignment | BYX `web-faucet/public/web_index.html:118–125`: bip44 coinType118 and byx/byxpub; no custom path configured; SDK CLI permits explicit alternate account/index/path, so path is default contract, not consensus enforcement; an external wallet account index must be explicitly bound before future use |
| Public key Any | `/cosmos.crypto.secp256k1.PubKey` in SDK-generated vector; compressed SEC1 33bytes |
| Address derivation | SDK `crypto/keys/secp256k1/secp256k1.go:39–44,150–165`: compressed point, RIPEMD160(SHA256(pubkey)),20bytes, Bech32 byx (not Keccak/Ethereum truncation) |
| Signature | SDK `crypto/keys/secp256k1/secp256k1_nocgo.go:14–23`: ECDSA over SHA256(SignDoc), deterministic nonce, low-S,64byte big-endian R||S without recovery byte/DER wrapper |
| Direct mode | SDK `x/auth/tx/config.go:59–69` DIRECT first/default, DIRECT_AUX and AMINO also supported; BYX CLI `cmd/byxd/cmd/root.go:115–122` enables TEXTUAL via metadata resolver; future signer pins DIRECT only |
| Direct SignDoc | SDK `x/auth/tx/direct.go:47–57`: body_bytes1, auth_info_bytes2, chain_id3, account_number4 |
| Sequence | AuthInfo.SignerInfo.sequence; checked vs account and incremented by successful ante. Included message failure can consume sequence; local CheckTx does not itself prove committed sequence advancement. SDK `x/auth/ante/sigverify.go:366–394` also uses account_number0 at genesis height0. Query committed account/tx before retry. |

Vector: [byx-direct-vector.json](../../src/test/resources/tx/byx-direct-vector.json), **TEST VECTOR — NOT A REAL WALLET**. Synthetic private scalar1, no seed/user wallet/keyring. The HD path identifies the CLI default; this scalar was chosen directly, not derived from a mnemonic. Account7, sequence9, chain-id byx, one MsgSend,120000gas/3000ubyx fee. TxBody includes typed MsgSend/memo; AuthInfo includes pubkey Any, ModeInfo.Single.DIRECT, sequence and fee/gas. SignDoc binds exact body/auth bytes, chain and account. TxRaw contains exact body/auth bytes plus signature; tx hash is SHA256(TxRaw). Hex strings can be upper/lowercase displays of the same hash bytes.

Producer: BYX `tools/signing-vector/main.go` uses actual SDK structs/crypto and checks signature plus TxRaw decode/re-encode. Consumer: test-only `SyntheticSigningVectorTest` independently encodes protobuf fields, derives address, signs with Java Bouncy Castle RFC6979 and compares all bytes/hashes/signature against Go. No real signing API, node or broadcast is needed. Golden vector covers one fixed single-signer MsgSend shape, not multisig/feegrant/arbitrary Cosmos message support.

## Recommended principal direction

**B: minimal offline Go signing helper within the service trust boundary**, using the pinned chain SDK types and a helper-owned software secp256k1 KeyProvider. This minimizes byte drift and JVM key exposure and permits a small auditable, separately signed macOS executable. No CLI/keyring/home/config/network/subcommand passthrough. No generic signing API. The helper must independently reconstruct/check the allowed bound single MsgSend and DIRECT document; the service must verify returned signed bytes match the confirmed quote and expected pubkey/address before any separately authorized transport can consume them.

**Secondary C: external hardware/wallet** for stronger key isolation; only after a device-specific Cosmos/DIRECT byte/vector and approval-flow proof. Do not assume every Ledger/Keplr mode supports the same flow. A(Java) remains a useful independent test implementation, but no production JVM signer is chosen. D(byxd CLI) remains rejected for production due to generic command/network/keyring exposure.

Packaging requires a fixed helper path, signed code identity/verified peer, protocol version, bounded input/output, no network, explicit entitlements/provisioning, and reproducible pinned dependencies. One-shot/offline helper minimizes lifetime/blast radius; crash/identity/version/vector mismatch fails closed. Helper isolation reduces exposure; it does not make software keys non-exportable or prevent every same-user/service compromise. The V2.1S helper is implemented for offline tests and is not packaged.

## Restricted signing capability and KeyProvider seam (design only)

`TxSignRequest` is final with a package-private constructor, and only `TxService` constructs it in main sources, after quote validation/confirmation. The narrow signer interface accepts that type only. This is Java compile-time construction restriction, not cryptographic authentication across a future IPC boundary: same-package hostile code is not defeated by package visibility. The future helper must also authenticate the service peer and validate a service-created bounded capability bound to operation/quote/intent/policy/chain generation/account/sequence/pubkey/sender/recipient/amount/memo/gas/integer fee/expiry. It rejects replay or changed binding.

Panel never constructs that request or receives signed bytes/keys. No arbitrary bytes, message, path, chain-id, pubkey URL or caller-provided key material may reach the signing operation. Define one supported message family explicitly and reject everything else; never expose `tx.sign`, `signBytes`, `rawTx` or broadcast through IPC.

Future **KeyProvider** seam: `publicMetadata(OpaqueKeyReference) -> {keyType,pubkey,address}` and `signBound(EngineCapability,OpaqueKeyReference) -> BoundSignature`. Opaque reference is an identifier, not a key, filepath or token permitting arbitrary signatures. Provider resolves a fixed allowlisted mapping and performs signing inside its boundary; it never returns raw private bytes to the transaction engine. Only the engine can construct capabilities; provider validates peer, binding, lifecycle and request freshness before key use. Capability/domain separation prevents reusing a request as arbitrary document signing. No production KeyProvider/Keychain code is added now.

## macOS Data Protection Keychain note (future design)

Separate opaque key reference, public metadata (pubkey/address/curve) and private scalar material. The existing service uses modern SecItem with `kSecUseDataProtectionKeychain=true`; a future signer must use a dedicated service-owned signer access group with fixed entitlement/code identity, no panel membership, no synchronization, bounded supported policy, and appropriate device/accessibility/user-presence controls subject to provisioning/UX validation. Do not reuse provider API/session/authority encryption secrets as signing keys or share the whole service secret-store capability.

In the helper direction, private material must be read and used **inside the signer provider process**, not passed through service JVM IPC/JSON/logs/env/argv. Service-only means the restricted service signing trust boundary; a separate helper needs its own approved signing/provisioning/access-group design, not automatic access under today's profile. Metadata/presence operations must not retrieve secret values unnecessarily. Minimize key residence/copies, avoid crash dumps and logging, and clear buffers best-effort; do not claim zeroization can erase every runtime/OS copy.

Native Secure Enclave signing is not the BYX curve: Apple documents NIST P-256-only support in [Protecting keys with the Secure Enclave](https://developer.apple.com/documentation/security/protecting-keys-with-the-secure-enclave). P-256 wrapping/authentication is distinct from actual software-held secp256k1 signing. Apple [TN3137](https://developer.apple.com/documentation/technotes/tn3137-on-mac-keychains) explains opting into Data Protection Keychain via kSecUseDataProtectionKeychain; encryption at rest is not proof of non-exportable secp256k1 hardware keys.

## Current production invariant

DEFAULT remains TX_DISABLED with TX mutations false, unavailable signer/keys, absent transport and private gate false. The five typed IPC operations remain unchanged. Private-key/signing operations, synthetic vector material and fake transaction code are test-only, absent from the service JAR; public verification and the inert narrow client are main sources. The panel DEFAULT physically excludes panel/txview; LOCAL_QA contains it through a separate compile-time source profile. This ADR authorizes neither signer implementation nor real keys, transactions, network access, private gates or deployment.


## V2.1S implemented boundary

The dedicated `signer-helper/` Go module has only decred secp256k1 v4.4.0 and x/crypto v0.42.0 (RIPEMD160) dependencies. It imports no Cosmos SDK application, node, RPC, CLI, staking, governance, IBC or EVM. Its fixed MsgSend/DIRECT protobuf encoder is deliberately a closed subset, tested against the existing SDK-produced vector and independent Java/Bouncy Castle encoding. Expanding message support requires new explicit contracts and vectors.

Chosen IPC: private inherited stdin/stdout pipes, uint32 big-endian length + strict JSON v1, then EOF. The helper accepts exactly one frame; a second frame is rejected before signing. Compared with a Unix socket, pipes remove socket discovery, permission/peer-handshake and persistent listener state. TCP/HTTP/gRPC add no benefit here. A one-shot process limits key lifetime, contains crashes, needs no helper replay database and is simple to kill on deadline. A persistent process could amortize startup, but retains secrets/state and adds replay/concurrency/recovery obligations; it is deferred.

The request has 17 exact fields: protocolVersion, requestId, chainId, keyReference, intentKeyReference, publicKeyType, publicKey, sender, recipient, amount, memo, fee, gasLimit, accountNumber, sequence, expectedIntentDigest, expectedBindingDigest. Integers except version are canonical decimal strings. Unknown/missing/duplicate/null fields, case aliases and unsupported version fail closed. No raw bytes/protobuf/Any/HD path/key material are accepted. Chain is compiled `byx`; compressed secp256k1 pubkey Any type is fixed; DIRECT=1; denom is `ubyx`. Account/sequence <=2^62-1, amounts/fees <=2^127-1, gas <=10^12; memo <=256 UTF-8 bytes with no controls. Total request/response <=8192 bytes, Body <=1024, AuthInfo <=512; frame bounds precede allocation. HD default m/44'/118'/0'/0/0 remains the agreed future policy; **no derivation is implemented or accepted as input**.

The confirmed engine request now retains the original typed BankSend intent (including memo) and verifies its equality with quote digest/amount/recipient/memo digest and sender key. Only TxService constructs it in main sources. The helper constructs Body/AuthInfo, SignDoc and TxRaw; the service independently reconstructs all expected bytes before accepting the response. This variant avoids accepting arbitrary protobuf across IPC. Signatures remain exactly SDK-compatible: SHA256(SignDoc), deterministic RFC6979 secp256k1, low-S, 64-byte R||S. Empty memo and zero account/sequence use canonical protobuf omission.

Intent digest uses the existing service domain/length-prefixed encoding. Binding SHA256 uses length-prefixed domain BYX-SIGNER-BINDING-V1, requestId (quote ID), intent digest, Body, AuthInfo, SignDoc. The engine continues to own auth, MFA, quote expiry, gas/fee policy and chain generation. Binding is a consistency commitment, **not peer authentication or an authorization MAC**. Session/MFA/admin/peer tokens never cross these pipes. Production invocation remains unavailable; custody work must define authenticated caller/capability freshness before real keys exist.

The eight-field response contains version, requestId, status, publicKey, signature, txRaw, txHash, bindingDigest. The service rejects extra/missing/duplicate fields and trailing bytes; verifies the pinned public key and derived sender, expected request ID/binding, exact TxRaw correspondence, SHA256 hash and independent BC low-S signature. SignedTx preserves the engine's uppercase hash convention. No response or memo/recipient/amount/signature/private key is logged.

SignerClient.UNAVAILABLE is the only publicly constructible service client. Package-private process composition has **no main-source caller**, no IPC route and no environment/property/file enable switch. Tests inject it; it does not implement or replace the production TxSigner.UNAVAILABLE. Attempts are reserved by quote ID in a bounded client set; retries, including failed/time-out attempts, are denied. The transaction engine remains the principal idempotency owner. Cross-process durable replay state is not implemented, and no production custody claim is made.

Direct ProcessBuilder uses an absolute normalized path, no shell/PATH lookup and fixed arguments, clears the child's environment and discards stderr. Binary SHA256 is pinned and checked before/after launch, symlinks in the path are denied, regular executable/current-user ownership/parent ownership and non-writable-by-others modes are required. These development checks are not a race-free substitute for a signed immutable bundle. Future packaging requires fixed bundle-relative path/expected helper identity, designated requirement, Team ID/code-signature verification, private installation and approved entitlements. No DEFAULT or LOCAL_QA distributable contains a helper or synthetic scalar in V2.1S.

Every exchange has a bounded deadline (50–5000 ms, test normal 2000 ms). Blocking read/write/wait/verification runs on an interruptible worker; timeout/interruption/crash/malformed output maps to SIGNING_FAILED, child is forcibly terminated, streams closed, task cancelled. Production main always supplies UnavailableProvider. SyntheticKeyProvider and its embedded known-vector resource are compiled **only into the non-distributable Go test executable**, never go build's helper. Zeroization is best effort; test-vector copies in test memory are not real custody.

The offline harness confirms the fake BankSend through the engine, captures its service-created request at SIGNING and raises a test barrier so broadcast is never invoked. It then signs via the actual Go test process, verifies in the service and records SYNTHETIC_READY_FOR_BROADCAST; the engine barrier's FAILED status is deliberately not a new production ready state. Existing fake-broadcast tests remain separate.

Remaining work is design only: real provider/Keychain access group/provisioning and user-presence policy, authenticated invocation, lifecycle/expiry and durable replay policy, signed helper identity/immutable installation, approved wallet handling and an independently authorized broadcast transport. V2.1S does not authorize those implementations. Private gate and TX master gate remain false; real keys, wallet import, Keychain and real broadcast remain absent.
