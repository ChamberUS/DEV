# Testnet pública AIOS/BYX – Guia de operação (read-only + Keplr + Faucet)

Este documento descreve **como expor uma testnet pública** (RPC/REST/gRPC) e um **faucet** para testers, com recomendações básicas de segurança e um template de reverse proxy (Nginx).

> Importante: este guia **não** altera consenso nem gera `genesis.json` automaticamente. Ele assume que você já tem uma testnet inicializada (genesis/validators/peers).

---

## Portas e serviços

**Node (byxd) – padrão Cosmos SDK**
- **P2P**: `26656` (TCP) — necessário para peering com outros nós
- **RPC Tendermint**: `26657` (TCP/HTTP) — status/blocks/tx broadcast via RPC
- **REST (LCD / API)**: `1317` (TCP/HTTP) — endpoints `/cosmos/*`
- **gRPC**: `9090` (TCP) — gRPC nativo
- (Opcional) **gRPC-web**: geralmente `9091` (TCP) se você expor via proxy específico

**Faucet (serviço separado)**
- Sugestão: `8000` (TCP/HTTP) — endpoint HTTP simples para solicitar tokens

**Reverse proxy (Nginx)**
- `80`/`443` (HTTP/HTTPS) — expõe paths:
  - `/rpc/*` → `26657`
  - `/rest/*` → `1317`
  - `/faucet/*` → faucet (ex: `8000`)

---

## Checklist de firewall (mínimo)

Recomendação: expor **apenas** o Nginx publicamente e manter portas internas **fechadas** para a internet.

**Entrada (inbound)**
- Liberar `80/443` para o Nginx (público).
- Liberar `26656` **apenas** se este nó precisa aceitar peers externos (p2p).
  - Se você tiver topologia com seed/sentry, prefira expor p2p só em sentry.

**Bloquear publicamente**
- `26657`, `1317`, `9090`, `8000` diretamente na internet (deixe somente atrás do proxy).

**Saída (outbound)**
- DNS/HTTP/HTTPS conforme necessário para updates/monitoring.

---

## Endpoints úteis (exemplos)

Assumindo Nginx em `https://testnet.aios.example`:

**RPC**
- Status: `GET https://testnet.aios.example/rpc/status`
- Último bloco: `GET https://testnet.aios.example/rpc/block`

**REST**
- Node info: `GET https://testnet.aios.example/rest/cosmos/base/tendermint/v1beta1/node_info`
- Latest block: `GET https://testnet.aios.example/rest/cosmos/base/tendermint/v1beta1/blocks/latest`
- Balances: `GET https://testnet.aios.example/rest/cosmos/bank/v1beta1/balances/<address>`
- Tx by hash: `GET https://testnet.aios.example/rest/cosmos/tx/v1beta1/txs/<txhash>`
- Broadcast tx: `POST https://testnet.aios.example/rest/cosmos/tx/v1beta1/txs`

**cURL rápido**
```bash
curl -sS https://testnet.aios.example/rpc/status | head
curl -sS https://testnet.aios.example/rest/cosmos/base/tendermint/v1beta1/node_info | head
```

---

## Como adicionar a chain no Keplr (ChainInfo)

No Keplr, você pode sugerir a chain via `experimentalSuggestChain`. Exemplo de ChainInfo (ajuste os valores):

```js
await window.keplr.experimentalSuggestChain({
  chainId: "byx_1",
  chainName: "AIOS Testnet",
  rpc: "https://testnet.aios.example/rpc",
  rest: "https://testnet.aios.example/rest",
  bip44: { coinType: 118 },
  bech32Config: {
    bech32PrefixAccAddr: "byx",
    bech32PrefixAccPub: "byxpub",
    bech32PrefixValAddr: "byxvaloper",
    bech32PrefixValPub: "byxvaloperpub",
    bech32PrefixConsAddr: "byxvalcons",
    bech32PrefixConsPub: "byxvalconspub",
  },
  currencies: [{ coinDenom: "BYX", coinMinimalDenom: "ubyx", coinDecimals: 6 }],
  feeCurrencies: [{ coinDenom: "BYX", coinMinimalDenom: "ubyx", coinDecimals: 6 }],
  stakeCurrency: { coinDenom: "BYX", coinMinimalDenom: "ubyx", coinDecimals: 6 },
  gasPriceStep: { low: 0.01, average: 0.025, high: 0.04 },
  features: ["stargate", "ibc-transfer"],
});
```

No front AIOS Web, use:
- `VITE_CHAIN_ID`, `VITE_DENOM`, `VITE_BECH32_PREFIX`
- `VITE_USE_PROXY=true` para dev (Vite proxy) ou aponte URLs públicas em produção.

---

## Faucet: recomendações de segurança

Sem backend, o faucet é o componente mais sensível do ambiente de testes.

Recomendações:
- **Nunca** exponha mnemonics/chaves privadas em repositórios, logs ou `.env` públicos.
- Rode o faucet em máquina/contêiner separado, com:
  - conta dedicada (hot wallet) com saldo limitado
  - limites de saque por IP e por endereço
  - logs e alertas (erro, volume, abuso)
- Coloque o faucet **atrás do Nginx**, com **rate limit** e (se possível) captcha (mesmo que apenas recomendado).
- Se o faucet suportar, valide:
  - formato do endereço (prefixo bech32)
  - valor máximo por request
  - cooldown por endereço/IP

---

## Fluxo sugerido para testers

1) Abrir o site AIOS Web (dev ou público).
2) Conectar Keplr em `/wallet`.
3) Solicitar tokens no faucet.
4) Abrir um link `/pay/:id` e pagar com Keplr.
5) Verificar status em `/network` e (opcional) consultar `/rest/cosmos/tx/v1beta1/txs/<txhash>`.

