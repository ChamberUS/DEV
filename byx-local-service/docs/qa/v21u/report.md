# BYX-MVP V2.1U — Legacy Signer Cleanup + Runtime/Packaging Hardening

Status: READY_FOR_UI_UX_FINAL_CLOSURE. All required final gates passed. Qualification time: 2026-10-08T13:38:26.257649+00:00.

The production stdio SignerClient was removed. CustodyClient and WalletLifecycle share the unchanged pure SignedResponseVerifier; the complete old transport remains a test-only regression fixture and is excluded from packaged QA and DEFAULT jars. No UnixSystem import or construction remains in production or test code; the regression guard retains its literal name. No jdk.security.auth module is needed by the embedded runtime.

The Service checks its loaded JAR against its live audit-token code identity and its exact signed nested Service bundle before acquiring authority. The parent application, helper bundles and executables remain subject to strict seals, Apple identity and Team requirements. The launcher and Go helper share a filesystem policy for critical executable, runtime, module and classpath paths and their ancestors. It rejects symlinks, unauthorized owners, group/other write permissions and mutating ACLs on those paths. Strict seals and Hardened Runtime library validation also protect native code. The final packaging audit checked safe owner/mode metadata on every regular file and directory; only standard legal-notice symlinks remain, and they resolve inside the bundle. Launcher main/classpath/identity are compiled constants; custody/auth/canary QA launchers reject injected environments, and the other launchers scrub them without a 512-entry cap. Argument overflow fails before starting the JVM.

Provisioning validation binds the exact selected signing certificate SHA-1 to its Team, exact app identifier/prefix, profile expiry and authorized certificate membership. Native OS acceptance is also exercised; profile inspection alone is not acceptance. QA launchers use the hardened native launcher. Auth/migration/canary fixture entrypoints and the legacy hash oracle moved to test scope; DEFAULT must contain none of them. DEFAULT remains PRODUCTION_DISABLED, with disabled wallet capability and transaction mutation gate. A sole --diagnostics argument executes a closed read-only path before authentication, storage, custody, feeds or runtime directory creation.

## Requirement matrix

| Requirement | Implementation | Evidence |
| --- | --- | --- |
| Retire legacy transport without alternate signing authority | main SignerClient removed; pure verifier; test-only fixture and packaging exclusion | legacy-retirement-tests.json; service-tests.json; runtime-evidence.json |
| No UnixSystem or runtime auth-module dependency | native identity in test fixture; production verifier uses no process or identity | source audit; actual embedded JVM diagnostics and sealed module manifest; default-package-evidence.json |
| Exact Service/helper origin and code identity | CustodyClient verifyServiceOrigin and strict parent/helper requirements; native live audit-token checks retained | origin-evidence.json; runtime-evidence.json; security-evidence.json |
| Filesystem and installation assumptions | shared install_policy.h; Java source JAR metadata; owner/mode/symlink/ACL checks | install-unit-tests.json; Go tests; runtime-evidence.json; origin-evidence.json |
| Provisioning diagnostics | resolve-signing-identity.py; validate-provisioning.py integrated into builds | provisioning-diagnostics.json; provisioning unit tests; qualified native build/start |
| QA/DEFAULT separation | six fixture sources moved to test; explicit stale-bytecode jar exclusions; narrowly selected canary jar | Service isolation guards; actual qualified DEFAULT bytecode and launch checks |
| Startup and shutdown/fencing | native hardened launch; actual signed synthetic lifecycle and external audit-token absence | fencing-evidence.json; lifecycle-evidence.json; panel-native-evidence.json |
| Generation/operation binding and fail closed | custody and lifecycle wire/receipt architecture retained | Service/Go tests; security, fencing, lifecycle and anomaly matrices |
| Panel remains public-only, no direct custody/Keychain authority | unchanged public DTO and Service composition; signed Panel negative | Panel tests; origin-evidence.json; panel-native-evidence.json |
| Synthetic lifecycle remains functional | current packaged native create/reconcile/sign/delete/crash recovery | lifecycle-evidence.json; panel-native-evidence.json |
| DEFAULT remains production-disabled | sealed chain/capability resources and closed diagnostics before auth | default-runtime-evidence.json; default-package-evidence.json |
| Preserve capture and pre-existing work | no capture interventions or discard/push; layered changes | capture-preservation.json; scope audit |

## Limits and interpretation

This qualifies a locally signed macOS development artifact. The installation policy trusts the owner UID. Protection against an adversarial owner UID requires a protected root-owned deployment; the writable development checkout does not establish immunity to same-UID seal/load TOCTOU. Profiles have finite expiry: the current Service profile expires 2026-10-13T16:49:55Z and signer QA profile 2026-10-15T01:03:43Z.

DEFAULT qualification starts only its real native read-only diagnostics path. It does not start normal production authentication or touch real user anchors, wallets or keys. Crash tests establish process-interruption recovery; they do not simulate physical power loss. Full Panel tests retain the opt-in capture observation skip; Go skips the subprocess-only TestProcessHarness entrypoint in the parent suite; SignerClientTest invokes that entrypoint as a child for the preserved legacy regression cases, alongside separate native custody process coverage. These limits do not authorize production signing or deployment.

REAL USER KEY = NOT AUTHORIZED. REAL TX = DISABLED. BROADCASTS = 0. REAL FUNDS = 0. REAL USER WALLETS = 0. ETHUSDT RESEARCH = UNTOUCHED.

Intermediate failed attempts are retained. Darwin ACL semantics caused the initial native smoke failure and were corrected with descriptor-based ACL presence and valid Darwin enumeration handling. A diagnostics placement failed the unchanged Service composition guard; constants moved into a read-only class and the full suite passed. The first long lifecycle run rejected a quote created by a fixed test clock; the synthetic confirmed fixture now uses the current clock, preserving the actual expiration validation. The full fresh lifecycle run passed all 178 checks.

Additional failures retained: origin probe assumptions were corrected for stripped Java commands, guard ordering and exact refusal before authority; repeated foreign-helper probes now use unique invocations and clean their exact owned socket after verified death. Auth QA verification uses clean normal-launch environments and requires hostile injection to exit74 before JVM; copied bundle probes use canonical /private/tmp paths. Canary EOF and framing response nondeterminism were corrected and requalified without changing signing acceptance or Keychain permissions.

## Final qualification

| Gate | PASS | FAIL | SKIP |
| --- | ---: | ---: | ---: |
| service-tests | 473 | 0 | 0 |
| panel-tests | 723 | 0 | 1 |
| go-default-tests | 37 | 0 | 1 |
| go-qa-tests | 64 | 0 | 1 |
| runtime-evidence | 126 | 0 | 0 |
| origin-evidence | 12 | 0 | 0 |
| generic-jvm-origin-evidence | 1 | 0 | 0 |
| default-package-evidence | 26 | 0 | 0 |
| canary-fixtures-evidence | 8 | 0 | 0 |
| auth-fixtures-evidence | 78 | 0 | 0 |
| migration-fixtures-evidence | 62 | 0 | 0 |
| provisioning-unit-tests | 5 | 0 | 0 |
| lifecycle-evidence | 178 | 0 | 0 |
| panel-native-evidence | 42 | 0 | 0 |
| anomalies-evidence | 44 | 0 | 0 |
| default-runtime-evidence | 6 | 0 | 0 |
| security-evidence | 65 | 0 | 0 |
| fencing-evidence | 24 | 0 | 0 |

The native chain completed successfully on the latest signed artifact. No QA actors remained afterward. Both qualified applications passed the final explicit Apple identity/Team requirement and strict deep seal. The production Service JAR is identical in QA and DEFAULT, and the main source hashes did not drift after the independent verifier audit. The packaging metadata audit covered 1,319 regular files/directories in QA and 443 in DEFAULT; legal-notice symlinks resolve inside their respective bundles.

The additional generic-JVM test loads the approved sealed Service JAR from its correct installed location, but the live executable has a different code identity. It receives SIGNER_UNTRUSTED with no authority or helper child. This complements the same-Team wrong-origin and signed-Panel denial probes.

Capture supervisor 10657 and collector 96558 remained unchanged. The capture checkout is clean and a fresh metadata-only observation showed the open .part file growing. No captured contents were read or analyzed. All 33 other pre-existing tracked diffs and the Panel diffs remain byte-identical to preflight; original build-app additions remain present. No reset, clean, stash, rebase, commit or push was performed during V2.1U.

Every requirement in the matrix is satisfied for this qualified development scope. No unresolved security/design decision remains. The project stops at READY_FOR_UI_UX_FINAL_CLOSURE; no UI/UX work or production signing/deployment was started.
