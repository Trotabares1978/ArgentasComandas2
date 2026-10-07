from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=root/"source"; assets=root/"app"/"src"/"main"/"assets"; assets.mkdir(parents=True,exist_ok=True)
parts=sorted(source.glob("argentas-original-*.part"))
if not parts: raise SystemExit("No se encontraron partes de Argentas original")
html="".join(p.read_text(encoding="utf-8") for p in parts)
overlay=(source/"comandas-overlay.html").read_text(encoding="utf-8")
backup=(source/"backup-recovery.js").read_text(encoding="utf-8")
bridge=(source/"supabase-bridge.html").read_text(encoding="utf-8")
marker="</body>"
if marker not in html: raise SystemExit("Argentas original no contiene </body>")
html=html.replace(marker,overlay+"\n<script>"+backup+"</script>\n"+bridge+"\n"+marker,1)
(assets/"index.html").write_text(html,encoding="utf-8")
print(f"Argentas Supabase preparado: {len(html)} caracteres desde {len(parts)} partes")
