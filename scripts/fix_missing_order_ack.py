from pathlib import Path

path = Path("app/src/main/assets/index.html")
html = path.read_text(encoding="utf-8")

anchor = "var remoteOrder=db.orders.find(function(o){return o&&String(o.id)===String(m.id)});"
guard = "if(!remoteOrder){/* No confirmar: el emisor debe reintentar hasta que llegue la comanda. */return;}"
if guard in html:
    print("Stage 6 missing-order ACK guard already applied")
else:
    if html.count(anchor) != 1:
        raise SystemExit("Stage 6 status handler anchor is not unique; refusing to modify generated app")
    html = html.replace(anchor, anchor + "\n    " + guard, 1)
    path.write_text(html, encoding="utf-8")
    print("Stage 6: defer comanda-status ACK until the receiver has the order")
