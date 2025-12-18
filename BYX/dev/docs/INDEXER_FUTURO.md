# Indexer futuro para IAOS

Nenhuma implementação agora — apenas desenho para preparar a separação entre frontend e blockchain.

## Por que será necessário
- Consultas por endereço/evento em `/cosmos/tx/v1beta1/txs` são opcionais em alguns nós e podem ser lentas.
- Pagamentos e históricos exigem filtros por múltiplos campos (merchant, memo `aios:*`, denom), inviáveis apenas com REST básico.
- UX precisa de paginação rápida e agregados (saldo disponível, últimas transações, status de pagamentos) sem sobrecarregar nós validadores.

## Dados que o indexer deve fornecer
- Histórico de transações por endereço, memo ou atributos (paginação, ordenação).
- Estado resumido de pagamentos (por merchantId, status, txhash associado).
- Métricas de rede (tempo de bloco, altura, disponibilidade) para dashboards.
- Mapas de denom/decimais e metadados de tokens.

## Por que é separado da blockchain
- Mantém nós validadores focados em consenso, sem queries pesadas.
- Permite versionar contratos de leitura sem depender do ciclo de release da chain.
- Facilita caches, replicação e rate limiting específicos para frontend/partners.

## Por que o frontend não deve falar direto com módulos internos
- Módulos podem evoluir sem aviso; contratos REST/RPC e schemas publicados são a superfície estável.
- Acesso direto a mensagens/structs Go quebra compatibilidade e fere a separação de camadas.
- O indexer atua como camada de estabilidade e observabilidade, expondo apenas dados consolidados/documentados.
