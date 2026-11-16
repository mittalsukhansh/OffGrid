#!/usr/bin/env python3
"""Build untouched protocol-4 application code with only the current test-only crash fixture."""
import argparse
from pathlib import Path
import shutil
import subprocess

BASELINE = '6fc4ee19f784b396d3f5e5bd746b564ba541afa5'
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--worktree', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
root = Path(__file__).resolve().parents[1]
worktree = a.worktree.resolve()
output = a.output.resolve()
subprocess.run(['git', 'worktree', 'add', '--detach', str(worktree), BASELINE], cwd=root, check=True)
fixture = 'app/src/androidTest/java/com/example/offgrid/IdentityProcessDeathTest.java'
shutil.copyfile(root / fixture, worktree / fixture)
# Neither the old production constructor nor its apply() call is patched or flushed by the fixture.
subprocess.run(['./gradlew', '--no-daemon', 'assembleDebug', 'assembleDebugAndroidTest'], cwd=worktree, check=True)
output.mkdir(parents=True, exist_ok=True)
shutil.copyfile(worktree / 'app/build/outputs/apk/debug/app-debug.apk', output / 'app-debug.apk')
shutil.copyfile(worktree / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', output / 'app-debug-androidTest.apk')
(output / 'application-source.txt').write_text(BASELINE + '\nOnly the test fixture was added. Production source unchanged.\n')
