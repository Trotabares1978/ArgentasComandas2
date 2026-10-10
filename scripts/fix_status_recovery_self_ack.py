from pathlib import Path

path = Path("app/src/main/assets/index.html")
html = path.read_text(encoding="utf-8")

old_query = "var qStatus=SUPABASE_URL+'/rest/v1/'+SUPABASE_TABLE+'?select=mensaje,created_at&mensaje=like.*%22type%22%3A%22comanda-status%22*&order=created_at.desc&limit=500';"
new_query = "var qStatus=SUPABASE_URL+'/rest/v1/'+SUPABASE_TABLE+'?select=mensaje,created_at,device_id&mensaje=like.*%22type%22%3A%22comanda-status%22*&order=created_at.desc&limit=500';"

old_loop = "statusRows.reverse().forEach(function(row){if(!row||!row.mensaje)return;try{var parsed=JSON.parse(row.mensaje);if(parsed.type==='comanda-status')message(row.mensaje)}catch(e){}});"
new_loop = "statusRows.reverse().forEach(function(row){if(!row||!row.mensaje)return;if(String(row.device_id||'')===DEVICE_ID)return;try{var parsed=JSON.parse(row.mensaje);if(parsed.type==='comanda-status')message(row.mensaje)}catch(e){}});"

if new_query in html and new_loop in html:
    print("Recovery skips own status messages to avoid self-ACKing before peer application")
else:
    if html.count(old_query) != 1:
        raise SystemExit("Status recovery query anchor is not unique; refusing to modify generated app")
    if html.count(old_loop) != 1:
        raise SystemExit("Status recovery loop anchor is not unique; refusing to modify generated app")
    html = html.replace(old_query, new_query, 1)
    html = html.replace(old_loop, new_loop, 1)
    path.write_text(html, encoding="utf-8")
    print("Recovery now ignores own device status messages and only ACKs messages recovered from another device")
