# byx-packaging

Empacota o painel e o serviço local em **`BYX-MVP.app`** (runtime Java embutido, Hardened Runtime, assinatura de desenvolvimento local) e prova a identidade do peer. Decisões e riscos: [`../byx-local-service/docs/APP_IDENTITY.md`](../byx-local-service/docs/APP_IDENTITY.md).

- `identity.env`: fonte única dos identificadores (`network.byx.mvp`, `network.byx.mvp.service`; **proposta**, ver o doc) e da versão do pacote.
- `entitlements/`: **só** `com.apple.security.cs.allow-jit` (a JVM não inicia sem ele sob Hardened Runtime).
- `build-app.sh`: jpackage + nativos pré-extraídos e assinados + assinatura de dentro para fora com requisito designado explícito. **Não roda a suíte**: rode `mvn package` no painel e no serviço antes. Usa a identidade *Apple Development* do chaveiro (ou `BYX_SIGN_IDENTITY`); não é Developer ID e não é notarizado.
- `verify-identity.sh [--market]`: 27 verificações (assinatura, runtime embutido, entitlements, peer legítimo, seis ataques do mesmo usuário com o `pairing.token` real, controle, reinício, mercado público). Usa homes temporários; não toca o home real, o Keychain nem a captura.
- `evidence/`: saída da última verificação.

```bash
./build-app.sh
./verify-identity.sh --market
```

Executar o serviço empacotado (sem root, sem porta): `build/BYX-MVP.app/Contents/MacOS/byx-local-service`. Sonda sem JavaFX, com a identidade assinada do app: `build/BYX-MVP.app/Contents/MacOS/BYX-MVP --probe-service [--market]`.
No IDE/Maven continua valendo `run.sh`/`run-service.sh` (modo `development_unverified`, nunca com capacidade privada).
