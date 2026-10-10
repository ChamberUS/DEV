# Package C4.1-R — Windows regression hardening

Date: 2026-10-09, America/Sao_Paulo. Final status: **BLOCKED_C4_1_REGRESSION**.

Compilation and the required 18 focused tests pass. The final complete native Windows suite has **992 PASS / 0 FAIL / 23 ERROR / 3 SKIP**, across 1,018 tests and 135 suites. Mascot timing/cleanup corrections passed ten independent Maven repetitions (190 executions) and the complete suite. The real DEFAULT application opened and closed safely. Mandatory authenticated IPC qualification, two larger physical window-size checks, authenticated-screen UAT and two observed public-layout issues remain unresolved. This is not Windows compatibility or cross-platform release qualification.

## Preflight and preserved baseline

Workspace `C:\src\DEV`; panel `mvp-binance-panel`; repository `ChamberUS/DEV`. Branch remains `feature/byx-windows-readiness-v1`, HEAD/C3 remains `625689b2021c4c6a5f4a409d5d4680bccc865cbc`. Existing C4.1 changes were already unstaged/untracked; the index remains empty. Both previous reports were read completely, the existing changes and screenshot harnesses were inspected, and no `AGENTS.md` was found. No reset, clean, stash, rebase, commit or push occurred.

Windows 11 Pro build 26200 (`10.0.26200`), x64, local NTFS. `mvn -version` uses Eclipse Adoptium Temurin **21.0.12.1**, Maven **3.9.9**; JavaFX remains **21.0.5**. JAVA_HOME is `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`; Maven home is `C:\src\tools\apache-maven-3.9.9`. Each Maven process refreshed its stale PATH from persisted user/machine values; no persistent environment change was made. The ordinary Windows token/account is the one documented in [C4.1 environment evidence](c4-1-environment.json); runner sandbox escalation did not grant Windows administrator privileges.

The authoritative preceding complete run is [C4.1 suite summary](c4-1-suite-summary.json) and [case-level failures](c4-1-test-failures.json): **997 total / 960 PASS / 3 FAIL / 31 ERROR / 3 SKIP**. Later isolated diagnostics had partially overwritten Surefire reports, so they were not mistaken for a newer complete baseline. Historical reports, screenshots, logs and JSON were retained. [Preflight hashes](c4-1-r-preflight-hashes.json) and [final preservation check](c4-1-r-preservation-check.json) confirm that both historical JSON files and WindowsStorage, RuntimeStorage, PrivateFiles, Database, LocalServiceClient and ServiceLauncher are unchanged during C4.1-R.

## Initial failure inventory and classification

| Group | Original result | Actual failure path | Classification |
|---|---:|---|---|
| ChainStatusClientTest | 7 ERROR | `Files.createTempDirectory(Path.of("/tmp"), "cs")` in BeforeEach, before the case body | Cross-platform test-fixture incompatibility; it masked deeper IPC limitations |
| LocalServiceClientTest | 12 ERROR | Same nonexistent Windows `\\tmp` setup with prefix `pc` | Cross-platform test-fixture incompatibility; security cases had not executed |
| MarketFeedClientTest | 12 ERROR | Same setup with prefix `mc` | Cross-platform test-fixture incompatibility; wire/lifecycle cases had not executed |
| MascotPresenceViewTest idle/pointer case | 1 FAIL | Gaze expected >0.8 and <=1, observed 0.0 | Timing-sensitive fixture mixing wall-clock and controlled time, with a native event-interleaving opportunity |
| MascotViewTest disposal and FULL-loop cases | 2 FAIL | Shared cached-image count expected 0, observed 5 | Shared fixture resource leak following an earlier assertion failure |

The failing BeforeEach also caused a suppressed cleanup NullPointerException because `home` was unset. The complete 34-case before/after inventory appears below and in [case comparison](c4-1-r-case-comparison.json). No IPC error was presumed harmless. No new failing production-source compatibility issue or missing JavaFX native library was observed. Missing secure native IPC remains a **security-related limitation**, with **macOS-only identity dependencies**; transport/pairing configuration cannot be repaired by changing a temp pathname alone.

## IPC correction and remaining guarantees

`IpcTestFiles.home` uses the actual Windows temporary directory, retaining short `/tmp` paths on macOS for AF_UNIX path limits. Null-safe cleanup no longer masks setup failures. FakeService and FakeMarketService now inspect POSIX filesystem capability before creating run directories, secrets or sockets. On NTFS they explicitly throw `native_ipc_fixture_unqualified: POSIX pairing and native peer identity required; no Windows substitute`. These are visible test **errors**, not assumptions, disabled cases or synthetic successful authentication. All original cases remain collected.

Eight original cases now execute successfully: four transport-independent chain contract/parsing cases; local monitor stop/stale-result handling and bounded absent-Service denial; market source isolation and absent-Service LOST state. The absent-Service assertion expects the real Windows `native_service_unsupported` code and verifies that historical connectivity never becomes current connectivity; macOS retains its existing absent/stale Unix socket branch.

| Original suite | Current total | PASS | ERROR | What still requires the native fixture |
|---|---:|---:|---:|---|
| ChainStatusClientTest | 7 | 4 | 3 | Configured/valid typed wire status and hostile/broken Service wire behavior |
| LocalServiceClientTest | 12 | 2 | 10 | Pairing permissions/secret, mutual proof, replay, strict code identity, hostile framing, fresh restart and timeouts |
| MarketFeedClientTest | 12 | 2 | 10 | Authenticated subscription, contract drops, capability denial, reconnect/backoff, stop/late-event ordering and live/lost wire lifecycle |

New `IpcProtocolContractTest` adds **5 portable tests** against production parsers, without connecting or authenticating: hostile declared sizes rejected before body reads, malformed/non-object/truncated frames refused, distinct typed prices/timestamps, hostile book/value/topic shapes rejected without prior-state mutation, and immutable ordered book lists. Static test data is not published to DEFAULT or a running client and does not fabricate a backend response.

`WindowsServiceUnavailableTest` grows from 3 to **7 native Windows denial tests**. Four additions prove refusal before fixture token/socket creation; strict identity never reaches an invented verifier; every permitted typed chain operation refuses without fabricated chain facts; and the actual market worker stays LOST/empty, then stops idempotently to IDLE. Existing tests still deny sessions/private operations and refuse macOS helper resolution. Windows ACL/ownership/reparse tests remain active and pass.

The production Windows early guard remains unchanged, before any pairing-token read or IPC connection. This qualification gap cannot be solved with a Java POSIX substitute, a PID, username, loopback socket or a claimed identity. Existing macOS PeerIdentity uses kernel LOCAL_PEERTOKEN audit data, Security.framework code requirements and environment checks; Windows equivalents and a protected pairing-secret lifecycle have not been implemented or approved. AF_UNIX connectivity alone would not establish those guarantees. **This implementation area stopped here**, as requested; all 23 errors remain in the full result. No production Service transport, signer, credential store or native identity policy changed.

## Mascot root cause and stability

The affected IDLE test advanced `tickAt(100000...)` after a shown scene could already seed IdleLife with real current milliseconds. IdleLife intentionally ignores backwards time: `dt=max(0,min(200,now-last))`, `last=max(last,now)`. That fixture ordering can freeze gaze smoothing at zero. Controlled time is now established using the existing test hook **before scene/window attachment**, and injected pointer input plus controlled ticks execute in one JavaFX Application Thread transaction, preventing a native exit event/pulse between them. Existing explicit focus override remains confined to tests; focus-loss/restoration assertions remain active.

A failed assertion formerly bypassed the trailing close, leaving the shared rig's five image leases alive. Both test classes now guarantee view disposal (and stage close where mounted) in AfterEach, including assertion-failure paths. The two strict zero-cache assertions were retained. No production mascot assets, animation engine, click reaction, menu, loading behavior or FULL/REDUCED/OFF semantics changed. No sleeps/timeouts were added or increased; animations were not disabled to obtain a pass.

`mvn "-Dtest=MascotPresenceViewTest,MascotViewTest" test` ran **10 times in fresh Maven/JVM executions**, each 19 PASS / 0 FAIL / 0 ERROR / 0 SKIP, each exit 0. [Repetition records](c4-1-r-mascot-repeats.json) name all ten locally retained logs. Total: **190 executions, no intermittent failure observed**. Separate test JVMs briefly shared the desktop during some runs; no forced focus change was applied to their windows. The initial 82-test regression and final complete suite also pass all 19 affected tests. This evidence supports the fixture correction; it does not qualify account/mascot interactions in authenticated DEFAULT or execution on macOS.

## Native Windows rendering and physical smoke

### Automated public layout coverage

New `WindowsPublicLayoutTest`: **12 PASS**, native Windows JavaFX toolkit, exact offscreen Scene/snapshot dimensions `1100x700`, `1440x900`, `1920x1080`, EN/PT-BR and DARK/LIGHT. Real public login/FAQ/Help components produce **36 PNGs** plus 12 layout measurements; [screenshot matrix](c4-1-r-layout/README.md) links all images. Automated checks cover requested dimensions, horizontal login form containment/usable width, unavailable route/readiness and absent privileged controls. Auth/persistence callbacks throw if invoked, successful-login callback throws, and ServerAuthorization still rejects persisted settings in both themes. No AppContext session, fake successful authority or private-screen startup is injected. Global locale/theme and views are restored/disposed.

These are **AUTOMATED_OFFSCREEN_LAYOUT**, not physical UAT and not a proof that every text element is unclipped. Representative rendered images were inspected at all three sizes, including both languages/themes. The public harness does not contain an authenticated mascot; its public motion preference does not change mascot tests or DEFAULT. It never saves a theme preference. Larger layout screenshots demonstrate native rendering at requested Scene sizes only.

### Actual DEFAULT on the connected display

`mvn javafx:run`, existing `panel.app.Main`, DEFAULT, wallet capability DISABLED; no QA profile or injected authority. Own application Java PID **9856** displayed login, then real public FAQ/Help. Languages EN -> PT-BR -> EN changed labels/title. Empty-credential Retry stayed unavailable. Tab produced visible focus outlines; Control+K did not expose authenticated navigation. Public login -> FAQ -> Help -> Sign in worked in both languages. The native language popup remained inside the visible window. Guidance expansion supplied only the existing administrator-contact text. No credentials were entered.

Normal WM_CLOSE of that exact window stopped its process; Maven **BUILD SUCCESS / exit 0**, total **1m46s**, finished **2026-10-09 19:16:45 -03:00**. [Startup log](c4-1-r-default-startup.log) retains JavaFX unnamed-module and SLF4J warnings; neither was suppressed. No macOS Service binary was started and no global Java-process termination was used.

Measurements use GetDpiForWindow/GetWindowRect and DPI-aware, unscaled physical screen capture. Display: **1920x1080 physical**, **144 DPI / 150%**, approximately 1280x720 logical. Actual outer-window dimensions include Windows decorations; offscreen Scene dimensions above do not, so they are not interchangeable.

| Requested outer logical window | Required physical pixels at 150% | Actual outer physical window | Captured physical pixels | Physical qualification |
|---|---:|---:|---:|---|
| 1100x700 | 1650x1050 | 1650x1050 | 1650x1050 | Full outer window visible; public smoke performed, with findings below |
| 1440x900 | 2160x1350 | 1946x1106 | 1920x1080 | Windows constrained it; clipped; requested target NOT achieved |
| 1920x1080 | 2880x1620 | 1946x1106 | 1920x1080 | Windows constrained it; clipped; requested target NOT achieved |

Physical screenshot evidence, each with a matching JSON measurement:

- [Login EN](c4-1-r-default-1100x700-en.png), [PT-BR Retry/focus](c4-1-r-default-1100x700-pt-br-retry-focus.png).
- [FAQ EN](c4-1-r-default-faq-1100x700-en.png), [FAQ PT-BR](c4-1-r-default-faq-1100x700-pt-br.png).
- [Help EN](c4-1-r-default-help-1100x700-en.png), [Help PT-BR](c4-1-r-default-help-1100x700-pt-br.png).
- [Language popup](c4-1-r-default-language-popup-1100x700.png), [expanded access guidance](c4-1-r-default-access-guidance-1100x700-en.png).
- [1440x900 request, clipped visible portion](c4-1-r-default-1440x900-visible.png), [1920x1080 request, clipped visible portion](c4-1-r-default-1920x1080-visible.png).

Public visual findings remain open: at the minimum decorated window the Help action text is ellipsized (`Open F...` / `Abrir ...`); expanding access guidance moves part of the Sign in heading underneath the language selector. The latter is consistent with AuthLayout stacking a vertically centered form under independently overlaid top controls/footer; SupportScreen's HBox can shrink its action button. These are source-inspection leads, not an executed fix or a proven Windows-only regression. Neither is detected by the narrower offscreen horizontal-containment assertions. No claim of complete clipping-free visual QA is made.

Actual DARK appearance was observed. Actual DEFAULT LIGHT switching, Home, Trading Desk, account/menu, mascot, authenticated dialogs/popovers, notifications and command palette remain inaccessible because Service-backed authentication is unavailable. Public UNAVAILABLE presentation was checked; live authentication/server-error responses were not fabricated. Isolated component tests cover navigation/theme/notifications/session behavior but do not confer authenticated DEFAULT UAT. No authentication, ADMIN or Research gate was bypassed.

## Regression results

Commands ran from the panel directory, with unchanged POM/profiles and no exclusions:

| Run | Total | PASS | FAIL | ERROR | SKIP | Maven exit |
|---|---:|---:|---:|---:|---:|---:|
| Historical C4.1 complete baseline | 997 | 960 | 3 | 31 | 3 | 1 |
| C4.1-R targeted ACL/IPC/mascot | 82 | 59 | 0 | 23 | 0 | 1 |
| Supported targeted layout/security/navigation/locale/theme/notification/session | 178 | 178 | 0 | 0 | 0 | 0 |
| Mascot repetitions, 10 x 19 | 190 | 190 | 0 | 0 | 0 | 0 each |
| Required StringsParity/C1/Navigator | 18 | 18 | 0 | 0 | 0 | 0 |
| **Required complete `mvn test`** | **1018** | **992** | **0** | **23** | **3** | **1** |

Required `mvn -DskipTests compile`: SUCCESS / exit 0, 0.787s, completed 19:12:13 -03:00. Required `mvn "-Dtest=StringsParityTest,PackageC1CatalogTest,NavigatorTest" test`: 3+11+4 PASS, 2.014s, completed 19:12:17. Complete suite: **135 suites**, 2m26s, completed **19:14:45 -03:00**. Incremental compilation used the actual native Windows build output; no macOS binary was substituted. [Suite summary](c4-1-r-suite-summary.json), [all case outcomes](c4-1-r-test-results.json), [errors/skips with traces](c4-1-r-test-failures.json) preserve results independently of later Surefire overwrites.

Local logs: `c4-1-r-compile.log`, `c4-1-r-focused.log`, `c4-1-r-full-suite.log`, `c4-1-r-targeted-first.log`, `c4-1-r-supported-targeted.log`, `c4-1-r-mascot-repeat-01.log` through `10.log`. Existing Git log-ignore rules apply; logs are retained locally. Exact targeted commands/results are also captured in [run records](c4-1-r-run-results.json).

Compared with C4.1: **+32 PASS, -3 FAIL, -8 ERROR, unchanged 3 SKIP, +21 total**. The additional 21 cases are four Windows refusal tests, five portable protocol tests and twelve layout parameter cases; two new suite classes explain 133 -> 135. No original case was removed. No new Windows skip was added in this milestone. The original three skips remain: capture.live absent, Unix capture file-symlink case, Unix legacy-file symlink case. They do not replace Windows junction/hardlink security coverage. New Windows-only layout/refusal cases are not macOS tests and will be platform-skipped there; original macOS IPC cases remain active.

Final full-suite security/behavior examples: WindowsStorage 16 PASS, PrivateFiles 9, Windows Service refusal 7, protocol 5, mascot 11+8, AuthenticationEpoch 17, Package A navigation boundary 3, C1 catalog 11, locale parity 3, theme 25, C3 notifications 31+17, Navigator 4 and public layouts 12. The previously approved 66-test C4.1 security result was preserved; current complete results, rather than selective successes, determine final status.

## Changed files and macOS handoff

**No production source changed during C4.1-R.** All preceding C4.1 production/storage changes remain local for owner review; their complete inventory is in [windows-storage-implementation.md](windows-storage-implementation.md). This correction touches only:

| Test source under src/test/java/panel | Change / portability |
|---|---|
| localservice/IpcTestFiles.java (new) | Shared platform-specific temp selection, explicit unsupported POSIX fixture refusal |
| localservice/FakeService.java; localservice/FakeMarketService.java | Preflight capability check; existing macOS POSIX secret/socket body retained |
| localservice/ChainStatusClientTest.java; LocalServiceClientTest.java; MarketFeedClientTest.java | Portable setup/cleanup; Windows bounded-denial branch, original macOS socket branch retained |
| localservice/IpcProtocolContractTest.java (new) | Five portable production-parser invariants |
| localservice/WindowsServiceUnavailableTest.java | Four new Windows-specific denial tests; seven total |
| mascot/MascotPresenceViewTest.java; MascotViewTest.java | Portable FX clock/event synchronization and guaranteed resource cleanup |
| WindowsPublicLayoutTest.java (new) | Windows-only public native snapshot/layout assertions, never authentication success |

POSIX storage checks, original macOS IPC peer/code identity, Keychain isolation, Service authorization and signer restrictions were not relaxed. No Service, packaging, signer or ETHUSDT source/process was changed. Package A/B/C1/C2/C3 production behavior was not changed by these test fixes. Existing C4.1 platform dispatch still requires separate owner review on macOS.

**macOS execution was not available and has not been verified.** Required handoff on a native approved macOS checkout with the complete unstaged C4.1/C4.1-R diff:

```sh
mvn -DskipTests compile
mvn "-Dtest=StringsParityTest,PackageC1CatalogTest,NavigatorTest" test
mvn "-Dtest=PrivateFilesTest,ChainStatusClientTest,LocalServiceClientTest,MarketFeedClientTest,IpcProtocolContractTest,AuthorityClientTest,PackageANavigationBoundaryTest,PackageC3NotificationTest,AuthenticationEpochTest" test
mvn "-Dtest=MascotPresenceViewTest,MascotViewTest" test
mvn test
```

Repeat the mascot command ten independent times; preserve every result and investigate any native focus/pulse difference. Confirm POSIX short-path fixtures and all 31 original IPC cases execute; check packaged native peer/code identity and Keychain/isolation with the existing approved macOS QA procedure. Recheck approved mascot interactions and A/B/C1/C2/C3 UI. WindowsStorage/refusal/layout cases platform-skip on macOS by their explicit Windows contract. These skips cannot qualify Windows IPC. Do not push before cross-platform review and owner approval.

## Git status, unresolved blockers and next milestone

[Final Git status](c4-1-r-git-status.txt): same branch/HEAD, empty index, 35 tracked modified files (28 preceding C4.1 plus seven newly modified tracked test files), existing/new untracked sources and QA evidence; nothing committed or pushed. The additional WindowsServiceUnavailable edits are inside an already untracked C4.1 test. No unrelated monorepository project changed. The baseline report and original evidence were preserved; the storage report has an explicitly dated C4.1-R addendum.

Next separately scoped work:

1. Security design/review for native Windows Service identity and authenticated transport, kernel-bound peer/code verification, protected pairing-secret ownership/lifecycle, replay/timeouts/restart contracts and hostile-actor tests. A new threat model and approved native implementation must precede any supported-IPC claim; keep current fail-closed guard until qualified. Do not implement a signer or Keychain substitute in this diagnostic/corrective task.
2. Public layout correction in AuthLayout/SupportScreen, with vertical non-overlap/expanded-guidance and full localized action-label regression cases at the decorated minimum size. Preserve public-only authorization and the approved visual design; inspect macOS impact before acceptance.
3. Repeat full physical UAT on a display capable of the requested logical dimensions at its actual DPI; qualify actual LIGHT switching and authenticated interactions only when legitimate Service authorization is available. Complete macOS regression/review before any push or packaging.

REAL USER KEY = NOT AUTHORIZED. REAL TX = DISABLED. BROADCASTS = 0. REAL FUNDS = 0. REAL USER WALLETS = 0. ETHUSDT RESEARCH = UNTOUCHED. No real credentials, wallets, funds, transactions, privileged research operation or broadcasting were used. No storage fallback, identity substitution or security bypass was introduced.

**BLOCKED_C4_1_REGRESSION**. Corrective tests and public startup were exercised; mandatory native IPC cases still error and visual/cross-platform qualification remains incomplete. Stop here: no installer, Service port, custody, live trading or Linux work.

## Complete original failing/erroring case comparison

The following table is generated from preserved historical case evidence and the final complete C4.1-R Surefire results. ERROR rows remain unqualified at explicit fixture refusal; PASS rows executed their assertions.

| Suite | Original case | Before | C4.1-R |
|---|---|---|---|
| localservice.ChainStatusClientTest | `denomAndSupplyFactsAreParsedStrictlyAndFormattedWithIntegersOnly` | ERROR | PASS |
| localservice.ChainStatusClientTest | `notConfiguredIsTheProductionAnswerAndCarriesNothingAboutAChain` | ERROR | ERROR |
| localservice.ChainStatusClientTest | `anyDeviationFromTheContractBecomesErrorWithAFixedReason` | ERROR | PASS |
| localservice.ChainStatusClientTest | `theGenerationIsReadAndBounded` | ERROR | PASS |
| localservice.ChainStatusClientTest | `aValidStatusIsReadOverTheTypedChannelWithNoArguments` | ERROR | ERROR |
| localservice.ChainStatusClientTest | `onlyTheThreePublicChainOperationsExistAndNothingGenericCanBeSent` | ERROR | PASS |
| localservice.ChainStatusClientTest | `anAbsentHostileOrBrokenServiceIsAnErrorNeverHealthy` | ERROR | ERROR |
| localservice.LocalServiceClientTest | `packagedClientRefusesAServiceWhoseCodeIdentityIsNotVerifiedEvenWithTheRightSecret` | ERROR | ERROR |
| localservice.LocalServiceClientTest | `restartIsReadFreshAndNeverReusesTheOldPairing` | ERROR | ERROR |
| localservice.LocalServiceClientTest | `oversizedGarbageAndOutOfContractResponsesAreRejected` | ERROR | ERROR |
| localservice.LocalServiceClientTest | `unsafeOrInvalidPairingFilesAreRefusedWithoutConnecting` | ERROR | ERROR |
| localservice.LocalServiceClientTest | `pairedServiceIsReadAndPrivateCapabilitiesAreNeverTrusted` | ERROR | ERROR |
| localservice.LocalServiceClientTest | `aServiceThatRejectsOurProofIsAuthFailedNotConnected` | ERROR | ERROR |
| localservice.LocalServiceClientTest | `anImpostorThatDoesNotKnowTheSecretIsRejectedBeforeAnyProofIsSent` | ERROR | ERROR |
| localservice.LocalServiceClientTest | `statusTextNeverCarriesPathsOrSecrets` | ERROR | ERROR |
| localservice.LocalServiceClientTest | `monitorDiscardsAResultThatArrivesAfterStopAndStopsEverything` | ERROR | PASS |
| localservice.LocalServiceClientTest | `aServerProofReplayedFromAnEarlierConversationIsRejected` | ERROR | ERROR |
| localservice.LocalServiceClientTest | `absentServiceIsUnavailableAndBounded` | ERROR | PASS |
| localservice.LocalServiceClientTest | `silentServiceTimesOutAndIsNotRetriedForever` | ERROR | ERROR |
| localservice.MarketFeedClientTest | `aServiceWithoutTheMarketCapabilityIsUnsupportedNotLive` | ERROR | ERROR |
| localservice.MarketFeedClientTest | `anEventAfterStopNeverChangesTheState` | ERROR | ERROR |
| localservice.MarketFeedClientTest | `panelSourceHasNoDirectBinanceAccess` | ERROR | PASS |
| localservice.MarketFeedClientTest | `reconnectsWithBackoffAndNeverHoldsTwoSessions` | ERROR | ERROR |
| localservice.MarketFeedClientTest | `stopCancelsTheSubscriptionClearsDataAndIsIdempotent` | ERROR | ERROR |
| localservice.MarketFeedClientTest | `absentServiceIsLostNotLive` | ERROR | PASS |
| localservice.MarketFeedClientTest | `everyContractViolationDropsTheSessionAndNeverBecomesData` | ERROR | ERROR |
| localservice.MarketFeedClientTest | `subscribeCarriesNoArgumentsAndNothingElseIsEverRequested` | ERROR | ERROR |
| localservice.MarketFeedClientTest | `anOversizedDeclaredEventIsRefusedBeforeAllocating` | ERROR | ERROR |
| localservice.MarketFeedClientTest | `streamsRealShapedEventsIntoTypedData` | ERROR | ERROR |
| localservice.MarketFeedClientTest | `silentServiceIsDetectedAndTheLinkIsDropped` | ERROR | ERROR |
| localservice.MarketFeedClientTest | `lostLinkKeepsLastDataAndNeverClaimsLive` | ERROR | ERROR |
| mascot.MascotPresenceViewTest | `idleFullRunsTheRigWithAliveBlinkingBreathingAndAnEyeThatFollowsTheCursorWithinLimits` | FAILURE | PASS |
| mascot.MascotViewTest | `closingTheScreenMidAnimationReleasesEverythingAndLaterCallbacksDoNothing` | FAILURE | PASS |
| mascot.MascotViewTest | `fullMotionLoopsAndAdvancesFramesThenStopsToThePoster` | FAILURE | PASS |

---

## Adendo C4.1-F — 2026-10-09

Este adendo preserva integralmente o resultado C4.1-R acima. O marco F corrige os dois defeitos públicos observados no mínimo 1100×700, acrescenta regressão de layout portável e entrega proposta IPC sem implementação.

Resultado real: compile exit 0; C1/Navigator 18 PASS; focused security/UI 251 PASS; full Maven **1034 total / 1008 PASS / 0 FAIL / 23 ERROR / 3 SKIP, 136 suites, exit 1**. Frente a 992 PASS, houve +16 casos do novo PublicResponsiveLayoutTest. Os mesmos 23 errors e três skips permanecem com identidade/tipo/mensagem iguais; não houve exclusão de suites ou skip novo. Mascot recebeu mais 3 JVM/Maven × 19 = **57 PASS**, além dos resultados focused/full e das 190 execuções históricas.

Causas visuais medidas: ação FAQ encolhia abaixo de prefWidth (inclusive EN 100 vs 100.140625 px); formulário expandido centralizado atravessava a região do seletor. Correções limitadas a AuthLayout (corpo rolável e reserva medida de topo/rodapé), SupportScreen (minWidth da ação e descrição flexível) e auth.css (tokens de fundo). Mínimo 1100×700, paleta/fontes e geometria do mascot preservados.

Execução DEFAULT pública em Windows antes/depois com shutdown normal, sem Service macOS/credenciais. LIGHT foi verificado na composição pública nativa isolada, que recusa autoridade e persistência, não em sessão DEFAULT autenticada. Capturas e limites estão no [relatório visual final](windows-c4-1-final-visual-qa.md). 1440×900 e 1920×1080 foram renderizados em Scenes exatas com EN/PT-BR e DARK/LIGHT; o monitor 1920×1080 a 150% não suporta essas janelas físicas completas. Solicitações maiores ficaram limitadas/clipped e não constituem qualificação física.

Todos os 23 erros foram revisados por método, incluindo submodos e assertions originais: [CSV](c4-1-f-error-matrix.csv), [JSON](c4-1-f-error-matrix.json). O primeiro frame comum é IpcTestFiles.requirePosixPairing:18, IOException native_ipc_fixture_unqualified, antes de endpoint/token. Classificação B observada, C de produto e D direto/transitivo conforme cenário; isso não valida as assertions posteriores. A sonda Java 21 abre somente canal UNIX não conectado e confirma SO_PEERCRED indisponível neste host. Sem identidade equivalente não se habilita IPC.

[RFC Windows IPC](../../package-c4/WINDOWS_SECURE_IPC_RFC.md): contrato macOS atual, named pipes/AF_UNIX/loopback, DACL/SID, identidade viva, mesmo usuário hostil, replay/restart/reparse, ownership, limites e fases. Named pipes é candidato de investigação, não transporte de produção escolhido/qualificado. Nenhum adapter de Service, signer/custody ou cofre foi implementado.

Estado final: branch feature/byx-windows-readiness-v1, HEAD 625689b2021c4c6a5f4a409d5d4680bccc865cbc, árvore local preservada, sem commit/push. Comparação dos 581 hashes de preflight encontrou apenas AuthLayout/SupportScreen modificados entre os Java/JSON auditados; CSS adicional é intencional. Handoff macOS e comandos exatos estão no relatório final; macOS não foi executado.

- **C4_1_WINDOWS_VISUAL_READY_FOR_UAT** — interface pública; tamanhos físicos maiores e telas privadas permanecem limitados conforme relatório.
- **C4_WINDOWS_SECURE_IPC_DESIGN_READY_FOR_REVIEW** — proposta/inventário; identidade ainda depende de revisão e evidência.
- **C4_WINDOWS_FULL_COMPATIBILITY_NOT_YET_QUALIFIED**.

REAL USER KEY = NOT AUTHORIZED; REAL TX = DISABLED; BROADCASTS = 0; REAL FUNDS = 0; REAL USER WALLETS = 0; ETHUSDT RESEARCH = UNTOUCHED. STOP: não implementar RFC, publicar branch ou iniciar packaging automaticamente.
