from pathlib import Path
import subprocess

root=Path(__file__).resolve().parents[1]
subprocess.run(['python3','scripts/prepare_argentas.py'],cwd=root,check=True)
assets=root/'app'/'src'/'main'/'assets'/'index.html'
engine=(root/'source'/'sync-engine.js').read_text(encoding='utf-8')
html=assets.read_text(encoding='utf-8')
if 'Argentas 2 — motor de sincronización' not in html:
    html=html.replace('</body>','<script>'+engine+'</script>\n</body>',1)
assets.write_text(html,encoding='utf-8')
print('Argentas 2: motor de sincronización embebido')