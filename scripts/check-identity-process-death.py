#!/usr/bin/env python3
"""Emulator-only SIGKILL regression; isolated preferences, no pm clear or real-phone execution."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import shlex
import uuid

PACKAGE = 'io.github.mittalsukhansh.offgrid.debug'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
CLASS = 'com.example.offgrid.IdentityProcessDeathTest'


def adb(*args, check=True, timeout=40):
    return subprocess.run(['adb', *args], capture_output=True, text=True, check=check, timeout=timeout)


def public_proof(text):
    fields = dict(re.findall(r'^INSTRUMENTATION_STATUS: (crash\w+)=(.*)\r?$', text, re.M))
    patterns = {'crashId': '[0-9a-f]{32}', 'crashKey': '[A-Za-z0-9_-]{87}',
                'crashFingerprint': '[0-9a-f]{64}', 'crashEpoch': '(?:[0-9a-f]{32})?', 'crashPid': '[1-9][0-9]*', 'crashProtocol': '[45]'}
    if set(fields) != set(patterns) or any(not re.fullmatch(patterns[k], v.strip()) for k, v in fields.items()):
        raise RuntimeError('Incomplete public identity proof; no successful crash test claimed')
    return {k: v.strip() for k, v in fields.items()}


def instrument(method, arguments):
    command = ['shell', 'am', 'instrument', '-r', '-w', '-e', 'class', CLASS + '#' + method]
    for name, value in arguments.items():
        command += ['-e', name, value]
    return adb('shell', shlex.join(command[1:] + [RUNNER]), check=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs', type=int, default=5, choices=range(1, 21))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--expected-protocol', type=int, choices=(4, 5), default=5)
    parser.add_argument('--historical', action='store_true', help='Record known old-code persistence losses without marking them PASS')
    args = parser.parse_args()
    if adb('shell', 'getprop', 'ro.kernel.qemu').stdout.strip() != '1':
        raise SystemExit('Refusing process-kill test outside an emulator')
    args.output.mkdir(parents=True, exist_ok=True)
    if args.historical and args.expected_protocol != 4:
        raise SystemExit('Historical mode is restricted to protocol-4 reference code')
    outcomes = []
    failures = 0
    for index in range(args.runs):
        token = uuid.uuid4().hex
        killed = instrument('createAndKill', {'crashMode': 'kill', 'crashToken': token})
        transcript = killed.stdout + killed.stderr
        (args.output / f'{index + 1}-killed.txt').write_text(transcript)
        proof = public_proof(transcript)
        if int(proof['crashProtocol']) != args.expected_protocol:
            raise RuntimeError('Wrong application reference version')
        if args.expected_protocol == 5 and not re.fullmatch('[0-9a-f]{32}', proof['crashEpoch']):
            raise RuntimeError('Missing current sender epoch')
        if 'Process crashed' not in transcript:
            raise RuntimeError('Instrumentation did not report the intended process crash')
        pids = adb('shell', 'ps', '-A', '-o', 'PID').stdout.split()
        if proof['crashPid'] in pids:
            raise RuntimeError('Creation process remains alive')
        reopened = instrument('verifyAfterKill', dict(proof, crashMode='verify', crashToken=token))
        result = reopened.stdout + reopened.stderr
        (args.output / f'{index + 1}-reopened.txt').write_text(result)
        new_pid = re.search(r'^INSTRUMENTATION_STATUS: reopenedPid=([1-9][0-9]*)\r?$', result, re.M)
        passed = reopened.returncode == 0 and 'OK (1 test)' in result and new_pid and new_pid[1] != proof['crashPid']
        if not passed:
            persistence_markers = ['Persisted signing public key', 'Persisted wrapped signing key', 'Identity must survive SIGKILL']
            if not args.historical or not any(marker in result for marker in persistence_markers):
                raise RuntimeError('Identity/private-key reload failed; inspect the transcript')
            failures += 1
            outcomes.append({'run': index + 1, 'identity': proof['crashId'], 'creationPid': proof['crashPid'], 'result': 'OBSERVED_PERSISTENCE_LOSS'})
            print(f'Observed historical persistence loss {index + 1}/{args.runs}; this is not a passing test')
        else:
            outcomes.append({'run': index + 1, 'identity': proof['crashId'], 'creationPid': proof['crashPid'],
                             'reopenedPid': new_pid[1], 'result': 'PASS'})
            print(f'PASS {index + 1}/{args.runs}: SIGKILL, new PID, same identity/fingerprint/epoch, private signing proof')
    assert len({row['identity'] for row in outcomes}) == args.runs, 'Each run must create a fresh identity'
    report = {'scope': 'API emulator OS-process death, not physical radio testing or proof of the historical failure cause',
              'package': PACKAGE, 'expectedProtocol': args.expected_protocol, 'historical': args.historical, 'observedLosses': failures, 'runs': outcomes, 'result': ('OBSERVED_PERSISTENCE_LOSS' if failures else 'PASS')}
    (args.output / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
