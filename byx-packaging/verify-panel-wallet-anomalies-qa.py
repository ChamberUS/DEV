#!/usr/bin/env python3
"""Actual signer metadata anomalies queried through the real signed Panel gateway."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("panel", HERE / "verify-panel-wallet-qa.py")
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)
roots = []
try:
    for anomaly in ("orphan-metadata", "key-mismatch", "orphan-key", "corrupt-metadata"):
        qa.root = Path(tempfile.mkdtemp(prefix="byx-wallet-qa-", dir="/private/tmp"))
        qa.root.chmod(0o700)
        roots.append(qa.root)
        orphan = False
        try:
            qa.run([qa.SERVICE, "wallet-init", qa.root])
            qa.start()
            original, _ = qa.panel("create")
            qa.stop()
            orphan = anomaly == "orphan-key"
            qa.run([qa.SERVICE, "wallet-panel-" + anomaly, qa.root])
            qa.start()
            if anomaly == "corrupt-metadata":
                output = qa.run([qa.PANEL, "corrupt", qa.root])
                qa.check("Corrupt metadata: authenticated gateway is fail closed and UI needs attention", "panel.corruptUi=NEEDS_ATTENTION" in output)
                continue
            view, _ = qa.panel("status")
            qa.check(anomaly + ": Needs attention", view["state"] == "NEEDS_ATTENTION")
            qa.check(anomaly + ": all mutations denied", not any(view["allowedActions"].values()))
            qa.check(anomaly + ": identity never repaired by UI", view["wallets"][0]["walletId"] == original["wallets"][0]["walletId"]
                     and view["wallets"][0]["address"] == original["wallets"][0]["address"])
            _, output = qa.panel("denySign")
            qa.check(anomaly + ": buggy UI sign denied", "panel.deniedSign=PASS" in output)
            qa.stop()
            qa.start()
            retained, _ = qa.panel("status")
            qa.check(anomaly + ": quarantine survives restart", retained["state"] == "NEEDS_ATTENTION")
        finally:
            qa.stop()
            if anomaly == "corrupt-metadata" and qa.root.joinpath("catalog-original.bin").exists():
                # Authorized fixture teardown only, after exact Service termination; never a UI repair.
                qa.root.joinpath("catalog.bin").write_bytes(qa.root.joinpath("catalog-original.bin").read_bytes())
            if orphan:
                qa.run([qa.SERVICE, "wallet-panel-clean-orphan", qa.root])
            qa.check(anomaly + ": exact teardown", "wallet.purged=true" in qa.run([qa.SERVICE, "wallet-purge", qa.root]))
except Exception as error:
    qa.CASES.append({"case": "execution", "pass": False, "reason": str(error)})
finally:
    result = {"cases": qa.CASES, "passed": sum(c["pass"] for c in qa.CASES), "failed": sum(not c["pass"] for c in qa.CASES),
              "skipped": 0, "fixtureRoots": [str(root) for root in roots], "broadcasts": 0, "realKeys": 0, "realWallets": 0}
    Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/byx-v21t4-anomalies-evidence.json").write_text(json.dumps(result, indent=2) + "\n")
    print(f'{result["passed"]} PASS / {result["failed"]} FAIL / 0 SKIP', flush=True)
    sys.exit(bool(result["failed"]))
