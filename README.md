# BYX-MVP DEMO — Windows Alpha

Interface JavaFX do BYX-MVP para experimentar navegação, gráficos e preferências com dados inteiramente simulados. Nenhuma operação financeira real, conexão automática, conta real, carteira, pesquisa ETHUSDT, Service privilegiado ou servidor de autenticação faz parte desta composição.

## Distribuição e execução

A publicação depende dos gates de build, auditoria e execução do launcher. A presença desta branch ou de artefatos de CI **não significa que exista uma release aprovada**. Consulte [GitHub Releases](https://github.com/ChamberUS/DEV/releases) e as evidências da versão específica. O primeiro candidato é **PORTABLE ONLY**; nenhum instalador é qualificado nesta recuperação.

Quando houver uma pré-release aprovada, baixe `BYX-MVP-Demo-Windows-x64-Portable.zip` e `SHA256SUMS.txt` na mesma release. Confira o ZIP com `Get-FileHash -Algorithm SHA256`, extraia toda a pasta e abra `BYX-MVP.exe`. Preserve `app/` e `runtime/` ao lado do launcher. O EXE depende dessa pasta completa; não é um arquivo único autossuficiente. Não é preciso instalar Java, Maven ou Python, alterar PATH, configurar servidor ou usar VPN. Para remover o portátil, feche a DEMO e apague somente a pasta extraída.

Alvo: Windows 11 x64 com ambiente gráfico. A versão e o sistema efetivamente testados são registrados em `runner.json` e no relatório de QA. Não se presume qualificação de toda versão Windows ou máquina. Use inicialmente uma pasta com nome ASCII; caminhos Unicode têm teste separado e podem falhar conforme o runtime/Windows. A janela abre em 1440 × 900; mínimo 1100 × 720, com componentes responsivos.

**Login público:** `teste-byx`
**Senha pública:** `byx123123`
**Perfil:** `DEMO_USER / USER`.

Essas credenciais criam apenas uma simulação local; não são autenticação segura nem dão acesso ao backend real. Senha incorreta é rejeitada. Use Logout ou o avatar para encerrar a sessão.

## O que explorar

Trading Desk com watchlist ETHUSDT/BTCUSDT, candles, book e negociações fictícias; Markets & Portfolio com saldo e posições simuladas; Settings com DARK/LIGHT, FULL/REDUCED/OFF, densidade Compact/Comfortable, idioma e ícones; sidebar, busca/command palette e navegação. Trading permanece denso por design. Preferências ficam somente em memória e não alteram arquivos do proprietário. Posições, histórico e ajuda são demonstrativos; envio de ordens permanece indisponível. Nenhum preço é apresentado como cotação ao vivo.

## Limitações e segurança

Este candidato é **NÃO ASSINADO**. Algumas políticas Windows podem bloquear programas não assinados; uma assinatura legítima também não garante aceitação em toda máquina. Não desative nem contorne proteções. Se houver bloqueio, reporte a mensagem e a versão Windows sem enviar dados privados.

O proprietário aprovou a interface executada pelo JAR. Isso não constitui UAT do EXE. O workflow separa testes de contrato, execução nativa do portátil, runtime incorporado e automação de UI pelo JAR distribuído. UAT visual humana do EXE, desconexão física da Internet e instalação permanecem pendentes quando marcadas NOT_EXECUTED. A observação de ausência de endpoints é amostrada; a ausência de backend operacional também é garantida pela composição sem esses módulos.

## Feedback

Use [GitHub Issues](https://github.com/ChamberUS/DEV/issues/new) para Bug, Instalação, Visual, Navegação/UX, Sugestão ou Desempenho. Informe versão Windows, versão DEMO, descrição e passos; screenshot é opcional. Não envie senhas, tokens, dados de conta, arquivos pessoais ou logs sensíveis. Não há telemetria obrigatória. Templates nesta branch só se tornam formulários globais se o proprietário os integrar à branch padrão; `main` permanece inalterada.

## Build e proveniência

Veja [build e release](docs/BUILD-RELEASE.md), [isolamento](docs/SECURITY-ARCHITECTURE.md), [licenças](THIRD-PARTY-NOTICES.md), `DEPENDENCIES.json` e `SOURCE-PROVENANCE.json`.

Fonte visual: `feature/byx-c4-2-a1-service-utf8`, commit `828492af7b383083dbd6b749623876c188182db5`. A árvore `mvp-binance-panel/src/main` coincide com `feature/byx-windows-readiness-v1` em `d3203f54126f73ea14e8b0d44a858c75ea4702ea`, de onde foram exportados somente arquivos tracked da apresentação. Este repositório DEMO tem história independente, sem bootstrap operacional ou objetos do histórico anterior. Nenhum conteúdo integral do repositório privado de autenticação é publicado.