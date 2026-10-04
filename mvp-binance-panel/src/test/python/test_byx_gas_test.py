import importlib
import io
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest


@pytest.fixture
def helper(monkeypatch):
    monkeypatch.syspath_prepend(str(Path(__file__).resolve().parents[3] / "scripts"))
    return importlib.import_module("byx_gas_test")


def test_dev_opt_in_precedes_keyring(helper, monkeypatch):
    monkeypatch.delenv("BYX_LOCALNET_TEST_SIGNER", raising=False)
    with pytest.raises(SystemExit, match="opt-in"):
        helper.main()


def test_chain_grantee_and_sponsor_binding(helper):
    manifest = {
        "chain_id": "test",
        "genesis_fingerprint": "g",
        "addresses": {"alice-test": "alice", "bob-test": "bob"},
    }
    payload = {
        "chainId": "test",
        "genesis": "g",
        "granter": "sponsor",
        "grantee": "alice",
    }
    helper.validate(payload, manifest, "sponsor")
    for key, value in [
        ("chainId", "wrong"),
        ("genesis", "wrong"),
        ("granter", "wrong"),
        ("grantee", "validator"),
    ]:
        with pytest.raises(SystemExit):
            helper.validate({**payload, key: value}, manifest, "sponsor")


def test_bounded_native_grant_and_no_replenishment(helper, monkeypatch, tmp_path):
    monkeypatch.setenv("BYX_LOCALNET_TEST_SIGNER", "I_ACKNOWLEDGE_TEST_ONLY")
    manifest = {
        "chain_id": "test",
        "genesis_fingerprint": "g",
        "addresses": {"alice-test": "alice", "bob-test": "bob"},
    }
    monkeypatch.setattr(helper, "checked", lambda: manifest)
    monkeypatch.setattr(helper, "process", lambda: True)
    monkeypatch.setattr(helper, "key_address", lambda name: "sponsor")
    monkeypatch.setattr(helper, "ROOT", tmp_path)
    (tmp_path / "evidence").mkdir()
    calls = []
    monkeypatch.setattr(
        helper, "confirm", lambda *args: calls.append(args) or {"tx_hash": "a" * 64}
    )
    monkeypatch.setattr(helper.sys, "argv", ["helper", "grant"])
    payload = {
        "chainId": "test",
        "genesis": "g",
        "granter": "sponsor",
        "grantee": "alice",
        "limit": "30000",
        "expiration": (datetime.now(timezone.utc) + timedelta(seconds=300)).isoformat(),
    }
    for bad in [
        {**payload, "limit": "0"},
        {**payload, "limit": "1000001"},
        {**payload, "expiration": "2020-01-01T00:00:00Z"},
    ]:
        monkeypatch.setattr(helper.sys, "stdin", io.StringIO(json.dumps(bad)))
        with pytest.raises(SystemExit, match="Bounded"):
            helper.main()
    assert not calls
    monkeypatch.setattr(helper.sys, "stdin", io.StringIO(json.dumps(payload)))
    helper.main()
    assert "--spend-limit" in calls[0] and "30000ubyx" in calls[0]
    assert "--expiration" in calls[0] and "/cosmos.bank.v1beta1.MsgSend" in calls[0]
    monkeypatch.setattr(helper.sys, "stdin", io.StringIO(json.dumps(payload)))
    with pytest.raises(FileExistsError):
        helper.main()
    assert len(calls) == 1
