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
| 7 | Trading | IN PROGRESS | Live OFF. Auditoria em `docs/BYX_V2_TRADING_AUDIT.md`. Ver "Passo 7" abaixo |
| 8 | Research + Capture | TODO | VALIDATION LOCKED / FINAL_HOLDOUT SEALED |
| 9 | BYX | TODO | LOCALNET/TEST, sem fundos |
| 10 | Account | TODO | |
| 11 | Help | TODO | |
| 12 | System + onboarding | TODO | Diagnóstico com allow-list |
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

Auditoria: `docs/BYX_V2_TRADING_AUDIT.md`. Escopo: o Trading Desk. Live trading continua OFF; nenhum dado de demonstração entra.

| Checkpoint | Conteúdo | Teste |
|---|---|---|
| 7.1 | Auditoria do Trading atual | — |
