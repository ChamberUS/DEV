# Arquitetura geral (IAOS ↔ BYX)

```
IAOS Frontend
     |
     v
API / RPC / REST (interfaces)
     |
     v
BYX Blockchain
     |
     v
Nós / Validadores
```

## Responsabilidades
- **IAOS Frontend**: experiência de usuário, composição de dados e ações via contratos públicos. Não conhece módulos internos nem structs Go.
- **API / RPC / REST**: superfície estável e versionada (Cosmos REST, JSON-RPC EVM, endpoints BYX). Único contrato que o frontend pode assumir.
- **BYX Blockchain**: consenso, execução de transações, validação de mensagens. Evolui internamente sem expor detalhes ao frontend.
- **Nós / Validadores**: operam a rede, aplicam upgrades e mantêm disponibilidade.

## O que muda com upgrades
- Implementação interna da chain, módulos Go, layout de armazenamento, versões do Cosmos/EVM.
- Perfis de gas, limites, mensagens suportadas — desde que expostos em contratos versionados.

## O que NÃO muda
- Contratos de interface documentados (REST/RPC e schemas JSON publicados).
- Convenções de autenticação/endereçamento fornecidas via config (chain-id, denom, prefixos).
- Separação entre frontend e chain: nenhuma dependência direta de código interno.
