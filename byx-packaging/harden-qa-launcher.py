#!/usr/bin/env python3
"""Compile the same sealed native launcher for explicit QA roles; no runtime JVM configuration."""
import pathlib
import re
import subprocess
import sys


def main():
    if len(sys.argv) != 6:
        raise SystemExit('usage: harden-qa-launcher.py executable cfg main-class identifier team')
    executable, cfg, main_class, identifier, team = sys.argv[1:]
    if not re.fullmatch(r'[A-Za-z0-9_.]+', main_class) or not re.fullmatch(r'[A-Za-z0-9_.]+', identifier) or not re.fullmatch(r'[A-Z0-9]{10}', team):
        raise SystemExit('QA_LAUNCHER_IDENTITY_INVALID')
    jars = [line.removeprefix('app.classpath=$APPDIR/').strip() for line in pathlib.Path(cfg).read_text().splitlines() if line.startswith('app.classpath=$APPDIR/')]
    if not jars or len(jars) > 64 or any(not re.fullmatch(r'[A-Za-z0-9_.-]+\.jar', name) for name in jars):
        raise SystemExit('QA_LAUNCHER_CLASSPATH_INVALID')
    source = pathlib.Path(__file__).resolve().parent / 'launcher/byx-launcher.c'
    command = ['/usr/bin/clang', '-arch', 'x86_64', '-O2', '-Wall', '-Werror', '-mmacosx-version-min=12.0',
               '-DMAIN_CLASS="' + main_class + '"', '-DJAR_LIST=' + ','.join('"' + name + '"' for name in jars),
               '-DEXPECTED_ID="' + identifier + '"', '-DEXPECTED_TEAM="' + team + '"', '-DPASS_ARGS', '-DSQLITE_NATIVE', '-DREJECT_INJECTION',
               '-framework', 'Security', '-framework', 'CoreFoundation', '-o', executable, str(source)]
    subprocess.run(command, check=True)


if __name__ == '__main__':
    main()
