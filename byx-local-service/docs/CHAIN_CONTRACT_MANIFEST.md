# Contrato consumido do nó BYX (manifesto v1)

Referência de compatibilidade chain ⇄ app. Fonte: auditoria SOMENTE LEITURA do repo da chain (`proto/byx/*/v1/query.proto`, `x/*/module/module.go`, Cosmos SDK `x/bank`) em 2026-10-07, HEAD da chain `7893dc7`; formato conferido contra um nó QA descartável (chain id `byx`). Não há payloads reais copiados: as fixtures de teste são sintéticas na mesma forma.
Todas as rotas são **GET**, em REST (porta fixa do perfil `LOCAL_QA`), **uma única rota aprovada por operação**, sem fallback. O perfil padrão (`PRODUCTION_DISABLED`) não faz nenhuma requisição.

## Matriz da superfície de query (auditoria)

| módulo | query | rota REST | parâmetros | resposta | paginação | contrato |
|---|---|---|---|---|---|---|
| lojas | Merchant | `/byx/lojas/v1/merchant/{id}` | id uint64 | `merchant{id,nome,endereco,saldo,creator,operator_address,kyc_ref,document_hash,kyc_status}` | – | **estável** (usada) |
| lojas | MerchantAll | `/byx/lojas/v1/merchant` | `pagination.*` | `merchant[]`, `pagination{next_key,total}` | key/limit | **estável** (usada; limite sempre explícito: o padrão do SDK é 100) |
| lojas | Params | `/byx/lojas/v1/params` | – | faucet/cashback/limites | – | existe; **não integrada** (sem uso na tela; contém campos de faucet) |
| lojas | SalesByLoja / SalesSummary | `/byx/lojas/v1/sales_by_loja`, `/sales_summary` | loja_id, janela | vendas | key/limit | existe; **não integrada** (fora do escopo desta fase) |
| payments | PaymentRequest | `/byx/payments/v1/payment_requests/{id}` | id uint64 | `payment_request{id,loja_id,amount_ubyx,memo,status,created_at_unix,expires_at_unix,payer,paid_at_unix}` | – | **estável** (usada; o nó DERIVA `EXPIRED` pelo tempo do bloco: o app usa o status da Query, nunca recalcula) |
| payments | PaymentRequestsByLoja | `/byx/payments/v1/payment_requests/by_loja/{loja_id}` | loja_id, `pagination.*` | `payment_requests[]`, `pagination` | key/limit/reverse | **estável** (usada; mais recentes primeiro) |
| payments | Params | `/byx/payments/v1/params` | – | `params{default,min,max_expires_in_seconds}` | – | **estável** (usada) |
| payments | PaymentsQRCode | `/byx/payments/v1/payment_requests/{request_id}/qr` | – | payload de QR | – | existe; **não integrada** (gera payload de pagamento: fora do escopo read-only da tela) |
| certificados | Certificate | `/byx/certificados/v1/certificates/{id}` | id uint64 | `certificate{…,serial_hash,revoked,revoked_reason,created_at}` | – | **estável** (usada) |
| certificados | CertificatesByMerchant | `/byx/certificados/v1/merchants/{merchant_id}/certificates` | merchant_id, `pagination.*` | `certificates[]`, `pagination` | key/limit/reverse | **estável** (usada) |
| certificados | CertificatesByOwner / BySerial / Params | `/owners/{owner}/…`, `/serial/{hash}/…`, `/params` | – | – | key/limit | existem; **não integradas** (sem tela que as use) |
| feesplit | — | — | — | — | — | **NOT_EXPOSED**: sem Query service e `RegisterGRPCGatewayRoutes` vazio. Não se lê o store, não se inventa rota |
| bank (SDK) | Balance (by_denom) | `/cosmos/bank/v1beta1/balances/{address}/by_denom?denom=ubyx` | address bech32 `byx`, denom fixo | `balance{denom,amount}` | – | **estável** (padrão Cosmos SDK; usada) |
| bank / node | denom metadata, supply, RPC `/status` | ver `CHAIN_PUBLIC_READ.md` | – | – | – | estável (V2.1L/M) |

## Operações do IPC (lista FECHADA) e DTOs

`args` é o único campo extra do pedido; só `id` (decimal `[1-9][0-9]{0,17}`), `address` (bech32 `byx`, checksum, 20/32 bytes), `limit` (1..10, padrão 5) e `cursor` opaco tipado (`v1.<tag>.<base64url ≤64 bytes>`, preso à operação). Qualquer outro campo = `INVALID_REQUEST` local, sem rede.

| operação | rota | campos do DTO (somente estes) | máx. resposta do nó | TTL cache |
|---|---|---|---|---|
| `byx.lojas.getMerchant` | Merchant | id, name, address, creator, operator, kycStatus (kyc_ref, document_hash e saldo NÃO são lidos) | 8 KiB | 30 s |
| `byx.lojas.listMerchants` | MerchantAll | items[] como acima, nextCursor | 24 KiB | 15 s |
| `byx.payments.getPayment` | PaymentRequest | id, storeId, amountUbyx, amountDisplay, memo, status(PENDING/PAID/EXPIRED/CANCELED), createdAtUnix, expiresAtUnix, paidAtUnix?, payer? | 4 KiB | 5 s |
| `byx.payments.listByStore` | PaymentRequestsByLoja | items[] de pagamento, nextCursor | 24 KiB | 5 s |
| `byx.payments.params` | Params | defaultExpiresInSeconds, minExpiresInSeconds, maxExpiresInSeconds | 2 KiB | 5 min |
| `byx.certificados.getCertificate` | Certificate | id, merchantId, issuer, owner, category, brand, model, serialHash(hex64 obrigatório), condition, notes, imageSha256, revoked, revokedReason, createdAtMs | 8 KiB | 30 s |
| `byx.certificados.listByMerchant` | CertificatesByMerchant | items[], nextCursor | 24 KiB | 15 s |
| `byx.bank.balance` | Balance | address, denom, amountUbyx, amountDisplay | 2 KiB | 5 s |
| `byx.feesplit.params` | (nenhuma) | status NOT_EXPOSED, source `DOCUMENTED_DEFAULT_NOT_QUERIED`, allocationBps{distribution 6000, treasury 3000, burn 1000} | – | – |
| `byx.moduleHealth` | (local) | modules[]{module,state,ok,failed,lastLatencyMs,lastSuccessAgeMs,lastFailure}, reads{fetches,cacheHits,coalesced,rateLimited} | – | – |

Justificativa dos TTLs: estado mutável de pagamento muda em segundos (5 s); registros de loja/certificado mudam raramente (30 s); listas 15 s (ou 5 s se contêm pagamentos); params de pagamento só mudam por governança (5 min); não encontrado 3 s (evita martelar o nó); dado antigo só é servido como `STALE` por no máximo 10 min e só enquanto o nó não responde. Sem número medido: são limites conservadores revisáveis.

## Mapeamento de erros (enum fechado `ReadFailure`)

| situação | resultado |
|---|---|
| perfil sem nó | `NOT_CONFIGURED` (zero rede, zero thread) |
| conexão recusada / timeout (1 s conectar, 2 s requisição) | `UNREACHABLE` / `TIMEOUT` (serve `STALE` se houver dado da mesma geração e chain id) |
| chain id ou denom diferente | `NETWORK_MISMATCH` / `DENOM_MISMATCH` (domina, purga o cache) |
| nó sincronizando ou bloco velho | `STALE_CHAIN` |
| HTTP 404 com corpo gRPC code 5 e mensagem `<registro> not found` | `NOT_FOUND` (a chain segue LIVE) |
| outro 404 / 405 / 501 | `UNSUPPORTED_QUERY` (rota ausente ou alterada) |
| HTTP 400 | `INVALID_REQUEST` |
| HTTP 429 | `RATE_LIMITED` |
| outros HTTP não-200 | `MODULE_UNAVAILABLE` |
| 3xx | `MALFORMED_RESPONSE` (redirecionamento recusado) |
| corpo acima do teto da rota | `RESPONSE_TOO_LARGE` |
| JSON inválido, campo ausente, tipo errado, enum desconhecido, valor negativo/estourado, texto com controle ou bidi, bech32 inválido, serial vazio, item de outro dono, mais itens que o pedido, `next_key` malformada | `MALFORMED_RESPONSE` (nenhum dado parcial) |
| mais de 2 leituras simultâneas + fila 8 / mais de 40 buscas por 10 s | `RATE_LIMITED` |

## O que exigiria atualizar o conector (prontidão de versão)

Não há como identificar a versão de cada módulo sem endpoint genérico; a compatibilidade é garantida por fixtures de deriva (campo removido, tipo alterado, enum novo, rota 404 genérica, envelope diferente, denom diferente → falha fechada). Mudanças futuras que exigiriam atualização: renomear/mover rota REST; mudar tipo de `id`/`amount_ubyx`/`*_unix`; novo valor de `PaymentStatus`; remover `serial_hash` ou torná-lo opcional; mudar o prefixo bech32; expor feesplit via Query (hoje NOT_EXPOSED: passaria a ser lida do nó em vez do padrão documentado); mudar o formato de `pagination.next_key`; TX V2 (`CreateMerchantV2`) — não faz parte do conector de leitura.
