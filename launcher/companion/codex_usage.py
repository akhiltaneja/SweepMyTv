#!/usr/bin/env python3
"""Read Codex's supported account methods and sync a sanitised snapshot over existing ADB.
No model turns, credential exports, account changes, network listeners or login flows.
"""
import argparse, datetime, json, os, queue, shutil, subprocess, tempfile, threading, time
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
ALLOWED={"initialize","account/read","account/rateLimits/read","account/usage/read"}

class CodexReader:
    def __init__(self):
        codex=shutil.which("codex")
        if not codex: raise RuntimeError("Codex CLI is not installed on this Mac")
        self.proc=subprocess.Popen([codex,"app-server"],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,bufsize=1)
        self.messages=queue.Queue();self.counter=0
        def read():
            for line in self.proc.stdout:
                try:self.messages.put(json.loads(line))
                except ValueError:pass
            self.messages.put(None)
        threading.Thread(target=read,daemon=True).start()
    def send(self,message):self.proc.stdin.write(json.dumps(message)+"\n");self.proc.stdin.flush()
    def request(self,method,params=None):
        if method not in ALLOWED:raise RuntimeError("This companion only reads usage")
        self.counter+=1;identity=self.counter;self.send({"id":identity,"method":method,"params":params or {}})
        deadline=time.monotonic()+25
        while True:
            try:message=self.messages.get(timeout=max(.01,deadline-time.monotonic()))
            except queue.Empty:raise RuntimeError("Codex usage request timed out") from None
            if message is None:raise RuntimeError("Codex usage connection closed")
            if message.get("id")==identity:
                if "error" in message:raise RuntimeError("Codex usage request failed (code "+str(message['error'].get('code','unknown'))+")")
                return message.get("result",{})
            if time.monotonic()>deadline:raise RuntimeError("Codex usage request timed out")
    def close(self):
        self.proc.terminate()
        try:self.proc.wait(timeout=5)
        except subprocess.TimeoutExpired:self.proc.kill();self.proc.wait(timeout=5)

def sanitise(limits,usage=None,now=None):
    now=time.time() if now is None else now
    buckets=limits.get("rateLimitsByLimitId") or {}
    bucket=buckets.get("codex") or limits.get("rateLimits") or {}
    primary=bucket.get("primary") or bucket.get("secondary") or {}
    used=primary.get("usedPercent");window=primary.get("windowDurationMins")
    if not isinstance(used,(int,float)) or not 0<=used<=100 or not isinstance(window,int) or window<=0:raise RuntimeError("No supported Codex quota window is available")
    output={"synced_at":round(now*1000),"used_percent":used,"window_minutes":window,"resets_at":round((primary.get("resetsAt") or 0)*1000),"source":"Codex App Server"}
    secondary=bucket.get("secondary")
    if secondary and secondary is not primary:
        output["secondary"]={key:secondary.get(key) for key in ("usedPercent","windowDurationMins","resetsAt")}
    if usage:
        today=datetime.datetime.fromtimestamp(now).astimezone().date().isoformat()
        for day in usage.get("dailyUsageBuckets") or []:
            if day.get("startDate")==today and isinstance(day.get("tokens"),int):output["tokens_today"]=day["tokens"]
    return output

def collect():
    reader=CodexReader()
    try:
        reader.request("initialize",{"clientInfo":{"name":"sweep_tv_usage","title":"Sweep TV Usage Companion","version":"1.0"}})
        reader.send({"method":"initialized","params":{}})
        account=reader.request("account/read",{"refreshToken":False}).get("account")
        if not account or account.get("type")=="apiKey":raise RuntimeError("Sign into Codex with your ChatGPT account on this Mac first")
        limits=reader.request("account/rateLimits/read")
        try:usage=reader.request("account/usage/read")
        except RuntimeError:usage=None
        return sanitise(limits,usage)
    finally:reader.close()

def sync(snapshot,device):
    adb=str(ROOT/'tools/platform-tools/adb');remote='/sdcard/Android/data/com.sweepmytv.launcher/files/codex_usage.json'
    subprocess.run([adb,'-s',device,'shell','pm','path','com.sweepmytv.launcher'],check=True,capture_output=True,timeout=15)
    with tempfile.TemporaryDirectory(prefix='sweep-usage-') as directory:
        path=Path(directory)/'codex_usage.json';path.write_text(json.dumps(snapshot));path.chmod(0o600)
        subprocess.run([adb,'-s',device,'push',str(path),remote+'.tmp'],check=True,capture_output=True,timeout=20)
        subprocess.run([adb,'-s',device,'shell','mv',remote+'.tmp',remote],check=True,capture_output=True,timeout=10)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--device',default='192.168.0.245:5555');parser.add_argument('--watch',action='store_true');parser.add_argument('--read-only',action='store_true',help='Read the summary without syncing to TV');args=parser.parse_args()
    previous=None
    while True:
        try:
            snapshot=collect()
            if not args.read_only:sync(snapshot,args.device)
            state=(snapshot['used_percent'],snapshot['window_minutes'])
            if state!=previous:print(json.dumps({'synced_to_tv':not args.read_only,'used_percent':snapshot['used_percent'],'window_minutes':snapshot['window_minutes']}),flush=True);previous=state
        except (RuntimeError,OSError,subprocess.SubprocessError) as error:
            print('Usage sync unavailable: '+(str(error) if isinstance(error,RuntimeError) else type(error).__name__),flush=True)
            if not args.watch:return 1
        if not args.watch:return 0
        time.sleep(300)
if __name__=='__main__':raise SystemExit(main())
