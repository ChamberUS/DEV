# BYX local service — fundação de segurança (V2.1A)

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

**Escolhida: A.** Menos superfície, sem dependência nova e sem criptografia própria no transporte. TLS (B) não se justifica enquanto o único adversário a que acrescentaria algo (um processo que alcança a porta) já seria um processo da mesma conta que lê o material do certificado. XPC com requisito de assinatura do peer é o caminho de proteção mais forte no macOS, mas exige binários assinados/provisionados e integração nativa que não existem neste ambiente de desenvolvimento: **limite declarado** (3.4), não esquecido.

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
- Segredo nunca trafega, nunca vai para argv, variável de ambiente, log, PID/nome de processo ou código-fonte; não há segredo fixo no executável. Usa-se apenas HMAC-SHA256 padrão (JCA), com rótulos por direção (uma prova de um lado não serve de oráculo para o outro). **Limite:** não há integridade nem confidencialidade do canal após o handshake (nenhum TLS); em socket Unix privado isso só importaria para um processo que já pode ler o segredo.

### 3.4 O que processos da mesma conta/root NÃO impedem (leia isto)

Qualquer processo do **mesmo usuário** consegue ler `pairing.token` e conectar; um processo **root** ou comprometido da conta pode ler memória, trocar o binário do serviço ou do painel e substituir o socket. Separar em processos **não** é isolamento forte contra isso. O que a fronteira entrega: (a) nenhum acesso por rede, navegador ou outro usuário; (b) o serviço, mesmo alcançado por um processo da mesma conta, só serve status não sensível; (c) um painel/UI que mente sobre papel ou MFA não ganha nada. A proteção mais forte (identidade de código do peer via XPC/`SecCodeCheckValidity`, ou credenciais do peer via `LOCAL_PEERCRED`) exige assinatura/provisionamento ou FFM não finalizado no JDK 21: **capacidades privadas ficam BLOQUEADAS** (`capabilities.features.* = false`, `privateCapabilities = blocked_…`) até lá. O modo atual se chama `development_local_same_user` e o serviço diz isso no próprio contrato.

## 4. Contrato e limites

Protocolo versão 1: quadro = 4 bytes de tamanho (big-endian) + JSON UTF-8.

- **Operações (allowlist fechada):** `health`, `version`, `capabilities`. Sem argumentos. Qualquer outra → `unsupported_operation` (a conexão continua), antes de qualquer efeito. Não existe proxy, shell, leitura de arquivo, URL, SQL, assinatura ou "execute".
- **DTOs estritos:** Jackson com `FAIL_ON_UNKNOWN_PROPERTIES`, sem lixo após o JSON, sem chaves duplicadas, sem coerção de escalares (`"1"` não vira `1`: bug achado pelos testes e corrigido), profundidade ≤ 8, strings ≤ 1024; ids `[A-Za-z0-9_-]{1,64}`.
- **Respostas não sensíveis:** `health` (status, uptime, id da instância aleatório), `version` (serviço, versão, faixa de protocolo), `capabilities` (operações, recursos todos falsos, modo e identidade declarados). Nenhum caminho local, usuário, versão de Java/SO, variável de ambiente ou segredo (TESTED).
- **Limites:** quadro ≤ 8 KiB (o tamanho é validado **antes** de alocar/ler o corpo), ≤ 8 conexões simultâneas (excedente recusado), 1 pedido por vez por conexão (sem fila), ≤ 1000 pedidos por conexão; handshake 3 s, leitura 5 s, ocioso 30 s; cinco provas falhas em 10 s → 2 s de recusa na porta.
- **Cliente (painel):** prazo total de 3 s por sondagem, uma conexão por sondagem, retentativas só para "ninguém escutando" (3 tentativas, 150/500 ms), nenhuma para prazo estourado/pareamento recusado/contrato violado; quadro de resposta ≤ 8 KiB; qualquer texto vindo do serviço é validado por padrão e **nunca** chega à UI (só códigos fixos do painel). O cliente **não honra nenhuma capacidade** além de status, qualquer que seja o que o serviço declare (bug de projeto achado e corrigido).
- **Serviço indisponível:** o painel mostra "Local service" UNAVAILABLE (esperado e neutro se nunca esteve de pé; recuperação/queda representada pelo `RecoveryTracker` existente se esteve). Falhar ou reconectar **não navega, não libera nem retira permissão e não toca a captura**. A sondagem só roda com sessão e para no logout; um resultado em voo de uma geração antiga é descartado.

## 5. Auditoria do login existente (código lido; nenhum segredo ou banco pessoal aberto)

Quem autentica e autoriza hoje: **tudo dentro da JVM do painel.** Autenticação: `AuthService.login` verifica Argon2id contra a tabela `users` do SQLite local (`SqliteUserRepository`, SQL parametrizado). Sessão: `SessionManager` (memória, uma sessão; `UserSession` com `UUID`). Autorização: porta de rota da UI (`PanelApp.evaluateRoute`) **e** barreira dentro dos serviços (`AdminGate.requireAdmin()` → `AdminAccessService.requireAdmin`, usada em `UserService`, `JobManager`, `CaptureMonitorService`, `ByxNetworkService`). Não existe autorização por objeto (aplicação de usuário único) nem fronteira entre processos: qualquer código na JVM é confiável.

| # | Achado (classe.método) | Evidência | Gravidade / efeito |
|---|---|---|---|
| L1 | `PasswordHasher`: Argon2id (BouncyCastle) m=19456 KiB, t=2, p=1, salt 16 B, hash 32 B, formato PHC, comparação com `MessageDigest.isEqual`; `AuthService` usa hash fictício para igualar tempo | leitura de código | **conforme** com o piso OWASP |
| L2 | `PasswordHasher.verify`: lê m/t/p do hash armazenado sem teto e não há limite de tamanho da senha em `hash/verify`/`PasswordPolicy` | leitura | MÉDIA (DoS local por senha enorme ou hash adulterado); sem *rehash* no login (`AuthService.login`) — necessário planejar para migrar parâmetros |
| L3 | `PasswordPolicy.check`: mínimo 10, só rejeita senha igual ao usuário; sem lista de senhas comuns; sem teto de 64+ declarado | leitura | MÉDIA: NIST 800-63B-4 pede 15 como fator único; aqui o login é fator único para a sessão comum |
| L4 | `InMemoryRateLimiter` (5 falhas/60 s por identificador, só memória): reinício zera; o bloqueio é por identificador (qualquer um pode bloquear a conta alheia); sem progressão; `UserService.contactLimiter` idem | leitura | MÉDIA (DoS de conta e reset por reinício) |
| L5 | `AuthService.login` registra `LOGIN_FAILED` com o identificador **como digitado** (`audit.record(..., key, ...)`); `SecurityAuditService.safe` só redige e-mail/telefone | leitura | MÉDIA: uma senha digitada no campo de usuário vai para o log de auditoria |
| L6 | `SessionManager`/`UserSession`: sem expiração ociosa/absoluta da sessão comum; `AdminSession` expira por inatividade (padrão 30 min, `SecurityConfig`) mas é renovada por qualquer clique/tecla (`PanelApp`: filtros de cena → `adminAccess.touch()`) | leitura | BAIXA/MÉDIA |
| L7 | `AdminAccessService.current/hasValidAdminSession` usam o usuário **copiado na sessão**; `UserService.setStatus/changeRole` alteram o banco mas não a sessão ativa; `changeOwnPassword` não revoga a sessão admin | leitura | MÉDIA: revogação/rebaixamento só vale após novo login |
| L8 | `OtpService`: 6 dígitos (`SecureRandom`), validade 5 min, uso único, 5 tentativas, cooldown de reenvio 30 s (`AdminAccessService.sent`); **reenviar gera novo código e zera as tentativas** | leitura | BAIXA (≈10 palpites/min por canal; exige e-mail **e** SMS) |
| L9 | E-mail/SMS: NIST 800-63B-4 não aceita e-mail como fator fora de banda e restringe SMS; OTP não é resistente a phishing | pesquisa + leitura (`ResendEmailOtpProvider`, `TwilioVerifySmsProvider`) | **limitação aceita**: não chamar de "MFA forte"; evolução = passkeys (não nesta rodada) |
| L10 | `DevOtpProvider` ativa-se por `security.dev.mode=true` em `~/.mvp-binance-panel/security.properties` (`SecurityConfig`, `AppContext`): a elevação admin deixa de depender de e-mail/SMS | leitura | ALTA **contra o mesmo usuário** (um processo da conta liga o modo dev e o código fica visível na JVM); o arquivo é 0600, não há proteção adicional |
| L11 | `TrustedDeviceService`: token de 32 bytes aleatórios no Keychain (`MacOsKeychainSecretStore`, nomes em allowlist, sem argv), SHA-256 no banco, expira em 30 dias, revogável, exige 2FA recente; leitura nega sem fallback em texto puro (`Unavailable`). **Não comprovado:** a ACL do item (APIs legadas `SecKeychain*` com ACL padrão, criado pelo binário `java`, não por app assinado) — outro programa `java` do usuário poderia lê-lo sem aviso | leitura; Keychain **não** inspecionado | MÉDIA / NÃO VERIFICADA |
| L12 | `Database.open`: `Files.createDirectories` sem permissões explícitas; `ls` mostra `panel.db` 0644 (o diretório `~/.mvp-binance-panel` hoje é 0700, mas não por código do `Database`) | metadados de `ls`, leitura | MÉDIA em instalação nova (diretório 0755 → banco legível por outros usuários) |
| L13 | Recuperação: só por administrador (`UserService.resetPassword` via `AdminGate`, força troca); e-mail de reset indisponível; `createInitialAdmin` aberto enquanto `users==0` | leitura | aceitável; janela de primeiro uso local |
| L14 | Logs: sem senha/OTP/hash em logs; `PanelApp.onUncaught` imprime a pilha em `stderr` (log local) | leitura | BAIXA |
| L15 | Provedores externos (Resend/Twilio) com `HttpClient`, `followRedirects(NEVER)`, hosts fixos | leitura | conforme |

**Não alterado nesta rodada** (por escopo): nenhuma conta, hash, senha, regra de política, limitador ou fluxo de OTP foi modificado. Estes achados alimentam o lote de identidade (seção 7).

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

**Riscos residuais:** processo da mesma conta/root (3.4); sem confidencialidade/integridade do canal depois do handshake; sem identidade de aplicativo do peer (XPC/assinatura indisponíveis); achados L2–L12 do verificador existente; sem revisão independente.

**Migração futura do verificador (sem segunda autoridade):** o verificador continua único. O serviço **não** terá um segundo banco de contas nem copiará hashes. Caminho recomendado: (1) o serviço passa a ser a única autoridade de identidade só depois de assumir `users`/`audit_log`/`trusted_devices` por *migração explícita e reversível* (cópia verificada do SQLite com backup, parâmetros Argon2id preservados; *rehash* oportunista no login para elevar parâmetros; nenhuma senha invalidada); (2) sessão = token opaco aleatório emitido pelo serviço, curto, revogável, ligado a geração e logout, distinto do segredo de pareamento; (3) elevação admin é um estado **do serviço** com expiração e revogação próprias (corrigindo L6/L7), nunca um campo enviado pela UI; (4) limites de tentativa persistidos no serviço (corrigindo L4) e política de senha NIST (L2/L3); (5) OTP, dispositivo confiável e recuperação migram com os mesmos testes negativos; e-mail/SMS seguem sendo limitação documentada até passkeys; (6) até o passo 1 existir, o painel continua sendo a autoridade e o serviço não recebe credenciais.

**Lotes propostos (cada um exige nova autorização):** (a) mercado público Binance sem chave, com limites de conexão/REST e dados tratados como não confiáveis; (b) identidade e persistência privada no serviço (seção acima), com correção de L2–L8, L10, L12; (c) notificações por usuário com `ownership` derivado da sessão do serviço; (d) conta somente leitura, depois de confirmar as permissões reais da exchange e a ACL do Keychain; (e) assinatura Developer ID/Hardened Runtime e XPC/identidade de código do peer, que permite habilitar capacidades privadas.

## 8. Como executar

```bash
# serviço (primeiro plano; Ctrl+C encerra). Socket Unix privado em ~/.byx-local-service/run; nenhuma porta de rede.
/Users/buynnex-corp/dev/byx-local-service/run-service.sh
# testes do serviço (negação dinâmica)
cd /Users/buynnex-corp/dev/byx-local-service && mvn -o test
```

O painel sonda sozinho (a cada 10 s, só com sessão) e mostra "Local service" em System Status e em Diagnostics. `BYX_LOCAL_SERVICE_HOME` muda o diretório do serviço (usado só em testes; o painel aplica a mesma política de permissões).
