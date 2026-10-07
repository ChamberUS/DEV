# V2.1R-1 — signer preconditions and DEFAULT artifact hardening

Status: **READY_FOR_SIGNER_IMPLEMENTATION_DESIGN**. **REAL TX DISABLED**.
Base commit678f508; no commit/push in this round. No production signer, real wallet, keychain access, node or broadcast.

## Capture health and historical incident

Supervisor10657 and collector96558 identities match continuous ETHUSDT futures campaign
`ethusdt-futures-continuous-20261002T204051Z`, streams aggTrade/bookTicker/depth/markPrice,
depth100ms, chunk1800s, continuous launcher. Initial active `.part` +41468bytes/3s;
intermediate observations +40847 and +59193bytes/3s; final observation +44377bytes/3s,
last-write age0.315s. Session rotation remained natural; recorder/config/campaign/admission untouched.

Historical session `microstructure-20261007T220638Z-usd_m_futures`: existing admission
`admitted=false`, reason REAL_SEQUENCE_GAP. User-reported incident: ~63s with both
connections silent. Classification **CORRELATED_NOT_CAUSAL**; Maven is not asserted as the cause.
No raw events were read or sessions reclassified.

## Chain contract

**KEY TYPE: Cosmos secp256k1**, normal SDK CLI/keyring default, not eth_secp256k1.
Coin type118 is a derivation namespace, not proof of curve. Default HD path
`m/44'/118'/0'/0/0`; bech32 account prefixbyx; public key Any
`/cosmos.crypto.secp256k1.PubKey`. Pubkey compressed SEC1 33bytes, address20bytes =
RIPEMD160(SHA256(compressed pubkey)), then Bech32 byx. Partial EVM branch excluded from this decision.
SDK supports other registered algorithms/modes; this does not claim consensus forces all accounts to secp256k1.

SIGN_MODE_DIRECT is supported/default; the proposed signer only accepts this mode.
Body: typed MsgSend Any and memo; AuthInfo: pubkey Any, Single DIRECT, sequence, fee/gas;
SignDoc: exact body bytes, auth bytes, chain-id, account_number; TxRaw: body/auth/signature.
Sequence is indirectly signed through AuthInfo. Successful included ante consumes sequence even
when a later message fails; local CheckTx alone is not evidence of committed account advancement.
ECDSA signature = low-S64byte R||S over SHA256(SignDoc), no recovery byte/DER wrapper.

Evidence files/line references and options are in [ADR-TX-001](../../adr/ADR-TX-001-signer-strategy.md).

## Synthetic vector and independent proof

[Golden vector](../../../src/test/resources/tx/byx-direct-vector.json): **TEST VECTOR — NOT A REAL WALLET**.
Private scalar1 is deliberately public/test-only, selected directly, not from a real seed/HD wallet.
Account7, sequence9, chainbyx, gas120000, fee3000ubyx. The resource contains full public/test-only
private scalar, compressed public key, address, body/auth/doc/raw hex and SHA256 hashes, signature and tx hash.

Producer BYX `tools/signing-vector/main.go`: actual pinned SDK types/crypto, signature verification and
TxRaw decoder/encoder byte roundtrip. Independent Java test `SyntheticSigningVectorTest`: manual protobuf
wire encoder, BC curve multiplication/address derivation, ECDSA RFC6979/low-S and raw encoding.
All bytes/hashes/signature match. Altered SignDoc rejected. Tx hash:
`aa0241aad9cd15c6048569cf5499b7509d3fad2fb284ed8ae7a67f7fe34989c3`.
No signed bytes transmitted. Production JAR excludes vector/test crypto.

## Signer architecture and KeyProvider seam

Principal recommendation: minimal offline Go helper, pinned SDK, explicit single MsgSend/DIRECT allowlist,
fixed path/code identity, authenticated service-only peer, bound/expiring/replay-protected request,
no node/CLI/keyring/network/generic signing API. Secondary: external hardware/wallet after device-specific
compatibility and approval proof. Java is an independent test implementation; byxd CLI rejected for production.

Current TxSignRequest is final with package-private constructor; source guard proves only TxService constructs it.
Future helper must enforce the capability itself, not treat package visibility as IPC security. Panel never constructs it.
Future KeyProvider takes an opaque key reference and engine-created bound capability, exposes public metadata,
and signs inside the provider boundary; it never returns raw private key to the transaction engine.
No arbitrary bytes/path/message/chain-id. No KeyProvider production implementation added.

Data Protection Keychain design: separate key reference/public metadata/private scalar; dedicated service signing
access group excluding panel, explicit helper entitlement/provisioning before use, no synchronization or private
material over JVM IPC/log/argv/env. Existing SecItem design remains untouched. Native Secure Enclave P-256 signing
cannot sign BYX secp256k1; software-key protection/wrapping is a separate claim, documented with Apple primary
sources in ADR. No actual Keychain query/write occurred.

## Physical build separation

Panel DEFAULT chooses `src/build-default/java` at build time: safe TxLabBuild stub, no txview reference.
LOCAL_QA chooses `src/build-local-qa/java`: registration/worker lives only there. Compiler/test/JAR profile excludes
panel/txview in DEFAULT, and the JAR exclusion also prevents stale classes from a prior QA compile being packaged.
No new runtime env/property/file/menu switch. PanelApp no longer links TransactionLab directly; qaBuild additionally
requires the compiled profile bridge. Existing common route identifiers/palette metadata remain; the entire actual Lab
screen/model/client-adapter package is absent from DEFAULT.

Packaging script now rebuilds panel with the same explicit chain profile as service, cleaning panel outputs and checking
Lab class absence/presence before bundling. Syntax checked; no signed macOS bundle or GUI rebuilt/launched this round.

[Artifact evidence](artifact-evidence.json) stores JAR SHA256s and exact class inventories:
DEFAULT panel txview absent; LOCAL_QA panel TransactionLab/model/adapter present; DEFAULT service profile
PRODUCTION_DISABLED with no FakeSigner/DraftPolicy/TxLabHost/fake transport/vector/test crypto.

## DEFAULT fail-closed proof

Service main code/IPC surfaces unchanged. Five operations only: tx.prepareBankSend,tx.getQuote,tx.confirm,
tx.getStatus,tx.cancel. No generic tx.sign/broadcast/signBytes/rawTx route added.
Valid synthetic authority login/session bound to peer11 plus real Unix IPC proves every typed operation returns
TX_DISABLED. Peer identity is injected in the test; no real production identity/authentication/credential was used.
Capabilities: txMutations=false; mutationsAllowed=false; policyTX_DISABLED; signerUNAVAILABLE;
transportABSENT; privateGateAllowed=false. ChainProfile PRODUCTION_DISABLED has no configured endpoint.
Market in the IPC fixture is fake, so no Binance connection is opened. No live BYX endpoint/node is opened.
One profile test uses a temporary local fake HTTP-node fixture; it is closed and is not a BYX/live-network connection.
Zero live BYX/Binance sockets for this DEFAULT evidence does not claim zero host sockets: scientific capture stays connected.

## Targeted tests and limits

- Go SDK vector generation/verification/TxRaw roundtrip PASS; no byxd/node.
- LOCAL_QA panel: TxPanelGuardTest5 + TxLabModelTest5 PASS.
- DEFAULT panel: TxPanelGuardTest5 PASS; rebuilt DEFAULT JAR has zero panel/txview entries.
- Service: SyntheticSigningVectorTest1; TxNoBypassGuardTest6; default TxIpc negative1; TxSessionsAdapterTest4;
  ChainConnector typed-profile test1.13 unique tests pass. Initial IPC fixture failed only due Darwin Unix socket path length;
  corrected to a short /tmp fixture and reran only that test (PASS), not the full selected set again.
- git diff --check, new-file whitespace inspection and packaging zsh syntax PASS.
- No full suites, visual GUI smoke, saturation, BYX/Binance live QA, real wallet/signature/broadcast, Keychain access or scientific processing.

## Files and commits

Panel: pom.xml; PanelApp.java; TxPanelGuardTest.java; new DEFAULT/LOCAL_QA TxLabBuild.java adapters.
Service: ADR-TX-001; TxSessionsAdapterTest.java; TxNoBypassGuardTest.java; new SyntheticSigningVectorTest.java;
test resource byx-direct-vector.json; this report and artifact-evidence.json.
Packaging: build-app.sh.
Chain: new test-only tools/signing-vector/main.go.
Existing unrelated changes and earlier gas-policy work preserved; no commits or push.

**V2.1R-1 STATUS: READY_FOR_SIGNER_IMPLEMENTATION_DESIGN. REAL TX STATUS: DISABLED.**
