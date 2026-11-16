#!/usr/bin/env python3
"""Capture named-device metadata without contacts, serial numbers or message logcat."""
import argparse
import datetime
import hashlib
import json
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--phone', action='append', required=True, metavar='LABEL=ADB_SERIAL')
parser.add_argument('--apk', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
if len(args.phone) != 3:
    parser.error('Provide three phones, e.g. --phone A=serial --phone B=serial --phone C=serial')
phones = []
labels = set()
serials = set()
for item in args.phone:
    label, sep, serial = item.partition('=')
    if not sep or label not in ('A', 'B', 'C') or label in labels or not serial or serial in serials:
        parser.error('Use distinct devices labelled A, B and C')
    labels.add(label)
    serials.add(serial)
    def prop(name):
        return subprocess.check_output(['adb', '-s', serial, 'shell', 'getprop', name], text=True, timeout=15).strip()
    phones.append({'label': label, 'model': prop('ro.product.model'), 'manufacturer': prop('ro.product.manufacturer'),
                   'api': prop('ro.build.version.sdk'), 'android': prop('ro.build.version.release')})
report = {'schema': 1, 'capturedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'apkSha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(), 'phones': phones,
          'status': 'SETUP_ONLY_NOT_TESTED', 'results': [],
          'instructions': 'Follow TESTING.md. Attach aggregate exports and observations; metadata is not a passed test.'}
args.output.parent.mkdir(parents=True, exist_ok=True)
with args.output.open('x') as out:
    json.dump(report, out, indent=2)
print(f'Wrote setup metadata to {args.output}; no tests claimed and no APK installed.')
