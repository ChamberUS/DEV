# byx-local-service

Serviço operacional local do BYX (fundação V2.1A). Responde `health`, `version`, `capabilities` e, desde a V2.1B, o mercado PÚBLICO ETHUSDT (USDⓈ-M) por operações tipadas `market.*`; não serve dados de conta, notificações privadas nem operações administrativas, e não aceita host, URL, caminho ou símbolo do cliente.

- Arquitetura, ameaças, achados do login, limites e riscos residuais: [`docs/SECURITY_FOUNDATION.md`](docs/SECURITY_FOUNDATION.md).
- Executar: `./run-service.sh` (socket Unix privado em `~/.byx-local-service/run`, sem porta de rede, sem root, sem instalação global).
- Testar: `mvn -o test` (testes dinâmicos de negação).
- Stack: Java 21, `java.nio` (socket Unix), Jackson (parser estrito). Sem framework.

- Identidade do app (V2.1D): empacotado e assinado (`byx-packaging/`), o serviço deriva do PRÓPRIO código assinado o modo `packaged_verified` e só aceita o app BYX-MVP verificado pelo kernel; no IDE/Maven o modo é `development_unverified` (sem verificação, sem capacidade privada). Ver `docs/APP_IDENTITY.md`.
