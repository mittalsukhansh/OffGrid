"""Host orchestration checks; Android assertions still run on the actual emulator in CI."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parent / 'check-same-protocol-reinstall.py'
STUB = '''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
args=sys.argv[1:]
with Path(os.environ['OFFGRID_FAKE_ADB_LOG']).open('a') as out: out.write(json.dumps(args)+'\\n')
if args[:2]==['shell','getprop']: print('1')
elif args[0]=='install': print('Success')
elif 'am instrument' in args[-1]:
 if os.environ['OFFGRID_FAKE_ADB_MODE']=='failure':
  print('INSTRUMENTATION_FAILED: missing test package',file=sys.stderr);sys.exit(1)
 print('OK (1 test)')
else: sys.exit(2)
'''


class ReinstallHarnessTest(unittest.TestCase):
    def run_host(self, mode):
        context = tempfile.TemporaryDirectory()
        self.addCleanup(context.cleanup)
        folder = Path(context.name)
        adb = folder / 'adb'
        adb.write_text(STUB)
        adb.chmod(0o700)
        log = folder / 'adb-calls.jsonl'
        env = dict(os.environ, PATH=str(folder) + os.pathsep + os.environ['PATH'],
                   OFFGRID_FAKE_ADB_LOG=str(log), OFFGRID_FAKE_ADB_MODE=mode)
        result = subprocess.run([sys.executable, str(SCRIPT)], cwd=folder, env=env, capture_output=True, text=True)
        calls = [json.loads(line) for line in log.read_text().splitlines()]
        return result, calls, folder / 'app/build/outputs/same-protocol-reinstall'

    def test_setup_installs_both_packages_before_seed_and_keeps_actual_reinstall(self):
        result, calls, output = self.run_host('success')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['shell', 'install', 'install', 'shell', 'install', 'install', 'shell'],
                         [call[0] for call in calls])
        self.assertIn('#seed', calls[3][-1])
        self.assertIn('#verify', calls[-1][-1])
        self.assertEqual('PASS', json.loads((output / 'summary.json').read_text())['result'])

    def test_adb_failure_keeps_diagnostic_and_never_claims_pass(self):
        result, _, output = self.run_host('failure')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('INSTRUMENTATION_FAILED: missing test package', result.stderr)
        self.assertFalse((output / 'summary.json').exists())


if __name__ == '__main__':
    unittest.main()
