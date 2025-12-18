# Limite frontend × blockchain (IAOS ↔ BYX)

Objetivo: garantir que o frontend dependa apenas de contratos de interface (REST/RPC/dados) e nunca de detalhes internos da chain ou do código Go.

## O que o frontend PODE assumir
- Endpoints REST públicos documentados em `dev/contracts/blockchain_interfaces.md` e `dev/contracts/api_endpoints.md`.
- Formatos de resposta JSON estáveis (ex.: `balances[]`, `tx_responses[]`, `account.base_account.account_number`, `tx_response.txhash`).
- Disponibilidade do RPC/REST via URLs configuradas (`VITE_CHAIN_RPC_URL`, `VITE_CHAIN_REST_URL`) ou proxy do Vite.
- Que o denom e prefixo Bech32 são fornecidos via env/config e não embutidos na lógica.
- Que chamadas EVM RPC seguem JSON-RPC 2.0 padrão quando ativadas.

## O que o frontend NÃO PODE assumir
- Estrutura interna de módulos Go ou schemas Protobuf não expostos por REST/RPC versionados.
- Eventos/mensagens além dos atributos documentados; mudanças de módulo não versionadas podem quebrar buscas por eventos.
- Que a chain suporta recursos opcionais (ex.: `txs?events` ou index completo) sem verificar `supported`/códigos HTTP.
- Ordem/shape exato de logs, gas, metadados internos da chain.
- Acesso direto a storage, consenso ou detalhes de validador.

## Regras claras
- Frontend nunca importa código Go ou arquivos do repositório da chain.
- Frontend nunca assume estrutura interna de módulos; usa apenas endpoints documentados.
- Frontend depende apenas de respostas de API (REST/RPC/gRPC) ou dados off-chain declarados.
- Toda nova integração deve ser descrita primeiro nos contratos em `dev/contracts/`.

## ACOPLAMENTO IDENTIFICADO
- `iaos-web/src/apps/site_legacy/Pages/PayRequest.jsx:266-344` — Uso direto de CosmJS (`SigningStargateClient`, `MsgSend`, encode/base64) e manipulação de `accountNumber/sequence` + fallback RPC/REST. **Sugestão futura**: mover fluxo de assinatura/broadcast para um gateway/backend ou encapsular em SDK oficial versionado, expondo apenas um endpoint de “pagar pedido” para o frontend.
- `iaos-web/src/api/paymentsClient.ts:157-206` — Dependência direta de caminhos REST específicos do módulo BYX (`/byx/payments/v1/*`) sem contrato único/versionado. **Sugestão futura**: estabilizar um endpoint único (`/byx/payments/v1/requests`) e publicar schema; oferecer fallback via indexer.
- `iaos-web/src/api/paymentsClient.ts:231-257` — Busca de transações por eventos `transfer.recipient` e `transfer.amount` com memo contendo `aios:*`, assumindo formato de eventos do módulo bank. **Sugestão futura**: substituir por consulta em indexer dedicado (por endereço/txhash) com schema estável.
