# ADR-TX-001 — Future real signer strategy

Status: **PROPOSED (decision deferred)**. No real signer is implemented, chosen for production, or authorized by this ADR. Context: the service owns authorization, quotes and the
signer boundary (`TxSigner`, `TxSignRequest`); the panel never sees keys. BYX is a Cosmos SDK chain (coin type 118, SDK 0.53.x, bech32 `byx`); MsgSend gas ≈ 94 k.

## Options

| | A. In-process Java signer | B. Minimal Go signer helper | C. External / hardware-style wallet | D. `byxd` CLI subprocess |
|---|---|---|---|---|
| Security boundary | Same JVM as the service: a service RCE = key access; key lives in heap | Separate OS process with its own entitlements; can be sandboxed, no network, tiny IPC | Key never enters our machine's processes; user approves on a device | Separate process but a **general-purpose** binary with a huge command surface |
| Key exposure | Heap, GC copies, swap; mitigable (char[]/zeroing, Keychain ref) but weak in Java | Key in a small process's memory only for the signing call; can use Keychain/Secure Enclave directly | Minimal: only signatures cross the boundary | Key via keyring/CLI flags/stdin/files; `--from`, keyring backends; argv/env/log leakage risks; password prompts |
| Protobuf compatibility | Must re-implement or vendor SignDoc/TxBody encoding; drift risk vs SDK | Uses the **same Go types as the chain** (cosmos-sdk `x/auth/tx`), byte-exact SignDoc | Wallet builds the doc itself (Keplr/Ledger amino/direct quirks) | Exact (same code as the node) |
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

## Direction (not a decision)
Preferred order to evaluate next: **B** (minimal Go helper that consumes a bound `TxSignRequest` and returns signature/tx bytes, no network, identity-verified peer, key from a service-owned
key provider) for the app, with **C** as the high-value/hardware tier, and **A** only for test vectors/fakes. Before choosing: (1) confirm the account key type (secp256k1 vs eth_secp256k1);
(2) golden-vector suite produced by the chain's own Go code; (3) threat model of key storage (Keychain/Data Protection vs Secure Enclave limits: SE does not support secp256k1, so keys would be
software-held and Keychain-wrapped); (4) helper entitlement/provisioning cost; (5) review of `TxSignRequest` as the helper's input contract. Any real signer needs its own explicit approval phase.
