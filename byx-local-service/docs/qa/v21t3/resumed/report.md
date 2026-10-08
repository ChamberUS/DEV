# V2.1T-3 — Synthetic wallet lifecycle, resumed after T-2R

## 1. STATUS

**READY_FOR_PANEL_WALLET_INTEGRATION**. REAL USER KEY: NOT AUTHORIZED. REAL TX: DISABLED. Broadcast attempts, real funds and real user wallets: 0. ETHUSDT research/capture untouched. No Panel integration or push.

## 2. REPOSITORY STATE

Root `/Users/buynnex-corp/dev`; branch `feature/byx-ui-redesign-v1`; starting HEAD `cee1379c935168a594d3e72e4d0ee4729b916c70`. The historical interrupted `docs/qa/v21t3/report.md` and `evidence.json` remain unchanged. Existing packaging/build-app.sh, Panel and iaos-web edits are excluded. The local checkpoint contains only the files listed below; its SHA is provided in the delivery.

## 3. T-2R INTEGRATION

The existing authority lease, strict signed-origin verification, kernel audit-token PID/pidversion, qualified stale-instance discovery, external instance fencing and ESRCH absence proof are reused. No helper is declared dead from timeout, PID alone or socket closure. Every native QA Service startup acquires authority before opening the TEST catalog and reconciles before allowing lifecycle mutation. Native regressions: 24/24 PASS, plus Panel authority denied.

## 4. IMPLEMENTED ARCHITECTURE

WalletCatalog and WalletLifecycle are domain code, with a package-private custody port. The adapter to CustodyClient, synthetic account/session/confirmation seam, native fault harness and actual TxService QA barrier are test-only. DEFAULT has no lifecycle composition, application route, new helper or auto-migration. The existing production-reference guard was preserved.

## 5. WALLET IDENTITY

Random immutable 128-bit catalog/wallet/key-reference/operation identities; no secret-derived identifiers. One non-DELETED wallet per owner. Binding fixes catalogId, ownerAccountId, walletId, signingKeyRef, creationOperationId, origin and algorithm. `SYNTHETIC_RANDOM_SCALAR_V1`, `cosmos-secp256k1`, BYX address, policy version 1, `LOCAL_ONLY_NO_RECOVERY`. No export/import/seed/mnemonic/backup/recovery APIs. Scalar generation/read/signing occurs only in Go signer QA, never Java or IPC.

## 6. PERSISTENCE & DURABILITY

AuthorityState/Codec carry format 4 with walletCatalogVersion 1 only through explicit TEST initialization/migration. Format 3 remains DEFAULT. Protected TEST anchor and AEAD key have dedicated SecretIds; no production authority/Keychain items are used. Catalog stores public wallet identity, operation journal, audit and quarantine; no wallet secret.

The actual Java writer uses owner/private-directory checks, no-symlink checks, exclusive same-directory 0600 tmp, a full write loop, file force, atomic rename without fallback, directory force, protected anchor update, then publication. Real Java 3-byte writes and twelve signed-Service crash points exercise this writer. Stale tmp blocks reload; the harness removes only its own matching regular 0600 tmp after exact Service termination, never promotes it. This is process-crash QA, not hardware power-loss qualification.

## 7. CREATE

Durable CREATING and stable idempotency operation precede receipt/scalar creation. PREPARING receipt precedes create-only scalar SecItemAdd carrying immutable kSecAttrGeneric binding. Existing LIVE identity is inspected, not regenerated. Qualified absence is required; ambiguous errors cannot authorize generation. Startup never generates a missing key; explicit same-operation create retry may finish a pending create. Native nine-boundary matrix: 9/9 PASS.

## 8. RECEIPTS & BINDINGS

PREPARING → LIVE → terminal REVOKED. Public receipt has immutable binding, revision, identity and last operation/digest. Scalar update is unavailable. Legacy QA calls cannot bypass a bound receipt or revoked identity. Repeated delete keeps the same durable operation/receipt. Inventory reads attributes and public receipts only, never scalar values: bounded four-entry pages, complete digest/total/offset checks, maximum 64 references across scalar/receipt union. Overflow or changed/incomplete inventory fails closed.

## 9. RECONCILIATION

| Evidence | Result |
|---|---|
| ACTIVE + matching LIVE receipt, scalar attributes and derived public identity | HEALTHY |
| Metadata with qualified missing scalar | ORPHAN_METADATA; no regeneration |
| Scalar/receipt reference absent from catalog | ORPHAN_KEY; durable quarantine, no adoption/deletion |
| Missing/divergent binding/receipt/public identity, REVOKED + ACTIVE | KEY_MISMATCH; durable quarantine |
| Bad encrypted metadata/anchor/schema | Authority untrusted / CORRUPT_CATALOG |
| Ambiguous native error | KEYCHAIN_UNAVAILABLE; not qualified absence |
| Partial/changed/overflow inventory | INVENTORY_INCOMPLETE; mutation denied |
| CREATING with existing matching scalar/PREPARING | Finish LIVE/ACTIVE without generating scalar |
| Durable DELETING | Resume exact authorized delete, never revert to ACTIVE |
| DELETED + REVOKED + no scalar | DELETED |

Quarantine survives restart and is not cleared merely by restoring the missing artifact. Lock-specific diagnosis is not inferred from an ambiguous native error.

## 10. SIGN

Current owner, enabled account/credential version, recent MFA, current confirmed typed quote, wallet version, ACTIVE/HEALTHY, LIVE receipt and public/key/address bindings precede dispatch. The stable SIGN operation is persisted as SIGN_ATTEMPT_RESERVED before IPC. Java independently verifies DIRECT, secp256k1, low-S, TxRaw, request material and transaction hash. Authorization and confirmation are checked again before completion and delivery. New lifecycle calls static verification only, never legacy SignerClient.verifyIdentity/UnixSystem.

The actual TxService fixture stops at a throwing QA signer barrier before transport; any transport.broadcast invocation fails the test. The captured confirmed request is signed by lifecycle separately and is never fed back into TxService.execute. Lifecycle has no RPC/REST/gRPC/localhost/shell/fallback/relay transport. Native signature/barrier gate: PASS; broadcast attempts 0.

## 11. UNKNOWN RESULT

Dispatch uncertainty or crash preserves SIGN_OUTCOME_UNKNOWN after restart. Reconciliation does not re-sign. Reusing the same quote/digest or idempotency operation is denied; a new explicitly confirmed quote is required. T-2R generation/op/invocation/challenge/digest and exact child identity bind transport separately from the stable lifecycle operation. A failed fence latches dispatch closed. Native pre-sign/post-sign stopped-helper crash cases: 2/2 PASS.

## 12. DELETE

DELETING durable → REVOKED receipt → exact scalar deletion → qualified absence → DELETED tombstone. Signing is denied from DELETING. Revocation wins over older ACTIVE metadata. Native nine-boundary matrix and tombstone restart: 9/9 PASS.

## 13. CONCURRENCY / IDEMPOTENCY

One Service writer, global lifecycle lock → owner lock → short AuthorityStore transaction; CustodyAuthority serializes native exchanges. Query sees the last committed snapshot without waiting on helper I/O. Create/create and delete/delete converge, create/delete rejects stale versions, sign/sign rejects overlap, delete waits behind an unknown sign, and sign during delete cannot dispatch. Bounded futures detect deadlock. Stable owner/action/idempotency/digest semantics survive restart; changed content conflicts. Capacity is explicit (64 wallets/quarantines, 1024 operations/audit), with no silent history eviction.

## 14. CORRUPTION / ROLLBACK

Java/Go fixtures cover truncated/tampered encrypted catalog, duplicate/missing/type/unknown fields, bad anchor, unsupported version, altered WalletId/keyRef/public key/address, malformed tombstone, missing receipt, stale PREPARING, LIVE mismatch, REVOKED with ACTIVE, restored old catalog and incomplete/changed inventory. Protected newer anchor rejects rollback. If an old catalog and its old anchor are restored together while newer REVOKED receipt remains, reconciliation still quarantines; no old key is regenerated or signed.

## 15. AUTHORIZATION / SECURITY

T-1: 65/65 PASS; T-2R: 24/24 PASS; additional Panel authority refusal: PASS. Both T-1 and T-2R observed zero signer IP sockets. Required negatives retain zero Keychain calls before authorization: Terminal, Panel, same-Team wrong role, copied/tampered/unsigned/wrong-ID helper, environment injection and Service direct scalar read. Native malformed/trailing requests are rejected before Keychain. Stale generation reply is rejected. Source/dependency guards retain no network, subprocess, daemon or production QA composition. Logs/probes expose fixed status, counts, boundaries and owned PID only; catalog/receipt/entry toString is redacted, no scalar/private key/seed/mnemonic logged.

## 16. TEST RESULTS

| Current suite | Result |
|---|---|
| Complete Java Service, final `mvn -o -q test` | 458 PASS / 0 FAIL / 0 SKIP; includes 29 new wallet tests |
| Go `go test -p 1 -tags qa -json ./... -count=1` | 59 PASS events / 0 FAIL / 1 SKIP; 16 root PASS + 43 subtest PASS |
| Signed native lifecycle / actual Keychain / writer | 178 PASS / 0 FAIL / 0 SKIP, 34 synthetic fixtures |
| Signed T-2R process fencing | 24 PASS / 0 FAIL / 0 SKIP |
| Integration scenario groups | 41 PASS / 0 FAIL / 0 SKIP, included in the 178 assertions (not extra tests) |
| Authorization/security | 66 PASS / 0 FAIL / 0 SKIP (65 T-1 + 1 Panel authority denial) |
| Rebuilt DEFAULT artifact | 7 PASS / 0 FAIL / 0 SKIP |
| Quality | Ruff PASS; scoped diff check PASS; global check finds only preserved preexisting UI whitespace |

Go SKIP: `TestProcessHarness` deliberately requires a subprocess mode; it is exercised by the Java subprocess tests. Root and subtest counts are disclosed to avoid representing parent assertions as independent scenarios. All native and security cases actually ran. These are current executions, not T-2R historical counts.

Evidence: [summary](evidence.json), [signed lifecycle matrix](native-evidence.json), [fencing matrix](process-evidence.json), [DEFAULT checks](default-evidence.json). Two intermediate failures were corrected, not hidden: initial full Java run caught a forbidden main-domain QA client reference (adapter moved to src/test without weakening guard); first qualified native run caught waiting for an unrequested probe during inventory (probe wait now requires explicit lifecycle probePoint). Original failed-attempt evidence is retained separately from final acceptance evidence.

## 17. DEFAULT SAFETY

Rebuilt artifact: `/tmp/byx-v21t3-default-qualified/BYX-MVP.app`, 7/7 checks PASS, strict deep seal verified and final Service jar SHA-256 matches target. DEFAULT keeps PRODUCTION_DISABLED, tx.mutationsAllowed=false, unavailable production signer, no QA test jar/helper/probe main/transaction lab, no QA automatic authority or namespace and no broadcast. No default application or GUI is launched.

## 18. KNOWN LIMITATIONS

Software-only synthetic custody, no hardware secp256k1, no proven secure erase, lock-screen custody policy or power-loss durability. A compromised trusted Service/signing account or writable development installation is outside this QA isolation guarantee. Joint restoration of catalog, protected anchors and all Keychain receipts lacks an external monotonic/hardware anchor. No recovery or wallet migration; bounded capacity fails closed rather than erasing history. Empirical zero IP sockets plus static no-network guards is not an OS network sandbox. Provisioning is reused and expires on 2026-10-13 (Service) / 2026-10-15 (Signer). Legacy UnixSystem identity issue remains unmodified and unreachable from this lifecycle. Interrupted helper private IPC directories may remain; their existence is never liveness evidence.

## 19. FILES CHANGED

- `byx-local-service/signer-helper/internal/custody/custody_test.go`
- `byx-local-service/signer-helper/internal/custody/native.go`
- `byx-local-service/signer-helper/internal/custody/server.go`
- `byx-local-service/signer-helper/internal/custody/lifecycle.go`
- `byx-local-service/signer-helper/internal/custody/lifecycle_probe.go`
- `byx-local-service/signer-helper/internal/custody/lifecycle_test.go`
- `byx-local-service/src/main/java/byx/service/auth/AuthorityAdmin.java`
- `byx-local-service/src/main/java/byx/service/auth/AuthorityCodec.java`
- `byx-local-service/src/main/java/byx/service/auth/AuthorityState.java`
- `byx-local-service/src/main/java/byx/service/auth/AuthorityStore.java`
- `byx-local-service/src/main/java/byx/service/auth/SecretStoreAnchor.java`
- `byx-local-service/src/main/java/byx/service/auth/SecretStoreKeyVault.java`
- `byx-local-service/src/main/java/byx/service/secrets/SecretId.java`
- `byx-local-service/src/main/java/byx/service/signer/CustodyClient.java`
- `byx-local-service/src/main/java/byx/service/signer/WalletCatalog.java`
- `byx-local-service/src/main/java/byx/service/signer/WalletLifecycle.java`
- `byx-local-service/src/test/java/byx/service/auth/WalletAuthorityTest.java`
- `byx-local-service/src/test/java/byx/service/auth/WalletWriterQaFaults.java`
- `byx-local-service/src/test/java/byx/service/signer/CustodyQaMain.java`
- `byx-local-service/src/test/java/byx/service/signer/CustodyReplyParserTest.java`
- `byx-local-service/src/test/java/byx/service/signer/WalletLifecycleQaCustody.java`
- `byx-local-service/src/test/java/byx/service/signer/WalletLifecycleQaMain.java`
- `byx-local-service/src/test/java/byx/service/signer/WalletLifecycleTest.java`
- `byx-local-service/src/test/java/byx/service/tx/CustodyQaFixture.java`
- `byx-packaging/verify-custody-fencing-qa.py`
- `byx-packaging/build-wallet-lifecycle-qa.sh`
- `byx-packaging/verify-wallet-lifecycle-qa.py`
- `byx-local-service/docs/qa/v21t3/resumed/report.md`
- `byx-local-service/docs/qa/v21t3/resumed/evidence.json`
- `byx-local-service/docs/qa/v21t3/resumed/native-evidence.json`
- `byx-local-service/docs/qa/v21t3/resumed/process-evidence.json`
- `byx-local-service/docs/qa/v21t3/resumed/default-evidence.json`

Artifact/source hashes are in evidence.json. Generated ignored QA/default bundles and encrypted private /tmp fixtures are not committed. Synthetic Keychain items and TEST authority keys were removed by exact QA teardown. No git reset/clean/stash/merge/rebase/push was used. Scientific data, recorder, supervisor, admission, contracts, Panel sources and preexisting unrelated changes are excluded. Capture operational observation at 05:34:01Z/05:34:14Z: the same active .part grew 2,633,553 → 2,981,845 bytes; collector 96558 and supervisor 10657 were not signaled or reconfigured. Final observation at 06:04:15Z/06:06:06Z: the same new active .part grew 5,204,751 → 10,994,143 bytes. Natural chunk rotation was observed. This checks writing only, not scientific integrity or cohort eligibility. Processing ran serially at nice 10; no capture intervention or perceptible impact was observed.

## 20. NEXT ACTION

`PROCEED TO V2.1T-4 — PANEL → SERVICE → SIGNER END-TO-END QA`

Stop for explicit review; T-4 was not started. No production keys, transactions, funds, real user wallets, scientific processing or push were executed.
