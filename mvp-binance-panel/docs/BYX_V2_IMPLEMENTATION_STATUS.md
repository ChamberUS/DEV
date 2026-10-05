# BYX V2 — implementation status (JavaFX)

Legenda: DONE · CODED (escrito, aguardando `mvn test` no Mac) · TODO

| # | Passo | Status | Notas |
|---|-------|--------|-------|
| 1 | Baseline / auditoria do app atual | DONE | Cópia do working tree; `.git` não visível ao sandbox → baseline `a3368a2` no repo local de trabalho |
| 2 | Corrigir navegação / lifecycle / motion | DONE | `Navigator`, `View.onShow/onHide`, `MotionService`, Orb/Bot/Icon, timers, dock. Validado no Mac (testes + manual) |
| 3 | Congelar fundação de roteamento | FROZEN | `0440d35`. `NavigatorTest` + `FoundationLifecycleTest` verdes antes de cada checkpoint seguinte |
| 4 | Design foundation (tokens, tipografia, componentes) | DONE | Pacote `panel.design`, tema `/panel/v2/*.css`, galeria `./run-gallery.sh` (DEV ONLY). Ver "Passo 4" abaixo |
| 5 | Shell (rail, top bar, dock, breakpoints) | TODO | |
| 6 | Auth (Login, First run; demais BACKEND_REQUIRED/REFERENCE_ONLY) | TODO | Sem endpoint inventado |
| 7 | Trading | TODO | Live OFF |
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
