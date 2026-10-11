# Alpha 2 — restauração da apresentação aprovada

A Alpha 1 incluiu componentes V2 corretos, mas sua composição reduziu a apresentação aprovada a três páginas, acrescentou controles genéricos e ignorou serviços de apresentação necessários. O problema principal não foi ausência de CSS do Mac nem necessidade de criar um desenho novo.

A fonte indicada foi conferida no checkout C4 `828492af7b383083dbd6b749623876c188182db5`. A base da Alpha 2 é exclusivamente a história pública limpa da Alpha 1 `4e1494736b2045d8640e4f8f35425673eeb24037`. Nenhum objeto do histórico C4 ou componente operacional foi importado.

## Causas concretas

**Janela.** Alpha 1 cria `Scene(1440,900)` e fixa o mínimo do Stage em `1100×720`, sem consultar `Screen.visualBounds`. Não há chamada a `setFullScreen` ou `setMaximized` na entrada DEMO. O limite inferior impede reduzir a janela abaixo dele; em uma tela com 150% a área lógica disponível pode ser menor que os números utilizados. A decoração padrão é nativa. A correção usa limites lógicos reais da área útil, janela inicial normal centralizada, mínimo adaptado e rolagem do conteúdo excedente. Login e navegação preservam a geometria escolhida pelo usuário.

**Idioma.** O seletor original altera `Strings.languageProperty()`, mas Alpha 1 nunca cria o `LocaleView` da Scene nem vincula o título do Stage. Muitos textos de DemoApp são literais em inglês. Sem o observador original, mudar o catálogo não reapresenta a árvore de controles. A correção reutiliza `Strings`, `LocaleView`, `Presentation` e `LanguageSelector`; PT-BR é o padrão, EN continua disponível. Valores técnicos e células de dados são explicitamente literais, enquanto estado/copy e cabeçalhos são traduzidos. A busca FAQ usa a cópia traduzida; mudar o idioma preserva a consulta e a pergunta aberta.

**Composição.** Alpha 1 usa IDs de rota como títulos de páginas e resultados da busca, recria Views a cada navegação, ignora `ViewTransitionService`, deixa o status dock sem modelo e usa o avatar como saída imediata. O logout e o seletor de ativo são controles JavaFX genéricos. A Alpha 2 mantém instâncias por sessão, original UserMenu/palette/dock/cards e transições de primeira entrada; apresenta nomes de produto no lugar dos identificadores internos.

## Comparação de componentes

| COMPONENTE ORIGINAL MAC | ALPHA 1 WINDOWS | DIFERENÇA | CORREÇÃO ALPHA 2 |
|---|---|---|---|
| AuthLayout + BrandPanel | Ambos incluídos | Copy inglesa/mista, falta LocaleView | Mesmos painéis, fontes, formulário e arte; integração original PT-BR/EN e avisos DEMO claros |
| Scene / Stage | 1440×900, mínimo 1100×720 | Não adapta à área lógica/DPI; aparenta janela grande travada | Área útil lógica, início normal centralizado e redimensionamento nativo |
| ByxShell / rail 68 / topbar 56 / dock 38 | Classe original | Dock vazio; barra genérica adicional; composição incompleta | Chrome original com modelo local explícito, elementos temáticos e mesmo roteador |
| Avatar / UserMenu | Avatar original | Avatar sai diretamente | UserMenu original com ações seguras de apresentação/saída |
| Busca / breadcrumbs | ShellPalette original | Resultado usa `t-orders`, `h-help` e outros IDs | Títulos do registro original; IDs permanecem internos |
| HomeScreen / HomeMarkets / ServiceCards | VBox de botões com IDs | Página inicial sem cartões nem composição aprovada | Home original com catálogo ilustrativo, serviços indisponíveis e rotas permitidas |
| TradingDesk / métricas / candles / book / trades / blotter | Classe original | ComboBox e faixa externos consomem altura; View recriada | View original estável, seleção de ativo no chrome, viewport com rolagem quando necessário |
| MarketsPage | Classe original | Portfólio reutiliza a mesma página; Performance aponta a rota ausente | Mercados original, páginas específicas de portfólio/posições/histórico/desempenho ilustrativos |
| Páginas Orders / Positions / History | Rótulos e linhas genéricos | IDs crus e ausência de hierarquia/tabulação | Kit/painéis/tabela V2; Orders indisponível com razão explícita, nenhuma ação de envio |
| SettingsScreen | Layout original com adapter DEMO | Sem persistência da apresentação e sem integração locale | Mesmo layout e adapter restrito; arquivo de preferências exclusivo DEMO |
| FAQ / ajuda / páginas de leitura | Texto genérico repetido | Busca, acordeão, categorias e sumário ausentes | FaqScreen/Highlight/HelpContent, SupportScreen, LegalScreen e ShortcutsScreen originais com conteúdo DEMO |
| DARK / LIGHT / tokens / fontes | Recursos originais | Aparência prejudicada por controles extra sem classes | Paletas, Schibsted/Inter/JetBrains e classes V2 preservadas; nenhum desenho substituto |
| FULL / REDUCED / OFF | MotionService original | Instâncias recriadas; serviço de transição ignorado | ViewTransitionService original, lifecycle da View e preferências de movimento |
| Breakpoints / leitura | Algumas páginas originais | Janela não pode ficar menor; páginas simples sem viewport | Reflow de Home/help/legal e scroll para tabelas/Desk/Markets, preservando informações |
| Segurança / capacidades reais | Ausentes do payload DEMO | UI simples não prova fidelidade nem acesso operacional | Mesma sessão local USER; apenas classes visuais adicionais, sem backend/admin/Service/LAN/custódia |

As novas páginas ilustrativas usam `Kit`, `TTable` com skin V2, `ByxBadge`, `ByxButton` e `ByxRegion` existentes. TraderScreens originais dependem de AppContext operacional; essa dependência não foi importada. Suas rotas recebem apresentações acabadas com dados fictícios ou indisponibilidade explícita. Home/FAQ/Support/Legal são reutilização de fontes originais, com adaptações documentadas no inventário `ALPHA2-REUSED-VIEW-SOURCES.json`.

## Referência Mac aprovada disponível

As referências Package C2 são **snapshots reais de Scene JavaFX gerados no ambiente Mac**, com autoridade sintética restrita ao harness de teste. `implementation-report.md` registra a execução no ambiente Mac e o pacote `.app`; `UAT_APPROVAL.md` registra aprovação humana do owner em 2026-10-09. `visual-manifest.json` documenta a natureza da imagem e os hashes. São imagens aprovadas da apresentação naquele momento, sem moldura nativa do launcher. Não provam estado atual do Mac, integração Service atual nem paridade operacional Windows.

Quatro imagens conferidas no disco contra o manifesto aprovado:

| Referência C2 | SHA-256 | Resultado |
|---|---|---|
| native-visual-release/dark-pt-BR-login-1440x900.png | `9334c29812f52b99ee1e9de52b3537b265f125d916ecbf789608cf242fcbcec7` | MATCH |
| native-visual-release/dark-pt-BR-t-desk-1440x900.png | `b0af9a1741ec188be6d3759357ab09f787324f0437a1bbae7f4722aa7d8d6e79` | MATCH |
| native-visual-release/light-pt-BR-t-markets-1440x900.png | `403373758b48085f4163fc2609f588571607655c0b159a7bf0220e62d8c39e21` | MATCH |
| native-visual-release/dark-pt-BR-t-settings-1440x900.png | `6be4970e31fc8e1f4c29d1b97eee46589070138f2ae5739fe8b490e2e55fd551` | MATCH |

Estas imagens, manifesto e aprovação são arquivos rastreados no commit C4 indicado. Referências: [aprovação C2](https://github.com/ChamberUS/DEV/blob/828492af7b383083dbd6b749623876c188182db5/mvp-binance-panel/docs/qa/package-c2/UAT_APPROVAL.md), [relatório C2](https://github.com/ChamberUS/DEV/blob/828492af7b383083dbd6b749623876c188182db5/mvp-binance-panel/docs/qa/package-c2/implementation-report.md), [manifesto](https://github.com/ChamberUS/DEV/blob/828492af7b383083dbd6b749623876c188182db5/mvp-binance-panel/docs/qa/package-c2/visual-manifest.json).

A comparação visual deve manter route/tema/idioma/dimensões equivalentes e registrar a diferença de dados: as imagens C2 possuem estados sem feed da fixture original; Alpha 2 contém valores deliberadamente fictícios. Não se deve interpretar diferenças de candles/números como mudança dos componentes. As referências C4.1-F são Windows e estão explicitamente identificadas assim; não foram apresentadas como capturas Mac.

## Adaptações Windows e limites

Decoração e botões de janela são nativos Windows; atalhos usam Ctrl. Não há botões cosméticos de semáforo Mac. A rolagem inferior/horizontal e o reflow em janelas menores são adaptações necessárias para não ocultar controles ou valores. Dark/Light usam os tokens originais; System continua indisponível no JavaFX utilizado.

A restauração estrutural pode ser verificada por fontes, screenshots reais Windows e testes. A avaliação humana da Alpha 2 e equivalência visual com qualquer versão Mac posterior às referências aprovadas continuam dependentes da revisão do owner. A prerelease é para essa avaliação e não declara homologação operacional, IPC, LAN ou aprovação humana prévia da Alpha 2. Este documento não contém totais de execução: os relatórios novos de unit/UI/EXE e workflow são as fontes desses resultados.