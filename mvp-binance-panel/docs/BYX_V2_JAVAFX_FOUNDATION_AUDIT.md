# BYX V2 — JavaFX foundation audit

Escopo: navegação, ciclo de vida de views e motion. Nenhuma lógica financeira, gate científico, dado ou segurança foi alterado.
Verificação: ver "FOUNDATION TESTS" (compilação e `mvn test` precisam rodar no Mac; o sandbox não alcança Maven Central).

## CURRENT ARCHITECTURE
- `PanelApp` (Application) mantém `views: Map<String, View>`, o chrome (rail, top bar, dock) e dois `Timeline`s: `chromeWatch` (1 s) e `expiryWatch` (10 s).
- `AppContext` entrega serviços: `motion` (`MotionService` + `ReferenceMotion`), `transitions` (`ViewTransitionService`), `adminAccess`, `sessions`, `toasts`, `icons`.
- Toda tela implementa `View { node(); onSnapshot(Snapshot) }`. Antes desta correção não havia hook de exibir/ocultar.

## NAVIGATION OWNERS
Quem pode mudar a tela exibida (todos passam por `PanelApp.show` → `display`):
- cliques no rail, switch Trading/Research/BYX, `UserMenu`, `CommandPalette`;
- `watchAdminSession` (expiração da AdminSession → volta para Trading);
- conclusão assíncrona de `requestResearch` (verificação de dispositivo confiável) e do `TwoFactorView`.
Somente os dois últimos são automáticos; são a origem do "navega sozinho".

## VIEW LIFECYCLE
- Antes: views criadas uma vez por login; visibilidade alternada por `ViewTransitionService`; cada view decidia sozinha quando rodar timers (via `visibleProperty` da raiz ou nunca).
- Depois: `View.onShow()/onHide()` (default vazio). `PanelApp.display` chama `onHide` na anterior e `onShow` na nova; `showEntry` (logout) e a reconstrução pós-login chamam `onHide` na ativa.

## MOTION LIFECYCLE
- `MotionService.play` guarda a animação por nó em `getProperties()` e cancela a anterior.
- Loops decorativos (`loop`) são registrados em listas/mapas, pausados quando o dono não está visível e removidos ao sair da cena ou ao trocar para REDUCED/OFF.
- `ViewTransitionService` esconde as demais views, reseta a alvo e dispara `enterCards` quando a view muda.

## STALE CALLBACKS
- `requestResearch` (REQUIRES_2FA): a thread `trusted-device-check` completava via `Platform.runLater` com o `target` capturado no início. Se o usuário clicasse em outra tela durante a verificação, a conclusão ainda chamava `display(target)` ou abria o overlay 2FA → "navegação sozinha". Um segundo clique era descartado por `checkingTrustedDevice`, e o primeiro destino vencia.
- `ByxWalletView.verify` / `refresh` e `ByxTreasury/Benefits.refresh` já validam sessão/cena antes de aplicar o resultado (mantido).

## LISTENERS
- `OrbIndicator`, `BotAvatar`, `AnimatedSvgIcon` mantinham referência a animações já removidas por `MotionService` (saída de cena, `stopLoops`), sem listener para recriar.
- Listeners de preferência de motion usam `WeakChangeListener` com referência forte em `getProperties()` (correto, mantido; `OrbIndicator` passou a usar o mesmo padrão).
- `AnimatedSvgIcon.stop()` fazia `Animation.stop()` sem `removeLoop`, vazando o loop e o listener de cena.

## TIMELINES
- `JobsView`: `Timeline` INDEFINITE iniciado no construtor e nunca parado (rodava oculto, `table.refresh()` por segundo).
- `ByxNetworkView`, `ByxWalletView`, `ByxBenefitsView`, `ByxTreasuryView`: iniciavam/paravam só por mudança de `visibleProperty`; a primeira view exibida nunca recebia a mudança, então não iniciava; não consideravam a cena.
- `chromeWatch`: `updateStatusDock` recriava ~11 `Label`s por segundo.
- `TwoFactorView.countdown` e `CaptureMonitorCard.timer` têm dono explícito (fechamento do overlay / `start`/`stop`) — sem alteração.

## Platform.runLater
Usos relevantes auditados: conclusão da verificação de dispositivo (corrigido por ticket), `ByxWalletView.verify` (valida sessão), `refresh` de Wallet/Benefits/Treasury (valida cena/sessão). Nenhum `runLater` novo foi adicionado.

## ASYNC SOURCES
`trusted-device-check` (thread), `CompletableFuture` de verificação de prova de carteira, refresh de benefícios/tesouraria/saldo, jobs/processos externos (`ctx.jobs`). Nenhuma toca rede nesta fase de UI.

## ROOT CAUSES
1. **Navegação automática**: conclusão assíncrona usa destino capturado e não é cancelada por navegação posterior.
2. **Animação que não termina/limpa**: `MotionService.play` sobrescrevia o `onFinished` do chamador (`spinOnce` não zerava a rotação; `flash` não removia o `DropShadow`).
3. **Animações que não reiniciam**: Orb/Bot/Icon seguravam loops removidos; `setActive(true)` retornava cedo para sempre.
4. **Timers fora de lugar**: `JobsView` sempre ligado; demais views dependiam de `visibleProperty` (primeira view nunca iniciava).
5. **Reconstrução desnecessária**: dock recriado a cada segundo.
6. **Re-exibição**: `ViewTransitionService` resetava a view alvo mesmo quando já estava ativa (interrompe entrada em curso).

## PROPOSED FIX
Implementado: `panel.nav.Navigator` (tickets: `begin`, `consumePending`, `cancelPending`, `displayed`, `reset`) integrado em `requestResearch`, `display`, cancelamento do 2FA, logout; `View.onShow/onHide` e migração dos timers; `MotionService.play` encadeia `onFinished` e ganha `isLooping`; `ViewTransitionService` só reseta/anima quando a view muda e expõe `entries()`; Orb/Bot/Icon recriam ou removem loops corretamente; dock com assinatura.

## FOUNDATION TESTS
- `NavigatorTest` (4): último pedido vence; `displayed` cancela pendente; reset; consumo único.
- `FoundationLifecycleTest` (6): `onFinished` preservado; Orb reinicia após `stopLoops`; Orb em REDUCED sem loop; `isLooping`; re-exibir a mesma view não repete entrada (`entries`); troca rápida deixa só uma view visível.
- Existentes relevantes: `ReferenceMotionTest.rapidWorkspaceChangesLeaveOnlyTargetWithoutMovingWholePage`, `MotionIconTest`, `CommandPaletteTest`.
- Status: sintaxe validada com `javac` (apenas erros de símbolos ausentes, sem JavaFX no sandbox). **Ainda não executado**: rodar `mvn test` no Mac.
