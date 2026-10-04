#!/usr/bin/env python3
"""One explicitly requested transfer between this localnet's two test accounts."""
import fcntl
import json
import os
import subprocess
import time
import urllib.request
from byx_localnet import ROOT, BINARY, NODE, checked, process


def get(path):
    with urllib.request.urlopen('http://127.0.0.1:1417' + path, timeout=3) as response:
        return json.load(response)


def balance(address):
    coin = get('/cosmos/bank/v1beta1/balances/' + address + '/by_denom?denom=ubyx')['balance']
    assert coin['denom'] == 'ubyx'
    return int(coin['amount'])


def main():
    os.umask(0o077)
    with (ROOT / 'control.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        manifest = checked()
        assert process(), 'Localnet must be running'
        assert get('/cosmos/base/tendermint/v1beta1/node_info')['default_node_info']['network'] == manifest['chain_id']
        proof = ROOT / 'evidence/transfer.json'
        # Exclusive claim prevents an accidental second transaction, including after a failed run.
        with (ROOT / 'evidence/transfer.claim').open('x') as claim:
            claim.write('LOCALNET test transfer only\n')
        sender, receiver = (manifest['addresses'][n] for n in ('alice-test', 'bob-test'))
        before = {'alice': balance(sender), 'bob': balance(receiver)}
        amount, fee = 1234567, 10000
        result = subprocess.run([str(BINARY), 'tx', 'bank', 'send', 'alice-test', receiver,
                                 str(amount) + 'ubyx', '--home', str(NODE), '--chain-id', manifest['chain_id'],
                                 '--node', 'tcp://127.0.0.1:27657', '--keyring-backend', 'test',
                                 '--fees', str(fee) + 'ubyx', '--gas', '200000', '--broadcast-mode', 'sync',
                                 '--yes', '--output', 'json'], capture_output=True, text=True, check=True)
        sent = json.loads(result.stdout)
        proof.write_text(json.dumps({'broadcast': sent, 'before': before}, indent=2))
        assert int(sent.get('code', 0)) == 0, 'Broadcast failed'
        tx = None
        for _ in range(40):
            response = subprocess.run([str(BINARY), 'query', 'tx', sent['txhash'], '--home', str(NODE),
                                       '--node', 'tcp://127.0.0.1:27657', '--output', 'json'], capture_output=True, text=True)
            if response.returncode == 0:
                tx = json.loads(response.stdout)
                break
            time.sleep(1)
        assert tx and int(tx['code']) == 0 and int(tx['height']) > 0, 'No successful committed transaction'
        after = {'alice': balance(sender), 'bob': balance(receiver)}
        charged = tx['tx']['auth_info']['fee']['amount']
        assert charged == [{'denom': 'ubyx', 'amount': str(fee)}], charged
        assert before['alice'] - after['alice'] == amount + fee
        assert after['bob'] - before['bob'] == amount
        evidence = dict(chain_id=manifest['chain_id'], txhash=sent['txhash'], height=tx['height'], code=tx['code'],
                        amount_ubyx=amount, fee_ubyx=fee, gas_used=tx['gas_used'], before=before, after=after,
                        reconciled=True, environment='LOCALNET', test_only=True)
        proof.write_text(json.dumps(evidence, indent=2) + '\n')
        print(json.dumps(evidence))


if __name__ == '__main__':
    main()
