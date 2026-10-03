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
- Abre no login. Primeiro uso sem usuários: "Initial admin setup" cria o primeiro ADMIN; não existe credencial padrão.
- Usuários e auditoria: SQLite em `~/.mvp-binance-panel/panel.db`. Senhas: Argon2id. USER permanece no Trading.
- Research exige login ADMIN e uma AdminSession obtida por **Email → SMS** ou por dispositivo confiável válido. Nenhum endereço IP concede autorização; a chave legada de rede é ignorada.
- Email: SDK oficial Resend; OTP local de seis dígitos, SecureRandom, HMAC somente em memória, validade de cinco minutos, cinco tentativas, uso único e cooldown de 30s. SMS: Twilio Verify gera e verifica o código; o app não gera SMS de produção.
- Desafios pertencem ao UUID do login e são invalidados por logout/troca de usuário. AdminSession possui timeout independente (`security.admin.sessionTimeoutMinutes` em `security.properties`).
- Após ambos os fatores, "Trust this Mac for 30 days" grava token aleatório de 256 bits no Keychain e somente SHA-256 no SQLite. Não usa IP, MAC, serial ou hostname. Revogar em Settings → Security invalida a sessão administrativa; o próximo acesso exige 2FA.
- Profile permite editar email/telefone E.164 com senha atual. Contatos ficam mascarados fora da edição; alterações invalidam dispositivos e autorização administrativa.
- `AdminGate`, TRAIN-only, VALIDATION LOCKED e FINAL_HOLDOUT SEALED continuam obrigatórios. `PasskeyProvider` é apenas interface futura; PASSKEY não concede sessão.

### Setup local real (macOS)
Execute `./setup-local-2fa.sh` em terminal interativo. API keys/secrets são digitados sem eco, enviados diretamente à API nativa do Keychain e nunca passam por argumentos de processos, histórico de shell ou arquivos temporários.

Secrets no Keychain:
- `mvp-binance-panel/resend-api-key`
- `mvp-binance-panel/twilio-api-secret`
- `mvp-binance-panel/trusted-device-token`

O setup grava somente os quatro campos de `providers.example.properties` em `~/.mvp-binance-panel/providers.properties` (0600; diretório 0700), desativa o modo dev e preserva outras configurações de segurança. Reinicie o app depois. Account SID (AC), API Key SID (SK) e API Secret são usados para Twilio Verify; não use Primary Auth Token. Crie o Verify Service manualmente no Twilio e informe seu SID VA. Sem ele, a UI mostra **Twilio Verify / NOT_CONFIGURED / Missing Verify Service SID** e não envia SMS.

Use um remetente autorizado no Resend. O default opcional `onboarding@resend.dev` está sujeito às restrições de teste da conta. Disponibilidade local CONFIGURED significa que a configuração e o segredo existem; entrega/autorização remota só são comprovadas por envio. Falhas aparecem como ERROR, com mensagem sanitizada. Keychain indisponível falha fechado, sem fallback automático.

Providers fake só existem quando `security.dev.mode=true` é explicitamente definido em `~/.mvp-binance-panel/security.properties`; a UI identifica **DEVELOPMENT AUTH PROVIDER**. Testes usam doubles em memória, nunca enviam email/SMS. Não use modo dev para contas reais.

Documentação: [Resend Java SDK](https://github.com/resend/resend-java), [Twilio Verify](https://www.twilio.com/docs/verify/api), [Verification](https://www.twilio.com/docs/verify/api/verification), [Verification Check](https://www.twilio.com/docs/verify/api/verification-check).

### Verificação manual
Após setup/build, abra `./run.sh`, faça login ADMIN, complete Email e SMS, marque Trust this Mac e entre em Research. Faça logout/login, confirme TRUSTED_DEVICE, revogue em Settings → Security e confirme que Research exige 2FA novamente. USER deve permanecer sem acesso. Não copie códigos/segredos para logs ou issues. Sem providers configurados, pule os envios reais; isso não impede o build.

Limitação: app desktop local. Quem controla o usuário do sistema, banco e binário pode contornar as barreiras locais. Keychain protege secrets em repouso; autorização de operações sensíveis deve migrar para servidor no futuro.

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
