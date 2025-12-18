# Endpoints consumidos pelo frontend IAOS

Lista dos endpoints conhecidos (ativos ou mockados), com tipo, payload e estabilidade desejada.

## REST Cosmos (ativos)
- `/cosmos/base/tendermint/v1beta1/node_info` — **tipo**: REST GET — **payload**: none — **status**: ativo — **estabilidade**: estável.
- `/cosmos/base/tendermint/v1beta1/blocks/latest` — **tipo**: REST GET — **payload**: none — **status**: ativo — **estabilidade**: estável.
- `/cosmos/bank/v1beta1/supply/{denom}` — **tipo**: REST GET — **payload**: denom path param — **status**: ativo — **estabilidade**: estável.
- `/cosmos/bank/v1beta1/balances/{address}` — **tipo**: REST GET — **payload**: address path param — **status**: ativo — **estabilidade**: estável.
- `/cosmos/auth/v1beta1/accounts/{address}` — **tipo**: REST GET — **payload**: address path param — **status**: ativo — **estabilidade**: estável.
- `/cosmos/tx/v1beta1/txs?events=...&pagination.limit=...` — **tipo**: REST GET — **payload**: query events, pagination — **status**: ativo (pode faltar em redes sem index) — **estabilidade**: pode mudar (depende de suporte do nó); preferir indexer no futuro.
- `/cosmos/tx/v1beta1/txs/{hash}` — **tipo**: REST GET — **payload**: hash path param — **status**: ativo — **estabilidade**: estável.
- `/cosmos/tx/v1beta1/txs` — **tipo**: REST POST — **payload**: `{ tx_bytes, mode }` — **status**: ativo — **estabilidade**: estável.

## Pagamentos módulo BYX (ativos mas sujeito a estabilização)
- `/byx/payments/v1/payment_requests?merchant_id={id}` — **tipo**: REST GET — **payload**: merchant_id query — **status**: ativo (variação) — **estabilidade**: pode mudar (precisa contrato único).
- `/byx/payments/v1/requests?merchant_id={id}` — **tipo**: REST GET — **payload**: merchant_id query — **status**: ativo (fallback) — **estabilidade**: pode mudar.
- `/byx/payments/v1/merchants/{id}/requests` — **tipo**: REST GET — **payload**: id path param — **status**: ativo (fallback) — **estabilidade**: pode mudar.

## RPC EVM (opcional/ativável)
- `{EVM_RPC_URL}` `eth_chainId` — **tipo**: JSON-RPC POST — **payload**: `{ method: "eth_chainId", params: [] }` — **status**: mock/condicional (só se env set) — **estabilidade**: estável (padrão Ethereum).
- `{EVM_RPC_URL}` `eth_blockNumber` — **tipo**: JSON-RPC POST — **payload**: `{ method: "eth_blockNumber", params: [] }` — **status**: mock/condicional — **estabilidade**: estável.

## HTTP genérico (mock)
- `POST /auth/login|register` — **tipo**: REST POST — **payload**: `{ email, password, type }` — **status**: mock (não implementado; só referência em código) — **estabilidade**: futuro backend.

### Observações
- Endpoints marcados como “pode mudar” devem ganhar contrato único e versão antes de serem considerados estáveis.
- Frontend deve sempre validar HTTP status e não depender de estruturas internas da chain além do schema público descrito.
