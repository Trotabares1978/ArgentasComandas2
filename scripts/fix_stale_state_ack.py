from pathlib import Path

path = Path("app/src/main/assets/index.html")
html = path.read_text(encoding="utf-8")

old = """if(rev>0&&rev<=lastRemoteRevision)return;
      if(rev>0)try{localStorage.setItem(remoteSeenKey,String(rev))}catch(e){}
      merge(m.db,m.sales||[],false);"""
new = """if(rev>0&&rev<=lastRemoteRevision){
        if(m.msgId&&window.ArgentasSupabaseTransport)
          window.ArgentasSupabaseTransport.send(JSON.stringify({type:'ack',msgId:m.msgId}));
        return
      }
      merge(m.db,m.sales||[],false);
      if(rev>0)try{localStorage.setItem(remoteSeenKey,String(rev))}catch(e){}"""

if new in html:
    print("Duplicate/stale state ACK and post-merge revision persistence already applied")
elif html.count(old) == 1:
    html = html.replace(old, new, 1)
    path.write_text(html, encoding="utf-8")
    print("ACK duplicate/stale snapshots; persist revision only after successful merge")
else:
    raise SystemExit("State revision/merge block is not unique; refusing to modify generated app")
