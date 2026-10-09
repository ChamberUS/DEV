# BYX-MVP LOCAL RC1

**READY_FOR_LOCAL_RC1_ACCEPTANCE**

Version: `1.0.0-local-rc1-9d3eb1e50ea4-20261009T002639Z`. Build/acceptance completed 2026-10-09T00:51:05.832027+00:00.

Source `9d3eb1e50ea4cc03e9523d4e178dd83e8836fb38` on `feature/byx-ui-redesign-v1`, intentionally dirty with 112 tracked changes and 5087 untracked entries at preflight. All 881 qualified source/package hashes unchanged from V2.1V. No functional/runtime/package code delta; full Panel 751 / Service 498 / lifecycle 178 suites were not repeated.

Artifact: `BYX-MVP-1.0.0-local-rc1-9d3eb1e50ea4-20261009T002639Z.zip` (182643406 bytes).
SHA-256: `cd382408cf2ef256413273d0778d23f74f508dabe3d79d05150f7e9ffa04a2d7`.

The ZIP contains only the approved DEFAULT BYX-MVP.app. Detached machine manifest and SHA256SUMS sit beside it. 457 extracted files match exactly; 38 JARs match all V2.1V entry bytes; 102 native components are signed. Panel/service/migrator identities, Team, hardened runtime, entitlements, profiles, dependencies/modules, native instructions/constants and helper origin passed. DEFAULT has no signer, QA launcher/test JAR, private wallet/key state, credentials/.env, sockets/locks, reports or capture data.

Smoke: all 16 mandatory gates PASS; 22 actual packaged assertions, 26 DEFAULT hardening checks and 24 unchanged targeted native identity/network/fencing checks. Actual Panel→Service protocol 1, wallet schema 1, disabled custody/sign/TX, authority fail-closed, shutdown/restart, exact absence and signer IP sockets zero passed. Real native GUI Accessibility confirms healthy Sign in and offline Local service unavailable/Retry. External screenshot access is restricted by macOS; it is an optional observer limitation, not a replaced security gate.

Measured fencing sample N=3: min 11.538251s, median 11.625805s, max/highest 17.882069s, margin 2.117931s to unchanged 20.000s. No p95 claim. The original 24 check independently includes its original 20s timeout/follow-up gate.

QA companion is a separate frozen V2.1V test tool, not part of the RC distribution. It proves signed helper origin and external stale/SIGSTOP fencing; the DEFAULT intentionally does not enable a signer.

Known limitations:
- Software custody; DEFAULT custody is disabled
- Development/Personal Team provisioning; local acceptance only, no distribution/notarization claim
- Service development provisioning expires 2026-10-13T16:49:55Z; nondistributed QA signer expires 2026-10-15T01:03:43Z
- Joint encrypted-state/Keychain rollback remains a known limitation; no new rollback guarantee
- No hardware non-exportable secp256k1 key
- No full physical power-loss qualification
- Owner-writable development installation and malicious same-UID replacement remain known threat-model limits
- No real-wallet or real-trading readiness
- External desktop PNG capture unavailable; actual native UI observed through trusted Accessibility/window metadata and fresh FX trace
- Service data namespace isolated; approved launcher keeps OS user.home for Panel preferences/runtime DB, no saved session used or preference change

Reproduce with existing build-app.sh, exact build-recipe/toolchain versions and recorded authorized public provisioning profile. Apply the recorded tracked patch selectively for the three application/package directories in an isolated checkout, preserving deletions, then overlay the 826 qualified inputs from nondistributed source-freeze-evidence.tar and verify their hashes. No private signing key was exported. Consult manifest.json for exact commands, every included file/hash, profiles/UUIDs/expirations and evidence.

Capture supervisor 10657/collector54866 healthy: +81881 bytes in 5.225s, metadata only; signals 0/scientific reads 0. Pre-existing work and all failed observer attempts preserved. No commit/reset/clean/stash/rebase/push.

REAL USER KEY: NOT AUTHORIZED · REAL TX: DISABLED · BROADCASTS: 0 · REAL FUNDS: 0 · REAL USER WALLETS: 0 · ETHUSDT RESEARCH: UNTOUCHED.
