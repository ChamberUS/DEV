#!/usr/bin/env python3
"""Only the isolated BYX-MVP test home; no historical state or global binary writes."""
import argparse
import datetime as dt
import fcntl
import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import time
import urllib.request
import uuid

ROOT = Path.home() / '.byx-mvp-localnet-b-v1'
NODE = ROOT / 'node'
BINARY = ROOT / 'bin/byxd'
MANIFEST = ROOT / 'localnet.json'
PORTS = {'rpc': 27657, 'rest': 1417, 'grpc': 9190, 'p2p': 27656, 'abci': 27658}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def cli(*args, secret=False):
    result = subprocess.run([str(BINARY), *args, '--home', str(NODE)],
                            stdout=subprocess.DEVNULL if secret else subprocess.PIPE,
                            stderr=subprocess.DEVNULL if secret else subprocess.PIPE, text=True)
    if result.returncode:
        raise RuntimeError('byxd failed: ' + args[0] + (' (secret output suppressed)' if secret else '\n' + result.stderr[-1200:]))
    return result.stdout


def replace_section(path, updates):
    section = ''
    found = set()
    lines = []
    for line in path.read_text().splitlines():
        if line.startswith('['):
            section = line.strip()[1:-1]
        key = line.split('=', 1)[0].strip()
        pair = (section, key)
        if pair in updates and not line.lstrip().startswith('#'):
            line = key + ' = ' + updates[pair]
            found.add(pair)
        lines.append(line)
    if found != set(updates):
        raise RuntimeError('Missing config keys: ' + str(set(updates) - found))
    path.write_text('\n'.join(lines) + '\n')


def rpc_genesis(g):
    # Cosmos SDK AppGenesis.ToGenesisDoc, then CometBFT JSON wire representation.
    result = dict(genesis_time=g['genesis_time'], chain_id=g['chain_id'],
                  initial_height=str(g['initial_height']), app_hash=g['app_hash'] or '',
                  app_state=g['app_state'], consensus_params=g['consensus']['params'])
    if g['consensus'].get('validators'):
        result['validators'] = g['consensus']['validators']
    return result


def init():
    if NODE.exists() or MANIFEST.exists():
        raise RuntimeError('Refusing to overwrite existing localnet state')
    if not BINARY.is_file():
        raise RuntimeError('Compile the audited checkout to the isolated bin/byxd first')
    chain = 'byx-mvp-localnet-b-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d') + '-' + uuid.uuid4().hex[:8]
    cli('init', 'byx-mvp-test-only', '--chain-id', chain, '--default-denom', 'ubyx')
    addresses = {}
    for name in ('validator-test', 'alice-test', 'bob-test'):
        cli('keys', 'add', name, '--keyring-backend', 'test', secret=True)
        addresses[name] = cli('keys', 'show', name, '-a', '--keyring-backend', 'test').strip()
    amounts = {'validator-test': 1000000000000, 'alice-test': 1000000000, 'bob-test': 100000000}
    for name, amount in amounts.items():
        cli('genesis', 'add-genesis-account', addresses[name], str(amount) + 'ubyx')
    genesis = NODE / 'config/genesis.json'
    g = json.loads(genesis.read_text())
    state = g['app_state']
    state['staking']['params']['bond_denom'] = 'ubyx'
    for key in ('min_deposit', 'expedited_min_deposit'):
        for coin in state['gov']['params'][key]:
            coin['denom'] = 'ubyx'
    mint = state['mint']
    mint['minter']['inflation'] = '0.000000000000000000'
    mint['params']['mint_denom'] = 'ubyx'
    for key in ('inflation_rate_change', 'inflation_max', 'inflation_min'):
        mint['params'][key] = '0.000000000000000000'
    state['bank']['denom_metadata'] = [{
        'description': 'LOCALNET / ATIVOS DE TESTE / SEM VALOR FINANCEIRO',
        'denom_units': [{'denom': 'ubyx', 'exponent': 0}, {'denom': 'BYX', 'exponent': 6}],
        'base': 'ubyx', 'display': 'BYX', 'name': 'BYX Local Test Asset', 'symbol': 'BYX'}]
    state['lojas']['params']['faucet_enabled'] = False
    state['feesplit']['params']['denoms_allowlist'] = ['ubyx']
    genesis.write_text(json.dumps(g, indent=2) + '\n')
    cli('genesis', 'gentx', 'validator-test', '500000000000ubyx', '--chain-id', chain, '--keyring-backend', 'test')
    cli('genesis', 'collect-gentxs')
    cli('genesis', 'validate', str(genesis))
    replace_section(NODE / 'config/config.toml', {
        ('', 'proxy_app'): '"tcp://127.0.0.1:27658"',
        ('rpc', 'laddr'): '"tcp://127.0.0.1:27657"',
        ('rpc', 'pprof_laddr'): '""',
        ('p2p', 'laddr'): '"tcp://127.0.0.1:27656"',
        ('p2p', 'external_address'): '""', ('p2p', 'seeds'): '""',
        ('p2p', 'persistent_peers'): '""', ('p2p', 'pex'): 'false',
        ('p2p', 'max_num_inbound_peers'): '0', ('p2p', 'max_num_outbound_peers'): '0',
        ('instrumentation', 'prometheus'): 'false'})
    replace_section(NODE / 'config/app.toml', {
        ('', 'minimum-gas-prices'): '"0.025ubyx"',
        ('api', 'enable'): 'true', ('api', 'address'): '"tcp://127.0.0.1:1417"',
        ('grpc', 'enable'): 'true', ('grpc', 'address'): '"127.0.0.1:9190"',
        ('grpc-web', 'enable'): 'false'})
    g = json.loads(genesis.read_text())
    manifest = dict(purpose='BYX-MVP LOCALNET TEST ONLY', chain_id=chain, home=str(NODE),
                    binary=str(BINARY), binary_sha256=digest(BINARY), ports=PORTS,
                    genesis_sha256=digest(genesis), genesis_fingerprint=hashlib.sha256(
                        json.dumps(rpc_genesis(g), sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest(),
                    config_sha256={n: digest(NODE / 'config' / n) for n in ('config.toml', 'app.toml')},
                    metadata=g['app_state']['bank']['denom_metadata'], addresses=addresses,
                    initial_ubyx=amounts, validator_bond_ubyx=500000000000)
    with MANIFEST.open('x') as f:
        json.dump(manifest, f, indent=2)
    print(json.dumps(manifest, indent=2))


def checked():
    m = json.loads(MANIFEST.read_text())
    if (m['purpose'] != 'BYX-MVP LOCALNET TEST ONLY' or m['home'] != str(NODE)
            or m['binary'] != str(BINARY) or m['ports'] != PORTS
            or not m['chain_id'].startswith('byx-mvp-localnet-b-')
            or m['binary_sha256'] != digest(BINARY)
            or m['genesis_sha256'] != digest(NODE / 'config/genesis.json')
            or any(digest(NODE / 'config' / n) != h for n, h in m['config_sha256'].items())):
        raise RuntimeError('Localnet identity/config changed; refusing operation')
    return m


def process():
    path = ROOT / 'node.pid'
    if not path.exists():
        return None
    state = json.loads(path.read_text())
    pid = state['pid']
    if not isinstance(pid, int) or pid <= 1:
        raise RuntimeError('Invalid PID')
    result = subprocess.run(['ps', '-p', str(pid), '-o', 'lstart=', '-o', 'command='], capture_output=True, text=True)
    if result.returncode != 0:
        return None
    if result.stdout.strip() != state['identity'] or f'{BINARY} start --home {NODE}' not in result.stdout:
        raise RuntimeError('PID identity mismatch; refusing signal/start')
    return pid


def status():
    m = checked()
    pid = process()
    out = dict(pid=pid, chain_id=m['chain_id'], environment='LOCALNET', test_assets=True)
    if pid:
        with urllib.request.urlopen('http://127.0.0.1:27657/status', timeout=3) as response:
            info = json.load(response)['result']
        if info['node_info']['network'] != m['chain_id']:
            raise RuntimeError('RPC chain mismatch')
        out.update(height=info['sync_info']['latest_block_height'], block_time=info['sync_info']['latest_block_time'])
    print(json.dumps(out))


def start():
    checked()
    if process():
        raise RuntimeError('Localnet already running; refusing duplicate')
    for port in PORTS.values():
        with socket.socket() as sock:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(('127.0.0.1', port))
    with (ROOT / 'node.log').open('ab') as log:
        child = subprocess.Popen([str(BINARY), 'start', '--home', str(NODE)], stdout=log, stderr=log,
                                 start_new_session=True, env={**os.environ, 'GOMAXPROCS': '2'})
    identity = subprocess.check_output(['ps', '-p', str(child.pid), '-o', 'lstart=', '-o', 'command='], text=True).strip()
    (ROOT / 'node.pid').write_text(json.dumps(dict(pid=child.pid, identity=identity)))
    print(json.dumps(dict(started_pid=child.pid, environment='LOCALNET')))


def stop():
    checked()
    pid = process()
    if pid:
        os.kill(pid, signal.SIGTERM)
        for _ in range(100):
            if process() is None:
                break
            time.sleep(0.2)
        else:
            raise RuntimeError('Graceful stop timeout; no SIGKILL sent')
    (ROOT / 'node.pid').unlink(missing_ok=True)
    print('LOCALNET stopped; state preserved')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['init', 'start', 'status', 'stop'])
    args = parser.parse_args()
    os.umask(0o077)
    if ROOT.is_symlink() or not ROOT.is_dir() or any(p.is_symlink() for p in (NODE, BINARY, MANIFEST)):
        raise SystemExit('Prepare an isolated, non-symlink root and binary first')
    with (ROOT / 'control.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            globals()[args.action]()
        except (RuntimeError, OSError, ValueError, KeyError) as error:
            raise SystemExit(str(error)) from error
