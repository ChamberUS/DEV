# BYX-MVP — V2.1V final closure after runtime/fencing repair

**READY_FOR_LOCAL_RELEASE_CANDIDATE_AND_FULL_REGRESSION**

Qualificação concluída em 2026-10-08T23:53:46.193212+00:00. O gate original de **20,000 segundos** passou sem alteração de deadline ou asserções. O artefato final O2 passou 20 execuções isoladas, incluindo sua primeira inicialização após rebuild; 3 variantes suplementares de código nativo recém-compilado passaram sua primeira inicialização. Todas as 23 execuções provaram ausência dos atores próprios antes da limpeza. A qualificação completa abaixo foi executada novamente depois da correção; resultados anteriores são históricos.

## Deadline e causa medida

23 amostras: mínimo **11.362193s**, mediana **11.486532s**, maior valor observado/máximo **17.643945s**, margem ao limite de 20 s **2.356055s**. Não se afirma p95 populacional. Os 20 resultados do bundle final e as 3 variantes suplementares estão separados em `fencing-repair/isolated-repeated.json`, `isolated-cold-variants.json` e `qualification-summary.json`.

A auditoria anterior à mudança registrou 24,000006s; a observação diagnóstica até 30 s apenas permitiu registrar o desfecho, mantendo FAIL acima de20s. O timeout interno de 12 s começava após preparação e attach, permitindo que custo anterior e prova de saída acrescentassem tempo. O helper suspenso antes de READY consumia quase toda a espera apesar de não poder receber uma operação autenticada. A latência anterior ao primeiro marcador nativo chegou a 5,574 s; sua origem não foi atribuída sem evidência.

Agora o orçamento existente de 12 s é um deadline monotônico único desde o início, propagado sem renovação. A reserva existente de 3 s protege a prova obrigatória de saída; bootstrap recebe no máximo 6 s, recortados pelo mesmo deadline. Os dois checks independentes de Service vivo e bundle pai ocorrem em paralelo, com ambos obrigatórios antes de aquisição/launch. A validação estática bem-sucedida usa o mesmo strict/nested e requirement em uma chamada; em falha a classificação original é preservada. As chamadas strict caíram 17→12, mas não houve ganho material medido em seu tempo agregado.

SIGSTOP é tratado pelo Service com SIGKILL externo, conforme política existente, e prova de ausência da instância exata por identidade forte. Sem prova dentro do prazo, CUSTODY_QUIESCENCE_UNPROVEN permanece bloqueando a próxima mutação. PID/pidversion/audit identity, executable/origin, Team/identifier/seal, descoberta stale, lease e fail-closed foram preservados. Não há cache de confiança por PID/path nem cache persistente. 25 novos testes determinísticos verificam deadlines, reserva, identidade, reutilização de PID, ambiguidade, falha de prova, expiração e checks de origem frescos/concorrentes. Gates nativos originais permaneceram intactos.

## Regressão fresca

| Gate fresco | PASS | FAIL | SKIP | Evidência em fresh-after-fencing |
| --- | ---: | ---: | ---: | --- |
| panel-suite | 751 | 0 | 1 | 752 total; zero errors |
| service-suite | 498 | 0 | 0 | 498 total; zero errors |
| fencing | 24 | 0 | 0 | fencing.json |
| runtime | 126 | 0 | 0 | runtime.json |
| origin | 12 | 0 | 0 | origin.json |
| canary | 8 | 0 | 0 | canary.json |
| panel-e2e | 42 | 0 | 0 | panel-e2e.json |
| panel-anomalies | 44 | 0 | 0 | panel-anomalies.json |
| panel-default | 6 | 0 | 0 | panel-default.json |
| lifecycle | 178 | 0 | 0 | lifecycle.json |
| default-hardening | 26 | 0 | 0 | default-hardening.json |
| authority | 78 | 0 | 0 | authority.log |
| migration | 62 | 0 | 0 | migration.log |
| custody | 65 | 0 | 0 | custody.log |
| go-default | 37 | 0 | 1 | go-summary.json; child harness entry |
| go-qa | 64 | 0 | 1 | go-summary.json; child harness entry |
| provisioning-unit | 5 | 0 | 0 | provisioning-unit.log |
| build-default | 1 | 0 | 0 | build-default.log |
| visual-runtime22 | 22 | 0 | 0 | 344 shell PNG |
| visual-fixtures | 4 | 0 | 0 | 175 fixture PNG |

Go DEFAULT 37 PASS e QA 64 PASS, zero falhas, um SKIP `TestProcessHarness` em cada invocação normal; o harness é acionado como processo filho pelos testes de integração Service. O único SKIP no Panel é a observação real Capture explicitamente opt-in; nenhum gate obrigatório foi omitido. `service-suite-xml`, `panel-suite-xml` e os resumos contêm apenas resultados frescos. `results.json` registra todos os 20 gates exigidos, incluindo provisioning e build DEFAULT.

O stale-login original, os 17 testes de epoch e os 3 de ownership de AuthScreens passaram na suíte completa. Login reserva geração antes da fila; callbacks de logout/senha e respostas antigas só publicam para a sessão proprietária. As correções aprovadas de UI, navegação, threading, confirmação destrutiva e UNKNOWN_RESULT foram preservadas. O guard original de identidade compartilhada exigiu que MacSecurity fosse espelhado no Panel; sua implementação e FencingTiming diferem do Service somente no package.

## Visual/runtime e artefatos

22 processos visuais frescos passaram: fluxos públicos/onboarding/unsaved/system/legacy/roundtrip; autenticação e navegação FULL/REDUCED/OFF; stress/holds; 14 rotas em 4 dimensões e 2 densidades; USER. As capturas são da Scene JavaFX real. A integridade e as dimensões de 344 imagens de shell e 175 fixtures foram conferidas; a revisão visual cobriu snapshots críticos e 16 painéis de contato. As capturas nativas E2E ADMIN READY/assinatura verificada e USER NO_WALLET, ambas 1920×1080, estão em `fresh-after-fencing/native-wallet-visual`. `fresh-after-fencing/visual-integrity.json` registra dimensões e hashes; `visual-inspection.json` registra a revisão visual. Snapshots imutáveis do bytecode visual inicial e final foram comparados por classe.

DEFAULT: `/private/tmp/byx-v21v-default-after-fencing/BYX-MVP.app`, capability DISABLED. QA: `byx-packaging/build-custody-qa/BYX-MVP.app`, capability SYNTHETIC_QA. U preservado: `/private/tmp/byx-v21v-preserved-u/BYX-MVP.app`. A auditoria independente verificou os 3 deep strict seals, Apple/Team W5Z65G9UP2, owner/modes, ausência de symlinks nos caminhos críticos, profiles válidos, bytecode Panel/Service correspondente à fonte testada, PRODUCTION_DISABLED e 105 JARs U idênticos ao manifest original. `final-artifact-audit.json` contém hashes e expiração de profiles. Nenhum ator QA permaneceu.

## Preservação e segurança

Branch `feature/byx-ui-redesign-v1`, HEAD `9d3eb1e50ea4cc03e9523d4e178dd83e8836fb38`. Os 767 hashes de fonte testada permaneceram estáveis. A fonte U congelada só difere nos 6 arquivos runtime autorizados; a fonte aprovada do Panel só difere no MacSecurity espelhado, além da nova utilidade de timing desabilitada por padrão. Diffs preexistentes foram preservados. Não houve reset, clean, stash, rebase, commit ou push. Whitespace passou no escopo alterado; achados preexistentes externos ao escopo foram mantidos.

Capture ETHUSDT foi observado exclusivamente por metadados e confirmou crescimento saudável; `capture-health.json` registra PID/supervisor, horários e bytes. Nenhum sinal foi enviado, nenhum conteúdo científico usado pelo QA e nenhum TRAIN/VALIDATION/FINAL_HOLDOUT executado. Profiles são locais de desenvolvimento; o alvo aprovado é release candidate local. Preferences/setup/administração continuam conforme as capacidades do Service. Não há implementação de wallet real, recuperação/exportação ou repair/adopt automático.

Falhas e medições anteriores permanecem em `history/`, `fencing-repair/` e nos arquivos de tentativa anterior. Elas não substituem os resultados frescos. Checkpoints finais: `docs/agent-state/STATE.json` e `HANDOFF.md`.

**REAL USER KEY: NOT AUTHORIZED · REAL TX: DISABLED · BROADCASTS: 0 · REAL FUNDS: 0 · REAL USER WALLETS: 0 · ETHUSDT RESEARCH: UNTOUCHED.**
