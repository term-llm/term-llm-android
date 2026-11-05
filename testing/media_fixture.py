#!/usr/bin/env python3
"""Deterministic local-only range/slow-download fixture. No credentials logged."""
import argparse,hashlib,json,time,struct
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
p=argparse.ArgumentParser();p.add_argument('--port',type=int,default=8898);p.add_argument('--log',required=True);p.add_argument('--delay',type=float,default=.15);args=p.parse_args()
if not 0 <= args.delay <= 1:p.error('--delay must be between 0 and 1 seconds')
samples=8000*120
body=b'RIFF'+struct.pack('<I',36+samples*2)+b'WAVEfmt '+struct.pack('<IHHIIHH',16,1,1,8000,16000,2,16)+b'data'+struct.pack('<I',samples*2)+bytes(samples*2)
etag='"'+hashlib.sha256(body).hexdigest()+'"'
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*a):pass
 def send(self,head=False):
  start=int(self.headers.get('Range','bytes=0-').split('=')[-1].split('-')[0] or 0)
  with open(args.log,'a') as out:out.write(json.dumps({'method':self.command,'path':self.path,'range':self.headers.get('Range'),'if_range':self.headers.get('If-Range'),'auth_present':bool(self.headers.get('Authorization') or self.headers.get('Cookie'))})+'\n')
  if start>=len(body):self.send_response(416);self.send_header('Content-Range',f'bytes */{len(body)}');self.end_headers();return
  self.send_response(206 if start else 200);self.send_header('Content-Type','audio/wav');self.send_header('Content-Length',str(len(body)-start));self.send_header('ETag',etag);self.send_header('Accept-Ranges','bytes')
  if start:self.send_header('Content-Range',f'bytes {start}-{len(body)-1}/{len(body)}')
  self.end_headers()
  if head:return
  try:
   for offset in range(start,len(body),16000):self.wfile.write(body[offset:offset+16000]);self.wfile.flush();time.sleep(args.delay)
  except (BrokenPipeError,ConnectionResetError):pass
 do_GET=lambda self:self.send()
 do_HEAD=lambda self:self.send(True)
ThreadingHTTPServer(('0.0.0.0',args.port),Handler).serve_forever()
