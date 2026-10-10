from pathlib import Path

path = Path("app/src/main/assets/index.html")
html = path.read_text(encoding="utf-8")

old = "statusRows.reverse().forEach(function(row){if(!row||!row.mensaje)return;if(String(row.device_id||'')===DEVICE_ID)return;try{var parsed=JSON.parse(row.mensaje);if(parsed.type==='comanda-status')message(row.mensaje)}catch(e){}});"
new = "statusRows.reverse().forEach(function(row){if(!row||!row.mensaje)return;try{var parsed=JSON.parse(row.mensaje);if(parsed.type==='comanda-status'&&String(row.device_id||'')!==DEVICE_ID&&String(parsed.statusDeviceId||'')!==DEVICE_ID)message(row.mensaje)}catch(e){}});"

if new in html:
    print("Status recovery already checks both row sender and payload sender")
elif html.count(old) == 1:
    html = html.replace(old, new, 1)
    path.write_text(html, encoding="utf-8")
    print("Status recovery now avoids replaying own messages even when legacy rows lack device_id")
else:
    raise SystemExit("Status recovery loop anchor is not unique; refusing to modify generated app")
