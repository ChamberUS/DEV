# C1/C2 publication audit — 2026-10-09

Owner-authorized publication after approved human UAT. Repository root `/Users/buynnex-corp/dev`, application `mvp-binance-panel`, remote `https://github.com/ChamberUS/DEV.git`, branch/upstream `feature/byx-ui-redesign-v1` / `origin/feature/byx-ui-redesign-v1`. Local and remote preflight HEAD both `2dd12ca65072d46599449a25932ddce881d14746`. Package B `6a2a73d` and Package A `e357a07` remain ancestors.

## Faithful separation

The C2 entry source archive matches all 611 entries in the C1 tested-source manifest (including unchanged POM). C1 source blobs are staged directly from that archive, without replacing the final C2 worktree. C2 is then staged from the approved final worktree. This preserves two qualified source states and shared-file history. Commit messages: `feat(panel): add complete EN and PT-BR localization`; `feat(panel): implement native dark and light themes`. Exact file allowlists: [C1_C2_PUBLICATION_FILES.json](C1_C2_PUBLICATION_FILES.json).

## Candidate and test provenance

C1: 894 PASS, 0 FAIL, 0 ERROR, 1 expected SKIP; 454 native checks; 9 signed packaged smoke checks; 2,115 catalog keys per language. C2: 922 PASS, 0 FAIL, 0 ERROR, 1 expected SKIP; 11 signed packaged smoke checks; 1,487 bundled native flow checks; 482 bundled resource checks. Expected skip is the opt-in CaptureLiveObservationTest. These are existing qualification executions, not newly executed publication tests. No product source was changed during publication preparation, so a fresh regression was unnecessary.

All 627 final C2 source entries match tested hashes. Approved version `1.0.0-local-pkgc2-uat-20261009T172945Z`: ZIP SHA-256 `c8998f79ff65e079ebae09d6e2fcb9b53c07d9e49048241be98db19a2de7ba08`; Panel JAR `dbb646e4e1274582240ab7959d0c0f3a96e8943d2c39fe364d07701786fa6df9`; Service JAR `9520ddc0566645fe8bbd48f0f23d8b7bb79ec10f4d8406dbe445fbc916852225`. ZIP/JAR checksums independently rechecked; 691 packaged Panel classes and five theme resources match qualified output/source byte for byte. Both bundled Service JAR copies retain the approved Service hash. Final native-image hashes match the C1/C2 manifests.

## Security and scope

Explicit per-file staging only. Text files scanned for private keys, authentication token patterns and developer signing identities; sensitive contexts reviewed. Password literals in test harnesses belong exclusively to isolated synthetic QaContext fixtures; they are not real-user credentials. SigningKeyRef appears as an untranslated field name/test literal, not a stored key reference. UI labels referring to passwords/Keychain are presentation copy, not credentials or state. Native evidence uses synthetic authority/financial fixtures. No real API credentials, tokens, passwords, mnemonics, private keys, Keychain state, developer identities/certificates, provisioning secrets, raw ETHUSDT data, sockets, runtime state or application build outputs are selected.

All 3,069 protected non-Panel source files match entry hashes. Changes are confined to Panel source, tests and related QA documentation/evidence. Service, signer, capture sources, POM and packaging pipeline are unchanged. Unrelated iaos-web and other local work remain outside staging. Collector 54866 and supervisor 10657 remain active with their parent relationship preserved; verification used process metadata only, with no scientific content reads or signals.

## Accepted limitations

SYSTEM theme is unavailable. Language and theme preferences are session-only. C2 adds 13 Appearance keys to the preserved C1 catalog (2,128 total per language). Known DARK contrast exceptions, compact ellipsis, absent per-candle volume data and local development provisioning limitations remain documented in the implementation reports. This publishes approved source, not a distribution binary.

REAL USER KEY = NOT AUTHORIZED; REAL TX = DISABLED; BROADCASTS = 0; REAL FUNDS = 0; REAL USER WALLETS = 0; ETHUSDT RESEARCH = UNTOUCHED.

Publication must use an ordinary non-force push to the verified existing upstream after a final remote comparison. No history rewrite, branch switch, reset, clean, stash, rebase or Package C3 work is authorized by this audit.

Generic ignored `.log` files remain local; structured final test summaries and native `.txt` check logs are committed. C1 translation resources intentionally retain escaped trailing spaces in compositional message values; Git whitespace diagnostics for these approved properties are not removed because trimming changes translated output.

Qualification hash inventories also contain four intentionally ignored local entries: QaApp.java, UserMenuProbe.java and two Python __pycache__ files. They match the qualified snapshots but are not staged. Both Java probes are explicitly excluded by the unchanged Maven compiler configuration; cached Python bytecode is generated. C1 commits 606 eligible src files in its complete source tree; C2 commits 623. All compilable/package-eligible sources correspond to the approved candidates.
