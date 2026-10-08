#!/usr/bin/env python3
"""Real signed Panel -> authenticated Service -> T-3 -> signer -> QA Keychain."""
import importlib.util
import json
import os
from pathlib import Path
import selectors
import subprocess
import sys
import tempfile
import time

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("fencing", HERE / "verify-custody-fencing-qa.py")
fencing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fencing)
SERVICE = fencing.SERVICE
PANEL = HERE / "build-custody-qa/BYX-MVP.app/Contents/MacOS/byx-wallet-panel-qa"
ENV = {"HOME": os.environ["HOME"], "PATH": "/usr/bin:/bin"}
CASES = []
HELPER = fencing.HELPER
helper_mode = HELPER.stat().st_mode & 0o777
service = None
identity = None
root = Path(tempfile.mkdtemp(prefix="byx-wallet-qa-", dir="/private/tmp"))
root.chmod(0o700)


def check(name, condition):
    CASES.append({"case": name, "pass": bool(condition)})
    print(("PASS " if condition else "FAIL ") + name, flush=True)
    if not condition:
        raise RuntimeError(name)


def run(command):
    result = subprocess.run([str(v) for v in command], env=ENV, capture_output=True, timeout=40)
    if result.returncode:
        raise RuntimeError("QA_PROCESS_FAILED " + result.stderr.decode(errors="replace")[-700:])
    return result.stdout.decode()


def start(mode="wallet-panel-service"):
    global service, identity
    service = subprocess.Popen([str(SERVICE), mode, str(root)], env=ENV,
                               stdout=subprocess.PIPE, stderr=root.joinpath("service-errors.log").open("ab"))
    identity = fencing.token(service.pid)
    sel = selectors.DefaultSelector()
    sel.register(service.stdout, selectors.EVENT_READ)
    deadline = time.monotonic() + 25
    output = b""
    while time.monotonic() < deadline:
        if service.poll() is not None:
            raise RuntimeError("SERVICE_START_FAILED " + root.joinpath("service-errors.log").read_text()[-700:])
        if sel.select(0.2):
            output += os.read(service.stdout.fileno(), 4096)
            if b"PANEL_SERVICE_READY" in output:
                sel.close()
                check("DEFAULT runtime starts without custody" if mode == "wallet-panel-default-runtime" else "Service startup reconciles before exposure", True)
                return
    raise RuntimeError("SERVICE_START_TIMEOUT")


def stop():
    global service
    if service is not None and service.poll() is None:
        if fencing.LIB.proc_signal_with_audittoken(identity, 15) != 0:
            raise RuntimeError("EXACT_SERVICE_STOP_FAILED")
        service.wait(timeout=15)
    service = None


def panel(mode):
    output = run([PANEL, mode, root])
    view = json.loads(next(line.removeprefix("panel.public=") for line in output.splitlines() if line.startswith("panel.public=")))
    check(mode + ": public DTO only", not any(word in json.dumps(view).lower() for word in
          ("signingkeyref", "scalar", "mnemonic", "seed", "keychain", "receipt", "requestdigest")))
    return view, output


if __name__ == "__main__":
    try:
        run([SERVICE, "wallet-init", root])
        start()
        view, _ = panel("status")
        check("NO_WALLET", view["state"] == "NO_WALLET")
        view, _ = panel("user")
        check("USER cannot mutate", not any(view["allowedActions"].values()))
        root.joinpath("qa-lost-create-response").touch(mode=0o600)
        _, output = panel("createLost")
        check("Lost create response requires reconciliation", "panel.unknownResult=RECONCILIATION_REQUIRED" in output)
        stop()
        root.joinpath("qa-lost-create-response").unlink()
        offline = run([PANEL, "offline", root])
        check("Service offline distinct from NO_WALLET", "panel.offline=SERVICE_UNAVAILABLE" in offline)
        start()
        restored, _ = panel("status")
        check("Create committed before lost response survives Service restart", restored["state"] == "READY")
        view, output = panel("create")
        check("Create through real gateway", view["state"] == "READY" and "panel.duplicateCreate=PASS" in output)
        wallet = view["wallets"][0]
        check("Public identity and no recovery", wallet["address"].startswith("byx1") and wallet["recoveryPolicy"] == "LOCAL_ONLY_NO_RECOVERY")
        restarted, _ = panel("status")
        check("Panel process restart reloads Service state", restarted["wallets"] == view["wallets"])
        stop()
        start()
        restarted, _ = panel("status")
        check("Service restart reconciles same identity", restarted["state"] == "READY" and restarted["wallets"] == view["wallets"])
        HELPER.chmod(helper_mode & ~0o111)
        offline, _ = panel("status")
        check("Signer offline distinct domain state", offline["state"] == "SIGNER_UNAVAILABLE" and not any(offline["allowedActions"].values()))
        _, denial = panel("denySign")
        check("Buggy UI cannot sign with offline signer", "panel.deniedSign=PASS" in denial)
        HELPER.chmod(helper_mode)
        root.joinpath("qa-inventory-incomplete").touch(mode=0o600)
        degraded, _ = panel("status")
        check("Incomplete inventory disables all mutations", degraded["state"] == "NEEDS_ATTENTION" and degraded["wallets"][0]["healthState"] == "INVENTORY_INCOMPLETE" and not any(degraded["allowedActions"].values()))
        root.joinpath("qa-inventory-incomplete").unlink()
        _, output = panel("barrier")
        check("Adversarial broadcast denied", "panel.broadcastBarrier=PASS" in output)
        view, output = panel("sign")
        check("Synthetic sign independently verified in Java", "panel.independentSign=PASS" in output)
        _, output = panel("visual")
        check("Real JavaFX 1920x1080 capture", "panel.visual=PASS" in output and root.joinpath("wallet-1920x1080.png").exists())
        _, user_visual = panel("visualUser")
        check("USER visual without synthetic debug actions", "panel.visual=PASS" in user_visual and root.joinpath("wallet-user-1920x1080.png").exists())
        view, output = panel("delete")
        check("Delete and duplicate delete converge", view["state"] == "DELETED" and "panel.duplicateDelete=PASS" in output)
        stop()
        start()
        final, _ = panel("status")
        check("Tombstone after Service restart", final["state"] == "DELETED" and final["wallets"][0]["walletId"] == wallet["walletId"])
        stop()
        root.joinpath("qa-quiescence-unproven").touch(mode=0o600)
        start()
        blocked, _ = panel("status")
        check("Unproven quiescence never publishes READY", blocked["state"] == "NEEDS_ATTENTION" and not any(blocked["allowedActions"].values()))
        stop()
        root.joinpath("qa-quiescence-unproven").unlink()
    except Exception as error:
        CASES.append({"case": "execution", "pass": False, "reason": str(error)})
        print("FAIL execution " + str(error), flush=True)
    finally:
        try:
            HELPER.chmod(helper_mode)
            stop()
            output = run([SERVICE, "wallet-purge", root])
            check("Exact synthetic namespace and TEST anchors teardown", "wallet.purged=true" in output)
        except Exception as error:
            CASES.append({"case": "teardown", "pass": False, "reason": str(error)})
        result = {"cases": CASES, "passed": sum(c["pass"] for c in CASES), "failed": sum(not c["pass"] for c in CASES),
                  "skipped": 0, "root": str(root), "broadcasts": 0, "realKeys": 0, "realWallets": 0}
        Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/byx-v21t4-native-evidence.json").write_text(json.dumps(result, indent=2) + "\n")
        print(f'{result["passed"]} PASS / {result["failed"]} FAIL / 0 SKIP', flush=True)
        sys.exit(bool(result["failed"]))
