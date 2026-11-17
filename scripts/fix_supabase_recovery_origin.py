from pathlib import Path

path = Path("app/src/main/assets/index.html")
html = path.read_text(encoding="utf-8")

def replace_once(old, new, label):
    global html
    count = html.count(old)
    if count == 0 and new in html:
        print(label + ": already applied")
        return
    if count != 1:
        raise SystemExit(f"{label}: expected one exact anchor, found {count}; refusing to patch")
    html = html.replace(old, new, 1)
    print(label + ": patched")

# Realtime filters out this device's own rows, but historical recovery did not.
# That asymmetry can replay our own state and ACK our own pending snapshot.
replace_once(
    "var qState=SUPABASE_URL+'/rest/v1/'+SUPABASE_TABLE+'?select=mensaje,created_at,device_id&device_id=like.d_*&mensaje=like.*%22type%22%3A%22state%22*&order=created_at.desc&limit=200';",
    "var qState=SUPABASE_URL+'/rest/v1/'+SUPABASE_TABLE+'?select=mensaje,created_at,device_id&device_id=neq.'+encodeURIComponent(DEVICE_ID)+'&mensaje=like.*%22type%22%3A%22state%22*&order=created_at.desc&limit=200';",
    "Exclude own device from state recovery query",
)
replace_once(
    "var qStatus=SUPABASE_URL+'/rest/v1/'+SUPABASE_TABLE+'?select=mensaje,created_at&order=created_at.desc&limit=500';",
    "var qStatus=SUPABASE_URL+'/rest/v1/'+SUPABASE_TABLE+'?select=mensaje,created_at,device_id&or=(device_id.is.null,device_id.neq.'+encodeURIComponent(DEVICE_ID)+')&order=created_at.desc&limit=500';",
    "Exclude own device from status recovery query",
)
replace_once(
    "stateRows.forEach(function(row){\n    if(!row||!row.mensaje)return;",
    "stateRows.forEach(function(row){\n    if(!row||!row.mensaje||String(row.device_id||'')===DEVICE_ID)return;",
    "Defensively ignore own recovered state rows",
)
replace_once(
    "statusRows.reverse().forEach(function(row){if(!row||!row.mensaje)return;",
    "statusRows.reverse().forEach(function(row){if(!row||!row.mensaje||String(row.device_id||'')===DEVICE_ID)return;",
    "Defensively ignore own recovered status rows",
)

checks = {
    "state recovery excludes current device": "device_id=neq.'+encodeURIComponent(DEVICE_ID)+'&mensaje=like.*%22type%22%3A%22state%22*" in html,
    "status recovery excludes current device and retains legacy null sender rows": "select=mensaje,created_at,device_id&or=(device_id.is.null,device_id.neq.'+encodeURIComponent(DEVICE_ID)+')&order=created_at.desc&limit=500" in html,
    "state rows have a client-side sender guard": "if(!row||!row.mensaje||String(row.device_id||'')===DEVICE_ID)return;" in html,
    "status rows have a client-side sender guard": "statusRows.reverse().forEach(function(row){if(!row||!row.mensaje||String(row.device_id||'')===DEVICE_ID)return;" in html,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("Supabase recovery regression guards failed: " + ", ".join(failed))

path.write_text(html, encoding="utf-8")
print(f"Supabase recovery origin filtering applied; {len(checks)} regression guards passed.")
