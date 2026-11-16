#!/usr/bin/env python3
"""Emulator-only same-build reinstall proof; does not claim a version-transition or radio test."""
import json
from pathlib import Path
import shlex
import subprocess

PACKAGE = 'io.github.mittalsukhansh.offgrid.debug'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
CLASS = 'com.example.offgrid.UpgradeContinuityTest'
OUTPUT = Path('app/build/outputs/same-protocol-reinstall')


def adb(*args, timeout=45):
    result = subprocess.run(['adb', *args], capture_output=True, text=True, timeout=timeout, check=False)
    diagnostic = result.stdout + result.stderr
    if result.returncode != 0:
        raise RuntimeError('adb failed: ' + diagnostic.strip())
    return diagnostic


def instrument(method, mode):
    command = ['am', 'instrument', '-w', '-e', 'class', CLASS + '#' + method, '-e', 'upgrade', mode, RUNNER]
    result = adb('shell', shlex.join(command))
    (OUTPUT / (mode + '.txt')).write_text(result)
    if 'OK (1 test)' not in result:
        raise RuntimeError('Seed/continuity assertion failed; inspect ' + mode + '.txt')


def main():
    if adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
        raise SystemExit('Refusing reinstall test outside an emulator')
    OUTPUT.mkdir(parents=True, exist_ok=True)
    initial = [adb('install', '-t', '-r', 'app/build/outputs/apk/debug/app-debug.apk'),
               adb('install', '-t', '-r', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]
    if any('Success' not in value for value in initial):
        raise RuntimeError('Initial app/test installation failed')
    (OUTPUT / 'initial-installation.txt').write_text('\n'.join(initial))
    instrument('seed', 'seed')
    installs = [adb('install', '-t', '-r', 'app/build/outputs/apk/debug/app-debug.apk'),
                adb('install', '-t', '-r', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]
    if any('Success' not in value for value in installs):
        raise RuntimeError('Android did not accept in-place installation')
    (OUTPUT / 'installation.txt').write_text('\n'.join(installs))
    instrument('verify', 'verify')
    (OUTPUT / 'summary.json').write_text(json.dumps({
        'result': 'PASS', 'scope': 'Same-protocol, same-build Android APK reinstall on emulator; not an N-to-N+1 or radio claim',
        'checks': ['same identity/signing key', 'same verified two-key contact fingerprint', 'verification preserved',
                   'contact nickname preserved', 'encrypted history readable'],
        'package': PACKAGE, 'uninstallPerformed': False
    }, indent=2) + '\n')
    print('PASS: same-protocol APK reinstall preserved identity, verified contact binding and encrypted history')


if __name__ == "__main__":
    main()
