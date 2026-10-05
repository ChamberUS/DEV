# BYX-MVP V2 — Final QA (Passo 14)

**FINAL STATUS: READY WITH KNOWN LIMITATIONS** (nenhum blocker, critical ou high conhecido; limitações em "Known limitations").

## ENVIRONMENT
macOS 26.5.1, JDK 21.0.12 (empacotado em `tools/`), Maven 3.9.9, JavaFX do `pom.xml`. QAs do app real rodam em homes temporários (`~/.mvp-binance-panel` isolado por execução, `security.dev.mode=true`, OTP de desenvolvimento): o ambiente persistente do usuário não é tocado. Nada foi enviado ao git remoto.

## HEAD
Início do Passo 14: `c18c93c` (depois `0b6b272`, correção do FAQ). Fechamento: ver "FINAL COMMITS" no relatório. Intervalo V2 auditado: `97b8e36..HEAD`.

## TESTS / BUILD
- Baseline de entrada: 483 testes. **Final: 492 testes, 0 falhas.**
- `mvn test`: BUILD SUCCESS (uma execução final). `mvn clean package`: BUILD SUCCESS, `target/mvp-binance-panel-0.1.0.jar`.
- Novos no Passo 14: `FinalSafetyTest` (4), `SemanticsAuditTest` (2), destaque do FAQ, matriz oficial de REDUCED, diagnóstico com campos/valores proibidos.

## NAVIGATION
Evidência: `docs/qa/step14/navigation-full.txt` (37/37), `stress-abc.txt`, `stress-cycle.txt`, `final-roundtrip.txt`.
- Idle de 60 s em Trading, Research e BYX, com um update de dados por segundo: a rota nunca muda, uma View visível, nenhuma entrada de página.
- A→B→C rápido ×10 e Trading→BYX→idle ×10 ciclos (`NavStressQa`): 0 falhas. Round trips Trading↔Research↔BYX ×10 (30 navegações): somente as rotas pedidas foram confirmadas (`RouteTrace`).
- Pedido pendente (verificação de admin em outra thread) com A→B(pendente)→C e A→B(pendente)→A: o callback atrasado não navega.
- **Finding reservado desde o Passo 7: FECHADO.** Nenhuma falha em toda a bateria; toda mudança de rota vem de `PanelApp.show → ShellRouter.request`.

## LIFECYCLE
`ShellNavigationQa` (resize, fundo→frente, 50 refresh de status, updates de dados: `ctx.transitions.entries()` não muda), `FinalQa roundtrip` (10 round trips sem repetir a entrada pesada; logout libera loops: só o loop do login sobra; nova sessão cria instâncias novas e permite novo first-enter) e `ViewEnterLifecycleTest`. Dispose: Capture, Jobs, Network, Wallet, Benefits, Treasury, System Status, Settings (barra de salvar) liberam timers/loops (testes dos passos 7–13).

## AUTH
`AuthFlowQa` 35/35 em FULL e, após as correções finais, de novo: primeiro administrador, política de senha, login, credencial inválida (copy neutra), lockout no 6º erro com contagem real e Esc que não contorna, mostrar/ocultar senha (`AuthKeyboardTest`), verificação de admin por e-mail+SMS (`AdminVerificationViewTest`, `ShellNavigationQa`), sessão expirada com diálogo persistente, "Sign in again" e retorno à rota capturada, logout com confirmação. Callback tardio de login/verificação descartado não navega (testes + QA). Welcome na primeira instalação confere em `final-onboarding.txt`.

## TRADING
LIVE TRADING = OFF. `FinalSafetyTest`: nenhuma escrita em `.trading`, nenhum `ENABLED`, nenhum endpoint de exchange ou ordem, o único HMAC é o do OTP, o dock mantém o texto "Live trading OFF". Settings tem a linha `OFF · LOCKED` sem controle (`settingsHaveNoLiveTradingSwitch…`). Nenhuma chave, ordem ou exchange foi usada no QA.

## RESEARCH / CAPTURE
`ResearchGateTest`, `CaptureScreenTest` e `final-legacy.txt`: Overview e Capture V2 abrem; Capture e monitor só com admin autorizado; as 13 Views de Research legadas abrem.

## SCIENTIFIC GATES
`Snapshot.validationStatus = "LOCKED"` e `finalHoldout = "SEALED"` (constantes `final`); `FinalSafetyTest`: ninguém atribui os gates, nenhuma rota contém "holdout", Validation/Paper/Live continuam `LockedView`; as Views V2 de Help, Account, System e BYX não tocam jobs nem processos. O QA de onboarding confere LOCKED/SEALED antes e depois. Nenhum job científico foi executado e o Final Holdout não foi acessado.

## BYX
Network, Wallet, Benefits e Treasury somente leitura (ver `BYX_V2_IMPLEMENTATION_STATUS.md`, Passo 9). Sem DEVNET, sem nó iniciado, sem broadcast, sem assinatura, sem fundos, sem chave privada, sem saldo inventado (`FinalSafetyTest.byxV2IsReadOnly…`: `byxGas.request/revoke/refresh` e `byxPayments.create/confirm` não são chamados por nenhuma UI; DEVNET só aparece como texto indisponível; sem `privateKey/mnemonic/seed/KeyPairGenerator`; `ProcessBuilder` só no signer de teste opt-in por variável de ambiente + modo dev, na sonda do SO e no `ProcessRunner` existente).

**INTENTIONALLY NOT EXPOSED IN V2** (serviços e testes mantidos, sem entrada na UI atual; não reintroduzir automaticamente):
| Função legada | Motivo específico |
|---|---|
| Criar e confirmar intenção de pagamento (`ByxPaymentService`) | O pedido do Passo 9 fixou o port como somente leitura; a confirmação concede uma entitlement por pagamento TEST e a criação reserva destinatário/memo: é escrita de estado financeiro de teste, sem tela de referência |
| Pedir e revogar allowance de gás (`GasSponsorshipService`) | Faz broadcast de feegrant na cadeia (assinatura pelo signer de teste); "NÃO broadcast / NÃO assinar" |
| Vincular/desvincular posse da carteira | Não é broadcast nem fundo, então segue na View legada `t-wallet-verify` (LEGACY / NO V2 REFERENCE) |

## ACCOUNT
Profile, Security, Sessions, Notifications (UNAVAILABLE, não EMPTY), Activity, Settings: ver Passo 10. QA real `final-unsaved.txt` (22/22): Settings sujo → sair → Cancel preserva o rascunho, Discard o remove, nada é gravado; Save só mostra sucesso depois de gravar em disco (`motion=REDUCED` no arquivo) e aplicar no sistema de motion; Profile sujo: mesma regra, e os contatos da conta não mudaram.

## HELP
FAQ (agora com destaque do termo buscado, chevron à direita e contagem), About, Overview, Support (Report a problem UNAVAILABLE · DEMO ONLY), Diagnostics, Terms/Privacy (LEGAL_PLACEHOLDER), Shortcuts, What's New. **Modo público** no app real (`final-public.txt`, 38/38): Login → FAQ → About → Help → Terms → Privacy → Sign in; sem sessão, Trading, Markets, Research, Capture, Validation, BYX (Network, Wallet, Treasury), Profile, Security, Sessions, Settings, System Status, Diagnostics, Overview, Shortcuts e What's New permanecem inacessíveis, sem rail nem dock no host público.

## SYSTEM
`final-system.txt` (19/19): backend conectado → desconectado → restaurado: faixa global aparece e some, aviso de restauração, chip RESTORED em System Status que volta a CONNECTED após 3 s, e em nenhum momento a rota muda, o shell é recriado ou a página repete a entrada. Page Unavailable (sem redirecionamento sozinho, Return só pelo roteador). Fallback de erro inesperado sem stack trace, exceção, segredo ou caminho; Retry desabilitado após falhas repetidas. Onboarding real (`final-onboarding.txt`, 23/23): Welcome → administrador → primeiro login → 6 passos, Skip, replay, Finish; só `onboardingCompleted` e `primaryWorkspace` mudaram em disco; trading, gates, carteira e nó BYX intactos.

## MOTION
- FULL/REDUCED/OFF terminam no mesmo estado lógico (`MotionAccessibilityTest`, `ShellNavigationQa` e `AuthFlowQa` nos três modos).
- **Matriz oficial como autoridade, não "REDUCED = zero loops" cego:** `modes.REDUCED.loops = "spinner only"`; o spinner continua em REDUCED (`loading.reducedMs` 800) e é glifo estático em OFF; `breathing`, `statePulse` e `shimmer` têm `reducedMs: none`; no brand field, em REDUCED só o brilho respira (8 s, 12 fps). Teste: `reducedModeFollowsTheOfficialMatrixNotABlindRule`.
- **macOS Reduce Motion real:** a sonda lia só `com.apple.universalaccess reduceMotion`, chave que **não existe** neste macOS 26 (a chave presente é `com.apple.Accessibility ReduceMotionEnabled`): teria ficado sempre "não reduzido". **Corrigido**: lê as duas. A política (`MotionPolicy`) só reduz FULL e nunca afrouxa REDUCED/OFF (tabela completa testada). Estado lido agora: desligado (`SYSTEM_REDUCE_MOTION=false`). Não alterei o ajuste do sistema (configuração de sistema não é algo que eu altere): **o liga/desliga real fica com você**, com `mvn -o test -Dtest=SystemMotionQa` antes e depois (comandos no relatório).

## ACCESSIBILITY
Revisão dirigida por teclado: Tab/Shift+Tab, Enter, Space, Esc e setas em Login (`AuthKeyboardTest`), FAQ (↑/↓/Home/End/↓ da busca), controles segmentados (←/→), categorias de Settings (↑/↓), Onboarding (Esc = Skip, foco inicial em Next), diálogos (foco preso, Esc fecha só o topo, foco volta ao abridor, nada de foco atrás do diálogo: `ByxOverlayHostTest`, `helpKeyOpensTheShortcutsDialog…`) e System Status (Retry focável). Foco visível: anéis de 2 px nos controles V2 novos.

## SEMANTICS (screen reader)
`SemanticsAuditTest`: todo botão do shell sem texto tem texto acessível (achado e corrigido: o avatar não tinha nome antes do login do usuário; agora "Account menu"); diálogos têm papel DIALOG e nome; linhas do FAQ anunciam expanded/collapsed; progresso do onboarding também em texto ("Step n of 6"); tier atual anunciada; estados de rede, tesouraria, status e recuperação sempre em texto. **Gaps restantes:** sem teste com VoiceOver real; rótulos de grupos de linhas (KvRow) usam o texto composto; tabelas são `HBox` de rótulos sem papel de tabela; sem anúncio vivo de mudanças de status (live region) além do texto.

## RESPONSIVE
Capturas finais em `docs/qa/step14/` (1440/1600/1920/1280 para FAQ, System Status, Onboarding e Login; Diagnostics e Terms em 1600/1920/1280) mais as famílias já validadas: Trading e Markets (step7), Overview e Capture (step8), Network/Wallet/Benefits/Treasury (step9), Profile/Security/Sessions/Activity/Settings (step10), Help (step11), System (step12). **Help em 1600/1920** (finding pendente): estrutura comum confirmada (coluna de leitura de 1240 centrada, FAQ de largura total). **Bug visual encontrado e corrigido:** páginas de leitura (Terms, Privacy, About, Settings, Shortcuts, What's New) ficavam alinhadas à esquerda em 1920; agora centralizadas como a especificação. 1280×760: sanity ok (sem corte, rolagem em vez de sobreposição).

## SECURITY
Diagnostics: allow-list de 17 campos, campo proibido recusado (password, token, OTP, API key, private key, seed, authorization, session secret, mnemonic…), valores hostis redigidos (oito formas testadas), e-mail e telefone redigidos, o texto copiado é exatamente o preview. Fallback de erro: código `ERR-XXXXXX` derivado da classe e da hora, nunca da mensagem. Auditoria de fonte (`FinalSafetyTest`): sem TODO/FIXME, sem caminhos absolutos, sem segredos fixos, `System.out` só na galeria de desenvolvimento e no dump `BYX_RUNTIME` que **passou a ser opt-in** (`-Dbyx.runtime.diagnostics=true`: continha caminhos locais e hashes). `printStackTrace` só no log local de erro inesperado.

## PERFORMANCE
Não refeito: nenhuma regressão perceptível, nenhum crescimento inesperado de nós, nenhum vazamento de timer/loop (round trips e logout verificados). Dados dos Passos 7 e 8 (`docs/qa/step7/performance.txt`, `step8/performance.txt`) permanecem a referência; as telas dos Passos 9–13 atualizam no lugar (System Status: 20 atualizações sem reconstruir linhas; Network/Wallet/Benefits/Treasury só com a tela visível).

## LEGACY VIEWS
Inventário fixado por `legacyViewInventoryIsExactlyTheDocumentedOne` e exercitado em `final-legacy.txt` (116 checagens, 0 falhas): todas as 22 Views abrem, uma visível por vez, dentro do `LegacyHost`; as 24 Views V2 abrem fora dele, sem classes legadas; a cena carrega só as folhas V2 (`panel.css`/`byx.css` só no `LegacyHost`).
LEGACY / NO V2 REFERENCE: Bot, Strategies, Signals, Portfolio, Positions, Orders, Performance, Bot Activity; Verify wallet ownership; Sessions, Dataset, Labels, Features, Hypotheses, Validation, Simulator, Paper/Shadow, Live, Jobs, Logs, Users, Research Settings. Também: diálogo Credits e `ToastHost` (entrada antiga). Os smokes antigos (`RedesignVisualSmoke`, `FidelityProductionSmoke`, `MotionParitySmoke`, `ByxVisualSmoke`) seguem fora do `mvn test` e só rodam com `-Dbyx.legacy.qa=true`; `QaApp`/`UserMenuProbe` são ferramentas locais, ignoradas pelo git e excluídas da compilação (pom). Não foram refatorados.

## VISUAL DIFFERENCES
Referências renderizadas em `docs/qa/step14/reference/` (Chrome headless, 1440×900) contra as capturas JavaFX.
| Tela | Diferença | Classe |
|---|---|---|
| FAQ | busca com ícone, contagem "N questions" (adicionada) e cabeçalhos por categoria dentro de um cartão; kicker "HELP" acima do título; destaque do termo (corrigido: sublinhado + acento) | NEAR |
| FAQ | chevron no canto direito (corrigido); ícones do rail Support e New diferentes | EXACT / NEAR |
| System Status | tiles OPERATIONAL / NEEDS ATTENTION / UNKNOWN / CHECKED (adicionados); colunas por cabeçalho (COMPONENT/STATE/REASON/LAST UPDATE/DATA AGE) viraram linhas com razão e idade | NEAR |
| System Status | Retry só onde há tentativa real (referência mostra Retry também em Market Feed e Wallet); botão Refresh global ausente; badges DEMO/BACKEND_REQUIRED ausentes (dados reais) | INTENTIONALLY PRESERVED |
| Settings | uma categoria por vez (referência: todas empilhadas com rolagem); Motion dentro de Appearance; badge "DEMO · NOT PERSISTED" ausente (persiste de verdade) | NEAR / INTENTIONALLY PRESERVED |
| BYX Network, Wallet, Benefits, Treasury | ver tabelas de diferenças do Passo 9 (blocos recentes: só o último; hash/txs "Not reported"; fonte esperada não declarada) | INTENTIONALLY PRESERVED / BACKEND UNAVAILABLE |
| Login, Trading, Research, Capture | ver Passos 6–8 (sem alteração) | EXACT / NEAR |
| Rail, dock e barra superior | chip de contexto, badge de admin e contagem de notificações dependem de dados reais (sem contagem fictícia); tipografia sem letter-spacing (limite do CSS do JavaFX, Passo 4) | NOT FEASIBLE / INTENTIONALLY PRESERVED |
| Todas | transições de cor sem animação (limite do CSS do JavaFX, Passo 4) | NOT FEASIBLE |

## KNOWN LIMITATIONS
1. **macOS Reduce Motion real não foi alternado por mim** (ajuste de sistema): a sonda foi corrigida, lê o estado atual e a política é testada em tabela; falta o liga/desliga manual (comando abaixo).
2. Sem VoiceOver real; tabelas sem papel semântico (ver SEMANTICS).
3. Os estados reais de rede, carteira e tesouraria só foram exercitados com stubs e fixtures: não há nó LOCALNET, feed de mercado, conta nem backend de projeto neste ambiente.
4. `./run.sh` no ambiente persistente do usuário: verificado só o início e o fechamento limpos (sem login nem criação de conta).
5. Settings mostra uma categoria por vez (referência: rolagem única); FAQ sem agrupamento por categoria; ícone de busca no FAQ ausente (LOW).
6. Dock: sem realce da linha de System Status ao vir de um item do dock (LOW).
7. Startup mínimo nunca aparece (não há espera real); contrato de 800 ms testado.
8. `ControlGallery`/`GalleryApp` (galeria de desenvolvimento, `--gallery`) seguem em `src/main` por decisão do Passo 4; não são acessíveis pelo app normal.
9. Views LEGACY / NO V2 REFERENCE seguem com a aparência anterior por decisão de escopo.

## BUGS FOUND / FIXED NO PASSO 14
| # | Gravidade | Bug | Estado |
|---|---|---|---|
| 1 | HIGH | A sonda do Reduce Motion do macOS lia uma chave que não existe no macOS atual (sempre "não reduzido") | corrigido (lê `com.apple.Accessibility ReduceMotionEnabled` e a antiga) |
| 2 | MEDIUM | Páginas de leitura alinhadas à esquerda em 1920 | corrigido (coluna de 1240 centrada) |
| 3 | MEDIUM | FAQ sem destaque do termo buscado; chevron colado ao texto | corrigido |
| 4 | MEDIUM | System Status sem os tiles de resumo da referência | corrigido |
| 5 | MEDIUM | Avatar da barra superior sem nome acessível antes de `setUser` | corrigido |
| 6 | LOW | Dump `BYX_RUNTIME` no stdout com caminhos locais e hashes | agora opt-in |
| 7 | — | Falsos positivos do meu próprio QA (expectativas de replay do onboarding, foco, texto invisível, padrões materializados no arquivo de preferências): ajustados no harness, sem mudança no produto | — |
