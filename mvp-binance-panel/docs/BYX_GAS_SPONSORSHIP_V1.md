# Native gas sponsorship V1 — LOCALNET / TEST ONLY

V1 uses Cosmos SDK x/feegrant v0.1.1 through byxd. No EVM, application payment,
exchange discount, security role, research permission or strategy execution is
involved. Paid analytics passes never qualify a FREE wallet for sponsorship.

`GasSponsorshipPolicy`, `GasSponsorshipService` and `GasGrantSnapshot` require an
active authenticated user, current ADR-036 control proof, configured chain/genesis,
fresh benefits balance, eligible tier and bounded quota. The native allowance is
`AllowedMsgAllowance(BasicAllowance)` restricted to `/cosmos.bank.v1beta1.MsgSend`.
Only ubyx is permitted; spend limit and expiration are mandatory. The displayed
original limit comes from the public journal; the native BasicAllowance spend
limit becomes the **remaining** amount after fees are consumed.

## Experimental configuration

The runtime file `~/.mvp-binance-panel/byx-gas-test.properties` contains only public
policy, never keys. Example development fixture (not future tokenomics):

```properties
environment=LOCALNET_TEST_ONLY
granter=<exclusive gas-sponsor-test public address>
chainId=<audited isolated localnet chain ID>
genesis=<canonical SHA-256 genesis fingerprint>
FREE=0
HOLDER=0
PLUS=30000
PRO=60000
validitySeconds=300
```

Validation caps each TEST quota at 1000000 ubyx and validity at one hour. This
fixture expires in five minutes. No network grant is infinite or periodic.

## Signer boundary and idempotency

Normal application wiring is read-only and rejects grant/revoke writes. Enabling
the separate `LocalnetGasTestSigner` requires both existing security DEV mode and
`BYX_LOCALNET_TEST_SIGNER=I_ACKNOWLEDGE_TEST_ONLY`. It invokes the SDK helper;
JavaFX never receives a seed/private key. No production signer/provider exists.
This is a trusted local development demonstration, not a deployable multi-user
sponsor backend: do not enable its SDK TEST keyring in any production environment.

`scripts/byx_gas_test.py` checks the fixed isolated home, binary/genesis/config,
process, exclusive gas-sponsor-test key and alice-test/bob-test grantee allowlist.
It requires spend limit/expiry, restricts messages and confirms the SDK transaction
in a block with code zero before returning its public hash. Setup creates only
that TEST sponsor and funds it once with 200000 ubyx from alice-test. The funding
and grant fees are TEST assets and have no USD valuation.

The SQLite journal has unique (user,chain,genesis) and (wallet,chain,genesis)
constraints. A claim precedes signing. There is **one lifetime V1 budget** on this
chain per user and wallet, across sessions/restarts/tier changes: no automatic
replenishment, no wallet cycling, no overwrite of unmanaged native allowances.
A separate exclusive-create claim in the external TEST home prevents helper
rebroadcast even if the QA database is removed. Interrupted/uncertain broadcasts
remain claimed and require read-only transaction reconciliation; never clear
claims to retry blindly. Duplicate active eligible requests read the same native
remaining balance without another transaction.

## Downgrade / revoke / offline

A request/refresh that observes revocation or a lower eligible quota revokes the
whole recorded allowance; it never replaces it with a larger/new allowance.
Explicit grant revoke remains possible for the owning authenticated user after
wallet unlink. After exhaustion, expiry or revoke, the lifetime quota stays used.
OFFLINE/STALE refuses new sponsorship and does not trust a higher tier.

Off-chain unlink cannot instantly cancel a Cosmos allowance while the app is
closed/offline. Use “Revoke TEST allowance” or grant reconciliation while online;
otherwise the native expiration bounds the residual window (five minutes in the
fixture, never longer than the configured one-hour cap). V1 has no background
production revocation agent and native feegrant cannot observe app user state.
The on-chain allowance itself enforces quota/expiry and the MsgSend restriction.

The audited REST version returns HTTP 500/code 13 with exactly
`fee-grant not found: not found` for an absent allowance. Only that precise response
or HTTP 404 is absence; all other failures deny new grants. Every query verifies
fresh localnet identity/genesis/metadata through the existing gateway first.

## Real smoke, 2026-10-04 UTC

Fresh ADR-036 verified alice-test qualified as PLUS. A native 30000 ubyx grant was
confirmed, and a duplicate request broadcast nothing. A 1 ubyx transfer with
`--fee-granter` reduced remaining quota to 20000: user paid 1, sponsor paid 10000 gas.
Revocation was confirmed and a later sponsored transfer failed. A new normal
transfer succeeded and charged the user 1 + 10000 ubyx. Sponsor delta was exactly
30000 (grant creation + sponsored fee + revoke); no allowance top-up occurred.

- Grant: `AC756DD8649CE5879163D7F13D4A4C871DEEFCDB5BF28A00D4238A6CF4E4B03E`.
- Sponsored MsgSend: `692260464DCC208CBDFCF0F75A6329E69CB06B0F8E3E659A69E4D6E301A604A1`, height 1658.
- Revoke: `AFA14B27DB19E6C4B878431C7C72DB2E4EAA88B66F1DD24CD43D0EF99F19E93B`.
- Unsponsored MsgSend: `7A8651C7A992AF84E02FA8D7E23F24DE525BD2FAA20EB06DC9F86BC4EED443AC`, height 1660.

Public runtime evidence is outside Git at
`~/.byx-mvp-localnet-b-v1/evidence/gas-smoke.json` and `gas-journal.db`. No logs,
keyring, binary, genesis or localnet data are committed. Smoke is deliberately
one-shot; do not rerun to replenish an exhausted/revoked V1 grant.
