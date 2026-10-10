# C4.2.0-E — Authorized Windows IPC test environment plan

Data: 2026-10-09, America/Sao_Paulo. **C4_WINDOWS_IPC_TEST_ENVIRONMENT_PLAN_READY**.

O plano está pronto para revisão; **o ambiente não foi provisionado nem sua execução autorizada**. Recomenda-se uma VM Windows 11 Pro x64 persistente, separadamente provisionada pelo responsável, com política de pesquisa aprovada antes de receber executáveis. Se o notebook não puder hospedar essa VM sem alterações não autorizadas, usar host de laboratório aprovado ou máquina física dedicada. A assinatura confiável do harness é alternativa para preservar integralmente a política existente do notebook, não uma prova de identidade BYX.

Permanecem **IPC_NATIVE_PROBE_EXECUTION_NOT_AUTHORIZED**, **WINDOWS_IPC_TRANSPORT_PROBE_BLOCKED** e **WINDOWS_APP_IDENTITY_NOT_PROVEN**. Esta tarefa não autoriza instalação, mudança de política, assinatura, criação de usuários, transferência ou execução do harness.

## 1. Evidência e notebook atual

Lidos o [diagnóstico SAC](windows-app-control-diagnostic.md), a [investigação anterior](windows-ipc-identity-probe.md) e o [RFC](../../package-c4/WINDOWS_SECURE_IPC_RFC.md). Base: branch `feature/byx-windows-readiness-v1`, HEAD `625689b2021c4c6a5f4a409d5d4680bccc865cbc`. Windows 11 Pro x64; SAC `VerifiedAndReputableDesktop`, recusa 0xc0e90002; inventário CiTool negado. UI pública DEFAULT operacional conforme evidência histórica; nenhum startup foi repetido aqui.

[Leitura de capacidade](c4-2-0-e/host-readiness.json): 23,7 GiB RAM, 8 cores/16 processadores lógicos, C: 475,8 GiB totais/107,7 GiB livres, HypervisorPresent=true. VMMonitorModeExtensions, SLAT e VirtualizationFirmwareEnabled retornaram false via CIM; serviços vmms/vmcompute não foram encontrados. Esses resultados não confirmam Client Hyper-V instalado nem permitem concluir capacidade de firmware ausente com hypervisor ativo. O administrador deve confirmar suporte/hypervisor e compatibilidade, sem desativar VBS/HVCI/SAC. Não foram consultados ou alterados firmware, recursos opcionais ou políticas com elevação.

RAM/CPU parecem adequados para uma VM de 8 GiB/4 vCPU: **estimativa de viabilidade, não provisionamento validado**. Espaço é apertado para guest, updates e checkpoints. Reservar pelo menos 25 GiB livres ao host; preferir armazenamento aprovado adicional para imagens/checkpoints. Não presumir que licença Pro do host inclua outra licença Windows guest. Confirmar termos e orçamento com o responsável.

Client Hyper-V exige edição Pro ou superior e recursos de virtualização/SLAT. Windows 11 guest deve atender seus requisitos próprios, incluindo Gen 2, Secure Boot/vTPM, mínimo 4 GB RAM e 64 GB disco. [Microsoft: Hyper-V](https://learn.microsoft.com/en-us/windows-server/virtualization/hyper-v/host-hardware-requirements), [Microsoft: requisitos Windows 11/VM](https://learn.microsoft.com/en-us/windows/whats-new/windows-11-requirements).

## 2. Comparação das opções

Estimativas abaixo são esforço de planejamento, sem cotação comercial, SLA ou promessa de aprovação.

| Opção | Pré-requisitos/edição e proprietário | Notebook/custo/complexidade | O que permite medir | Limites e riscos |
|---|---|---|---|---|
| A — VM autorizada persistente (preferida) | Guest Win11 Pro x64 licenciado, atualizado; host Pro/Enterprise para Hyper-V, ou outro hypervisor suportado e aprovado. Responsável provisiona imagem, duas contas QA padrão e política já adequada à pesquisa; permissão documentada para hashes/versões e ações de teste. | Recursos parecem suficientes para uma VM; hypervisor completo ainda não confirmado. Estimativa 0,5–2 dias após aprovações/imagem disponíveis; licença, espaço e eventual host externo adicionais. | Win32/NPFS reais no kernel guest, DACL, SID, tokens, handles, IO, restart, ataques entre contas/sessões guest. | Integração host/guest e rede podem quebrar isolamento. Snapshot contém estado/segredos efêmeros e relógio pode voltar. Não prova política do notebook, hardware físico ou identidade BYX por ser VM. |
| B — máquina física dedicada autorizada | Hardware compatível Win11 x64, UEFI/Secure Boot/TPM e licença; Pro recomendado para gestão. Named pipes básicos não exigem Enterprise. Owner/admin aprova previamente imagem e execução, sem alterar o notebook protegido. | Notebook serve como estação de revisão; não é esta opção enquanto houver bloqueio sem procedimento aceito. Baixo custo se já existir máquina; aquisição/licença/manutenção se não. Estimativa 1–3 dias após disponibilização. | Mesmos casos nativos, mais comportamento físico de suspensão, firmware, TPM/hardware e dispositivos sob configuração registrada. | Custo e drift maiores; física também não comprova app identity, segurança de todas as builds ou compatibilidade macOS. Não resetar/reconfigurar notebook para converter em laboratório. |
| C — assinatura confiável do harness revisado | Owner aprova revisão e assinatura específica de pesquisa; prestador/certificado code-signing confiável à política atual, dependências igualmente elegíveis e evidência da cadeia. Nenhum requisito de Enterprise para verificar Authenticode. | Pode permitir execução futura no notebook sem mudança de política. Complexidade alta se não houver cadeia/processo aprovado: dias/semanas para cadastro/revisão, custo de serviço/certificado conforme cotação. | Se aceito e expressamente autorizado, casos nativos sob a política real do notebook; útil para comparação VM/host. | Assinatura cria identidade do publisher do arquivo e requer governança de revogação/custódia. Não prova código vivo Java/BYX. Aceitação concreta ainda não testada. Não autoassinar/importar raiz, nem usar certificados macOS. |
| D — análise estática/portável | Ambiente atual, ferramentas já permitidas; Java 21/Maven 3.9.9 para testes portáveis futuros. Sem privilégio admin ou mudança de edição. | Disponível agora; custo incremental baixo, esforço de revisão de horas/dias conforme escopo. Nenhum teste adicional foi executado neste marco. | Contrato, parsers, limites, lógica de negação e revisão da arquitetura/harness; execução Java só dentro do escopo já permitido. | Não mede named pipes, DACL/token/processo em kernel, timing/cleanup nativos nem aplicações vivas. Não executar harness via Java ou outro wrapper como alternativa à recusa. |

SAC considera certificados de provedores confiáveis; self-signed não é confiança presumida. Mesmo certificado adequado exige validação do artefato e políticas realmente vigentes. [Microsoft: assinatura para SAC](https://learn.microsoft.com/en-us/windows/apps/develop/smart-app-control/code-signing-for-smart-app-control). A opção C deve usar assinatura externa administrada pelo responsável; **nenhuma chave privada de code-signing será copiada ao guest/harness**. Nenhuma chave real de wallet/Signer é permitida. Contratar ou usar assinatura não está autorizado nesta tarefa.

Não recomendar Windows Sandbox como baseline: a investigação precisa de estado reproduzível, duas contas/sessões reais e evidências persistentes. WSL, containers e emulação de protocolo não substituem o kernel Windows guest. Localização, nome do arquivo ou seu transporte para outra pasta não conferem confiança.

## 3. Especificação reproduzível proposta

1. **Autorização anterior ao provisionamento:** documento com owner/admin, ambiente/imagem, objetivo, revisores, hashes do harness/peers, contas QA elegíveis, política de execução, permissões para testes de ataque e validade temporal. Admin administra o laboratório; harness roda somente sob conta padrão. Aprovação desta proposta não deve ser tratada como aprovação automática de sua execução.
2. **Guest:** Win11 Pro x64, UEFI Gen 2, Secure Boot habilitado, vTPM 2.0, NTFS; 4 vCPU, 8 GiB RAM e disco virtual nominal 80 GiB (mínimo OS 64 GB). Manter UAC, Defender e controles da imagem aprovada. Se a política preparada recusar o artefato, parar; não desligar proteção nem improvisar exceção. Provisionamento de imagem/política ocorre apenas pelo responsável em marco futuro.
3. **Contas:** QA-A e QA-B, ambas padrão, com senhas descartáveis fornecidas por canal próprio pelo admin, nunca no repositório/argv/log. Admin separado apenas para setup/coleta de inventário que exigir permissão. Execução A/B em sessões reais, incluindo dois logons de A para investigar user SID versus logon SID. Não herdar privilégios do admin; registrar integridade/elevation/session e anonimizar SID em evidência compartilhada.
4. **Toolchain:** Temurin JDK 21 x64, Maven 3.9.9, Git e toolchain C#/.NET Framework compatível com o harness; JavaFX 21.0.5 e JNA 5.17.0 somente quando necessários aos peers/testes. Registrar builds exatas e hashes de instaladores provenientes de fornecedores oficiais. Validar `java -version` e `mvn -version` usando Java 21; JAVA_HOME/PATH e caches reprodutíveis. Não migrar .m2, perfis ou credenciais indiscriminadamente.
5. **Entradas:** somente source/artefatos explicitamente revisados do manifesto, sem .git completa, identity.env, .env, certificados privados, home, panel.db, tokens, Keychain, binários Service/signers ou captura ETHUSDT. Compilação dentro do guest gera novo hash; autorização precisa cobrir esse artefato final. Hash do exe existente `FEF1EF45ADA5F6A7CED7EC529968F8036349DCA384E5E2286B750492EF981A8B` identifica apenas o artefato atual, não o conteúdo dos eventos bloqueados anteriores.
6. **Isolamento:** rede desconectada durante casos locais; atualização/download por janela controlada anterior, sem acesso a backend BYX, exchanges ou wallets. Sem compartilhamento do workspace host, pastas graváveis/drive redirection/clipboard USB ou certificados host. Transferência allowlist por mídia/artefato revisado. Para rejeição remota, segundo endpoint QA autorizado em switch de laboratório isolado, sem NAT/bridge para LAN/produção; caso contrário NOT_EXECUTED.
7. **Recursos do harness:** nomes `BYX-C420-ISOLATED-<GUID>`, processos filhos próprios e ACK sem autoridade; nenhum endpoint de produto. Provas usam somente bytes aleatórios descartáveis de QA. Nenhum serviço privilegiado, credencial de produto ou sessão BYX real. Revisor verifica fonte antes de execução: build anterior contém warning CS0184; resolver/aceitar formalmente seus efeitos em marco separado, sem alterar o harness neste plano.
8. **Reprodução:** guardar versão de hypervisor, configuração VM sem IDs privados, digest da imagem base, Windows build/updates, política assinada/inventário fornecido pelo admin, toolchains, hash de fontes/artefatos e comando de build/execução aprovado. Snapshot limpo antes de inserir segredos efêmeros; exportar evidência antes de rollback. Executar restart de processo e restart guest como casos distintos. Repetir com nova geração/nonce após snapshot, sem permitir restauração como continuação de sessão.

Não fornecer um comando de execução como “já autorizado”: não existe hoje combinação ambiente/artefato/procedimento aprovada. A revisão do próximo marco deve fixar os argumentos e timeouts reais do harness antes de executar, sem mascarar erros do PowerShell/LASTEXITCODE.

### Evidência e critérios de execução

Cada caso produz JSONL com RunId/CaseId, UTC + elapsed monotônico, Windows build, hash do executável, gate, PID/creation-time de processos QA, APIs, retorno/GetLastError imediatamente preservado, contexto token/sessão anonimizado, descriptor lido do handle, esperado/observado e PASS/FAIL/ERROR/NOT_EXECUTED. Salvar stdout/stderr separados, exit real do processo e motivo do erro do launcher; não reutilizar LASTEXITCODE da compilação. Event records CodeIntegrity/AppLocker ficam ligados por timestamp/ActivityID, sem exportar logs inteiros contendo dados privados.

Evidência bruta de SID/SDDL e logs de processo fica em armazenamento privado aprovado do laboratório; versão compartilhada usa aliases consistentes A/B/logon-1/logon-2, preservando relações sem nomes. Não registrar chaves, HMAC secrets ou tokens; transcript pode registrar digest e fixture ID não secretas. Arquivar fonte/hash/configuração/resultados com SHA-256, revisão por segundo responsável e resumo de cobertura; hash não substitui revisão ou cadeia de custódia.

Usar três repetições dos casos funcionais/negativos; lifecycle/cancel 100 ciclos após warm-up, registrando min/mediana/p95/max, baseline final de handles do processo e estado de threads/filhos. Timeout proposto 2 s por IO e 300 ms para conexão negativa; allowance externo de teardown 5 s e orçamento total por run aprovado. Travamento termina somente processos QA próprios sob controlador autorizado; falha de drain/cleanup é FAIL, não sucesso por timeout. Valores são critérios propostos, não medições prévias.

## 4. Matriz nativa exata — todos os casos ainda pendentes

G1=transporte; G2=peer kernel/instância; G3=código vivo BYX; G4=pareamento criptográfico; G5=autorização Service. Registrar casos agregados por subcaso, nunca esconder um negativo não exercitado.

| ID/gate | Experimento isolado e evidência | Resultado exigido / limite |
|---|---|---|
| T01/G1 | CreateNamedPipeW com DACL explícita, FIRST_PIPE_INSTANCE, REJECT_REMOTE_CLIENTS, IO overlapped; A conecta, round-trip payload QA | IO real e descriptor efetivo corretos; ACK não autenticado. |
| T02/G1 | PIPE remoto via segundo endpoint QA aprovado; manter configuração de controle capaz de alcançar endpoint | Rejeição atribuível ao pipe/flag, não só firewall/rede indisponível. Sem peer/control, NOT_EXECUTED. |
| T03/G1–2 | Pipe user-SID-only: A permite, B real tenta conectar e criar outra instância | B negado com erro nativo; A permitido. Direitos write específicos não devem conceder create-instance indevido. |
| T04/G2 | Logon-SID-only: A logon-1 positivo, A logon-2 e B negativos; GetTokenInformation(TOKEN_GROUPS/SE_GROUP_LOGON_ID) | Verificar usuário e logon separadamente. Duas allow ACEs user/logon são união, não AND. |
| T05/G1–2 | DACL deliberadamente permissiva/default/NULL apenas em pipe QA separado; GetSecurityInfo e verifier experimental | Política da sonda rejeita insegurança antes de aceitar evidência; conectividade com ACL permissiva não é PASS de segurança. |
| T06/G2 | Owner/WRITE_DAC: processo hostil A tenta alterar descriptor do pipe QA e adicionar direitos | Medir direitos efetivos; sucesso expõe blocker de isolamento de mesmo usuário. Não “passar” porque SID continua igual. |
| T07/G2 | GetNamedPipeClientProcessId/ServerProcessId em ambas direções, OpenProcess, GetProcessTimes, tokens e handles retidos | IDs originados no canal correspondem a filhos vivos; PID informado em payload ignorado. Todas as leituras ausentes negam qualificação. |
| T08/G2 | Disconnect/reconnect concorrente entre consulta PID e abertura de handle; processo encerra durante verificação | Sem identidade de outra geração aceita; evidência da corrida. Repetição sem erro não prova ausência universal de race. |
| T09/G2 | ImpersonateNamedPipeClient em identification SQOS, leitura mínima de token, RevertToSelf em todos os caminhos | Identidade correta e retorno à própria identidade após sucesso/erro; sem operações privilegiadas impersonadas. |
| T10/G2–3 | Peer Java B/arbitrário sob A com mesmo java.exe assinado; peer nativo arbitrário local | Pode passar DACL; nunca rotular BYX. Rejeição positiva G3 requer verifier vivo aprovado ainda inexistente. |
| T11/G1–3 | Squatter filho QA cria nome primeiro; harness tenta first-instance | Recusa de posse/exclusividade; não reutilizar endpoint do impostor nem autenticar por nome. |
| T12/G2–3 | Servidor QA legítimo termina; impostor ocupa mesmo nome antes/depois da conexão | Canal antigo invalida, servidor novo precisa evidência integral; nenhuma prova de cliente enviada a servidor sem identidade. |
| T13/G2–4 | Restart servidor, nova geração; reusar antigo handle/peer/session experimental | Instância e sessão antigas recusadas, handle terminado sinalizado, nova geração exige verificação completa. |
| T14/G2 | Capturar PID/creation-time antigo; tentar observar reciclagem real sob churn bounded | PID igual/instância diferente negada pelo verifier. Se PID não reciclar, caso reciclado NOT_EXECUTED; stale handle não o substitui. |
| T15/G1 | Frames 0, 1, 8192, 8193, inteiro extremo, header/body parciais, UTF-8 inválido, EOF, mensagens desconhecidas | Limite antes de alocar; parser estrito, rejeição sem auth/autoridade. Tamanho 0 só aceito se explicitamente previsto no contrato aprovado. |
| T16/G1 | Slow reader/writer, silêncio, disconnect em leitura/escrita, término inesperado de filho servidor | Erro/timeout bounded, sem loop eterno, sem estado conectado fictício. Medir códigos e tempos. |
| T17/G1 | CancelIoEx antes/durante/ao concluir IO, stop repetido e late callbacks | Completion/drain antes de liberar OVERLAPPED/buffer; ausência de use-after-free, efeito tardio e close duplo. |
| T18/G1–2 | 100 ciclos connect/disconnect/restart/cancel + encerramento controller | Sem crescimento sustentado de handles/threads/filhos; wrappers zerados não bastam sem contagem OS. |
| T19/G3 | Composição futura aprovada versus launcher/JVM/JAR/config/DLL trocados; paths graváveis e TOCTOU | Código vivo vinculado ao peer rejeita alterações; hash isolado não basta. Atualmente sem mecanismo/positivo elegível: BLOCKED. |
| T20/G3 | Java agents, classpath/env/DLL search alterados, native loading não autorizado, módulo após verificação | Cobertura do threat model aprovado e falha fechada; nenhuma alegação sobre processo comprometido fora do modelo. Ainda BLOCKED. |
| T21/G2–3 | PID=0, handle inválido, token parcial/AccessDenied, assinatura/verifier inconclusivos | Negar, nunca fallback dev ou app identity baseada apenas em SID/path. |
| T22/G4 unit | HMAC puro QA: nonce/role/transcript/generation mutados e replay | Recusa criptográfica unitária; não qualifica pareamento de pipe. |
| T23/G4 canal | Futuro protocolo QA sobre canal real: provas nas duas direções, server-proof antiga, client-proof refletida/replay, segredo errado, ordem/mensagens alteradas | Ambos verificam peer e freshness/channel binding; servidor verificado antes de prova do cliente. Preparação atual não implementa esse teste: pendente. |
| T24/G3–4 | Processo hostil A possui segredo QA correto; reinício/snapshot restaura prova antiga | App impostor recusado apesar do segredo; fresh provisioning por app/generation e sessão antiga recusada. Sem isolamento de credencial aprovado: BLOCKED. |
| T25/G5 | Revisão estática da separação conta/sessão/MFA/papel/operação; futura matriz de acesso em milestone Service independente | Nenhuma autoridade real nesta sonda. Mock/ACK de QA não qualificam Service authorization; G5 permanece NOT_EXECUTED. |

As chamadas Win32 devem ocorrer no guest Windows real; ausência de resultado não pode ser preenchida por mocks. T19–24 dependem de decisões/implementações futuras **isoladas e revisadas**, não autorização para construir agora adapters de produção. A execução do harness existente cobrirá apenas seus casos presentes; matriz adicional exige extensão auditada em marco separado. Outros usuários/logons e remoto precisam provisioning explícito.

### Gates e extensão da evidência

1. **Transporte funcionando:** NPFS/frames/cancel/cleanup realmente medidos. Não implica token/identidade.
2. **Peer OS verificado:** conta/logon/instância do canal bidirecional e races tratados. Não identifica BYX.
3. **Aplicação viva verificada:** composição autorizada protegida e vinculada ao peer. java.exe signed, PID, SID, nome/path e segredo per-user não bastam.
4. **Pareamento verificado:** provisioning restrito ao app, provas mútuas fresh/bound/rotacionáveis sobre canal qualificado. Teste HMAC puro não basta.
5. **Service authorization verificada:** sessão de usuário, MFA, expiração/revogação e decisão de operação/papel no Service. Marco separado; proibido ativar agora.

VM permite resultados reais sobre kernel guest, NTFS/NPFS e seus tokens/processos; não são emulação de ACL. Valem apenas para build/configuração registrada. Bare metal adicional é necessário para afirmações de firmware/TPM físico, proteção de chave em hardware, suspensão real/dispositivos e desempenho de máquina alvo. Política SAC do notebook exige teste autorizado nesse notebook ou imagem comprovadamente equivalente; VM permissiva não demonstra compatibilidade SAC. Matriz de builds, políticas corporativas, sessões RDP/logon e configurações HVCI diferentes exige ambientes próprios; nenhum desses casos pode ser declarado coberto por um único guest. macOS sempre precisa regressão macOS.

## 5. Auditoria e checkpoint local seguro

[Manifesto CSV](c4-2-0-e/source-manifest.csv) / [JSON](c4-2-0-e/source-manifest.json): 2.010 entradas, classificadas por path/estado/tamanho e SHA-256 de arquivos não sensíveis, não ignorados. Snapshot antecede a escrita deste plano e exclui sua própria pasta para evitar autorreferência. [Contagens](c4-2-0-e/manifest-counts.json). Inventário não é auditoria exaustiva de segredos: nomes/tipos e conteúdo dos logs/screenshots precisam revisão privada antes de publicação.

| Grupo anterior a C4.2.0-E | Quantidade | Leitura de escopo |
|---|---:|---|
| Produção modificada | 10 | AppContext/PanelApp; AuthLayout/SupportScreen/auth.css; LocalServiceClient/ServiceLauncher/ServiceNotificationObserver; Database/PrivateFiles. |
| Produção nova | 2 | WindowsStorage.java (Windows específico); RuntimeStorage.java (dispatcher multiplataforma, não Windows-only). |
| Testes modificados | 28 | Helpers de temporários/IPC POSIX, boundary/runtime/storage e mascot/layout; nomes completos no manifesto. |
| Testes/auxiliares novos | 9 | WindowsStorageTest, WindowsPublicLayoutTest, WindowsServiceUnavailableTest; IpcProtocolContractTest, PublicResponsiveLayoutTest; ProcessTestChild, PublicVisualQa, SecureTempDirFactory, IpcTestFiles. Nem todos são Windows-only. |
| Documentação | 8 | RFC e relatórios C4 históricos; este plano é adicional. |
| Evidence | 502 | Logs/JSON/CSV/screenshots/metrics anteriores, incluindo diagnósticos. |
| Harness/fontes/binários isolados | 10 | C#/Java/EXE/class de pesquisa; generated .exe/.class não são source de produção. |
| Gerados ignorados | 1.439 | Principalmente target/build/reports; inventariados por metadados, sem hashing/conteúdo. Não publicar cegamente. |
| Potencialmente sensíveis | 2 | byx-packaging/identity.env e iaos-web/.env.example: já tracked, sem leitura de conteúdo/hash. Não atribuir a C4 nem transportar ao guest. |

A soma separa categorias primárias; evidências/untracked/Windows-targeted podem se sobrepor conceitualmente. Rótulo Windows-targeted no manifesto é revisão por função/nome, não afirmação de que todo arquivo só compila em Windows. Tracked não significa seguro para exportação: **identity.env já está no Git**, está sem alteração nesta tarefa e não foi inspecionado. Isso requer revisão privada do owner antes de qualquer export; não se afirma que contém chave privada nem se altera histórico. .env.example é candidato por nome, não segredo confirmado.

Leitura dos diffs confirma guard Windows native_service_unsupported antes de token/IPC e ServiceLauncher sem helper macOS no Windows; PrivateFiles/Database delegam storage Windows e preservam ramo POSIX. Testes mantêm distinção unsupported versus autenticação e fixture POSIX. [857 hashes](c4-2-0-e/source-preservation.json) seguem idênticos ao preflight C4.2.0. A auditoria não constitui nova validação de segurança de todas as alterações. Fonte macOS/Service e mudanças não relacionadas foram preservadas; zero alterações rastreadas fora do Panel no snapshot.

**Checkpoint recomendado, a executar somente em etapa autorizada posterior:** backup privado fora do guest e fora da árvore a publicar, com allowlist explícita de source/test/doc, snapshot HEAD/branch/status, patch binário dos 38 arquivos tracked modificados e cópia dos 11 novos source/test files. Acrescentar fontes do harness, documentação e evidência curada; gerados grandes e binários ficam em arquivo de evidência privado separado quando necessários, com hashes. Excluir identity.env, .env, chaves/certificados, wallets/dbs, caches e homes. Conferir hashes e restaurar em diretório temporário vazio para validar recuperação. Esse backup não foi criado aqui; apenas manifesto/preservação e plano foram escritos.

**Publicação futura em branch separada:** partir do HEAD base registrado em novo clone/worktree aprovado, reaplicar patch/lista de arquivos revisados e verificar igualdade; não mover/resetar/limpar a árvore suja atual. Separar commits de storage/DEFAULT, visual/testes, RFC/plano e harness isolado/evidence curated. Conferir dependências entre commits; não omitir testes auxiliares por engano. Excluir executáveis/class/target e evidência privada da publicação de source; manter hashes e comandos de reprodução. Stage futuro somente por paths explícitos e diff staged revisado, nunca git add .; secret scan local aprovado, diff/checks e PR draft em branch de pesquisa antes de merge. Commit/push/PR não foram executados e precisam nova autorização. Não incluir alterações iaos-web/byx-packaging nem reescrever a baseline macOS.

## 6. Aprovações faltantes e regressão

Faltam: escolha do ambiente e seu responsável; licença/imagem/armazenamento/hypervisor aprovados; política de execução e hash final autorizado; revisão do harness/native memory/warning; provisioning QA-A/B/logons; eventual segundo endpoint remoto; custodiante de evidência e retenção; assinatura externa se escolhida C; threat model e mecanismo de código vivo/provisioning. Esta lista registra pré-requisitos, não solicita mudança de política nesta tarefa.

Antes de publicar/mergear alterações C4, executar em **macOS provisionado autorizado** compile, StringsParityTest/PackageC1CatalogTest/NavigatorTest e suíte Maven inteira do Panel, suíte Service e QA existentes de identity/store/signer com somente materiais QA permitidos. Conferir verifier vivo, requisitos/selo/launch environment, audit token/pidversion, permissões 0700/0600, reparse/link rejection, reload/cleanup, sessão/MFA/autoridade e modos dev/packaged. Preservar todos os 23 cenários IPC originais e submodos; não substituir seus positivos por negação Windows. No Windows, repetir regressão DEFAULT/storage/visual e suites apropriadas após qualquer mudança futura, mantendo 23 ERROR históricos distintos de cenários qualificados. Nenhuma regressão nova executada neste marco.

Bloqueadores restantes de arquitetura: launcher dedicado/protected composition, JVM/JAR/native integrity sob atacante de mesmo usuário, vínculo canal→processo vivo sem race, ownership/WRITE_DAC, isolamento/provisioning de segredo por app, rotação/revogação e sessão independente da conexão. AppContainer/broker/composição selada são hipóteses a revisar, não soluções provadas. Não há transporte alternativo recomendado como fuga do bloqueio nem conexão Service privilegiada aprovada.

**C4_WINDOWS_IPC_TEST_ENVIRONMENT_PLAN_READY** significa plano entregue, não execução autorizada ou IPC qualificado.

REAL USER KEY = NOT AUTHORIZED
REAL TX = DISABLED
BROADCASTS = 0
ETHUSDT RESEARCH = UNTOUCHED

STOP. Sem alteração de políticas Windows, execução do harness bloqueado, integração Service, stage/commit/push, migração Keychain, custódia ou trading.
