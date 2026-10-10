# C4 — Windows local checkpoint and cross-platform handoff

Data: 2026-10-09, America/Sao_Paulo. **C4_WINDOWS_LOCAL_CHECKPOINT_READY**.

Commit **local**, não publicado: `fddf2a93dd13715fe033ab767f7dc5c391653451`, branch `feature/byx-windows-readiness-v1`, pai `625689b2021c4c6a5f4a409d5d4680bccc865cbc`.

Mensagem: `chore(panel): checkpoint Windows C4 public UI and security research`.

Este recibo foi escrito após o checkpoint e inicialmente ficou local/untracked. Na etapa posterior de publicação autorizada, ele é incluído em commit separado somente de documentação, sem amend do checkpoint. O [escopo/handoff versionado](checkpoint-scope.md), inventário e resultados sanitizados integram o checkpoint. A data e o estado descritos abaixo são históricos; o SHA remoto publicado será confirmado no recibo da publicação. Nenhum merge, release ou instalador é autorizado.

## 1. Estado inicial e classificação

Git root `C:/src/DEV`; Panel `mvp-binance-panel`; origin fetch/push `https://github.com/ChamberUS/DEV.git`. Branch e HEAD iniciais correspondem à base acima. Índice inicialmente vazio; 576 entradas de status: 38 arquivos rastreados modificados e 538 untracked. Snapshot completo privado: checkpoint-local/initial-status.txt. Manifesto anterior C4.2.0-E e relatórios QA/RFC/diagnóstico SAC revisados; não se assumiu que todos os untracked eram C4 source.

Delta source revisado: **49 arquivos** — 12 produção/resources (10 modificados + 2 novos) e 37 testes/helpers (28 modificados + 9 novos). [Inventário por arquivo e SHA-256](checkpoint-source-inventory.csv).

| Classificação do source | Arquivos |
|---|---:|
| Windows secure storage e dispatcher multiplataforma | 4 |
| Startup/JavaFX portability e Service unavailable | 5 |
| Correções visuais públicas | 3 |
| Testes Windows específicos | 3 |
| Testes portáveis e helpers | 34 |

Além desses 49: 12 documentos/CSV/JSON revisados, total **61 arquivos no commit**. Generated screenshots/logs brutos e output target foram separados; pesquisa IPC tem documentação incluída, mas fontes/binários da sonda e sua evidência bruta continuam locais. Nenhum arquivo de outro projeto do monorepo no delta ou stage. identity.env e iaos-web/.env.example já tracked na base, sem alteração/leitura/inclusão no delta. Nenhum dataset/capture/runtime state incluído.

## 2. Mudanças e auditoria de segurança

WindowsStorage.java consulta known folder e SID reais, cria DACL explícita, exige NTFS local e ACL verificável, owner esperado, ACEs reconhecidas e permissões restritas; recusa reparse/hard links, remoto/ADS e owner/principal não aprovado. Handles retidos e verificação de sidecars apoiam proteção durante uso. WindowsStorageTest executou 16 casos, sem falha/erro. É storage do Panel, não cofre Service restrito por identidade de app. Não prometer proteção contra administrador/kernel comprometidos.

RuntimeStorage mantém caminho legado macOS e dispatcher Windows. Database conserva ramo POSIX e protege lease lifecycle; AppContext/PanelApp fecham storage após workers. PrivateFiles.tighten recusa migração ACL implícita Windows. Histórico panel.db permanece rollback-only/read-only. Nenhuma migração Keychain/credential fallback.

LocalServiceClient recusa Windows com native_service_unsupported antes de ler token/abrir IPC; ServiceLauncher não resolve helper macOS; notifications representa indisponibilidade. WindowsServiceUnavailableTest mantém negação de canal/sessão/capacidade e strict identity; nenhum verifier fictício é aceito. Autoridade, sessão de usuário, pairing e transporte são independentes. Nenhuma mudança no verifier macOS, Service, signer, custody, collector ou supervisor ETHUSDT.

AuthLayout reserva topo/rodapé e scroll do formulário; SupportScreen preserva largura do botão FAQ; auth.css usa tokens existentes. Mascot tests mantêm relógio controlado/cleanup e assertions. ProcessTestChild/SecureTempDirFactory/IpcTestFiles substituem dependência de shell/temp path por fixtures portáveis e conservam recusa POSIX real. PublicVisualQa continua apenas launcher opt-in de componentes públicos test-only, fora de DEFAULT.

Todos os candidates foram inspecionados com leitura de diffs/fonte/documentos, buscas por passwords/tokens/credentials/private keys/SigningKeyRef/Keychain, strings de atribuição e metadados pessoais. Senhas e SID órfão detectados em src/test são fixtures sintéticas, não dados de usuário. Não apareceu chave privada real, provisioning real, account DB ou segredo real no delta selecionado. Não se afirma varredura completa de segredos de todo o histórico do monorepo.

Nome de conta/SID e caminho de perfil presentes em windows-baseline.md/windows-storage-implementation.md foram substituídos por marcadores QA; originais privados preservados em checkpoint-local/*.private-original. Documentos candidatos tiveram line endings/trailing whitespace normalizados, sem mudança de fonte de produto. Nenhum screenshot/log bruto foi incluído; não se declarou esses arquivos anonimizados. A fonte permanece idêntica nos **857 hashes** do preflight C4.2.0, antes e depois dos testes. Sem novas dependências ou alteração pom.xml.

## 3. Testes atuais e correspondência

Toolchain real: Temurin Java 21.0.12.1, Maven 3.9.9, Windows 11 x64. JAVA_HOME/PATH definidos somente nos processos de teste. Sem perfis de auth/IPC bypass.

| Execução | Total | PASS | FAIL | ERROR | SKIP | Exit |
|---|---:|---:|---:|---:|---:|---:|
| Focados no source do checkpoint | 250 | 249 | 0 | 0 | 1 | 0 |
| Panel completo, 136 suítes | 1034 | 1008 | 0 | 23 | 3 | 1 |

Focados: 38.462 s / BUILD SUCCESS. Completo: 02:26 min / BUILD FAILURE. [Resultado sanitizado e cases não PASS](checkpoint-test-results.json); logs brutos preservados em checkpoint-local/focused.log e full.log, excluídos do commit.

Comando focado executado:

```powershell
mvn "-Dtest=StringsParityTest,PackageC1CatalogTest,NavigatorTest,WindowsStorageTest,PrivateFilesTest,WindowsServiceUnavailableTest,IpcProtocolContractTest,PublicResponsiveLayoutTest,WindowsPublicLayoutTest,AuthLayoutTest,AuthKeyboardTest,HelpScreensTest,MascotViewTest,MascotPresenceViewTest,MascotPresencePureTest,MascotPolicyTest,MascotAvatarTest,PackageC2ThemeTest,PackageC3NotificationTest,PackageC3NotificationViewTest,LegacyPanelDbTest,RuntimeMigrationTest,LegacyIsolationProductTest" test
mvn test
```

O lifecycle Maven recompilou a composição DEFAULT; não houve patch de source após os testes. Os resultados confirmam os totais históricos C4.1-F. Os 23 métodos de erro atuais foram comparados individualmente com [matriz histórica](c4-1-f-error-matrix.csv): **zero diferenças de identidade dos casos**. Erro exato comum: `native_ipc_fixture_unqualified: POSIX pairing and native peer identity required; no Windows substitute`. Skips: um live observation não solicitado e dois OS-gated. Nenhum erro convertido em skip. Não surgiu regressão funcional/segurança adicional nesses resultados.

Testes não provam Win32 pipe nativo, código BYX vivo, Service autenticação Windows ou compatibilidade macOS. Fonte preservada e passing tests não tornam os 23 ERROR aprovados.

## 4. Limites IPC e UAT pública

SAC enforcement VerifiedAndReputableDesktop bloqueou o executável unsigned (0xc0e90002); CiTool inventário negado. **WINDOWS_IPC_TRANSPORT_PROBE_BLOCKED**, **WINDOWS_APP_IDENTITY_NOT_PROVEN**, **IPC_NATIVE_PROBE_EXECUTION_NOT_AUTHORIZED** permanecem. Não houve reexecução, policy change, VM, wrapper, signing ou fallback.

UI DEFAULT pública tem evidência histórica de Login/Help/FAQ/EN/PT-BR, keyboard/focus, scroll e shutdown; os testes públicos/tema/mascot foram repetidos. Nesta tarefa não houve nova sessão autenticada ou smoke DEFAULT manual. **Aprovação UAT humana ainda não recebida.** 1100x700 possui evidência física Windows histórica; 1440x900 e 1920x1080 possuem snapshots JavaFX automatizados completos, sem qualificação física completa no monitor. Não afirmar Windows C4 totalmente qualificado.

## 5. Commit e exclusões

Commit foi tentado inicialmente e recusado por Author identity unknown; nenhum commit parcial foi criado. O owner informou nome/e-mail e autorizou seu uso somente no comando. Commit final usa author `chambuerUS`, configuração command-scoped, sem alteração global. Nenhuma assinatura/binary publish solicitado. Índice revisado por allowlist exata, checks de privacidade/whitespace e confirmação de branch/pai antes de commit; 61 arquivos, 2999 inserções/68 remoções, nenhum caminho fora Panel, binary/runtime/target.

Lista integral de arquivos efetivamente incluídos segue no apêndice. Intencionalmente excluídos: todos os PNG/screenshots, logs brutos e JSON de ambiente/SDDL/properties privados; target/classes/reports; EXE/class/native harness e suas fontes isoladas; manifestos absolutos e evidência de pesquisa não curada; backups privados; account databases, tokens, chaves/certificados/provisioning, dados ETHUSDT e arquivos de outros projetos. Preservados localmente, não apagados. Alguns links dos relatos históricos referem evidência local excluída e não estarão disponíveis num clone; scope/results/matriz/inventário versionados são o handoff portátil.

Final: branch inalterada, HEAD no checkpoint, índice vazio e **zero alterações rastreadas unstaged**. Árvore permanece com arquivos untracked privados/excluídos, inclusive este recibo. Snapshot privado completo final e lista de exclusões: checkpoint-local/final-status.txt e excluded-files.txt. Não usar reset/clean/stash para removê-los. Não houve push/fetch/merge/rebase/amend/force-push.

## 6. Handoff macOS exato — somente após autorização de publicação

Checkpoint de fonte alvo: **fddf2a93dd13715fe033ab767f7dc5c391653451**. A publicação da branch foi autorizada em etapa posterior; o HEAD final inclui somente documentação adicional. Depois de confirmar o SHA publicado informado no recibo, no clone Mac, criar worktree sibling NOVO e vazio, sem tocar a árvore existente ou o collector ativo. Os comandos abaixo pinam o checkpoint de fonte; para incluir o recibo versionado, usar o HEAD publicado confirmado no lugar desse SHA:

```sh
git fetch origin feature/byx-windows-readiness-v1
git cat-file -e fddf2a93dd13715fe033ab767f7dc5c391653451^{commit}
git worktree add --detach ../DEV-c4-macos-qa fddf2a93dd13715fe033ab767f7dc5c391653451
cd ../DEV-c4-macos-qa/mvp-binance-panel
git rev-parse HEAD
mvn -version
mvn -DskipTests compile
mvn "-Dtest=StringsParityTest,PackageC1CatalogTest,NavigatorTest" test
mvn "-Dtest=PublicResponsiveLayoutTest,AuthLayoutTest,AuthKeyboardTest,HelpScreensTest,AuthenticationEpochTest,SessionReturnTest,PrivateFilesTest,LegacyPanelDbTest,RuntimeMigrationTest,LegacyIsolationProductTest,LocalServiceClientTest,ChainStatusClientTest,MarketFeedClientTest,IpcProtocolContractTest,MascotViewTest,MascotPresenceViewTest,MascotPresencePureTest,MascotPolicyTest,MascotAvatarTest,PackageC2ThemeTest,PackageC3NotificationTest,PackageC3NotificationViewTest" test
mvn test
cd ../byx-local-service
mvn test
```

Confirmar JDK 21/Maven 3.9.9 e registrar exit + Surefire de CADA execução antes da seguinte. Se worktree destino existir, escolher outro diretório aprovado, não limpar/mover. Não executar comandos de reset, stash, clean ou interrupção de processos. Não reutilizar home/state/credenciais de produção para QA.

Java alterado multiplataforma: AppContext, PanelApp, AuthLayout, SupportScreen, LocalServiceClient, ServiceLauncher, ServiceNotificationObserver, Database, PrivateFiles e RuntimeStorage; WindowsStorage é implementação específica com carga lazy Win32. CSS e todos os helpers/37 testes requerem revisão cross-platform; três classes Windows-only têm gates explícitos. Sem nova dependência.

Mac gate: todos os 23 casos POSIX e seus submodos devem executar; manter owner/runtime 0700, token/socket 0600, sem links, leitura bounded, audit token/pidversion, code requirement/seal/launch environment, pairing mútuo fresh/replay/restart, sessão/MFA/role/operação, expiração/revogação e teardown/late events. Service security suite e procedimentos QA packaged de identity/cofre/signer existentes precisam ambiente e materiais QA autorizados; não lançar scripts de packaging cegamente nem usar chaves/ref de produção. Este checkpoint não modifica o contrato macOS.

JavaFX Mac nativo: Login/Help/FAQ, EN/PT-BR × DARK/LIGHT nos três tamanhos, foco/Tab/Space/mouse/trackpad, scroll do texto completo sem header/footer overlap ou botão truncado, resize/shutdown, fontes/rounding, FULL/REDUCED/OFF mascot e cleanup. DEFAULT público sem login inventado enquanto Service não estiver provisionado legitimamente. Guard Windows continua unavailable; não transpor bloqueios para skips.

## 7. Prerequisitos históricos e autorização posterior

Autorização owner para publicar esta branch; revisão privada de materiais sensíveis já presentes na base (incluindo identity.env, não lido aqui) e metadados Git; revisão do delta/limites da QA; macOS worktree/regressão autorizados; UAT humana pública pendente e equipamento adequado para tamanhos maiores. Publicação não prova IPC ou macOS. Nenhum merge para feature/byx-ui-redesign-v1, Service Windows, custody ou instalador recomendado neste checkpoint.

REAL USER KEY = NOT AUTHORIZED; REAL TX = DISABLED; BROADCASTS = 0; REAL FUNDS = 0; REAL USER WALLETS = 0; ETHUSDT RESEARCH = UNTOUCHED.

**C4_WINDOWS_LOCAL_CHECKPOINT_READY** — commit local + handoff; Windows full qualification/macOS regression/publicação não concluídos.

## Arquivos incluídos no commit

- `mvp-binance-panel/docs/package-c4/WINDOWS_SECURE_IPC_RFC.md`
- `mvp-binance-panel/docs/qa/package-c4/c4-1-f-error-matrix.csv`
- `mvp-binance-panel/docs/qa/package-c4/checkpoint-scope.md`
- `mvp-binance-panel/docs/qa/package-c4/checkpoint-source-inventory.csv`
- `mvp-binance-panel/docs/qa/package-c4/checkpoint-test-results.json`
- `mvp-binance-panel/docs/qa/package-c4/windows-app-control-diagnostic.md`
- `mvp-binance-panel/docs/qa/package-c4/windows-baseline.md`
- `mvp-binance-panel/docs/qa/package-c4/windows-c4-1-final-visual-qa.md`
- `mvp-binance-panel/docs/qa/package-c4/windows-c4-1-regression-hardening.md`
- `mvp-binance-panel/docs/qa/package-c4/windows-ipc-identity-probe.md`
- `mvp-binance-panel/docs/qa/package-c4/windows-ipc-test-environment-plan.md`
- `mvp-binance-panel/docs/qa/package-c4/windows-storage-implementation.md`
- `mvp-binance-panel/src/main/java/panel/app/AppContext.java`
- `mvp-binance-panel/src/main/java/panel/app/PanelApp.java`
- `mvp-binance-panel/src/main/java/panel/authview/AuthLayout.java`
- `mvp-binance-panel/src/main/java/panel/helpview/SupportScreen.java`
- `mvp-binance-panel/src/main/java/panel/localservice/LocalServiceClient.java`
- `mvp-binance-panel/src/main/java/panel/localservice/ServiceLauncher.java`
- `mvp-binance-panel/src/main/java/panel/notifications/ServiceNotificationObserver.java`
- `mvp-binance-panel/src/main/java/panel/security/Database.java`
- `mvp-binance-panel/src/main/java/panel/security/PrivateFiles.java`
- `mvp-binance-panel/src/main/java/panel/security/RuntimeStorage.java`
- `mvp-binance-panel/src/main/java/panel/security/WindowsStorage.java`
- `mvp-binance-panel/src/main/resources/panel/v2/auth.css`
- `mvp-binance-panel/src/test/java/panel/BackendGatewayTest.java`
- `mvp-binance-panel/src/test/java/panel/ByxWalletOwnershipTest.java`
- `mvp-binance-panel/src/test/java/panel/CaptureMonitorTest.java`
- `mvp-binance-panel/src/test/java/panel/CaptureRuntimeResolverTest.java`
- `mvp-binance-panel/src/test/java/panel/ChainOwnershipGuardTest.java`
- `mvp-binance-panel/src/test/java/panel/FinalSafetyTest.java`
- `mvp-binance-panel/src/test/java/panel/GasSignerIsolationTest.java`
- `mvp-binance-panel/src/test/java/panel/LegacyAuthDisabledTest.java`
- `mvp-binance-panel/src/test/java/panel/PackageANavigationBoundaryTest.java`
- `mvp-binance-panel/src/test/java/panel/PackageC1AuthorityTest.java`
- `mvp-binance-panel/src/test/java/panel/PackageC3AppIsolationTest.java`
- `mvp-binance-panel/src/test/java/panel/ProcessTestChild.java`
- `mvp-binance-panel/src/test/java/panel/PublicResponsiveLayoutTest.java`
- `mvp-binance-panel/src/test/java/panel/PublicVisualQa.java`
- `mvp-binance-panel/src/test/java/panel/ResearchGateReproductionTest.java`
- `mvp-binance-panel/src/test/java/panel/ScientificCaptureResolverTest.java`
- `mvp-binance-panel/src/test/java/panel/SecureTempDirFactory.java`
- `mvp-binance-panel/src/test/java/panel/ServerAuthorizationBoundaryTest.java`
- `mvp-binance-panel/src/test/java/panel/ShellComponentsTest.java`
- `mvp-binance-panel/src/test/java/panel/WindowsPublicLayoutTest.java`
- `mvp-binance-panel/src/test/java/panel/adapter/ServiceChainGatewayTest.java`
- `mvp-binance-panel/src/test/java/panel/localservice/AuthorityClientTest.java`
- `mvp-binance-panel/src/test/java/panel/localservice/ChainStatusClientTest.java`
- `mvp-binance-panel/src/test/java/panel/localservice/FakeMarketService.java`
- `mvp-binance-panel/src/test/java/panel/localservice/FakeService.java`
- `mvp-binance-panel/src/test/java/panel/localservice/IpcProtocolContractTest.java`
- `mvp-binance-panel/src/test/java/panel/localservice/IpcTestFiles.java`
- `mvp-binance-panel/src/test/java/panel/localservice/LocalServiceClientTest.java`
- `mvp-binance-panel/src/test/java/panel/localservice/MarketFeedClientTest.java`
- `mvp-binance-panel/src/test/java/panel/localservice/WindowsServiceUnavailableTest.java`
- `mvp-binance-panel/src/test/java/panel/mascot/MascotPresenceViewTest.java`
- `mvp-binance-panel/src/test/java/panel/mascot/MascotViewTest.java`
- `mvp-binance-panel/src/test/java/panel/runtime/LegacyIsolationProductTest.java`
- `mvp-binance-panel/src/test/java/panel/runtime/RuntimeMigrationTest.java`
- `mvp-binance-panel/src/test/java/panel/security/LegacyPanelDbTest.java`
- `mvp-binance-panel/src/test/java/panel/security/PrivateFilesTest.java`
- `mvp-binance-panel/src/test/java/panel/security/WindowsStorageTest.java`

## Auditoria da etapa de publicação autorizada

O owner autorizou push somente da branch feature/byx-windows-readiness-v1, sem merge, force-push, release ou alteração de produção. Root/branch/HEAD/origin correspondem à baseline. Os 61 paths do checkpoint coincidem com approved-files.txt; os 49 hashes SHA-256 de source/resources/test coincidem com checkpoint-source-inventory.csv e seus blobs Git correspondem ao checkpoint. Pai direto é C3 625689b2021c4c6a5f4a409d5d4680bccc865cbc, confirmado também como HEAD remoto da branch macOS no preflight.

Auditoria do delta publicado não encontrou credenciais reais, private keys, Keychain/provisioning/signing identity real, account DB/runtime state, raw research captures, screenshots ou executáveis. Literais de credenciais em fixtures test-only e referências textuais a SigningKeyRef/Keychain não são material real de custódia. Arquivos sensíveis já existentes na base remota não foram abertos, modificados ou adicionados no delta. Nenhuma afirmação de ausência de segredos em todo o histórico do monorepo é feita.

Este é o único relatório pós-checkpoint necessário ao handoff. c4-1-r-layout/README.md é índice de snapshots gerados, não relatório posterior de qualificação; continua local junto aos artefatos excluídos. A branch Windows remota não existia no preflight (ls-remote concluído com sucesso, sem ref correspondente); qualquer criação concorrente será protegida por push ordinário sem força. O SHA remoto precisa ser lido novamente após push; autorização sozinha não é prova de publicação.

Nenhum teste foi reexecutado na etapa de publicação: source permanece igual ao checkpoint testado. Focados 249 PASS/0 FAIL/0 ERROR/1 SKIP; completo 1008 PASS/0 FAIL/23 ERROR/3 SKIP. Publicação não resolve os 23 ERROR, não autoriza harness bloqueado e não qualifica macOS.
