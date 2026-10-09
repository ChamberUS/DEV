# BYX-MVP — Package C1: internacionalização EN / PT-BR

Publication update (2026-10-09): **human UAT APPROVED by project owner**. See [approval and evidence scope](UAT_APPROVAL.md); status fields below are historical handoff records.

Status: **PACKAGE_C1_I18N_READY_FOR_UAT**. Implementação e verificações obrigatórias concluídas; UAT humano pendente. `PUSH_NOT_ATTEMPTED`.

## 1. Estado inicial do repositório

Raiz `/Users/buynnex-corp/dev`; aplicação `mvp-binance-panel`. Branch `feature/byx-ui-redesign-v1`, HEAD `2dd12ca65072d46599449a25932ddce881d14746`, remote ChamberUS/DEV. A e B aprovados foram lidos como baseline, sem reutilizar seus resultados históricos como aprovação de C1. Havia 31 alterações tracked externas em iaos-web, além de arquivos untracked de outros trabalhos e ferramentas. Registro completo: [preflight-status.txt](preflight-status.txt). Inventário e hashes foram registrados antes da implementação. Não houve reset, stash, clean, rebase, commit ou push.

## 2. Arquitetura de internacionalização

Mantida a abstração Java nativa `panel.i18n.Strings`, Properties UTF-8 e paths existentes `panel/i18n`. Acrescentados `ui_en.properties` e `ui_pt-BR.properties`; os recursos Package B continuam carregados. `Strings` fornece locale observável, fallback EN, detecção de chaves/traduções ausentes e parâmetros nomeados. Substituição de parâmetros é feita em uma passagem: valores do usuário contendo `{q}` não viram novos parâmetros.

`Presentation` associa a cópia original das telas a chaves centrais, com mensagens parametrizadas e segmentos compostos. Isso permite manter as mesmas views e suas actions aprovadas. O padrão genérico de três campos `mk.rowAria` não participa da inferência de templates: qualquer frase com duas vírgulas poderia ser confundida com ele. Anúncios compostos usam segmentos, preservando os valores técnicos.

`LocaleView` observa uma Scene, atualiza text/prompt/accessibility/tooltip/table headers e acompanha children dinâmicos. Guarda a fonte original, traduz respostas assíncronas que chegam depois da troca e remove listeners de subtrees desmontadas. Fecha no stop. Não reconstrói shell, views, sessões, feeds ou conexões. `LocaleTooltip` cobre Tooltip.install em wrappers; seletores e listeners especializados usam listeners fracos com delegate retido pelo nó. Sem dependências externas ou tradução em rede.

## 3. Inventário de tradução

[translation-inventory-before.json](translation-inventory-before.json): 194 componentes e 3.094 candidatos únicos brutos, incluindo strings técnicas, estilos, comentários e diagnósticos. O dicionário HTML foi extraído como dados: 440 entradas EN e 440 PT. Só textos foram reutilizados; autenticação e dados demonstrativos do protótipo não foram copiados. 54 traduções adequadas completam os recursos B; uma chave curta própria resolve a legenda da rail; 1.613 entradas adicionais centralizam a cópia da aplicação, estados e conteúdo versionado. Os restantes do inventário bruto incluem caminhos, códigos, padrões de log, SVG, nomes próprios, fragmentos já cobertos e ferramentas QA; não constituem uma lista de strings visíveis pendentes.

## 4. Cobertura EN / PT-BR

Catálogos completos e com a mesma cobertura de chaves. Troca pela seleção nas Configurações ou no login atualiza navegação, header, conteúdo, menus, overlays, paleta, notificações indisponíveis, erros, tooltips, mensagens assistivas e apresentação inicial. [glossary.md](glossary.md) explicita os termos técnicos mantidos nos dois idiomas. Nomes, contas, contatos, endereços, hashes e valores de seleção são dados, não tradução. Campos digitados e seus skins são excluídos do adaptador.

O scanner inspeciona construtores de Label/Button/ByxButton/ByxToggle/ByxRegion/Tooltip/TableColumn, helpers Fx/Ui/Kit/ByxBadge/ByxField e setters de cópia visual, removendo comentários. Exige catálogo ou allowlist técnica explícita; não é um analisador Java completo e não prova cada combinação dinâmica. Conteúdo JSON humano possui um check separado. A cobertura foi complementada pela navegação real e auditoria dos textos capturados.

## 5. Número de chaves

**2.115 chaves por idioma**: 386 Package B, 61 Package B extra e 1.668 no catálogo C1. Paridade detalhada em [catalog-coverage.json](catalog-coverage.json). Chaves novas são semânticas com sufixo estável derivado do texto original; não dependem do locale ou estado do Service.

## 6. Ausências e fallback

Testes verificam todas as chaves, textos não vazios, paridade de parâmetros e padrões válidos. Fallback de tradução ausente devolve EN e registra `locale:key`; chave desconhecida permanece visível e registrada, sem silenciar falhas. Casos de ausência são injetados apenas nos testes e restaurados. Em uma JVM nativa nova, o fluxo verifica zero chaves ausentes e zero fallback inesperado. Textos técnicos desconhecidos permanecem disponíveis para diagnóstico.

## 7. Persistência e escopo do locale

**SESSION_UI_ONLY**, sem gravação em disco ou no backend. `SETTINGS_PREFERENCES_PERSIST` exige autoridade do Service; a implementação atual nega gravações de preferência. Não há mecanismo aprovado por usuário para linguagem. Foi escolhida a alternativa de sessão explicitamente permitida no pedido, sem inventar autorização de backend ou um novo armazenamento isolado.

Idioma escolhido no login acompanha a sessão que se inicia. Logout, expiração/retorno à entrada e substituição por outra conta voltam ao EN. Reiniciar inicia EN. A UI informa essa limitação. Troca de idioma não altera Prefs/draft, savebar, MFA, roles ou resultados do Service. Testes cobrem usuário substituído, logout, DENY e preservação de draft quando autorizado. Persistência futura depende de um mecanismo cliente por usuário aprovado; não é declarada como implementada.

## 8. Telas implementadas

Autenticação: entrada, password, validações, disponibilidade de cadastro, recuperação, verificações de contato/2FA e apresentação inicial. Trading: Home, Desk, Markets, Bot, Strategies, Signals, Portfolio, Positions, Orders, Performance e Activity. BYX: Network, Chain Data, Wallet, Benefits, Treasury e estados LOCALNET/TEST. Account: Profile, Security, Sessions, Settings, Notifications e Activity. Help: FAQ, Support, About, Overview, Diagnostics, What's New, Terms, Privacy e Shortcuts. System status e pesquisa autorizada: Overview, Capture, Sessions, Dataset, Labels, Features, Hypotheses, Validation, Execution, Paper, Live, Jobs, Logs, Settings e Users. Conteúdo restrito usa o mesmo gate; a tradução não o expõe ao USER.

## 9. Datas e números

`DisplayFormats` é somente apresentação. Preserva dígitos e precisão dos valores formatados existentes, usa separadores EN/PT e oferece NumberFormat/exact BigDecimal. Datas EN `yyyy-MM-dd` e PT `dd/MM/yyyy`; horários conservam seu ZoneId e sufixo. O adaptador transforma a representação já formatada no fuso escolhido; não reinterpreta Instants. Relativos são mensagens do catálogo. Eixos do CandleChart usam o locale de apresentação com a mesma precisão anterior. DenomFormat, parsing, cálculos, API, CSV e serialização criptográfica não foram modificados. Testes incluem porcentagem, BYX e base units canônicos.

## 10. Layout e overflow

Capturas reais JavaFX em 1920×1080, 1440×900 e 1100×700. O root real é temporariamente renderizado numa Scene off-screen de tamanho exato porque a tela física deste Mac não oferece 1920 pixels lógicos; não se usa HTML nem imagem do desktop pessoal. OFF é usado nas capturas para estados determinísticos. O seletor usa tokens/fontes do tema B; geometria da mascote e mínimo da janela não foram alterados.

O aviso `Real OFF` mantém o código OFF visível na janela mínima, inclusive com o indicador adicional de sessão ADMIN em Research. StatusDock reserva a largura preferida para o item de chave estável `live` e respeita o mínimo computado do seu grupo; a redução de espaço se aplica aos demais textos, com tooltips completos. Um teste usa o DockModel real de ADMIN em EN/PT nas três larguras, verificando largura do texto e ausência de sobreposição, sem mudar modelo, rotas ou handlers. Tooltips e labels assistivos mostram a cópia completa quando componentes compactos usam ellipsis aprovada pelo layout B. A legenda Settings usa a chave curta PT `Config.` para caber nos 68 px da rail; nome completo, tooltip e acessibilidade permanecem `Configurações`. As demais legendas conservam a regra B de centralizar palavras longas sem reticências. Revisão visual humana continua necessária nas interações e combinações de dados reais; screenshots sintéticos não equivalem a UAT.

## 11. Autenticação e autorização

Testes reais de PanelApp usam somente autoridade fake de teste em home temporário. USER continua bloqueado para Research; ADMIN sem MFA continua bloqueado. O harness conclui o fluxo normal de autoridade sintética de email/SMS antes de abrir as telas de pesquisa. Troca não muda ID/horário de login/userid, gates, rota ou handlers. O Service pode renovar o objeto de apresentação da sessão mantendo a identidade; a verificação compara identidade estável. Dados reais, Service, signer e custody não foram alterados. Nenhum gate foi relaxado para passar um teste.

## 12. Regressão da mascote

Fonte de geometry, pose, timing e OperationRegistry inalterada. LocaleView traduz acessibilidade de conta e estados loading/error com nome literal. Teste parametrizado cobre esses anúncios. Regressão existente verifica tracking, click/menu, loading/error, lifecycle e FULL/REDUCED/OFF. Fluxos nativos também verificam a mesma mascote e handlers através de trocas; repetição das três preferências gera evidência fresca, separada do histórico A/B.

## 13. Contagens de testes

| Verificação fresca | PASS | FAIL | ERROR | SKIP |
|---|---:|---:|---:|---:|
| Panel completo (895 testes) | 894 | 0 | 0 | 1 esperado |
| Novos testes C1, incluídos no total | 29 | 0 | 0 | 0 |
| Fluxo nativo C1 EN/PT | 337 | 0 | 0 | 0 |
| Fluxo nativo B preservado, FULL | 39 | 0 | 0 | 0 |
| Fluxo nativo B preservado, REDUCED | 39 | 0 | 0 | 0 |
| Fluxo nativo B preservado, OFF | 39 | 0 | 0 | 0 |
| Runtime empacotado | 9 | 0 | 0 | 0 |
| Probe adicional do classpath do bundle | 13 | 0 | 0 | 0 |

Total nativo: **454/454**, dos quais 117 de preservação B. O único skip é CaptureLiveObservationTest, opt-in; não foi habilitada uma qualificação de dados científicos. [panel-suite.log](panel-suite.log), [final-results.json](final-results.json), [native-results.json](native-results.json). Executado `mvn test` com Java 21/Maven 3.9.9. Suites de Service/signer/custody não foram executadas: suas fontes permaneceram intactas.

Os 29 testes C1 cobrem catálogo/parâmetros/fallback, formatos exatos, códigos e nomes literais, componentes e inputs, diálogos, paleta, bindings desmontados, drafts, DENY, logout/troca de conta e layout da rail/dock. O teste do OFF exercita o DockModel de ADMIN em EN/PT nas três larguras e exige a largura integral do texto e nenhuma sobreposição.

A primeira suíte completa encontrou apenas a referência redundante a DEVNET na lista de termos técnicos do novo adaptador. A referência foi removida; `FinalSafetyTest` permaneceu intacto. Logs de tentativas intermediárias são preservados, inclusive a rodada nativa REDUCED interrompida porque a correção final da legenda já havia tornado sua versão obsoleta. A rodada final tem evidência separada. Uma rodada encontrou gazeX=0 no teste de ponteiro da mascote, antes da limpeza do fixture, seguida de duas assertions de cache. Os mesmos 19 testes passaram em uma JVM nova (mascot-isolated.log), sem alterar código/assertions/timing da mascote. A suíte completa foi repetida; esse episódio é registrado como falha transitória de execução, sem alegar uma causa de desktop não instrumentada. A validação final usa código recompilado, hashes de fonte e JVMs novas; testes históricos A/B não contam como execução C1.

## 14. Screenshots JavaFX

**321 screenshots finais**, duas línguas e três resoluções, de 32 rotas gerais e 15 rotas de Research, mais login, menu, paleta, notificações, diálogos, gate de Research e apresentação inicial. [visual-manifest.json](visual-manifest.json) contém nomes, tamanhos e SHA-256. [native-copy-audit.json](native-copy-audit.json) registra a auditoria de cópia: nenhuma frase longa em inglês compartilhada inesperadamente entre EN/PT; placeholder legal e termos técnicos estão documentados.

Amostras: [Login PT 1100](native-visual/pt-BR-login-1100x700.png), [Settings PT 1100](native-visual/pt-BR-t-settings-1100x700.png), [Desk PT 1100](native-visual/pt-BR-t-desk-1100x700.png), [Research PT 1100 com OFF integral](native-visual/pt-BR-research-capture-1100x700.png), [Menu PT](native-visual/pt-BR-account-menu-1100x700.png), [Paleta PT](native-visual/pt-BR-palette-1100x700.png), [Settings EN 1920](native-visual/en-t-settings-1920x1080.png), [Settings PT 1440](native-visual/pt-BR-t-settings-1440x900.png).

Logs e duas imagens representativas de cada etapa supersedida foram mantidos para explicar as correções de composição, legenda e compressão do OFF; apenas o diretório native-visual é a evidência final. Avisos JavaFX unnamed-module/SLF4J e o aviso de paint em trading.css foram observados nos logs; trading.css não foi modificado por C1 e as verificações finais passaram.

## 15. Empacotamento e smoke

Candidato final **1.0.0-local-pkgc1-uat-20261009T082713Z**, construído em diretório novo. [Manifesto](candidate-manifest.json). Aplicação: `/Users/buynnex-corp/dev/byx-packaging/build/local-pkgc1-uat/1.0.0-local-pkgc1-uat-20261009T082713Z/BYX-MVP.app`. ZIP: `/Users/buynnex-corp/dev/byx-packaging/build/local-pkgc1-uat/1.0.0-local-pkgc1-uat-20261009T082713Z/BYX-MVP-1.0.0-local-pkgc1-uat-20261009T082713Z.zip`. SHA-256 do ZIP: `0556e453e9a804054d3f6e34671276af8714359fad73842d3494bc92bfb2c715`.

**9/9 checks de runtime PASS**: assinatura deep/strict, ausência do Service corretamente UNAVAILABLE, helper DEFAULT pronto, peer packaged_verified/CONNECTED/protocolo 1, mutação/signing/broadcast desabilitados, startup JavaFX + Service READY, encerramento dos processos próprios, limpeza do socket e nenhuma inicialização de authority snapshot ou wallet catalog. [packaged-smoke/result.json](packaged-smoke/result.json).

**13/13 checks adicionais PASS** usando somente os JARs do bundle como classpath (mais a classe de probe externa), com Java 21 standalone: catálogos, paridade, EN/PT nas mesmas propriedades, input literal, seletor/autônimo, precisão financeira, anúncio da mascote, OFF compacto, reset e cleanup. Este probe não é o launcher assinado; o launcher e seu runtime embutido foram verificados pelos nove checks acima. [bundle-i18n-probe.json](packaged-smoke/bundle-i18n-probe.json) e [fonte do probe](packaged-smoke/ByxC1BundleProbe.java).

As **686 classes Panel** e **seis recursos i18n** do bundle correspondem byte a byte à saída da suíte final. JAR Service mantém `9520ddc0566645fe8bbd48f0f23d8b7bb79ec10f4d8406dbe445fbc916852225`, idêntico ao B, inclusive conteúdo de todas as entradas. DEFAULT não contém Transaction Lab ou harness C1. Candidatos C1 intermediários também foram mantidos em seus próprios diretórios e ZIPs; não são o candidato final.

RC1 e B mantêm os SHA-256 originais, e os JARs do candidato A não mudaram: [preserved-candidates.json](preserved-candidates.json). Build usa scripts existentes, perfil `production-disabled`, runtime embutido e perfil de desenvolvimento local existente; nenhum novo provisioning é feito. Não é release de distribuição. Perfil local expira em 2026-10-13 16:49:55 UTC.

## 16. Limitações conhecidas

Escolha de idioma não persiste entre sessões/restarts. Conteúdo legal/onboarding/What's New que já era provisório continua provisório, traduzido e explicitamente sinalizado. APIs e endpoints indisponíveis continuam indisponíveis; traduções não criam funcionalidades de backend. Diagnóstico/export técnico e campos digitados permanecem literais. Termos mantidos têm justificativa no glossário. Scanner de literais é prático e deve ser ampliado se novos tipos de componentes forem introduzidos. UAT humano está pendente; READY significa pronto para essa aceitação, não release de produção.

## 17. Arquivos modificados

**21 fontes/recursos existentes modificados, 13 adicionados, 0 removidos**. [source-change-inventory.json](source-change-inventory.json), [tested-source-hashes.json](tested-source-hashes.json). POM e scripts de build não foram modificados. Toda alteração de produto está no Panel. Documentação/evidência C1 e checkpoint durável são adicionais.

- [src/main/java/panel/tradeview/Fx.java](../../../src/main/java/panel/tradeview/Fx.java)
- [src/main/java/panel/ui/Dialogs.java](../../../src/main/java/panel/ui/Dialogs.java)
- [src/main/java/panel/ui/HypothesesView.java](../../../src/main/java/panel/ui/HypothesesView.java)
- [src/main/java/panel/ui/LabelsView.java](../../../src/main/java/panel/ui/LabelsView.java)
- [src/main/java/panel/app/PanelApp.java](../../../src/main/java/panel/app/PanelApp.java)
- [src/main/java/panel/shell/ShellRail.java](../../../src/main/java/panel/shell/ShellRail.java)
- [src/main/java/panel/shell/ShellPalette.java](../../../src/main/java/panel/shell/ShellPalette.java)
- [src/main/java/panel/shell/UserMenu.java](../../../src/main/java/panel/shell/UserMenu.java)
- [src/main/java/panel/shell/StatusDock.java](../../../src/main/java/panel/shell/StatusDock.java)
- [src/main/java/panel/helpview/Highlight.java](../../../src/main/java/panel/helpview/Highlight.java)
- [src/main/java/panel/helpview/HelpContent.java](../../../src/main/java/panel/helpview/HelpContent.java)
- [src/main/java/panel/homeview/HomeScreen.java](../../../src/main/java/panel/homeview/HomeScreen.java)
- [src/main/java/panel/accountview/ProfileScreen.java](../../../src/main/java/panel/accountview/ProfileScreen.java)
- [src/main/java/panel/accountview/SessionsScreen.java](../../../src/main/java/panel/accountview/SessionsScreen.java)
- [src/main/java/panel/accountview/SettingsScreen.java](../../../src/main/java/panel/accountview/SettingsScreen.java)
- [src/main/java/panel/authview/AuthLayout.java](../../../src/main/java/panel/authview/AuthLayout.java)
- [src/main/java/panel/i18n/Strings.java](../../../src/main/java/panel/i18n/Strings.java)
- [src/main/java/panel/ui/trader/CandleChart.java](../../../src/main/java/panel/ui/trader/CandleChart.java)
- [src/main/resources/panel/v2/controls.css](../../../src/main/resources/panel/v2/controls.css)
- [src/main/resources/panel/i18n/package-b-extra_pt-BR.properties](../../../src/main/resources/panel/i18n/package-b-extra_pt-BR.properties)
- [src/main/resources/panel/i18n/package-b_pt-BR.properties](../../../src/main/resources/panel/i18n/package-b_pt-BR.properties)
- [src/main/java/panel/i18n/Presentation.java](../../../src/main/java/panel/i18n/Presentation.java)
- [src/main/java/panel/i18n/LocaleView.java](../../../src/main/java/panel/i18n/LocaleView.java)
- [src/main/java/panel/i18n/LanguageSelector.java](../../../src/main/java/panel/i18n/LanguageSelector.java)
- [src/main/java/panel/i18n/DisplayFormats.java](../../../src/main/java/panel/i18n/DisplayFormats.java)
- [src/main/java/panel/i18n/LocaleTooltip.java](../../../src/main/java/panel/i18n/LocaleTooltip.java)
- [src/main/resources/panel/i18n/ui_pt-BR.properties](../../../src/main/resources/panel/i18n/ui_pt-BR.properties)
- [src/main/resources/panel/i18n/ui_en.properties](../../../src/main/resources/panel/i18n/ui_en.properties)
- [src/test/java/panel/PackageC1CatalogTest.java](../../../src/test/java/panel/PackageC1CatalogTest.java)
- [src/test/java/panel/PackageC1LiteralCoverageTest.java](../../../src/test/java/panel/PackageC1LiteralCoverageTest.java)
- [src/test/java/panel/PackageC1AuthorityTest.java](../../../src/test/java/panel/PackageC1AuthorityTest.java)
- [src/test/java/panel/PackageC1FlowQa.java](../../../src/test/java/panel/PackageC1FlowQa.java)
- [src/test/java/panel/PackageC1RuntimeTest.java](../../../src/test/java/panel/PackageC1RuntimeTest.java)
- [src/test/java/panel/accountview/SettingsLocalizationTest.java](../../../src/test/java/panel/accountview/SettingsLocalizationTest.java)

## 18. Saúde da captura ETHUSDT

Coletor **54866** e supervisor **10657** continuam ativos, com a mesma relação de processos. Observações de stat registraram crescimento positivo em um mesmo .part (até 4,971,943 bytes entre amostras) e rotações normais. [capture-health.json](capture-health.json): healthy=true, metadataOnly=true, scientificDataRead=false, signalsSent=0 para os processos de captura.

Verificações limitaram-se a ps, nomes de arquivos abertos, diretórios e stat (tamanho/mtime). Não foram lidos registros científicos nem executados TRAIN, VALIDATION ou FINAL_HOLDOUT reais. Os fixtures dos testes são sintéticos e isolados; o pipeline de captura não foi modificado ou reiniciado.

## 19. Git e publicação

Branch e HEAD permanecem iguais ao preflight. **PUSH_NOT_ATTEMPTED; commit não criado; nada staged.** Mudanças de C1 estão disponíveis no working tree para revisão. As 31 alterações tracked externas e os trabalhos não relacionados foram preservados. Nenhuma diferença tracked em Service, packaging ou repositórios de captura. [git-audit.json](git-audit.json) e [final-git-status.txt](final-git-status.txt).

Fonte final ainda corresponde a todos os hashes testados depois do empacotamento e smoke. Artifacts A/B/RC1 e os candidatos C1 intermediários foram rechecados; seus bytes permanecem intactos. O novo candidato permanece local, sem publicação remota. O checkpoint `/Users/buynnex-corp/dev/docs/agent-state/PACKAGE_C1_STATE.json` registra somente C1, preservando os estados A/B.

## 20. UAT humano

1. Abrir o candidato C1 local. No login, escolher Português (Brasil) e validar rótulos/erros; voltar a English.
2. Entrar com sua própria conta pelo fluxo normal. Em Configurações > Geral escolher o idioma e navegar Home, Mesa, Benefícios, Carteira, Ajuda e FAQ. Conferir datas, decimais, valores ausentes e estados TEST/LOCALNET/OFF.
3. Abrir menu da conta, paleta, tooltips e confirmação. Trocar idioma mantendo overlays abertos; fechar com Esc e exercitar atalhos. Confirmar que a troca não dispara ações.
4. Validar 1920×1080, 1440×900 e 1100×700 e scroll das telas maiores. Testar os dois idiomas em FULL/REDUCED/OFF; acompanhar ponteiro, click/menu e loading/error de operações reais existentes.
5. USER deve permanecer sem Research; ADMIN deve fazer o MFA normal antes de acessar. Trocar idioma não deve autorizar carteiras, REAL, ordens, gravações de pesquisa ou preferências negadas pelo Service.
6. Se sua autoridade permite editar preferências, criar um draft, trocar idioma e confirmar que o draft/save/discard se mantém. Não enviar ações financeiras para testar tradução.
7. Sair, entrar em outra conta e reiniciar: EN é o estado inicial; a escolha é apenas da sessão. Registrar qualquer cópia não técnica sem tradução, clipping novo, perda de foco ou regressão funcional com tela/idioma/resolução.

PERMANENT SAFETY GATES: REAL USER KEY = NOT AUTHORIZED; REAL TX = DISABLED; BROADCASTS = 0; REAL FUNDS = 0; REAL USER WALLETS = 0; ETHUSDT RESEARCH = UNTOUCHED.

O trabalho para em C1. Tema, serviço de notificações e outras plataformas não foram iniciados.
