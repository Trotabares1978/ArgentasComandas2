from pathlib import Path

path = Path("app/src/main/assets/index.html")
html = path.read_text(encoding="utf-8")

old = """  stateRows.forEach(function(row){
    if(!row||!row.mensaje)return;"""
new = """  stateRows.forEach(function(row){
    if(!row||!row.mensaje)return;
    if(String(row.device_id||'')===DEVICE_ID)return;"""

if new in html:
    print("State recovery already skips this device's own snapshots")
elif html.count(old) == 1:
    html = html.replace(old, new, 1)
    path.write_text(html, encoding="utf-8")
    print("State recovery now ignores own device snapshots before replaying them")
else:
    raise SystemExit("State recovery row loop anchor is not unique; refusing to modify generated app")
