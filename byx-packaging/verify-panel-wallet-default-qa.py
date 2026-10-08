#!/usr/bin/env python3
"""Signed runtime of the exact disabled Service composition, without custody or secrets."""
import importlib.util
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("panel", HERE / "verify-panel-wallet-qa.py")
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)
try:
    qa.start("wallet-panel-default-runtime")
    view, output = qa.panel("default")
    qa.check("DEFAULT public capability disabled", view["capability"] == "DISABLED")
    qa.check("DEFAULT status unavailable, never NO_WALLET", view["state"] == "UNAVAILABLE")
    qa.check("DEFAULT no mutation capability", not any(view["allowedActions"].values()))
    qa.check("DEFAULT direct create/sign/delete and TX denied", "panel.default=PASS" in output)
except Exception as error:
    qa.CASES.append({"case": "execution", "pass": False, "reason": str(error)})
finally:
    qa.stop()
    result = {"cases": qa.CASES, "passed": sum(c["pass"] for c in qa.CASES), "failed": sum(not c["pass"] for c in qa.CASES), "skipped": 0,
              "root": str(qa.root), "custodyComposed": False, "secretsComposed": False, "broadcasts": 0, "realKeys": 0, "realWallets": 0}
    Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/byx-v21t4-default-runtime.json").write_text(json.dumps(result, indent=2) + "\n")
    print(f'{result["passed"]} PASS / {result["failed"]} FAIL / 0 SKIP', flush=True)
    sys.exit(bool(result["failed"]))
