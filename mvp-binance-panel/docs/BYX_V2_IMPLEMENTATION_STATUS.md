# BYX V2 — implementation status (JavaFX)

Legenda: DONE · CODED (escrito, aguardando `mvn test` no Mac) · TODO

| # | Passo | Status | Notas |
|---|-------|--------|-------|
| 1 | Baseline / auditoria do app atual | DONE | Cópia do working tree; `.git` não visível ao sandbox → baseline `a3368a2` no repo local de trabalho |
| 2 | Corrigir navegação / lifecycle / motion | DONE | `Navigator`, `View.onShow/onHide`, `MotionService`, Orb/Bot/Icon, timers, dock. Validado no Mac (testes + manual) |
| 3 | Congelar fundação de roteamento | FROZEN | `0440d35`. `NavigatorTest` + `FoundationLifecycleTest` verdes antes de cada checkpoint seguinte |
| 4 | Design foundation (tokens, tipografia, componentes) | DONE | Pacote `panel.design`, tema `/panel/v2/*.css`, galeria `./run-gallery.sh` (DEV ONLY). Ver "Passo 4" abaixo |
| 5 | Shell (rail, top bar, dock, breakpoints) | DONE | `panel.shell`; shell V2 é o shell real; Views legadas hospedadas em `LegacyHost`. Ver "Passo 5" abaixo |
| 6 | Auth (Login, First run; demais BACKEND_REQUIRED/REFERENCE_ONLY) | DONE | `panel.authview`; auditoria em `docs/BYX_V2_AUTH_AUDIT.md`. Ver "Passo 6" abaixo |
| 7 | Trading | DONE | Desk e Markets & Portfolio em V2; Live OFF. Auditoria em `docs/BYX_V2_TRADING_AUDIT.md`. Ver "Passo 7" abaixo |
| 8 | Research + Capture | DONE | Overview e Capture em V2; VALIDATION LOCKED / FINAL_HOLDOUT SEALED. Auditoria em `docs/BYX_V2_RESEARCH_CAPTURE_AUDIT.md`. Ver "Passo 8" abaixo |
| 9 | BYX | DONE | Network, Wallet, Benefits e Treasury em V2, somente leitura. Ver "Passo 9" abaixo |
| 10 | Account | DONE | Profile, Security, Sessions, Notifications, Activity e Settings em V2. Ver "Passo 10" abaixo |
| 11 | Help | DONE | FAQ, About, Overview, Support, Diagnostics, Terms, Privacy, Shortcuts e What's new em V2. Ver "Passo 11" abaixo |
| 12 | System + onboarding | DONE | Welcome, Onboarding, System Status, recuperação, erros, Page unavailable, fallback e startup. Ver "Passo 12" abaixo |
| 13 | Motion + acessibilidade | TODO | FULL/REDUCED/OFF |
| 14 | QA final | TODO | |

## Critérios de aceite da fundação
Dependem de execução no Mac (`mvn test`, `./run.sh`). Cobertos por testes: último pedido vence, navegação assíncrona cancelável, entrada de cards uma vez, uma única view visível, loops reiniciáveis, `onFinished` preservado. Pendente de verificação manual: ausência de navegação automática em uso real, timers parados em views ocultas.

## Passo 4 · Design foundation

Saída: a galeria (`ControlGallery`, `./run-gallery.sh` ou `Main --gallery`) renderiza todos os estados de todos os controles em FULL, REDUCED e OFF; `ControlGalleryTest` verifica isso e grava `target/gallery/gallery-{full,reduced,off}.png`. O tema V2 ainda não está ligado ao `PanelApp`: os prefixos `-byx-*` / `.byx-*` convivem com `panel.css` / `byx.css` até o passo 5 (Shell).

| Checkpoint | Conteúdo | Teste |
|---|---|---|
| 4.1 | `BYX_DESIGN_TOKENS.json` em `/design`, `tokens.css` (looked-up colors), contextos trader/research/byx, `.hc` | `DesignTokensTest` (JSON = CSS, parse sem erros) |
| 4.2 | `MotionSpec`: 79 tokens por modo (`none`, `static`, `instant`, faixa) + `MotionService.token/duration/easing` | `MotionSpecTest` |
| 4.3 | `ByxFonts`, `typography.css` | `ByxTypographyTest` (face e tamanho por token) |
| 4.4 | Button (5 variantes, loading), Field/Password, Checkbox, Toggle, StatusBanner, badges | `ByxControlsTest` |
| 4.5 | Status chip + ponto (6 estados + UNAVAILABLE esperado, pulso só FULL) | `ByxStatusChipTest` (inclui vazamento) |
| 4.6 | `ByxRegion`: contrato de 10 estados, LOADING finito, STALE | `ByxRegionTest` |
| 4.7 | `ByxOverlayHost`: camadas 30–80, Esc no topo, foco, persistentes, toasts | `ByxOverlayHostTest` |
| 4.8 | `ControlGallery`, `GalleryApp`, `run-gallery.sh` | `ControlGalleryTest` |

### Contagem de motion tokens
- REFERENCE TOKENS: 79 = 74 das fases 1–3 + 5 `brandField*` do P3.20 (painel de marca de auth/welcome). O README do handoff final declara 79; a contagem 74 era anterior ao P3.20.
- DERIVED/INTERNAL TOKENS: 0. `MotionSpec` resolve só as chaves do JSON; `REDUCED_FALLBACK_CAP` é constante, não token.
- TOTAL RESOLVED: 79.
- `ReferenceTokensGuardTest` fixa o SHA-256 dos dois JSONs da referência e a lista exata dos 79 nomes.

### Desvios conhecidos (JavaFX)
1. **letter-spacing** (`label` .06em, dock .1em): JavaFX CSS não tem. Caixa alta via `ByxFonts.upper()`; o espaçamento fica como desvio.
2. **line-height**: aplicado como `-fx-min-height` em texto de uma linha e `-fx-line-spacing` no body; texto multilinha pode diferir alguns px da referência.
3. **Transição de cor** (hover, foco, chip, ponto do dock): JavaFX CSS não anima cor; a cor muda na hora. Movimento (hover 1 px, press .98, polegar do toggle, escala do chip) segue os tokens via `MotionService`.
4. **Pesos de fonte**: cada TTF estático vira família própria no JavaFX; `-fx-font-weight` escolhia faces arbitrárias (ex.: JetBrains Mono NORMAL → SemiBold). O tema V2 escolhe a face pelo nome. `byx.css` atual ainda usa `"Inter"` + `bold` e tem o mesmo problema latente (fora do escopo do passo 4).
5. **Schibsted Grotesk 500** não está empacotada; nenhum token de UI usa 500 (só `data`, que é JetBrains Mono Medium).
6. **Toggle em REDUCED**: `modes.REDUCED.translate = false`, então o polegar vai direto à posição (o `toggleSwitch.reducedMs` 100 não move nada). Mudanças de estado vindas de dados também não animam.
7. **`profileSaved`** não declara `reducedMs`: usa o teto `MotionSpec.REDUCED_FALLBACK_CAP` (120 ms, maior reducedMs não-loop do JSON).
8. **`delayMs`** (tooltip 300, rowRemove 700) é tratado como tempo lógico e vale em FULL, REDUCED e OFF.
9. **Toasts** ficam no canto inferior esquerdo com margem 16; a margem final acima do dock (38) é definida no passo 5 (`setToastMargin`).

## Passo 5 · Shell

Saída: o `ByxShell` (rail 68, top bar 56, dock 38, camadas 30–80) é o shell real do `PanelApp`. As Views de Trading, Research, BYX e Profile/Settings continuam as legadas, hospedadas num `LegacyHost` até o passo que as porta.

### Arquitetura
- **Autoridade de rota:** `ShellRouter` (sobre o `Navigator` congelado). Rail, seletor, busca, menu, dock, atalhos e Views legadas (`ctx.navigate`) só chamam `request(id)`. O gate real (admin + 2FA/dispositivo confiável do `PanelApp`) responde ALLOW/DENY/PENDING; um callback com ticket antigo não faz nada. Rail, seletor, breadcrumb, acento e contexto legado são derivados de `routeProperty()`.
- **Fronteira de CSS:** a cena carrega só o tema V2 (`/panel/v2/*.css`, classes `.byx-*`). `panel.css` e `byx.css` ficam presas ao `LegacyHost`, que carrega a classe `root` e o contexto (`trader`/`research`/`byx`) de que essas folhas dependem. Nada depende de ordem ou especificidade entre os dois temas; popups legados (ContextMenu, Tooltip, ComboBox) herdam o estilo pelo host do dono. `LegacyBoundaryTest` verifica os dois sentidos.
- **Telas de entrada** (login, setup inicial, troca de senha, 2FA) seguem legadas no host de entrada até o passo 6.

| Checkpoint | Conteúdo | Teste |
|---|---|---|
| 5.1 `e53a224` | Tema V2 na cena; folhas legadas presas ao `LegacyHost` | `LegacyBoundaryTest` |
| 5.2 `4c34a0f` | `ShellRouter` + gate real | `ShellRouterTest` |
| 5.3 `091e10e` | Rail, seletor, top bar, dock, `ByxShell` | `ShellComponentsTest` |
| 5.4 `17330fc` | Busca/paleta, menu do usuário, painel de notificações | `ShellGlobalControlsTest` |
| 5.5 `663880a` | Shell V2 como shell real; `DockModel` | `ShellComponentsTest`, `ShellQaSmoke` |
| 5.6 `fee488e` | Foco da paleta volta ao abridor; regressão de navegação no app real | `ByxOverlayHostTest`, `ShellNavigationQa` |
| 5.7 `5359f54` | Medidas P2.4; regras do dock | `ShellMeasurementsTest`, `DockModelTest` |
| 5.8 `7d8dd30` | Polimento visual de menu, filtros e paleta | `ShellQaSmoke` |

### Funções antigas reposicionadas (nada removido em silêncio)
| Antes | Agora | Motivo |
|---|---|---|
| Botão/menu Refresh | Busca → COMMAND "Refresh data" | O V2 não tem refresh no top bar |
| Selo MOCK | Selo "MOCK DATA" no top bar (só com fonte MOCK) | Dado não real precisa ser sinalizado; o V2 não tem posição própria |
| Menu: Switch Workspace / Admin-Research | Seletor de workspace | Redundante com o seletor V2 |
| Menu: Security → Profile | Igual (Profile legado contém senha, acesso admin e eventos de segurança) | Tela Security V2 chega no passo 10 |
| Menu: About / Credits | "About BYX" (diálogo Credits legado) | Mesmo destino |
| Menu: Logout direto | "Sign out" com confirmação (foco em Cancel) | Handoff: sair sempre pergunta |
| Toasts legados (`ToastHost`) | Camada 80 do shell (antes do login, o host legado de entrada) | Toasts V2 |
| Rail Trading "Wallet" → BYX Wallet | Rail Trading "Wallet" → Portfolio (`t-portfolio`); BYX Wallet fica no rail BYX | Referência: tooltip "Portfolio" |

Destinos V2 sem tela ainda aparecem desabilitados com motivo (COMING SOON): Keyboard shortcuts e Help (passo 11); no rail Account, Security, Sessions, Notifications e Activity (passo 10). Rotas legadas fora do rail (Strategies, Signals, Positions, Performance, Bot Activity, Sessions, Dataset, Validation, Simulator, Paper, Live, Jobs, Logs, Users, Research Settings) seguem acessíveis pela busca e pelos links internos das Views.

### Dock
`DockModel` converte estado real: ilegível = UNKNOWN; feed `NOT_CONFIGURED` e captura parada = ponto neutro; queda = vermelho; "Live trading OFF" é texto primário, nunca vermelho. Itens com tela real navegam pelo roteador (feed → Markets, capture → Capture só para admin, network/environment → BYX Network, wallet → Wallet, admin session → Profile); Backend e MODE aguardam a System Status (passo 12). Estado igual não toca em nenhum nó; mesma estrutura atualiza no lugar. Abaixo de 1440 (mínimo 1280) o dock aperta espaçamentos e esconde os títulos dos grupos em vez de cortar ou quebrar.

### QA
- `ShellNavigationQa` (app real, FULL/REDUCED/OFF): 36/36 em cada modo, mesmo estado final. Resultados em `docs/qa/step5/navigation-*.txt`.
- Capturas do shell real: `docs/qa/step5/` (1440x900, 1600x1000, 1920x1080, 1280x760, contexto Account, paleta, menu, notificações + toast). Esta tela (1536x960 lógicos) não comporta janelas de 1600/1920: o `ShellQaSmoke` move a raiz real do app para uma cena fora da tela do tamanho exato.

### Achados e desvios
1. **RESOLVIDO (pós-passo 5): entrada de cards ao voltar a uma View.** Antes, `ViewTransitionService` reexecutava a entrada em toda troca de View (20 idas e voltas: 40 entradas). Agora a marca "já entrou" fica no nó da própria instância de View: primeira exibição de uma instância nova = entrada; voltar à mesma instância, refresh de dados, status, resize e fundo→frente = nada; `dispose(node)` ou fim de sessão (`forget()`) = a próxima instância entra uma vez. `ViewEnterLifecycleTest` e `ShellNavigationQa` cobrem os três modos.
2. **Notificações — INTENTIONALLY PRESERVED: UNAVAILABLE.** Sem backend de notificações o painel mostra UNAVAILABLE. A matriz do handoff lista READY/LOADING/EMPTY/ERROR para notificações, mas EMPTY implicaria que uma fonte real foi consultada e retornou zero itens.
3. **Seletor:** 248 px contra 244 da P2.4 (largura do texto; o protótipo foi medido sem as fontes empacotadas).
4. **Dock em 1280:** títulos dos grupos ocultos (o handoff só desenha a partir de 1440).
5. **Cores sem transição** (hover, seleção, chip): limite do CSS JavaFX, já registrado no passo 4. Indicadores do rail e do seletor deslizam com os tokens só em FULL.
6. **LEGACY QA — REVIEW REQUIRED BEFORE STEP 14.** `FidelityProductionSmoke`, `RedesignVisualSmoke`, `MotionParitySmoke` e `ByxVisualSmoke` (fora do `mvn test`) medem o cromo antigo e acessam o `PanelApp` por reflexão (o campo `palette` mudou de tipo). Não valem como evidência de layout V2 até serem atualizados. Evidência V2: `ShellQaSmoke`, `ShellNavigationQa` e os testes `Shell*`.
7. `ToastHost` e `panel.ui.CommandPalette` permanecem: o primeiro só para a entrada legada; do segundo só `commands(admin, verified)` é usado (fonte dos gates da busca).

## Passo 6 · Auth

Auditoria e mapeamento V2 → capacidade real: `docs/BYX_V2_AUTH_AUDIT.md`. Nenhum backend inventado.

| Checkpoint | Conteúdo | Teste |
|---|---|---|
| 6.1 `27c2644` | Auditoria do auth existente | — |
| 6.2 `8a79638` | `AuthLayout` (520/560/640, 64/64/96, 392/432/448) + brand field P3.20 (geometria idêntica à referência) | `AuthLayoutTest` |
| 6.3 `de635d5` | `ByxOtpInput`, acessório no rótulo do `ByxField` | `ByxOtpInputTest` |
| 6.4 `5c8f43e` | Login, primeiro uso, troca obrigatória, "esqueci a senha" como rotas `auth:*` do roteador | `LoginControllerTest`, `AuthLayoutTest` |
| 6.5 `183cb77` | Verificação de admin V2 (e-mail → SMS → trust opcional; NOT CONFIGURED real) | `AdminVerificationViewTest` |
| 6.6 `083e376` | Sessão expirada (P3.11) + retorno; correção do bloqueio furado por Esc | `SessionReturnTest`, `AuthFlowQa` |
| 6.7 | Teclado, QA visual e evidências | `AuthKeyboardTest`, `AuthFlowQa` |

### Real × sem backend
- **Real:** login no repositório local de contas (erros neutros, conta desativada, repositório ilegível, bloqueio com o tempo real do serviço), primeiro administrador, troca obrigatória de senha, verificação de admin por e-mail + SMS (ou dispositivo confiável), "Trust this Mac" opcional, logout com confirmação, sessão expirada.
- **Sem backend (nunca finge sucesso):** cadastro e verificação de e-mail (inalcançáveis; o login diz que contas são criadas por um administrador), recuperação por e-mail (tela "Not available in this build", aponta o caminho real do administrador), reset por token e configuração de 2FA (inalcançáveis). Rodapé: About real; FAQ, Terms e Privacy desabilitados até o passo 11.

### Navegação
Telas de entrada são rotas do mesmo `ShellRouter` (`auth:login`, `auth:forgot`, `auth:setup`, `auth:change-password`); o gate só as permite sem sessão (troca obrigatória só para a sessão que precisa) e nega rotas do app sem sessão. Sucesso de login chama o roteador; nenhuma animação decide. Uma tentativa de login descartada não muda estado, não navega e encerra a sessão que tenha aberto. A verificação de admin só aplica a rota Research pelo ticket do roteador; fechada, cancela o desafio e ignora resultados tardios.

### QA
`AuthFlowQa` (app real, dirigido pela UI): 35/35 em FULL, REDUCED e OFF. `ShellNavigationQa`: 37/37 nos três modos depois do passo 6. Capturas em `docs/qa/step6/`.

### Achados e desvios
1. **Corrigidos no passo:** Esc limpava o bloqueio por tentativas na UI; a contagem continuava reescrevendo o botão depois do bloqueio (ambos achados pelo `AuthFlowQa`).
2. **SMS:** campo único de dígitos (4–10) em vez de 6 caixas: o comprimento do código é do serviço Twilio Verify e não é exposto ao cliente. E-mail usa 6 caixas (código gerado localmente com 6 dígitos).
3. **2FA não é etapa do login:** no app real ele é a elevação para Research; o estado "2FA required" do login V2 virou a tela de verificação de admin.
4. **"Server unavailable"** virou "Sign-in unavailable · The local account store could not be read": não há servidor remoto de auth.
5. **Sucesso:** o estado "Signed in" é renderizado, mas o workspace abre na hora (sem atraso artificial).
6. **Política de senha:** a real (10+ caracteres, diferente do usuário) substitui o painel DEMO_POLICY de 4 regras; não há barra de força.
7. **A sessão de usuário não expira no backend;** o contrato P3.11 vale quando ela some com o app aberto. A expiração da autorização de admin mantém o comportamento existente (aviso e volta para Trading).
8. **Brand field** usa a cor Ion (#7C96FF) fixa da referência; o desenho roda a ≤ 30 fps em FULL e para com a janela oculta.

## Passo 7 · Trading

Auditoria e mapa de fontes: `docs/BYX_V2_TRADING_AUDIT.md`. Live trading continua OFF; nenhum dado de demonstração entra; nenhuma ordem, credencial, endpoint ou salvaguarda foi tocada.

| Checkpoint | Conteúdo | Teste |
|---|---|---|
| 7.1 `1feefc5` | Auditoria do Trading atual | — |
| 7.2 `15283e4` | `DeskMode` (contrato 1440/1600/1920 lido do JSON) e `DeskModel` (estado do feed, métricas, bot, book, trades) | `DeskModeTest`, `DeskModelTest` |
| 7.3 `693e0ee` | Desk V2: cabeçalho, métricas, gráfico, book, trades, blotter, bot; host V2 no shell; `TradingDeskView` removida | `DeskLayoutTest` |
| 7.4 `2c88c16` | Ajustes contra os renders da referência | — |
| 7.5 `d29dac6` | Estados, incremental, guarda de live trading, FULL/REDUCED/OFF, estilo só V2 | `DeskStateTest`, `DeskIncrementalTest`, `DeskGuardTest`, `DeskMotionAndStyleTest` |
| 7.6 `a016beb` | Markets & Portfolio V2 | `MarketsPageTest` |

### Escopo e fronteira legada (7.15)
- **V2 CONTENT no V2 SHELL:** Trading Desk (`t-desk`) e Markets & Portfolio (`t-markets`). Vivem em `ByxShell.v2Content()`, fora do `LegacyHost`; a cena carrega só `/panel/v2/*.css` (`DeskMotionAndStyleTest` e `MarketsPageTest` verificam folhas, ancestrais e classes legadas).
- **Ainda legadas (documentado, não é acidente):** Bot, Portfolio (rail "Wallet"), Orders, Positions, Signals, Strategies, Performance, Bot Activity e o Settings do Trader (`TraderScreens`). O handoff só tem tela de referência para Desk e Markets & Portfolio; as demais seguem os passos 10 (Account/Settings) e posteriores.

### Fontes reais, estados reais
- Real hoje: símbolo/mercado (captura), backend online, estratégia, modo, `trading = DISABLED`. **Não existem:** feed, preço, candles, book, trades, conta, equity, PnL, exposição, drawdown, horário do feed. Sem eles: "Waiting for market data", "—", N/A com razão ("No account", "No feed", "No positions"). `TraderSnapshot.feedUpdatedAt` (novo, opcional) é preenchido por nenhum provider real; só fixtures de QA o usam.
- Feed: NO FEED (não configurado, ponto neutro), WAITING (conectando, pulsa), LIVE, MOCK, STALE, DEGRADED, RECONNECTING, DISCONNECTED, UNAVAILABLE, ERROR. Estado desconhecido nunca é "vivo"; só LIVE/MOCK/DEGRADED/STALE/RECONNECTING mostram valores; STALE mantém o último valor esmaecido com LAST UPDATE; queda/erro mostram "—" e mensagem, nunca o preço antigo.
- Métricas: READY, N/A, STALE, UNAVAILABLE com razão. Bot: MONITORING, IDLE, UNAVAILABLE, ERROR (só o que o app declara; nunca "active trading").
- Blotter (Positions, Orders, Trades, Signals, Activity): LOADING, EMPTY, READY, ERROR, UNAVAILABLE. Real: Positions/Orders/Trades ficam EMPTY (execução OFF: nenhuma pode existir); Signals/Activity ficam UNAVAILABLE se o backend está offline (derivam dele) e EMPTY caso contrário. ERROR existe no componente mas nenhuma fonte real o produz hoje.
- Recent trades e a aba Trades leem o mesmo `tradeRows` (fills de conta, não fita de mercado): mantido, ver auditoria §4.1.

### Layout (medido contra a referência)
| | COMPACT 1440 | STANDARD 1600 | EXPANDED 1920 |
|---|---|---|---|
| Coluna(s) | 330, abas Market/Bot/Risk | 350, 3 painéis empilhados | 330 Market + 350 Context |
| Book / trades | 3 níveis / 3 linhas | 6 / 4 | 10 / 9 |
| Bot | faixa | painel, 4 linhas | painel próprio, 4 linhas |
| Risk / Freshness / Activity | abas Bot e Risk | (não há; ver métricas e blotter) | painéis próprios |
| Blotter | 214 px, 7 col. | 240 px, 8 col. | 300 px, 10 col. |
`DeskLayoutTest` confere os retângulos de 1440 (cabeçalho 88,70,1332,60 · métricas 88,144,988,84 · gráfico 88,242,988,376 · contexto 1090,144,330,474 · blotter 88,632,1332,214) e os derivados de 1600/1920. Breakpoint pela largura de conteúdo (janela − rail): ≥1700 EXPANDED, ≥1480 STANDARD. 1280×760: COMPACT; a coluna de contexto rola em vez de sobrepor, estatísticas de 24h saem do cabeçalho (< 1300 px).

### Movimento
Nenhum feedback de tick: valores mudam sem animação, sem entrada de página, sem Timeline por tick (`aTickNeverMovesOrFadesAPanel`). Loops: ponto do bot e marca de espera, só em FULL e só com o estado (`loopsExistOnlyInFullAndNeverGrowWithTicks`). Um relógio de 1 s só existe com horário de feed conhecido e o Desk visível. `everyStateEndsIdenticallyInFullReducedAndOff`: mesmo estado final nos três modos para 9 estados em 1440 e 1920.

### Performance (`docs/qa/step7/performance.txt`, `DeskPerfQa`)
400 ticks com book/trades/candles/uPnL mudando: update 0,65–0,77 ms p50 (máx 1,8 ms), CSS+layout forçado ~10–13 ms (três passes manuais por tick, limite superior), nós constantes (558/514/778 em 1440/1600/1920), 0–1 loop. Poll idêntico: 0,04–0,07 ms. `a150TickStreamReusesEveryNodeAndNeverRebuilds`: 150 ticks nos três tamanhos com os mesmos nós, linhas, colunas, gráfico e sem acumular loop.

### Guarda de live trading (7.11)
`DeskGuardTest`: o Desk não tem campo, slider, menu, checkbox nem botão fora de timeframe e abas; só 1m habilitado; renderizar não escreve `trading` nem outro campo; varredura de fonte: nenhum código de execução/exchange/chave em `panel/tradeview` e `panel/ui/trader`, nenhum endpoint de ordem em `src/main`, nenhuma escrita em `.trading =`; o provider real continua `DISABLED` / `NOT_CONFIGURED`.

### QA
- **Desk e Markets no app real** (`TradingDeskQa`, `docs/qa/step7/`): estado real (NO FEED) em 1440/1600/1920/1280; fixtures isolados de QA (marcados no `report.txt`; poll de 1 h para não serem sobrescritos): waiting, conta, stale, degraded, disconnected, error, abas de contexto e do blotter. Referências renderizadas em `reference-*`.
- **Navegação** (`ShellNavigationQa`, idle de 60 s): FULL 37/37, REDUCED e OFF sem falhas. Uma primeira execução FULL, feita com suítes rodando em paralelo, falhou 2 checagens do idle de `t-byx`; o fechamento está em "Stress de navegação (fechamento do Passo 7)" abaixo.
- **Auth** (`AuthFlowQa`): FULL, REDUCED e OFF sem falhas.

### Diferenças
| Referência | JavaFX | Paridade | Motivo |
|---|---|---|---|
| Geometria 1440/1600/1920 | idêntica ao DOM | EXACT | `DeskLayoutTest` |
| Esqueleto do book com barras de profundidade coloridas | esqueleto cinza sem barras | INTENTIONALLY PRESERVED | esqueleto não pode sugerir dado |
| Esqueleto com shimmer | esqueleto estático | NEAR | sem 80+ animações no hot path; estado explicado pelo ponto/texto |
| "Binance USD-M" no gráfico | "Binance USD-M Futures" | NEAR | texto vem do mercado real |
| Cabeçalho sem Live badge nem 24h high/low/volume | `LIVE OFF` e estatísticas (se houver dado e largura) | NEAR | pedido 7.4 |
| Eixo de tempo `--:--` com candles | só no estado de espera | NOT FEASIBLE | `Candle` não tem horário |
| Timeframes 5m–4h habilitados | desabilitados com motivo | BACKEND UNAVAILABLE | backend só fornece 1m |
| Latência no Data freshness | "—" | BACKEND UNAVAILABLE | sem fonte |
| Risk limits | "Unavailable" | BACKEND UNAVAILABLE | sem fonte |
| Mensagem vazia do blotter de Markets em 1 linha | ícone + título + texto (mesmo componente do Desk) | NEAR | reuso |
| Contagem das 4 linhas do Bot em 1920 "completo" | 4 linhas, painel próprio | EXACT | a referência não tem linhas extras |
| Tinta de PnL por sinal | verde/vermelho + texto | NEAR | texto sempre presente |
| `ETHUSDT` no cabeçalho | símbolo da captura ou N/A | INTENTIONALLY PRESERVED | sem símbolo inventado (breadcrumb: "Desk") |

### Limitações conhecidas
1. Sem feed/conta reais, tudo que depende deles só foi verificado com fixtures de QA.
2. `TraderSnapshot` não tem horário de feed nem por candle; STALE por idade só existe quando um provider futuro preencher `feedUpdatedAt`.
3. **Recent Trades NÃO é um market tape verdadeiro.** Reutiliza `tradeRows`, os fills de conta do provider existente, a mesma fonte da aba Trades. INTENTIONALLY PRESERVED / DATA SOURCE LIMITATION até existir um feed real de market trades (nada foi mudado).
4. Markets não tem relógio de idade (atualiza no poll de 3 s).
5. Smokes legados (`FidelityProductionSmoke`, `RedesignVisualSmoke`, `MotionParitySmoke`) citam ids do Desk antigo; seguem na lista "REVIEW REQUIRED BEFORE STEP 14".
6. A falha isolada de navegação em FULL sob carga: UNREPRODUCED LOADED-RUN FAILURE (ver abaixo).

### Stress de navegação (fechamento do Passo 7)
Executado antes do freeze, sequencial e sem `mvn test` em paralelo, no app real, com `RouteTrace` registrando cada mudança de rota (instante, thread, ticket, pendente, de/para e pilha do app). Evidência em `docs/qa/step8/stress/`.

| Cenário | Resultado |
|---|---|
| `ShellNavigationQa` FULL isolado × 10 | 10/10, 0 falhas |
| BYX idle (60 s, updates de dados, rota == BYX o tempo todo) × 20 | 0 falhas |
| A→B→C rápido (com B pendente) × 20 | 0 falhas |
| Trading→BYX→idle→Trading→BYX→idle × 20 ciclos (30 s) | 0 falhas |
| `ShellNavigationQa` FULL com 3 suítes `mvn test` não relacionadas em paralelo × 1 | 37/37, 0 falhas |

Toda mudança de rota nos traços vem de `PanelApp.show → ShellRouter.request/commit` chamado pelo próprio roteiro do QA (nenhuma de timer, callback de gate ou animação). A execução original que falhou antecede o `RouteTrace`, então a sequência exata dela não foi capturada. Classificação: **UNREPRODUCED LOADED-RUN FAILURE**. Carga de CPU não muda a rota: sob contenção o app ficou estável. **Item para o Passo 14:** repetir este stress final (10 × `ShellNavigationQa`, 20 × BYX idle, 20 × A→B→C, 20 ciclos, 1 execução sob carga).

**STEPS 1–7 = FROZEN** (403 testes, `mvn clean package` PASS). Trading não muda sem regressão comprovada.

## Passo 8 · Research + Capture

Auditoria: `docs/BYX_V2_RESEARCH_CAPTURE_AUDIT.md`. Os gates científicos não mudaram: VALIDATION continua LOCKED e FINAL_HOLDOUT continua SEALED (constantes `final` do `Snapshot`). Nenhum job, captura, dataset, feature, label ou hipótese foi executado para QA; fixtures são registros em memória.

| Checkpoint | Conteúdo | Teste |
|---|---|---|
| 8.1 | Auditoria de Research Overview e Capture | — |
| 8.2 | `ResearchModel`, `ResearchOverview`, `CapturePanel`, `CaptureScreen`, `research.css`; legados `OverviewView`, `CaptureView`, `CaptureMonitorCard` removidos | `ResearchModelTest`, `ResearchOverviewTest`, `CaptureScreenTest` |
| 8.3 | Gates, ciclo de vida do JobsView, QA | `ResearchGateTest`, `JobsViewLifecycleTest` |

### Escopo e fronteira legada (8.22)
- **V2 CONTENT no V2 SHELL:** Research Overview (`overview`) e Capture (`capture`), em `ByxShell.v2Content()`; a cena carrega só `/panel/v2/*.css`.
- **Ainda legadas (documentado):** Labels, Features, Hypotheses, Sessions, Dataset, Validation, Simulator, Paper/Shadow, Live, Jobs, Logs, Users e Research Settings. O handoff só tem tela de referência para Overview e Capture; o rail de Research (Overview, Capture, Labels, Features, Hypotheses) leva a páginas legadas nas três últimas.

### Pipeline (8.3, 8.4)
As oito etapas (Capture, Dataset, Features, Labels, Hypotheses, Validation, Execution, Live) vêm de `PipelineService`: READY→complete, RUNNING/PARTIAL→current, PENDING/MISSING/UNKNOWN→pending, LOCKED/BLOCKED→locked (hachurada), FAILED→failed. Nada é "complete" para combinar a referência: vazio completa zero etapas; Validation e Live são sempre locked. 1440/1600 mostram só os nomes; 1920 mostra o resumo real (`Running`, `34 sessions`, `34 / 34`, `0 / 4 completed`, `Locked`, `Pending`). Um recorder desconhecido não derruba a tela (`StageState.valueOf` era frágil).

### Sessions (8.5)
Uma célula por sessão real (`SessionInfo.overall()`): complete, capturing, failed ou pending. A sessão ativa é uma célula CAPTURING contada uma vez (substitui a sua entrada ou é acrescentada). Nenhum 34 fixo. Só a célula ativa tem movimento (uma animação que acompanha a célula).

### Gates (8.11, 8.17)
O guard mostra três tratamentos: TRAIN aberto (verde, só se a partição real é TRAIN), VALIDATION trancada (vermelho, cadeado), FINAL_HOLDOUT selada (hachura, escudo). Nenhum é botão. "Next allowed step" reflete `PipelineService.currentStage()` e o botão só navega (o roteador decide). `ResearchGateTest`: sem acesso a jobs/processos/arquivos no pacote, monitor só `start/stop/refresh`, fixtures em memória, nenhum clique abre gate nem altera o snapshot. `Snapshot.validationStatus/finalHoldout` são `final`.

### Estados reais (8.6, 8.12)
- Overview vazio (backend sem projeto): dataset N/A, KPIs N/A, "Session states unavailable", pipeline sem etapa completa. "No warnings" quando não há aviso (a tela antiga imprimia "0 WARNINGS"); avisos e jobs com falha vêm de dados reais.
- Capture: só os estados do monitor (RUNNING, STALE, STOPPED, UNKNOWN). Eventos, arquivos, retry/recovery, continuidade de sequência, gaps, drift e schema são "Not reported", nunca zero. A timeline da referência diz "None recorded" para rotation/retry/failure/recovery; o monitor não tem histórico, então V2 diz "Not reported". O estado RECOVERING da referência não existe no monitor e não foi inventado.

### Motion e atualizações (8.8, 8.9, 8.16)
RUNNING = uma animação (barra do recorder no pipeline, célula ativa, marcador "now" do Capture). `ResearchOverviewTest`/`CaptureScreenTest`: 20 ciclos RUNNING/IDLE sem acumular, 0 fora de RUNNING, 0 ao parar; FULL/REDUCED/OFF terminam no mesmo estado; 150 atualizações reutilizam os mesmos nós (pipeline, faixa, painéis), poll idêntico não toca nada. O detalhe do Capture é construído uma vez (a tela antiga reconstruía a grade a cada 5 s).

### Timers e Jobs (8.13)
Capture: monitor e timer de 1 s só existem com a tela visível e admin autorizado (escondida, sem sessão ou descartada: zero; 20 ciclos sem acumular). `JobsViewLifecycleTest` (novo, `JobsView.dispose()`): nunca exibido/escondido = parado, visível = um, descartado = nenhum.

### Responsivo e medidas (8.15)
`ResearchOverviewTest`/`CaptureScreenTest` conferem a referência em 1440: header 88,70,1332,82 · guard 88,166,1332,92 · pipeline 88,272,1332,108 · coluna esquerda 88,394,958 · direita 1060,394,360; Capture: header 82 · process 88,166,462,116 · session 564,166,462,116 · growth 88,296,462 · storage 564,296,462 · coluna de integridade 1040,166,380 · timeline 88 de largura 938 (280 de altura). 1920: coluna lateral 420 (Overview) e 460 (Capture), faixas de sessões de 44 px, lanes da timeline de 60 px. 1280×760: as telas rolam em vez de cortar (ScrollPane V2). A timeline mantém a altura do conteúdo (não estica).

### Performance (`docs/qa/step8/performance.txt`, `ResearchPerfQa`)
Overview: update p50 0,2–0,4 ms (máx 2,8 ms), 264 nós constantes, 2 loops (RUNNING), poll idêntico 0,004–0,02 ms. Capture: show+timers p50 0,14–0,32 ms (p95 ≤ 1,1 ms), 218 nós constantes, 1 loop e 1 timer em RUNNING; 0 e 0 após `stop()`.

### QA
- App real (`ResearchQa`, `docs/qa/step8/`): Overview real vazio em 1440/1920; fixtures isoladas (running, ready/idle, failed session + warning, labels incompletos) em 1440/1600/1920/1280; Capture running/stale/stopped/unknown. Referências renderizadas em `reference-*`.
- Regressões (cópia congelada das classes, sem outra carga): `ShellNavigationQa` 37/37 em FULL, REDUCED e OFF; `AuthFlowQa` sem falhas nos três modos; os testes de Trading e da fundação seguem verdes (439 no total).

### Diferenças
| Referência | JavaFX | Paridade | Motivo |
|---|---|---|---|
| Geometria 1440 (Overview e Capture) | idêntica | EXACT | testes de geometria |
| 1600 (sem referência) | resumos de etapa ocultos, colunas laterais 360/420 | NEAR | a referência só define 1440 e 1920 |
| Capture: 3 linhas em Growth | 4 (acrescenta Captured data real) | NEAR | dado real do monitor |
| "None recorded" na timeline | "Not reported" | INTENTIONALLY PRESERVED | não há histórico: zero seria afirmação falsa |
| Process state com RECOVERING | RUNNING, STALE, STOPPED, UNKNOWN | BACKEND UNAVAILABLE | o monitor não reporta RECOVERING |
| Badges NOT REPORTED em caixa | texto simples em caixa alta | NEAR | reuso do componente de linha |
| Tiles do guard com ícone check/cadeado | unlock/lock/shield do catálogo nativo | NEAR | catálogo de ícones existente |
| "1 WARNING" fixo do mock | contagem real ("No warnings" quando 0) | INTENTIONALLY PRESERVED | sem dado inventado |
| Uptime "1269 s" | `HH:MM:SS` do monitor | NEAR | formato já usado pelo app |

### Limitações conhecidas
1. Sem backend de projeto, Overview só mostra o estado vazio real; os demais estados foram verificados com fixtures de QA.
2. Telas de Research fora de Overview/Capture continuam legadas (ver fronteira).
3. A etapa "Dataset" usa o estado do checkpoint (como a tela antiga); não há estado de dataset separado.
4. As células de sessão e a barra do pipeline navegam ao clicar (comportamento herdado); só abrem telas somente leitura.
5. Smokes legados citam ids antigos (REVIEW REQUIRED BEFORE STEP 14).
6. Repetir o stress de navegação final no Passo 14 (item aberto do Passo 7).

## Passo 9 · BYX (modo implementação: validação direcionada)

Auditoria curta: o fluxo do BYX já estava claro (4 Views legadas sobre `ctx.byx*`), então não houve documento de auditoria separado. Nenhuma DEVNET, nó, broadcast, assinatura, chave privada, transferência ou saldo inventado.

| Peça | Conteúdo | Teste |
|---|---|---|
| `panel.v2.Kit` | blocos compartilhados dos passos 9–12 (página, painel, cabeçalho, linha, faixa de ambiente); `screens.css` | — |
| `panel.byxview.ByxData` | fonte somente leitura; adaptador `panel.app.ByxDataAdapter` sobre os serviços existentes | `byxPackageIsReadOnly` (varredura de fonte) |
| `NetworkScreen` + `NetworkModel` | AWAITING NODE, SYNCING, HEALTHY, STALE, DEGRADED, OFFLINE, IDENTITY MISMATCH; chain id, identidade, altura, bloco, frescor, sync, ambiente; "—" quando o nó não informa; hash/txs "Not reported" | `networkStatesFollowOnlyTheSnapshot`, `networkLoopOnlyExistsWithRealActivityAndVisibility` |
| `WalletScreen` + `WalletModel` | NOT LINKED, CONNECTING, LINKED, LOADING, ERROR, UNAVAILABLE; saldo e tier só com carteira verificada e cadeia confiável | `walletStatesNeverInventALink` |
| `BenefitsScreen` + `BenefitsModel` | tier real, recursos classificados (HOLD_TO_UNLOCK com política TEST real; REFERENCE ONLY para Bot Controls e Premium Research), "BYX never grants", pagamento e gás como estado lido | `benefitsClassifyDemonstrativeFeaturesAsReferenceOnly` |
| `TreasuryScreen` + `TreasuryModel` | REAL VERIFIED / TEST / PAPER / MANUAL_UNVERIFIED em faixas separadas; nenhum total, nenhuma conversão BYX/USD | `treasuryKeepsTheFourClassesApartAndComputesNoTotal`, `treasuryLateResultAfterHideIsDiscarded` |

### Movimento e ciclo de vida
Rede: os anéis respiram só em AWAITING NODE e SYNCING (um loop); HEALTHY estável; OFFLINE/STALE/DEGRADED/IDENTITY MISMATCH sem movimento; escondida/descartada = 0 loops e 0 timers. Wallet/Benefits/Treasury: timer só visível; resposta tardia depois de esconder é descartada (geração).

### Escopo somente leitura — decisões
- **Fora do V2 (serviços e testes mantidos):** criar intenção de pagamento, confirmar pagamento, pedir/revogar gás (broadcast de feegrant). O V2 mostra só o estado lido. Reposicionado, não removido do código: `ByxPaymentService`, `GasSponsorshipService`.
- **Vincular/desvincular posse da carteira** (prova externa; sem fundos, sem assinatura no app) segue na View legada `t-wallet-verify`, aberta por "Manage verification". **LEGACY / NO V2 REFERENCE.**
- **Configuração LOCALNET** (nó de leitura, só admin, só sessão) vive no painel "LOCALNET connection" da Network; sem admin mostra PERMISSION REQUIRED.
- Views legadas removidas: `ByxNetworkView`, `ByxBenefitsView`, `ByxTreasuryView`.

### QA visual
`ByxVisualQa` (manual; fixtures só em memória): `docs/qa/step9/` em 1440/1600/1920 (Network awaiting/healthy, Wallet sem vínculo/vinculada, Benefits, Treasury vazia/TEST+PAPER).

### Diferenças
| Referência | JavaFX | Paridade | Motivo |
|---|---|---|---|
| Recent blocks: 3 linhas | 1 linha (último bloco observado) | INTENTIONALLY PRESERVED | o nó só informa o último bloco; histórico seria inventado |
| Hash/Txs | "Not reported" | BACKEND UNAVAILABLE | gateway não lê |
| Botões Verify ownership / Unlink | "Manage verification" → fluxo legado | NEAR | ver acima |
| Intent stepper do pagamento | estados fixos, "Active" só com pagamento TEST ativo | REFERENCE ONLY | criação fora do escopo |
| Treasury: fonte esperada por categoria | "NOT CONFIGURED" sem ativo | INTENTIONALLY PRESERVED | não declarar fonte sem ativo |

### Findings para o Passo 14
1. Smokes legados (`RedesignVisualSmoke`, `FidelityProductionSmoke`, `MotionParitySmoke`, `ByxVisualSmoke`) ainda citam as Views BYX antigas por reflexão.
2. A atualização de saldo da carteira usa o `ByxBenefitsService.refresh`, que exige o nó LOCALNET: sem nó, Wallet fica CONNECTING/UNAVAILABLE (verificado só com stubs).

## Passo 10 · Account

`panel.accountview` (+ `AccountDataAdapter`, `AppInfo`). Rail Account agora tem as cinco rotas reais (`t-profile`, `t-security`, `t-sessions`, `t-notifications`, `t-account-activity`) e Settings no pé; os itens "arrives in step 10" foram removidos. Views legadas removidas: `ProfileView`, `TraderScreens.Settings`.

| Tela | Fonte real | Sem backend |
|---|---|---|
| Profile | usuário, e-mail/telefone mascarados, papel, criação, último acesso, status | nome de exibição: "Not provided by the API". Edição real = contatos (e-mail e telefone, senha atual, revoga confiança/sessão admin); erro mantém o texto e mostra banner; nunca "salvo" fingido |
| Security | troca de senha (serviço real, diálogo camada 70, senha nunca retida), verificação de admin (estado real dos provedores), dispositivos confiáveis, auditoria local | Authenticator app, Recovery: NOT CONFIGURED. Nenhum segundo fluxo de 2FA |
| Sessions | sessão local atual (início, método) e dispositivos confiáveis reais (só admin; revogar pergunta e só atualiza após o serviço confirmar) | outras sessões: UNAVAILABLE (sem serviço de sessões) |
| Notifications | — | UNAVAILABLE (e não EMPTY): INTENTIONALLY PRESERVED, sem itens demo |
| Activity | auditoria de segurança local (agrupada por dia; resultado só quando o nome do evento o diz, senão "Recorded") | categorias Account/Research/BYX não existem |
| Settings | motion FULL/REDUCED/OFF, densidade, ícones animados (o sistema de motion real; persistem em `settings.properties`) | General, Trading (só 1m), Research, BYX, Notifications, Accessibility, About: linhas informativas ou LOCKED/UNAVAILABLE/PERMISSION REQUIRED, sem controle |

### Edição, barra de salvar e navegação
`View.hasUnsavedChanges()/discardChanges()` (novo, padrão falso). O gate do `PanelApp` pergunta ("Discard changes?", foco em Cancel) antes de sair de Profile (edição) ou Settings (rascunho ≠ salvo); a rota só muda depois da confirmação e cancelar limpa o pedido pendente (`ByxOverlayHost.confirm` ganhou `onCancel`). Settings usa rascunho separado do salvo, "UNSAVED" por linha, barra de salvar na camada 30 e falha de gravação mantém o rascunho. Live trading é linha LOCKED sem controle; DEVNET aparece indisponível; Light é COMING SOON.

### Testes e QA
`AccountScreensTest` (9): resultado de auditoria, agrupamento, Profile sem fake-save e edição suja, falha de salvar, Notifications UNAVAILABLE, Sessions sem dispositivos fictícios, Activity real/vazia/indisponível, rascunho/salvar/descartar de Settings, nenhuma chave de live trading em nenhuma categoria, Security sem segundo fator. Regressão de navegação (tocado): `ShellRouterTest`, `Shell*Test`, `CommandPaletteTest`, `ViewEnterLifecycleTest`, `SessionReturnTest`, `ByxOverlayHostTest` e uma execução curta do `ShellNavigationQa` no app real (idle de 5 s, FULL: 0 falhas). Capturas: `docs/qa/step10/` (1440/1600/1920).

### Diferenças
| Referência | JavaFX | Paridade | Motivo |
|---|---|---|---|
| Edit profile: display name | contatos reais | NEAR | não há display name na API |
| Motion como seção própria | dentro de Appearance (Accessibility aponta para ela) | NEAR | lista de categorias do passo |
| Language / datas / fuso | linhas informativas | BACKEND UNAVAILABLE | sem mecanismo de preferência |
| Sessions: 5/6/7 colunas | sessão atual + painel UNAVAILABLE | BACKEND UNAVAILABLE | sem serviço de sessões |
| Follow system motion, focus ring, contrast | Passo 13 | — | — |

### Findings para o Passo 14
1. Guard de saída testado no app real só por regressão de navegação; um QA dirigido do diálogo "Discard changes?" (Profile/Settings) fica para o Passo 14.
2. Botões About / What's new de Settings apontam para rotas do Passo 11.

## Passo 11 · Help

`panel.helpview`, contexto `HELP` do shell (não é workspace; chip HELP no breadcrumb), rail Help com FAQ, Support, Diagnostics, About, Overview e What's new; Terms, Privacy e Shortcuts por links. Conteúdo em `/content/*.json` (faq, legal, whats-new, onboarding, system-messages copiados do handoff): trocar o arquivo muda a página sem código.

| Tela | Classificação | Notas |
|---|---|---|
| FAQ | PLACEHOLDER_CONTENT (selo) | categorias, busca, acordeão por teclado (Enter/Space, ↑/↓, Home/End, ↓ da busca entra na lista), sem resultados com "Clear search" e "Report a problem"; resultados da busca global levam à pergunta (`open(id)`) |
| About | PLACEHOLDER_CONTENT | só fatos da especificação (Cosmos SDK, ubyx → BYX 6 casas, LOCALNET); sem licenças, certificações, regulação, clientes, parcerias ou números; versão/build de `AppInfo`; "Copy version info" |
| Product overview | UI_ONLY | pilares TRADING/RESEARCH/BYX com navegação real (Research segue atrás da verificação de admin) |
| Help & Support | UI_ONLY | FAQ, Diagnostics, Shortcuts; Documentation UNAVAILABLE; Contact support NOT CONFIGURED; **Report a problem: UNAVAILABLE + DEMO ONLY · NOTHING IS SENT** (sem formulário, nada sai do app) |
| Diagnostics | allow-list | `DiagnosticsReport.FIELDS` (versão, build, Java, JavaFX, SO, ambiente, backend, feed, captura, research, nó BYX, carteira, autenticação, motion, fonte de dados, densidade); campo fora da lista é recusado; valores com e-mail/telefone/palavras de segredo são redigidos; o texto copiado é exatamente o preview |
| Terms / Privacy | **LEGAL_PLACEHOLDER** | selo + banner "not an approved legal document"; corpo vem de `legal.json`; sumário leva à seção |
| Shortcuts | UI_ONLY | `ShortcutRegistry` (registro único); página e diálogo `?` (não abre digitando num campo nem com outra camada); só atalhos que existem |
| What's new | PLACEHOLDER | changelog de design do handoff, selo do status |

### Acesso público
O roteador é a autoridade: sem sessão, `ShellRoutes.PUBLIC` = About, FAQ, Help, Terms, Privacy; qualquer outra rota (Trading, Research, BYX, Account, Security, Diagnostics, Overview, Shortcuts) é DENY. O rodapé do login deixou de ter itens "arrives in step 11" e abre essas páginas num `PublicHost` (sem rail, seletor, busca, notificações, menu, dock; botão Sign in). Rotas `h-*` não são Research (`ShellRoutes.isResearch`), então o bloqueio de sessão de admin não as afeta. Menu do usuário: Keyboard shortcuts (diálogo), Help, About BYX apontam para as telas V2; a busca ganhou entradas HELP e uma por pergunta do FAQ.

### Testes e QA
`HelpScreensTest` (10): conteúdo e filtro, legais placeholder, allow-list/segredos/preview exato, FAQ (busca, acordeão, sem resultados, deep link), Support sem envio, About sem afirmações extras, registro de atalhos × shell, contrato público e PublicHost sem chrome. Regressão (gate de rota e rodapé de auth tocados): `Shell*Test`, `Auth*Test`, `LoginControllerTest`, `SessionReturnTest`, `CommandPaletteTest` e QAs do app real (`ShellNavigationQa` idle 5 s FULL e `AuthFlowQa` FULL: 0 falhas). Capturas em `docs/qa/step11/`.

### Diferenças
| Referência | JavaFX | Paridade | Motivo |
|---|---|---|---|
| FAQ: destaque do termo buscado | só filtra | NEAR | sem texto rico no Label |
| FAQ EXPANDED: lista de categorias 240 + "Still need help" | chips em todos os tamanhos | NEAR | reorganização responsiva fica para o Passo 14 |
| Report a problem: formulário (demo) | UNAVAILABLE | INTENTIONALLY PRESERVED | pedido: nada é enviado, nada finge envio |
| Terms/Privacy: realce do sumário pela rolagem | clique no sumário rola até a seção | NEAR | — |

### Findings para o Passo 14
1. O modo público foi validado por teste de contrato + `PublicHost`; um QA dirigido (login → FAQ → Sign in) no app real fica para o Passo 14.
2. "Open-source notices" aparece como NOT CONFIGURED (sem texto).

## Passo 12 · System + onboarding

`panel.systemview` (+ `SystemStatusModel`, `RecoveryTracker`, `FirstRunModel`, `StartupModel`, `RegionMatrix`, `ErrorArchitecture`), contexto `SYSTEM` (não é workspace; rail só com Settings). Nenhum session manager novo: o fluxo de sessão expirada do Passo 6 foi reutilizado sem mudança.

| Peça | O que faz | Real × inventado |
|---|---|---|
| First Run | `FirstRunModel`: FIRST_INSTALL, RETURNING_USER, VALID_SESSION, NO_SESSION, SESSION_EXPIRED, a partir de `auth.firstRun()` + `onboardingCompleted` + sessão real | só leitura de estado real |
| Welcome | `auth:welcome` (só na primeira instalação, antes de criar o administrador): três linhas objetivas (Trading, Research, BYX), sem alegações comerciais | rota do mesmo `ShellRouter` (permitida só com `firstRun()`) |
| Onboarding | diálogo persistente (camada 70) de 6 passos com conteúdo de `onboarding.json` (PLACEHOLDER_CONTENT); Back/Next/Skip/Finish, Esc = Skip, fundo não fecha; cinco Next rápidos terminam no passo 6 com um passo ativo | grava só `onboardingCompleted` e `primaryWorkspace` em `settings.properties` (o mecanismo local que já existia); a classe nem referencia trading, carteira, DEVNET, gates ou fundos (varredura de fonte no teste). Reabrir: Settings › General › "Replay onboarding" e a busca |
| System Status | `sys-status`: Backend, Market feed, Capture, Research, BYX node, Wallet, Authentication com estado, razão e idade; ilegível = UNKNOWN; nada configurado = UNAVAILABLE esperado (neutro); linhas atualizadas no lugar, timer só visível | Retry só para Backend (refresh) e nó BYX (leitura real) |
| Status dock | continua resumo; Backend, Capture (sem admin) e MODE agora abrem System Status; atualizar status não navega nem recria | — |
| Connection recovery | `RecoveryTracker`: CONNECTED, DISCONNECTED, RECONNECTING (só quando o serviço reporta), RESTORED (3 s, um toast), RETRY FAILED (após retry real). Perda do backend levanta a faixa global de 36 px (`ByxShell.setGlobalBar`); um serviço que nunca esteve de pé não "caiu" | sem "tentativa n de 3" (nenhum serviço informa) |
| Error architecture | `ErrorArchitecture` (9 padrões; só AUTH e UNEXPECTED bloqueiam) + `ErrorPatterns` (erro inline de componente, faixa global, permissão); erro de campo = `ByxField`, região = `ByxRegion`, aviso = `ByxBanner` | nada de modal para todo erro |
| Region states | `RegionMatrix` lida de `system-messages.json` (19 regiões) | Notifications: a matriz de referência não tem UNAVAILABLE; o produto mantém UNAVAILABLE de propósito (sem backend ≠ vazio) |
| Page unavailable | `sys-unavailable`: rota interna desconhecida mostra a tela (Return só com rota anterior; Go to default workspace via roteador); nunca redireciona sozinha. Antes uma rota t-/h- desconhecida era descartada em silêncio | — |
| Permission required | padrão `ErrorPatterns.permissionRequired` (nunca concede). Pedidos de Research sem admin continuam DENY + toast com a rota intacta (INTENTIONALLY PRESERVED: Passo 5/6) | — |
| Unexpected error | handler só da thread FX: detalhe no log; a UI mostra o fallback (Something went wrong, código `ERR-XXXXXX` derivado da classe e da hora, Retry só quando seguro, Open diagnostics, Return); nunca stack trace, token ou caminho | — |
| Startup | `StartupModel` (800 ms, 160 ms de fade, token: o pedido mais novo vence) e `StartupScreen` mínima. Não há espera real hoje (a composição é síncrona): o app abre normal, sem splash | componente e contrato prontos e testados, não exibidos |

### Gate e navegação
`ShellRoutes.isResearch` agora exclui `sys-`; abrir a rota de onboarding (`sys-onboarding`) é diálogo, não rota. A rota inicial vem do workspace principal escolhido (Trading por padrão; Research só para admin, com a verificação normal; BYX), e a sessão expirada continua voltando à rota capturada.

### Testes e QA
`SystemScreensTest` (9): estados sem inventar saúde, recuperação só com transições reais, First Run/Startup, matriz de regiões, erros sem vazamento, onboarding (6 passos, Esc, sem efeitos colaterais), Page unavailable/fallback seguros, System Status no lugar e sem loops escondidos. Regressão (gate, dock, entrada de auth e rotas tocados): 157 testes direcionados (`Shell*`, `Auth*`, `Dock*`, `SessionReturn`, `CommandPalette`, `ByxOverlayHost`, `Account/Byx/Help`), `ShellNavigationQa` (idle 5 s, FULL) e `AuthFlowQa` (FULL): 0 falhas. Os harnesses de QA do app real passaram a gravar `onboardingCompleted=true` para o onboarding não abrir por cima do roteiro. Capturas em `docs/qa/step12/`.

### Findings para o Passo 14
1. Onboarding, fallback inesperado e faixa global foram verificados por componente/teste; um QA dirigido no app real (primeiro login → onboarding → rota inicial; falha forçada do backend → faixa e restauração) fica para o Passo 14.
2. Welcome/First Run depende de `firstRun()` verdadeiro: só exercitado manualmente com um home limpo.
3. Dock: o realce da linha do componente clicado em System Status (âncora #id) não existe; o dock só pede a rota.
