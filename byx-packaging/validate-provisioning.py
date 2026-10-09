#!/usr/bin/env python3
"""Read-only diagnostics for explicit Apple development profiles; never grants entitlements."""
import argparse
import datetime
import hashlib
import json
import pathlib
import plistlib
import re
import subprocess
import sys


class ProfileFailure(ValueError):
    pass


def validate(document, identifier, team, now=None, certificate_sha1=None):
    now = now or datetime.datetime.now(datetime.timezone.utc)
    try:
        expiry = document['ExpirationDate']
        expiry = expiry.replace(tzinfo=datetime.timezone.utc) if expiry.tzinfo is None else expiry.astimezone(datetime.timezone.utc)
        if expiry <= now:
            raise ProfileFailure('PROFILE_EXPIRED')
        ent = document['Entitlements']
        teams = document['TeamIdentifier']
        prefixes = document['ApplicationIdentifierPrefix']
        if len(teams) != 1 or teams[0] != team or ent.get('com.apple.developer.team-identifier') != team:
            raise ProfileFailure('PROFILE_TEAM_MISMATCH')
        if len(prefixes) != 1 or not re.fullmatch(r'[A-Z0-9]{10}', prefixes[0]):
            raise ProfileFailure('PROFILE_PREFIX_INVALID')
        application_id = prefixes[0] + '.' + identifier
        if ent['com.apple.application-identifier'] != application_id:
            raise ProfileFailure('PROFILE_APPLICATION_MISMATCH')
        uuid = document['UUID']
        if not re.fullmatch(r'[A-Fa-f0-9-]{36}', uuid) or not document.get('DeveloperCertificates'):
            raise ProfileFailure('PROFILE_METADATA_INVALID')
        if certificate_sha1 is not None and not any(isinstance(cert, bytes) and hashlib.sha1(cert).hexdigest().lower() == certificate_sha1.lower() for cert in document['DeveloperCertificates']):
            raise ProfileFailure('PROFILE_SIGNING_CERTIFICATE_NOT_AUTHORIZED')
        return {'applicationId': application_id, 'team': team, 'prefix': prefixes[0], 'expiresUtc': expiry.isoformat(), 'uuid': uuid,
                'daysRemaining': (expiry - now).total_seconds() / 86400}
    except (KeyError, TypeError, AttributeError, IndexError) as error:
        raise ProfileFailure('PROFILE_MALFORMED') from error


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('profile')
    parser.add_argument('identifier')
    parser.add_argument('team')
    parser.add_argument('--shell', action='store_true')
    parser.add_argument('--certificate-sha1')
    args = parser.parse_args()
    profile, identifier, team = args.profile, args.identifier, args.team
    if args.certificate_sha1 is not None and not re.fullmatch(r'[A-Fa-f0-9]{40}', args.certificate_sha1):
        raise ProfileFailure('PROFILE_CERTIFICATE_FINGERPRINT_INVALID')
    if not re.fullmatch(r'[A-Za-z0-9_.]+', identifier) or not re.fullmatch(r'[A-Z0-9]{10}', team):
        raise ProfileFailure('PROFILE_EXPECTED_IDENTITY_INVALID')
    path = pathlib.Path(profile)
    if path.is_symlink() or not path.is_file():
        raise ProfileFailure('PROFILE_MISSING_OR_SYMLINK')
    result = subprocess.run(['/usr/bin/security', 'cms', '-D', '-i', str(path)], capture_output=True, timeout=15)
    if result.returncode:
        raise ProfileFailure('PROFILE_CMS_UNREADABLE')
    try:
        document = plistlib.loads(result.stdout)
    except plistlib.InvalidFileException as error:
        raise ProfileFailure('PROFILE_MALFORMED') from error
    diagnostic = validate(document, identifier, team, certificate_sha1=args.certificate_sha1)
    if args.shell:
        print(diagnostic['applicationId'], diagnostic['team'], diagnostic['prefix'], diagnostic['expiresUtc'])
    else:
        print(json.dumps(diagnostic, sort_keys=True))


if __name__ == '__main__':
    try:
        main()
    except (ProfileFailure, subprocess.TimeoutExpired) as error:
        print('BLOCKED: ' + (str(error) if isinstance(error, ProfileFailure) else 'PROFILE_CMS_TIMEOUT'), file=sys.stderr)
        raise SystemExit(7)
