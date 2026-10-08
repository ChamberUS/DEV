# V2.1T-2R — Restart quiescence and helper fencing

## 1. STATUS

**READY_TO_RESUME_V2_1T_3** — synthetic QA fencing only. T-3 was not resumed. REAL USER KEY NOT AUTHORIZED; REAL TX DISABLED; real wallets, funds used and broadcasts: 0. Scientific capture/research untouched. No push.

Machine-readable evidence: [summary](evidence.json), [process matrix](process-evidence.json), [DEFAULT](default-evidence.json).

## 2. REPOSITORY STATE

Root `/Users/buynnex-corp/dev`; branch `feature/byx-ui-redesign-v1`; starting HEAD `687119574096f89518942f8009eff7d5d8e3681e`. Recent commits: `6871195`, `c04a441`, `553737e`, `22cab56`, `678f508`.

Preexisting tracked changes in `byx-packaging/build-app.sh`, Panel and iaos-web were preserved and excluded from this checkpoint. Existing untracked T-3 failure records were preserved. T-2 design was previously untracked and is included because this phase explicitly revises that document. No reset, clean, stash, merge, rebase or push.

## 3. ROOT CAUSE / PROCESS MODEL

`CustodyClient.exchange` directly creates the helper through an absolute-path ProcessBuilder with empty environment and stdio pipes. No shell or PATH discovery. Previously its finally block requested `destroyForcibly()` without proving exit. `Serve` authenticated its direct parent once; after authentication it could continue native work after the Service died. Before authentication, parent/caller identity mismatch or absent IPC denies work. After authentication, before/during access or after mutation but before response, parent death alone proves neither termination nor outcome.

The 15 s `time.AfterFunc` callback cannot execute during SIGSTOP. The historical T-3 failure (16.013 s) and this phase's real signed baseline both show a live stopped helper after more than 16 s; the current measurement is in process-evidence.json.

Observed signed process model: Service PID 71995, helper PID 71996, helper PPID 71995; both PGID/session 71979. Helper was T, Service S. Process group/session is inherited; fencing does not kill groups. Kernel pidversions 779597/779600. The signed READY reports stdio hygiene; inherited descriptors are scrubbed, no IP socket or child was observed. Static dependency/source guards prohibit shell, exec, fork, spawning, daemonization and networking. Go runtime threads are not descendants. There is no transfer of a mutation or secret to another process.

## 4. NEW SECURITY INVARIANT

Mutable synthetic custody is eligible only while this process owns the exclusive authority lease and every previous authenticated helper instance is externally proved gone. An uncertain discovery, identity, termination or absence blocks dispatch with `CUSTODY_QUIESCENCE_UNPROVEN`. Time elapsed and socket closure never establish quiescence.

## 5. SERVICE AUTHORITY LOCK

`CustodyAuthority.Lease`: retained FileChannel/FileLock on an inode in the OS-derived per-user temporary directory, `byx-custody-qa-authority/authority.lock`. Directory 0700, file 0600, actual effective UID, regular file, no symlink; inode checked before dispatch. The file is retained across generations; its existence is not a lock. Only the Service code role may establish authority. A suspended Service retains exclusion; actual death releases it. The shutdown hook keeps exclusion through verified helper termination. Ambiguity blocks mutation.

The same held channel stores only public fencing metadata: version, status, generation, operationId and unknownResult. IN_FLIGHT is forced before launch. Partial/malformed/duplicate/trailing checkpoint data fails closed. No credentials, receipt, wallet or production authority store is created. This is not a complete lifecycle operation journal.

## 6. GENERATION / EPOCH

Fresh unpredictable 128-bit epoch after acquiring authority; separate random 128-bit operation ID per call. QA protocol 3 binds generation, operation, invocation/challenge and SHA-256 request digest. It does not change the production signing protocol or scientific contracts. No default composition uses this client.

## 7. PROCESS IDENTITY

`task_name_for_pid` + `TASK_AUDIT_TOKEN` obtain kernel metadata, including PID and pidversion. Live code must satisfy Apple chain, expected Team, exact signer identifier, strict nested seal, exact bundle and executable origin. IPC peer identity must equal the captured instance.

`proc_signal_with_audittoken` targets the instance, not a later process sharing its PID. The deliberately wrong pidversion returned ESRCH and left the real helper alive. The [XNU process lookup](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/kern/kern_proc.c) validates pidversion; the [libproc wrapper](https://github.com/apple-oss-distributions/xnu/blob/main/libsyscall/wrappers/libproc/libproc.c) supplies the audit token to signaling.

## 8. STALE HELPER DISCOVERY

While holding authority, bounded OS process enumeration inspects UID/path metadata. Expected executable basename selects candidates only; it never authorizes termination. Candidates require full live identity/origin/seal verification. Unreadable same-user state or an unqualified candidate fails closed. Different-user unrelated processes are excluded. No broad pkill, command substring trust or persisted PID trust.

A crash before the helper authenticates cannot authorize a mutation; an authenticated helper already has the executable identity discovered by this gate. Signer source/dependency guards prohibit descendant/exec escape. A prior authority cannot dispatch after relinquishing its verified lease.

## 9. VERIFIED TERMINATION

Externally signal SIGKILL using the captured audit token. Poll `proc_pidpath_audittoken` for that token: only ESRCH establishes absence; other errors are uncertainty. The 3 s monotonic polling budget limits the attempt, never converts a timeout into success. Signal acknowledgment without absence is rejected. Real stopped helper: termination and absence PASS.

## 10. PER-OPERATION FENCING

One authority monitor serializes calls. The exact child is captured before sending invocation data. Timeout, cancellation, EOF, malformed response and shutdown retain that identity until verified gone. Failure latches the generation closed; no further helper dispatch. Cancellation clears the thread interrupt temporarily for external verification and restores it afterwards. No blind retry.

## 11. UNKNOWN RESULT

Quiescence establishes no outcome. Post-dispatch transport/response uncertainty returns `TIMEOUT_UNKNOWN_RESULT`. IN_FLIGHT at restart or uncertain completion preserves a sticky public UNKNOWN indicator. The D probe performed one public in-memory increment, stopped before responding, then crashed the Service. Restart terminated the old helper, reported UNKNOWN and performed only a distinct read-only count. Fault probes made zero Keychain calls. T-3 must supply operation-specific durable receipts/journal and reconciliation; this indicator is not a replacement.

## 12. STALE RESPONSE TESTS

Old epoch, different operation, different digest, duplicate accepted response and wrong helper pidversion are rejected. Binding tests are in-memory; real IPC identity and quiescence use signed processes.

## 13. NEGATIVE AUTHORIZATION

T-1 matrix rerun against the final signed artifact: 65 PASS, 0 FAIL. Terminal, Panel, same-Team wrong role, copied/tampered/unsigned/wrong-ID helper and caller injection remain denied. Direct Panel authority acquisition additionally returned CALLER_UNTRUSTED. No secret value was emitted. Only the synthetic QA namespace was exercised and cleaned by the existing T-1 runner.

## 14. TEST RESULTS

| Suite | Result |
|---|---|
| Go `go test -p 1 -tags qa -json ./...` | 41 PASS, 0 FAIL, 1 SKIP: subprocess-only harness |
| Java complete Service suite, final `mvn -o -q package` | 429 tests, 0 failures/errors/skips; includes 20 fencing unit tests |
| Native/signed process matrix | 24 PASS, 0 FAIL |
| Additional signed process-model observation | 1 PASS; direct parent, inherited group/session, authenticated pre-mutation, zero Keychain calls |
| Integration A/B/C/D + timeout | 5/5 scenarios PASS, included in process matrix |
| Security | 65 T-1 PASS + 1 Panel authority denial PASS |
| DEFAULT rebuilt/final Service jar replaced and bundles re-signed | 7/7 artifact checks PASS |
| Quality | Ruff `src tests` PASS; phase `git diff --check` PASS; global check reports only preexisting whitespace in iaos-web Layout.jsx/MyStore.jsx |

DEFAULT uses the exact final Service jar hash in default-evidence.json. Strict nested signature verifies; production-disabled chain, no QA helper/test jar/probe main/Panel transaction lab. The full Service suite verifies unavailable production signer, disabled mutation policy and absent broadcast transport. No GUI was started.

## 15. FILES CHANGED

* Main: `identity/{MacSecurity,CodeIdentity}.java`, `signer/{CustodyClient,CustodyAuthority}.java`.
* Go QA: `cmd/byx-signer-helper-qa/main.go`, `internal/custody/{server,custody_test,fencing_probe}.go`.
* Java tests: `signer/{CustodyGuardTest,CustodyAuthorityTest,CustodyQaMain,CustodyFencingQaMain}.java`.
* Packaging QA only: `build-custody-fencing-qa.sh`, `verify-custody-fencing-qa.py`.
* Documentation: revised T-2 design; this report and three T-2R evidence JSON files.

## 16. KNOWN LIMITATIONS

macOS-only qualified primitive; unavailable APIs/permissions fail closed. Owner-writable development installation and malicious same-UID filesystem replacement remain the previously documented production threat-model blocker, not solved by a QA file lock. Old incompatible signer identities/origins block automatic recovery. Interrupted helper private IPC directories may remain; socket existence never proves liveness. No power-loss durability qualification, wallet journal/reconciliation, lock-screen custody policy or complete T-3 lifecycle is claimed. Provisioning was reused, not renewed. No production key, authority migration or scientific processing was executed.

## 17. NEXT ACTION

`RESUME V2.1T-3 FROM THE BEGINNING USING THE REVISED T-2 CONTRACT`

Only after explicit review; no automatic continuation.
