#!/usr/bin/env python3
"""Independent origin/identity probes, including a stopped foreign-origin signed helper. No key operations."""
import importlib.util
import json
import os
from pathlib import Path
import selectors
import secrets
import stat
import subprocess
import sys
import tempfile
import time

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('runtime', HERE / 'verify-runtime-hardening-qa.py')
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)
SERVICE, HELPER, APP = qa.SERVICE, qa.HELPER, qa.APP
ROOT = qa.ROOT


def independent_java():
    # jpackage intentionally strips native commands, including bin/java. Use the genuine
    # signed native Service/JVM in a standalone origin: same identity, wrong enclosing app.
    standalone = ROOT / 'standalone/byx-custody-qa-service.app'
    standalone.parent.mkdir(mode=0o700)
    subprocess.run(['/bin/cp', '-cR', str(SERVICE.parent.parent.parent), str(standalone)], check=True)
    command = [standalone / 'Contents/MacOS/byx-custody-qa-service', 'fencing-count']
    rc, output, error = qa.run(command)
    qa.check('same-Team signed embedded Service at wrong origin cannot acquire authority', rc == 0 and 'fencing.result=SIGNER_UNTRUSTED' in output
             and 'fencing.authority=QUIESCENT' not in output and 'fencing.child=' not in output)
    panel = APP / 'Contents/MacOS/byx-custody-qa-panel'
    rc, output, _ = qa.run([panel, 'fencing-count'])
    # The new Service-origin guard rejects this Panel executable before helper caller checks.
    qa.check('signed Panel origin refused before custody authority', rc == 0 and 'fencing.result=SIGNER_UNTRUSTED' in output
             and 'fencing.authority=QUIESCENT' not in output and 'fencing.child=' not in output)


def helper_symlink():
    original = HELPER.with_name(HELPER.name + '.owned-original')
    HELPER.rename(original)
    try:
        HELPER.symlink_to(original.name)
        rc, output, error = qa.run([SERVICE, 'fencing-count'])
        # Native launcher verifies its own Service bundle; the Service checks the enclosing
        # app and sibling helper before authority. A sibling mutation reaches this guard.
        qa.check('helper executable symlink refused before authority or child', rc == 0 and 'fencing.result=SIGNER_UNTRUSTED' in output
                 and 'fencing.authority=QUIESCENT' not in output and 'fencing.child=' not in output)
    finally:
        HELPER.unlink()
        original.rename(HELPER)
    parent = APP.parent
    mode = parent.stat().st_mode & 0o777
    try:
        parent.chmod(mode | 0o020)
        rc, output, error = qa.run([SERVICE, 'fencing-count'])
        qa.check('writable installation ancestry refused before JVM', rc == 76 and not output and 'unsafe install permissions' in error)
    finally:
        parent.chmod(mode)


def foreign_origin():
    destination = ROOT / 'foreign/BYX-MVP.app'
    destination.parent.mkdir(mode=0o700)
    subprocess.run(['/bin/cp', '-cR', str(APP), str(destination)], check=True)
    foreign = destination / HELPER.relative_to(APP)
    process = subprocess.Popen([str(foreign)], env={}, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    instance = qa.fencing.token(process.pid)
    qa.OWNED.append((process, instance))
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    socket = None
    try:
        process.stdin.write(secrets.token_hex(16).encode('ascii') + b'\n')
        process.stdin.flush()
        buffer = b''
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline and process.poll() is None:
            if selector.select(0.1):
                buffer += os.read(process.stdout.fileno(), 8192)
                if len(buffer) >= 4:
                    size = int.from_bytes(buffer[:4], 'big')
                    if len(buffer) >= size + 4:
                        break
        qa.check('foreign-origin genuine signed helper reaches public ready', len(buffer) >= 4 and len(buffer) >= int.from_bytes(buffer[:4], 'big') + 4)
        ready = json.loads(buffer[4:4 + int.from_bytes(buffer[:4], 'big')])
        socket = Path(ready['path'])
        qa.check('foreign helper exact PID from kernel token and ready match', ready['pid'] == instance[5] and qa.fencing.path(instance) == str(foreign))
        qa.fencing.signal(instance, 17)
        rc, output, _ = qa.run([SERVICE, 'fencing-count'])
        qa.check('foreign same-UID helper causes unproven quiescence before authority', rc == 0 and 'fencing.result=CUSTODY_QUIESCENCE_UNPROVEN' in output
                 and 'fencing.authority=QUIESCENT' not in output and 'fencing.child=' not in output)
        qa.check('Service never kills untrusted foreign instance', qa.fencing.path(instance) == str(foreign))
    finally:
        selector.close()
        qa.fencing.signal(instance, 9)
        process.wait(timeout=5)
        qa.check('exact owned foreign helper externally absent after teardown', qa.fencing.gone(instance))
        if socket is not None and socket.exists():
            metadata = socket.lstat()
            qa.check('foreign helper exact socket is owned private socket', stat.S_ISSOCK(metadata.st_mode)
                     and metadata.st_uid == os.getuid() and stat.S_IMODE(metadata.st_mode) == 0o600)
            socket.unlink()
            socket.parent.rmdir()
    rc, output, _ = qa.run([SERVICE, 'fencing-count'])
    qa.check('after exact foreign teardown authority and readonly count work', rc == 0 and 'fencing.authority=QUIESCENT' in output and 'fencing.result=COUNT' in output)


def main():
    try:
        independent_java()
        helper_symlink()
        foreign_origin()
        qa.check('origin probes restore final strict deep seal', subprocess.run(['codesign', '--verify', '--deep', '--strict', str(APP)], capture_output=True).returncode == 0)
    except Exception as error:
        qa.CASES.append({'case': 'execution', 'pass': False, 'reason': str(error)[:600]})
        print('FAIL ' + str(error), flush=True)
    finally:
        for process, instance in reversed(qa.OWNED):
            if process.poll() is None:
                qa.fencing.signal(instance, 9)
                process.wait(timeout=5)
        result = {'cases': qa.CASES, 'passed': sum(c['pass'] for c in qa.CASES), 'failed': sum(not c['pass'] for c in qa.CASES),
                  'skipped': 0, 'root': str(ROOT), 'keychainMutations': 0, 'realKeys': 0, 'broadcasts': 0}
        Path(sys.argv[1]).write_text(json.dumps(result, indent=2) + '\n')
    return int(any(not c['pass'] for c in qa.CASES))


if __name__ == '__main__':
    sys.exit(main())
