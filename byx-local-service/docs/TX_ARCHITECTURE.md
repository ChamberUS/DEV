# TX architecture foundation (V2.1R)

**Status: architecture only. REAL TX: DISABLED.** The pipeline below is fully modelled and tested with a fake signer, a fake chain transport, a draft policy and
synthetic accounts. In the product (DEFAULT and LOCAL_QA bundles) the service composes `TxProduction.disabled(...)`: master gate `false`, policy `TX_DISABLED`,
signer `UNAVAILABLE`, key provider `UNAVAILABLE`, chain TX transport `ABSENT`, authorization `DENY_ALL`. No code path, flag, environment variable, property or file turns
it on. `privateGateAllowed=false` is unchanged and independent.

## Flow and trust boundaries

```
 panel (UI, presentation only)               byx-local-service (AUTHORITY)                          chain
 ┌──────────────────────────┐   typed IPC    ┌───────────────────────────────────────────────┐   (nothing in this phase)
 │ Transaction Lab (QA)     │ ─────────────► │ TxIpc: closed fields, strict validation        │
 │ builds a request         │  tx.prepareBankSend / getQuote / confirm / getStatus / cancel   │
 │ shows the quote          │ ◄───────────── │ TxService                                       │
 │ asks to confirm          │   quote view   │  gate+policy → authz → key → account/sequence   │
 └──────────────────────────┘                │  → simulate → gas/fee → funds → TxQuote         │
   never sees: key, seed,                    │  confirm: re-auth, TTL, chain gen, sequence,    │
   signer, signed bytes                      │  budget, policy → SIGN (TxSigner) → journal hash│
                                             │  → BROADCAST (ChainTxTransport) → follow by hash│
                                             └───────────────────────────────────────────────┘
```

* Verified IPC peer (kernel code identity + pairing) is unchanged. Every `tx.*` call carries the session token; the service revalidates the session (peer, expiry, account
  enabled, credential version) on **every** call through `AuthService.resolveSession`. No peer key (0) = `UNAUTHORIZED`.
* The panel decides nothing. It cannot alter a quote: confirmation references the quote by id and the service signs **only the values stored in its own `TxQuote`**
  (`TxSignRequest` has a package-private constructor; the signer interface accepts nothing else).
* **Invariant:** the panel never sees a private key, seed, decrypted key material, signer object or signed transaction bytes. Responses carry the tx *hash* only.

## Closed intent model
`sealed interface TxIntent permits BankSendIntent` — `BankSendIntent(KeyRef sender, BankAddress recipient, UbyxAmount amount, Memo memo)`. There is no "type string +
payload". `sender` is an opaque key *reference* resolved by the service (`TxKeys`), never an address supplied by the panel. Merchant/payment/certificate messages are
deliberately absent; each future type is added here explicitly. An unknown type cannot be represented, so it never reaches the service.

## Value types (exact, no float)
`UbyxAmount` (1..2^127-1, strict decimal string, never BYX decimal), `GasAmount` (1..10^12), `GasLimit`, `GasPrice` (BigDecimal, scale ≤ 18), `EstimatedFee`,
`MaximumFee`, `TxQuoteId` (128-bit hex), `ClientOperationId` (128-bit hex), `TxIntentDigest` (SHA-256 hex), `AccountNumber`/`Sequence` (0..2^62), `ChainGeneration`,
`BankAddress` (bech32 `byx`, checksum), `KeyRef`, `Memo` (≤ 256 UTF-8 bytes, no control characters). Constructors validate and throw a closed `TxError`.

## Gas / fee engine
```
adjustedGas = ceil(simulatedGas × gasAdjustment)         (draft: 1.10)
gasLimit    = adjustedGas;  require gasLimit ≤ globalGasLimit (draft: 2 000 000)   else MAX_GAS_EXCEEDED
fee         = ceil(adjustedGas × gasPrice)               (matches Cosmos SDK Factory.BuildUnsignedTx / default fee checker)
require fee ≤ maximumFeeBudget (draft: 50 000 ubyx = 0.05 BYX)                      else MAX_FEE_EXCEEDED
```
The six numbers (simulated, adjusted, limit, price, estimated fee, maximum fee) are kept separate in the quote. Gas is **never** reduced silently to fit the budget. At HIGH
(0.05) the budget allows at most 1 000 000 gas; HIGH with 1 800 000 simulated gas (1 980 000 adjusted ≤ 2 000 000 ceiling) fails with `MAX_FEE_EXCEEDED` before signing.
Draft prices (tests only, **not production defaults**): LOW 0.020, STANDARD 0.025, HIGH 0.050 ubyx/gas. Production remains PROVISIONAL; block max gas: POST_CAPTURE_QA_REQUIRED.

## Policy and master gate
`TxPolicy` is owned by the service. Production implementation: `TxPolicy.DISABLED` ("TX_DISABLED"; every getter throws `TX_DISABLED`). `DraftTestnetPolicy` exists only in `src/test`.
`TxGate.TX_MUTATIONS_ALLOWED` is a compile-time `false` constant; `TxGate.PRODUCTION` returns it. The gate is independent of `PrivateCapabilityGate`. `TxService.enabled()` needs the gate
**and** an enabled policy; either missing → `TX_DISABLED`. `TxIpc` answers `TX_DISABLED` **before** any field/session validation, so nothing leaks without UI either.
Guards (`TxNoBypassGuardTest`): no `enableTx/allowTx/skipTxSecurity/txTestMode/fakeSignerMode/broadcastAnyway`; the tx package reads no env/property/file; exactly one production
signer (UNAVAILABLE), one transport (ABSENT), one policy (DISABLED), zero gate implementations, `new TxService(` only inside `TxService.disabled`.

## State machine
`NEW → VALIDATED → SIMULATING → QUOTED → AWAITING_CONFIRMATION → CONFIRMED → SIGNING → BROADCASTING → SUBMITTED → CONFIRMED_ON_CHAIN`; side exits `FAILED`, `EXPIRED`,
`UNKNOWN_OUTCOME` (→ `CONFIRMED_ON_CHAIN`/`FAILED` only by querying the hash). The table is closed (`TxState.canGoTo`); every other transition is refused. Before `CONFIRMED`,
`EXPIRED`/`FAILED`/`AWAITING_CONFIRMATION` may re-quote (`SIMULATING`). From `CONFIRMED` on the operation is final: a new `clientOperationId` is required.

## Quote (`TxQuote`, immutable record)
quote id, intent digest, fee mode, chain id, chain generation, sender, recipient, amount, memo digest, account number, sequence, simulated/adjusted gas, gas limit, gas price,
fee, maximum fee, policy name+version, created/expires (ms). Total debit = amount + fee. The panel only receives a view.

## Intent digest
SHA-256 over an explicit canonical encoding: each field as a 4-byte big-endian length + UTF-8 bytes, in fixed order — domain `BYX-TX-INTENT-V1`, type `bank_send`, sender key ref,
recipient, amount (decimal), memo. Never Java serialization. Any change to sender, recipient, amount or memo changes the digest. Fee mode is **not** in the intent digest (it is part
of the quote, so changing it yields a new quote); the memo digest is also stored separately in the quote.

## Quote TTL (draft)
60 s (`DraftTestnetPolicy.TTL`): long enough to read a confirmation screen, short enough that sequence, gas and fee do not age. Not a production policy; production TTL must be set by
the production policy review. An expired quote is never signed; confirm → `QUOTE_EXPIRED`; re-quote required.

## Account / sequence / chain binding
Prepare snapshots `accountNumber` + `sequence` (strictly parsed, bounded). Confirm re-reads them: any difference → `STALE_SEQUENCE`; the service never re-signs with another sequence,
a new quote and a new confirmation are needed. The quote is bound to chain id + chain generation (reconnect, node or profile change bumps it) → `CHAIN_CHANGED`. Funds are checked at
quote time and again at confirm (`INSUFFICIENT_FUNDS`).

## Untrusted transport data
`ChainTxTransport` returns raw *strings* (`RawAccount`, `RawSimulation`, `RawBroadcast`, `RawTxStatus`); the service parses strictly and fails closed: gas 0/negative/huge/overflow/missing →
`GAS_ESTIMATE_INVALID`; account negative/huge/wrong address/wrong chain/malformed → `ACCOUNT_INVALID`/`CHAIN_CHANGED`. Production transport = `ABSENT` (no HTTP/gRPC TX route exists).

## Signer boundary
`TxSigner.sign(TxSignRequest)` is the only signing entry; `TxSignRequest` can only be built inside `byx.service.tx` from a stored quote. Production = `UNAVAILABLE`. `FakeSigner` (tests):
ephemeral random key in memory, HMAC over the canonical sign doc, never persisted, never logged, no Keychain. Key lookup is behind `TxKeys` (future: service-owned secure key provider;
macOS Keychain/Data Protection is a separate evaluation). See `adr/ADR-TX-001-signer-strategy.md`.

## Authorization hook
`TxAuthorizationPolicy.decide(session, intent, stage)` → `ALLOW | DENY | REQUIRE_MFA | REQUIRE_ELEVATION`, consulted at prepare **and** confirm. Production: `DENY_ALL`. Seams for normal
tx, high-value tx (amount threshold) and admin tx exist; no thresholds are defined now. `REQUIRE_*` does not burn the quote.

## IPC surface (closed)
`tx.prepareBankSend {session, operation, sender, recipient, amountUbyx, memo, feeMode}`, `tx.getQuote {session, operation, quote}`, `tx.confirm {session, operation, quote}`,
`tx.getStatus {session, operation}`, `tx.cancel {session, operation}`. All fields are JSON strings; extra fields, numbers, objects or arrays → `BAD_REQUEST`. There is no sign, broadcast,
signBytes, sendAnyMessage, protobufAny, genericCosmosRequest, rawHttp or rawGrpc, and tests assert their absence. `capabilities` reports `features.txMutations=false` and
`tx{mutationsAllowed,policy,signer,transport}`.

## Idempotency
`operation` = `clientOperationId`: 128-bit random hex chosen by the panel per logical transaction, scoped to the **session**. Same operation + same request (intent digest + fee mode) with a
live quote → the same quote, no new simulation. Same operation + different fee mode/memo/amount/recipient → the previous quote is superseded (confirming it → `QUOTE_NOT_FOUND`).
After `CONFIRMED` the operation is final (`QUOTE_MISMATCH` on re-prepare). Deliberately not the chain-level `request_id` semantics of CreateMerchantV2.
**Double confirm:** the operation lock + state check make repeated/concurrent confirms return the current status; one signature and one broadcast at most (tested with 16 concurrent confirms).

## Unknown outcome
If the broadcast response is lost/ambiguous/unexpected (OUTCOME_UNKNOWN, unreadable response, hash mismatch, unexpected exception) the state is `UNKNOWN_OUTCOME`. The service never resends
and never re-signs; `getStatus` queries by the **tx hash**, which was computed at signing and written to the journal **before** the broadcast (tested by asserting the journal entry exists when the
transport is invoked).

## Persistence design (not implemented)
Owner: the service, under its private home (`~/.byx-local-service/tx/journal.db`, directory 0700, file 0600), separate from the legacy `panel.db` and from the panel's `runtime.db`.
Table `tx_operation(session_hash, operation_id, state, quote_id, intent_digest, fee_mode, chain_id, chain_generation, account_number, sequence, gas_limit, fee_ubyx, tx_hash, error, created_ms,
updated_ms)` with UNIQUE(session_hash, operation_id); append-only `tx_transition(operation_id, state, at_ms)`. Stored: only what quote lifecycle, dedupe, status and unknown-outcome recovery
need (amount/recipient are allowed here because the file is private; they are **not** logged). Never stored: private key, seed, signed bytes, session token. On restart: non-terminal
operations before `CONFIRMED` are expired; `SUBMITTED`/`UNKNOWN_OUTCOME` are resolved by hash; sessions do not survive a restart.

## Audit
Events: `TX_PREPARE, TX_QUOTED, TX_CONFIRM, TX_SIGN_REQUEST, TX_BROADCAST_REQUEST, TX_SUBMITTED, TX_FAILED, TX_UNKNOWN_OUTCOME` (+ `TX_CANCEL, TX_DENIED`). Policy: the service log carries only the
event, the 8-hex prefixes of the operation and quote ids and the closed error code. **Never** amount, recipient, sender, memo, tx hash, signed bytes, key, seed, token (tested).

## Error taxonomy (closed)
TX_DISABLED, UNAUTHORIZED, MFA_REQUIRED, ELEVATION_REQUIRED, BAD_REQUEST, INVALID_ADDRESS, INVALID_AMOUNT, INVALID_FEE_MODE, MEMO_TOO_LARGE, SIMULATION_FAILED, GAS_ESTIMATE_INVALID,
MAX_GAS_EXCEEDED, MAX_FEE_EXCEEDED, ACCOUNT_INVALID, QUOTE_EXPIRED, QUOTE_NOT_FOUND, QUOTE_MISMATCH, CHAIN_CHANGED, STALE_SEQUENCE, SIGNER_UNAVAILABLE, SIGNING_FAILED, BROADCAST_FAILED,
UNKNOWN_OUTCOME, INSUFFICIENT_FUNDS, FEE_TOO_LOW, OUT_OF_GAS, TOO_MANY_OPERATIONS, INTERNAL. Any unexpected exception maps to `INTERNAL`; node/exception text never travels.
Node result codes map: 5→INSUFFICIENT_FUNDS, 13→FEE_TOO_LOW, 11→OUT_OF_GAS, 32→STALE_SEQUENCE, other→BROADCAST_FAILED.

## Ownership
A quote/operation belongs to one session, one peer and one account. Anyone else gets `QUOTE_NOT_FOUND`. Logout, revocation, disabled account or credential change make the session fail
revalidation, so unexecuted quotes are unusable immediately (`sessionEnded` also expires them eagerly). At most 32 live operations per session.

## Transaction Lab (LOCAL_QA only)
`panel.txview.TransactionLab`: fields recipient / amount (BYX, converted to exact ubyx by `TxLabModel`, 6 decimals) / memo / fee mode; shows the 12 states with `(fake)` labels, the fee
preview (amount, simulated gas, adjusted gas + limit, gas price, estimated fee, maximum fee, total debit) and a confirmation block (from, to, amount, fee, total, network, memo, policy);
confirm is disabled when the quote expired, a field changed or a request is in flight; the screen says SIMULATION / FAKE TX. Registered only when `qaBuild()`; reachable only from the
LOCAL_QA command palette; never in the rail; calls run on a worker thread. In a LOCAL_QA *bundle* the service is still `TX_DISABLED`, so the Lab shows that honestly; to exercise the
synthetic pipeline run the panel (dev classpath) against `byx.service.auth.TxLabHost` (src/test). No `--fake-tx` or any production switch exists.

## DEFAULT guarantee
`PRODUCTION_DISABLED` chain profile, TX gate false, private gate false, signer unavailable, transport absent, no TX route. `BYX-MVP --probe-service --ensure-service --tx` proves it
(capabilities + a direct `tx.*` call answering `TX_DISABLED`).

## Signing dependency audit (for the future real signer; nothing added now)
Needs: protobuf messages `cosmos.tx.v1beta1.{TxBody,AuthInfo,SignerInfo,ModeInfo,Fee,SignDoc,TxRaw}`, `cosmos.bank.v1beta1.MsgSend`, `cosmos.base.v1beta1.Coin`, `google.protobuf.Any`, the pubkey
type (`cosmos.crypto.secp256k1.PubKey`; **verify** whether BYX accounts are plain secp256k1 — app coin type is 118 — or `eth_secp256k1` before choosing), SIGN_MODE_DIRECT over deterministic
`SignDoc` bytes, ECDSA secp256k1 with RFC 6979 and low-S, 64-byte r‖s, SHA-256, address = bech32(`byx`, RIPEMD160(SHA256(compressed pubkey))), account number, sequence, chain id.
Already present in the service: BouncyCastle `bcprov` 1.78 (secp256k1, RFC 6979 `HMacDSAKCalculator`, RIPEMD160), Jackson, JNA, sqlite-jdbc. Missing: protobuf runtime in the service
(the panel bundle ships `protobuf-java` 4.33.5) or a hand-written minimal encoder for 7 small messages; generated classes from `BYX/proto`. No heavy crypto dependency is required.

## Capture protection in this phase
No BYX node, no Binance, no heavy stress; nothing here touches the recorder.
