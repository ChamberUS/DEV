# Package B — Code map (Stage 0 audit)

Design source: `/Users/buynnex-corp/dev/design-inputs/BYX-PACKAGE-B/` (PKG-B-DESIGN-1, extracted from `~/Downloads/BYX-PACKAGE-B.zip`;
HTML/SPEC/TOKENS/MOTION/DEPENDENCIES/README byte-identical to the copy in `BYX-PACKAGE-B-IMPLEMENTATION.zip`).
Panel root: `/Users/buynnex-corp/dev/mvp-binance-panel` (git root is `/Users/buynnex-corp/dev`, branch `feature/byx-ui-redesign-v1`, HEAD `9d3eb1e`).

## Baseline

* 224 pre-existing modified/untracked entries in the repository (Service, packaging, Panel V2.1V/W work, `BYX/`, `iaos-web/`). Nothing was reset, cleaned, stashed or committed.
* Pre-existing Panel diff against HEAD saved before editing (3,615 lines) — scratchpad `panel-preexisting.patch`.
* LOCAL RC1 artifact (`byx-packaging/build/local-rc1/...`) and its manifest (`docs/qa/v21w/local-rc1`) are not touched.
* V2.1V report: Panel suite 751 pass / 0 fail / 1 skip. V2.1W: 16/16 mandatory package gates.

## Findings that shape the implementation

| # | Finding | Consequence |
|---|---|---|
| F1 | `ServerAuthorization.DENY_ALL`: `Settings.save()` and every preference write throw `SERVER_AUTHORIZATION_REQUIRED` in production. The existing onboarding already shows "Onboarding preferences are read-only in this build". | Landing preference and "welcome seen" **cannot be persisted** without weakening the freeze. They are kept in an in-memory, per-account (`userId`) `LandingPreference` store for the process lifetime and the UI says so. Dependency D-11 stays PENDING. No file is written. |
| F2 | Service wire ops are `auth.password/beginSecondFactor/verifySecondFactor/...`; there is **no** register / signup / createAccount op (only `UserService.createInitialAdmin` for first install). | Registration = `UNAVAILABLE`. `REGISTRATION_BACKEND_DEPENDENCY` recorded. No form, no endpoint, no role chosen by the client. |
| F3 | `ByxDataAdapter.accountOperationsUnavailableReason()` returns `SERVER_AUTHORIZATION_REQUIRED` in production. Wallet/benefit reads are denied. | Benefits maps this to the **Authorization** row (`unauthorized`) — never to "upgrade your plan". |
| F4 | Market feed (`MarketFeedClient` → `ResearchModeTradingProvider.applyMarket`) delivers **one instrument**: `ETHUSDT`, market `USD-M Futures` (PERPETUAL). `feedUpdatedAt`, `change24hPct`, `volume24h` (quote, USDT) may be null. | Home catalog = one row ("single catalog in this beta"). Spot ETH is **not** listed. Search filters this catalog; `BTC` → `UNSUPPORTED_SEARCH`. Terminal navigation only for `ETHUSDT`. |
| F5 | `DeskModel.feed(TraderSnapshot, now)` already classifies LIVE/STALE/RECONNECTING/DISCONNECTED/ERROR/WAITING/NO_FEED. | Home market state derives from the same function (no second freshness rule). |
| F6 | Mascot today is a raster rig (`panel.mascot.MascotView`, sprite sheets) used in Chain Data, plus `MascotTransitionOverlay` for workspace changes. No vector source exists in the repository (A-01 pending). | New vector component `panel.shell.avatar.MascotAvatar` from the traced `SVGPath` in `BYX_MASCOT_MOTION.md` §9 (recorded as a fidelity dependency). Existing `MascotView` is left for the remaining places (gallery, workspace transition); only the Chain Data instance is removed. |
| F7 | `ShellTopBar.avatar()` is a long-lived `Button` owned by the per-session `ByxShell`; `UserMenu` toggles from it and never re-parents it. | The avatar graphic replaces the initials on the same Button. No second menu. |
| F8 | `evaluateRoute` already guards unsaved edits (`View.hasUnsavedChanges/discardChanges`) with a 2-way confirm. `SettingsScreen` has `dirty()` + `save()`. | Extend `View` with `saveChanges()`; replace confirm with the 3-way `NavigationGuard` (Stay default / Discard and go / Save and go). |
| F9 | No i18n infrastructure exists (all strings are literals). | New `panel.i18n.Strings` backed by `/panel/i18n/package-b_{en,pt-BR}.properties`, Package B surfaces only, EN default, **no language switch exposed**. |
| F10 | `Cmd/Ctrl+Shift+H`: existing shortcuts are `Mod+K`, `Mod+,`, `Mod+1..6`, `?`, `Esc` (ShortcutRegistry + ByxShell.onShortcut). No conflict. macOS "Hide Others" is `Cmd+Option+H`; "Hide" is `Cmd+H` (no Shift). | Bind `SHORTCUT+SHIFT+H`, register in `ShortcutRegistry`. |

## Requirement → component → source → test

| Req | Component (new = N, extended = E) | Data/capability source | Tests |
|---|---|---|---|
| AB01 route | `ShellRoutes` (E: `home` route, `ShellContext.HOME`-less: uses TRADING accent, rail unchanged) | router | `HomeNavigationTest` |
| AB01 logo | `ShellRail.logo()` (E: focusable button, accessible "BYX — Go to Home"), `PanelApp.show("home")` | `ShellRouter.request` | `HomeNavigationTest` |
| AB01 no-op on Home | `ShellRouter.request` (E: same-route → no `display`) — preserved by existing idempotence + explicit guard in logo action | router | `HomeNavigationTest` |
| AB01 guard | `NavigationGuard` (N), `View.saveChanges()` (E), `SettingsScreen` (E), `PanelApp.evaluateRoute` (E) | `View` | `HomeNavigationTest` |
| AB01 shortcut/palette | `ByxShell.onShortcut` (E), `ShortcutRegistry` (E), `PanelApp.paletteIndex` + `CommandPalette.commands` (E) | — | `HomeNavigationTest` |
| B01 Home | `panel.homeview.HomeScreen` (N), `HomeModel` (N), `HomeMarkets` (N), `ServiceCards` (N) | `TraderSnapshot` + `DeskModel.feed`, `ByxSnapshot`, `DockModel` | `HomeMarketsTest`, `ServiceCardsAndLandingTest`, `HomeScreenTest` |
| B01 landing | `LandingPreference` (N), `PanelApp.primaryRoute` (E) | in-memory per account | `ServiceCardsAndLandingTest` |
| B02 login | `AuthScreens.renderLogin` (E) + `RegistrationAvailability` (N) | Service has no register op → `UNAVAILABLE` | `EntryPresentationTest` |
| B02 welcome | `WelcomeDialog` (N), `PanelApp.openOnboarding` (E) | `LandingPreference` | `EntryPresentationTest` |
| B02 stale login | untouched `LoginController`, `AuthService` epochs | — | existing `V21vStaleLoginBoundaryTest`, `AuthenticationEpochTest` |
| B03 avatar | `panel.shell.avatar.MascotAvatar` (N), `AvatarMotion` (N), `OperationRegistry` (N), `AvatarPaths` (N) | `MotionService.preference`, scene mouse events | `MascotAvatarTest`, `AvatarMotionTest`, `OperationRegistryTest` |
| B03 header | `ShellTopBar` (E: `setUser` installs avatar graphic), `UserMenu` (E: expanded state → avatar) | — | `AvatarHeaderTest` |
| B03 Chain Data | `ChainDataScreen` (E: header mascot, hint bubble, guide removed) | — | `ChainDataScreenTest` (E) |
| B04 Benefits | `BenefitsState` (N), `BenefitsScreen` (E, rebuilt layout) | `ByxData`, `ServerAuthorization.REQUIRED` | `BenefitsStateTest`, `BenefitsScreenTest` |
| i18n | `panel.i18n.Strings` (N) | properties | `StringsParityTest` |
| Tokens | `DesignTokens`/`byx.css` (E): `avatar.bg`, `ring.1..5`, mascot timings in `MascotTokens` (N) | `BYX_PACKAGE_B_TOKENS.json` | `OperationRegistryTest` (tokens) |

## Dependencies (D-xx from the design package)

| ID | Status in this implementation |
|---|---|
| D-01/D-02/D-03 registration | PENDING (backend). UI shows `unavailable`. |
| D-04 catalog | Satisfied for exactly one instrument (ETHUSDT USD-M perpetual). |
| D-05 symbol switch | Terminal supports ETHUSDT only → navigation limited to it. |
| D-06 tier policy | Panel `EntitlementService` TEST policy is not exposed in production (F3). Tiers are not shown unless the Service returns them. |
| D-07 benefit status codes | Derived locally from real exceptions/reasons (`SERVER_AUTHORIZATION_REQUIRED`, `AccessDeniedException`, chain state). No new wire codes. |
| D-09/D-10 | Not present; honest copy only. |
| D-11 landing persistence | PENDING (F1). In-memory. |
| D-12 system reduce-motion | Existing `MotionPolicy` / `SystemMotionProbe` reused. |
| D-13 operation registry | `OperationRegistry` API implemented; only real operations are wired (login submit, Benefits refresh). |
| A-01 vector | PENDING; traced path used. |
| A-04 fonts | Schibsted Grotesk + JetBrains Mono already bundled (OFL). |

## Status

Implemented and verified; see `docs/qa/package-b/implementation-report.md` (final status `PACKAGE_B_IMPLEMENTED_READY_FOR_UAT`).
