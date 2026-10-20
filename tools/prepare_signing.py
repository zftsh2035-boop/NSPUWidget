"""Load protected CI signing material without ever putting it in the repository."""
import base64
import json
import os
from pathlib import Path


FIELDS = ('keystore_base64', 'store_password', 'key_alias', 'key_password')
LEGACY = ('NSPU_KEYSTORE_B64', 'NSPU_KEYSTORE_PASSWORD', 'NSPU_KEY_ALIAS', 'NSPU_KEY_PASSWORD')


def configuration(env):
    if env.get('NSPU_SIGNING_BUNDLE', '').strip():
        try:
            values = json.loads(env['NSPU_SIGNING_BUNDLE'])
        except (ValueError, TypeError):
            raise ValueError('Invalid signing bundle JSON') from None
        if not isinstance(values, dict):
            raise ValueError('Invalid signing bundle structure')
    else:
        values = dict(zip(FIELDS, (env.get(name, '') for name in LEGACY)))
    if not any(values.get(name) for name in FIELDS):
        if env.get('NSPU_REQUIRE_SIGNING', '').lower() == 'true':
            raise ValueError('Stable builds require NSPU_SIGNING_BUNDLE in repository Actions secrets')
        return None
    if any(not isinstance(values.get(name), str) or not values[name] or
           '\n' in values[name] or '\r' in values[name] for name in FIELDS):
        raise ValueError('Signing configuration must contain all four single-line fields')
    try:
        key = base64.b64decode(values['keystore_base64'], validate=True)
    except (ValueError, TypeError):
        raise ValueError('Invalid signing keystore encoding') from None
    if not 128 <= len(key) <= 65536:
        raise ValueError('Invalid signing keystore size')
    return key, {name: values[name] for name in FIELDS[1:]}


def main():
    try:
        config = configuration(os.environ)
        if config is None:
            print('Development build without production signing; stable publication is disabled without a key.')
            return
        key, values = config
        destination = Path(os.environ['RUNNER_TEMP']) / 'nspu-release.jks'
        destination.write_bytes(key)
        destination.chmod(0o600)
        # GitHub consumes these workflow commands and redacts later diagnostic output.
        for name in ('store_password', 'key_password'):
            print('::add-mask::' + values[name].replace('%', '%25'))
        with open(os.environ['GITHUB_ENV'], 'a') as output:
            output.write('NSPU_SIGNING_KEYSTORE=' + str(destination) + '\n')
            for key_name, field in zip(LEGACY[1:], FIELDS[1:]):
                output.write(key_name + '=' + values[field] + '\n')
        print('Persistent signing configuration loaded from protected GitHub Secrets.')
    except (ValueError, KeyError):
        print('::error::Signing configuration is missing or invalid. Check the private bundle in repository Actions secrets.')
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()
