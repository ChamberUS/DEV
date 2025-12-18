# Contratos de interface BYX ↔ IAOS Frontend

Objetivo: documentar as interfaces estáveis que o frontend IAOS pode consumir da blockchain BYX sem depender da implementação Go ou de módulos internos.

## Status da rede
- **Endpoint**: `GET /cosmos/base/tendermint/v1beta1/node_info` (fallback: `/node_info`)
- **Resposta esperada (resumo)**:
  ```json
  {
    "default_node_info": {
      "network": "byx_1",
      "moniker": "node-1"
    },
    "application_version": {
      "name": "byx",
      "version": "x.y.z"
    }
  }
  ```

## Bloco mais recente
- **Endpoint**: `GET /cosmos/base/tendermint/v1beta1/blocks/latest` (fallback: `/blocks/latest`)
- **Resposta esperada (resumo)**:
  ```json
  {
    "block": {
      "header": {
        "height": "12345",
        "time": "2024-01-01T00:00:00Z"
      },
      "data": { "txs": [] }
    }
  }
  ```

## Saldo de conta
- **Endpoint**: `GET /cosmos/bank/v1beta1/balances/{address}`
- **Resposta esperada (resumo)**:
  ```json
  {
    "balances": [
      { "denom": "ubyx", "amount": "1230000" }
    ]
  }
  ```

## Suprimento do denom
- **Endpoint**: `GET /cosmos/bank/v1beta1/supply/{denom}`
- **Resposta esperada (resumo)**:
  ```json
  { "amount": { "denom": "ubyx", "amount": "210000000000000" } }
  ```

## Conta (accountNumber / sequence)
- **Endpoint**: `GET /cosmos/auth/v1beta1/accounts/{address}`
- **Resposta esperada (resumo)**:
  ```json
  {
    "account": {
      "base_account": {
        "account_number": "123",
        "sequence": "7"
      }
    }
  }
  ```

## Histórico de transações
- **Endpoint principal**: `GET /cosmos/tx/v1beta1/txs?events=...&pagination.limit=...`
- **Endpoint por hash**: `GET /cosmos/tx/v1beta1/txs/{hash}`
- **Resposta esperada (resumo)**:
  ```json
  {
    "tx_responses": [
      {
        "txhash": "ABC123",
        "code": 0,
        "tx": { "body": { "memo": "aios:pr_..." } },
        "logs": [ { "events": [ { "type": "transfer", "attributes": [] } ] } ]
      }
    ]
  }
  ```
  - Em `txs?events=`, o frontend assume atributos de evento `transfer.recipient` e `transfer.amount`.

## Broadcast de transação
- **Endpoint**: `POST /cosmos/tx/v1beta1/txs`
- **Payload esperado**:
  ```json
  { "tx_bytes": "<base64>", "mode": "BROADCAST_MODE_SYNC|BLOCK|ASYNC" }
  ```
- **Resposta esperada (resumo)**:
  ```json
  { "tx_response": { "txhash": "ABC123", "code": 0, "raw_log": "" } }
  ```

## Pagamentos (módulo BYX)
- **Endpoints tentados** (aceitar pelo menos um):
  - `GET /byx/payments/v1/payment_requests?merchant_id={id}`
  - `GET /byx/payments/v1/requests?merchant_id={id}`
  - `GET /byx/payments/v1/merchants/{id}/requests`
- **Resposta esperada (resumo)**:
  ```json
  {
    "requests": [
      {
        "id": "pr_123",
        "merchant_id": "store_1",
        "owner_email": "user@example.com",
        "amount": "1000000",
        "denom": "ubyx",
        "memo": "aios:pr_123",
        "status": "pending|paid|expired",
        "created_at": "2024-01-01T00:00:00Z",
        "expires_at": "2024-01-01T01:00:00Z",
        "paid_tx_hash": "ABC123"
      }
    ]
  }
  ```

## RPC EVM (quando habilitado)
- **Endpoint**: `POST {VITE_EVM_RPC_URL}` com JSON-RPC 2.0.
- **Métodos usados**: `eth_chainId`, `eth_blockNumber`.
- **Resposta esperada (resumo)**:
  ```json
  { "jsonrpc": "2.0", "id": 1, "result": "0x1" }
  ```

## Governança (somente leitura)
- **Estado atual**: não consumido pelo frontend.
- **Sugestão de contrato futuro**: `GET /cosmos/gov/v1beta1/proposals` e `GET /cosmos/gov/v1beta1/params/*` retornando arrays/objetos JSON padrão do Cosmos SDK.
