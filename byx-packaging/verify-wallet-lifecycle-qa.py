#!/usr/bin/env python3
"""Synthetic signed lifecycle, real writer/receipts and verified process fencing."""
import importlib.util
import json
import os
from pathlib import Path
import selectors
import stat
import subprocess
import sys
import tempfile
import time

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("fencing", HERE / "verify-custody-fencing-qa.py")
fencing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fencing)
SERVICE = fencing.SERVICE
CASES = []
OWNED = []
ROOTS = []


def check(name, condition):
    CASES.append({"case": name, "pass": bool(condition)})
    print(("PASS " if condition else "FAIL ") + name, flush=True)
    if not condition:
        raise RuntimeError(name)


def spawn(mode, root, point=None):
    command = [str(SERVICE), mode, str(root)]
    if point:
        command.append(point)
    p = subprocess.Popen(command, env={"HOME": os.environ["HOME"], "PATH": "/usr/bin:/bin"},
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    instance = fencing.token(p.pid)
    OWNED.append((p, instance))
    return p, instance


def run(mode, root):
    p, _ = spawn(mode, root)
    output, error = p.communicate(timeout=40)
    if p.returncode != 0:
        raise RuntimeError(mode + " failed: " + error.decode(errors="replace")[-500:])
    return output.decode()


def fresh():
    root = Path(tempfile.mkdtemp(prefix="byx-wallet-qa-", dir="/private/tmp"))
    root.chmod(0o700)
    ROOTS.append(root)
    run("wallet-init", root)
    return root


def crash(mode, root, point):
    p, instance = spawn(mode, root, point)
    sel = selectors.DefaultSelector()
    sel.register(p.stdout, selectors.EVENT_READ)
    buffer = b""
    helper = None
    deadline = time.monotonic() + 20
    reached = False
    try:
        while time.monotonic() < deadline and p.poll() is None:
            for key, _ in sel.select(0.25):
                chunk = os.read(key.fd, 4096)
                if not chunk:
                    break
                buffer += chunk
                while b"\n" in buffer:
                    line, buffer = buffer.split(b"\n", 1)
                    text = line.decode()
                    if text.startswith("wallet.helperPid="):
                        helper = fencing.token(int(text.split("=", 1)[1]))
                    if text == "wallet.boundary=" + point:
                        reached = True
                        break
                if reached:
                    break
            if reached:
                break
    finally:
        sel.close()
    check(mode + ":" + point + ":boundary", reached)
    fencing.signal(instance, 9)
    p.wait(timeout=5)
    check(mode + ":" + point + ":service_terminated", fencing.path(instance) is None)
    return helper


def writer_temp_review(root, point):
    files = list(root.glob("catalog.bin.*.tmp"))
    if not files:
        return
    p, _ = spawn("wallet-reconcile", root)
    _, _ = p.communicate(timeout=20)
    check("writer:" + point + ":stale_tmp_fail_closed", p.returncode != 0)
    # Explicit harness cleanup, after exact Service death. Never promotes an incomplete snapshot.
    for path in files:
        metadata = path.lstat()
        check("writer:" + point + ":owned_regular_private_tmp", stat.S_ISREG(metadata.st_mode)
              and metadata.st_uid == os.getuid() and stat.S_IMODE(metadata.st_mode) == 0o600)
        path.unlink()


def purge(root):
    check(root.name + ":exact_QA_teardown", "wallet.purged=true" in run("wallet-purge", root))


def matrix():
    root = fresh()
    created = run("wallet-create", root)
    check("happy:create", "wallet.state=ACTIVE" in created and "wallet.scalarCount=1" in created)
    check("happy:restart", "wallet.health=HEALTHY" in run("wallet-reconcile", root))
    signed = run("wallet-sign", root)
    check("happy:independent_DIRECT_signature_and_broadcast_barrier", "wallet.independentSignature=true" in signed and "wallet.broadcasts=0" in signed)
    check("happy:delete", "wallet.state=DELETED" in run("wallet-delete", root))
    check("happy:tombstone_restart_scalar_absent", "wallet.scalarCount=0" in run("wallet-reconcile", root))
    purge(root)

    root = fresh()
    for point in ["duplicate", "missing", "type", "unknown", "trailing_json", "trailing_frame", "delayed_frame", "stale_reply"]:
        p, _ = spawn("wallet-wire", root, point)
        output, error = p.communicate(timeout=20)
        output = output.decode()
        check("wire:" + point + ":fail_closed", p.returncode == 0 and "wallet.wireRejected=" in output)
        if point != "stale_reply":
            check("wire:" + point + ":zero_Keychain_calls", "wallet.wireKeychainCalls=0" in output)
    purge(root)

    for point in ["beforeCreating", "afterCreating", "afterPreparing", "beforeScalar", "afterScalar", "beforeLive", "afterLive", "beforeActive", "afterActive"]:
        root = fresh()
        helper = crash("wallet-create", root, point)
        run("wallet-reconcile", root)
        check("create:" + point + ":old_helper_absent_after_restart", helper is None or fencing.path(helper) is None)
        output = run("wallet-create", root)
        check("create:" + point + ":same_operation_one_wallet_one_scalar", "wallet.walletCount=1" in output and "wallet.scalarCount=1" in output and "wallet.state=ACTIVE" in output)
        purge(root)

    for point in ["beforeDeleting", "afterDeleting", "beforeRevoked", "afterRevoked", "beforeScalarDelete", "afterScalarDelete", "beforeAbsence", "beforeDeleted", "afterDeleted"]:
        root = fresh()
        run("wallet-create", root)
        helper = crash("wallet-delete", root, point)
        run("wallet-reconcile", root)
        check("delete:" + point + ":old_helper_absent_after_restart", helper is None or fencing.path(helper) is None)
        output = run("wallet-delete", root)
        check("delete:" + point + ":same_tombstone_no_scalar", "wallet.state=DELETED" in output and "wallet.scalarCount=0" in output)
        purge(root)

    for point in ["beforeSign", "afterSign"]:
        root = fresh()
        run("wallet-create", root)
        helper = crash("wallet-sign", root, point)
        output = run("wallet-reconcile", root)
        check("sign:" + point + ":fenced_UNKNOWN_no_automatic_resign", "wallet.unknownSigns=1" in output and "wallet.broadcasts=0" in output and fencing.path(helper) is None)
        purge(root)

    for point in ["afterTmpCreate", "afterWrite", "beforeFileSync", "afterFileSync", "beforeRename", "afterRename", "beforeDirectorySync", "afterDirectorySync", "beforeAnchor", "afterAnchor", "beforePublish", "afterPublish"]:
        root = fresh()
        crash("wallet-create", root, "writer:" + point)
        writer_temp_review(root, point)
        run("wallet-reconcile", root)
        output = run("wallet-create", root)
        check("writer:" + point + ":trusted_reload_before_single_create", "wallet.walletCount=1" in output and "wallet.scalarCount=1" in output)
        purge(root)


def main():
    try:
        matrix()
    except Exception as error:
        CASES.append({"case": "execution", "pass": False, "reason": str(error)[:600]})
        print("FAIL " + str(error), flush=True)
    finally:
        for p, instance in reversed(OWNED):
            if fencing.path(instance) is not None:
                fencing.signal(instance, 9)
            p.wait(timeout=5)
        target = Path(sys.argv[1]) if len(sys.argv) == 2 else HERE.parent / "byx-local-service/docs/qa/v21t3/resumed/native-evidence.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps({"cases": CASES, "passed": sum(c["pass"] for c in CASES),
                                     "failed": sum(not c["pass"] for c in CASES), "skipped": 0,
                                     "fixtureRoots": [str(r) for r in ROOTS], "broadcasts": 0,
                                     "realKeys": 0, "realWallets": 0}, indent=2) + "\n")
    return int(any(not c["pass"] for c in CASES))


if __name__ == "__main__":
    sys.exit(main())
