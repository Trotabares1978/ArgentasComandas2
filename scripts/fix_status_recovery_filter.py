from pathlib import Path

path = Path("app/src/main/assets/index.html")
html = path.read_text(encoding="utf-8")

old = "var qStatus=SUPABASE_URL+'/rest/v1/'+SUPABASE_TABLE+'?select=mensaje,created_at&order=created_at.desc&limit=500';"
new = "var qStatus=SUPABASE_URL+'/rest/v1/'+SUPABASE_TABLE+'?select=mensaje,created_at&mensaje=like.*%22type%22%3A%22comanda-status%22*&order=created_at.desc&limit=500';"

if new in html:
    print("Targeted comanda-status recovery query already applied")
elif html.count(old) == 1:
    html = html.replace(old, new, 1)
    path.write_text(html, encoding="utf-8")
    print("Filtered status recovery to comanda-status messages before applying the 500-row limit")
else:
    raise SystemExit("Status recovery query anchor is not unique; refusing to modify generated app")
