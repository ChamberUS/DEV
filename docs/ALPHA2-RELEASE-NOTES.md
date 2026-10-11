# BYX-MVP DEMO — Windows Alpha 2 — UI & PT-BR

Pré-release **PORTABLE ONLY**, para avaliação visual do proprietário. A Alpha 1 continua disponível e `main` permanece preservada.

- Janela normal centralizada e redimensionável, dentro da área útil em pixels lógicos JavaFX. Scaling e decoração nativa são considerados; login/logout e navegação preservam a geometria. Painéis extensos oferecem rolagem.
- Português (Brasil) por padrão, English alternativo e troca imediata usando o LocaleView original. Credenciais, ativos, rotas e entradas do usuário permanecem intactos.
- Idioma, tema DARK/LIGHT, densidade, FULL/REDUCED/OFF e ícones lembrados somente no perfil visual local da DEMO. Nenhuma senha, token ou capacidade operacional é persistida.
- Composição original recuperada: AuthLayout/BrandPanel, shell/rail/topbar/status dock, avatar/UserMenu, busca com títulos corretos, transições/cache de views, TradingDesk/Markets/Settings e Home/FAQ/ajuda originais. Páginas ilustrativas usam componentes V2; ordens reais permanecem indisponíveis.
- Controles de janela Windows e atalhos Ctrl são adaptações nativas. Paletas, fontes, ícones e organização do conteúdo vêm da apresentação aprovada.

**Credenciais públicas:** `teste-byx` / `byx123123`, perfil `DEMO_USER / USER`. Todos os dados são simulados. Não há servidor, conta real, Service, signer, custody, carteiras, transações, broadcasts ou pesquisa ETHUSDT executável.

Baixe ZIP e SHA256SUMS da mesma release, verifique o SHA-256, extraia todos os arquivos e abra `BYX-MVP.exe`, mantendo `app/` e `runtime/`. Java já está incorporado. Use uma pasta ASCII: espaços/colchetes são testados; caminhos com `Ω` continuam não qualificados por limitação do launcher.

**Não assinado.** Algumas políticas Windows podem bloquear execução. Mantenha Smart App Control e Defender ativos e reporte a mensagem de bloqueio; não desative proteções.

A referência visual disponível consiste em snapshots reais de Scene JavaFX no Mac aprovados no pacote C2; não é a versão Mac atual desconhecida. A fidelidade completa ainda depende da revisão visual do proprietário. Esta Alpha 2 não recebeu UAT humana antecipada. A matriz física completa de resoluções/scaling, máquina Windows de consumidor limpa, desconexão física da Internet e Setup não foram executados. Os nove cenários de geometria automatizados são modelos, não nove monitores físicos testados.

O workflow do commit publicado reúne build, unit tests, UI pelo JAR distribuído, auditoria e automação do EXE real com runtime incorporado, controles de janela, PT/EN, preferências e reabertura. Relatórios e imagens distinguem EXE, JAR e UAT humana. No Windows local, desktop ocupado limitou a automação nativa; o runner dedicado fornece a qualificação final. Ausência de endpoints é observação amostrada complementada por composição sem módulos operacionais.

Envie feedback por GitHub Issues com versão Windows, resolução/scaling, idioma e passos. Não envie dados privados. Compare com a Alpha 1, disponível separadamente. Veja README e documentação de QA/comparação no commit desta branch.