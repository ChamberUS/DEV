# C4 — Windows secure IPC RFC

Data: 2026-10-09. Estado: **DESIGN_READY_FOR_REVIEW**, proposta sem implementação de transporte. Branch `feature/byx-windows-readiness-v1`; base publicada `625689b2021c4c6a5f4a409d5d4680bccc865cbc`. Evidência local: Windows 11 Pro 10.0.26200 x64, Temurin 21.0.12.1, Maven 3.9.9, JavaFX 21.0.5.

## 1. Contrato macOS existente

A fronteira permanece **Panel → Service → CustodyClient → Signer → armazenamento seguro da plataforma**. O Panel apresenta estado; não decide autoridade, não recebe chave privada e não acessa diretamente o Signer/cofre. Não houve alteração ou execução do Service, Signer ou pipeline ETHUSDT nesta tarefa.

Código examinado no repositório:

- Panel: `panel.localservice.LocalServiceClient`, `AuthorityClient`, `MarketFeedClient`; `panel.security.ServerAuthorization` e isolamento de sessão.
- Service: `RuntimeDir`, `ServiceInstance`, `ServiceMain`; `identity.IdentityPolicy/PeerIdentity/CodeIdentity/MacSecurity/PeerKeys`; `auth.AuthComposition/SessionStore/AuthLimits`; documentação `APP_IDENTITY.md`, `SECURITY_FOUNDATION.md`, `AUTHORITY_MODEL.md`, `SECURE_SECRET_STORE.md`.
- Os comentários/documentos antigos da foundation que dizem “sem contas” ou “ServiceMain não monta autenticação” são históricos. O código atual de `ServiceMain` compõe autoridade real com perfil e cofre de produção, e `TxProduction.disabled`. Nenhuma conclusão de autorização usa esses comentários antigos.

O runtime é privado ao usuário: diretório 0700, token/socket 0600, proprietário atual, arquivos sem links e caminho de socket limitado. A instância escreve segredo novo de pareamento e abre socket UNIX; teardown fecha conexões, watchdog/workers e remove socket/token. Panel rejeita arquivo inseguro, token malformado ou leitura grande antes de conectar.

O protocolo usa frames limitados a 8 KiB e handshake mútuo HMAC-SHA256, com rótulo, nonce de cliente e nonce de servidor. O cliente verifica o servidor antes de enviar sua prova. Tamanho, versão, formato e operações são estritos. Pareamento não concede papel nem custódia. Cliente possui prazo de conexão total de 3 s; chamadas de autoridade possuem limites próprios, e o Service limita conexões/handshake/leitura/escrita/ociosidade. O feed mantém backoff, um canal por geração, cancelamento e descarte de eventos tardios.

No modo empacotado, ambos verificam identidade de código do peer **antes da autenticação de aplicação**. `LOCAL_PEERTOKEN` fornece token de auditoria do kernel; código vivo, requisito de componente/Team, selo e ambiente de lançamento são examinados. A chave de peer utiliza PID **e pidversion**, evitando tratar PID reciclado como instância antiga. O caminho informado pelo cliente não é prova.

`SessionStore` usa tokens aleatórios de 256 bits (43 caracteres), armazena hash, vincula conta/versão de credencial/peer, expira e revoga. `AuthLimits` atual define 8 h absolutas, 15 min de idle e elevação administrativa de 5 min. Sessão IPC pareada e sessão de usuário autenticada são estados diferentes; backend decide operação/papel/MFA. O cofre macOS não se reduz a um arquivo privado ao usuário: a política de acesso do Service por identidade de código é parte do contrato.

`IdentityPolicy.detect` possui fallback histórico para desenvolvimento quando a verificação macOS não existe. **Esse fallback não é proposta para produção Windows**. O port deverá falhar fechado quando a plataforma não tiver identidade qualificada; não promover “development” a conexão Windows autenticada.

## 2. Limitação Windows observada

O adaptador ACL do Panel permite armazenamento privado local e a janela pública DEFAULT. Ele não porta runtime/socket/identidade/cofre do Service. `LocalServiceClient.loadSecret` recusa Windows com `native_service_unsupported` antes de ler token/conectar. Esse guard permanece intacto.

A sonda [WindowsTransportProbe.java](../qa/package-c4/WindowsTransportProbe.java), executada por Java 21 neste host, abriu somente um **SocketChannel UNIX não ligado e não conectado**. [Resultado](../qa/package-c4/c4-1-f-transport-probe.log): `UNIX_CHANNEL_OPEN=true`, `SO_PEERCRED_SUPPORTED=false`, opções `SO_SNDBUF, SO_RCVBUF, SO_LINGER`. Não abriu servidor, endpoint, sessão nem autenticação. A API de transporte existir não demonstra peer identity.

Os testes originais usam runtime POSIX privado e fake servers de protocolo. O guard da fixture recusa ambiente não qualificado; não substituímos credenciais do kernel, aceitação do verifier nem resultados de autenticação. Os testes portáveis de parser/contrato e negação Windows continuam ativos. O fato de a fixture se chamar FakeService não autoriza adaptar o teste para alegar identidade nativa real.

## 3. Inventário exato dos 23 ERROR

Matriz auditável completa, com todas as colunas solicitadas e frames por método: [CSV](../qa/package-c4/c4-1-f-error-matrix.csv) e [JSON](../qa/package-c4/c4-1-f-error-matrix.json). Os relatórios históricos preservados contêm a exceção e stack completos; o novo inventário compara as mesmas identidades de casos após regressão.

Cada um dos 23 reports foi examinado individualmente. Tipo observado: `java.io.IOException`. Mensagem exata comum:

`native_ipc_fixture_unqualified: POSIX pairing and native peer identity required; no Windows substitute`

Primeiro frame significativo: `panel.localservice.IpcTestFiles.requirePosixPairing(IpcTestFiles.java:18)`. Chain/Local seguem por `FakeService.java:39`; Market segue por `FakeMarketService.java:46`. Recusa antes da criação de token/endpoint. Isso explica o erro inicial; não prova que as asserções posteriores passariam, nem que não existe defeito de produto.

Categorias: A problema exclusivo de harness Windows; B fixture POSIX; C produto Windows ausente; D autenticação/identidade; E regressão real; F desconhecido. Todos têm **B observado e C de implementação**. D é direto nos casos de identidade/prova/pareamento/restart e pré-requisito nos demais. Nenhum A corrigível isoladamente preservando toda a invariância foi encontrado entre os 23; E não foi demonstrado. A correção anterior de temporários já está preservada. Os cenários posteriores continuam não qualificados, não “erros esperados aprovados”.

| # | Classe | Método | Modos/fixture exercitados | Invariante original | Classificação |
|---|---|---|---|---|---|
| 1 | `ChainStatusClientTest` | `notConfiguredIsTheProductionAnswerAndCarriesNothingAboutAChain` | GOOD | Typed NOT_CONFIGURED; no invented chain/network facts | B → C; D como pré-requisito |
| 2 | `ChainStatusClientTest` | `aValidStatusIsReadOverTheTypedChannelWithNoArguments` | GOOD | Argument-free typed chain.status; honest server result | B → C; D como pré-requisito |
| 3 | `ChainStatusClientTest` | `anAbsentHostileOrBrokenServiceIsAnErrorNeverHealthy` | IMPOSTOR,GARBAGE,REJECTS_CLIENT,OVERSIZE | Absent/hostile/broken service never healthy; absent assertions run before fixture refusal | B → C; D como pré-requisito |
| 4 | `LocalServiceClientTest` | `packagedClientRefusesAServiceWhoseCodeIdentityIsNotVerifiedEvenWithTheRightSecret` | GOOD; strict verifier deny AND accept branches | Code identity before hello despite correct pairing; both original branches remain | B → C; D direto |
| 5 | `LocalServiceClientTest` | `restartIsReadFreshAndNeverReusesTheOldPairing` | GOOD twice | Fresh pairing and fresh Service generation after restart; no stale connected state | B → C; D direto |
| 6 | `LocalServiceClientTest` | `oversizedGarbageAndOutOfContractResponsesAreRejected` | OVERSIZE,GARBAGE,INCOMPATIBLE_PROTOCOL,HOSTILE_STRINGS | Allocation cap, strict protocol/version and untrusted-string rejection | B → C; D como pré-requisito |
| 7 | `LocalServiceClientTest` | `unsafeOrInvalidPairingFilesAreRefusedWithoutConnecting` | GOOD; mode/token/link variants | 0700 directory/0600 token; current owner; no symlink; bounded valid 32-byte secret; no connection on invalid file | B → C; D direto |
| 8 | `LocalServiceClientTest` | `pairedServiceIsReadAndPrivateCapabilitiesAreNeverTrusted` | GOOD | Paired status readable; backend private capability claims never authorize Panel | B → C; D como pré-requisito |
| 9 | `LocalServiceClientTest` | `aServiceThatRejectsOurProofIsAuthFailedNotConnected` | REJECTS_CLIENT | Rejected client HMAC maps to AUTH_FAILED; never connected | B → C; D direto |
| 10 | `LocalServiceClientTest` | `anImpostorThatDoesNotKnowTheSecretIsRejectedBeforeAnyProofIsSent` | IMPOSTOR | Verify server proof before disclosing client proof or requests | B → C; D direto |
| 11 | `LocalServiceClientTest` | `statusTextNeverCarriesPathsOrSecrets` | All FakeService modes except STALL | Actual wire-derived status fields redact paths and pairing secret; not only static status factories | B → C; D como pré-requisito |
| 12 | `LocalServiceClientTest` | `aServerProofReplayedFromAnEarlierConversationIsRejected` | REPLAYED_SERVER_PROOF | Nonce-bound server proof; no client auth/request sent to replaying endpoint | B → C; D direto |
| 13 | `LocalServiceClientTest` | `silentServiceTimesOutAndIsNotRetriedForever` | STALL | TOTAL_TIMEOUT_MS + 1500ms bound and TIMEOUT result; no unbounded retry | B → C; D como pré-requisito |
| 14 | `MarketFeedClientTest` | `aServiceWithoutTheMarketCapabilityIsUnsupportedNotLive` | UNSUPPORTED | Missing capability means UNSUPPORTED; no populated live market fields | B → C; D como pré-requisito |
| 15 | `MarketFeedClientTest` | `anEventAfterStopNeverChangesTheState` | LATE_EVENT | Stopped generation ignores late events and stays IDLE with cleared data | B → C; D como pré-requisito |
| 16 | `MarketFeedClientTest` | `reconnectsWithBackoffAndNeverHoldsTwoSessions` | DROP_AFTER_FIRST_STATE | Bounded backoff; max one open session; 3 accepts in 15s and <=6 accepts | B → C; D como pré-requisito |
| 17 | `MarketFeedClientTest` | `stopCancelsTheSubscriptionClearsDataAndIsIdempotent` | GOOD | Stop closes subscription/peer and clears data; repeated stop safe; restart one session | B → C; D como pré-requisito |
| 18 | `MarketFeedClientTest` | `everyContractViolationDropsTheSessionAndNeverBecomesData` | BAD_TOPIC,CROSSED_BOOK,TOO_MANY_LEVELS,UNSORTED_BOOK,NON_INCREASING_SEQ,BOOK_WITH_LEVELS_WHILE_NOT_LIVE,NAN_PRICE,WRONG_SYMBOL,UNKNOWN_FEED,CANDLE_GAP_ORDER | Each hostile event drops session and never becomes accepted data | B → C; D como pré-requisito |
| 19 | `MarketFeedClientTest` | `subscribeCarriesNoArgumentsAndNothingElseIsEverRequested` | GOOD | Exact fixed market.subscribe request; no client-selected symbol or arbitrary operations | B → C; D como pré-requisito |
| 20 | `MarketFeedClientTest` | `anOversizedDeclaredEventIsRefusedBeforeAllocating` | OVERSIZE_EVENT | Reject oversized declared frame before allocation; LOST without data | B → C; D como pré-requisito |
| 21 | `MarketFeedClientTest` | `streamsRealShapedEventsIntoTypedData` | GOOD | Typed last/mark/index/time/trades/book/candles; coalesced notifications; no fabricated production feed | B → C; D como pré-requisito |
| 22 | `MarketFeedClientTest` | `silentServiceIsDetectedAndTheLinkIsDropped` | SILENT_AFTER_ACK | Acked STREAMING then bounded silence watchdog (~6s) reaches LOST | B → C; D como pré-requisito |
| 23 | `MarketFeedClientTest` | `lostLinkKeepsLastDataAndNeverClaimsLive` | DROP_AFTER_FIRST_STATE | Retain last observation during reconnect; never label stale data LIVE | B → C; D como pré-requisito |

Os modos estão registrados para evitar perder subcenários agregados. Exemplo: o teste de identidade contém rejeição **e** aceitação originais; substituir por um teste de recusa Windows perderia a metade positiva. O teste de redaction percorre respostas reais da fixture, não somente factories estáticas. O teste de arquivos inseguros conserva POSIX no macOS e precisará equivalente ACL genuíno; não trocar octal por “arquivo existe”. A recomendação para todos é manter o original e adicionar a futura versão Windows contra uma implementação realmente autenticada. Não foram excluídos suites, adicionados skips ou removidas asserções.

## 4. Candidatos e comparação de segurança

| Critério | Named pipes Windows | AF_UNIX Windows / Java 21 | Loopback autenticado |
|---|---|---|---|
| Transporte local | Nativo, handle de pipe, limitar remoto explicitamente | API UNIX abre neste JDK; runtime POSIX não é portável | TCP 127.0.0.1/::1; bind local não autentica |
| Controle de acesso | DACL explícita, SID de usuário/logon; recusar remoto | ACL NTFS no endpoint/runtime; sem SO_PEERCRED neste host | Sem DACL de cliente no socket; toda identidade depende da composição |
| Peer no kernel | APIs PID/token/impersonation úteis, insuficientes isoladamente | Token de auditoria macOS indisponível; adapter nativo adicional exigido | PID de conexão/porta não constitui autoridade |
| Identidade do app vivo | Contrato ainda não demonstrado para JVM/JAR/launcher/DLL | Mesmo problema; nenhum equivalente implementado | mTLS/posse de chave requer provisionamento e isolamento de app demonstráveis |
| Mesmo usuário hostil | DACL de usuário não exclui seus processos | ACL de usuário não exclui seus processos | Segredo/chave acessível ao mesmo usuário não exclui seus processos |
| Replay/restart | HMAC/generation/session binding obrigatório além do pipe | Idem | Canal autenticado + nonce/generation/request ordering obrigatório |
| Java/native | Adapter Win32 limitado e auditado; JNA já existe, não é qualificação | Java NIO é parcial; identity bridge separado | Java TLS pode atender transporte; provisioning/identity nativos ainda abertos |
| Resultado agora | Candidato prioritário para investigação controlada; não aprovado para produção | Disponibilidade de transporte demonstrada, identidade insuficiente | Não rejeitado em teoria; falta prova de isolamento e identidade |

### Named pipes

DACL deve ser explícita, não descriptor NULL/default; restringir SID previsto e, se adequado ao contrato, logon SID. Evitar conceder `FILE_GENERIC_WRITE` indiscriminadamente porque inclui o direito de criar instâncias. Os direitos de criação pertencem ao broker autorizado, os de leitura/escrita aos clientes elegíveis. SID correto é isolamento de usuário, não selo BYX. [Microsoft: segurança de named pipes](https://learn.microsoft.com/en-us/windows/win32/ipc/named-pipe-security-and-access-rights).

Requerer `PIPE_REJECT_REMOTE_CLIENTS`, primeira instância exclusiva, IO sobreposto com cancelamento/prazos, e validação de qualquer instância adicional. Nome aleatório/determinístico não é prova; ocupação prévia por impostor deve impedir disponibilidade. Tratar frames como contrato explícito, com leituras parciais, limite antes de alocar e sem confiar na fronteira de mensagem. [Microsoft: CreateNamedPipe](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-createnamedpipea).

`GetNamedPipeClientProcessId` / `GetNamedPipeServerProcessId` são pistas de processo nas duas direções, não equivalentes automáticos ao audit token. Abrir e manter handles de processo, validar conta/logon, criação/instância e término; resolver corrida entre metadado e handle; rejeitar informação incompleta. Um PID fornecido pelo cliente nunca entra no verifier. [ClientProcessId](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-getnamedpipeclientprocessid), [ServerProcessId](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-getnamedpipeserverprocessid).

Se impersonation for necessária apenas para identificar token, usar SQOS mínimo (identification), escopo estreito e `RevertToSelf` em finally. Falha em impersonar aborta; não executar operações de autoridade/cofre impersonando cliente. A prevenção de confused deputy exige manter autorização independente. [Microsoft: impersonation](https://learn.microsoft.com/en-us/windows/win32/ipc/impersonating-a-named-pipe-client?redirectedfrom=MSDN).

A pesquisa original Project Zero demonstrou riscos históricos de confiar em PID de pipe. Não foi testada nem alegada exploração da build 26200 atual; serve para exigir validação contra spoofing em vez de assumir equivalência. [Pesquisa de 2019](https://projectzero.google/2019/09/windows-exploitation-tricks-spoofing.html).

### AF_UNIX Windows

A disponibilidade Java é consistente com a introdução dos canais UNIX em JDK 16. [OpenJDK / Inside Java](https://inside.java/2021/02/03/jep380-unix-domain-sockets-channels/). A implementação Windows de opções no JDK 21 não oferece caminho de peer credentials nesta API; a sonda local acima é a evidência específica do ambiente. [Código OpenJDK 21u](https://raw.githubusercontent.com/openjdk/jdk21u/master/src/jdk.net/windows/classes/jdk/net/WindowsSocketOptions.java).

A descrição original Microsoft de 2017 documenta sockets de caminho no NTFS e diferenças, incluindo ausência então de ancillary data. Essa fonte histórica não qualifica todas as versões atuais; qualquer bridge nativo precisará prova na matriz Windows alvo. [Microsoft: AF_UNIX Windows](https://devblogs.microsoft.com/commandline/af_unix-comes-to-windows/).

Manter ACL, owner, rejeição de reparse e runtime privado; não tratar arquivo de socket/proprietário/pareamento como identidade de código. Sem mecanismo equivalente bidirecional de processo vivo, AF_UNIX permanece indisponível para integração privilegiada.

### Loopback explicitamente autenticado

Só considerar após decidir identidade de endpoint e provisioning de credenciais. TLS mútuo com trust root específica, rotação, challenge freshness e binding ao canal é candidato, não implementação. Não usar certificado autoaceito/TOFU indiscriminado, segredo fixo no repo, bearer em argv/env/log, porta conhecida como prova ou autenticação unilateral. Browser/origem/porta não definem cliente autorizado.

Exigir resistência a outro usuário e a processo hostil do mesmo usuário. Se a chave de cliente pode ser lida/usada por qualquer processo daquele usuário, mTLS não demonstra identidade BYX. A ACL do arquivo e DPAPI por usuário não resolvem essa lacuna. A decisão de como criar, proteger, entregar, revogar e rotacionar chaves/secret é anterior ao protótipo. Não implementar fallback loopback para contornar IPC indisponível.

## 5. Credenciais e código vivo Windows

`WinVerifyTrust` verifica objeto/arquivo e política Authenticode; retorno de sucesso é zero. Isso não atesta automaticamente a composição viva JVM + JAR + launcher + DLLs. Inferência de projeto: confiar em um `java.exe` genérico assinado permite outra aplicação do mesmo JDK; verificar somente caminho/assinatura/publisher deixa TOCTOU/substituição e injeção por resolver. [Microsoft: WinVerifyTrust](https://learn.microsoft.com/en-us/windows/win32/api/wintrust/nf-wintrust-winverifytrust).

DPAPI oferece proteção ligada a credenciais do usuário/máquina e integridade de blob. `CRYPTPROTECT_LOCAL_MACHINE` permite decriptação a qualquer usuário local; mesmo DPAPI de usuário não é isolamento por assinatura de app. Não equiparar isso ao cofre macOS restrito ao Service. [Microsoft: CryptProtectData](https://learn.microsoft.com/en-us/windows/win32/api/dpapi/nf-dpapi-cryptprotectdata).

Decisões ainda necessárias: broker sob usuário versus identidade isolada; integridade da instalação e launcher dedicado; proteção da JVM/JAR/native loading; acesso ao cofre restrito ao Service; vínculo entre canal e processo vivo; resistência a processo comprometido do mesmo usuário. Não supor que autenticar processo legítimo não comprometido contenha código já injetado nele. Administrador/kernel comprometidos não recebem promessa de isolamento; declarar limites, não desativar políticas para facilitar QA.

## 6. Threat model e critérios de aceitação

| Atacante/condição | Requisito de projeto | Evidência futura obrigatória |
|---|---|---|
| Outro usuário / processo sob outro usuário | DACL + owner + logon esperado; endpoint somente local | Usuário B real não conecta/lê token/cria instância |
| Processo malicioso do mesmo usuário | Identidade BYX viva em ambas direções e cofre não acessível ao Panel/processo genérico | Cliente genérico, JVM genérica e impostor com segredo correto rejeitados |
| Processo autorizado comprometido | Documentar limite de confiança e proteção contra loading/injection; autoridade por operação no Service | Tamper/JAR/DLL/env alterados rejeitados; nenhum papel confiado ao Panel |
| Endpoint impostor/substituído | Exclusividade + verifier de servidor antes de prova do cliente | Preocupação inicial, troca durante conexão, descriptor inválido nunca conectados |
| Cliente local não autorizado | Verifier de cliente antes de processar hello/autoridade | Sem frames de autenticação aceitos após falha |
| Sessão stale / PID reciclado | Peer instance estável, geração do Service, expiração, revogação | Reinício/PID reuse/credential change invalidam sessão |
| Replay | Nonces frescos, proof binding, ordem/IDs, channel/generation binding | Server proof antigo e requisição repetida não aceitos |
| Restart concorrente | Uma instância dona, segredo novo, cancelamento atômico | Sem canal antigo/promessa pendente reaproveitados |
| ACL permissiva ou falha de leitura | Recusa, sem “reparar” inseguramente | Everyone/Users/Anonymous e herança hostil rejeitados |
| Reparse/symlink/TOCTOU | Abrir/verificar handles e tipos, diretório aprovado, impedir troca | Junction/reparse/endpoint e token substituídos rejeitados |
| Elevação/confused deputy | Token mínimo, não herdar autoridade do cliente; escopo impersonation estreito | Sem operação privilegiada em identidade inesperada, sem bypass UAC |
| DoS/mau enquadramento | Limites de conexões/frame/tempo/backoff, cancelamento | Slow reader, silêncio, oversize, garbage e eventos após stop |

Reparse no runtime de arquivos e named-pipe namespace são problemas distintos; aplicar handle validation ao recurso correto, não regras POSIX fictícias.

## 7. Dependências nativas e interfaces propostas — sem código

O Panel já possui JNA 5.17.0 e JavaFX 21.0.5 com binários Windows; isso não qualifica uma API de Service. Um futuro adapter named-pipe exigiria bridge limitado para kernel32 (pipe/handles, IO overlapped, CancelIoEx e consulta de processo), advapi32 (security descriptors, tokens, SIDs, impersonation/RevertToSelf) e wintrust/crypt32 para a política de assinatura/credenciais que vier a ser aprovada. DPAPI/crypt32 é candidato de armazenamento com limites já descritos, não substituto aprovado do cofre. AF_UNIX com identidade precisaria bridge independente cujo mecanismo ainda não foi demonstrado; TLS Java não elimina o bridge de provisioning/isolamento do loopback.

Exigir tipos e tamanhos corretos x64, lifetime de buffers/handles, fechamento/cancelamento idempotentes, códigos de erro exatos e recusa em falha parcial; decidir JNA versus JNI dedicado por auditoria e evidência, não somente por conveniência. Não carregar dylib/frameworks macOS no Windows nem executar launchers de packaging. Não foram adicionadas dependências, privilégios, serviços instalados ou adapters nativos neste marco.

Separar um `LocalTransport` (conectar/aceitar, frame bounded, deadlines/cancel/close), `NativePeerIdentity` (evidência do handle vivo, conta/logon/instância/código, verdict deny por padrão), `RuntimeEndpointPolicy` (ownership/exclusividade/ACL/reparse), `PairingProvider` (provisioning/rotação em memória e armazenamento aprovado), e `ServiceGeneration` (lifecycle/revocation).

Esses nomes são proposta de contrato, não APIs implementadas. Não adaptar `PeerKeys.NONE` para um número fake nem truncar identidade Windows a PID. Revisar evolução da representação de peer sem quebrar pidversion macOS. Identity verifier deve ser independente do transporte; status só “authenticated” quando ambas evidências e provas forem válidas.

Service inicia endpoint só após validar composição/plataforma/armazenamento/identidade; qualquer falha aborta sem transport alternativo. Panel não lança binário macOS nem aceita home/endpoint vindo de fonte não confiável. Ownership futuro sob usuário limitado ou broker isolado depende de revisão; não escolher LocalSystem por conveniência. Start único, geração nova, proof fresh, sessões revogadas no restart, teardown idempotente, cancelamento de callbacks tardios. Não ativar rede/market/trading para investigar IPC.

## 8. Capacidades e apresentação

| Capacidade | Windows atual | Gate futuro |
|---|---|---|
| JavaFX pública DEFAULT | Login/FAQ/Help disponíveis | Build e execução nativos; sem login fake |
| Local Service / transport | UNSUPPORTED/UNAVAILABLE | Transporte e identidade bidirecional realmente qualificados |
| Service authenticated | Não atingido | Identidade + mutual pairing; separado de sessão de usuário |
| Usuário/autorização privada | Indisponível | Conta/sessão/MFA/role decididos pelo Service |
| Research restrita | Indisponível | Nenhum bypass; nenhuma alteração ETHUSDT |
| Signer / custody | Unsupported | Marco separado de código/cofre/identidade; não autorizado agora |
| Real trading | Disabled | Não faz parte deste RFC ou QA |

Não deduzir disponibilidade de custódia, dados de mercado, notificações de backend ou Home/Trading Desk privado a partir de janela aberta. Toasts/temas/mascot possuem testes de componentes; não são sessões DEFAULT autenticadas.

## 9. Estratégia de testes e compatibilidade macOS

Preservar todos os testes POSIX/macOS e suas asserções. Reutilizar cenários de protocolo após separar recursos de transporte, acrescentando testes nativos Windows **sem substituir identidade real por doubles nos testes de qualificação**. Doubles são admissíveis em unit tests declarados de parser/policy, nunca prova de peer autenticado.

Gate Windows: todos os 23 casos e seus modos; ACL/user isolation real; identidade cliente e servidor; signer/store indisponíveis sem autoridade; restart/PID reuse; provas/replay; deadlines/allocation; frames parciais/cancel/late events; ausência de dados inventados; logs sem segredo/caminho. Native reparse/junction sob conta normal e teste de outro usuário precisam ambiente autorizado, sem habilitar privilégio especial para passar testes.

Mac gate: executar compile, focused e suíte inteira no Panel, mais suite de Service e QA de identidade/cofre/assinatura existentes, em ambiente macOS provisionado autorizado com QA keys apenas. Comparar peer pidversion, requisito/selo/launch env, modos dev/packaged, permissões 0700/0600, reload/cleanup e sessões. Não alterar identidade macOS ao inserir interfaces. A regressão macOS desta tarefa **não foi executada**.

## 10. Fases e decisões de revisão

1. Revisão de threat model, propriedade/launcher/cofre e critério exato de identidade Windows. Nenhum transporte de produção aprovado antes desse gate.
2. Investigações pequenas independentes para named-pipe handles/DACL/cancel, AF_UNIX identity possível e provisioning loopback. Ambiente controlado, sem autoridade/custódia/rede e sem declarar compatibilidade.
3. Escolher transporte somente com evidência de identidade equivalente. Named pipes parecem o melhor candidato de investigação pelos controles locais disponíveis, mas **não há evidência suficiente para recomendar um transporte de produção qualificado**.
4. Implementar fronteiras aprovadas em marco posterior; manter macOS adapter e Windows deny como default até qualificação.
5. Executar negativos nativos e os 23 cenários; posteriormente integrar autoridade de usuário separada, mantendo custódia/trading disabled.
6. Regressão macOS e Windows; liberar somente capacidades efetivamente verificadas. Cofre/Signer/instalação são marcos distintos.

Questões para aprovação do owner em um marco posterior: ameaça mesmo-usuário e limites de processo comprometido; identidade/broker/launcher protegido; política publisher/assinatura/rotação/revogação; domínio do cofre exclusivo ao Service; per-user/per-logon vs broker dedicado; formato/evolução de peer key e generation; provisioning sem shared-secret estático; critérios e ambiente de testes com usuários reais; comportamento de atualização/tamper. Não se solicita permissão para implementar neste marco: a implementação está expressamente fora do escopo.

## Resultado

**C4_WINDOWS_SECURE_IPC_DESIGN_READY_FOR_REVIEW** — inventário e propostas entregues; mecanismo de identidade ainda precisa decisão/evidência. **C4_WINDOWS_FULL_COMPATIBILITY_NOT_YET_QUALIFIED** — build e UI pública não substituem integração Service autenticada.


---

## Adendo de evidência C4.2.0 — 2026-10-09

A investigação isolada de named pipes está registrada em [windows-ipc-identity-probe.md](../qa/package-c4/windows-ipc-identity-probe.md). Este adendo não aprova transporte, reduz threat model ou altera as fases/contratos anteriores.

- **WINDOWS_IPC_TRANSPORT_PROBE_BLOCKED**: harness C# x64 e peers Java preparados e compilados (exit 0), mas executável da sonda recusado pelo Windows App Control. Eventos CodeIntegrity 3077/3033 para o artefato unsigned; ambiente de integridade média e sem administrador efetivo. Não se alterou política nem se usou outro runtime para contornar o bloqueio.
- Uma execução anterior abortou no preflight por ausência de logon SID na coleção gerenciada, antes de criar pipe. Consulta nativa TOKEN_GROUPS/SE_GROUP_LOGON_ID foi preparada, porém não executada; não inferir que o token do Windows não possui logon SID.
- **WINDOWS_APP_IDENTITY_NOT_PROVEN**: assinatura de arquivo java.exe verificada como válida (Eclipse Foundation), sem prova de identidade BYX viva. Os programas Java A/B foram somente compilados; nenhum teste de impostor sobre pipe foi executado.
- DACL/first-instance/remote reject/PID/token/SQOS/replay/restart/frames/cancel/cleanup permanecem **NOT_EXECUTED**, não PASS. Nenhum endpoint ou sessão BYX foi aberto. O HMAC preparado é teste puro, não pareamento em canal qualificado.
- User SID e logon SID em ACEs allow separadas não formam conjunção; política futura exige avaliação independente. Ownership/WRITE_DAC e ataque de mesmo usuário também precisam prova específica. Preservar as exigências de composição JVM/JAR/launcher/native libraries e vínculo ao canal.
- Recomendação: continuar somente prova isolada após revisão e artefato aceito pela política existente. Resolver código vivo/provisioning e evidência mútua antes de recomendar conectividade Service privilegiada; AF_UNIX/loopback não se tornam aprovados por este bloqueio.
- Fontes do Panel/Service/ACL/visual/macOS verifier permanecem idênticas em 857 hashes de preflight. Nenhum commit/push, modificação de Signer/custody/Keychain/autorização ou ETHUSDT.

O blocker de execução é evidência de enforcement do artefato, não equivalência demonstrada ao contrato macOS. A compatibilidade Windows completa continua não qualificada.
