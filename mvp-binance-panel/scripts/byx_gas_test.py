#!/usr/bin/env python3
"""Bounded native feegrant SDK signer. Exclusive audited LOCALNET TEST keyring."""

import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone

from byx_localnet import BINARY, NODE, ROOT, checked, process
from byx_payment_test import call, require


def key_address(name):
    return call("keys", "show", name)["address"]


def confirm(*args):
    tx = call(
        *args,
        "--fees",
        "10000ubyx",
        "--gas",
        "200000",
        "--chain-id",
        checked()["chain_id"],
        "--node",
        "tcp://127.0.0.1:27657",
        "--broadcast-mode",
        "sync",
        "--yes",
    )
    require(int(tx.get("code", 0)) == 0, "TEST broadcast rejected")
    for _ in range(30):
        result = subprocess.run(
            [
                str(BINARY),
                "query",
                "tx",
                tx["txhash"],
                "--node",
                "tcp://127.0.0.1:27657",
                "--output",
                "json",
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode == 0:
            receipt = json.loads(result.stdout)
            require(
                int(receipt["code"]) == 0 and int(receipt["height"]) > 0,
                "TEST execution failed",
            )
            return {
                "tx_hash": tx["txhash"],
                "height": receipt["height"],
                "fee_ubyx": 10000,
            }
        time.sleep(1)
    raise SystemExit("Confirmation timeout; do not blindly rebroadcast")


def validate(payload, manifest, sponsor):
    require(
        payload["chainId"] == manifest["chain_id"]
        and payload["genesis"] == manifest["genesis_fingerprint"],
        "Wrong chain",
    )
    require(payload["granter"] == sponsor, "Exclusive TEST sponsor required")
    require(
        payload["grantee"]
        in [manifest["addresses"][k] for k in ("alice-test", "bob-test")],
        "Exclusive TEST grantee required",
    )


def main():
    require(
        os.environ.get("BYX_LOCALNET_TEST_SIGNER") == "I_ACKNOWLEDGE_TEST_ONLY",
        "DEV TEST opt-in required",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "action", choices=["setup", "grant", "revoke", "send", "send-unsponsored"]
    )
    args = parser.parse_args()
    manifest = checked()
    require(process(), "Audited localnet process required")
    if args.action == "setup":
        result = subprocess.run(
            [
                str(BINARY),
                "keys",
                "show",
                "gas-sponsor-test",
                "--home",
                str(NODE),
                "--keyring-backend",
                "test",
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        if result.returncode != 0:
            subprocess.run(
                [
                    str(BINARY),
                    "keys",
                    "add",
                    "gas-sponsor-test",
                    "--home",
                    str(NODE),
                    "--keyring-backend",
                    "test",
                ],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=True,
            )
        sponsor = key_address("gas-sponsor-test")
        claim = ROOT / "evidence/gas-sponsor-funding.claim"
        if not claim.exists():
            with claim.open("x") as out:
                out.write("single TEST sponsor funding 200000ubyx from alice-test\n")
            confirm("tx", "bank", "send", "alice-test", sponsor, "200000ubyx")
        print(
            json.dumps(
                {
                    "granter": sponsor,
                    "chainId": manifest["chain_id"],
                    "genesis": manifest["genesis_fingerprint"],
                }
            )
        )
        return
    sponsor = key_address("gas-sponsor-test")
    payload = json.load(sys.stdin)
    validate(payload, manifest, sponsor)
    if args.action == "grant":
        limit = int(payload["limit"])
        now = datetime.now(timezone.utc)
        expiry = datetime.fromisoformat(payload["expiration"].replace("Z", "+00:00"))
        require(
            0 < limit <= 1000000 and now < expiry <= now + timedelta(seconds=3600),
            "Bounded TEST allowance required",
        )
        claim = hashlib.sha256(
            (manifest["chain_id"] + payload["grantee"]).encode()
        ).hexdigest()
        with (ROOT / "evidence" / ("gas-grant-" + claim + ".claim")).open("x") as out:
            out.write("single V1 TEST grant; no replenishment\n")
        tx = confirm(
            "tx",
            "feegrant",
            "grant",
            "gas-sponsor-test",
            payload["grantee"],
            "--spend-limit",
            str(limit) + "ubyx",
            "--expiration",
            expiry.isoformat().replace("+00:00", "Z"),
            "--allowed-messages",
            "/cosmos.bank.v1beta1.MsgSend",
        )
    elif args.action == "revoke":
        tx = confirm("tx", "feegrant", "revoke", "gas-sponsor-test", payload["grantee"])
    else:
        name = next(
            k
            for k in ("alice-test", "bob-test")
            if manifest["addresses"][k] == payload["grantee"]
        )
        other = manifest["addresses"][
            "bob-test" if name == "alice-test" else "alice-test"
        ]
        options = ("--fee-granter", sponsor) if args.action == "send" else ()
        tx = confirm("tx", "bank", "send", name, other, "1ubyx", *options)
    print(json.dumps(tx))


if __name__ == "__main__":
    main()
