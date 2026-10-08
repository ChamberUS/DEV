# BYX-MVP — V2.1T-4 — Panel → Service → Signer QA

## 1. STATUS

**READY_FOR_LEGACY_SIGNER_CLEANUP_AND_RUNTIME_HARDENING**. Aceitação limitada à integração sintética local descrita aqui. REAL USER KEY: NOT AUTHORIZED; REAL TX: DISABLED; BROADCASTS: 0; REAL FUNDS: 0; REAL USER WALLETS: 0; ETHUSDT RESEARCH: UNTOUCHED.

## 2. REPOSITORY STATE

Implementação em `/Users/buynnex-corp/dev`, branch `feature/byx-ui-redesign-v1`, HEAD inicial `ee0d6966a2235f8de1c3d54123f78c9bd45b161f`. A pasta ativa do IDE é outro checkout de captura, mantido sem alterações. Alterações anteriores de `build-app.sh`, `PanelApp.java`, plumbing dos perfis no POM, iaos-web e outros arquivos foram preservadas e excluídas do commit T-4. A validação ocorreu sobre a árvore local combinada; o commit não incorpora esse trabalho anterior. Sem push, reset, clean, stash, merge ou rebase.

## 3. PANEL/SERVICE CONTRACT

Fluxo real: WalletScreen → WalletPane/Controller → WalletGateway → AuthorityClient → socket local pareado e peer verificado → WalletIpc → WalletEndpoint → WalletLifecycle T-3 → CustodyClient → helper Go assinado → Keychain de QA com Data Protection. Panel não instancia custody nem lê Keychain de signing.

Contrato envelope `v=1`, `walletSchema=1`, ID correlacionado, sessão opaca. Consultas: `wallet.list`, `wallet.get`, `wallet.health`, `wallet.signerHealth`, `wallet.operation`; mutações exclusivamente `wallet.createSynthetic`, `wallet.deleteSynthetic`, `wallet.signSynthetic`. Campos extras, schema incompatível e tipos incorretos são recusados. `wallet.*` desconhecido falha QA_BARRIER. Não existe rota wallet de broadcast/import/export/recovery. Cada mutation passa pelos guards canônicos T-3; as consultas observam e nunca reparam catálogo/chaves.

## 4. PUBLIC WALLET DTO

DTO fechado e idêntico em Panel/Service, testado por comparação de fonte. Inclui schemaVersion, geração pública independente, revisão, capability, estado, disponibilidade do signer, wallets e operações públicas, allowedActions. Wallet: walletId/address/publicKey/version/lifecycleState/healthState/recoveryPolicy/timestamps. Operação: operationId/requestId público de idempotência/walletId/action/state/outcome/publicHash/timestamp. Sem owner interno, signingKeyRef, namespace, receipt, scalar, seed, mnemonic ou private key. A projeção lista a wallet local atual e o último tombstone, com oito operações recentes; consultas por ID podem selecionar registros anteriores. Guards consultam o journal completo, sem descartar operações desconhecidas antigas.

## 5. UI STATE MODEL

LOADING, NO_WALLET, CREATING, READY, LOCKED, SIGNER_UNAVAILABLE, DEGRADED, NEEDS_ATTENTION, DELETING, DELETED, OPERATION_PENDING, UNKNOWN_RESULT e UNAVAILABLE são tratados explicitamente. Falha de conexão não vira NO_WALLET. Capability selada no build e allowedActions do Service controlam botões; USER não recebe controles sintéticos. Confirmações explícitas para criar, assinar e excluir. Aviso LOCAL_ONLY_NO_RECOVERY visível; exclusão mostra identidade e irreversibilidade. Sucesso anterior não é exibido como sucesso da operação desconhecida.

## 6. CREATE E2E

Criação usando conta ADMIN sintética, sessão real, MFA recente e elevação; chave gerada pelo helper, jamais pelo Panel. DTO READY com endereço byx1 e política local sem recovery. Cliques concorrentes bloqueados no controller; repetição do mesmo pedido converge no Service. Teste nativo perde deliberadamente a resposta após commit ACTIVE: Panel mostra reconciliação necessária; restart recupera a mesma identidade, sem criação automática.

## 7. RESTART / RECONCILIATION E2E

Processos Panel e Service reiniciados separadamente com identidade pública preservada. Service adquire authority e reconcilia antes de expor API. Regressões T-2R confirmam fencing por audit token, ausência do helper antigo e quiescência. Não há inferência de morte por PID isolado, timeout ou socket fechado. Regressores T-3 cobrem checkpoints de create/delete/journal/sign desconhecido. Restart após delete preserva tombstone e scalar ausente.

## 8. SYNTHETIC SIGN E2E

Confirmação explícita de ADMIN elevado; vetor typed canônico de QA fica no test-jar do Service, com confirmação por ThreadLocal. Usa assinatura T-3 e verificação independente Java da assinatura DIRECT/endereço. Panel recebe apenas resultado público/hash. Replay de sign não redispatcha. Signer indisponível e UI deliberadamente defeituosa são recusados no Service. Barreira real TxService recusa broadcast; chain PRODUCTION_DISABLED.

## 9. DELETE E2E

Pedido com walletId, expectedVersion e idempotencyKey. Delete real percorre revoke/delete canônico, deixa tombstone, atualiza saúde DELETED, nega sign posterior. Repetição converge com a versão original. Refresh e navegação nunca executam cleanup, delete ou recriação. Teardown de QA remove somente bindings sintéticos conhecidos e anchors TEST.

## 10. UNKNOWN RESULT

Timeout de mutation mantém chave pública do pedido e geração do Service para reconciliação. Sem retry automático de create/sign/delete. Resposta saudável não correlacionada não limpa UNKNOWN_RESULT; somente operação correspondente concluída ou novo Service reconciliado permite sair. Estado desconhecido persiste por hide/show. Deadline de RPC wallet: 15 s; processamento wallet Service: 20 s; deadlines ordinários e fences T-3 preservados.

## 11. ERROR/QUARANTINE UX

Anomalias nativas isoladas: metadata órfã, chave órfã, key mismatch e catálogo AEAD corrompido. NEEDS_ATTENTION, ações false, identidade não reparada pelo UI, recusa de sign e quarentena persistente após restart. Catálogo corrompido expõe somente erro fixo e nega autenticação; sem payload de catálogo. Inventário incompleto, signer offline e quiescência não provada têm estados distintos/fail closed. Erros retornam códigos fixos sanitizados, sem exception message interna.

## 12. THREADING / NAVIGATION

Gateway executado em worker; render no executor JavaFX. Geração por request descarta respostas após navegação; revisão/geração do Service evitam rollback visual. Double click/busy impede overlap; conclusão de mutation após navegar dispara consulta quando apropriado. Canais wallet independentes evitam prender o monitor principal de login/logout. Timer de tela a cada 30 s apenas consulta, e para ao ocultar. Testes JavaFX exercitam responsividade e controles USER.

## 13. AUTHORIZATION

Principal e sessão derivados do AuthService real do Service, não de role/MFA informados pelo Panel. Peer verificado e sessão ativa em consultas; ADMIN, elevação e MFA recente em mutation. Guard reconsulta sessão em cada etapa e antes de entregar sucesso; sessão revogada durante create não recebe sucesso. Isolamento de owner: USER após wallet ADMIN recebe NO_WALLET sem vazamento; regressão dedicada preservada. Terminal/Panel/wrong role/assinaturas adulteradas continuam recusados pelo helper; Panel não consegue adquirir authority custody.

## 14. SENSITIVE DATA AUDIT

DTOs e respostas nativas testados contra campos internos/secretos. UI, controller e gateway não recebem signingKeyRef, namespaces, receipt ou conteúdo de chave. Chaves, MFA e bindings de fixtures ficam somente em harness/Service e arquivos privados TEST, não no artefato público. Probes adversariais históricos injetam referências sintéticas fora de banda para testar recusa: isso não é entrega de referência pela API Panel. Logs/evidências publicam códigos, IDs públicos, hashes e metadados de processo; sem scalar/seed/mnemonic/private key. MacSecurity do Panel foi sincronizado com os utilitários públicos de identidade T-2R para satisfazer o teste de paridade, sem API SecItem/custody.

## 15. TEST RESULTS

| Suíte | Resultado |
|---|---|
| Panel DEFAULT final | 723 PASS / 0 FAIL / 1 SKIP |
| Service final | 469 PASS / 0 FAIL / 0 SKIP |
| Go (inclui subtests) | 59 PASS / 0 FAIL / 1 SKIP |
| Panel → Service → signer | 42 PASS / 0 FAIL / 0 SKIP |
| Anomalias nativas | 44 PASS / 0 FAIL / 0 SKIP |
| Runtime disabled | 6 PASS / 0 FAIL / 0 SKIP |
| T-2R fencing | 24 PASS / 0 FAIL / 0 SKIP |
| T-3 lifecycle regressions | 178 PASS / 0 FAIL / 0 SKIP |
| Security T-1 | 65 PASS / 0 FAIL / 0 SKIP |
| Panel custody authority denial | 1 PASS / 0 FAIL / 0 SKIP |
| Packaged DEFAULT | 11 PASS / 0 FAIL / 0 SKIP |
| Panel QA serial anterior | 723 PASS / 0 FAIL / 1 SKIP |

SKIP Panel: CaptureLiveObservationTest exige opt-in `capture.live`; não foi ativado para esta entrega. SKIP Go: TestProcessHarness só roda em subprocesso e foi exercitado pela suíte Java; três pacotes de comandos sem testes não contam como tests skipped. Os números Go incluem subtests, não são somados aos asserts nativos. A rodada serial Panel QA usa o snapshot anterior; a suíte Panel DEFAULT final e o E2E nativo QA cobrem a fonte final, incluindo estilo e refresh periódico.

Falhas intermediárias são registradas, não aceitas: guard de dependência Service corrigido movendo transport para pacote público; baseline de paridade MacSecurity corrigida sem relaxar teste; timeout RPC do sign corrigido sem relaxar fencing; teste USER nativo revelou lookup owner incorreto em observação global, corrigido com healthOf interno e regressão. Uma execução concorrente Panel teve três falhas de timing do mascot; execução serial passou sem alteração de mascot. Tentativas nativas 28/1 e 32/1 preservadas separadamente; somente a rodada final 42/0 vale como aceitação.

## 16. VISUAL QA

Capturas reais JavaFX da WalletScreen integrada a 1920×1080: [ADMIN](visual/admin-1920x1080.png) e [USER](visual/user-1920x1080.png). Revisão visual: contraste legível, identidade/hash completos sem corte, aviso recovery visível, botões coerentes, nenhum controle sintético em USER. A parte antiga de prova externa mostra unavailable e foi diferenciada da wallet local. Snapshot foi produzido pelo Panel com gateway nativo real; os dados da seção externa são uma seam somente leitura unavailable. Estados transitórios e navegação são cobertos por testes; não há captura nativa de todos os estados.

## 17. DEFAULT SAFETY

Pacote DEFAULT separado em `/tmp/byx-v21t4-default-qualified/BYX-MVP.app`, capability Panel DISABLED e chain PRODUCTION_DISABLED, assinatura deep/strict validada, sem helper/launcher/test-jar/vetores de QA ou TxLab. ServiceMain usa composição disabled, sem signer de produção habilitado. Runtime de QA assinado inicia a mesma composição ServiceInstance disabled sem abrir custody, catálogo ou secret store: status UNAVAILABLE, ações false, create/sign/delete FEATURE_DISABLED e tx TX_DISABLED. Isso prova comportamento da composição e inspeção do pacote DEFAULT; o GUI/ServiceMain DEFAULT empacotado não foi iniciado para evitar tocar autoridade real.

## 18. CAPTURE HEALTH

Collector 96558 e supervisor 10657 preservados; nenhuma signal/reconfiguração/restart. Observação final somente de metadados: 2026-10-08T08:04:19.808060+00:00, mesmo `.part` 10,375,846 → 10,891,195 bytes em 3 s, crescimento 515,349 bytes. Checkout de captura limpo. H001/H002/VALIDATION/TRAIN/holdout/ETHUSDT não foram processados. Crescimento comprova escrita operacional, não integridade científica ou elegibilidade de cohorts.

## 19. FILES CHANGED

Manifesto completo dos arquivos T-4 em `files-changed.json`. Principais grupos: Service wallet DTO/IPC/endpoint e observação lifecycle; Panel gateway/controller/pane e integração WalletScreen; resource capability/POM wallet-qa; fixtures test-only; build e verificadores de QA; relatório/JSONs/capturas. Build-app.sh/PanelApp.java e trabalho anterior do POM excluídos. Nenhum fonte Go ou contrato científico alterado.

## 20. KNOWN LIMITATIONS

QA exclusivamente local/sintético com certificados de desenvolvimento; não constitui autorização de produção. Sem recuperação, seed/import/export, hardware/power-loss/lock-screen QA ou fundos reais. Proteção existente contra instalação writable pelo mesmo UID continua limite conhecido para hardening seguinte. Perfis locais expiram em outubro de 2026 e precisam de renovação antes de novo uso após validade. Catálogo/journal mantém limites T-3; projeção pública tem limite explícito de histórico. Fixtures privadas encrypted/tmp e bundles ignorados não entram no commit. Runtime DEFAULT testado por composição, não por lançamento da GUI principal. Não há claim de notarização/distribuição ou cobertura visual de todos os estados. Validação usa a árvore com trabalho anterior do usuário, preservado fora deste commit.

## 21. NEXT ACTION

**PROCEED TO V2.1U — LEGACY SIGNER CLEANUP + RUNTIME/PACKAGING HARDENING** para revisão do usuário. T-4 termina aqui; V2.1U não foi iniciado. Commit local somente T-4; sem push.
