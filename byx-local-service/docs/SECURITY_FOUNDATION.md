# BYX local service — fundação de segurança (V2.1A)

> **V2.1F:** a autoridade de autenticação e sessão (no serviço; QA empacotado isolado, sem migrar usuários reais) está documentada em [`AUTHORITY_MODEL.md`](AUTHORITY_MODEL.md). O lançador do helper do serviço agora é nativo e endurecido (ambiente limpo, argumentos constantes, selo validado).

Estado: fundação mínima. O serviço só responde `health`, `version` e `capabilities`. **Nenhum dado de conta, administrativo, de mercado ou de notificação é servido.** Este documento descreve o que foi decidido, o que foi provado por teste dinâmico e o que NÃO está coberto.

Convenção de evidência: **TESTED** = teste dinâmico executado nesta entrega; **IMPLEMENTED** = código/documento sem teste dinâmico próprio; **NOT_APPLICABLE** = a ameaça não existe neste transporte (com a razão); **BLOCKED** = recusado de propósito até haver identidade e autorização demonstradas no serviço. Os IDs `BYX-xx` são os do pacote `byx-secure-development` (nossos, não oficiais).

## 1. Escopo e preservação

- Painel: `/Users/buynnex-corp/dev/mvp-binance-panel`. Serviço: `/Users/buynnex-corp/dev/byx-local-service`. Raiz Git comum: `/Users/buynnex-corp/dev` (um único repositório; staging só por caminho explícito destes dois projetos).
- Não tocados: `mvp-binance`, `mvp-binance-capture-*`, `~/.mvp-binance-capture`, dados de VALIDATION/FINAL_HOLDOUT, manifests, recorder. O `CaptureRuntimeResolver`/monitor de captura do painel não foi alterado e o novo código não os chama.
- Fora do escopo: Binance (mercado ou conta), ordens, chaves, carteira, assinatura/broadcast, DEVNET, TOTP/passkeys, cadastro público, reset remoto, migração de contas, servidor remoto.

## 2. Ativos, atores, ameaças

| Ativo | Hoje | Proteção nesta fundação |
|---|---|---|
| Contas, hashes, sessão, elevação admin | no painel (SQLite local, memória) | **não** expostos pelo serviço (capacidade BLOQUEADA) |
| Segredos de provedores / dispositivo confiável | Keychain do painel | fora do serviço; nenhuma API os devolve |
| Segredo de pareamento painel↔serviço | arquivo 0600 em diretório 0700 | gerado a cada início, nunca em argv/log/Git |
| Captura científica e gates | painel (leitura) + projeto de pesquisa | intocados; o serviço não tem caminho até eles |
| Disponibilidade da máquina durante a captura | — | limites de quadro, conexões, filas e timeouts |

| Ator | Pode | Cobertura |
|---|---|---|
| Outro usuário do Mac | tentar ler/conectar | diretório 0700, socket 0600 (TESTED: permissões e recusa de diretório frouxo) |
| Página web / navegador | chamar uma API local | navegadores não falam socket Unix: **NOT_APPLICABLE** (sem Host/Origin/CORS/CSRF a validar; nenhum listener TCP) |
| Processo local do mesmo usuário (sem root) | ler o arquivo de pareamento e conectar | **NÃO coberto** (ver 3.4); só obtém o que o serviço serve: status não sensível |
| Processo com controle da conta/root | tudo (ler memória, trocar binários, substituir serviço) | **fora de garantia** |
| UI/painel comprometido ou com bug | enviar `role=admin`, `mfa=true`, `userId` | recusado: campos não existem no contrato e o pedido inteiro é rejeitado (TESTED) |
| Serviço substituto/impostor | responder no socket | recusado antes de receber qualquer prova ou pedido do painel (TESTED) |

## 3. Decisão arquitetural

### 3.1 Alternativas comparadas (apenas duas viáveis na stack Java 21)

| | A. Socket Unix privado (`java.nio` UNIX domain sockets, JDK 16+) | B. TCP `127.0.0.1` + TLS mútuo (JSSE) |
|---|---|---|
| Superfície de rede | nenhuma (sem porta, sem IPv4/IPv6) | porta em loopback, alcançável por qualquer processo local e por navegador (DNS rebinding, Origin/Host a validar) |
| Controle de acesso do transporte | permissões do diretório/socket (0700/0600) | certificados; ainda assim qualquer processo local pode tentar conectar |
| Manutenção | JDK puro, sem dependência | exige emitir/guardar CA e certificados (sem API X.509 no JDK; `keytool` ou biblioteca) e política de renovação |
| Identidade do peer | nenhuma nativa no Java para macOS (ver 3.4) | certificado de cliente (prova posse de uma chave, não de um aplicativo) |
| Risco de configuração | baixo | médio (trust-all, hostname, protocolos) |

**Escolhida: A**, por reduzir a superfície e a manutenção neste estágio, não porque TLS seja inadequado: TLS (B) é uma opção madura e continua candidata assim que existir qualquer listener TCP, cliente remoto ou outro usuário do SO na fronteira. Hoje ela acrescentaria pouco: não há rede a proteger e o bootstrap do certificado dependeria dos mesmos arquivos privados do mesmo usuário. XPC com requisito de assinatura do peer é o caminho de proteção mais forte no macOS, mas exige binários assinados/provisionados e integração nativa que não existem neste ambiente de desenvolvimento: **limite declarado** (3.4), não esquecido.

### 3.2 Três perguntas separadas

1. **Qual processo conectou (identidade do processo).** Nesta fundação: "quem consegue ler o segredo de pareamento 0600 de um diretório 0700 do mesmo usuário". É identidade de *usuário do SO*, não de aplicativo.
2. **Qual usuário do BYX está autenticado (sessão do usuário).** **Não implementado no serviço.** O painel autentica hoje e o serviço não recebe nem confia em nada disso.
3. **Qual operação esse usuário pode fazer (autorização).** As três operações da allowlist não precisam de usuário: nada privado existe. Qualquer capacidade privada futura só será habilitada quando 2 e 3 forem demonstrados *dentro do serviço*.

O serviço **nunca** aceita `role`, `mfa`, `userId` ou qualquer claim vindo da UI como prova. O token de pareamento não autoriza dados de conta.

### 3.3 Bootstrap de confiança, nos dois sentidos

- O serviço, ao iniciar, valida o diretório (`~/.byx-local-service`, `run/`: real, do usuário atual, 0700; recusa symlink, dono errado, acesso de grupo/outros; **não "conserta" em silêncio**), gera 32 bytes aleatórios (`SecureRandom`), grava `run/pairing.token` de forma atômica com 0600 e escuta em `run/service.sock` (0600). Um início novo = segredo novo: pareamentos antigos deixam de valer. No desligamento remove socket e segredo.
- O painel, a cada sondagem, lê o segredo (rejeitando arquivo/diretório fora da política: symlink, dono errado, permissão de grupo/outros, tamanho/formato inválidos) e executa o desafio-resposta:
  1. cliente → `hello` com nonce novo;
  2. servidor → nonce novo + `serverProof = HMAC-SHA256(segredo, "byx-ipc-v1|server|cn|sn")`;
  3. o cliente **confere a prova do servidor antes de enviar qualquer coisa** (serviço substituto é descartado sem receber a prova do cliente: TESTED);
  4. cliente → `clientProof = HMAC-SHA256(segredo, "byx-ipc-v1|client|cn|sn")`; o servidor confere em tempo constante;
  5. só então `ready` e pedidos.
- O segredo nunca trafega e nunca vai para argv, variável de ambiente, log, PID/nome de processo ou código-fonte; não há segredo fixo no executável.
- **HMAC-SHA256 é a primitiva padrão (JCA); o handshake em si é um protocolo de aplicação PRÓPRIO** (mensagens, rótulos `byx-ipc-v1|server|…` / `…|client|…` e ordem foram desenhados aqui). Ele não é um protocolo padronizado nem teve revisão criptográfica independente. Propriedades verificadas por teste: nonces novos por conexão (replay de prova capturada rejeitado), prova ligada aos dois nonces da conexão, rótulos por direção (uma prova de um lado não vale para o outro) e prova do servidor conferida antes de qualquer envio do cliente.
- **Não há criptografia por mensagem nem TLS.** Depois do handshake os quadros não têm integridade nem confidencialidade próprias; a proteção do canal atual é a do socket Unix e das permissões do SO (diretório 0700, socket 0600), não da camada de aplicação.

### 3.4 O que processos da mesma conta/root NÃO impedem (leia isto)

Qualquer processo do **mesmo usuário** consegue ler `pairing.token` e conectar; um processo **root** ou comprometido da conta pode ler memória, trocar o binário do serviço ou do painel e substituir o socket. Separar em processos **não** é isolamento forte contra isso. O que a fronteira entrega: (a) nenhum acesso por rede, navegador ou outro usuário; (b) o serviço, mesmo alcançado por um processo da mesma conta, só serve status não sensível; (c) um painel/UI que mente sobre papel ou MFA não ganha nada. A proteção mais forte (identidade de código do peer via XPC/`SecCodeCheckValidity`, ou credenciais do peer via `LOCAL_PEERCRED`) exige assinatura/provisionamento ou FFM não finalizado no JDK 21: **capacidades privadas ficam BLOQUEADAS** (`capabilities.features.* = false`, `privateCapabilities = blocked_…`) até lá. O modo atual se chama `development_local_same_user` e o serviço diz isso no próprio contrato.

### 3.5 Garantias e não-garantias (resumo normativo)

| Afirmação | Vale? |
|---|---|
| Quem lê o `pairing.token` consegue falar com o serviço | sim, e **só isso**: posse do token **não identifica o aplicativo nem o usuário do BYX** (identifica "quem lê um arquivo 0600 do usuário do SO") |
| O painel prova que está falando com o serviço que escreveu o token | sim, contra serviços que não conhecem o segredo (impostor); não contra processo que já leu o arquivo |
| O canal é protegido por criptografia própria | **não**: depende do socket e das permissões do SO |
| O handshake é um protocolo padronizado/auditado | **não**: é um protocolo de aplicação próprio sobre HMAC-SHA256, sem revisão criptográfica independente |
| Mesma conta comprometida ou root | **fora da proteção demonstrada** (3.4) |
| Dados privados, conta, admin | **bloqueados**: nenhuma capacidade privada é servida nem honrada |

## 4. Contrato e limites

Protocolo versão 1: quadro = 4 bytes de tamanho (big-endian) + JSON UTF-8.

- **Operações (allowlist fechada):** `health`, `version`, `capabilities` e, só se o serviço foi montado com o feed de mercado (V2.1B, seção 10), `market.status`, `market.subscribe`, `market.unsubscribe`. Sem argumentos. Qualquer outra → `unsupported_operation` (a conexão continua), antes de qualquer efeito. Não existe proxy, shell, leitura de arquivo, URL, SQL, assinatura ou "execute".
- **DTOs estritos:** Jackson com `FAIL_ON_UNKNOWN_PROPERTIES`, sem lixo após o JSON, sem chaves duplicadas, sem coerção de escalares (`"1"` não vira `1`: bug achado pelos testes e corrigido), profundidade ≤ 8, strings ≤ 1024; ids `[A-Za-z0-9_-]{1,64}`.
- **Respostas não sensíveis:** `health` (status, uptime, id da instância aleatório), `version` (serviço, versão, faixa de protocolo), `capabilities` (operações, recursos todos falsos, modo e identidade declarados). Nenhum caminho local, usuário, versão de Java/SO, variável de ambiente ou segredo (TESTED).
- **Limites:** quadro ≤ 8 KiB (o tamanho é validado **antes** de alocar/ler o corpo), ≤ 8 conexões simultâneas (excedente recusado), 1 pedido por vez por conexão (sem fila), ≤ 1000 pedidos por conexão; handshake 3 s, leitura 5 s, ocioso 30 s; cinco provas falhas em 10 s → 2 s de recusa na porta.
- **Cliente (painel):** prazo total de 3 s por sondagem, uma conexão por sondagem, retentativas só para "ninguém escutando" (3 tentativas, 150/500 ms), nenhuma para prazo estourado/pareamento recusado/contrato violado; quadro de resposta ≤ 8 KiB; qualquer texto vindo do serviço é validado por padrão e **nunca** chega à UI (só códigos fixos do painel). O cliente **não honra nenhuma capacidade** além de status, qualquer que seja o que o serviço declare (bug de projeto achado e corrigido).
- **Serviço indisponível:** o painel mostra "Local service" UNAVAILABLE (esperado e neutro se nunca esteve de pé; recuperação/queda representada pelo `RecoveryTracker` existente se esteve). Falhar ou reconectar **não navega, não libera nem retira permissão e não toca a captura**. A sondagem só roda com sessão e para no logout; um resultado em voo de uma geração antiga é descartado.

## 5. Auditoria do login existente (código lido; nenhum segredo ou banco pessoal aberto)

Quem autentica e autoriza hoje: **tudo dentro da JVM do painel.** Autenticação: `AuthService.login` verifica Argon2id contra a tabela `users` do SQLite local (`SqliteUserRepository`, SQL parametrizado). Sessão: `SessionManager` (memória, uma sessão; `UserSession` com `UUID`). Autorização: porta de rota da UI (`PanelApp.evaluateRoute`) **e** barreira dentro dos serviços (`AdminGate.requireAdmin()` → `AdminAccessService.requireAdmin`, usada em `UserService`, `JobManager`, `CaptureMonitorService`, `ByxNetworkService`). Não existe autorização por objeto (aplicação de usuário único) nem fronteira entre processos: qualquer código na JVM é confiável.

| # | Achado (classe.método) | Evidência | Gravidade / efeito | Estado |
|---|---|---|---|---|
| L1 | `PasswordHasher`: Argon2id (BouncyCastle) m=19456 KiB, t=2, p=1, salt 16 B, hash 32 B, formato PHC, comparação com `MessageDigest.isEqual`; `AuthService` usa hash fictício para igualar tempo | leitura de código | **conforme** com o piso OWASP  | **conforme** (sem ação) |
| L2 | `PasswordHasher.verify`: lê m/t/p do hash armazenado sem teto e não há limite de tamanho da senha em `hash/verify`/`PasswordPolicy` | leitura | MÉDIA (DoS local por senha enorme ou hash adulterado); sem *rehash* no login (`AuthService.login`) — necessário planejar para migrar parâmetros  | **pendente** — requisito: teto de tamanho de senha e dos parâmetros m/t/p aceitos do hash armazenado + *rehash* no login; lote (b) |
| L3 | `PasswordPolicy.check`: mínimo 10, só rejeita senha igual ao usuário; sem lista de senhas comuns; sem teto de 64+ declarado | leitura | MÉDIA: NIST 800-63B-4 pede 15 como fator único; aqui o login é fator único para a sessão comum  | **pendente** — requisito: política NIST (comprimento conforme fator, lista de senhas comuns, teto ≥ 64); lote (b) |
| L4 | `InMemoryRateLimiter` (5 falhas/60 s por identificador, só memória): reinício zera; o bloqueio é por identificador (qualquer um pode bloquear a conta alheia); sem progressão; `UserService.contactLimiter` idem | leitura | MÉDIA (DoS de conta e reset por reinício)  | **pendente, requisito definido** — limites persistidos (sobrevivem a reinício), por conta **e** por origem do cliente, atraso progressivo, e o dono não pode ficar bloqueado indefinidamente por tentativas de terceiros (reautenticação/elevação própria); teste: reinício não zera contadores e falhas de outro identificador não bloqueiam o dono além do teto; lote (b) |
| L5 | `AuthService.login` registra `LOGIN_FAILED` com o identificador **como digitado** (`audit.record(..., key, ...)`); `SecurityAuditService.safe` só redige e-mail/telefone | leitura | MÉDIA: uma senha digitada no campo de usuário vai para o log de auditoria  | **corrigido** (`AuthService.login`, `SecurityAuditService`; testes `LoginAuditSafetyTest`) |
| L6 | `SessionManager`/`UserSession`: sem expiração ociosa/absoluta da sessão comum; `AdminSession` expira por inatividade (padrão 30 min, `SecurityConfig`) mas é renovada por qualquer clique/tecla (`PanelApp`: filtros de cena → `adminAccess.touch()`) | leitura | BAIXA/MÉDIA  | **pendente** — requisito: expiração ociosa e absoluta da sessão comum, e renovação da elevação só por ação protegida (não por qualquer clique); lote (b) |
| L7 | `AdminAccessService.current/hasValidAdminSession` usam o usuário **copiado na sessão**; `UserService.setStatus/changeRole` alteram o banco mas não a sessão ativa; `changeOwnPassword` não revoga a sessão admin | leitura | MÉDIA: revogação/rebaixamento só vale após novo login  | **corrigido** (`AdminAccessService.revalidate`, `credentialsChanged`; testes `PrivilegeRevalidationTest`). Residual: dispositivos confiáveis não são revogados em troca de senha (só em troca de contato) e a elevação ainda é renovada por entrada da UI (L6) |
| L8 | `OtpService`: 6 dígitos (`SecureRandom`), validade 5 min, uso único, 5 tentativas, cooldown de reenvio 30 s (`AdminAccessService.sent`); **reenviar gera novo código e zera as tentativas** | leitura | BAIXA (≈10 palpites/min por canal; exige e-mail **e** SMS)  | **pendente** (baixo) — requisito: reenvio não zera tentativas; teto por janela; lote (b) |
| L9 | E-mail/SMS: NIST 800-63B-4 não aceita e-mail como fator fora de banda e restringe SMS; OTP não é resistente a phishing | pesquisa + leitura (`ResendEmailOtpProvider`, `TwilioVerifySmsProvider`) | **limitação aceita**: não chamar de "MFA forte"; evolução = passkeys (não nesta rodada)  | **limitação aceita / fora do modelo atual** — resistência a phishing exige passkeys (lote e) |
| L10 | `DevOtpProvider` ativa-se por `security.dev.mode=true` em `~/.mvp-binance-panel/security.properties` (`SecurityConfig`, `AppContext`): a elevação admin deixa de depender de e-mail/SMS | leitura | ALTA **contra o mesmo usuário** (um processo da conta liga o modo dev e o código fica visível na JVM); o arquivo é 0600, não há proteção adicional  | **corrigido para o OTP** (provedor de desenvolvimento fora do artefato, sem flag; ver seção 9). **Pendente (L10b):** o signer de teste de gás ainda se liga por `security.dev.mode` + variável de ambiente (sem caminho de UI); isolar da mesma forma no lote (b) |
| L11 | `TrustedDeviceService`: token de 32 bytes aleatórios no Keychain (`MacOsKeychainSecretStore`, nomes em allowlist, sem argv), SHA-256 no banco, expira em 30 dias, revogável, exige 2FA recente; leitura nega sem fallback em texto puro (`Unavailable`). **Não comprovado:** a ACL do item (APIs legadas `SecKeychain*` com ACL padrão, criado pelo binário `java`, não por app assinado) — outro programa `java` do usuário poderia lê-lo sem aviso | leitura; Keychain **não** inspecionado | MÉDIA / NÃO VERIFICADA  | **não comprovado** — requisito: item do Keychain com ACL presa a binário assinado (Developer ID) / ao serviço, migrar das APIs `SecKeychain*` depreciadas para `SecItem*` com controle de acesso, e teste no artefato EMPACOTADO (negativa de acesso sem fallback em texto puro, sem ler valores); depende de assinatura; lotes (d)/(e) |
| L12 | `Database.open`: `Files.createDirectories` sem permissões explícitas; `ls` mostra `panel.db` 0644 (o diretório `~/.mvp-binance-panel` hoje é 0700, mas não por código do `Database`) | metadados de `ls`, leitura | MÉDIA em instalação nova (diretório 0755 → banco legível por outros usuários)  | **pendente, requisito definido** — `Database.open` cria diretório 0700 e `.db`/`-wal`/`-shm` 0600 por código, recusa diretório de outro dono ou gravável por grupo (como o serviço), com teste dinâmico em home temporário; **sem** mudar o banco existente (apertar o arquivo atual é ação explícita separada, com aprovação); lote (b) |
| L13 | Recuperação: só por administrador (`UserService.resetPassword` via `AdminGate`, força troca); e-mail de reset indisponível; `createInitialAdmin` aberto enquanto `users==0` | leitura | aceitável; janela de primeiro uso local  | **aceito no modelo local** (recuperação só por administrador); janela de primeiro uso: pendente, requisito = só com presença local do dono |
| L14 | Logs: sem senha/OTP/hash em logs; `PanelApp.onUncaught` imprime a pilha em `stderr` (log local) | leitura | BAIXA  | **aceito / não investigado a fundo** — mensagens de exceção de bibliotecas de terceiros no `stderr` não foram auditadas; lote (b) |
| L15 | Provedores externos (Resend/Twilio) com `HttpClient`, `followRedirects(NEVER)`, hosts fixos | leitura | conforme  | **conforme** (sem ação) |

**Fechamento (seção 9):** L5, L7 e L10 (OTP) foram corrigidos; nenhuma conta, hash, senha, banco ou Keychain foi alterado ou migrado. Os demais continuam como indicado na coluna Estado e alimentam o lote de identidade (seção 7).

## 6. Evidência dos controles de fundação

| ID | Estado | Evidência |
|---|---|---|
| BYX-01 modelo de ameaça / mesmo usuário | IMPLEMENTED | seções 2–3.4 |
| BYX-02 UI forja autorização | TESTED | `forgedAuthorityClaimsAndUnknownOperationsAreDenied` (role/mfa/userId rejeitados; operações fora da lista negadas) |
| BYX-03 listener exposto | TESTED | socket Unix; `lsof -i` do processo real: nenhum TCP/UDP/IPv4/IPv6 (`LocalServiceQa`) |
| BYX-04 peers (nos dois sentidos) | TESTED | cliente sem segredo e pedido antes do auth recusados; serviço impostor recusado sem receber a prova; segredo antigo rejeitado após reinício |
| BYX-05 navegador/Host/Origin | NOT_APPLICABLE | transporte que navegadores não alcançam; se um dia houver TCP/WebSocket, este controle passa a ser exigido |
| BYX-06 limites de recurso | TESTED | quadro grande/truncado/zero/negativo, JSON hostil, timeouts (ocioso, meio pedido, sem hello), limite de conexões e recuperação, limitador de falhas |
| BYX-07 sem proxy genérico | TESTED | allowlist; nenhuma operação aceita argumento |
| BYX-08 resposta atrasada após logout | TESTED (caminho alterado) | `monitorDiscardsAResultThatArrivesAfterStopAndStopsEverything` + `LocalServiceQa` (logout para o monitor, sem estado) |
| BYX-09 permissões/symlinks | TESTED | 0700/0600 reais; diretório de grupo, home symlink, socket ocupado por arquivo comum e home relativo recusados; arquivo alheio nunca apagado; cliente recusa token/diretório/socket fora da política |
| BYX-10 agente não toca a captura | IMPLEMENTED | diff restrito aos dois projetos (ver entrega); nenhum segredo real em fixture/log |

Segredo-canário: o serviço real foi iniciado com `BYX_CANARY_SECRET` no ambiente e payloads com a string no id/campo; nem a saída do processo, nem os logs capturados, nem as respostas contêm o canário, o segredo de pareamento, nonces, provas ou caminhos (TESTED em processo e no processo real).

## 7. Riscos residuais e próximos lotes (não executados)

**Riscos residuais:** processo da mesma conta/root (3.4); sem confidencialidade/integridade do canal depois do handshake; sem identidade de aplicativo do peer (XPC/assinatura indisponíveis); achados pendentes do verificador existente (L2–L4, L6, L8, L10b, L11, L12); sem revisão independente.

**Migração futura do verificador (sem segunda autoridade):** o verificador continua único. O serviço **não** terá um segundo banco de contas nem copiará hashes. Caminho recomendado: (1) o serviço passa a ser a única autoridade de identidade só depois de assumir `users`/`audit_log`/`trusted_devices` por *migração explícita e reversível* (cópia verificada do SQLite com backup, parâmetros Argon2id preservados; *rehash* oportunista no login para elevar parâmetros; nenhuma senha invalidada); (2) sessão = token opaco aleatório emitido pelo serviço, curto, revogável, ligado a geração e logout, distinto do segredo de pareamento; (3) elevação admin é um estado **do serviço** com expiração e revogação próprias (corrigindo L6/L7), nunca um campo enviado pela UI; (4) limites de tentativa persistidos no serviço (corrigindo L4) e política de senha NIST (L2/L3); (5) OTP, dispositivo confiável e recuperação migram com os mesmos testes negativos; e-mail/SMS seguem sendo limitação documentada até passkeys; (6) até o passo 1 existir, o painel continua sendo a autoridade e o serviço não recebe credenciais.

**Lotes propostos (cada um exige nova autorização):** (a) mercado público Binance sem chave, com limites de conexão/REST e dados tratados como não confiáveis; (b) identidade e persistência privada no serviço (seção acima), com correção de L2–L4, L6, L8, L10b, L12; (c) notificações por usuário com `ownership` derivado da sessão do serviço; (d) conta somente leitura, depois de confirmar as permissões reais da exchange e a ACL do Keychain; (e) assinatura Developer ID/Hardened Runtime e XPC/identidade de código do peer, que permite habilitar capacidades privadas.

## 8. Como executar

```bash
# serviço (primeiro plano; Ctrl+C encerra). Socket Unix privado em ~/.byx-local-service/run; nenhuma porta de rede.
/Users/buynnex-corp/dev/byx-local-service/run-service.sh
# testes do serviço (negação dinâmica)
cd /Users/buynnex-corp/dev/byx-local-service && mvn -o test
```

O painel sonda sozinho (a cada 10 s, só com sessão) e mostra "Local service" em System Status e em Diagnostics. `BYX_LOCAL_SERVICE_HOME` muda o diretório do serviço (usado só em testes; o painel aplica a mesma política de permissões).

## 9. Fechamento V2.1A-1 (direcionado)

**Teste pulado.** O único skip dos relatórios Surefire é `panel.CaptureLiveObservationTest.actualRuntimePublishesToJavafxPanel`, motivo "System property [capture.live] does not exist". É um condicional legítimo: observa o runtime REAL da captura e só roda com `-Dcapture.live`; ficar de fora do build é intencional (não deve tocar a captura aqui). Não é controle de segurança nem de V2.1A. Limitação: esse caminho não é exercitado pelo build; pertence ao trabalho do resolver de captura (fora desta rodada).

**Mudanças nesta rodada** (nenhuma conta, hash, senha, banco ou Keychain foi alterado ou migrado; o perfil real do usuário não foi tocado):

- **L5.** `AuthService.login` não grava mais o identificador digitado em `LOGIN_FAILED`: conta existente → o próprio nome de usuário; sem conta → `attempt:<12 hex>` (HMAC-SHA256 com chave aleatória por processo: a tentativa continua registrada e as repetições se correlacionam, mas o conteúdo não pode ser recuperado nem confirmado offline). `SecurityAuditService` neutraliza caracteres de controle e limita o tamanho no gravador. Linhas antigas **não** são apagadas nem reescritas: são mascaradas na leitura (`legacy-attempt`) quando o ator não é um usuário existente. Testes: senha-canário no campo de usuário, quebras de linha/controles (sem linha forjada), contas conhecidas, impressões não correlacionáveis offline, histórico antigo mascarado e intacto em disco, erro de login sem eco.
- **L7.** A validade do privilégio passou a ser decidida contra o repositório (`AdminAccessService.revalidate`), em toda decisão protegida (`requireAdmin`, `hasValidAdminSession`, `evaluate`, `current` dos desafios): conta desativada ou removida encerra a sessão; rebaixamento remove a elevação e o papel (e uma nova promoção não a ressuscita); falha ao ler a fonte **nega** sem derrubar a sessão. Política explícita: trocar a senha ou o reset da senha da conta logada **encerra a elevação administrativa** e cancela desafios/OTPs em curso (`credentialsChanged`, ligado ao `UserService`); a sessão comum do dono continua após a troca da própria senha. Desafios e respostas assíncronas antigos não restauram privilégio (`current` revalida). Mesma autoridade, sem outro gerenciador de sessão. Testes com contas e banco em memória (`PrivilegeRevalidationTest`, 6) e prova de mutação: removida a revalidação, 4 dos 6 e 3 dos 5 de L5 falham. Residual: dispositivos confiáveis não são revogados em troca de senha.
- **L10.** O provedor de OTP de desenvolvimento deixou de existir no artefato: `DevOtpProvider` foi movido para o código de **teste**; o caminho normal só constrói Resend e Twilio Verify e nenhuma configuração, variável de ambiente ou propriedade os troca (`security.dev.mode=true` não tem mais efeito sobre o OTP). A injeção é código explícito (`AppContext.create(..., Providers)`), usada só por harnesses de QA. Prova dinâmica no app real com `security.dev.mode=true` num home temporário: provedores reais, sem rótulo de desenvolvimento, 2FA recusado como NOT CONFIGURED, Research fechado (`docs/qa/v21b/final-prodauth.txt`), e a classe ausente do jar. **Dependência do perfil do usuário:** o perfil real tem `security.dev.mode=false` e provedores configurados (Resend e Twilio, só nomes de chaves verificados), então o acesso dele **não** depende do mecanismo removido; nada foi alterado. Limite: não protege quem pode modificar os próprios binários. Pendente (L10b): o signer de teste de gás ainda é ligado por `security.dev.mode` + variável de ambiente, sem caminho de UI.

**Criptografia do handshake.** Os testes de nonce/replay, direção das provas e vínculo à conexão foram conferidos; a lacuna concreta (replay de prova capturada, prova com outro nonce, autenticação de uma conexão não valendo para outra, nonces do servidor únicos, e replay de prova do servidor contra o cliente) ganhou testes dinâmicos nos dois lados. Isto **não** é revisão criptográfica independente.

**Guards.** As exceções do `FinalSafetyTest` são específicas: HMAC-SHA256 só em `OtpService.mac`, `LocalServiceClient.proof` (rótulo `byx-ipc-v1|`) e `AuthService.fingerprint`, nenhuma outra variante; `ProcessBuilder` com exceção nomeada só para `CaptureRuntimeResolver` (um `lsof` com executável e argumentos literais, e o pid) e `SystemMotionProbe` (dois `defaults read` com argumentos literais), sem shell nem outro caminho de execução; o resolver de captura **não** foi alterado.

### Condições para iniciar o mercado público (lote a)

1. Autorização explícita do lote; nenhuma chave, conta ou ordem Binance (somente endpoints públicos de mercado).
2. Allowlist de destinos (host e caminho), `followRedirects` desligado, TLS padrão verificado, limites de reconexão e de REST compartilhados com a captura (por IP), filas finitas e descarte sob pressão; payload tratado como entrada não confiável (DTOs estritos, limites de tamanho, profundidade e cardinalidade).
3. Nenhum novo dado privado no serviço: continua sendo só mercado público; `capabilities.features.marketData` só vira `true` quando a operação existir e tiver teste; o painel passa a honrar `marketData` explicitamente (hoje ignora tudo) e nada além.
4. Não tocar o runtime nem o resolver de captura; leituras do painel continuam somente leitura.
5. Testes negativos antes do código: destino fora da allowlist, redirect, resposta enorme/malformada, reconexão em laço, indisponibilidade da exchange sem afetar a captura, e canário de segredo nos logs.
6. Antes de habilitar qualquer dado privado (contas, notificações por usuário): lote (b) concluído, incluindo L4, L12 e a decisão de Keychain (L11).

### Composição exata do build testado

| Projeto | O que foi construído | Resultado |
|---|---|---|
| `byx-local-service` | `HEAD d4faccf` (código do serviço inalterado desde `4d7bd49`; o commit `d4faccf` acrescenta só testes) | `mvn -o clean package`: 13 testes, 0 falhas, 0 skips |
| `mvp-binance-panel` (build completo) | `HEAD e521f4c` **+ alterações locais**: as correções L5/L7/L10 e testes (hoje o commit `b972135`) **+ arquivos de captura NÃO commitados de outro trabalho**: `adapter/CaptureRuntimeResolver.java` (sha256 `1f698e7c…`), `adapter/LocalCaptureProcessProbe.java` (`683b11e0…`), `model/CaptureSnapshot.java` (`6966ad91…`), `researchview/CapturePanel.java` (`5b7d0ad5…`), `researchview/CaptureScreen.java` (`11639572…`), `CaptureLiveObservationTest.java` (`2d01b101…`), `CaptureRuntimeResolverTest.java` (`303c8f91…`) | `mvn -o clean package`: 526 testes, 0 falhas, **1 skip** (`CaptureLiveObservationTest`, condicional `-Dcapture.live`); jar sem `DevOtpProvider` |
| `mvp-binance-panel` (HEAD isolado `d4faccf`, checkout limpo sem os arquivos de captura acima) | **não** foi feito o build completo: foram rodados compilação e 78 testes direcionados (`FinalSafetyTest`, `PrivilegeRevalidationTest`, `LoginAuditSafetyTest`, `ProductionOtpIsolationTest`, `LocalServiceClientTest`, `SystemScreensTest`, `AuthSecurityTest`, `TrustedDeviceSecurityTest`): 0 falhas | HEAD isolado compila e passa esses testes; **não** é um build completo reproduzido |

Observações: depois do `package` completo, o único ajuste foi no `FinalSafetyTest` (a exceção do resolver de captura passa a existir só se o arquivo existir, para o HEAD isolado não depender de arquivos não commitados); `FinalSafetyTest` foi rodado nas duas composições (com e sem o resolver). O resolver de captura e os demais arquivos de captura não foram modificados nem commitados por este trabalho. QAs do app real depois das correções (composição com os arquivos de captura): `AuthFlowQa`, `ShellNavigationQa` (idle de 5 s), `FinalQa` public/unsaved/system/onboarding/prodauth, todos 0 falhas.


## 10. V2.1B — mercado público Binance (ETHUSDT, USDⓈ-M Futures)

**Escopo.** Somente dado público de mercado. Sem conta, chave, segredo, User Data Stream, ordens, posições, notificações privadas, TOTP ou recuperação. A rota `/private` e qualquer endpoint assinado são recusados pela allowlist e testados.

**Arquitetura.** `painel → socket Unix local (protocolo tipado) → byx-local-service → Binance pública`. O painel nunca abre conexão com a Binance (há um teste que varre o código do painel). O serviço é o único dono do feed e não compartilha estado com a captura científica (`mvp-binance`, gravador, `.part`, `CaptureRuntimeResolver`): são dois consumidores independentes da Binance.

**Contrato Binance usado (documentação oficial atual, conferida ao vivo em 2026-10-06).**

| Rota | Stream | Observação |
|---|---|---|
| `wss://fstream.binance.com/public/stream?streams=ethusdt@depth@100ms` | depth diferencial | PUBLIC (alta frequência) |
| `wss://fstream.binance.com/market/stream?streams=ethusdt@aggTrade/ethusdt@markPrice@1s/ethusdt@ticker/ethusdt@kline_1m` | trades agregados, mark price, ticker 24 h, kline 1 m | MARKET (regular) |
| `GET https://fapi.binance.com/fapi/v1/depth?symbol=ETHUSDT&limit=1000` | snapshot do book (peso 20) | só para sincronizar/ressincronizar |
| `GET https://fapi.binance.com/fapi/v1/klines?symbol=ETHUSDT&interval=1m&limit=120` | bootstrap dos candles (peso 1) | uma vez por conexão MARKET |

Mapeamento confirmado: `depth` só entrega na rota `/public` e `aggTrade/markPrice/ticker/kline` só na `/market` (o inverso não entrega nada). Duas conexões é o mínimo. Diferenças/observações registradas: o payload atual traz `ps` e `st` (st=1 USDⓈ-M, st=2 COIN-M; `depth` traz `ps`, os demais `st`, `kline` nenhum), validados **quando presentes**; o exemplo da documentação mostra `ps` de outro par, mas ao vivo `ps` é o próprio `ETHUSDT`; a regra de alinhamento do futures é `U <= lastUpdateId && u >= lastUpdateId` (não a `lastUpdateId+1` do spot). Limites documentados: conexão válida por 24 h, ping do servidor a cada 3 min (pong em até 10 min — o cliente HTTP da JVM responde sozinho), no máximo 10 mensagens/s de entrada (o serviço só envia pong), 1024 streams por conexão (usamos 1 e 4).

**Allowlist.** Comparação EXATA com 4 URIs fixas (`Allowlist`): outro host, host parecido (`…binance.com.evil.com`, userinfo), caminho, esquema (`http`, `ws`), porta, símbolo, `limit`, query extra ou fragmento, `/private`, order/account/listenKey/assinatura — tudo recusado antes de abrir socket (testes dinâmicos). Redirecionamento HTTP desligado (3xx é erro; o alvo nunca é requisitado), TLS padrão da JVM sem contexto/verificador próprio, nenhum cabeçalho de credencial, corpo REST ≤ 256 KiB (também por `Content-Length`), mensagem WebSocket ≤ 128 KiB.

**Book local (procedimento oficial).** Buffer de eventos → snapshot REST → descarta `u < lastUpdateId` → primeiro evento com `U <= lastUpdateId <= u` → daí em diante `pu == u` do evento anterior; quantidade 0 remove o nível; evento fora de contiguidade, book cruzado, buffer > 1500 eventos, símbolo ou `st`/`ps` errado, evento malformado ⇒ book invalidado, `RESYNCING`, novo snapshot (com espaçamento 1 s → 60 s e recuo crescente). Nunca interpola, nunca fabrica id, nunca mostra book como vivo depois de lacuna (`topBids/topAsks` vazios fora de LIVE). Internamente ≤ 1500 níveis por lado.

**Reconexão e rotação de 24 h.** Recuo exponencial com jitter (1 s → 60 s, metade fixa + metade aleatória), nunca laço agressivo. Rotação programada a 23 h (+ jitter ≤ 10 min por conexão) e fechamento esperado perto das 24 h reconectam já, sem contar como falha. Silêncio > 30 s recicla a conexão. Depois de reconectar o estado volta a CONNECTING/RECONNECTING e só é LIVE com book alinhado + candles + ticker. 418/429 (REST ou handshake): toda atividade pausa (mín. 60 s, dobra a cada reincidência, respeita `Retry-After`, teto 2 h), estado `ERROR rate_limited`, fail-closed. O feed só existe enquanto há assinante local (e 30 s depois); sem painel, nenhuma conexão com a Binance.

**Protocolo local.** `market.status` (estado leve, não inicia o feed), `market.subscribe` (sem argumento; a resposta precede o primeiro evento), `market.unsubscribe`. Eventos tipados `state`, `book` (top 20/lado, **sempre snapshot consistente, nunca delta**), `trades` (≤ 50, mais novos primeiro), `candles` (≤ 120, completo ao assinar e quando a estrutura muda) e `candle` (a vela em andamento). Sem fila: o assinante só guarda versões já enviadas e a cada 250 ms envia o que mudou da visão mais nova do feed ⇒ memória constante, preço/trades coalescidos, book nunca com delta perdido. Batimento de estado a cada 2 s. Cliente lento: a escrita tem prazo (5 s) e o watchdog fecha SÓ aquela conexão; o feed nunca bloqueia. Fechar a conexão cancela a assinatura; ≤ 4 assinantes e 1 por conexão (reconectar localmente não acumula).

**Limites.** Pedidos/respostas/handshake continuam ≤ 8 KiB. Eventos de mercado (só servidor→cliente em conexão assinada) ≤ 16 KiB (`Protocol.MAX_MARKET_FRAME`; pior caso medido por teste: ~8 KiB de candles); o leitor recusa o cabeçalho acima disso antes de alocar. Fila interna do feed 4096 (estouro ⇒ a conexão é abortada e reconectada, a fila acumulada é descartada), publicação ≤ 10 visões/s, REST uma requisição por vez.

**Capacidade vs estado.** `capabilities.features.marketData` = `true` só quando o serviço é montado com o feed (`ServiceMain`); sem feed continua `false` e as operações `market.*` são `unsupported_operation`. `marketData=true` com `feed=DISCONNECTED` é válido. O painel honra `marketData` e nada mais (conta, notificações e administração seguem falsas por construção).

**Logs.** Só eventos e códigos fixos; nenhum corpo, URL, cabeçalho, caminho ou conteúdo de mensagem (teste com canário). 

**Testes (serviço).** `AllowlistTest`, `MarketEventsTest`, `DepthBookTest`, `MarketFeedTest`, `ServiceMarketIpcTest`; fumaça manual opt-in contra a Binance real fora da suíte.


## 11. V2.1C (endurecimento antes de capacidades privadas)
L4 (limitador persistente), L12 (permissões do banco), L10b (signer de teste removido do produto), a auditoria L11 (Keychain/identidade do app: **BLOCKED ON APP SIGNING**), a decisão estática `PRIVATE_CAPABILITIES_ALLOWED = false` e o modelo de autoridade futuro estão em [`PRIVATE_CAPABILITY_GATE.md`](PRIVATE_CAPABILITY_GATE.md). Nesta tabela, L4, L10b e L12 passam a **corrigidos** (L12 para homes novos; o banco real não foi alterado), L11 continua **não comprovado/BLOQUEADO** por falta de assinatura do app.


## 12. V2.1D (empacotamento macOS e identidade do app)
App `BYX-MVP.app` assinado (Hardened Runtime, runtime Java embutido, único entitlement `allow-jit`) e identidade do peer verificada pelo kernel/Security.framework em modo `packaged_verified`: ver [`APP_IDENTITY.md`](APP_IDENTITY.md). O `pairing.token` deixa de ser a única barreira no modo empacotado; o gate de capacidades privadas continua `false`.


## 13. V2.1E (armazenamento seguro de segredos)
API interna tipada (`SecretId`/`SecretBytes`/`SecretStore`) sobre `SecItem*` (proteção de dados), sem IPC de segredo. Estado: **BLOCKED ON PROVISIONING** e **FINAL BUNDLE ID REQUIRED**; nada permanente criado, nenhum segredo real tocado. Ver [`SECURE_SECRET_STORE.md`](SECURE_SECRET_STORE.md).


## 14. V2.1E-1 (IDs finais, provisioning e prova do canário)
IDs finais `com.buynnex.byx[.service]`; perfil de desenvolvimento (Personal Team, 7 dias); helper aninhado; CRUD do canário no keychain de proteção de dados e matriz negativa real aprovados (18 PASS/0 FAIL/1 SKIP); identidade do app reverificada (29 PASS). `secure_secret_storage` satisfeito; o gate continua `false` (autoridade pendente). Ver [`SECURE_SECRET_STORE.md`](SECURE_SECRET_STORE.md).
