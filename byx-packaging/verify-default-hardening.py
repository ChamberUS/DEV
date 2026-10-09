#!/usr/bin/env python3
"""Qualify a newly built DEFAULT bundle: actual native diagnostics, module/bytecode/role and seal checks. No auth startup."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
APP = Path(sys.argv[1]).resolve()
TARGET = Path(sys.argv[2])
CASES = []
ROOT = Path(tempfile.mkdtemp(prefix='byx-default-runtime-qa-', dir='/private/tmp'))
ROOT.chmod(0o700)
CONTENTS = APP / 'Contents'
SERVICE_APP = CONTENTS / 'Helpers/byx-local-service.app'
SERVICE = SERVICE_APP / 'Contents/MacOS/byx-local-service'
PANEL_JAR = CONTENTS / 'app/mvp-binance-panel-0.1.0.jar'
SERVICE_JAR = SERVICE_APP / 'Contents/app/byx-local-service-0.1.0.jar'


def check(name, condition):
    CASES.append({'case': name, 'pass': bool(condition)})
    print(('PASS ' if condition else 'FAIL ') + name, flush=True)
    if not condition:
        raise RuntimeError(name)


def run(command, env=None):
    base = {'HOME': str(ROOT), 'BYX_LOCAL_SERVICE_HOME': str(ROOT / 'never-created'), 'PATH': '/usr/bin:/bin'}
    if env:
        base.update(env)
    return subprocess.run([str(p) for p in command], env=base, capture_output=True, timeout=20)


def main():
    try:
        requirement = '=identifier "com.buynnex.byx" and anchor apple generic and certificate leaf[subject.OU] = "W5Z65G9UP2"'
        check('DEFAULT Apple identity/Team plus deep strict seal', subprocess.run(['codesign', '--verify', '--deep', '--strict', '-R', requirement, str(APP)], capture_output=True).returncode == 0)
        check('DEFAULT no QA helper or launcher', not any('qa' in p.name.lower() for d in [CONTENTS / 'Helpers', CONTENTS / 'MacOS'] for p in d.iterdir()))
        check('DEFAULT no QA/test jar', not any('qa' in p.name.lower() or 'tests' in p.name.lower() for p in APP.rglob('*.jar')))
        with zipfile.ZipFile(PANEL_JAR) as jar:
            check('DEFAULT sealed Panel capability DISABLED', jar.read('panel/wallet-capability.txt').strip() == b'DISABLED')
            check('DEFAULT no transaction lab or QA UI', not any(name.startswith('panel/txview/') or 'WalletPanelQaMain' in name for name in jar.namelist()))
        with zipfile.ZipFile(SERVICE_JAR) as jar:
            check('DEFAULT chain PRODUCTION_DISABLED', jar.read('byx/chain-profile.txt').strip() == b'PRODUCTION_DISABLED')
            check('DEFAULT retired stdio transport and fixture absent', not any('SignerClient' in name or 'LegacyStdioSigner' in name for name in jar.namelist()))
            check('DEFAULT no custody QA/probe main or golden private vector', not any('QaMain' in name or name.startswith('tx/byx-direct-vector') for name in jar.namelist()))
            check('DEFAULT no synthetic secret canary or legacy hash oracle', not any('SecretCanary' in name or 'LegacyPanelHash' in name for name in jar.namelist()))
            check('DEFAULT pure verifier and read-only diagnostics present', all(name in jar.namelist() for name in ['byx/service/signer/SignedResponseVerifier.class', 'byx/service/RuntimeDiagnostics.class']))
        result = run([SERVICE, '--diagnostics'])
        output = result.stdout.decode()
        check('actual native DEFAULT Service starts and exits readonly diagnostics', result.returncode == 0 and 'service.diagnostics.schema=1' in output)
        for key, value in [('javaFeature', '21'), ('authModule', 'false'), ('txMutationsAllowed', 'false'), ('walletCapability', 'DISABLED'), ('keychainCalls', '0'), ('networkStarted', 'false')]:
            check('actual DEFAULT diagnostic ' + key, 'service.diagnostics.' + key + '=' + value in output)
        check('DEFAULT diagnostics create no authority/runtime directory', not (ROOT / 'never-created').exists())
        variables = {'JAVA_UNTRUSTED_' + str(i): 'x' for i in range(600)}
        variables.update({'JAVA_TOOL_OPTIONS': '-Xbootclasspath/a:/untrusted', 'JDK_JAVA_OPTIONS': '--class-path=/untrusted', 'CLASSPATH': '/untrusted'})
        result = run([SERVICE, '--diagnostics'], variables)
        check('DEFAULT scrub removes >512 injection entries and retains sealed main', result.returncode == 0 and 'service.diagnostics.schema=1' in result.stdout.decode()
              and 'Picked up' not in result.stderr.decode())
        result = run([SERVICE, '-cp', '/untrusted', '--diagnostics'])
        check('DEFAULT invalid argv rejected before authority', result.returncode == 2 and 'takes no arguments' in result.stderr.decode() and not (ROOT / 'never-created').exists())
        result = run([SERVICE, '--diagnostics'] + ['x'] * 400)
        check('DEFAULT argument overflow refused before JVM', result.returncode == 75 and 'too many arguments' in result.stderr.decode() and not result.stdout)
        release = (CONTENTS / 'runtime/Contents/Home/release').read_text()
        modules = next(line.split('=', 1)[1].strip('"').split() for line in release.splitlines() if line.startswith('MODULES='))
        check('sealed embedded DEFAULT module manifest excludes jdk.security.auth', 'jdk.security.auth' not in modules)
        check('DEFAULT strips generic Java command; native diagnostics prove boot module absence', not (CONTENTS / 'runtime/Contents/Home/bin/java').exists()
              and 'service.diagnostics.authModule=false' in output)
        check('DEFAULT target Service jar exact hash', SERVICE_JAR.read_bytes() == (HERE.parent / 'byx-local-service/target/byx-local-service-0.1.0.jar').read_bytes())
        check('DEFAULT target Panel jar exact hash', PANEL_JAR.read_bytes() == (HERE.parent / 'mvp-binance-panel/target/mvp-binance-panel-0.1.0.jar').read_bytes())
        check('DEFAULT final seal unchanged after probes', subprocess.run(['codesign', '--verify', '--deep', '--strict', str(APP)], capture_output=True).returncode == 0)
    except Exception as error:
        CASES.append({'case': 'execution', 'pass': False, 'reason': str(error)[:600]})
        print('FAIL ' + str(error), flush=True)
    result = {'artifact': str(APP), 'cases': CASES, 'passed': sum(v['pass'] for v in CASES), 'failed': sum(not v['pass'] for v in CASES),
              'skipped': 0, 'serviceJarSha256': hashlib.sha256(SERVICE_JAR.read_bytes()).hexdigest() if SERVICE_JAR.exists() else None,
              'panelJarSha256': hashlib.sha256(PANEL_JAR.read_bytes()).hexdigest() if PANEL_JAR.exists() else None,
              'normalProductionAuthStarted': False, 'nativeReadonlyDiagnosticsStarted': True, 'root': str(ROOT), 'realKeys': 0, 'broadcasts': 0}
    TARGET.write_text(json.dumps(result, indent=2) + '\n')
    return int(any(not v['pass'] for v in CASES))


if __name__ == '__main__':
    sys.exit(main())
