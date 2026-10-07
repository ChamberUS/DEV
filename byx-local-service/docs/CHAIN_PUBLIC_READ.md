# V2.1L/V2.1M — Conector PÚBLICO e SOMENTE LEITURA da chain BYX local

Estado: **NOT_CONFIGURED em produção**. Nenhum nó real foi contatado; o contrato definitivo da chain ainda está sendo fechado. Tudo foi provado contra um nó FALSO em 127.0.0.1 (porta efêmera), em teste. Nada de transação, assinatura, carteira, mnemônico, chave privada, grant de gás, mutação de pagamento/loja/governança, nem proxy genérico.

## Arquitetura
`Painel → IPC verificado (peer assinado + pareamento) → byx-local-service → nó BYX público (loopback)`. O painel NÃO fala com RPC/REST/gRPC/WebSocket da chain, não conhece host, porta, chain ID esperado nem denom: o serviço é dono do endpoint, das rotas e do parsing. A leitura pública da chain é uma capacidade PÚBLICA separada do gate privado (não depende dele e não o afeta; teste de isolamento nos dois sentidos).

## Operações tipadas (`ChainOperation`, enum fechado; todas GET, sem argumentos)
| operação | IPC | rota (fixa) | DTO |
|---|---|---|---|
| CHAIN_STATUS | `byx.status` | RPC `/status` | status mínimo |
| CHAIN_LATEST_HEIGHT / CHAIN_NETWORK_INFO | (servidas dentro do status) | RPC `/status` | campos do status |
| CHAIN_DENOM_METADATA | `byx.denomMetadata` | REST `/cosmos/bank/v1beta1/denoms_metadata/{base}` | base, display, expoente |
| CHAIN_SUPPLY | `byx.supply` | REST `/cosmos/bank/v1beta1/supply/by_denom?denom={base}` | unidades-base + texto exato |
Queries de módulos (lojas, payments, feesplit, certificados): **fora desta rodada** (sem contrato estável). Proibido e ausente: `http.request`, `rpc.call`, `grpc.call`, `chain.queryRaw`, `fetchUrl`, `proxy`, `execute`, e qualquer envio/transmissão/assinatura/carteira.

## Modelo de endpoint e segurança de localhost
`ChainConfig` tipada: RPC e REST (gRPC e WebSocket só quando o contrato fechar), `expectedChainId`, `DenomModel`. Produção: `ChainConfig.production()` = vazio = **NOT_CONFIGURED** (nenhuma thread, nenhuma rede). `ChainEndpoint` só aceita `http://127.0.0.1:porta` ou `http://[::1]:porta` (porta ≥ 1024, sem usuário/caminho/consulta/fragmento): recusa 0.0.0.0, IP de LAN/público, nome de host (sem DNS), https, file://, unix, caminhos. O host nunca vem do painel, de ambiente, de propriedade nem de arquivo do usuário; a futura origem aprovada virá de configuração EMPACOTADA revisada. Transporte: sem redirecionamento (nunca seguido), sem proxy, prazo (conexão 1 s, requisição 2 s), leitura LIMITADA de corpo (16 KiB `/status` e metadata, 8 KiB supply).

## Chain ID e denom
`expectedChainId` é configuração tipada (testes: `byx`; valor final a confirmar quando o Codex retornar READY_FOR_APP_INTEGRATION; nada cristalizado). Divergência ⇒ `NETWORK_MISMATCH` (log `chain_network_mismatch`), a altura da rede errada NÃO é exposta e nada é mostrado como saudável. Denom: `DenomModel(base, display, exponente)` (`ubyx`, `BYX`, 6 nos testes); metadata do nó precisa bater (base com expoente 0, display com o expoente esperado), senão `DENOM_MISMATCH` ⇒ NETWORK_MISMATCH. Conversão monetária SÓ com BigInteger/texto decimal exato (nunca double/float; o pacote `chain` não contém `double`/`float`, por teste).

## Estado (`ChainStatus`) e regras (falha fechada)
Campos: `state, configured, reachable, chainId, latestHeight, catchingUp, blockTimeMs, networkMatch, reason, generation, updatedAtMs`. Estados: NOT_CONFIGURED, CONNECTING, OFFLINE, SYNCING, LIVE, STALE, NETWORK_MISMATCH, ERROR. **Só LIVE é saudável.** catching_up ⇒ SYNCING; bloco velho (>60 s) ⇒ STALE; bloco no futuro (>30 s) ⇒ ERROR; resposta inválida/grande ⇒ ERROR (razão tipada); inalcançável/prazo/HTTP≠200/redirect ⇒ OFFLINE. **Monotonicidade:** dentro de uma geração a altura não regride em silêncio: regressão ⇒ STALE (HEIGHT_REGRESSION) até superar o máximo visto; reinício/mudança de configuração = nova geração (zera o máximo). A atualização é por demanda, uma por vez, com intervalo mínimo, fora da thread do IPC (nunca bloqueia o chamador).

## IPC
`byx.status`, `byx.denomMetadata`, `byx.supply`: sem campos além de `v,id,op` (campo extra ⇒ `bad_request`); respostas só com campos fixos (sem JSON cru, sem cabeçalhos, sem detalhes internos do nó). `capabilities` ganhou `features.chainRead` e `features.chainConfigured`.

## Log (mínimo, sem payload nem URL)
`chain_connect gen=`, `chain_disconnect gen= reason=`, `chain_network_mismatch gen= reason=`, `chain_parse_rejected gen= reason=` (razões do enum fechado).

## Painel (somente leitura)
`ChainStatusClient` (IPC tipado) → `ServiceChainGateway` → a tela de rede existente. Estados: NOT CONFIGURED, CONNECTING, SYNCING, LIVE, OFFLINE, NETWORK MISMATCH, ERROR (+ STALE). Removido da tela o formulário que enviava endpoints REST/RPC, chain ID e genesis: o endpoint é do serviço. Sem botão de transação, sem conexão de carteira, sem promessa de mainnet. Os adaptadores legados diretos (carteira/pagamento/gás/benefícios/tesouraria) continuam no painel, **inertes** (atrás do ServerAuthorizer DENY_ALL e sem endpoint configurado); migrá-los para operações do serviço é trabalho futuro, depois do contrato READY.

## Pendências (dependem do contrato da chain)
`expectedChainId` final; portas/origem empacotada; verificação de identidade do nó além do chain id (id do nó, impressão do genesis) se o contrato exigir; gRPC/WebSocket; leituras de módulos (lojas, payments, feesplit, certificados) e migração dos adaptadores diretos.

## V2.1M — integração com o nó local REAL (QA descartável)
Contrato da chain: `READ_ONLY APP INTEGRATION = READY` (chain id `byx`, base `ubyx`, display `BYX`, expoente 6, CoinType 118, prefixo `byx`); **TX APP INTEGRATION continua BLOQUEADA** (`GAS_PRICE_POLICY_PENDING`): esta fase é só leitura pública.
Perfil tipado `ChainProfile`: `PRODUCTION_DISABLED` (sem nó) e `LOCAL_QA` (RPC 127.0.0.1:28657, REST 127.0.0.1:28317, `expectedChainId=byx`, `ubyx/BYX/6`). O perfil é escolhido no BUILD/PACKAGE, não em runtime: `build-app.sh` (padrão `production-disabled`; `--chain-profile local-qa` para o artefato de QA local) passa `-Dbyx.chain.profile` ao Maven, que grava o nome em `byx/chain-profile.txt` dentro do JAR do serviço (assinado no bundle). Nenhuma env/propriedade/arquivo de usuário/UI o lê ou o muda; nome desconhecido no build FALHA, e vazio/ausente/desconhecido em runtime resolve para `PRODUCTION_DISABLED`. Padrão: NOT_CONFIGURED, nenhuma thread nem socket da chain. Com `LOCAL_QA` e sem nó o estado é OFFLINE (fechado); chain id ou metadata divergente é NETWORK_MISMATCH.
Nó QA: `~/.byx-qa-v21m` (binário compilado do HEAD aprovado da chain, sem alterar o código-fonte; single-validator `byx`; RPC/REST/P2P só em 127.0.0.1; gRPC e grpc-web desligados; pex off; CORS e unsafe off; sem launchd). Parar: `kill -TERM $(cat ~/.byx-qa-v21m/node.pid)`. Subir: `nice -n 10 ~/.byx-qa-v21m/bin/byxd start --home ~/.byx-qa-v21m/home`.
Prova no produto (sonda assinada `--chain`/`--chain-watch=N`, mesmo caminho da tela de rede): LIVE com altura crescente e monótona, geração estável, denom e suprimento exatos; parada graciosa → OFFLINE → LIVE com altura preservada; chain id errada e metadata errada (nós descartáveis nos mesmos endpoints) → NETWORK MISMATCH sem altura/denom/suprimento.


## V2.1N/N+ — leituras públicas de módulo (somente leitura)

Contrato consumido e matriz da superfície de query: `CHAIN_CONTRACT_MANIFEST.md`. Resumo da arquitetura (tudo dentro do serviço; o painel só recebe DTOs tipados):

- **Fonte única** (`ChainReader`): uma instância por conector; reutiliza o transporte do conector (um `HttpClient`, sem redirecionamento, sem proxy, loopback fixo, prazos 1 s/2 s). Concorrência máxima 2 leituras + fila 8 (excesso = `RATE_LIMITED`, nunca fila infinita) e no máximo 40 buscas por 10 s. O nó nunca viu mais de 2 leituras + 1 status ao mesmo tempo nos testes.
- **Coalescência**: mesma operação + mesmos parâmetros normalizados em voo = uma única requisição ao nó; parâmetros diferentes nunca são compartilhados.
- **Cache** limitado (128 entradas, LRU), por operação (TTL no manifesto), preso à geração, à época de reconexão e ao chain id verificado. Nova geração ou rede divergente purgam o cache; dado de época anterior é revalidado, nunca servido como fresco. Nó indisponível: dado anterior só como `STALE` (máx. 10 min, mesma geração e chain id); "não encontrado" tem cache negativo de 3 s e nunca é servido como dado.
- **Polling**: não existe polling de módulos. O status do nó segue sendo atualizado sob demanda (mín. 5 s). A tela de dados pede ao serviço ao entrar, ao clicar e no Refresh (debounce de 1 s no painel; o serviço aplica cache e limite de taxa); ao sair, resultados em voo são descartados e nada continua rodando (zero timers).
- **Saúde**: `byx.moduleHealth` separa o estado do NÓ do estado de cada módulo (AVAILABLE/UNAVAILABLE/DEGRADED/NOT_EXPOSED/UNKNOWN) e expõe contadores locais (fetches, hits, coalesced, limited; latência da última leitura por módulo). `byx.status` ganhou `blockHash` (opcional), `blockAgeMs` e `blockFreshness` (FRESH ≤ 60 s, STALE, FUTURE > 2 s à frente).
- **Fronteira**: nenhuma operação de escrita, proxy ou consulta genérica; nenhum caminho do nó vem do painel (id decimal, endereço bech32 validado e cursor opaco tipado só ocupam o seu campo previsto). O pacote de leitura não contém vocabulário de assinatura/transação e uma futura TX terá pacote e interface próprios.
- **Autenticação**: o dado dos módulos é PÚBLICO. O painel o mostra depois do login porque o app inteiro exige sessão (arquitetura geral), não porque o dado seja privado; o gate privado não é consultado nem alterado pelas leituras (`PRIVATE_CAPABILITIES_ALLOWED=false`, coberto por teste).
- **Feesplit**: a chain não tem Query service nem rota para o módulo; o serviço responde `NOT_EXPOSED` e a tela mostra a alocação 60/30/10 (distribuição / tesouraria / queima) como padrão documentado do módulo, rotulado como NÃO lido do nó, sem afirmar "60% líquido para validadores" e sem codificar a community tax.

### Caminhos de leitura legados (auditoria, nada removido)
Permanecem inertes (atrás do `ServerAuthorizer` DENY_ALL e sem endpoint configurado): `adapter/CosmosByxChainGateway`, `CosmosByxPaymentVerifier`, `CosmosGasGrantGateway` e os adaptadores de carteira/tesouraria/benefícios. As telas Wallet/Benefits/Treasury ainda usam esses caminhos; só a rede e os dados públicos novos passam pelo serviço. Nenhum foi removido (não há prova de código morto com cobertura suficiente) e nenhum adaptador mutável foi tocado.
