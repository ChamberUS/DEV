# Qualificação Alpha 2

A Alpha 1 tinha Stage normal e resize habilitado; o Scene de 1440×900, mais decoração, extrapolava a área útil no scaling de 150%, e mínimos1100×720 impediam redução suficiente. O diagnóstico real por UIAutomation observou janela2182×1406 física em área útil1920×1008, parcialmente fora da tela. O launcher não impõe fullscreen. WindowPolicy usa Screen.visualBounds lógicos uma vez, antes de show, com mínimos adaptados; as rotas não alteram Stage.

PT-BR não funcionava porque DemoApp não criava LocaleView(scene); textos originais fixos e novos textos fora da composição não participavam de alterações. O port conecta esse observador original, catálogos DEMO complementares e proteção de credenciais/IDs técnicos. O tradutor genérico também podia transformar o placeholder `teste-byx`; um teste de regressão cobre esse caso. Campos do usuário e dados de autoridade nunca são traduzidos.

## Gates reproduzíveis

- Unitários: contrato auth/demo/fixtures/ausência de módulos privilegiados; geometria de área útil; catálogos PT/EN e identificadores; persistência permitida e fallback inválido.
- UI pelo JAR distribuído: português inicial, troca nos principais views, inputs preservados, navegação, Settings, temas/motion/densidade, cache/transições/listeners, Stage/scroll, logout/relogin e reabertura com preferências.
- EXE: runtime e DLLs do próprio pacote, ausência amostrada de TCP/UDP/subprocessos operacionais, fechar/reabrir sem Java externo.
- UIAutomation do EXE: login errado/correto, janela/maximizar/restaurar/minimizar/mover/redimensionar, principais páginas, PT/EN, temas, preferências/reabertura e screenshots exclusivos da janela pertencente ao teste.
- Auditoria: credenciais privadas, artefatos locais, classes fora do allowlist, dependências SHA-256, proveniência de fontes, runtime/licenças e payload proibido.

Resultados efetivamente executados ficam nos relatórios do workflow do SHA específico; contagens antigas não são evidência Alpha 2. Os scripts falham ao reprovar gates obrigatórios. O teste adicional de caminho `Ω` pode registrar a limitação do launcher separadamente.

## Evidência e escopo

Capturas JAR são snapshots da Scene usando o JAR empacotado e driver de teste externo. Capturas EXE vêm do retângulo da janela nativa BYX-MVP.exe via UIAutomation; nenhuma captura do desktop inteiro é necessária. Comparações Mac usam somente snapshots reais aprovados e identificados do pacote C2. Não são screenshots da versão Mac atual desconhecida.

Unit tests modelam1366×768,1920×1080,2560×1440 em100/125/150%, com área reservada para taskbar. Isso não constitui teste de nove configurações físicas. Windows local e runner reportam o ambiente real. UAT humana da Alpha 2, matriz física completa, máquina de consumidor limpa, desconexão física da Internet e Setup permanecem pendentes. Sem alteração de políticas de execução ou proteção.

## Preservação

Alpha 2 parte exclusivamente do histórico sanitizado Alpha 1. Worktrees anteriores, imagens aprovadas, relatórios QA, SQLite, pesquisas ETHUSDT, Service/Keychain/macOS e V4-D3 permanecem preservados. Preferências da DEMO não participam de autorização. Gate permanente: DEMO_USER/USER local; real key não autorizada; TX desativadas; broadcasts/fundos/carteiras reais0; LAN e identidade Service Windows não qualificados; Service autenticado desativado; pesquisa intocada; macOS fora do escopo.