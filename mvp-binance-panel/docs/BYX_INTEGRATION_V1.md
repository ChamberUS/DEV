# BYX-MVP — integração de leitura V1

Etapa A auditada em 2026-10-03; seu registro histórico segue abaixo.
Etapas B/D executadas em 2026-10-04 UTC (2026-10-03 America/Sao_Paulo),
conforme seção final, que atualiza os smokes antes pendentes. Código BYX e motor
permaneceram intactos; a nova rede usa exclusivamente estado externo isolado.

## Fontes e identidade verificadas

Referência: https://github.com/ChamberUS/CRYPTO-. O origin local é
`git@github.com:ChamberUS/CRYPTO-.git`. `git ls-remote` confirmou HEAD
`11a80ac535abbbebaa60b58a7bdfec1bcb26d2c9`, igual ao checkout local.
Alterações preexistentes em documentação, backend e script webhook foram preservadas.
O painel pertence ao repositório pai DEV; nenhum commit ou push foi feito.

Fontes locais BYX consultadas: `go.mod`, `config.yml`, `app/app_config.go`,
`app/app.go`, `app/config.go`, `cmd/byxd/cmd/config.go`, `genesis.json`,
`docs/aws_private_devnet_checklist.md`, `docs/sprint10_fee_split.md`,
`docs/sprint11_5c_validation.md`, `docs/static/openapi.json`,
`evmd-fork/go.mod`, `evmd-fork/app.go`, `evmd-fork/config/config.go`.
Contratos HTTP confirmados nos protos locais do Cosmos SDK v0.53.3,
`cosmos/base/tendermint/v1beta1/query.proto` e `cosmos/bank/v1beta1/query.proto`.
O OpenAPI customizado não lista essas rotas padrão; `app.App.RegisterAPIRoutes`
registra as APIs do SDK. Suporte em código não equivale a smoke de nó ativo.

| Item | Evidência |
|---|---|
| Caminho escolhido | `byxd` / Cosmos, build principal `cmd/byxd` |
| Código principal | Go 1.25; SDK v0.53.3; CometBFT v0.38.17; IBC v10.2.0 |
| Binário instalado | `/Users/buynnex-corp/go/bin/byxd`; nome Byx; versão vazia; SDK v0.53.3; Go 1.25.3 darwin/amd64 |
| Commit do binário | `752d580de430f60c3f72f6296e8bbcc58b793c62`, diferente do checkout; não recompilado |
| Genesis de referência | `BYX/genesis.json`; chain `byx-devnet-private-1`; timestamp `2026-05-19T04:49:55.485598Z` |
| SHA-256 do arquivo | `b5f3e8365a8cc788afce1a22cd023ed9dbc07944668d4104e85e948f77c68f67` |
| SHA-256 canônico | `4ac6c20126c188bae8d7b4526b47cbdfb40bb4852ffd743b4dc2ce7b41596ef7` |
| Ativo | base `ubyx`, display `BYX`, 6 casas; fonte `app_state.bank.denom_metadata` |
| Endereços | prefixo `byx`; validator `byxvaloper`; consensus `byxvalcons` |
| Portas documentadas | REST 1317, RPC 26657, gRPC 9090; nenhuma com listener detectado nesta auditoria |
| EVM documentada | RPC 8545, WS 8546; nenhum listener detectado; `evmd` não encontrado no PATH |

O fingerprint canônico é SHA-256 do objeto genesis serializado em UTF-8, sem espaços,
com chaves de objetos ordenadas recursivamente e arrays preservados, como
`CosmosByxChainGateway.fingerprint`. Não é o hash do arquivo com sua indentação.
O fingerprint acima é referência de auditoria, não configuração automática nem
aprovação da rede existente como LOCALNET. Uma nova localnet terá outra identidade.

Módulos reaproveitáveis: bank/auth (saldo/identidade), tendermint (estado/blocos),
staking, distribution, gov, IBC; módulos próprios certificados, lojas, payments e
feesplit. V1 consulta apenas estado da rede, genesis, metadata e saldo bank.

Divergências: a checklist mistura supply em `byx` com gas em `ubyx`; o sprint10
menciona deltas em `byx` após fee `ubyx`. Nenhuma equivalência é assumida.
O sprint11.5-C cita `byx`, chain EVM `0x40000` e deploy `9000`, números diferentes.
O fork usa SDK v0.54.0-beta.0, CometBFT v1.0.0 e replace para `../cosmos-evm`:
não é o mesmo app principal. Sua funcionalidade e prontidão não foram demonstradas.

## Adapter e operação

`ByxChainGateway` é independente do BackendGateway quantitativo.
`CosmosByxChainGateway` permite apenas GETs fixos:

- REST `/cosmos/base/tendermint/v1beta1/node_info`
- RPC `/status` e `/genesis`
- REST `/cosmos/bank/v1beta1/denoms_metadata/ubyx`
- REST `/cosmos/base/tendermint/v1beta1/blocks/latest`
- REST `/cosmos/base/tendermint/v1beta1/syncing`
- REST `/cosmos/bank/v1beta1/balances/{address}/by_denom?denom=ubyx`

Não há executor shell, assinatura, broadcast, transferência ou importação de chave.
Redirecionamentos HTTP são rejeitados. V1 aceita apenas origins HTTP explícitas em
127.0.0.1 com porta; isso restringe acesso, mas NÃO prova ambiente de teste.
O ADMIN deve selecionar LOCALNET e informar chain ID e fingerprint de uma rede
local de teste conhecida. TESTNET e demais ambientes são rejeitados nesta entrega.

Abrir Research e completar a autorização existente; voltar a Trading > BYX Network
para preencher REST, RPC, ambiente, chain ID, fingerprint e endereço opcional.
Sem AdminSession válida, os controles e endpoints não aparecem. A mesma barreira
AdminGate existe no serviço, inclusive após expiração. USER tem apenas o resumo.
A configuração fica na memória deste processo, compartilhada pelas sessões; não
é gravada no banco/Keychain. Logout pausa polling, limpa resultados e invalida
respostas em andamento; o próximo login retoma a configuração aprovada. Reiniciar
o aplicativo exige nova configuração ADMIN. Nenhum endpoint antigo é carregado.

A verificação exige chain ID esperado, mesmo ID de nó e rede em REST/RPC, genesis
esperado e metadata exata `ubyx`/`BYX`/6. Divergência produz UNVERIFIED sem saldo.
Identidade ausente em resposta inválida resulta em UNVERIFIED/OFFLINE, nunca aceitação.
Fingerprint é obrigatório na V1 para evitar confiança silenciosa sem genesis.
O endereço é observação pública, não prova de propriedade; validação local de
formato/prefixo não substitui a validação Bech32 do nó.

Saldos usam BigInteger e BigDecimal para formatação, sem double. Zero somente se
retornado explicitamente; ausência é UNKNOWN. Sem mistura com equity/PnL, conversão
para BRL/USDT ou valor sintético. Transações ficam indisponíveis: consulta paginada
e indexador não foram comprovados. Sincronização ausente fica UNKNOWN.

Worker daemon dedicado; conexão com timeout 2s e cada GET com timeout 3s; sete GETs
no máximo por ciclo, seis sem endereço. Polling a cada 30s; uma consulta em andamento;
UI só lê cache. Bloco/consulta com mais de 60s é STALE, com timestamp preservado.
Falha após sucesso exibe OFFLINE + STALE e saldo explicitamente antigo; primeira
falha fica UNKNOWN sem zero. Horário futuro >30s fica UNKNOWN. Pausar/desligar BYX
não interrompe captura. Fonte LIVE_NODE/MOCK, rede LOCALNET/UNKNOWN e execução
DISABLED são campos distintos, independentes da fonte escolhida no Research.

Marca central em AppBranding, títulos Login/Trading/Research/BYX Network e assinatura
by Buynnex. CSS, fontes, MotionService, nomes de packages, banco e identificadores
Keychain preservados. Nenhum logo adicional incorporado. Binance continua como fonte
de mercado. Autenticação, 2FA e trusted devices não foram modificados.

## Tesouraria experimental BYX — próxima etapa, não executada

- Conta comum de teste: chaves exclusivamente de teste, saldos somente provenientes
  de genesis/faucet formalmente aprovados em futura entrega.
- Module account `treasury`: destino interno do feesplit, sem chave privada comum;
  está na lista de contas bloqueadas em `app/app_config.go`. Não assumir recebimento
  por bank send nem assinatura como carteira comum. Split documentado 60/30/10 em
  validators/treasury/burn, sobre fees; não é compartilhamento de lucro do bot.
- Capital paper do bot: contabilidade de simulação separada, não lastreada pelo saldo BYX.
- Reservas financeiras reais: fora desta integração, sem depósitos, resgates ou remuneração.

Propor um novo diretório de nó, por exemplo `~/.byx-mvp-localnet-v1`, novo chain ID
exclusivo e novo genesis/fingerprint; novas chaves descartáveis de teste, portas
isoladas em loopback, limites de recursos compatíveis com a captura e revisão
prévia do bootstrap. Não reutilizar identidade, seeds ou estado da rede existente.
Nenhum supply, tokenomics, genesis existente ou saldo foi alterado. Bootstrap,
faucet, transferências e criação de chaves ficam para autorização/execução futura.

## Validação e limites

Fixtures sintéticas em `ByxNetworkTest`, servidas por HTTP local efêmero; não são
dados da BYX. O adapter de transporte mantém LIVE_NODE como tipo de fonte; o teste
com gateway MOCK verifica a identificação explícita. Não há fallback automático
para dados mock na interface.

Testes cobrem conexão, timeout, nó parado/cache, precisão, metadata/chain/genesis
incorretos, endpoints de nós diferentes, dados ausentes/stale, separação do bot,
USER/ADMIN/expiração, logout em voo e marca sem reset da sessão. A suíte existente
exercita autenticação/trusted devices, providers, captura e motion.

Smoke real e revisão visual interativa da nova tela estão pendentes: nenhum nó
configurado/disponível e nenhuma conexão a endpoints históricos realizada.
Não foram executados build BYX/EVM, bootstrap, faucet, transferência, processamento
quantitativo, acesso a VALIDATION/FINAL_HOLDOUT ou push. Estado de nó, bancos, logs,
seeds e credenciais não fazem parte dos arquivos adicionados.

Resultado final: `mvn clean package` — BUILD SUCCESS, 107 testes, 0 falhas,
0 erros, 0 skips (15 testes BYX). `ruff check src tests` executado pelo binário
`.venv/bin/ruff` no motor, sem alterações: passou. `git diff --check`: passou.
Revisão dirigida de 14 arquivos de código/documentação não encontrou credenciais
incorporadas; alterações preexistentes BYX e arquivos de autenticação/Keychain
permaneceram intactos. Nenhum teste usou o Keychain real para validar o branding.

## Etapa B executada — localnet e smoke real

### Binário, identidade e isolamento

Checkout auditado mantido: `11a80ac535abbbebaa60b58a7bdfec1bcb26d2c9`.
Compilação `go build -p 2 -mod=readonly`, `GOMAXPROCS=2`, prioridade `nice -n 10`,
ldflags Name=byx, AppName=byxd, Version=localnet-b-11a80ac e Commit do checkout.
Go 1.25.3 darwin/amd64, Cosmos SDK v0.53.3. Saída exclusiva em
`~/.byx-mvp-localnet-b-v1/bin/byxd`; o binário global não foi substituído.
A árvore BYX contém as mesmas alterações locais de documentação/backend já auditadas;
nenhum arquivo Go ou go.mod foi modificado nesta tarefa.

| Identidade | Valor |
|---|---|
| SHA-256 binário novo | `d4089cf9c9dfee58c8f243577fded05d7b350f319535a0a7bbe699d3850575da` |
| Home novo | `~/.byx-mvp-localnet-b-v1/node` |
| Chain ID | `byx-mvp-localnet-b-20261004-5c2d82d9` |
| SHA-256 genesis SDK em disco | `ec9a3dd6cd5e02ab4c1f76303b0334923e1e44f65b3f79cef72b68b5098a1087` |
| Fingerprint canônico RPC | `0995695705b30305d266250292861dffd45107d7a9414b480bdcc5ffcf110e52` |
| REST / RPC | `http://127.0.0.1:1417` / `http://127.0.0.1:27657` |
| Outros listeners | gRPC `127.0.0.1:9190`; P2P `127.0.0.1:27656` |
| ABCI reservado | `127.0.0.1:27658`, sem listener externo na execução observada |

A metadata de genesis e REST confirmou base `ubyx`, display `BYX`, exponent 6.
As fontes de código são `x/lojas/types/keys.go`, `tokenomics.go` (ByxExponent=6)
e `x/feesplit/types/keys.go` (DefaultDenom=BaseDenom). Não foi inferido pelo prefixo.
Staking, depósitos gov, mint e feesplit usam ubyx. Inflação desativada apenas neste
novo genesis de teste; faucet desativado. Nenhum genesis ou saldo histórico mudou.

**Correções pontuais descobertas no smoke:** REST SDK usa
`default_node_info.default_node_id`, RPC usa `node_info.id`; adapter e fixture foram
corrigidos sem substituir a arquitetura. Cosmos SDK serializa AppGenesis em disco,
mas CometBFT expõe GenesisDoc no RPC. O controlador deriva a projeção esperada do
arquivo local conforme `AppGenesis.ToGenesisDoc` do SDK: `consensus.params` vira
`consensus_params`, initial_height vira string, app_hash nulo vira string vazia,
app_name/app_version ficam fora. Todo app_state permanece idêntico. O fingerprint
é calculado dessa projeção local, **não adotado por confiança no primeiro RPC**.
Foi verificada igualdade integral da projeção com `/genesis`. Para configurar o
painel, usar o fingerprint RPC acima, não o SHA do arquivo SDK.

`byx_localnet.py init/start/status/stop` atua somente no home fixo isolado. Init
recusa estado existente; start verifica hashes, marcador, portas e PID, recusando
instância duplicada; stop verifica PID, comando e horário de nascimento antes de
SIGTERM e não usa SIGKILL. Estado é preservado. O teste de portas usa SO_REUSEADDR
para TIME_WAIT sem permitir listener ativo, coberto por teste. Seeds/peers vazios,
PEX desativado, zero peers de entrada/saída, gRPC-web/metrics desativados. Todos os
listeners reais foram conferidos por lsof como loopback.

Novas chaves geradas pelo CLI, keyring `test` exclusivamente local, com umask 077;
stdout/stderr da criação foram descartados para não exportar mnemônicos.
Home, keyring, chaves, PID, bancos, binários, logs e evidências ficam fora do Git.
Nada foi copiado do estado histórico ou do servidor antigo. O script histórico
`genesis_private_devnet.sh`, que remove home e escreve genesis do repositório,
**não foi executado**.

### Quantidades e transferência

Valores deliberadamente de teste, sem equivalência financeira:

| Conta nova | Genesis (ubyx) | Observação |
|---|---:|---|
| validator-test | 1000000000000 | 500000000000 delegados no gentx |
| alice-test | 1000000000 | remetente da transferência |
| bob-test | 100000000 | destinatário |

Uma única transferência via `byxd tx bank send`, keyring test e home explícito:
`1234567 ubyx`, taxa `10000 ubyx`, gas limit 200000, gas usado 79224.
Tx `E289D6AF5028D7220CBB8BEFCF32D6510D5AD7B902B90A98E92C077EE06DEBE3`,
bloco 24, code=0. Confirmação por `query tx`, não apenas CheckTx/broadcast.

Alice: `1000000000 → 998755433 ubyx`; Bob: `100000000 → 101234567 ubyx`.
Diferença da remetente = valor + taxa; diferença do destinatário = valor.
Treasury recebeu `3000 ubyx` internamente pelo feesplit; burn `1000 ubyx`,
supply observado `1001099999000 ubyx` (supply inicial `1001100000000`).
Não houve transferência direta à module account treasury nem assinatura por ela.
O script `byx_localnet_transfer.py` fixa as contas locais, verifica identidade e
usa um arquivo de claim exclusivo: uma segunda execução é recusada, sem duplicar
transferência. Nenhuma assinatura/custódia foi adicionada ao Java de produção.

Runner manual `panel.ByxLocalnetSmoke` usou o gateway existente: identidade VERIFIED,
blocos 65→66, Alice `998.755433 BYX`, Bob `101.234567 BYX`, chain errada UNVERIFIED,
parada graciosa com OFFLINE/STALE e timestamp preservado; primeira consulta offline
sem saldo (não zero). A localnet foi reiniciada com o mesmo estado, sem bootstrap.

### Inspeção visual e autenticação

`panel.ByxVisualSmoke` executou o painel real em banco e user.home descartáveis,
com o DevOtpProvider já existente: login, OTP email/SMS, AdminSession e troca
obrigatória de senha de USER. Sem trusted-device/Keychain real e sem mensagens
externas. Research MOCK isolado para não ler datasets; BYX usou LIVE_NODE real.
Screenshots ADMIN, USER e benefícios inspecionados em 1440×900; Motion FULL e CSS
existentes preservados. ADMIN viu configuração; USER viu apenas resumo, com aviso
LOCALNET / ATIVOS DE TESTE / SEM VALOR FINANCEIRO.

O teste visual encontrou uma falha preexistente: `PanelApp.render` renderizava todas
as páginas Research, inclusive SecuritySettingsPane, antes de AdminSession válida.
A correção é restrita a renderizar essas páginas somente com a autorização existente.
Não há bypass nem relaxamento de AdminGate/2FA. Branding da Etapa A foi reutilizado.

### Captura observada (UTC)

Não houve pausa solicitada pelo agente nem sinal enviado ao supervisor/recorder.
Build limitado para preservar recursos; binário concluído às 00:05:04 UTC.
Mecanismo existente confirmado: supervisor `continuous_capture.sh`, SIGTERM com
wait do filho e cleanup; retomada pelo mesmo supervisor/script, nunca concorrente.
O supervisor PID 62472 permaneceu o mesmo durante toda a tarefa.

- Inicial: recorder PID 7176, campaign `ethusdt-futures-continuous-20261003T233724Z`,
  sessão `microstructure-20261003T233725Z-usd_m_futures`. Às 00:04:43 UTC,
  segundo arquivo parcial tinha 6942002 bytes / 38232 linhas de eventos.
- A sessão fechou às 00:07:27.719854 UTC, manifest COMPLETE, 152849 eventos;
  último evento 00:07:25.592000. Runtime health READY, dropped_events=0,
  backlog_unrecovered=false. Nenhum artefato foi reescrito para compensação.
- Às 00:08:03 UTC a campanha encerrou com exit_code=2 e mensagem
  `campaign requires at least one session`. A sessão física completa não significa
  campanha/coorte aceita. A causa científica não foi investigada nem alterada.
- Supervisor aguardou o backoff existente de 300s e retomou às 00:13:03 UTC:
  recorder PID 10194, campaign `ethusdt-futures-continuous-20261004T001303Z`,
  sessão `microstructure-20261004T001304Z-usd_m_futures`. Primeiro evento observado
  às 00:13:05.090184 UTC: intervalo entre eventos de **5m39.498184s**.
- Às 00:14:27 UTC: 1438423 bytes / 7475 eventos no novo arquivo parcial.
  Às 00:19:23 UTC: 7508893 bytes / 41300 eventos. Uma única instância recorder
  confirmada. Evidência de gravação efetiva, não só PID vivo.

Essa interrupção autônoma aconteceu durante a tarefa; não foi tratada como pausa
planejada nem atribuída sem evidência ao build BYX. Não se alteraram contrato de
coorte, admission, hashes, freeze, critérios ou retornos para ocultar o intervalo.

## Etapa D — base de benefícios, sem concessão

Área “Plano e benefícios BYX” disponível em Trading para USER/ADMIN, sem carteira.
`ByxBenefitsService` fornece quatro contratos pequenos: PaymentMethods,
AppDiscountPolicy, UsagePolicy e NetworkFeePolicy. Pagamento independente de BYX
é uma opção explícita; BYX é opcional. Nenhum provedor está conectado, nenhum preço,
percentual ou limite adicional foi escolhido. Valores Optional vazios significam
**não definido**, não zero, grátis ou ilimitado. UI marcada SIMULAÇÃO / NÃO CONTRATADO.

Taxa do app (desconto futuro), taxa da rede (possível subsídio futuro) e taxa da
exchange são independentes. Nenhum custo Binance no motor foi reduzido ou editado.
A política conserva acesso normal e devolve benefício=false, admin=false,
researchUnlocked=false e strategyExecutionEnabled=false para qualquer endereço.

Não existe verificador de prova de controle reutilizável nesta entrega. Portanto,
`controlProof=NOT_IMPLEMENTED` e toda concessão permanece bloqueada. Próxima etapa:
desafio gerado pelo serviço autenticado com nonce imprevisível de uso único,
expiração curta, domínio BYX-MVP, usuário/sessão, endereço, chain ID e fingerprint
vinculados; assinatura de mensagem com consentimento explícito na carteira e
verificação de assinatura/endereço no serviço. Deve rejeitar replay, outra rede,
usuário/domínio/nonce divergente e expiração, com revogação/auditoria. Não basta
saldo, endereço digitado ou booleano enviado pelo cliente. Nenhuma assinatura real
ou conta administrativa decorre dessa futura prova; ela não autoriza operações.

### Tesourarias distintas

| Categoria | Estado nesta entrega |
|---|---|
| Tesouraria operacional em dinheiro | Não conectada/verificada; entrada manual não comprova reserva |
| Liquidez futura | Apenas possibilidade; sem bridge, pool ou compromisso de liquidez |
| Lastro/resgate | Não definido, sem promessa ou token dólar |
| ubyx localnet / treasury do feesplit | Somente teste sem valor financeiro; module account sem chave comum |
| Capital paper/live do bot | Separado de BYX e de reservas; nenhuma execução nova habilitada |

Nenhum USD/USDC/USDT, dinheiro real, rendimento, resgate ou compartilhamento de lucro.
Sem acesso a retornos novos, VALIDATION ou FINAL_HOLDOUT; motor e datasets preservados.

### Operação manual após a entrega

Do diretório do painel, `python3 scripts/byx_localnet.py status`, `start` e `stop`
gerenciam apenas esta localnet. `init` recusa o home já existente; não usar novamente.
`byx_localnet_transfer.py` recusa repetir o smoke já executado. Nunca apagar claim
para repetir automaticamente uma transferência.

O painel de produção conserva configuração apenas em memória. Após login e 2FA,
configurar BYX Network com a identidade acima e o endereço Alice público registrado
em `~/.byx-mvp-localnet-b-v1/localnet.json`. O QA não alterou o banco/login reais.
Smokes manuais Java ficam em src/test e não entram no JAR de produção. Evidências,
screenshots e logs permanecem no diretório externo `evidence`, fora do Git.

### Resultado final B/D

- `mvn clean package`: BUILD SUCCESS, 111 testes, 0 falhas/erros/skips.
- `go test -p 2 -mod=readonly ./x/lojas/types ./x/feesplit/types`: lojas/types passou;
  feesplit/types informa ausência de testes. `go test ... ./x/feesplit/keeper`: passou.
- `python3 -m unittest discover -s src/test/python -v`: 7 testes passaram (recusa de
  overwrite/instância duplicada/listener ativo/marcador incorreto/PID reutilizado,
  projeção do genesis e SIGTERM).
- Runner real ByxLocalnetSmoke e runner visual ByxVisualSmoke: passaram.
- `.venv/bin/ruff check src tests` no motor e `git diff --check`: passaram.
- Diff/14 arquivos da Etapa B/D revisados; nenhuma chave/credencial real incorporada.
  Binário global conferido pelo SHA-256 anterior: inalterado. Arquivos BYX locais
  preexistentes preservados. Não houve push.

A localnet foi deixada em execução para configuração manual do painel, somente
loopback e com GOMAXPROCS=2. `stop` a encerra graciosamente; `start` reutiliza o estado
isolado existente. Captura continua pelo supervisor original, sem instância dupla.
A causa da rejeição autônoma da campanha anterior continua fora do escopo; não foi
corrigida mediante mudança de critérios. Prova de controle e pagamentos externos
continuam não implementados por desenho; nenhuma concessão real foi liberada.

## Wallet Ownership v1 — implementação localnet (2026-10-04 UTC)

Esta seção substitui a limitação anterior de prova não implementada. A concessão
agora existe somente como benefício experimental LOCALNET, sem pagamentos ou
permissões de segurança. Branding, byxd/Cosmos, ubyx/BYX/6 e gateway foram reutilizados.

### Formato e auditoria do suporte

Fontes primárias consultadas:

- [Cosmos ADR-036](https://docs.cosmos.network/v0.53/build/architecture/adr-036-arbitrary-signature):
  envelope off-chain `sign/MsgSignData`, sem transação válida para broadcast.
- [Keplr signArbitrary](https://docs.keplr.app/api/guide/sign-a-message): compatibilidade
  conceitual com assinaturas ADR-036; integração Keplr não foi implementada/testada.
- [BIP-173](https://github.com/bitcoin/bips/blob/master/bip-0173.mediawiki): codificação
  Bech32; prefixo BYX de contas `byx`.
- Código SDK local v0.53.3: `crypto/keyring/keyring.go` (Sign),
  `crypto/keys/secp256k1/secp256k1_nocgo.go` (SHA-256, r||s, low-S),
  `crypto/keys/secp256k1/secp256k1.go` (derivação de endereço).

`byxd tx --help` não oferece comando ADR-036 pronto. Não se reutilizou assinatura
normal de tx como prova de identidade. O pequeno adapter Go DEV usa SDK keyring.Sign
com SIGN_MODE_LEGACY_AMINO_JSON, sem exportar material privado. Java verifica com
Bouncy Castle 1.78.1 já presente, sem algoritmo de assinatura próprio.

`CosmosWalletProof.message(challenge)` define exatamente os bytes UTF-8 assinados:
JSON compacto, chaves ordenadas, com `address`, `chain_id`, `context`, `expires_at`,
`genesis_fingerprint`, `issued_at`, `nonce`, `user_id` (string decimal).
Contexto fixo: `BYX-MVP/wallet-ownership/v1/LOCALNET/TEST-ONLY`.
A UI exibe esse conteúdo exato. O botão “Copy DEV challenge JSON” copia o DTO para
entrada do signer de teste; o DTO usa userId/chainId/genesisFingerprint/issuedAt/
expiresAt, e não deve ser confundido com o payload canônico de assinatura.

O payload vira base64 em `msgs[0].value.data`; `signer` é o endereço desafiado,
`type=sign/MsgSignData`. O documento Amino tem account_number="0", chain_id="",
sequence="0", fee.amount=[], fee.gas="0", memo="". Chain real e fingerprint estão
vinculados **dentro dos dados assinados**, não no chain_id vazio exigido pelo ADR.
A assinatura é base64 de 64 bytes r||s, SHA-256 dos sign bytes, secp256k1 low-S.
Public key é base64 da chave comprimida de 33 bytes. O endereço esperado é
Bech32(byx, RIPEMD160(SHA256(pubkey))). Multisig/ed25519 ficam fora da v1.
A interoperabilidade foi demonstrada entre o SDK Go e o verificador Java no smoke.

### Challenge, vínculo e armazenamento

ByxWalletIdentityService exige usuário autenticado ativo, com troca obrigatória de
senha concluída. Challenge contém 32 bytes SecureRandom em hex (256 bits), usuário,
endereço, chain ID, fingerprint, timestamps e contexto. Validade 5 minutos;
um challenge pendente por usuário, também vinculado ao UUID da sessão de login.
Nova emissão invalida o anterior. Logout limpa pendências. Uma tentativa vinculada
à sessão consome o nonce antes de verificar assinatura; falha exige novo challenge.
Map e persistência são protegidos contra corrida: apenas uma verificação concorrente
vence. Modificação dos campos, sessão, usuário, rede, contexto, expiração ou replay
falha. Reinício perde pendências, logo uma prova antiga não é reaceita.

Tabela aditiva `verified_wallets`, sem mudar User ou resetar login/Keychain:
user_id, address, public_key, chain_id, genesis_fingerprint, verified_at,
last_verified_at, revoked_at. Chave composta usuário/endereço/identidade da rede;
permite múltiplas wallets. Persistência testada reabrindo SQLite. Não persistem
challenge, assinatura, nonce, seed ou private key. Verificação vence em 24h,
exigindo Reverify. Reverify mantém verified_at original e atualiza last_verified_at;
Revoke marca a data, invalida pending e desabilita imediatamente os benefícios.
Relink após revoke exige novo challenge e assinatura; não reativa prova antiga.

A confiança de uma instalação desktop/local continua limitada à integridade do seu
processo e banco. Isso não é backend multiusuário de produção nem prova de propriedade
exclusiva da wallet: demonstra controle da chave no instante da verificação.
Uma futura concessão financeira precisará de validação e enforcement no servidor.

### Signer DEV separado e extensibilidade

`tools/byx-test-signer/main.go` nunca é empacotado no JAR. Exige
`BYX_LOCALNET_TEST_SIGNER=I_ACKNOWLEDGE_TEST_ONLY`; recusa outro propósito, home,
chain, genesis, expiração ou endereço. Home fixo `~/.byx-mvp-localnet-b-v1/node`;
allowlist somente alice-test/bob-test (nunca validator). Usa keyring test já criado,
sem exportar seed/private key para Java, código, banco, stdout ou Git. Só emite
WalletProof público; não possui broadcast. Não existe chamada a esse executável
no Java de produção; os runners de src/test o invocam explicitamente em DEV.

`ByxWalletSigner.sign(WalletChallenge)` retorna CompletionStage<WalletProof>, sem API
para chaves. Pode receber depois adapters Keplr, WalletConnect compatível e hardware.
Não há WebView, wallet browser, custódia ou integração externa nesta entrega.
O fluxo normal da UI recebe prova pública de signer externo por JSON.

Compilar o helper com as dependências do checkout auditado (não recompila byxd):

```bash
cd /Users/buynnex-corp/dev/BYX
GOMAXPROCS=2 go build -p 2 -mod=readonly -o "$HOME/.byx-mvp-localnet-b-v1/bin/wallet-test-signer" /Users/buynnex-corp/dev/mvp-binance-panel/tools/byx-test-signer/main.go
```

Para demonstração manual: copiar “DEV challenge JSON” para um arquivo temporário
**fora do repositório**, fornecer por stdin ao helper e colar o JSON público resultante
em “Verify signature”. Nenhum comando de exportação de chave é necessário:

```bash
BYX_LOCALNET_TEST_SIGNER=I_ACKNOWLEDGE_TEST_ONLY "$HOME/.byx-mvp-localnet-b-v1/bin/wallet-test-signer" < /private/tmp/byx-wallet-challenge.json
```

O challenge expira; não usar exemplos históricos. Para carteira externa futura,
usar ADR-036/signArbitrary sobre o conteúdo UTF-8 exibido, não assinatura raw de tx.

### Tiers e BenefitsSnapshot

Configuração explícita `src/main/resources/panel/byx-localnet-benefits.properties`,
ambiente obrigatório LOCALNET_TEST_ONLY. Valores de desenvolvimento em ubyx:
FREE=0, HOLDER=100000000, PLUS=1000000000, PRO=10000000000. Esses thresholds são
simulação configurável, não preços, reservas ou política financeira permanente.

Somente vínculo válido do usuário e da rede configurada entra no cálculo. Saldo
vem do ByxChainGateway existente, consultando o endereço verificado (não o endereço
observado do ADMIN). Requer LIVE_NODE, LOCALNET, identidade VERIFIED, ONLINE/FRESH,
syncing=false, timestamps recentes, denom e decimais confirmados. Arithmetic
BigInteger; exibição BigDecimal com seis casas. Não há double monetário.

BenefitsSnapshot contém estado da wallet, endereço, saldo ubyx, tier, benefitsEnabled,
lastChainUpdate, chainState, benefícios disponíveis e próximo tier. Uma wallet
selecionada por vez; saldos de várias wallets não são somados. UNKNOWN, offline,
stale, sincronização ou expiração/revogação desabilitam benefícios e voltam a FREE:
nenhuma escalada por cache antigo. Saldo ausente nunca vira zero. Cache envelhece
em 60s e é revisto contra vínculo/sessão/rede atuais; chamadas assíncronas dedicadas,
uma consulta por vez e polling de 30s nas telas visíveis.

Ativação significa selo e prévia do tier experimental. Não libera desconto monetário,
recursos pagos, subsídio efetivo, ADMIN, Research, VALIDATION, FINAL_HOLDOUT ou live
trading. Métodos de pagamento independentes permanecem disponíveis como contrato;
BYX continua opcional. Taxas do app, rede BYX e exchange continuam separadas.

### UX e smoke real

Navegação BYX-MVP → BYX → Wallet, com Link/Reverify, challenge, importação de prova,
VERIFYING, VERIFIED, saldo/tier e Unlink/Revoke. Estados NO WALLET, WATCH-ONLY,
VERIFICATION REQUIRED, EXPIRED/REVOKED e CHAIN OFFLINE; avisos LOCALNET / TEST ASSETS /
NO FINANCIAL VALUE. Benefits exibe wallet selecionada, current tier, saldo, benefícios,
próximo tier e atualização. CSS/fontes/Motion foram preservados. UI inspecionada em
banco descartável e usuário USER real do fluxo de teste, com Motion FULL; sem tocar
no login/Keychain de produção. Scripts/helper e evidências ficam fora do fluxo normal.

Smoke `panel.ByxWalletLocalnetSmoke`:

- challenge → SDK signature → Java verification → wallet linked (sem AdminSession);
- Alice `998755433 ubyx` / `998.755433 BYX`, tier HOLDER;
- Bob enviou `2000000 ubyx` exclusivamente na localnet; taxa `10000 ubyx`;
- tx `F864E4E714238A6A7CD747D091CF0D617E959891016E1FDA5922935376BC0D40`,
  bloco 378, code=0, saldos reconciliados;
- Alice `1000755433 ubyx` / `1000.755433 BYX`, refresh ativou PLUS;
  Bob `99224567 ubyx`;
- revoke devolveu FREE e benefitsEnabled=false imediatamente.

`scripts/byx_wallet_tier_transfer.py` tem contas/valor/chain fixos, checks de identidade,
claim de execução única e confirmação em bloco. Recusa repetir o teste ou executar
sobre outro saldo/estado. Não se removeu o claim anterior nem se repetiu transferência.
`ByxVisualSmoke` com `-Dbyx.wallet.qa=true` também percorreu geração e verificação pela
UI, Benefits e revoke; screenshots externos inspecionados. Nenhuma chave foi persistida
no painel. O smoke anterior de saldos fixos da Etapa B é histórico e não deve ser
reexecutado esperando os mesmos saldos após esta transferência autorizada.

### Postmortem da captura — somente leitura

Classificação: **PROCESS_EXCEPTION**, especificamente erro reportado na construção
ou validação da campanha; associação com rejeição científica é inferência apoiada
nos metadados, sem traceback suficiente para apontar a linha causadora. Não há
indícios nesses registros de NETWORK, BINANCE ou FILESYSTEM.

Evidências consultadas exclusivamente no incidente:
`~/.mvp-binance-capture/logs/ethusdt-futures-continuous-20261003T233724Z.log`,
manifest e scientific_admission da sessão `microstructure-20261003T233725Z-usd_m_futures`.
Manifest: COMPLETE, início 23:37:25.311494 UTC, fim 00:07:27.719854 UTC, 152849 eventos,
parser_errors=0, disconnects=0, gaps=0, dropped_events=0, runtime READY e fila vazia.
Scientific admission: admitted=false, status REJECT_FROM_SCIENTIFIC_DATASET,
reasons `PROVENANCE_INCOMPLETE|DIRTY_WORKTREE`, duration_seconds=0.0 apesar do intervalo
físico da sessão. Esse valor divergente foi registrado, não corrigido.

Às 00:08:03 UTC: `campaign requires at least one session`, exit_code=2. A sessão
fisicamente completa não foi admitida para a campanha científica. O supervisor
aplicou o backoff existente de 300s; recuperação histórica observada às 00:13:03 UTC.
Gap entre último/primeiro evento já registrado na Etapa B: 5m39.498184s. Impacto:
interrupção temporária da gravação e campanha anterior sem sessão admitida. Nenhum
retorno ou critério foi consultado/ajustado para compensar isso. Não houve parada,
restart ou alteração do supervisor nesta fase; não é classificação CAMPAIGN_ROTATION
normal, pois a saída foi de erro e a meta de 24h não foi concluída.

### Validação final e arquivos desta fase

`mvn test` e `mvn clean package`: ambos BUILD SUCCESS, **132 testes**, zero falhas,
erros ou skips. Incluem 21 testes de ownership/benefícios (assinatura, chave, endereço,
usuário, chain/context/genesis, expiração, nonce, replay/restart/corrida, low-S,
revogação, watch-only, offline/stale, limites exatos e persistência de campos públicos),
além das regressões existentes de autenticação, captura, gateway e motion.
Testes Go do signer DEV passaram, incluindo recusa sem opt-in e proteção contra
rede histórica/genesis alterado antes de abrir keyring. Smoke real e inspeção visual
passaram. Artefatos de test runner/signer Go não são incluídos no JAR de produção.

A revisão final preserva lastChainUpdate e saldo anterior explicitamente STALE se o
nó ficar offline; tier volta a FREE e benefícios são desativados. Assinaturas high-S
malleáveis são rejeitadas. O script Python novo passou em sintaxe e Ruff.
`git diff --check` passou. Ruff amplo no **painel** (`src scripts`) identificou 18
pendências anteriores em byx_localnet.py, byx_localnet_transfer.py e seu teste Python;
nenhuma é do novo script. Esses arquivos não foram alterados nesta fase para limpeza
fora do escopo. O lint/código do motor não foi analisado: somente logs/metadados da
captura autorizados. Revisão de 23 arquivos da fase não encontrou material privado
ou credenciais reais incorporados. Nenhum push ou mudança no código BYX.

Criados (15):

- `src/main/java/panel/adapter/ByxWalletSigner.java`
- `src/main/java/panel/model/BenefitsSnapshot.java`
- `src/main/java/panel/model/WalletChallenge.java`
- `src/main/java/panel/model/WalletProof.java`
- `src/main/java/panel/model/VerifiedWallet.java`
- `src/main/java/panel/security/CosmosWalletProof.java`
- `src/main/java/panel/repository/ByxWalletRepository.java`
- `src/main/java/panel/service/ByxWalletIdentityService.java`
- `src/main/java/panel/ui/ByxWalletView.java`
- `src/main/resources/panel/byx-localnet-benefits.properties`
- `src/test/java/panel/ByxWalletOwnershipTest.java`
- `src/test/java/panel/ByxWalletLocalnetSmoke.java`
- `tools/byx-test-signer/main.go`
- `tools/byx-test-signer/main_test.go`
- `scripts/byx_wallet_tier_transfer.py`

Alterados (8):

- `src/main/java/panel/app/AppContext.java`
- `src/main/java/panel/app/PanelApp.java`
- `src/main/java/panel/service/ByxNetworkService.java`
- `src/main/java/panel/service/ByxBenefitsService.java`
- `src/main/java/panel/ui/ByxBenefitsView.java`
- `src/test/java/panel/ByxBenefitsTest.java`
- `src/test/java/panel/ByxVisualSmoke.java`
- `docs/BYX_INTEGRATION_V1.md`

Não executados: reinício/parada da captura, alteração do supervisor ou coortes,
processamento do motor, análise de retornos/VALIDATION/FINAL_HOLDOUT, integração
Keplr/WalletConnect/hardware, transação real, EVM, migração de saldo ou push.
As integrações externas ficam para adapters futuros; a v1 localnet está funcional.

Revisão final: `git diff --check -- .` passou no painel. A verificação sem
pathspec também alcança outros projetos do repositório pai e encontrou cinco
linhas com whitespace preexistente em `iaos-web/src/apps/site_legacy/Layout.jsx`
e `iaos-web/src/apps/site_legacy/Pages/MyStore.jsx`; esses arquivos não foram
alterados nesta tarefa.

## BYX Entitlements v1 — application capabilities, LOCALNET/TEST

This phase extends the earlier badge/preview delivery with a central application
capability service. `Entitlement` stores id, display name, required tier, enabled,
source, optional expiry and UNLOCKED/LOCKED/UNAVAILABLE status. Tier thresholds
remain in the existing exact-integer ubyx development configuration; they are not
prices, financial balances, permanent policy or exchange cost reductions.

`VerifiedWallet -> ByxBenefitsService -> tier -> EntitlementService -> UI capability`.
Views never authorize from a balance. Only a fresh verified current-user wallet
can unlock the four allowlisted application capabilities:

| Capability | Required tier | Scope |
| --- | --- | --- |
| extended_history | HOLDER | Implemented noncritical UI demo: recent public wallet balance refreshes |
| advanced_analytics | PLUS | Experimental application capability contract |
| advanced_bot_controls | PLUS | Interface preview contract; no bot execution authority |
| premium_research_tools | PRO | Synthetic preview contract; no dataset or administrative research permission |

The allowlist rejects all other ids, including ADMIN, research_admin, VALIDATION,
FINAL_HOLDOUT, live_trading and strategy_execution. Existing authentication,
research approvals, dataset gates and execution checks are unchanged. The source
is explicitly `LOCALNET_TEST/HOLD_TO_UNLOCK`. The metadata expiry is the chain
freshness deadline (last update + 60 seconds); proof expiry/revocation or logout
may remove access earlier. Every consumer must recheck through the service.
No payment, financial discount, real custody or live execution is implemented.

### Real UI gate and exact progress

Benefits shows current tier, verified wallet, balance, LOCALNET + connection /
freshness, last refresh, YOUR BENEFITS rows, and next tier / required / current /
remaining BYX. Arithmetic is BigInteger ubyx; rendering is BigDecimal with six
places. Unknown amounts stay unknown, rather than becoming fabricated zeroes.
For maximum tier no required/remaining amount is applicable. Cached offline data
is explicitly OFFLINE/STALE; policy falls back to FREE and disables grants.

“Open Extended History” is disabled for FREE. Its click handler also rechecks
`EntitlementService.extendedHistory`, so an old enabled button cannot bypass a
subsequent downgrade, expiry, revocation, logout or stale/offline chain. The
implemented view lists up to 20 distinct wallet balance refresh snapshots observed
when opened during this app session. It reads no historical market/research data
and performs no trading operation. The other three entries are capability
contracts/preview metadata; this phase does not implement their feature modules.
Normal application access without a wallet stays available. Auth and Motion are
preserved; screens retain LOCALNET / TEST ASSETS / NO FINANCIAL VALUE notices.

### Future payment sources — documentation only

Entitlement issuance can later accept independent HOLD_TO_UNLOCK, PAY_TO_UNLOCK,
SUBSCRIPTION and DISCOUNT policies. Each source would require its own evidence,
expiry/revocation and enforcement; a tier should not substitute for a payment
receipt. Payment proofs and subscription records would live separately from
wallet ownership and balance snapshots. No external payment connection, invoice,
price or final discount is introduced now. Normal payments remain independent of
BYX; BYX will never be the sole mandatory payment method. App fees, BYX network
fees and exchange fees remain separate.

### Capture correction and validation for this phase

Concrete cause: the rejected first chunk left `paths` empty, then the CLI
unconditionally built an empty campaign. See
[CAPTURE_NO_SESSION_ROBUSTNESS.md](../../mvp-binance/docs/CAPTURE_NO_SESSION_ROBUSTNESS.md)
for incident evidence, bounded retries, NO_SESSION/INCOMPLETE exit 75 and the
versioned supervisor's fast retry policy. Scientific rejection is unchanged.
The old recorder autonomously failed again at 2026-10-04 01:19:57 UTC. During its
supervisor's backoff, with no recorder active, the old supervisor was closed
through its existing graceful cleanup and the reviewed launcher was installed.
Capture resumed at 01:22:19 UTC, supervisor 13916 / recorder 13923 / campaign
`ethusdt-futures-continuous-20261004T012219Z`. Fresh HTTP 200 plus growth of a new
event part-file confirmed recording. This task did not interrupt an active
recorder. The versioned retry policy is now installed; source details and exact
UTC activation evidence are in the capture document. No scientific gate changed.

Validation completed:

- `mvn test`: 139 tests, zero failures/errors/skips, BUILD SUCCESS.
- `mvn clean package`: 139 tests, zero failures/errors/skips, BUILD SUCCESS.
- Seven new entitlement tests cover all tiers, upgrade/downgrade, revocation,
  watch-only/logout, offline/stale/aged cache, exact one-ubyx progress, real history
  enforcement and exclusion of administrative/research/trading capabilities.
- Python: 21 targeted campaign tests passed with `--no-cov`; Ruff passed on both
  touched Python files. Shell syntax and exit 0/75/2 retry-delay tests passed.
  The initial pytest run passed its 16 tests but failed the full-suite coverage
  threshold; the targeted verification intentionally disables aggregate coverage.
- Real localnet visual smoke passed with disposable USER database, existing DEV
  signer and fresh public proof: FREE button disabled -> verified PLUS at
  1000.755433 BYX, unlocked history opened -> revoked wallet button disabled.
  Screenshots were visually inspected. No transfer was repeated in this phase.
- Scoped diff checks and private-key/credential-marker review passed. Preexisting
  Ruff findings and another project's whitespace were not corrected.

Files created: `src/main/java/panel/model/Entitlement.java`,
`src/main/java/panel/service/EntitlementService.java`,
`src/test/java/panel/ByxEntitlementsTest.java`.
Files changed: `src/main/java/panel/app/AppContext.java`,
`src/main/java/panel/service/ByxBenefitsService.java`,
`src/main/java/panel/ui/ByxBenefitsView.java`,
`src/test/java/panel/ByxVisualSmoke.java`, this documentation.
Capture files: `src/adaptive_trader/cli/main.py`,
`tests/microstructure/test_campaign_no_session.py`,
`scripts/continuous_capture.sh`, `docs/CAPTURE_NO_SESSION_ROBUSTNESS.md` in motor repo.

No active recording was interrupted. The supervisor was safely replaced only
after a new autonomous failure left it idle. No payment integration, real transfer,
heavy research, reserved dataset access, EVM or frozen-spec modification was run.

Git: capture source/tests/launcher/documentation were committed and pushed without
including preexisting research edits or data. The panel's preexisting BYX base is
still untracked; its coherent phase-only commit/push remains pending the user's
choice about including that necessary earlier base. The incremental source patch
is retained outside Git at `/private/tmp/byx-entitlements-phase/panel-phase.patch`.
No keys, keyrings, state, credentials, logs or raw data were staged.
