# C4.2.0-R — Windows App Control block diagnostic

Data: 2026-10-09, America/Sao_Paulo. Diagnóstico de leitura de políticas; somente este relatório e evidências em docs foram escritos.

**WINDOWS_APP_CONTROL_CAUSE_VERIFIED**
**IPC_NATIVE_PROBE_EXECUTION_NOT_AUTHORIZED**

A recusa histórica foi causada pelo **Smart App Control em enforcement**, política `VerifiedAndReputableDesktop`, ao carregar o próprio executável isolado `NamedPipeProbe.exe`, sem assinatura. A causa está comprovada por eventos 3077, assinaturas correlacionadas 3089, ativação 3099 e estado local de CI. O inventário completo de políticas e a procedência administrativa da configuração continuam incompletos. Não houve nova tentativa de executar o harness, recompilação ou alteração de segurança.

## Baseline e método

Workspace `C:\src\DEV`; branch `feature/byx-windows-readiness-v1`; HEAD `625689b2021c4c6a5f4a409d5d4680bccc865cbc`. Windows 11 Pro 10.0.26200.9457, 25H2, AMD64. A identificação CIM confirma Windows 11; o ProductName legado do registro ainda diz Windows 10 Pro e não foi usado para trocar a identificação do sistema.

Foram examinados o [relatório C4.2.0](windows-ipc-identity-probe.md), o [RFC](../../package-c4/WINDOWS_SECURE_IPC_RFC.md) e seus artefatos históricos. Consultas: Get-WinEvent, XML dos eventos, Get-AuthenticodeSignature, Get-FileHash, metadados PE/assembly sem carregar código executável, Get-AppLockerPolicy -Effective -Xml, CiTool -lp -json, leituras pontuais do registro/CIM e Git. Nenhum segredo, identity.env, certificado privado ou credencial foi lido.

Sessão efetivamente não administradora: WindowsPrincipal.IsInRole(Administrator)=false. Nenhuma elevação do token Windows. Janela histórica pesquisada: **2026-10-09 21:03:00–21:07:00 -03:00** (2026-10-10 00:03:00–00:07:00 UTC). As evidências guardam somente eventos correlacionados à sonda, sem nomes de usuário, SID de usuário, hostname, domínio ou tenant. ActivityIDs identificam correlação de eventos, não autoridade BYX.

## Arquivo e erro exatos

Alvo histórico completo: `C:\src\DEV\mvp-binance-panel\docs\qa\package-c4\c4-2-0\NamedPipeProbe.exe`. Caminho de dispositivo nos eventos: `\Device\HarddiskVolume3\src\DEV\mvp-binance-panel\docs\qa\package-c4\c4-2-0\NamedPipeProbe.exe`.

O processo que tentou carregá-lo foi `\Device\HarddiskVolume3\Windows\System32\WindowsPowerShell\v1.0\powershell.exe`. **O alvo recusado é o harness, não PowerShell, java.exe, uma DLL Java ou Maven.** Não há evidência de bloqueio dessas dependências ligado às quatro atividades examinadas. Isso não qualifica dependências futuras.

Erro NTSTATUS em todos os 3077: **0xc0e90002 — STATUS_SYSTEM_INTEGRITY_POLICY_VIOLATION**, confirmado pela consulta de leitura `certutil.exe -error 0xc0e90002`. Requested Signing Level=2; Validated Signing Level=1. Os oito 3089 correlacionados informam TotalSignatureCount=0, publisher/issuer Unknown e nenhum signer. A mensagem anterior registrada no relatório foi “Uma política de Controle de Aplicativo bloqueou este arquivo”, NativeCommandFailed. O arquivo native-initial-results.jsonl existente está **vazio (0 bytes)**; não se apresenta esse arquivo como transcrição recuperada. Os eventos são a evidência primária. O código Win32 da exceção original não foi preservado; não se inventa esse valor nem um exit code do processo que não iniciou.

### SHA-256: versões históricas e arquivo atual

| Artefato | SHA-256 flat (arquivo completo) | Evidência |
|---|---|---|
| Versão recusada às 21:04:33 | `77E0FDE045AA23161C80885C7F6ECCF3FF76F46C73B6F14F9AD4E539E7041C04` | 3077 record 5620 |
| Versão recusada às 21:05:08/09 | `E18B28FFF171C5FBE81DE67E799D30B6B2B98911DB102EF32E131FAD8F67F33F` | 3077 records 5625, 5630, 5635 |
| Arquivo presente após compilação final | `FEF1EF45ADA5F6A7CED7EC529968F8036349DCA384E5E2286B750492EF981A8B` | Get-FileHash atual; manifesto histórico também confere |

SHA-256 Authenticode dos eventos: primeiro `3075AB74B737E104534A846C64DDBB8A4C06950DCA5AD3EB85547936F0B1E261`; demais `F860074F2E081E2E1957435720989CC4BF9D96B7C90EFF0DC3AA6BC47B3AC2D2`. Não são hashes flat. Os binários antigos foram sobrescritos; sua identidade e ausência de assinatura são recuperadas dos eventos, não de uma reanálise desses bytes.

Arquivo atual: 45.568 bytes, PE machine 0x8664 (x64), assembly `NamedPipeProbe, Version=0.0.0.0, Culture=neutral, PublicKeyToken=null`, OriginalFilename NamedPipeProbe.exe, FileVersion 0.0.0.0. Última escrita **2026-10-10T00:07:36.8705889Z**, posterior aos bloqueios. Get-AuthenticodeSignature=**NotSigned**; sem certificado de signer ou timestamp. [Identidade atual](c4-2-0-r/file-identity.json). PublicKeyToken=null é metadado de strong-name, distinto de assinatura Authenticode. Não houve teste de execução dessa versão neste diagnóstico.

## Correlação e mecanismo de enforcement

[Evidência sanitizada de Code Integrity](c4-2-0-r/code-integrity-events.json): 20 eventos da sonda — quatro 3077, quatro 3033, oito 3089 e quatro 3118.

| Hora local -03:00 | 3077 | 3089 correlacionados | ActivityID |
|---|---|---|---|
| 21:04:33.3555564 | 5620 | 5619, 5621 | `{1dd2d070-5792-0005-d780-ed1d9257dd01}` |
| 21:05:08.7818330 | 5625 | 5624, 5626 | `{1dd2d070-5792-000e-1632-f91d9257dd01}` |
| 21:05:09.2395201 | 5630 | 5629, 5631 | `{1dd2d070-5792-0000-1b31-b71e9257dd01}` |
| 21:05:09.3716978 | 5635 | 5634, 5636 | `{1dd2d070-5792-0006-4fbc-011e9257dd01}` |

Todos nomeiam PolicyName=`VerifiedAndReputableDesktop`, PolicyGUID=`{0283ac0f-fff1-49ae-ada1-8a933130cad6}`, friendly PolicyID=`27555.1000.240208`, PolicyHash=`2668895A5B233A80432D00D67251D7B7F52686A3FB13780F4B242C5A1F937A01`. O 3099 record 4930, de 2026-10-08 23:01:39.3797525 -03, registra essa mesma política/hash carregada com Status=0x0; há também sua supplemental Flight. O registro CI\Policy informa VerifiedAndReputablePolicyState=1.

A documentação Microsoft associa VerifiedAndReputableDesktop enforced ao Smart App Control. Portanto a identificação usa nome da política, eventos efetivos e estado, além da mensagem genérica. O termo “Enterprise signing level” sozinho não identificaria App Control for Business administrado por empresa. [Microsoft: identificação SAC](https://learn.microsoft.com/en-us/windows/apps/develop/smart-app-control/test-your-app-with-smart-app-control).

3077 demonstra recusa enforced; os 3089 foram associados pelo ActivityID. Os campos flat e Authenticode têm significados diferentes, e assinatura ausente aparece como TotalSignatureCount=0. [Microsoft: interpretação dos eventos](https://learn.microsoft.com/en-us/windows/security/application-security/application-control/app-control-for-business/operations/appcontrol-debugging-and-troubleshooting).

3118 informa chamada de avaliação Defender, nenhum ThreatName e, em três atividades, DefenderCloudHTTPCode=0xc8000000; na última a chamada cloud não ocorreu. **Não foi decodificada a decisão interna de reputação/cloud**, nem provado que falha de rede causou a recusa. A causa verificável é decisão SAC enforced sobre artefato unsigned; não há evidência para chamar o harness de malware. Também não se infere que uma assinatura qualquer garantiria aceitação.

### AppLocker e permissões

Ambos os canais AppLocker pedidos estão habilitados e legíveis: EXE and DLL (99 registros no total) e MSI and Script (28). No intervalo pesquisado, **zero eventos** em ambos, inclusive sem 8004/8007 ou outros bloqueios correlacionados. Get-AppLockerPolicy -Effective -Xml retornou `<AppLockerPolicy Version="1" />`. Não há evidência de regra AppLocker causando esta recusa. [Resultado](c4-2-0-r/applocker-log-access.json).

CiTool -lp -json retornou OperationResult/exit **-2147024891 = 0x80070005 = ERROR_ACCESS_DENIED**. Nenhum inventário completo foi obtido; não se elevou a sessão. Os três logs solicitados foram legíveis, sem permissão adicional necessária para sua consulta. [Estado e eventos de carga](c4-2-0-r/policy-state.json).

Uma tentativa de usar um coletor .ps1 de leitura também encontrou PSSecurityException/UnauthorizedAccess (“execução de scripts foi desabilitada”). Não foi empregado Bypass, Set-ExecutionPolicy ou wrapper para executá-lo. O coletor foi removido, e as consultas foram realizadas como comandos interativos de leitura já disponíveis. Todos os escopos Get-ExecutionPolicy -List estão Undefined; essa recusa de script é distinta da recusa NTSTATUS do executável e não comprova GPO.

### Administração local ou central

O mecanismo identificado é o SAC integrado ao Windows, aplicado localmente por Code Integrity. **Quem habilitou/configurou sua política e se existe administração central não foram comprovados.** PartOfDomain=false não exclui MDM/Entra; 33 subchaves GUID em Microsoft\Enrollments não comprovam inscrição ativa ou origem desta política. Não foram coletados identificadores privados. CiTool negado limita o inventário; eventos 3099 provam carregamento, não o responsável pela implantação. Não se pode concluir “política corporativa” nem “máquina exclusivamente autogerida”. O proprietário/administrador precisa confirmar procedência e eventual gestão. [Snapshot sanitizado](c4-2-0-r/session-git.json).

## Recuperação que preserva segurança

| Opção | Viabilidade neste cenário | Pré-requisitos |
|---|---|---|
| A — executável revisado com assinatura confiável | Candidata compatível com SAC; ainda não disponível ou validada | Revisão técnica; assinatura Windows legítima com cadeia aceita pela política existente; aprovação do responsável; integridade/hash do artefato final e dependências; sessão normal. Validar aceitação somente em novo marco autorizado. Sem self-signing presumido, importação de confiança ou assets macOS. |
| B — autorização estreita pelo policy owner | Aprovação administrativa pode autorizar a investigação, mas não constitui exceção técnica SAC | Microsoft não oferece liberação individual de aplicativo SAC. Não propor allowlist local ou desativação. O responsável pode aprovar A ou C e esclarecer políticas adicionais; inventário elevado somente por ele, fora desta tarefa. |
| C — ambiente de testes separado e autorizado | Alternativa se A não for viável | Ambiente explicitamente aprovado com política adequada à pesquisa isolada, conta normal e sem autoridade/segredos BYX. Não transferir automaticamente o artefato nem mudar controles desta máquina. |
| D — análise estática/testes portáveis | Disponível enquanto falta execução nativa autorizada | Manter classificação separada: leitura de fonte, revisão de contrato e testes portáveis não qualificam NPFS/token/processo nem aplicação viva. Nenhum teste adicional foi executado aqui. |

A Microsoft documenta que SAC não permite uma exceção individual e recomenda assinatura válida para desenvolvedores. A aprovação humana não substitui aceitação técnica. [Microsoft: FAQ SAC](https://support.microsoft.com/en-us/windows/security/threat-malware-protection/smart-app-control-frequently-asked-questions).

Não foi assinado, renomeado, movido ou encapsulado o harness para executá-lo; não houve mudança em políticas, registro de segurança, certificados, trusted publishers, Defender, SAC, AppLocker, UAC ou Code Integrity. O diagnóstico não autoriza mudar flags de detecção nem repetir o lançamento.

## Gates independentes e próximo marco

**Gate A — transporte nativo: pendente e bloqueado neste ambiente.** Exige ambiente/procedimento explicitamente autorizado, execução real em sessão normal e medição de DACL, user/logon SID, rejeição remota, first instance, identidade kernel nas duas direções, handles/process lifecycle, impersonation/substituição, replay/restart, frames, cancelamento/timeouts e cleanup. Os cenários não executados continuam não qualificados. A primeira execução antiga abortou antes de pipe por logon SID missing; os bloqueios posteriores também não qualificaram transporte.

**Gate B — identidade viva BYX: não provada.** Mesmo que A passe, é necessário distinguir BYX autorizado de Java arbitrário do mesmo usuário. Verificação da assinatura de java.exe identifica o publisher do JDK; não atesta JAR/classes carregadas. Faltam composição e integridade protegidas de launcher/JVM/JAR/configuração/native libraries, controle de agentes/loading/injeção dentro do threat model aprovado, vínculo da evidência ao processo/canal vivo e resistência a substituição/identidade reciclada. SID, PID, nome/path, hash isolado ou segredo per-user comum não resolvem esse gate.

Depois desses gates, pareamento criptográfico mútuo com provisioning protegido, sessão autenticada de usuário e autorização por operação no Service ainda precisam aprovação e testes próprios. Conectividade, identidade kernel, código vivo, pareamento, login e autoridade permanecem estados separados. O contrato macOS não foi alterado.

Recomendação: próximo marco deve ser **revisão do artefato e autorização do ambiente de pesquisa**, escolhendo A ou C com o responsável. A investigação de identidade viva deve continuar explicitamente separada do transporte. **Não recomendar integração privilegiada Service agora.** A sonda nativa não se tornou executável por este diagnóstico, não existe procedimento de execução aceitável já autorizado e nenhuma aprovação futura foi presumida.

## Preservação e inventário Git

[Verificação](c4-2-0-r/preservation-check.json): 857 arquivos-fonte Panel/Service e 25 artefatos do manifesto C4.2.0 conferem integralmente, sem diferenças. RFC e relatório anterior preservados. As adaptações ACL e correções visuais anteriores continuam intactas. Nenhuma modificação em Service, signer, custody, Keychain, identidade macOS, autorização ou pesquisa ETHUSDT. Não foi executada regressão macOS e não se alega qualificação nova.

Inventário incremental: novo `windows-app-control-diagnostic.md` e novo diretório `c4-2-0-r` com eventos sanitizados, acessibilidade dos logs, estado de política, identidade do arquivo e verificação/inventário. Mudanças locais anteriores não pertencem a este marco. Sem commit/push, sem produção IPC ou autenticação fake.

**WINDOWS_APP_CONTROL_CAUSE_VERIFIED**
**IPC_NATIVE_PROBE_EXECUTION_NOT_AUTHORIZED**

Qualificações anteriores permanecem: WINDOWS_IPC_TRANSPORT_PROBE_BLOCKED; WINDOWS_APP_IDENTITY_NOT_PROVEN.

REAL USER KEY = NOT AUTHORIZED
REAL TX = DISABLED
BROADCASTS = 0
REAL FUNDS = 0
REAL USER WALLETS = 0
ETHUSDT RESEARCH = UNTOUCHED

STOP após o diagnóstico. Nenhuma execução automática do harness ou integração de produção.
