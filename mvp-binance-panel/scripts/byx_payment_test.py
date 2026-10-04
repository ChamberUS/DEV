#!/usr/bin/env python3
"""Explicit DEV-only localnet receiver setup and bank-send fixture. No key export."""

import argparse
import json
import os
import subprocess
import sys
from datetime import datetime, timezone

from byx_localnet import BINARY, NODE, ROOT, checked, process


def require(ok, message):
    if not ok:
        raise SystemExit(message)


def call(*args):
    result = subprocess.run(
        [
            str(BINARY),
            *args,
            "--home",
            str(NODE),
            "--keyring-backend",
            "test",
            "--output",
            "json",
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    require(result.returncode == 0, "DEV localnet command failed")
    return json.loads(result.stdout)


def main():
    require(
        os.environ.get("BYX_LOCALNET_TEST_SIGNER") == "I_ACKNOWLEDGE_TEST_ONLY",
        "Explicit DEV/TEST opt-in required",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["service-wallet", "send"])
    args = parser.parse_args()
    manifest = checked()
    require(process(), "Audited localnet process required")
    result = subprocess.run(
        [
            str(BINARY),
            "keys",
            "show",
            "payments-service-test",
            "--home",
            str(NODE),
            "--keyring-backend",
            "test",
            "--output",
            "json",
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        require(args.action == "service-wallet", "Create TEST receiver first")
        # SDK creates the key in its external TEST keyring. Discard mnemonic output.
        recipient = call("keys", "add", "payments-service-test")["address"]
    else:
        recipient = json.loads(result.stdout)["address"]
    public = {
        "recipient": recipient,
        "chain_id": manifest["chain_id"],
        "genesis_fingerprint": manifest["genesis_fingerprint"],
    }
    if args.action == "service-wallet":
        print(json.dumps(public))
        return
    intent = json.load(sys.stdin)
    require(
        intent["chainId"] == public["chain_id"]
        and intent["genesisFingerprint"] == public["genesis_fingerprint"],
        "Wrong chain",
    )
    require(intent["recipient"] == recipient, "Wrong TEST service receiver")
    require(
        intent["verifiedWallet"] == manifest["addresses"]["bob-test"],
        "Only bob-test allowed",
    )
    require(intent["purpose"] == "advanced_analytics_test", "Wrong purpose")
    amount = int(intent["amountUbyx"])
    require(0 < amount <= 1000000, "TEST amount bound exceeded")
    nonce = intent["id"]
    require(
        len(nonce) == 64 and all(c in "0123456789abcdef" for c in nonce),
        "Invalid intent id",
    )
    now = datetime.now(timezone.utc)
    require(
        datetime.fromisoformat(intent["createdAt"].replace("Z", "+00:00"))
        <= now
        < datetime.fromisoformat(intent["expiresAt"].replace("Z", "+00:00")),
        "Expired intent",
    )
    with (ROOT / "evidence" / ("payment-" + nonce + ".claim")).open("x") as claim:
        claim.write("single TEST payment broadcast\n")
    tx = call(
        "tx",
        "bank",
        "send",
        "bob-test",
        recipient,
        str(amount) + "ubyx",
        "--note",
        "BYX-MVP:PAY:v1:" + nonce,
        "--fees",
        "10000ubyx",
        "--gas",
        "200000",
        "--chain-id",
        manifest["chain_id"],
        "--node",
        "tcp://127.0.0.1:27657",
        "--broadcast-mode",
        "sync",
        "--yes",
    )
    require(int(tx.get("code", 0)) == 0, "TEST broadcast rejected")
    print(
        json.dumps({"tx_hash": tx["txhash"], "amount_ubyx": amount, "fee_ubyx": 10000})
    )


if __name__ == "__main__":
    main()
