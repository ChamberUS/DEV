# Windows C4 local checkpoint scope

Checkpoint local na branch feature/byx-windows-readiness-v1, pai 625689b2021c4c6a5f4a409d5d4680bccc865cbc. Sem push/merge/release. SHA definitivo e estado pós-commit constam do relatório local C4_WINDOWS_CHECKPOINT_REPORT.md, produzido depois do commit; ele não integra este mesmo commit porque registra seu SHA e recibo final.

## Coverage

Java 21.0.12.1 / Maven 3.9.9 / Windows 11 x64. Maven test compile compila o source DEFAULT. Focados: 250 testes, 249 PASS, 0 FAIL, 0 ERROR, 1 SKIP; exit 0, 38.462 s. Suíte completa atual: 1034 testes / 136 suítes, 1008 PASS, 0 FAIL, 23 ERROR, 3 SKIP; exit 1, BUILD FAILURE. Os 23 ERROR são native_ipc_fixture_unqualified: POSIX pairing and native peer identity required; no Windows substitute. Nenhum convertido em skip. Veja checkpoint-test-results.json e c4-1-f-error-matrix.csv.

A fonte é a mesma coberta pela última regressão C4.1-F, conforme 857 hashes do preflight C4.2.0, e foi retestada neste checkpoint. No product source patch após a regressão. Mudanças de documentação são sanitização/inventário, não nova implementação. Nenhuma nova dependência: pom.xml permanece igual ao pai, JNA/JavaFX já existiam.

## Source and security

checkpoint-source-inventory.csv registra todos os 49 source/resources/test candidates (38 modified + 11 new). 12 production files/resources: storage Windows fail-closed; startup/shutdown e recusa Service; Help/FAQ/form scroll. 37 tests/helpers: 28 modified + 9 new. Windows-only: WindowsStorage implementation, WindowsStorageTest, WindowsPublicLayoutTest, WindowsServiceUnavailableTest. RuntimeStorage é dispatcher multiplataforma; os demais Java modificados precisam regressão macOS.

WindowsStorage exige NTFS local, ACL legível com owner SID real, DACL e ACEs reconhecidas; rejeita reparse/hard-link/path remoto/ADS/principal desconhecido; mantém handles e verifica sidecars. PrivateFiles.tighten não faz migração insegura no Windows. Database continua ramo POSIX 0700/0600 e histórico panel.db somente leitura. API Win32 é lazy no macOS. Isso é storage Panel, não cofre autorizado por identidade de código.

Windows loadSecret recusa antes de token/connect; launcher não resolve helper macOS; testes de unavailable, strict identity, bounded frames, legacy isolation e autorização continuam. Nenhuma mudança no verifier macOS, Service/custody/signer ou ETHUSDT collector/supervisor. Nenhuma sessão BYX, carteira/credential real ou transação foi habilitada. Fixtures de autenticação/passwords e SID órfão de teste são sintéticas, somente src/test, não credenciais reais. PublicVisualQa é opt-in de componentes públicos sem autoridade; não modifica DEFAULT.

## Documentation and excluded private evidence

Incluídos RFC, sete relatórios históricos sanitizados, matriz dos 23 erros, este escopo, inventário source e resultados sem properties privadas de Surefire. Nome de conta/SID/caminho de perfil removidos de windows-baseline.md e windows-storage-implementation.md; originais retidos em checkpoint-local/*.private-original. Relatórios históricos conservam suas conclusões e datas, não representam nova autorização.

Links históricos para arquivos locais de QA ficam preservados como referência à evidência privada, porém esses alvos não acompanham este commit. Resultados curados e matriz incluídos são a evidência portátil; não presumir que todo link estará disponível num clone. Generated PNG/JSON/log/CSV bruto, matrizes com metadados privados, snapshots, source manifests absolutos, target, EXE/class, fontes do harness isolado e caches continuam locais e não foram staged. Arquivos de pesquisa são referenciados pelos relatos; este checkpoint não autoriza reexecução nem tenta compilar/executar sonda nativa.

Nenhum screenshot foi incluído; não se alega anonimização dos screenshots excluídos. identity.env já tracked na baseline, fora do diff e sem leitura; não há novo arquivo desse projeto no commit. Antes de publicação, owner precisa auditar privadamente arquivos sensíveis já presentes na baseline e metadados Git; este checkpoint não reescreve histórico. Sem datasets/runtime DB/Keychain/SigningKeyRef real/token/provisioning/private key no delta revisado. Nenhum binário assinado ou executável gerado incluído.

## macOS handoff

Publicação da branch precisa autorização posterior. Em Mac autorizado, fazer fetch dessa branch e usar worktree separado, detached no SHA do recibo; não limpar/resetar/stash/mover branch da árvore existente. Preservar desenvolvimento local e ETHUSDT collector em execução. Confirmar git rev-parse HEAD e mvn -version (Java 21/Maven 3.9.9).

No novo worktree/mvp-binance-panel:

    mvn -DskipTests compile
    mvn "-Dtest=StringsParityTest,PackageC1CatalogTest,NavigatorTest" test
    mvn "-Dtest=PublicResponsiveLayoutTest,AuthLayoutTest,AuthKeyboardTest,HelpScreensTest,AuthenticationEpochTest,SessionReturnTest,PrivateFilesTest,LegacyPanelDbTest,RuntimeMigrationTest,LegacyIsolationProductTest,LocalServiceClientTest,ChainStatusClientTest,MarketFeedClientTest,IpcProtocolContractTest,MascotViewTest,MascotPresenceViewTest,MascotPresencePureTest,MascotPolicyTest,MascotAvatarTest,PackageC2ThemeTest,PackageC3NotificationTest,PackageC3NotificationViewTest" test
    mvn test

Depois, byx-local-service: mvn test em ambiente de QA aprovado. Arquivar resultados de cada comando antes do seguinte. Verificação packaged identity/cofre por procedimentos QA já existentes deve ser revisada e autorizada no Mac, sem usar chaves/referências de produção. Preservar audit token/pidversion, requisitos/selo/launch environment, runtime owner 0700/0600, leitura bounded sem links, rotação/provas/replay, login/MFA/session/role/authorization, teardown/late callbacks. Os 23 casos POSIX e submodos devem executar no Mac; não aceitar recusa Windows como prova. Skips OS.WINDOWS no Mac são esperados somente nas classes gated, não substituem testes portáveis.

JavaFX público nativo: Login, Help/FAQ, EN/PT-BR, DARK/LIGHT, keyboard/focus/scroll/trackpad, resize e shutdown; validar FULL/REDUCED/OFF mascot, rounding/font métricas e acesso ao fim da orientação. 1100x700 tem observação física Windows histórica; 1440x900/1920x1080 apenas snapshots automatizados completos, sem UAT física aprovada. Aprovação visual humana e regressão macOS permanecem pendentes. Service Windows, código vivo BYX e pairing autorizado não comprovados. IPC execution remains NOT_AUTHORIZED.

REAL USER KEY = NOT AUTHORIZED; REAL TX = DISABLED; BROADCASTS = 0; REAL FUNDS = 0; REAL USER WALLETS = 0; ETHUSDT RESEARCH = UNTOUCHED.
