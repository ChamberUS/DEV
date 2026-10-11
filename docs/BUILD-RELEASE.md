# Build e release Windows

JDK 21 x64 com jpackage, Maven e PowerShell são ferramentas do **build**, não requisitos do testador. Use um checkout limpo da branch DEMO:

```powershell
./scripts/build-demo-windows.ps1 -Jdk $env:JAVA_HOME -Output "$env:TEMP/BYX-demo-build-novo"
./scripts/audit-demo.ps1 -Image "$env:TEMP/BYX-demo-build-novo/image/BYX-MVP" -Report "$env:TEMP/BYX-demo-build-novo/audit.json"
./scripts/test-demo-launcher.ps1 -Zip "$env:TEMP/BYX-demo-build-novo/BYX-MVP-Demo-Windows-x64-Portable.zip" -Destination "$env:TEMP/QA BYX spaces [alpha]" -Report "$env:TEMP/BYX-demo-build-novo/launcher-qa.json"
./scripts/test-demo-ui.ps1 -Image "$env:TEMP/BYX-demo-build-novo/image/BYX-MVP" -Evidence "$env:TEMP/BYX-demo-build-novo/ui"
```

Todos os destinos devem ser novos. Nenhuma política de execução é alterada. Em máquinas gerenciadas, utilize a forma de execução permitida pelo proprietário da política. Build online usa cache Maven próprio na saída. `-Offline` exige dependências previamente disponíveis; nunca significa teste físico sem Internet. Plugins e runtime dependencies têm versões fixadas; os 14 JARs são validados por SHA-256 após resolução. O runtime é jlink Java 21, sem Java externo. Os hashes de binários jpackage/ZIP podem variar entre builds/toolchains; não se promete reprodução byte a byte.

Instalador opcional: `-IncludeInstaller -Wix caminho-para-WiX3`. WiX oficial isolado: https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip . SHA-256 observado e fixado do ZIP: `6ac824e1642d6f7277d0ed7ea09411a508f6116ba6fae0aa5f2c7daa2ff43d31`. Nenhuma instalação global é necessária. Gerar um Setup não o qualifica; instalação/desinstalação e política de execução precisam de testes próprios antes de anexá-lo.

O workflow usa push **apenas** em `feature/byx-mvp-demo-windows-x64`, actions fixadas por SHA, token `contents: read`, sem produção/secrets, sem `pull_request_target`, sem publicação automática. Não há dependência de `workflow_dispatch`: [GitHub exige o workflow na branch padrão para esse gatilho](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow). A primeira qualificação funciona pelo push da própria branch DEMO. Não se altera `main`.

O smoke test nativo extrai o ZIP em caminho com espaços e colchetes; um segundo teste separado registra a compatibilidade Unicode, remove Java externo do PATH do processo de teste, confere janela JavaFX, módulos jvm.dll/glass.dll dentro do pacote, amostra endpoints TCP/UDP e subprocessos, fecha normalmente e reabre. Não automatiza a UAT humana do EXE. A automação UI separada usa o JAR de `app/` e classes de teste externas com o JDK do runner; não deve ser descrita como uso do runtime incorporado ou observação humana.

Release manual: verificar SHA público da branch, conclusão do workflow no mesmo SHA, audit PASS, launcher PASS, unit/UI PASS, licenças e ausência de payload privado. Preparar release notes com limitações NOT_EXECUTED. Se somente portátil estiver qualificado, publicar pré-release PORTABLE ONLY com ZIP e SHA256SUMS, tag disponível `demo-windows-v0.1.0-alpha.1`, título `BYX-MVP DEMO — Windows Alpha 1`. Não incluir Setup anterior. Baixar novamente os assets públicos, comparar hashes, conferir inventário/launcher extraído e verificar tag/commit remoto. Se qualquer gate necessário falhar, preservar artefatos de CI e não criar release.

Documentação jpackage: https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html .
Teste nativo adicional (PowerShell Windows com UIAutomationClient): `./scripts/test-demo-native-ui.ps1 -Executable caminho/BYX-MVP.exe -Report caminho/native-ui-qa.json`. Só manipula a janela DEMO; verifica login errado/correto, USER, navegação, preferências e logout. Não é UAT humana. No runner é uma etapa dependente de desktop e seu resultado deve ser lido no JSON; `continue-on-error` não converte falha em PASS.
