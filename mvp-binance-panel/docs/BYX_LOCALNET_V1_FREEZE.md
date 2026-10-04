# LOCALNET V1 checkpoint — 2026-10-04 UTC

Functional baseline: `f1b1e84` on `feature/byx-integration-v1` in the panel's parent
DEV repository; it follows `46c5011` (baseline) and `8f7d5b0` (entitlements).
This checkpoint freezes technical identity/contracts, **not TEST economic thresholds**.

| Identity | Recorded value |
|---|---|
| Cosmos checkout | `11a80ac535abbbebaa60b58a7bdfec1bcb26d2c9` |
| Isolated byxd SHA-256 | `d4089cf9c9dfee58c8f243577fded05d7b350f319535a0a7bbe699d3850575da` |
| Genesis file SHA-256 | `ec9a3dd6cd5e02ab4c1f76303b0334923e1e44f65b3f79cef72b68b5098a1087` |
| Canonical RPC genesis fingerprint | `0995695705b30305d266250292861dffd45107d7a9414b480bdcc5ffcf110e52` |
| Chain ID | `byx-mvp-localnet-b-20261004-5c2d82d9` |
| Metadata | base `ubyx` / display `BYX` / 6 decimal places, bank metadata exponents 0/6 |
| Runtime home | `~/.byx-mvp-localnet-b-v1/node`, entirely outside Git |
| Binary | `~/.byx-mvp-localnet-b-v1/bin/byxd`; global binary unchanged |
| Endpoints | loopback REST 1417, RPC 27657, gRPC 9190, P2P 27656, ABCI 27658 |

The global binary previously reported a different commit (`752d580...`); it is
not the localnet executable. Recomputed binary/genesis hashes and public metadata
matched this checkpoint during this phase. No historical balance or denom migration.

Genesis modules: auth, authz, bank, certificados, circuit, consensus, distribution,
epochs, evidence, feegrant, feesplit, genutil, gov, group, lojas, mint, nft, params,
payments, runtime, slashing, staking, upgrade, vesting. V1 uses auth/bank and
CometBFT identity/block evidence; standard bank sends implement optional TEST
payments. Custom x/payments is not the pass verification protocol. Native
x/feegrant is SDK v0.1.1, wired into default SDK v0.53.3 AnteHandler.

Wallet proof: ADR-036 `sign/MsgSignData`, canonical Amino sign bytes, SDK-compatible
secp256k1/SHA-256, compressed public key, Bech32 address derivation and low-S
signature. Random 256-bit nonce, single use, user/session/address/chain/genesis/
context binding, five-minute challenge expiry. Only public wallet records persist;
24-hour proof freshness. Watch-only never unlocks benefits; JavaFX has no keys.
External Keplr/WalletConnect/hardware signer integration is still absent.

Entitlements: central allowlist with genuine interface gates for extended wallet
balance history and synthetic analytics. Preview-only bot/research features never
grant ADMIN, research dataset access or trading execution. FREE/HOLDER/PLUS/PRO
and their ubyx thresholds are configurable experimental LOCALNET fixtures only.

Payment: optional bank MsgSend, exact TEST amount/recipient/sender/ubyx and
`BYX-MVP:PAY:v1:<intent>` memo. Exact raw bytes/hash and block inclusion, chain/
genesis/time checked. Single-consumption receipt supplies an expiring analytics
pass, independently of tier/gas. No real payment provider, exchange API or custody.

Feegrant: previously SUPPORTED by audit only; this phase activates bounded,
expiring, MsgSend-only native TEST allowances with an external TEST sponsor.
See [Gas sponsorship V1](BYX_GAS_SPONSORSHIP_V1.md) for security policy, public
transaction evidence and finite revocation window. All assets have no financial
value; sponsorship changes only BYX network gas, never Binance costs.

Limitations: single isolated development validator, no persistence SLA or remote
DEVNET readiness claim; runtime keys/state are unversioned. No indexer, production
sponsor, external wallet UI, payment settlement service or final tokenomics.
Uncertain broadcasts fail closed and need manual public evidence reconciliation.
No reserves, backing, USD price, redemption or yield is asserted.

## Remote development status

DEVNET STATUS: DEFERRED

Remote infrastructure intentionally inactive.
Development continues on LOCALNET until explicitly requested.
