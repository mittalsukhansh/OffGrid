#!/usr/bin/env python3
"""Validate public pre-signed APKs; candidate checks never need private signing material."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

VERSION = re.compile(r'[0-9]+\.[0-9]+(?:\.[0-9]+)?(?:-[a-z0-9]+(?:[.-][a-z0-9]+)*)?')
SHA = re.compile(r'[0-9a-f]{40}')
DIGEST = re.compile(r'[0-9a-f]{64}')


def validate_manifest(manifest):
    version = manifest.get('version', '')
    source = manifest.get('sourceCommit', '')
    if not isinstance(version, str) or not VERSION.fullmatch(version):
        raise ValueError('Invalid version')
    if not isinstance(source, str) or not SHA.fullmatch(source):
        raise ValueError('Invalid source commit')
    schema = manifest.get('schema', 1)
    if type(schema) is not int or schema not in (1, 2):
        raise ValueError('Unsupported staging schema')
    publish = manifest.get('publish', False)
    if type(publish) is not bool:
        raise ValueError('publish must be a boolean')
    if schema == 1:
        if version != '1.0':
            raise ValueError('Legacy manifests only describe the historical 1.0 release')
        baseline = '0.3-dev'
    else:
        baseline = manifest.get('baselineVersion', '')
        baseline_source = manifest.get('baselineSource', '')
        if not isinstance(baseline, str) or not VERSION.fullmatch(baseline):
            raise ValueError('Invalid baseline version')
        if not isinstance(baseline_source, str) or not SHA.fullmatch(baseline_source):
            raise ValueError('Invalid baseline source')
        new_code, old_code = manifest.get('versionCode'), manifest.get('baselineVersionCode')
        if type(new_code) is not int or type(old_code) is not int or not 0 < old_code < new_code <= 2100000000:
            raise ValueError('versionCode must advance from the baseline')
        # No stable-labelled publication until real-device evidence and a reviewed policy change.
        if publish and not version.endswith('-experimental'):
            raise ValueError('New publications must explicitly use an experimental version')
    apk = f'offgrid-{version}.apk'
    required = {apk, 'baseline.apk', 'baseline-test.apk', 'upgrade-test.apk'}
    hashes = manifest.get('sha256')
    if not isinstance(hashes, dict) or set(hashes) != required:
        raise ValueError('Unexpected artifact set')
    if any(not isinstance(h, str) or not DIGEST.fullmatch(h) for h in hashes.values()):
        raise ValueError('Invalid artifact digest')
    return dict(version=version, source=source, baseline=baseline, apk=apk,
                tag='v' + version, publish=str(publish).lower())


def main():
    root = Path(__file__).resolve().parents[1]
    folder = root / 'release-staging'
    manifest = json.loads((folder / 'manifest.json').read_text())
    meta = validate_manifest(manifest)
    parent = subprocess.check_output(['git', 'rev-parse', 'HEAD^'], text=True).strip()
    if parent != meta['source']:
        raise SystemExit('Staging must be a single commit on the exact release source')
    subprocess.run(['git', 'merge-base', '--is-ancestor', meta['source'], 'origin/main'], check=True)
    for name, digest in manifest['sha256'].items():
        path = folder / name
        if path.is_symlink() or not 0 < path.stat().st_size <= 40 * 1024 * 1024 or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise SystemExit('Artifact hash/size mismatch: ' + name)
    verify = ['python3', 'scripts/verify-release-apk.py']
    current = verify + [str(folder / meta['apk']), '--version', meta['version'],
                        '--source-sha', meta['source'], '--signature-output', str(folder / 'signature.txt')]
    baseline = verify + [str(folder / 'baseline.apk'), '--version', meta['baseline']]
    if manifest.get('schema') == 2:
        current += ['--version-code', str(manifest['versionCode'])]
        baseline += ['--version-code', str(manifest['baselineVersionCode']), '--source-sha', manifest['baselineSource']]
    subprocess.run(current, check=True)
    subprocess.run(baseline, check=True)
    pin = json.loads((root / 'signing/release-identity.json').read_text())['certificateSha256']
    signer = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0/apksigner'
    analyzer = shutil.which('apkanalyzer')
    for name in ('baseline-test.apk', 'upgrade-test.apk'):
        apk = str(folder / name)
        signature = subprocess.check_output([str(signer), 'verify', '--print-certs', apk], text=True)
        actual = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', signature, re.M)
        if len(actual) != 1 or actual[0].lower() != pin:
            raise SystemExit('Test fixture has an unpinned signer: ' + name)
        package = subprocess.check_output([analyzer, 'manifest', 'application-id', apk], text=True).strip()
        if package != 'io.github.mittalsukhansh.offgrid.test':
            raise SystemExit('Wrong instrumentation package: ' + name)
    # Public release checksum lists only files actually published, not private test fixtures.
    (folder / 'SHA256SUMS').write_text(f'{manifest["sha256"][meta["apk"]]}  {meta["apk"]}\n')
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            for key, value in meta.items():
                output.write(f'{key}={value}\n')
    print('Verified signed candidate and upgrade fixtures for ' + meta['source'])


if __name__ == '__main__':
    main()
