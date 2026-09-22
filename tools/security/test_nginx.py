"""Isolated nginx boundary regression; uses fixtures only, never production APIs/volumes."""
import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


def docker(*args,check=True):
    p=subprocess.run(["docker",*args],capture_output=True,text=True,timeout=120)
    if check and p.returncode: raise RuntimeError("Docker QA command failed: "+p.stderr[-1000:])
    return (p.stdout + (p.stderr if not check else "")).strip()


def main():
    root=Path(__file__).resolve().parents[2]
    suffix=uuid.uuid4().hex[:8]; prefix="gying-security-nginx-"+suffix
    network=prefix+"-net"; upstream=prefix+"-upstream"; nginx=prefix+"-proxy"
    stage=root/"tmp"/prefix; stage.mkdir(parents=True)
    fixture=r'''from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
import threading,json
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args): pass
 def do_GET(self):
  body=json.dumps({'port':self.server.server_port,'path':self.path,'xff':self.headers.get('X-Forwarded-For'),'cf':self.headers.get('CF-Connecting-IP'),'proto':self.headers.get('X-Forwarded-Proto')}).encode()
  self.send_response(200); self.send_header('Content-Length',str(len(body))); self.send_header('Content-Type','application/json'); self.end_headers(); self.wfile.write(body)
 def do_POST(self): self.do_GET()
for port in [8880,3000,9000]:
 threading.Thread(target=ThreadingHTTPServer(('0.0.0.0',port),Handler).serve_forever,daemon=True).start()
threading.Event().wait()
'''
    (stage/"fixture.py").write_text(fixture,encoding="utf-8")
    try:
        docker("network","create",network)
        docker("run","-d","--name",upstream,"--network",network,"--network-alias","backend","--network-alias","frontend","--network-alias","minio",
               "--mount",f"type=bind,src={stage/'fixture.py'},dst=/fixture.py,readonly","python:3.12-slim","python","/fixture.py")
        args=["run","-d","--name",nginx,"--network",network,"--user","101:101","--cap-drop","ALL","--read-only","--security-opt","no-new-privileges:true",
              "--tmpfs","/tmp:rw,noexec,nosuid,size=64m","-p","127.0.0.1::8080"]
        for src,dst in [("nginx-main.conf","nginx.conf"),("nginx.conf","conf.d/default.conf"),("proxy-headers.conf","proxy-headers.conf"),("trusted-tunnel-peers.conf","trusted-tunnel-peers.conf")]:
            args += ["--mount",f"type=bind,src={root/'nginx'/src},dst=/etc/nginx/{dst},readonly"]
        docker(*args,"nginx:stable-alpine")
        docker("exec",nginx,"nginx","-t")
        info=json.loads(docker("inspect",nginx))[0]
        port=info["NetworkSettings"]["Ports"]["8080/tcp"][0]["HostPort"]
        base="http://127.0.0.1:"+port
        def request(path,method="GET",headers=None):
            req=urllib.request.Request(base+path,method=method,headers=headers or {})
            try:
                with urllib.request.urlopen(req,timeout=5) as r: return r.status,r.headers,r.read()
            except urllib.error.HTTPError as e: return e.code,e.headers,e.read()
        for _ in range(20):
            try:
                if request("/")[0]==200: break
            except Exception: pass
            time.sleep(.25)
        checks=[]
        for path in ["/api/internal/resource-hub/health","/api/qq-bot/health","/api/%69nternal/resource-hub/health","/api/internal;ignored/resource-hub/health","/.env","/actuator/health","/media/private.txt","/media/secret/file.jpg"]:
            assert request(path)[0]==404,path; checks.append(path)
        status,headers,body=request("/api/test.js",headers={"X-Forwarded-For":"1.2.3.4"})
        data=json.loads(body); assert status==200 and data["port"]==8880 and data["xff"]!="1.2.3.4" and not data["cf"] and data["proto"]=="https"
        assert headers.get("X-Content-Type-Options")=="nosniff" and "immutable" not in headers.get("Cache-Control",""); checks.append("api-routing-and-header-sanitization")
        status,headers,body=request("/media/mv/fixture/384.avif?response-content-type=text/html&token=fixture")
        assert status==200 and json.loads(body)["path"]=="/gying/mv/fixture/384.avif"; checks.append("image-query-discarded")
        assert request("/media/mv/fixture/384.avif",method="POST")[0] in (403,405); checks.append("media-read-only")
        statuses=[request("/api/auth/login",method="POST")[0] for _ in range(12)]
        assert 429 in statuses; checks.append("nginx-auth-rate-limit")
        print(json.dumps({"status":"PASS","checks":checks,"nginx_uid":docker("exec",nginx,"id","-u")},indent=2))
    except Exception:
        print(docker("logs", "--tail", "30", nginx, check=False))
        raise
    finally:
        for name in (nginx,upstream): docker("rm","-f",name,check=False)
        docker("network","rm",network,check=False)


if __name__=="__main__": main()
