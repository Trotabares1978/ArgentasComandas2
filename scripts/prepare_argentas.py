from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=root/"source"; assets=root/"app"/"src"/"main"/"assets"; assets.mkdir(parents=True,exist_ok=True)
parts=sorted(source.glob("argentas-original-*.part"))
if not parts: raise SystemExit("No se encontraron partes de Argentas original")
html="".join(p.read_text(encoding="utf-8") for p in parts)
# Desde la base canónica 232, las partes ya contienen el HTML final completo.
# No volver a inyectar bridges/overlays antiguos: eso degradaría la versión 232.
if "</body>" not in html:
    raise SystemExit("Argentas 232 no contiene </body>")
(assets/"index.html").write_text(html,encoding="utf-8")
print(f"Argentas 232 canónico preparado: {len(html)} caracteres desde {len(parts)} partes")
