# C4.2.0 — Windows IPC identity probe

Data: 2026-10-09, America/Sao_Paulo. Investigação isolada, **sem integração de produção**.

**WINDOWS_IPC_TRANSPORT_PROBE_BLOCKED**
**WINDOWS_APP_IDENTITY_NOT_PROVEN**

A sonda foi criada e compilada, mas a execução do artefato nativo foi bloqueada pelo Windows App Control. Nenhuma conexão named-pipe, autenticação de aplicativo ou sessão BYX foi qualificada. Este resultado não demonstra que named pipes sejam inviáveis; demonstra que a prova solicitada não foi concluída neste ambiente e que identidade BYX continua sem evidência suficiente.

## Baseline e escopo

Root C:\src\DEV; branch feature/byx-windows-readiness-v1; HEAD 625689b2021c4c6a5f4a409d5d4680bccc865cbc. O [RFC](../../package-c4/WINDOWS_SECURE_IPC_RFC.md) foi lido integralmente antes da criação do harness.

Windows 11 Pro 10.0.26200 x64. Sessão PowerShell efetivamente não administradora, integridade média S-1-16-8192: [environment.json](c4-2-0/environment.json). Não se alteraram política de segurança, registro, serviços instalados, privilégios ou configurações de display. O uso do runner fora do sandbox de ferramentas não elevou o token Windows.

As 857 referências de hash de src do Panel e src do Service permanecem idênticas: [preservação](c4-2-0/source-preservation.json). Isso inclui ACLs Windows, correções visuais, guard de transporte, testes e verifier macOS. [Git inicial](c4-2-0/preflight-git.txt). Nenhum arquivo identity.env, senha, token de produção ou chave foi lido.

A janela pública DEFAULT previamente qualificada permanece a baseline. O guard LocalServiceClient.java:338–340 continua retornando native_service_unsupported antes de token/connect. Nenhum teste Maven anterior foi modificado ou reexecutado para alterar os 1008 PASS / 0 FAIL / 23 ERROR / 3 SKIP históricos.

## 1. Contrato mínimo para ambos os endpoints

| Camada | Evidência necessária | Não constitui substituto |
|---|---|---|
| Conectividade | Pipe real local, descriptor verificado pelo handle, framing e lifecycle bounded | Janela JavaFX aberta; nome conhecido |
| Kernel/processo | Evidência originada no canal nas duas direções; token do peer; usuário/logon esperados; instância viva vinculada ao handle, com race/PID reuse resolvidos | PID enviado pelo cliente, nome/path de processo, SID sozinho |
| Código vivo do aplicativo | Composição autorizada BYX de launcher + JVM + JAR/classes/configuração + bibliotecas nativas; integridade protegida e vínculo à instância realmente conectada | Assinatura válida de java.exe; hash isolado de arquivo; lista de DLLs observada uma vez |
| Pareamento criptográfico mútuo | Provisionamento protegido ao aplicativo elegível, segredo fresco por geração, provas nas duas direções, nonces e binding ao canal/transcript; rotação e replay refusal | Token ordinário legível por qualquer processo do usuário; ACK da sonda |
| Sessão de usuário | Conta/credenciais/MFA, token de sessão ligado a peer/generation/credential version, expiração/revogação | Pipe conectado ou aplicativo assinado |
| Autorização Service | Decisão por operação/capacidade/papel no Service, falha fechada | Flags enviados pelo Panel ou token de pareamento |

Todas as camadas devem ser demonstradas separadamente. O contrato macOS conserva audit token do kernel, pidversion, verificação de código vivo, requisito/selo de bundle e ambiente; nenhum desses verificadores foi modificado. Process creation time + handle Windows é candidato de instância, não equivalente automaticamente demonstrado ao audit token. A corrida entre obter PID e abrir handle permanece a ser provada.

A fronteira continua Panel → Service → CustodyClient → Signer → armazenamento seguro da plataforma. A pesquisa não concede acesso ao Service ou ao cofre.

## 2. Harness preparado

Código independente em [c4-2-0](c4-2-0), fora de src/main, src/test, dependências Maven e entrypoints do produto:

- [NamedPipeProbe.cs](c4-2-0/NamedPipeProbe.cs): executável C# x64, Windows/.NET Framework; controlador, peers filhos próprios e servidor descartável. Não referencia classes BYX.
- [ProbeJavaWire.java](c4-2-0/ProbeJavaWire.java), [Peer A](c4-2-0/ProbeJavaPeerA.java), [Peer B](c4-2-0/ProbeJavaPeerB.java): dois programas Java deliberadamente arbitrários com o mesmo java.exe/JNA existente, classes distintas; **nenhum representa cliente BYX autorizado**.
- Compilação C# e Java final: exit 0 / exit 0; [build-results.json](c4-2-0/build-results.json). Os peers Java foram somente compilados, não executados. Nenhuma dependência foi instalada.

Arquitetura prevista no código, **não medida em runtime**:

1. Recusar token elevado ou ausência de logon SID nativo.
2. Nome novo aleatório exclusivo, prefixo BYX-C420-ISOLATED, sem usar endpoint/home/token do produto.
3. Pipe byte-stream duplex, uma instância; FILE_FLAG_FIRST_PIPE_INSTANCE, FILE_FLAG_OVERLAPPED e PIPE_REJECT_REMOTE_CLIENTS.
4. Descriptor explícito protegido, owner atual e **uma** ACE de direitos específicos 0x00120003 (dados, READ_CONTROL, SYNCHRONIZE), sem FILE_CREATE_PIPE_INSTANCE. Casos separados para SID de usuário e SID de logon. Verificar descriptor retornado pelo handle.
5. Cliente pede direitos específicos e SECURITY_SQOS_PRESENT | SECURITY_IDENTIFICATION.
6. APIs de PID nos dois lados; OpenProcess com query-limited + synchronize, creation time, path e token. Correlação com PID do filho lançado é controle da experiência, **não autenticação do aplicativo**.
7. Impersonation apenas para inspeção de token de thread após dados públicos; finally RevertToSelf e verificação de ausência de thread token. Nenhuma operação privilegiada dentro desse escopo.
8. Frames de 1–8192 bytes, header de quatro bytes little-endian, UTF-8 estrito e payload PROBE. Prazo total de frame 2 s; IO overlapped com CancelIoEx e espera da conclusão antes de liberar buffers/OVERLAPPED. Timeout de conexão próprio 300 ms no caso negativo. Filhos limitados pelo controlador.
9. ACK exclusivamente PROBE:ACK_NO_AUTHORITY; flags de relatório AppIdentity=NOT_PROVEN e AuthorizedSession=false.
10. Handles da sonda fechados por Dispose; teste de fim do servidor encerra somente o próprio processo filho. Nenhum processo externo é encerrado.

HMAC-SHA256 de QA é um teste **puro em memória** preparado, com chave aleatória descartável, nonces e generation. Não há provisioning a peer, pareamento mútuo real sobre pipe ou sessão BYX. O teste puro também não executou; não contar como resistência a replay de canal.

A API de pipe documenta first-instance, byte mode, overlapped e rejeição de remoto; código/flags preparados não provam que o kernel os aplicou nesta sonda. [Microsoft CreateNamedPipe](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-createnamedpipea).

DACLs com duas ACEs allow para user SID e logon SID expressam união de permissões, não “user AND logon”. O harness usa casos separados; futura política precisa verificar ambas as propriedades do token independentemente. Direitos genéricos de escrita incluem criação de instância; descriptor default possui permissões amplas, portanto são recusados na proposta. [Microsoft named-pipe security](https://learn.microsoft.com/en-us/windows/win32/ipc/named-pipe-security-and-access-rights).

CancelIoEx solicita cancelamento, não sua conclusão. A fonte mantém memória nativa até completion/drain; a garantia ainda precisa medição, inclusive falhas de cancelamento. [Microsoft CancelIoEx](https://learn.microsoft.com/en-us/windows/win32/api/ioapiset/nf-ioapiset-cancelioex).

## 3. Execuções reais e bloqueio

| Ação executada | Resultado observado | Alcance |
|---|---|---|
| Compilação inicial | Erro de caminho relativo do csc e depois construtor RawSecurityDescriptor incompatível; corrigidos | Problemas do harness, não produto |
| Primeira execução compilada | Exit 2; InvalidDataException: logon SID missing | Preflight usou WindowsIdentity.Groups; abortou **antes de criar pipe** |
| Ajuste da consulta | Preparada leitura nativa TOKEN_GROUPS e atributo SE_GROUP_LOGON_ID, sem fabricar SID | Compilado; não comprovado em execução |
| Tentativa de executar a versão nativa corrigida | “Uma política de Controle de Aplicativo bloqueou este arquivo”; NativeCommandFailed | Processo não iniciado; sem resultado de testes |
| Leitura CodeIntegrity/Operational | Eventos **3077 e 3033** para NamedPipeProbe.exe | Evidência de política/signer incompatível |
| Consulta de assinatura do harness | NotSigned, status numérico 2 | Artefato unsigned |
| Consulta de assinatura de java.exe | Valid, status 0; publisher Eclipse Foundation | Apenas assinatura do arquivo JDK |
| Compilação final C# + peers Java | Exit 0 / 0 | Não houve nova tentativa de execução após bloqueio |

[Primeiro log](c4-2-0/initial-results.jsonl), [erro de lançamento Windows](c4-2-0/native-initial-results.jsonl), [eventos Code Integrity](c4-2-0/code-integrity-events.json), [assinatura JDK](c4-2-0/java-file-signature.json), [assinatura da sonda](c4-2-0/probe-file-signature.json).

O primeiro “logon SID missing” não comprova que o token Windows não possui logon SID: só mostrou ausência na coleção gerenciada usada inicialmente. A consulta nativa corrigida não executou. O environment registra ManagedLogonSidCount=0 e NativeLogonSidQualification=NOT_EXECUTED.

A mensagem do evento informa falha nos requisitos Enterprise signing level ou violação de política, com Policy ID 0283ac0f-fff1-49ae-ada1-8a933130cad6. Não se inferiu uma regra específica além dessa evidência. 3077 é evento de bloqueio de política enforced, distinto de 3076 audit-only. [Microsoft App Control events](https://learn.microsoft.com/en-us/windows/security/application-security/application-control/app-control-for-business/operations/event-id-explanations).

Importante: o wrapper PowerShell de uma tentativa retornou 0 porque LASTEXITCODE ainda refletia a compilação anterior. O NativeCommandFailed significa que **não há exit code válido do probe** nessa tentativa; não foi tratado como PASS.

Não se alterou ExecutionPolicy/App Control, não se assinou com assets macOS, não se elevou a sessão e não se transferiu o mesmo harness para outro host/runtime para contornar a recusa. Compilação posterior valida sintaxe somente. O bloqueio é Windows App Control, não rejeição automática de aprovação da ferramenta.

## 4. APIs efetivamente exercidas versus somente preparadas

**Efetivamente observado:** WindowsIdentity/WindowsPrincipal gerenciados e whoami para usuário efetivo/grupos/integridade; Get-CimInstance para versão Windows; Get-AuthenticodeSignature para arquivos específicos; leitura do log CodeIntegrity. Não se instrumentou a implementação interna do cmdlet para alegar chamada direta própria a WinVerifyTrust.

**Somente preparados no harness, sem resultado nativo qualificado:**

- CreateNamedPipeW/CreateFileW, ConnectNamedPipe, ReadFile/WriteFile.
- GetSecurityInfo, GetSecurityDescriptorLength, conversão SDDL e SID.
- GetNamedPipeClientProcessId/GetNamedPipeServerProcessId.
- OpenProcess/GetProcessTimes/QueryFullProcessImageNameW/WaitForSingleObject.
- OpenProcessToken/GetTokenInformation(TOKEN_GROUPS, elevation, session ID).
- ImpersonateNamedPipeClient/OpenThreadToken/RevertToSelf.
- CreateEventW/GetOverlappedResult/CancelIoEx/CloseHandle/LocalFree.

Nenhum endpoint NPFS foi criado por esta execução. Nenhum listener TCP, Service macOS ou binário de Signer foi iniciado.

## 5. Matriz de positivos/negativos

| Cenário requerido | Estado real | Preparação ou limitação |
|---|---|---|
| Conexão local com DACL user SID | NOT_EXECUTED | Caso explicit_user_dacl |
| Conexão local com DACL logon SID | NOT_EXECUTED | Caso explicit_logon_dacl; exige logon SID nativo |
| Rejeição de remoto | NOT_EXECUTED | Flag preparada; nenhum host/SMB remoto usado |
| Processo local não autorizado | NOT_EXECUTED | Peers arbitrários e ACK sem autoridade; não há verifier BYX |
| Processo de outro usuário | NOT_EXECUTED | Sem sessão/credenciais de QA de usuário B provisionadas; nenhum usuário criado |
| Impostor mesmo usuário Java | NOT_EXECUTED | Classes A/B compiladas; comparação real de peers pendente |
| Pipe squatting / first-instance | NOT_EXECUTED | Duplicação prevista deve retornar ERROR_ACCESS_DENIED; não observado |
| Impostor de endpoint / substituição | NOT_EXECUTED | PID do servidor e recriação após término preparados; vínculo de identidade não demonstrado |
| Replay de provas | NOT_EXECUTED | Apenas teste HMAC puro preparado; replay de canal ainda não implementado |
| Restart / identidade stale | NOT_EXECUTED | Handle sinalizado após término e segredo/generation novos preparados |
| PID realmente reciclado | NOT_EXECUTED | Não se forçou reuse; teste de handle stale não o substitui |
| ACL Everyone permissiva | NOT_EXECUTED | Descriptor deliberadamente permissivo só no pipe aleatório da sonda; policy deverá recusar |
| Disconnect de cliente | NOT_EXECUTED | IO deve detectar EOF/erro nativo; nenhuma medição |
| Término inesperado do servidor | NOT_EXECUTED | Kill somente do próprio filho; nenhum processo foi encerrado neste caso |
| Frame oversized | NOT_EXECUTED | Header 8193 deve recusar antes do body allocation |
| Mensagem malformada/truncada | NOT_EXECUTED | UTF-8 inválido e header parcial preparados |
| Silêncio / timeout / cancel | NOT_EXECUTED | 2 s e connect 300 ms; drain não medido |
| Evidência nativa incompleta | NOT_EXECUTED | Handle inválido/PID 0 previstos para negar; ausência inicial foi erro de harness |
| Cleanup de handles | NOT_EXECUTED | Contador de wrappers nativos; não confundir com medição de todos os handles do processo |

**Positivos reais:** compilação, ambiente não elevado, coleta de bloqueio e assinatura de arquivo. **Positivos de transporte: zero. Negativos de segurança nativa qualificados: zero.** Nenhuma linha acima é PASS apenas porque existe uma asserção no código.

## 6. Código vivo e ataque do mesmo usuário

O [java.exe verificado](c4-2-0/java-file-signature.json) é o Temurin 21.0.12.1 já utilizado na baseline. Sua assinatura é válida e identifica publisher do JDK; não identifica qual aplicação Java a instância executa.

A assinatura Authenticode é uma verificação de objeto/arquivo. Não foi obtido proof de WinVerifyTrust atestando processo vivo, JARs, classes ou DLLs. [Microsoft WinVerifyTrust](https://learn.microsoft.com/en-us/windows/win32/api/wintrust/nf-wintrust-winverifytrust), [assinaturas PE](https://learn.microsoft.com/en-us/windows/win32/secbp/understanding-pe-signatures).

Inferência arquitetural, **não resultado de ataque executado**: programas A/B podem compartilhar usuário, logon, executable path e publisher enquanto carregam classes distintas. Por isso a combinação dessas propriedades não contém a evidência que diferencia BYX autorizado. Os dois peers preparados permitem testar essa hipótese em um próximo ambiente autorizado, sem fingir que A é BYX.

Lacunas concretas:

- Launcher dedicado e política de componente/publisher ainda não definidos para Windows. Aceitar generic java.exe não resolve.
- JVM, runtime, JAR/classes, configuração e native libraries devem ter proveniência/integridade e instalação protegida; hash de arquivo lido em diretório gravável não resolve troca entre verificação e carga.
- É preciso vincular composição verificada à instância do canal e controlar loading dinâmico, DLL search, agentes, parâmetros e environment que alteram execução.
- Enumerar módulos/path não prova ausência de injeção ou de código Java não autorizado. Não se alegou resistência a processo BYX já comprometido.
- Um owner compartilhado entre processos do usuário é uma ameaça também à DACL. O owner normalmente tem WRITE_DAC implícito; portanto min-rights do cliente não comprova que outro processo do mesmo owner não pode alterar permissões. Isso requer prova/política própria, inclusive revisão de ownership/OWNER_RIGHTS ou isolamento de broker. Nenhuma solução foi aplicada. [Microsoft ownership](https://learn.microsoft.com/en-us/windows/win32/secauthz/owner-of-a-new-object).
- Um token/segredo per-user acessível a esses processos não autentica o app; DPAPI per-user não é equivalente automaticamente ao cofre restrito por identidade de código macOS.
- O bloqueio do executável unsigned mostra enforcement sobre esse artefato. Não demonstra uma allowlist BYX, cobertura de bytecode Java ou identidade em cada conexão.
- AppContainer/package identity, launcher protegido/broker isolado ou composição selada são linhas de investigação, não soluções comprovadas. Não foi instalado, assinado ou empacotado aplicativo para testá-las.

**WINDOWS_APP_IDENTITY_NOT_PROVEN** não significa impossibilidade universal de Windows; significa ausência de estratégia qualificada para o contrato atual, sobretudo contra atacante do mesmo usuário.

## 7. Decisão e próximo marco

Named pipes continuam candidato do RFC; não há medição nova suficiente para classificá-los como transporte aprovado. AF_UNIX e loopback não foram testados neste marco e não são recomendados como alternativa ao bloqueio.

Próximo marco deve permanecer **isolado e sem privilégio BYX**:

1. Revisão do harness e artefato de pesquisa executável aceito pela política existente, sob sessão normal. Autorizar explicitamente eventual signing de QA compatível com a política; não desabilitar controles, usar assets macOS ou publicar instalador. A presença de assinatura sozinha pode não satisfazer a política; a elegibilidade precisa ser confirmada.
2. Executar DACL/user/logon/owner rights, flags/PIDs/token/SQOS/lifecycle/framing/cancel; arquivar resultados exatos. Provisionar outro usuário/host QA somente em ambiente autorizado para os casos que o exigem.
3. Concluir binding canal→instância e proteção de composição BYX, com peers arbitrários, tamper/JAR/DLL/agent/env e races. Os dois programas Java preparados são controles negativos; não fornecem resultado positivo BYX.
4. Provisionamento/rotação e provas mútuas em canal real, sem token ordinário como app identity; replay/stale sessions/restart.
5. Só depois, em marco separado e autorizado, investigar integração de sessão/autoridade Service; preservar operação/papel/MFA no Service. Signer/custódia/trading permanecem fora desse gate.

**Não se recomenda implementar conectividade Service privilegiada agora.** Faltam prova de transporte, código vivo, provisioning, identidade mútua e autorização integrada. Não existe fallback ou sessão autenticada fake.

## 8. Impacto macOS e Git

Nenhuma mudança no verifier macOS, RuntimeDir, Keychain, custody, Signer, autorização ou ETHUSDT. Nenhum projeto fora do Panel foi alterado. O harness é Windows-only em docs; não altera dependências/build/testes do aplicativo. Não foi executada regressão macOS; não se faz alegação de nova qualificação.

Inventário incremental desta tarefa:

- Novo docs/qa/package-c4/windows-ipc-identity-probe.md.
- Novo docs/qa/package-c4/c4-2-0/: fontes C#/Java, artefatos compilados de pesquisa, logs, evidência de policy/ambiente, hashes e inventário.
- RFC existente: somente adendo C4.2.0 com novos fatos e decisões pendentes; prefixo histórico preservado.
- **src do Panel e Service: zero alterações**, comprovadas pelos 857 hashes. Mudanças locais C4.1/C4.1-F anteriores continuam intactas; git diff contra HEAD ainda as inclui, e não devem ser atribuídas a C4.2.0.
- [Inventário Git final](c4-2-0/final-git-inventory.json); índice vazio; sem commit/push/merge/release.

REAL USER KEY = NOT AUTHORIZED
REAL TX = DISABLED
BROADCASTS = 0
REAL FUNDS = 0
REAL USER WALLETS = 0
ETHUSDT RESEARCH = UNTOUCHED

STOP após esta investigação. Não iniciar produção IPC, packaging/installer, custódia Windows ou trading.
