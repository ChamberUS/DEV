# BYX DEVNET V1 — deployment plan, preflight blocked

Status: **NOT DEPLOYED / REMOTE PREFLIGHT BLOCKED**. Date: 2026-10-04 UTC.
This phase stops at the deployment plan as requested when secure remote access
cannot be established. No remote state or panel runtime behavior was changed.
The parent DEV branch is `feature/byx-devnet-v1`, derived from integration
checkpoint `ab03e5b`. [LOCALNET V1](BYX_LOCALNET_V1_FREEZE.md) remains frozen.

## IDENTITY

Target: clean **DEVNET**, byxd/Cosmos, base ubyx, display BYX, bank metadata
exponents 0/6. No EVM, production claim, real assets or definitive tokenomics.
The DEVNET must have a new persistent chain ID, for example
`byx-devnet-v1-<creation-UTC-date>-<random-suffix>`; this is a naming template,
**not an initialized chain**. The actual ID/genesis/validator public identity
will be recorded only after a successful authorized preflight and initialization.
Do not use `byx-mvp-localnet-b-20261004-5c2d82d9` or `byx-devnet-private-1`.

Central public manifest `/etc/byx/devnet-v1.json` should pin environment, chain ID,
ubyx/BYX/6 metadata, creation timestamp UTC, source commit, toolchain, module
versions, binary path/SHA-256, genesis file SHA-256, canonical RPC fingerprint,
public validator identity, fixed authorized peers and endpoint policy. No seeds,
private keys or credentials belong in that manifest or Git.

Build the audited main Cosmos checkout `11a80ac535abbbebaa60b58a7bdfec1bcb26d2c9`
using a recorded compatible Go toolchain and `cmd/byxd`; SDK v0.53.3 and CometBFT
v0.38.17. Inspect changes affecting build inputs before building. Use a Linux
artifact matching the server's measured architecture, with operational path
`/opt/byx/bin/<source-commit>-<binary-sha256>/byxd`. Do not copy the macOS LOCALNET
binary or overwrite global byxd. No DEVNET build/hash has been produced yet.

Initialization must refuse any nonempty target directory. Generate fresh DEV-only
consensus identity and exclusive TEST accounts outside Git. Set all relevant
bond/mint/gov/fee denoms coherently to ubyx and verify bank metadata. Document TEST
funding amounts without USD equivalents. Do not reuse historical validator keys,
keyring, genesis, database or balances. Existing destructive genesis/bootstrap
scripts are unsuitable without replacement safeguards; see the
[readiness audit](BYX_DEVNET_READINESS_V1.md).

Before each start, an ExecStartPre identity guard must verify manifest, pinned
binary hash, genesis hash/chain/metadata and configured network exposure. After
start, RPC identity must match that manifest. An identity mismatch blocks start
or reports IDENTITY_MISMATCH; it must never trigger an automatic reset/rebuild.

## SERVER LAYOUT

Proposed, subject to measured OS and permissions:

| Path | Purpose / ownership |
|---|---|
| `/opt/byx/bin/<release>/byxd` | Pinned executable; root-owned, not daemon-writable |
| `/var/lib/byx-devnet/v1` | New persistent node data; dedicated byx-devnet owner |
| `/etc/byx/devnet-v1.json` | Root-owned public manifest; read-only to service |
| `/etc/byx/devnet-v1/` | Public service configuration, separate from LEGACY |
| `/var/log/byx/devnet-v1/` | Controlled operational logs if file logging is needed |
| operator-approved private backup destination | Encrypted backups, outside Git |

Create a dedicated non-login service user/group `byx-devnet` only after preflight.
Use owner-only access for key material and private data directories. Keep account
signers separate from the node identity and JavaFX. Never recursively chown a
LEGACY home. Record actual chosen paths and permissions after deployment.

## NETWORK EXPOSURE

Defaults below are the target policy, not claims about existing server listeners.
If LEGACY owns a desired port, choose an unused dedicated DEVNET port; do not
stop LEGACY or overwrite its configuration to reuse a port.

| Interface | Default target | Classification |
|---|---|---|
| P2P | `127.0.0.1:26656` initially, alternate port if occupied | LOCALHOST; PRIVATE authorized peers only after explicit configuration |
| CometBFT RPC | `127.0.0.1:26657` | LOCALHOST; unsafe RPC disabled |
| REST | `127.0.0.1:1317` | LOCALHOST |
| gRPC | `127.0.0.1:9090` | LOCALHOST or DISABLED when unused |
| gRPC-web | 9091 | DISABLED |
| metrics | `127.0.0.1:26660`, if enabled | LOCALHOST / private monitoring |
| pprof | none | DISABLED |
| public RPC/REST/gRPC | none | DISABLED |

No public seeds or discovery by default. Pin authorized peers when adding nodes;
no PUBLIC endpoint is required for the initial single-node DEVNET. Audit IPv4,
IPv6, firewall/security group rules and Docker/proxy exposure separately. Maintain
SSH administration allowlisting. Never change firewall rules in a way that can
lock out the established operator session.

## SYSTEMD

Target a separate `byx-devnet-v1.service`, leaving old units untouched. Use:

- Dedicated User/Group, explicit WorkingDirectory and pinned ExecStart/home.
- ExecStartPre identity guard; no implicit init, genesis replacement or reset.
- Restart=on-failure, RestartSec=30s, StartLimitIntervalSec=600,
  StartLimitBurst=3; alert on repeated failure instead of an aggressive loop.
- LimitNOFILE=65535, KillSignal=SIGINT, TimeoutStopSec=120.
- NoNewPrivileges, supported filesystem/process hardening and writable paths
  restricted to the new service's data/log directories.
- Bounded journald retention or logrotate size/age policy; no key/seed logging.

Future operational commands (unit does not exist yet):

```sh
sudo systemctl status byx-devnet-v1 --no-pager
sudo systemctl start byx-devnet-v1
sudo systemctl stop byx-devnet-v1
sudo systemctl restart byx-devnet-v1
sudo journalctl -u byx-devnet-v1 --since '15 minutes ago' --no-pager
```

Service installation/start is blocked until the read-only preflight, public
manifest, safe directory checks and key custody separation are complete.

## HEALTH

Implement a lightweight read-only monitor with connection/time limits. Check
service/process identity, RPC reachability, expected chain and genesis, metadata,
latest height/time, syncing, disk space/inodes and peers when available. Compare
height across observations without any transaction or chain modification.

| State | Meaning |
|---|---|
| HEALTHY | Correct identity, advancing/recent blocks, not syncing, adequate disk |
| SYNCING | Correct identity and RPC reachable, node reports catching_up |
| STALE | Block older than configured freshness threshold, or height fails to advance |
| DEGRADED | Low disk/inodes, expected peer absent, or partial endpoint failure |
| OFFLINE | Process absent or required RPC unavailable |
| IDENTITY_MISMATCH | Chain/genesis/metadata/binary differs from pinned manifest |

Initial planning thresholds: block age 60s, two observations at least 15s apart,
disk warning below 20% free and critical below 10%. Tune only after measuring
normal block cadence/storage growth. Single-node zero peers is expected, not
an automatic failure. Identity mismatch takes precedence over ordinary health.
Do not treat a responding PID/RPC as proof that the correct DEVNET is healthy.

## BACKUPS

Keep three separate inventories:

1. Consensus identity/key material plus last-signing state: encrypted,
   access-controlled operator custody, never public artifacts or Git. Never start
   the same validator identity twice; fence signing during restore rehearsal.
2. Public genesis/config/manifest/release hashes: checksummed public evidence.
3. Chain data: consistent offline/quiesced or supported database snapshot,
   checksums and version/height/app-hash metadata; encrypted restricted storage.

Before any later destructive operation, require a verified backup and an isolated
restore rehearsal. Do not copy a live database with ordinary tar and call it
consistent. No backup has been created in this phase and no backup is versioned.
Target daily consistent DEVNET snapshots, 7 daily and 4 weekly copies, capacity
alerts and restoration checks. Proposed RPO <=24h; proposed RTO 2–4h after artifact
availability. These are planning objectives, not measured guarantees.

## RECOVERY

Documented future procedure; **not executed**:

1. Stop/fence only the identified DEVNET service, preserve failure evidence and
   record UTC, release, identity, last healthy height and signing-state custody.
2. Validate backup hashes/version/manifest and restore to a new empty rehearsal
   path with network/signing disabled. Never extract onto LOCALNET or LEGACY.
3. Establish DB compatibility, config/metadata, app hash/height and data integrity.
   Validate consensus signing-state safety through the authorized operator.
4. Promote only a verified restore with one active signer, then health-check
   progress and identity. Do not roll a migrated DB back to an incompatible binary.

Keep upgrade heights, migrations and rollback procedures tied to tested releases
and consistent snapshots. A bad identity must not invoke unsafe-reset-all.

## PANEL CONNECTION

**Planned, not implemented**, because the preflight stop condition applies.
The current panel remains LOCALNET-only; no existing identity/security gate is
weakened. A future change must explicitly support LOCALNET and DEVNET with pinned
expected chain ID, canonical genesis fingerprint and ubyx/BYX/6 metadata, freshness,
sync state and endpoint health. Unknown DEVNET must fail closed.

Prefer an authenticated SSH tunnel to private server endpoints, with local ports
separate from LOCALNET 1417/27657. Pin the known host; BatchMode and strict host
checking; do not embed a private key or password in Java/config/Git. The UI should
show Environment DEVNET, chain identity, latest block, sync, endpoint health and
wallet network, without changing existing authentication or motion.

Current proof repositories already bind user/address to chain ID and genesis;
future DEVNET challenges must also carry explicit network/domain context. Audit
all LOCALNET-only guards before adding DEVNET. Separate policy/configuration,
recipients, sponsor accounts, replay domains and TEST signers per network.
LOCALNET wallet proofs, PaymentIntents, receipts and gas quotas cannot be promoted
to DEVNET. Do not simply accept another environment string in ByxConfig while
services/helpers still assume the frozen LOCALNET home and keyring.

Required regressions: unknown/mismatched DEVNET, wrong genesis/metadata, offline/
stale, cross-network wallet challenge/proof, payment consumption and gas quotas;
preserve all LOCALNET cases and ADMIN/research/live execution barriers.

## SMOKE RESULTS

**NOT EXECUTED**: no deployed healthy DEVNET is available. Future smoke gates:

- Correct manifest and isolated paths, two observations showing block progression.
- Two fresh TEST accounts; exact ubyx balances and external ADR-036 wallet proof.
- TEST bank send with block/code-zero confirmation and exact sender/recipient/gas
  reconciliation; no real money.
- Network-bound TEST intent -> receipt -> temporary entitlement; native bounded,
  expiring MsgSend-only TEST feegrant -> quota reduction -> revoke/expire.
- LOCALNET state unchanged; cross-network proof/payment replay rejected.

Do not reuse the one-shot LOCALNET feegrant/payment smoke claims or replenish
LOCALNET allowances to simulate DEVNET success.

## LEGACY NETWORK STATUS

**UNINSPECTED REMOTELY / PRESERVED**. A candidate SSH identity file exists with
0600 permissions and a known-host entry exists. There is no ~/.ssh/config profile
or usable agent identity. A read-only attempt using the BYX-named identity, strict
host checking, BatchMode, password/interactive authentication disabled and an
8-second connection timeout to the known host failed before authentication:
`ssh: connect to host 200.234.218.43 port 22: Operation timed out` (exit 255),
2026-10-04 approximately 02:43 UTC. Root was a candidate based on the local generic
VPS deployment documentation, not a verified remote account. No alternative
ports/users were scanned or guessed after failure.

OS/version, architecture/CPU/RAM, disks, hostname/timezone, active BYX units,
listeners, existing homes, binary versions/hashes, actual historical chain/genesis
and data size/integrity are **UNKNOWN**. The reference historical genesis in local
docs is not proof of current remote state. No old service was started/stopped,
no reset/migration/overwrite occurred and no validator/account secret was read.

On a future successful preflight, inventory public path/size/UTC/status and hashes
where reasonable; classify each existing BYX service/home as LEGACY. Read only
public genesis/config/release metadata; exclude validator/private key and account
keyring contents. Keep full runtime inventories/backups outside Git and summarize
only non-secret evidence. Port conflicts must be resolved by separation first.

## SECURITY

No credentials requested, printed or copied. The SSH client used the existing
identity file without exposing its contents; no new host key was accepted and
no authentication prompt was allowed. No remote command executed. No real funds,
EVM, quantitative engine change or reserved dataset access. LOCALNET identity,
genesis and state remain unchanged. This document contains public planning data
only; secrets, node data, backups, logs and raw datasets remain outside Git.

## BLOCKERS

- Secure noninteractive SSH connectivity/authentication is not established;
  connection to the known host's port 22 timed out. Cause (routing/firewall/server/
  port/account) is unknown; no remote operational facts can be inferred.
- Legacy inventory, capacity/architecture, compatible build and custody checks
  require successful authorized read-only access before any remote modification.
- DEVNET identity/runtime/health/backup tooling, panel DEVNET support and real smoke
  are intentionally deferred under the explicit “stop after deployment plan” rule.
- Restore, deployment, Maven/Go/runtime tests and quantitative processing were not
  executed for this documentation-only phase. Documentation diff/secrets review
  and capture file-growth verification are the applicable checks.

## Capture verification

Read-only event-file size checks on session `microstructure-20261004T022411Z-usd_m_futures`
showed growth of 190327 bytes between 2026-10-04T02:46:33.994790+00:00 and
2026-10-04T02:46:52.358502+00:00. Supervisor PID 13916 and recorder
PID 13923 remained alive in campaign `ethusdt-futures-continuous-20261004T012219Z`.
Both completed event files and the current `.part` file were counted; raw contents
were not read. No capture/engine/supervisor change or restart occurred. The frozen
LOCALNET genesis file SHA-256 was also recomputed and matched the checkpoint.
