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
- Abre no login. **A autenticação é do serviço local (autoridade), não do painel** (V2.1G): contas, verificadores de senha (Argon2id), papéis, limitador de tentativas, segundo fator, dispositivos confiáveis, sessões e auditoria de segurança vivem no serviço (`byx-local-service`), em snapshot cifrado (AES-256-GCM) com âncora de rollback no keychain de proteção de dados. O painel só envia credenciais/códigos e APRESENTA o que o serviço decide (papel, elevação e expiração são exibição; esconder um botão não é autorização). Não existe credencial padrão, primeiro-uso no painel nem caminho para autenticar contra o `panel.db` antigo, e nenhuma flag o reabre.
- O app empacotado inicia o helper do serviço do PRÓPRIO bundle quando ele não está rodando. Reiniciar o serviço invalida todas as sessões (novo login).
- Contas migradas do banco legado (somente leitura) pelo migrador assinado `byx-migrate` (ver [`docs/AUTHORITY_CUTOVER.md`](docs/AUTHORITY_CUTOVER.md)); o `panel.db` antigo permanece intacto como fonte de rollback até uma etapa de limpeza separada.
- Research exige login ADMIN e uma elevação obtida por **Email → SMS** (decididos pelo serviço) ou por dispositivo confiável do serviço. Nenhum endereço IP concede autorização. A elevação desliza com atividade (janela migrada de `security.admin.sessionTimeoutMinutes`) e some se o papel mudar.
- Email: Resend e SMS: Twilio Verify, ambos chamados PELO SERVIÇO; os segredos ficam só no cofre do serviço (nunca no painel). OTP de e-mail de seis dígitos gerado no serviço (uso único, validade absoluta de cinco minutos, cinco tentativas, reenvio não renova a janela).
- "Trust this Mac" é inscrito pelo serviço (registro revogável, 30 dias, ligado à conta; sem IP/MAC/hostname). A confiança antiga do painel NÃO foi migrada: após o cutover o 2º fator é refeito e o serviço emite uma nova.
- Durante a janela de segurança do cutover o serviço recusa troca de senha, mudança de papel, desabilitar/apagar e inscrição de dispositivo permanente; login, logout e 2º fator funcionam. A administração de contas (criar/listar/desabilitar/papel/redefinir/contato) ainda não existe no serviço e fica indisponível na interface.
- `AdminGate`, TRAIN-only, VALIDATION LOCKED e FINAL_HOLDOUT SEALED continuam obrigatórios. `PasskeyProvider` é apenas interface futura; PASSKEY não concede sessão.

### Provedores de 2º fator e segredos
Chave Resend, segredo Twilio e a configuração não secreta (remetente, SIDs) migram do painel antigo para o serviço pelo migrador; o antigo `setup-local-2fa.sh` foi REMOVIDO (gravava no keychain legado). `providers.example.properties` documenta o formato do arquivo lido pelo migrador. Testes usam dublês de autoridade; provedor de desenvolvimento não existe no produto. Uma prova real de envio de OTP é MANUAL e exige confirmação explícita.

### Verificação manual
Com o serviço preparado (ver `docs/AUTHORITY_CUTOVER.md`), abra o app empacotado, faça login ADMIN, complete Email e SMS, marque Trust this Mac (fora da janela de segurança) e entre em Research. Faça logout/login, confirme TRUSTED_DEVICE, revogue em Settings → Security e confirme que Research exige 2FA novamente. USER deve permanecer sem acesso. Não copie códigos/segredos para logs ou issues. Sem providers configurados, pule os envios reais; isso não impede o build.

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
