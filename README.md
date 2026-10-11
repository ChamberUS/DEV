# BYX-MVP DEMO — Windows Alpha 2

Port de apresentação JavaFX do BYX-MVP, com dados simulados e login exclusivamente local. A Alpha 2 corrige janela, PT-BR e composição visual da Alpha 1. Nenhuma conta real, servidor, Service, carteira, ordem, transação ou pesquisa executável integra o pacote.

## Baixar e abrir

Use a [pré-release Alpha 2](https://github.com/ChamberUS/DEV/releases/tag/demo-windows-v0.1.0-alpha.2) e baixe `BYX-MVP-Demo-Windows-x64-Portable.zip` e `SHA256SUMS.txt` da mesma release. Confira `Get-FileHash -Algorithm SHA256`, extraia a pasta completa e abra `BYX-MVP.exe`. Mantenha `app/` e `runtime/` junto do launcher. Java já está incorporado; não há instalação de Java, servidor ou VPN. Esta entrega é **PORTABLE ONLY**, sem Setup.

Alvo: Windows 11 x64 com desktop. O binário é **não assinado**. Se a política Windows impedir a abertura, reporte a mensagem; mantenha Smart App Control e Defender ativos. Prefira caminho ASCII: espaços e colchetes têm teste obrigatório; o caminho adicional com `Ω` segue não qualificado por falha do launcher ao carregar o runtime.

**Login público:** `teste-byx` · **Senha pública:** `byx123123` · **Perfil:** `DEMO_USER / USER`.

Essas credenciais são públicas e criam uma simulação local, sem acesso ao backend. Nenhum preço é cotação ao vivo. Use **Sair** ou o menu do avatar para encerrar a sessão.

## Janela e idioma

A janela inicia normal, centralizada, redimensionável e dentro da área útil do monitor primário. O dimensionamento usa pixels lógicos JavaFX, respeitando o scaling: aproximadamente 1200×760 quando cabe, com margem e mínimos adaptados. Login, navegação e logout preservam a geometria. Maximizar/restaurar e minimizar usam controles nativos Windows. Os painéis amplos mantêm conteúdo e oferecem rolagem em janelas menores.

**Português (Brasil)** é o idioma inicial. O seletor original alterna PT-BR/English imediatamente no login e no shell; Settings também oferece o seletor. Idioma, DARK/LIGHT, densidade, FULL/REDUCED/OFF e ícones animados são lembrados localmente em `~/.byx-mvp-demo/presentation.properties`. Esse arquivo contém somente preferências visuais: nenhuma senha, token, perfil operacional ou endereço de servidor. Arquivos inválidos têm fallback seguro. Para remover a DEMO, feche-a, apague a pasta extraída e, opcionalmente, somente essa pasta de preferências.

## Interface original

AuthLayout/BrandPanel, ByxShell, rail/topbar/status dock, avatar/menu, TradingDesk, MarketsPage, SettingsScreen, HomeScreen, componentes de ajuda, fontes, ícones, tokens, temas e motion vêm da apresentação original. A composição agora usa LocaleView e ViewTransitionService, cache de views e guarda de alterações não salvas. Busca e páginas mostram títulos de produto, sem IDs internos. Home, posições, histórico, ordens indisponíveis e ajuda usam componentes aprovados e fixtures locais.

Veja a [comparação visual](docs/ALPHA2-VISUAL-COMPARISON.md). Referência de código: `828492af7b383083dbd6b749623876c188182db5`. Referência visual local disponível: snapshots reais de Scene JavaFX no Mac, aprovados no pacote C2 em 2026-10-09. Não se declara equivalência com uma versão Mac posterior não fornecida. A pré-release é para avaliação do proprietário; sua aprovação visual humana da Alpha 2 ainda está pendente.

## Qualificação e limitações

A publicação exige build, testes unitários, testes da UI pelo JAR distribuído, auditoria e testes do EXE com runtime incorporado, janela, navegação, idioma e persistência. Relatórios diferenciam screenshots **JAR** e **EXE**, testes automáticos e UAT humana. Os nove cenários de resolução/scaling em unit tests são modelos de geometria, não nove configurações físicas testadas. A matriz física completa, desconexão física da Internet e instalação de Setup não foram executadas. Ausência de endpoints é observação amostrada, complementada pela auditoria de composição sem backend.

A [Alpha 1](https://github.com/ChamberUS/DEV/releases/tag/demo-windows-v0.1.0-alpha.1) permanece disponível para comparação. A branch Alpha 2 não altera `main` nem a release anterior.

## Feedback e reprodução

Reporte problemas em [GitHub Issues](https://github.com/ChamberUS/DEV/issues/new), com versão, resolução/scaling, idioma, passos e comportamento esperado. Não envie credenciais privadas ou arquivos pessoais. Não existe telemetria obrigatória.

[Build/release](docs/BUILD-RELEASE.md) · [QA Alpha 2](docs/ALPHA2-QA.md) · [Isolamento](docs/SECURITY-ARCHITECTURE.md) · [Licenças](THIRD-PARTY-NOTICES.md). Dependências e fontes têm SHA-256 em `DEPENDENCIES.json`, `SOURCE-PROVENANCE.json` e `SOURCE-MANIFEST.json`.