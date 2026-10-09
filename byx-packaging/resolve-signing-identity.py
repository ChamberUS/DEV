#!/usr/bin/env python3
"""Resolve the selected valid signing identity and its certificate Team, without choosing another certificate by name."""
import re
import subprocess
import sys


def main():
    selected = sys.argv[1] if len(sys.argv) == 2 else ''
    identities = subprocess.check_output(['/usr/bin/security', 'find-identity', '-v', '-p', 'codesigning'], text=True)
    matches = re.findall(r'\b([A-Fa-f0-9]{40})\s+"([^"]+)"', identities)
    choices = [(digest, name) for digest, name in matches if selected.lower() == digest.lower() or selected == name]
    if len(choices) != 1:
        raise SystemExit('BLOCKED: SIGNING_IDENTITY_MISSING_OR_AMBIGUOUS')
    digest, name = choices[0]
    pem = subprocess.check_output(['/usr/bin/security', 'find-certificate', '-a', '-c', name, '-p'])
    for cert in re.findall(rb'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----', pem, re.S):
        detail = subprocess.check_output(['/usr/bin/openssl', 'x509', '-noout', '-fingerprint', '-sha1', '-subject', '-nameopt', 'multiline'], input=cert).decode()
        fingerprint = re.search(r'Fingerprint=([A-Fa-f0-9:]+)', detail)
        team = re.search(r'organizationalUnitName\s*=\s*([A-Z0-9]{10})\s*(?:\n|$)', detail)
        if fingerprint and team and fingerprint[1].replace(':', '').lower() == digest.lower():
            print(digest.upper(), team[1])
            return
    raise SystemExit('BLOCKED: SIGNING_CERTIFICATE_TEAM_UNPROVEN')


if __name__ == '__main__':
    main()
