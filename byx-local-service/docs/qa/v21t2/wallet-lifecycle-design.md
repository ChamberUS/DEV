# V2.1T-2 — Wallet lifecycle design

Status: **READY_FOR_SYNTHETIC_WALLET_LIFECYCLE_IMPLEMENTATION**, design only.
**REAL USER KEY: NOT AUTHORIZED. REAL TX: DISABLED.** This document authorizes no implementation or execution of V2.1T-3. No wallet, key, Keychain item, profile, production catalog or broadcast was created in this phase.

## 1. Audited baseline and responsibility map

Repository root `/Users/buynnex-corp/dev`; branch `feature/byx-ui-redesign-v1`; audited HEAD `687119574096f89518942f8009eff7d5d8e3681e`. Recent custody commits: `6871195` (T-1), `c04a441` (T), `553737e` (S), `22cab56` (R-1). Working tree is not clean: pre-existing packaging `build-app.sh`, Panel `pom.xml` / `PanelApp.java`, iaos-web modifications and unrelated untracked trees remain untouched. No reset/clean/stash/rebase/merge/push or commit in T-2.

Java class paths in tables abbreviate `src/main/java/byx/service/`; Go custody paths abbreviate `signer-helper/internal/custody/`. Other paths are relative to byx-local-service unless prefixed `../`.

| Responsibility | Actual implementation | Gap / future change |
|---|---|---|
| Panel public projection | No wallet DTO/API exists; `account/AccountDtos.java` is Binance account data, not a wallet | New closed wallet DTOs; no Panel changes now |
| Account ownership/auth | `auth/Account`, `AuthorityState`, authenticated service boundary | Wallet ownership must use immutable authority Account.id, not username/email/role |
| Wallet storage | No WalletRecord/catalog in `AuthorityState` | Versioned catalog and operation journal inside protected authority state |
| Protected persistence | `AuthorityStore.mutateRaw/writeFileAtomic`, `AuthorityCodec` format 3, AES-GCM + HMAC/version anchor | Explicit wallet-domain schema migration; durable directory sync and writer lock qualification |
| Caller/helper identity | `signer/CustodyClient`, `identity/CodeIdentity`, `MacSecurity`; custody `SelfCheck/Serve` | Preserve reciprocal token/code/origin checks; fresh checks at secret access/output boundaries |
| QA IPC | T-2R `CustodyClient`/`CustodyAuthority`: external verified fencing, OS-held lock, protocol 3 epoch/operation/request binding; 15 s timer is best effort only | Wallet-domain lifecycle protocol, bounded complete inventory and stable errors |
| Scalar custody | custody `native.go` `KCAdd/KCGet/KCDelete`, `server.go` `provision/derive/signOp` | Immutable creation binding and public revocation receipts; strict load validation |
| Public inventory | `KCList` internally; external ops only count/cleanup, lookup returns public identity | Authenticated paged inventory; no secret data returned |
| Signing | `internal/signer/protocol.go`, `CosmosBankSend`, `SignerClient.verifySigned` | Resolve WalletId/version before TxKeys; durable attempt and wallet lock |
| TX engine | `tx/TxService`, `TxPorts.TxKey/TxSignRequest` | Existing per-operation locks are not a wallet lock or durable signing journal |
| Artifact isolation | `../byx-packaging/build-custody-qa.sh`, `verify-custody-qa.sh` | T-3 stays in QA artifact; DEFAULT remains unavailable |

[T-1 report](../v21t1/report.md) and [evidence](../v21t1/evidence.json) record 65 PASS/0 FAIL and 409 service tests/0 failures. They were read, not rerun. These prove the listed synthetic cases, not crash-safe wallet lifecycle, screen-lock semantics, OS sandboxing or production readiness.

T-1 signer identity: `com.buynnex.byx.signer.qa`; group `W5Z65G9UP2.com.buynnex.byx.signer.qa.keys`; namespace `byx.signer.qa.synthetic.v1`; Data Protection GenericPassword, WhenUnlockedThisDeviceOnly, synchronizable=false. QA signer profile expires 2026-10-15 01:03:43 UTC; recorded service profile expires 2026-10-13 16:49:55 UTC. No provisioning run or renewal here. T-3 must recheck actual validity before execution.

Current production source remains `TxGate.TX_MUTATIONS_ALLOWED=false`, `TxService.production`: TxKeys.UNAVAILABLE, TxSigner.UNAVAILABLE, transport ABSENT, authorization DENY_ALL. CustodyClient is a QA client, not production composition.

## 2. Normative identity, ownership and cardinality

MUST/NEVER below specify the future synthetic implementation; they do not describe missing code as implemented.

* WalletId: Service generates 128 CSPRNG bits, exactly 32 lowercase hexadecimal characters. Logical identity scoped to catalogId and immutable owner Account.id. Unique globally within catalog including tombstones; immutable and never reused. Non-secret; may appear in owner-filtered APIs. Collision probability is approximately n(n−1)/2^129; collision check is still mandatory before persistence.
* SigningKeyRef: Service independently generates another 128-bit random hex value, same grammar, distinct from WalletId and every prior ref. It is not an alias, address, path, Keychain persistent reference or bearer credential. Stable across restart; never rotated in place or reused, even after deletion. Only Service and authenticated signer know it; omit from Panel DTOs/logs.
* Binding: `(catalogId, ownerAccountId, walletId, signingKeyRef, creationOperationId, origin, algorithm)` is immutable. catalogId is another 128-bit random metadata identifier belonging to the protected authority. Every wallet has exactly one ref; every ref has exactly one wallet. Duplicate ref/WalletId/public-key identity in a catalog fails closed, never selects the first record.
* Public identity: compressed secp256k1 pubkey, exactly 33 bytes/66 lowercase hex, independently validated point; canonical BYX address derived using existing CosmosBankSend public-key/address callable. Both immutable after activation. Address/public key are bound together, not supplied by Panel.
* QA origin: `SYNTHETIC_RANDOM_SCALAR_V1`; scalar generated only inside signer, range 1..n−1, stored as exactly 32 bytes. No seed, mnemonic or HD claim. This follows T-1. ADR-TX-002's older proposed envelope/HD creation is not implemented or silently authorized by T-2; production origin/derivation remains a separate real-key review.
* Chain identity: fixed allowed family BYX and allowed chain ID `byx` in policy. Observed chain generation, account number, sequence, balance and observedAt are separate transient service chain state, never wallet identity. Account number/sequence updates do not mutate WalletId/ref/pubkey/address. No production chain configuration is enabled.
* V1 cardinality: at most ONE non-DELETED wallet per authority account, including CREATING, DELETING and unhealthy wallets. Multiple owners are supported by the storage schema, not multiple live wallets per owner. Tombstones do not occupy that slot. A new wallet after confirmed deletion gets new IDs/address. There is no default/switch API: the sole live wallet is default; after deletion there is none. Label is optional, ≤64 Unicode scalar values, no control characters, never a lookup selector, never logged. Multi-wallet/switching requires a later version/review.

Panel owns only public presentation and explicit user requests. Service owns account authorization, catalog/operation journal, public identity, lifecycle decisions and transaction confirmation. Signer owns scalar access, protected creation/revocation receipts, identity checks and signing. Service may hold authority encryption/auth material already required by its own domain; it NEVER holds wallet scalar/seed/mnemonic or a reconstructing representation.

## 3. Persistence contract and invariants

Future WalletCatalog is a versioned domain **inside** AuthorityState/AuthorityCodec, not panel.db, plaintext wallet JSON or a new production credential store. Current format 3 rejects additional fields and has no wallet persistence. Target outer format 4 explicitly adds `walletCatalog` while preserving accounts/providers/devices/migrationFreeze; inner `walletCatalogVersion=1`. Migration is explicit and authorized, not startup fallback. T-3 exercises this only with synthetic accounts/test anchor and encryption vault in an isolated private QA directory; never opens/migrates real authority data or initializes real authority keys.

Proposed catalog schema (all wallet fields are non-secret):

```text
WalletCatalogV1
  catalogId, revision, walletCatalogVersion=1
  wallets[], operations[], auditEvents[], quarantines[]
WalletRecordV1
  walletId, ownerAccountId, signingKeyRef, creationOperationId
  origin=SYNTHETIC_RANDOM_SCALAR_V1, algorithm=cosmos-secp256k1
  allowedChainId=byx, policyVersion=1, recoveryPolicy=LOCAL_ONLY_NO_RECOVERY
  publicKey/address (null only while CREATING has not resolved identity)
  durableState=CREATING|ACTIVE|DELETING|DELETED
  version>=1, label, createdAtMs, updatedAtMs, deletedAtMs|null
  deletionOperationId|null, publicCreationReceiptDigest|null
  quarantineReason|null, quarantinedAtMs|null
OperationRecordV1
  operationId, ownerAccountId, idempotencyKey, action, requestDigest
  walletId, expectedWalletVersion, status, outcomeCode
  createdAtMs, updatedAtMs, attemptState, publicResult|null
AuditEventV1
  eventId, operationId, walletId, action, resultCode, timestampMs
QuarantineRecordV1
  anomalyId, signingKeyRef, reason, firstObservedAtMs, resolvedAtMs|null
```

Strict duplicate-field detection, exact schema, bounded lists/text/numbers, no unknown version/coercion, no duplicate identifiers, canonical byte serialization and owner/ref/cardinality checks before commit. Public keys/address/nullability checked by state. QA limits: 64 lifetime wallet records including tombstones and 1,024 operation records/audit entries each; capacity exhaustion returns CAPACITY_EXCEEDED before mutation, never truncates/prunes identity or idempotency history. Production limits/scaling are outside this QA design.

Directories 0700/files 0600, current UID, regular files only, no symlink in any path component. Single writer holds a kernel file lock for the entire Service lifetime, including reconciliation; failure to obtain it means no mutable wallet service. Within that owner, catalog transactions use the AuthorityStore lock. Reads return immutable snapshots; no plaintext debug dumps, sensitive record toString or raw internal DTO serialization.

Commit order: validate -> create exclusive regular same-directory .tmp with 0600/O_NOFOLLOW -> write all bytes (loop on partial write) -> force file -> atomic rename -> fsync directory -> advance protected version/MAC anchor -> publish in-memory revision. Failure at any point means operation outcome UNKNOWN until reload/reconciliation. Never continue IPC after uncertain pending-record persistence. No weaker non-atomic rename fallback. Old snapshot ahead/behind or anchor mismatch uses existing fail-closed anchor logic, qualified by crash tests; missing file with anchor or missing anchor with file is CORRUPT_CATALOG, never bootstrap-empty.

Current writer has force(file)+atomic move but no directory fsync, fixed temporary filename/delete and a single FileChannel.write; it is a reuse point, not proven complete lifecycle durability. Future work must qualify/improve it in QA, without changing real authority data in T-2. Metadata backup/restoration is not secret recovery and cannot override signer revocation or authorize orphan adoption. Restoring snapshot AND Keychain/anchor together can evade file-only rollback defenses: real deployment requires an independently reviewed rollback/restore policy.

Quarantines are durable (maximum 64 refs, overflow fails closed). KEY_MISMATCH and unexplained ORPHAN_KEY remain blocked across restart until an explicitly reviewed remediation resolves them; a later healthy-looking scan cannot silently clear them. No orphan is assigned an owner or WalletId. Quarantine persistence failure itself blocks mutations. LOCKED/unavailable observations are transient and never manufacture quarantine or missing-key evidence.

## 4. Protected public receipts and versioned IPC

Two signer-only compiled QA services in the SAME signer-exclusive access group are proposed:

1. existing scalar namespace `byx.signer.qa.synthetic.v1`: account=SigningKeyRef, value=scalar[32]; add immutable non-secret binding attributes (`kSecAttrGeneric`, bounded canonical encoding).
2. new public receipt namespace `byx.signer.qa.synthetic.v1.lifecycle`: account=SigningKeyRef, value contains only binding, receiptVersion=1, state PREPARING/LIVE/REVOKED, pubkey/address when established and monotonic receipt revision. No scalar/hash-of-scalar/seed/token/key. Same Data Protection/accessibility/synchronizable policy. Receipt is a signer-owned safety record, not another secret.

These additions do NOT exist in T-1. They require an explicitly approved T-3 change: exact namespace APIs, no caller-provided service/group/path. No production service/group is chosen or written now. Receipt REVOKED is terminal, cannot become LIVE, and is retained after scalar deletion. This prevents an old catalog ACTIVE snapshot from re-enabling a deleted/ref-revoked key. It does not defeat full privileged Keychain rollback or certificate compromise.

Cross-item add/update/delete is NOT atomic. Crash rules below explicitly tolerate incomplete combinations. `SecItemAdd` is create-only/no overwrite. `SecItemUpdate` may change only receipt state/revision/public completion fields under matching immutable binding; never scalar or immutable ownership. Absent, ambiguous, conflicting or malformed receipt is never treated as a healthy key. Existing T-1 scalar without the new binding/receipt is an orphan/legacy item, NOT automatically migrated/adopted.

Future outer protocol is version **3**, separate from protocol-2 regression. The nested DIRECT request remains the existing strict version-1 signing schema and numerical/protobuf callable; this design does not change fees, canonical bytes or supported BankSend fields. Exact closed per-operation schemas reject duplicates, unknown fields/statuses, trailing JSON/frames and invalid types; 8,192-byte frames remain bounded. Invocation ID 128 bits and helper challenge 256 bits are fresh per process. Stable operationId and canonical SHA-256 requestDigest are separate from invocation ID/challenge. Reply echoes protocol/op/operationId/digest/invocation/challenge plus closed public result; no credential/MFA/session token, native error text, item location or secret. SHA binding is not a MAC and does not authenticate a compromised Service; kernel code identity authenticates the caller.

Operations: provisionBound, inspectBound, inventoryPage, revokeDeleteBound, signBound. Broad cleanup/count are QA harness utilities only, never wallet APIs. Preserve exact AccessGroup, direct child/parent, code seal/origin, LOCAL_PEERTOKEN and empty env/FD checks. Recheck live identity/channel/direct-parent before every secret read/write and before reply. Receipt match is not a replacement for caller authorization.

Authenticated inventory enumerates **attributes/public receipts only** from exactly the two compiled services, never kSecReturnData for scalar inventory. Page ≤8 entries, total ≤64 refs (including receipt-only refs), snapshotDigest, totalCount, complete and nextOffset; sorted by ref. Full scan under Service global lifecycle lock; digests must agree across one-shot page calls. Capacity overflow, missing page, malformed item or change during scan returns INVENTORY_INCOMPLETE and blocks mutations; never infers missing key from partial inventory. Known-key inspect may derive pubkey inside signer for consistency; only public material returns. Unknown orphan inspection does not adopt it.

## 5. Formal state machine

Durable intent and observed health are distinct. Durable states are CREATING/ACTIVE/DELETING/DELETED. ABSENT is no record. Observed health is HEALTHY, LOCKED, KEYCHAIN_UNAVAILABLE, DEGRADED, ORPHAN_METADATA, ORPHAN_KEY, KEY_MISMATCH or CORRUPT_METADATA. API presents the specific effective state while retaining pending intent internally. ORPHAN_KEY is an inventory anomaly, not an adopted WalletRecord. KEY_MISMATCH covers binding/pubkey/address/ref corruption. No health observation rewrites public identity.

| Effective state | Detector | Allowed operations/recovery | Forbidden | Public code / sign |
|---|---|---|---|---|
| ABSENT | Service trusted catalog | query, explicit create | sign/delete arbitrary ref | WALLET_NOT_FOUND / no |
| CREATING | durable Service intent | query, bound inspect, authorized same-op resume | another create, sign, delete-before-settled | CREATE_IN_PROGRESS / no |
| ACTIVE | Service + signer consistency | query, synthetic sign or explicit delete under guards | identity edit, implicit rotation | none / only QA gates |
| LOCKED | proven native policy observation | query, explicit recheck after unlock | signing/generation/prompt loops | KEYCHAIN_LOCKED / no |
| KEYCHAIN_UNAVAILABLE | signer/native context | query, explicit retry after availability | declare missing/repair/create | KEYCHAIN_UNAVAILABLE / no |
| DEGRADED | Service transport/inventory failure | query, bounded complete reconciliation | mutation/sign until resolved | SIGNER_UNAVAILABLE or INVENTORY_INCOMPLETE / no |
| ORPHAN_METADATA | known live record, qualified item-not-found | query, explicit discard/delete metadata identity | regenerate/adopt replacement | KEY_NOT_FOUND / no |
| ORPHAN_KEY | signer inventory lacks catalog/intended binding | diagnostic quarantine, separately authorized QA cleanup | adoption/sign/automatic deletion | ORPHAN_KEY / no |
| KEY_MISMATCH | Service or signer binding/derived identity | query, quarantine, explicit reviewed remediation | overwrite/rebind/sign | KEY_MISMATCH / no |
| CORRUPT_METADATA | catalog strict parser/MAC/anchor | redacted health only, explicit diagnostic review | empty bootstrap, partial parse, sign/create/delete | CORRUPT_CATALOG / no |
| DELETING | durable Service delete intent or signer REVOKED | query, same-op resume; no identity changes | sign/create same owner until settled | DELETE_IN_PROGRESS / no |
| DELETED | durable tombstone + scalar absent | query tombstone, idempotent delete result, new distinct wallet | resurrect/reuse ref/sign | WALLET_NOT_ACTIVE / no |

All transitions not listed below are denied with WALLET_NOT_ACTIVE/CONFLICT, no Keychain mutation. Unauthorized events leave state unchanged. Observation failures can overlay any nonterminal intent but never erase CREATING/DELETING.

| Current State | Event | Guard | Next State | Side Effect | Failure |
|---|---|---|---|---|---|
| ABSENT | create | authenticated owner, capacity, no owner live slot, trusted catalog | CREATING | durable IDs/binding/idempotency intent first | no IPC on persistence failure |
| CREATING | provision acknowledgement / reconciliation | exact pending binding, receipt LIVE, derived identity valid | ACTIVE | atomic public identity + success journal | remain pending/UNKNOWN on lost commit |
| CREATING | retry with no scalar | same authorized operation, qualified absence, receipt absent/PREPARING only | CREATING | resume exact IDs; receipt then create-only scalar | unavailable/mismatch: no generate |
| CREATING | conflicting item/receipt | mismatch or missing binding | KEY_MISMATCH | quarantine, retain intent | never overwrite/adopt |
| ACTIVE | proven lock/unavailable/inventory failure | supported observation | LOCKED/KEYCHAIN_UNAVAILABLE/DEGRADED | health overlay, invalidate attempts | no signing |
| LOCKED/KEYCHAIN_UNAVAILABLE/DEGRADED | explicit recheck | full trusted match + original durable ACTIVE | ACTIVE | refreshed health only | keep blocked if ambiguous |
| ACTIVE | qualified scalar missing | receipt/binding understood, exact lookup | ORPHAN_METADATA | retain original identity | no key regeneration |
| ACTIVE | pubkey/address/ref differs | derive/binding/structural check | KEY_MISMATCH | disable/invalidate attempts | no signing |
| any | catalog parse/MAC/anchor failure | error confirmed | CORRUPT_METADATA | global mutation lockout | no partial recovery |
| inventory-only key | startup/full scan | no known exact catalog binding | ORPHAN_KEY | quarantine; block create while unexplained | no adoption |
| ACTIVE/ORPHAN_METADATA | explicit delete | owner + fresh consent/version; identity not ambiguous | DELETING | durable intent revokes service signing first | uncertain commit: reconcile first |
| DELETING | revoke/delete retry | same exact deletion/binding; receipt REVOKED or safely revocable | DELETING | retain receipt REVOKED; exact scalar delete | unavailable: retain pending |
| DELETING | qualified absent confirmation | signer receipt REVOKED and exact scalar NOT_FOUND | DELETED | tombstone + result/audit commit | do not publish DELETED on unknown |
| ACTIVE restored from stale catalog | receipt REVOKED observed | trusted signer binding | DELETING | sign denied; reconcile deletion | never REVOKED→LIVE |
| DELETED | same delete retry | same owner/idempotency digest | DELETED | return recorded success | changed digest: CONFLICT |
| DELETED | new create | fresh IDs, owner slot empty | new record CREATING | old tombstone preserved | never resurrect old record |

## 6. Create protocol, retry and crash boundaries

Create authorization uses authenticated Service account ownership, account enabled/credential version current, explicit create consent and acknowledged LOCAL_ONLY_NO_RECOVERY. No Panel ref/public key/owner override. QA uses synthetic sessions/test policy; production wallet mutation capability stays absent.

1. Under global lifecycle and owner locks, reserve owner slot and generate WalletId/ref/operationId; bind Panel idempotency key to action + canonical requestDigest + owner.
2. Commit CREATING + operation PENDING before contacting signer. If commit uncertain, reload trusted state before any retry; never allocate new IDs to satisfy an old request.
3. Authenticated helper provisionBound checks exact binding. Add PREPARING receipt first. Existing LIVE same binding returns its verified public result WITHOUT generation; PREPARING same binding resumes; REVOKED/conflicting receipt refuses. Only qualified scalar NOT_FOUND permits generation. Other native errors never mean absence.
4. Generate valid scalar internally; atomically add scalar + immutable binding attributes in ONE SecItemAdd. Never replace existing value. If duplicate, inspect matching binding; unknown/conflict blocks. Finalize receipt LIVE with independently derived pubkey/address. Best-effort wipe and exit.
5. Service validates point/address, binding and receipt, then atomically commits ACTIVE + public identity + operation success/audit. No success response before this commit. Recheck session before delivering response; if revoked after creation, retain identity for later authenticated query rather than delete/regenerate it.

| Crash boundary | Durable observations | Required resume |
|---|---|---|
| Before pending commit | no pending record/no authorized signer call | same request may create once after trusted reload |
| After pending commit, before helper | CREATING, no item | explicit same operation resumes exact IDs |
| After PREPARING receipt, before scalar add | matching pending + PREPARING, scalar absent | same bound authorized create may generate once |
| After scalar add, before receipt LIVE | pending + PREPARING + bound scalar | derive SAME scalar, finalize receipt; never regenerate |
| After receipt LIVE, before public reply | pending + LIVE + bound scalar | inspectBound returns same public identity |
| After reply, before ACTIVE commit | pending + LIVE | reconcile same binding, commit same identity |
| After ACTIVE commit, before response | ACTIVE + successful operation | return recorded owner-filtered result |
| Catalog/receipt missing or mismatched unexpectedly | orphan/uncertain identity | fail closed; never infer a successful old create |

Timeout may mean key creation succeeded. Return TIMEOUT_UNKNOWN_RESULT + operationId; retry same idempotency key performs inspect/reconciliation, not another logical create. Automatic startup may finish matching LIVE/PREPARING-with-existing-scalar into ACTIVE; it may NOT generate missing scalar. Missing scalar under stale CREATING remains CREATING until explicit authorized retry. No cancellation silently deletes a possibly created key.

## 7. Startup reconciliation and query contract

On startup, acquire exclusive writer lock, load/validate entire trusted catalog and establish qualified signer/profile context. Until complete inventory/reconciliation, mutation/sign disabled. Persisted ACTIVE is not enough to sign before this generation's checks. Never delete temp/catalog files blindly, derive absence from process error, or scan other Keychain groups/legacy namespaces.

| Catalog / protected signer observation | Decision |
|---|---|
| ACTIVE + LIVE exact binding + scalar matches derived pubkey/address | ACTIVE/HEALTHY |
| ACTIVE + scalar missing in qualified context | ORPHAN_METADATA, retain identity |
| Key/receipt without any catalog/intended binding | ORPHAN_KEY, no adoption/generation; new create blocked pending review |
| Known ref, wrong owner/wallet/create op/catalog or duplicate ref | KEY_MISMATCH, global catalog integrity failure if duplicate |
| Pubkey or independently derived address differs | KEY_MISMATCH, never rewrite catalog to match key |
| CREATING + matching LIVE or bound scalar/PREPARING | finish public receipt/catalog commit without generation |
| CREATING + qualified absent scalar | CREATE_IN_PROGRESS, explicit authorized retry required |
| DELETING + LIVE or PREPARING | revoke before any access/sign, then exact deletion |
| DELETING + REVOKED + scalar exists | retry exact delete, no sign |
| DELETING + REVOKED + scalar absent | commit DELETED tombstone |
| DELETED + scalar reappears or non-revoked receipt | inconsistency quarantine, no sign/adoption/automatic erasure |
| Missing/corrupt receipt or old bare T-1 scalar | legacy/orphan quarantine, no implicit upgrade |
| Malformed/unknown-version catalog | CORRUPT_CATALOG; version migration only by explicit future command |
| Partial/overflow/changing inventory or locked/unavailable Keychain | specific unavailable health; never missing/orphan verdict from it |

Receipts are authoritative only for monotonic signer revocation/creation binding; Service catalog is authoritative for owner authorization. Both must agree. The signer never proves a caller's current account session; Service must enforce that independently. Auth account deletion/rename/re-role cannot delete, adopt or reassign wallet identity; disabled/deleted owner blocks operations pending explicit review.

Panel read projection: walletId, optional label, address, publicKey only if explicitly needed, effectiveState, signerAvailability, recoveryPolicy, createdAt/updatedAt/version, allowedActions, redacted recovery reason, chain observation freshness. No SigningKeyRef, catalogId, ownerAccountId from other owners, namespace/group/SecItem reference, receipts, raw signature/TxRaw/token/native status. Unavailable chain is separate from unavailable custody. Signer public inventory never becomes a Panel API.

## 8. Sign guards and uncertain outcomes

WalletId → owner-filtered trusted record → SigningKeyRef → TxKey/key-address + pinned wallet version → typed confirmed TxSignRequest → qualified helper → verified public result. Existing KeyRef is a sender alias and TxKey.keyId is resolved ref; do not relabel old string semantics or accept arbitrary Panel references. Introduce a typed WalletId intent selection in the future adapter; it resolves before creating the closed intent and pins wallet version/ref. Only service-owned resolution participates in intent/binding digests.

Required guards: durable ACTIVE + HEALTHY; trusted current catalog and complete reconciliation; owner enabled and authenticated current peer/session/credential version; same wallet/ref/pubkey/address/version; explicit confirmed quote; current chainId/generation/account number/sequence; exact policy/gas/fee/amount/recipient/memo bindings and expiry; synthetic key origin; synthetic-only signing policy; SINGLE wallet operation lease. Preserve TxService.revalidate checks and Java independent low-S, DIRECT SignDoc/TxRaw/hash/binding verification.

Commit SIGN_ATTEMPT_RESERVED before helper. Hold wallet/global lifecycle lock through access, verification and attempt result commit; no delete may begin in between. Helper checks LIVE receipt and immutable scalar attributes, valid length AND range (no modulo normalization), derives actual pubkey/address and compares expected identity before signing. Never arbitrary bytes/Any, fallback key, mutated chain parameters or reuse of old challenge. No result may bypass independent service verification.

IPC retains a 12 s Service budget and one call/process. The helper's 15 s timer is **BEST_EFFORT_SELF_TERMINATION**, defense in depth only: SIGSTOP prevents its callback from running. It is never a security boundary or proof of termination. Lifecycle deadline starts at dispatch with monotonic accounting; waiting for locks counts toward the future lifecycle Service budget. No interactive auth prompt or longer deadline in QA. Any native interaction-required/ambiguous condition denies. Fresh challenge per attempt; transport invocation/request IDs are not durable idempotency IDs. Duplicated socket request is refused, not executed twice.

Lost/late response or service crash after dispatch marks SIGN_OUTCOME_UNKNOWN; NO automatic re-sign/rebroadcast. On restart RESERVED/dispatched attempts are UNKNOWN, not replayed. An explicit new confirmation/new operation after fresh quote and lease is required; deterministic signature math does not justify blindly retrying an uncertain operation. Late results after deletion/version/session change are discarded. Signing is not a chain mutation and never increments local sequence speculatively.

IMPORTANT: current TxService.execute immediately progresses from sign to broadcast. It MUST NOT be directly composed with a real transport for lifecycle QA. T-3 uses the existing test-only signing barrier/CustodyQaFixture with a transport that makes any broadcast an assertion failure (counter must remain zero). No production TxService route/gate or private capability is enabled.

## 9. Delete protocol and tombstones

Explicit owner-authenticated fresh confirmation names WalletId + expectedVersion + loss acknowledgement; require recent MFA via existing policy seam for eventual real custody, not admin's ability to silently delete another owner's wallet. QA uses synthetic credentials/consent only. Sign/creation lease must have settled; CREATING cannot be canceled by destructive blind cleanup. KEY_MISMATCH/CORRUPT require reviewed repair, not ordinary delete. ORPHAN_METADATA may be explicitly discarded with exact binding and qualified absence.

1. Atomically commit DELETING, deletionOperationId, version++, operation PENDING and audit before helper; signing denied immediately.
2. Helper revokeDeleteBound verifies exact binding. Commit/create a REVOKED public receipt BEFORE attempting scalar deletion; cannot be undone. Known missing scalar may still receive a bound revocation receipt. A conflicting/malformed receipt is refused.
3. Delete ONLY exact scalar group/service/ref. errSecItemNotFound in qualified context counts as absence; unknown native status does not. Retain receipt, never broad cleanup.
4. Confirm qualified scalar NOT_FOUND with REVOKED receipt. Commit DELETED tombstone, deletion time, success operation/audit atomically; retain IDs/ref/public identity and ownership inside catalog. Panel gets tombstone public result, not a receipt/location.

Crash before (1): no delete occurred. After (1) before (2): startup resumes pending authorized deletion, never re-enables sign. After receipt revocation before scalar deletion: scalar may remain but cannot be signed; retry same operation deletes it. After scalar delete before catalog commit: DELETING remains and reload confirms absence then tombstones. Lost success response returns the same result via same idempotency key. Disk/Keychain unavailable retains pending state; never report successful deletion. A qualified missing item is idempotent only for this known bound deletion, not an arbitrary foreign ref.

DELETED is terminal for that identity. Receipt/tombstone retention is lifetime/no pruning in V1; capacity fails closed. Audit records use event/operation/wallet IDs and closed codes only. No metadata-first removal and no still-ACTIVE metadata after a known deletion. Scalar deletion is a logical Keychain removal verified by lookup; it is NOT proof of secure erase of CFData/Go temporaries, swap, crash dumps, backups or privileged copies. [Apple SecItemDelete](https://developer.apple.com/documentation/security/secitemdelete(_:)), [item-not-found](https://developer.apple.com/documentation/security/errsecitemnotfound).

## 10. Recovery and UI state policy

V1 **LOCAL_ONLY_NO_RECOVERY**, BACKUP_NOT_SUPPORTED, EXPORT_NOT_SUPPORTED, IMPORT_NOT_SUPPORTED. No mnemonic/export/restore/adoption route. Loss of item/device can permanently lose signing ability; generating a replacement is a new address and not recovery. Public metadata backup does not recover a private key. ThisDeviceOnly prevents migration to another device but does not prove no same-device backup restoration. [Apple accessibility](https://developer.apple.com/documentation/security/ksecattraccessiblewhenunlockedthisdeviceonly), [backup behavior](https://developer.apple.com/documentation/security/restricting-keychain-item-accessibility).

Future options requiring separate review: mnemonic, encrypted export, wrapped backup, recovery package, external signer, hardware signer, multisig. None is implemented/selected as a current capability. Software secp256k1 custody and best-effort wiping remain explicit; no hardware non-exportability or guaranteed secure erase claim.

Future Panel copy: “Local wallet; no supported backup or recovery. Losing this device/key can permanently prevent signing.” Show before create/delete and in wallet detail; do not expose technical namespace/Keychain codes as recovery instructions. Initial UI still supports one wallet only.

| Presentation | Actions |
|---|---|
| No wallet | create only if synthetic wallet capability explicitly authorized; TX remains disabled |
| Creating | query operation; same-request resume when authorized; no sign/delete/new create |
| Ready — local only/no recovery | public query; explicit synthetic sign/delete only under backend guards |
| Locked | query/recheck after unlock; no automatic prompts/sign/create/delete |
| Signer unavailable / inventory incomplete | query/recheck; all mutations disabled |
| Needs attention: missing key/orphan/mismatch | query diagnostics; no sign/adoption; only authorized remediation appropriate to state |
| Deleting | query/retry same authorized operation; no sign/new create |
| Deleted | tombstone query; fresh distinct create if policy permits |
| Corrupt catalog | health only; all mutations disabled |

allowedActions is server-derived and not an authorization token. Panel compromised with an authenticated session may request allowed operations: service account policy and future helper-owned user consent matter; caller identity alone does not prevent it.

## 11. Concurrency and idempotency rules

Single Service writer/process, one global lifecycle mutex (including complete inventory and all signer invocations), then owner/wallet mutex, then short AuthorityStore transaction. Acquisition order never reverses. Do not hold AuthorityStore monitor while waiting for IPC/native UI. No second helper starts until first process has exited/been terminated and its result is reconciled. Read queries use last committed snapshot and health flags and can show pending state while mutation holds wallet lock.

* Two creates for same owner: same key+digest joins one operation; different key returns CONFLICT/CREATE_IN_PROGRESS; no second ID/key allocation. Different owners serialize in V1.
* create+delete: delete refuses CREATING; finish/reconcile creation first.
* delete+sign: whichever acquired wallet lease first settles; after DELETING commit no new sign allowed. Unknown sign outcome must settle as UNKNOWN before delete may be authorized; no hidden retry.
* Two signs: second returns WALLET_BUSY rather than dispatching in parallel; it requires its own explicit quote/confirmation afterwards.
* query+delete: committed DELETING observed immediately; public identity stable throughout.
* Service restart: **EXTERNAL VERIFIED QUIESCENCE**. Acquire the exclusive process-held authority lock, generate an unpredictable epoch, discover stale signer instances using OS process metadata, and require exact executable origin, Apple chain, Team, identifier and strict seal. Signal only a verified instance using its kernel audit token (PID + pidversion), then require OS proof that that exact instance no longer exists. Suspended authorities retain the lock. Suspended helpers must be terminated externally; elapsed time, EOF and watchdogs prove nothing. Any discovery, identity, signaling or absence uncertainty returns `CUSTODY_QUIESCENCE_UNPROVEN` and blocks dispatch. Only after all stale instances are proved gone may the generation become CUSTODY_QUIESCENT. Reconcile before lifecycle mutations; RESERVED/dispatched signs remain UNKNOWN. Never kill an unrelated PID.
* Timeout/cancel/EOF/malformed response/shutdown: retain the previous exact helper identity and authority lock; externally terminate if needed and verify absence before dispatching another helper. Failure remains fail-closed. Generation + operationId + requestDigest and authenticated helper pidversion bind responses; old or duplicate responses are refused.
* Process quiescence is independent of result certainty. A terminated helper may have already mutated: persist UNKNOWN and never retry that operation blindly. The T-2R public checkpoint is fencing metadata only, not a wallet journal, receipt store or a substitute for T-3 reconciliation. Corrupt/partial checkpoint blocks startup. OS polling deadlines bound attempts; they never manufacture a positive absence result.

Idempotency keys are Panel-generated random 128-bit lowercase hex, not session tokens; scoped by immutable owner + action. OperationId is independently Service-generated 128-bit hex. Canonical digest covers action, owner, WalletId/version or create policy/label/loss acknowledgment; excludes transport nonce, auth token and current timestamps. Same key/different digest or action => CONFLICT. Existing op reads still require current owner auth. No idempotency history expiry/eviction in V1; capacity error before further mutation. Create/delete durable results are replayed as public outcomes, not by repeating side effects. Transient failure carries operationId and retryability; no new operation hidden behind a retry button.

## 12. Future Service ↔ Panel API and error mapping

Closed DTOs reuse authenticated IPC/session resolution, not raw CustodyClient.call from Panel. Route names below are new design, NOT currently available. Requests are exact schemas and omit KeyRef/namespace/public key/owner override. Service derives owner from auth. Session tokens remain only at existing auth transport boundary and never cross signer IPC.

| Operation | Request | Response / idempotency |
|---|---|---|
| listWallets | no owner filter input | owner-only public projection list, at most one live + explicit tombstone page |
| getWallet | WalletId | owner-filtered public projection; foreign ID indistinguishable from missing |
| walletHealth | WalletId | effectiveState, signer/chain availability, allowedActions, closed reason/version |
| signerHealth | empty | availability + closed reason only; no paths/profiles/group/peer details |
| createWallet | idempotencyKey, optional label, recoveryAcknowledged=true | operationId, PENDING/COMPLETE, public wallet; same-key resume contract |
| deleteWallet | WalletId, expectedVersion, idempotencyKey, lossAcknowledged=true | operationId, PENDING/COMPLETE + tombstone public result |
| getWalletOperation | operationId | owner-filtered action/status/public result/closed error |

No generic sign endpoint, key export/import, orphan adoption, namespace cleanup, key inventory or metadata rewrite endpoint. Signing uses typed TxService confirmation seam in QA; Panel never receives SignedTx.bytes or a signing capability. Version disagreement returns CONFLICT, not latest-version mutation. UI never relies on native OSStatus.

| Domain code | Evidence / retry policy | Infrastructure mapping |
|---|---|---|
| WALLET_NOT_FOUND | owner-filtered no record | not-found; no foreign disclosure |
| WALLET_NOT_ACTIVE | durable/observed state disallows action | fixed domain validation |
| KEY_NOT_FOUND | exact query absent with trusted context | errSecItemNotFound only; never creation fallback |
| ORPHAN_KEY | full inventory ref without intended binding | quarantine, no automatic adoption |
| KEY_MISMATCH | immutable binding/derived pubkey/address differs | deny, public fixed reason |
| KEY_CORRUPT | scalar invalid size/range or malformed protected record | deny, never normalize bytes |
| KEYCHAIN_LOCKED | lock proven by supported observation | ambiguous auth/native statuses MUST NOT map here |
| KEYCHAIN_UNAVAILABLE | ambiguous access/context/policy/interaction | deny; explicit noninteractive recheck |
| SIGNER_UNAVAILABLE | absent/expired/crashed/timed-out pre-dispatch | fixed availability reason; no missing-key inference |
| SIGNER_UNTRUSTED | identity/origin/seal/peer fail | no payload/Keychain access; no fallback |
| CREATE_IN_PROGRESS / DELETE_IN_PROGRESS | known pending operation | poll/resume same bound operation only |
| WALLET_BUSY | conflicting operation lease | no queued automatic sign |
| RECOVERY_UNSUPPORTED | import/export/recovery requested | no fallback |
| CORRUPT_CATALOG | parse/MAC/anchor/path/version failure | global fail closed; redact native/filesystem text |
| INVENTORY_INCOMPLETE | overflow/change/missing page/native failure | no absence decisions/mutations |
| CONFLICT | stale version/key-digest/uniqueness/owner slot | no mutation |
| CAPACITY_EXCEEDED | fixed QA catalog/inventory limits | no truncation/eviction |
| TIMEOUT_UNKNOWN_RESULT | mutation/sign may have reached helper | operation ID; inspect for create/delete, never auto re-sign |
| UNAUTHORIZED / FORBIDDEN / FEATURE_DISABLED | auth/session/owner/capability failed | no helper launched |

Internal diagnostics record native/IPC/filesystem categories behind closed redacted codes; no raw exception/toString, challenge transcript, scalar, verifier, receipt payload, signature, TxRaw or transaction memo/amount/recipient in logs. Public fields are not automatically safe for unrestricted logs. Current CustodyException SIGNING_FAILED/TIMEOUT is insufficient to distinguish unknown mutation outcomes; add mapping before lifecycle QA.

## 13. Lifecycle threat model and release gates

S = required for T-3 synthetic acceptance. K = required before any REAL USER KEY. X = additional requirement before REAL TX. None of these gates authorizes its phase.

| Threat | Existing Control | Remaining Risk | Required Before Synthetic | Required Before Real Key | Required Before Real TX |
|---|---|---|---|---|---|
| Malicious same-user/foreign process | kernel token + signed exact Service role before SecItem | authentic trusted binary compromise not prevented | S: keep negative caller matrix, zero calls | K: audited installation/runtime trust | X: transaction policy/consent |
| Compromised Panel | no signer entitlement; service auth boundary | can misuse an already authorized session | S: ownership/confirmation negatives | K: explicit loss consent + helper-owned sensitive-action policy | X: trusted transaction summary/auth |
| Compromised Service | cannot directly read scalar | trusted Service can ask helper to sign/delete; HIGH residual | S: document authority, strict binding | K: explicit threat acceptance/presence policy or different signer boundary | X: narrowly scoped sign policy independent confirmation |
| Helper replacement/copy/TOCTOU | static/live seal, origin, child token | dev bundle writable; HIGH race risk | S: preserve copy/seal negatives; state development limitation | K: non-user-writable verified install and launch-race qualification | X: immutable release provenance |
| Catalog/ref tampering | auth AEAD/HMAC/version anchor | schema/owner/ref checks missing today | S: strict schema + bound receipts/identity negatives | K: wallet migration + trusted persistence qualified | X: pin record version into confirmation |
| File rollback | authority version anchor | jointly restored anchor/Keychain or root can rollback; HIGH | S: snapshot rollback + receipt revocation tests | K: explicit independent restore/revocation policy; cannot claim solved by local receipt | X: authoritative chain replay/sequence handling |
| Orphan injection | T-1 no adoption/overwrite | no durable binding/inventory API | S: complete inventory + exact pending receipts | K: no auto adoption, reviewed recovery policy | X: unhealthy wallets never eligible |
| Symlink/path substitution | helper origin/private socket checks; authority load checks | mutable file ancestors/write races | S: O_NOFOLLOW/exclusive write/owner/lock negatives | K: installation and dir-FD durability qualification | X: no arbitrary transport/config paths |
| Process/env injection | hardened runtime, empty helper env, banned caller env, token | trusted JVM/JIT and signing key compromise remain | S: preserve env/FD denial matrix | K: debug/core/diagnostic policy and release audit | X: auth revocation + quote guards |
| IPC replay/duplicate JSON/request | challenge, one-shot, bounded frame | outer duplicates currently accepted; response binding incomplete | S: protocol 3 exact parser, echoed binding, delayed-trailing denial | K: review native boundary/cancellation | X: durable signing attempt; no auto retry |
| Concurrent Service/helpers | per-Tx operation lock only | lacks durable lifecycle singleton | S: kernel writer lock + global/wallet ordering + crash tests | K: qualify lock lifetime/power-loss behavior | X: sequence reservation/cross-TX conflict policy |
| Another app of same Team | exact signer entitlement group in current binaries; wrong-role QA deny | wildcard profile/signing credential can grant group; HIGH | S: repeat real signed negative roles | K: signing-account/certificate security, release entitlement review | X: key/authorization operational audit |
| Local user/root/debugger | software secret, best-effort wipe, process exit | no guarantee against root/kernel/memory copies; HIGH | S: no secret logs/files, transient lifetime assertions | K: explicit software-custody acceptance, diagnostics/security qualification | X: funds risk acceptance |
| Backup/restoration/reinstall | ThisDeviceOnly, no sync | same-device restoration can be inconsistent | S: missing key/catalog/revoked restore cases | K: recovery/loss decision and rollback strategy | X: no fund recovery claims |
| Profile expiry/changed Team/group | macOS code/entitlement eligibility | availability loss, not proof of missing item | S: valid existing profile preflight; unavailable mapping | K: sustainable approved release provisioning | X: validity/identity monitoring; deny expiry |
| Network | native QA has no IP code; empirical zero inet sockets | NO App Sandbox; no OS enforcement against future compromise | S: dependency/source/FD/socket tests | K: reviewed confinement/network-denial policy qualification | X: signer never receives transport capability |
| Secret residue | bounded one-shot, C/Go wipe | secure erase not guaranteed; CFData/runtime/swap copies | S: best-effort cleanup even failures | K: accept software-memory threat; qualify failure diagnostics | X: no claims of non-exportability |
| Lost user consent/auth after dispatch | current TxService revalidation | state can change while helper operates | S: version/lease result discard + negative races | K: presence and auth boundary review | X: revalidate before signing/output/broadcast stages |

Screen-lock/fast-user-switch/sleep-wake accessibility behavior is NOT proven by T-1's happy path. K requires supported platform tests; ambiguous state denies. T-3 may mock these transitions and qualify synthetic lock cases later without claiming real lock enforcement. Secure Enclave is not a secp256k1 non-exportable substitute. No sandbox/provisioning/legacy repair in this design phase.

## 14. Known blockers and technical debt (no fixes now)

| File / function | Actual behavior | Impact / future required correction |
|---|---|---|
| custody/native.go `qa_kc_list/KCList` | fixed 8,192-byte buffer; entries that do not fit or cannot convert are skipped without failure | structural blocker for complete reconciliation; explicit overflow/malformed detection + bounded pages before T-3 PASS |
| custody/server.go `decodeOuter` | DisallowUnknownFields but no duplicate-name rejection | lifecycle parser ambiguity; exact duplicate/trailing schema tests before T-3 PASS |
| custody/server.go `provision` | any KCGet error other than found proceeds to generation; existing found returns ALREADY_EXISTS without binding | cannot classify timeout/absence or safely complete same-op retry; qualified NOT_FOUND-only create + protected receipts |
| custody/native.go `KCGet`, server `derive/signOp` | size check, no explicit range check on stored scalar; KCGet may return nil with success when native length !=32 | corrupted-record ambiguity; explicit KEY_CORRUPT + range validation before derivation, not modulo normalization |
| custody/server.go `Serve` | caller checked once before request; Pending tests currently available trailing bytes only | pre-access/output freshness and delayed-extra-frame semantics must be specified/tested in protocol 3 |
| signer/CustodyClient `parseReply/exchange` | loose outer reply field/status interpretation; mutation timeout maps SIGNING_FAILED | strict response/status/operation correlation, TIMEOUT_UNKNOWN_RESULT classification |
| auth/AuthorityState/AuthorityCodec | no wallet catalog; strict format 3 | schema migration and synthetic-only fixtures needed, not production ready |
| auth/AuthorityStore `writeFileAtomic` | force(file), atomic move; no directory force, single write, fixed tmp name | qualify full durable write + no-follow + lock in future implementation; no current power-loss proof |
| tx/TxService `execute` | broadcasts after successful sign | QA must retain explicit no-broadcast signing barrier; no blind production composition |
| signer/SignerClient `verifyIdentity` | uses UnixSystem / jdk.security.auth absent in packaged jlink runtime | legacy stdio path remains unusable there; CustodyClient avoids it and uses verifySigned only. Separate future repair; NOT lifecycle fallback |

Original T-2 was design-only. T-2R subsequently implemented the external quiescence/fencing primitive; see [T-2R evidence](../v21t2r/report.md). Missing wallet lifecycle features remain work for the explicitly reviewed T-3 scope. These remaining findings block full T-3 acceptance, not the fencing primitive. Real-key/TX blockers in section 13 remain separate and stronger.

## 15. V2.1T-3 synthetic implementation/acceptance matrix

T-3 requires explicit user authorization. Existing T-1 namespace/group/identities remain synthetic; only the proposed lifecycle receipt service is added after review. QA data/catalog/anchors live under a fresh 0700 per-run test root, never real ~/.byx-local-service or panel.db. Test encryption/auth fixtures are not wallet secrets. All wallet scalars remain helper-only. No real funds, node, mnemonic, private Binance, production transport or broadcast.

Implementation order: value/schema/domain logic in memory -> synthetic isolated persistence/journal -> strict protocol/bound receipts/inventory/native mapping -> Service orchestration adapter -> targeted synthetic crash/concurrency tests -> separately packaged signed role integration. Do not run broad builds before source stabilizes; resource limits/one worker preserve scientific capture, without inspecting or changing it.

| Gate | Required proof for READY_FOR_PANEL_WALLET_INTEGRATION |
|---|---|
| Happy path | create → public query → Service restart → exact resolve → synthetic DIRECT sign independently verified → revoke/delete → query DELETED; scalar NOT_FOUND; no identity change |
| Create crash matrix | kill at every boundary in section 6, including rename/fsync/anchor and all SecItem boundaries; same-key retry preserves IDs/pubkey, ≤1 live scalar per ref, no unknown-result second creation |
| Delete crash matrix | kill before/after DELETING commit, receipt revoke, scalar delete, absence proof, tombstone/result commit; sign never succeeds after revoke; retry converges to same tombstone |
| Unknown persistence | disk full/partial write/rename/dir sync/anchor unavailable; valid prior snapshot preserved where possible, otherwise fail closed; no IPC before trusted pending intent |
| Orphan A | active metadata + qualified missing scalar ⇒ KEY_NOT_FOUND, zero generation |
| Orphan B | scalar/receipt without catalog ⇒ ORPHAN_KEY, no adoption/sign/automatic cleanup |
| Reconciliation | pending/no key requires explicit retry; matching bound existing key finishes without generation; inventory partial/change/overflow fails closed |
| Corruption | duplicate JSON, unknown fields/version, malformed scalar size/range, corrupt receipt/catalog/MAC/anchor, duplicate refs/owner slot/pubkey, stale tmp/symlink ⇒ no mutation/sign |
| Mismatch | wallet/ref/owner/catalog/create op/public key/address/origin mismatch; panel-supplied ref cannot select key |
| Rollback/restore | ACTIVE old catalog + REVOKED receipt cannot sign; DELETED plus injected scalar fails; simultaneous protected-state rollback documented outside QA guarantee |
| Concurrency | same/different-key simultaneous creates; cross-owner serialization; create+delete, delete+sign, query+delete, two signs, second Service lock denial |
| Idempotency | same-key exact digest returns same operation/outcome; changed digest conflicts; timeouts reconcile create/delete only; no sign replay on restart/timeout |
| Authorization | owner isolation/current auth/disabled owner/revoked credential/stale version/loss consent; Panel/direct/foreign/wrong role refuse before Keychain |
| Native signed integration | preserve T-1 identity/access-group matrix and no IP sockets; same-Team wrong role cannot read scalar; invalid/copy/expired identity is unavailable/untrusted, not missing key |
| Framing/freshness | duplicate/unknown/trailing/delayed second request, stale challenge/wrong op/digest/version, parent death, pid reuse, late reply; no key access before auth |
| Sign binding | low-S/pubkey/address/DIRECT bytes/TxRaw/hash/request binding independent verification; fake no-broadcast transport assertion and counter=0 |
| Cleanup | exact QA scalar refs only; public tombstones retained during tests; separate authorized harness teardown of both exact synthetic services after run; no production/foreign namespace affected |
| DEFAULT regression | no helper/test provider/vector/QA API/receipt storage in DEFAULT, signer UNAVAILABLE, TX/private gates unchanged |
| Secret exposure | no scalar/mnemonic/seed/token in Service/Panel DTO/log/file/env/argv or public receipts; helper lifetime bounded; wiping explicitly best effort |
| Test evidence | targeted tests first; one clean full service suite after source stabilizes, all PASS; signed packaged synthetic matrix all PASS; no skips claimed as native proof |

All gates are mandatory for T-3 acceptance. If profiles expire before execution: BLOCKED, no weaker custody or automatic provisioning workaround. `READY_FOR_PANEL_WALLET_INTEGRATION` means a **synthetic-only** lifecycle can be integrated into a later explicitly authorized Panel phase; it does not mean production keys or TX are authorized. Production persistence migration, immutable installation, presence/lock policy, recovery/loss acceptance, certificate/rollback/confinement qualification and transaction transport/consent gates require separate review.

## 16. T-2 delivery boundary

Created only this design document. No production/QA code, packaging, Panel, signer, Keychain, provisioning, authority or scientific dataset was modified. No tests/builds or T-3 execution; documentation/source audit only. Document consistency/whitespace checks PASS. Global `git diff --check` reports unrelated pre-existing whitespace in iaos-web Layout.jsx/MyStore.jsx; left untouched. Existing 65/409 results remain historical evidence, not tests rerun in T-2.

Next action: **review and explicitly authorize V2.1T-3 — Synthetic Wallet Lifecycle QA against this contract.**

**REAL USER KEY: NOT AUTHORIZED. REAL TX: DISABLED.**
