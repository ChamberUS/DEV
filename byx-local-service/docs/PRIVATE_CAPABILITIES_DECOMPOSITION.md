# V2.1K — Decomposição do gate privado e prontidão (tudo DESLIGADO)

Estado do artefato: `PRIVATE_CAPABILITIES_ALLOWED=false` (mestre, constante de compilação), `EXPLICIT_REVIEW_REQUIRED=true`. Nenhuma capacidade privada está habilitada, implementada nem configurada. Esta rodada define o CONTRATO e as provas de isolamento; não abre nada, não cria credencial, não envia notificação, não faz tráfego privado.

## 1. Inventário da superfície privada (antes)
| onde | o que existia |
|---|---|
| `PrivateCapabilityGate` | constante mestre + `allowed(String)` com nome livre: com o mestre aberto, QUALQUER texto não nulo seria permitido (defeito latente) |
| `Operations` (`capabilities`) | `notifications`, `accountData`, `adminOperations`, `secretIntegrations` vindos de `allowed(nome)`; `privateGate.{allowed,reviewRequired,unmetPrerequisites}` |
| `AuthPolicy` / `AuthService.decide` | regras `account.read`, `notifications.read`, `admin.operation` com UM booleano `privateCapability` para todas (sem isolamento entre capacidades) |
| `Migrator` (preflight do finalize) | exige gate fechado (usa o mestre) |
| `SecretId` | `BINANCE_READONLY_CREDENTIAL` já declarado como UNUSABLE; sem item real |
| rede | só mercado público (`Allowlist`: 4 URIs) e Resend/Twilio da AUTENTICAÇÃO (`HttpTransport.ALLOWED_HOSTS`). Nenhuma chamada Binance privada |
| painel | `LocalServiceClient` força as capacidades privadas a `false` mesmo que o serviço declare `true` (apresentação apenas); `ServiceProbe` imprime; notificações do painel são só UI local; wallet/payment/gas usam `ServerAuthorizer` (DENY_ALL) — outra superfície, fora deste gate |
| capacidades privadas ADICIONAIS | nenhuma além das quatro |

## 2. Modelo (agora)
`PrivateCapability` (enum fechado): NOTIFICATIONS, BINANCE_ACCOUNT_READ (nome de fio `accountData`), ADMIN_OPERATIONS, SECRET_INTEGRATIONS. `PrivateOperation` (enum fechado): operações tipadas, cada uma de UMA capacidade, nunca mais frouxa que o piso dela. `AuthPolicy.standard()` gera suas linhas privadas dessas operações (fonte única).

| capacidade | papel mín. | MFA recente | elevação | peer verificado | segredo | hosts | posse | status |
|---|---|---|---|---|---|---|---|---|
| NOTIFICATIONS | USER | não | não | sim | nenhum | nenhum (local) | sim | CONTRACT_ONLY |
| BINANCE_ACCOUNT_READ | USER | só configurar/remover | não | sim | `BINANCE_READONLY_CREDENTIAL` | fapi.binance.com, api.binance.com | sim | CONTRACT_ONLY |
| ADMIN_OPERATIONS | ADMIN | sim | sim | sim | nenhum | nenhum | não | NOT_IMPLEMENTED |
| SECRET_INTEGRATIONS | ADMIN | sim | sim | sim | por integração | por integração | n/a | NOT_IMPLEMENTED; **nunca exposta à UI** (nenhuma operação mapeia para ela) |

Resend/Twilio da AUTENTICAÇÃO não são capacidades privadas nem são reutilizados por notificações.

## 3. Semântica do gate
`allowed = mestre AND capacidade habilitada AND capacidade implementada AND peer verificado AND (segredo, se exigido) AND autorização da operação`. Padrão NEGA; capacidade/operação desconhecida NEGA (mesmo com o mestre aberto); mestre=false nega TODAS, qualquer que seja a configuração individual (tabela-verdade de 64 combinações em teste). Produção usa SEMPRE `Config.PRODUCTION` (mestre=constante `false`, nenhuma habilitada); nada de env/propriedade/arquivo/UI constrói outra (teste varre o `src/main`). Habilitar uma capacidade NUNCA habilita outra (teste de isolamento cruzado em todos os pares). Hoje `AuthService` passa `peerVerified=false`/`secretConfigured=false`: ligar esses fatos é parte da revisão que habilitar uma capacidade (falha fechado).

## 4. Autoridade no serviço
A decisão final é sempre do serviço (sessão opaca ligada ao peer, revalidada a cada uso). O painel só apresenta: flags forjados no painel (`notifications`, `accountData`, `admin`, `mfa`) não liberam nada — o cliente ignora flags privados declarados e o `ServerAuthorizer` do painel é DENY_ALL. Nenhuma operação privada está no IPC; o serviço responde `unsupported_operation`.

## 5. Notificações (prontidão)
A) notificação local de desktop: UI do painel, sem serviço nem rede; não é capacidade privada. B) notificação privada derivada de conta/atividade: a futura NOTIFICATIONS (contrato `notifications.status|subscribe|unsubscribe`; **sem `send` genérico**). C) e-mail/SMS de segurança (Resend/Twilio): infraestrutura da AUTENTICAÇÃO, separada e não reutilizável. D) push remoto: não desenhado (seria outra capacidade, com revisão). Nada implementado.

## 6. Binance Account READ-ONLY (prontidão, sem credencial)
Allowlist fechada (`BinanceReadEndpoint`, não usada por nenhum código de rede): todos GET, assinados (USER_DATA), hosts fixos.
| operação | método | host | caminho | peso | DTO |
|---|---|---|---|---|---|
| account.balances | GET | fapi.binance.com | /fapi/v3/balance | 5 | AccountBalance |
| account.positions | GET | fapi.binance.com | /fapi/v3/positionRisk | 5 | OpenPosition |
| account.status | GET | fapi.binance.com | /fapi/v3/account | 5 | AccountStatus (mínimo) |
| account.credential.configure (checagem) | GET | api.binance.com | /sapi/v1/account/apiRestrictions | 1 | CredentialPermissions (só decisão local) |
Fontes (documentação pública, conferir de novo com chave real): Futures Account Balance V3, Position Information V3, Account Information V3 (USDⓈ-M) e Get API Key Permission (Wallet). PROIBIDO e ausente: criar/cancelar ordem, alavancagem, margem, saque, transferência, chave de stream privado genérica, proxy REST genérico, qualquer URL vinda da UI.
Assinatura: HMAC-SHA256 SÓ no serviço; timestamp e recvWindow (≤ 5 s) gerados/validados no serviço; o painel envia operação tipada, nunca URL/query/assinatura. Resposta: DTO mínimo, sem passthrough; campo desconhecido não passa.

## 7. Modelo de credencial
O painel NUNCA recebe chave nem segredo. O serviço é o único consumidor, via `SecretId` tipado `BINANCE_READONLY_CREDENTIAL` (hoje UNUSABLE: nenhum item real pode ser criado nesta rodada); não reutiliza Resend, Twilio nem chaves da autoridade. Configurar/remover exige sessão válida + posse + segundo fator recente + ação explícita (sem elevação de admin: a credencial é do próprio usuário); ler exige sessão + posse. Credencial é write-only na UI: não existe operação de leitura.
Política da chave (`ReadOnlyCredentialPolicy`, pura): leitura ON; saque, transferência (interna/universal), margem/margem de portfólio, negociação à vista e opções OFF; **futuros**: a Binance usa "Enable Futures" também para negociar e **não está provado** que ler saldos/posições de USDⓈ-M funciona sem ela — `FUTURES_PERMISSION_UNRESOLVED=true`: a política RECUSA chave com futuros ligado por padrão. **BLOQUEADOR CANDIDATO:** se uma chave somente leitura real provar que a leitura de futuros EXIGE essa permissão, é decisão do dono (aceitar chave com futuros restringida por IP, ou não habilitar a leitura de futuros); nunca aceito em silêncio. Não se cria chave agora.

## 8. IPC (desenho)
Operações fechadas futuras: `account.status|balances|positions`, `account.credential.configure|remove`, `notifications.status|subscribe|unsubscribe`, `admin.operation`. Nenhuma está no protocolo; proibidas por teste: `http.request`, `binance.request`, `proxy`, `rawQuery`, `signedRequest`, `execute`, `dumpSecret`, `notification.send`, `credential.get`.

## 9. Taxa e erros (política, `AccountRequestPolicy`)
Uma requisição por vez, intervalo mínimo por operação, timeout, teto de resposta, 429/418 bloqueiam toda atividade (≥ 60 s ou o que o provedor mandar), 5xx até 3 tentativas com recuo exponencial limitado, 4xx nunca repete. Tudo é GET (idempotente): nenhuma operação com efeito colateral pode ser repetida.

## 10. Auditoria (`AccountAuditEvent`)
`ACCOUNT_READ_REQUESTED|OK|FAILED`, `CREDENTIAL_CONFIGURED|REJECTED_PERMISSIONS|REMOVED`, `NOTIFICATIONS_SUBSCRIBED|UNSUBSCRIBED`. Só tipo, id interno da conta (hash) e código fixo: nunca chave, assinatura, query assinada, saldos nem identificador externo.

## 11. Resposta `capabilities` (agora)
`privateGate.privateMasterAllowed` e, por capacidade (`notifications`, `accountData`, `adminOperations`, `secretIntegrations`), `{available, configured, allowed}` — só booleanos fixos, sem motivo sensível. `configured` ≠ `allowed`. Os booleanos antigos em `features.*` continuam.

## 12. Para habilitar uma capacidade no futuro (fora desta rodada)
Mudança de CÓDIGO revisada que: marque a capacidade IMPLEMENTED, a habilite em `Config.PRODUCTION`, abra o mestre, ligue `peerVerified`/`secretConfigured` em `AuthService`, adicione a operação ao IPC com campos estritos, prove cada item acima com credencial real (somente leitura) e exiba revisão explícita do dono.
