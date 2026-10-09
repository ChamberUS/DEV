#!/usr/bin/env python3
"""Qualify test-only packaged canary entrypoints with a live positive item and exact cleanup."""
import importlib.util
import json
import os
from pathlib import Path
import selectors
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('fencing', HERE / 'verify-custody-fencing-qa.py')
fencing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fencing)
APP = HERE / 'build-custody-qa/BYX-MVP.app'
HELPERS = APP / 'Contents/Helpers'
ENV = {'HOME': os.environ['HOME'], 'PATH': '/usr/bin:/bin'}
CASES = []


def check(name, condition):
    CASES.append({'case': name, 'pass': bool(condition)})
    print(('PASS ' if condition else 'FAIL ') + name, flush=True)
    if not condition:
        raise RuntimeError(name)


def run(path):
    return subprocess.run([str(path)], env=ENV, capture_output=True, timeout=30)


def main():
    holder = None
    instance = None
    canary = HELPERS / 'byx-secret-canary.app/Contents/MacOS/byx-secret-canary'
    reader = HELPERS / 'byx-secret-servicereader.app/Contents/MacOS/byx-secret-servicereader'
    try:
        result = run(canary)  # harness also removes any exact TEST canary left by an interrupted run
        check('test-only packaged canary CRUD and cleanup', result.returncode == 0 and b'canary.result=OK' in result.stdout)
        holder = subprocess.Popen([str(HELPERS / 'byx-secret-holder.app/Contents/MacOS/byx-secret-holder')],
                                  env=ENV, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        instance = fencing.token(holder.pid)
        selector = selectors.DefaultSelector()
        selector.register(holder.stdout, selectors.EVENT_READ)
        output = b''
        deadline = time.monotonic() + 25
        try:
            while time.monotonic() < deadline and b'holder.ready=OK' not in output and holder.poll() is None:
                if selector.select(.2):
                    output += os.read(holder.stdout.fileno(), 4096)
        finally:
            selector.close()
        check('test-only signed holder positive ready', b'holder.ready=OK' in output)
        result = run(reader)
        check('Service reader sees live synthetic canary', result.returncode == 0 and b'reader.result=FOUND(' in result.stdout)
        result = run(APP / 'Contents/MacOS/byx-secret-reader')
        check('Panel reader identity has no configured Keychain authority while item exists', result.returncode == 1
              and b'reader.result=NOT_CONFIGURED' in result.stdout and b'reader.result=FOUND(' not in result.stdout)
        holder.stdin.close()
        holder.stdin = None
        output, _ = holder.communicate(timeout=25)
        check('holder deletes exact synthetic canary', holder.returncode == 0 and b'holder.cleanup=DELETED' in output)
        check('holder exact token externally absent', fencing.gone(instance))
        result = run(reader)
        check('synthetic canary absent after cleanup', result.returncode == 1 and b'reader.result=NOT_FOUND' in result.stdout)
    except Exception as error:
        CASES.append({'case': 'execution', 'pass': False, 'reason': str(error)[:300]})
    finally:
        if instance is not None and fencing.path(instance) is not None:
            fencing.signal(instance, 9)
            holder.wait(timeout=5)
        result = run(canary)
        check('final exact TEST-only canary cleanup', result.returncode == 0 and b'canary.result=OK' in result.stdout)
        Path(sys.argv[1]).write_text(json.dumps({'cases': CASES, 'passed': sum(c['pass'] for c in CASES),
                                               'failed': sum(not c['pass'] for c in CASES), 'skipped': 0,
                                               'realKeys': 0, 'broadcasts': 0}, indent=2) + '\n')
    return int(any(not c['pass'] for c in CASES))


if __name__ == '__main__':
    sys.exit(main())
