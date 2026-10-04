import importlib
import io
import json
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest


@pytest.fixture
def helper(monkeypatch):
    from pathlib import Path

    monkeypatch.syspath_prepend(str(Path(__file__).resolve().parents[3] / "scripts"))
    return importlib.import_module("byx_payment_test")


def test_requires_explicit_dev_opt_in_before_keyring(helper, monkeypatch):
    monkeypatch.delenv("BYX_LOCALNET_TEST_SIGNER", raising=False)
    with pytest.raises(SystemExit, match="opt-in"):
        helper.main()


def test_invalid_chain_never_broadcasts(helper, monkeypatch, tmp_path):
    monkeypatch.setenv("BYX_LOCALNET_TEST_SIGNER", "I_ACKNOWLEDGE_TEST_ONLY")
    monkeypatch.setattr(
        helper, "checked", lambda: {"chain_id": "local", "genesis_fingerprint": "g"}
    )
    monkeypatch.setattr(helper, "process", lambda: True)
    monkeypatch.setattr(
        helper.subprocess,
        "run",
        lambda *args, **kwargs: SimpleNamespace(
            returncode=0, stdout='{"address":"recipient"}'
        ),
    )
    monkeypatch.setattr(helper.sys, "argv", ["helper", "send"])
    monkeypatch.setattr(
        helper.sys, "stdin", io.StringIO('{"chainId":"wrong","genesisFingerprint":"g"}')
    )
    with pytest.raises(SystemExit, match="Wrong chain"):
        helper.main()
    assert not list(tmp_path.iterdir())


def test_exact_reference_note_and_duplicate_claim(
    helper, monkeypatch, tmp_path, capsys
):
    monkeypatch.setenv("BYX_LOCALNET_TEST_SIGNER", "I_ACKNOWLEDGE_TEST_ONLY")
    monkeypatch.setattr(
        helper,
        "checked",
        lambda: {
            "chain_id": "local",
            "genesis_fingerprint": "g",
            "addresses": {"bob-test": "bob"},
        },
    )
    monkeypatch.setattr(helper, "process", lambda: True)
    monkeypatch.setattr(
        helper.subprocess,
        "run",
        lambda *args, **kwargs: SimpleNamespace(
            returncode=0, stdout='{"address":"recipient"}'
        ),
    )
    monkeypatch.setattr(helper, "ROOT", tmp_path)
    (tmp_path / "evidence").mkdir()
    calls = []

    def call(*args):
        calls.append(args)
        return {"txhash": "A" * 64, "code": 0}

    monkeypatch.setattr(helper, "call", call)
    monkeypatch.setattr(helper.sys, "argv", ["helper", "send"])
    now = datetime.now(timezone.utc)
    intent = {
        "chainId": "local",
        "genesisFingerprint": "g",
        "recipient": "recipient",
        "verifiedWallet": "bob",
        "purpose": "advanced_analytics_test",
        "amountUbyx": "10000",
        "id": "a" * 64,
        "createdAt": now.isoformat(),
        "expiresAt": (now + timedelta(seconds=60)).isoformat(),
    }
    monkeypatch.setattr(helper.sys, "stdin", io.StringIO(json.dumps(intent)))
    helper.main()
    assert "--memo" not in calls[0]
    assert calls[0][calls[0].index("--note") + 1] == "BYX-MVP:PAY:v1:" + intent["id"]
    assert "10000ubyx" in calls[0]
    assert json.loads(capsys.readouterr().out)["fee_ubyx"] == 10000
    monkeypatch.setattr(helper.sys, "stdin", io.StringIO(json.dumps(intent)))
    with pytest.raises(FileExistsError):
        helper.main()
    assert len(calls) == 1
