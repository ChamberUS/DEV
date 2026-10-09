# Package C2 — mapa pré-implementação

2026-10-09. Design aprovado pelo usuário; implementação autorizada. HEAD2dd12ca65072d46599449a25932ddce881d14746, branch feature/byx-ui-redesign-v1. Preflight em docs/qa/package-c2. Nenhum reset/stash/clean/rebase/commit/push.

## Baseline e proteção

C1 aprovada e ainda dirty: preservar suas alterações de21 arquivos existentes e13 novos, além das31 alterações tracked externas em iaos-web. Inventário integral modified/untracked em preflight-git-status.txt;3677 hashes de fontes/protected files registrados antes de mudanças. Histórico A/B/RC1 e candidatos antigos não serão substituídos. Service/packaging/signer/custody não são destinos de edição.

## Arquitetura e mudanças planejadas

| Área atual | Fonte / folha | Mudança C2 |
|---|---|---|
| Aplicação central de CSS | panel.design.ByxTheme, DesignTokens; resources/design | Estender ByxTheme com modo observável, paleta semântica115 tokens, typed Color access, resolução FX-thread e registro fraco de roots/scenes. JSON aprovado empacotado; cores não duplicadas em telas. |
| Tokens V2 | panel/v2/tokens.css | Preservar DARK; folha/token overrides LIGHT com aliases existentes bg0–3, text, semantic, acentos, tints. Nenhuma segunda implementação de tela. |
| Shell, popovers e controles | v2/shell.css, controls.css, typography.css | Remover paints literais com meaning semântico; hover/focus/disabled/text/inverse e essential borders. Preservar layout e densidade. |
| Login e marca | authview/BrandPanel; v2/auth.css | Canvas/mesh/bars/glows atualmente pintados por Color literais. Resolver via palette e redraw sem reiniciar motion/model. Login/2FA/error recebem tokens. |
| Trading | v2/trading.css; ui/trader/CandleChart; tradeview | Ink Regions CSS existentes no Canvas precisam disparar redraw em revisão; demais chart/custom paints/depth auditados. Nenhuma alteração de dataset, assinatura, cálculo ou ordem. |
| Research | v2/research.css; legacy panel.css/byx.css e ui views | Gates/status/heatmaps/tables/logs/dialogs usam tokens. Não alterar Research/H001/H002 ou processar dados científicos. |
| Legacy hosts | CSS scoped no host nativo | Aliases legados bg/panel/panel2/text/muted/ok/bad/warn/info/purple para nova paleta, com prioridade correta e DARK preservado. Auditar inline styles sem modificar funções. |
| Home/BYX/Account/Help | v2/screens.css e views | Surface/readonly/availability/placeholder/selection/tooltip/palette completos. |
| Mascote B | shell/avatar/MascotAvatar, AvatarPaths, AvatarMotion, Operations; shell.css | Corpo/olhos invariantes, container/boundary e ring paints por tema. Sem mudar geometria36px/timing/tokens/timers/listeners. |
| Mascotes legados/dev | mascot/MascotStage, HintBubble/Gallery; ui/motion | Auditar hardcodes para layouts acessíveis; não importar GIF nem criar nova animação. |
| Appearance | accountview/SettingsScreen | Seletor nativo Dark/Light/System com seleção imediata independente de draft/read-only preference gate. SYSTEM inativo com motivo. |
| Locale | Strings / LocaleView / Presentation e resources/panel/i18n | Reusar C1; somente novas appearance.* produto. Nenhum rebuild do dicionário ou mudança de formatação financeira. |
| Lifecycle | app/PanelApp | Reset Dark nos limites de sessão/conta aprovados, locale independente. Style/paint switch não navega ou toca Auth/Service. |
| Diálogos nativos | ui/Dialogs, ui/auth/UsersView, WalletPane, Popup owners | Aplicação de tema a roots/Scene separados; update mantém focus/modalidade/inputs/actions. |

Preflight scanner detectou20 arquivos com207 matches (inclui comentários e paletas legacy). Lista linha/paint em hardcoded-paint-audit-before.json. Contagem não é checklist de bugs: transparência/white eyes/art invariantes podem ser deliberados.

## Rotas design → JavaFX

Home→t-home; Desk/chart/book/feed→t-desk; Markets→t-markets; Portfolio→t-portfolio; Orders→t-orders/t-positions; Bot→t-bot; Risk→painéis Desk/t-performance; Network→t-byx; Chain→t-chain-data; Wallet→t-wallet; Benefits→t-benefits; Treasury→t-treasury; Help→h-faq/h-help; Research→overview; Checkpoints→dataset/sessions (sem nova rota inventada); Features→features; Validation→validation; Capture→capture; ByxNetwork/Admin→Research views/settings e t-byx; System→sys-status; Settings→t-settings; Profile→t-profile; Security→t-security; Sessions→t-sessions; Notifications→t-notifications/popover; Login/Registration unavailable/2FA→AuthScreens; Welcome→sys-onboarding/WelcomeDialog.

Também auditar rotas nativas adicionais: t-strategies, t-signals, t-performance, t-activity, t-account-activity, labels, hypotheses, execution, paper, live, jobs, logs, users, settings Research, h-diagnostics/h-about/h-overview/h-whats-new/h-terms/h-privacy/h-shortcuts/sys-unavailable. Rotas local-QA de mascot/tx/wallet verification não serão habilitadas em DEFAULT. A matriz executada final deverá enumerar as rotas efetivas.

## SYSTEM / persistência

Runtime Java21 + JavaFX21.0.5. API Platform21 não tem aparência observável (fonte: https://openjfx.io/javadoc/21/javafx.graphics/javafx/application/Platform.html). Nenhum adapter OS qualificado encontrado na arquitetura inspecionada. SYSTEM permanece indisponível; não usar subprocess polling ou snapshot único como sync. Auditoria macOS será registrada com documentação oficial e API local.

AccountData.Prefs não contém theme, savePrefs é protegido e preferencesEditable=false no DEFAULT. Não criar store paralelo: mínimo aprovado é sessão somente; resets login/logout/restart/account replacement. Motion/density/draft continuam com autorização existente.

## Verificação planejada

Testes focados token completeness/CSS/resource/engine/FX thread/Scenes/popups/data paints/redraw/state/focus/locale/role/authority/motion/lifecycle. Regressão Panel completa com contagem nova; flows JavaFX EN/PT × DARK/LIGHT ×3 tamanhos, Compact/Comfortable/FULL/REDUCED/OFF representativos. Screenshots nativas pares de mesma fixture. Candidato local C2 em nova pasta com pipeline existente; smoke assinatura/resources/default/startup/sessão/reset. Não rodar suites custody/fencing caras sem mudança nesses componentes.

REAL USER KEY NOT AUTHORIZED; REAL TX DISABLED; BROADCASTS0; REAL FUNDS0; REAL USER WALLETS0; ETHUSDT RESEARCH UNTOUCHED. Capture checks só ps/stat.

## Decisões verificadas durante a implementação

O engine usa pseudo classes `:byx-theme-dark`/`:byx-theme-light` na raiz; as listas estruturais de classes (incluindo `LegacyHost`) permanecem exatamente iguais. Hosts legados recebem somente as duas folhas centrais de paleta/overrides depois das folhas locais, herdando a pseudo classe do ancestral. Não há `applyCss()` eager em toda cena cacheada: o próximo pulse resolve CSS antes de layout e redraw.

`Candle` fornece OHLC e horário, sem volume por candle. Barras de volume não podem ser fabricadas; volume24h continua no header existente. O crosshair e tooltip OHLC são overlays locais de apresentação. Não alteram candles, cálculos, conexão ou risco. Escala plana recebe padding visual para evitar coordenadas NaN.

` t-wallet-verify ` existe como view legada de compatibilidade; abrir sua apresentação não habilita a capacidade DEFAULT. Mascot gallery é uma view de desenvolvimento criada lazy e escondida da palette DEFAULT. Transaction Lab permanece ausente do JAR DEFAULT. A QA enumerará estas distinções sem habilitar caminhos financeiros.


Native cascade qualification: use `.root:byx-theme-light` / `.root:byx-theme-dark` anchors. An unqualified pseudo selector resolved tokens but lost JavaFX rule precedence on nested real Desk controls. `PackageC2ResolvedControlsTest` verifies native card, axis ink and avatar boundary after DARK → LIGHT → DARK; structural class lists remain unchanged.

Minimum-width qualification: Settings' generic `USE_PREF_SIZE` minimum belongs to segmented controls. Native ThemeSelector instead retains min-width zero so its existing wrapText labels fit the content column at 1100px. Tests assert selector's actual Scene bounds after each theme/locale switch. Body typography is reused for native appearance choices. LIGHT legacy decorative Gaussian effects retain geometry and now use approved `shadow.elevated` ink.

Native scrollbar probe: actual LIGHT thumb previously resolved to #CCD5E2 (subtle separator), which is inappropriate for this interactive indicator. LIGHT now resolves #697A92 (essential boundary); real Desk regression asserts native thumb paint. Original DARK #2A3144 is retained and classified as an existing low-contrast baseline limitation rather than counted as an accessibility pass.
