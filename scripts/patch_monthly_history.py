from pathlib import Path

path = Path("app/src/main/assets/index.html")
html = path.read_text(encoding="utf-8")
old = 'return (el.textContent||"").trim()==="🔒Historial protegido: cada cierre se borra individualmente con confirmación. No hay borrado total.";'
new = 'var text=(el.textContent||"").replace(/\\s+/g," ").trim();return text.indexOf("Historial protegido")!==-1&&text.indexOf("cada cierre se borra individualmente")!==-1;'

if old not in html:
    if new in html:
        print("Monthly history selector already patched")
    else:
        raise SystemExit("Could not find the exact monthly-history selector; refusing to patch blindly")
else:
    html = html.replace(old, new, 1)
    path.write_text(html, encoding="utf-8")
    print("Patched monthly-history selector")
