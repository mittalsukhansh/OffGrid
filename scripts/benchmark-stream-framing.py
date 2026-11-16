#!/usr/bin/env python3
"""Host loopback framing benchmark; never a BLE/Wi-Fi/device throughput measurement."""
import argparse,json,platform,socket,struct,threading,time
from datetime import datetime,timezone
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--bytes',type=int,default=2*1024*1024)
a=p.parse_args()
rows=[]
for chunk in (640,2560,7680):
 server=socket.socket(); server.bind(('127.0.0.1',0)); server.listen(1)
 errors=[]
 def read_exact(sock,n):
  out=bytearray()
  while len(out)<n:
   part=sock.recv(n-len(out))
   if not part: raise EOFError('Truncated frame')
   out.extend(part)
  return bytes(out)
 def receive():
  try:
   peer,_=server.accept()
   with peer:
    peer.settimeout(10)
    while True:
     n=struct.unpack('!I',read_exact(peer,4))[0]
     if n==0: break
     if n>32768: raise ValueError('Oversized frame')
     read_exact(peer,n); peer.sendall(b'\x01')
  except Exception as e: errors.append(str(e))
 thread=threading.Thread(target=receive);thread.start()
 start=time.perf_counter();count=0
 with socket.create_connection(server.getsockname(),timeout=10) as client:
  client.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
  while count<a.bytes:
   n=min(chunk,a.bytes-count)
   client.sendall(struct.pack('!I',n)+bytes(n)); assert read_exact(client,1)==b'\x01';count+=n
  client.sendall(struct.pack('!I',0))
 duration=time.perf_counter()-start
 thread.join(10); server.close()
 if thread.is_alive() or errors: raise RuntimeError(errors or 'Receiver timeout')
 rows.append({'payload_chunk_bytes':chunk,'payload_bytes':count,'elapsed_seconds':duration,'payload_bytes_per_second':count/duration})
result={'recorded_utc':datetime.now(timezone.utc).isoformat(),'host':platform.system()+' '+platform.machine(),'scope':'Host localhost TCP length framing and byte confirmation only; no Android, encryption, media pacing, BLE or physical Wi-Fi. Not an application speed claim.','legacy_configured_media_ceiling_bytes_per_second':256,'results':rows}
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
