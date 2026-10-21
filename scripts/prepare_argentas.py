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

def replace_once(old,new,label):
    global html
    count=html.count(old)
    if count==0 and new in html:
        print(label+": already applied")
        return
    if count!=1:
        raise SystemExit(f"{label}: expected one exact anchor, found {count}; refusing to patch")
    html=html.replace(old,new,1)
    print(label+": patched")

# If Realtime rejects the channel join, report it and force a clean reconnect.
# Scheduling before closing ensures onclose does not create a duplicate retry timer.
replace_once(
    "console.error('Argentas Supabase: rechazo de phx_join:',reason);state('DESCONECTADO','Supabase rechazó la suscripción: '+reason);return",
    "console.error('Argentas Supabase: rechazo de phx_join:',reason);state('DESCONECTADO','Supabase rechazó la suscripción: '+reason);schedule();try{if(ws)ws.close()}catch(e){}return",
    "Retry after Supabase join rejection",
)
replace_once(
    "console.error('Argentas Supabase: error del canal:',systemReason);state('DESCONECTADO','Error del canal Supabase: '+systemReason);return",
    "console.error('Argentas Supabase: error del canal:',systemReason);state('DESCONECTADO','Error del canal Supabase: '+systemReason);schedule();try{if(ws)ws.close()}catch(e){}return",
    "Retry after Supabase channel error",
)

(assets/"index.html").write_text(html,encoding="utf-8")
print(f"Argentas 232 canónico preparado: {len(html)} caracteres desde {len(parts)} partes")
