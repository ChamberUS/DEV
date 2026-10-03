# MVP Binance Panel

Research Control Center em JavaFX (Java 21, Maven) para o projeto `~/dev/mvp-binance`.
Não contém lógica de pesquisa e não altera o projeto Python; lê os relatórios TRAIN e chama o `adaptive-trader` existente.

- Executar: `./run.sh`
- Testes: `mvn test`
- Camadas: `ui` → `service` → `adapter` (CommandAdapter / ResearchBackend) → `repository` / `process`
- Comandos permitidos (`CommandSpec`): label-status, checkpoint-status, feature-status, label-run-session, label-run, label-aggregate
- VALIDATION e FINAL_HOLDOUT são recusados pelo adapter; comandos pesados pedem confirmação TRAIN.
- DATA SOURCE: REAL (relatórios) ou MOCK (valores fictícios, sempre sinalizado). Configurável em Settings.
- Main class `panel.app.Main` (não herda de Application) para facilitar `jpackage`.

## Autenticação e autorização
- Abre no login. Primeiro uso (sem usuários): "Initial admin setup" cria o primeiro ADMIN; não existe cadastro público nem credencial padrão.
- Usuários e audit log: SQLite em `~/.mvp-binance-panel/panel.db`. Senhas: Argon2id.
- USER e ADMIN entram no Trading. Research exige ADMIN + (IPv6 confiável OU e-mail + SMS). IPv6 é só sinal de confiança, nunca concede role.
- Configuração (fora do repositório): `~/.mvp-binance-panel/security.properties` (`security.admin.trustedIpv6`, `security.admin.sessionTimeoutMinutes`, `security.dev.mode`) ou a variável `MVP_BINANCE_ADMIN_TRUSTED_IPV6`.
- Comandos do backend e gestão de usuários exigem ADMIN + AdminSession válida na camada de serviço (`AdminGate`).
- Provedores de e-mail/SMS: interfaces `EmailOtpProvider`/`SmsOtpProvider`; hoje "não configurados". `security.dev.mode=true` liga o DEVELOPMENT AUTH PROVIDER (apenas desenvolvimento).
- Limitação: app desktop local. Quem controla o computador e os arquivos do app pode contornar essas barreiras (banco, configuração, binário). Autorização sensível deve migrar para um servidor no futuro.

## Motion, tipografia e ícones
- `panel.motion`: `MotionService` (FULL / REDUCED / OFF, persistido em Settings → Appearance), `MotionTokens` (MICRO 110 · FAST 160 · STANDARD 220 · EMPHASIS 320 · SLOW 500 ms; easing ease-out / ease-in / ease-in-out), `ViewTransitionService` (crossfade + 10–12 px).
- Ícones: `AnimatedIcon` → `LottieAnimatedIcon` (Lottie4J, nativo), `AnimatedSvgIcon` / `SvgIcon` (fallback). `AnimationRepository` cacheia, nunca baixa nada e cai no SVG se o Lottie faltar ou for inválido.
- Fontes embutidas (Inter, JetBrains Mono, OFL). Créditos e licenças: `THIRD_PARTY_ASSETS.md` e About / Credits.

## Backend local
- `BackendGateway` → `LocalBackendGateway` → Python `-m adaptive_trader.panel_status` (contrato JSON v1) e `FileResearchBackend` para detalhes TRAIN já gerados. Um futuro gateway remoto pode implementar a mesma interface sem mudar as views.
- Python é localizado ao lado do CLI configurado. Health padrão: 3s (`pollSeconds`); resumos: 20s (`researchPollSeconds`, mínimo 15s). Settings locais preservados. Refresh manual invalida o cache.
- Scheduler único fora da thread FX; refresh coalescido; processo de status com timeout de 8s. Falhas publicam BACKEND OFFLINE; o próximo polling tenta reconectar.
- Captura: identidade do processo e metadados do arquivo aberto, sem ler eventos. RUNNING significa processo observado, não garantia de saúde. Timestamp de status é mtime do arquivo ativo; uptime é da sessão. Sem preço/book/trades, health ou conta inventados.
- Overview usa somente TRAIN; frozen spec vem do JSON de freeze. Readiness VALIDATION sem fonte autorizada fica N/A; VALIDATION LOCKED e FINAL_HOLDOUT SEALED.
- Executar o bridge isoladamente: `.venv/bin/python -m adaptive_trader.panel_status --project /caminho/backend` no backend.

## Monitor contínuo ADMIN / Capture
- `CaptureMonitorService` publica `CaptureSnapshot` imutável via `CaptureProcessProbe`; `LocalCaptureProcessProbe` consulta `ProcessHandle` e os arquivos locais `~/.mvp-binance-capture/capture.pid` e `current_campaign`. Não executa subprocessos nem envia sinais.
- Leitura em background a cada 5s, storage em cache por 60s e timer visual local de 1s. A varredura de storage usa somente metadados, não segue symlinks, ignora partições protegidas e tem orçamento de 2s; resultado incompleto fica N/A.
- Uptime contínuo usa `ProcessHandle.Info.startInstant()`. A campanha usa o timestamp UTC do identificador; alvo de 86400s, barra limitada a 100%. Uma rotação não redefine o uptime do supervisor.
- Primeiro PID morto/inválido ou de outro processo: STALE. Processo observado vivo que encerra: STOPPED. PID ausente: STOPPED. Erro de acesso ou campanha em transição: UNKNOWN. Metadados de processo restritos produzem warning, sem inventar start/symbol/market.
- Symbol e market vêm dos argumentos do collector filho da campanha atual. Recorder health e contagem de sessões ficam N/A sem fonte barata. Última atualização é o mtime mais recente do storage observado no scan.
- A tela e o serviço exigem AdminSession. Ao ocultar a tela/logout, polling e timer param; dados não são publicados ao Trading. Só há Refresh, sem controles de captura. FULL usa pulsação discreta; REDUCED/OFF mantêm indicador estático.
