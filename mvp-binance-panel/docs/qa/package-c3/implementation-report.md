# Package C3 — implementation and UAT delivery

Status: **PACKAGE_C3_IMPLEMENTED_READY_FOR_UAT**. Prepared 2026-10-09. Human UAT was explicitly approved by the owner in the final publication request. C3 is being prepared for the authorized publication; source qualification remains unchanged. This report describes the final qualified source and isolated signed local candidate, not a production release.

## Repository and preflight

Repository root: `/Users/buynnex-corp/dev`; application: `mvp-binance-panel`; branch: `feature/byx-ui-redesign-v1`; origin: `https://github.com/ChamberUS/DEV.git`. Initial and final HEAD: `1a35f0da76032898430c6cd0ea69961665a687c4`, matching the verified existing upstream. Published C1 `08f6e026a610266ea4db5130b27790f780eb1ec5` and C2 HEAD are present; A/B ancestors remain intact. A, B, C1 and C2 QA reports were reviewed before implementation.

The index and Panel source were initially clean. The 31 tracked iaos-web changes and 6,422 previously reported unrelated untracked files were preserved. Final audit compares the exact outside-Panel status inventory (2,966 porcelain entries, including collapsed directories) against preflight, rather than confusing that count with individual files. No reset, clean, stash, amend, rebase, branch switch, commit, push or release was performed. C3 source changes comprise eight modified and ten added files, with zero deletions. Documentation and local QA artifacts remain untracked. Full local inventories are retained for audit, not intended for indiscriminate publication.

## Event-source audit and architecture

The [initial event-source audit](../../package-c3/EVENT_SOURCE_AUDIT.md) was produced before source edits. Its table records publisher, actual trigger, existing contract, authority, payload/privacy, reliability, destination and backend dependency for every candidate source.

`NotificationEvent` is an immutable record with UUID identity, closed semantic type/severity/destination enums, observed UTC time, deterministic tie ordering, read state and bounded occurrence count. It accepts no arbitrary message, localization parameter, response body or executable navigation callback. `NotificationCenter` owns the FX-thread collection and view state; `ServiceNotificationObserver` translates existing probe results without performing IO. `NotificationCenterView` renders the shared native interface. Separate publisher/view-model infrastructure was unnecessary: the scoped center API and observable properties provide those responsibilities.

Production publishers are bound to a captured account/session/generation scope. Invalidating a scope revokes it synchronously before FX cleanup. UI updates occur on the FX Application Thread; old worker results and queued FX callbacks cannot populate a newer session. The existing Panel `LocalServiceMonitor` now captures a per-start observer for its existing poll and retry tasks. It retains the same scheduler, probe and polling interval; no notification executor, timer, network request or research poll was added.

## Genuine implemented sources

| Source | Observation and resulting notification | Reliability and privacy |
|---|---|---|
| Existing authenticated Service probe | CONNECTED followed by fixed `not_started`/`refused`: connection lost; subsequently verified CONNECTED: restored | Confirmed local absence/refusal and verified recovery only. First CONNECTED is never recovery. No pending-operation outcome inferred. |
| Existing Service availability probe | Initial confirmed absence: unavailable; timeout/no-answer: availability uncertain | Initial absence is not called a disconnect. Uncertain availability is not an acknowledged operation failure. |
| Existing client security/contract checks | AUTH_FAILED/INSECURE_PAIRING: generic security rejection; INCOMPATIBLE: generic contract error | No pairing proof, token, instance identity, filesystem path, raw error or API body enters the event. |
| Actual Panel Research gate failure | Completed verification exception or native gate failure: generic locked-access warning | ADMIN only, without Research content or exception details. Existing pending-request/session guards remain. |
| Existing administrative elevation watcher | Elevation expires while in Research: attention required | ADMIN only, fixed generic copy. Existing watcher is reused; destination remains subject to normal authorization. |

Logout and account/role changes are cleanup triggers, not private notifications transferred into another account. An expired ordinary session has no valid owner: the existing blocking sign-in UI remains authoritative. Session-expired vocabulary exists, but C3 does not fabricate a retained event for a subsequent login.

## Unsupported sources and dependencies

There is no approved remote notification API, server history, remote read receipt or notification store. Those capabilities remain visibly unavailable. OS push/permissions, BYX chain/transaction/wallet/custody events, Research jobs, capture, Validation and Final Holdout events are outside C3. Broad toast and avatar operation signals are not imported: a busy indicator ending is insufficient evidence of operation success. Existing status surfaces remain available for unsupported categories.

Future remote history requires an approved authenticated backend contract, account authorization, retention/privacy policy and read-receipt semantics. No Service, signer, custody or scientific capture architecture was changed. `panel/localservice/LocalServiceMonitor.java` belongs to the Panel; the related Service project is unchanged.

## UI, retention and read state

The existing header bell opens a native JavaFX popover with title, live unread count, bounded ListView, severity glyph and word, short description, relative timestamp, exact localized timestamp tooltip, individual read/unread action, allowlisted navigation and mark-all-read. Source availability, source error, empty collection and local-only history are distinct states. The existing Account notifications route shares the same center; it introduces no sidebar or new workspace. The legacy constructor without a center stays honestly unavailable.

History contains at most 100 rows, newest first by UTC/tie order; the oldest row is evicted regardless of read state. At most 200 recently seen event UUIDs are remembered for deduplication. Same-type events aggregate within 30 seconds, preserving the original row identity and bounded occurrence count. Unchanged ordinary polling state produces no new notification. Repeated observed security rejections update an occurrence count without resetting read state or flooding rows; this count describes observations, not distinct attacks. Technical logging remains separate. These bounded policies do not promise indefinite deduplication or lossless event history.

Read/unread and mark-all-read change memory only. Locale/theme changes preserve IDs, ordering, selection and read state. Relative age is refreshed when rendering/reopening/interacting; no new periodic timer was added. All event timestamps are stored in UTC; the tooltip uses the active locale and local timezone.

## Session, role and navigation isolation

Each scope carries session UUID, account ID, generation and ADMIN eligibility. Logout, account replacement and role changes revoke old scopes and clear history/read state. A role change fails closed and requires a fresh authenticated session before ingestion resumes. USER cannot publish or view ADMIN event types. Even ADMIN events contain only generic gate information, so they disclose no protected Research details before reauthentication/MFA.

Destinations are closed allowlisted values mapped to existing System status, Security and Research overview routes. Opening a row uses the existing application router; the notification itself grants no authority. Research reauthentication and 2FA gates remain mandatory. Disposable session presentation observers and scene-bound view subscriptions prevent cached detached Account views or repeated popover openings from accumulating listeners. Disposal removes observers, actions and scene listeners; hidden list items detach.

## Persistence, localization, themes and motion

History and read state are current-session memory only and disappear on logout/restart. No preference, notification or sensitive event data is written through a bypass of `ServerAuthorization`. Server-backed history/read receipts are unavailable. Accepted C2 limitations remain: SYSTEM theme is unavailable; language/theme preferences are session-only.

C3 adds 37 keys in each of EN and PT-BR, bringing the combined existing catalog to 2,165 keys per language. Direct-key rendering updates visible content on language changes without generating events. Notification-prefixed keys are excluded from the generic C1 source-phrase adapter so they do not change approved translation precedence.

Native tests exercise DARK/LIGHT and EN/PT-BR at 1100×700, 1440×900 and 1920×1080. C3-only CSS uses the approved semantic tokens for list backgrounds, scrollbars, selection and focus; existing palette/global rules remain intact. Wrapping rows do not create horizontal scrolling or truncate the severity glyph. FULL, REDUCED and OFF use existing overlay motion contracts. Opening the center starts no unrelated mascot operation or animation.

## Keyboard and accessibility

The bell exposes a localized accessible unread count and calls JavaFX TEXT attribute-change notification when that text changes. Native list roles, localized row/control labels, severity words, timestamps and visible focus are present. Enter activates the selected notification; Escape and outside click close the existing popover and restore focus. Tab traversal follows native controls without an added modal focus trap. Severity is communicated by text/glyph as well as color.

Automated native tests verify accessible text/roles/change updates, keyboard behavior and focus restoration. They do not certify the complete macOS VoiceOver experience; human UAT must verify announcements and traversal with VoiceOver.

## Fresh verification and provenance

| Gate | Final result | Evidence |
|---|---|---|
| Full clean Panel regression | 977 total; **976 PASS, 0 FAIL, 0 ERROR, 1 expected SKIP**; 131 suites | [summary](panel-suite-summary.json), local-only `full-panel-suite-final.log`, local-only `panel-suite-xml/` |
| Focused C3 JUnit cases | 54 PASS: model/observer 31, native view 17, actual app isolation 6 | [28-requirement test mapping](../../package-c3/TEST_MATRIX.md) and final XML |
| Native PanelApp flows, OFF | 173 PASS, 0 FAIL; 60 final native snapshots | [flow log](native-qualified/native-flow.txt) |
| Native PanelApp flows, FULL | 133 PASS, 0 FAIL | [flow log](native-full-qualified/native-flow.txt) |
| Native PanelApp flows, REDUCED | 133 PASS, 0 FAIL | [flow log](native-reduced-qualified/native-flow.txt) |
| Isolated signed candidate | **11/11 PASS** | [smoke results](packaged-smoke/result.json), local-only `packaged-smoke-final.log` |

The one expected SKIP is opt-in `CaptureLiveObservationTest` without `capture.live`. No live scientific data qualification was requested or performed. Historical C2 counts are context only; the table reports fresh C3 runs. Existing tests were not relaxed or rewritten.

Native flow QA runs the actual JavaFX PanelApp with isolated synthetic test authority. It exercises a real missing-Service probe for offline state; controlled security and recovery statuses are deterministic test fixtures, not claims of production activity. It covers empty, offline unread/read, security error and recovery; opening/closing/navigation, language/theme, mascot stability, logout and stale ownership. Test publishers and authority are confined to test sources and excluded from DEFAULT packaging. Signed smoke covers actual launch/restart and Service behavior; human UAT approval was subsequently declared by the owner for this package.

The final 637-source-file manifest matches the qualified source byte for byte. The frozen clean-test output matches 703 packaged Panel classes and 771 compiled entries; seven required theme/localization resources also match. Existing ignored local QA/bytecode artifacts are not packaged. The Service JAR is identical to the qualified previous candidate. See [qualified build](qualified-build.json), [source manifest](tested-source-hashes.json), [compiled manifest](tested-output-entry-hashes.json) and [candidate manifest](candidate-manifest.json).

Earlier findings are retained in [intermediate findings](intermediate-findings.json), rather than substituted for final evidence. The first full suite exposed a C1 translation precedence collision, fixed in the adapter without weakening its assertion. Native visual review exposed list width/scrollbar and severity-glyph sizing issues, fixed locally. An own unmodifiable-list assertion was corrected to test a populated list. The first signed smoke rejected the added `JAVA_TOOL_OPTIONS` under the existing peer-environment policy; final smoke removed it and used the minimal qualified C2 environment, without changing security policy. Existing JavaFX unnamed-module, SLF4J and legacy CSS lookup warnings remain; no new C3 CSS warning was accepted.

## Screenshots

All 60 final snapshots are in [native-qualified](native-qualified/), with dimensions and SHA256 in the [visual manifest](visual-manifest.json). They span both themes/languages, three sizes and five states. Intermediate screenshot directories are retained locally but are not final evidence.

Representative final images:

- [DARK / EN / empty / 1100×700](native-qualified/dark-en-empty-1100x700.png)
- [LIGHT / PT-BR / offline unread / 1440×900](native-qualified/light-pt-BR-offline-unread-1440x900.png)
- [DARK / PT-BR / read / 1920×1080](native-qualified/dark-pt-BR-offline-read-1920x1080.png)
- [LIGHT / EN / security error / 1100×700](native-qualified/light-en-service-error-1100x700.png)
- [DARK / EN / recovery / 1440×900](native-qualified/dark-en-recovered-1440x900.png)

## Isolated signed local candidate

Application name: **BYX-MVP Package C3 UAT**.

Version: `1.0.0-local-pkgc3-uat-20261009T191714Z`.

Application:

`/Users/buynnex-corp/dev/byx-packaging/build/local-pkgc3-uat/1.0.0-local-pkgc3-uat-20261009T191714Z/BYX-MVP Package C3 UAT.app`

ZIP:

`/Users/buynnex-corp/dev/byx-packaging/build/local-pkgc3-uat/1.0.0-local-pkgc3-uat-20261009T191714Z/BYX-MVP-Package-C3-UAT-1.0.0-local-pkgc3-uat-20261009T191714Z.zip`

ZIP SHA256: `b229e5ee81195ca9e97ffdfde607a7d0bdfede48fb20903789672acbfd65defc`.

Panel JAR SHA256: `94cab6b7e35b319e4fbbd3f73b0bb3b614162c34069cbf3b8f25b53f281a88ac`.

Service JAR SHA256: `9520ddc0566645fe8bbd48f0f23d8b7bb79ec10f4d8406dbe445fbc916852225`.

The unchanged packaging pipeline built a separate output directory using the existing local development provisioning and `production-disabled` chain profile. Only this new output received the friendly name and was re-signed with the same qualified identity, identifier, designated requirement and entitlements. Developer identity/certificate/provisioning secrets are not included in source/report publication. Deep strict signature verification passed. DEFAULT is PRODUCTION_DISABLED, wallet capability DISABLED; no TxLab or test harness is bundled. Local development provisioning expires **2026-10-13 16:49:55 UTC**; this is a local UAT candidate, not a distribution release. Older C1/C2 candidate ZIP checksums remain unchanged: [preservation evidence](preserved-candidates.json).

All 11 signed gates passed: deep strict signing; offline UNAVAILABLE; nested DEFAULT Service ready; verified CONNECTED protocol 1; mutation/signing/broadcast disabled; GUI FX startup; GUI restart; Service restart with DEFAULT deny; own processes cleaned; own socket cleaned; no authority snapshot or wallet catalog initialization. Smoke used temporary Service state and only its own PIDs, with no collector/supervisor signals.

## Changed files and security audit

The [source change inventory](source-change-inventory.json) lists every modified/added source file and SHA256. Changes are limited to PanelApp, disposable SessionManager presentation observation, Panel LocalServiceMonitor generation ownership, the existing bell and Account notifications surfaces, Strings/Presentation integration, C3-scoped CSS, four notification classes, two localized resources and four test sources. No existing test file or dependency configuration was changed.

The [final safety audit](final-safety-audit.json) verifies 2,956 protected non-Panel files unchanged, all 637 tested source hashes matched, no deleted source, empty index, unchanged HEAD and exact preservation of outside-Panel worktree entries. Changed source was reviewed and scanned for sensitive material; no real secret/private key/token or developer signing identity was found. Synthetic credential literals exist only in isolated tests. The closed event model prevents raw service payloads, stack traces, SigningKeyRef, Keychain state, mnemonic or wallet data from entering notification content. Generated bundles, local state, temporary sockets and raw research datasets were not staged or published.

Metadata-only health checks confirmed the existing collector/supervisor alive with an updating partial-file metadata record; no scientific file content was read, pipeline action executed or signal sent. Detailed machine-local health/status inventories are local evidence, not source publication material.

```
REAL USER KEY = NOT AUTHORIZED
REAL TX = DISABLED
BROADCASTS = 0
REAL FUNDS = 0
REAL USER WALLETS = 0
ETHUSDT RESEARCH = UNTOUCHED
```

## Remaining limitations

History/read state is bounded, session-only and local, with no remote synchronization. Events reflect existing best-effort Service observations at its existing polling cadence, not a lossless remote stream. Unsupported categories remain unsupported. SYSTEM theme and persistent language/theme preferences remain unavailable as accepted in C2. Full VoiceOver usability has not been independently certified by automated tests. Local signing/provisioning validity limits the candidate's evaluation period. Human UAT approval is owner-declared; the automated evidence does not independently certify VoiceOver or provide a recording of that human session.

## Human UAT checklist

- Launch the exact isolated C3 application above; retain earlier candidates. Confirm normal sign-in and DEFAULT-disabled transaction/wallet behavior using the already authorized test setup, without real user keys or funds.
- Open the header bell and existing Account notifications route. Check empty versus source unavailable, local-history explanation, unread count, severity, timestamps and scrolling at all three representative sizes.
- In an owned test Service environment, exercise genuine initial absence, confirmed connection loss/recovery and actual permitted security/gate failures. Do not stop or reconfigure the research collector, supervisor or shared pipeline. Production has no artificial event-injection control.
- Mark individual entries read/unread and mark all read; close/reopen and navigate. Check that read state and IDs survive locale/theme changes within the session, with no duplicate notifications or mascot loading animation.
- Switch EN/PT-BR and DARK/LIGHT; inspect descriptions, exact/relative timestamps, focus, severity glyphs, scrollbars and wrapping. Exercise FULL/REDUCED/OFF.
- Use keyboard Tab/Enter/Escape and outside click. Check selected-row activation, allowed destinations and focus restoration; verify VoiceOver unread updates, row severity/timestamp descriptions and traversal.
- As authorized USER/ADMIN test accounts, verify generic ADMIN-only warnings remain hidden from USER. Opening Research must still require existing reauthentication/2FA; notification navigation never grants access.
- Logout, change account and verify clearing; verify no previous private history after restart. Role changes must clear/invalidate until a fresh login. Confirm the documented lack of remote history/read receipts and session-only language/theme preferences.
- Record human approval or findings for this exact version/hash before any C3 Git publication. Stop at C3; C4, custody and live trading are not part of this delivery.

## Final Git publication preparation

The owner explicitly approved C3 human UAT and authorized one commit and an ordinary push to `ChamberUS/DEV`, branch `feature/byx-ui-redesign-v1`, from verified baseline `1a35f0da76032898430c6cd0ea69961665a687c4`. All 637 qualified source hashes still match; no source or existing test was changed for publication, so no fresh regression was required. The selected final evidence includes structured results, source/output hashes, 60 synthetic native snapshots and three native verification logs. Raw Maven/XML/build/smoke logs, intermediate images, helper scripts, machine-local status/health inventories and signed bundles remain local. See [publication audit](publication-audit.json) for the exact reviewed file list and safety checks. Earlier preflight/index/HEAD statements above describe the qualification baseline, not a prediction of the resulting commit SHA.

Windows handoff: clone `https://github.com/ChamberUS/DEV.git`, branch `feature/byx-ui-redesign-v1`, and verify the published SHA supplied in the final publication receipt. Panel path is `mvp-binance-panel/`; use Temurin JDK 21 and Maven 3.9.9, with JavaFX 21.0.5 from the POM. Windows compilation/runtime is unqualified: macOS peer identity, Keychain/SecItem, Unix-domain IPC and signed packaging require separate platform evaluation. No Windows adaptation or C4 work was performed.

Publication recheck found one unrelated Finder metadata change, `byx-packaging/.DS_Store`, since the historical qualification audit. It was excluded and preserved. All 2,955 other protected non-Panel files still match; no Service/signer/packaging source change was found.
