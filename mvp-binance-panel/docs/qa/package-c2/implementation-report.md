# BYX-MVP — Package C2: Native LIGHT/DARK

Publication update (2026-10-09): **human UAT APPROVED by project owner**. See [approval and evidence scope](UAT_APPROVAL.md); status fields below are historical handoff records.

Status: **PACKAGE_C2_IMPLEMENTED_READY_FOR_UAT**. Human UAT pending. `PUSH_NOT_ATTEMPTED`.

## 1. Preflight repository state

Branch `feature/byx-ui-redesign-v1`; HEAD `2dd12ca65072d46599449a25932ddce881d14746`. Approved C1 entered as uncommitted work (21 modified / 13 new source files); unrelated iaos-web and untracked work were preserved. Preflight, 3,677 protected hashes and entry source archive preceded edits. [Preflight](preflight-git-status.txt), [C1 baseline](c1-baseline-preservation.json).

## 2. Approved design source

Approved local `design-inputs/BYX-PACKAGE-C2`, ZIP SHA-256 `d16db8baa738189e15de6c3b8c55893cb5e68f1419d0ca9d74a6c5a727fa4186`. Reviewed required handoff files, 115 tokens, 29 design routes, 87 design screenshots and contrast evidence. The design contact sheets are reference material; native screenshots below are separate. [Review](design-reference-file-review.json).

## 3. Theme architecture

Extended `ByxTheme`, existing stylesheets and views. `ThemeMode`, immutable typed `ThemePalette` and 115-entry `ThemeToken` provide central paint ownership. FX-thread-only mode transitions mark weakly held roots with pseudo classes; JavaFX resolves CSS on its next pulse. Weak scene-scoped subscriptions repaint custom graphics without new timers or backend work. Native popup scenes inherit the owner theme. No WebView or parallel application. [Implementation map](../../package-c2/THEME_IMPLEMENTATION_MAP.md).

## 4. Token mapping

Copied authoritative token JSON as a packaged resource. Both palettes contain all 115 semantic tokens. Original DARK tokens.css is unchanged; original fixed DARK paints remain exact through `ByxTheme.paint`. LIGHT maps native controls, legacy lookup aliases and Canvas paints centrally. [Resolved colors](native-resolved-colors.json), [original paint audit](hardcoded-paint-audit-before.json).

## 5. Full route coverage

Native PanelApp coverage: 34 general routes, 15 Research routes, login, Appearance, menu, palette, notifications, modal, Research gate and onboarding. Research navigation uses the existing synthetic test authority and normal email/SMS MFA flow. No production authorization bypass. [Native results](native-results.json), [route log](native-visual-release/native-flow.txt).

## 6. DARK compatibility findings

Preserved original token palette, typography, layout, asset geometry, role gates and structural style-class lists. Real Desk test checks DARK → LIGHT → DARK against the original resolved card. Meaningful menu/palette helper text on hover uses secondary ink; muted token remains unchanged. LegacyBoundary and keyboard assertions were not weakened. A reduced-blink fixture now initializes its manual clock before Scene attachment, eliminating a real-time/manual-time race; all original assertions remain. Intermediate failures and probes are retained in logs/history, not counted as final qualification.

## 7. LIGHT implementation

Native LIGHT surfaces, text, borders, semantic feedback, focus and popup paints come from approved tokens. Actual nested Desk controls exposed JavaFX selector precedence missed by token-only probes; `.root:byx-theme-light` anchors fixed this centrally. Minimum-size capture also exposed the generic Settings intrinsic-width constraint: the wrapping ThemeSelector now keeps min-width zero, with viewport-bound assertions in EN/PT. New real-component regression verifies card FA FB FD, axis 59 67 7D and avatar outline 69 7A 92, plus DARK restoration. No structural class changes or eager traversal of every cached Scene.

## 8. Chart/data visualization changes

Existing native CandleChart/Canvas retains candle list, OHLC, order book, current price, daily PnL, viewport and chart identity on switching. Theme observers repaint grids/axes/Canvas. Added native pointer crosshair and OHLC tooltip with C1 number formatting; flat candles get finite display scaling. Actual model has no per-candle volume: no fabricated volume bars were added; existing 24h volume remains. Six synthetic approved feed fixtures (live/stale/degraded/disconnected/waiting/error) use the same data for each DARK/LIGHT pair. [72 graphics checks](graphics-release/graphics-checks.txt).

## 9. Mascot compatibility

Native 36px B03 body artwork, eye art, gaze, blinking, five-ring reactions and operation lifecycle remain unchanged. Body 05070B and eyes EEF1F8 are invariant. Only central palette paints changed. Forty native state images verify identical poses/presentation across themes. FULL/REDUCED/OFF and cleanup are covered by fresh tests and native flows. [Mascot images](mascot-release), [flow variants](native-results.json).

## 10. Appearance Settings integration

Native RadioButtons with palette previews: Dark / Light / System. Dark/Light are usable with DEFAULT preferences DENY; selection does not call savePrefs or imply backend persistence. Density/motion drafts, save bar, role help and inputs remain in the existing Settings view. Keyboard/native accessibility roles and C1 copy binding are reused. [PT LIGHT Appearance](native-visual-release/light-pt-BR-appearance-1100x700.png).

## 11. SYSTEM adapter status

Unavailable and disabled with translated explanation. Runtime is JavaFX 21.0.5; its Platform API does not provide the platform appearance preference API. No qualified adapter exists in this project. No OS polling, subprocess, permission request or invented automatic mode was added. [Official Platform API](https://openjfx.io/javadoc/21/javafx.graphics/javafx/application/Platform.html). Unsupported SYSTEM is permitted by the dependency contract.

## 12. Persistence behavior

Session only. Selection resets to DARK at logout, account replacement and process restart, independently of language reset. No new persistence store and no ServerAuthorization bypass. System selection cannot mutate mode. A compatible approved store remains a future dependency.

## 13. EN/PT-BR compatibility

C1 catalog/resources and translation behavior preserved. Added 13 production Appearance keys per language; excluded draft-only handoff keys. Total 2,128 per language with key/parameter parity. Theme and language are independent; input values and financial precision are literal. Final native flow reports no missing keys or English fallback. Bundled probe verifies the packaged catalog and native selector (482 checks). Additional full native navigation: 1487 checks PASS, loading only packaged production JARs plus the external test authority/harness.

## 14. Contrast results

Actual JavaFX CSS resolves 230 token paints exactly. **135 required pairs PASS; 0 failures**. Method: WCAG sRGB relative luminance. Real-widget regression additionally verifies resolved LIGHT card, chart axis, avatar border and native scrollbar thumb (essential ink 697A92 rather than subtle separator ink), avoiding reliance on token-only evidence. Known approved DARK exceptions: muted on hover 3.837:1; semantic avatar boundary/container 2.425:1. The existing native DARK scrollbar thumb remains a low-contrast baseline limitation and is explicitly excluded from a non-text pass. DARK actual decorative outer alpha outline retains legacy alpha and is separately classified in visual-contrast-notes.json. Disabled controls, subtle separators and decorative grids are exempt; LIGHT required text/essential/focus pairs meet 4.5/3/3. [Measurements](native-contrast.json), [classification](visual-contrast-notes.json).

## 15. Supported-resolution QA

Native exact Scene snapshots at 1920×1080, 1440×900 and unchanged minimum 1100×700, both languages/themes. 418 final native PNGs, with dimensions and hashes. Compact/OFF full route checks with representative native screenshots plus Comfortable/FULL and Comfortable/REDUCED native flow checks; fresh Settings tests preserve motion drafts at all three sizes. Paired composites use original native PNGs, same fixture/route/size/locale; no HTML or fabricated financial data. [Manifest](visual-manifest.json), [paired review](paired-comparisons).

## 16. Files modified

34 source/resource files changed versus the entry archive: 17 modified, 17 added, 0 deleted. All product changes are in Panel. POM/build pipeline, Service, signer/custody and capture source unchanged. [Exact inventory](source-change-inventory.json), [tested source hashes](tested-source-hashes.json). QA scripts/reports and bounded native evidence are additional.

## 17. Fresh Panel test counts

Final exclusive desktop execution: `JAVA_HOME=/Users/buynnex-corp/dev/tools/jdk-21.0.12.1+1/Contents/Home /Users/buynnex-corp/dev/tools/apache-maven-3.9.9/bin/mvn -q clean test`. **923 tests / 922 PASS / 0 FAIL / 0 ERROR / 1 expected SKIP**. Includes 28 new focused C2 tests, existing full regression and actual-component cascade test. Expected SKIP: `CaptureLiveObservationTest`, opt-in `capture.live` property absent; no live scientific-content observation enabled. No assertions relaxed. Final XML and compiled/source hashes are frozen before packaging. [Summary](panel-suite-summary.json), [XML](panel-suite-xml), [log](full-panel-suite-release.log).

## 18. Native UI flow results

Final results and exact counts: [native-results.json](native-results.json). Existing sessions, view/shell/mascot identities, route, focus, locale and MFA boundaries are checked during both theme transitions. Auth states are captured in both themes/languages at three sizes. Graphics checks preserve financial fixtures; mascot captures preserve animation poses. External synthetic authority is confined to test fixtures and is not packaged.

## 19. Packaged smoke results

**11/11 signed launcher/runtime smoke checks PASS**: deep/strict signature; offline UNAVAILABLE; DEFAULT helper; packaged_verified/CONNECTED protocol 1; mutations/signing/broadcast disabled; real JavaFX startup; Panel and Service restart; owned process/socket cleanup; no authority snapshot or wallet catalog initialized. Separate external bundle probe uses only packaged production JARs plus external probe/test classes; it is not a signed-launcher authenticated UAT claim. [Smoke](packaged-smoke/result.json), [bundle probe](packaged-smoke/bundle-probe.log).

## 20. Artifact path and checksums

Candidate **1.0.0-local-pkgc2-uat-20261009T172945Z**, display name **BYX-MVP Package C2 UAT**. App: `/Users/buynnex-corp/dev/byx-packaging/build/local-pkgc2-uat/1.0.0-local-pkgc2-uat-20261009T172945Z/BYX-MVP Package C2 UAT.app`. ZIP: `/Users/buynnex-corp/dev/byx-packaging/build/local-pkgc2-uat/1.0.0-local-pkgc2-uat-20261009T172945Z/BYX-MVP-Package-C2-UAT-1.0.0-local-pkgc2-uat-20261009T172945Z.zip`. ZIP SHA-256 `c8998f79ff65e079ebae09d6e2fcb9b53c07d9e49048241be98db19a2de7ba08`; Panel JAR `dbb646e4e1274582240ab7959d0c0f3a96e8943d2c39fe364d07701786fa6df9`; Service JAR `9520ddc0566645fe8bbd48f0f23d8b7bb79ec10f4d8406dbe445fbc916852225`. 691 Panel class entries match qualified output; five theme resources match source. Bundled Java runtime 21.0.12.1, JavaFX 21.0.5; build JDK 21.0.12.1+1 and Maven 3.9.9. Runtime metadata: [release](packaged-smoke/runtime-version.txt). Existing build-app.sh with embedded local profile and `--chain-profile production-disabled`; only display label/path changed afterward and re-signed with the same identity, identifier, designated requirement and entitlements. [Exact recipe/signature manifest](candidate-manifest.json). Earlier intermediate C2 candidate is retained separately and superseded. RC1/A/B/C1 are not overwritten.

## 21. Known limitations

Human UAT remains pending. SYSTEM unavailable; preference session-only. Per-candle volume absent from approved model. Existing backend/content placeholders remain accurately unavailable. Approved DARK decorative contrast exceptions and the existing low-contrast DARK scrollbar thumb are classified, not silently repainted. LIGHT thumb uses essential ink, with actual native assertions. Native Gaussian shadow geometry is retained with the approved semantic shadow paint; Appearance choices use the existing body typography. Local Apple Development profile expires 2026-10-13 16:49:55 UTC; this is a local UAT candidate, not a distribution release. Native QA uses synthetic isolated authority/data; owner checks ordinary real-account UI without keys/trading. Existing compact layout elides some metric values/headings at 1100px; full values remain in data and accessibility text. No geometry changes were introduced to address this pre-existing behavior. JavaFX unnamed-module/SLF4J and isolated legacy log -warn lookup warnings occurred; final assertions passed.

## 22. Security verification

DEFAULT is PRODUCTION_DISABLED, wallet capability DISABLED; no Transaction Lab or QA harness in bundle. Service JAR and every entry are byte-identical to C1. Preferences persistence stays DENY. No auth/Research/MFA/custody policy changes, real account creation, wallet/key access or transactions. REAL USER KEY = NOT AUTHORIZED; REAL TX = DISABLED; BROADCASTS = 0; REAL FUNDS = 0; REAL USER WALLETS = 0; ETHUSDT RESEARCH = UNTOUCHED. [Final audit](final-safety-audit.json).

## 23. ETHUSDT capture health

Metadata-only checks: same collector PID 54866 and supervisor PID 10657 remain alive with the same parent relationship. Current .part growth/rotation is recorded, without reading scientific contents or sending signals. No TRAIN, VALIDATION or FINAL_HOLDOUT. [First check](capture-health-first.json), [final check](capture-health-final.json).

## 24. Git status

HEAD and branch unchanged. No commit, stage, reset, clean, rewrite or push. Existing C1 and unrelated work preserved; exact final worktree state in [final-git-status.txt](final-git-status.txt). `PUSH_NOT_ATTEMPTED`; publication requires owner UAT approval. C2 ends here; no C3/C4 or Windows/Linux work.

## 25. Human UAT instructions

Close an older BYX-MVP instance before opening the exact new candidate above; keep RC1/C1 available separately. Sign in through the ordinary approved flow without real keys/wallet operations. In Settings → Appearance select Light and Dark; confirm same drafts, focus, scroll, open menus/modals and pending operations. Test EN/PT-BR independently, Compact/Comfortable and FULL/REDUCED/OFF at all three sizes. Inspect Trading Desk candles/axes/book/PnL/crosshair, 36px mascot eyes/rings and Research MFA gate. Confirm System disabled and session-only explanation; logout/restart should restore DARK and EN. Review paired native captures and known exceptions. Approve/reject this version explicitly; no Git publication has occurred.
