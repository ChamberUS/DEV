# BYX V2 — implementation status (JavaFX)

Legenda: DONE · CODED (escrito, aguardando `mvn test` no Mac) · TODO

| # | Passo | Status | Notas |
|---|-------|--------|-------|
| 1 | Baseline / auditoria do app atual | DONE | Cópia do working tree; `.git` não visível ao sandbox → baseline `a3368a2` no repo local de trabalho |
| 2 | Corrigir navegação / lifecycle / motion | CODED | `Navigator`, `View.onShow/onHide`, `MotionService`, Orb/Bot/Icon, timers, dock |
| 3 | Congelar fundação de roteamento | CODED | Congela após `mvn test` verde no Mac |
| 4 | Design foundation (tokens, tipografia, componentes) | TODO | |
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
