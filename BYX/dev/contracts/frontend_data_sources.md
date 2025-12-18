# Fontes de dados do frontend IAOS

Classificação entre dados on-chain (via RPC/REST) e off-chain (mock/local/futuro indexer), indicando fonte atual e fonte futura desejada.

## On-chain (REST/RPC)
- **Saldos e supply**
  - Fonte atual: `GET /cosmos/bank/v1beta1/balances/{address}` e `/supply/{denom}`.
  - Fonte futura: manter REST estável; cache opcional via indexer.
- **Informações de conta**
  - Fonte atual: `GET /cosmos/auth/v1beta1/accounts/{address}`.
  - Fonte futura: REST estável; indexer pode espelhar accountNumber/sequence para UX rápida.
- **Transações**
  - Fonte atual: `GET /cosmos/tx/v1beta1/txs?events=...` e `/txs/{hash}`.
  - Fonte futura: indexer dedicado para busca por endereço/evento, mantendo REST como fallback.
- **Broadcast**
  - Fonte atual: `POST /cosmos/tx/v1beta1/txs` (bytes base64).
  - Fonte futura: manter REST; camadas de gateway podem validar/limitar.
- **Status da rede / bloco**
  - Fonte atual: `GET /cosmos/base/tendermint/v1beta1/node_info` e `/blocks/latest`.
  - Fonte futura: manter REST; indexer pode fornecer métricas agregadas.
- **Pagamentos módulo BYX**
  - Fonte atual: `GET /byx/payments/v1/...` (variações listadas).
  - Fonte futura: endpoint estável único, ou leitura via indexer.
- **EVM RPC (quando configurado)**
  - Fonte atual: JSON-RPC `eth_chainId`, `eth_blockNumber`.
  - Fonte futura: RPC oficial + provider redundante.

## Off-chain
- **Autenticação e sessão**
  - Fonte atual: mocks em memória/localStorage (`authClient.ts`, `mockUsers`).
  - Fonte futura: backend de identidade/Base44 (não criar agora).
- **Pagamentos locais (fallback)**
  - Fonte atual: localStorage (`aios_payment_requests`) com normalização de status.
  - Fonte futura: leitura primária via indexer ou REST estável; localStorage apenas cache.
- **Catálogo/lojas/produtos (legado)**
  - Fonte atual: mocks Base44 (`base44Client`) e estados locais em páginas legadas.
  - Fonte futura: backend/indexer específico; frontend continua consumindo via API HTTP.
- **Staking/trade/marketplace (legado)**
  - Fonte atual: dados mockados em memória/localStorage nos apps legados.
  - Fonte futura: módulos on-chain expostos via REST/indexer; frontend não deve acessar lógica interna da chain.
