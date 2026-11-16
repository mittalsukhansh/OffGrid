#!/usr/bin/env python3
"""Verify the public app/certificate identity before publishing an APK; never reads passwords."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--version")
    parser.add_argument("--signature-output", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    identity = json.loads((root / "signing/release-identity.json").read_text())
    cert = (root / "signing/offgrid-release-cert.pem").read_text()
    expected = identity["certificateSha256"]
    if hashlib.sha256(ssl.PEM_cert_to_DER_cert(cert)).hexdigest() != expected:
        raise SystemExit("Public certificate and pinned signing fingerprint do not match")
    analyzer = shutil.which("apkanalyzer")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not analyzer or not sdk:
        raise SystemExit("Set ANDROID_HOME and add Android cmdline-tools/latest/bin to PATH")
    signer = Path(sdk) / "build-tools/36.0.0/apksigner"
    apk = str(args.apk.resolve())

    def manifest(field):
        return subprocess.check_output([analyzer, "manifest", field, apk], text=True).strip()

    if manifest("application-id") != identity["applicationId"]:
        raise SystemExit("Wrong application ID: refusing to publish a debug or unrelated package")
    if manifest("debuggable").lower() != "false":
        raise SystemExit("Refusing to publish a debuggable APK")
    version = manifest("version-name")
    if args.version and version != args.version:
        raise SystemExit(f"Version mismatch: APK {version}, expected {args.version}")
    signature = subprocess.check_output(
        [str(signer), "verify", "--verbose", "--print-certs", apk], text=True
    )
    actual = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", signature, re.M)
    if len(actual) != 1 or actual[0].lower() != expected:
        raise SystemExit("APK signer does not match the pinned OffGrid release certificate")
    if args.signature_output:
        args.signature_output.write_text(signature)
    print(f"Verified release: {identity['applicationId']} {version}")
    print(f"Certificate SHA-256: {expected}")


if __name__ == "__main__":
    main()
