# V2.1S — minimal Go signer foundation

Status: **READY_FOR_KEY_CUSTODY_DESIGN**. REAL TX: **DISABLED**.

## Evidence scope

This is offline synthetic signing, with the existing SDK golden vector and independent Java/Bouncy Castle verification. No node, real wallet, Keychain, scientific data processing, live Binance connection, real broadcast, production packaging or push was performed. The capture was observed through process identity and file metadata only.

R-1 isolated artifact checkpoint: `22cab56`, eight explicit ADR/vector/report/evidence/test/guard files. Pre-existing panel build/source and packaging guard changes were left unstaged because the checkpoint authorization listed artifacts/tests/guards, not a general product checkpoint. V2.1S changes are separate. The BYX repository has no new production changes.

## Implemented flow

Confirmed synthetic BankSend -> engine-created TxSignRequest -> strict framed stdin/stdout v1 -> one-shot Go test helper -> deterministic DIRECT signature/TxRaw -> independent service verification -> synthetic ready-for-broadcast observation. The engine test barrier prevents any fake or real broadcast call. No new production transaction state or public IPC operation is added.

Helper: `byx-local-service/signer-helper/`. Dependencies: decred secp256k1 v4.4.0 and x/crypto v0.42.0 (RIPEMD160); dependency graph has no node/SDK app/RPC/REST/CLI/gov/staking/IBC/EVM. Closed protobuf subset locked to the SDK/Java golden bytes.

IPC/lifetime: uint32 big-endian framed JSON, one request + EOF per process. No socket/TCP listener, shell, PATH search, unbounded JSON, arbitrary protobuf, arbitrary chain/HD path, private key input or auth/session/MFA token. Child environment cleared; stderr discarded; no signing payload logs. Test provider is exclusively in `_test.go`; the production binary always uses UnavailableProvider. Synthetic scalar is a test resource, never distributed.

Protocol/request/response, exact field names and all bounds are in ADR-TX-001 and source structs. Chain=byx, type=Cosmos compressed secp256k1, Any URL fixed, DIRECT=1, denom=ubyx. Default HD path remains the future policy; no HD derivation/input now. Frame <=8192, body <=1024, auth <=512, memo <=256 UTF-8 bytes. Helper reconstructs Body/AuthInfo/SignDoc/TxRaw; service reconstructs and verifies independently. Request ID is quote ID, local attempted-ID set rejects retries; engine owns principal idempotency. Binding hash commits ID/intent/body/auth/doc, but is not authentication. Future custody must resolve authenticated caller/freshness.

Service SignerClient is public only as UNAVAILABLE; package-private test process composition has no production callers. Independent response checks: exact schema, version, ID, pinned pubkey/address, binding, byte-exact TxRaw, SHA256 tx hash, BC secp signature and low-S. Failures/timeouts/crashes map to SIGNING_FAILED; bounded exchange, child kill/stream cleanup, cancellation. No production path constructs an enabled client.

Process identity: absolute normalized path, no symlink traversal, current-user-owned regular executable, matching owner/non-writable parent, pinned SHA256 before/after launch. Future signed-bundle Team ID/designated requirement validation and race-resistant installation remain unimplemented. These are development checks, not production code-signing proof.

## Preserved production boundary

DEFAULT artifact helper: ABSENT. LOCAL_QA distributable helper: ABSENT. Main JVM contains verification/inert client, not private-key signing or synthetic resources. Production signer UNAVAILABLE, production real KeyProvider/Keychain/user wallet UNIMPLEMENTED, real transport ABSENT, private gate false, TX master gate false. Only the offline non-distributable test executable can resolve the vector scalar.

## Capture observations

Beginning: supervisor 10657 and collector 96558 match continuous ETHUSDT command; .part grew 57,240 bytes / 3 seconds (age 0.43 s).
Middle: natural session handoff from 20261007T230929Z to 20261007T234116Z; new .part grew 41,673 bytes / 3 seconds (age 0.53 s). No intervention. A transient absence of fresh .part at natural finalization was not treated as capture failure.
End: same supervisor/collector identities; current events-00001.jsonl.gz.part grew 20,779 bytes / 3 seconds (age 1.81 s). Capture remained RUNNING; never altered.

## Performance

Small offline observations, not a throughput benchmark. Production process startup/deny (three cached executions): 12.615–14.159 ms, median 13.796 ms. Synthetic signing process (three executions): 25.533–46.159 ms, median 42.906 ms, including launch/framing/sign/exit. /usr/bin/time -l cold observations: 0.50 s startup/deny, 0.52 s test signing; peak RSS 2,818,048 / 9,015,296 bytes. Cold launch includes OS overhead and differs from cached runs. Go first synthetic signing 12.470 ms (includes lazy curve initialization). Directed service measurement with the two-processor test JVM: complete exchange 77.176 ms, independent verification 2.790 ms. Final isolated golden-vector rerun in a clean JVM: 804.102 ms complete exchange, 4.166 ms independent verification (includes cold JVM/provider initialization; not a steady-state latency claim). Binary size: unavailable helper 5,180,176 bytes; final non-distributable test executable 6,258,992 bytes. Measurements are illustrative, not guarantees. Build concurrency 2, nice +10, Java heap 512 MiB, Go test build memory target 256 MiB.

## Tests

Go closed-schema/framing/malformed/replay/vector/determinism/provider checks and go vet: PASS. Java targeted final SignerClientTest: 24 tests PASS. Initial combined targeted run had 57/58 passing; one negative fixture mutated an ID to its existing first nibble. Fixture corrected to always change it; subsequent client runs passed. Engine/no-bypass/vector tests from that combined run passed. Final complete service suite: one run, 405 tests, initially 403 PASS and two PeerIdentityTest failures caused by JAVA_TOOL_OPTIONS added to bound the test JVM. The security guard correctly denied that launch environment. Only PeerIdentityTest (7 tests) was rerun without that variable: PASS; 405 unique tests PASS across the full run plus clean peer rerun. No security rule was relaxed and the full suite was not repeated.

No panel tests in this phase: no panel changes in V2.1S. No full BYX tests, heavy stress, custody or real-network/transaction tests.

## Files and commits

Created: signer-helper/go.mod, go.sum, cmd/byx-signer-helper/main.go, internal/signer/protocol.go, protocol_test.go, testdata/byx-direct-vector.json; main SignerClient.java, CosmosBankSend.java; test SignerClientTest.java, SignerRequestFixture.java; this report and evidence.json.
Changed: TxPorts.java, TxService.java (retain confirmed typed intent), TxNoBypassGuardTest.java (restricted constructor assertion), ADR-TX-001.
Commit R-1: 22cab56. V2.1S: separate local implementation commit, identified in the delivery (no push).

## Next boundary

READY_FOR_KEY_CUSTODY_DESIGN only after final checks pass. This authorizes no Keychain implementation, wallet import, seed/mnemonic handling, node, broadcast, private gate or production signing.


## Artifact and gate evidence

DEFAULT service JAR SHA256: 8961393bf16fb12b81029d33409f546439afc355d1018eb532aa6119798c349c. Profile resource: PRODUCTION_DISABLED. ZIP member inspection found no helper, synthetic provider, vector JSON, test harness or testdata. TxGate.TX_MUTATIONS_ALLOWED=false, PrivateCapabilityGate.PRIVATE_CAPABILITIES_ALLOWED=false, EXPLICIT_REVIEW_REQUIRED=true. Keys and real broadcast remain UNAVAILABLE/ABSENT. No production packaging changes in V2.1S.

A final Go parser review tightened recipient validation to ASCII bytes, matching canonical Bech32 and eliminating rune-to-byte truncation. Dedicated address/vector/protocol tests and one final Java real-process golden-vector test were rerun: PASS; no contract was expanded.

Logs (local, not committed): /private/tmp/byx-v21s-go-final.log, byx-v21s-client-final.log, byx-v21s-service-full.log, byx-v21s-peer-clean.log, byx-v21s-package.log. Measurements: byx-v21s-perf.json, byx-v21s-perf-sign.log, byx-v21s-perf-start.log. Test outputs contain only result/size/timing metadata in the new signing harness; no private signing payload is logged.
