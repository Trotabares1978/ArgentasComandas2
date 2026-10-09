from pathlib import Path

path = Path("app/src/main/assets/index.html")
html = path.read_text(encoding="utf-8")

start = "function cajaSnapshot(){"
end = "var reliablePending=null,"
already = "var ArgentasSyncSnapshot=(function(){"

if already in html:
    print("Stage 4 snapshot extraction already applied")
elif html.count(start) != 1 or html.count(end) != 1:
    raise SystemExit("Stage 4 snapshot anchors are not unique; refusing to modify generated app")
else:
    a = html.index(start)
    b = html.index(end, a)
    original = html[a:b]
    required = ("function cajaSnapshot(){", "function mergeCajaSnapshot(remote){")
    if any(original.count(token) != 1 for token in required):
        raise SystemExit("Stage 4 snapshot functions are incomplete or ambiguous; refusing to modify generated app")
    module = (
        "var ArgentasSyncSnapshot=(function(){'use strict';\n"
        + original
        + "return Object.freeze({cajaSnapshot:cajaSnapshot,mergeCajaSnapshot:mergeCajaSnapshot});})();\n"
        + "function cajaSnapshot(){return ArgentasSyncSnapshot.cajaSnapshot()}\n"
        + "function mergeCajaSnapshot(remote){return ArgentasSyncSnapshot.mergeCajaSnapshot(remote)}\n"
    )
    html = html[:a] + module + html[b:]
    path.write_text(html, encoding="utf-8")
    print("Extracted caja snapshot read/merge into ArgentasSyncSnapshot; compatibility wrappers preserved")
