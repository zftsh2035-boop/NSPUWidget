import base64
import json
import unittest
from prepare_signing import configuration


class SigningConfigurationTest(unittest.TestCase):
    def bundle(self):
        return dict(keystore_base64=base64.b64encode(b'example' * 50).decode(),
                    store_password='example-store-password', key_alias='release', key_password='example-key-password')

    def test_stable_build_cannot_silently_change_to_a_temporary_signature(self):
        with self.assertRaises(ValueError):
            configuration({'NSPU_REQUIRE_SIGNING': 'true'})
        self.assertIsNone(configuration({}))

    def test_bundle_and_legacy_secrets_resolve_to_the_same_material(self):
        bundle = self.bundle()
        expected = configuration({'NSPU_SIGNING_BUNDLE': json.dumps(bundle)})
        actual = configuration(dict(zip(('NSPU_KEYSTORE_B64', 'NSPU_KEYSTORE_PASSWORD', 'NSPU_KEY_ALIAS', 'NSPU_KEY_PASSWORD'), bundle.values())))
        self.assertEqual(expected, actual)

    def test_partial_or_invalid_configuration_is_rejected_without_echoing_values(self):
        for value in ('not-json', '{}', '[]', json.dumps({'store_password': 'PRIVATE'}),
                      json.dumps(dict(self.bundle(), key_password='PRIVATE\nENV_INJECTION=1'))):
            with self.assertRaises(ValueError) as raised:
                configuration({'NSPU_SIGNING_BUNDLE': value, 'NSPU_REQUIRE_SIGNING': 'true'})
            self.assertNotIn('PRIVATE', str(raised.exception))

    def test_invalid_base64_is_rejected(self):
        with self.assertRaises(ValueError):
            configuration({'NSPU_SIGNING_BUNDLE': json.dumps(dict(self.bundle(), keystore_base64='invalid%%'))})


if __name__ == '__main__':
    unittest.main()
