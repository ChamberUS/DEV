#!/usr/bin/env python3
"""QA fixture packaging: retain tests, omit the retired stdio transport and private golden scalar."""
import json
import pathlib
import sys
import zipfile

source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
canary_only = len(sys.argv) == 4 and sys.argv[3] == '--canary-only'
canary_prefixes = ('byx/service/auth/AuthQaMain', 'byx/service/migration/MigrateQaMain', 'byx/service/migration/LegacyPanelHash', 'byx/service/secrets/SecretCanary')
with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_DEFLATED) as jar:
    for file in sorted(source.rglob('*')):
        if not file.is_file():
            continue
        name = file.relative_to(source).as_posix()
        if canary_only and not name.startswith(canary_prefixes):
            continue
        if name.startswith(('byx/service/signer/LegacyStdioSignerFixture', 'byx/service/signer/SignerClientTest')):
            continue
        if name == 'tx/byx-direct-vector.json':
            public = json.loads(file.read_text())
            public.pop('private_key_test_only', None)
            jar.writestr(name, json.dumps(public, sort_keys=True))
        else:
            jar.write(file, name)
