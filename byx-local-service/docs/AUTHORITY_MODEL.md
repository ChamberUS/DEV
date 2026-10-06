# V2.1F — Autoridade de autenticação e sessão (fundação)

> **V2.1G:** a autoridade deixou de ser só QA: o serviço do produto a monta com o perfil de PRODUÇÃO e o painel autentica exclusivamente por ela (migração dos usuários/segredos reais preparada, cutover manual pendente). Plano, trava de migração, rollback e limites: ver `mvp-binance-panel/docs/AUTHORITY_CUTOVER.md`. O parágrafo abaixo descreve a fase V2.1F (histórico).

**Escopo desta fase.** Esta fase NÃO migra usuários reais, NÃO lê os três segredos reais, NÃO habilita Binance Account, NÃO habilita notificações privadas e NÃO abre capacidades privadas. O app normal continua no fluxo de login atual (painel). A nova autoridade vive no serviço, isolada num harness de teste / modo de QA empacotado (`AuthQaMain`, só no bundle de TESTE); não existe flag de produção que a ligue (`AuthIsolationTest`). `PRIVATE_CAPABILITIES_ALLOWED` continua `false`.

## 1. Auditoria da autenticação atual (painel; somente leitura)
| Estado | Onde vive hoje | Observação |
|---|---|---|
| **Credencial** | tabela `users` do `panel.db` (hash Argon2id via BouncyCastle, m=19456 t=2 p=1; `PasswordHasher`) | o banco é `0644` no home real; o painel é quem verifica |
| **Autorização** | `users.role` + `SessionManager` (usuário corrente em memória) | o painel decide papel e admin; nenhuma prova ao serviço |
| **Sessão** | `SessionManager`: um único usuário corrente em memória; **sem token, sem expiração para usuário comum** (a sessão admin tem prazo de `security.properties`) | nada liga a sessão a um processo |
| **Segundo fator** | `AdminAccessService` + `OtpService` (HMAC por desafio, 5 min, 5 tentativas, cooldown 30 s) + `TrustedDeviceService` (`trusted_devices`, hash SHA-256, token legado no Keychain, 30 dias, exige 2FA fresco ≤ 300 s) | **fraqueza:** reenviar cria um desafio novo que zera tentativas e renova a validade |
| **Limite de tentativas** | `PersistentRateLimiter` (L4, tabela `rate_limits`, assunto = HMAC do texto digitado) | já correto; portado para o serviço |
| **Auditoria** | tabela `audit_log` | mantida no painel; o serviço tem seu próprio anel de eventos fixos |
O painel é, hoje, a autoridade. Nada disso foi alterado, migrado ou lido (nem `panel.db`, nem hashes, nem Keychain real).

## 2. Desenho do armazenamento da autoridade (V2.1F-1: snapshot CIFRADO)
**Auditoria (V2.1F-1):** na V2.1F o arquivo era `{estado em JSON claro + HMAC}` — íntegro, mas **não confidencial** (usuários, papéis e verificadores de senha legíveis por qualquer processo do mesmo usuário; `0600` não protege contra o mesmo usuário). Corrigido antes de qualquer migração real.

**AEAD (JCA, `AES/GCM/NoPadding`, etiqueta de 128 bits):** o snapshot é o encoding canônico existente, cifrado e autenticado. Nenhuma cifra, padding, KDF ou protocolo próprios.
- **Chave:** 256 bits, `SecureRandom`, **INDEPENDENTE** da chave MAC (nunca derivada dela), guardada só no keychain de proteção de dados do serviço (`SecretId.AUTHORITY_TEST_ENCRYPTION_KEY`, via `EncryptionKeyVault`/`SecretStoreKeyVault`). Nunca em env, propriedade, configuração, argv, arquivo ou log; o painel nunca a recebe.
- **Formato** (versionado, limitado): `magic "BYXA"(4) | formatVersion=2 (2) | authorityVersion (8) | nonce (12) | ciphertext+tag`. O cabeçalho inteiro (magic, versão de formato, versão da autoridade) + um rótulo de domínio formam o **AAD**: trocar qualquer um invalida a etiqueta. Limites (mín. / máx. 8 MiB) são aplicados **antes** de ler/alocar/decifrar. Sem serialização de objetos Java.
- **Nonce:** 96 bits novos de `SecureRandom` **a cada gravação** (nunca derivado de tempo); com chave fixa e poucas gravações (≪ 2³²) a colisão é desprezível. Testado: 24 gravações consecutivas, todos os nonces e textos cifrados distintos.
- **Rollback continua indispensável:** o AEAD dá confidencialidade e integridade, **não** rollback (um snapshot antigo é autêntico). A âncora monotônica no keychain (versão + MAC da cabeça, com a chave MAC) foi preservada; `v < âncora` ⇒ `rollback`.
- **Queda:** escreve o snapshot novo (atômico, `fsync`) → atualiza a âncora. Arquivo exatamente uma versão à frente é aceito e a âncora reparada **somente depois** de: autenticação AEAD OK + esquema válido + cabeçalho == texto claro + relação de versão (v == âncora+1). Futuro arbitrário (outra chave) ⇒ `decrypt_failed`; v > âncora+1 ⇒ `version_ahead`. Chave órfã de uma inicialização interrompida é substituída por `initialize()`.
- **Memória:** os buffers mutáveis controlados (texto claro canônico, cópia da chave) são zerados após serializar/cifrar/decifrar/verificar; **sem garantia de zeroização na JVM** (Strings e objetos do esquema — nomes de usuário, hashes — não são zeráveis) e nada disso é logado.
- Fixos: códigos de falha (`decrypt_failed`, `format_invalid`, `enckey_missing`, `enckey_unavailable`, `rollback`, …); exceções sem causa, sem pilha e sem conteúdo.

Âncora (72 bytes: chave MAC | versão monotônica | MAC da cabeça) continua no cofre do serviço. Sem inicialização automática e sem admin padrão: `initialize()` é explícito; arquivo, âncora ou chave ausentes ⇒ `UNTRUSTED`/`UNINITIALIZED`, nunca recriados em silêncio. Estado não confiável é **pegajoso** (só um reinício reconfia). `credentialVersion` sobe a cada mudança de segurança.

## 3. Modelo de adulteração / rollback (testado)
| Ataque | Detecção | Teste |
|---|---|---|
| editar bit do texto cifrado / etiqueta / nonce / cabeçalho (versão no AAD) / truncar | autenticação AEAD falha (`decrypt_failed`) ou `format_invalid` | unit + empacotado |
| editar papel / inserir admin / trocar hash / reativar conta / mudar `credentialVersion` | impossível sem a chave AEAD (e nada é legível); qualquer edição cega quebra a etiqueta | unit (snapshot forjado com outra chave ⇒ `decrypt_failed`) |
| chave AEAD errada / ausente / cofre indisponível | `decrypt_failed` / `enckey_missing` / `enckey_unavailable` | unit |
| rollback a snapshot antigo (também ao vivo) | versão do arquivo < âncora (`rollback`) | unit + empacotado (âncora no keychain REAL) |
| arquivo apagado / âncora apagada | `file_missing` / `anchor_missing` | unit + empacotado |
| lixo, truncado, symlink, campo extra, versão muito à frente | `format_invalid` / `file_not_regular` / `version_ahead` | unit |
**Ameaça medida que muda o desenho:** com o lançador genérico do jpackage, `JAVA_TOOL_OPTIONS=-Xbootclasspath/a:evil.jar` executou código alheio **dentro** do helper do serviço (identidade e keychain do serviço) — a chave da âncora só é "só do serviço" se esse vetor estiver fechado. `-javaagent` falha (o runtime não tem `java.instrument`) e agente nativo exige biblioteca assinada. Mitigação implementada: **lançador nativo endurecido** (`byx-packaging/launcher/byx-launcher.c`), executável principal do helper: limpa o ambiente (prefixos `JAVA`, `_JAVA`, `JDK_`, `CLASSPATH`, `DYLD_`, `LD_`, `MALLOC`), usa argumentos/classpath/classe **constantes** (o `.cfg` mutável e o argv são ignorados) e valida o selo do próprio bundle (aninhado + estrito) antes de iniciar a JVM (exit 71 se quebrado). Limites: TOCTOU entre a validação e a leitura do jar (instale onde o usuário comum não grava, ex. `/Applications`); **V2.1F-1:** o PAINEL também usa o mesmo lançador (um só código, parametrizado em build-time: classe principal, jars na ordem do `.cfg`, `PASS_ARGS` — argv entra só DEPOIS da classe principal, como argumento da aplicação —, e `SQLITE_NATIVE`), além da triagem do ambiente do peer da V2.1D no serviço (defesa em profundidade). Testes empacotados: `JAVA_TOOL_OPTIONS`/`_JAVA_OPTIONS`/`JDK_JAVA_OPTIONS` e `DYLD_INSERT_LIBRARIES` hostis não executam no painel (com controle positivo); jar ou `.cfg` alterado ⇒ exit 71.

## 4. Verificador de senha
Argon2id (BouncyCastle, PHC; padrão m=19456 t=2 p=1, igual ao painel auditado), parâmetros do hash armazenado limitados (m ≤ 256 MiB, t ≤ 10, p ≤ 4) para que um registro adulterado não vire DoS, comparação em tempo constante. **Usuário conhecido, desconhecido e desabilitado são externamente equivalentes:** o mesmo caminho faz exatamente **uma** derivação Argon2 (verificador *dummy* para desconhecido/desabilitado), mesma resposta `INVALID_CREDENTIALS` (testado por contagem de derivações e por igualdade byte a byte da resposta no fio). Limitador persistente L4 portado (`AuthRateLimiter`): 4 falhas livres, 30 s / 60 s / 2 min… teto 5 min, decaimento 30 min, ≤ 5000 linhas, assunto = HMAC (nunca o texto digitado), arquivo com MAC; adulteração = falha fechada pelo período de decaimento; relógio que volta é limitado.

## 5. Contrato IPC de autenticação (tipado)
`auth.password{username,password}`, `auth.beginSecondFactor{session}`, `auth.verifySecondFactor{session,challenge,code}`, `auth.sessionStatus{session}`, `auth.logout{session}`, `auth.adminElevation{session}`, `auth.changePassword{session,current,next}`. Cada op tem conjunto **fechado** de campos (um a mais = `bad_request`), tipos e regex validados (token 43 chars base64url, desafio 22, código 6 dígitos, usuário `[A-Za-z0-9._-]{1,64}`, senha ≤ 256), quadro ≤ 8 KiB, chaves duplicadas recusadas. **A UI nunca envia papel, userId, admin, mfa nem estado de sessão** (campos extras são recusados; `AuthorityClient` não tem como enviá-los). **Não existem** `auth.execute/querySql/setRole/setMfa/impersonate/override/debugLogin` (teste de fio + guarda de fonte). Códigos de erro fixos; nada de mensagem de exceção, usuário digitado, senha ou token em respostas ou logs. O serviço só expõe `auth.*` quando montado pelo QA (`features.authentication`); o produto responde `unsupported_operation`.

## 6. Token de sessão
256 bits (`SecureRandom`), base64url (43 chars), opaco. O serviço guarda só o **SHA-256**; nunca o token cru em disco/log/diagnóstico; `Session.toString` redigido; sessões só em memória. O painel (`AuthorityClient`) guarda o token só em memória (campo privado), e ele nunca vai a argv, ambiente, arquivo, diagnóstico, notificação ou log. (O CLI de QA aceita o token por stdin só para simular roubo.)

## 7. Ligação ao peer
A sessão é ligada à **chave do peer** dada pelo kernel (`pid<<32 | pidversion` do token de auditoria `LOCAL_PEERTOKEN`; nada vem do cliente; um pid reaproveitado tem outra pidversion). Token de outro processo ⇒ `AUTH_REQUIRED` (e não derruba a sessão do dono). Além disso o modo empacotado já recusa, antes de ler qualquer byte, qualquer peer que não seja o app verificado (V2.1D): Python/Java genéricos que leem o `pairing.token` são fechados no handshake. Novo run do app exige novo login; não há login persistente.

## 8. Tempos de vida (centralizados em `AuthLimits`)
| | valor |
|---|---|
| sessão absoluta | 8 h |
| inatividade | 15 min |
| elevação de admin | 5 min |
| segundo fator "recente" | 10 min |
| OTP: validade / tentativas / envios / cooldown | 5 min / 5 / 3 / 30 s |
| sessões simultâneas | 64 |
Reinício do serviço invalida **todas** as sessões (mapa em memória) → `AUTH_REQUIRED`; nada reconstrói sessão automaticamente.

## 9. Política de autorização (tabela fechada, negar por padrão)
Regra = (papel mínimo, MFA recente, elevação, posse, capacidade privada). Operação desconhecida/nula ⇒ negada. `account.read`, `notifications.read`, `admin.operation` e `qa.privateOp` exigem a capacidade privada e portanto são **negadas para todos** enquanto o gate estiver fechado (testado inclusive para ADMIN + MFA + elevado + dono). Elevação de admin é propriedade **temporária da sessão**, não papel.

## 10. Revalidação antes de operação protegida
A cada uso: autoridade confiável → sessão (token, peer, prazos) → conta existe → habilitada → `credentialVersion` igual → papel ATUAL (rebaixado perde elevação) → MFA recente → elevação → posse → gate privado. `guarded(...)` autoriza antes **e depois** de executar e descarta o resultado se a sessão foi revogada no meio (corrida logout/rebaixamento: sem ressurreição de privilégio). Testado: ADMIN→USER, desabilitar (e reabilitar não ressuscita), apagar, trocar senha, mudar versão de credencial, autoridade adulterada (revoga tudo).

## 11. Troca de senha (somente usuários de teste)
Exige sessão válida e prova da senha atual (limitador próprio). Sobe `credentialVersion`; **a sessão comum corrente permanece** (decisão explícita: o usuário acabou de provar a credencial; ela perde MFA e elevação), **as outras sessões são revogadas** e os desafios OTP da conta são cancelados. Senha fraca (< 12) recusada.

## 12. Segundo fator
`SecondFactorProvider` (`configured()`, `deliver(accountId, code)`). Produção: `NotConfiguredSecondFactor` ⇒ `SECOND_FACTOR_NOT_CONFIGURED`. Nenhuma chamada real a Resend/Twilio e nenhum provedor de OTP de desenvolvimento no serviço (guarda de fonte). Dublês só em testes (`FakeSecondFactor`) e, no bundle de QA, um provedor que escreve o código num arquivo `0600` do diretório de QA (nunca IPC, log ou saída).
**Contrato OTP:** 6 dígitos; só um HMAC é guardado; uso único; validade **absoluta** (reenviar NÃO renova nem zera tentativas — corrige a fraqueza do painel); 5 tentativas; 3 envios; cooldown 30 s; ligado a sessão e conta (desafio de outra conta/sessão ⇒ `CHALLENGE_INVALID`); o código nunca é devolvido por IPC.

## 13. Elevação de admin
Sessão válida + papel ADMIN **atual** + segundo fator recente; prazo próprio de 5 min; rebaixar, desabilitar ou mudar credencial revoga; a UI não tem como declarar `adminElevated`.

## 14. Logout
Revoga no serviço; teste de corrida (operação em voo → logout → conclusão tardia): resultado descartado (`AUTH_REQUIRED`), evento `LATE_RESULT_DISCARDED`.

## 15. Dispositivo confiável (modelo futuro; nada implementado/alterado)
Registro **revogável e rotacionável**, ligado a conta + dispositivo (chave gerada no dispositivo/app assinado, não hostname/MAC), guardado **pelo serviço** (hash do segredo, expiração, último uso), nunca um booleano permanente; só reduz o segundo fator, nunca o primeiro; revogado em troca de senha, rebaixamento, desabilitação e a pedido; o dispositivo atual (token legado no Keychain real) não é tocado.

## 16. Limites declarados
- A chave da âncora só protege contra o mesmo usuário se o código do serviço for o do bundle: depende do lançador endurecido + selo; TOCTOU e instalação gravável pelo usuário enfraquecem (use `/Applications`).
- Perfil de provisionamento de desenvolvimento expira em 7 dias (2026-10-13); reprovisionar com `byx-packaging/provisioning/provision.sh`.
- Mutações legítimas ao vivo (rebaixar, desabilitar) são provadas em teste de unidade; o QA empacotado cobre adulteração, rollback, peer, reinício, injeção e selo.
- O painel normal NÃO usa esta autoridade; migrar usuários, hashes e dispositivo confiável é fase futura, com revisão explícita.
