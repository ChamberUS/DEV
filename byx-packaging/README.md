# byx-packaging

Empacota o painel e o serviço local (helper app-like aninhado, perfil próprio) em **`BYX-MVP.app`** (runtime Java embutido, Hardened Runtime, assinatura de desenvolvimento local) e prova a identidade do peer. Decisões e riscos: [`../byx-local-service/docs/APP_IDENTITY.md`](../byx-local-service/docs/APP_IDENTITY.md).

- `identity.env`: fonte única dos identificadores (`com.buynnex.byx`, `com.buynnex.byx.service`; **definitivos**, domínio buynnex.com) e da versão do pacote.
- `entitlements/`: painel só `com.apple.security.cs.allow-jit` (a JVM não inicia sem ele sob Hardened Runtime); helper do serviço, com perfil: `application-identifier` + `team-identifier` + `allow-jit` (modelo em `service.keychain.entitlements.template`; valores lidos do perfil).
- `provisioning/`: `provision.sh` gera o perfil de DESENVOLVIMENTO (Personal Team, 7 dias; ação persistente na conta Apple). Ver o README da pasta.
- `set-final-ids.sh`: troca os IDs em um só passo (ensaio por padrão).
- `build-app.sh`: jpackage + nativos pré-extraídos e assinados + assinatura de dentro para fora com requisito designado explícito. **Não roda a suíte**: rode `mvn package` no painel e no serviço antes. Usa a identidade *Apple Development* do chaveiro (ou `BYX_SIGN_IDENTITY`); não é Developer ID e não é notarizado. `--chain-profile production-disabled|local-qa` escolhe o perfil da chain pública NO BUILD (padrão `production-disabled`: NOT_CONFIGURED, sem rede; `local-qa`: nó descartável em loopback; desconhecido: falha); reconstrói só o JAR do serviço (`-DskipTests`) e não afeta identidade, Team ID, assinatura nem Keychain.
- `verify-secret-store.sh`: matriz real do canário do cofre (A–F + controle positivo + limpeza), com `build-app.sh --with-canary-harness --embedded-profile …`.
- `verify-identity.sh [--market]`: 29 verificações (assinatura, runtime embutido, entitlements, peer legítimo, seis ataques do mesmo usuário com o `pairing.token` real, controle, reinício, mercado público). Usa homes temporários; não toca o home real, o Keychain nem a captura.
- `evidence/`: saída da última verificação.

```bash
./build-app.sh
./verify-identity.sh --market
```

Executar o serviço empacotado (sem root, sem porta): `build/BYX-MVP.app/Contents/Helpers/byx-local-service.app/Contents/MacOS/byx-local-service`. Sonda sem JavaFX, com a identidade assinada do app: `build/BYX-MVP.app/Contents/MacOS/BYX-MVP --probe-service [--market]`.
No IDE/Maven continua valendo `run.sh`/`run-service.sh` (modo `development_unverified`, nunca com capacidade privada).
