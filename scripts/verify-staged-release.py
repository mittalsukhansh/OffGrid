#!/usr/bin/env python3
"""Validate public, pre-signed artifacts. This script never reads a private key or password."""
import hashlib
import json
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
folder = root / 'release-staging'
manifest = json.loads((folder / 'manifest.json').read_text())
source = manifest['sourceCommit']
if manifest['version'] != '1.0' or not re.fullmatch('[0-9a-f]{40}', source):
    raise SystemExit('Invalid release manifest')
parent = subprocess.check_output(['git', 'rev-parse', 'HEAD^'], text=True).strip()
if parent != source:
    raise SystemExit('Staging must be a single commit on the exact release source')
subprocess.run(['git', 'merge-base', '--is-ancestor', source, 'origin/main'], check=True)
required = {'offgrid-1.0.apk', 'baseline.apk', 'baseline-test.apk', 'upgrade-test.apk'}
if set(manifest['sha256']) != required:
    raise SystemExit('Unexpected artifact set')
for name in sorted(required):
    path = folder / name
    if path.stat().st_size > 40 * 1024 * 1024 or hashlib.sha256(path.read_bytes()).hexdigest() != manifest['sha256'][name]:
        raise SystemExit('Artifact hash/size mismatch: ' + name)
subprocess.run(['python3', 'scripts/verify-release-apk.py', str(folder/'offgrid-1.0.apk'), '--version', '1.0',
                '--source-sha', source, '--signature-output', str(folder/'signature.txt')], check=True)
subprocess.run(['python3', 'scripts/verify-release-apk.py', str(folder/'baseline.apk'), '--version', '0.3-dev'], check=True)
(folder/'SHA256SUMS').write_text(''.join(f'{manifest["sha256"][name]}  {name}\n' for name in sorted(required)))
print('Verified signed source artifact and public upgrade fixtures for ' + source)
