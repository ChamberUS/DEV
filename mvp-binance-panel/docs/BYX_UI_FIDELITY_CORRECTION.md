# BYX: correção de fidelidade visual e movimento — 2026-10-04

Implementação JavaFX nativa. Trading permanece OFF; Capture read-only; VALIDATION LOCKED e FINAL_HOLDOUT SEALED. Nenhum job, dataset real, SSH, DEVNET, broadcast, ordem, fundo ou push foi utilizado.

## Execução e referência

- Projeto executável: `/Users/buynnex-corp/dev/mvp-binance-panel`; branch inicial `feature/byx-ui-redesign-v1`, HEAD `0066a8c4abdf0e048c026167483bb80a4f427319`. Seis commits anteriores preservados: db34f8a, 256631a, 693d82b, ccf0337, 878befd, 0066a8c. Alterações em iaos-web e mvp-binance preservadas.
- Lançamento normal pelo `run.sh` executado por 10 s após startup, na entrada de autenticação, sem fixtures e com perfil temporário vazio (REAL/COMFORTABLE/FULL; cliPath=/usr/bin/false). Log `normal-run-verified.log`.
- `run.sh`: JDK 21.0.12+1; Maven 3.9.9; `javafx:run`; PanelApp/target/classes; JavaFX 21.0.5. CSS `/panel/panel.css` seguido de `/panel/byx.css`. Logs de diagnóstico registram origem, hashes, fontes, escala e preferências sem credenciais.
- Configuração existente observada: FULL, COMFORTABLE, animatedIcons=true, fonte REAL. Não foi alterada. QA utiliza perfil temporário, FULL/COMPACT e depois COMFORTABLE; Scene lógica 1440×900, outputScale=2, sem transformação de zoom. Também 1600×1000 e 1920×1080.
- HTML local indicado pelo usuário: `mvp-binance/desing-reference/BYX-MVP Redesign.html`. SHA-256 `c794c07504392708c683dc084282a1af2853b152e6cabb2eb68a3c4069c748e8`, igual ao artefato indicado no guia. Todas as 14 pranchas foram extraídas/renderizadas; nenhuma apresentou erro JavaScript. O pacote separado BYX_REFERENCE_EVIDENCE não foi localizado/fornecido: a resposta à solicitação de caminho indicou o próprio HTML. Não se declara leitura do pacote ausente.
- Causas constatadas: árvore/layout divergentes, símbolo incorreto, containers e seletores CSS conflitantes, ausência dos alvos de espera, hierarquia Research diferente. CSS fonte/target anterior eram idênticos. A análise dos pixels durante os pulsos identificou também um callback de entrada após cancelamento (opacidade ficava intermediária) e a regra antiga `.skeleton` substituindo o gradiente na reaplicação de CSS. Corrigidos com controle de propriedade do callback e binding nativo do background ao offset; snapshots deixam de forçar CSS em cada frame. O smoke verifica painéis opacos durante a execução e o preenchimento animado real. Não há evidência para atribuir a diferença a build antigo ou modo reduzido persistido.

## Resultado por contrato

| Área | Especificação implementada | Aparência / movimento verificados | Classificação |
|---|---|---|---|
| Shell | Switcher único Trading/Research/BYX; rail completa; símbolo SVG correto; fontes 400/600; topbar compacta | Três dimensões, FULL/COMPACT e COMFORTABLE; conta e rotas adicionais preservadas | NEAR: rasterização e métricas JavaFX diferem do browser |
| Trading | Header só à esquerda; períodos no header; direita alinhada; 9+6 skeletons; símbolo 44; métricas/abas/posições compactas; bot sem avatar grande | View de produção, fixtures explícitas de espera, geometria e fases observadas | NEAR; deltas dos painéis principais até 1,77 px |
| Estado real do feed | Provider sem integração declara NOT_CONFIGURED; STALE/erro não exibem falsa espera | Verificação sem endpoints/datasets, atualizações incrementais mantêm a TableView | Adaptação necessária à disponibilidade real |
| Research | Header/guard/pipeline/KPIs/dataset/Sessions e coluna 330; hachuras; sessões baseadas nos estados reais e IDs únicos | Capture e sessão ativa pulsando em View real mesmo com estágio global IDLE; ausência de sessões não fabrica blocos | NEAR; maior delta de painel 2,09 px |
| Entradas | 500 ms, translateY 8→0; delays 0/60/120/180 por posição no pai, demais posições zero | Infraestrutura existente e regressão nativa; interrupção/retorno limpos | Contrato implementado; pixel/raster NEAR |
| Shimmer | 2200 ms, CSS ease, direção e gradiente da referência | 15 regiões da View Trading, fases/gradientes registrados em motion.csv | Movimento verificado na View com fixture WAITING |
| Breathing | 2600 ms ease-in-out, opacidade 1→0,45→1 | Símbolo, bot, sessão ativa, rede aguardando; Capture Now somente quando ativo | Movimento verificado nos nodes reais |
| Pipeline Capture | 2400 ms CSS ease, independente dos loops 2600 | Timeline real da linha Capture, não um node de teste | Movimento verificado na View |
| Login | Barras 92/76/60/44/30/18%, delays 120 ms, opacidades finais .9/.6/.4/.28/.18/.1 | Execução JavaFX real e sequência temporal | NEAR: texto/fontes nativas |
| Hover / tooltip | Hover 200 ms; tooltip 120 ms; foco com anel geométrico; reversões sem acumulação | Helpers e bindings exercitados; tooltip aparece por requestFocus na View | Ponteiro/teclado reais NOT VERIFIED: TCC macOS bloqueou Robot |
| Network / Capture / Treasury | Emblema/círculos; monitor 3 colunas; Now 18; quatro faixas sem soma de categorias | Capturas reais, loops de Network/Capture; detalhes adicionais acessíveis | NEAR e adaptações a estados ausentes |
| Wallet / Benefits / Settings / autenticação / palette | Tema compartilhado; palette 560/top110; serviços/permissões existentes | Smoke visual em três dimensões; autenticação/locks/accessos testados | NEAR; dados/controles adicionais preservados |
| Menus, diálogos, toast, palette e ciclos ausentes do HTML | Nenhum novo ciclo reivindicado como referência | Comportamento existente preservado | NOT SPECIFIED |

Serviços, modelos, autenticação, permissões e dados incrementais não foram substituídos. A única mudança no provider é declarar explicitamente a integração ausente. Funções extras continuam acessíveis por menus/contexto/command palette; controles de atualização também aceitam F5 onde o botão foi compactado. A infraestrutura MotionService/ReferenceMotion foi reutilizada; nenhum framework novo ou WebView.

## Evidências e limites

Diretório de entrega: `/Users/buynnex-corp/dev/mvp-binance/reports/ui-fidelity-20261004-verified`.

- `trading-before-reference-after.png`, `research-before-reference-after.png`: mesma área cliente 1440×900, ANTES no HEAD original, REFERÊNCIA HTML, DEPOIS. Contadores do mockup existem somente na fixture rotulada do teste, nunca na implementação.
- Overlays/diferenças e `geometry-comparison.json`: medidas dos containers, cores/fontes efetivas em `geometry.csv`. Diferenças de rasterização impedem afirmar equivalência pixel a pixel.
- `trading-javafx-pulses.mp4` e `research-javafx-pulses.mp4`: 10 s cada; Network/Capture 8 s. Sequências de Scene.snapshot em pulsos normais, ~8–9 fps, codificadas com timestamps reais. NÃO são gravações do desktop. `motion.csv` e `frame-times.csv` permitem verificar fases e duração, sem acelerar/reescrever os timelines.
- `video-motion-samples.csv`, `video-motion-comparison.json` e `motion-filmstrip.png`: comparação de pixels dos vídeos a 10 fps; estimação de período por ponto, sem alinhamento de fase e com resolução temporal de 0,1 s. Movimento visível confirmado após corrigir callbacks e gradiente.
- `trading-reference.mp4` e `research-reference.mp4`: reprodução local dos bundles originais, ~10 s. Referência e JavaFX usam tempo normal; não se reivindica sincronização frame a frame.
- `tooltip-programmatic-focus.png`: popup da implementação real, aberto por requestFocus. Não comprova hover físico ou Tab físico.
- A execução tentou JavaFX Robot; CGPreflightPostEventAccess=false, zero eventos mouse/teclado recebidos. Comparação por gravação desktop com interações reais NÃO EXECUTADA. CGPreflightScreenCaptureAccess também retornou false; a gravação desktop não estava disponível neste processo. Requer permissão macOS para captura/eventos e validação manual.
- Pranchas de catálogo/design system/overlays são referências de componentes, não novas rotas. Foram renderizadas; não se declara equivalência integral de cada composição de catálogo no aplicativo.

Aproximações restantes: antialiasing, letter-spacing e pesos efetivos de algumas linhas diferem do browser; hachuras usam gradientes nativos; anéis de foco e controles adicionais preservam a acessibilidade/funcionalidade JavaFX. As sequências anteriores à correção dos callbacks/gradiente foram preservadas, identificadas como substituídas e não compõem a entrega verificada.

## Validação executada

- Maven: 202 testes, zero failures/errors/skips. Regressões incluem provider sem valores fictícios, sessões únicas, motion cancellation e autenticação/proteções.
- Smoke visual: 46 capturas; três dimensões; fluxos de autenticação/locks/Capture read-only.
- Smoke motion final: 368 amostras nativas; modos FULL/REDUCED/OFF, reversões e cancelamento.
- Smoke de fidelidade de produção (aproximadamente 2 mil amostras de estilo/loop): sessões/skeletons reais da View, espera, STALE, tabelas incrementais, retomada de Views, janela oculta, navegação rápida e modos. Fixtures em perfil temporário; cliPath=/usr/bin/false; nenhum acesso a datasets reais.
- `ruff check src tests`: All checks passed! (repositório mvp-binance).
- `git diff --check`: sem erros de whitespace.

Não executados: jobs pesados, datasets reais, DEVNET (DEFERRED), SSH, broadcasts, ordens/fundos, push, gravação desktop e interações físicas bloqueadas por TCC. Não se marca aparência EXACT nem aprovação final do usuário.

## Arquivos criados/alterados

- `src/main/java/panel/adapter/ResearchModeTradingProvider.java`
- `src/main/java/panel/app/PanelApp.java`
- `src/main/java/panel/motion/ReferenceMotion.java`
- `src/main/java/panel/motion/icon/IconPaths.java`
- `src/main/java/panel/ui/ByxNetworkView.java`
- `src/main/java/panel/ui/ByxTreasuryView.java`
- `src/main/java/panel/ui/CaptureMonitorCard.java`
- `src/main/java/panel/ui/CaptureView.java`
- `src/main/java/panel/ui/CommandPalette.java`
- `src/main/java/panel/ui/Credits.java`
- `src/main/java/panel/ui/LedgerMark.java`
- `src/main/java/panel/ui/OverviewView.java`
- `src/main/java/panel/ui/PageView.java`
- `src/main/java/panel/ui/auth/AuthShell.java`
- `src/main/java/panel/ui/motion/SegmentedSwitch.java`
- `src/main/java/panel/ui/motion/UserMenu.java`
- `src/main/java/panel/ui/trader/TradingDeskView.java`
- `src/main/resources/fonts/JetBrainsMono-SemiBold.ttf`
- `src/main/resources/fonts/SchibstedGrotesk-OFL.txt`
- `src/main/resources/fonts/SchibstedGrotesk-Regular.ttf`
- `src/main/resources/fonts/SchibstedGrotesk-SemiBold.ttf`
- `src/main/resources/panel/byx.css`
- `src/test/java/panel/FidelityProductionSmoke.java`
- `src/test/java/panel/MotionParitySmoke.java`
- `src/test/java/panel/RedesignVisualSmoke.java`
- `src/test/java/panel/ReferenceMotionTest.java`
- `src/test/java/panel/TradingProviderTest.java`
- `scripts/ui_fidelity_smoke.sh`
- `THIRD_PARTY_ASSETS.md`
- `docs/BYX_UI_FIDELITY_CORRECTION.md`

## Execução manual

```sh
cd /Users/buynnex-corp/dev/mvp-binance-panel
./run.sh
JAVA_TOOL_OPTIONS=-Dbyx.qa.requireNativeInput=true ./scripts/ui_fidelity_smoke.sh /tmp/byx-fidelity-manual
```
