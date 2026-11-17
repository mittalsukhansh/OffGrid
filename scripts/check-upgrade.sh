#!/usr/bin/env bash
set -euo pipefail
mkdir -p release-evidence
trap 'adb logcat -d -s OffGridCrypto:W AndroidRuntime:E | tee release-evidence/key-loading.txt' EXIT
{
    echo 'OffGrid signed in-place upgrade: emulator evidence (not a physical radio test)'
    date -u +%FT%TZ
    cat release-staging/manifest.json
    adb shell getprop ro.product.model
    adb shell getprop ro.build.version.sdk
    adb install release-staging/baseline.apk
    adb install release-staging/baseline-test.apk
    adb shell am instrument -w -e class com.example.offgrid.UpgradeContinuityTest#seed -e upgrade seed io.github.mittalsukhansh.offgrid.test/androidx.test.runner.AndroidJUnitRunner > release-evidence/seed.txt
    cat release-evidence/seed.txt
    grep -F 'OK (1 test)' release-evidence/seed.txt
    adb shell am instrument -w -e class com.example.offgrid.UpgradeContinuityTest#verify -e upgrade verify io.github.mittalsukhansh.offgrid.test/androidx.test.runner.AndroidJUnitRunner > release-evidence/baseline-reopen.txt
    cat release-evidence/baseline-reopen.txt
    grep -F 'OK (1 test)' release-evidence/baseline-reopen.txt
    CANDIDATE=$(python3 - <<'PYTHON'
import importlib.util,json
spec=importlib.util.spec_from_file_location('staged','scripts/verify-staged-release.py')
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
print('release-staging/' + module.validate_manifest(json.load(open('release-staging/manifest.json')))['apk'])
PYTHON
    )
    adb install -r "$CANDIDATE"
    adb install -r release-staging/upgrade-test.apk
    adb shell am instrument -w -e class com.example.offgrid.UpgradeContinuityTest#verify -e upgrade verify io.github.mittalsukhansh.offgrid.test/androidx.test.runner.AndroidJUnitRunner > release-evidence/verify.txt
    cat release-evidence/verify.txt
    grep -F 'OK (1 test)' release-evidence/verify.txt
    echo 'PASSED: package/signature accepted in place; identity, signing public key, contact nickname and encrypted history preserved; contact trust matches the baseline key format.'
} 2>&1 | tee release-evidence/upgrade.txt
