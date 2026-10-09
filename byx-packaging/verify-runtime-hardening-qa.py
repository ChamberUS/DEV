#!/usr/bin/env python3
"""Independent packaged JVM/origin/install adversaries. Public fixtures only; no mutation or real authority."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('fencing', HERE / 'verify-custody-fencing-qa.py')
fencing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fencing)
APP = HERE / 'build-custody-qa/BYX-MVP.app'
SERVICE = fencing.SERVICE
HELPER = fencing.HELPER
CASES = []
OWNED = []
ROOT = Path(tempfile.mkdtemp(prefix='byx-runtime-qa-', dir='/private/tmp'))
ROOT.chmod(0o700)


def check(name, condition):
    CASES.append({'case': name, 'pass': bool(condition)})
    print(('PASS ' if condition else 'FAIL ') + name, flush=True)
    if not condition:
        raise RuntimeError(name)


def run(command, env=None, input_data=None):
    process = subprocess.Popen([str(v) for v in command], env=env or {'HOME': os.environ['HOME'], 'PATH': '/usr/bin:/bin'},
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    instance = fencing.token(process.pid)
    OWNED.append((process, instance))
    try:
        stdout, stderr = process.communicate(input_data, timeout=40)
    except subprocess.TimeoutExpired:
        fencing.signal(instance, 9)
        process.wait(timeout=5)
        check('timed out exact owned process absent', fencing.gone(instance))
        raise RuntimeError('RUNTIME_PROCESS_TIMEOUT')
    return process.returncode, stdout.decode(errors='replace'), stderr.decode(errors='replace')


def runtime_proof():
    rc, output, _ = run([SERVICE, 'runtime-verify'])
    check('sealed signed packaged runtime public golden verifier', rc == 0 and 'runtime.publicGoldenSignatureVerified=true' in output)
    for tag in ['legacyAbsent', 'authModuleAbsent', 'alteredBindingRefused', 'productionDisabled']:
        check('runtime ' + tag, 'runtime.' + tag + '=true' in output)
    check('runtime no custody or Keychain operation', 'runtime.keychainCalls=0' in output)
    for jar in APP.rglob('*.jar'):
        with zipfile.ZipFile(jar) as z:
            forbidden = [name for name in z.namelist() if name.endswith(('signer/SignerClient.class', 'signer/LegacyStdioSignerFixture.class', 'signer/SignerClientTest.class'))]
            check('no retired transport in ' + str(jar.relative_to(APP)), not forbidden)
            if 'tx/byx-direct-vector.json' in z.namelist():
                check('public packaged golden vector only', 'private_key_test_only' not in json.loads(z.read('tx/byx-direct-vector.json')))


def launcher_proof():
    for name, value in [('JAVA_TOOL_OPTIONS', '-Xbootclasspath/a:/untrusted'), ('_JAVA_OPTIONS', '-Duntrusted=1'),
                        ('JDK_JAVA_OPTIONS', '--class-path=/untrusted'), ('CLASSPATH', '/untrusted')]:
        rc, out, error = run([SERVICE, 'runtime-verify'], {name: value})
        check(name + ' refused before JVM', rc == 74 and not out and 'environment injection rejected' in error)
    env = {'JAVA_' + str(i): 'x' for i in range(600)}
    env['JAVA_TOOL_OPTIONS'] = '-Xbootclasspath/a:/untrusted'
    rc, out, error = run([SERVICE, 'runtime-verify'], env)
    check('more than 512 injection variables refused', rc == 74 and not out and 'environment injection rejected' in error)
    rc, out, _ = run([SERVICE, 'runtime-verify', '-cp', '/untrusted', 'java.lang.System'])
    check('argv cannot change JVM classpath or main', rc == 0 and 'runtime.publicGoldenSignatureVerified=true' in out)
    cfg = SERVICE.parent.parent / 'app/byx-custody-qa-service.cfg'
    original = cfg.read_bytes()
    try:
        cfg.write_bytes(original + b'\napp.mainclass=java.lang.System\n')
        rc, out, error = run([SERVICE, 'runtime-verify'])
        check('changed cfg breaks strict seal before JVM', rc == 71 and not out and 'bundle seal invalid' in error)
    finally:
        cfg.write_bytes(original)
    jar = SERVICE.parent.parent / 'app/byx-local-service-0.1.0.jar'
    original_mode = jar.stat().st_mode & 0o777
    try:
        jar.chmod(original_mode | 0o020)
        rc, out, error = run([SERVICE, 'runtime-verify'])
        check('group writable jar refused before JVM', rc == 76 and not out and 'unsafe classpath permissions' in error)
    finally:
        jar.chmod(original_mode)
    entry = 'everyone allow write'
    try:
        subprocess.run(['/bin/chmod', '+a', entry, str(jar)], check=True)
        rc, out, error = run([SERVICE, 'runtime-verify'])
        check('writable jar ACL refused before JVM', rc == 76 and not out and 'unsafe classpath permissions' in error)
    finally:
        subprocess.run(['/bin/chmod', '-a', entry, str(jar)], check=True)
    helper_mode = HELPER.stat().st_mode & 0o777
    try:
        HELPER.chmod(helper_mode | 0o020)
        rc, out, _ = run([HELPER], env={'PATH': '/usr/bin:/bin'}, input_data=b'1' * 32 + b'\n')
        check('Go helper rejects writable own executable before ready', rc == 20 and not out)
        rc, out, _ = run([SERVICE, 'fencing-count'])
        check('Service rejects writable helper before authority', rc == 0 and 'fencing.result=SIGNER_UNTRUSTED' in out and 'fencing.authority=QUIESCENT' not in out)
    finally:
        HELPER.chmod(helper_mode)
    rc, out, _ = run([SERVICE, 'runtime-verify'])
    check('restored artifact still executes same verified public fixture', rc == 0 and 'runtime.publicGoldenSignatureVerified=true' in out)


def main():
    try:
        runtime_proof()
        launcher_proof()
        check('final deep strict signed seal', subprocess.run(['codesign', '--verify', '--deep', '--strict', str(APP)], capture_output=True).returncode == 0)
    except Exception as error:
        CASES.append({'case': 'execution', 'pass': False, 'reason': str(error)[:600]})
        print('FAIL ' + str(error), flush=True)
    finally:
        for process, instance in reversed(OWNED):
            if process.poll() is None:
                fencing.signal(instance, 9)
                process.wait(timeout=5)
                check('owned runtime probe absent', fencing.gone(instance))
        result = {'cases': CASES, 'passed': sum(v['pass'] for v in CASES), 'failed': sum(not v['pass'] for v in CASES),
                  'skipped': 0, 'keychainMutations': 0, 'realKeys': 0, 'broadcasts': 0, 'root': str(ROOT)}
        target = Path(sys.argv[1]) if len(sys.argv) == 2 else HERE.parent / 'byx-local-service/docs/qa/v21u/runtime-evidence.json'
        target.write_text(json.dumps(result, indent=2) + '\n')
    return int(any(not v['pass'] for v in CASES))


if __name__ == '__main__':
    sys.exit(main())
