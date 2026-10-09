# BYX-MVP — Package B native JavaFX implementation report

**Status: `PACKAGE_B_IMPLEMENTED_READY_FOR_UAT`**

Local development candidate for the user's visual and functional acceptance. It does not authorize production custody or real trading, it is not a release, and it does not replace LOCAL RC1.

`REAL USER KEY = NOT AUTHORIZED` · `REAL TX = DISABLED` · `BROADCASTS = 0` · `REAL FUNDS = 0` · `REAL USER WALLETS = 0` · `ETHUSDT RESEARCH = UNTOUCHED`

Work stopped here. Package A, Package C and real-wallet development were not started.

---

## 1. Source state and pre-existing changes

| Item | Value |
|---|---|
| Repository | `/Users/buynnex-corp/dev` (Panel: `mvp-binance-panel/`) |
| Branch / HEAD | `feature/byx-ui-redesign-v1` / `9d3eb1e50ea4cc03e9523d4e178dd83e8836fb38` |
| Pre-existing working tree | 224 modified/untracked entries at session start (Service, packaging, Panel V2.1V/W work, `BYX/`, `iaos-web/`). All preserved. |
| Operations NOT performed | reset, clean, stash, rebase, checkout, commit, push, amend |
| Package B on top of | the same uncommitted base RC1 was built from; the Service and packaging scripts were not edited |
| Baseline measured before editing | Panel `mvn test`: **752 run / 0 fail / 0 err / 1 skip** (identical to the V2.1V figure) |
| Panel source tree hash (588 files) | `5857255a130bca2e8f2ac17846c1ccec236829d7d70056b0b7bcfd7a9ec76c3a` (`panel-source-hashes.json`) |
| Candidate | `byx-packaging/build/local-pkgb-uat/1.0.0-local-pkgb-uat-9d3eb1e50ea4-20261009T045154Z/` |
| Candidate zip SHA-256 | `88122d266440b98430949aa1b28ec9c9aaeb05b61d69026bc3bd9ac09b91fb88` (`candidate-manifest.json`) |
| RC1 | zip SHA-256 re-checked after the work: `cd382408…a04a2d7`, **unchanged**; manifest and tree untouched |

The inputs listed in the task brief were found as follows. The extracted design package did not exist at the expected path; I extracted `~/Downloads/BYX-PACKAGE-B.zip` to `design-inputs/BYX-PACKAGE-B/` (all 12 required items present). The six documents are byte-identical to the copy inside `BYX-PACKAGE-B-IMPLEMENTATION.zip`. The reference PNG sheets differ between the two zips by size; the sheets from the design zip were used.

## 2. Design version

`PKG-B-DESIGN-1` (`DESIGN_READY_WITH_EXPLICIT_DEPENDENCIES`). Tokens are read at runtime from a copy of `BYX_PACKAGE_B_TOKENS.json` in `src/main/resources/design/`. The HTML prototype was used only as a visual and interaction reference. No prototype demo value, demo credential, demo 2FA/email code or demo balance is in the product.

## 3. Files

Code map (requirement → component → source → test): `docs/package-b/CODE_MAP.md`.

**New (main)**: `homeview/{HomeScreen,HomeMarkets,ServiceCards,LandingPreference,WelcomeDialog}`, `shell/NavigationGuard`, `shell/avatar/{MascotAvatar,AvatarMotion,OperationRegistry,Operations,MascotTokens,AvatarPaths}`, `authview/RegistrationAvailability`, `byxview/BenefitsState`, `i18n/Strings`, four `package-b*_{en,pt-BR}.properties`, `design/BYX_PACKAGE_B_TOKENS.json`.

**Modified (main)** (several were already modified before this session; Package B is layered on them): `app/PanelApp`, `shell/{ByxShell,ShellRail,ShellTopBar,ShellRouter,ShellRoutes,ShellContext,ShellIcons,ShortcutRegistry,UserMenu}`, `authview/AuthScreens`, `byxview/{BenefitsScreen,ChainDataScreen}`, `ui/{View,CommandPalette}`, `accountview/SettingsScreen`, CSS `v2/{tokens,shell,screens}.css`.

**New tests (96)**: `HomeMarketsTest`, `ServiceCardsAndLandingTest`, `StringsParityTest`, `HomeNavigationTest`, `HomeScreenTest`, `EntryPresentationTest`, `OperationRegistryTest`, `AvatarMotionTest`, `MascotAvatarTest`, `AvatarHeaderTest`, `BenefitsStateTest`, `BenefitsScreenTest`.

**QA programs (not run by surefire)**: `PackageBFlowQa` (real PanelApp driven through the UI), `HomeVisualQa`, `BenefitsVisualQa`, `AvatarVisualQa`.

**Existing tests I had to change — please review**:
1. `ChainDataScreenTest`: the test `oneMascotPerScreen…` asserted behaviour of the Chain Data mascot that B03 requires removing. It was replaced by `chainDataHasNoDecorativeMascotAndEmptyStatesStayInformative` (no `MascotView` in the page; empty/not-found states still shown). Every data/state/threading assertion of the other 11 tests is unchanged and passes.
2. `ByxScreensTest.treasuryAndBenefitsPoll…`: `BenefitsScreen` now reads off the FX thread, so the test constructs it with synchronous executors. All its assertions (`Free`, `REFERENCE ONLY`, `NEVER`, `TEST POLICY · NOT FINAL TOKENOMICS`, timer lifecycle) are unchanged and pass.

No acceptance gate was weakened or skipped.

## 4. Native component architecture

* One shell, one router, one account menu. Nothing was embedded in a WebView and no second shell, router or theme system was added. Home is a normal route (`t-home`) rendered by `HomeScreen` in the existing V2 content host.
* `ShellRouter` stays the only navigation authority. AB01's logo, the palette entry and the shortcut all call the single action `ShellRouter.requestHome()`. `t-home` keeps the `t-` prefix so it can never fall into the Research gate.
* Semantic tokens: `-byx-avatar-bg`, `-byx-ring-1..5`, `-byx-bg-canvas` were added to the existing `tokens.css`; colours in the avatar come only through CSS classes.
* Strings: `panel.i18n.Strings` backed by 386 generated design keys (EN + PT-BR, symmetric, tested) plus hand-written extras. English is the only locale used; **no language switch is exposed**; PT-BR is not claimed.
* Threading: Home reads cheap snapshots; Benefits reads on a private worker with a generation token; the avatar uses one `AnimationTimer`; no I/O on the FX thread was added.

## 5. B01 — Home

* Default authenticated landing (precedence: authorized deep link → this account's choice → an explicit non-default workspace stored by an earlier version (BYX/Research) → **Home**). `TRADING` is the legacy default value and is not read as a choice of the Terminal.
* Regions: title/welcome, BETA · PUBLIC DATA · READ-ONLY · LIVE TRADING OFF · LOCALNET·TEST chips (derived from live state: trading flag, node environment, MOCK flag), Supported markets, Getting started (Hide is session-only), About this beta, Services.
* **Catalog ≠ subscription.** The catalog is exactly what the platform supports today: one instrument, **ETHUSDT USD-M perpetual**. Spot ETH is never listed. Data state comes from the existing `DeskModel.feed()` classifier (same rule as the Terminal).
* States implemented and tested: LOADING, READY, EMPTY, ERROR, OFFLINE, STALE, PARTIAL_DATA, UNSUPPORTED_SEARCH (+ "single catalog" note). A failed catalog request is ERROR, never EMPTY. Missing numbers render `—` with a tooltip, never `0`. Stale data shows its age and warn colour. Search filters only the real catalog (`BTC spot` → "isn't supported in this beta" + what exists).
* Terminal navigation only for `ETHUSDT` perpetual (D-05: the Terminal is bound to that feed). Other instruments show an informative note.
* Service cards: Markets, Terminal, BYX Network, Wallet, Benefits, Help (+ Research only for admin sessions, labelled **Restricted** and routed through the real verification gate). Labels AVAILABLE / READ-ONLY / UNAVAILABLE (with reason) / PLANNED follow real state; none is a trade, deposit or wallet-creation action.
* Responsive: 1180 px content width and above → two columns; table drops *Updated*, then *24h volume*, then *24h change* before ever truncating the symbol; cards 3→2→1 columns.

## 6. B02 — Entry and onboarding

* **Registration**: `RegistrationAvailability` is `UNAVAILABLE` in this build because the Service has **no** registration operation (wire ops are `auth.password/beginSecondFactor/verifySecondFactor/…` only). The login shows the honest line plus an expandable "How do I get access?". There is no create-account button, form, role choice or invitation field for any capability value, including a hypothetical `OPEN` (tested for all four values). `REGISTRATION_BACKEND_DEPENDENCY` recorded.
* Login behaviour untouched: real password verification, 2FA, trusted device, session restore, roles, logout, expiry, stale-login fencing, controller disposal. `LoginController`/`AuthService` were not edited.
* **Welcome** is one step: real beta facts (trading off, public data, network from the node, role shown as "assigned by the service"), choice of Home or Terminal, honest note that saving is unavailable. No credential field.
* **State scoping**: welcome-seen and the entry choice are kept **per account id, in memory** (`LandingPreference`). Shown at most once per account per run, never on a normal later login, replay only on request (Getting started → "Replay welcome", palette "Onboarding tour").
* "Forgot password?" keeps its existing honest "not available" page.

## 7. B03 — Mascot avatar

* The mascot is drawn with JavaFX vector primitives (`SVGPath` body from the design's traced path, two eye pills, five ring ellipses, a `!` mark) **inside the existing header button**. It replaces the initials; the button, its focus, Enter/Space, accessible name and the existing `UserMenu` are unchanged. The node is created once with the shell's top bar and is never re-parented or recreated per page.
* Menu: opening/closing the menu leaves the avatar in place with identical bounds (tested over 120 navigations and 40 menu cycles). The existing menu items and permissions are unchanged; no second menu was introduced.
* **Pointer**: scene mouse events only (no global cursor, no new macOS permission). Events outside the Scene are ignored; leaving the window returns the eyes to idle; nothing is allocated per mouse move. Tracking pauses when the window is unfocused/minimised.
* **Click**: the reaction is an additional listener on the same action; the menu opens in the same event. 490 ms = 110/160/220 ms phases (FULL), body-press only during loading/error, 100 ms press in REDUCED, none in OFF.
* **Motion layers** L0–L3 from `BYX_MASCOT_MOTION.md`: gaze τ 80 ms, clamp ±5.2/±4.2 units, lean 12 %, menu-open gaze (0, 3.4), loading amplitude 60 %, blink seed 7 within 4.2–6.8 s.
* **Operations (D-13)**: `OperationRegistry` with one token per real operation; show delay 300 ms, minimum 500 ms, error hold 4 s. A token from before logout/another registry is inert, so an old operation can never finish, cancel or overwrite a newer one. The 15 s watchdog is **presentation only**: it shows "did not finish", never "failed", and it never cancels, retries or completes the operation (a late real result is still accepted silently). Wired only to real operations: the Research gate's trusted-device/IPC check, sign-out, and the Benefits Refresh button. Background Benefits polling is deliberately silent. No decorative loading exists.
* **Modes**: FULL = tracking, drift (~30 fps), blink, 490 ms reaction, rotating rings; REDUCED = no tracking/drift/blink, 100 ms press, static partial rings with an accessible label; OFF = instantaneous states. Accessible name gains ", loading" / ", an operation did not finish".
* **Lifecycle**: one `AnimationTimer`, started only while the window is focused/visible and something needs frames; `dispose()` removes every filter/listener/handler, stops the timer and invalidates tokens. 25 shells created/disposed leaked 0 filters; 50 scene attach/detach cycles kept exactly 3 filters.
* **Chain Data**: the decorative mascot, hint bubble, guide, activity tracker and empty-state mascots were removed; the page's data, lookups, pagers and states are untouched.
* Not shipped: Mascot Lab, `MascotGallery` remains the pre-existing LOCAL_QA-only tool.

## 8. B04 — Benefits

* Pure model `BenefitsState` maps real conditions to the design's six states (`WALLET_NOT_LINKED`, `WALLET_SERVICE_UNAVAILABLE`, `BALANCE_UNREADABLE`, `NETWORK_OFFLINE`, `SERVER_AUTHORIZATION_REQUIRED`, `OK`). Technical conditions render as "can't be checked now", never as a plan limit; a missing balance is "Unknown / Not returned", never zero (a real zero is shown as zero); no capability is ever AVAILABLE outside the KNOWN state.
* **Production build**: the app denies account reads (`ByxData.accountOperationsUnavailableReason() = SERVER_AUTHORIZATION_REQUIRED`), so the real page shows "Session not authorized" with the code, tier "Unknown", capabilities "can't be checked now". Tiers are shown only from a real read (labelled TEST POLICY · NOT FINAL) or as the policy baseline for "no wallet" while the policy path is operational. FREE/HOLDER/PLUS/PRO appear only under "Planned" as non-operational references.
* Reads run off the FX thread; late results are dropped; Refresh is a real operation reported to the avatar. Payments/subscriptions/transfers show "NOT IN THIS BETA"; "Never granted, at any tier" (Admin, Validation, Final holdout, Unapproved live trading) stays visible. Footer: "This page only reads…".

## 9. AB01 — Logo → Home

* The logo is now a real button (Tab, Enter/Space, pointer, accessible name "BYX-MVP · Go to Home"); first Tab stop.
* No-op on Home when nothing is pending (no re-render); a pending request for another page is replaced.
* `Cmd/Ctrl+Shift+H`: verified free (registry has K, `,`, 1–6, `?`, Esc; macOS Hide is `Cmd+H` without Shift); registered in `ShortcutRegistry` (shows in the shortcuts dialog). "Go to Home" is the first palette item.
* Navigation guard for unsaved edits: **Stay here** (default focus, Esc, backdrop) / Discard and go / Save and go (shown only when the page can save; navigates only after a successful save; a failed save keeps the edit and the page and says so). Never discards silently. `View` gained `unsavedChangeCount/canSaveChanges/saveChanges`; `SettingsScreen` implements them.

## 10. Backend dependencies satisfied

D-04 (one-instrument catalog from the existing public feed), D-05 (Terminal switching limited to ETHUSDT), D-07 (states derived locally from real exceptions/reasons; no new wire codes), D-12 (existing `MotionPolicy`/system probe reused), D-13 (operation registry with real operations), D-14 (existing dock), A-04 (Schibsted Grotesk and JetBrains Mono were already bundled, OFL).

## 11. Backend dependencies still pending

| ID | What | UI behaviour meanwhile |
|---|---|---|
| D-01/D-02/D-03 | Public registration contract (`REGISTRATION_BACKEND_DEPENDENCY`) | "not available in this beta"; no form |
| D-11 | A preference channel. Every preference write is denied by `ServerAuthorization` (`SETTINGS_PERSIST`), which I did not bypass. | Entry choice and welcome-seen live in memory per account until the app quits; the UI says so. Consequence: on an install where `onboardingCompleted` is false, the welcome appears once per account per launch. |
| D-06 | Service-declared tier policy | tiers only from a real read, otherwise "Unknown"/Planned |
| D-09/D-10 | Notification and password-reset services | existing honest states |
| A-01 | Original mascot vector | traced path from the design (see §12) |

## 12. Mascot visual/motion fidelity

Checked in the real JavaFX scene (`visual/avatar/contact-sheet.png`, 20 states at 6×) and against the reel's contact sheet: same blob silhouette and white pill eyes, same ring-orbit and "!" scenes.

Honest differences:
* The body is the **traced approximation** (A-01 pending); an original vector may differ slightly.
* **Rings are scaled to fit the 36 px circle** instead of 1.4× the body width, so nothing overflows the header button or changes layout. Colours are the sampled `ring.1..5`.
* The orbit period is the design's **proposal (1200 ms), not tuned** against the reel. It is a single token (`mascot.rings.orbitMsPerRev`).
* The error look is **derived** (no explicit error scene exists in the reel): eyes squint and drop, a white "!" appears above them. The reel's "!" is a black glyph that replaces the body; the design requires the body to stay, so it is white over the dark body.
* The surprise reaction shows a brief "!" above the eyes while idle; during loading/error it is a body press only.
* I did not compare animation frame-by-frame against the original GIF; side-by-side review in UAT is recommended.

## 13. Authentication and authorization evidence

* Existing suites pass unchanged: `V21vStaleLoginBoundaryTest`, `AuthenticationEpochTest` (17), `AuthScreenCompletionOwnershipTest`, `LoginControllerTest`, `AuthKeyboardTest`, `AuthLayoutTest`, `ResearchGateReproductionTest`, `ServerAuthorizationBoundaryTest`.
* Real app, driven through the UI (`flow/package-b-flow-{FULL,REDUCED,OFF}.txt`, 39 checks each, 0 failures): default landing Home; welcome once per account; ADMIN's Terminal choice did not leak to a USER account; USER cannot open Research (route unchanged, toast, no operation left pending); no create-account control; logo/guard flows; avatar disposed on logout with 0 filters and a fresh one per session; Benefits shows "Session not authorized" with no upgrade wording; no operation left pending after Refresh.
* No client-side role/permission/preference path exists: the welcome result carries one field (the entry page), entry choices can only resolve to non-Research routes, and the Research gate is the unchanged `evaluateRoute`/`requestResearch`.

## 14. JavaFX visual QA evidence

All captures come from the real JavaFX scene graph (`docs/qa/package-b/visual/`, synthetic data only; the RC1 reference captures E01–E09 with a real account were not reproduced).

* Real app (`PanelApp` + test-only fake authority): Home, Benefits, Chain Data, Desk, menu open, palette at **1920×1080, 1440×900, 1100×700, 900×640**; FULL, REDUCED, OFF; Compact and Comfortable; ADMIN and USER (`visual/real/shell-*`, `visual/real/flow-FULL`: login, welcome, guard, Benefits).
* Fixture states inside the real shell: Home READY/STALE/OFFLINE/PARTIAL/ERROR/EMPTY/LOADING/UNSUPPORTED_SEARCH/spot+perp/admin card at 1440×900 and READY at all four sizes (`visual/home`); Benefits unauthorized/no-wallet/wallet-unavailable/offline/balance-unknown/known (`visual/benefits`).
* Problems found by inspection and fixed: truncated instrument cell and misaligned columns, table overflow at 900 px, cards stretching to viewport height, "PLAN…" pill truncation, duplicated authorization text, "!" overlapping the eyes.
* 900×640 is below the app's own minimum window (1100×700); it was rendered in a scene to confirm nothing clips, but the app cannot reach it.
* Not done: a screen recording. The avatar states are covered by the 20-state sheet and by deterministic motion tests.

## 15. Test counts

| Gate | Result |
|---|---|
| Baseline Panel suite (before) | 752 run, 0 fail, 0 err, 1 skip |
| **Final Panel suite (final sources)** | **848 run, 0 fail, 0 err, 1 skip** (the skip is the pre-existing opt-in capture observation) |
| New tests | 96 (12+6+3+8+7+6+9+13+10+6+10+6) |
| Real-app flow QA | 3 modes × 39 checks, 0 failures (stable on repeated runs) |
| Packaged smoke (candidate) | 9/9 pass: deep-strict signature, offline probe, DEFAULT Service ready, `packaged_verified`/CONNECTED/protocol 1, TX mutation/signing/broadcast disabled, packaged Panel FX pulse + Service READY, no owned process left, socket cleaned, no authority snapshot/wallet catalog created |
| Service / signer / custody / fencing suites | **not re-run**: no Service or signer code changed. The candidate's Service jar has entries identical to RC1's (`provenance-diff-vs-rc1.json`). |

One QA-harness flake was found and fixed (it polled the welcome dialog after a fixed delay and failed once on a slow run); the final harness passes repeatedly.

## 16. Known limitations

* Registration, preference persistence and tier policy depend on backend work (§11).
* Home lists a single market because the platform supports a single market.
* The wallet card uses the dock's existing wallet-read check on the FX timer (dock parity); it throws immediately under the production denial.
* `MascotTransitionOverlay` (short workspace-change animation, FULL only) still exists; only the Chain Data mascot was removed.
* The pre-existing toast "Dismiss" label truncates ("Di…"); not changed.
* The packaged candidate cannot be signed in by a human without a real account; the human signs in with their own account, as with RC1.
* The signing profile expires on 2026-10-13 (development profile; same as RC1).

## 17. Capture health

ETHUSDT collector pid 54866 under supervisor 10657 (the same pids recorded at RC1), `.part` file grew 81,544 bytes in 6 s (`capture-health.json`). Metadata only; no signal sent, no scientific data read, collector and supervisor untouched, nothing ran against `~/.mvp-binance-capture` or the data directory.

## 18. Next actions for human acceptance

1. Launch the candidate (`byx-packaging/build/local-pkgb-uat/1.0.0-local-pkgb-uat-9d3eb1e50ea4-20261009T045154Z/BYX-MVP.app`) and sign in with your own account. Because your settings file already has `onboardingCompleted=true` and `primaryWorkspace=TRADING`, you land on **Home** and the welcome does not auto-open; use Home → Getting started → **Replay welcome**.
2. Check: Home states and the market row; logo/`⌘⇧H`/palette to Home; the mascot in the header (cursor tracking, click reaction, menu open, REDUCED/OFF in Settings); Chain Data without the mascot; Benefits wording.
3. Side-by-side review of the mascot against the original GIF (tune `orbitMsPerRev`, ring extent) and decide on supplying the original vector (A-01).
4. Decide on D-01/D-02 (registration contract) and D-11 (preference channel) as backend work.

Status: `PACKAGE_B_IMPLEMENTED_READY_FOR_UAT`
