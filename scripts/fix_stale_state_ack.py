from pathlib import Path

path = Path("app/src/main/assets/index.html")
html = path.read_text(encoding="utf-8")

old = "if(rev>0&&rev<=lastRemoteRevision)return;"
new = (
    "if(rev>0&&rev<=lastRemoteRevision){"
    "if(m.msgId&&window.ArgentasSupabaseTransport)"
    "window.ArgentasSupabaseTransport.send(JSON.stringify({type:'ack',msgId:m.msgId}));"
    "return"
    "}"
)

if new in html:
    print("Duplicate/stale state ACK fix already applied")
elif html.count(old) == 1:
    html = html.replace(old, new, 1)
    path.write_text(html, encoding="utf-8")
    print("ACK duplicate/stale snapshots so sender can retire their delivery")
else:
    raise SystemExit("State revision guard is not unique; refusing to modify generated app")
