# Treasury V1 — model / LOCALNET TEST

`TreasuryAsset` separates OPERATING_CASH, STABLECOIN_RESERVE, BYX_HOLDINGS,
BOT_CAPITAL, LIQUIDITY_ALLOCATION and GAS_SPONSORSHIP_BUDGET. Each row carries
asset, base unit, exact BigInteger units/decimal exponent, network/custodian,
source, timestamp, verification and a TEST marker. Sources are ON_CHAIN,
EXTERNAL_CUSTODY, PAPER and MANUAL_UNVERIFIED.

Manual/paper entries cannot claim VERIFIED status. `TreasurySnapshot` provides
separate verified balances per category/asset/network/unit/TEST scope with fresh
non-future evidence. It has no heterogeneous money total or price/FX method.
No BYX/USD, reserve ratio, redemption/backing or yield is fabricated.

BYX → Treasury is available with normal authenticated access and preserves the
existing authentication and motion. REAL ASSETS is NONE / NOT CONFIGURED. The
only configured row queries the exclusive LOCALNET sponsor account through the
existing identity/genesis/metadata gateway, with six exact decimals. Its balance
is allocated once to GAS_SPONSORSHIP_BUDGET; BYX_HOLDINGS does not duplicate it.
Other categories remain NONE / NOT CONFIGURED; bot paper/live capital is neither
queried nor included. All views show LOCALNET / TEST ASSETS / NO FINANCIAL VALUE.

Gas budget means currently available native sponsor balance, not a fiat fund or
sum of promises. The UI shows active **recorded** grants and observed allowance
consumption. It does not claim to inventory grants made outside the journal.
Absent/revoked/exhausted grants preserve last observed consumption and explicitly
require transaction reconciliation for a complete historical total. Grant/revoke
transaction fees are separate from fee-allowance consumption. OFFLINE/STALE or
wrong identity clears verified presentation; refresh runs outside the FX thread.

`ExternalTreasuryProvider` is an interface only: future external USDC/USDT/cash
records must name asset, network/custodian, units, as_of and verification evidence.
No provider, Binance connection, API key, exchange API, bridge, pool, dollar token,
external balance import, deposit or real transfer is implemented. A future price
feed and verified custody reconciliation require separate authorization/design.

Real read-only reconciliation after gas smoke: sponsor 170000 ubyx = 0.170000 BYX
TEST, zero active recorded allowances, 10000 ubyx observed sponsored consumption.
The public TEST journal/evidence is outside Git; ordinary production configuration
has no real assets and no gas signer. Unit tests cover source/category separation,
manual/paper exclusion, stale timestamps, exact large integers and absence of FX.
