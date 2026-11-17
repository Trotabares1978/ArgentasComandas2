from pathlib import Path

path = Path("app/src/main/assets/index.html")
html = path.read_text(encoding="utf-8")

old = """  function flushState(){
    if(!connected||!window.ArgentasSupabaseTransport||!reliablePending)return;
    try{
      var q=syncQueue.find(function(x){return x.msgId===reliablePending.msgId});
      if(q){q.attempts++;q.lastSentAt=Date.now();saveSyncQueue()}
      window.ArgentasSupabaseTransport.send(JSON.stringify(reliablePending));
      syncDiag.lastSend=Date.now();syncDiag.sentStates++;diagSave();updateDiagnostics()
    }catch(e){
      syncDiag.lastError='No se pudo enviar el estado';diagSave();updateDiagnostics()
    }
  }"""

new = """  function flushState(){
    if(!connected||!window.ArgentasSupabaseTransport||!reliablePending)return;
    try{
      var q=syncQueue.find(function(x){return x.msgId===reliablePending.msgId});
      if(q){q.attempts=Number(q.attempts||0)+1;q.lastSentAt=Date.now();saveSyncQueue()}
      var result=window.ArgentasSupabaseTransport.send(JSON.stringify(reliablePending));
      function sent(){syncDiag.lastSend=Date.now();syncDiag.sentStates=Number(syncDiag.sentStates||0)+1;syncDiag.lastError='';diagSave();updateDiagnostics()}
      function failed(e){var detail=String(e&&e.message||e||'Error desconocido');if(q){q.lastError=detail;q.lastFailureAt=Date.now();saveSyncQueue()}syncDiag.lastError='No se pudo enviar el estado: '+detail;diagSave();updateDiagnostics()}
      if(result&&typeof result.then==='function')result.then(sent,failed);else sent();
    }catch(e){
      syncDiag.lastError='No se pudo enviar el estado: '+String(e&&e.message||e);diagSave();updateDiagnostics()
    }
  }"""

if html.count(old) != 1:
    if new in html:
        print("State snapshot async handling: already applied")
    else:
        raise SystemExit(f"Expected one flushState block, found {html.count(old)}")
else:
    html = html.replace(old, new, 1)

checks = {
    "state attempts persisted before send": "q.attempts=Number(q.attempts||0)+1;q.lastSentAt=Date.now();saveSyncQueue()" in html,
    "state success recorded only after transport resolves": "result.then(sent,failed)" in html,
    "state async errors persisted to retry queue": "q.lastError=detail;q.lastFailureAt=Date.now();saveSyncQueue()" in html,
    "state diagnostics report real failures": "No se pudo enviar el estado: '+detail" in html,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("State sync regression guards failed: " + ", ".join(failed))

path.write_text(html, encoding="utf-8")
print(f"State snapshot async error handling applied; {len(checks)} regression guards passed.")
