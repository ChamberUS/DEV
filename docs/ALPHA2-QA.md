# Qualificação Alpha 2

A Alpha 1 tinha Stage normal e resize habilitado; o Scene de 1440×900, mais decoração, extrapolava a área útil no scaling de 150%, e os mínimos de 1100×720 impediam redução suficiente. O diagnóstico real por UIAutomation observou uma janela de 2182×1406 pixels físicos em uma área útil de 1920×1008, parcialmente fora da tela. O launcher não impõe fullscreen. WindowPolicy usa Screen.visualBounds lógicos uma vez, antes de show, com mínimos adaptados; as rotas não alteram Stage.

PT-BR não funcionava porque DemoApp não criava LocaleView(scene); textos originais fixos e novos textos fora da composição não participavam de alterações. O port conecta esse observador original, catálogos DEMO complementares e proteção de credenciais/IDs técnicos. O tradutor genérico também podia transformar o placeholder `teste-byx`; um teste de regressão cobre esse caso. Campos do usuário e dados de autoridade nunca são traduzidos.

## Regressão de bindings de idioma

O teste de alternância repetida encontrou um vazamento real no observador original. Após estabilizar CSS e layout, dez trocas de idioma fizeram a contagem subir de 5.303 para 5.403 bindings: cada ciclo EN → PT-BR retinha mais 20. A instrumentação confirmou propriedades de acessibilidade de `LabeledText` sem vínculo com a Scene. A diferença inicial causada pela criação tardia de skins foi medida separadamente e não explica esse crescimento contínuo.

A causa era a ordem de descarte em `LocaleView.detach`: restaurar o texto canônico de um controle enquanto seu observador de filhos ainda estava ativo podia fazer a skin criar novos filhos. Esses nós desconectados recebiam novos bindings depois que o descarte dos filhos originais já havia terminado.

A correção ocorre em duas fases. Primeiro captura toda a subárvore e remove todos os seus observadores; depois restaura as fontes canônicas e libera as propriedades pertencentes aos nós capturados. Inputs, dados, handlers e traduções permanecem preservados. O teste mantém igualdade exata da contagem após um ciclo de estabilização e verifica que o encerramento libera todos os bindings da Scene.

O novo JAR empacotado executado localmente no UI06 passou 79 verificações, com zero falhas. Após estabilizar CSS e layout, a contagem permaneceu exatamente em 4.509 bindings durante as dez trocas; nenhum binding de nó desconectado foi retido. O encerramento também confirmou a liberação completa dos bindings da Scene. A qualificação de publicação ainda depende da execução do workflow no SHA final; esse resultado local não substitui a validação de CI.

## Gates reproduzíveis

- Unitários: contrato auth/demo/fixtures/ausência de módulos privilegiados; geometria de área útil; catálogos PT/EN e identificadores; persistência permitida e fallback inválido.
- UI pelo JAR distribuído: português inicial, troca nos principais views, inputs preservados, navegação, Settings, temas/motion/densidade, cache/transições/listeners, Stage/scroll, logout/relogin e reabertura com preferências.
- EXE: runtime e DLLs do próprio pacote, ausência amostrada de TCP/UDP/subprocessos operacionais, fechar/reabrir sem Java externo.
- UIAutomation do EXE: login errado/correto, janela/maximizar/restaurar/minimizar/mover/redimensionar, principais páginas, PT/EN, temas, preferências/reabertura e screenshots exclusivos da janela pertencente ao teste.
- Auditoria: credenciais privadas, artefatos locais, classes fora do allowlist, dependências SHA-256, proveniência de fontes, runtime/licenças e payload proibido.

Resultados efetivamente executados ficam nos relatórios do workflow do SHA específico; contagens antigas não são evidência Alpha 2. Os scripts falham ao reprovar gates obrigatórios. O teste adicional de caminho `Ω` pode registrar a limitação do launcher separadamente.

## Provider nativo e seletor de idioma

O relatório UIA do EXE mostrou um ComboBox visível com `Name` vazio. O provider Windows do JavaFX 21 expõe o texto desses controles por `ValuePattern`; `Name` usa somente `LABELED_BY`. A automação identifica o seletor por seu valor acessível traduzido e continua exigindo que a tela mude efetivamente entre PT-BR e EN. Nenhum valor de campo de senha ou credencial é lido para esse diagnóstico. Referências primárias: [WinAccessible](https://raw.githubusercontent.com/openjdk/jfx/jfx21/modules/javafx.graphics/src/main/java/com/sun/glass/ui/win/WinAccessible.java) e [ComboBox](https://raw.githubusercontent.com/openjdk/jfx/jfx21/modules/javafx.controls/src/main/java/javafx/scene/control/ComboBox.java).

## Evidência e escopo

Capturas JAR são snapshots da Scene usando o JAR empacotado e driver de teste externo. Capturas EXE vêm do retângulo da janela nativa BYX-MVP.exe via UIAutomation; nenhuma captura do desktop inteiro é necessária. Comparações Mac usam somente snapshots reais aprovados e identificados do pacote C2. Não são screenshots da versão Mac atual desconhecida.

Os testes unitários modelam 1366×768, 1920×1080 e 2560×1440 nas escalas de 100%, 125% e 150%, com área reservada para a barra de tarefas. Isso não constitui teste de nove configurações físicas. Windows local e runner reportam o ambiente real. UAT humana da Alpha 2, matriz física completa, máquina de consumidor limpa, desconexão física da Internet e Setup permanecem pendentes. Sem alteração de políticas de execução ou proteção.

## Preservação

Alpha 2 parte exclusivamente do histórico sanitizado Alpha 1. Worktrees anteriores, imagens aprovadas, relatórios QA, SQLite, pesquisas ETHUSDT, Service/Keychain/macOS e V4-D3 permanecem preservados. Preferências da DEMO não participam de autorização. Gate permanente: DEMO_USER/USER local; real key não autorizada; TX desativadas; broadcasts/fundos/carteiras reais: 0; LAN e identidade Service Windows não qualificados; Service autenticado desativado; pesquisa intocada; macOS fora do escopo.
