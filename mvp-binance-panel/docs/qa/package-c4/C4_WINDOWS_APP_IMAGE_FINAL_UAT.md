# C4.1-P — final UAT closeout

2026-10-09. **C4_1_P_FINALIZED**. **C4_WINDOWS_APP_IMAGE_REBUILD_AND_RUNTIME_VERIFIED** for local QA public DEFAULT only. **C4_WINDOWS_FULL_COMPATIBILITY_NOT_YET_QUALIFIED**.

## Provenance and evidence

Repository `C:\src\DEV`, branch `feature/byx-windows-readiness-v1`. Audited source/script checkpoint: `54e1406e04cc977026fe1f7a401eb1d81df50bd1`; previous published checkpoint: `a8e1eaab044777215e24425c5688ec9ac1765a1d`. At closeout start, tracked tree/index clean, 591 untracked files. The rebuild introduced no tracked changes. Generated executables/runtimes remain outside Git. Existing private/unrelated evidence was inventoried and preserved; no clean/reset/deletion.

Owner-built and owner-executed image:

`C:\src\BYX-artifacts\windows-c4-1-p\finalization-20261009-230319\rebuild\image\BYX-MVP`

Artifact root is the directory above `image`. Its `provenance.json` records source HEAD `54e1406…`, DEFAULT and wallet DISABLED. All **434 staged source/POM/resource hashes** match current unchanged repository inputs. Read-only evidence inspection confirms:

- `maven-package.log`: **362 source files**, release 21; **BUILD SUCCESS**. Tests skipped for packaging, not reclassified as passing.
- `jpackage.log`: jlink invocation and **Succeeded in building Windows Application Image package**, 23:23:51 local time.
- Java/jpackage **21.0.12.1**, Temurin x64; Maven **3.9.9**; JavaFX **21.0.5**.
- **261 image files**, 140,643,235 bytes; zero mismatches against the rebuild's saved SHA-256 manifest, and no additional image files.
- Launcher Authenticode **NotSigned**, independently rechecked; same launcher SHA-256 as the initial image.

The owner states the reviewed script ran with **Process-scoped RemoteSigned**, with no requested persistent policy change, and confirms runtime/public functionality of the rebuilt image. These are owner-confirmed execution/UAT facts; this task did not rerun the script or application. Current diagnostic shell's effective execution policy reads **Restricted**. No policy write was performed here. PowerShell script policy is distinct from Smart App Control, whose prior native IPC harness block remains unresolved.

## Qualification boundaries

| Property | Evidence / result |
| --- | --- |
| Maven compilation | Artifact log independently inspected: success, 362 sources |
| Packaging procedure | Committed script, dependency hash lock, source manifest and successful owner rebuild logs |
| Runtime execution | Owner confirms new image execution; original image had independently measured non-admin smoke |
| Public UI | Owner confirms expected public behavior; earlier Login/EN/PT-BR/Help/FAQ/focus/window/close evidence remains documented |
| Security qualification | NOT COMPLETE: no authenticated Windows IPC, live application identity, protected pairing or Service authorization proof |
| Production readiness | NOT QUALIFIED: unsigned launcher, generic icon, IPC/identity and platform gates outstanding |

The package uses image-local runtime/JARs; no external Maven, Java or development working directory is needed to launch. Clean-machine/empty JavaFX cache remains a separate qualification item. No new comprehensive resolution/theme/private-screen coverage is inferred from owner's public UAT. Earlier 1920×1080/150% restored-window taskbar overlap and FAQ title issue remain recorded, not silently fixed.

## Hash comparison and reproducibility limits

Rebuild image manifest SHA-256:

`500FE70747117423FD4CD8C237C7F1DC72CEFB0964A334837F53F0E9F9415876`

Launcher `BYX-MVP.exe` SHA-256:

`02C0CE6B6D81F2A79C70280396DCEC5497922D446AFB9FDB1B59DFF329533406`

Rebuild application JAR SHA-256:

`623939558C40DB1827C6CC1FC438E69CD11673734DE99251A06909981B8043F8`

Comparison with original `run-20261009-224800`: **260 image files byte-identical; application JAR differs as a whole file**. Both JARs contain 833 entries; every corresponding uncompressed entry has identical SHA-256. This comparison does not identify the differing ZIP metadata. The procedure is successfully reproduced; the complete images are **not bit-for-bit identical**.

Read-only closeout evidence is stored in the new external `C:\src\BYX-artifacts\windows-c4-1-p\closeout-*` directory identified by local `closeout-current.txt`: `rebuild-verification.json`, `jar-content-comparison.json`, source/untracked preservation and checkpoint receipts. Full image manifest and build logs stay in the rebuild root; no raw logs, user identifiers, binaries or private evidence are staged.

## Build and launch procedure

The successful script inputs were the command documented in [checkpoint report](windows-app-image-checkpoint.md), with output root `finalization-20261009-230319\rebuild`. For another authorized build, select a **new non-existing external output directory**. The script refuses overwriting the UAT-approved images. This closeout does not request any execution-policy change.

Launch the rebuilt image normally in Explorer by double-clicking `BYX-MVP.exe`, keeping `app` and `runtime` alongside it. Equivalent normal PowerShell launch:

```powershell
Start-Process -FilePath 'C:\src\BYX-artifacts\windows-c4-1-p\finalization-20261009-230319\rebuild\image\BYX-MVP\BYX-MVP.exe'
```

The generic Explorer icon is the documented default **JavaApp.ico** in jpackage's log. No approved BYX Windows `.ico` was supplied; no logo was invented. Approved UI branding is preserved. Signing and custom icon remain future separately reviewed work, not reasons to relabel public UAT as IPC/security success.

## Preservation and closeout

No shared Java, POM, packaging script, dependency or macOS verifier changes in this task; documentation-only checkpoint permitted after exact-path staged review, without push/merge. Existing Windows results stay **1008 PASS, 0 FAIL, 23 ERROR, 3 SKIP**. No full regression rerun, no fixture weakening or artificial skips. Owner-reported macOS baseline remains **998 PASS, 0 FAIL, 0 ERROR, 25 SKIP, 181 native UI checks**; not independently rerun here.

The historical PowerShell rebuild blocker in the preceding reports is superseded by the owner rebuild evidence, not erased. Windows Service access remains honestly unavailable. REAL USER KEY = NOT AUTHORIZED; REAL TX = DISABLED; BROADCASTS = 0; REAL FUNDS = 0; REAL USER WALLETS = 0; ETHUSDT RESEARCH = UNTOUCHED.
