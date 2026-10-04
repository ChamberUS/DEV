#!/usr/bin/env python3
"""DEV LOCALNET: one 2 BYX test transfer to exercise the experimental PLUS threshold."""
import fcntl
import json
import os
import subprocess
import time
import urllib.request

from byx_localnet import BINARY, NODE, ROOT, checked, process


def get(path):
    with urllib.request.urlopen('http://127.0.0.1:1417' + path, timeout=3) as response:
        return json.load(response)


def require(value, message):
    if not value:
        raise RuntimeError(message)


def main():
    os.umask(0o077)
    with (ROOT / 'control.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        m = checked()
        require(process(), 'Localnet required')
        require(get('/cosmos/base/tendermint/v1beta1/node_info')['default_node_info']['network'] == m['chain_id'], 'Chain mismatch')
        def balance(name):
            coin = get('/cosmos/bank/v1beta1/balances/' + m['addresses'][name] + '/by_denom?denom=ubyx')['balance']
            require(coin['denom'] == 'ubyx', 'Denom mismatch')
            return int(coin['amount'])
        before = {name: balance(name) for name in ['alice-test', 'bob-test']}
        require(before['alice-test'] < 1000000000 <= before['alice-test'] + 2000000, 'Expected development tier crossing; refuse other state')
        with (ROOT / 'evidence/wallet-tier-transfer.claim').open('x') as f:
            f.write('single DEV LOCALNET transfer\n')
        response = subprocess.run([str(BINARY), 'tx', 'bank', 'send', 'bob-test', m['addresses']['alice-test'],
                                   '2000000ubyx', '--fees', '10000ubyx', '--gas', '200000', '--home', str(NODE),
                                   '--chain-id', m['chain_id'], '--node', 'tcp://127.0.0.1:27657',
                                   '--keyring-backend', 'test', '--broadcast-mode', 'sync', '--yes', '--output', 'json'],
                                  capture_output=True, text=True, check=True)
        tx = json.loads(response.stdout)
        require(int(tx.get('code', 0)) == 0, 'Broadcast failed')
        evidence = ROOT / 'evidence/wallet-tier-transfer.json'
        evidence.write_text(json.dumps({'broadcast': tx, 'before': before}))
        confirmed = None
        for _ in range(40):
            result = subprocess.run([str(BINARY), 'query', 'tx', tx['txhash'], '--home', str(NODE),
                                     '--node', 'tcp://127.0.0.1:27657', '--output', 'json'], capture_output=True, text=True, check=False)
            if result.returncode == 0:
                confirmed = json.loads(result.stdout)
                break
            time.sleep(1)
        require(confirmed and int(confirmed['code']) == 0 and int(confirmed['height']) > 0, 'Transaction not committed successfully')
        after = {name: balance(name) for name in before}
        require(after['alice-test'] - before['alice-test'] == 2000000, 'Recipient mismatch')
        require(before['bob-test'] - after['bob-test'] == 2010000, 'Sender/fee mismatch')
        output = {'chain_id': m['chain_id'], 'before': before, 'after': after, 'txhash': tx['txhash'],
                  'height': confirmed['height'], 'code': confirmed['code'], 'amount_ubyx': 2000000,
                  'fee_ubyx': 10000, 'test_only': True}
        evidence.write_text(json.dumps(output, indent=2) + '\n')
        print(json.dumps(output))


if __name__ == '__main__':
    main()
