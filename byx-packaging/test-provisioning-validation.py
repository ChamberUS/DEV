#!/usr/bin/env python3
"""Profile identity/expiry negatives independent of the signed app launch checks."""
import copy
import datetime
import importlib.util
import json
import pathlib
import unittest

spec = importlib.util.spec_from_file_location('profile', pathlib.Path(__file__).with_name('validate-provisioning.py'))
profile = importlib.util.module_from_spec(spec)
spec.loader.exec_module(profile)


class ProfileTest(unittest.TestCase):
    def setUp(self):
        self.now = datetime.datetime(2026, 10, 8, tzinfo=datetime.timezone.utc)
        self.document = {'ExpirationDate': datetime.datetime(2026, 10, 13), 'Entitlements': {
            'com.apple.application-identifier': 'W5Z65G9UP2.com.buynnex.byx.service', 'com.apple.developer.team-identifier': 'W5Z65G9UP2'},
            'TeamIdentifier': ['W5Z65G9UP2'], 'ApplicationIdentifierPrefix': ['W5Z65G9UP2'], 'UUID': 'b7f753ed-f641-4459-88c2-fe2c9300aea0',
            'DeveloperCertificates': [b'synthetic-certificate-placeholder']}

    def validate(self, document):
        return profile.validate(document, 'com.buynnex.byx.service', 'W5Z65G9UP2', self.now)

    def test_valid_identity_and_expiry(self):
        self.assertEqual(self.validate(self.document)['daysRemaining'], 5)

    def test_expired_and_exact_expiry_refused(self):
        for expiry in [self.now.replace(tzinfo=None), self.now.replace(tzinfo=None) - datetime.timedelta(seconds=1)]:
            d = copy.deepcopy(self.document)
            d['ExpirationDate'] = expiry
            with self.assertRaisesRegex(profile.ProfileFailure, 'PROFILE_EXPIRED'):
                self.validate(d)

    def test_wrong_team_prefix_wildcard_and_missing_certificate_refused(self):
        for field, value in [('TeamIdentifier', ['WRONGTEAM0']), ('ApplicationIdentifierPrefix', ['wrong']), ('DeveloperCertificates', [])]:
            d = copy.deepcopy(self.document)
            d[field] = value
            with self.assertRaises(profile.ProfileFailure):
                self.validate(d)
        d = copy.deepcopy(self.document)
        d['Entitlements']['com.apple.application-identifier'] = 'W5Z65G9UP2.*'
        with self.assertRaisesRegex(profile.ProfileFailure, 'PROFILE_APPLICATION_MISMATCH'):
            self.validate(d)

    def test_selected_certificate_must_be_authorized(self):
        import hashlib
        expected = hashlib.sha1(self.document['DeveloperCertificates'][0]).hexdigest()
        self.assertEqual(profile.validate(self.document, 'com.buynnex.byx.service', 'W5Z65G9UP2', self.now, expected)['team'], 'W5Z65G9UP2')
        with self.assertRaisesRegex(profile.ProfileFailure, 'PROFILE_SIGNING_CERTIFICATE_NOT_AUTHORIZED'):
            profile.validate(self.document, 'com.buynnex.byx.service', 'W5Z65G9UP2', self.now, '0' * 40)

    def test_missing_or_malformed_identity_fields_refused(self):
        for field in self.document:
            d = copy.deepcopy(self.document)
            del d[field]
            with self.assertRaises(profile.ProfileFailure):
                self.validate(d)


if __name__ == '__main__':
    unittest.main()
