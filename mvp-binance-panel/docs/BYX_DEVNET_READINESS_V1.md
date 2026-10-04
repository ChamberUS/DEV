# Persistent DEVNET readiness — local audit only

Audit date: 2026-10-04 UTC. Scope: local BYX source, public reference genesis,
configuration templates and deployment scripts. No SSH, remote bootstrap, server
restart, validator secret, mnemonic, keyring or historical database was read.
The current working LOCALNET is development-only and is not the historical chain.

## Evidence and gaps

Local references audited: `BYX/docs/aws_private_devnet_checklist.md`, `config.yml`,
`genesis.json`, `scripts/genesis_private_devnet.sh`, and deploy `bootstrap.sh`,
`configure_node.sh`, `validate_node_config.sh`, `healthcheck.sh`,
`backup_snapshot.sh`, `systemd/byxd.service.template`. These are plans/tools,
not proof that the old server currently satisfies them.

| Criterion | Available evidence / unresolved requirement |
|---|---|
| Chain identity/genesis | Reference `byx-devnet-private-1`, file SHA-256 `b5f3e8365a8cc788afce1a22cd023ed9dbc07944668d4104e85e948f77c68f67`, canonical `4ac6c20126c188bae8d7b4526b47cbdfb40bb4852ffd743b4dc2ce7b41596ef7`; remote identity/height unknown. Never copy LOCALNET genesis into historical state. |
| Validator keys | Not inspected. Recovery needs an authorized operator to establish custody/public consensus identity and last signing state, without exposing secrets. Never start the same consensus key on two nodes. |
| Binary compatibility | Historical installed binary commit `752d580...` differs from audited source `11a80ac...`. No remote binary hash/upgrade history is available. Isolated localnet binary is not evidence of historical DB compatibility. |
| State/database | No remote DB/height/app hash/integrity evidence. Require a consistent snapshot, offline restore test and compatible replay before recovery. |
| Ports / exposure | Checklist uses P2P 26656, private RPC 26657, REST 1317, gRPC 9090, metrics 26660. Actual firewall, listeners, public exposure and TLS/auth perimeter unknown. |
| Backups | Existing backup script creates config/data tarballs and hashes with age retention. It does not stop/quiesce the node, encrypt validator material or demonstrate a consistent database restore. Not recovery evidence. |
| Monitoring | Health script prints chain/height/peers/node-info; no expected identity, freshness, progress interval, syncing, disk alert or alert delivery assertion. |
| Restart | Unit has isolated configurable user, Restart=always/3s, SIGINT and 120s graceful stop. No restart rate limit, service hardening or crash-loop alert established. |
| Upgrades | No demonstrated version manifest, compatibility matrix, upgrade-height plan, migration/restore rehearsal or downgrade-safe checkpoint. |

Additional script hazards: genesis script defaults to a TEST keyring and executes
`rm -rf HOME_DIR`; it creates keys and monetary distribution fixtures. Bootstrap
can copy a supplied genesis into an already existing home without checking existing
data/identity. Neither should be run against historical state. They were **not
executed or changed**. The checklist's supply statements are historical planning,
not this phase's tokenomics approval. No balances/denoms are renamed or migrated.

## RECOVER_EXISTING vs REBUILD_CLEAN_DEVNET

| Option | Admission criteria | Tradeoff |
|---|---|---|
| RECOVER_EXISTING | Confirm exact chain/genesis/binary/upgrade history; prove consistent state and successful isolated restore; resolve validator custody and signing-state fencing; inventory exposure and backups. Require all criteria, not only a live PID. | Preserves historical test state, but currently lacks the evidence required to restart safely. |
| REBUILD_CLEAN_DEVNET | Use a new named persistent DEVNET chain ID/genesis, fresh DEV-only consensus/account keys, separate directory/service user, version-pinned build and validated public metadata; archive old state without automatic migration. | More reproducible and safer when history/custody/integrity cannot be established. Does not preserve old balances and must never be described as recovering that chain. |

Recommendation: **REBUILD_CLEAN_DEVNET** as the provisional future path, subject to
an explicitly authorized deployment phase. Leave the old server stopped/unchanged
until its audit proves otherwise. RECOVER_EXISTING is blocked on remote evidence;
no claim is made about corruption or compromise. Do not reuse this ephemeral
LOCALNET home or TEST keyring as a persistent/public DEVNET service.

## Target architecture (not deployed)

1. Persistent chain ID, immutable public genesis hash/metadata and a signed release
   manifest containing source commit, toolchain, binary hash and module versions.
   Stable explicit data home; initialization refuses existing data/genesis.
2. Dedicated unprivileged service user and restricted directories. Separate SDK
   account custody from consensus signing. No account seeds in JavaFX, repo,
   environment examples, process arguments or logs. Operator-managed consensus
   custody and single-signer fencing/signing-state backup are prerequisites.
3. Pinned executable outside PATH ambiguity; systemd/supervisor uses explicit home,
   graceful SIGINT shutdown, bounded restart backoff/rate limit and crash alerts.
   WritablePaths limited to data; no root daemon; add supported service hardening.
4. Private validator with authorized fixed peers; P2P through sentry where needed.
   RPC/REST/gRPC/metrics bound loopback/private network behind firewall/VPN. No
   public admin/query surface by default; remote panel support needs a separate
   authenticated gateway design because V1 currently accepts loopback only.
5. Health checks assert chain/genesis, advancing height, recent block time, syncing
   false, process identity and peer expectations. Monitor free space/inodes, DB
   growth, time synchronization, memory, restarts and public network exposure.
6. Consistent versioned data snapshots (offline/quiesced or supported DB snapshot),
   encrypted restricted backups, checksums and retention. Handle validator secrets
   separately with audited access; routine public evidence excludes them. Rehearse
   restore into a fenced non-signing environment before calling backups usable.
7. Log rotation with size/age limits, structured public health evidence, redaction,
   alert routing and a documented shutdown/recovery operator procedure.
8. Planned upgrade heights, compatibility checks, state backup and isolated replay.
   Never downgrade a migrated DB or blindly replace binaries/genesis. Keep rollback
   procedures tied to an actually tested compatible snapshot.

Readiness status: **NOT READY FOR REMOTE START**. This document is an architecture
and gap audit only. It does not create infrastructure, move real assets, access
research holdouts, migrate tokenomics or authorize deployment.
