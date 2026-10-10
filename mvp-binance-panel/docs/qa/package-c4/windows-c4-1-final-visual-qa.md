# C4.1-F — Windows final visual QA

Data: **2026-10-09**, America/Sao_Paulo. Escopo: correção dos dois defeitos públicos C4.1-R, regressão Windows e revisão arquitetural IPC. Este relatório acrescenta evidência; não substitui a auditoria C4.1/C4.1-R.

## Resultado e limites

**C4_1_WINDOWS_VISUAL_READY_FOR_UAT**, limitado à interface pública e às correções verificadas em Windows. O mínimo 1100×700 foi executado em janela nativa; DARK/LIGHT e EN/PT-BR foram verificados também em composição pública isolada. **1440×900 e 1920×1080 têm evidência JavaFX automatizada, mas não validação física completa neste monitor.** UAT dos tamanhos físicos maiores permanece pendente em equipamento adequado.

**C4_WINDOWS_SECURE_IPC_DESIGN_READY_FOR_REVIEW** — [RFC](../../package-c4/WINDOWS_SECURE_IPC_RFC.md) e [matriz dos 23 erros](c4-1-f-error-matrix.csv). Nenhum transporte foi implementado.

**C4_WINDOWS_FULL_COMPATIBILITY_NOT_YET_QUALIFIED** — 23 casos IPC sem execução completa Windows, Service autenticado indisponível e regressão macOS não executada.

## Preflight e ambiente

Os três relatórios anteriores foram lidos integralmente, incluindo a matriz/skips e screenshots, antes dos patches. Root `C:/src/DEV`, branch `feature/byx-windows-readiness-v1`, HEAD `625689b2021c4c6a5f4a409d5d4680bccc865cbc`. A árvore já continha adaptações locais C4.1/C4.1-R, preservadas sem reset/clean/stash/rebase.

Windows 11 Pro 10.0.26200 x64; NTFS; monitor primário **1920×1080 pixels físicos**, DPI 144 (**150%**; aproximadamente 1280×720 lógico). Temurin 21.0.12.1, Maven 3.9.9, JavaFX 21.0.5; [mvn -version real](c4-1-f-toolchain.log). JAVA_HOME foi lido da configuração de usuário; PATH foi recomposto na sessão com os valores de usuário/máquina. Não foram alterados settings Windows, drivers ou escala.

[Estado inicial](c4-1-f-preflight-git-status.txt), [untracked inicial](c4-1-f-preflight-untracked.txt) e [581 hashes de referência](c4-1-f-preflight-hashes.json) registram código Java do Panel/Service e JSON histórico de QA. [Comparação final](c4-1-f-final-hash-comparison.json) mostra somente AuthLayout e SupportScreen modificados entre esses arquivos. CSS é alteração adicional intencional, fora da lista Java. ACL, guard Windows, Service/Signer/custody e resultados históricos JSON não foram modificados nesta tarefa. Os hashes não abrangem todos os binários/imagens; não são alegação de attestation completa.

## Defeito 1 — ação FAQ truncada

Reprodução DEFAULT: [antes EN](c4-1-f-before-help-1100x700-en.png), com JSON físico adjacente. Capturas anteriores C4.1-R e probes novos confirmaram `Open F...` / `Abrir ...`.

Causa medida no HBox de SupportScreen: o VBox de descrição e a ação competiam pela largura; o botão podia encolher abaixo da largura preferida. Na Scene 1086×663, EN recebia 100 px para prefWidth **100.140625** e PT-BR 88 px para **98.80078125**. A pequena diferença EN também acionava ellipsis real da skin. A mensagem localizada estava correta; não era erro de catálogo.

Correção mínima: permitir o VBox de descrição encolher e preservar no botão `minWidth = USE_PREF_SIZE`. O texto de descrição quebra linha; a ação mantém o rótulo completo. Após correção: EN **101 px**, PT-BR **99 px**, skin text exatamente igual ao texto localizado. Não se mudou fonte, palette, texto ou desenho aprovado.

[Depois DEFAULT EN](c4-1-f-default-after-help-1100x700-en.png). Composição pública nativa sem autoridade:

| Locale / tema | Screenshot físico depois |
|---|---|
| EN DARK | [Open FAQ](c4-1-f-after-help-1100x700-en-dark.png) |
| EN LIGHT | [Open FAQ](c4-1-f-after-help-1100x700-en-light.png) |
| PT-BR DARK | [Abrir FAQ](c4-1-f-after-help-1100x700-pt-dark.png) |
| PT-BR LIGHT | [Abrir FAQ](c4-1-f-after-help-1100x700-pt-light.png) |

As quatro capturas foram inspecionadas; rótulo completo e descrição quebrando linha, sem ellipsis na ação. Mouse abriu FAQ na composição pública. No DEFAULT, Tab levou ao botão com foco visível: [foco](c4-1-f-default-help-keyboard-focus-1100x700-en.png); Space por evento nativo abriu a página: [FAQ por teclado](c4-1-f-default-faq-keyboard-after.png). A metadata do título pode registrar “Help” durante atualização assíncrona; a imagem mostra conteúdo FAQ. Não usar só nome do arquivo/título como prova.

## Defeito 2 — orientação de acesso sob o seletor de idioma

[Antes DEFAULT](c4-1-f-before-guidance-1100x700-en.png): formulário inteiro centralizado sem reservar a altura do topo/rodapé. Ao expandir a orientação, a altura excedia a região disponível; título começava aproximadamente Y=71 lógico e o bloco superior terminava Y=89, produzindo sobreposição. Fonte/tamanho mínimo não eram a causa.

Correção: ScrollPane JavaFX no corpo do formulário, com margem calculada a partir das alturas reais de topo/rodapé; conteúdo centralizado quando cabe e rolável quando excede. Cabeçalho/idioma e rodapé continuam nas posições aprovadas; formulário usa altura preferida sem comprimir as linhas. Horizontal scrollbar permanece desabilitada e o conteúdo cabe na viewport. CSS usa tokens existentes dos dois temas.

Na Scene 1086×663: topo termina **Y=89**, título começa **Y=105**, viewport termina **Y=607**, rodapé começa **Y=624**, e guidance termina Y=607 ao rolar até o fim. Na 1100×700: viewport termina Y=644, rodapé começa Y=661. [Métricas](c4-1-f-layout-after/metrics-1086x663-EN-DARK.txt). O mínimo do Stage permanece 1100×700; 1086×663 é uma Scene menor adicional aproximando a área útil da janela decorada.

[Depois DEFAULT topo](c4-1-f-default-after-guidance-top-1100x700-en.png) e [fim acessível por roda do mouse](c4-1-f-default-after-guidance-bottom-1100x700-en.png). O título sair da viewport durante rolagem é comportamento esperado; não invade seletor/rodapé.

| Locale / tema | Composição pública física |
|---|---|
| EN DARK | [Orientação expandida](c4-1-f-after-guidance-1100x700-en-dark.png) |
| EN LIGHT | [Orientação expandida](c4-1-f-after-guidance-1100x700-en-light.png) |
| PT-BR DARK | [Orientação expandida](c4-1-f-after-guidance-1100x700-pt-dark.png) |
| PT-BR LIGHT | [Orientação expandida](c4-1-f-after-guidance-1100x700-pt-light.png) |

Mouse expande/recolhe; Tab percorre campos, links e ação, com [foco PT-BR visível](c4-1-f-default-guidance-keyboard-focus-1100x700-pt.png). Space nativo expandiu a orientação sem credenciais: [antes](c4-1-f-default-guidance-keyboard-before.png), [depois](c4-1-f-default-guidance-keyboard-after.png). Roda alcança o fim do texto. Uma sondagem posterior de PageDown ocorreu com orientação recolhida e não qualifica navegação de texto expandido por PageDown. Nenhum resultado de autenticação foi fabricado.

## Resoluções e natureza da evidência

| Tamanho lógico solicitado | Física/display-backed | JavaFX automatizado | Ainda não testado |
|---|---|---|---|
| 1100×700 | Stage redimensionado a 1650×1050 físico; login, guidance, Help/FAQ, mouse/Tab/Space e idiomas; DARK no DEFAULT, DARK/LIGHT no harness público | 4 combinações locale/tema; snapshots antes/depois e constraints; também 1086×663 | Telas/diálogos privados autenticados |
| 1440×900 | Solicitação 2160×1350 foi limitada pelo Windows a 1946×1106; captura visível 1920×1080 **clipped=true** | Scene exata 1440×900, EN/PT-BR × DARK/LIGHT, login expandido/Help/FAQ | Janela inteira no tamanho físico requerido |
| 1920×1080 | Solicitação 2880×1620 sofreu a mesma limitação, **clipped=true** | Scene exata 1920×1080, EN/PT-BR × DARK/LIGHT, login expandido/Help/FAQ | Janela inteira no tamanho físico requerido |

Capturas limitadas: [1440](c4-1-f-physical-limited-help-1440x900-pt-dark.png) / [metadata](c4-1-f-physical-limited-help-1440x900-pt-dark.json), [1920](c4-1-f-physical-limited-help-1920x1080-pt-dark.png) / [metadata](c4-1-f-physical-limited-help-1920x1080-pt-dark.json). Não contam como qualificação física dessas resoluções. O helper intersectou janela e display sem redimensionar imagem; barra de tarefas pode cobrir a borda inferior. Não foi criado monitor virtual.

Evidência automatizada usa toolkit JavaFX Windows nativo e `Scene.snapshot`, sem Stage físico, sem substituição por HTML/imagem gerada. [c4-1-f-layout-after](c4-1-f-layout-after): **64 PNG / 16 métricas** (topo/fim da orientação, Help, FAQ por caso). [c4-1-f-layout-public](c4-1-f-layout-public): **36 PNG / 12 métricas** de login/FAQ/Help preservando o teste C4.1-R com novo destino de output. Nenhum desses snapshots prova validação física de monitor.

Inspeção representativa em tamanhos maiores: [1440 PT-BR DARK Help](c4-1-f-layout-after/help-1440x900-PT_BR-DARK.png), [1920 EN DARK orientação](c4-1-f-layout-after/guidance-top-1920x1080-EN-DARK.png), [1920 PT-BR LIGHT FAQ](c4-1-f-layout-after/faq-1920x1080-PT_BR-LIGHT.png). Todos os 16 casos verificam bounds/texto real da skin, overflow e rodapé. Não se afirma inspeção humana individual de cada um dos 64 PNG.

Composição física de QA `panel.PublicVisualQa`: somente AuthScreens e PublicHost reais, Services que recusam autoridade/persistência; callbacks de login bem-sucedido lançam erro. Ctrl+Alt+L/D muda tema **somente em memória nessa fixture**, sem menu privado/ServerAuthorization bypass. Isso não é DEFAULT nem backend fake autenticado. O DEFAULT real permaneceu com tema disponível pré-login, sem persistência de preferências. Harness tem título explícito e JSON `NOT DEFAULT`.

Reprodução Windows da fixture opt-in, no diretório do Panel e com Java 21/Maven 3.9.9 configurados:

```powershell
mvn -DskipTests test-compile dependency:build-classpath "-Dmdep.outputFile=target/c4-1-f-visual-classpath.txt"
$qaClasspath='target/test-classes;target/classes;'+(Get-Content target/c4-1-f-visual-classpath.txt -Raw).Trim()
java -cp $qaClasspath panel.PublicVisualQa
```

Fechar normalmente depois da inspeção; não usar a fixture para qualificar autenticação ou telas privadas. O startup de produto separado é `mvn javafx:run` sem perfil/desvio de segurança.

Login, Help e FAQ foram posicionados/redimensionados e idiomas selecionados por controle real. Focus rings de campos/links/botões foram verificados, e consulta/categorias FAQ têm testes existentes. Não foram encontrados diálogos públicos modais nesses dois fluxos; diálogos de conta/custódia/Research e telas privadas não foram alcançados. Home/Trading Desk, mascot integrado na área privada, notificações privadas e seletor de tema autenticado **não receberam UAT DEFAULT**. Seus componentes foram testados sem afirmar sessão de produto.

## Testes reais

| Execução | Resultado | Exit | Evidência |
|---|---|---|---|
| `mvn -DskipTests compile` | BUILD SUCCESS | 0 | [compile](c4-1-f-compile.log) |
| `mvn "-Dtest=StringsParityTest,PackageC1CatalogTest,NavigatorTest" test` | 18 PASS / 0 FAIL / 0 ERROR / 0 SKIP | 0 | [focused](c4-1-f-focused.log) |
| Layout/interação direcionados | 50 PASS / 0 FAIL / 0 ERROR / 0 SKIP | 0 | [visual-second](c4-1-f-visual-second.log) |
| Probe adicional de overflow horizontal, PublicResponsiveLayoutTest | 16 PASS / 0 FAIL / 0 ERROR / 0 SKIP | 0 | [horizontal-probe](c4-1-f-horizontal-probe.log) |
| ACL/storage/session/navigation/layout/mascot/i18n/theme/notifications | 251 PASS / 0 FAIL / 0 ERROR / 0 SKIP | 0 | [security-ui-focused](c4-1-f-security-ui-focused.log) |
| `mvn test` | **1034 total; 1008 PASS / 0 FAIL / 23 ERROR / 3 SKIP; 136 suites** | **1** | [log completo](c4-1-f-full-tests.log), [summary](c4-1-f-test-summary.json), [casos](c4-1-f-test-cases.json), [erros/skips](c4-1-f-test-failures.json) |
| MascotViewTest + MascotPresenceViewTest, 3 JVM/Maven novas | 3 × 19 = **57 PASS**, sem failure/error/skip | 0 em cada | [repetições](c4-1-f-mascot-repeats.json) |

A suíte completa terminou 20:27:33 -03, 2 min 29 s; focused security/UI terminou 20:25:02 -03, 36.380 s. São **+16 casos e +1 suite** frente a 1018 total / 992 PASS / 23 ERROR / 3 SKIP: os 16 parâmetros do novo PublicResponsiveLayoutTest. Nenhum teste antigo foi removido. As identidades, tipos e mensagens dos 23 errors e três skips são idênticas ao JSON histórico C4.1-R. O summary da suíte completa foi arquivado antes de outras execuções sobrescreverem Surefire.

Contagens úteis dentro da suíte completa: WindowsStorageTest 16; PrivateFilesTest 9; AuthenticationEpochTest 17; SessionReturnTest 5; WindowsServiceUnavailableTest 7; IpcProtocolContractTest 5; AuthKeyboardTest 3; AuthLayoutTest 7; HelpScreensTest 12; PublicResponsiveLayoutTest 16; WindowsPublicLayoutTest 12; Theme 25; C3 notifications 31 + views 17. MascotViewTest 8 e PresenceViewTest 11 passaram na targeted/full e nas 57 repetições adicionais; as 190 execuções históricas permanecem documentadas, não recontadas como novas.

Os três skips preservados:

1. CaptureLiveObservationTest.actualRuntimePublishesToJavafxPanel: propriedade `capture.live` ausente; não se iniciou pipeline real.
2. CaptureMonitorTest.nioStorageUsesMetadataAndDoesNotFollowSymlinks(Path): condição Windows preexistente, sem assumir privilégio POSIX/symlink.
3. LegacyPanelDbTest.aSymlinkedLegacyFileIsUnavailable: condição Windows preexistente para symlink Unix.

Nenhum skip novo. ACL/reparse negativos de WindowsStorageTest continuam ativos. A suíte **não passou integralmente**: os 23 ERROR são pendências de qualificação IPC, descritas individualmente no RFC/matriz. Não são classificados como regressão visual estabelecida nem normalizados para sucesso.

Probes pré-correção de 16 casos falharam como previsto nos contratos reproduzidos; FxSupport encapsula AssertionError como RuntimeException, por isso aparecem ERROR. O primeiro postpatch retornou 34 PASS/16 ERROR por lookup da nova ScrollPane antes de aplicar CSS/layout na fixture; corrigiu-se o timing de layout do teste, preservando as asserções. Logs before/first ficam como evidência exploratória, não resultado final. A primeira fixture manual tinha callback público vazio e foi corrigida sem mudança de produto; capturas `physical-*-stable/current` exploratórias não substituem os arquivos depois qualificados acima.

## Startup/shutdown DEFAULT e segurança

Aplicação real via `mvn javafx:run` em DEFAULT, sem Service macOS, credenciais ou backend sintetizado. Capturas antes PID 6604; depois PID 14824 e teclado PID 5160. Shutdown por WM_CLOSE apenas na própria janela, com exit 0 e BUILD SUCCESS: [default-after](c4-1-f-default-after.log), [default-keyboard](c4-1-f-default-keyboard.log). Harness público PID 19976 também encerrado normalmente; PID 23304 foi primeira fixture exploratória.

Warnings JavaFX de classes em unnamed module e SLF4J sem provider foram mantidos nos logs, sem suppress. Harness classpath não é empacotamento/instalador. Nenhum macOS binário ou assinatura foi usado; nenhum serviço novo/listener de IPC foi aberto pela sonda.

REAL USER KEY = NOT AUTHORIZED; REAL TX = DISABLED; BROADCASTS = 0; REAL FUNDS = 0; REAL USER WALLETS = 0; ETHUSDT RESEARCH = UNTOUCHED. Não houve fallback de armazenamento inseguro, bypass Research, token fake ou persistência de credencial.

## Arquivos alterados neste marco e handoff macOS

Mudanças desta tarefa:

- `src/main/java/panel/authview/AuthLayout.java`: corpo responsivo rolável, reserva medida de topo/rodapé.
- `src/main/java/panel/helpview/SupportScreen.java`: minWidth correto para descrição/ação FAQ.
- `src/main/resources/panel/v2/auth.css`: fundo de ScrollPane/viewport nos tokens aprovados.
- Novo `src/test/java/panel/PublicResponsiveLayoutTest.java`: 16 casos portáveis, incluindo viewport menor decorada; mesmos componentes, autoridade negada.
- Novo `src/test/java/panel/PublicVisualQa.java`: launcher opt-in somente de componentes públicos; fora da composição DEFAULT.
- `src/test/java/panel/WindowsPublicLayoutTest.java` anterior: apenas destino de captura C4.1-F, sem alterar 12 casos/assertions, preservando PNG C4.1-R.
- `docs/qa/package-c4/WindowsTransportProbe.java`: sonda não ligada/não conectada; documentação/artefatos C4.1-F e RFC.

Nenhum arquivo de implementação Windows-only de armazenamento foi modificado no marco F. Os adapters ACL/JNA e guards Windows anteriores permanecem locais; sua compatibilidade macOS também precisa regressão. Os fake servers POSIX, os 23 métodos originais e assertions de identidade macOS não receberam correção cosmética para passar Windows.

Mac precisa verificar métricas/fontes/rounding de botão, altura de header/footer, scroll com mouse/teclado/trackpad, layout em FULL/REDUCED/OFF e ausência de mudança de foco/horizontal overflow. Não há posição Windows hardcoded em fonte de produto; coordenadas Win32 existem somente nas ações externas de QA. Não transferir identity.env, tokens, DBs de contas, cofres ou dados pessoais; handoff somente por diff de código e docs/evidência sem segredos, sem publicar branch.

Comandos exatos a executar em clone macOS autorizado com JDK 21/Maven 3.9.9, revalidando SHA/branch e aplicando o diff revisado:

```sh
cd /caminho/do/DEV/mvp-binance-panel
git branch --show-current
git rev-parse HEAD
mvn -version
mvn -DskipTests compile
mvn "-Dtest=StringsParityTest,PackageC1CatalogTest,NavigatorTest" test
mvn "-Dtest=PublicResponsiveLayoutTest,AuthLayoutTest,AuthKeyboardTest,HelpScreensTest,AuthenticationEpochTest,SessionReturnTest,PrivateFilesTest,LocalServiceClientTest,ChainStatusClientTest,MarketFeedClientTest,IpcProtocolContractTest,MascotViewTest,MascotPresenceViewTest,MascotPresencePureTest,MascotPolicyTest,MascotAvatarTest,PackageC2ThemeTest,PackageC3NotificationTest,PackageC3NotificationViewTest" test
mvn test
cd ../byx-local-service
mvn test
```

Guardar exit e Surefire de cada comando antes da próxima execução. WindowsPublicLayoutTest/WindowsStorageTest/WindowsServiceUnavailableTest possuem gates Windows anteriores; skips no Mac por esses gates não dispensam os 16 casos portáveis nem os testes POSIX originais. O macOS deve rodar também os procedimentos empacotados de identidade/cofre existentes em byx-packaging com ambiente QA autorizado; não lançar scripts cegamente em Windows. Renderizar nativamente no Mac os três tamanhos EN/PT-BR e DARK/LIGHT, usando somente UI pública caso autoridade não provisionada. **Mac não qualificado por esta execução Windows.**

## Git final e parada

HEAD/branch preservados; índice sem mudanças adicionadas. A árvore permanece suja com adaptações C4.1/C4.1-R anteriores mais os arquivos deste marco. Diff de tracked contra HEAD inclui as etapas anteriores; não atribuir toda essa lista ao marco F. [Status final](c4-1-f-final-git-state.txt). `git diff --check` passou. Outros projetos Service/Signer/packaging/research não receberam mudanças desta tarefa.

Não houve commit, push, merge, release, instalador ou implementação do RFC. Trabalho encerrado neste marco; IPC nativo, autenticação integrada, custódia e port Linux não foram iniciados.
