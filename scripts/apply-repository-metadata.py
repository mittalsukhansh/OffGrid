#!/usr/bin/env python3
"""Owner-operated metadata update; requires GitHub repository administration permission."""
import json,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1]
meta=json.loads((root/'.github/repository-metadata.json').read_text())
login=subprocess.check_output(['gh','api','user','--jq','.login'],text=True).strip()
if login!='mittalsukhansh':raise SystemExit('Authenticate as mittalsukhansh before changing repository metadata')
args=['gh','repo','edit','mittalsukhansh/OffGrid','--description',meta['description']]
for topic in meta['topics']:args+=['--add-topic',topic]
subprocess.run(args,check=True)
