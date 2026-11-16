#!/usr/bin/env python3
"""Scan reachable Git blobs/current signing files. Findings contain paths/hashes, never secrets."""
import argparse, json, re, subprocess
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--known-secrets-dir',type=Path)
a=p.parse_args()
root=Path(subprocess.check_output(['git','rev-parse','--show-toplevel'],text=True).strip())
private_suffixes=('.jks','.keystore','.p12','.pfx')
known=[]
if a.known_secrets_dir:
 for name in ['offgrid-release.p12','OFFGRID_KEYSTORE_BASE64','OFFGRID_STORE_PASSWORD','OFFGRID_KEY_PASSWORD']:
  f=a.known_secrets_dir/name
  if f.is_file() and len(f.read_bytes())>16: known.append(f.read_bytes())
objects=subprocess.check_output(['git','rev-list','--objects','--all'],text=True).splitlines()
findings=[];scanned=0
for row in objects:
 parts=row.split(' ',1);sha=parts[0];path=parts[1] if len(parts)>1 else ''
 kind=subprocess.check_output(['git','cat-file','-t',sha],text=True).strip()
 if kind!='blob':continue
 data=subprocess.check_output(['git','cat-file','blob',sha]);scanned+=1
 reasons=[]
 if path.lower().endswith(private_suffixes):reasons.append('private keystore filename')
 if re.search(rb'-----BEGIN (?:RSA |EC |ENCRYPTED |OPENSSH )?PRIVATE KEY-----',data):reasons.append('private-key PEM block')
 if data.startswith(b'\xfe\xed\xfe\xed'):reasons.append('Java keystore binary header')
 if any(secret in data for secret in known):reasons.append('known private signing value')
 if reasons:findings.append({'path':path,'blob':sha,'reasons':reasons})
current=sorted(str(f.relative_to(root)) for f in (root/'signing').rglob('*') if f.is_file())
allowed={'signing/offgrid-release-cert.pem','signing/release-identity.json'}
for path in current:
 if path not in allowed:findings.append({'path':path,'reasons':['unexpected material in signing directory']})
result={'scope':'All blobs reachable from local refs/tags, current signing directory, private-key markers/keystore filenames, and optional exact known signing values. Not proof against every possible secret encoding.','reachable_blobs_scanned':scanned,'current_signing_files':current,'known_value_comparisons_enabled':bool(known),'findings':findings}
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(result,indent=2)+'\n')
print(f'Signing audit: {scanned} reachable blobs, {len(current)} current public files, {len(findings)} findings')
raise SystemExit(1 if findings else 0)
