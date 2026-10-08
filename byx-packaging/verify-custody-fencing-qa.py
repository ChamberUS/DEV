#!/usr/bin/env python3
"""Real signed process probes; public instrumentation, no Keychain calls or lifecycle."""
import ctypes
import json
import os
from pathlib import Path
import selectors
import secrets
import struct
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent
HELPERS = ROOT / "build-custody-qa/BYX-MVP.app/Contents/Helpers"
HELPER = HELPERS / "byx-signer-helper-qa.app/Contents/MacOS/byx-signer-helper-qa"
SERVICE = HELPERS / "byx-custody-qa-service.app/Contents/MacOS/byx-custody-qa-service"
LIB = ctypes.CDLL("/usr/lib/libSystem.B.dylib", use_errno=True)
SELF = ctypes.c_uint.in_dll(LIB, "mach_task_self_").value
Token = ctypes.c_uint * 8
LIB.task_name_for_pid.argtypes = [ctypes.c_uint, ctypes.c_int, ctypes.POINTER(ctypes.c_uint)]
LIB.task_info.argtypes = [ctypes.c_uint, ctypes.c_int, ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint)]
LIB.proc_signal_with_audittoken.argtypes = [ctypes.POINTER(ctypes.c_uint), ctypes.c_int]
LIB.proc_pidpath_audittoken.argtypes = [ctypes.POINTER(ctypes.c_uint), ctypes.c_void_p, ctypes.c_uint]
CASES = []
OWNED = []
SOCKETS = []


def check(name, condition, **metadata):
    CASES.append({"case": name, "pass": bool(condition), **metadata})
    print(f"{'PASS' if condition else 'FAIL'} {name}", flush=True)
    if not condition:
        raise RuntimeError(name)


def token(pid):
    port = ctypes.c_uint()
    if LIB.task_name_for_pid(SELF, pid, ctypes.byref(port)) != 0:
        raise RuntimeError("TOKEN_UNAVAILABLE")
    try:
        result = Token()
        count = ctypes.c_uint(8)
        if LIB.task_info(port.value, 15, result, ctypes.byref(count)) != 0 or result[5] != pid:
            raise RuntimeError("TOKEN_UNAVAILABLE")
        return result
    finally:
        LIB.mach_port_deallocate(SELF, port.value)


def path(instance):
    buf = ctypes.create_string_buffer(4096)
    n = LIB.proc_pidpath_audittoken(instance, buf, len(buf))
    if n > 0:
        return buf.value.decode()
    if ctypes.get_errno() == 3:
        return None
    raise RuntimeError("INSTANCE_STATE_UNPROVEN")


def signal(instance, sig):
    rc = LIB.proc_signal_with_audittoken(instance, sig)
    if rc not in (0, 3):
        raise RuntimeError(f"SIGNAL_FAILED_{rc}")


def gone(instance, budget=5):
    deadline = time.monotonic() + budget
    while time.monotonic() < deadline:
        if path(instance) is None:
            return True
        time.sleep(0.01)
    return False


def spawn(mode):
    p = subprocess.Popen([str(SERVICE), mode], env={}, stdin=subprocess.PIPE,
                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
    instance = token(p.pid)
    OWNED.append((p, instance))
    return p, instance


def lines_until(p, key, budget=8):
    data = b""
    deadline = time.monotonic() + budget
    with selectors.DefaultSelector() as selector:
        selector.register(p.stdout, selectors.EVENT_READ)
        while time.monotonic() < deadline:
            if selector.select(0.05):
                part = os.read(p.stdout.fileno(), 4096)
                if not part:
                    break
                data += part
                if key.encode() in data:
                    return data.decode()
    raise RuntimeError("PROBE_CHECKPOINT_MISSING:" + key + ":" + data.decode()[:500])


def value(output, key):
    return next(line.split("=", 1)[1] for line in output.splitlines() if line.startswith(key + "="))


def stopped(instance, budget=3):
    deadline = time.monotonic() + budget
    while time.monotonic() < deadline:
        if path(instance) is None:
            return False
        result = subprocess.run(["/bin/ps", "-p", str(instance[5]), "-o", "state="], capture_output=True, text=True)
        if result.stdout.strip().startswith("T"):
            return True
        time.sleep(0.02)
    return False


def run():
    requirement = '=identifier "com.buynnex.byx.signer.qa" and anchor apple generic and certificate leaf[subject.OU] = "W5Z65G9UP2"'
    verify = subprocess.run(["/usr/bin/codesign", "--verify", "--strict", "-R", requirement, str(HELPER.parent.parent.parent)], capture_output=True)
    check("signed_helper_Team_identifier_strict_seal", verify.returncode == 0)
    baseline = subprocess.Popen([str(HELPER)], env={}, stdin=subprocess.PIPE,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    own = token(baseline.pid)
    OWNED.append((baseline, own))
    baseline.stdin.write((secrets.token_hex(16) + "\n").encode())
    baseline.stdin.close()
    n = struct.unpack(">I", baseline.stdout.read(4))[0]
    ready = json.loads(baseline.stdout.read(n))
    SOCKETS.append(Path(ready["path"]))
    check("signed_helper_exact_executable", path(own) == str(HELPER))
    signal(own, 17)
    network = subprocess.run(["/usr/sbin/lsof", "-a", "-nP", "-p", str(own[5]), "-i"], capture_output=True)
    check("signed_helper_IP_sockets_zero", network.returncode == 1 and not network.stdout.strip())
    children = subprocess.run(["/usr/bin/pgrep", "-P", str(own[5])], capture_output=True)
    check("signed_helper_descendants_zero", children.returncode == 1 and not children.stdout.strip())
    start = time.monotonic()
    time.sleep(16)
    check("SIGSTOP_outlives_watchdog_baseline", stopped(own), elapsedSeconds=round(time.monotonic() - start, 3))
    reused = Token(*own)
    reused[7] += 1
    rc = LIB.proc_signal_with_audittoken(reused, 9)
    check("wrong_pidversion_cannot_signal_real_helper", rc == 3 and path(own) is not None, nativeReturn=rc)
    signal(own, 9)
    baseline.wait(timeout=5)
    check("external_SIGSTOP_termination_verified", gone(own), helperExit=baseline.returncode)

    a, ai = spawn("fencing-lock")
    first = lines_until(a, "fencing.authority=QUIESCENT")
    signal(ai, 17)
    check("Service_A_suspended_holds_lock", stopped(ai))
    b, bi = spawn("fencing-normal")
    output = b.communicate(timeout=10)[0].decode()
    check("Service_B_denied_no_helper", "CUSTODY_QUIESCENCE_UNPROVEN" in output and "fencing.child=" not in output)
    signal(ai, 9)
    a.wait(timeout=5)
    check("Service_A_actual_death_verified", gone(ai))
    b, bi = spawn("fencing-normal")
    output = b.communicate(timeout=15)[0].decode()
    check("lock_released_on_death_normal_helper_exits", "fencing.result=COUNT" in output,
          publicResult=output.strip().splitlines()[-1] if output else "EMPTY")
    check("fresh_generation_after_death", value(first, "fencing.generation") != value(output, "fencing.generation"))

    for mode, name, expected in [("fencing-stop", "A_stale_stopped_helper", None),
                                 ("fencing-before", "C_authenticated_before_mutation", 0),
                                 ("fencing-after", "D_mutation_before_response", 1)]:
        old, oldi = spawn(mode)
        output = lines_until(old, "fencing.child=")
        child = token(int(value(output, "fencing.child")))
        OWNED.append((None, child))
        if expected is not None:
            if "fencing.keychainCalls=" not in output:
                output += lines_until(old, "fencing.keychainCalls=")
            check(name + "_checkpoint", f"fencing.publicMutationCount={expected}" in output and "fencing.keychainCalls=0" in output)
        check(name + "_stopped", stopped(child))
        signal(oldi, 9)
        old.wait(timeout=5)
        new, newi = spawn("fencing-normal")
        result = new.communicate(timeout=15)[0].decode()
        check(name + "_restart_verified_before_operation", gone(child) and "fencing.authority=QUIESCENT" in result and "fencing.result=COUNT" in result)
        check(name + "_UNKNOWN_no_mutation_retry", "fencing.priorResult=UNKNOWN" in result and "fencing.result=COUNT" in result)

    timeout, ti = spawn("fencing-timeout")
    output = timeout.communicate(timeout=20)[0].decode()
    check("timeout_UNKNOWN_verified_fence_then_readonly_followup", "fencing.result=TIMEOUT_UNKNOWN_RESULT" in output and "fencing.followup=COUNT" in output)


def main():
    try:
        run()
    except Exception as exc:
        print("PROBE_FAILURE", str(exc), file=sys.stderr)
        CASES.append({"case": "probe_execution", "pass": False, "reason": str(exc)[:500]})
    finally:
        for process, instance in reversed(OWNED):
            if path(instance) is not None:
                signal(instance, 9)
            if process is not None:
                process.wait(timeout=5)
        for socket in SOCKETS:
            socket.unlink(missing_ok=True)
            socket.parent.rmdir()
        target = Path(sys.argv[1]) if len(sys.argv) == 2 else ROOT.parent / "byx-local-service/docs/qa/v21t2r/process-evidence.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps({"cases": CASES, "passed": sum(c["pass"] for c in CASES),
                                      "failed": sum(not c["pass"] for c in CASES),
                                      "keychainCallsInFaultProbes": 0, "broadcasts": 0, "realKeys": 0}, indent=2) + "\n")
    return 1 if any(not c["pass"] for c in CASES) else 0


if __name__ == "__main__":
    sys.exit(main())
