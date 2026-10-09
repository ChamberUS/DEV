# BYX-MVP — Package A functional corrections and UX hardening

Implementation: `PACKAGE_A_IMPLEMENTED_READY_FOR_UAT`. Publication: `GIT_PUSH_VERIFIED` (code commit independently confirmed on GitHub).

## Preflight and provenance

Repository `/Users/buynnex-corp/dev`, Panel `mvp-binance-panel/`; branch `feature/byx-ui-redesign-v1`, entry HEAD `9d3eb1e50ea4cc03e9523d4e178dd83e8836fb38`. Existing upstream `origin/feature/byx-ui-redesign-v1`, remote `https://github.com/ChamberUS/DEV.git`, entry remote SHA `0fc68013413577216c4586cbab6d7f6f18a700d6`. GitHub reports this feature branch unprotected. It is 128 commits ahead; no divergence observed. No separate Git root exists for Panel, Service or packaging: all belong to this monorepo.

The 5,411-entry preflight inventory is retained locally in `preflight.json`/`preflight-status.txt`; it classifies historical approved work, Package B, unrelated work, generated artifacts and local tooling. Sensitive/local inventory and raw logs are excluded from publication. Package B was uncommitted; all 588 source hashes exactly matched its qualified manifest at entry. The V2.1V report, V2.1W manifest, U/V/W and Package B agent checkpoints were read before implementation. Original checkpoints and reports remain preserved. A separate Package A checkpoint was written before source edits.

Approved earlier dependencies are reconstructed from the RC1 source archive and checked against all approved freeze hashes. The Git plan keeps an explicit historical baseline separate from B and A; it excludes unrelated `iaos-web`, BYX, captures, generated outputs and local tools. Existing Service/packaging files match RC1 byte for byte; Package A edits neither their architecture nor source. The previously approved baseline includes required identity copies, build profile separation and authorization/session hardening. The pre-existing LOCAL_QA TxLabBuild factory is classified as historical dependency (the split is referenced in V2.1V authorized preflight; its exact bytes also match the qualified B source snapshot), rather than a B redesign file. It is not labelled Package B.

Commands: Java 21.0.12.1, Maven 3.9.9; `mvn -Dtest=... test`, then `mvn test`; native QA programs run from compiled test/main classpaths. Packaging uses existing `byx-packaging/build-app.sh --out <new-directory> --embedded-profile <existing-public-profile> --chain-profile production-disabled`. DEFAULT excludes Transaction Lab; no runtime override or authorization bypass was added.

## Settings root cause and correction

Reproduced in native JavaFX with a test-only authorized, in-memory AccountData stub. Production Settings stays READ_ONLY under ServerAuthorization. The HBox save bar had maxWidth 720 but default unbounded maxHeight; StackPane stretched it to the parent height despite BOTTOM_CENTER alignment. Before: **720×1080, 720×900, 720×700** at the three required scene sizes. The opaque background is the save bar itself, not a modal backdrop. Screenshots and measurements are in `visual/before`; after correction the bar is **720×62** at all three sizes (`visual/final/settings`).

The host now caps the bar at preferred content height, preserves its intended width and places it above the status dock/alongside the rail. Hide/dispose unmount even a dirty bar; returning to an authorized draft remounts it once. Reopening the same node stops old exit motion and restores pointer interaction. Failed saves keep the draft; only explicit save/discard changes state. Settings rows stack copy and controls when horizontal space would truncate critical labels. No theme tokens or approved B visual elements changed.

## Keyboard audit

| Binding | Result / authority |
|---|---|
| Cmd/Ctrl+K | Existing command palette; duplicate Scene filter removed. Shell is sole binding; modal guard respected. |
| Cmd/Ctrl+, | Existing Settings binding retained; no navigation behind modal. |
| Cmd/Ctrl+Shift+H | Existing Home binding retained; plain Cmd+H untouched. |
| ? | Shortcut help from registry; ignored in text inputs and active modal/palette. |
| Cmd/Ctrl+1…6 | Existing rail navigation through router and gate; extra Shift/Alt cannot trigger it. |
| Escape | Logical top dialog, then palette, then popover; persistent dialogs remain persistent. Focus restored. |
| Enter | Palette selection and account menu activation, exactly once. Explicit menu handler covers native macOS. |
| Up/Down, Home/End | Existing list/menu navigation retained. Blocked palette rows cannot execute. |
| Tab / Shift+Tab | Native forms traversal; dialogs and palette contain focus. Account menu closes to avatar. |
| Research | No undocumented shortcut added. Every internal/rail/palette request uses existing admin, reauthentication, 2FA and Service gates. |

## Navigation, focus and lifecycle

The existing router remains sole navigation authority. An ALLOW from an older reentrant request cannot override a newer request. Old navigation guards cannot act after close/replacement; actions are ticket/page-bound. Route changes close popovers. Dialogs disable their controls on logical close so animated remnants cannot execute obsolete decisions. Palette opening respects an active modal; Tab stays in the surface.

Overlay disposal removes its Scene listener, focus guard, keyboard and mouse filters; it recursively stops nested entrance/exit animation and toast timers, invalidates deferred focus, and invokes popover/palette closure callbacks once. Shell disposal removes its shortcut/help filters; account menu disposal releases avatar handlers and identity/item state. Header popovers follow the opener across resize, clamp to viewport and remove geometry listeners on close.

Authentication implementation remains the approved V2.1V/B version. Stale-login, authentication epoch and completion-ownership tests are mandatory regressions; no callback may revoke a newer valid session. Research privilege and DEFAULT ServerAuthorization restrictions remain unchanged.

## Responsive and mascot qualification

Required evaluation scenes: 1920×1080, 1440×900, 1100×700. The supported minimum remains 1100×700. This Mac cannot display a full 1920×1080 logical window; exact larger sizes use the actual JavaFX root rendered in a temporary Scene, then restored to its native Stage. No HTML prototype is used as evidence.

The visual pass found Settings breadcrumbs truncated with ADMIN SESSION at 1100×700 because search had a fixed 320 px minimum. Search now shrinks to a compact native control when necessary; full destination/account labels take priority, and 320 px remains the preferred width at larger sizes. No token changes. Repeated notifications open is idempotent and cannot dispose a newly rebuilt surface through an old closure callback.

Home, approved mascot/header and Benefits are preserved. The same avatar remains across navigation; one account menu, unchanged vector implementation, FULL/REDUCED/OFF policies, three scene filters and disposal on logout are checked. Chain Data remains without its decorative mascot. No new animation implementation or backend capability was added. Compact status-dock labels keep their existing ellipsis policy and expose full text/state in tooltips and accessible names; live-trading OFF remains visible. Toast Dismiss now reserves readable width and is keyboard reachable without taking focus on appearance.

## Honest capability states and remaining Package C dependencies

Settings READ_ONLY; Research AUTHORIZATION_REQUIRED for unauthorized sessions; notifications UNAVAILABLE with no service and disabled actions; registration UNAVAILABLE; wallet/trading mutation unavailable under DEFAULT; network NOT_CONFIGURED under production-disabled. Existing offline, planned and unknown states remain explicit. No registration, notification backend, real wallet operation or live trading was enabled. Package C/backend dependencies remain registration, preference persistence, Service tier policy, notification/password-reset services and any real custody/trading work requiring separate approval.

## Files modified by Package A

- `mvp-binance-panel/src/main/java/panel/accountview/SettingsScreen.java`
- `mvp-binance-panel/src/main/java/panel/app/PanelApp.java`
- `mvp-binance-panel/src/main/java/panel/design/ByxOverlayHost.java`
- `mvp-binance-panel/src/main/java/panel/shell/ByxShell.java`
- `mvp-binance-panel/src/main/java/panel/shell/NavigationGuard.java`
- `mvp-binance-panel/src/main/java/panel/shell/NotificationPanel.java`
- `mvp-binance-panel/src/main/java/panel/shell/ShellPalette.java`
- `mvp-binance-panel/src/main/java/panel/shell/ShellRouter.java`
- `mvp-binance-panel/src/main/java/panel/shell/ShellTopBar.java`
- `mvp-binance-panel/src/main/java/panel/shell/ShortcutRegistry.java`
- `mvp-binance-panel/src/main/java/panel/shell/UserMenu.java`
- `mvp-binance-panel/src/test/java/panel/PackageAKeyboardTest.java`
- `mvp-binance-panel/src/test/java/panel/PackageAOverlayLifecycleTest.java`
- `mvp-binance-panel/src/test/java/panel/PackageANavigationBoundaryTest.java`
- `mvp-binance-panel/src/test/java/panel/PackageAResponsiveHeaderTest.java`
- `mvp-binance-panel/src/test/java/panel/accountview/SettingsOverlayTest.java`
- `mvp-binance-panel/src/test/java/panel/accountview/SettingsOverlayQa.java`

QA documentation and sanitized screenshots are under `docs/qa/package-a/`; durable checkpoint is separate at `../docs/agent-state/PACKAGE_A_STATE.json`. Baseline/B version boundaries are documented in `historical-baseline.md`; the original B report and images are preserved.

## Tests and visual evidence

Initial complete Panel suite before the final header correction: **864 total / 863 passed / 0 failures / 0 errors / 1 expected skip** (Capture observation is opt-in). Initial sixteen new targeted tests cover Settings (4), keyboard (3), overlays/lifecycle (6), actual PanelApp navigation (3). Two additional tests cover minimum-size headers with both badges and repeated notification opening. The **final complete suite passed: 866 total / 865 passed / 0 failures / 0 errors / 1 expected skip**. It was repeated because actual visual QA uncovered the additional header correction. Historical B baseline was 848 total, 0 failures/errors, 1 skip. Final-source hashes are in `tested-source-hashes.json`; summary in `panel-suite-summary.json`; raw XML/logs remain local. Failed intermediate checks are retained locally with their original assertions. An early resize assertion included shadow bounds; it was corrected to measure control layout bounds. A subsequent check exposed stale owner-transform invalidation after a second resize; the implementation now reads/validates that observable transform on every update. A same-Home request is intentionally a no-op under approved B; the route-change test now starts on a different route. No product security gate or acceptance condition was weakened. The later native pass exposed a real breadcrumb-width defect; a focused header/notifications qualification passed before the final full suite. Both full-suite runs and all failed intermediate attempts are retained locally.

Native flow regression on final sources: **FULL 39/39, REDUCED 39/39, OFF 39/39**. Exact current-root screenshots at all three required sizes cover Home, Settings, Benefits and confirmation surfaces; the route matrix additionally covers Desk, Markets, Orders, Wallet, Network, Chain Data, Research, Profile, Security, Help/FAQ, user menu, notifications and palette. `visual-evidence.json` lists 48 fresh images and their dimensions/hashes. Important proofs: `visual/before/settings-dirty-1100x700.png`, `visual/final/settings/settings-dirty-1100x700.png`, `visual/final/routes/016-t-settings-1100x700.png`, `visual/final/routes/018-menu-1100x700.png`, `visual/final/routes/024-palette-1100x700.png`.

## Capture health and preservation

Capture observed only with process metadata and `.part` stat information; collector 54866 and supervisor 10657 remained present. The recorded open-part growth is positive in every follow-up observation (`capture-health.json`). No scientific data, TRAIN, VALIDATION or FINAL_HOLDOUT was read or run; no collector/supervisor signal sent. The preserved RC1 zip still hashes `cd382408cf2ef256413273d0778d23f74f508dabe3d79d05150f7e9ffa04a2d7`; preserved B candidate still hashes `88122d266440b98430949aa1b28ec9c9aaeb05b61d69026bc3bd9ac09b91fb88`. Original frozen artifacts were not rebuilt.

## Native candidate and limitations

Fresh candidate: `../byx-packaging/build/local-pkga-uat/1.0.0-local-pkga-uat-20261009T055159Z/BYX-MVP.app` (from repository root: `byx-packaging/build/local-pkga-uat/1.0.0-local-pkga-uat-20261009T055159Z/BYX-MVP.app`). Existing production-disabled build recipe and signing identity were used in a new output directory. **Packaged smoke: 9/9 PASS** (strict signature; unavailable offline; Service ready; packaged verified identity/protocol; TX/signing/broadcast disabled; native Panel FX pulse/READY; owned process absence; socket cleanup; no authority snapshot/catalog initialization). Service JAR entries are identical to qualified B; DEFAULT has neither Transaction Lab nor test classes. Source hashes remained exact after rebuild. No custody suite repeated because no Service/signer source change was made.

This is a local development UAT candidate, not a production custody/trading release. Development provisioning expires 2026-10-13T16:49:55Z. Settings persistence remains unavailable; the edit/dirty surface is reproducible only with the isolated test harness. No real user sign-in was automated. Above-display sizes use the real native JavaFX root in an exact-size temporary Scene. Raw QA logs/XML, inventories, archives, generated apps and machine state remain local.

## Human UAT

1. Launch the separate Package A candidate and sign in with your own account. Confirm Home, Benefits and the approved mascot are preserved.
2. Use Cmd+K, Cmd+comma, Cmd+Shift+H, ?, arrows, Enter, Tab/Shift+Tab and Escape; verify focus returns from menus/palette and modals block navigation.
3. Resize within the supported minimum 1100×700; confirm the full Settings breadcrumb, account menu anchoring, readable controls and stable dock. Production Settings should stay read-only and show no save bar.
4. Confirm a USER cannot enter Research and ADMIN follows the existing reauthentication/2FA gate. Inspect FULL/REDUCED/OFF against the recorded native fixture flows; preferences are not writable in DEFAULT.

## Git audit and publication

Published commits:

- Historical approved baseline: `66efd2384a67074049255a4675083dd3fda3a66e` (`chore(panel): record approved U V W qualification baseline`).
- Package B: `6a2a73dc77291e406bfe10f2097fdfd3805a5f72` (`feat(panel): implement approved Package B redesign`).
- Package A: `e357a078606de4f09adbcdc3fabdc3183c36dc6f` (`fix(panel): harden settings navigation and keyboard UX`).

Staged diffs and file names were reviewed at each boundary. Automated private-key/token/credential/file-size scans found **0 findings**; test data is deterministic/synthetic and no runtime Keychain/catalog/auth state is included. All 1,437 unpublished ancestor text blobs were also audited; none contains detected secret material, no blob exceeds 10 MB, and ancestor changes are confined to these three related components. Generated apps, archives, raw captures, logs/XML, inventories, local tooling, private signing/provisioning materials and unrelated projects are excluded. The tested source hashes match the state being staged.

Target remains existing feature branch `origin/feature/byx-ui-redesign-v1` on verified `https://github.com/ChamberUS/DEV.git`; default branch main is not a target. GitHub reports this feature branch unprotected and push permission granted. Remote ancestry is checked again immediately before ordinary non-force push. Ordinary push succeeded; independent `git ls-remote` returned the exact Package A SHA `e357a078606de4f09adbcdc3fabdc3183c36dc6f`. The tracked `publication-verification.json` records the code publication; a following documentation-only commit records these results. Final documentation publication SHA is retained in the separate durable checkpoint and user handoff. No reset, clean, stash, discard, force push, rebase, history rewrite or automatic PR/merge.

`REAL USER KEY = NOT AUTHORIZED` · `REAL TX = DISABLED` · `BROADCASTS = 0` · `REAL FUNDS = 0` · `REAL USER WALLETS = 0` · `ETHUSDT RESEARCH = UNTOUCHED`.
