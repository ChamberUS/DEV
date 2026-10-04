# BYX UI redesign V1

Native JavaFX migration on `feature/byx-ui-redesign-v1`, starting at `099b25e`
(the latest local integration, including gas sponsorship and Treasury).
Unrelated pending changes in the parent DEV repository remain untouched.

## Artifact audit

Authoritative artifact: `../mvp-binance/desing-reference/BYX-MVP Redesign.html`.
SHA-256: `c794c07504392708c683dc084282a1af2853b152e6cabb2eb68a3c4069c748e8`.
The 3,587,231-byte bundle has 14 compressed HTML pages. The extraction script
writes static QA copies and a hash manifest outside Git, without installing any
embedded assets or fonts. Extracted pages: Trading Desk, Login, Sign-in states,
Research Overview, Capture Detail, Markets + Portfolio, BYX Network, BYX Wallet,
BYX Benefits, BYX Treasury, Settings + Profile, Command Palette, Overlays,
Design System. No bundle/base64 is incorporated into the app or documentation.

Direction: **Ledger de camadas**, four dark surface levels, one accent per
workspace, a compact icon rail with labels/tooltips, segmented workspace choices,
restrained status badges, rounded panels, tabular numerals and explicit empty
states. Primary reference is 1440×900: rail 60, topbar 52, status dock 30.

| Tokens | Ported values |
|---|---|
| Surfaces | `#080A0F`, `#0E1119`, `#141824`, `#1C2131` |
| Borders | `#232A3C` |
| Text | `#ECEFF6`, `#A0A9BE`, `#7A839A` |
| Trading / Research / BYX | `#7C96FF`, `#B896FF`, `#5ED6C4` |
| Positive / negative / warning / info / disabled | `#42D79B`, `#FF7A7A`, `#E9B44C`, `#5CC8FF`, `#4A5266` |
| Spacing / radii | 4, 8, 14, 16, 24 / 6, 9, 14 |
| Typography | Existing licensed Inter; existing JetBrains Mono for numerals |

`panel/byx.css` is the central theme loaded after legacy `panel.css`, which
continues to support unmigrated secondary components. Canvas candle colors use
CSS ink nodes. Native vectors and the layered mark have no external dependencies.
MotionService FULL/REDUCED/OFF remains the authority for animations; the redesign
does not install animation libraries or download assets at runtime.

## Migrated surfaces and behavior

Shell: Trading/Research/BYX, compact rail, Settings shortcut and command search.
Secondary pages remain reachable through the command palette. USER sees no
Research/admin navigation. Every command routes through the existing `show` gate;
sealed holdout and unavailable MAINNET have no actionable target. The session
watchdog hides Research even when another component already observed expiration,
and refreshes the AdminSession badge and Advanced settings availability.
The dock separates SYSTEM HEALTH, MODE and ENVIRONMENT, reports actual state,
and never equates backend health with permission to trade.

Login: layered illustration, typography and compact form. Credentials enter
Trading normally; ADMIN opening Research still needs email OTP then SMS OTP or
a valid trusted device. Invalid code, resend cooldown, provider unavailable,
optional trust and verification success use the existing flow. The artifact's
suggested email-only SMS fallback is intentionally absent: the real security
policy wins.

Trading: market header, chart grid/empty state, timeframes, book, recent-trades
empty state, bot state, portfolio/risk metrics and native blotter tabs. Only 1m
is available from the existing backend; other timeframes remain disabled.
ETHUSDT/Binance USD-M Futures is the configured instrument hint when telemetry
omits the instrument. Prices, data age, balances and risk values stay N/A unless
a real snapshot supplies them. Live execution stays OFF/DISABLED.
Markets + Portfolio keeps TableView instances/columns, incrementally replacing
changed rows and preserving unchanged rows. No mock fallback exists in REAL mode.

Research: guard, eight-stage pipeline, real TRAIN metrics, dataset/session
panels, attention and environment sidebar. Snapshot metadata controls values;
no static artifact counts were copied. Validation remains locked and holdout
sealed. Existing services, hypotheses, criteria and command restrictions remain
unchanged.
Capture: real read-only monitor, process/campaign/storage, scientific integrity
and timeline unavailable states. Existing monitor reports RUNNING/STALE/STOPPED/
UNKNOWN, with no RECOVERING event or retry/recovery timeline contract. Those
missing observations are explicitly NOT REPORTED; no new process controls exist.

BYX Network/Wallet/Benefits/Treasury: native column/grid layouts with persistent
LOCALNET / TEST ASSETS / NO FINANCIAL VALUE labels. Unconfigured identity stays
UNKNOWN/unavailable in the dock; no live network is silently selected. Existing
network state fields are displayed without inventing a new gateway health enum.
Latest-block metadata is shown as a single real observation; hash, transaction
counts and block history remain unavailable. Admin endpoint forms are in a
collapsible section and retain their existing service gate.
Wallet linking/reverification/revocation and external ADR-036 proof ingestion
remain intact. Benefits use actual tiers, progress, entitlements, intents and gas
services. Missing configuration has disabled payment actions and N/A balances.
Treasury separates real not configured, TEST, PAPER and MANUAL_UNVERIFIED, and
shows each category's actual source/verification when available. No USD totals,
conversion, reserve ratio or backing are introduced.

Settings/Profile preserve the existing preferences, motion, contact/password,
trusted device and provider controls. Advanced settings require authorization.
Toasts, tooltips, context menus and confirmations reuse the existing infrastructure;
dialogs load the same theme. Wallet/payment states remain service-driven panels,
including pending/confirmed transaction feedback, rather than a parallel overlay
or notification system. The DEV proof JSON fields remain visible because an
external wallet signer UI is not yet integrated; they accept public proof only.

## Visual QA and intentional differences

Real JavaFX smoke uses a disposable database, development OTP, REAL mode, empty
TRAIN reports and an inactive CLI in a temporary home. It does not access the
actual capture, validation returns, final holdout, keys or localnet state.
Screenshots cover the eight requested surfaces plus Markets, Settings, Profile,
command palette and the locked Validation explanation at 1440×900, 1600×1000 and
1920×1080. Additional captures exercise OTP/cooldown/error/success, expired admin
and USER palette permissions. Source permissions/state tests remain in the
existing suite; this smoke does not broadcast transactions to fill screenshots.

QA comparison is outside Git: `/tmp/byx-native-final-qa/comparison.html` pairs
the eight native 1440×900 screenshots with static artifact renders. Reference QA
uses font fallback after stripping embedded fonts. Layout, surface hierarchy,
alignment, active states, empty states, status colors and overflow were inspected;
chart height and long-label wrapping were corrected after the first comparison.

Native controls, extra existing operational metadata, public wallet proof forms
and scrollable long settings/benefit/capture content intentionally differ from
static artifact cards. Missing market/block/timeline data is not simulated.
The existing portfolio/positions/orders pages remain available as well as the
combined market overview. Old supporting classes/assets were retained for the
controlled migration.

1920×1080 has room to expand without fixed screen coordinates, but chart and
blotter vertical density still needs the designer's next artifact. No speculative
large-screen redesign was introduced. Future Claude round: update the artifact,
rerun extraction/comparison and refine central CSS and grid proportions.

## Validation

Directed suites: CommandPaletteTest, IncrementalTableTest, AuthSecurityTest,
ByxBenefitsTest, TreasuryTest, CaptureMonitorTest, ByxNetworkTest,
ByxWalletOwnershipTest, ByxEntitlementsTest and MotionIconTest.
Final required checks: `mvn test`, `mvn clean package`, native visual smoke,
`ruff check src tests` in the engine, Ruff for the extraction script and scoped
`git diff --check`. Final result: both Maven commands passed **191 tests, zero failures/errors/skips**.
Native smoke passed with **46 screenshots**, including real OTP error/cooldown/
success, USER permissions and already-observed AdminSession expiration.
Ruff and scoped whitespace checks passed.
The parent repository has preexisting whitespace errors in
iaos-web; they are not part of this change.

Not executed: heavy research jobs, real-money movement, test-chain broadcasts,
localnet bootstrap/configuration, SSH, remote DEVNET activation, validation
returns or final holdout access. No secrets, databases, keys, state, logs,
temporary screenshots, bundled fonts or artifact HTML are staged.

## Files

Created: `src/main/resources/panel/byx.css`,
`src/main/java/panel/ui/{CommandPalette,LedgerMark}.java`,
`src/test/java/panel/{CommandPaletteTest,IncrementalTableTest,RedesignVisualSmoke}.java`,
`scripts/{audit_ui_reference.py,ui_visual_smoke.sh}`, this document.
Modified: `.gitignore`, `docs/BYX_LOCALNET_V1_FREEZE.md`,
`src/main/java/panel/app/PanelApp.java`,
`src/main/java/panel/motion/icon/IconPaths.java`,
`src/main/java/panel/ui/{Ui,OverviewView,CaptureMonitorCard,ByxNetworkView,ByxWalletView,ByxBenefitsView,ByxTreasuryView,Dialogs,Credits}.java`,
`src/main/java/panel/ui/auth/{AuthShell,LoginView,TwoFactorView}.java`,
`src/main/java/panel/ui/trader/{TradingDeskView,TraderScreens,TTable,CandleChart}.java`.

## Manual review

```sh
./run.sh
python3 scripts/audit_ui_reference.py '../mvp-binance/desing-reference/BYX-MVP Redesign.html' /tmp/byx-reference-review
scripts/ui_visual_smoke.sh /tmp/byx-native-review
```
