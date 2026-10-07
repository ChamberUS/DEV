# V2.1G — Migração e cutover da autoridade de autenticação

Plano PREPARADO (nada de dado real foi alterado ao escrevê-lo). `PRIVATE_CAPABILITIES_ALLOWED = false` e `EXPLICIT_REVIEW_REQUIRED = true` continuam: autenticar de verdade NÃO liga recurso privado.

## Estado de partida (auditoria SOMENTE LEITURA do home real)
Esquema do `panel.db` (SQLite): `users` (id inteiro, username/email únicos sem diferença de caixa, `password_hash` Argon2id PHC, `role` ADMIN|USER, `status` ACTIVE|DISABLED, phone, flags de verificação, `must_change_password`, datas), `trusted_devices` (hash SHA-256 do token; o token está no keychain legado), `audit_log` (ator/evento/detalhe), `rate_limits`, `meta`, além de tabelas BYX (carteiras/pagamentos/gás) que referenciam `users.id`. Contagens reais e itens do keychain: ver o relatório da fase (nunca valores sensíveis). A chave de identidade estável é `users.id` → `legacyUserId` na autoridade (carteiras BYX continuam ligadas ao mesmo id).

## Pré-requisitos (verificados antes de qualquer dado real)
GUI do bundle final validada manualmente; regressões de identidade, cofre de segredos e QA da autoridade em PASS no artefato final; IDs finais `com.buynnex.byx` / `com.buynnex.byx.service`; perfil de provisionamento de desenvolvimento válido (expira em 2026-10-13: reprovisionar com `byx-packaging/provisioning/provision.sh` e reconstruir se o cutover passar dessa data).

## Namespace de produção × QA
Itens do keychain de dados protegidos, caminhos e estado são separados por perfil (`AuthProfile`). Produção: `SecretId.AUTHORITY_ENCRYPTION_KEY` (AES-256, aleatória), `AUTHORITY_ROLLBACK_ANCHOR`, `RESEND_API_KEY`, `TWILIO_API_SECRET` no namespace `com.buynnex.byx.service/secrets/…`, diretório `~/.byx-local-service/authority`. QA/teste: ids de escopo TEST (`invalid.byx-canary-test/…`) e diretório temporário. `ScopedSecretStore` recusa o escopo oposto; o QA se recusa a rodar sobre o home real; testes de fonte impedem referências cruzadas. O grupo de acesso do keychain é o application-identifier REAL assinado do helper (lido do perfil na construção do bundle; nada de Team ID fixo no código).

## Etapas (todas via helper assinado `byx-migrate`, subcomando como argumento)
1. `audit` — SOMENTE LEITURA: lê o banco com `mode=ro&immutable=1` (sem arquivo auxiliar, sem mudar permissão), confere o SHA-256 antes/depois, consulta só ATRIBUTOS dos 3 itens do keychain legado (não lê segredo, não abre diálogo). Reporta contagens, papéis, estados, algoritmos, normalização (usuários que viram minúsculas), problemas de importação.
2. `plan` — grava `~/.byx-local-service/authority/migration-manifest.json` (0600, sem segredos): versão, data, versão do esquema/impressão digital, SHA-256 do banco, contagens (total/papéis/habilitados/algoritmos), itens do keychain de origem, versão do formato alvo, IDs e requisito de código do serviço, status.
3. `prepare` (exige digitar `PREPARE-REAL-MIGRATION`): confere manifesto e que os USUÁRIOS não mudaram desde o plano (mudança só em auditoria/último login não invalida); cria a autoridade de produção (chave AEAD e âncora novas só no cofre do serviço), importa as contas preservando verificador Argon2, papel, estado e identidade legada (ninguém é promovido, conta desabilitada continua desabilitada, `credentialVersion=1`, nenhum usuário padrão), grava a configuração não secreta dos provedores e o prazo de 5 minutos definido em `AuthLimits` (o prazo legado não autoriza elevação), **liga a trava de migração**, relê/decifra/valida e compara FONTE × ALVO sem expor valores (qualquer diferença PARA). Depois copia Resend e Twilio: LÊ O LEGADO UMA VEZ → item tipado de produção → relê → compara em tempo constante; item negado/ausente bloqueia SÓ aquele item (sem arquivo/env/área de transferência/argv/log); item de produção diferente já existente NUNCA é sobrescrito. O token de dispositivo confiável legado NÃO é lido nem migrado.
4. `verify` — somente leitura: refaz a comparação e confirma os segredos no cofre.
5. Cutover manual (ver abaixo) e `finalize` (exige `FINALIZE-CUTOVER`): remove a trava, marca o legado `ROLLBACK_ONLY`. NADA legado é apagado.

O diálogo do macOS ao ler o keychain legado (o item confia no `java` do painel antigo, não no helper assinado) é esperado e manual: o usuário o autoriza. Negado ⇒ o item fica `BLOCKED_ACCESS` e `prepare` pode ser repetido depois (retoma sem refazer o que já está pronto).

## Trava de migração (janela de segurança)
Ligada por `prepare`. Recusa: troca de senha, mudança de papel, desabilitar/habilitar, apagar, criar conta e inscrever dispositivo confiável permanente. Permite: login, logout, 2º fator, revogar confiança. Só o migrador liga/desliga (nunca IPC). Não há duas bases sincronizadas: nada diverge do legado enquanto ela estiver ligada.

## Cutover manual (somente com autorização explícita; nenhum passo abaixo foi executado)
1. Fechar o app antigo. 2. `audit` e `plan`. 3. `prepare` (autorizar o diálogo do keychain se aparecer). 4. `verify`. 5. Abrir o app NOVO (o serviço é iniciado pelo próprio app): login com a sua senha atual, 2º fator REAL (e-mail e SMS: envio real exige confirmação), elevação, logout, novo login. 6. Se tudo OK: `finalize`; ficam `panel.db` e os itens legados como ROLLBACK_ONLY até uma limpeza separada.

## Trusted device
Não migrado. O 2º fator é refeito e o serviço emite um novo registro (revogável, 30 dias, ligado à conta; só fora da janela de segurança). O item legado e a linha antiga ficam apenas para rollback.

## Rollback
NÃO é flag: é voltar deliberadamente ao artefato anterior (que usa o `panel.db` legado intacto). Enquanto a trava estiver ligada, nenhuma mudança de senha/papel ocorreu na autoridade nova, então o legado não divergiu. O app novo não escreve nas tabelas `users`/`trusted_devices`/`rate_limits` do banco antigo (guarda de teste); só eventos de auditoria não relacionados a autenticação podem aparecer lá. Depois do `finalize`, mudanças feitas na autoridade nova NÃO voltam ao legado: o rollback passa a perder essas mudanças (por isso `ROLLBACK_ONLY` e a limpeza são etapas separadas e deliberadas).

## Limites
Administração de contas (criar/listar/desabilitar/papel/redefinir/contato), password recovery e leitura da auditoria do serviço ainda não têm operação no serviço: OPERATIONAL LIMITATION, indisponíveis no produto (fases futuras, com revisão). Troca da própria senha continua no serviço, com prova da senha atual. O histórico de auditoria antigo continua só-leitura no `panel.db` (a interface mostra esse histórico; os eventos novos estão no serviço).

## V2.1G-1 — Gate de segurança antes do cutover

`AuthLimits` mantém sessão absoluta de 8h, idle de 15m, MFA recente de 10m e elevação de 5m absolutos. Atividade, navegação, keepalive e chamadas repetidas durante uma elevação válida não estendem seu prazo. Trusted device não substitui o segundo fator real recente para elevar; papel ADMIN e sessão são revalidados na autoridade.

`SessionManager` e `AdminSession` são apresentação. `AdminAccessService.requireAdmin()` consulta a autoridade. Sem endpoint de autorização no serviço, jobs de pesquisa, operações de wallet/pagamento/gas, escrita da auditoria legada e persistência de settings ficam em `SERVER_AUTHORIZATION_REQUIRED`, antes de qualquer efeito. O inventário por operação está em `docs/PRE_CUTOVER_OPERATION_INVENTORY.csv`. Gate visual continua permitido para navegação; leituras públicas/locais são distinguidas no inventário.

`finalize` mantém a frase `FINALIZE-CUTOVER` e repete `verify` antes de remover a trava. Exige autoridade trusted, janela de segurança ativa, gate privado fechado, perfil de provisionamento embutido vigente, ADMIN enabled e configuração/cofre dos dois provedores disponíveis. Não envia OTP para esse preflight; disponibilidade de entrega real será verificada no smoke manual, com autorização. Falha conserva o estado PREPARED e não levanta a trava. Não existe chamada de migração pelo app normal.

A revisão e o QA usam dados sintéticos; não constituem execução ou aprovação automática do cutover real. `PRIVATE_CAPABILITIES_ALLOWED=false` e `EXPLICIT_REVIEW_REQUIRED=true` permanecem.

## FUTURE POST-CUTOVER HARDENING (não implementado agora)
Os verificadores migrados do painel legado estão no formato antigo (Argon2id sobre o array de apoio do ByteBuffer, com NUL de preenchimento para senhas de 10+ caracteres); o serviço os aceita calculando SEMPRE os dois formatos (exato e legado). Depois de um cutover estável, e SÓ em uma autenticação comprovada, poderemos converter o verificador legado para o formato exato de modo transacional (nova `credentialVersion`, revogação das outras sessões, nenhum rehash antes disso). Nesta fase NÃO há rehash automático e NÃO muda `credentialVersion`: a janela de segurança ainda está ativa e o rollback precisa continuar limpo. CORREÇÃO (Stage 3A, provada por `LoginAuthorityMutationTest` e pelo login real): um LOGIN_OK bem-sucedido GRAVA na autoridade (`AuthorityAdmin.recordLogin` → `mutateRaw`), e SOMENTE isto: `lastLoginAtMs` da conta que entrou, a versão do snapshot (+1), um nonce AES-GCM novo e o MAC/âncora da nova versão. Verificador, `credentialVersion`, papel, habilitação, contatos, provedores, dispositivos confiáveis e a trava de migração não mudam; falha de login, status de sessão e logout não gravam. Portanto o hash de `authority.bin` muda a cada login; o que se compara é o estado semântico (`byx-migrate verify`), não o hash anterior ao login.

## Lição do primeiro login real (V2.1G Stage 3A)
O QA sintético tinha fabricado a "origem legada" com o hasher do SERVIÇO e a verificação da migração só compara impressões digitais; por isso não viu que os hashes reais usam o formato antigo. Agora toda origem legada sintética (QA empacotado e testes) é gerada pelo algoritmo legado EXATO (`LegacyPanelHash`, `UTF_8.encode(...).array()`), há matriz de compatibilidade e um guarda de código impede voltar a simular o legado com o hasher do serviço.

## ServerAuthorizer (V2.1G, painel)
`panel.security.ServerOperation` é a lista FECHADA das operações sensíveis que ainda precisam de autorização do serviço; `ServerAuthorizer.require(ServerOperation)` é o contrato. A ÚNICA implementação em `src/main` é `ServerAuthorization.DENY_ALL` (nega tudo, inclusive operação nula; sem estado, sem configuração, sem variante permissiva). A composição de produção usa os construtores sem autorizador, que fixam `DENY_ALL`; os construtores com `ServerAuthorizer` explícito existem só para testes de domínio. O dublê (`FakeServerAuthorizer`) vive somente em `src/test` e nunca é selecionável por env/propriedade/configuração (guardas em `ServerAuthorizationBoundaryTest` varrem fonte e `target/classes`). Sessão, papel ADMIN e elevação do painel são apresentação e não liberam nada. Wallet/payment/gas/treasury/jobs/settings continuam `SERVER_AUTHORIZATION_REQUIRED` até serem migrados para o serviço.

## `market_started` (esperado)
Registrado quando o primeiro painel assina `market.subscribe`. O feed é exclusivamente o público ETHUSDT USDⓈ-M (`Allowlist`: 2 WebSockets — depth@100ms; aggTrade/markPrice/ticker/kline_1m — e 2 GET REST — depth e klines); comparação exata de URI, sem cabeçalho de autenticação, sem chave, sem endpoint de conta/ordens/listenKey, sem importar auth/secrets/identity.
